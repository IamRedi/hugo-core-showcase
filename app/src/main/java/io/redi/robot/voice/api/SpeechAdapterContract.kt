package io.redi.robot.voice.api

import io.redi.robot.voice.capture.AudioCaptureConfiguration
import io.redi.robot.voice.session.AudioMonotonicTimeMs
import io.redi.robot.voice.session.AudioSessionId
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@JvmInline
value class SpeechOperationId private constructor(val value: String) {
    override fun toString(): String = "speech-operation:[redacted]"

    companion object {
        fun fromUuid(uuid: UUID): SpeechOperationId = SpeechOperationId(uuid.toString())

        fun parse(raw: String): SpeechContractResult<SpeechOperationId> {
            val canonical = runCatching { UUID.fromString(raw).toString() }.getOrNull()
            return if (canonical == raw) {
                SpeechContractResult.Success(SpeechOperationId(raw))
            } else {
                SpeechContractResult.Failure(SpeechContractError.INVALID_OPERATION_ID)
            }
        }
    }
}

@JvmInline
value class SpeechLocale private constructor(val tag: String) {
    override fun toString(): String = tag

    companion object {
        private val canonicalLocale = Regex("^[a-z]{2,3}-[A-Z]{2}$")

        fun parse(raw: String): SpeechContractResult<SpeechLocale> = if (canonicalLocale.matches(raw)) {
            SpeechContractResult.Success(SpeechLocale(raw))
        } else {
            SpeechContractResult.Failure(SpeechContractError.INVALID_LOCALE)
        }
    }
}

@JvmInline
value class SpeechText private constructor(val value: String) {
    override fun toString(): String = "SpeechText(redacted)"

    companion object {
        const val MAX_CODE_POINTS = 1_000

        fun create(raw: String): SpeechContractResult<SpeechText> = if (validBoundedText(raw, MAX_CODE_POINTS)) {
            SpeechContractResult.Success(SpeechText(raw))
        } else {
            SpeechContractResult.Failure(SpeechContractError.INVALID_TEXT)
        }
    }
}

/**
 * One-shot transcript envelope. The coordinator consumes and drops its references immediately;
 * adapters must not rely on it as a transcript store.
 */
class EphemeralSpeechTranscript private constructor(alternatives: List<String>) {
    private val content = AtomicReference<List<String>?>(Collections.unmodifiableList(alternatives.toList()))

    val consumed: Boolean get() = content.get() == null

    fun <T> consume(block: (List<String>) -> T): T {
        val values = content.getAndSet(null) ?: error("transcript has already been consumed")
        return block(values)
    }

    fun discard() {
        content.set(null)
    }

    override fun toString(): String = "EphemeralSpeechTranscript(redacted, consumed=$consumed)"

    companion object {
        const val MAX_ALTERNATIVES = 3
        const val MAX_CODE_POINTS_PER_ALTERNATIVE = 160

        fun create(alternatives: List<String>): SpeechContractResult<EphemeralSpeechTranscript> {
            if (alternatives.isEmpty() || alternatives.size > MAX_ALTERNATIVES) {
                return SpeechContractResult.Failure(SpeechContractError.INVALID_TRANSCRIPT)
            }
            if (alternatives.any { !validBoundedText(it, MAX_CODE_POINTS_PER_ALTERNATIVE) }) {
                return SpeechContractResult.Failure(SpeechContractError.INVALID_TRANSCRIPT)
            }
            return SpeechContractResult.Success(EphemeralSpeechTranscript(alternatives))
        }
    }
}

data class SpeechRecognitionRequest(
    val operationId: SpeechOperationId,
    val sessionId: AudioSessionId,
    val locale: SpeechLocale,
    val requestedAt: AudioMonotonicTimeMs,
    val deadlineAt: AudioMonotonicTimeMs,
    val captureConfiguration: AudioCaptureConfiguration = AudioCaptureConfiguration(),
) {
    val requiresExplicitCapture: Boolean get() = true

    init {
        require(deadlineAt.value > requestedAt.value)
        require(deadlineAt.value - requestedAt.value <= MAX_RECOGNITION_DURATION_MS)
    }

    override fun toString(): String = "SpeechRecognitionRequest(" +
        "operation=$operationId, session=$sessionId, locale=$locale, " +
        "deadline=${deadlineAt.value}, explicitCapture=true)"

    companion object {
        const val MAX_RECOGNITION_DURATION_MS = 30_000L
    }
}

data class SpeechSynthesisRequest(
    val operationId: SpeechOperationId,
    val sessionId: AudioSessionId,
    val locale: SpeechLocale,
    val requestedAt: AudioMonotonicTimeMs,
    val deadlineAt: AudioMonotonicTimeMs,
    val text: SpeechText,
) {
    init {
        require(deadlineAt.value > requestedAt.value)
        require(deadlineAt.value - requestedAt.value <= MAX_SYNTHESIS_DURATION_MS)
    }

    override fun toString(): String = "SpeechSynthesisRequest(" +
        "operation=$operationId, session=$sessionId, locale=$locale, deadline=${deadlineAt.value}, text=redacted)"

    companion object {
        const val MAX_SYNTHESIS_DURATION_MS = 60_000L
    }
}

enum class SpeechAdapterStartDisposition {
    ACCEPTED,
    BUSY,
    UNSUPPORTED_LOCALE,
    UNAVAILABLE,
    INVALID_REQUEST,
}

data class SpeechAdapterStartResult(
    val disposition: SpeechAdapterStartDisposition,
    val operationOwned: Boolean,
) {
    init {
        require(operationOwned == (disposition == SpeechAdapterStartDisposition.ACCEPTED))
    }
}

enum class SpeechAdapterCancelDisposition {
    REQUESTED,
    ALREADY_CANCELLING,
    NOT_ACTIVE,
    WRONG_OPERATION,
}

enum class SpeechRecognitionFailure {
    AUDIO_INPUT_UNAVAILABLE,
    SERVICE_UNAVAILABLE,
    UNSUPPORTED_LANGUAGE,
    TIMEOUT,
    INTERNAL_FAILURE,
}

enum class SpeechSynthesisFailure {
    SERVICE_UNAVAILABLE,
    UNSUPPORTED_LANGUAGE,
    AUDIO_OUTPUT_UNAVAILABLE,
    TIMEOUT,
    INTERNAL_FAILURE,
}

sealed interface SpeechRecognitionEvent {
    val operationId: SpeechOperationId
    val sessionId: AudioSessionId
    val occurredAt: AudioMonotonicTimeMs

    data class Started(
        override val operationId: SpeechOperationId,
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : SpeechRecognitionEvent

    data class FinalTranscript(
        override val operationId: SpeechOperationId,
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        val transcript: EphemeralSpeechTranscript,
    ) : SpeechRecognitionEvent {
        override fun toString(): String = "SpeechRecognitionEvent.FinalTranscript(" +
            "operation=$operationId, session=$sessionId, at=${occurredAt.value}, transcript=redacted)"
    }

    data class NoMatch(
        override val operationId: SpeechOperationId,
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : SpeechRecognitionEvent

    data class Cancelled(
        override val operationId: SpeechOperationId,
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : SpeechRecognitionEvent

    data class Failed(
        override val operationId: SpeechOperationId,
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        val reason: SpeechRecognitionFailure,
    ) : SpeechRecognitionEvent
}

sealed interface SpeechSynthesisEvent {
    val operationId: SpeechOperationId
    val sessionId: AudioSessionId
    val occurredAt: AudioMonotonicTimeMs

    data class Started(
        override val operationId: SpeechOperationId,
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : SpeechSynthesisEvent

    data class Completed(
        override val operationId: SpeechOperationId,
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : SpeechSynthesisEvent

    data class Cancelled(
        override val operationId: SpeechOperationId,
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : SpeechSynthesisEvent

    data class Failed(
        override val operationId: SpeechOperationId,
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        val reason: SpeechSynthesisFailure,
    ) : SpeechSynthesisEvent
}

fun interface SpeechRecognitionListener {
    fun onEvent(event: SpeechRecognitionEvent)
}

fun interface SpeechSynthesisListener {
    fun onEvent(event: SpeechSynthesisEvent)
}

interface SpeechRecognitionPort {
    fun start(request: SpeechRecognitionRequest, listener: SpeechRecognitionListener): SpeechAdapterStartResult

    fun cancel(operationId: SpeechOperationId): SpeechAdapterCancelDisposition

    fun snapshot(): SpeechAdapterSnapshot
}

interface SpeechSynthesisPort {
    fun start(request: SpeechSynthesisRequest, listener: SpeechSynthesisListener): SpeechAdapterStartResult

    fun cancel(operationId: SpeechOperationId): SpeechAdapterCancelDisposition

    fun snapshot(): SpeechAdapterSnapshot
}

data class SpeechAdapterSnapshot(
    val active: Boolean,
    val cancelling: Boolean,
    val activeOperationId: SpeechOperationId?,
    val retainsTranscript: Boolean = false,
    val retainsAudio: Boolean = false,
) {
    init {
        require(!cancelling || active)
        require(active == (activeOperationId != null))
        require(!retainsTranscript)
        require(!retainsAudio)
    }
}

enum class SpeechContractError {
    INVALID_OPERATION_ID,
    INVALID_LOCALE,
    INVALID_TEXT,
    INVALID_TRANSCRIPT,
}

sealed interface SpeechContractResult<out T> {
    data class Success<T>(val value: T) : SpeechContractResult<T>
    data class Failure(val error: SpeechContractError) : SpeechContractResult<Nothing>
}

private fun validBoundedText(raw: String, maximumCodePoints: Int): Boolean {
    val codePoints = raw.codePoints().toArray()
    return codePoints.isNotEmpty() &&
        codePoints.size <= maximumCodePoints &&
        raw.isNotBlank() &&
        codePoints.none { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }
}
