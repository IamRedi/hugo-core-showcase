package io.redi.robot.firstlife.orientation

import io.redi.robot.firstlife.vision.CameraGeneration
import io.redi.robot.firstlife.vision.CameraSessionRequestId
import io.redi.robot.firstlife.vision.NormalizedBoundingBox
import io.redi.robot.firstlife.vision.PersonDirection
import io.redi.robot.firstlife.vision.PersonDirectionEvidence
import io.redi.robot.firstlife.vision.PersonEvidenceReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLifeOrientationControllerTest {
    private val controller = FirstLifeOrientationController()
    private val attempt = OrientationAttemptId("fl013.attempt-1")

    @Test
    fun `centered evidence completes without movement or stop`() {
        val transition = begin(direction = PersonDirection.CENTER)

        assertEquals(OrientationPhase.COMPLETED, transition.model.phase)
        assertEquals(OrientationTerminalReason.CENTERED, transition.model.terminalReason)
        assertTrue(transition.effects.isEmpty())
        assertEquals(0, transition.model.pulseCount)
    }

    @Test
    fun `left pulse has exactly two refreshes at magnitude sixteen then mandatory stop`() {
        val first = begin()
        val firstMove = first.effects.single() as OrientationEffect.ProposeTurn
        assertEquals(OrientationTurnDirection.LEFT, firstMove.direction)
        assertEquals(16, firstMove.signedMagnitude)
        assertEquals(1, firstMove.refreshOrdinal)

        val accepted = complete(first.model, firstMove.id, OrientationEffectOutcome.MOVE_ACCEPTED, 0)
        assertEquals(50L, accepted.model.nextDeadlineElapsedMs)
        val second = tick(accepted.model, 50)
        val secondMove = second.effects.single() as OrientationEffect.ProposeTurn
        assertEquals(2, secondMove.refreshOrdinal)
        assertEquals(16, secondMove.signedMagnitude)

        val refreshed = complete(second.model, secondMove.id, OrientationEffectOutcome.MOVE_ACCEPTED, 50)
        assertEquals(100L, refreshed.model.nextDeadlineElapsedMs)
        val stop = tick(refreshed.model, 100)
        assertTrue(stop.effects.single() is OrientationEffect.RequestStop)
        assertEquals(100, stop.model.cumulativeEnergizedMs)
    }

    @Test
    fun `three pulses enforce cooldown and three hundred millisecond hard budget`() {
        var transition = begin()
        repeat(3) { pulseIndex ->
            val first = transition.effects.single() as OrientationEffect.ProposeTurn
            transition = complete(transition.model, first.id, OrientationEffectOutcome.MOVE_ACCEPTED, pulseIndex * 350L)
            transition = tick(transition.model, pulseIndex * 350L + 50)
            val second = transition.effects.single() as OrientationEffect.ProposeTurn
            transition = complete(transition.model, second.id, OrientationEffectOutcome.MOVE_ACCEPTED, pulseIndex * 350L + 50)
            transition = tick(transition.model, pulseIndex * 350L + 100)
            val stop = transition.effects.single() as OrientationEffect.RequestStop
            transition = complete(transition.model, stop.id, OrientationEffectOutcome.STOP_CONFIRMED, pulseIndex * 350L + 100)
            if (pulseIndex < 2) {
                assertEquals(OrientationPhase.COOLDOWN, transition.model.phase)
                assertEquals(pulseIndex * 350L + 350, transition.model.nextDeadlineElapsedMs)
                transition = tick(transition.model, pulseIndex * 350L + 350)
            }
        }

        assertEquals(OrientationPhase.COMPLETED, transition.model.phase)
        assertEquals(OrientationTerminalReason.PULSE_BUDGET_EXHAUSTED, transition.model.terminalReason)
        assertEquals(3, transition.model.pulseCount)
        assertEquals(300, transition.model.cumulativeEnergizedMs)
        assertTrue(transition.effects.isEmpty())
    }

    @Test
    fun `release background emergency and every prerequisite loss request terminal stop`() {
        val active = acceptedMove().model
        val terminalEvents = listOf<OrientationEvent>(
            OrientationEvent.UserReleased(10, nanos(10)),
            OrientationEvent.UserCancelled(10, nanos(10)),
            OrientationEvent.Backgrounded(10, nanos(10)),
            OrientationEvent.EmergencyStop(10, nanos(10)),
        )
        terminalEvents.forEach { event ->
            assertTrue(controller.reduce(active, event).effects.single() is OrientationEffect.RequestStop)
        }

        val losses = listOf(
            prerequisites().copy(bodyReady = false),
            prerequisites().copy(activeBodySelected = false),
            prerequisites().copy(mountingAligned = false),
            prerequisites().copy(connectionCurrent = false),
            prerequisites().copy(negotiationCurrent = false),
            prerequisites().copy(authorityGrantCurrent = false),
            prerequisites().copy(cameraSessionActive = false),
            prerequisites().copy(floorModeArmed = false),
            prerequisites().copy(lowerContactKnown = false),
            prerequisites().copy(lowerContactFresh = false),
            prerequisites().copy(lowerContactSafe = false),
            prerequisites().copy(emergencyStopClear = false),
            prerequisites().copy(personEvidence = null),
            prerequisites().copy(personEvidence = evidence(PersonDirection.UNKNOWN, PersonEvidenceReason.MULTIPLE_PEOPLE)),
        )
        losses.forEach { loss ->
            val transition = controller.reduce(active, OrientationEvent.PrerequisitesChanged(10, nanos(10), loss))
            assertTrue("loss=$loss", transition.effects.single() is OrientationEffect.RequestStop)
        }
    }

    @Test
    fun `stale evidence ambiguity and missing grant never propose movement`() {
        val stale = begin(prerequisites = prerequisites().copy(
            personEvidence = evidence(PersonDirection.LEFT, expiresAtNanos = 1),
        ), nowMs = 2)
        val ambiguous = begin(prerequisites = prerequisites().copy(
            personEvidence = evidence(PersonDirection.UNKNOWN, PersonEvidenceReason.MULTIPLE_PEOPLE),
        ))
        val noGrant = begin(prerequisites = prerequisites().copy(authorityGrantCurrent = false))

        listOf(stale, ambiguous, noGrant).forEach {
            assertEquals(OrientationPhase.BLOCKED, it.model.phase)
            assertTrue(it.effects.isEmpty())
        }
    }

    @Test
    fun `alternating direction and wall clock deadline stop without another turn`() {
        val active = acceptedMove().model
        val changed = controller.reduce(active, OrientationEvent.PrerequisitesChanged(
            10, nanos(10), prerequisites(PersonDirection.RIGHT, nowMs = 10),
        ))
        assertEquals(OrientationTerminalReason.DIRECTION_CHANGED,
            (changed.effects.single() as OrientationEffect.RequestStop).reason)

        val timeout = controller.reduce(active, OrientationEvent.Tick(
            FirstLifeOrientationEnvelope.MAX_ATTEMPT_WALL_CLOCK_MS,
            nanos(FirstLifeOrientationEnvelope.MAX_ATTEMPT_WALL_CLOCK_MS),
            prerequisites(nowMs = FirstLifeOrientationEnvelope.MAX_ATTEMPT_WALL_CLOCK_MS),
        ))
        assertEquals(OrientationTerminalReason.WALL_CLOCK_BUDGET_EXHAUSTED,
            (timeout.effects.single() as OrientationEffect.RequestStop).reason)
    }

    @Test
    fun `wrong effect correlation is rejected and unconfirmed stop blocks`() {
        val active = acceptedMove().model
        val stale = controller.reduce(active, OrientationEvent.EffectCompleted(
            OrientationEffectId(attempt, 99), OrientationEffectOutcome.MOVE_ACCEPTED, 10, nanos(10),
        ))
        assertTrue(stale.staleEventRejected)
        assertEquals(active, stale.model)

        val stop = controller.reduce(active, OrientationEvent.UserReleased(10, nanos(10)))
        val failed = complete(
            stop.model,
            (stop.effects.single() as OrientationEffect.RequestStop).id,
            OrientationEffectOutcome.STOP_UNCONFIRMED,
            11,
        )
        assertEquals(OrientationPhase.BLOCKED, failed.model.phase)
        assertEquals(OrientationTerminalReason.STOP_NOT_CONFIRMED, failed.model.terminalReason)
        assertFalse(failed.model.active)
        assertNull(failed.model.nextDeadlineElapsedMs)
    }

    private fun acceptedMove(): OrientationTransition {
        val begin = begin()
        val effect = begin.effects.single() as OrientationEffect.ProposeTurn
        return complete(begin.model, effect.id, OrientationEffectOutcome.MOVE_ACCEPTED, 0)
    }

    private fun begin(
        direction: PersonDirection = PersonDirection.LEFT,
        prerequisites: OrientationPrerequisites = prerequisites(direction),
        nowMs: Long = 0,
    ) = controller.reduce(
        FirstLifeOrientationModel.IDLE,
        OrientationEvent.BeginHold(attempt, nowMs, nanos(nowMs), prerequisites),
    )

    private fun tick(model: FirstLifeOrientationModel, nowMs: Long) = controller.reduce(
        model,
        OrientationEvent.Tick(nowMs, nanos(nowMs), prerequisites(nowMs = nowMs)),
    )

    private fun complete(
        model: FirstLifeOrientationModel,
        id: OrientationEffectId,
        outcome: OrientationEffectOutcome,
        nowMs: Long,
    ) = controller.reduce(
        model,
        OrientationEvent.EffectCompleted(id, outcome, nowMs, nanos(nowMs)),
    )

    private fun prerequisites(
        direction: PersonDirection = PersonDirection.LEFT,
        nowMs: Long = 0,
    ) = OrientationPrerequisites(
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
        personEvidence = evidence(direction, nowMs = nowMs),
    )

    private fun evidence(
        direction: PersonDirection,
        reason: PersonEvidenceReason = PersonEvidenceReason.STABLE_PERSON,
        nowMs: Long = 0,
        expiresAtNanos: Long = nanos(nowMs + 250),
    ) = PersonDirectionEvidence(
        CameraSessionRequestId("camera-fl013"),
        CameraGeneration(1),
        1,
        nanos(nowMs),
        expiresAtNanos,
        direction,
        reason,
        if (direction == PersonDirection.UNKNOWN) null else NormalizedBoundingBox(.2f, .1f, .4f, .9f),
        if (direction == PersonDirection.UNKNOWN) null else -.4f,
        if (direction == PersonDirection.UNKNOWN) null else .9f,
    )

    private fun nanos(ms: Long) = ms * 1_000_000L
}
