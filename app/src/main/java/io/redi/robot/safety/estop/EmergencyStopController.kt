package io.redi.robot.safety.estop

import io.redi.robot.core.authority.MotionAuthorityEvent
import io.redi.robot.core.authority.MotionAuthorityStore
import io.redi.robot.core.authority.MotionAuthorityTransition

enum class EmergencyStopReason {
    LOCAL_USER,
    TRANSPORT_LOST,
    SERVICE_SHUTDOWN,
    SAFETY_POLICY,
}

enum class EmergencyStopResetInitiator {
    LOCAL_UI,
    REMOTE,
    AI_AGENT,
    SYSTEM,
}

data class EmergencyStopAudit(
    val reason: EmergencyStopReason,
    val activatedAtElapsedMs: Long,
    val activationCount: Long,
) {
    init {
        require(activatedAtElapsedMs >= 0) { "activation timestamp must not be negative" }
        require(activationCount > 0) { "activation count must be positive" }
    }
}

/** Immutable latch state that callers retain across UI recreation. */
data class EmergencyStopState(
    val latched: Boolean = false,
    val lastAudit: EmergencyStopAudit? = null,
)

sealed interface EmergencyStopEvent {
    val occurredAtElapsedMs: Long

    data class Activate(
        val reason: EmergencyStopReason,
        override val occurredAtElapsedMs: Long,
    ) : EmergencyStopEvent

    data class ResetRequested(
        val initiator: EmergencyStopResetInitiator,
        val reportedStationary: Boolean,
        override val occurredAtElapsedMs: Long,
    ) : EmergencyStopEvent
}

sealed interface EmergencyStopOutcome {
    data object Latched : EmergencyStopOutcome
    data object RepeatedStopRequested : EmergencyStopOutcome
    data object Reset : EmergencyStopOutcome
    data class ResetRejected(val reason: ResetRejectionReason) : EmergencyStopOutcome
}

enum class ResetRejectionReason {
    NOT_LATCHED,
    NOT_LOCAL_UI,
    NOT_STATIONARY,
}

data class EmergencyStopTransition(
    val state: EmergencyStopState,
    val outcome: EmergencyStopOutcome,
)

/** Pure state reducer; delivery/cancellation effects are applied by [EmergencyStopController]. */
object EmergencyStopReducer {
    fun reduce(state: EmergencyStopState, event: EmergencyStopEvent): EmergencyStopTransition {
        require(event.occurredAtElapsedMs >= 0) { "event timestamp must not be negative" }
        return when (event) {
            is EmergencyStopEvent.Activate -> {
                val activationCount = (state.lastAudit?.activationCount ?: 0L) + 1L
                val updated = state.copy(
                    latched = true,
                    lastAudit = EmergencyStopAudit(event.reason, event.occurredAtElapsedMs, activationCount),
                )
                EmergencyStopTransition(updated, if (state.latched) EmergencyStopOutcome.RepeatedStopRequested else EmergencyStopOutcome.Latched)
            }
            is EmergencyStopEvent.ResetRequested -> when {
                !state.latched -> EmergencyStopTransition(state, EmergencyStopOutcome.ResetRejected(ResetRejectionReason.NOT_LATCHED))
                event.initiator != EmergencyStopResetInitiator.LOCAL_UI -> EmergencyStopTransition(
                    state,
                    EmergencyStopOutcome.ResetRejected(ResetRejectionReason.NOT_LOCAL_UI),
                )
                !event.reportedStationary -> EmergencyStopTransition(
                    state,
                    EmergencyStopOutcome.ResetRejected(ResetRejectionReason.NOT_STATIONARY),
                )
                else -> EmergencyStopTransition(state.copy(latched = false), EmergencyStopOutcome.Reset)
            }
        }
    }
}

/** Port implemented later by the serialized GATT queue adapter; safety imports no transport type. */
fun interface EmergencyStopQueueCancellation {
    fun cancelPendingActions()
}

/** Port for stop delivery. T023 intentionally does not choose a protocol payload or transport. */
fun interface EmergencyStopDelivery {
    fun requestStop()
}

data class EmergencyStopPolicy(val repeatedStopRequestCount: Int) {
    init {
        require(repeatedStopRequestCount > 0) { "repeated stop request count must be positive" }
    }
}

data class EmergencyStopControllerTransition(
    val emergencyStop: EmergencyStopTransition,
    val authority: MotionAuthorityTransition? = null,
)

/**
 * Synchronous local e-stop coordinator. Activation revokes authority before queue cancellation
 * and stop delivery. It never accepts a remote or AI reset.
 */
class EmergencyStopController(
    private val authority: MotionAuthorityStore,
    private val queueCancellation: EmergencyStopQueueCancellation,
    private val stopDelivery: EmergencyStopDelivery,
    private val policy: EmergencyStopPolicy,
    initialState: EmergencyStopState = EmergencyStopState(),
) {
    var state: EmergencyStopState = initialState
        private set

    fun dispatch(event: EmergencyStopEvent): EmergencyStopControllerTransition {
        val emergencyStop = EmergencyStopReducer.reduce(state, event)
        state = emergencyStop.state
        val authorityTransition = if (event is EmergencyStopEvent.Activate) {
            authority.dispatch(MotionAuthorityEvent.RevokeAll(event.occurredAtElapsedMs)).also {
                queueCancellation.cancelPendingActions()
                repeat(policy.repeatedStopRequestCount) { stopDelivery.requestStop() }
            }
        } else {
            null
        }
        return EmergencyStopControllerTransition(emergencyStop, authorityTransition)
    }
}
