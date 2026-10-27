package io.redi.robot.core

/** Provider-neutral commands. Raw BLE bytes are deliberately absent from this API. */
sealed interface RobotAction {
    val issuedAtElapsedMs: Long

    data class Drive(
        val linear: Float,
        val angular: Float,
        override val issuedAtElapsedMs: Long,
    ) : RobotAction

    data class SetHead(
        val normalizedPitch: Float,
        override val issuedAtElapsedMs: Long,
    ) : RobotAction

    data class SetLight(
        val normalizedBrightness: Float,
        override val issuedAtElapsedMs: Long,
    ) : RobotAction

    data class Stop(override val issuedAtElapsedMs: Long) : RobotAction
}

enum class ActionSource { MANUAL, LOCAL_SKILL, AI_AGENT, SYSTEM }

data class ActionRequest(
    val sequence: Long,
    val source: ActionSource,
    val action: RobotAction,
)

interface RobotBody {
    suspend fun connect(): Result<Unit>
    suspend fun disconnect()
    suspend fun execute(action: RobotAction): Result<Unit>
    suspend fun emergencyStop(): Result<Unit>
}
