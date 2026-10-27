package io.redi.robot.firstlife.experience

import io.redi.robot.firstlife.interaction.FirstLifeBodyStatus
import io.redi.robot.firstlife.interaction.FirstLifeInteractionState
import io.redi.robot.firstlife.interaction.FirstLifeSessionId
import io.redi.robot.firstlife.interaction.FirstLifeSpeechText
import io.redi.robot.firstlife.vision.CameraGeneration
import io.redi.robot.firstlife.vision.CameraBlockedReason
import io.redi.robot.firstlife.vision.CameraLensFacing
import io.redi.robot.firstlife.vision.CameraSessionEvidence
import io.redi.robot.firstlife.vision.CameraSessionModel
import io.redi.robot.firstlife.vision.CameraSessionPhase
import io.redi.robot.firstlife.vision.CameraSessionRequest
import io.redi.robot.firstlife.vision.CameraSessionRequestId
import io.redi.robot.firstlife.vision.NormalizedBoundingBox
import io.redi.robot.firstlife.vision.PersonDirection
import io.redi.robot.firstlife.vision.PersonDirectionEvidence
import io.redi.robot.firstlife.vision.PersonEvidenceReason
import io.redi.robot.firstlife.voice.LocalSpeechText
import io.redi.robot.firstlife.voice.SpeechOutputEvidence
import io.redi.robot.firstlife.voice.SpeechOutputKind
import io.redi.robot.firstlife.voice.SpeechOutputModel
import io.redi.robot.firstlife.voice.SpeechOutputPhase
import io.redi.robot.firstlife.voice.SpeechOutputRequest
import io.redi.robot.firstlife.voice.SpeechOutputRequestId
import io.redi.robot.firstlife.voice.VoiceRecognitionEvidence
import io.redi.robot.firstlife.voice.VoiceRecognitionTier
import io.redi.robot.firstlife.voice.VoiceSessionBlockedReason
import io.redi.robot.firstlife.voice.VoiceSessionModel
import io.redi.robot.firstlife.voice.VoiceSessionPhase
import io.redi.robot.firstlife.voice.VoiceSessionRequest
import io.redi.robot.firstlife.voice.VoiceSessionRequestId
import io.redi.robot.hugo.identity.HugoIdentity
import io.redi.robot.hugo.identity.HugoIdentityId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLifeExperienceCoordinatorTest {
    private val identity = HugoIdentity(
        HugoIdentityId.fromUuid(UUID.fromString("3a1c7392-6df0-4ab1-82ba-c2200b73f678")),
    )
    private var nowMs = 10L
    private var nowNanos = 10_000_000L
    private var nextSession = 0
    private val effects = FakeEffects()
    private val snapshots = mutableListOf<FirstLifeExperienceSnapshot>()
    private val coordinator = FirstLifeExperienceCoordinator(
        identity,
        sessionIdFactory = {
            FirstLifeSessionId.fromUuid(
                UUID.fromString("00000000-0000-0000-0000-${(++nextSession).toString().padStart(12, '0')}"),
            )
        },
        nowMs = { ++nowMs },
        nowNanos = { nowNanos },
        effects = effects,
        onSnapshotChanged = snapshots::add,
    )

    @Test
    fun `full scripted flow binds FL004 speech name and explicit observation without fake success`() {
        assertEquals(FirstLifeExperienceStage.READY, coordinator.snapshot.stage)
        assertTrue(coordinator.startIntroduction())
        assertEquals(SpeechOutputKind.INTRODUCTION, effects.spoken.single().first)
        assertEquals(FirstLifeExperienceStage.INTRODUCTION, coordinator.snapshot.stage)

        coordinator.onSpeechOutputChanged(speaking(SpeechOutputKind.INTRODUCTION, 1))
        coordinator.onSpeechOutputChanged(completed(SpeechOutputKind.INTRODUCTION))
        assertEquals(FirstLifeExperienceStage.AWAITING_EXPLICIT_LISTEN, coordinator.snapshot.stage)

        assertTrue(coordinator.requestNameInput())
        assertEquals(1, effects.nameInputStarts)
        coordinator.onVoiceSessionChanged(listeningVoice())
        coordinator.onVoiceSessionChanged(completedVoice(VoiceRecognitionTier.ON_DEVICE))
        coordinator.onTranscript("  Redi  ")

        assertTrue(coordinator.snapshot.sessionNameAvailable)
        assertEquals(SpeechOutputKind.SESSION_NAME_GREETING, effects.spoken.last().first)
        assertEquals(FirstLifeExperienceStage.GREETING, coordinator.snapshot.stage)
        coordinator.onSpeechOutputChanged(speaking(SpeechOutputKind.SESSION_NAME_GREETING, 2))
        coordinator.onSpeechOutputChanged(completed(SpeechOutputKind.SESSION_NAME_GREETING))
        assertEquals(FirstLifeExperienceStage.OBSERVATION_READY, coordinator.snapshot.stage)
        assertFalse(coordinator.snapshot.centeredPersonConfirmed)

        assertTrue(coordinator.openVisibleCamera())
        coordinator.onCameraSessionChanged(observingCamera(PersonDirection.LEFT))
        assertEquals(FirstLifeExperienceStage.OBSERVING, coordinator.snapshot.stage)
        coordinator.onCameraSessionChanged(observingCamera(PersonDirection.CENTER))
        assertEquals(FirstLifeExperienceStage.PERSON_CENTERED, coordinator.snapshot.stage)
        assertTrue(coordinator.snapshot.centeredPersonConfirmed)
        assertEquals(listOf("orientation:OBSERVATION_COMPLETE", "camera:false"), effects.calls.takeLast(2))
        assertSame(identity, coordinator.model.hugoIdentity)
        assertFalse(coordinator.snapshot.toString().contains("Redi"))
        assertFalse(coordinator.snapshot.toString().contains(identity.id.value))
    }

    @Test
    fun `background cleanup is deterministic idempotent and clears session data before body authority`() {
        coordinator.startIntroduction()
        effects.calls.clear()

        assertTrue(coordinator.background())
        assertEquals(
            listOf(
                "orientation:BACKGROUND",
                "camera:true",
                "voice:true",
                "speech:true",
                "body",
            ),
            effects.calls,
        )
        assertEquals(FirstLifeExperienceStage.READY, coordinator.snapshot.stage)
        assertFalse(coordinator.snapshot.sessionNameAvailable)
        assertEquals(FirstLifeExperienceCleanupReason.BACKGROUND, coordinator.snapshot.lastCleanupReason)

        assertFalse(coordinator.background())
        assertEquals(5, effects.calls.size)
        coordinator.foreground()
        assertTrue(coordinator.background())
    }

    @Test
    fun `explicit reset changes only run session and cannot replace Hugo identity or invent completion`() {
        completeNamedGreeting()
        coordinator.bodyStatusChanged(FirstLifeBodyStatus.SELECTED)
        val oldSession = coordinator.model.sessionId
        effects.calls.clear()

        assertTrue(coordinator.reset())

        assertSame(identity, coordinator.model.hugoIdentity)
        assertNotEquals(oldSession, coordinator.model.sessionId)
        assertNull(coordinator.model.sessionName)
        assertEquals(FirstLifeInteractionState.READY, coordinator.model.state)
        assertEquals(FirstLifeExperienceStage.READY, coordinator.snapshot.stage)
        assertFalse(coordinator.snapshot.observationReady)
        assertEquals(
            listOf("orientation:RESET", "camera:false", "voice:false", "speech:false", "body"),
            effects.calls,
        )
    }

    @Test
    fun `resource conflicts and concrete permission failures block honestly`() {
        coordinator.startIntroduction()
        coordinator.onSpeechOutputChanged(speaking(SpeechOutputKind.INTRODUCTION, 1))
        coordinator.onSpeechOutputChanged(completed(SpeechOutputKind.INTRODUCTION))
        coordinator.onCameraSessionChanged(observingCamera(PersonDirection.LEFT))

        assertFalse(coordinator.requestNameInput())
        assertEquals(FirstLifeExperienceBlockedSource.RESOURCE_CONFLICT, coordinator.snapshot.blockedSource)

        coordinator.onCameraSessionChanged(CameraSessionModel.IDLE)
        assertTrue(coordinator.requestNameInput())
        coordinator.onVoiceSessionChanged(
            VoiceSessionModel(
                VoiceSessionPhase.BLOCKED,
                blockedReason = VoiceSessionBlockedReason.PERMISSION_PERMANENTLY_DENIED,
                activeRequest = null,
                recognizerOwned = false,
                evidence = null,
            ),
        )
        assertEquals(FirstLifeExperienceStage.BLOCKED, coordinator.snapshot.stage)
        assertEquals(FirstLifeExperienceBlockedSource.VOICE, coordinator.snapshot.blockedSource)
        assertEquals(FirstLifeInteractionState.AWAITING_LISTEN_START, coordinator.model.state)
    }

    @Test
    fun `fallback review time is not charged against the bounded listening window`() {
        coordinator.startIntroduction()
        coordinator.onSpeechOutputChanged(speaking(SpeechOutputKind.INTRODUCTION, 1))
        coordinator.onSpeechOutputChanged(completed(SpeechOutputKind.INTRODUCTION))
        assertTrue(coordinator.requestNameInput())

        coordinator.onVoiceSessionChanged(
            VoiceSessionModel(
                VoiceSessionPhase.BLOCKED,
                activeRequest = null,
                recognizerOwned = false,
                evidence = null,
                blockedReason = VoiceSessionBlockedReason.ALBANIAN_MODEL_UNAVAILABLE,
            ),
        )
        nowMs += 60_000L
        assertEquals(FirstLifeInteractionState.AWAITING_LISTEN_START, coordinator.model.state)

        coordinator.onVoiceSessionChanged(listeningVoice(VoiceRecognitionTier.SYSTEM_NETWORK_ASSISTED))
        coordinator.onVoiceSessionChanged(completedVoice(VoiceRecognitionTier.SYSTEM_NETWORK_ASSISTED))
        coordinator.onTranscript("Redi")

        assertEquals(FirstLifeInteractionState.GREETING_READY, coordinator.model.state)
        assertTrue(coordinator.snapshot.sessionNameAvailable)
        assertEquals(1, effects.nameInputStarts)
    }

    @Test
    fun `late callbacks after reset cannot restore name progress or completion`() {
        coordinator.startIntroduction()
        val oldSession = coordinator.model.sessionId
        coordinator.reset()
        coordinator.onSpeechOutputChanged(speaking(SpeechOutputKind.INTRODUCTION, 9))
        coordinator.onVoiceSessionChanged(listeningVoice())
        coordinator.onTranscript("Private Name")

        assertNotEquals(oldSession, coordinator.model.sessionId)
        assertEquals(FirstLifeInteractionState.READY, coordinator.model.state)
        assertFalse(coordinator.snapshot.sessionNameAvailable)
        assertFalse(coordinator.snapshot.observationReady)
    }

    @Test
    fun `body disconnect preserves Hugo identity and ephemeral dialogue while revoking body fact`() {
        completeNamedGreeting()
        coordinator.bodyStatusChanged(FirstLifeBodyStatus.SELECTED)
        val session = coordinator.model.sessionId

        coordinator.bodyStatusChanged(FirstLifeBodyStatus.LOST)

        assertSame(identity, coordinator.model.hugoIdentity)
        assertEquals(session, coordinator.model.sessionId)
        assertTrue(coordinator.snapshot.sessionNameAvailable)
        assertTrue(coordinator.snapshot.observationReady)
        assertEquals(FirstLifeBodyStatus.LOST, coordinator.snapshot.bodyStatus)
    }

    @Test
    fun `camera denial cannot become centered or completed evidence`() {
        completeNamedGreeting()
        assertTrue(coordinator.openVisibleCamera())

        coordinator.onCameraSessionChanged(
            CameraSessionModel(
                CameraSessionPhase.BLOCKED,
                blockedReason = CameraBlockedReason.PERMISSION_DENIED,
            ),
        )

        assertEquals(FirstLifeExperienceStage.BLOCKED, coordinator.snapshot.stage)
        assertEquals(FirstLifeExperienceBlockedSource.CAMERA, coordinator.snapshot.blockedSource)
        assertFalse(coordinator.snapshot.centeredPersonConfirmed)
    }

    @Test
    fun `process recreation restores identity only and never restores the prior run session or name`() {
        completeNamedGreeting()
        val priorSession = coordinator.model.sessionId
        val recreated = FirstLifeExperienceCoordinator(
            identity,
            sessionIdFactory = {
                FirstLifeSessionId.fromUuid(UUID.fromString("20000000-0000-4000-8000-000000000014"))
            },
            nowMs = { 900L },
            nowNanos = { 900_000_000L },
            effects = FakeEffects(),
        )

        assertSame(identity, recreated.model.hugoIdentity)
        assertNotEquals(priorSession, recreated.model.sessionId)
        assertNull(recreated.model.sessionName)
        assertEquals(FirstLifeExperienceStage.READY, recreated.snapshot.stage)
        assertFalse(recreated.snapshot.observationReady)
    }

    @Test
    fun `one cleanup failure remains blocked evidence but cannot skip later cleanup steps`() {
        coordinator.startIntroduction()
        effects.calls.clear()
        effects.failOrientation = true

        coordinator.cancel()

        assertEquals(
            listOf("orientation:USER_CANCEL", "camera:false", "voice:false", "speech:false", "body"),
            effects.calls,
        )
        assertEquals(FirstLifeExperienceStage.BLOCKED, coordinator.snapshot.stage)
        assertEquals(FirstLifeExperienceBlockedSource.CLEANUP, coordinator.snapshot.blockedSource)
        assertEquals(FirstLifeInteractionState.READY, coordinator.model.state)
    }

    private fun completeNamedGreeting() {
        coordinator.startIntroduction()
        coordinator.onSpeechOutputChanged(speaking(SpeechOutputKind.INTRODUCTION, 1))
        coordinator.onSpeechOutputChanged(completed(SpeechOutputKind.INTRODUCTION))
        coordinator.requestNameInput()
        coordinator.onVoiceSessionChanged(listeningVoice())
        coordinator.onVoiceSessionChanged(completedVoice(VoiceRecognitionTier.ON_DEVICE))
        coordinator.onTranscript("Redi")
        coordinator.onSpeechOutputChanged(speaking(SpeechOutputKind.SESSION_NAME_GREETING, 2))
        coordinator.onSpeechOutputChanged(completed(SpeechOutputKind.SESSION_NAME_GREETING))
    }

    private fun speaking(kind: SpeechOutputKind, ordinal: Long): SpeechOutputModel {
        val request = SpeechOutputRequest(
            SpeechOutputRequestId("speech-$ordinal"),
            ordinal,
            kind,
            text = LocalSpeechText.from("bounded text"),
        )
        return SpeechOutputModel(SpeechOutputPhase.SPEAKING, activeRequest = request)
    }

    private fun completed(kind: SpeechOutputKind) = SpeechOutputModel(
        SpeechOutputPhase.COMPLETED,
        evidence = SpeechOutputEvidence(kind, "sq-AL", localVoice = true),
    )

    private fun listeningVoice(tier: VoiceRecognitionTier = VoiceRecognitionTier.ON_DEVICE): VoiceSessionModel {
        val request = VoiceSessionRequest(
            VoiceSessionRequestId("voice"),
            1,
            8_001,
            tier,
            if (tier == VoiceRecognitionTier.ON_DEVICE) "sq-AL" else "en-US",
        )
        return VoiceSessionModel(VoiceSessionPhase.LISTENING, request, true, null, null)
    }

    private fun completedVoice(tier: VoiceRecognitionTier) = VoiceSessionModel(
        VoiceSessionPhase.COMPLETED,
        activeRequest = null,
        recognizerOwned = false,
        evidence = VoiceRecognitionEvidence(tier, if (tier == VoiceRecognitionTier.ON_DEVICE) "sq-AL" else "en-US", 1, 4),
        blockedReason = null,
    )

    private fun observingCamera(direction: PersonDirection): CameraSessionModel {
        val request = CameraSessionRequest(CameraSessionRequestId("camera"), CameraGeneration(1))
        val evidence = PersonDirectionEvidence(
            request.id,
            request.generation,
            sequence = 3,
            observedAtElapsedNanos = nowNanos,
            expiresAtElapsedNanos = nowNanos + 250_000_000L,
            direction = direction,
            reason = PersonEvidenceReason.STABLE_PERSON,
            boundingBox = NormalizedBoundingBox(.2f, .1f, .4f, .9f),
            horizontalCenterOffset = if (direction == PersonDirection.CENTER) 0f else -.4f,
            confidence = .9f,
        )
        return CameraSessionModel(
            CameraSessionPhase.OBSERVING,
            activeRequest = request,
            evidence = CameraSessionEvidence(request.generation, CameraLensFacing.FRONT, true, true),
            personEvidence = evidence,
        )
    }

    private class FakeEffects : FirstLifeExperienceEffectPort {
        val calls = mutableListOf<String>()
        val spoken = mutableListOf<Pair<SpeechOutputKind, FirstLifeSpeechText>>()
        var nameInputStarts = 0
        var failOrientation = false

        override fun speak(kind: SpeechOutputKind, speech: FirstLifeSpeechText): Boolean {
            spoken += kind to speech
            calls += "speak:$kind"
            return true
        }

        override fun startNameInput(): Boolean {
            nameInputStarts++
            calls += "voice:start"
            return true
        }

        override fun cancelNameInput(background: Boolean) {
            calls += "voice:$background"
        }

        override fun cancelSpeech(background: Boolean) {
            calls += "speech:$background"
        }

        override fun openVisibleCamera(): Boolean {
            calls += "camera:open"
            return true
        }

        override fun closeVisibleCamera(background: Boolean) {
            calls += "camera:$background"
        }

        override fun stopOrientation(reason: FirstLifeExperienceCleanupReason) {
            calls += "orientation:$reason"
            if (failOrientation) error("test cleanup failure")
        }

        override fun deactivateActiveBody() {
            calls += "body"
        }
    }
}
