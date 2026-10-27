package io.redi.robot.body.looi.protocol.profileA

import io.redi.robot.body.looi.ble.queue.GattOperationEnqueued
import io.redi.robot.body.looi.ble.queue.GattOperationFailure
import io.redi.robot.body.looi.ble.queue.GattOperationFailureResult
import io.redi.robot.body.looi.ble.queue.GattOperationKind
import io.redi.robot.body.looi.ble.queue.GattOperationLane
import io.redi.robot.body.looi.ble.queue.GattOperationResult
import io.redi.robot.body.looi.ble.queue.GattOperationSuccess
import io.redi.robot.body.looi.ble.queue.GattOperationRejected
import io.redi.robot.body.looi.ble.queue.GattQueuedOperationRequest
import io.redi.robot.body.looi.ble.queue.SerializedGattOperationQueue
import io.redi.robot.core.ActionRequest
import io.redi.robot.core.RobotAction
import io.redi.robot.safety.DefaultSafetyController
import io.redi.robot.safety.SafetyContext
import io.redi.robot.safety.SafetyDecision
import io.redi.robot.safety.deadman.DeadManController
import io.redi.robot.safety.deadman.DeadManOutcome
import io.redi.robot.safety.deadman.MotionRefreshRequest
import io.redi.robot.safety.estop.EmergencyStopController
import io.redi.robot.safety.estop.EmergencyStopEvent
import io.redi.robot.safety.estop.EmergencyStopReason

data class ProfileADriveEndpoint(
    val serviceUuid: String,
    val fed0CharacteristicUuid: String,
) {
    init {
        require(serviceUuid.isNotBlank()) { "service UUID must not be blank" }
        require(fed0CharacteristicUuid.isNotBlank()) { "FED0 characteristic UUID must not be blank" }
    }
}

data class ProfileADriveQueuePolicy(
    val writeWithResponse: Boolean,
    val timeoutMs: Long,
) {
    init {
        require(timeoutMs > 0) { "queue timeout must be positive" }
    }
}

@JvmInline
value class ProfileADriveOperationId(val value: Long)

enum class ProfileADriveOperationStatus {
    PENDING,
    SUCCESS,
    FAILURE,
}

data class ProfileADriveObservedResult(
    val operationId: ProfileADriveOperationId,
    val status: ProfileADriveOperationStatus,
    val failure: GattOperationFailure? = null,
)

sealed interface ProfileADriveExecutionResult {
    data class RejectedBySafety(val reason: SafetyDecision.Reason) : ProfileADriveExecutionResult
    data class RejectedByDeadMan(val outcome: DeadManOutcome) : ProfileADriveExecutionResult
    data class RejectedByEncoder(val reason: ProfileADriveEncodingRejection) : ProfileADriveExecutionResult
    data class RejectedByQueue(val failure: GattOperationFailure) : ProfileADriveExecutionResult
    data class Queued(val operationId: ProfileADriveOperationId) : ProfileADriveExecutionResult
}

/**
 * The only profile-A drive write path. Encoder and queue remain private, and every non-stop
 * motion request is safety-approved and dead-man refreshed before it can be delivered.
 */
class ProfileADriveCommandExecutor(
    private val safety: DefaultSafetyController,
    private val deadMan: DeadManController,
    private val emergencyStop: EmergencyStopController,
    private val encoder: ProfileADriveEncoder,
    private val queue: SerializedGattOperationQueue,
    private val endpoint: ProfileADriveEndpoint,
    private val queuePolicy: ProfileADriveQueuePolicy,
) {
    private var nextOperationId = 1L
    private val observed = linkedMapOf<ProfileADriveOperationId, ProfileADriveObservedResult>()

    fun execute(
        request: ActionRequest,
        safetyContext: SafetyContext,
        connectionEpoch: Long,
    ): ProfileADriveExecutionResult {
        val approval = safety.evaluate(request, safetyContext)
        if (approval is SafetyDecision.Rejected) return ProfileADriveExecutionResult.RejectedBySafety(approval.reason)

        if (request.action is RobotAction.Drive) {
            val deadManTransition = deadMan.refreshMotion(MotionRefreshRequest(request, connectionEpoch))
            if (deadManTransition.outcome !is DeadManOutcome.Accepted) {
                return ProfileADriveExecutionResult.RejectedByDeadMan(deadManTransition.outcome)
            }
        }

        val encoding = encoder.encode(approval)
        val frame = (encoding as? ProfileADriveEncodingResult.Encoded)?.frame
            ?: return ProfileADriveExecutionResult.RejectedByEncoder((encoding as ProfileADriveEncodingResult.Rejected).reason)
        val operationId = ProfileADriveOperationId(nextOperationId++)
        observed[operationId] = ProfileADriveObservedResult(operationId, ProfileADriveOperationStatus.PENDING)
        val isStop = request.action is RobotAction.Stop
        val submission = queue.submit(
            GattQueuedOperationRequest(
                generation = connectionEpoch,
                kind = GattOperationKind.WRITE,
                serviceUuid = endpoint.serviceUuid,
                characteristicUuid = endpoint.fed0CharacteristicUuid,
                payload = frame.payload.map(Byte::toInt),
                withResponse = queuePolicy.writeWithResponse,
                lane = if (isStop) GattOperationLane.STOP else GattOperationLane.NORMAL,
                timeoutMs = queuePolicy.timeoutMs,
            ),
        ) { result -> recordTransportResult(operationId, result, safetyContext.nowElapsedMs) }

        return when (submission) {
            is GattOperationEnqueued -> ProfileADriveExecutionResult.Queued(operationId)
            is GattOperationRejected -> {
                observed[operationId] = ProfileADriveObservedResult(operationId, ProfileADriveOperationStatus.FAILURE, submission.reason)
                emergencyStop.dispatch(EmergencyStopEvent.Activate(EmergencyStopReason.SAFETY_POLICY, safetyContext.nowElapsedMs))
                ProfileADriveExecutionResult.RejectedByQueue(submission.reason)
            }
        }
    }

    fun observedResult(operationId: ProfileADriveOperationId): ProfileADriveObservedResult? = observed[operationId]

    private fun recordTransportResult(operationId: ProfileADriveOperationId, result: GattOperationResult, nowElapsedMs: Long) {
        val failure = (result as? GattOperationFailureResult)?.failure
        observed[operationId] = ProfileADriveObservedResult(
            operationId,
            if (result is GattOperationSuccess) ProfileADriveOperationStatus.SUCCESS else ProfileADriveOperationStatus.FAILURE,
            failure,
        )
        if (failure != null) emergencyStop.dispatch(EmergencyStopEvent.Activate(EmergencyStopReason.SAFETY_POLICY, nowElapsedMs))
    }
}
