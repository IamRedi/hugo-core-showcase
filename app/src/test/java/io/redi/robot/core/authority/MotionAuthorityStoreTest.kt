package io.redi.robot.core.authority

import io.redi.robot.core.ActionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionAuthorityStoreTest {
    private val policy = MotionLeasePolicy(maxDurationMs = 100)

    @Test
    fun `manual preempts AI and AI cannot preempt manual or system`() {
        val reducer = MotionAuthorityReducer(policy)
        val ai = acquire("ai", ActionSource.AI_AGENT, at = 0)
        val manual = acquire("manual", ActionSource.MANUAL, at = 1)
        val system = acquire("system", ActionSource.SYSTEM, at = 2)

        val afterAi = reducer.reduce(MotionAuthorityState(), ai).state
        val afterManual = reducer.reduce(afterAi, manual)
        val rejectedAi = reducer.reduce(afterManual.state, acquire("ai-2", ActionSource.AI_AGENT, at = 3))
        val afterSystem = reducer.reduce(afterManual.state, system)
        val rejectedManual = reducer.reduce(afterSystem.state, acquire("manual-2", ActionSource.MANUAL, at = 4))

        assertEquals(MotionAuthorityOutcome.Preempted(afterAi.activeLease!!, afterManual.state.activeLease!!), afterManual.outcome)
        assertEquals(RejectionReason.LOWER_OR_EQUAL_PRIORITY, (rejectedAi.outcome as MotionAuthorityOutcome.Rejected).reason)
        assertEquals(ActionSource.SYSTEM, afterSystem.state.activeLease?.source)
        assertEquals(RejectionReason.LOWER_OR_EQUAL_PRIORITY, (rejectedManual.outcome as MotionAuthorityOutcome.Rejected).reason)
    }

    @Test
    fun `virtual time expiry event releases only the due active lease`() {
        val store = MotionAuthorityStore(policy)
        val request = acquire("manual", ActionSource.MANUAL, at = 10)
        val granted = store.dispatch(request).state.activeLease!!

        val early = store.dispatch(MotionAuthorityEvent.LeaseExpired(granted.id, 0, occurredAtElapsedMs = 109))
        val due = store.dispatch(MotionAuthorityEvent.LeaseExpired(granted.id, 0, occurredAtElapsedMs = 110))

        assertEquals(RejectionReason.EXPIRY_NOT_DUE, (early.outcome as MotionAuthorityOutcome.Rejected).reason)
        assertNull(due.state.activeLease)
        assertEquals(MotionAuthorityOutcome.Expired(granted), due.outcome)
    }

    @Test
    fun `only the active owner can renew and release its lease`() {
        val store = MotionAuthorityStore(policy)
        val granted = store.dispatch(acquire("manual", ActionSource.MANUAL, at = 10)).state.activeLease!!

        val renewed = store.dispatch(
            MotionAuthorityEvent.Renew(granted.id, ActionSource.MANUAL, 0, durationMs = 100, occurredAtElapsedMs = 50),
        )
        val foreignRelease = store.dispatch(
            MotionAuthorityEvent.Release(granted.id, ActionSource.AI_AGENT, 0, occurredAtElapsedMs = 51),
        )
        val released = store.dispatch(
            MotionAuthorityEvent.Release(granted.id, ActionSource.MANUAL, 0, occurredAtElapsedMs = 52),
        )

        assertEquals(150L, renewed.state.activeLease?.expiresAtElapsedMs)
        assertEquals(RejectionReason.NOT_ACTIVE_OWNER, (foreignRelease.outcome as MotionAuthorityOutcome.Rejected).reason)
        assertNull(released.state.activeLease)
        assertEquals(MotionAuthorityOutcome.Released(renewed.state.activeLease!!), released.outcome)
    }

    @Test
    fun `replay race is deterministic and reconnect invalidates old token`() {
        val trace = listOf(
            acquire("ai", ActionSource.AI_AGENT, at = 0),
            acquire("manual", ActionSource.MANUAL, at = 0),
            MotionAuthorityEvent.ReconnectStarted(connectionGeneration = 1, occurredAtElapsedMs = 1),
            MotionAuthorityEvent.Renew(MotionLeaseId("manual"), ActionSource.MANUAL, 0, 10, occurredAtElapsedMs = 2),
        )

        val first = replay(trace)
        val second = replay(trace)

        assertEquals(first, second)
        assertNull(first.state.activeLease)
        assertEquals(1L, first.state.connectionGeneration)
        assertEquals(RejectionReason.STALE_CONNECTION_GENERATION, (first.outcomes.last() as MotionAuthorityOutcome.Rejected).reason)
    }

    private fun acquire(id: String, source: ActionSource, at: Long): MotionAuthorityEvent.Acquire =
        MotionAuthorityEvent.Acquire(MotionLeaseRequest(MotionLeaseId(id), source, connectionGeneration = 0, durationMs = 100), at)

    private fun replay(events: List<MotionAuthorityEvent>): ReplayResult {
        val store = MotionAuthorityStore(policy)
        val outcomes = events.map { store.dispatch(it).outcome }
        return ReplayResult(store.state, outcomes)
    }

    private data class ReplayResult(
        val state: MotionAuthorityState,
        val outcomes: List<MotionAuthorityOutcome>,
    )
}
