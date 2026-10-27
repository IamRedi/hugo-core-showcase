package io.redi.robot.voice.capture

import io.redi.robot.voice.session.AudioMonotonicTimeMs
import io.redi.robot.voice.session.AudioSessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class LocalVoiceActivityDetectorTest {
    private val detector = ThresholdLocalVoiceActivityDetector()

    @Test
    fun `two voiced frames start and five quiet frames end local activity`() {
        val observations = mutableListOf<LocalVadObservation>()
        var sequence = 0L
        repeat(2) { observations += evaluate(++sequence, 1_200) }
        assertEquals(LocalVadTransition.NONE, observations[0].transition)
        assertFalse(observations[0].voiceActive)
        assertEquals(LocalVadTransition.VOICE_STARTED, observations[1].transition)
        assertTrue(observations[1].voiceActive)

        repeat(4) { observations += evaluate(++sequence, 0) }
        assertTrue(observations.last().voiceActive)
        observations += evaluate(++sequence, 0)
        assertEquals(LocalVadTransition.VOICE_ENDED, observations.last().transition)
        assertFalse(observations.last().voiceActive)
    }

    @Test
    fun `amplitude boundary and decision latency are deterministic`() {
        val config = LocalVadConfiguration(900, voicedFramesToStart = 1, quietFramesToEnd = 1)
        val vad = ThresholdLocalVoiceActivityDetector(config)
        val below = frame(1, 899, capturedAt = 10)
        val belowResult = EphemeralVadProcessor(vad).process(below, time(13))
        assertEquals(LocalVadTransition.NONE, belowResult.transition)
        assertEquals(3L, belowResult.decisionLatencyMs)
        assertTrue(below.isReleased)

        val exact = frame(2, 900, capturedAt = 20)
        val exactResult = EphemeralVadProcessor(vad).process(exact, time(27))
        assertEquals(LocalVadTransition.VOICE_STARTED, exactResult.transition)
        assertEquals(7L, exactResult.decisionLatencyMs)
    }

    @Test
    fun `frame copies input and is inaccessible after idempotent zeroizing release`() {
        val source = ShortArray(320) { 1_100 }
        val frame = EphemeralPcm16Frame.copyOf(1, time(0), source, source.size)
        source.fill(0)
        assertEquals(1_100.toShort(), frame.sampleAt(0))
        frame.close()
        frame.close()
        assertTrue(frame.isReleased)
        assertFailsState { frame.sampleAt(0) }
    }

    @Test
    fun `processor releases frame even when detector fails`() {
        val frame = frame(1, 1_000)
        val processor = EphemeralVadProcessor(object : LocalVoiceActivityDetector {
            override fun evaluate(frame: EphemeralPcm16Frame, evaluatedAt: AudioMonotonicTimeMs): LocalVadObservation {
                error("synthetic detector failure")
            }

            override fun reset() = Unit
        })
        var failed = false
        try {
            processor.process(frame, time(1))
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
        assertTrue(frame.isReleased)
    }

    @Test
    fun `sequence replay is rejected and reset begins a fresh detector session`() {
        evaluate(1, 0)
        assertFailsArgument { evaluate(1, 0) }
        detector.reset()
        assertEquals(LocalVadTransition.NONE, evaluate(1, 0).transition)
    }

    @Test
    fun `capture request and configuration enforce bounded explicit sessions`() {
        val session = AudioSessionId.fromUuid(UUID.fromString("00000000-0000-0000-0000-000000000048"))
        val config = AudioCaptureConfiguration()
        val request = ExplicitAudioCaptureRequest(session, time(100), time(10_100), config)
        assertEquals(320, config.samplesPerFrame)
        assertEquals(10_000L, request.deadlineAt.value - request.requestedAt.value)
        assertTrue(request.toString().contains("audio-session:[redacted]"))
        assertFalse(request.toString().contains(session.value))

        assertFailsArgument { AudioCaptureConfiguration(sampleRateHz = 44_100) }
        assertFailsArgument { AudioCaptureConfiguration(frameDurationMs = 0) }
        assertFailsArgument { AudioCaptureConfiguration(maximumDurationMs = 16_000) }
        assertFailsArgument { ExplicitAudioCaptureRequest(session, time(100), time(100), config) }
        assertFailsArgument { ExplicitAudioCaptureRequest(session, time(100), time(10_101), config) }
    }

    @Test
    fun `evidence forbids retained or uploaded raw audio`() {
        val evidence = AudioCaptureEvidence(2, 640, 2, 20, 3, 1)
        assertFalse(evidence.rawAudioRetained)
        assertFalse(evidence.audioUploaded)
        assertFailsArgument { AudioCaptureEvidence(2, 640, 1, 20, 3, 1) }
        assertFailsArgument { AudioCaptureEvidence(0, 0, 0, null, null, 0, rawAudioRetained = true) }
        assertFailsArgument { AudioCaptureEvidence(0, 0, 0, null, null, 0, audioUploaded = true) }
    }

    @Test
    fun `fake port proves explicit start correlation stop and non authority semantics`() {
        val fake = FakeCapturePort()
        val session = AudioSessionId.fromUuid(UUID.fromString("00000000-0000-0000-0000-000000000048"))
        val other = AudioSessionId.fromUuid(UUID.fromString("00000000-0000-0000-0000-000000000049"))
        val request = ExplicitAudioCaptureRequest(session, time(0), time(1_000))
        val events = mutableListOf<AudioCaptureEvent>()
        assertEquals(AudioCaptureStartDisposition.ACCEPTED, fake.start(request, events::add).disposition)
        assertTrue(fake.snapshot().microphoneOwned)
        assertEquals(AudioCaptureStartDisposition.BUSY, fake.start(request, events::add).disposition)
        assertEquals(AudioCaptureStopDisposition.WRONG_SESSION, fake.stop(other, AudioCaptureStopReason.USER_RELEASE))
        assertEquals(AudioCaptureStopDisposition.REQUESTED, fake.stop(session, AudioCaptureStopReason.USER_RELEASE))
        assertEquals(AudioCaptureStopDisposition.ALREADY_STOPPING, fake.stop(session, AudioCaptureStopReason.CANCELLED))
        fake.complete()
        assertFalse(fake.snapshot().active)
        val terminal = events.single() as AudioCaptureEvent.Stopped
        assertEquals(AudioCaptureStopReason.USER_RELEASE, terminal.reason)
        assertFalse(terminal.evidence.rawAudioRetained)
        assertFalse(terminal.evidence.audioUploaded)
    }

    @Test
    fun `capture domain is Android free and platform adapter has no storage network STT or logging path`() {
        val domain = locate("app/src/main/java/io/redi/robot/voice/capture")
            .walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        listOf("import android.", "AudioRecord", "MediaRecorder", "FileOutputStream", "http://", "https://", "SpeechRecognizer", "TextToSpeech").forEach {
            assertFalse("domain leak: $it", domain.contains(it))
        }

        val adapter = locate("app/src/main/java/io/redi/robot/platform/voice/android/AndroidAudioRecordCapturePort.kt").readText()
        assertTrue(adapter.contains("AudioRecord"))
        listOf("FileOutputStream", "File(", "SharedPreferences", "RoomDatabase", "http://", "https://", "SpeechRecognizer", "TextToSpeech", "Log.", "println(").forEach {
            assertFalse("adapter leak: $it", adapter.contains(it))
        }
    }

    private fun evaluate(sequence: Long, amplitude: Int): LocalVadObservation {
        val frame = frame(sequence, amplitude, capturedAt = sequence * 20)
        return EphemeralVadProcessor(detector).process(frame, time(sequence * 20 + 1))
    }

    private fun frame(sequence: Long, amplitude: Int, capturedAt: Long = 0): EphemeralPcm16Frame =
        EphemeralPcm16Frame.copyOf(sequence, time(capturedAt), ShortArray(320) { amplitude.toShort() }, 320)

    private fun time(value: Long) = AudioMonotonicTimeMs(value)

    private fun locate(relative: String): File = sequenceOf(File(relative), File("../$relative"), File("../../$relative"))
        .firstOrNull(File::exists) ?: error("Unable to locate $relative")

    private fun assertFailsArgument(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("expected IllegalArgumentException", failed)
    }

    private fun assertFailsState(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue("expected IllegalStateException", failed)
    }

    private class FakeCapturePort : ExplicitAudioCapturePort {
        private var request: ExplicitAudioCaptureRequest? = null
        private var listener: AudioCaptureListener? = null
        private var stopping = false
        private var reason: AudioCaptureStopReason? = null

        override fun start(request: ExplicitAudioCaptureRequest, listener: AudioCaptureListener): AudioCaptureStartResult {
            if (this.request != null) return AudioCaptureStartResult(AudioCaptureStartDisposition.BUSY, false)
            this.request = request
            this.listener = listener
            return AudioCaptureStartResult(AudioCaptureStartDisposition.ACCEPTED, true)
        }

        override fun stop(sessionId: AudioSessionId, reason: AudioCaptureStopReason): AudioCaptureStopDisposition {
            val active = request ?: return AudioCaptureStopDisposition.NOT_ACTIVE
            if (active.sessionId != sessionId) return AudioCaptureStopDisposition.WRONG_SESSION
            if (stopping) return AudioCaptureStopDisposition.ALREADY_STOPPING
            stopping = true
            this.reason = reason
            return AudioCaptureStopDisposition.REQUESTED
        }

        override fun snapshot() = AudioCapturePortSnapshot(request != null, stopping, request != null)

        fun complete() {
            val active = requireNotNull(request)
            listener!!.onEvent(
                AudioCaptureEvent.Stopped(
                    active.sessionId,
                    active.deadlineAt,
                    requireNotNull(reason),
                    AudioCaptureEvidence(0, 0, 0, null, null, 0),
                ),
            )
            request = null
            listener = null
            stopping = false
        }
    }
}
