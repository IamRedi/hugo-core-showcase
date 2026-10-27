package io.redi.robot.firstlife.interaction

import io.redi.robot.hugo.identity.HugoIdentity
import java.text.Normalizer
import java.util.UUID

@JvmInline
value class FirstLifeSessionId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun fromUuid(uuid: UUID): FirstLifeSessionId = FirstLifeSessionId(uuid.toString())
    }
}

@JvmInline
value class FirstLifeEventSequence(val value: Long) {
    init {
        require(value > 0L) { "event sequence must be positive" }
    }
}

@JvmInline
value class FirstLifeMonotonicTimeMs(val value: Long) {
    init {
        require(value >= 0L) { "monotonic time must be non-negative" }
    }
}

enum class FirstLifeRequestKind { INTRODUCTION_SPEECH, NAME_INPUT, GREETING_SPEECH }

data class FirstLifeRequestReference(
    val sessionId: FirstLifeSessionId,
    val kind: FirstLifeRequestKind,
    val ordinal: Long,
) {
    init {
        require(ordinal > 0L) { "request ordinal must be positive" }
    }
}

enum class FirstLifeInteractionState {
    READY,
    INTRODUCTION_READY,
    INTRO_SPEAKING,
    AWAITING_LISTEN_START,
    NAME_INPUT_REQUESTED,
    LISTENING,
    GREETING_READY,
    GREETING_SPEAKING,
    READY_FOR_NEXT_PHASE,
    CANCELLING,
    BLOCKED,
    FAILED,
}

enum class FirstLifeBodyStatus {
    UNAVAILABLE,
    AVAILABLE,
    SELECTED,
    LOST,
    PROVIDER_BLOCKED,
}

enum class FirstLifeBlockedReason {
    IDENTITY_UNAVAILABLE,
    INTRODUCTION_CANCELLED,
    SPEECH_OUTPUT_UNAVAILABLE,
    SPEECH_CANCELLED,
    NAME_INPUT_TIMEOUT,
    NAME_INPUT_CANCELLED,
    INVALID_NAME,
    AMBIGUOUS_NAME,
    NO_SPEECH,
}

enum class FirstLifeFatalReason { UNRECOVERABLE_INTERACTION_ERROR }

enum class FirstLifeTextKey {
    INTRODUCTION_SQ_AL,
    NAME_GREETING_SQ_AL,
}

data class FirstLifeSpeechText(
    val key: FirstLifeTextKey,
    val text: String,
)

object FirstLifeDialogue {
    const val INTRODUCTION = "Përshëndetje. Unë jam HUGO. Kush je ti?"
    const val NAME_GREETING_TEMPLATE = "Gëzohem që të njoh, %s."

    fun introduction(): FirstLifeSpeechText =
        FirstLifeSpeechText(FirstLifeTextKey.INTRODUCTION_SQ_AL, INTRODUCTION)

    fun greeting(name: FirstLifeSessionName): FirstLifeSpeechText =
        FirstLifeSpeechText(FirstLifeTextKey.NAME_GREETING_SQ_AL, NAME_GREETING_TEMPLATE.replace("%s", name.value))
}

@JvmInline
value class FirstLifeSessionName internal constructor(val value: String) {
    override fun toString(): String = value
}

enum class FirstLifeNameInvalidReason {
    BLANK,
    TOO_LONG,
    CONTROL_CHARACTER,
    MALFORMED,
    TOO_MANY_COMPONENTS,
}

sealed interface FirstLifeNameValidation {
    data class Valid(val name: FirstLifeSessionName) : FirstLifeNameValidation
    data class Invalid(val reason: FirstLifeNameInvalidReason) : FirstLifeNameValidation
}

object FirstLifeNameValidator {
    const val MAX_INPUT_CODE_POINTS = 80
    const val MAX_NAME_CODE_POINTS = 40
    const val MAX_COMPONENTS = 3

    fun validate(raw: String): FirstLifeNameValidation {
        if (raw.isBlank()) return FirstLifeNameValidation.Invalid(FirstLifeNameInvalidReason.BLANK)
        if (raw.codePointCount(0, raw.length) > MAX_INPUT_CODE_POINTS) {
            return FirstLifeNameValidation.Invalid(FirstLifeNameInvalidReason.TOO_LONG)
        }
        if (raw.codePoints().anyMatch { Character.isISOControl(it) }) {
            return FirstLifeNameValidation.Invalid(FirstLifeNameInvalidReason.CONTROL_CHARACTER)
        }

        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
            .trim()
            .replace(Regex(" +"), " ")
        val points = normalized.codePoints().toArray()
        if (points.isEmpty()) return FirstLifeNameValidation.Invalid(FirstLifeNameInvalidReason.BLANK)
        if (points.size > MAX_NAME_CODE_POINTS) {
            return FirstLifeNameValidation.Invalid(FirstLifeNameInvalidReason.TOO_LONG)
        }
        if (!Character.isLetter(points.first()) || !Character.isLetter(points.last())) {
            return FirstLifeNameValidation.Invalid(FirstLifeNameInvalidReason.MALFORMED)
        }
        points.forEachIndexed { index, point ->
            if (Character.isLetter(point)) return@forEachIndexed
            val separator = point == ' '.code || point == '-'.code || point == '\''.code || point == '’'.code
            val surroundedByLetters = index > 0 && index < points.lastIndex &&
                Character.isLetter(points[index - 1]) && Character.isLetter(points[index + 1])
            if (!separator || !surroundedByLetters) {
                return FirstLifeNameValidation.Invalid(FirstLifeNameInvalidReason.MALFORMED)
            }
        }
        if (normalized.split(' ').size > MAX_COMPONENTS) {
            return FirstLifeNameValidation.Invalid(FirstLifeNameInvalidReason.TOO_MANY_COMPONENTS)
        }
        return FirstLifeNameValidation.Valid(FirstLifeSessionName(normalized))
    }
}

enum class FirstLifeNameInputSource { ON_DEVICE_STT, SYSTEM_RECOGNIZER_FALLBACK, MANUAL_FALLBACK }

sealed interface FirstLifeNameInputResult {
    data class Success(val rawText: String, val source: FirstLifeNameInputSource) : FirstLifeNameInputResult
    data object NoSpeech : FirstLifeNameInputResult
    data object Ambiguous : FirstLifeNameInputResult
    data object Cancelled : FirstLifeNameInputResult
    data object TimedOut : FirstLifeNameInputResult
}

data class FirstLifeActiveRequest(
    val reference: FirstLifeRequestReference,
    val deadline: FirstLifeMonotonicTimeMs? = null,
)

data class FirstLifeInteractionModel(
    val hugoIdentity: HugoIdentity?,
    val sessionId: FirstLifeSessionId,
    val state: FirstLifeInteractionState,
    val lastSequence: Long,
    val lastOccurredAt: FirstLifeMonotonicTimeMs,
    val sessionName: FirstLifeSessionName?,
    val activeRequest: FirstLifeActiveRequest?,
    val bodyStatus: FirstLifeBodyStatus,
    val blockedReason: FirstLifeBlockedReason?,
    val fatalReason: FirstLifeFatalReason?,
) {
    init {
        require(lastSequence >= 0L)
        require((state == FirstLifeInteractionState.BLOCKED) == (blockedReason != null))
        require((state == FirstLifeInteractionState.FAILED) == (fatalReason != null))
        require(sessionName == null || hugoIdentity != null)
    }

    companion object {
        fun start(
            hugoIdentity: HugoIdentity?,
            sessionId: FirstLifeSessionId,
            startedAt: FirstLifeMonotonicTimeMs,
        ): FirstLifeInteractionModel = FirstLifeInteractionModel(
            hugoIdentity = hugoIdentity,
            sessionId = sessionId,
            state = if (hugoIdentity == null) FirstLifeInteractionState.BLOCKED else FirstLifeInteractionState.READY,
            lastSequence = 0,
            lastOccurredAt = startedAt,
            sessionName = null,
            activeRequest = null,
            bodyStatus = FirstLifeBodyStatus.UNAVAILABLE,
            blockedReason = if (hugoIdentity == null) FirstLifeBlockedReason.IDENTITY_UNAVAILABLE else null,
            fatalReason = null,
        )
    }
}

enum class FirstLifePresentationIntent {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    BODY_READY,
    BLOCKED,
    ERROR,
}

object FirstLifePresentationProjection {
    fun project(model: FirstLifeInteractionModel): FirstLifePresentationIntent = when (model.state) {
        FirstLifeInteractionState.INTRO_SPEAKING,
        FirstLifeInteractionState.GREETING_SPEAKING,
        -> FirstLifePresentationIntent.SPEAKING
        FirstLifeInteractionState.LISTENING -> FirstLifePresentationIntent.LISTENING
        FirstLifeInteractionState.GREETING_READY,
        FirstLifeInteractionState.CANCELLING,
        -> FirstLifePresentationIntent.THINKING
        FirstLifeInteractionState.BLOCKED -> FirstLifePresentationIntent.BLOCKED
        FirstLifeInteractionState.FAILED -> FirstLifePresentationIntent.ERROR
        FirstLifeInteractionState.READY,
        FirstLifeInteractionState.INTRODUCTION_READY,
        FirstLifeInteractionState.AWAITING_LISTEN_START,
        FirstLifeInteractionState.NAME_INPUT_REQUESTED,
        FirstLifeInteractionState.READY_FOR_NEXT_PHASE,
        -> if (model.bodyStatus == FirstLifeBodyStatus.AVAILABLE || model.bodyStatus == FirstLifeBodyStatus.SELECTED) {
            FirstLifePresentationIntent.BODY_READY
        } else {
            FirstLifePresentationIntent.IDLE
        }
    }
}

sealed interface FirstLifeInteractionEffect {
    data class SpeakText(
        val request: FirstLifeRequestReference,
        val speech: FirstLifeSpeechText,
    ) : FirstLifeInteractionEffect

    data class RequestNameInput(
        val request: FirstLifeRequestReference,
        val deadline: FirstLifeMonotonicTimeMs,
    ) : FirstLifeInteractionEffect

    data class CancelNameInput(val request: FirstLifeRequestReference) : FirstLifeInteractionEffect
    data class StopSpeaking(val request: FirstLifeRequestReference) : FirstLifeInteractionEffect
    data class PresentState(val intent: FirstLifePresentationIntent) : FirstLifeInteractionEffect
    data class NameRetryRequired(val reason: FirstLifeBlockedReason) : FirstLifeInteractionEffect
    data class ProceedToObservationPhase(val sessionId: FirstLifeSessionId) : FirstLifeInteractionEffect
}

sealed interface FirstLifeInteractionEvent {
    val sessionId: FirstLifeSessionId
    val sequence: FirstLifeEventSequence
    val occurredAt: FirstLifeMonotonicTimeMs

    data class StartIntroduction(
        override val sessionId: FirstLifeSessionId,
        override val sequence: FirstLifeEventSequence,
        override val occurredAt: FirstLifeMonotonicTimeMs,
    ) : FirstLifeInteractionEvent

    data class SpeechStarted(
        override val sessionId: FirstLifeSessionId,
        override val sequence: FirstLifeEventSequence,
        override val occurredAt: FirstLifeMonotonicTimeMs,
        val request: FirstLifeRequestReference,
    ) : FirstLifeInteractionEvent

    data class SpeechCompleted(
        override val sessionId: FirstLifeSessionId,
        override val sequence: FirstLifeEventSequence,
        override val occurredAt: FirstLifeMonotonicTimeMs,
        val request: FirstLifeRequestReference,
    ) : FirstLifeInteractionEvent

    data class SpeechUnavailable(
        override val sessionId: FirstLifeSessionId,
        override val sequence: FirstLifeEventSequence,
        override val occurredAt: FirstLifeMonotonicTimeMs,
        val request: FirstLifeRequestReference,
    ) : FirstLifeInteractionEvent

    data class SpeechCancelled(
        override val sessionId: FirstLifeSessionId,
        override val sequence: FirstLifeEventSequence,
        override val occurredAt: FirstLifeMonotonicTimeMs,
        val request: FirstLifeRequestReference,
    ) : FirstLifeInteractionEvent

    data class RequestNameInput(
        override val sessionId: FirstLifeSessionId,
        override val sequence: FirstLifeEventSequence,
        override val occurredAt: FirstLifeMonotonicTimeMs,
        val deadline: FirstLifeMonotonicTimeMs,
    ) : FirstLifeInteractionEvent

    data class NameInputStarted(
        override val sessionId: FirstLifeSessionId,
        override val sequence: FirstLifeEventSequence,
        override val occurredAt: FirstLifeMonotonicTimeMs,
        val request: FirstLifeRequestReference,
    ) : FirstLifeInteractionEvent

    data class NameInputCompleted(
        override val sessionId: FirstLifeSessionId,
        override val sequence: FirstLifeEventSequence,
        override val occurredAt: FirstLifeMonotonicTimeMs,
        val request: FirstLifeRequestReference,
        val result: FirstLifeNameInputResult,
    ) : FirstLifeInteractionEvent

    data class NameInputDeadlineElapsed(
        override val sessionId: FirstLifeSessionId,
        override val sequence: FirstLifeEventSequence,
        override val occurredAt: FirstLifeMonotonicTimeMs,
        val request: FirstLifeRequestReference,
    ) : FirstLifeInteractionEvent

    data class BodyStatusChanged(
        override val sessionId: FirstLifeSessionId,
        override val sequence: FirstLifeEventSequence,
        override val occurredAt: FirstLifeMonotonicTimeMs,
        val status: FirstLifeBodyStatus,
    ) : FirstLifeInteractionEvent

    data class CancelSession(
        override val sessionId: FirstLifeSessionId,
        override val sequence: FirstLifeEventSequence,
        override val occurredAt: FirstLifeMonotonicTimeMs,
    ) : FirstLifeInteractionEvent

    data class CancellationCompleted(
        override val sessionId: FirstLifeSessionId,
        override val sequence: FirstLifeEventSequence,
        override val occurredAt: FirstLifeMonotonicTimeMs,
    ) : FirstLifeInteractionEvent

    data class ResetSession(
        override val sessionId: FirstLifeSessionId,
        override val sequence: FirstLifeEventSequence,
        override val occurredAt: FirstLifeMonotonicTimeMs,
        val newSessionId: FirstLifeSessionId,
    ) : FirstLifeInteractionEvent

    data class FatalFailure(
        override val sessionId: FirstLifeSessionId,
        override val sequence: FirstLifeEventSequence,
        override val occurredAt: FirstLifeMonotonicTimeMs,
        val reason: FirstLifeFatalReason = FirstLifeFatalReason.UNRECOVERABLE_INTERACTION_ERROR,
    ) : FirstLifeInteractionEvent
}

enum class FirstLifeReductionStatus {
    APPLIED,
    IDEMPOTENT,
    IGNORED_STALE,
    IGNORED_WRONG_SESSION,
    IGNORED_WRONG_REQUEST,
    IGNORED_INVALID_STATE,
    IGNORED_LATE,
}

data class FirstLifeInteractionReduction(
    val model: FirstLifeInteractionModel,
    val effects: List<FirstLifeInteractionEffect>,
    val status: FirstLifeReductionStatus,
)
