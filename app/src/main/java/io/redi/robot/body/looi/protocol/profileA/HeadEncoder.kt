package io.redi.robot.body.looi.protocol.profileA

import io.redi.robot.core.RobotAction
import io.redi.robot.safety.SafetyDecision
import kotlin.math.roundToInt

data class ProfileAHeadExperimentConfiguration(val experimentalEnabled: Boolean, val safeUpByte: Int, val candidateCenterByte: Int, val safeDownByte: Int, val minimumIntervalMs: Long) {
    init { require(safeUpByte in 0..255 && candidateCenterByte in safeUpByte + 1 until safeDownByte && safeDownByte in 0..255 && minimumIntervalMs > 0) }
}

sealed interface ProfileAHeadEncodingResult {
    data class Encoded(val characteristicShortId: String, val payload: List<Int>) : ProfileAHeadEncodingResult
    data class Rejected(val reason: String) : ProfileAHeadEncodingResult
}

class ProfileAHeadEncoder(private val configuration: ProfileAHeadExperimentConfiguration) {
    fun encode(approval: SafetyDecision): ProfileAHeadEncodingResult {
        if (!configuration.experimentalEnabled) return ProfileAHeadEncodingResult.Rejected("experiment disabled")
        val pitch = ((approval as? SafetyDecision.Allowed)?.action as? RobotAction.SetHead)?.normalizedPitch
            ?: return ProfileAHeadEncodingResult.Rejected("safety-approved head action required")
        if (!pitch.isFinite() || pitch !in -1f..1f) return ProfileAHeadEncodingResult.Rejected("invalid normalized pitch")
        val byte = if (pitch >= 0f) (configuration.candidateCenterByte + pitch * (configuration.safeDownByte - configuration.candidateCenterByte)).roundToInt()
        else (configuration.candidateCenterByte + pitch * (configuration.candidateCenterByte - configuration.safeUpByte)).roundToInt()
        return ProfileAHeadEncodingResult.Encoded("FED1", listOf(byte))
    }
}

class ProfileAHeadRateLimiter(private val minimumIntervalMs: Long) {
    private var lastAcceptedAtMs: Long? = null
    fun accept(nowElapsedMs: Long): Boolean {
        require(nowElapsedMs >= 0)
        if (lastAcceptedAtMs?.let { nowElapsedMs - it < minimumIntervalMs } == true) return false
        lastAcceptedAtMs = nowElapsedMs
        return true
    }
}
