package io.redi.robot.voice.api

import io.redi.robot.voice.session.AudioMonotonicTimeMs
import io.redi.robot.voice.session.AudioSessionId

enum class SpeechAudioFocusUse {
    RECOGNITION,
    SPEECH_OUTPUT,
}

data class SpeechAudioFocusRequest(
    val operationId: SpeechOperationId,
    val sessionId: AudioSessionId,
    val use: SpeechAudioFocusUse,
)

enum class SpeechAudioFocusAcquireDisposition {
    GRANTED,
    DENIED,
    UNAVAILABLE,
    BUSY,
}

data class SpeechAudioFocusAcquireResult(
    val disposition: SpeechAudioFocusAcquireDisposition,
    val ownsFocus: Boolean,
) {
    init {
        require(ownsFocus == (disposition == SpeechAudioFocusAcquireDisposition.GRANTED))
    }
}

enum class SpeechAudioFocusReleaseDisposition {
    RELEASED,
    NOT_OWNED,
    WRONG_OPERATION,
}

enum class SpeechAudioFocusLoss {
    TRANSIENT,
    PERMANENT,
}

data class SpeechAudioFocusEvent(
    val operationId: SpeechOperationId,
    val sessionId: AudioSessionId,
    val occurredAt: AudioMonotonicTimeMs,
    val loss: SpeechAudioFocusLoss,
)

fun interface SpeechAudioFocusListener {
    fun onFocusEvent(event: SpeechAudioFocusEvent)
}

interface SpeechAudioFocusPort {
    fun acquire(
        request: SpeechAudioFocusRequest,
        listener: SpeechAudioFocusListener,
    ): SpeechAudioFocusAcquireResult

    fun release(operationId: SpeechOperationId): SpeechAudioFocusReleaseDisposition

    fun snapshot(): SpeechAudioFocusSnapshot
}

data class SpeechAudioFocusSnapshot(
    val owned: Boolean,
    val operationId: SpeechOperationId?,
) {
    init {
        require(owned == (operationId != null))
    }
}
