package io.redi.robot.conversation.runtime

import io.redi.robot.ai.gateway.GatewayClient
import io.redi.robot.ai.gateway.GatewayClientModelResult
import io.redi.robot.ai.gateway.GatewayClientMonotonicMs
import io.redi.robot.ai.gateway.GatewayClientPollResult
import io.redi.robot.ai.gateway.GatewayClientStartResult
import io.redi.robot.ai.gateway.GatewayClientTerminalReport
import io.redi.robot.ai.gateway.GatewayClientTerminalStatus
import io.redi.robot.conversation.context.ConversationContextBuilder
import io.redi.robot.conversation.context.ConversationContextBudget
import io.redi.robot.conversation.context.ConversationContextBuildResult
import io.redi.robot.conversation.context.ConversationContextPrivacyPolicy
import io.redi.robot.conversation.context.ConversationContextProvenanceId
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.runtime.ContextSnapshot
import io.redi.robot.gateway.auth.GatewayAuthEpochSeconds
import io.redi.robot.gateway.auth.GatewayEndpointOrigin
import io.redi.robot.gateway.policy.ModelPolicyContextNarrower
import io.redi.robot.gateway.policy.ModelPolicyDecision
import io.redi.robot.gateway.policy.ModelPolicyPort
import io.redi.robot.gateway.policy.ModelTaskClass
import io.redi.robot.gateway.contract.GatewayCancellationReason
import io.redi.robot.gateway.contract.GatewayContractResult
import io.redi.robot.gateway.contract.GatewayOperation
import io.redi.robot.gateway.contract.GatewayRequest
import io.redi.robot.gateway.contract.GatewayRequestId
import io.redi.robot.gateway.contract.GatewaySchemaVersion
import io.redi.robot.gateway.contract.GatewayText
import io.redi.robot.interaction.localintent.LocalIntentPriority
import io.redi.robot.interaction.localintent.LocalIntentRouteStatus
import io.redi.robot.interaction.localintent.LocalIntentRouter
import io.redi.robot.skills.api.SkillDefinition
import io.redi.robot.voice.api.EphemeralSpeechTranscript
import io.redi.robot.voice.api.LocalSpeechInteractionCoordinator
import io.redi.robot.voice.api.SpeechAdapterCancelDisposition
import io.redi.robot.voice.api.SpeechAdapterSnapshot
import io.redi.robot.voice.api.SpeechAdapterStartResult
import io.redi.robot.voice.api.SpeechAudioFocusPort
import io.redi.robot.voice.api.SpeechContractResult
import io.redi.robot.voice.api.SpeechInteractionEvent
import io.redi.robot.voice.api.SpeechInteractionMode
import io.redi.robot.voice.api.SpeechInteractionStartDisposition
import io.redi.robot.voice.api.SpeechInterruptionReason
import io.redi.robot.voice.api.SpeechLocale
import io.redi.robot.voice.api.SpeechOperationId
import io.redi.robot.voice.api.SpeechRecognitionEvent
import io.redi.robot.voice.api.SpeechRecognitionListener
import io.redi.robot.voice.api.SpeechRecognitionPort
import io.redi.robot.voice.api.SpeechRecognitionRequest
import io.redi.robot.voice.api.SpeechSynthesisPort
import io.redi.robot.voice.api.SpeechSynthesisRequest
import io.redi.robot.voice.api.SpeechText
import io.redi.robot.voice.session.AudioMonotonicTimeMs
import io.redi.robot.voice.session.AudioSessionCancellationReason
import io.redi.robot.voice.session.AudioSessionEffect
import io.redi.robot.voice.session.AudioSessionEvent
import io.redi.robot.voice.session.AudioSessionFailureReason
import io.redi.robot.voice.session.AudioSessionId
import io.redi.robot.voice.session.AudioSessionModel
import io.redi.robot.voice.session.AudioSessionPhase
import io.redi.robot.voice.session.AudioSessionReducer
import io.redi.robot.voice.session.AudioSessionReduction
import io.redi.robot.voice.session.AudioSessionTerminal
import java.util.EnumMap
import java.util.UUID

/**
 * H056 conversation turn coordinator. One activation-gated turn at a time is coordinated through
 * the existing H047 session reducer, H049 speech interaction coordinator, H046 local-first route
 * report, H054 bounded context packet, and H053 gateway client.
 *
 * Local stop wins: a STOP-priority local route terminates the turn without any gateway dependency.
 * Barge-in is explicit re-activation; it cancels in-flight generation and output. Provider loss
 * degrades to a locally spoken injected fallback and releases audio ownership. The coordinator
 * creates no capability request, authorization, skill execution, or memory write, and it holds no
 * body or robot lease.
 */
class ConversationTurnCoordinator @Suppress("LongParameterList") constructor(
    private val gateway: GatewayClient,
    recognitionPort: SpeechRecognitionPort,
    synthesisPort: SpeechSynthesisPort,
    focusPort: SpeechAudioFocusPort,
    private val endpoint: GatewayEndpointOrigin,
    private val fallbacks: ConversationFallbackMessages,
    private val contextBuilder: ConversationContextBuilder,
    private val contextPrivacyPolicy: ConversationContextPrivacyPolicy,
    private val contextBudget: ConversationContextBudget,
    private val snapshotProvider: () -> ContextSnapshot,
    private val authEpochNow: () -> GatewayAuthEpochSeconds,
    private val currentTurnPrivacy: ContextPrivacyClassification = ContextPrivacyClassification.PRIVATE,
    private val toolDefinitions: List<SkillDefinition> = emptyList(),
    private val policy: ConversationTurnPolicy = ConversationTurnPolicy(),
    intentRouter: LocalIntentRouter = LocalIntentRouter(),
    private val speechOperationIds: () -> SpeechOperationId = ::newSpeechOperationId,
    private val gatewayRequestIds: () -> GatewayRequestId = ::newGatewayRequestId,
    private val provenanceIds: () -> ConversationContextProvenanceId = ::newProvenanceId,
    generationPolicy: ModelPolicyPort? = null,
    private val listener: ConversationTurnListener,
) {
    /**
     * Optional H057 generation policy. When absent the coordinator keeps its historical behavior:
     * the fixed H051 request envelope, no context narrowing, and no usage reporting. When present,
     * policy evaluation happens before any request construction or transport open, blocks degrade
     * to the typed local fallback, the context budget can only narrow, and every stream terminal
     * is reported for budget and provider-health accounting.
     */
    private val generationPolicy: ModelPolicyPort? = generationPolicy

    private val lock = Any()
    private val reducer = AudioSessionReducer(policy.sessionPolicy)
    private val transcriptTee = TranscriptTeeRecognitionPort(recognitionPort)
    private val speech = LocalSpeechInteractionCoordinator(
        transcriptTee,
        synthesisPort,
        focusPort,
        intentRouter,
    ) { event -> onSpeechEvent(event) }

    private var session: AudioSessionModel = AudioSessionModel.IDLE
    private var turn: ActiveTurn? = null
    private var lastRecord: ConversationTurnRecord? = null
    private var generationInFlight = false

    /**
     * Explicit activation starts one turn from idle or a terminal state. Activation while a turn is
     * still active is the H056 barge-in: in-flight generation and output are cancelled first and
     * the new turn then listens.
     */
    fun activate(
        sessionId: AudioSessionId,
        locale: SpeechLocale,
        at: AudioMonotonicTimeMs,
    ): ConversationTurnStartDisposition = synchronized(lock) {
        var bargedIn = false
        if (session.phase.isActive) {
            interruptActiveTurn(
                at = at,
                sessionReason = AudioSessionCancellationReason.INTERRUPTED,
                gatewayReason = GatewayCancellationReason.BARGE_IN,
                speechReason = if (
                    session.phase == AudioSessionPhase.THINKING || session.phase == AudioSessionPhase.SPEAKING
                ) {
                    SpeechInterruptionReason.NEW_SPEECH
                } else {
                    SpeechInterruptionReason.USER_REQUEST
                },
            )
            bargedIn = true
        }
        if (session.phase.isTerminal) {
            session.sessionId?.let { previous ->
                apply(reducer.reduce(session, AudioSessionEvent.Reset(previous, at)), at)
            }
        }
        if (session.phase != AudioSessionPhase.IDLE) return ConversationTurnStartDisposition.LISTENING_FAILED
        apply(reducer.reduce(session, AudioSessionEvent.Activate(sessionId, at)), at)
        if (session.phase != AudioSessionPhase.ACTIVATING) return ConversationTurnStartDisposition.LISTENING_FAILED
        val active = ActiveTurn(sessionId, locale)
        turn = active
        active.mark(ConversationTurnMark.ACTIVATED, at)
        listener.onEvent(ConversationTurnEvent.TurnStarted(sessionId, at, bargedIn))
        apply(reducer.reduce(session, AudioSessionEvent.ActivationReady(sessionId, at)), at)
        return if (session.phase == AudioSessionPhase.LISTENING) {
            if (bargedIn) ConversationTurnStartDisposition.BARGE_IN_ACCEPTED else ConversationTurnStartDisposition.ACCEPTED
        } else {
            ConversationTurnStartDisposition.LISTENING_FAILED
        }
    }

    /** User or lifecycle cancellation of the active turn. Only user-initiated reasons enter here. */
    fun cancel(
        reason: AudioSessionCancellationReason,
        at: AudioMonotonicTimeMs,
    ): ConversationCancelDisposition = synchronized(lock) {
        require(
            reason == AudioSessionCancellationReason.USER_REQUEST ||
                reason == AudioSessionCancellationReason.BACKGROUNDED,
        ) { "Only user-requested and backgrounded cancellations enter here" }
        if (!session.phase.isActive) return ConversationCancelDisposition.NOT_ACTIVE
        interruptActiveTurn(
            at = at,
            sessionReason = reason,
            gatewayReason = if (reason == AudioSessionCancellationReason.BACKGROUNDED) {
                GatewayCancellationReason.BACKGROUND
            } else {
                GatewayCancellationReason.USER
            },
            speechReason = SpeechInterruptionReason.USER_REQUEST,
        )
        ConversationCancelDisposition.REQUESTED
    }

    /** Injected-time tick: enforces session deadlines and advances in-flight generation. */
    fun poll(now: AudioMonotonicTimeMs): ConversationPollStatus = synchronized(lock) {
        val deadline = session.phaseDeadlineAt
        if (session.phase.isActive && deadline != null && now.value >= deadline.value) {
            session.sessionId?.let { id ->
                apply(reducer.reduce(session, AudioSessionEvent.DeadlineElapsed(id, now)), now)
            }
            quiesceInFlightWork(GatewayCancellationReason.DEADLINE, SpeechInterruptionReason.USER_REQUEST, now)
            completeCancellationIfPending(now)
        }
        if (session.phase == AudioSessionPhase.THINKING && generationInFlight) {
            when (val polled = gateway.poll(gatewayTime(now))) {
                is GatewayClientPollResult.Terminal -> onGenerationTerminal(polled.report, now)
                else -> Unit
            }
        }
        return if (session.phase.isActive) ConversationPollStatus.ACTIVE else ConversationPollStatus.IDLE
    }

    fun snapshot(): ConversationTurnSnapshot = synchronized(lock) {
        ConversationTurnSnapshot(
            active = session.phase.isActive,
            phase = session.phase.takeIf { it.isActive },
            lastOutcome = lastRecord?.outcome,
        )
    }

    fun lastTurnRecord(): ConversationTurnRecord? = synchronized(lock) { lastRecord }

    private fun onSpeechEvent(event: SpeechInteractionEvent) = synchronized(lock) {
        val active = turn ?: return
        if (event.sessionId != active.sessionId) return
        when (event) {
            is SpeechInteractionEvent.LocalIntentRouted -> onRouted(active, event)
            is SpeechInteractionEvent.RecognitionNoMatch -> onNoRecognizedInput(active, event.occurredAt)
            is SpeechInteractionEvent.SynthesisCompleted -> onSynthesisCompleted(active, event.occurredAt)
            is SpeechInteractionEvent.AdapterFailed -> onAdapterFailed(active, event)
            is SpeechInteractionEvent.DeadlineExceeded -> onSpeechDeadline(active, event.occurredAt)
            is SpeechInteractionEvent.Interrupted -> onSpeechInterrupted(active, event)
            is SpeechInteractionEvent.AdapterStarted -> Unit
        }
    }

    private fun onRouted(active: ActiveTurn, event: SpeechInteractionEvent.LocalIntentRouted) {
        if (session.phase != AudioSessionPhase.LISTENING) return
        val report = event.report
        active.routeStatus = report.status
        active.stopPriority = report.proposal?.priority == LocalIntentPriority.STOP
        active.matchedCapability = report.proposal?.capability?.wireValue
        active.mark(ConversationTurnMark.ROUTE_DECIDED, event.occurredAt)
        listener.onEvent(
            ConversationTurnEvent.TurnRouted(
                active.sessionId,
                event.occurredAt,
                report.status,
                active.stopPriority,
                active.matchedCapability,
            ),
        )
        when (report.status) {
            LocalIntentRouteStatus.PROPOSED -> {
                // Local stop wins and local proposals stay unexecuted: both end the turn locally
                // with a typed outcome, a defensive generation cancellation, and no AI dependency.
                active.outcome = if (active.stopPriority) {
                    ConversationTurnOutcome.LOCAL_STOP
                } else {
                    ConversationTurnOutcome.LOCAL_PROPOSED
                }
                apply(
                    reducer.reduce(
                        session,
                        AudioSessionEvent.Cancel(
                            active.sessionId,
                            event.occurredAt,
                            AudioSessionCancellationReason.USER_REQUEST,
                        ),
                    ),
                    event.occurredAt,
                )
                quiesceInFlightWork(GatewayCancellationReason.USER, null, event.occurredAt)
                completeCancellationIfPending(event.occurredAt)
            }
            LocalIntentRouteStatus.AMBIGUOUS -> {
                active.outcome = ConversationTurnOutcome.CLARIFICATION_REQUESTED
                active.pendingSpeak = fallbacks.clarification
                advanceFromListening(active, event.occurredAt)
            }
            LocalIntentRouteStatus.UNSUPPORTED_LOCALE -> {
                active.outcome = ConversationTurnOutcome.LOCAL_FALLBACK
                active.localFallbackReason = ConversationLocalFallbackReason.UNSUPPORTED_LOCALE
                active.pendingSpeak = fallbacks.unsupportedLocale
                advanceFromListening(active, event.occurredAt)
            }
            LocalIntentRouteStatus.INVALID_INPUT -> {
                active.outcome = ConversationTurnOutcome.LOCAL_FALLBACK
                active.localFallbackReason = ConversationLocalFallbackReason.INVALID_INPUT
                active.pendingSpeak = fallbacks.invalidRoute
                advanceFromListening(active, event.occurredAt)
            }
            LocalIntentRouteStatus.NO_MATCH -> advanceFromListening(active, event.occurredAt)
        }
    }

    private fun onNoRecognizedInput(active: ActiveTurn, at: AudioMonotonicTimeMs) {
        if (session.phase != AudioSessionPhase.LISTENING) return
        active.outcome = ConversationTurnOutcome.NO_RECOGNIZED_INPUT
        apply(
            reducer.reduce(
                session,
                AudioSessionEvent.Cancel(active.sessionId, at, AudioSessionCancellationReason.USER_REQUEST),
            ),
            at,
        )
        completeCancellationIfPending(at)
    }

    private fun onSynthesisCompleted(active: ActiveTurn, at: AudioMonotonicTimeMs) {
        if (session.phase != AudioSessionPhase.SPEAKING) return
        active.mark(ConversationTurnMark.SPEAKING_ENDED, at)
        apply(reducer.reduce(session, AudioSessionEvent.SpeakingCompleted(active.sessionId, at)), at)
    }

    private fun onAdapterFailed(active: ActiveTurn, event: SpeechInteractionEvent.AdapterFailed) {
        val failure = when (event.mode) {
            SpeechInteractionMode.RECOGNITION ->
                if (session.phase == AudioSessionPhase.LISTENING) {
                    AudioSessionFailureReason.LISTENING_FAILED
                } else {
                    return
                }
            SpeechInteractionMode.SYNTHESIS ->
                if (session.phase == AudioSessionPhase.SPEAKING) {
                    AudioSessionFailureReason.SPEAKING_FAILED
                } else {
                    return
                }
        }
        apply(
            reducer.reduce(session, AudioSessionEvent.Failure(active.sessionId, event.occurredAt, failure)),
            event.occurredAt,
        )
    }

    private fun onSpeechDeadline(active: ActiveTurn, at: AudioMonotonicTimeMs) {
        if (!session.phase.isActive || session.phase == AudioSessionPhase.CANCELLING) return
        apply(reducer.reduce(session, AudioSessionEvent.DeadlineElapsed(active.sessionId, at)), at)
        // H049 already cancelled its own operation before reporting the deadline.
        quiesceInFlightWork(GatewayCancellationReason.DEADLINE, null, at)
        completeCancellationIfPending(at)
    }

    private fun onSpeechInterrupted(active: ActiveTurn, event: SpeechInteractionEvent.Interrupted) {
        when (event.reason) {
            SpeechInterruptionReason.AUDIO_FOCUS_LOST ->
                if (session.phase.isActive && session.phase != AudioSessionPhase.CANCELLING) {
                    interruptActiveTurn(
                        at = event.occurredAt,
                        sessionReason = AudioSessionCancellationReason.AUDIO_FOCUS_LOST,
                        gatewayReason = GatewayCancellationReason.USER,
                        speechReason = null,
                    )
                }
            SpeechInterruptionReason.NEW_SPEECH,
            SpeechInterruptionReason.USER_REQUEST,
            SpeechInterruptionReason.BACKGROUNDED,
            -> completeCancellationIfPending(event.occurredAt)
        }
    }

    private fun onGenerationTerminal(report: GatewayClientTerminalReport, now: AudioMonotonicTimeMs) {
        generationInFlight = false
        val active = turn ?: return
        if (active.sessionId != session.sessionId || session.phase != AudioSessionPhase.THINKING) return
        // H057 budget and provider-health accounting for every finished stream; the engine itself
        // decides which statuses count as health failures and which accrue usage.
        generationPolicy?.onTerminal(
            ModelTaskClass.CONVERSATION_TEXT,
            report.status,
            report.stream.usage,
            now.value,
            epochDay(),
        )
        active.gatewayTerminal = report.status
        active.mark(ConversationTurnMark.GENERATION_ENDED, now)
        listener.onEvent(ConversationTurnEvent.GenerationEnded(active.sessionId, now, report.status))
        when (report.status) {
            GatewayClientTerminalStatus.COMPLETED -> {
                val text = report.stream.partialText.joinToString(separator = "") { it.value }
                when (val bounded = SpeechText.create(text)) {
                    is SpeechContractResult.Success -> {
                        active.pendingSpeak = bounded.value
                        active.outcome = ConversationTurnOutcome.AI_COMPLETED
                    }
                    is SpeechContractResult.Failure -> applyAiFallback(
                        active,
                        if (text.isEmpty()) {
                            ConversationTurnFallbackReason.EMPTY_OUTPUT
                        } else {
                            ConversationTurnFallbackReason.OUTPUT_EXCEEDED
                        },
                    )
                }
            }
            GatewayClientTerminalStatus.REFUSED ->
                applyAiFallback(active, ConversationTurnFallbackReason.REFUSED)
            GatewayClientTerminalStatus.FAILED ->
                applyAiFallback(active, ConversationTurnFallbackReason.GENERATION_FAILED)
            GatewayClientTerminalStatus.GATEWAY_TIMED_OUT,
            GatewayClientTerminalStatus.CLIENT_TIMED_OUT,
            -> applyAiFallback(active, ConversationTurnFallbackReason.GENERATION_TIMEOUT)
            GatewayClientTerminalStatus.TRANSPORT_FAILURE ->
                applyAiFallback(active, ConversationTurnFallbackReason.TRANSPORT_FAILURE)
            GatewayClientTerminalStatus.PROTOCOL_ERROR ->
                applyAiFallback(active, ConversationTurnFallbackReason.PROTOCOL_ERROR)
            GatewayClientTerminalStatus.GATEWAY_CANCELLED,
            GatewayClientTerminalStatus.CLIENT_CANCELLED,
            -> return
        }
        if (session.phase == AudioSessionPhase.THINKING) {
            apply(reducer.reduce(session, AudioSessionEvent.ThinkingCompleted(active.sessionId, now)), now)
        }
    }

    private fun applyAiFallback(active: ActiveTurn, reason: ConversationTurnFallbackReason) {
        active.outcome = ConversationTurnOutcome.AI_FALLBACK
        active.fallbackReason = reason
        active.pendingSpeak = when (reason) {
            ConversationTurnFallbackReason.START_REJECTED,
            ConversationTurnFallbackReason.CONTEXT_REJECTED,
            ConversationTurnFallbackReason.POLICY_BLOCKED,
            -> fallbacks.providerUnavailable
            ConversationTurnFallbackReason.TRANSPORT_FAILURE -> fallbacks.providerLost
            ConversationTurnFallbackReason.GENERATION_TIMEOUT -> fallbacks.generationTimedOut
            else -> fallbacks.generationFailed
        }
    }

    private fun advanceFromListening(active: ActiveTurn, at: AudioMonotonicTimeMs) {
        apply(reducer.reduce(session, AudioSessionEvent.ListeningCompleted(active.sessionId, at)), at)
        if (session.phase == AudioSessionPhase.THINKING && !generationInFlight) {
            apply(reducer.reduce(session, AudioSessionEvent.ThinkingCompleted(active.sessionId, at)), at)
        }
    }

    private fun beginThinking(sessionId: AudioSessionId) {
        val active = turn ?: return
        val at = requireNotNull(session.phaseEnteredAt)
        when (active.routeStatus) {
            LocalIntentRouteStatus.NO_MATCH -> startAiGeneration(active, sessionId, at)
            else -> Unit
        }
    }

    private fun startAiGeneration(active: ActiveTurn, sessionId: AudioSessionId, at: AudioMonotonicTimeMs) {
        val alternatives = active.recognitionOperationId?.let(transcriptTee::take)
        val prompt = when {
            alternatives.isNullOrEmpty() -> null
            else -> when (val text = GatewayText.create(alternatives.first())) {
                is GatewayContractResult.Success -> text.value
                is GatewayContractResult.Failure -> null
            }
        }
        if (prompt == null) {
            applyAiFallback(active, ConversationTurnFallbackReason.EMPTY_OUTPUT)
            return
        }
        // H057 policy gate: evaluation precedes every transport open. A block never opens a
        // socket, never builds a context packet, and degrades to the typed local fallback.
        val decision = generationPolicy?.evaluate(
            ModelTaskClass.CONVERSATION_TEXT,
            currentTurnPrivacy,
            at.value,
            epochDay(),
        )
        if (decision is ModelPolicyDecision.Blocked) {
            applyAiFallback(active, ConversationTurnFallbackReason.POLICY_BLOCKED)
            return
        }
        val allowance = (decision as? ModelPolicyDecision.Allowed)?.allowance
        val envelope = allowance?.requestBudget ?: policy.gatewayBudget
        val effectiveContextBudget = allowance
            ?.let { ModelPolicyContextNarrower.narrow(contextBudget, it.maximumContextInputUnits) }
            ?: contextBudget
        val request = when (
            val created = GatewayRequest.create(
                GatewaySchemaVersion.CURRENT,
                gatewayRequestIds(),
                GatewayOperation.TEXT,
                prompt,
                null,
                emptyList(),
                null,
                toolDefinitions.map { it.reference },
                envelope,
            )
        ) {
            is GatewayContractResult.Success -> created.value
            is GatewayContractResult.Failure -> {
                generationPolicy?.onStartRejected(ModelTaskClass.CONVERSATION_TEXT)
                applyAiFallback(active, ConversationTurnFallbackReason.CONTEXT_REJECTED)
                return
            }
        }
        when (
            val built = contextBuilder.build(
                request,
                provenanceIds(),
                currentTurnPrivacy,
                null,
                snapshotProvider(),
                contextPrivacyPolicy,
                effectiveContextBudget,
                toolDefinitions,
            )
        ) {
            is ConversationContextBuildResult.Built -> Unit
            is ConversationContextBuildResult.Rejected -> {
                generationPolicy?.onStartRejected(ModelTaskClass.CONVERSATION_TEXT)
                applyAiFallback(active, ConversationTurnFallbackReason.CONTEXT_REJECTED)
                return
            }
        }
        when (
            val started = gateway.start(
                request,
                endpoint,
                authEpochNow(),
                gatewayTime(at),
            )
        ) {
            is GatewayClientStartResult.Started -> {
                generationInFlight = true
                active.mark(ConversationTurnMark.GENERATION_STARTED, at)
                listener.onEvent(ConversationTurnEvent.GenerationStarted(sessionId, at, request.requestId.value))
            }
            is GatewayClientStartResult.Rejected -> {
                // Authentication/configuration rejection is not gateway health; release any
                // half-open probe without counting a provider-health failure.
                generationPolicy?.onStartRejected(ModelTaskClass.CONVERSATION_TEXT)
                applyAiFallback(active, ConversationTurnFallbackReason.START_REJECTED)
            }
        }
    }

    private fun epochDay(): Long = authEpochNow().value / 86_400L

    private fun interruptActiveTurn(
        at: AudioMonotonicTimeMs,
        sessionReason: AudioSessionCancellationReason,
        gatewayReason: GatewayCancellationReason,
        speechReason: SpeechInterruptionReason?,
    ) {
        val id = session.sessionId ?: return
        apply(reducer.reduce(session, AudioSessionEvent.Cancel(id, at, sessionReason)), at)
        quiesceInFlightWork(gatewayReason, speechReason, at)
        completeCancellationIfPending(at)
    }

    private fun quiesceInFlightWork(
        gatewayReason: GatewayCancellationReason,
        speechReason: SpeechInterruptionReason?,
        at: AudioMonotonicTimeMs,
    ) {
        gateway.cancel(gatewayReason)
        generationInFlight = false
        if (speechReason != null) {
            session.sessionId?.let { id -> speech.interrupt(id, at, speechReason) }
        }
    }

    private fun completeCancellationIfPending(at: AudioMonotonicTimeMs) {
        if (session.phase != AudioSessionPhase.CANCELLING) return
        val id = session.sessionId ?: return
        apply(reducer.reduce(session, AudioSessionEvent.CancellationCompleted(id, at)), at)
    }

    private fun apply(reduction: AudioSessionReduction, at: AudioMonotonicTimeMs) {
        session = reduction.model
        reduction.effects.forEach(::executeEffect)
        if (session.phase.isTerminal) finalizeTurn(at)
    }

    private fun executeEffect(effect: AudioSessionEffect) {
        when (effect) {
            is AudioSessionEffect.RequestActivation -> Unit
            is AudioSessionEffect.RequestListening -> beginListening(effect.sessionId)
            is AudioSessionEffect.RequestThinking -> beginThinking(effect.sessionId)
            is AudioSessionEffect.RequestSpeaking -> beginSpeaking(effect.sessionId)
            is AudioSessionEffect.CancelPhase,
            is AudioSessionEffect.ReleaseSession,
            -> Unit
        }
    }

    private fun beginListening(sessionId: AudioSessionId) {
        val active = turn ?: return
        val at = requireNotNull(session.phaseEnteredAt)
        val operationId = speechOperationIds()
        active.recognitionOperationId = operationId
        val request = SpeechRecognitionRequest(
            operationId,
            sessionId,
            active.locale,
            at,
            AudioMonotonicTimeMs(at.value + policy.recognitionDeadlineMs),
        )
        when (speech.startRecognition(request).disposition) {
            SpeechInteractionStartDisposition.ACCEPTED ->
                active.mark(ConversationTurnMark.LISTENING_STARTED, at)
            else -> failSession(sessionId, at, AudioSessionFailureReason.LISTENING_FAILED)
        }
    }

    private fun beginSpeaking(sessionId: AudioSessionId) {
        val active = turn ?: return
        val at = requireNotNull(session.phaseEnteredAt)
        val text = active.pendingSpeak
        if (text == null) {
            failSession(sessionId, at, AudioSessionFailureReason.SPEAKING_FAILED)
            return
        }
        val request = SpeechSynthesisRequest(
            speechOperationIds(),
            sessionId,
            active.locale,
            at,
            AudioMonotonicTimeMs(at.value + policy.synthesisDeadlineMs),
            text,
        )
        when (speech.startSynthesis(request).disposition) {
            SpeechInteractionStartDisposition.ACCEPTED ->
                active.mark(ConversationTurnMark.SPEAKING_STARTED, at)
            else -> failSession(sessionId, at, AudioSessionFailureReason.SPEAKING_FAILED)
        }
    }

    private fun failSession(sessionId: AudioSessionId, at: AudioMonotonicTimeMs, reason: AudioSessionFailureReason) {
        apply(reducer.reduce(session, AudioSessionEvent.Failure(sessionId, at, reason)), at)
    }

    private fun finalizeTurn(at: AudioMonotonicTimeMs) {
        val active = turn ?: return
        active.mark(ConversationTurnMark.TURN_ENDED, at)
        // Route-terminal outcomes are decided at route time and end the turn immediately. Every
        // other prepared outcome (clarification, fallback, AI response) is only earned when its
        // spoken output actually completes; interruption or failure records the terminal truth.
        val prepared = active.outcome
        val terminalOutcome = when {
            prepared != null && prepared in ROUTE_TERMINAL_OUTCOMES -> prepared
            session.terminal is AudioSessionTerminal.Completed ->
                prepared ?: ConversationTurnOutcome.AI_COMPLETED
            session.terminal is AudioSessionTerminal.Cancelled -> ConversationTurnOutcome.CANCELLED
            else -> ConversationTurnOutcome.ERROR
        }
        val record = ConversationTurnRecord(
            sessionId = active.sessionId,
            outcome = terminalOutcome,
            routeStatus = active.routeStatus,
            localFallbackReason = active.localFallbackReason,
            fallbackReason = active.fallbackReason,
            cancellationReason = (session.terminal as? AudioSessionTerminal.Cancelled)?.reason,
            gatewayTerminal = active.gatewayTerminal,
            latency = ConversationTurnLatency(active.marks),
        )
        lastRecord = record
        turn = null
        generationInFlight = false
        transcriptTee.clear()
        listener.onEvent(ConversationTurnEvent.TurnEnded(active.sessionId, at, record))
    }

    private fun gatewayTime(at: AudioMonotonicTimeMs): GatewayClientMonotonicMs =
        when (val created = GatewayClientMonotonicMs.create(at.value)) {
            is GatewayClientModelResult.Success -> created.value
            is GatewayClientModelResult.Failure -> error("Monotonic gateway time is negative")
        }

    private companion object {
        val ROUTE_TERMINAL_OUTCOMES = setOf(
            ConversationTurnOutcome.LOCAL_STOP,
            ConversationTurnOutcome.LOCAL_PROPOSED,
            ConversationTurnOutcome.NO_RECOGNIZED_INPUT,
        )
    }

    private inner class ActiveTurn(
        val sessionId: AudioSessionId,
        val locale: SpeechLocale,
        val marks: MutableMap<ConversationTurnMark, Long> = EnumMap(ConversationTurnMark::class.java),
        var routeStatus: LocalIntentRouteStatus? = null,
        var stopPriority: Boolean = false,
        var matchedCapability: String? = null,
        var outcome: ConversationTurnOutcome? = null,
        var localFallbackReason: ConversationLocalFallbackReason? = null,
        var fallbackReason: ConversationTurnFallbackReason? = null,
        var gatewayTerminal: GatewayClientTerminalStatus? = null,
        var pendingSpeak: SpeechText? = null,
        var recognitionOperationId: SpeechOperationId? = null,
    ) {
        fun mark(mark: ConversationTurnMark, at: AudioMonotonicTimeMs) {
            marks.getOrPut(mark) { at.value }
        }
    }
}

/**
 * Forwards recognition work to the delegate while observing the one-shot transcript once so the
 * turn coordinator can build the AI prompt. The observed alternatives are re-wrapped in a fresh
 * one-shot envelope for H049 local-first routing and are dropped immediately after prompt
 * construction, cancellation, or turn end.
 */
private class TranscriptTeeRecognitionPort(
    private val delegate: SpeechRecognitionPort,
) : SpeechRecognitionPort {
    private val teeLock = Any()
    private var captured: Pair<SpeechOperationId, List<String>>? = null

    override fun start(
        request: SpeechRecognitionRequest,
        listener: SpeechRecognitionListener,
    ): SpeechAdapterStartResult = delegate.start(request) { event ->
        if (event is SpeechRecognitionEvent.FinalTranscript) {
            val alternatives = event.transcript.consume { it }
            synchronized(teeLock) { captured = event.operationId to alternatives }
            when (val fresh = EphemeralSpeechTranscript.create(alternatives)) {
                is SpeechContractResult.Success ->
                    listener.onEvent(
                        SpeechRecognitionEvent.FinalTranscript(
                            event.operationId,
                            event.sessionId,
                            event.occurredAt,
                            fresh.value,
                        ),
                    )
                is SpeechContractResult.Failure -> Unit
            }
        } else {
            listener.onEvent(event)
        }
    }

    override fun cancel(operationId: SpeechOperationId): SpeechAdapterCancelDisposition =
        delegate.cancel(operationId)

    override fun snapshot(): SpeechAdapterSnapshot = delegate.snapshot()

    fun take(operationId: SpeechOperationId): List<String>? = synchronized(teeLock) {
        val current = captured ?: return null
        if (current.first != operationId) return null
        captured = null
        current.second
    }

    fun clear() = synchronized(teeLock) { captured = null }
}

private fun newSpeechOperationId(): SpeechOperationId = SpeechOperationId.fromUuid(UUID.randomUUID())

private fun newGatewayRequestId(): GatewayRequestId = when (
    val parsed = GatewayRequestId.parse(UUID.randomUUID().toString())
) {
    is GatewayContractResult.Success -> parsed.value
    is GatewayContractResult.Failure -> error("Generated gateway request ID was invalid")
}

private fun newProvenanceId(): ConversationContextProvenanceId =
    ConversationContextProvenanceId.fromUuid(UUID.randomUUID())
