package io.redi.robot.firstlife.runtime

import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.negotiation.CapabilityNegotiationId
import io.redi.robot.capability.negotiation.CapabilityNegotiationReport
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleState
import io.redi.robot.connection.api.ConnectionLifecycleState
import io.redi.robot.connection.api.ConnectionReadiness
import io.redi.robot.connection.api.ConnectionSessionId
import io.redi.robot.context.provider.BodyAvailability
import io.redi.robot.discovery.identity.KnownProviderIdentityCandidate
import io.redi.robot.firstlife.interaction.FirstLifePresentationIntent
import io.redi.robot.hugo.identity.HugoIdentity
import io.redi.robot.provider.calibration.CalibrationNegotiationReadinessStatus
import io.redi.robot.provider.diagnostic.BlockedStateDiagnostic
import io.redi.robot.provider.diagnostic.ProviderDiagnosticId
import io.redi.robot.provider.runtime.ProviderOrchestrationPhase
import io.redi.robot.trust.runtime.AuthorizationDecisionStatus

/**
 * Slice-local composition of accepted HUGO evidence. It has no provider port and deliberately
 * cannot connect, select, authorize, dispatch, or execute anything.
 */
class FirstLifeRuntimeCoordinator(
    identity: HugoIdentity,
    sessionId: FirstLifeRuntimeSessionId,
    startedAt: FirstLifeRuntimeTimeMs,
    relevantCapabilities: Set<CapabilityReference>,
    authorizationRequiredCapabilities: Set<CapabilityReference> = relevantCapabilities,
) {
    private val hugoIdentity = identity
    private val requiredCapabilities = relevantCapabilities.toSet()
    private val requiredAuthorizationCapabilities = authorizationRequiredCapabilities.toSet().also {
        require(requiredCapabilities.containsAll(it))
    }
    private var state = State(sessionId = sessionId, occurredAt = startedAt)

    fun snapshot(): FirstLifeRuntimeSnapshot = project()

    fun initialUpdate(): FirstLifeRuntimeUpdate = FirstLifeRuntimeUpdate(
        snapshot = project(),
        interactionSignals = listOf(FirstLifeRuntimeInteractionSignal.RuntimeReady),
        presentationIntent = FirstLifePresentationIntent.IDLE,
        disposition = FirstLifeRuntimeDisposition.APPLIED,
    )

    @Synchronized
    fun accept(envelope: FirstLifeRuntimeEnvelope): FirstLifeRuntimeUpdate {
        val prior = project()
        correlationFailure(envelope)?.let { return ignored(prior, it) }
        val disposition = when (val event = envelope.event) {
            is FirstLifeRuntimeEvent.ProviderState -> acceptProvider(event)
            is FirstLifeRuntimeEvent.ConnectionState -> acceptConnection(event)
            is FirstLifeRuntimeEvent.NegotiationState -> acceptNegotiation(event.report, envelope.occurredAt)
            is FirstLifeRuntimeEvent.ExplicitDemoConsent -> acceptConsent(event)
            is FirstLifeRuntimeEvent.AuthorizationState -> acceptAuthorization(event)
            is FirstLifeRuntimeEvent.CalibrationState -> acceptCalibration(event)
            is FirstLifeRuntimeEvent.BodyState -> acceptBody(event)
            is FirstLifeRuntimeEvent.Blocked -> acceptBlocked(event)
            is FirstLifeRuntimeEvent.NegotiationExpired -> expireNegotiation(event)
            is FirstLifeRuntimeEvent.AuthorizationLost -> loseAuthorization(event)
            is FirstLifeRuntimeEvent.ProviderLost -> loseProvider(event)
            is FirstLifeRuntimeEvent.Reset -> reset(event, envelope.occurredAt)
        }
        if (disposition != FirstLifeRuntimeDisposition.APPLIED) return ignored(prior, disposition)
        if (envelope.event !is FirstLifeRuntimeEvent.Reset) {
            state.lastSequence = envelope.sequence.value
            state.occurredAt = envelope.occurredAt
        }
        val current = project()
        return FirstLifeRuntimeUpdate(
            current,
            semanticSignals(prior, current),
            presentation(current),
            FirstLifeRuntimeDisposition.APPLIED,
        )
    }

    private fun correlationFailure(envelope: FirstLifeRuntimeEnvelope): FirstLifeRuntimeDisposition? = when {
        envelope.sessionId != state.sessionId -> FirstLifeRuntimeDisposition.IGNORED_WRONG_SESSION
        envelope.sequence.value < state.lastSequence -> FirstLifeRuntimeDisposition.IGNORED_STALE_SEQUENCE
        envelope.sequence.value == state.lastSequence -> FirstLifeRuntimeDisposition.IGNORED_DUPLICATE
        envelope.occurredAt.value < state.occurredAt.value -> FirstLifeRuntimeDisposition.IGNORED_STALE_TIME
        else -> null
    }

    private fun acceptProvider(event: FirstLifeRuntimeEvent.ProviderState): FirstLifeRuntimeDisposition {
        val correlation = event.orchestration.correlation
            ?: return FirstLifeRuntimeDisposition.IGNORED_WRONG_CORRELATION
        state.providerGeneration?.let { current ->
            if (correlation.generation != current) {
                return if (correlation.generation.value < current.value) {
                    FirstLifeRuntimeDisposition.IGNORED_STALE_GENERATION
                } else FirstLifeRuntimeDisposition.IGNORED_WRONG_CORRELATION
            }
        }
        if (event.orchestration.phase == ProviderOrchestrationPhase.OBSERVED) {
            state.providerPhase = event.orchestration.phase
            state.diagnosticReference = event.diagnosticReference
            state.blocked = null
            return FirstLifeRuntimeDisposition.APPLIED
        }
        if (event.orchestration.phase in blockedPhases) {
            if (event.blockedState == null) return FirstLifeRuntimeDisposition.IGNORED_INVALID_STAGE
            state.providerPhase = event.orchestration.phase
            state.diagnosticReference = event.diagnosticReference
            state.blocked = event.blockedState
            return FirstLifeRuntimeDisposition.APPLIED
        }
        if (event.orchestration.phase != ProviderOrchestrationPhase.PREPARED) {
            return FirstLifeRuntimeDisposition.IGNORED_INVALID_STAGE
        }
        val candidate = event.orchestration.identityResolution?.candidates
            ?.filterIsInstance<KnownProviderIdentityCandidate>()?.singleOrNull()
            ?: return FirstLifeRuntimeDisposition.IGNORED_WRONG_CORRELATION
        val registered = event.registry.find(candidate.identity.instanceId)
            ?: return FirstLifeRuntimeDisposition.IGNORED_WRONG_CORRELATION
        val record = registered.record
        if (record.identity != candidate.identity || record.runtime.generation != correlation.generation) {
            return FirstLifeRuntimeDisposition.IGNORED_WRONG_CORRELATION
        }
        state.providerInstanceId = record.identity.instanceId
        state.providerGeneration = correlation.generation
        state.alias = record.identity.displayAlias
        state.family = record.identity.family
        state.runtimeAvailability = record.runtime.availability.availability
        state.providerPhase = event.orchestration.phase
        state.diagnosticReference = event.diagnosticReference
        state.blocked = null
        state.prepared = true
        state.registered = record.runtime.lifecycle is ProviderLifecycleState.Registered
        return FirstLifeRuntimeDisposition.APPLIED
    }

    private fun acceptConnection(event: FirstLifeRuntimeEvent.ConnectionState): FirstLifeRuntimeDisposition {
        generationFailure(event.providerGeneration)?.let { return it }
        if (event.lifecycle.latestGeneration?.value != event.providerGeneration.value) {
            return FirstLifeRuntimeDisposition.IGNORED_WRONG_CORRELATION
        }
        val connected = event.lifecycle.state as? ConnectionLifecycleState.Connected
        if (connected != null) {
            if (connected.session.generation.value != event.providerGeneration.value) {
                return FirstLifeRuntimeDisposition.IGNORED_WRONG_CORRELATION
            }
            state.connectionReadiness = ConnectionReadiness.CHANNEL_ESTABLISHED
            state.connectionSessionId = connected.session.id
        } else {
            state.connectionReadiness = ConnectionReadiness.NOT_CONNECTED
            state.connectionSessionId = null
            clearNegotiationAndBelow()
        }
        return FirstLifeRuntimeDisposition.APPLIED
    }

    private fun acceptNegotiation(
        report: CapabilityNegotiationReport,
        occurredAt: FirstLifeRuntimeTimeMs,
    ): FirstLifeRuntimeDisposition {
        generationFailure(report.providerGeneration)?.let { return it }
        if (report.providerInstanceId != state.providerInstanceId) {
            return FirstLifeRuntimeDisposition.IGNORED_WRONG_CORRELATION
        }
        val recordsCorrelated = report.negotiatedCapabilities.all {
            it.providerInstanceId == state.providerInstanceId &&
                it.providerGeneration == report.providerGeneration &&
                it.negotiationId == report.negotiationId &&
                occurredAt.value >= it.negotiatedAt.value && occurredAt.value < it.validUntil.value &&
                (state.connectionSessionId == null || state.connectionSessionId in it.connectionSessionIds)
        }
        if (!recordsCorrelated) return FirstLifeRuntimeDisposition.IGNORED_WRONG_CORRELATION
        state.negotiationId = report.negotiationId
        state.negotiationStatus = report.status
        state.capabilities = if (report.status in successfulNegotiationStatuses) {
            report.negotiatedCapabilities.map { it.capability }.toSet()
        } else emptySet()
        state.authorization.clear()
        state.explicitConsent = false
        state.calibration = null
        return FirstLifeRuntimeDisposition.APPLIED
    }

    private fun acceptConsent(event: FirstLifeRuntimeEvent.ExplicitDemoConsent): FirstLifeRuntimeDisposition {
        generationFailure(event.providerGeneration)?.let { return it }
        if (event.negotiationId != state.negotiationId || state.negotiationStatus !in successfulNegotiationStatuses) {
            return FirstLifeRuntimeDisposition.IGNORED_WRONG_CORRELATION
        }
        state.explicitConsent = true
        return FirstLifeRuntimeDisposition.APPLIED
    }

    private fun acceptAuthorization(event: FirstLifeRuntimeEvent.AuthorizationState): FirstLifeRuntimeDisposition {
        val audit = event.decision.audit
        generationFailure(audit.providerGeneration)?.let { return it }
        if (!state.explicitConsent || audit.providerInstanceId != state.providerInstanceId ||
            audit.negotiationId != state.negotiationId || audit.capability !in state.capabilities
        ) return FirstLifeRuntimeDisposition.IGNORED_WRONG_CORRELATION
        state.authorization[audit.capability] = event.decision.status
        return FirstLifeRuntimeDisposition.APPLIED
    }

    private fun acceptCalibration(event: FirstLifeRuntimeEvent.CalibrationState): FirstLifeRuntimeDisposition {
        val value = event.readiness
        generationFailure(value.providerGeneration)?.let { return it }
        if (value.providerInstanceId != state.providerInstanceId || value.negotiationId != state.negotiationId) {
            return FirstLifeRuntimeDisposition.IGNORED_WRONG_CORRELATION
        }
        state.calibration = value.status
        return FirstLifeRuntimeDisposition.APPLIED
    }

    private fun acceptBody(event: FirstLifeRuntimeEvent.BodyState): FirstLifeRuntimeDisposition {
        val instance = state.providerInstanceId ?: return FirstLifeRuntimeDisposition.IGNORED_INVALID_STAGE
        if (state.family != ProviderFamily.BODY_ADAPTER) {
            return if (event.state.bodies.isEmpty() && event.state.activeSelection == null) {
                FirstLifeRuntimeDisposition.APPLIED
            } else FirstLifeRuntimeDisposition.IGNORED_WRONG_CORRELATION
        }
        val body = event.state.bodies.singleOrNull { it.body.identity.instanceId == instance }
            ?: return FirstLifeRuntimeDisposition.IGNORED_WRONG_CORRELATION
        state.bodyAvailability = body.availability
        state.activeBodySelected = event.state.activeSelection?.body == body.body
        state.activeBodySelectionExpiresAt = event.state.activeSelection?.expiresAt?.value
        return FirstLifeRuntimeDisposition.APPLIED
    }

    private fun acceptBlocked(event: FirstLifeRuntimeEvent.Blocked): FirstLifeRuntimeDisposition {
        event.providerGeneration?.let { generationFailure(it)?.let { failure -> return failure } }
        state.blocked = event.state
        state.diagnosticReference = event.diagnosticReference ?: state.diagnosticReference
        return FirstLifeRuntimeDisposition.APPLIED
    }

    private fun expireNegotiation(event: FirstLifeRuntimeEvent.NegotiationExpired): FirstLifeRuntimeDisposition {
        generationFailure(event.providerGeneration)?.let { return it }
        if (event.negotiationId != state.negotiationId) return FirstLifeRuntimeDisposition.IGNORED_WRONG_CORRELATION
        clearNegotiationAndBelow()
        return FirstLifeRuntimeDisposition.APPLIED
    }

    private fun loseAuthorization(event: FirstLifeRuntimeEvent.AuthorizationLost): FirstLifeRuntimeDisposition {
        generationFailure(event.providerGeneration)?.let { return it }
        if (event.negotiationId != state.negotiationId) return FirstLifeRuntimeDisposition.IGNORED_WRONG_CORRELATION
        state.authorization.clear()
        state.explicitConsent = false
        return FirstLifeRuntimeDisposition.APPLIED
    }

    private fun loseProvider(event: FirstLifeRuntimeEvent.ProviderLost): FirstLifeRuntimeDisposition {
        generationFailure(event.providerGeneration)?.let { return it }
        val session = state.sessionId
        val sequence = state.lastSequence
        val time = state.occurredAt
        state = State(session, sequence, time)
        return FirstLifeRuntimeDisposition.APPLIED
    }

    private fun reset(event: FirstLifeRuntimeEvent.Reset, time: FirstLifeRuntimeTimeMs): FirstLifeRuntimeDisposition {
        if (event.nextSessionId == state.sessionId) return FirstLifeRuntimeDisposition.IGNORED_WRONG_SESSION
        state = State(sessionId = event.nextSessionId, occurredAt = time)
        return FirstLifeRuntimeDisposition.APPLIED
    }

    private fun generationFailure(value: ProviderGeneration): FirstLifeRuntimeDisposition? {
        val current = state.providerGeneration ?: return FirstLifeRuntimeDisposition.IGNORED_INVALID_STAGE
        return when {
            value.value < current.value -> FirstLifeRuntimeDisposition.IGNORED_STALE_GENERATION
            value != current -> FirstLifeRuntimeDisposition.IGNORED_WRONG_CORRELATION
            else -> null
        }
    }

    private fun clearNegotiationAndBelow() {
        state.negotiationId = null
        state.negotiationStatus = null
        state.capabilities = emptySet()
        state.authorization.clear()
        state.explicitConsent = false
        state.calibration = null
        state.activeBodySelected = false
        state.activeBodySelectionExpiresAt = null
    }

    private fun project(): FirstLifeRuntimeSnapshot {
        val negotiated = state.negotiationStatus in successfulNegotiationStatuses &&
            requiredCapabilities.all { it in state.capabilities }
        val bodyReady = state.prepared && state.registered &&
            state.runtimeAvailability == ProviderRuntimeAvailability.AVAILABLE &&
            state.connectionReadiness == ConnectionReadiness.CHANNEL_ESTABLISHED && negotiated &&
            state.bodyAvailability == BodyAvailability.AVAILABLE && state.blocked == null
        val authorizationReady = requiredAuthorizationCapabilities.isNotEmpty() &&
            requiredAuthorizationCapabilities.all {
            state.authorization[it] == AuthorizationDecisionStatus.AUTHORIZED
        }
        return FirstLifeRuntimeSnapshot(
            hugoIdentity, state.sessionId, state.lastSequence, state.occurredAt, state.providerPhase,
            state.prepared, state.registered, state.alias, state.family, state.providerGeneration,
            state.runtimeAvailability, state.connectionReadiness, state.negotiationId,
            state.negotiationStatus, state.capabilities.toList(),
            state.authorization.map { FirstLifeAuthorizationReadiness(it.key, it.value) },
            state.explicitConsent, state.calibration, state.bodyAvailability, state.activeBodySelected,
            state.activeBodySelectionExpiresAt?.let(::FirstLifeRuntimeTimeMs),
            bodyReady, authorizationReady,
            bodyReady && authorizationReady && state.activeBodySelected &&
                state.calibration == CalibrationNegotiationReadinessStatus.READY,
            state.blocked, state.diagnosticReference,
        )
    }

    private fun semanticSignals(
        prior: FirstLifeRuntimeSnapshot,
        current: FirstLifeRuntimeSnapshot,
    ): List<FirstLifeRuntimeInteractionSignal> {
        val signals = mutableListOf<FirstLifeRuntimeInteractionSignal>()
        if (!prior.bodyReady && current.bodyReady) signals += FirstLifeRuntimeInteractionSignal.BodyAvailable
        if (prior.bodyReady && !current.bodyReady) signals += FirstLifeRuntimeInteractionSignal.BodyUnavailable
        current.negotiatedCapabilities.filter { it !in prior.negotiatedCapabilities }.forEach {
            signals += FirstLifeRuntimeInteractionSignal.CapabilityReady(it)
        }
        if (prior.blockedState == null && current.blockedState != null) {
            signals += FirstLifeRuntimeInteractionSignal.ProviderBlocked
        }
        if (prior.providerPrepared && !current.providerPrepared) signals += FirstLifeRuntimeInteractionSignal.RuntimeLost
        if (prior.runtimeSessionId != current.runtimeSessionId) signals += FirstLifeRuntimeInteractionSignal.RuntimeReady
        return signals
    }

    private fun presentation(snapshot: FirstLifeRuntimeSnapshot): FirstLifePresentationIntent = when {
        snapshot.blockedState != null -> FirstLifePresentationIntent.BLOCKED
        snapshot.bodyReady -> FirstLifePresentationIntent.BODY_READY
        else -> FirstLifePresentationIntent.IDLE
    }

    private fun ignored(snapshot: FirstLifeRuntimeSnapshot, disposition: FirstLifeRuntimeDisposition) =
        FirstLifeRuntimeUpdate(snapshot, emptyList(), null, disposition)

    private data class State(
        var sessionId: FirstLifeRuntimeSessionId,
        var lastSequence: Long = 0L,
        var occurredAt: FirstLifeRuntimeTimeMs,
        var providerPhase: ProviderOrchestrationPhase = ProviderOrchestrationPhase.INACTIVE,
        var providerInstanceId: ProviderInstanceId? = null,
        var providerGeneration: ProviderGeneration? = null,
        var alias: RedactedProviderAlias? = null,
        var family: ProviderFamily? = null,
        var runtimeAvailability: ProviderRuntimeAvailability? = null,
        var prepared: Boolean = false,
        var registered: Boolean = false,
        var connectionReadiness: ConnectionReadiness = ConnectionReadiness.NOT_CONNECTED,
        var connectionSessionId: ConnectionSessionId? = null,
        var negotiationId: CapabilityNegotiationId? = null,
        var negotiationStatus: io.redi.robot.capability.negotiation.CapabilityNegotiationStatus? = null,
        var capabilities: Set<CapabilityReference> = emptySet(),
        val authorization: MutableMap<CapabilityReference, AuthorizationDecisionStatus> = linkedMapOf(),
        var explicitConsent: Boolean = false,
        var calibration: CalibrationNegotiationReadinessStatus? = null,
        var bodyAvailability: BodyAvailability = BodyAvailability.UNAVAILABLE,
        var activeBodySelected: Boolean = false,
        var activeBodySelectionExpiresAt: Long? = null,
        var blocked: BlockedStateDiagnostic? = null,
        var diagnosticReference: ProviderDiagnosticId? = null,
    )

    private companion object {
        val blockedPhases = setOf(
            ProviderOrchestrationPhase.NEEDS_ADAPTER,
            ProviderOrchestrationPhase.AMBIGUOUS,
            ProviderOrchestrationPhase.BLOCKED,
            ProviderOrchestrationPhase.FAILED,
            ProviderOrchestrationPhase.CANCELLED,
        )
    }
}
