package io.redi.robot.firstlife.runtime

import io.redi.robot.firstlife.orientation.FirstLifeOrientationModel
import io.redi.robot.firstlife.orientation.OrientationAttemptId
import io.redi.robot.firstlife.orientation.OrientationEffect
import io.redi.robot.firstlife.orientation.OrientationEffectOutcome
import io.redi.robot.firstlife.orientation.OrientationPhase
import io.redi.robot.firstlife.orientation.OrientationPrerequisites
import io.redi.robot.firstlife.orientation.OrientationTerminalReason
import io.redi.robot.firstlife.vision.CameraGeneration
import io.redi.robot.firstlife.vision.CameraSessionRequestId
import io.redi.robot.firstlife.vision.NormalizedBoundingBox
import io.redi.robot.firstlife.vision.PersonDirection
import io.redi.robot.firstlife.vision.PersonDirectionEvidence
import io.redi.robot.firstlife.vision.PersonEvidenceReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLifeOrientationSessionCoordinatorTest {
    private var nowMs = 0L
    private val effects = mutableListOf<OrientationEffect>()
    private var ended = 0

    @Test
    fun `virtual time executes three bounded pulses with mandatory confirmed stop and cooldown`() {
        val coordinator = coordinator()
        coordinator.begin(prerequisites())

        listOf(50L, 100L, 350L, 400L, 450L, 700L, 750L, 800L).forEach {
            nowMs = it
            coordinator.tick(prerequisites())
        }

        assertEquals(OrientationPhase.COMPLETED, coordinator.model.phase)
        assertEquals(OrientationTerminalReason.PULSE_BUDGET_EXHAUSTED, coordinator.model.terminalReason)
        assertEquals(3, coordinator.model.pulseCount)
        assertEquals(300L, coordinator.model.cumulativeEnergizedMs)
        assertEquals(6, effects.count { it is OrientationEffect.ProposeTurn })
        assertEquals(3, effects.count { it is OrientationEffect.RequestStop })
        assertEquals(1, ended)
    }

    @Test
    fun `release and changed prerequisite synchronously stop and end hold exactly once`() {
        val coordinator = coordinator()
        coordinator.begin(prerequisites())
        nowMs = 10
        coordinator.release()

        assertEquals(OrientationPhase.COMPLETED, coordinator.model.phase)
        assertEquals(OrientationTerminalReason.USER_RELEASED, coordinator.model.terminalReason)
        assertTrue(effects.last() is OrientationEffect.RequestStop)
        assertEquals(1, ended)

        coordinator.prerequisitesChanged(prerequisites().copy(connectionCurrent = false))
        assertEquals(1, ended)
    }

    @Test
    fun `unconfirmed stop blocks and never schedules another pulse`() {
        val coordinator = coordinator { effect ->
            if (effect is OrientationEffect.RequestStop) OrientationEffectOutcome.STOP_UNCONFIRMED
            else OrientationEffectOutcome.MOVE_ACCEPTED
        }
        coordinator.begin(prerequisites())
        nowMs = 10
        coordinator.background()
        val count = effects.size
        nowMs = 1_000
        coordinator.tick(prerequisites())

        assertEquals(OrientationPhase.BLOCKED, coordinator.model.phase)
        assertEquals(OrientationTerminalReason.STOP_NOT_CONFIRMED, coordinator.model.terminalReason)
        assertFalse(coordinator.model.active)
        assertEquals(count, effects.size)
    }

    @Test
    fun `centered begin ends authority without dispatching any effect`() {
        val coordinator = coordinator()
        coordinator.begin(prerequisites(PersonDirection.CENTER))

        assertEquals(FirstLifeOrientationModel.IDLE.pulseCount, coordinator.model.pulseCount)
        assertEquals(OrientationTerminalReason.CENTERED, coordinator.model.terminalReason)
        assertTrue(effects.isEmpty())
        assertEquals(1, ended)
    }

    private fun coordinator(
        outcome: (OrientationEffect) -> OrientationEffectOutcome = { effect ->
            if (effect is OrientationEffect.RequestStop) OrientationEffectOutcome.STOP_CONFIRMED
            else OrientationEffectOutcome.MOVE_ACCEPTED
        },
    ) = FirstLifeOrientationSessionCoordinator(
        execute = { effect -> effects += effect; outcome(effect) },
        nowElapsedMs = { nowMs },
        nowElapsedNanos = { nowMs * 1_000_000L },
        attemptId = { OrientationAttemptId("fl013.coordinator-attempt") },
        onAttemptEnded = { ended++ },
    )

    private fun prerequisites(direction: PersonDirection = PersonDirection.LEFT) = OrientationPrerequisites(
        bodyReady = true,
        activeBodySelected = true,
        mountingAligned = true,
        connectionCurrent = true,
        negotiationCurrent = true,
        authorityGrantCurrent = true,
        cameraSessionActive = true,
        floorModeArmed = true,
        lowerContactKnown = true,
        lowerContactFresh = true,
        lowerContactSafe = true,
        emergencyStopClear = true,
        personEvidence = PersonDirectionEvidence(
            CameraSessionRequestId("camera-fl013"), CameraGeneration(1), 1,
            0, 10_000_000_000L, direction, PersonEvidenceReason.STABLE_PERSON,
            NormalizedBoundingBox(.1f, .1f, .4f, .9f), -.4f, .9f,
        ),
    )
}
