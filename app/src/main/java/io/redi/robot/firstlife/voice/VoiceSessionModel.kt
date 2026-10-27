package io.redi.robot.firstlife.voice

import java.util.Locale

@JvmInline
value class VoiceSessionRequestId(val value: String) {
    init {
        require(value.isNotBlank()) { "voice request ID must not be blank" }
    }

    override fun toString(): String = "voice-request:[redacted]"
}

data class VoiceSessionRequest(
    val id: VoiceSessionRequestId,
    val startedAtMs: Long,
    val deadlineAtMs: Long,
    val tier: VoiceRecognitionTier = VoiceRecognitionTier.ON_DEVICE,
    val localeTag: String = VoiceCapturePolicy.LOCALE_TAG,
) {
    init {
        require(startedAtMs >= 0L)
        require(deadlineAtMs > startedAtMs)
        require(deadlineAtMs - startedAtMs <= VoiceCapturePolicy.MAX_DURATION_MS)
        require(localeTag == VoiceCapturePolicy.LOCALE_TAG || localeTag == VoiceCapturePolicy.SYSTEM_FALLBACK_LOCALE_TAG)
    }
}

object VoiceCapturePolicy {
    const val LOCALE_TAG = "sq-AL"
    const val SYSTEM_FALLBACK_LOCALE_TAG = "en-US"
    const val MAX_DURATION_MS = 8_000L
    const val MAX_ALTERNATIVES = 3
    const val MAX_TRANSCRIPT_CODE_POINTS = 80
}

enum class VoiceRecognitionTier { ON_DEVICE, SYSTEM_NETWORK_ASSISTED }

enum class VoiceSessionPhase {
    IDLE,
    AWAITING_SYSTEM_CONSENT,
    AWAITING_PERMISSION,
    CHECKING_SUPPORT,
    LISTENING,
    PROCESSING,
    COMPLETED,
    BLOCKED,
}

enum class VoiceSessionBlockedReason {
    PERMISSION_DENIED,
    PERMISSION_PERMANENTLY_DENIED,
    ON_DEVICE_RECOGNIZER_UNAVAILABLE,
    ALBANIAN_MODEL_DOWNLOAD_REQUIRED,
    ALBANIAN_MODEL_UNAVAILABLE,
    NO_MATCH,
    TIMEOUT,
    CANCELLED,
    BACKGROUNDED,
    RECOGNIZER_BUSY,
    AUDIO_UNAVAILABLE,
    NETWORK_ATTEMPT_REJECTED,
    RECOGNIZER_FAILURE,
}

data class VoiceRecognitionEvidence(
    val tier: VoiceRecognitionTier,
    val localeTag: String,
    val alternativeCount: Int,
    val acceptedCodePointCount: Int,
) {
    init {
        require(localeTag == VoiceCapturePolicy.LOCALE_TAG || localeTag == VoiceCapturePolicy.SYSTEM_FALLBACK_LOCALE_TAG)
        require(alternativeCount in 1..VoiceCapturePolicy.MAX_ALTERNATIVES)
        require(acceptedCodePointCount in 1..VoiceCapturePolicy.MAX_TRANSCRIPT_CODE_POINTS)
    }
}

data class VoiceSessionModel(
    val phase: VoiceSessionPhase,
    val activeRequest: VoiceSessionRequest?,
    val recognizerOwned: Boolean,
    val evidence: VoiceRecognitionEvidence?,
    val blockedReason: VoiceSessionBlockedReason?,
) {
    init {
        require((phase == VoiceSessionPhase.BLOCKED) == (blockedReason != null))
        require((phase == VoiceSessionPhase.COMPLETED) == (evidence != null))
        require(!recognizerOwned || phase == VoiceSessionPhase.LISTENING || phase == VoiceSessionPhase.PROCESSING)
    }

    companion object {
        val IDLE = VoiceSessionModel(VoiceSessionPhase.IDLE, null, false, null, null)
    }
}

/** Ephemeral bounded text. It deliberately redacts itself and is never part of [VoiceSessionModel]. */
class VoiceTranscript private constructor(internal val value: String) {
    val codePointCount: Int = value.codePointCount(0, value.length)

    override fun toString(): String = "VoiceTranscript([redacted], codePoints=$codePointCount)"

    companion object {
        internal fun from(raw: String): VoiceTranscript? {
            val normalized = raw.trim()
            if (normalized.isEmpty()) return null
            val points = normalized.codePoints().limit(VoiceCapturePolicy.MAX_TRANSCRIPT_CODE_POINTS.toLong()).toArray()
            return VoiceTranscript(String(points, 0, points.size))
        }
    }
}

class VoiceTranscriptCandidates private constructor(internal val values: List<VoiceTranscript>) {
    override fun toString(): String = "VoiceTranscriptCandidates([redacted], count=${values.size})"

    companion object {
        fun from(rawAlternatives: List<String>): VoiceTranscriptCandidates = VoiceTranscriptCandidates(
            rawAlternatives.asSequence()
                .take(VoiceCapturePolicy.MAX_ALTERNATIVES)
                .mapNotNull(VoiceTranscript::from)
                .toList(),
        )
    }
}

enum class VoiceRecognizerFailure {
    NO_MATCH,
    SPEECH_TIMEOUT,
    BUSY,
    AUDIO,
    NETWORK,
    CLIENT_CANCELLED,
    OTHER,
}

sealed interface VoiceSessionEvent {
    data class ExplicitStart(val request: VoiceSessionRequest) : VoiceSessionEvent
    data class ExplicitSystemFallbackReview(val request: VoiceSessionRequest) : VoiceSessionEvent
    data class SystemFallbackConsent(
        val requestId: VoiceSessionRequestId,
        val consentedAtMs: Long,
    ) : VoiceSessionEvent {
        init {
            require(consentedAtMs >= 0L)
        }
    }
    data class PermissionResult(
        val requestId: VoiceSessionRequestId,
        val granted: Boolean,
        val canAskAgain: Boolean,
    ) : VoiceSessionEvent
    data class SupportResult(
        val requestId: VoiceSessionRequestId,
        val support: VoiceOnDeviceSupport,
    ) : VoiceSessionEvent
    data class CaptureStarted(val requestId: VoiceSessionRequestId) : VoiceSessionEvent
    data class SpeechEnded(val requestId: VoiceSessionRequestId) : VoiceSessionEvent
    data class Results(
        val requestId: VoiceSessionRequestId,
        val candidates: VoiceTranscriptCandidates,
    ) : VoiceSessionEvent
    data class Failure(
        val requestId: VoiceSessionRequestId,
        val failure: VoiceRecognizerFailure,
    ) : VoiceSessionEvent
    data class DeadlineElapsed(val requestId: VoiceSessionRequestId, val nowMs: Long) : VoiceSessionEvent
    data class Cancel(val requestId: VoiceSessionRequestId) : VoiceSessionEvent
    data class Backgrounded(val requestId: VoiceSessionRequestId) : VoiceSessionEvent
    data object Reset : VoiceSessionEvent
}

enum class VoiceOnDeviceSupport {
    SUPPORTED,
    RECOGNIZER_UNAVAILABLE,
    ALBANIAN_MODEL_DOWNLOAD_REQUIRED,
    ALBANIAN_MODEL_UNAVAILABLE,
}

sealed interface VoiceSessionEffect {
    data class RequestMicrophonePermission(val requestId: VoiceSessionRequestId) : VoiceSessionEffect
    data class CheckOnDeviceSupport(val requestId: VoiceSessionRequestId) : VoiceSessionEffect
    data class StartOnDeviceCapture(val request: VoiceSessionRequest) : VoiceSessionEffect
    data class StartSystemCapture(val request: VoiceSessionRequest) : VoiceSessionEffect
    data class ReleaseRecognizer(val requestId: VoiceSessionRequestId) : VoiceSessionEffect
    class DeliverTranscript(
        val requestId: VoiceSessionRequestId,
        internal val transcript: VoiceTranscript,
    ) : VoiceSessionEffect {
        override fun toString(): String = "DeliverTranscript(requestId=$requestId, transcript=[redacted])"
    }
}

enum class VoiceReductionStatus {
    APPLIED,
    IDEMPOTENT,
    IGNORED_WRONG_REQUEST,
    IGNORED_INVALID_STATE,
    IGNORED_EARLY,
}

data class VoiceSessionReduction(
    val model: VoiceSessionModel,
    val effects: List<VoiceSessionEffect>,
    val status: VoiceReductionStatus,
)

fun String.isAlbanianLocaleTag(): Boolean =
    Locale.forLanguageTag(replace('_', '-')).toLanguageTag().equals(VoiceCapturePolicy.LOCALE_TAG, ignoreCase = true)
