package io.redi.robot.body.looi.connection.reconnect

import io.redi.robot.body.looi.connection.ConnectionTarget
import io.redi.robot.body.looi.transport.timing.MonotonicClock
import io.redi.robot.body.looi.transport.timing.TimingScheduler
import io.redi.robot.body.looi.transport.timing.TimingTask

enum class ReconnectBlockReason {
    BLUETOOTH_DISABLED,
    PERMISSION_UNAVAILABLE,
}

data class ReconnectReadiness(
    val bluetoothEnabled: Boolean,
    val permissionsGranted: Boolean,
) {
    val blockReason: ReconnectBlockReason? get() = when {
        !permissionsGranted -> ReconnectBlockReason.PERMISSION_UNAVAILABLE
        !bluetoothEnabled -> ReconnectBlockReason.BLUETOOTH_DISABLED
        else -> null
    }
}

data class ReconnectBackoffPolicy(
    val initialDelayMs: Long = 500L,
    val maximumDelayMs: Long = 30_000L,
    val maximumJitterPercent: Int = 20,
) {
    init {
        require(initialDelayMs > 0) { "initial delay must be positive" }
        require(maximumDelayMs >= initialDelayMs) { "maximum delay must be at least initial delay" }
        require(maximumJitterPercent in 0..100) { "jitter percent must be in 0..100" }
    }

    fun baseDelayMs(attempt: Int): Long {
        require(attempt > 0) { "attempt must be positive" }
        var delay = initialDelayMs
        repeat(attempt - 1) { delay = (delay * 2).coerceAtMost(maximumDelayMs) }
        return delay
    }
}

/** Makes jitter repeatable in tests and prevents hidden wall-clock randomness. */
fun interface ReconnectJitter {
    fun offsetMs(attempt: Int, baseDelayMs: Long, maximumAbsoluteOffsetMs: Long): Long
}

/** A reconnect path must revoke authority before waiting to retry; it cannot replay old motion. */
fun interface ReconnectSafetyPort {
    fun revokeAuthorityAndRequestStop(disconnectedGeneration: Long)
}

enum class ReconnectPhase {
    IDLE,
    BACKING_OFF,
    ATTEMPT_DUE,
    CONNECTING,
    BLOCKED,
    CANCELLED,
}

data class ReconnectState(
    val phase: ReconnectPhase = ReconnectPhase.IDLE,
    val generation: Long? = null,
    val target: ConnectionTarget? = null,
    val attempt: Int = 0,
    val blockReason: ReconnectBlockReason? = null,
) {
    val hasActiveAttempt: Boolean get() = phase in setOf(ReconnectPhase.BACKING_OFF, ReconnectPhase.ATTEMPT_DUE, ReconnectPhase.CONNECTING)
}

sealed interface ReconnectEvent {
    data class RetryScheduled(
        val generation: Long,
        val attempt: Int,
        val deadlineMs: Long,
    ) : ReconnectEvent

    data class ReconnectAttemptDue(
        val generation: Long,
        val target: ConnectionTarget,
        val attempt: Int,
    ) : ReconnectEvent

    data class RetryBlocked(
        val generation: Long,
        val reason: ReconnectBlockReason,
    ) : ReconnectEvent
}

/**
 * Coordinates retry timing only. The caller owns actual GATT initiation and must report lifecycle events.
 * Neither target nor this state carries a previously approved action, so reconnect cannot replay motion.
 */
class ReconnectCoordinator(
    private val clock: MonotonicClock,
    private val scheduler: TimingScheduler,
    private val safetyPort: ReconnectSafetyPort,
    private val policy: ReconnectBackoffPolicy = ReconnectBackoffPolicy(),
    private val jitter: ReconnectJitter = ReconnectJitter { _, _, _ -> 0L },
    private val onEvent: (ReconnectEvent) -> Unit,
) {
    private var stateInternal = ReconnectState()
    private var retryTask: TimingTask? = null

    val state: ReconnectState get() = stateInternal

    /** Start an automatic retry only after a transport loss for the supplied connection generation. */
    fun onTransportLost(generation: Long, target: ConnectionTarget, readiness: ReconnectReadiness): ReconnectState {
        if (generation < 0 || stateInternal.hasActiveAttempt && generation <= (stateInternal.generation ?: -1L)) return state
        cancelRetryTask()
        safetyPort.revokeAuthorityAndRequestStop(generation)
        return scheduleOrBlock(generation + 1, target, attempt = 1, readiness)
    }

    /** The GATT adapter/caller confirms that it has consumed the emitted due event. */
    fun onConnectionStarted(generation: Long): ReconnectState {
        if (stateInternal.phase != ReconnectPhase.ATTEMPT_DUE || stateInternal.generation != generation) return state
        stateInternal = stateInternal.copy(phase = ReconnectPhase.CONNECTING)
        return state
    }

    /** A current attempt failed/disconnected before readiness, so stop/revoke again before bounded backoff. */
    fun onConnectionFailed(generation: Long, readiness: ReconnectReadiness): ReconnectState {
        if (stateInternal.generation != generation || stateInternal.phase !in setOf(ReconnectPhase.ATTEMPT_DUE, ReconnectPhase.CONNECTING)) return state
        val target = stateInternal.target ?: return state
        safetyPort.revokeAuthorityAndRequestStop(generation)
        return scheduleOrBlock(generation + 1, target, stateInternal.attempt + 1, readiness)
    }

    /** Ready ends retry ownership. No motion, handshake state, or lease is restored here. */
    fun onConnectionReady(generation: Long): ReconnectState {
        if (stateInternal.generation != generation) return state
        cancelRetryTask()
        stateInternal = ReconnectState()
        return state
    }

    fun updateReadiness(readiness: ReconnectReadiness): ReconnectState {
        val target = stateInternal.target ?: return state
        val generation = stateInternal.generation ?: return state
        return when (stateInternal.phase) {
            ReconnectPhase.BACKING_OFF -> {
                cancelRetryTask()
                scheduleOrBlock(generation, target, stateInternal.attempt, readiness)
            }
            ReconnectPhase.BLOCKED -> if (readiness.blockReason == null) {
                scheduleOrBlock(generation, target, stateInternal.attempt, readiness)
            } else {
                block(generation, target, stateInternal.attempt, requireNotNull(readiness.blockReason))
            }
            else -> state
        }
    }

    fun cancel(): ReconnectState {
        cancelRetryTask()
        stateInternal = ReconnectState(phase = ReconnectPhase.CANCELLED)
        return state
    }

    private fun scheduleOrBlock(
        generation: Long,
        target: ConnectionTarget,
        attempt: Int,
        readiness: ReconnectReadiness,
    ): ReconnectState {
        val reason = readiness.blockReason
        if (reason != null) return block(generation, target, attempt, reason)

        val baseDelay = policy.baseDelayMs(attempt)
        val maxOffset = baseDelay * policy.maximumJitterPercent / 100
        val offset = jitter.offsetMs(attempt, baseDelay, maxOffset)
        require(offset in -maxOffset..maxOffset) { "jitter offset exceeds configured bounds" }
        val delay = (baseDelay + offset).coerceIn(0L, policy.maximumDelayMs)
        val deadline = clock.nowMs() + delay
        stateInternal = ReconnectState(ReconnectPhase.BACKING_OFF, generation, target, attempt)
        retryTask = scheduler.scheduleAt(deadline) {
            if (stateInternal.phase != ReconnectPhase.BACKING_OFF || stateInternal.generation != generation) return@scheduleAt
            stateInternal = stateInternal.copy(phase = ReconnectPhase.ATTEMPT_DUE)
            onEvent(ReconnectEvent.ReconnectAttemptDue(generation, target, attempt))
        }
        onEvent(ReconnectEvent.RetryScheduled(generation, attempt, deadline))
        return state
    }

    private fun block(
        generation: Long,
        target: ConnectionTarget,
        attempt: Int,
        reason: ReconnectBlockReason,
    ): ReconnectState {
        cancelRetryTask()
        stateInternal = ReconnectState(ReconnectPhase.BLOCKED, generation, target, attempt, reason)
        onEvent(ReconnectEvent.RetryBlocked(generation, reason))
        return state
    }

    private fun cancelRetryTask() {
        retryTask?.cancel()
        retryTask = null
    }
}
