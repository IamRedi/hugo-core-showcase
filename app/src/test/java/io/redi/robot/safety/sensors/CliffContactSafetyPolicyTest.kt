package io.redi.robot.safety.sensors

import io.redi.robot.core.RobotAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CliffContactSafetyPolicyTest {
    private val policy = CliffContactSafetyPolicy()

    @Test fun `each verified corner blocks motion toward it and permits direct escape`() {
        val cases = listOf(
            Triple(CliffContactCorner.FRONT_LEFT, drive(linear = .1f), drive(linear = -.1f)),
            Triple(CliffContactCorner.FRONT_RIGHT, drive(linear = .1f), drive(linear = -.1f)),
            Triple(CliffContactCorner.REAR_LEFT, drive(linear = -.1f), drive(linear = .1f)),
            Triple(CliffContactCorner.REAR_RIGHT, drive(linear = -.1f), drive(linear = .1f)),
        )
        cases.forEach { (corner, toward, escape) ->
            assertBlocked(policy.evaluate(toward, state(setOf(corner)), 1, 100), CliffContactMotionDecision.Reason.HAZARD, corner)
            assertEquals(CliffContactMotionDecision.Clear, policy.evaluate(escape, state(setOf(corner)), 1, 100))
        }
    }

    @Test fun `turning wheel directions map to the physically corresponding corners`() {
        val leftTurn = drive(angular = -.1f)
        val rightTurn = drive(angular = .1f)
        assertBlocked(policy.evaluate(leftTurn, state(setOf(CliffContactCorner.FRONT_RIGHT)), 1, 100), CliffContactMotionDecision.Reason.HAZARD, CliffContactCorner.FRONT_RIGHT)
        assertBlocked(policy.evaluate(leftTurn, state(setOf(CliffContactCorner.REAR_LEFT)), 1, 100), CliffContactMotionDecision.Reason.HAZARD, CliffContactCorner.REAR_LEFT)
        assertBlocked(policy.evaluate(rightTurn, state(setOf(CliffContactCorner.FRONT_LEFT)), 1, 100), CliffContactMotionDecision.Reason.HAZARD, CliffContactCorner.FRONT_LEFT)
        assertBlocked(policy.evaluate(rightTurn, state(setOf(CliffContactCorner.REAR_RIGHT)), 1, 100), CliffContactMotionDecision.Reason.HAZARD, CliffContactCorner.REAR_RIGHT)
    }

    @Test fun `unknown malformed stale future and wrong generation observations fail closed`() {
        assertReason(CliffContactSensorState.unknown(1), 100, CliffContactMotionDecision.Reason.UNKNOWN)
        assertReason(CliffContactStateReducer.malformed(1, 100), 100, CliffContactMotionDecision.Reason.MALFORMED)
        assertReason(state(emptySet(), observedAt = 100), 351, CliffContactMotionDecision.Reason.STALE)
        assertReason(state(emptySet(), observedAt = 101), 100, CliffContactMotionDecision.Reason.STALE)
        val wrongGeneration = policy.evaluate(drive(linear = .1f), state(emptySet()), 2, 100)
        assertEquals(CliffContactMotionDecision.Blocked(CliffContactMotionDecision.Reason.GENERATION_MISMATCH), wrongGeneration)
    }

    @Test fun `stationary drive is safe even before the first sensor frame`() {
        assertEquals(
            CliffContactMotionDecision.Clear,
            policy.evaluate(drive(), CliffContactSensorState.unknown(1), 1, 100),
        )
    }

    @Test fun `stuck triggered sensor remains directional and does not trap escape`() {
        val configured = CliffContactSafetyPolicy(CliffContactSafetyConfiguration(maximumAgeMs = 10_000, stuckTriggeredAfterMs = 500))
        val first = state(setOf(CliffContactCorner.FRONT_LEFT), observedAt = 0)
        val refreshed = CliffContactStateReducer.observe(first, 1, 600, setOf(CliffContactCorner.FRONT_LEFT))
        assertBlocked(
            configured.evaluate(drive(linear = .1f), refreshed, 1, 600),
            CliffContactMotionDecision.Reason.STUCK_TRIGGERED,
            CliffContactCorner.FRONT_LEFT,
        )
        assertEquals(CliffContactMotionDecision.Clear, configured.evaluate(drive(linear = -.1f), refreshed, 1, 600))
    }

    @Test fun `reducer preserves active duration and clears it on safe transition`() {
        val first = state(setOf(CliffContactCorner.FRONT_LEFT), observedAt = 10)
        val repeated = CliffContactStateReducer.observe(first, 1, 20, setOf(CliffContactCorner.FRONT_LEFT))
        val safe = CliffContactStateReducer.observe(repeated, 1, 30, emptySet())
        assertEquals(10L, repeated.readings.getValue(CliffContactCorner.FRONT_LEFT).triggeredSinceElapsedMs)
        assertEquals(null, safe.readings.getValue(CliffContactCorner.FRONT_LEFT).triggeredSinceElapsedMs)
    }

    private fun state(triggered: Set<CliffContactCorner>, observedAt: Long = 100) = CliffContactStateReducer.observe(
        CliffContactSensorState.unknown(1), 1, observedAt, triggered,
    )
    private fun drive(linear: Float = 0f, angular: Float = 0f) = RobotAction.Drive(linear, angular, 0)
    private fun assertReason(state: CliffContactSensorState, now: Long, reason: CliffContactMotionDecision.Reason) {
        assertEquals(CliffContactMotionDecision.Blocked(reason), policy.evaluate(drive(linear = .1f), state, 1, now))
    }
    private fun assertBlocked(
        decision: CliffContactMotionDecision,
        reason: CliffContactMotionDecision.Reason,
        corner: CliffContactCorner,
    ) {
        assertTrue(decision is CliffContactMotionDecision.Blocked)
        decision as CliffContactMotionDecision.Blocked
        assertEquals(reason, decision.reason)
        assertTrue(corner in decision.corners)
    }
}
