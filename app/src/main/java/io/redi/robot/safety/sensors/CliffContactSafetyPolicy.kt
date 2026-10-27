package io.redi.robot.safety.sensors

import io.redi.robot.core.RobotAction

enum class CliffContactCorner {
    FRONT_LEFT,
    FRONT_RIGHT,
    REAR_LEFT,
    REAR_RIGHT,
}

enum class CliffContactObservationStatus {
    UNKNOWN,
    VALID,
    MALFORMED,
}

data class CliffContactReading(
    val triggered: Boolean,
    val triggeredSinceElapsedMs: Long? = null,
) {
    init {
        require(triggered == (triggeredSinceElapsedMs != null)) {
            "only a triggered reading carries a triggered-since timestamp"
        }
        require(triggeredSinceElapsedMs == null || triggeredSinceElapsedMs >= 0) {
            "triggered-since timestamp must not be negative"
        }
    }
}

/** Project-owned safety state. It contains no BLE UUID, packet, or Android type. */
data class CliffContactSensorState(
    val generation: Long?,
    val status: CliffContactObservationStatus,
    val observedAtElapsedMs: Long?,
    val readings: Map<CliffContactCorner, CliffContactReading> = emptyMap(),
) {
    init {
        require(generation == null || generation >= 0) { "generation must not be negative" }
        require(observedAtElapsedMs == null || observedAtElapsedMs >= 0) { "observation timestamp must not be negative" }
        when (status) {
            CliffContactObservationStatus.UNKNOWN -> require(observedAtElapsedMs == null && readings.isEmpty())
            CliffContactObservationStatus.MALFORMED -> require(generation != null && observedAtElapsedMs != null && readings.isEmpty())
            CliffContactObservationStatus.VALID -> require(
                generation != null && observedAtElapsedMs != null && readings.keys == CliffContactCorner.entries.toSet(),
            ) { "valid state requires all four sensor readings" }
        }
    }

    companion object {
        fun unknown(generation: Long? = null) = CliffContactSensorState(
            generation,
            CliffContactObservationStatus.UNKNOWN,
            observedAtElapsedMs = null,
        )
    }
}

object CliffContactStateReducer {
    fun observe(
        previous: CliffContactSensorState,
        generation: Long,
        observedAtElapsedMs: Long,
        triggered: Set<CliffContactCorner>,
    ): CliffContactSensorState {
        require(generation >= 0) { "generation must not be negative" }
        require(observedAtElapsedMs >= 0) { "observation timestamp must not be negative" }
        val readings = CliffContactCorner.entries.associateWith { corner ->
            if (corner !in triggered) {
                CliffContactReading(triggered = false)
            } else {
                val previousSince = previous
                    .takeIf { it.generation == generation && it.status == CliffContactObservationStatus.VALID }
                    ?.readings
                    ?.get(corner)
                    ?.takeIf { it.triggered }
                    ?.triggeredSinceElapsedMs
                CliffContactReading(triggered = true, triggeredSinceElapsedMs = previousSince ?: observedAtElapsedMs)
            }
        }
        return CliffContactSensorState(
            generation,
            CliffContactObservationStatus.VALID,
            observedAtElapsedMs,
            readings,
        )
    }

    fun malformed(generation: Long, observedAtElapsedMs: Long) = CliffContactSensorState(
        generation,
        CliffContactObservationStatus.MALFORMED,
        observedAtElapsedMs,
    )

    /**
     * Refreshes a previously valid event-based contact observation only while another parsed
     * packet from the same subscribed telemetry stream proves that stream is still live.
     */
    fun refreshFromLiveStream(
        previous: CliffContactSensorState,
        generation: Long,
        observedAtElapsedMs: Long,
    ): CliffContactSensorState {
        require(generation >= 0) { "generation must not be negative" }
        require(observedAtElapsedMs >= 0) { "observation timestamp must not be negative" }
        if (previous.generation != generation || previous.status != CliffContactObservationStatus.VALID) return previous
        return previous.copy(observedAtElapsedMs = observedAtElapsedMs)
    }
}

data class CliffContactDirectionalMapping(
    val leftWheelForward: CliffContactCorner = CliffContactCorner.FRONT_LEFT,
    val leftWheelReverse: CliffContactCorner = CliffContactCorner.REAR_LEFT,
    val rightWheelForward: CliffContactCorner = CliffContactCorner.FRONT_RIGHT,
    val rightWheelReverse: CliffContactCorner = CliffContactCorner.REAR_RIGHT,
) {
    init {
        require(setOf(leftWheelForward, leftWheelReverse, rightWheelForward, rightWheelReverse).size == 4) {
            "directional mapping must assign each physical corner exactly once"
        }
    }
}

data class CliffContactSafetyConfiguration(
    val maximumAgeMs: Long = 250,
    val stuckTriggeredAfterMs: Long = 5_000,
    val movementEpsilon: Float = 0.0001f,
    val mapping: CliffContactDirectionalMapping = CliffContactDirectionalMapping(),
) {
    init {
        require(maximumAgeMs >= 0) { "maximum age must not be negative" }
        require(stuckTriggeredAfterMs > 0) { "stuck threshold must be positive" }
        require(movementEpsilon.isFinite() && movementEpsilon >= 0f) { "movement epsilon must be finite and non-negative" }
    }
}

sealed interface CliffContactMotionDecision {
    data object Clear : CliffContactMotionDecision

    data class Blocked(
        val reason: Reason,
        val corners: Set<CliffContactCorner> = emptySet(),
    ) : CliffContactMotionDecision

    enum class Reason {
        UNKNOWN,
        MALFORMED,
        STALE,
        GENERATION_MISMATCH,
        HAZARD,
        STUCK_TRIGGERED,
    }
}

/**
 * Pure fail-closed motion policy for the physically verified FL/FR/RL/RR active-low mapping.
 * A triggered corner blocks only wheel motion toward that corner. Motion directly away from the
 * hazard remains available as a deliberate escape; unknown, malformed, stale, or wrong-generation
 * state permits only stationary drive/Stop.
 */
class CliffContactSafetyPolicy(
    private val configuration: CliffContactSafetyConfiguration = CliffContactSafetyConfiguration(),
) {
    fun evaluate(
        action: RobotAction.Drive,
        state: CliffContactSensorState,
        expectedGeneration: Long,
        nowElapsedMs: Long,
    ): CliffContactMotionDecision {
        val leftWheel = action.linear + action.angular
        val rightWheel = action.linear - action.angular
        if (kotlin.math.abs(leftWheel) <= configuration.movementEpsilon &&
            kotlin.math.abs(rightWheel) <= configuration.movementEpsilon
        ) return CliffContactMotionDecision.Clear

        if (state.generation != expectedGeneration) {
            return CliffContactMotionDecision.Blocked(CliffContactMotionDecision.Reason.GENERATION_MISMATCH)
        }
        when (state.status) {
            CliffContactObservationStatus.UNKNOWN -> return CliffContactMotionDecision.Blocked(CliffContactMotionDecision.Reason.UNKNOWN)
            CliffContactObservationStatus.MALFORMED -> return CliffContactMotionDecision.Blocked(CliffContactMotionDecision.Reason.MALFORMED)
            CliffContactObservationStatus.VALID -> Unit
        }
        val observedAt = checkNotNull(state.observedAtElapsedMs)
        if (nowElapsedMs - observedAt !in 0..configuration.maximumAgeMs) {
            return CliffContactMotionDecision.Blocked(CliffContactMotionDecision.Reason.STALE)
        }

        val mapping = configuration.mapping
        val towardHazard = buildSet {
            if (leftWheel > configuration.movementEpsilon) add(mapping.leftWheelForward)
            if (leftWheel < -configuration.movementEpsilon) add(mapping.leftWheelReverse)
            if (rightWheel > configuration.movementEpsilon) add(mapping.rightWheelForward)
            if (rightWheel < -configuration.movementEpsilon) add(mapping.rightWheelReverse)
        }.filterTo(linkedSetOf()) { state.readings.getValue(it).triggered }

        if (towardHazard.isEmpty()) return CliffContactMotionDecision.Clear
        val stuck = towardHazard.filterTo(linkedSetOf()) { corner ->
            nowElapsedMs - checkNotNull(state.readings.getValue(corner).triggeredSinceElapsedMs) >=
                configuration.stuckTriggeredAfterMs
        }
        return if (stuck.isNotEmpty()) {
            CliffContactMotionDecision.Blocked(CliffContactMotionDecision.Reason.STUCK_TRIGGERED, stuck)
        } else {
            CliffContactMotionDecision.Blocked(CliffContactMotionDecision.Reason.HAZARD, towardHazard)
        }
    }
}
