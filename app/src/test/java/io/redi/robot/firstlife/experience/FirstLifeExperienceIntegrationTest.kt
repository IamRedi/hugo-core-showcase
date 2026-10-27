package io.redi.robot.firstlife.experience

import io.redi.robot.firstlife.interaction.FirstLifeSessionId
import io.redi.robot.firstlife.interaction.FirstLifeSpeechText
import io.redi.robot.firstlife.vision.CameraBlockedReason
import io.redi.robot.firstlife.vision.CameraSessionCoordinator
import io.redi.robot.firstlife.vision.CameraSessionListener
import io.redi.robot.firstlife.vision.CameraSessionPort
import io.redi.robot.firstlife.vision.CameraSessionRequest
import io.redi.robot.firstlife.vision.CameraSessionRequestId
import io.redi.robot.firstlife.vision.NormalizedBoundingBox
import io.redi.robot.firstlife.vision.PersonDetection
import io.redi.robot.firstlife.vision.PersonDetectionObservation
import io.redi.robot.firstlife.voice.LocalSpeechOutputPort
import io.redi.robot.firstlife.voice.LocalSpeechText
import io.redi.robot.firstlife.voice.LocalTtsSupport
import io.redi.robot.firstlife.voice.SpeechOutputCoordinator
import io.redi.robot.firstlife.voice.SpeechOutputKind
import io.redi.robot.firstlife.voice.SpeechOutputListener
import io.redi.robot.firstlife.voice.SpeechOutputRequest
import io.redi.robot.firstlife.voice.SpeechOutputRequestId
import io.redi.robot.firstlife.voice.VoiceCaptureListener
import io.redi.robot.firstlife.voice.VoiceCapturePort
import io.redi.robot.firstlife.voice.VoiceOnDeviceSupport
import io.redi.robot.firstlife.voice.VoiceSessionCoordinator
import io.redi.robot.firstlife.voice.VoiceSessionRequest
import io.redi.robot.firstlife.voice.VoiceSessionRequestId
import io.redi.robot.firstlife.voice.VoiceTranscriptCandidates
import io.redi.robot.hugo.identity.HugoIdentity
import io.redi.robot.hugo.identity.HugoIdentityId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLifeExperienceIntegrationTest {
    @Test
    fun `real slice coordinators complete the deterministic scripted path over fakes`() {
        val harness = Harness(cameraPermission = true)

        assertTrue(harness.experience.startIntroduction())
        harness.speechPort.startedAndCompleted()
        assertEquals(FirstLifeExperienceStage.AWAITING_EXPLICIT_LISTEN, harness.experience.snapshot.stage)

        assertTrue(harness.experience.requestNameInput())
        harness.voicePort.startedAndReturned("Redi")
        harness.speechPort.startedAndCompleted()
        assertEquals(FirstLifeExperienceStage.OBSERVATION_READY, harness.experience.snapshot.stage)

        assertTrue(harness.experience.openVisibleCamera())
        harness.cameraPort.opened()
        repeat(3) { index -> harness.cameraPort.centerObservation(index + 1L) }

        assertEquals(FirstLifeExperienceStage.PERSON_CENTERED, harness.experience.snapshot.stage)
        assertTrue(harness.experience.snapshot.sessionNameAvailable)
        assertEquals(2, harness.speechPort.requests.size)
        assertEquals(listOf(SpeechOutputKind.INTRODUCTION, SpeechOutputKind.SESSION_NAME_GREETING),
            harness.speechPort.requests.map { it.kind })
    }

    @Test
    fun `camera permission denial in the composed path remains blocked evidence`() {
        val harness = Harness(cameraPermission = false)
        harness.experience.startIntroduction()
        harness.speechPort.startedAndCompleted()
        harness.experience.requestNameInput()
        harness.voicePort.startedAndReturned("Redi")
        harness.speechPort.startedAndCompleted()

        assertTrue(harness.experience.openVisibleCamera())

        assertEquals(FirstLifeExperienceStage.BLOCKED, harness.experience.snapshot.stage)
        assertEquals(FirstLifeExperienceBlockedSource.CAMERA, harness.experience.snapshot.blockedSource)
    }

    private class Harness(cameraPermission: Boolean) {
        var nowMs = 10L
        var nowNanos = 10_000_000L
        val voicePort = FakeVoicePort()
        val speechPort = FakeSpeechPort()
        val cameraPort = FakeCameraPort { nowNanos }
        lateinit var voice: VoiceSessionCoordinator
        lateinit var speech: SpeechOutputCoordinator
        lateinit var camera: CameraSessionCoordinator
        val experience: FirstLifeExperienceCoordinator

        init {
            val effects = object : FirstLifeExperienceEffectPort {
                override fun speak(kind: SpeechOutputKind, speechText: FirstLifeSpeechText): Boolean =
                    speech.speak(kind, LocalSpeechText.from(speechText.text))

                override fun startNameInput(): Boolean {
                    voice.startExplicitSession()
                    return true
                }

                override fun cancelNameInput(background: Boolean) {
                    if (background) voice.background() else voice.cancel()
                }

                override fun cancelSpeech(background: Boolean) = speech.cancel(background)
                override fun openVisibleCamera(): Boolean = camera.startExplicitSession()
                override fun closeVisibleCamera(background: Boolean) = camera.close(background)
                override fun stopOrientation(reason: FirstLifeExperienceCleanupReason) = Unit
                override fun deactivateActiveBody() = Unit
            }
            experience = FirstLifeExperienceCoordinator(
                HugoIdentity(HugoIdentityId.fromUuid(UUID.fromString("40000000-0000-4000-8000-000000000014"))),
                { FirstLifeSessionId.fromUuid(UUID.fromString("41000000-0000-4000-8000-000000000014")) },
                { ++nowMs },
                { nowNanos },
                effects,
            )
            voice = VoiceSessionCoordinator(
                voicePort,
                { ++nowMs },
                { VoiceSessionRequestId("voice") },
                { id -> voice.onPermissionResult(id, granted = true, canAskAgain = false) },
                { transcript -> experience.onTranscript(transcript.value) },
                experience::onVoiceSessionChanged,
            )
            speech = SpeechOutputCoordinator(
                speechPort,
                { SpeechOutputRequestId("speech-${speechPort.requests.size + 1}") },
                experience::onSpeechOutputChanged,
            )
            camera = CameraSessionCoordinator(
                cameraPort,
                { CameraSessionRequestId("camera") },
                { id -> camera.onPermissionResult(id, cameraPermission, canAskAgain = true) },
                experience::onCameraSessionChanged,
                nowElapsedNanos = { nowNanos },
            )
        }
    }

    private class FakeVoicePort : VoiceCapturePort {
        private var listener: VoiceCaptureListener? = null
        private var request: VoiceSessionRequest? = null

        override fun checkOnDeviceSupport(
            requestId: VoiceSessionRequestId,
            callback: (VoiceSessionRequestId, VoiceOnDeviceSupport) -> Unit,
        ) = callback(requestId, VoiceOnDeviceSupport.SUPPORTED)

        override fun startOnDeviceCapture(request: VoiceSessionRequest, listener: VoiceCaptureListener) {
            this.request = request
            this.listener = listener
        }

        override fun startSystemCapture(request: VoiceSessionRequest, listener: VoiceCaptureListener) =
            startOnDeviceCapture(request, listener)

        override fun release(requestId: VoiceSessionRequestId) = Unit

        fun startedAndReturned(value: String) {
            val id = checkNotNull(request).id
            checkNotNull(listener).onCaptureStarted(id)
            checkNotNull(listener).onResults(id, VoiceTranscriptCandidates.from(listOf(value)))
        }
    }

    private class FakeSpeechPort : LocalSpeechOutputPort {
        val requests = mutableListOf<SpeechOutputRequest>()
        private var listener: SpeechOutputListener? = null

        override fun checkSupport(
            requestId: SpeechOutputRequestId,
            callback: (SpeechOutputRequestId, LocalTtsSupport) -> Unit,
        ) = callback(requestId, LocalTtsSupport.SUPPORTED)

        override fun speak(request: SpeechOutputRequest, listener: SpeechOutputListener) {
            requests += request
            this.listener = listener
        }

        override fun release(requestId: SpeechOutputRequestId) = Unit

        fun startedAndCompleted() {
            val id = requests.last().id
            checkNotNull(listener).onStarted(id)
            checkNotNull(listener).onCompleted(id)
        }
    }

    private class FakeCameraPort(private val nowNanos: () -> Long) : CameraSessionPort {
        private var request: CameraSessionRequest? = null
        private var listener: CameraSessionListener? = null

        override fun open(request: CameraSessionRequest, listener: CameraSessionListener) {
            this.request = request
            this.listener = listener
        }

        override fun close(requestId: CameraSessionRequestId) = Unit

        fun opened() = checkNotNull(listener).onOpened(checkNotNull(request))

        fun centerObservation(sequence: Long) {
            val current = checkNotNull(request)
            val observedAt = nowNanos() + sequence * 10_000_000L
            checkNotNull(listener).onPersonObservation(
                PersonDetectionObservation(
                    current.id,
                    current.generation,
                    sequence,
                    observedAt,
                    observedAt + 20_000_000L,
                    detectorLatencyMs = 20,
                    detections = listOf(PersonDetection(.95f, NormalizedBoundingBox(.4f, .1f, .6f, .9f))),
                ),
            )
        }

        @Suppress("unused")
        fun fail(reason: CameraBlockedReason) = checkNotNull(listener).onFailure(checkNotNull(request), reason)
    }
}
