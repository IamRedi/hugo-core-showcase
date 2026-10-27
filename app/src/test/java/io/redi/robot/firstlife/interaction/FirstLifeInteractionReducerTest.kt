package io.redi.robot.firstlife.interaction

import io.redi.robot.hugo.identity.HugoIdentity
import io.redi.robot.hugo.identity.HugoIdentityId
import java.io.Serializable
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLifeInteractionReducerTest {
    private val identity = HugoIdentity(HugoIdentityId.fromUuid(UUID.fromString("86c08ca5-0256-46ba-a89f-31b77c533f5b")))
    private val session = session("36fd8efc-6e3d-40e9-9d30-3981a5769a28")

    @Test
    fun normalFullScriptedFlowIsCorrelatedAndDeterministic() {
        var model = ready()

        val introduction = reduce(model, FirstLifeInteractionEvent.StartIntroduction(session, seq(1), at(10)))
        model = introduction.model
        val introRequest = model.activeRequest!!.reference
        assertEquals(FirstLifeInteractionState.INTRODUCTION_READY, model.state)
        assertEquals(
            FirstLifeInteractionEffect.SpeakText(introRequest, FirstLifeDialogue.introduction()),
            introduction.effects.first(),
        )
        assertEquals("Përshëndetje. Unë jam HUGO. Kush je ti?", FirstLifeDialogue.INTRODUCTION)

        model = reduce(model, FirstLifeInteractionEvent.SpeechStarted(session, seq(2), at(20), introRequest)).model
        assertEquals(FirstLifeInteractionState.INTRO_SPEAKING, model.state)
        assertEquals(FirstLifePresentationIntent.SPEAKING, FirstLifePresentationProjection.project(model))

        model = reduce(model, FirstLifeInteractionEvent.SpeechCompleted(session, seq(3), at(30), introRequest)).model
        assertEquals(FirstLifeInteractionState.AWAITING_LISTEN_START, model.state)

        val requested = reduce(model, FirstLifeInteractionEvent.RequestNameInput(session, seq(4), at(40), at(400)))
        model = requested.model
        val nameRequest = model.activeRequest!!.reference
        assertEquals(FirstLifeInteractionState.NAME_INPUT_REQUESTED, model.state)
        assertEquals(FirstLifeInteractionEffect.RequestNameInput(nameRequest, at(400)), requested.effects.first())

        model = reduce(model, FirstLifeInteractionEvent.NameInputStarted(session, seq(5), at(50), nameRequest)).model
        assertEquals(FirstLifeInteractionState.LISTENING, model.state)
        assertEquals(FirstLifePresentationIntent.LISTENING, FirstLifePresentationProjection.project(model))

        val accepted = reduce(
            model,
            FirstLifeInteractionEvent.NameInputCompleted(
                session,
                seq(6),
                at(60),
                nameRequest,
                FirstLifeNameInputResult.Success("  Redi  ", FirstLifeNameInputSource.ON_DEVICE_STT),
            ),
        )
        model = accepted.model
        val greetingRequest = model.activeRequest!!.reference
        assertEquals(FirstLifeInteractionState.GREETING_READY, model.state)
        assertEquals("Redi", model.sessionName!!.value)
        assertTrue(accepted.effects.contains(FirstLifeInteractionEffect.CancelNameInput(nameRequest)))
        assertTrue(
            accepted.effects.contains(
                FirstLifeInteractionEffect.SpeakText(
                    greetingRequest,
                    FirstLifeSpeechText(FirstLifeTextKey.NAME_GREETING_SQ_AL, "Gëzohem që të njoh, Redi."),
                ),
            ),
        )
        assertEquals(FirstLifePresentationIntent.THINKING, FirstLifePresentationProjection.project(model))

        model = reduce(model, FirstLifeInteractionEvent.SpeechStarted(session, seq(7), at(70), greetingRequest)).model
        assertEquals(FirstLifeInteractionState.GREETING_SPEAKING, model.state)

        val completed = reduce(model, FirstLifeInteractionEvent.SpeechCompleted(session, seq(8), at(80), greetingRequest))
        assertEquals(FirstLifeInteractionState.READY_FOR_NEXT_PHASE, completed.model.state)
        assertEquals("Redi", completed.model.sessionName!!.value)
        assertTrue(completed.effects.contains(FirstLifeInteractionEffect.ProceedToObservationPhase(session)))
    }

    @Test
    fun conservativeNameValidationAcceptsSimpleUnicodeNamesAndRejectsMalformedPayloads() {
        val accepted = listOf("Redi", "Ana Maria", "Jean-Luc", "O'Connor", "D’Angelo", "Ëndrra")
        accepted.forEach { assertTrue("expected valid: $it", FirstLifeNameValidator.validate(it) is FirstLifeNameValidation.Valid) }

        val invalid = listOf("", "   ", "Redi7", "<Redi>", "Redi\nOther", "one two three four", "-Redi", "Redi--Ana")
        invalid.forEach { assertTrue("expected invalid: $it", FirstLifeNameValidator.validate(it) is FirstLifeNameValidation.Invalid) }
        assertTrue(
            FirstLifeNameValidator.validate("A".repeat(41)) ==
                FirstLifeNameValidation.Invalid(FirstLifeNameInvalidReason.TOO_LONG),
        )
    }

    @Test
    fun invalidNameRequiresExplicitFreshSessionRetryAndClearsTemporaryData() {
        val listening = listening()
        val invalid = reduce(
            listening.model,
            FirstLifeInteractionEvent.NameInputCompleted(
                session,
                seq(6),
                at(60),
                listening.request,
                FirstLifeNameInputResult.Success("not-a-name-7", FirstLifeNameInputSource.ON_DEVICE_STT),
            ),
        )
        assertEquals(FirstLifeInteractionState.BLOCKED, invalid.model.state)
        assertEquals(FirstLifeBlockedReason.INVALID_NAME, invalid.model.blockedReason)
        assertNull(invalid.model.sessionName)
        assertTrue(invalid.effects.contains(FirstLifeInteractionEffect.NameRetryRequired(FirstLifeBlockedReason.INVALID_NAME)))

        val newSession = session("3ad1c8bd-25e7-47e9-a871-931cc0d84430")
        val reset = reduce(
            invalid.model,
            FirstLifeInteractionEvent.ResetSession(session, seq(7), at(70), newSession),
        )
        assertEquals(newSession, reset.model.sessionId)
        assertEquals(FirstLifeInteractionState.READY, reset.model.state)
        assertNull(reset.model.sessionName)
        assertSame(identity, reset.model.hugoIdentity)
    }

    @Test
    fun deliberateSessionResetClearsAnAcceptedNameButPreservesHugoIdentity() {
        val named = acceptedName(startSequence = 1)
        assertEquals("Redi", named.sessionName!!.value)
        val newSession = session("e38ef170-2e13-4bb7-b474-3014823b368b")

        val reset = reduce(
            named,
            FirstLifeInteractionEvent.ResetSession(session, seq(7), at(70), newSession),
        )

        assertNull(reset.model.sessionName)
        assertSame(identity, reset.model.hugoIdentity)
        assertEquals(newSession, reset.model.sessionId)
        assertEquals(FirstLifeInteractionState.READY, reset.model.state)
    }

    @Test
    fun noSpeechAmbiguityCancellationAndTimeoutAreExplicitRecoverableOutcomes() {
        val cases = listOf(
            FirstLifeNameInputResult.NoSpeech to FirstLifeBlockedReason.NO_SPEECH,
            FirstLifeNameInputResult.Ambiguous to FirstLifeBlockedReason.AMBIGUOUS_NAME,
            FirstLifeNameInputResult.Cancelled to FirstLifeBlockedReason.NAME_INPUT_CANCELLED,
            FirstLifeNameInputResult.TimedOut to FirstLifeBlockedReason.NAME_INPUT_TIMEOUT,
        )
        cases.forEach { (input, reason) ->
            val listening = listening()
            val occurredAt = if (input == FirstLifeNameInputResult.TimedOut) at(500) else at(60)
            val result = reduce(
                listening.model,
                FirstLifeInteractionEvent.NameInputCompleted(session, seq(6), occurredAt, listening.request, input),
            )
            assertEquals(reason, result.model.blockedReason)
            assertEquals(FirstLifePresentationIntent.BLOCKED, FirstLifePresentationProjection.project(result.model))
            assertTrue(result.effects.contains(FirstLifeInteractionEffect.NameRetryRequired(reason)))
        }
    }

    @Test
    fun deadlineEventCancelsCaptureOnlyAtOrAfterDeadline() {
        val listening = listening(deadline = 100)
        val early = reduce(
            listening.model,
            FirstLifeInteractionEvent.NameInputDeadlineElapsed(session, seq(6), at(99), listening.request),
        )
        assertEquals(FirstLifeReductionStatus.IGNORED_LATE, early.status)
        assertEquals(listening.model, early.model)

        val timedOut = reduce(
            listening.model,
            FirstLifeInteractionEvent.NameInputDeadlineElapsed(session, seq(6), at(100), listening.request),
        )
        assertEquals(FirstLifeBlockedReason.NAME_INPUT_TIMEOUT, timedOut.model.blockedReason)
        assertTrue(timedOut.effects.contains(FirstLifeInteractionEffect.CancelNameInput(listening.request)))
    }

    @Test
    fun manualFallbackProducesTheSameValidatedSessionNameWithoutPersistence() {
        val listening = listening()
        val result = reduce(
            listening.model,
            FirstLifeInteractionEvent.NameInputCompleted(
                session,
                seq(6),
                at(60),
                listening.request,
                FirstLifeNameInputResult.Success("Arta", FirstLifeNameInputSource.MANUAL_FALLBACK),
            ),
        )

        assertEquals("Arta", result.model.sessionName!!.value)
        assertFalse(result.model.javaClass.interfaces.contains(Serializable::class.java))
        val fieldNames = result.model.javaClass.declaredFields.map { it.name.lowercase() }
        listOf("storage", "repository", "database", "transcript", "persist").forEach { forbidden ->
            assertFalse(fieldNames.any { it.contains(forbidden) })
        }
    }

    @Test
    fun bodyUnavailableDoesNotPreventIntroductionAndBodyLossDoesNotEraseNameOrIdentity() {
        var model = ready()
        val bodyUnavailable = reduce(
            model,
            FirstLifeInteractionEvent.BodyStatusChanged(session, seq(1), at(10), FirstLifeBodyStatus.LOST),
        )
        model = bodyUnavailable.model
        assertEquals(FirstLifeInteractionState.READY, model.state)
        assertEquals(FirstLifePresentationIntent.IDLE, FirstLifePresentationProjection.project(model))

        val intro = reduce(model, FirstLifeInteractionEvent.StartIntroduction(session, seq(2), at(20)))
        assertEquals(FirstLifeInteractionState.INTRODUCTION_READY, intro.model.state)

        val named = acceptedName(startSequence = 2)
        val available = reduce(
            named,
            FirstLifeInteractionEvent.BodyStatusChanged(session, seq(8), at(80), FirstLifeBodyStatus.AVAILABLE),
        ).model
        val lost = reduce(
            available,
            FirstLifeInteractionEvent.BodyStatusChanged(session, seq(9), at(90), FirstLifeBodyStatus.LOST),
        ).model
        assertEquals(named.sessionName, lost.sessionName)
        assertSame(identity, lost.hugoIdentity)
        assertEquals(named.state, lost.state)
    }

    @Test
    fun providerBlockedIsAStatusFactAndCannotBlockHugoIntroduction() {
        val changed = reduce(
            ready(),
            FirstLifeInteractionEvent.BodyStatusChanged(session, seq(1), at(10), FirstLifeBodyStatus.PROVIDER_BLOCKED),
        )
        val started = reduce(changed.model, FirstLifeInteractionEvent.StartIntroduction(session, seq(2), at(20)))

        assertEquals(FirstLifeBodyStatus.PROVIDER_BLOCKED, started.model.bodyStatus)
        assertEquals(FirstLifeInteractionState.INTRODUCTION_READY, started.model.state)
        assertTrue(started.effects.any { it is FirstLifeInteractionEffect.SpeakText })
    }

    @Test
    fun staleWrongSessionDuplicateWrongRequestAndLateEventsNeverAdvance() {
        val initial = ready()
        val wrongSessionEvent = FirstLifeInteractionEvent.StartIntroduction(
            session("2c060c1e-eb6f-4dd9-a3bc-b2faf8073e9b"),
            seq(1),
            at(10),
        )
        assertIgnored(initial, wrongSessionEvent, FirstLifeReductionStatus.IGNORED_WRONG_SESSION)

        val startEvent = FirstLifeInteractionEvent.StartIntroduction(session, seq(1), at(10))
        val started = reduce(initial, startEvent)
        assertIgnored(started.model, startEvent, FirstLifeReductionStatus.IGNORED_STALE)
        assertIgnored(
            started.model,
            FirstLifeInteractionEvent.SpeechStarted(session, seq(1), at(20), started.model.activeRequest!!.reference),
            FirstLifeReductionStatus.IGNORED_STALE,
        )
        assertIgnored(
            started.model,
            FirstLifeInteractionEvent.SpeechStarted(session, seq(2), at(9), started.model.activeRequest.reference),
            FirstLifeReductionStatus.IGNORED_STALE,
        )

        val wrongRequest = FirstLifeRequestReference(session, FirstLifeRequestKind.GREETING_SPEECH, 99)
        assertIgnored(
            started.model,
            FirstLifeInteractionEvent.SpeechStarted(session, seq(2), at(20), wrongRequest),
            FirstLifeReductionStatus.IGNORED_WRONG_REQUEST,
        )

        val listening = listening(deadline = 100)
        assertIgnored(
            listening.model,
            FirstLifeInteractionEvent.NameInputCompleted(
                session,
                seq(6),
                at(101),
                listening.request,
                FirstLifeNameInputResult.Success("Redi", FirstLifeNameInputSource.ON_DEVICE_STT),
            ),
            FirstLifeReductionStatus.IGNORED_LATE,
        )
    }

    @Test
    fun speakingAndListeningCancellationEmitCleanupExactlyOnceAndAreIdempotent() {
        var speaking = ready()
        speaking = reduce(speaking, FirstLifeInteractionEvent.StartIntroduction(session, seq(1), at(10))).model
        val speechRequest = speaking.activeRequest!!.reference
        speaking = reduce(speaking, FirstLifeInteractionEvent.SpeechStarted(session, seq(2), at(20), speechRequest)).model

        val cancelledSpeech = reduce(speaking, FirstLifeInteractionEvent.CancelSession(session, seq(3), at(30)))
        assertEquals(FirstLifeInteractionState.CANCELLING, cancelledSpeech.model.state)
        assertEquals(listOf(FirstLifeInteractionEffect.StopSpeaking(speechRequest)), cancelledSpeech.effects.filterIsInstance<FirstLifeInteractionEffect.StopSpeaking>())
        assertNull(cancelledSpeech.model.sessionName)

        val repeated = reduce(cancelledSpeech.model, FirstLifeInteractionEvent.CancelSession(session, seq(4), at(40)))
        assertEquals(FirstLifeReductionStatus.IDEMPOTENT, repeated.status)
        assertTrue(repeated.effects.isEmpty())
        val completed = reduce(repeated.model, FirstLifeInteractionEvent.CancellationCompleted(session, seq(5), at(50)))
        assertEquals(FirstLifeInteractionState.READY, completed.model.state)

        val listening = listening()
        val cancelledInput = reduce(listening.model, FirstLifeInteractionEvent.CancelSession(session, seq(6), at(60)))
        assertEquals(listOf(FirstLifeInteractionEffect.CancelNameInput(listening.request)), cancelledInput.effects.filterIsInstance<FirstLifeInteractionEffect.CancelNameInput>())
    }

    @Test
    fun recoverableFailureAndFatalFailureRemainDistinct() {
        val speakingReady = reduce(ready(), FirstLifeInteractionEvent.StartIntroduction(session, seq(1), at(10))).model
        val request = speakingReady.activeRequest!!.reference
        val recoverable = reduce(
            speakingReady,
            FirstLifeInteractionEvent.SpeechUnavailable(session, seq(2), at(20), request),
        )
        assertEquals(FirstLifeInteractionState.BLOCKED, recoverable.model.state)
        assertEquals(FirstLifeBlockedReason.SPEECH_OUTPUT_UNAVAILABLE, recoverable.model.blockedReason)
        assertEquals(FirstLifePresentationIntent.BLOCKED, FirstLifePresentationProjection.project(recoverable.model))

        val fatal = reduce(
            speakingReady,
            FirstLifeInteractionEvent.FatalFailure(session, seq(2), at(20)),
        )
        assertEquals(FirstLifeInteractionState.FAILED, fatal.model.state)
        assertEquals(FirstLifeFatalReason.UNRECOVERABLE_INTERACTION_ERROR, fatal.model.fatalReason)
        assertNull(fatal.model.sessionName)
        assertEquals(FirstLifePresentationIntent.ERROR, FirstLifePresentationProjection.project(fatal.model))
    }

    @Test
    fun introductionCancellationAndInvalidInputDeadlineFailHonestly() {
        val introductionReady = reduce(
            ready(),
            FirstLifeInteractionEvent.StartIntroduction(session, seq(1), at(10)),
        ).model
        val request = introductionReady.activeRequest!!.reference
        val cancelled = reduce(
            introductionReady,
            FirstLifeInteractionEvent.SpeechCancelled(session, seq(2), at(20), request),
        )
        assertEquals(FirstLifeBlockedReason.INTRODUCTION_CANCELLED, cancelled.model.blockedReason)
        assertEquals(FirstLifePresentationIntent.BLOCKED, FirstLifePresentationProjection.project(cancelled.model))

        var awaiting = reduce(ready(), FirstLifeInteractionEvent.StartIntroduction(session, seq(1), at(10))).model
        val awaitingRequest = awaiting.activeRequest!!.reference
        awaiting = reduce(awaiting, FirstLifeInteractionEvent.SpeechStarted(session, seq(2), at(20), awaitingRequest)).model
        awaiting = reduce(awaiting, FirstLifeInteractionEvent.SpeechCompleted(session, seq(3), at(30), awaitingRequest)).model
        val invalidDeadline = reduce(
            awaiting,
            FirstLifeInteractionEvent.RequestNameInput(session, seq(4), at(40), at(40)),
        )
        assertEquals(FirstLifeReductionStatus.IGNORED_LATE, invalidDeadline.status)
        assertEquals(awaiting, invalidDeadline.model)
        assertTrue(invalidDeadline.effects.isEmpty())
    }

    @Test
    fun presentationProjectionUsesFactsWithoutDuplicatingVisualSpecifications() {
        val initial = ready()
        assertEquals(FirstLifePresentationIntent.IDLE, FirstLifePresentationProjection.project(initial))
        val bodyReady = reduce(
            initial,
            FirstLifeInteractionEvent.BodyStatusChanged(session, seq(1), at(10), FirstLifeBodyStatus.AVAILABLE),
        ).model
        assertEquals(FirstLifePresentationIntent.BODY_READY, FirstLifePresentationProjection.project(bodyReady))

        val interactionFieldTypes = FirstLifePresentationIntent::class.java.declaredFields.map { it.type.name }
        assertFalse(interactionFieldTypes.any { it.contains("HiHugoVisualSpec") || it.contains("android") })
    }

    @Test
    fun identityUnavailableStartsFailClosedAndResetCannotInventOrReplaceIdentity() {
        val unavailable = FirstLifeInteractionModel.start(null, session, at(0))
        assertEquals(FirstLifeInteractionState.BLOCKED, unavailable.state)
        assertEquals(FirstLifeBlockedReason.IDENTITY_UNAVAILABLE, unavailable.blockedReason)
        assertIgnored(
            unavailable,
            FirstLifeInteractionEvent.StartIntroduction(session, seq(1), at(10)),
            FirstLifeReductionStatus.IGNORED_INVALID_STATE,
        )

        val newSession = session("25ab18e4-6785-4e5b-b44d-07a6113d9fb3")
        val reset = reduce(unavailable, FirstLifeInteractionEvent.ResetSession(session, seq(1), at(10), newSession))
        assertNull(reset.model.hugoIdentity)
        assertEquals(FirstLifeBlockedReason.IDENTITY_UNAVAILABLE, reset.model.blockedReason)

        val readyReset = reduce(
            ready(),
            FirstLifeInteractionEvent.ResetSession(session, seq(1), at(10), newSession),
        )
        assertSame(identity, readyReset.model.hugoIdentity)
        assertNotEquals(session, readyReset.model.sessionId)
    }

    private fun ready(): FirstLifeInteractionModel = FirstLifeInteractionModel.start(identity, session, at(0))

    private data class ListeningFixture(
        val model: FirstLifeInteractionModel,
        val request: FirstLifeRequestReference,
    )

    private fun listening(deadline: Long = 400): ListeningFixture {
        var model = ready()
        model = reduce(model, FirstLifeInteractionEvent.StartIntroduction(session, seq(1), at(10))).model
        val intro = model.activeRequest!!.reference
        model = reduce(model, FirstLifeInteractionEvent.SpeechStarted(session, seq(2), at(20), intro)).model
        model = reduce(model, FirstLifeInteractionEvent.SpeechCompleted(session, seq(3), at(30), intro)).model
        model = reduce(model, FirstLifeInteractionEvent.RequestNameInput(session, seq(4), at(40), at(deadline))).model
        val request = model.activeRequest!!.reference
        model = reduce(model, FirstLifeInteractionEvent.NameInputStarted(session, seq(5), at(50), request)).model
        return ListeningFixture(model, request)
    }

    private fun acceptedName(startSequence: Long): FirstLifeInteractionModel {
        var model = ready()
        model = reduce(model, FirstLifeInteractionEvent.StartIntroduction(session, seq(startSequence), at(startSequence * 10))).model
        val intro = model.activeRequest!!.reference
        model = reduce(model, FirstLifeInteractionEvent.SpeechStarted(session, seq(startSequence + 1), at((startSequence + 1) * 10), intro)).model
        model = reduce(model, FirstLifeInteractionEvent.SpeechCompleted(session, seq(startSequence + 2), at((startSequence + 2) * 10), intro)).model
        model = reduce(model, FirstLifeInteractionEvent.RequestNameInput(session, seq(startSequence + 3), at((startSequence + 3) * 10), at(500))).model
        val request = model.activeRequest!!.reference
        model = reduce(model, FirstLifeInteractionEvent.NameInputStarted(session, seq(startSequence + 4), at((startSequence + 4) * 10), request)).model
        return reduce(
            model,
            FirstLifeInteractionEvent.NameInputCompleted(
                session,
                seq(startSequence + 5),
                at((startSequence + 5) * 10),
                request,
                FirstLifeNameInputResult.Success("Redi", FirstLifeNameInputSource.ON_DEVICE_STT),
            ),
        ).model
    }

    private fun assertIgnored(
        model: FirstLifeInteractionModel,
        event: FirstLifeInteractionEvent,
        expected: FirstLifeReductionStatus,
    ) {
        val result = reduce(model, event)
        assertEquals(expected, result.status)
        assertEquals(model, result.model)
        assertTrue(result.effects.isEmpty())
    }

    private fun reduce(model: FirstLifeInteractionModel, event: FirstLifeInteractionEvent): FirstLifeInteractionReduction =
        FirstLifeInteractionReducer.reduce(model, event)

    private fun seq(value: Long) = FirstLifeEventSequence(value)
    private fun at(value: Long) = FirstLifeMonotonicTimeMs(value)
    private fun session(value: String) = FirstLifeSessionId.fromUuid(UUID.fromString(value))
}
