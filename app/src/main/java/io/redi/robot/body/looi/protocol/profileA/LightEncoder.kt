package io.redi.robot.body.looi.protocol.profileA

import io.redi.robot.core.RobotAction
import io.redi.robot.safety.SafetyDecision

data class ProfileALightExperimentConfiguration(val experimentalEnabled: Boolean, val minimumOnByte: Int? = null, val supportsBrightness: Boolean = false) {
    init { require(minimumOnByte == null || minimumOnByte in 1..255) }
}
sealed interface ProfileALightEncodingResult {
    data class Encoded(val characteristicShortId: String, val payload: List<Int>) : ProfileALightEncodingResult
    data class Rejected(val reason: String) : ProfileALightEncodingResult
}
class ProfileALightEncoder(private val configuration: ProfileALightExperimentConfiguration) {
    fun encode(approval: SafetyDecision): ProfileALightEncodingResult {
        if (!configuration.experimentalEnabled) return ProfileALightEncodingResult.Rejected("experiment disabled")
        val brightness = ((approval as? SafetyDecision.Allowed)?.action as? RobotAction.SetLight)?.normalizedBrightness
            ?: return ProfileALightEncodingResult.Rejected("safety-approved light action required")
        if (!brightness.isFinite() || brightness !in 0f..1f) return ProfileALightEncodingResult.Rejected("invalid brightness")
        val value = when {
            brightness == 0f -> 0
            configuration.supportsBrightness && configuration.minimumOnByte != null -> (configuration.minimumOnByte + brightness * (255 - configuration.minimumOnByte)).toInt()
            brightness == 1f && configuration.minimumOnByte != null -> configuration.minimumOnByte
            else -> return ProfileALightEncodingResult.Rejected("unsupported brightness")
        }
        return ProfileALightEncodingResult.Encoded("FED2", listOf(value))
    }
}
