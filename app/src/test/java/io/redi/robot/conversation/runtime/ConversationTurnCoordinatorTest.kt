package io.redi.robot.conversation.runtime

import io.redi.robot.ai.gateway.GatewayClient
import io.redi.robot.ai.gateway.GatewayClientModelResult
import io.redi.robot.ai.gateway.GatewayClientTerminalStatus
import io.redi.robot.ai.gateway.GatewayHttpStreamChannel
import io.redi.robot.ai.gateway.GatewayHttpStreamPort
import io.redi.robot.ai.gateway.GatewayStreamReadResult
import io.redi.robot.ai.gateway.GatewayTransportCancellationDelivery
import io.redi.robot.ai.gateway.GatewayTransportOpenResult
import io.redi.robot.ai.gateway.GatewayTransportReadFailure
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.conversation.context.ConversationContextBudget
import io.redi.robot.conversation.context.ConversationContextBuilder
import io.redi.robot.conversation.context.ConversationContextPrivacyPolicy
import io.redi.robot.conversation.context.ConversationContextResult
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.runtime.ContextSnapshot
import io.redi.robot.context.runtime.ContextSnapshotBudget
import io.redi.robot.context.runtime.ContextSnapshotEngine
import io.redi.robot.context.runtime.ContextSnapshotRequest
import io.redi.robot.context.runtime.ContextSnapshotResult
import io.redi.robot.gateway.auth.GatewayAccessToken
import io.redi.robot.gateway.auth.GatewayAuthEpochSeconds
import io.redi.robot.gateway.auth.GatewayAuthModelResult
import io.redi.robot.gateway.auth.GatewayAuthScope
import io.redi.robot.gateway.auth.GatewayBuildMode
import io.redi.robot.gateway.auth.GatewayClientSessionId
import io.redi.robot.gateway.auth.GatewayEndpointOrigin
import io.redi.robot.gateway.auth.GatewayEndpointPolicy
import io.redi.robot.gateway.auth.GatewayTokenDocumentReadResult
import io.redi.robot.gateway.auth.GatewayTokenDocumentResetResult
import io.redi.robot.gateway.auth.GatewayTokenDocumentWriteResult
import io.redi.robot.gateway.auth.GatewayTokenId
import io.redi.robot.gateway.auth.GatewayTokenRepository
import io.redi.robot.gateway.contract.GatewayBudget
import io.redi.robot.gateway.contract.GatewayContractResult
import io.redi.robot.gateway.contract.GatewayUsage
import io.redi.robot.gateway.policy.ModelCircuitPolicy
import io.redi.robot.gateway.policy.ModelCircuitState
import io.redi.robot.gateway.policy.ModelPolicyBlockReason
import io.redi.robot.gateway.policy.ModelPolicyConfiguration
import io.redi.robot.gateway.policy.ModelPolicyDecision
import io.redi.robot.gateway.policy.ModelPolicyEngine
import io.redi.robot.gateway.policy.ModelPolicyPort
import io.redi.robot.gateway.policy.ModelPolicyResult
import io.redi.robot.gateway.policy.ModelPolicyUsageSnapshot
import io.redi.robot.gateway.policy.ModelTaskClass
import io.redi.robot.gateway.policy.ModelTaskPolicy
import io.redi.robot.gateway.auth.ProtectedGatewayTokenStorage
import io.redi.robot.gateway.auth.StoreGatewayTokenRequest
import io.redi.robot.gateway.contract.GatewayContractCodec
import io.redi.robot.gateway.contract.GatewayOperation
import io.redi.robot.gateway.contract.GatewayRequest
import io.redi.robot.interaction.localintent.LocalCapabilityProposal
import io.redi.robot.interaction.localintent.LocalIntentCorpus
import io.redi.robot.interaction.localintent.LocalIntentCorpusId
import io.redi.robot.interaction.localintent.LocalIntentLocale
import io.redi.robot.interaction.localintent.LocalIntentModelResult
import io.redi.robot.interaction.localintent.LocalIntentPriority
import io.redi.robot.interaction.localintent.LocalIntentRouteStatus
import io.redi.robot.interaction.localintent.LocalIntentRule
import io.redi.robot.interaction.localintent.LocalIntentRuleId
import io.redi.robot.interaction.localintent.LocalIntentRouter
import io.redi.robot.voice.api.EphemeralSpeechTranscript
import io.redi.robot.voice.api.SpeechAdapterCancelDisposition
import io.redi.robot.voice.api.SpeechAdapterSnapshot
import io.redi.robot.voice.api.SpeechAdapterStartDisposition
import io.redi.robot.voice.api.SpeechAdapterStartResult
import io.redi.robot.voice.api.SpeechAudioFocusAcquireDisposition
import io.redi.robot.voice.api.SpeechAudioFocusAcquireResult
import io.redi.robot.voice.api.SpeechAudioFocusEvent
import io.redi.robot.voice.api.SpeechAudioFocusListener
import io.redi.robot.voice.api.SpeechAudioFocusLoss
import io.redi.robot.voice.api.SpeechAudioFocusPort
import io.redi.robot.voice.api.SpeechAudioFocusReleaseDisposition
import io.redi.robot.voice.api.SpeechAudioFocusRequest
import io.redi.robot.voice.api.SpeechAudioFocusSnapshot
import io.redi.robot.voice.api.SpeechContractResult
import io.redi.robot.voice.api.SpeechLocale
import io.redi.robot.voice.api.SpeechOperationId
import io.redi.robot.voice.api.SpeechRecognitionEvent
import io.redi.robot.voice.api.SpeechRecognitionListener
import io.redi.robot.voice.api.SpeechRecognitionPort
import io.redi.robot.voice.api.SpeechRecognitionRequest
import io.redi.robot.voice.api.SpeechSynthesisEvent
import io.redi.robot.voice.api.SpeechSynthesisFailure
import io.redi.robot.voice.api.SpeechSynthesisListener
import io.redi.robot.voice.api.SpeechSynthesisPort
import io.redi.robot.voice.api.SpeechSynthesisRequest
import io.redi.robot.voice.api.SpeechText
import io.redi.robot.voice.session.AudioMonotonicTimeMs
import io.redi.robot.voice.session.AudioSessionCancellationReason
import io.redi.robot.voice.session.AudioSessionId
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTurnCoordinatorTest {
    private val locale = speechLocale("en-US")
    private val unsupportedLocale = speechLocale("fr-FR")

    @Test
    fun `explicit turn listens routes no match generates bounded text and speaks with latency evidence`() {
        val h = Harness()
        assertEquals(ConversationTurnStartDisposition.ACCEPTED, h.activate(1, 100))
        h.recognition.emitFinalTranscript(listOf("what time is it"), 110)
        assertEquals(1, h.transport.openCalls)

        val sent = h.sentRequest()
        assertEquals(GatewayOperation.TEXT, sent.operation)
        assertEquals("what time is it", sent.prompt?.value)
        assertTrue(sent.declaredSkills.isEmpty())

        h.enqueueAiCompleted("Hello there")
        assertEquals(ConversationPollStatus.ACTIVE, h.coordinator.poll(t(120)))
        assertEquals(ConversationPollStatus.ACTIVE, h.coordinator.poll(t(130)))
        assertEquals(ConversationPollStatus.ACTIVE, h.coordinator.poll(t(140)))
        assertEquals(ConversationPollStatus.ACTIVE, h.coordinator.poll(t(150)))

        assertEquals(1, h.synthesis.startCalls)
        assertEquals("Hello there", h.synthesis.request!!.text.value)
        h.synthesis.emitCompleted(160)

        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnOutcome.AI_COMPLETED, record.outcome)
        assertEquals(LocalIntentRouteStatus.NO_MATCH, record.routeStatus)
        assertEquals(GatewayClientTerminalStatus.COMPLETED, record.gatewayTerminal)
        assertEquals(60L, record.latency.totalMs)
        assertEquals(10L, record.latency.listeningMs)
        assertEquals(40L, record.latency.generationMs)
        assertEquals(10L, record.latency.speakingMs)
        assertFalse(record.grantsAuthorization)
        assertFalse(record.executesCapability)
        assertFalse(record.writesMemory)

        assertEquals(
            listOf("TurnStarted", "TurnRouted", "GenerationStarted", "GenerationEnded", "TurnEnded"),
            h.events.map { it::class.simpleName },
        )
        assertFalse(h.coordinator.snapshot().active)
    }

    @Test
    fun `local stop wins without gateway synthesis or ai dependency`() {
        val h = Harness()
        h.activate(1, 100)
        h.recognition.emitFinalTranscript(listOf("stop"), 110)

        assertEquals(0, h.transport.openCalls)
        assertEquals(0, h.synthesis.startCalls)
        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnOutcome.LOCAL_STOP, record.outcome)
        assertEquals(AudioSessionCancellationReason.USER_REQUEST, record.cancellationReason)
        val routed = h.events.filterIsInstance<ConversationTurnEvent.TurnRouted>().single()
        assertTrue(routed.stopPriority)
        assertEquals("mobility.stop@1.0", routed.matchedCapability)
        assertTrue(record.redactedTrace().first().startsWith("TURN_OUTCOME:LOCAL_STOP"))
    }

    @Test
    fun `non stop local proposal produces only a typed unexecuted route outcome`() {
        val h = Harness()
        h.activate(1, 100)
        h.recognition.emitFinalTranscript(listOf("connection status"), 110)

        assertEquals(0, h.transport.openCalls)
        assertEquals(0, h.synthesis.startCalls)
        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnOutcome.LOCAL_PROPOSED, record.outcome)
        assertEquals(LocalIntentRouteStatus.PROPOSED, record.routeStatus)
        val routed = h.events.filterIsInstance<ConversationTurnEvent.TurnRouted>().single()
        assertEquals("provider.connection.status@1.0", routed.matchedCapability)
        assertFalse(routed.stopPriority)
        assertFalse(record.executesCapability)
        assertFalse(record.writesMemory)
    }

    @Test
    fun `ambiguous route never escalates to the gateway and asks for clarification locally`() {
        val h = Harness(ambiguousCorpus = true)
        h.activate(1, 100)
        h.recognition.emitFinalTranscript(listOf("status"), 110)

        assertEquals(0, h.transport.openCalls)
        assertEquals(1, h.synthesis.startCalls)
        assertEquals("which command did you mean", h.synthesis.request!!.text.value)
        h.synthesis.emitCompleted(120)

        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnOutcome.CLARIFICATION_REQUESTED, record.outcome)
        assertEquals(LocalIntentRouteStatus.AMBIGUOUS, record.routeStatus)
    }

    @Test
    fun `unsupported locale stays local with spoken fallback and no gateway`() {
        val h = Harness()
        h.activate(1, 100, locale = unsupportedLocale)
        h.recognition.emitFinalTranscript(listOf("quel temps fait-il"), 110)

        assertEquals(0, h.transport.openCalls)
        assertEquals(1, h.synthesis.startCalls)
        assertEquals("locale not available locally", h.synthesis.request!!.text.value)
        h.synthesis.emitCompleted(120)

        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnOutcome.LOCAL_FALLBACK, record.outcome)
        assertEquals(ConversationLocalFallbackReason.UNSUPPORTED_LOCALE, record.localFallbackReason)
    }

    @Test
    fun `barge in during thinking cancels generation exactly once and never speaks`() {
        val h = Harness()
        h.activate(1, 100)
        h.recognition.emitFinalTranscript(listOf("what time is it"), 110)
        h.channel.enqueue(h.frame(0, h.sentRequestId(), STARTED_PAYLOAD))
        assertEquals(ConversationPollStatus.ACTIVE, h.coordinator.poll(t(120)))

        assertEquals(ConversationTurnStartDisposition.BARGE_IN_ACCEPTED, h.activate(2, 130))

        assertEquals(1, h.channel.cancelCalls)
        assertEquals(1, h.channel.closeCalls)
        assertEquals(0, h.synthesis.startCalls)
        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnOutcome.CANCELLED, record.outcome)
        assertEquals(AudioSessionCancellationReason.INTERRUPTED, record.cancellationReason)
        assertEquals(2, h.recognition.startCalls)
        assertTrue(h.events.filterIsInstance<ConversationTurnEvent.TurnStarted>().last().bargedIn)
    }

    @Test
    fun `barge in during speaking interrupts output and starts the next listen`() {
        val h = Harness()
        runAiTurnToSpeaking(h, text = "Hello there")
        assertEquals(1, h.synthesis.startCalls)

        assertEquals(ConversationTurnStartDisposition.BARGE_IN_ACCEPTED, h.activate(2, 160))

        assertEquals(1, h.synthesis.cancelCalls)
        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnOutcome.CANCELLED, record.outcome)
        assertEquals(AudioSessionCancellationReason.INTERRUPTED, record.cancellationReason)
        assertEquals(2, h.recognition.startCalls)
    }

    @Test
    fun `provider transport failure cancels the turn gracefully and speaks the local fallback`() {
        val h = Harness()
        h.activate(1, 100)
        h.recognition.emitFinalTranscript(listOf("what time is it"), 110)
        h.channel.enqueue(
            h.frame(0, h.sentRequestId(), STARTED_PAYLOAD),
            GatewayStreamReadResult.Failure(GatewayTransportReadFailure.CONNECTION_LOST),
        )
        assertEquals(ConversationPollStatus.ACTIVE, h.coordinator.poll(t(120)))
        assertEquals(ConversationPollStatus.ACTIVE, h.coordinator.poll(t(130)))

        assertEquals(1, h.synthesis.startCalls)
        assertEquals("provider lost", h.synthesis.request!!.text.value)
        h.synthesis.emitCompleted(140)

        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnOutcome.AI_FALLBACK, record.outcome)
        assertEquals(ConversationTurnFallbackReason.TRANSPORT_FAILURE, record.fallbackReason)
        assertEquals(GatewayClientTerminalStatus.TRANSPORT_FAILURE, record.gatewayTerminal)
        assertEquals(1, h.channel.closeCalls)
    }

    @Test
    fun `missing credentials degrade to spoken fallback without opening transport`() {
        val h = Harness(storeToken = false)
        h.activate(1, 100)
        h.recognition.emitFinalTranscript(listOf("what time is it"), 110)

        assertEquals(0, h.transport.openCalls)
        assertEquals(1, h.synthesis.startCalls)
        assertEquals("provider unavailable", h.synthesis.request!!.text.value)
        h.synthesis.emitCompleted(120)

        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnOutcome.AI_FALLBACK, record.outcome)
        assertEquals(ConversationTurnFallbackReason.START_REJECTED, record.fallbackReason)
    }

    @Test
    fun `refusal timeout and oversized output produce typed spoken fallbacks`() {
        runFallbackCase(
            terminalPayloads = listOf("{\"type\":\"refusal\",\"reason\":\"policy\"}"),
            expectedReason = ConversationTurnFallbackReason.REFUSED,
            expectedText = "generation failed",
        )
        runFallbackCase(
            terminalPayloads = listOf("{\"type\":\"timed_out\"}"),
            expectedReason = ConversationTurnFallbackReason.GENERATION_TIMEOUT,
            expectedText = "generation timed out",
        )
        runFallbackCase(
            terminalPayloads = listOf(
                "{\"type\":\"partial_text\",\"text\":\"${"a".repeat(1_001)}\"}",
                "{\"type\":\"completed\",\"reason\":\"complete\"}",
            ),
            expectedReason = ConversationTurnFallbackReason.OUTPUT_EXCEEDED,
            expectedText = "generation failed",
        )
    }

    @Test
    fun `empty model output is an honest typed fallback rather than silence`() {
        val h = Harness()
        h.activate(1, 100)
        h.recognition.emitFinalTranscript(listOf("what time is it"), 110)
        h.channel.enqueue(
            h.frame(0, h.sentRequestId(), STARTED_PAYLOAD),
            h.frame(1, h.sentRequestId(), "{\"type\":\"completed\",\"reason\":\"complete\"}"),
        )
        h.coordinator.poll(t(120))
        h.coordinator.poll(t(130))

        assertEquals(1, h.synthesis.startCalls)
        h.synthesis.emitCompleted(140)
        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnOutcome.AI_FALLBACK, record.outcome)
        assertEquals(ConversationTurnFallbackReason.EMPTY_OUTPUT, record.fallbackReason)
    }

    @Test
    fun `rejected bounded context never calls the model and falls back locally`() {
        val h = Harness(contextPolicyPublicOnly = true)
        h.activate(1, 100)
        h.recognition.emitFinalTranscript(listOf("what time is it"), 110)

        assertEquals(0, h.transport.openCalls)
        assertEquals(1, h.synthesis.startCalls)
        h.synthesis.emitCompleted(120)
        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnOutcome.AI_FALLBACK, record.outcome)
        assertEquals(ConversationTurnFallbackReason.CONTEXT_REJECTED, record.fallbackReason)
    }

    @Test
    fun `user cancellation during thinking cancels generation and stays silent`() {
        val h = Harness()
        h.activate(1, 100)
        h.recognition.emitFinalTranscript(listOf("what time is it"), 110)

        assertEquals(
            ConversationCancelDisposition.REQUESTED,
            h.coordinator.cancel(AudioSessionCancellationReason.USER_REQUEST, t(120)),
        )

        assertEquals(1, h.channel.cancelCalls)
        assertEquals(0, h.synthesis.startCalls)
        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnOutcome.CANCELLED, record.outcome)
        assertEquals(AudioSessionCancellationReason.USER_REQUEST, record.cancellationReason)
        assertEquals(
            ConversationCancelDisposition.NOT_ACTIVE,
            h.coordinator.cancel(AudioSessionCancellationReason.USER_REQUEST, t(125)),
        )
    }

    @Test
    fun `audio focus loss during speaking cancels output and the turn`() {
        val h = Harness()
        runAiTurnToSpeaking(h, text = "Hello there")

        h.focus.emitLoss(160)

        assertEquals(1, h.synthesis.cancelCalls)
        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnOutcome.CANCELLED, record.outcome)
        assertEquals(AudioSessionCancellationReason.AUDIO_FOCUS_LOST, record.cancellationReason)
    }

    @Test
    fun `thinking phase deadline fails closed with cancellation evidence`() {
        val h = Harness()
        h.activate(1, 100)
        h.recognition.emitFinalTranscript(listOf("what time is it"), 110)

        val deadline = 110L + h.policy.sessionPolicy.thinkingTimeoutMs
        assertEquals(ConversationPollStatus.IDLE, h.coordinator.poll(t(deadline)))

        assertEquals(1, h.channel.cancelCalls)
        assertEquals(1, h.channel.closeCalls)
        assertEquals(0, h.synthesis.startCalls)
        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnOutcome.ERROR, record.outcome)
    }

    @Test
    fun `recognition start rejection ends the turn honestly without ai work`() {
        val h = Harness(denyFocus = true)
        assertEquals(ConversationTurnStartDisposition.LISTENING_FAILED, h.activate(1, 100))
        assertEquals(0, h.transport.openCalls)
        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnOutcome.ERROR, record.outcome)
    }

    @Test
    fun `synthesis failure after generation records an error turn`() {
        val h = Harness()
        runAiTurnToSpeaking(h, text = "Hello there")

        h.synthesis.emitFailed(170, SpeechSynthesisFailure.SERVICE_UNAVAILABLE)

        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnOutcome.ERROR, record.outcome)
        assertEquals(GatewayClientTerminalStatus.COMPLETED, record.gatewayTerminal)
    }

    @Test
    fun `stale wrong-session and late events cannot revive or complete work`() {
        val h = Harness()
        h.activate(1, 100)
        h.recognition.emitWrongSessionFinalTranscript(listOf("stop"), 105)
        assertEquals(0, h.events.count { it is ConversationTurnEvent.TurnRouted })

        h.recognition.emitFinalTranscript(listOf("stop"), 110)
        assertEquals(ConversationTurnOutcome.LOCAL_STOP, h.coordinator.lastTurnRecord()!!.outcome)

        h.synthesis.emitCompleted(120)
        h.recognition.emitFinalTranscript(listOf("stop"), 125)
        assertEquals(ConversationTurnOutcome.LOCAL_STOP, h.coordinator.lastTurnRecord()!!.outcome)
        assertEquals(1, h.recognition.startCalls)
    }

    @Test
    fun `terminal session auto resets and a new explicit turn can start`() {
        val h = Harness()
        h.activate(1, 100)
        h.recognition.emitFinalTranscript(listOf("stop"), 110)
        assertEquals(ConversationTurnStartDisposition.ACCEPTED, h.activate(2, 200))
        assertEquals(2, h.recognition.startCalls)
        assertTrue(h.coordinator.snapshot().active)
    }

    @Test
    fun `records traces and events stay content free and deterministic`() {
        fun run(): ConversationTurnRecord {
            val h = Harness()
            h.activate(1, 100)
            h.recognition.emitFinalTranscript(listOf("what time is it"), 110)
            h.enqueueAiCompleted("Hello there")
            h.coordinator.poll(t(120))
            h.coordinator.poll(t(130))
            h.coordinator.poll(t(140))
            h.coordinator.poll(t(150))
            h.synthesis.emitCompleted(160)
            return h.coordinator.lastTurnRecord()!!
        }
        val first = run()
        val second = run()

        assertEquals(first.redactedTrace(), second.redactedTrace())
        assertEquals(first.latency.marks, second.latency.marks)
        val rendered = buildString {
            append(first.toString())
            append(first.redactedTrace())
            append(first.latency.toString())
            append("TurnStarted(session=redacted) TurnRouted(status=NO_MATCH) GenerationStarted(request=redacted)")
        }
        assertFalse(rendered.contains("what time is it"))
        assertFalse(rendered.contains("Hello there"))
    }

    @Test
    fun `policy blocked turn degrades to typed fallback with zero transport opens`() {
        val policy = RecordingPolicy { ModelPolicyDecision.Blocked(policyBlockReason(), ModelCircuitState.CLOSED) }
        val h = Harness(generationPolicy = policy)
        h.activate(1, 100)
        h.recognition.emitFinalTranscript(listOf("what time is it"), 110)

        assertEquals(0, h.transport.openCalls)
        assertEquals(1, h.synthesis.startCalls)
        assertEquals("provider unavailable", h.synthesis.request!!.text.value)
        h.synthesis.emitCompleted(130)

        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnOutcome.AI_FALLBACK, record.outcome)
        assertEquals(ConversationTurnFallbackReason.POLICY_BLOCKED, record.fallbackReason)
        assertEquals(1, policy.evaluateCalls)
        assertEquals(0, policy.terminalCalls)
    }

    @Test
    fun `budget exhaustion after one turn blocks the next before any transport open`() {
        val policy = exhaustAfterOneTurnPolicy()
        val h = Harness(generationPolicy = policy)

        h.activate(1, 100)
        h.recognition.emitFinalTranscript(listOf("what time is it"), 110)
        h.enqueueAiCompleted("Hello there")
        h.coordinator.poll(t(120))
        h.coordinator.poll(t(130))
        h.coordinator.poll(t(140))
        h.coordinator.poll(t(150))
        h.synthesis.emitCompleted(160)
        assertEquals(ConversationTurnOutcome.AI_COMPLETED, h.coordinator.lastTurnRecord()!!.outcome)

        val opensAfterFirstTurn = h.transport.openCalls
        h.activate(2, 200)
        h.recognition.emitFinalTranscript(listOf("what time is it"), 210)
        assertEquals(opensAfterFirstTurn, h.transport.openCalls)
        h.synthesis.emitCompleted(260)
        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnFallbackReason.POLICY_BLOCKED, record.fallbackReason)
    }

    @Test
    fun `open circuit fails fast on the next turn and local stop stays dominant`() {
        val policy = exhaustAfterOneTurnPolicy(failTerminal = TRANSPORT_FAILURE_ALIAS)
        val h = Harness(generationPolicy = policy)

        h.activate(1, 100)
        h.recognition.emitFinalTranscript(listOf("what time is it"), 110)
        h.channel.enqueue(
            h.frame(0, h.sentRequestId(), STARTED_PAYLOAD),
            GatewayStreamReadResult.Failure(GatewayTransportReadFailure.CONNECTION_LOST),
        )
        h.coordinator.poll(t(120))
        h.coordinator.poll(t(130))
        assertEquals(1, h.synthesis.startCalls)
        h.synthesis.emitCompleted(140)
        assertEquals(ConversationTurnOutcome.AI_FALLBACK, h.coordinator.lastTurnRecord()!!.outcome)
        val opensAfterFailure = h.transport.openCalls

        // Provider loss spoke its fallback; the circuit now blocks the next AI turn instantly.
        h.activate(2, 200)
        h.recognition.emitFinalTranscript(listOf("what time is it"), 210)
        assertEquals(opensAfterFailure, h.transport.openCalls)
        h.synthesis.emitCompleted(260)
        assertEquals(ConversationTurnFallbackReason.POLICY_BLOCKED, h.coordinator.lastTurnRecord()!!.fallbackReason)

        // Local stop never consults the policy and still wins over everything.
        val stopsBefore = h.synthesis.startCalls
        h.activate(3, 300)
        h.recognition.emitFinalTranscript(listOf("stop"), 310)
        assertEquals(ConversationTurnOutcome.LOCAL_STOP, h.coordinator.lastTurnRecord()!!.outcome)
        assertEquals(stopsBefore, h.synthesis.startCalls)
    }

    @Test
    fun `stream usage is reported to the policy for accounting`() {
        val policy = exhaustAfterOneTurnPolicy()
        val h = Harness(generationPolicy = policy)
        h.activate(1, 100)
        h.recognition.emitFinalTranscript(listOf("what time is it"), 110)
        h.enqueueAiCompleted("Hello there")
        h.coordinator.poll(t(120))
        h.coordinator.poll(t(130))
        h.coordinator.poll(t(140))
        h.coordinator.poll(t(150))
        h.synthesis.emitCompleted(160)

        val snapshot = policy.snapshot(170)
        assertTrue(snapshot.sessionRequests >= 1)
        assertTrue(snapshot.sessionOutputUnits >= 1)
    }

    @Test
    fun `stop priority turn leaves policy usage untouched`() {
        val policy = exhaustAfterOneTurnPolicy()
        val h = Harness(generationPolicy = policy)
        h.activate(1, 100)
        h.recognition.emitFinalTranscript(listOf("stop"), 110)

        assertEquals(ConversationTurnOutcome.LOCAL_STOP, h.coordinator.lastTurnRecord()!!.outcome)
        assertEquals(0, h.transport.openCalls)
        assertEquals(0, policy.evaluateCalls)
        assertEquals(0, policy.terminalCalls)
        assertEquals(0, policy.snapshot(120).sessionRequests)
    }

    @Test
    fun `coordinator source contains no authority execution memory or platform coupling`() {
        val sources = sourceFiles()
        assertTrue(sources.isNotEmpty())
        val text = sources.joinToString(separator = "\n") { it.readText(Charsets.UTF_8) }
        listOf(
            "io.redi.robot.trust.",
            "io.redi.robot.ai.tools.",
            "io.redi.robot.skills.local.",
            "io.redi.robot.skills.api.AuthorizedSkillDispatch",
            "io.redi.robot.platform.",
            "io.redi.robot.body.looi.",
            "io.redi.robot.feature.",
            "android.bluetooth",
            "ToolProposalValidator",
            "CapabilityBackedSkillExecutor",
            "AuthorizationGate",
        ).forEach { forbidden ->
            assertFalse("conversation/runtime must not reference $forbidden", text.contains(forbidden))
        }
    }

    private fun runFallbackCase(
        terminalPayloads: List<String>,
        expectedReason: ConversationTurnFallbackReason,
        expectedText: String,
    ) {
        val h = Harness()
        h.activate(1, 100)
        h.recognition.emitFinalTranscript(listOf("what time is it"), 110)
        val id = h.sentRequestId()
        h.channel.enqueue(h.frame(0, id, STARTED_PAYLOAD))
        terminalPayloads.forEachIndexed { index, payload -> h.channel.enqueue(h.frame(index + 1, id, payload)) }
        repeat(terminalPayloads.size + 1) { tick ->
            h.coordinator.poll(t(120L + tick * 10L))
        }
        assertEquals(1, h.synthesis.startCalls)
        assertEquals(expectedText, h.synthesis.request!!.text.value)
        h.synthesis.emitCompleted(140)
        val record = h.coordinator.lastTurnRecord()!!
        assertEquals(ConversationTurnOutcome.AI_FALLBACK, record.outcome)
        assertEquals(expectedReason, record.fallbackReason)
    }

    private fun runAiTurnToSpeaking(h: Harness, text: String) {
        h.activate(1, 100)
        h.recognition.emitFinalTranscript(listOf("what time is it"), 110)
        h.enqueueAiCompleted(text)
        h.coordinator.poll(t(120))
        h.coordinator.poll(t(130))
        h.coordinator.poll(t(140))
        h.coordinator.poll(t(150))
    }

    private inner class Harness(
        storeToken: Boolean = true,
        denyFocus: Boolean = false,
        contextPolicyPublicOnly: Boolean = false,
        ambiguousCorpus: Boolean = false,
        generationPolicy: ModelPolicyPort? = null,
    ) {
        val policy = ConversationTurnPolicy()
        val recognition = FakeRecognitionPort()
        val synthesis = FakeSynthesisPort()
        val focus = FakeFocusPort(denied = denyFocus)
        val channel = FakeChannel()
        val transport = FakeTransport(channel)
        val events = mutableListOf<ConversationTurnEvent>()
        val client: GatewayClient

        init {
            val endpointPolicy = GatewayEndpointPolicy.create(
                GatewayBuildMode.RELEASE,
                setOf(endpoint()),
            ).successAuth()
            val repository = GatewayTokenRepository(MemoryTokenStorage(), endpointPolicy)
            if (storeToken) {
                val token = GatewayAccessToken.copyOf(ByteArray(48) { (it * 5 + 19).toByte() }).successAuth()
                val request = StoreGatewayTokenRequest.create(
                    GatewayTokenId.newReference(),
                    GatewayClientSessionId.newReference(),
                    endpoint(),
                    setOf(GatewayAuthScope.TEXT),
                    epoch(1_000),
                    epoch(1_600),
                    token,
                ).successAuth()
                repository.store(request, null)
                token.close()
            }
            client = GatewayClient(repository, transport)
        }

        val coordinator = ConversationTurnCoordinator(
            gateway = client,
            recognitionPort = recognition,
            synthesisPort = synthesis,
            focusPort = focus,
            endpoint = endpoint(),
            fallbacks = fallbackMessages(),
            contextBuilder = ConversationContextBuilder(),
            contextPrivacyPolicy = if (contextPolicyPublicOnly) {
                ConversationContextPrivacyPolicy.create(setOf(ContextPrivacyClassification.PUBLIC)).successContext()
            } else {
                ConversationContextPrivacyPolicy.create(
                    setOf(ContextPrivacyClassification.PUBLIC, ContextPrivacyClassification.PRIVATE),
                ).successContext()
            },
            contextBudget = ConversationContextBudget.create(32, 8_192, 32_768).successContext(),
            snapshotProvider = { emptySnapshot() },
            authEpochNow = { epoch(1_100) },
            policy = policy,
            intentRouter = if (ambiguousCorpus) LocalIntentRouter(ambiguousCorpus()) else LocalIntentRouter(),
            generationPolicy = generationPolicy,
            listener = { event -> events.add(event) },
        )

        fun activate(index: Int, at: Long, locale: SpeechLocale = this@ConversationTurnCoordinatorTest.locale) =
            coordinator.activate(sessionId(index), locale, t(at))

        fun sentRequest(): GatewayRequest {
            val document = checkNotNull(transport.requestCopy) { "gateway transport was never opened" }
            return when (val decoded = GatewayContractCodec.decodeRequest(document.toString(StandardCharsets.UTF_8))) {
                is GatewayContractResult.Success -> decoded.value
                is GatewayContractResult.Failure -> error("captured gateway request did not decode")
            }
        }

        fun sentRequestId(): String = sentRequest().requestId.value

        fun enqueueAiCompleted(text: String) {
            val id = sentRequestId()
            channel.enqueue(
                frame(0, id, STARTED_PAYLOAD),
                frame(1, id, "{\"type\":\"partial_text\",\"text\":\"$text\"}"),
                frame(2, id, "{\"type\":\"usage\",\"evidence\":\"measured\",\"inputUnits\":12,\"outputUnits\":9}"),
                frame(3, id, "{\"type\":\"completed\",\"reason\":\"complete\"}"),
            )
        }

        fun frame(sequence: Int, requestId: String, payload: String): GatewayStreamReadResult.Frame {
            val tail = (sequence + 1).toString().padStart(12, '0')
            val json = """{"schemaVersion":"1.0","messageType":"event","requestId":"$requestId",""" +
                """"eventId":"20000000-0000-4000-8000-$tail","sequence":$sequence,"operation":"text","event":$payload}"""
            return when (val created = GatewayStreamReadResult.Frame.copyOf(json.toByteArray(StandardCharsets.UTF_8))) {
                is GatewayClientModelResult.Success -> created.value
                is GatewayClientModelResult.Failure -> error("synthetic frame exceeded bounds")
            }
        }
    }

    private class FakeRecognitionPort : SpeechRecognitionPort {
        var startCalls = 0
        var cancelCalls = 0
        private var listener: SpeechRecognitionListener? = null
        private var request: SpeechRecognitionRequest? = null

        override fun start(
            request: SpeechRecognitionRequest,
            listener: SpeechRecognitionListener,
        ): SpeechAdapterStartResult {
            startCalls++
            this.request = request
            this.listener = listener
            return SpeechAdapterStartResult(SpeechAdapterStartDisposition.ACCEPTED, true)
        }

        override fun cancel(operationId: SpeechOperationId): SpeechAdapterCancelDisposition {
            cancelCalls++
            return SpeechAdapterCancelDisposition.REQUESTED
        }

        override fun snapshot(): SpeechAdapterSnapshot =
            SpeechAdapterSnapshot(active = false, cancelling = false, activeOperationId = null)

        fun emitFinalTranscript(alternatives: List<String>, at: Long) {
            val request = this.request ?: return
            val current = listener ?: return
            val transcript = (EphemeralSpeechTranscript.create(alternatives))
                as SpeechContractResult.Success<EphemeralSpeechTranscript>
            current.onEvent(
                SpeechRecognitionEvent.FinalTranscript(request.operationId, request.sessionId, t(at), transcript.value),
            )
        }

        fun emitWrongSessionFinalTranscript(alternatives: List<String>, at: Long) {
            val request = this.request ?: return
            val current = listener ?: return
            val transcript = (EphemeralSpeechTranscript.create(alternatives))
                as SpeechContractResult.Success<EphemeralSpeechTranscript>
            current.onEvent(
                SpeechRecognitionEvent.FinalTranscript(request.operationId, sessionId(99), t(at), transcript.value),
            )
        }
    }

    private class FakeSynthesisPort : SpeechSynthesisPort {
        var startCalls = 0
        var cancelCalls = 0
        var request: SpeechSynthesisRequest? = null
        private var listener: SpeechSynthesisListener? = null

        override fun start(
            request: SpeechSynthesisRequest,
            listener: SpeechSynthesisListener,
        ): SpeechAdapterStartResult {
            startCalls++
            this.request = request
            this.listener = listener
            return SpeechAdapterStartResult(SpeechAdapterStartDisposition.ACCEPTED, true)
        }

        override fun cancel(operationId: SpeechOperationId): SpeechAdapterCancelDisposition {
            cancelCalls++
            return SpeechAdapterCancelDisposition.REQUESTED
        }

        override fun snapshot(): SpeechAdapterSnapshot =
            SpeechAdapterSnapshot(active = false, cancelling = false, activeOperationId = null)

        fun emitCompleted(at: Long) {
            val request = this.request ?: return
            val current = listener ?: return
            current.onEvent(SpeechSynthesisEvent.Completed(request.operationId, request.sessionId, t(at)))
        }

        fun emitFailed(at: Long, reason: SpeechSynthesisFailure) {
            val request = this.request ?: return
            val current = listener ?: return
            current.onEvent(SpeechSynthesisEvent.Failed(request.operationId, request.sessionId, t(at), reason))
        }
    }

    private class FakeFocusPort(private val denied: Boolean = false) : SpeechAudioFocusPort {
        private var listener: SpeechAudioFocusListener? = null
        private var acquiredOperation: SpeechOperationId? = null
        private var acquiredSession: AudioSessionId? = null

        override fun acquire(
            request: SpeechAudioFocusRequest,
            listener: SpeechAudioFocusListener,
        ): SpeechAudioFocusAcquireResult {
            this.listener = listener
            acquiredOperation = request.operationId
            acquiredSession = request.sessionId
            return if (denied) {
                SpeechAudioFocusAcquireResult(SpeechAudioFocusAcquireDisposition.DENIED, false)
            } else {
                SpeechAudioFocusAcquireResult(SpeechAudioFocusAcquireDisposition.GRANTED, true)
            }
        }

        override fun release(operationId: SpeechOperationId): SpeechAudioFocusReleaseDisposition =
            SpeechAudioFocusReleaseDisposition.RELEASED

        override fun snapshot(): SpeechAudioFocusSnapshot =
            SpeechAudioFocusSnapshot(owned = false, operationId = null)

        fun emitLoss(at: Long) {
            val current = listener ?: return
            current.onFocusEvent(
                SpeechAudioFocusEvent(
                    checkNotNull(acquiredOperation),
                    checkNotNull(acquiredSession),
                    t(at),
                    SpeechAudioFocusLoss.PERMANENT,
                ),
            )
        }
    }

    private class FakeTransport(private val channel: FakeChannel) : GatewayHttpStreamPort {
        var openCalls = 0
        var requestCopy: ByteArray? = null

        override fun open(
            endpoint: GatewayEndpointOrigin,
            bearerToken: ByteArray,
            requestDocument: ByteArray,
        ): GatewayTransportOpenResult {
            openCalls++
            requestCopy = requestDocument.copyOf()
            return GatewayTransportOpenResult.Accepted(channel)
        }
    }

    private class FakeChannel : GatewayHttpStreamChannel {
        private val reads = ArrayDeque<GatewayStreamReadResult>()
        var cancelCalls = 0
        var readCalls = 0
        var closeCalls = 0

        fun enqueue(vararg values: GatewayStreamReadResult) {
            values.forEach(reads::addLast)
        }

        override fun readNext(): GatewayStreamReadResult {
            readCalls++
            return reads.removeFirstOrNull() ?: GatewayStreamReadResult.WouldBlock
        }

        override fun cancel(cancellationDocument: ByteArray): GatewayTransportCancellationDelivery {
            cancelCalls++
            return GatewayTransportCancellationDelivery.DELIVERED
        }

        override fun close() {
            closeCalls++
        }
    }

    private class MemoryTokenStorage : ProtectedGatewayTokenStorage {
        private var content: ByteArray? = null
        override fun read(): GatewayTokenDocumentReadResult = content?.copyOf()?.let {
            GatewayTokenDocumentReadResult.Content(it)
        } ?: GatewayTokenDocumentReadResult.Missing

        override fun writeAtomically(plaintext: ByteArray): GatewayTokenDocumentWriteResult {
            content = plaintext.copyOf()
            return GatewayTokenDocumentWriteResult.COMMITTED
        }

        override fun reset(): GatewayTokenDocumentResetResult {
            content?.fill(0)
            content = null
            return GatewayTokenDocumentResetResult.RESET
        }
    }

    private fun ambiguousCorpus(): LocalIntentCorpus {
        fun rule(id: String, capability: String): LocalIntentRule {
            val created = LocalIntentRule.create(
                (LocalIntentRuleId.parse(id) as LocalIntentModelResult.Success).value,
                (LocalIntentLocale.parse("en-US") as LocalIntentModelResult.Success).value,
                setOf("status"),
                LocalCapabilityProposal(
                    (CapabilityReference.parse(capability) as CapabilityIdentityResult.Success).value,
                    null,
                    LocalIntentPriority.NORMAL,
                ),
            )
            return (created as LocalIntentModelResult.Success).value
        }
        val corpus = LocalIntentCorpus.create(
            (LocalIntentCorpusId.parse("test.ambiguous") as LocalIntentModelResult.Success).value,
            1,
            setOf((LocalIntentLocale.parse("en-US") as LocalIntentModelResult.Success).value),
            listOf(rule("one", "provider.connection.status@1.0"), rule("two", "safety.lower_contact.status@1.0")),
        )
        return (corpus as LocalIntentModelResult.Success).value
    }

    private fun fallbackMessages(): ConversationFallbackMessages {
        fun text(value: String): SpeechText = (SpeechText.create(value) as SpeechContractResult.Success).value

        return ConversationFallbackMessages.create(
            clarification = text("which command did you mean"),
            unsupportedLocale = text("locale not available locally"),
            invalidRoute = text("command not understood"),
            providerUnavailable = text("provider unavailable"),
            providerLost = text("provider lost"),
            generationFailed = text("generation failed"),
            generationTimedOut = text("generation timed out"),
        )
    }

    private fun emptySnapshot(): ContextSnapshot {
        val budget = (ContextSnapshotBudget.create(16, 8, 32, 16_384) as ContextSnapshotResult.Success).value
        val request = when (val created = ContextSnapshotRequest.create(
            timestamp(100),
            null,
            ContextPrivacyClassification.entries.toSet(),
            budget,
        )) {
            is ContextSnapshotResult.Success -> created.value
            is ContextSnapshotResult.Failure -> error("empty snapshot request was rejected")
        }
        return when (val built = ContextSnapshotEngine.build(emptyList(), request)) {
            is ContextSnapshotResult.Success -> built.value
            is ContextSnapshotResult.Failure -> error("empty snapshot build was rejected")
        }
    }

    private fun sourceFiles(): List<File> {
        val relative = "app/src/main/java/io/redi/robot/conversation/runtime"
        val roots = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }.take(8)
        val root = roots.firstOrNull { File(it, relative).isDirectory }
            ?: error("Unable to locate conversation runtime source root")
        return File(root, relative).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Scripted policy port recording every interaction for assertions. */
    private class RecordingPolicy(
        private val decision: () -> ModelPolicyDecision,
    ) : ModelPolicyPort {
        var evaluateCalls = 0
        var terminalCalls = 0
        var lastSnapshot: ModelPolicyUsageSnapshot? = null

        override fun evaluate(
            task: ModelTaskClass,
            privacy: ContextPrivacyClassification,
            monotonicNowMs: Long,
            epochDay: Long,
        ): ModelPolicyDecision {
            evaluateCalls++
            return decision()
        }

        override fun onTerminal(
            task: ModelTaskClass,
            status: GatewayClientTerminalStatus,
            usage: GatewayUsage?,
            monotonicNowMs: Long,
            epochDay: Long,
        ) {
            terminalCalls++
        }

        override fun onStartRejected(task: ModelTaskClass) = Unit

        override fun snapshot(monotonicNowMs: Long): ModelPolicyUsageSnapshot {
            val value = ModelPolicyUsageSnapshot(0, 0, 0, 0, 0, 0, 0, 0, ModelCircuitState.CLOSED, null)
            lastSnapshot = value
            return value
        }
    }

    private fun policyBlockReason() = ModelPolicyBlockReason.SESSION_BUDGET_EXHAUSTED

    /** One-turn engine: first allowed turn accrues real H051 usage, then the session cap blocks. */
    private fun exhaustAfterOneTurnPolicy(
        failTerminal: GatewayClientTerminalStatus? = null,
    ): ModelPolicyEngineRecorder {
        val taskPolicy = (ModelTaskPolicy.create(
            (GatewayBudget.create(512, 30_000) as GatewayContractResult.Success).value,
            4_096,
            setOf(ContextPrivacyClassification.PUBLIC, ContextPrivacyClassification.PRIVATE),
            1,
            100_000,
            100,
            1_000_000,
        ) as ModelPolicyResult.Success).value
        val configuration = (ModelPolicyConfiguration.create(
            mapOf(ModelTaskClass.CONVERSATION_TEXT to taskPolicy),
            (ModelCircuitPolicy.create(1, 60_000) as ModelPolicyResult.Success).value,
        ) as ModelPolicyResult.Success).value
        return ModelPolicyEngineRecorder(ModelPolicyEngine(configuration), failTerminal)
    }

    /** Wraps the real engine with a forced scripted terminal to drive circuit transitions. */
    private class ModelPolicyEngineRecorder(
        private val engine: ModelPolicyEngine,
        private val forcedTerminal: GatewayClientTerminalStatus?,
    ) : ModelPolicyPort {
        var lastSnapshot: ModelPolicyUsageSnapshot? = null
        var evaluateCalls = 0
        var terminalCalls = 0

        override fun evaluate(
            task: ModelTaskClass,
            privacy: ContextPrivacyClassification,
            monotonicNowMs: Long,
            epochDay: Long,
        ): ModelPolicyDecision {
            evaluateCalls++
            return engine.evaluate(task, privacy, monotonicNowMs, epochDay)
        }

        override fun onTerminal(
            task: ModelTaskClass,
            status: GatewayClientTerminalStatus,
            usage: GatewayUsage?,
            monotonicNowMs: Long,
            epochDay: Long,
        ) {
            terminalCalls++
            engine.onTerminal(task, forcedTerminal ?: status, usage, monotonicNowMs, epochDay)
        }

        override fun onStartRejected(task: ModelTaskClass) = engine.onStartRejected(task)

        override fun snapshot(monotonicNowMs: Long): ModelPolicyUsageSnapshot =
            engine.snapshot(monotonicNowMs).also { lastSnapshot = it }
    }

    private companion object {
        private const val STARTED_PAYLOAD = "{\"type\":\"started\"}"
    }
}

private val TRANSPORT_FAILURE_ALIAS: GatewayClientTerminalStatus =
    GatewayClientTerminalStatus.TRANSPORT_FAILURE

private fun t(value: Long): AudioMonotonicTimeMs = AudioMonotonicTimeMs(value)

private fun sessionId(index: Int): AudioSessionId =
    AudioSessionId.fromUuid(UUID.fromString("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"))

private fun speechLocale(tag: String): SpeechLocale =
    (SpeechLocale.parse(tag) as SpeechContractResult.Success).value

private fun epoch(value: Long): GatewayAuthEpochSeconds =
    (GatewayAuthEpochSeconds.create(value) as GatewayAuthModelResult.Success).value

private fun endpoint(): GatewayEndpointOrigin =
    (GatewayEndpointOrigin.parse("https://gateway.test") as GatewayAuthModelResult.Success).value

private fun timestamp(value: Long): MonotonicTimestampMs =
    (MonotonicTimestampMs.create(value) as CapabilityEnvelopeResult.Success).value

private fun <T> GatewayAuthModelResult<T>.successAuth(): T =
    (this as GatewayAuthModelResult.Success).value

private fun <T> ConversationContextResult<T>.successContext(): T =
    (this as ConversationContextResult.Success).value
