package io.redi.robot.body.looi.protocol.profileA

import io.redi.robot.core.RobotAction
import io.redi.robot.safety.SafetyDecision
import kotlin.math.ceil
import kotlin.math.floor

private const val PROFILE_A_DRIVE_CHARACTERISTIC = "FED0"

enum class ProfileAAxisPolarity {
    POSITIVE,
    INVERTED,
}

/**
 * Candidate configuration only. The signed-axis mapping and polarities remain unverified until
 * the raised-wheel protocol is completed on the target hardware.
 */
data class ProfileADriveExperimentConfiguration(
    val experimentalEnabled: Boolean,
    val normalizedInputCap: Float,
    val signedByteCap: Int,
    val linearPolarity: ProfileAAxisPolarity,
    val angularPolarity: ProfileAAxisPolarity,
) {
    init {
        require(normalizedInputCap.isFinite() && normalizedInputCap > 0f && normalizedInputCap <= 1f) {
            "normalized input cap must be finite and within (0, 1]"
        }
        require(signedByteCap in 1..127) { "signed byte cap must be within 1..127" }
    }

    companion object {
        /** Disabled by default; a caller must explicitly opt into a raised-wheel experiment. */
        fun conservativeCandidate() = ProfileADriveExperimentConfiguration(
            experimentalEnabled = false,
            normalizedInputCap = 0.20f,
            signedByteCap = 16,
            linearPolarity = ProfileAAxisPolarity.POSITIVE,
            angularPolarity = ProfileAAxisPolarity.POSITIVE,
        )
    }
}

data class ProfileADriveFrame(
    val characteristicShortId: String,
    val payload: List<Byte>,
) {
    init {
        require(characteristicShortId == PROFILE_A_DRIVE_CHARACTERISTIC) { "profile A drive must use FED0" }
        require(payload.size == 2) { "profile A drive payload must contain two bytes" }
    }
}

sealed interface ProfileADriveEncodingResult {
    data class Encoded(val frame: ProfileADriveFrame) : ProfileADriveEncodingResult
    data class Rejected(val reason: ProfileADriveEncodingRejection) : ProfileADriveEncodingResult
}

enum class ProfileADriveEncodingRejection {
    EXPERIMENT_DISABLED,
    SAFETY_APPROVAL_REQUIRED,
    INVALID_NUMBER,
}

/**
 * Pure profile-A candidate encoder. It only accepts an already-approved drive decision; raw
 * normalized motion values cannot reach this API. No GATT operation is created here.
 */
class ProfileADriveEncoder(private val configuration: ProfileADriveExperimentConfiguration) {
    fun encode(approval: SafetyDecision): ProfileADriveEncodingResult {
        if (!configuration.experimentalEnabled) {
            return ProfileADriveEncodingResult.Rejected(ProfileADriveEncodingRejection.EXPERIMENT_DISABLED)
        }
        return when (val action = (approval as? SafetyDecision.Allowed)?.action) {
            is RobotAction.Drive -> {
                if (!action.linear.isFinite() || !action.angular.isFinite()) {
                    ProfileADriveEncodingResult.Rejected(ProfileADriveEncodingRejection.INVALID_NUMBER)
                } else {
                    ProfileADriveEncodingResult.Encoded(
                        ProfileADriveFrame(
                            characteristicShortId = PROFILE_A_DRIVE_CHARACTERISTIC,
                            payload = listOf(
                                quantize(action.linear, configuration.linearPolarity),
                                quantize(action.angular, configuration.angularPolarity),
                            ),
                        ),
                    )
                }
            }

            is RobotAction.Stop -> ProfileADriveEncodingResult.Encoded(
                ProfileADriveFrame(PROFILE_A_DRIVE_CHARACTERISTIC, listOf(0, 0)),
            )

            else -> ProfileADriveEncodingResult.Rejected(ProfileADriveEncodingRejection.SAFETY_APPROVAL_REQUIRED)
        }
    }

    private fun quantize(value: Float, polarity: ProfileAAxisPolarity): Byte {
        val capped = value.coerceIn(-configuration.normalizedInputCap, configuration.normalizedInputCap)
        val signed = roundAwayFromZero(capped / configuration.normalizedInputCap * configuration.signedByteCap)
        val polarized = if (polarity == ProfileAAxisPolarity.POSITIVE) signed else -signed
        return polarized.toByte()
    }

    private fun roundAwayFromZero(value: Float): Int = if (value >= 0f) floor(value + 0.5f).toInt() else ceil(value - 0.5f).toInt()
}
