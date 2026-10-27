package io.redi.robot.safety.deadman

import io.redi.robot.core.ActionRequest
import io.redi.robot.core.RobotAction
import io.redi.robot.safety.estop.EmergencyStopController
import io.redi.robot.safety.estop.EmergencyStopEvent
import io.redi.robot.safety.estop.EmergencyStopReason

/** Monotonic elapsed time only; wall-clock time cannot influence a motion deadline. */
fun interface DeadManMonotonicClock {
    fun nowElapsedMs(): Long
}

fun interface DeadManScheduledTask {
    fun cancel()
}

/** Platform scheduling is a port; the deadline policy is fully testable in this package. */
fun interface DeadManScheduler {
    fun scheduleAt(deadlineElapsedMs: Long, action: () -> Unit): DeadManScheduledTask
}

data class DeadManPolicy(val commandLeaseTimeoutMs: Long) {
    init {
        require(commandLeaseTimeoutMs > 0) { "command lease timeout must be positive" }
    }
}

/** A high-watermark sequence window is scoped to exactly one connection epoch. */
data class CommandSequenceWindow(
    val connectionEpoch: Long,
    val highestAcceptedSequence: Long? = null,
) {
    init {
        require(connectionEpoch >= 0) { "connection epoch must not be negative" }
        require(highestAcceptedSequence == null || highestAcceptedSequence >= 0) {
            "highest accepted sequence must not be negative"
        }
    }

    fun accepts(sequence: Long): Boolean = sequence >= 0 && (highestAcceptedSequence == null || sequence > highestAcceptedSequence)

    fun record(sequence: Long): CommandSequenceWindow = copy(highestAcceptedSequence = sequence)
}

data class MotionRefreshRequest(
    val actionRequest: ActionRequest,
    val connectionEpoch: Long,
)

data class DeadManState(
    val sequenceWindow: CommandSequenceWindow? = null,
    val activeDeadlineElapsedMs: Long? = null,
)

sealed interface DeadManOutcome {
    data object EpochStarted : DeadManOutcome
    data object EpochIgnored : DeadManOutcome
    data class Accepted(val deadlineElapsedMs: Long) : DeadManOutcome
    data object Expired : DeadManOutcome
    data object WatchdogIgnored : DeadManOutcome
    data class Rejected(val reason: DeadManRejectionReason) : DeadManOutcome
}

enum class DeadManRejectionReason {
    NO_ACTIVE_EPOCH,
    STALE_EPOCH,
    NOT_MOTION,
    INVALID_SEQUENCE,
    STALE_COMMAND,
    FUTURE_TIMESTAMP,
    EMERGENCY_STOP_LATCHED,
}

data class DeadManTransition(
    val state: DeadManState,
    val outcome: DeadManOutcome,
)

/** Pure reducer. Only the controller below schedules the independent watchdog and delivers stop. */
class DeadManReducer(private val policy: DeadManPolicy) {
    fun beginEpoch(state: DeadManState, epoch: Long): DeadManTransition {
        require(epoch >= 0) { "connection epoch must not be negative" }
        val current = state.sequenceWindow
        if (current != null && epoch <= current.connectionEpoch) return DeadManTransition(state, DeadManOutcome.EpochIgnored)
        return DeadManTransition(DeadManState(CommandSequenceWindow(epoch)), DeadManOutcome.EpochStarted)
    }

    fun acceptRefresh(
        state: DeadManState,
        request: MotionRefreshRequest,
        observedAtElapsedMs: Long,
        emergencyStopLatched: Boolean,
    ): DeadManTransition {
        require(observedAtElapsedMs >= 0) { "observed timestamp must not be negative" }
        if (emergencyStopLatched) return DeadManTransition(state, DeadManOutcome.Rejected(DeadManRejectionReason.EMERGENCY_STOP_LATCHED))
        val window = state.sequenceWindow ?: return DeadManTransition(state, DeadManOutcome.Rejected(DeadManRejectionReason.NO_ACTIVE_EPOCH))
        if (request.connectionEpoch != window.connectionEpoch) return DeadManTransition(state, DeadManOutcome.Rejected(DeadManRejectionReason.STALE_EPOCH))
        if (request.actionRequest.action !is RobotAction.Drive) return DeadManTransition(state, DeadManOutcome.Rejected(DeadManRejectionReason.NOT_MOTION))
        if (!window.accepts(request.actionRequest.sequence)) return DeadManTransition(state, DeadManOutcome.Rejected(DeadManRejectionReason.INVALID_SEQUENCE))

        val issuedAt = request.actionRequest.action.issuedAtElapsedMs
        if (issuedAt > observedAtElapsedMs) return DeadManTransition(state, DeadManOutcome.Rejected(DeadManRejectionReason.FUTURE_TIMESTAMP))
        val deadline = issuedAt + policy.commandLeaseTimeoutMs
        if (observedAtElapsedMs >= deadline) return DeadManTransition(state, DeadManOutcome.Rejected(DeadManRejectionReason.STALE_COMMAND))

        return DeadManTransition(
            state.copy(sequenceWindow = window.record(request.actionRequest.sequence), activeDeadlineElapsedMs = deadline),
            DeadManOutcome.Accepted(deadline),
        )
    }

    fun watchdogDeadlineReached(state: DeadManState, epoch: Long, deadlineElapsedMs: Long, nowElapsedMs: Long): DeadManTransition {
        require(nowElapsedMs >= 0) { "watchdog timestamp must not be negative" }
        if (state.sequenceWindow?.connectionEpoch != epoch || state.activeDeadlineElapsedMs != deadlineElapsedMs || nowElapsedMs < deadlineElapsedMs) {
            return DeadManTransition(state, DeadManOutcome.WatchdogIgnored)
        }
        return DeadManTransition(state.copy(activeDeadlineElapsedMs = null), DeadManOutcome.Expired)
    }
}

/**
 * Safety-owned dead-man controller. Its deadline task is created independently of action
 * producers; an accepted refresh only updates the deadline that the watchdog observes.
 */
class DeadManController(
    policy: DeadManPolicy,
    private val clock: DeadManMonotonicClock,
    private val scheduler: DeadManScheduler,
    private val emergencyStop: EmergencyStopController,
    initialState: DeadManState = DeadManState(),
) {
    private val reducer = DeadManReducer(policy)
    private var watchdogTask: DeadManScheduledTask? = null

    var state: DeadManState = initialState
        private set

    fun beginConnectionEpoch(epoch: Long): DeadManTransition {
        val transition = reducer.beginEpoch(state, epoch)
        if (transition.outcome == DeadManOutcome.EpochStarted) watchdogTask?.cancel()
        state = transition.state
        return transition
    }

    fun refreshMotion(request: MotionRefreshRequest): DeadManTransition {
        val transition = reducer.acceptRefresh(state, request, clock.nowElapsedMs(), emergencyStop.state.latched)
        state = transition.state
        (transition.outcome as? DeadManOutcome.Accepted)?.let { accepted ->
            watchdogTask?.cancel()
            scheduleWatchdog(request.connectionEpoch, accepted.deadlineElapsedMs)
        }
        return transition
    }

    /** Called only by the independently scheduled watchdog callback, never by an action producer. */
    fun onWatchdogDeadline(epoch: Long, deadlineElapsedMs: Long): DeadManTransition {
        val transition = reducer.watchdogDeadlineReached(state, epoch, deadlineElapsedMs, clock.nowElapsedMs())
        state = transition.state
        if (transition.outcome == DeadManOutcome.Expired) {
            watchdogTask = null
            emergencyStop.dispatch(EmergencyStopEvent.Activate(EmergencyStopReason.SAFETY_POLICY, clock.nowElapsedMs()))
        }
        return transition
    }

    fun cancel(): DeadManState {
        watchdogTask?.cancel()
        watchdogTask = null
        state = state.copy(activeDeadlineElapsedMs = null)
        return state
    }

    private fun scheduleWatchdog(epoch: Long, deadlineElapsedMs: Long) {
        watchdogTask = scheduler.scheduleAt(deadlineElapsedMs) {
            onWatchdogDeadline(epoch, deadlineElapsedMs)
        }
    }
}
