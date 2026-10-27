package io.redi.robot.conversation.runtime

import io.redi.robot.ai.gateway.GatewayClientTerminalStatus
import io.redi.robot.gateway.contract.GatewayBudget
import io.redi.robot.interaction.localintent.LocalIntentRouteStatus
import io.redi.robot.voice.api.SpeechText
import io.redi.robot.voice.session.AudioMonotonicTimeMs
import io.redi.robot.voice.session.AudioSessionCancellationReason
import io.redi.robot.voice.session.AudioSessionId
import io.redi.robot.voice.session.AudioSessionPhase
import io.redi.robot.voice.session.AudioSessionPolicy
import java.util.Collections

/**
 * H056 turn-level outcome. It classifies how one activation-gated conversation turn ended and
 * implies no provider selection, authorization, capability execution, or memory write.
 */
enum class ConversationTurnOutcome {
    LOCAL_STOP,
    LOCAL_PROPOSED,
    CLARIFICATION_REQUESTED,
    LOCAL_FALLBACK,
    NO_RECOGNIZED_INPUT,
    AI_COMPLETED,
    AI_FALLBACK,
    CANCELLED,
    ERROR,
    ;
}

/** Typed reason an AI turn degraded to a locally spoken fallback message. */
enum class ConversationTurnFallbackReason {
    START_REJECTED,
    CONTEXT_REJECTED,
    POLICY_BLOCKED,
    TRANSPORT_FAILURE,
    REFUSED,
    GENERATION_FAILED,
    EMPTY_OUTPUT,
    OUTPUT_EXCEEDED,
    GENERATION_TIMEOUT,
    PROTOCOL_ERROR,
    ;
}

/** Typed reason a local route terminated the turn without AI generation. */
enum class ConversationLocalFallbackReason {
    UNSUPPORTED_LOCALE,
    INVALID_INPUT,
    ;
}

/**
 * Host-supplied bounded fallback texts. H056 defines no product copy: every spoken fallback is
 * injected, validated, and redacted in diagnostics.
 */
class ConversationFallbackMessages private constructor(
    val clarification: SpeechText,
    val unsupportedLocale: SpeechText,
    val invalidRoute: SpeechText,
    val providerUnavailable: SpeechText,
    val providerLost: SpeechText,
    val generationFailed: SpeechText,
    val generationTimedOut: SpeechText,
) {
    override fun toString(): String = "ConversationFallbackMessages(content=[redacted])"

    companion object {
        fun create(
            clarification: SpeechText,
            unsupportedLocale: SpeechText,
            invalidRoute: SpeechText,
            providerUnavailable: SpeechText,
            providerLost: SpeechText,
            generationFailed: SpeechText,
            generationTimedOut: SpeechText,
        ): ConversationFallbackMessages = ConversationFallbackMessages(
            clarification,
            unsupportedLocale,
            invalidRoute,
            providerUnavailable,
            providerLost,
            generationFailed,
            generationTimedOut,
        )
    }
}

/**
 * Turn timing policy. Phase deadlines reuse the H047 session policy; operation deadlines are
 * bounded by the H049 speech contracts; the AI request budget is bounded by the H051 contract.
 */
data class ConversationTurnPolicy(
    val sessionPolicy: AudioSessionPolicy = AudioSessionPolicy(),
    val recognitionDeadlineMs: Long = 15_000L,
    val synthesisDeadlineMs: Long = 30_000L,
    val gatewayBudget: GatewayBudget = defaultGatewayBudget(),
) {
    init {
        require(recognitionDeadlineMs in 1..MAX_RECOGNITION_DEADLINE_MS)
        require(synthesisDeadlineMs in 1..MAX_SYNTHESIS_DEADLINE_MS)
    }

    companion object {
        const val MAX_RECOGNITION_DEADLINE_MS = 30_000L
        const val MAX_SYNTHESIS_DEADLINE_MS = 60_000L

        private fun defaultGatewayBudget(): GatewayBudget = when (
            val budget = GatewayBudget.create(maximumOutputUnits = 512, timeoutMs = 30_000L)
        ) {
            is io.redi.robot.gateway.contract.GatewayContractResult.Success -> budget.value
            is io.redi.robot.gateway.contract.GatewayContractResult.Failure ->
                error("Default gateway budget is invalid")
        }
    }
}

/** Content-free per-turn latency evidence. Every value is an injected monotonic millisecond. */
class ConversationTurnLatency internal constructor(
    marks: Map<ConversationTurnMark, Long>,
) {
    val marks: Map<ConversationTurnMark, Long> = Collections.unmodifiableMap(marks.toMap())

    val totalMs: Long? = duration(ConversationTurnMark.ACTIVATED, ConversationTurnMark.TURN_ENDED)
    val listeningMs: Long? = duration(ConversationTurnMark.LISTENING_STARTED, ConversationTurnMark.ROUTE_DECIDED)
    val generationMs: Long? = duration(ConversationTurnMark.GENERATION_STARTED, ConversationTurnMark.GENERATION_ENDED)
    val speakingMs: Long? = duration(ConversationTurnMark.SPEAKING_STARTED, ConversationTurnMark.SPEAKING_ENDED)

    override fun toString(): String = "ConversationTurnLatency(" +
        "total=$totalMs, listening=$listeningMs, generation=$generationMs, speaking=$speakingMs, content=[none])"

    private fun duration(from: ConversationTurnMark, to: ConversationTurnMark): Long? {
        val start = marks[from] ?: return null
        val end = marks[to] ?: return null
        return end - start
    }
}

enum class ConversationTurnMark {
    ACTIVATED,
    LISTENING_STARTED,
    ROUTE_DECIDED,
    GENERATION_STARTED,
    GENERATION_ENDED,
    SPEAKING_STARTED,
    SPEAKING_ENDED,
    TURN_ENDED,
    ;
}

/** Final immutable record of one conversation turn. It carries no transcript or synthesis text. */
class ConversationTurnRecord internal constructor(
    val sessionId: AudioSessionId,
    val outcome: ConversationTurnOutcome,
    val routeStatus: LocalIntentRouteStatus?,
    val localFallbackReason: ConversationLocalFallbackReason?,
    val fallbackReason: ConversationTurnFallbackReason?,
    val cancellationReason: AudioSessionCancellationReason?,
    val gatewayTerminal: GatewayClientTerminalStatus?,
    val latency: ConversationTurnLatency,
) {
    val grantsAuthorization: Boolean get() = false
    val executesCapability: Boolean get() = false
    val writesMemory: Boolean get() = false

    fun redactedTrace(): List<String> = listOf(
        "TURN_OUTCOME:${outcome.name}" +
            (routeStatus?.let { ":ROUTE:${it.name}" } ?: "") +
            (localFallbackReason?.let { ":LOCAL_FALLBACK:${it.name}" } ?: "") +
            (fallbackReason?.let { ":FALLBACK:${it.name}" } ?: "") +
            (cancellationReason?.let { ":CANCELLED:${it.name}" } ?: "") +
            (gatewayTerminal?.let { ":GATEWAY:${it.name}" } ?: ""),
        "TURN_LATENCY:total=${latency.totalMs ?: -1},listening=${latency.listeningMs ?: -1}," +
            "generation=${latency.generationMs ?: -1},speaking=${latency.speakingMs ?: -1}",
    )

    override fun toString(): String = "ConversationTurnRecord(" +
        "session=redacted, outcome=$outcome, route=${routeStatus?.name}, " +
        "fallback=${fallbackReason?.name}, gateway=${gatewayTerminal?.name}, " +
        "authorization=false, execution=false, memoryWrites=false)"
}

sealed interface ConversationTurnEvent {
    val sessionId: AudioSessionId
    val occurredAt: AudioMonotonicTimeMs

    val grantsAuthorization: Boolean get() = false
    val executesCapability: Boolean get() = false
    val writesMemory: Boolean get() = false

    data class TurnStarted(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        val bargedIn: Boolean,
    ) : ConversationTurnEvent

    data class TurnRouted(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        val status: LocalIntentRouteStatus,
        val stopPriority: Boolean,
        val matchedCapability: String?,
    ) : ConversationTurnEvent {
        override fun toString(): String = "ConversationTurnEvent.TurnRouted(" +
            "session=redacted, at=${occurredAt.value}, status=$status, stop=$stopPriority, " +
            "capability=${matchedCapability ?: "none"}, transcript=redacted, execution=false)"
    }

    data class GenerationStarted(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        val requestId: String,
    ) : ConversationTurnEvent {
        override fun toString(): String = "ConversationTurnEvent.GenerationStarted(" +
            "session=redacted, at=${occurredAt.value}, request=redacted, content=[redacted])"
    }

    data class GenerationEnded(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        val status: GatewayClientTerminalStatus,
    ) : ConversationTurnEvent

    data class TurnEnded(
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        val record: ConversationTurnRecord,
    ) : ConversationTurnEvent
}

fun interface ConversationTurnListener {
    fun onEvent(event: ConversationTurnEvent)
}

data class ConversationTurnSnapshot(
    val active: Boolean,
    val phase: AudioSessionPhase?,
    val lastOutcome: ConversationTurnOutcome?,
) {
    init {
        require(active == (phase != null && phase.isActive))
    }

    val selectsProvider: Boolean get() = false
    val grantsAuthorization: Boolean get() = false
    val executesCapability: Boolean get() = false
    val writesMemory: Boolean get() = false

    override fun toString(): String = "ConversationTurnSnapshot(" +
        "active=$active, phase=$phase, lastOutcome=$lastOutcome, content=[redacted])"
}

enum class ConversationTurnStartDisposition {
    ACCEPTED,
    BARGE_IN_ACCEPTED,
    LISTENING_FAILED,
}

enum class ConversationCancelDisposition {
    REQUESTED,
    NOT_ACTIVE,
}

enum class ConversationPollStatus {
    IDLE,
    ACTIVE,
}
