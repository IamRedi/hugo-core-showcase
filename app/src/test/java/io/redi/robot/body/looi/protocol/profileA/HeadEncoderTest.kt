package io.redi.robot.body.looi.protocol.profileA

import io.redi.robot.core.RobotAction
import io.redi.robot.safety.SafetyDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadEncoderTest {
    private val encoder = ProfileAHeadEncoder(ProfileAHeadExperimentConfiguration(true, 0x20, 0x5A, 0xC0, 100))
    @Test fun `golden bytes use configured candidate center`() {
        assertEquals(ProfileAHeadEncodingResult.Encoded("FED1", listOf(0x5A)), encoder.encode(approved(0f)))
        assertEquals(ProfileAHeadEncodingResult.Encoded("FED1", listOf(0x20)), encoder.encode(approved(-1f)))
        assertEquals(ProfileAHeadEncodingResult.Encoded("FED1", listOf(0xC0)), encoder.encode(approved(1f)))
    }
    @Test fun `invalid input is rejected and refresh is rate limited`() {
        assertTrue(encoder.encode(approved(Float.NaN)) is ProfileAHeadEncodingResult.Rejected)
        val limiter = ProfileAHeadRateLimiter(100)
        assertTrue(limiter.accept(0)); assertFalse(limiter.accept(99)); assertTrue(limiter.accept(100))
    }
    private fun approved(value: Float) = SafetyDecision.Allowed(RobotAction.SetHead(value, 0))
}
