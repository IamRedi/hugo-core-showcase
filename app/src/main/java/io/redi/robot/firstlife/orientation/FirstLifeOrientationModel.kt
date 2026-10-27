package io.redi.robot.firstlife.orientation

import io.redi.robot.firstlife.vision.PersonDirectionEvidence

@JvmInline
value class OrientationAttemptId(val value: String) {
    init { require(value.matches(Regex("^[a-z0-9][a-z0-9._-]{2,95}$"))) }
    override fun toString(): String = "orientation-attempt:redacted"
}

data class OrientationPrerequisites(
    val bodyReady: Boolean,
    val activeBodySelected: Boolean,
    val mountingAligned: Boolean,
    val connectionCurrent: Boolean,
    val negotiationCurrent: Boolean,
    val authorityGrantCurrent: Boolean,
    val cameraSessionActive: Boolean,
    val floorModeArmed: Boolean,
    val lowerContactKnown: Boolean,
    val lowerContactFresh: Boolean,
    val lowerContactSafe: Boolean,
    val emergencyStopClear: Boolean,
    val personEvidence: PersonDirectionEvidence?,
)

enum class OrientationTurnDirection { LEFT, RIGHT }

enum class OrientationPhase {
    IDLE,
    AWAITING_MOVE_RESULT,
    PULSING,
    AWAITING_STOP_RESULT,
    COOLDOWN,
    COMPLETED,
    BLOCKED,
}

enum class OrientationTerminalReason {
    CENTERED,
    USER_RELEASED,
    USER_CANCELLED,
    BACKGROUNDED,
    EMERGENCY_STOP,
    BODY_UNAVAILABLE,
    ACTIVE_BODY_LOST,
    MOUNTING_UNCONFIRMED,
    CONNECTION_LOST,
    NEGOTIATION_STALE,
    AUTHORITY_LOST,
    CAMERA_CLOSED,
    FLOOR_MODE_NOT_ARMED,
    LOWER_CONTACT_UNKNOWN,
    LOWER_CONTACT_STALE,
    LOWER_CONTACT_UNSAFE,
    PERSON_EVIDENCE_MISSING,
    PERSON_EVIDENCE_STALE,
    PERSON_EVIDENCE_AMBIGUOUS,
    DIRECTION_CHANGED,
    WALL_CLOCK_BUDGET_EXHAUSTED,
    PULSE_BUDGET_EXHAUSTED,
    AUTHORIZATION_DENIED,
    SAFETY_REJECTED,
    DISPATCH_REJECTED,
    STOP_NOT_CONFIRMED,
}

data class OrientationEffectId(val attemptId: OrientationAttemptId, val sequence: Long) {
    init { require(sequence > 0L) }
    override fun toString(): String = "orientation-effect:redacted/$sequence"
}

sealed interface OrientationEffect {
    val id: OrientationEffectId

    data class ProposeTurn(
        override val id: OrientationEffectId,
        val direction: OrientationTurnDirection,
        val signedMagnitude: Int = FirstLifeOrientationEnvelope.SIGNED_MAGNITUDE,
        val refreshOrdinal: Int,
    ) : OrientationEffect {
        init {
            require(signedMagnitude == FirstLifeOrientationEnvelope.SIGNED_MAGNITUDE)
            require(refreshOrdinal in 1..FirstLifeOrientationEnvelope.MAX_REFRESHES_PER_PULSE)
        }
    }

    data class RequestStop(
        override val id: OrientationEffectId,
        val reason: OrientationTerminalReason?,
    ) : OrientationEffect
}

enum class OrientationEffectOutcome {
    MOVE_ACCEPTED,
    STOP_CONFIRMED,
    AUTHORIZATION_DENIED,
    SAFETY_REJECTED,
    STALE_CORRELATION,
    UNAVAILABLE,
    FAILED,
    STOP_UNCONFIRMED,
}

sealed interface OrientationEvent {
    val nowElapsedMs: Long
    val nowElapsedNanos: Long

    data class BeginHold(
        val attemptId: OrientationAttemptId,
        override val nowElapsedMs: Long,
        override val nowElapsedNanos: Long,
        val prerequisites: OrientationPrerequisites,
    ) : OrientationEvent

    data class Tick(
        override val nowElapsedMs: Long,
        override val nowElapsedNanos: Long,
        val prerequisites: OrientationPrerequisites,
    ) : OrientationEvent

    data class PrerequisitesChanged(
        override val nowElapsedMs: Long,
        override val nowElapsedNanos: Long,
        val prerequisites: OrientationPrerequisites,
    ) : OrientationEvent

    data class EffectCompleted(
        val effectId: OrientationEffectId,
        val outcome: OrientationEffectOutcome,
        override val nowElapsedMs: Long,
        override val nowElapsedNanos: Long,
    ) : OrientationEvent

    data class UserReleased(
        override val nowElapsedMs: Long,
        override val nowElapsedNanos: Long,
    ) : OrientationEvent

    data class UserCancelled(
        override val nowElapsedMs: Long,
        override val nowElapsedNanos: Long,
    ) : OrientationEvent

    data class Backgrounded(
        override val nowElapsedMs: Long,
        override val nowElapsedNanos: Long,
    ) : OrientationEvent

    data class EmergencyStop(
        override val nowElapsedMs: Long,
        override val nowElapsedNanos: Long,
    ) : OrientationEvent
}

data class FirstLifeOrientationModel(
    val attemptId: OrientationAttemptId? = null,
    val phase: OrientationPhase = OrientationPhase.IDLE,
    val startedAtElapsedMs: Long? = null,
    val pulseStartedAtElapsedMs: Long? = null,
    val pulseCount: Int = 0,
    val currentPulseRefreshes: Int = 0,
    val cumulativeEnergizedMs: Long = 0L,
    val direction: OrientationTurnDirection? = null,
    val pendingEffect: OrientationEffectId? = null,
    val nextDeadlineElapsedMs: Long? = null,
    val stopTerminalReason: OrientationTerminalReason? = null,
    val terminalReason: OrientationTerminalReason? = null,
    val nextEffectSequence: Long = 1L,
) {
    val active: Boolean get() = phase !in setOf(OrientationPhase.IDLE, OrientationPhase.COMPLETED, OrientationPhase.BLOCKED)
    val mayBeMoving: Boolean get() = phase in setOf(
        OrientationPhase.AWAITING_MOVE_RESULT,
        OrientationPhase.PULSING,
        OrientationPhase.AWAITING_STOP_RESULT,
    )

    companion object { val IDLE = FirstLifeOrientationModel() }
}

data class OrientationTransition(
    val model: FirstLifeOrientationModel,
    val effects: List<OrientationEffect> = emptyList(),
    val staleEventRejected: Boolean = false,
)

object FirstLifeOrientationEnvelope {
    const val SIGNED_MAGNITUDE = 16
    const val REFRESH_INTERVAL_MS = 50L
    const val MAX_PULSE_MS = 100L
    const val MAX_REFRESHES_PER_PULSE = 2
    const val COOLDOWN_MS = 250L
    const val MAX_PULSES = 3
    const val MAX_CUMULATIVE_ENERGIZED_MS = 300L
    const val MAX_ATTEMPT_WALL_CLOCK_MS = 2_000L
}
