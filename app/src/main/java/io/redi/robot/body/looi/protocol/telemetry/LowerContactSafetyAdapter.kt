package io.redi.robot.body.looi.protocol.telemetry

import io.redi.robot.body.looi.protocol.profile.LooiProtocolProfile
import io.redi.robot.safety.sensors.CliffContactCorner
import io.redi.robot.safety.sensors.CliffContactSensorState
import io.redi.robot.safety.sensors.CliffContactStateReducer

/** Profile boundary that promotes only the hardware-verified FED9 01 frame into safety state. */
class LowerContactSafetyAdapter(initialGeneration: Long) {
    var state: CliffContactSensorState = CliffContactSensorState.unknown(initialGeneration)
        private set

    init {
        require(initialGeneration >= 0) { "generation must not be negative" }
    }

    fun reset(generation: Long): CliffContactSensorState {
        state = CliffContactSensorState.unknown(generation)
        return state
    }

    fun accept(result: LooiTelemetryParseResult): CliffContactSensorState {
        val envelope = result.envelope
        if (envelope.generation != state.generation) return state
        when (result) {
            is LooiTelemetryParseResult.Parsed -> {
                val contact = result.observation as? LowerContactCandidate
                if (contact == null) {
                    if (envelope.profile == LooiProtocolProfile.PROFILE_A && isFed9(envelope.characteristicUuid)) {
                        state = CliffContactStateReducer.refreshFromLiveStream(
                            state,
                            envelope.generation,
                            envelope.timestampMs,
                        )
                    }
                    return state
                }
                val triggered = buildSet {
                    if (contact.frontLeftActiveLow) add(CliffContactCorner.FRONT_LEFT)
                    if (contact.frontRightActiveLow) add(CliffContactCorner.FRONT_RIGHT)
                    if (contact.rearLeftActiveLow) add(CliffContactCorner.REAR_LEFT)
                    if (contact.rearRightActiveLow) add(CliffContactCorner.REAR_RIGHT)
                }
                state = CliffContactStateReducer.observe(state, envelope.generation, envelope.timestampMs, triggered)
            }
            is LooiTelemetryParseResult.Unknown -> if (
                envelope.profile == LooiProtocolProfile.PROFILE_A &&
                isFed9(envelope.characteristicUuid) &&
                envelope.payload.firstOrNull() == 0x01
            ) {
                state = CliffContactStateReducer.malformed(envelope.generation, envelope.timestampMs)
            }
        }
        return state
    }

    private fun isFed9(uuid: String): Boolean {
        val normalized = uuid.trim().lowercase()
        return normalized == "fed9" || normalized == "0000fed9-0000-1000-8000-00805f9b34fb"
    }
}
