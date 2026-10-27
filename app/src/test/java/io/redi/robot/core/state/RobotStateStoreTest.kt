package io.redi.robot.core.state

import io.redi.robot.core.authority.MotionAuthorityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RobotStateStoreTest {
    @Test
    fun `replayed trace produces deterministic immutable snapshot`() {
        val trace = listOf(
            RobotStateEvent.ConnectionObserved(
                RobotConnectionState(RobotConnectionPhase.TRANSPORT_READY, generation = 7),
                occurredAtElapsedMs = 10,
            ),
            RobotStateEvent.ProfileObserved(
                RobotProfileState(RobotProfileStatus.SELECTED, profileId = "PROFILE_A/v1"),
                occurredAtElapsedMs = 11,
            ),
            RobotStateEvent.TelemetryObserved(TelemetryKind.BATTERY, occurredAtElapsedMs = 12),
            RobotStateEvent.AuthorityObserved(MotionAuthorityState(), occurredAtElapsedMs = 13),
            RobotStateEvent.SafetyAvailabilityObserved(StateAvailability.UNAVAILABLE, "sensor mapping unverified", occurredAtElapsedMs = 14),
        )

        val first = replay(trace)
        val second = replay(trace)

        assertEquals(first, second)
        assertEquals(StateAvailability.AVAILABLE, first.connection.availability)
        assertEquals(TelemetryFreshness.FRESH, first.telemetry.freshnessAt(nowElapsedMs = 112, maxAgeMs = 100))
        assertEquals(TelemetryFreshness.STALE, first.telemetry.freshnessAt(nowElapsedMs = 113, maxAgeMs = 100))
    }

    @Test
    fun `unknown and unavailable remain distinct`() {
        val initial = RobotStateStore().snapshot
        val unavailable = RobotStateStore().dispatch(
            RobotStateEvent.ProfileUnavailable("no inventory", occurredAtElapsedMs = 4),
        )

        assertEquals(StateAvailability.UNKNOWN, initial.profile.availability)
        assertNull(initial.profile.observedAtElapsedMs)
        assertEquals(StateAvailability.UNAVAILABLE, unavailable.profile.availability)
        assertEquals("no inventory", unavailable.profile.unavailableReason)
        assertEquals(4L, unavailable.profile.observedAtElapsedMs)
    }

    @Test
    fun `reconnect clears stale state and starts a new generation`() {
        val populated = replay(
            listOf(
                RobotStateEvent.ProfileObserved(RobotProfileState(RobotProfileStatus.SELECTED, "PROFILE_B/v1"), 1),
                RobotStateEvent.TelemetryObserved(TelemetryKind.OPAQUE_STATUS, 2),
                RobotStateEvent.AuthorityObserved(MotionAuthorityState(), occurredAtElapsedMs = 3),
                RobotStateEvent.SafetyAvailabilityObserved(StateAvailability.UNAVAILABLE, "degraded", occurredAtElapsedMs = 4),
            ),
        )

        val reconnected = RobotStateReducer.reduce(populated, RobotStateEvent.ReconnectStarted(generation = 8, occurredAtElapsedMs = 5))

        assertEquals(RobotConnectionPhase.CONNECTING, reconnected.connection.value?.phase)
        assertEquals(8L, reconnected.connection.value?.generation)
        assertEquals(StateAvailability.UNKNOWN, reconnected.profile.availability)
        assertEquals(TelemetryFreshness.UNKNOWN, reconnected.telemetry.freshnessAt(nowElapsedMs = 5, maxAgeMs = 0))
        assertEquals(StateAvailability.UNKNOWN, reconnected.authority.availability)
        assertEquals(StateAvailability.UNKNOWN, reconnected.safety.availability)
    }

    private fun replay(trace: List<RobotStateEvent>): RobotStateSnapshot {
        val store = RobotStateStore()
        trace.forEach(store::dispatch)
        return store.snapshot
    }
}
