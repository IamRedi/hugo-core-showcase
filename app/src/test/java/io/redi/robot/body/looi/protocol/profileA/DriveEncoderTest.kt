package io.redi.robot.body.looi.protocol.profileA

import io.redi.robot.core.RobotAction
import io.redi.robot.safety.SafetyDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveEncoderTest {
    private val configuration = ProfileADriveExperimentConfiguration.conservativeCandidate().copy(experimentalEnabled = true)
    private val encoder = ProfileADriveEncoder(configuration)

    @Test
    fun `golden candidate vectors encode speed and turn with explicit symmetric rounding`() {
        val vectors = listOf(
            Triple(0f, 0f, listOf(0, 0)),
            Triple(0.20f, 0f, listOf(16, 0)),
            Triple(-0.20f, 0f, listOf(-16, 0)),
            Triple(0f, 0.20f, listOf(0, 16)),
            Triple(0f, -0.20f, listOf(0, -16)),
            Triple(0.10f, -0.10f, listOf(8, -8)),
        )

        vectors.forEach { (linear, angular, expected) ->
            val result = encoder.encode(approvedDrive(linear, angular)) as ProfileADriveEncodingResult.Encoded
            assertEquals("FED0", result.frame.characteristicShortId)
            assertEquals(expected, result.frame.payload.map(Byte::toInt))
        }
    }

    @Test
    fun `all normalized boundaries clamp to conservative signed cap and never use FF02`() {
        listOf(-1f, -0.20f, 0f, 0.20f, 1f).forEach { linear ->
            listOf(-1f, -0.20f, 0f, 0.20f, 1f).forEach { angular ->
                val result = encoder.encode(approvedDrive(linear, angular)) as ProfileADriveEncodingResult.Encoded
                assertEquals("FED0", result.frame.characteristicShortId)
                assertTrue(result.frame.payload.all { it.toInt() in -16..16 })
            }
        }
    }

    @Test
    fun `encoder requires explicit experiment enablement and safety approved drive`() {
        val disabled = ProfileADriveEncoder(ProfileADriveExperimentConfiguration.conservativeCandidate())
        assertEquals(
            ProfileADriveEncodingResult.Rejected(ProfileADriveEncodingRejection.EXPERIMENT_DISABLED),
            disabled.encode(approvedDrive(0f, 0f)),
        )
        assertEquals(
            ProfileADriveEncodingResult.Rejected(ProfileADriveEncodingRejection.SAFETY_APPROVAL_REQUIRED),
            encoder.encode(SafetyDecision.Rejected(SafetyDecision.Reason.SENSOR_STATE_UNSAFE)),
        )
    }

    @Test
    fun `non finite values are rejected even if an invalid approval is constructed`() {
        assertEquals(
            ProfileADriveEncodingResult.Rejected(ProfileADriveEncodingRejection.INVALID_NUMBER),
            encoder.encode(approvedDrive(Float.NaN, 0f)),
        )
    }

    @Test
    fun `candidate polarity is explicit and testable`() {
        val inverted = ProfileADriveEncoder(configuration.copy(angularPolarity = ProfileAAxisPolarity.INVERTED))
        val result = inverted.encode(approvedDrive(0.20f, 0.20f)) as ProfileADriveEncodingResult.Encoded
        assertEquals(listOf(16, -16), result.frame.payload.map(Byte::toInt))
    }

    private fun approvedDrive(linear: Float, angular: Float): SafetyDecision.Allowed =
        SafetyDecision.Allowed(RobotAction.Drive(linear, angular, issuedAtElapsedMs = 0))
}
