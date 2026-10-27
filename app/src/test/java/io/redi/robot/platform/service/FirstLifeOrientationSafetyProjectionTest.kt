package io.redi.robot.platform.service

import io.redi.robot.feature.manualcontrol.T032AcceptanceState
import io.redi.robot.feature.manualcontrol.T032VerificationMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLifeOrientationSafetyProjectionTest {
    @Test
    fun `raw fresh observation is current before floor arming`() {
        assertTrue(state(streamFresh = true, mode = T032VerificationMode.DISARMED)
            .hasCurrentLowerContactEvidenceForOrientation())
    }

    @Test
    fun `verified floor mode retains generation-scoped event state without inventing heartbeat`() {
        assertTrue(state(streamFresh = false, mode = T032VerificationMode.FLOOR)
            .hasCurrentLowerContactEvidenceForOrientation())
    }

    @Test
    fun `subscription generation safety and transport losses fail closed`() {
        val current = state(streamFresh = false, mode = T032VerificationMode.FLOOR)
        assertFalse(current.copy(generation = 0).hasCurrentLowerContactEvidenceForOrientation())
        assertFalse(current.copy(prerequisiteReady = false).hasCurrentLowerContactEvidenceForOrientation())
        assertFalse(current.copy(fed9Active = false).hasCurrentLowerContactEvidenceForOrientation())
        assertFalse(current.copy(sensorStateKnown = false).hasCurrentLowerContactEvidenceForOrientation())
        assertFalse(current.copy(mode = T032VerificationMode.DISARMED).hasCurrentLowerContactEvidenceForOrientation())
        assertFalse(current.copy(emergencyStopLatched = true).hasCurrentLowerContactEvidenceForOrientation())
    }

    @Test
    fun `current hazard remains current evidence but never becomes safe`() {
        val hazard = state(streamFresh = false, mode = T032VerificationMode.FLOOR).copy(lowerContactSafe = false)
        assertTrue(hazard.hasCurrentLowerContactEvidenceForOrientation())
        assertFalse(hazard.lowerContactSafe == true)
    }

    private fun state(streamFresh: Boolean, mode: T032VerificationMode) = T032AcceptanceState(
        generation = 7,
        prerequisiteReady = true,
        fed9Active = true,
        sensorStateKnown = true,
        streamFresh = streamFresh,
        lowerContactSafe = true,
        mode = mode,
        raisedWheelVerified = true,
    )
}
