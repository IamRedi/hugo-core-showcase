package io.redi.robot.safety.estop

import io.redi.robot.core.ActionSource
import io.redi.robot.core.authority.MotionAuthorityEvent
import io.redi.robot.core.authority.MotionAuthorityStore
import io.redi.robot.core.authority.MotionLeaseId
import io.redi.robot.core.authority.MotionLeasePolicy
import io.redi.robot.core.authority.MotionLeaseRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyStopControllerTest {
    @Test
    fun `activation synchronously revokes authority cancels queue and repeats stop`() {
        val authority = authorityWithManualLease()
        val calls = mutableListOf<String>()
        val controller = EmergencyStopController(
            authority = authority,
            queueCancellation = EmergencyStopQueueCancellation {
                assertNull(authority.state.activeLease)
                calls += "cancel"
            },
            stopDelivery = EmergencyStopDelivery { calls += "stop" },
            policy = EmergencyStopPolicy(repeatedStopRequestCount = 2),
        )

        val transition = controller.dispatch(EmergencyStopEvent.Activate(EmergencyStopReason.LOCAL_USER, 10))

        assertTrue(controller.state.latched)
        assertEquals(EmergencyStopOutcome.Latched, transition.emergencyStop.outcome)
        assertEquals(EmergencyStopReason.LOCAL_USER, controller.state.lastAudit?.reason)
        assertNull(authority.state.activeLease)
        assertEquals(listOf("cancel", "stop", "stop"), calls)
    }

    @Test
    fun `repeated activation repeats stop and retains auditable count`() {
        val calls = mutableListOf<String>()
        val controller = controller(calls)

        controller.dispatch(EmergencyStopEvent.Activate(EmergencyStopReason.TRANSPORT_LOST, 1))
        val repeated = controller.dispatch(EmergencyStopEvent.Activate(EmergencyStopReason.SERVICE_SHUTDOWN, 2))

        assertEquals(EmergencyStopOutcome.RepeatedStopRequested, repeated.emergencyStop.outcome)
        assertEquals(2L, controller.state.lastAudit?.activationCount)
        assertEquals(EmergencyStopReason.SERVICE_SHUTDOWN, controller.state.lastAudit?.reason)
        assertEquals(listOf("cancel", "stop", "stop", "cancel", "stop", "stop"), calls)
    }

    @Test
    fun `only deliberate stationary local reset clears latch and recreated controller retains latch`() {
        val original = controller(mutableListOf())
        original.dispatch(EmergencyStopEvent.Activate(EmergencyStopReason.LOCAL_USER, 1))
        val restored = controller(mutableListOf(), initialState = original.state)

        assertTrue(restored.state.latched)
        assertEquals(
            ResetRejectionReason.NOT_LOCAL_UI,
            (restored.dispatch(EmergencyStopEvent.ResetRequested(EmergencyStopResetInitiator.AI_AGENT, true, 2)).emergencyStop.outcome
                as EmergencyStopOutcome.ResetRejected).reason,
        )
        assertEquals(
            ResetRejectionReason.NOT_LOCAL_UI,
            (restored.dispatch(EmergencyStopEvent.ResetRequested(EmergencyStopResetInitiator.REMOTE, true, 3)).emergencyStop.outcome
                as EmergencyStopOutcome.ResetRejected).reason,
        )
        assertEquals(
            ResetRejectionReason.NOT_STATIONARY,
            (restored.dispatch(EmergencyStopEvent.ResetRequested(EmergencyStopResetInitiator.LOCAL_UI, false, 4)).emergencyStop.outcome
                as EmergencyStopOutcome.ResetRejected).reason,
        )

        assertEquals(EmergencyStopOutcome.Reset, restored.dispatch(
            EmergencyStopEvent.ResetRequested(EmergencyStopResetInitiator.LOCAL_UI, true, 5),
        ).emergencyStop.outcome)
        assertFalse(restored.state.latched)
    }

    @Test
    fun `later activation wins a deterministic activation reset activation race trace`() {
        val state = listOf(
            EmergencyStopEvent.Activate(EmergencyStopReason.LOCAL_USER, 1),
            EmergencyStopEvent.ResetRequested(EmergencyStopResetInitiator.LOCAL_UI, true, 2),
            EmergencyStopEvent.Activate(EmergencyStopReason.TRANSPORT_LOST, 3),
        ).fold(EmergencyStopState()) { current, event ->
            EmergencyStopReducer.reduce(current, event).state
        }

        assertTrue(state.latched)
        assertEquals(EmergencyStopReason.TRANSPORT_LOST, state.lastAudit?.reason)
        assertEquals(2L, state.lastAudit?.activationCount)
    }

    private fun controller(calls: MutableList<String>, initialState: EmergencyStopState = EmergencyStopState()) = EmergencyStopController(
        authority = MotionAuthorityStore(MotionLeasePolicy(100)),
        queueCancellation = EmergencyStopQueueCancellation { calls += "cancel" },
        stopDelivery = EmergencyStopDelivery { calls += "stop" },
        policy = EmergencyStopPolicy(2),
        initialState = initialState,
    )

    private fun authorityWithManualLease(): MotionAuthorityStore {
        val authority = MotionAuthorityStore(MotionLeasePolicy(100))
        authority.dispatch(
            MotionAuthorityEvent.Acquire(
                MotionLeaseRequest(MotionLeaseId("manual"), ActionSource.MANUAL, connectionGeneration = 0, durationMs = 100),
                occurredAtElapsedMs = 0,
            ),
        )
        return authority
    }
}
