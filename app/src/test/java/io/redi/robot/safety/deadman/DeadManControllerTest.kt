package io.redi.robot.safety.deadman

import io.redi.robot.core.ActionRequest
import io.redi.robot.core.ActionSource
import io.redi.robot.core.RobotAction
import io.redi.robot.core.authority.MotionAuthorityStore
import io.redi.robot.core.authority.MotionLeasePolicy
import io.redi.robot.safety.estop.EmergencyStopController
import io.redi.robot.safety.estop.EmergencyStopDelivery
import io.redi.robot.safety.estop.EmergencyStopPolicy
import io.redi.robot.safety.estop.EmergencyStopQueueCancellation
import io.redi.robot.safety.estop.EmergencyStopResetInitiator
import io.redi.robot.safety.estop.EmergencyStopEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadManControllerTest {
    @Test
    fun `independent watchdog stops exactly at configured virtual deadline`() {
        val harness = Harness()
        harness.controller.beginConnectionEpoch(1)
        assertEquals(DeadManOutcome.Accepted(100), harness.controller.refreshMotion(harness.motion(sequence = 0, epoch = 1, issuedAt = 0)).outcome)

        harness.advanceTo(99)
        assertFalse(harness.emergencyStop.state.latched)
        harness.advanceTo(100)

        assertTrue(harness.emergencyStop.state.latched)
        assertEquals(listOf("cancel", "stop", "stop"), harness.calls)
        assertEquals(null, harness.controller.state.activeDeadlineElapsedMs)
    }

    @Test
    fun `replayed and out of order refreshes do not renew command lease`() {
        val harness = Harness()
        harness.controller.beginConnectionEpoch(1)
        harness.controller.refreshMotion(harness.motion(sequence = 5, epoch = 1, issuedAt = 0))

        assertEquals(
            DeadManOutcome.Rejected(DeadManRejectionReason.INVALID_SEQUENCE),
            harness.controller.refreshMotion(harness.motion(sequence = 5, epoch = 1, issuedAt = 1)).outcome,
        )
        assertEquals(
            DeadManOutcome.Rejected(DeadManRejectionReason.INVALID_SEQUENCE),
            harness.controller.refreshMotion(harness.motion(sequence = 4, epoch = 1, issuedAt = 1)).outcome,
        )
        assertEquals(100L, harness.controller.state.activeDeadlineElapsedMs)

        harness.advanceTo(100)
        assertTrue(harness.emergencyStop.state.latched)
    }

    @Test
    fun `epoch rollover rejects old refresh and stale watchdog callback`() {
        val harness = Harness()
        harness.controller.beginConnectionEpoch(1)
        harness.controller.refreshMotion(harness.motion(sequence = 0, epoch = 1, issuedAt = 0))
        val staleWatchdog = harness.scheduler.actionsAt(100).single()

        harness.controller.beginConnectionEpoch(2)
        staleWatchdog.invoke()

        assertFalse(harness.emergencyStop.state.latched)
        assertEquals(
            DeadManOutcome.Rejected(DeadManRejectionReason.STALE_EPOCH),
            harness.controller.refreshMotion(harness.motion(sequence = 1, epoch = 1, issuedAt = 1)).outcome,
        )
        harness.now = 1
        assertEquals(DeadManOutcome.Accepted(101), harness.controller.refreshMotion(harness.motion(sequence = 0, epoch = 2, issuedAt = 1)).outcome)
    }

    @Test
    fun `queue load is cancelled before watchdog stop delivery and e stop requires local reset`() {
        val harness = Harness(backlogSize = 32)
        harness.controller.beginConnectionEpoch(1)
        harness.controller.refreshMotion(harness.motion(sequence = 0, epoch = 1, issuedAt = 0))

        harness.advanceTo(100)
        assertTrue(harness.emergencyStop.state.latched)
        assertEquals(0, harness.backlog.size)
        assertEquals(listOf("cancel", "stop", "stop"), harness.calls)
        assertEquals(
            DeadManOutcome.Rejected(DeadManRejectionReason.EMERGENCY_STOP_LATCHED),
            harness.controller.refreshMotion(harness.motion(sequence = 1, epoch = 1, issuedAt = 100)).outcome,
        )

        harness.emergencyStop.dispatch(EmergencyStopEvent.ResetRequested(EmergencyStopResetInitiator.LOCAL_UI, true, 101))
        harness.now = 101
        assertEquals(DeadManOutcome.Accepted(201), harness.controller.refreshMotion(harness.motion(sequence = 1, epoch = 1, issuedAt = 101)).outcome)
    }

    private class Harness(backlogSize: Int = 0) {
        var now = 0L
        val calls = mutableListOf<String>()
        val backlog = MutableList(backlogSize) { "ordinary-$it" }
        val scheduler = VirtualScheduler()
        val emergencyStop = EmergencyStopController(
            authority = MotionAuthorityStore(MotionLeasePolicy(100)),
            queueCancellation = EmergencyStopQueueCancellation {
                calls += "cancel"
                backlog.clear()
            },
            stopDelivery = EmergencyStopDelivery { calls += "stop" },
            policy = EmergencyStopPolicy(2),
        )
        val controller = DeadManController(
            policy = DeadManPolicy(100),
            clock = DeadManMonotonicClock { now },
            scheduler = scheduler,
            emergencyStop = emergencyStop,
        )

        fun motion(sequence: Long, epoch: Long, issuedAt: Long) = MotionRefreshRequest(
            ActionRequest(sequence, ActionSource.MANUAL, RobotAction.Drive(0.1f, 0f, issuedAt)),
            epoch,
        )

        fun advanceTo(timestamp: Long) {
            now = timestamp
            scheduler.runDue(timestamp)
        }
    }

    private class VirtualScheduler : DeadManScheduler {
        private data class Entry(val deadline: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val entries = mutableListOf<Entry>()

        override fun scheduleAt(deadlineElapsedMs: Long, action: () -> Unit): DeadManScheduledTask {
            val entry = Entry(deadlineElapsedMs, action)
            entries += entry
            return DeadManScheduledTask { entry.cancelled = true }
        }

        fun actionsAt(deadline: Long): List<() -> Unit> = entries.filter { it.deadline == deadline }.map { it.action }

        fun runDue(now: Long) {
            while (true) {
                val entry = entries.filter { !it.cancelled && it.deadline <= now }.minByOrNull { it.deadline } ?: return
                entry.cancelled = true
                entry.action()
            }
        }
    }
}
