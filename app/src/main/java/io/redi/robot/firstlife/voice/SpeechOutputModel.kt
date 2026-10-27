package io.redi.robot.firstlife.voice

@JvmInline
value class SpeechOutputRequestId(val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = "SpeechOutputRequestId(redacted)"
}

enum class SpeechOutputKind { INTRODUCTION, SESSION_NAME_GREETING }
enum class SpeechOutputPhase { IDLE, CHECKING_SUPPORT, QUEUED, SPEAKING, COMPLETED, BLOCKED }
enum class LocalTtsSupport { SUPPORTED, ENGINE_UNAVAILABLE, ALBANIAN_LANGUAGE_UNAVAILABLE, VOICE_DATA_MISSING }
enum class SpeechOutputBlockedReason {
    ENGINE_UNAVAILABLE,
    ALBANIAN_LANGUAGE_UNAVAILABLE,
    VOICE_DATA_MISSING,
    AUDIO_FOCUS_DENIED,
    SYNTHESIS_FAILED,
    CANCELLED,
    BACKGROUNDED,
    INTERRUPTED,
}

@JvmInline
value class LocalSpeechText private constructor(val value: String) {
    override fun toString(): String = "LocalSpeechText(redacted)"

    companion object {
        const val MAX_CODE_POINTS = 96
        fun from(value: String): LocalSpeechText {
            require(value.isNotBlank())
            require(value.codePointCount(0, value.length) <= MAX_CODE_POINTS)
            require(value.codePoints().noneMatch(Character::isISOControl))
            return LocalSpeechText(value)
        }
    }
}

data class SpeechOutputRequest(
    val id: SpeechOutputRequestId,
    val ordinal: Long,
    val kind: SpeechOutputKind,
    val localeTag: String = LOCAL_ALBANIAN_LOCALE,
    val text: LocalSpeechText,
) {
    init {
        require(ordinal > 0)
        require(localeTag == LOCAL_ALBANIAN_LOCALE)
    }

    override fun toString(): String =
        "SpeechOutputRequest(id=redacted, ordinal=$ordinal, kind=$kind, localeTag=$localeTag, text=redacted)"
}

data class SpeechOutputEvidence(
    val kind: SpeechOutputKind,
    val localeTag: String,
    val localVoice: Boolean,
)

data class SpeechOutputModel(
    val phase: SpeechOutputPhase,
    val activeRequest: SpeechOutputRequest? = null,
    val blockedReason: SpeechOutputBlockedReason? = null,
    val evidence: SpeechOutputEvidence? = null,
) {
    init {
        require((phase == SpeechOutputPhase.BLOCKED) == (blockedReason != null))
        require(activeRequest == null || phase in ACTIVE_PHASES)
    }

    override fun toString(): String =
        "SpeechOutputModel(phase=$phase, active=${activeRequest != null}, blockedReason=$blockedReason, evidence=$evidence)"

    companion object {
        const val LOCAL_ALBANIAN_LOCALE = "sq-AL"
        val ACTIVE_PHASES = setOf(SpeechOutputPhase.CHECKING_SUPPORT, SpeechOutputPhase.QUEUED, SpeechOutputPhase.SPEAKING)
        val IDLE = SpeechOutputModel(SpeechOutputPhase.IDLE)
    }
}

const val LOCAL_ALBANIAN_LOCALE = "sq-AL"
