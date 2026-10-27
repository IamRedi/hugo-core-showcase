package io.redi.robot.body.looi.ble.notification

import io.redi.robot.body.looi.ble.queue.GattOperationFailure
import io.redi.robot.body.looi.ble.queue.GattOperationFailureResult
import io.redi.robot.body.looi.ble.queue.GattOperationHandle
import io.redi.robot.body.looi.ble.queue.GattOperationKind.DESCRIPTOR_WRITE
import io.redi.robot.body.looi.ble.queue.GattOperationRejected
import io.redi.robot.body.looi.ble.queue.GattOperationSuccess
import io.redi.robot.body.looi.ble.queue.GattOperationTransport
import io.redi.robot.body.looi.ble.queue.GattQueuedOperationRequest
import io.redi.robot.body.looi.ble.queue.SerializedGattOperationQueue

data class GattNotificationKey(
    val channel: String,
    val serviceUuid: String,
    val characteristicUuid: String,
)

data class GattNotificationSubscriptionHandle(
    val generation: Long,
    val id: Long,
    val key: GattNotificationKey,
)

data class GattNotificationPayload(
    val generation: Long,
    val key: GattNotificationKey,
    val timestampMs: Long,
    val bytes: ByteArray,
)

enum class GattNotificationSubscriptionState {
    PENDING,
    ACTIVE,
    FAILED,
    CANCELLED,
}

sealed interface GattNotificationSubscriptionResult {
    val handle: GattNotificationSubscriptionHandle

    data class Accepted(override val handle: GattNotificationSubscriptionHandle) : GattNotificationSubscriptionResult
    data class Rejected(
        override val handle: GattNotificationSubscriptionHandle,
        val reason: String,
    ) : GattNotificationSubscriptionResult
}

sealed interface GattNotificationEvent {
    val generation: Long
    val handle: GattNotificationSubscriptionHandle?

    data class Subscribed(
        override val generation: Long,
        override val handle: GattNotificationSubscriptionHandle,
        val key: GattNotificationKey,
    ) : GattNotificationEvent

    data class Unsubscribed(
        override val generation: Long,
        override val handle: GattNotificationSubscriptionHandle,
        val reason: String,
    ) : GattNotificationEvent

    data class SubscribeFailed(
        override val generation: Long,
        override val handle: GattNotificationSubscriptionHandle,
        val reason: String,
        val failure: GattOperationFailure? = null,
    ) : GattNotificationEvent

    data class NotificationReceived(
        override val generation: Long,
        override val handle: GattNotificationSubscriptionHandle,
        val payload: GattNotificationPayload,
    ) : GattNotificationEvent

    data class MalformedNotification(
        override val generation: Long,
        override val handle: GattNotificationSubscriptionHandle?,
        val reason: String,
        val rawBytes: ByteArray,
    ) : GattNotificationEvent
}

class GattNotificationRouter(
    private val transport: GattOperationTransport,
    private val onEvent: (GattNotificationEvent) -> Unit,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val maxQueuedOperations: Int = 16,
) {
    private data class TrackedSubscription(
        var state: GattNotificationSubscriptionState,
        val handle: GattNotificationSubscriptionHandle,
        var operationHandle: GattOperationHandle? = null,
    )

    private val subscriptions = HashMap<GattNotificationSubscriptionHandle, TrackedSubscription>()
    private val stableKeyToHandle = HashMap<String, GattNotificationSubscriptionHandle>()
    private val generationToHandles = HashMap<Long, MutableList<GattNotificationSubscriptionHandle>>()
    private var nextHandleId = 1L
    private val queue = SerializedGattOperationQueue(
        transport = transport,
        maxQueuedOperations = maxQueuedOperations,
    )

    fun requestSubscribe(
        generation: Long,
        key: GattNotificationKey,
    ): GattNotificationSubscriptionResult {
        val stableKey = stableKey(generation, key)
        val existingHandle = stableKeyToHandle[stableKey]
        val existing = existingHandle?.let(subscriptions::get)
        if (existing != null && (existing.state == GattNotificationSubscriptionState.PENDING ||
            existing.state == GattNotificationSubscriptionState.ACTIVE)
        ) {
            return GattNotificationSubscriptionResult.Accepted(existing.handle)
        }
        existingHandle?.let(::removeSubscription)

        val handle = GattNotificationSubscriptionHandle(
            generation = generation,
            id = nextHandleId++,
            key = key,
        )
        val tracked = TrackedSubscription(
            state = GattNotificationSubscriptionState.PENDING,
            handle = handle,
        )
        subscriptions[handle] = tracked
        stableKeyToHandle[stableKey] = handle
        generationToHandles.getOrPut(generation) { mutableListOf() }.add(handle)

        val enqueueResult = queue.submit(
            requestDescriptorWrite(handle.generation, handle.key, true),
        ) { result ->
            val current = subscriptions[handle] ?: return@submit
            if (current.state != GattNotificationSubscriptionState.PENDING) return@submit
            when (result) {
                is GattOperationSuccess -> {
                    current.state = GattNotificationSubscriptionState.ACTIVE
                    onEvent(
                        GattNotificationEvent.Subscribed(
                            generation = generation,
                            handle = handle,
                            key = key,
                        ),
                    )
                }

                is GattOperationFailureResult -> {
                    current.state = GattNotificationSubscriptionState.FAILED
                    onEvent(
                        GattNotificationEvent.SubscribeFailed(
                            generation = generation,
                            handle = handle,
                            reason = "CCCD write failed",
                            failure = result.failure,
                        ),
                    )
                }
            }
        }

        return when (enqueueResult) {
            is io.redi.robot.body.looi.ble.queue.GattOperationEnqueued -> {
                tracked.operationHandle = enqueueResult.handle
                GattNotificationSubscriptionResult.Accepted(handle)
            }

            is GattOperationRejected -> {
                tracked.state = GattNotificationSubscriptionState.FAILED
                onEvent(
                    GattNotificationEvent.SubscribeFailed(
                        generation = generation,
                        handle = handle,
                        reason = enqueueResult.reason.detail,
                        failure = enqueueResult.reason,
                    ),
                )
                GattNotificationSubscriptionResult.Rejected(
                    handle = handle,
                    reason = enqueueResult.reason.detail,
                )
            }
        }
    }

    fun routeNotification(
        generation: Long,
        channel: String,
        serviceUuid: String,
        characteristicUuid: String,
        bytes: ByteArray,
    ) {
        val active = subscriptions.entries.find { entry ->
            val tracked = entry.value
            val key = tracked.handle.key
            tracked.state == GattNotificationSubscriptionState.ACTIVE &&
                tracked.handle.generation == generation &&
                key.channel == channel &&
                key.serviceUuid == serviceUuid &&
                key.characteristicUuid == characteristicUuid
        } ?: return

        val track = active.value
        val copiedBytes = bytes.copyOf()
        if (copiedBytes.isEmpty()) {
            onEvent(
                GattNotificationEvent.MalformedNotification(
                    generation = generation,
                    handle = track.handle,
                    reason = "notification payload was empty",
                    rawBytes = copiedBytes,
                ),
            )
            return
        }

        onEvent(
            GattNotificationEvent.NotificationReceived(
                generation = generation,
                handle = track.handle,
                payload = GattNotificationPayload(
                    generation = generation,
                    key = track.handle.key,
                    timestampMs = nowProvider(),
                    bytes = copiedBytes,
                ),
            ),
        )
    }

    fun requestUnsubscribe(handle: GattNotificationSubscriptionHandle): Boolean {
        val tracked = subscriptions[handle] ?: return false
        if (tracked.state == GattNotificationSubscriptionState.CANCELLED) {
            return false
        }

        tracked.state = GattNotificationSubscriptionState.CANCELLED
        removeSubscription(handle)
        onEvent(
            GattNotificationEvent.Unsubscribed(
                generation = handle.generation,
                handle = handle,
                reason = "manual unsubscribe",
            ),
        )
        return true
    }

    fun onGenerationDisconnect(generation: Long) {
        val handles = generationToHandles.remove(generation)?.toList() ?: return
        handles.forEach { handle ->
            if (subscriptions[handle]?.state == GattNotificationSubscriptionState.CANCELLED) return@forEach
            removeSubscription(handle)
            onEvent(
                GattNotificationEvent.Unsubscribed(
                    generation = generation,
                    handle = handle,
                    reason = "generation disconnected",
                ),
            )
        }
    }

    private fun removeSubscription(handle: GattNotificationSubscriptionHandle) {
        val tracked = subscriptions.remove(handle) ?: return
        tracked.operationHandle?.let(queue::cancel)
        stableKeyToHandle.entries.removeIf { it.value == handle }
        generationToHandles[handle.generation]?.remove(handle)
        if (generationToHandles[handle.generation].isNullOrEmpty()) {
            generationToHandles.remove(handle.generation)
        }
    }

    private fun requestDescriptorWrite(
        generation: Long,
        key: GattNotificationKey,
        enabled: Boolean,
    ) = GattQueuedOperationRequest(
        generation = generation,
        kind = DESCRIPTOR_WRITE,
        serviceUuid = key.serviceUuid,
        characteristicUuid = key.characteristicUuid,
        payload = if (enabled) listOf(0x01, 0x00) else listOf(0x00, 0x00),
    )

    private fun stableKey(
        generation: Long,
        key: GattNotificationKey,
    ) = "${generation}|${key.channel}|${key.serviceUuid}|${key.characteristicUuid}"
}
