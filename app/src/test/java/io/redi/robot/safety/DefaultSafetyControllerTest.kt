package io.redi.robot.safety

import io.redi.robot.core.ActionRequest
import io.redi.robot.core.ActionSource
import io.redi.robot.core.RobotAction
import io.redi.robot.safety.sensors.CliffContactSensorState
import io.redi.robot.safety.sensors.CliffContactStateReducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSafetyControllerTest {
    private val controller = DefaultSafetyController()

    @Test fun `stop is allowed even when disconnected and e-stop is latched`() {
        val stop = RobotAction.Stop(issuedAtElapsedMs = 1_000)
        val result = controller.evaluate(
            ActionRequest(1, ActionSource.SYSTEM, stop),
            context(2_000, connected = false, emergencyStopLatched = true, sensors = CliffContactSensorState.unknown(1)),
        )
        assertEquals(SafetyDecision.Allowed(stop), result)
    }

    @Test fun `stale drive is rejected`() {
        val result = controller.evaluate(
            ActionRequest(1, ActionSource.MANUAL, RobotAction.Drive(.1f, 0f, 1_000)),
            context(1_251),
        )
        assertEquals(SafetyDecision.Rejected(SafetyDecision.Reason.STALE_COMMAND), result)
    }

    @Test fun `drive above configured bound is rejected`() {
        val result = controller.evaluate(
            ActionRequest(1, ActionSource.AI_AGENT, RobotAction.Drive(.5f, 0f, 1_000)),
            context(1_010),
        )
        assertEquals(SafetyDecision.Rejected(SafetyDecision.Reason.SPEED_LIMIT_EXCEEDED), result)
    }

    @Test fun `bounded fresh drive is allowed`() {
        val drive = RobotAction.Drive(.2f, -.2f, 1_000)
        val result = controller.evaluate(
            ActionRequest(1, ActionSource.MANUAL, drive),
            context(1_010),
        )
        assertTrue(result is SafetyDecision.Allowed)
    }

    @Test fun `head input must be finite and normalized`() {
        val context = context(1_010)
        assertEquals(SafetyDecision.Rejected(SafetyDecision.Reason.INVALID_NUMBER), controller.evaluate(ActionRequest(1, ActionSource.MANUAL, RobotAction.SetHead(Float.NaN, 1_000)), context))
        assertEquals(SafetyDecision.Rejected(SafetyDecision.Reason.HEAD_LIMIT_EXCEEDED), controller.evaluate(ActionRequest(2, ActionSource.MANUAL, RobotAction.SetHead(1.1f, 1_000)), context))
    }

    @Test fun `unknown movement sensors fail closed while stop remains available`() {
        val result = controller.evaluate(
            ActionRequest(1, ActionSource.MANUAL, RobotAction.Drive(.1f, 0f, 1_000)),
            context(1_010, sensors = CliffContactSensorState.unknown(1)),
        )
        assertEquals(SafetyDecision.Rejected(SafetyDecision.Reason.SENSOR_STATE_UNSAFE), result)
    }

    private fun context(
        now: Long,
        connected: Boolean = true,
        emergencyStopLatched: Boolean = false,
        sensors: CliffContactSensorState = CliffContactStateReducer.observe(
            CliffContactSensorState.unknown(1),
            generation = 1,
            observedAtElapsedMs = 1_000,
            triggered = emptySet(),
        ),
    ) = SafetyContext(now, connected, 1, emergencyStopLatched, sensors)
}
