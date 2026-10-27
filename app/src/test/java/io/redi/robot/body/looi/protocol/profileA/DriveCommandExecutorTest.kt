package io.redi.robot.body.looi.protocol.profileA

import io.redi.robot.body.looi.ble.queue.GattOperationFailure
import io.redi.robot.body.looi.ble.queue.GattOperationFailureResult
import io.redi.robot.body.looi.ble.queue.GattOperationLane
import io.redi.robot.body.looi.ble.queue.GattOperationResult
import io.redi.robot.body.looi.ble.queue.GattOperationSuccess
import io.redi.robot.body.looi.ble.queue.GattOperationTransport
import io.redi.robot.body.looi.ble.queue.GattQueueFailureReason
import io.redi.robot.body.looi.ble.queue.GattQueuedOperationRequest
import io.redi.robot.body.looi.ble.queue.SerializedGattOperationQueue
import io.redi.robot.core.ActionRequest
import io.redi.robot.core.ActionSource
import io.redi.robot.core.RobotAction
import io.redi.robot.core.authority.MotionAuthorityStore
import io.redi.robot.core.authority.MotionLeasePolicy
import io.redi.robot.safety.DefaultSafetyController
import io.redi.robot.safety.SafetyContext
import io.redi.robot.safety.SafetyDecision
import io.redi.robot.safety.sensors.CliffContactSensorState
import io.redi.robot.safety.sensors.CliffContactStateReducer
import io.redi.robot.safety.deadman.DeadManController
import io.redi.robot.safety.deadman.DeadManMonotonicClock
import io.redi.robot.safety.deadman.DeadManPolicy
import io.redi.robot.safety.deadman.DeadManScheduledTask
import io.redi.robot.safety.deadman.DeadManScheduler
import io.redi.robot.safety.estop.EmergencyStopController
import io.redi.robot.safety.estop.EmergencyStopDelivery
import io.redi.robot.safety.estop.EmergencyStopPolicy
import io.redi.robot.safety.estop.EmergencyStopQueueCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveCommandExecutorTest {
    @Test
    fun `safety rejected drive performs no motion write or dead man refresh`() {
        val harness = Harness()
        harness.deadMan.beginConnectionEpoch(1)

        val result = harness.executor.execute(harness.drive(), harness.context(connected = false), 1)

        assertEquals(ProfileADriveExecutionResult.RejectedBySafety(SafetyDecision.Reason.DISCONNECTED), result)
        assertTrue(harness.transport.requests.isEmpty())
        assertEquals(null, harness.deadMan.state.activeDeadlineElapsedMs)
    }

    @Test
    fun `approved drive queues candidate FED0 write refreshes lease and records success`() {
        val harness = Harness()
        harness.deadMan.beginConnectionEpoch(1)

        val queued = harness.executor.execute(harness.drive(linear = 0.10f, angular = -0.10f), harness.context(), 1)
            as ProfileADriveExecutionResult.Queued
        assertEquals(listOf(8, -8), harness.transport.requests.single().payload)
        assertEquals(GattOperationLane.NORMAL, harness.transport.requests.single().lane)
        assertEquals(100L, harness.deadMan.state.activeDeadlineElapsedMs)

        harness.transport.complete(0, GattOperationSuccess())
        assertEquals(ProfileADriveOperationStatus.SUCCESS, harness.executor.observedResult(queued.operationId)?.status)
    }

    @Test
    fun `stop preempts queued drive under load and sends neutral on stop lane`() {
        val harness = Harness()
        harness.deadMan.beginConnectionEpoch(1)
        harness.executor.execute(harness.drive(sequence = 0), harness.context(), 1)
        harness.executor.execute(harness.drive(sequence = 1), harness.context(), 1)

        val stop = harness.executor.execute(harness.stop(sequence = 2), harness.context(), 1)
        assertTrue(stop is ProfileADriveExecutionResult.Queued)
        assertEquals(2, harness.transport.requests.size)
        assertEquals(GattOperationLane.STOP, harness.transport.requests[1].lane)
        assertEquals(listOf(0, 0), harness.transport.requests[1].payload)
        harness.transport.complete(0, GattOperationSuccess())
        harness.transport.complete(1, GattOperationSuccess())
        assertTrue(harness.emergencyStop.state.latched)
    }

    @Test
    fun `transport failure remains visible and activates emergency stop`() {
        val harness = Harness()
        harness.deadMan.beginConnectionEpoch(1)
        val queued = harness.executor.execute(harness.drive(), harness.context(), 1) as ProfileADriveExecutionResult.Queued

        harness.transport.complete(
            0,
            GattOperationFailureResult(GattOperationFailure(GattQueueFailureReason.TRANSPORT_ERROR, "injected failure")),
        )

        assertEquals(ProfileADriveOperationStatus.FAILURE, harness.executor.observedResult(queued.operationId)?.status)
        assertEquals(GattQueueFailureReason.TRANSPORT_ERROR, harness.executor.observedResult(queued.operationId)?.failure?.reason)
        assertTrue(harness.emergencyStop.state.latched)
        assertEquals(listOf("cancel", "stop", "stop"), harness.stopCalls)
    }

    private class Harness {
        var now = 0L
        val stopCalls = mutableListOf<String>()
        val transport = ControlledTransport()
        lateinit var queue: SerializedGattOperationQueue
        val emergencyStop = EmergencyStopController(
            authority = MotionAuthorityStore(MotionLeasePolicy(100)),
            queueCancellation = EmergencyStopQueueCancellation {
                stopCalls += "cancel"
                queue.cancelGeneration(1)
            },
            stopDelivery = EmergencyStopDelivery { stopCalls += "stop" },
            policy = EmergencyStopPolicy(2),
        )
        val deadMan = DeadManController(
            policy = DeadManPolicy(100),
            clock = DeadManMonotonicClock { now },
            scheduler = DeadManScheduler { _, _ -> DeadManScheduledTask {} },
            emergencyStop = emergencyStop,
        )
        val executor: ProfileADriveCommandExecutor

        init {
            queue = SerializedGattOperationQueue(transport, nowProvider = { now })
            executor = ProfileADriveCommandExecutor(
                safety = DefaultSafetyController(),
                deadMan = deadMan,
                emergencyStop = emergencyStop,
                encoder = ProfileADriveEncoder(ProfileADriveExperimentConfiguration.conservativeCandidate().copy(experimentalEnabled = true)),
                queue = queue,
                endpoint = ProfileADriveEndpoint("000000FF-0000-1000-8000-00805F9B34FB", "0000FED0-0000-1000-8000-00805F9B34FB"),
                queuePolicy = ProfileADriveQueuePolicy(writeWithResponse = false, timeoutMs = 500),
            )
        }

        fun context(connected: Boolean = true) = SafetyContext(
            nowElapsedMs = now,
            connected = connected,
            connectionGeneration = 1,
            emergencyStopLatched = emergencyStop.state.latched,
            cliffContactSensors = CliffContactStateReducer.observe(
                CliffContactSensorState.unknown(1),
                generation = 1,
                observedAtElapsedMs = now,
                triggered = emptySet(),
            ),
        )

        fun drive(sequence: Long = 0, linear: Float = 0.10f, angular: Float = 0f) = ActionRequest(
            sequence,
            ActionSource.MANUAL,
            RobotAction.Drive(linear, angular, now),
        )

        fun stop(sequence: Long) = ActionRequest(sequence, ActionSource.SYSTEM, RobotAction.Stop(now))
    }

    private class ControlledTransport : GattOperationTransport {
        val requests = mutableListOf<GattQueuedOperationRequest>()
        private val callbacks = mutableListOf<(GattOperationResult) -> Unit>()

        override fun dispatch(operation: GattQueuedOperationRequest, onResult: (GattOperationResult) -> Unit) {
            requests += operation
            callbacks += onResult
        }

        fun complete(index: Int, result: GattOperationResult) = callbacks[index](result)
    }
}
