package io.redi.robot.body.looi.ble.queue

import java.util.ArrayDeque
import java.util.LinkedHashMap

enum class GattOperationKind {
    READ,
    WRITE,
    DESCRIPTOR_READ,
    DESCRIPTOR_WRITE,
}

enum class GattOperationLane {
    NORMAL,
    STOP,
}

enum class GattOperationQueueState {
    QUEUED,
    ACTIVE,
    SUCCESS,
    FAILURE,
    CANCELLED,
    TIMEOUT,
}

enum class GattQueueFailureReason {
    QUEUE_FULL,
    STOP_PREEMPTED,
    CANCELLED,
    TIMEOUT,
    TRANSPORT_ERROR,
    STALE_CALLBACK,
}

data class GattOperationFailure(
    val reason: GattQueueFailureReason,
    val detail: String,
)

sealed interface GattOperationResult

data class GattOperationSuccess(
    val payload: List<Int>? = null,
) : GattOperationResult

data class GattOperationFailureResult(
    val failure: GattOperationFailure,
) : GattOperationResult

data class GattOperationHandle(
    val id: Long,
    val generation: Long,
) {
    override fun toString(): String = "op-$id(gen=$generation)"
}

data class GattQueuedOperationRequest(
    val generation: Long,
    val kind: GattOperationKind,
    val serviceUuid: String,
    val characteristicUuid: String,
    val payload: List<Int> = emptyList(),
    val withResponse: Boolean = false,
    val lane: GattOperationLane = GattOperationLane.NORMAL,
    val timeoutMs: Long = 5000L,
)

sealed interface GattQueueSubmissionResult

data class GattOperationEnqueued(
    val handle: GattOperationHandle,
) : GattQueueSubmissionResult

data class GattOperationRejected(
    val reason: GattOperationFailure,
) : GattQueueSubmissionResult

interface GattOperationTransport {
    fun dispatch(operation: GattQueuedOperationRequest, onResult: (GattOperationResult) -> Unit)
}

data class GattOperationProgress(
    val handle: GattOperationHandle,
    val request: GattQueuedOperationRequest,
    val state: GattOperationQueueState,
    val failure: GattOperationFailure? = null,
)

/**
 * Serializes GATT operations and owns in-flight correlation.
 *
 * All callbacks are scoped to request handles and generation IDs.
 */
class SerializedGattOperationQueue(
    private val transport: GattOperationTransport,
    private val maxQueuedOperations: Int = 16,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(maxQueuedOperations > 0)
    }

    private data class QueuedNode(
        val handle: GattOperationHandle,
        val request: GattQueuedOperationRequest,
        val onResult: (GattOperationResult) -> Unit,
        var state: GattOperationQueueState,
        var failure: GattOperationFailure? = null,
        var startedAtMs: Long? = null,
    ) {
        fun toProgress() = GattOperationProgress(
            handle = handle,
            request = request,
            state = state,
            failure = failure,
        )
    }

    private val normalLane = ArrayDeque<GattOperationHandle>()
    private val stopLane = ArrayDeque<GattOperationHandle>()
    private val operations = LinkedHashMap<GattOperationHandle, QueuedNode>()
    private var nextOperationCounter = 1L
    private var activeHandle: GattOperationHandle? = null

    @Synchronized
    fun submit(
        request: GattQueuedOperationRequest,
        onResult: (GattOperationResult) -> Unit,
    ): GattQueueSubmissionResult {
        val handle = GattOperationHandle(id = nextOperationCounter++, generation = request.generation)
        if (request.lane == GattOperationLane.STOP) {
            preemptQueuedNormal("stop operation was queued and bypassed normal lane")
        } else if (normalLane.size + stopLane.size >= maxQueuedOperations) {
            return GattOperationRejected(
                GattOperationFailure(
                    reason = GattQueueFailureReason.QUEUE_FULL,
                    detail = "operation queue is full",
                ),
            )
        }

        val entry = QueuedNode(
            handle = handle,
            request = request,
            onResult = onResult,
            state = GattOperationQueueState.QUEUED,
        )
        operations[handle] = entry

        if (request.lane == GattOperationLane.STOP) {
            stopLane.addLast(handle)
        } else {
            normalLane.addLast(handle)
        }

        dispatchIfIdle()
        return GattOperationEnqueued(handle)
    }

    @Synchronized
    fun cancelGeneration(generation: Long) {
        val toCancel = operations.keys.filter { it.generation == generation }
        toCancel.forEach(::cancel)
    }

    @Synchronized
    fun cancel(handle: GattOperationHandle): Boolean {
        val entry = operations[handle] ?: return false
        if (entry.state == GattOperationQueueState.SUCCESS ||
            entry.state == GattOperationQueueState.FAILURE ||
            entry.state == GattOperationQueueState.CANCELLED ||
            entry.state == GattOperationQueueState.TIMEOUT
        ) {
            return false
        }

        transition(
            handle,
            GattOperationQueueState.CANCELLED,
            GattQueueFailureReason.CANCELLED,
            "operation cancelled",
        )
        return true
    }

    @Synchronized
    fun process(nowMs: Long = nowProvider()) {
        val active = activeHandle ?: return
        val entry = operations[active] ?: return
        val startedAt = entry.startedAtMs ?: return
        if (nowMs - startedAt < entry.request.timeoutMs) {
            return
        }
        transition(
            active,
            GattOperationQueueState.TIMEOUT,
            GattQueueFailureReason.TIMEOUT,
            "operation timed out",
        )
    }

    @Synchronized
    fun progress(handle: GattOperationHandle): GattOperationProgress? = operations[handle]?.toProgress()

    @Synchronized
    fun queuedSnapshot(): List<GattOperationProgress> {
        val snapshot = mutableListOf<GattOperationProgress>()
        activeHandle?.let {
            operations[it]?.let { entry ->
                snapshot.add(entry.toProgress())
            }
        }
        stopLane.mapNotNull { operations[it] }
            .map { it.toProgress() }
            .forEach { snapshot.add(it) }
        normalLane.mapNotNull { operations[it] }
            .map { it.toProgress() }
            .forEach { snapshot.add(it) }
        return snapshot
    }

    @Synchronized
    fun hasPendingOperations(): Boolean = activeHandle != null || normalLane.isNotEmpty() || stopLane.isNotEmpty()

    private fun dispatchIfIdle() {
        if (activeHandle != null) return
        val nextHandle = popNextHandle() ?: return
        start(nextHandle)
    }

    private fun popNextHandle(): GattOperationHandle? = if (stopLane.isNotEmpty()) {
        stopLane.removeFirst()
    } else {
        if (normalLane.isNotEmpty()) {
            normalLane.removeFirst()
        } else {
            null
        }
    }

    private fun start(handle: GattOperationHandle) {
        val entry = operations[handle] ?: return
        if (entry.state != GattOperationQueueState.QUEUED) return

        activeHandle = handle
        entry.state = GattOperationQueueState.ACTIVE
        entry.startedAtMs = nowProvider()

        val request = entry.request
        try {
            transport.dispatch(request) { result ->
                handleTransportResult(handle, result)
            }
        } catch (error: Throwable) {
            transition(
                handle,
                GattOperationQueueState.FAILURE,
                GattQueueFailureReason.TRANSPORT_ERROR,
                "transport dispatch failed: ${error.message}",
            )
        }
    }

    @Synchronized
    private fun handleTransportResult(handle: GattOperationHandle, result: GattOperationResult) {
        if (activeHandle != handle) return
        val entry = operations[handle] ?: return
        if (entry.state != GattOperationQueueState.ACTIVE) return

        when (result) {
            is GattOperationSuccess -> {
                transition(handle, GattOperationQueueState.SUCCESS, null, null)
            }

            is GattOperationFailureResult -> {
                transition(
                    handle,
                    GattOperationQueueState.FAILURE,
                    result.failure.reason,
                    result.failure.detail,
                )
            }
        }
    }

    private fun transition(
        handle: GattOperationHandle,
        state: GattOperationQueueState,
        failureReason: GattQueueFailureReason?,
        failureDetail: String?,
    ) {
        val entry = operations[handle] ?: return
        if (entry.state !in setOf(GattOperationQueueState.QUEUED, GattOperationQueueState.ACTIVE)) {
            return
        }

        entry.state = state
        if (failureReason != null && failureDetail != null) {
            entry.failure = GattOperationFailure(failureReason, failureDetail)
        }

        if (handle == activeHandle) {
            activeHandle = null
        }
        removeFromQueue(stopLane, handle)
        removeFromQueue(normalLane, handle)
        entry.onResult(
            when (state) {
                GattOperationQueueState.SUCCESS -> GattOperationSuccess()
                GattOperationQueueState.CANCELLED -> GattOperationFailureResult(
                    GattOperationFailure(
                        reason = failureReason ?: GattQueueFailureReason.CANCELLED,
                        detail = failureDetail ?: "cancelled",
                    ),
                )

                GattOperationQueueState.FAILURE -> GattOperationFailureResult(
                    GattOperationFailure(
                        reason = failureReason ?: GattQueueFailureReason.TRANSPORT_ERROR,
                        detail = failureDetail ?: "transport failure",
                    ),
                )

                GattOperationQueueState.TIMEOUT -> GattOperationFailureResult(
                    GattOperationFailure(
                        reason = failureReason ?: GattQueueFailureReason.TIMEOUT,
                        detail = failureDetail ?: "timeout",
                    ),
                )

                else -> GattOperationFailureResult(
                    GattOperationFailure(
                        reason = failureReason ?: GattQueueFailureReason.CANCELLED,
                        detail = failureDetail ?: "non-success",
                    ),
                )
            },
        )
        operations.remove(handle)
        dispatchIfIdle()
    }

    private fun preemptQueuedNormal(detail: String) {
        while (normalLane.isNotEmpty()) {
            val preempted = normalLane.removeFirst()
            transition(
                preempted,
                GattOperationQueueState.CANCELLED,
                GattQueueFailureReason.STOP_PREEMPTED,
                detail,
            )
        }
    }

    private fun removeFromQueue(queue: ArrayDeque<GattOperationHandle>, handle: GattOperationHandle): Boolean {
        val remaining = ArrayDeque<GattOperationHandle>(queue.size)
        var removed = false
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next == handle) {
                removed = true
                continue
            }
            remaining.addLast(next)
        }
        queue.addAll(remaining)
        return removed
    }
}
