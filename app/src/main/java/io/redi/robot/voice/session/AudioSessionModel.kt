package io.redi.robot.voice.session

import java.util.UUID

@JvmInline
value class AudioSessionId private constructor(val value: String) {
    override fun toString(): String = "audio-session:[redacted]"

    companion object {
        fun fromUuid(uuid: UUID): AudioSessionId = AudioSessionId(uuid.toString())

        fun parse(raw: String): AudioSessionModelResult<AudioSessionId> {
            val canonical = runCatching { UUID.fromString(raw).toString() }.getOrNull()
            return if (canonical == raw) {
                AudioSessionModelResult.Success(AudioSessionId(raw))
            } else {
                AudioSessionModelResult.Failure(AudioSessionModelError.INVALID_SESSION_ID)
            }
        }
    }
}

@JvmInline
value class AudioMonotonicTimeMs(val value: Long) {
    init {
        require(value >= 0L) { "monotonic time must be non-negative" }
    }
}

data class AudioSessionPolicy(
    val activationTimeoutMs: Long = 5_000L,
    val listeningTimeoutMs: Long = 15_000L,
    val thinkingTimeoutMs: Long = 30_000L,
    val speakingTimeoutMs: Long = 30_000L,
    val cancellationTimeoutMs: Long = 3_000L,
    val overallTimeoutMs: Long = 90_000L,
) {
    init {
        val phaseTimeouts = listOf(
            activationTimeoutMs,
            listeningTimeoutMs,
            thinkingTimeoutMs,
            speakingTimeoutMs,
            cancellationTimeoutMs,
        )
        require(phaseTimeouts.all { it in 1..MAX_PHASE_TIMEOUT_MS })
        require(overallTimeoutMs in 1..MAX_OVERALL_TIMEOUT_MS)
    }

    internal fun timeoutFor(phase: AudioSessionPhase): Long = when (phase) {
        AudioSessionPhase.ACTIVATING -> activationTimeoutMs
        AudioSessionPhase.LISTENING -> listeningTimeoutMs
        AudioSessionPhase.THINKING -> thinkingTimeoutMs
        AudioSessionPhase.SPEAKING -> speakingTimeoutMs
        AudioSessionPhase.CANCELLING -> cancellationTimeoutMs
        AudioSessionPhase.IDLE,
        AudioSessionPhase.COMPLETED,
        AudioSessionPhase.CANCELLED,
        AudioSessionPhase.ERROR,
        -> error("terminal phases have no timeout")
    }

    companion object {
        const val MAX_PHASE_TIMEOUT_MS = 120_000L
        const val MAX_OVERALL_TIMEOUT_MS = 300_000L
    }
}

enum class AudioSessionPhase {
    IDLE,
    ACTIVATING,
    LISTENING,
    THINKING,
    SPEAKING,
    CANCELLING,
    COMPLETED,
    CANCELLED,
    ERROR,
    ;

    val isActive: Boolean
        get() = this in setOf(ACTIVATING, LISTENING, THINKING, SPEAKING, CANCELLING)

    val isTerminal: Boolean
        get() = this in setOf(COMPLETED, CANCELLED, ERROR)
}

enum class AudioSessionCancellationReason {
    USER_REQUEST,
    BACKGROUNDED,
    INTERRUPTED,
    AUDIO_FOCUS_LOST,
    PHASE_TIMEOUT,
}

enum class AudioSessionFailureReason {
    ACTIVATION_FAILED,
    LISTENING_FAILED,
    THINKING_FAILED,
    SPEAKING_FAILED,
    RESOURCE_FAILURE,
    UNKNOWN_FAILURE,
}

sealed interface AudioSessionTerminal {
    val occurredAt: AudioMonotonicTimeMs

    data class Completed(
        override val occurredAt: AudioMonotonicTimeMs,
    ) : AudioSessionTerminal

    data class Cancelled(
        val reason: AudioSessionCancellationReason,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : AudioSessionTerminal

    data class Failed(
        val reason: AudioSessionFailureReason,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : AudioSessionTerminal

    data class TimedOut(
        val phase: AudioSessionPhase,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : AudioSessionTerminal

    data class CancellationTimedOut(
        override val occurredAt: AudioMonotonicTimeMs,
    ) : AudioSessionTerminal
}

data class AudioSessionModel internal constructor(
    val phase: AudioSessionPhase,
    val sessionId: AudioSessionId?,
    val startedAt: AudioMonotonicTimeMs?,
    val phaseEnteredAt: AudioMonotonicTimeMs?,
    val phaseDeadlineAt: AudioMonotonicTimeMs?,
    val overallDeadlineAt: AudioMonotonicTimeMs?,
    val cancellationReason: AudioSessionCancellationReason?,
    val timedOutPhase: AudioSessionPhase?,
    val terminal: AudioSessionTerminal?,
) {
    init {
        val idle = phase == AudioSessionPhase.IDLE
        require(idle == (sessionId == null))
        require(idle == (startedAt == null))
        require(idle == (phaseEnteredAt == null))
        require(idle == (overallDeadlineAt == null))
        require(phase.isActive == (phaseDeadlineAt != null))
        require((phase == AudioSessionPhase.CANCELLING) == (cancellationReason != null))
        require((cancellationReason == AudioSessionCancellationReason.PHASE_TIMEOUT) == (timedOutPhase != null))
        require(phase.isTerminal == (terminal != null))
        if (!idle) {
            require(startedAt!!.value <= phaseEnteredAt!!.value)
            require(startedAt.value < overallDeadlineAt!!.value)
            phaseDeadlineAt?.let {
                require(it.value >= phaseEnteredAt.value)
                if (phase != AudioSessionPhase.CANCELLING) require(it.value <= overallDeadlineAt.value)
            }
        }
        when (phase) {
            AudioSessionPhase.COMPLETED -> require(terminal is AudioSessionTerminal.Completed)
            AudioSessionPhase.CANCELLED -> require(terminal is AudioSessionTerminal.Cancelled)
            AudioSessionPhase.ERROR -> require(
                terminal is AudioSessionTerminal.Failed ||
                    terminal is AudioSessionTerminal.TimedOut ||
                    terminal is AudioSessionTerminal.CancellationTimedOut,
            )
            else -> Unit
        }
    }

    override fun toString(): String = "AudioSessionModel(" +
        "phase=$phase, session=${if (sessionId == null) "none" else "redacted"}, " +
        "deadline=${phaseDeadlineAt?.value}, terminal=${terminal?.javaClass?.simpleName})"

    companion object {
        val IDLE = AudioSessionModel(
            AudioSessionPhase.IDLE,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
        )
    }
}

sealed interface AudioSessionEvent {
    val sessionId: AudioSessionId
    val occurredAt: AudioMonotonicTimeMs

    data class Activate(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : AudioSessionEvent

    data class ActivationReady(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : AudioSessionEvent

    data class ListeningCompleted(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : AudioSessionEvent

    data class ThinkingCompleted(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : AudioSessionEvent

    data class SpeakingCompleted(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : AudioSessionEvent

    data class Cancel(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        val reason: AudioSessionCancellationReason,
    ) : AudioSessionEvent {
        init {
            require(reason != AudioSessionCancellationReason.PHASE_TIMEOUT)
        }
    }

    data class CancellationCompleted(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : AudioSessionEvent

    data class Failure(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        val reason: AudioSessionFailureReason,
    ) : AudioSessionEvent

    data class DeadlineElapsed(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : AudioSessionEvent

    data class Reset(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : AudioSessionEvent
}

sealed interface AudioSessionEffect {
    val sessionId: AudioSessionId

    data class RequestActivation(override val sessionId: AudioSessionId) : AudioSessionEffect
    data class RequestListening(override val sessionId: AudioSessionId) : AudioSessionEffect
    data class RequestThinking(override val sessionId: AudioSessionId) : AudioSessionEffect
    data class RequestSpeaking(override val sessionId: AudioSessionId) : AudioSessionEffect

    data class CancelPhase(
        override val sessionId: AudioSessionId,
        val interruptedPhase: AudioSessionPhase,
        val reason: AudioSessionCancellationReason,
    ) : AudioSessionEffect

    data class ReleaseSession(override val sessionId: AudioSessionId) : AudioSessionEffect
}

enum class AudioSessionReductionStatus {
    APPLIED,
    IDEMPOTENT,
    IGNORED_WRONG_SESSION,
    IGNORED_INVALID_STATE,
    IGNORED_STALE_TIME,
    IGNORED_EARLY,
}

data class AudioSessionReduction(
    val model: AudioSessionModel,
    val effects: List<AudioSessionEffect>,
    val status: AudioSessionReductionStatus,
)

enum class AudioSessionModelError { INVALID_SESSION_ID }

sealed interface AudioSessionModelResult<out T> {
    data class Success<T>(val value: T) : AudioSessionModelResult<T>
    data class Failure(val error: AudioSessionModelError) : AudioSessionModelResult<Nothing>
}
