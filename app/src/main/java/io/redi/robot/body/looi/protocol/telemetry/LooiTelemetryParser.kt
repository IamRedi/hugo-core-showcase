package io.redi.robot.body.looi.protocol.telemetry

import io.redi.robot.body.looi.protocol.profile.LooiProtocolProfile

private const val BLUETOOTH_16BIT_SUFFIX = "-0000-1000-8000-00805f9b34fb"

/** Raw, project-owned telemetry input. It is deliberately separate from safety state. */
data class RawLooiTelemetryEnvelope(
    val generation: Long,
    val timestampMs: Long,
    val profile: LooiProtocolProfile,
    val characteristicUuid: String,
    val payload: List<Int>,
) {
    init {
        require(generation >= 0) { "generation must not be negative" }
        require(timestampMs >= 0) { "timestampMs must not be negative" }
        require(payload.all { it in 0..0xff }) { "payload must contain unsigned bytes" }
    }
}

enum class TelemetryEvidence {
    SOURCE_REPORTED,
    OBSERVED_ONLY,
}

enum class TelemetryConfidence {
    HIGH_SOURCE_REPORTED,
    MEDIUM_SOURCE_REPORTED,
    LOW_SOURCE_REPORTED,
    UNKNOWN,
}

sealed interface LooiTelemetryObservation {
    val confidence: TelemetryConfidence
    val evidence: TelemetryEvidence

    /** No T017 output is allowed to authorize physical movement. */
    val safetyUsable: Boolean get() = false
}

data class InitializationCompleteCandidate(
    override val confidence: TelemetryConfidence = TelemetryConfidence.HIGH_SOURCE_REPORTED,
    override val evidence: TelemetryEvidence = TelemetryEvidence.SOURCE_REPORTED,
) : LooiTelemetryObservation

data class LowerContactCandidate(
    val frontLeftActiveLow: Boolean,
    val frontRightActiveLow: Boolean,
    val rearLeftActiveLow: Boolean,
    val rearRightActiveLow: Boolean,
    override val confidence: TelemetryConfidence = TelemetryConfidence.HIGH_SOURCE_REPORTED,
    override val evidence: TelemetryEvidence = TelemetryEvidence.SOURCE_REPORTED,
) : LooiTelemetryObservation

data class ImuLikeAxesCandidate(
    val axisA: Int,
    val axisB: Int,
    override val confidence: TelemetryConfidence = TelemetryConfidence.MEDIUM_SOURCE_REPORTED,
    override val evidence: TelemetryEvidence = TelemetryEvidence.SOURCE_REPORTED,
) : LooiTelemetryObservation

enum class DockCandidateState { DOCKED, REMOVED }

data class DockCandidate(
    val state: DockCandidateState,
    override val confidence: TelemetryConfidence = TelemetryConfidence.HIGH_SOURCE_REPORTED,
    override val evidence: TelemetryEvidence = TelemetryEvidence.SOURCE_REPORTED,
) : LooiTelemetryObservation

enum class TouchCandidateState { PRESSED, RELEASED }

data class SideTouchCandidate(
    val state: TouchCandidateState,
    val rawValue: Int,
    override val confidence: TelemetryConfidence = TelemetryConfidence.HIGH_SOURCE_REPORTED,
    override val evidence: TelemetryEvidence = TelemetryEvidence.SOURCE_REPORTED,
) : LooiTelemetryObservation

data class ExternalPowerCandidate(
    val rawValue: Int,
    override val confidence: TelemetryConfidence = TelemetryConfidence.MEDIUM_SOURCE_REPORTED,
    override val evidence: TelemetryEvidence = TelemetryEvidence.SOURCE_REPORTED,
) : LooiTelemetryObservation

data class AttitudeLikeCandidate(
    val x: Int,
    val y: Int,
    override val confidence: TelemetryConfidence = TelemetryConfidence.LOW_SOURCE_REPORTED,
    override val evidence: TelemetryEvidence = TelemetryEvidence.SOURCE_REPORTED,
) : LooiTelemetryObservation

data class FrontTouchPulseCandidate(
    override val confidence: TelemetryConfidence = TelemetryConfidence.HIGH_SOURCE_REPORTED,
    override val evidence: TelemetryEvidence = TelemetryEvidence.SOURCE_REPORTED,
) : LooiTelemetryObservation

data class ProfileABatteryCandidate(
    val percentage: Int,
    val externalPower: Boolean,
    override val confidence: TelemetryConfidence = TelemetryConfidence.MEDIUM_SOURCE_REPORTED,
    override val evidence: TelemetryEvidence = TelemetryEvidence.SOURCE_REPORTED,
) : LooiTelemetryObservation

/** Profile B FED8 is retained as an opaque ACK/status payload, never as battery data. */
data class ProfileBFed8AckCandidate(
    val rawStatus: List<Int>,
    override val confidence: TelemetryConfidence = TelemetryConfidence.UNKNOWN,
    override val evidence: TelemetryEvidence = TelemetryEvidence.OBSERVED_ONLY,
) : LooiTelemetryObservation

sealed interface LooiTelemetryParseResult {
    val envelope: RawLooiTelemetryEnvelope

    data class Parsed(
        override val envelope: RawLooiTelemetryEnvelope,
        val observation: LooiTelemetryObservation,
    ) : LooiTelemetryParseResult

    data class Unknown(
        override val envelope: RawLooiTelemetryEnvelope,
        val reason: String,
    ) : LooiTelemetryParseResult
}

object LooiTelemetryParser {
    fun parse(envelope: RawLooiTelemetryEnvelope): LooiTelemetryParseResult {
        val characteristic = shortUuid(envelope.characteristicUuid)
            ?: return unknown(envelope, "unsupported characteristic UUID")

        return when (envelope.profile) {
            LooiProtocolProfile.PROFILE_A -> when (characteristic) {
                "FED8" -> parseProfileABattery(envelope)
                "FED9" -> parseProfileAFed9(envelope)
                else -> unknown(envelope, "characteristic is not a profile A telemetry candidate")
            }

            LooiProtocolProfile.PROFILE_B -> when (characteristic) {
                "FED8" -> if (envelope.payload.isEmpty()) {
                    unknown(envelope, "profile B FED8 ACK/status payload must not be empty")
                } else {
                    LooiTelemetryParseResult.Parsed(envelope, ProfileBFed8AckCandidate(envelope.payload))
                }
                else -> unknown(envelope, "characteristic is not a profile B telemetry candidate")
            }
        }
    }

    private fun parseProfileABattery(envelope: RawLooiTelemetryEnvelope): LooiTelemetryParseResult {
        val payload = envelope.payload
        if (payload.size != 2) return unknown(envelope, "profile A FED8 battery payload must be exactly 2 bytes")
        if (payload[0] !in 0..100) return unknown(envelope, "profile A FED8 percentage is outside 0..100")
        if (payload[1] !in 0..1) return unknown(envelope, "profile A FED8 external-power flag must be 0 or 1")
        return LooiTelemetryParseResult.Parsed(
            envelope,
            ProfileABatteryCandidate(percentage = payload[0], externalPower = payload[1] == 1),
        )
    }

    private fun parseProfileAFed9(envelope: RawLooiTelemetryEnvelope): LooiTelemetryParseResult {
        val payload = envelope.payload
        val observation = when {
            payload == listOf(0x11, 0x01, 0x00) -> InitializationCompleteCandidate()
            payload.size == 5 && payload[0] == 0x01 && payload.drop(1).all { it in 0..1 } -> LowerContactCandidate(
                frontLeftActiveLow = payload[1] == 0,
                frontRightActiveLow = payload[2] == 0,
                rearLeftActiveLow = payload[3] == 0,
                rearRightActiveLow = payload[4] == 0,
            )
            payload.size == 3 && payload[0] == 0x02 -> ImuLikeAxesCandidate(payload[1].toSignedByte(), payload[2].toSignedByte())
            payload == listOf(0x05) -> DockCandidate(DockCandidateState.DOCKED)
            payload == listOf(0x06) -> DockCandidate(DockCandidateState.REMOVED)
            payload.size == 2 && payload[0] == 0x09 -> SideTouchCandidate(TouchCandidateState.PRESSED, payload[1])
            payload.size == 2 && payload[0] == 0x0a -> SideTouchCandidate(TouchCandidateState.RELEASED, payload[1])
            payload.size == 2 && payload[0] == 0x0b -> ExternalPowerCandidate(payload[1])
            payload.size == 3 && payload[0] == 0x0e -> AttitudeLikeCandidate(payload[1], payload[2])
            payload == listOf(0x12, 0x00, 0x00) -> FrontTouchPulseCandidate()
            else -> return unknown(envelope, "unrecognized or malformed profile A FED9 payload")
        }
        return LooiTelemetryParseResult.Parsed(envelope, observation)
    }

    private fun unknown(envelope: RawLooiTelemetryEnvelope, reason: String) =
        LooiTelemetryParseResult.Unknown(envelope, reason)

    private fun shortUuid(uuid: String): String? {
        val normalized = uuid.trim().lowercase()
        if (Regex("^[0-9a-f]{4}$").matches(normalized)) return normalized.uppercase()
        if (normalized.endsWith(BLUETOOTH_16BIT_SUFFIX)) {
            val prefix = normalized.removeSuffix(BLUETOOTH_16BIT_SUFFIX)
            if (prefix.length == 8 && prefix.startsWith("0000")) return prefix.substring(4).uppercase()
        }
        return null
    }
}

private fun Int.toSignedByte(): Int = if (this > 127) this - 256 else this
