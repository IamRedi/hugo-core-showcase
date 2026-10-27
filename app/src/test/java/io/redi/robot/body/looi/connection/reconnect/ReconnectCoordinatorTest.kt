package io.redi.robot.body.looi.connection.reconnect

import io.redi.robot.body.looi.connection.ConnectionTarget
import io.redi.robot.body.looi.transport.timing.TimingScheduler
import io.redi.robot.body.looi.transport.timing.TimingTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectCoordinatorTest {
    @Test
    fun `loss revokes safety before first bounded retry and emits one due event`() {
        val harness = Harness()
        harness.coordinator.onTransportLost(4, target, ready)

        assertEquals(listOf(4L), harness.stoppedGenerations)
        assertEquals(ReconnectPhase.BACKING_OFF, harness.coordinator.state.phase)
        assertEquals(ReconnectEvent.RetryScheduled(5, 1, 500), harness.events.single())
        harness.advanceTo(500)
        assertEquals(ReconnectPhase.ATTEMPT_DUE, harness.coordinator.state.phase)
        assertEquals(ReconnectEvent.ReconnectAttemptDue(5, target, 1), harness.events.last())

        harness.advanceTo(5_000)
        assertEquals(2, harness.events.size)
    }

    @Test
    fun `backoff is exponential bounded and deterministic jitter stays bounded`() {
        val harness = Harness(jitter = ReconnectJitter { _, _, max -> max })
        harness.coordinator.onTransportLost(0, target, ready)
        harness.advanceTo(600)
        harness.coordinator.onConnectionStarted(1)
        harness.coordinator.onConnectionFailed(1, ready)

        assertEquals(ReconnectEvent.RetryScheduled(2, 2, 1_800), harness.events.last())
        assertEquals(listOf(0L, 1L), harness.stoppedGenerations)
    }

    @Test
    fun `permission and bluetooth gates block retries until readiness returns`() {
        val harness = Harness()
        harness.coordinator.onTransportLost(2, target, ReconnectReadiness(bluetoothEnabled = false, permissionsGranted = true))
        assertEquals(ReconnectPhase.BLOCKED, harness.coordinator.state.phase)
        assertEquals(ReconnectBlockReason.BLUETOOTH_DISABLED, harness.coordinator.state.blockReason)

        harness.coordinator.updateReadiness(ReconnectReadiness(bluetoothEnabled = true, permissionsGranted = false))
        assertEquals(ReconnectBlockReason.PERMISSION_UNAVAILABLE, harness.coordinator.state.blockReason)
        harness.coordinator.updateReadiness(ready)
        assertEquals(ReconnectPhase.BACKING_OFF, harness.coordinator.state.phase)
    }

    @Test
    fun `cancel and stale lifecycle events cannot create reconnect work`() {
        val harness = Harness()
        harness.coordinator.onTransportLost(8, target, ready)
        harness.coordinator.cancel()
        harness.coordinator.cancel()
        harness.advanceTo(10_000)
        harness.coordinator.onConnectionFailed(9, ready)
        harness.coordinator.onConnectionReady(9)

        assertEquals(ReconnectPhase.CANCELLED, harness.coordinator.state.phase)
        assertEquals(1, harness.events.size)
        assertFalse(harness.coordinator.state.hasActiveAttempt)
    }

    @Test
    fun `ready discards retry ownership without replaying safety or target action`() {
        val harness = Harness()
        harness.coordinator.onTransportLost(10, target, ready)
        harness.advanceTo(500)
        harness.coordinator.onConnectionStarted(11)
        harness.coordinator.onConnectionReady(11)

        assertEquals(ReconnectPhase.IDLE, harness.coordinator.state.phase)
        assertEquals(listOf(10L), harness.stoppedGenerations)
        assertTrue(harness.events.last() is ReconnectEvent.ReconnectAttemptDue)
    }

    private class Harness(jitter: ReconnectJitter = ReconnectJitter { _, _, _ -> 0L }) {
        var now = 0L
        val events = mutableListOf<ReconnectEvent>()
        val stoppedGenerations = mutableListOf<Long>()
        private val scheduler = VirtualScheduler()
        val coordinator = ReconnectCoordinator(
            clock = { now },
            scheduler = scheduler,
            safetyPort = ReconnectSafetyPort(stoppedGenerations::add),
            jitter = jitter,
            onEvent = events::add,
        )

        fun advanceTo(value: Long) {
            now = value
            scheduler.runDue(value)
        }
    }

    private class VirtualScheduler : TimingScheduler {
        private data class Entry(val deadline: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val entries = mutableListOf<Entry>()

        override fun scheduleAt(monotonicDeadlineMs: Long, action: () -> Unit): TimingTask {
            val entry = Entry(monotonicDeadlineMs, action)
            entries += entry
            return TimingTask { entry.cancelled = true }
        }

        fun runDue(now: Long) {
            while (true) {
                val due = entries.filter { !it.cancelled && it.deadline <= now }.minByOrNull { it.deadline } ?: return
                due.cancelled = true
                due.action()
            }
        }
    }

    private companion object {
        val target = ConnectionTarget(deviceId = "candidate-1", displayName = "LOOI candidate")
        val ready = ReconnectReadiness(bluetoothEnabled = true, permissionsGranted = true)
    }
}
