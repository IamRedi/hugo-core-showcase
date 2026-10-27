package io.redi.robot.safety

import io.redi.robot.core.ActionRequest
import io.redi.robot.core.RobotAction
import io.redi.robot.safety.sensors.CliffContactMotionDecision
import io.redi.robot.safety.sensors.CliffContactSafetyPolicy
import io.redi.robot.safety.sensors.CliffContactSensorState

data class SafetyContext(
    val nowElapsedMs: Long,
    val connected: Boolean,
    val connectionGeneration: Long,
    val emergencyStopLatched: Boolean,
    val cliffContactSensors: CliffContactSensorState,
)

data class SafetyLimits(
    val maxCommandAgeMs: Long = 250,
    val maxLinearMagnitude: Float = 0.35f,
    val maxAngularMagnitude: Float = 0.50f,
    val maxHeadPitchMagnitude: Float = 1f,
)

sealed interface SafetyDecision {
    data class Allowed(val action: RobotAction) : SafetyDecision
    data class Rejected(val reason: Reason) : SafetyDecision

    enum class Reason {
        DISCONNECTED,
        EMERGENCY_STOP_LATCHED,
        STALE_COMMAND,
        INVALID_NUMBER,
        SPEED_LIMIT_EXCEEDED,
        SENSOR_STATE_UNSAFE,
        HEAD_LIMIT_EXCEEDED,
    }
}

/** Pure, deterministic policy. Transport and AI code may not bypass this contract. */
class DefaultSafetyController(
    private val limits: SafetyLimits = SafetyLimits(),
    private val cliffContactSafety: CliffContactSafetyPolicy = CliffContactSafetyPolicy(),
) {
    fun evaluate(request: ActionRequest, context: SafetyContext): SafetyDecision {
        val action = request.action
        if (action is RobotAction.Stop) return SafetyDecision.Allowed(action)
        if (context.emergencyStopLatched) {
            return SafetyDecision.Rejected(SafetyDecision.Reason.EMERGENCY_STOP_LATCHED)
        }
        if (!context.connected) return SafetyDecision.Rejected(SafetyDecision.Reason.DISCONNECTED)
        if (context.nowElapsedMs - action.issuedAtElapsedMs !in 0..limits.maxCommandAgeMs) {
            return SafetyDecision.Rejected(SafetyDecision.Reason.STALE_COMMAND)
        }
        if (action is RobotAction.Drive) {
            if (!action.linear.isFinite() || !action.angular.isFinite()) {
                return SafetyDecision.Rejected(SafetyDecision.Reason.INVALID_NUMBER)
            }
            if (kotlin.math.abs(action.linear) > limits.maxLinearMagnitude ||
                kotlin.math.abs(action.angular) > limits.maxAngularMagnitude
            ) {
                return SafetyDecision.Rejected(SafetyDecision.Reason.SPEED_LIMIT_EXCEEDED)
            }
            if (cliffContactSafety.evaluate(action, context.cliffContactSensors, context.connectionGeneration, context.nowElapsedMs) is
                CliffContactMotionDecision.Blocked
            ) {
                return SafetyDecision.Rejected(SafetyDecision.Reason.SENSOR_STATE_UNSAFE)
            }
        }
        if (action is RobotAction.SetHead) {
            if (!action.normalizedPitch.isFinite()) return SafetyDecision.Rejected(SafetyDecision.Reason.INVALID_NUMBER)
            if (kotlin.math.abs(action.normalizedPitch) > limits.maxHeadPitchMagnitude) {
                return SafetyDecision.Rejected(SafetyDecision.Reason.HEAD_LIMIT_EXCEEDED)
            }
        }
        if (action is RobotAction.SetLight && (!action.normalizedBrightness.isFinite() || action.normalizedBrightness !in 0f..1f)) {
            return SafetyDecision.Rejected(SafetyDecision.Reason.INVALID_NUMBER)
        }
        return SafetyDecision.Allowed(action)
    }
}
