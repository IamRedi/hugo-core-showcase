package io.redi.robot.voice.capture

import io.redi.robot.voice.session.AudioMonotonicTimeMs
import io.redi.robot.voice.session.AudioSessionId

data class AudioCaptureConfiguration(
    val sampleRateHz: Int = 16_000,
    val frameDurationMs: Int = 20,
    val maximumDurationMs: Long = 10_000L,
) {
    val samplesPerFrame: Int = sampleRateHz * frameDurationMs / 1_000

    init {
        require(sampleRateHz in setOf(8_000, 16_000, 24_000, 48_000))
        require(frameDurationMs in 10..40)
        require(sampleRateHz * frameDurationMs % 1_000 == 0)
        require(samplesPerFrame in 80..1_920)
        require(maximumDurationMs in 250L..15_000L)
    }
}

data class ExplicitAudioCaptureRequest(
    val sessionId: AudioSessionId,
    val requestedAt: AudioMonotonicTimeMs,
    val deadlineAt: AudioMonotonicTimeMs,
    val configuration: AudioCaptureConfiguration = AudioCaptureConfiguration(),
) {
    init {
        require(deadlineAt.value > requestedAt.value)
        require(deadlineAt.value - requestedAt.value <= configuration.maximumDurationMs)
    }

    override fun toString(): String = "ExplicitAudioCaptureRequest(" +
        "session=$sessionId, requestedAt=${requestedAt.value}, deadlineAt=${deadlineAt.value}, " +
        "sampleRate=${configuration.sampleRateHz}, frameMs=${configuration.frameDurationMs})"
}

enum class AudioCaptureStartDisposition {
    ACCEPTED,
    BUSY,
    PERMISSION_UNAVAILABLE,
    AUDIO_UNAVAILABLE,
    INVALID_REQUEST,
}

data class AudioCaptureStartResult(
    val disposition: AudioCaptureStartDisposition,
    val microphoneOwned: Boolean,
) {
    init {
        require(!microphoneOwned || disposition == AudioCaptureStartDisposition.ACCEPTED)
    }
}

enum class AudioCaptureStopReason {
    USER_RELEASE,
    CANCELLED,
    BACKGROUNDED,
    DEADLINE_REACHED,
}

enum class AudioCaptureStopDisposition {
    REQUESTED,
    ALREADY_STOPPING,
    NOT_ACTIVE,
    WRONG_SESSION,
}

enum class AudioCaptureFailure {
    PERMISSION_REVOKED,
    INITIALIZATION_FAILED,
    START_FAILED,
    READ_FAILED,
    CALLBACK_FAILED,
    UNKNOWN_FAILED,
}

data class AudioCaptureEvidence(
    val framesProcessed: Long,
    val samplesProcessed: Long,
    val bufferReleaseCount: Long,
    val firstFrameLatencyMs: Long?,
    val maximumVadDecisionLatencyMs: Long?,
    val voiceActivityTransitions: Int,
    val rawAudioRetained: Boolean = false,
    val audioUploaded: Boolean = false,
) {
    init {
        require(framesProcessed >= 0L)
        require(samplesProcessed >= 0L)
        require(bufferReleaseCount == framesProcessed)
        require(firstFrameLatencyMs == null || firstFrameLatencyMs >= 0L)
        require(maximumVadDecisionLatencyMs == null || maximumVadDecisionLatencyMs >= 0L)
        require(voiceActivityTransitions >= 0)
        require(!rawAudioRetained)
        require(!audioUploaded)
    }
}

sealed interface AudioCaptureEvent {
    val sessionId: AudioSessionId
    val occurredAt: AudioMonotonicTimeMs

    data class Started(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        val startLatencyMs: Long,
    ) : AudioCaptureEvent {
        init {
            require(startLatencyMs >= 0L)
        }
    }

    data class VoiceActivity(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        val observation: LocalVadObservation,
    ) : AudioCaptureEvent

    data class Stopped(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        val reason: AudioCaptureStopReason,
        val evidence: AudioCaptureEvidence,
    ) : AudioCaptureEvent

    data class Failed(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        val failure: AudioCaptureFailure,
        val evidence: AudioCaptureEvidence,
    ) : AudioCaptureEvent
}

fun interface AudioCaptureListener {
    fun onEvent(event: AudioCaptureEvent)
}

/** Explicit PTT-style capture boundary. Presence of this port never implies permission or ownership. */
interface ExplicitAudioCapturePort {
    fun start(request: ExplicitAudioCaptureRequest, listener: AudioCaptureListener): AudioCaptureStartResult

    fun stop(sessionId: AudioSessionId, reason: AudioCaptureStopReason): AudioCaptureStopDisposition

    fun snapshot(): AudioCapturePortSnapshot
}

data class AudioCapturePortSnapshot(
    val active: Boolean,
    val stopping: Boolean,
    val microphoneOwned: Boolean,
    val rawAudioRetained: Boolean = false,
    val audioUploaded: Boolean = false,
) {
    init {
        require(!stopping || active)
        require(!microphoneOwned || active)
        require(!rawAudioRetained)
        require(!audioUploaded)
    }
}
