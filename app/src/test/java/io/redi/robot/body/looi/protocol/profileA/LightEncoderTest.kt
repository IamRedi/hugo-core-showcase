package io.redi.robot.body.looi.protocol.profileA

import io.redi.robot.core.RobotAction
import io.redi.robot.safety.SafetyDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightEncoderTest {
    @Test fun `off is FED2 zero and only configured minimum on is exposed`() {
        val encoder = ProfileALightEncoder(ProfileALightExperimentConfiguration(true, minimumOnByte = 3))
        assertEquals(ProfileALightEncodingResult.Encoded("FED2", listOf(0)), encoder.encode(allowed(0f)))
        assertEquals(ProfileALightEncodingResult.Encoded("FED2", listOf(3)), encoder.encode(allowed(1f)))
        assertTrue(encoder.encode(allowed(.5f)) is ProfileALightEncodingResult.Rejected)
    }
    private fun allowed(value: Float) = SafetyDecision.Allowed(RobotAction.SetLight(value, 0))
}
