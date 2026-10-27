package io.redi.robot.body.looi.transport.timing

import io.redi.robot.body.looi.protocol.profile.LooiProtocolProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportTimingCoordinatorTest {
    @Test
    fun `watchdog fires exactly at monotonic deadline and activity renews it`() {
        val harness = Harness()
        harness.coordinator.start(1, configuration(watchdogMs = 100))

        harness.advanceTo(99)
        assertTrue(harness.events.isEmpty())
        harness.advanceTo(100)
        assertEquals(TransportTimingEvent.LocalWatchdogMissedDeadline(1, 100, 100), harness.events.single())

        harness.coordinator.recordTransportActivity(1)
        assertFalse(harness.coordinator.snapshot().watchdogMissed)
        harness.advanceTo(199)
        assertEquals(1, harness.events.size)
        harness.advanceTo(200)
        assertEquals(2, harness.events.size)
    }

    @Test
    fun `refresh cadence resists scheduler drift and does not encode an operation`() {
        val harness = Harness()
        harness.coordinator.start(2, configuration(idle = TransportRefreshCandidate(intervalMs = 10, enabled = true)))

        harness.advanceTo(10)
        harness.advanceTo(26)

        val refreshes = harness.events.filterIsInstance<TransportTimingEvent.RefreshDue>()
        assertEquals(listOf(10L, 20L), refreshes.map { it.scheduledDeadlineMs })
        assertEquals(30L, harness.scheduler.nextDeadline())
        assertTrue(refreshes.all { it.kind == TransportRefreshKind.IDLE_NEUTRAL })
    }

    @Test
    fun `movement refresh is only an explicit enabled hook`() {
        val harness = Harness()
        harness.coordinator.start(3, configuration(movement = TransportRefreshCandidate(intervalMs = 20, enabled = false)))
        harness.coordinator.setMovementRefreshActive(3, true)
        harness.advanceTo(100)
        assertTrue(harness.events.isEmpty())

        val enabledHarness = Harness()
        enabledHarness.coordinator.start(3, configuration(movement = TransportRefreshCandidate(intervalMs = 20, enabled = true)))
        enabledHarness.coordinator.setMovementRefreshActive(3, true)
        enabledHarness.advanceTo(20)
        assertEquals(TransportRefreshKind.MOVEMENT, (enabledHarness.events.single() as TransportTimingEvent.RefreshDue).kind)
    }

    @Test
    fun `new generation cancels old work and stale callbacks cannot leak`() {
        val harness = Harness()
        harness.coordinator.start(4, configuration(watchdogMs = 10, idle = TransportRefreshCandidate(5, true)))
        val staleAction = harness.scheduler.actionsAt(5).single()
        harness.coordinator.start(5, configuration(watchdogMs = 10))

        staleAction.invoke()
        harness.advanceTo(10)
        assertEquals(listOf(TransportTimingEvent.LocalWatchdogMissedDeadline(5, 10, 10)), harness.events)
        assertEquals(5L, harness.coordinator.snapshot().generation)
    }

    @Test
    fun `cancellation is idempotent and removes all deadlines`() {
        val harness = Harness()
        harness.coordinator.start(6, configuration(idle = TransportRefreshCandidate(10, true), movement = TransportRefreshCandidate(10, true)))
        harness.coordinator.setMovementRefreshActive(6, true)
        harness.coordinator.cancelGeneration(6)
        harness.coordinator.cancelGeneration(6)

        harness.advanceTo(1_000)
        assertTrue(harness.events.isEmpty())
        assertNull(harness.coordinator.snapshot().generation)
        assertFalse(harness.coordinator.snapshot().movementRefreshActive)
    }

    private fun configuration(
        watchdogMs: Long = 1_000,
        idle: TransportRefreshCandidate? = null,
        movement: TransportRefreshCandidate? = null,
    ) = TransportTimingConfiguration(LooiProtocolProfile.PROFILE_A, watchdogMs, idle, movement)

    private class Harness {
        var now = 0L
        val scheduler = VirtualScheduler { now }
        val events = mutableListOf<TransportTimingEvent>()
        val coordinator = TransportTimingCoordinator({ now }, scheduler, events::add)

        fun advanceTo(timestampMs: Long) {
            now = timestampMs
            scheduler.runDue(timestampMs)
        }
    }

    private class VirtualScheduler(private val now: () -> Long) : TimingScheduler {
        private data class Entry(val deadlineMs: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private val entries = mutableListOf<Entry>()

        override fun scheduleAt(monotonicDeadlineMs: Long, action: () -> Unit): TimingTask {
            val entry = Entry(monotonicDeadlineMs, action)
            entries += entry
            return TimingTask { entry.cancelled = true }
        }

        fun runDue(timestampMs: Long) {
            while (true) {
                val due = entries.filter { !it.cancelled && it.deadlineMs <= timestampMs }.minByOrNull { it.deadlineMs } ?: return
                due.cancelled = true
                due.action.invoke()
            }
        }

        fun nextDeadline(): Long? = entries.filter { !it.cancelled }.minOfOrNull { it.deadlineMs }
        fun actionsAt(deadlineMs: Long): List<() -> Unit> = entries.filter { it.deadlineMs == deadlineMs }.map { it.action }
    }
}
