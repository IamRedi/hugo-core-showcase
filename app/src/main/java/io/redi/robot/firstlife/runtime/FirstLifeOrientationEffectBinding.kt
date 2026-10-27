package io.redi.robot.firstlife.runtime

import io.redi.robot.capability.api.CapabilityActorKind
import io.redi.robot.capability.api.CapabilityArgumentName
import io.redi.robot.capability.api.CapabilityArgumentValue
import io.redi.robot.capability.api.CapabilityAuditId
import io.redi.robot.capability.api.CapabilityAuthorizationReference
import io.redi.robot.capability.api.CapabilityContractReference
import io.redi.robot.capability.api.CapabilityData
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.CapabilityExecutionContext
import io.redi.robot.capability.api.CapabilityProviderTarget
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.CapabilityRequest
import io.redi.robot.capability.api.CapabilityRequestId
import io.redi.robot.capability.api.CapabilityResultState
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.negotiation.CapabilityNegotiationReport
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.context.provider.ActiveBodyState
import io.redi.robot.context.provider.BodyProviderReference
import io.redi.robot.context.provider.EmbodimentEvidenceKind
import io.redi.robot.context.provider.EmbodimentHost
import io.redi.robot.context.provider.EmbodimentResult
import io.redi.robot.context.provider.MountingState
import io.redi.robot.firstlife.orientation.FirstLifeOrientationEnvelope
import io.redi.robot.firstlife.orientation.OrientationPhase
import io.redi.robot.firstlife.orientation.OrientationEffect
import io.redi.robot.firstlife.orientation.OrientationEffectOutcome
import io.redi.robot.firstlife.orientation.OrientationTerminalReason
import io.redi.robot.firstlife.orientation.OrientationTurnDirection
import io.redi.robot.trust.domain.ApprovalId
import io.redi.robot.trust.domain.ApprovalRecord
import io.redi.robot.trust.domain.ApprovalState
import io.redi.robot.trust.domain.AuthorityActor
import io.redi.robot.trust.domain.AuthorityActorId
import io.redi.robot.trust.domain.AuthorityProvenanceReference
import io.redi.robot.trust.domain.AuthoritySessionId
import io.redi.robot.trust.domain.ExecutionLocality
import io.redi.robot.trust.domain.ExecutionOrigin
import io.redi.robot.trust.domain.HugoHostRole
import io.redi.robot.trust.domain.PermissionGrant
import io.redi.robot.trust.domain.PermissionGrantLifetime
import io.redi.robot.trust.domain.PermissionRestrictions
import io.redi.robot.trust.domain.PermissionScope
import io.redi.robot.trust.domain.PermissionValidityWindow
import io.redi.robot.trust.domain.ProviderTrustRecord
import io.redi.robot.trust.domain.ProviderTrustState
import io.redi.robot.trust.domain.TrustDomainResult
import io.redi.robot.trust.domain.TrustRecordId
import io.redi.robot.trust.domain.TrustSchemaVersion
import io.redi.robot.trust.runtime.AuthorizationAuthorityState
import io.redi.robot.trust.runtime.AuthorizationDecision
import io.redi.robot.trust.runtime.AuthorizationDecisionId
import io.redi.robot.trust.runtime.AuthorizationDecisionStatus
import io.redi.robot.trust.runtime.AuthorizationEvidenceReference
import io.redi.robot.trust.runtime.AuthorizationGate
import io.redi.robot.trust.runtime.AuthorizationGateInput
import io.redi.robot.trust.runtime.AuthorizationGateModelResult
import io.redi.robot.trust.runtime.AuthorizationHostContext
import io.redi.robot.trust.runtime.AuthorizationPolicyReference
import io.redi.robot.trust.runtime.AuthorizationRuntimeContext
import io.redi.robot.trust.runtime.PhysicalPresenceEvidence
import java.util.UUID

/** Current generic HUGO evidence used to evaluate one explicit orientation hold. */
class FirstLifeOrientationAuthorityContext(
    val providerIdentity: ProviderIdentity,
    val negotiation: CapabilityNegotiationReport,
    val embodiment: ActiveBodyState,
    val host: EmbodimentHost,
) {
    fun isCurrentAt(now: MonotonicTimestampMs): Boolean {
        if (providerIdentity.instanceId != negotiation.providerInstanceId) return false
        val move = negotiation.negotiatedCapabilities.singleOrNull { it.capability.wireValue == MOVE_CAPABILITY }
            ?: return false
        if (now.value < move.negotiatedAt.value || now.value >= move.validUntil.value) return false
        val body = body(BodyProviderReference.create(providerIdentity)) ?: return false
        val snapshot = embodiment.body(body) ?: return false
        val mounted = snapshot.mounting == MountingState.MOUNTED && snapshot.evidence.any {
            it.kind == EmbodimentEvidenceKind.MOUNTING_VERIFIED && it.isFreshAt(now)
        }
        val selected = embodiment.activeSelection?.let { it.body == body && it.isActiveAt(now) } == true
        return mounted && selected
    }

    private fun <T> body(result: EmbodimentResult<T>): T? = (result as? EmbodimentResult.Success)?.value

    private companion object { const val MOVE_CAPABILITY = "mobility.move@1.0" }
}

enum class OrientationAuthorityBeginStatus {
    STARTED,
    CONTEXT_NOT_CURRENT,
    MOVE_NOT_NEGOTIATED,
}

class FirstLifeOrientationAuthoritySession internal constructor(
    internal val context: FirstLifeOrientationAuthorityContext,
    internal val actor: AuthorityActor,
    internal val sessionId: AuthoritySessionId,
    internal val trustRecord: ProviderTrustRecord,
    internal val grant: PermissionGrant,
    val startedAt: MonotonicTimestampMs,
    val expiresAt: MonotonicTimestampMs,
) {
    fun isCurrentAt(now: MonotonicTimestampMs): Boolean =
        now.value >= startedAt.value && now.value < expiresAt.value && context.isCurrentAt(now)

    override fun toString(): String = "FirstLifeOrientationAuthoritySession(redacted, current=false-by-default)"
}

data class OrientationAuthorityBeginResult(
    val status: OrientationAuthorityBeginStatus,
    val session: FirstLifeOrientationAuthoritySession? = null,
) {
    val started: Boolean get() = status == OrientationAuthorityBeginStatus.STARTED && session != null
}

/**
 * Creates only an explicit, in-memory, two-second H014 session grant for one user hold. Provider
 * readiness never calls this and never becomes authority. Every move still consumes a request-bound
 * approval through H030.
 */
class FirstLifeOrientationAuthorization(
    private val uuid: () -> UUID = UUID::randomUUID,
    private val consumption: SessionApprovalConsumption = SessionApprovalConsumption(),
) {
    private val gate = AuthorizationGate(consumption)

    fun begin(
        context: FirstLifeOrientationAuthorityContext,
        now: MonotonicTimestampMs,
    ): OrientationAuthorityBeginResult {
        val move = context.negotiation.negotiatedCapabilities.singleOrNull {
            it.capability.wireValue == MOVE_CAPABILITY
        } ?: return OrientationAuthorityBeginResult(OrientationAuthorityBeginStatus.MOVE_NOT_NEGOTIATED)
        if (!context.isCurrentAt(now)) {
            return OrientationAuthorityBeginResult(OrientationAuthorityBeginStatus.CONTEXT_NOT_CURRENT)
        }
        val expiresAt = time(now.value + FirstLifeOrientationEnvelope.MAX_ATTEMPT_WALL_CLOCK_MS)
        val actor = AuthorityActor(trust(AuthorityActorId.parse(uuid().toString())), CapabilityActorKind.HUGO_RUNTIME)
        val sessionId = trust(AuthoritySessionId.parse(uuid().toString()))
        val reference = envelope(CapabilityAuthorizationReference.parse(uuid().toString()))
        val validity = trust(PermissionValidityWindow.create(now, expiresAt))
        val provenance = trust(AuthorityProvenanceReference.parse(PROVENANCE))
        val authorityAudit = CapabilityAuditId.fromUuid(uuid())
        val trustRecord = trust(ProviderTrustRecord.create(
            schema(), trust(TrustRecordId.parse(uuid().toString())), context.providerIdentity.instanceId,
            ProviderTrustState.TRUSTED, validity, provenance, authorityAudit,
        ))
        val scope = trust(PermissionScope.create(
            actor,
            context.providerIdentity.instanceId,
            move.capability,
            move.descriptor.permissionIntent,
            emptyMap(),
        ))
        val restrictions = trust(PermissionRestrictions.create(
            allowedHostRoles = setOf(HugoHostRole.MOBILE_HOST),
            locality = ExecutionLocality.LOCAL_ONLY,
            requiresPhysicalPresence = true,
            requiresMountedHost = true,
            requiresNearbyProvider = false,
            requiresActiveBody = true,
        ))
        val grant = trust(PermissionGrant.create(
            schema(), reference, scope, PermissionGrantLifetime.SESSION,
            sessionId, null, move.descriptor.approvalRequirement, validity,
            restrictions, provenance, authorityAudit,
        ))
        return OrientationAuthorityBeginResult(
            OrientationAuthorityBeginStatus.STARTED,
            FirstLifeOrientationAuthoritySession(
                context, actor, sessionId, trustRecord, grant, now, expiresAt,
            ),
        )
    }

    fun authorize(
        session: FirstLifeOrientationAuthoritySession,
        request: CapabilityRequest,
        current: FirstLifeOrientationAuthorityContext,
        now: MonotonicTimestampMs,
    ): AuthorizationDecision? {
        if (!session.isCurrentAt(now) ||
            current.providerIdentity != session.context.providerIdentity ||
            current.negotiation.negotiationId != session.context.negotiation.negotiationId ||
            request.authorizationReference != session.grant.authorizationReference
        ) return null
        val approval = trust(ApprovalRecord.create(
            schema(), trust(ApprovalId.parse(uuid().toString())), session.grant.authorizationReference,
            session.actor, current.providerIdentity.instanceId, request.capability,
            session.grant.approvalRequirement, request.requestId, null, ApprovalState.APPROVED,
            trust(PermissionValidityWindow.create(session.startedAt, session.expiresAt)),
            trust(AuthorityProvenanceReference.parse(PROVENANCE)), request.auditId,
        ))
        val presenceExpiry = time(minOf(session.expiresAt.value, now.value + PRESENCE_WINDOW_MS))
        if (presenceExpiry.value <= now.value) return null
        val presence = model(PhysicalPresenceEvidence.create(
            present = true,
            observedAt = now,
            expiresAt = presenceExpiry,
            reference = model(AuthorizationEvidenceReference.parse("firstlife.orientation/user-hold")),
        ))
        val record = current.negotiation.negotiatedCapabilities.singleOrNull {
            it.capability == request.capability
        } ?: return null
        val runtime = model(AuthorizationRuntimeContext.create(
            current.providerIdentity,
            current.negotiation.providerGeneration,
            record.connectionSessionIds,
            AuthorizationHostContext(current.host, ExecutionOrigin.LOCAL, session.sessionId),
            presence,
            current.embodiment,
        ))
        val authority = model(AuthorizationAuthorityState.create(
            session.trustRecord,
            session.grant,
            approval,
            emptyList(),
        ))
        val input = model(AuthorizationGateInput.create(
            model(AuthorizationDecisionId.fromUuid(uuid())),
            request,
            session.actor,
            current.negotiation,
            runtime,
            authority,
            emptyMap(),
            listOf(model(AuthorizationPolicyReference.parse(POLICY))),
            now,
        ))
        return gate.authorize(input)
    }

    fun clear() = consumption.clearForRuntimeReset()

    private fun schema() = trust(TrustSchemaVersion.parse("1.0"))
    private fun time(value: Long) = envelope(MonotonicTimestampMs.create(value))
    private fun <T> model(result: AuthorizationGateModelResult<T>) =
        (result as AuthorizationGateModelResult.Success).value
    private fun <T> trust(result: TrustDomainResult<T>) = (result as TrustDomainResult.Success).value
    private fun <T> envelope(result: CapabilityEnvelopeResult<T>) = (result as CapabilityEnvelopeResult.Success).value

    private companion object {
        const val MOVE_CAPABILITY = "mobility.move@1.0"
        const val PROVENANCE = "local.fl013.user-hold"
        const val POLICY = "policy.fl013.bounded-orientation"
        const val PRESENCE_WINDOW_MS = 250L
    }
}

data class FirstLifeOrientationDispatchReceipt(
    val dispatch: AuthorizedDispatchResult,
    val stopObserved: Boolean = false,
)

fun interface FirstLifeOrientationDispatchPort {
    fun dispatch(request: CapabilityRequest, authorization: AuthorizationDecision?): FirstLifeOrientationDispatchReceipt
}

enum class FirstLifeOrientationRoute {
    H007_H029_H030_H018_T032,
}

class FirstLifeOrientationTrace(
    val effect: OrientationEffect,
    val request: CapabilityRequest,
    val decision: AuthorizationDecision?,
    val receipt: FirstLifeOrientationDispatchReceipt,
    val route: FirstLifeOrientationRoute = FirstLifeOrientationRoute.H007_H029_H030_H018_T032,
) {
    override fun toString(): String =
        "FirstLifeOrientationTrace(effect=${effect::class.simpleName}, route=$route, outcome=${receipt.dispatch::class.simpleName}, redacted=true)"
}

data class FirstLifeOrientationEffectResult(
    val outcome: OrientationEffectOutcome,
    val trace: FirstLifeOrientationTrace?,
)

/** Bounded, redacted run-local evidence. It retains no request/provider/session identifier. */
data class FirstLifeOrientationServiceEvidence(
    val holdStarted: Boolean = false,
    val moveInvocationCount: Int = 0,
    val stopConfirmedCount: Int = 0,
    val lastOutcome: OrientationEffectOutcome? = null,
    val lastRoute: FirstLifeOrientationRoute? = null,
    val h030AuthorizationCorrelated: Boolean = false,
    val h018Invoked: Boolean = false,
    val t032StopObserved: Boolean = false,
    val controllerPhase: OrientationPhase = OrientationPhase.IDLE,
    val pulseCount: Int = 0,
    val cumulativeEnergizedMs: Long = 0L,
    val terminalReason: OrientationTerminalReason? = null,
    val grantsSafetyAuthority: Boolean = false,
) {
    override fun toString(): String =
        "FirstLifeOrientationServiceEvidence(hold=$holdStarted, move=$moveInvocationCount, " +
            "stop=$stopConfirmedCount, outcome=$lastOutcome, route=$lastRoute, redacted=true)"
}

/** Exact H007 request binding. It contains no scheduler, motor value, encoder, transport, or safety rule. */
class FirstLifeOrientationEffectBinding(
    private val dispatch: FirstLifeOrientationDispatchPort,
    private val authorization: FirstLifeOrientationAuthorization = FirstLifeOrientationAuthorization(),
    private val uuid: () -> UUID = UUID::randomUUID,
) {
    private var session: FirstLifeOrientationAuthoritySession? = null
    private var executionSequence = 0L

    @Synchronized
    fun beginHold(
        context: FirstLifeOrientationAuthorityContext,
        now: MonotonicTimestampMs,
    ): OrientationAuthorityBeginResult {
        session = null
        executionSequence = 0L
        authorization.clear()
        return authorization.begin(context, now).also {
            session = it.session
        }
    }

    @Synchronized
    fun authorityCurrent(now: MonotonicTimestampMs): Boolean = session?.isCurrentAt(now) == true

    @Synchronized
    fun execute(
        effect: OrientationEffect,
        context: FirstLifeOrientationAuthorityContext,
        now: MonotonicTimestampMs,
    ): FirstLifeOrientationEffectResult {
        val isMove = effect is OrientationEffect.ProposeTurn
        val active = session
        if (isMove && (active == null || !active.isCurrentAt(now))) {
            return FirstLifeOrientationEffectResult(OrientationEffectOutcome.AUTHORIZATION_DENIED, null)
        }
        val capability = reference(if (isMove) MOVE_CAPABILITY else STOP_CAPABILITY)
        val input = (effect as? OrientationEffect.ProposeTurn)?.let(::moveInput)
        val authorizationReference = if (isMove) active?.grant?.authorizationReference else null
        val deadline = time(minOf(
            now.value + REQUEST_DEADLINE_MS,
            active?.expiresAt?.value ?: now.value + REQUEST_DEADLINE_MS,
        ))
        val request = envelope(CapabilityRequest.create(
            CapabilityRequestId.fromUuid(uuid()), capability,
            CapabilityProviderTarget.Exact(context.providerIdentity.instanceId),
            CapabilityActorKind.HUGO_RUNTIME,
            envelope(CapabilityExecutionContext.create(
                context.negotiation.providerGeneration.value,
                ++executionSequence,
            )),
            now,
            deadline,
            authorizationReference,
            CapabilityAuditId.fromUuid(uuid()),
            input,
        ))
        val decision = if (isMove) {
            authorization.authorize(checkNotNull(active), request, context, now)
        } else null
        if (isMove && decision?.status != AuthorizationDecisionStatus.AUTHORIZED) {
            return FirstLifeOrientationEffectResult(
                OrientationEffectOutcome.AUTHORIZATION_DENIED,
                null,
            )
        }
        val receipt = dispatch.dispatch(request, decision)
        val trace = FirstLifeOrientationTrace(effect, request, decision, receipt)
        return FirstLifeOrientationEffectResult(map(effect, receipt), trace)
    }

    @Synchronized
    fun endHold() {
        session = null
        executionSequence = 0L
        authorization.clear()
    }

    private fun map(effect: OrientationEffect, receipt: FirstLifeOrientationDispatchReceipt): OrientationEffectOutcome =
        when (val result = receipt.dispatch) {
            is AuthorizedDispatchResult.Rejected -> when (result.reason) {
                AuthorizedDispatchRejection.AUTHORIZATION_MISSING,
                AuthorizedDispatchRejection.AUTHORIZATION_DENIED,
                AuthorizedDispatchRejection.AUTHORIZATION_CORRELATION_MISMATCH,
                -> OrientationEffectOutcome.AUTHORIZATION_DENIED
                AuthorizedDispatchRejection.GENERATION_STALE,
                AuthorizedDispatchRejection.NEGOTIATION_STALE,
                AuthorizedDispatchRejection.CONNECTION_SESSION_STALE,
                AuthorizedDispatchRejection.PROVIDER_CORRELATION_MISMATCH,
                AuthorizedDispatchRejection.REQUEST_EXPIRED,
                -> OrientationEffectOutcome.STALE_CORRELATION
                else -> OrientationEffectOutcome.FAILED
            }
            is AuthorizedDispatchResult.Invoked -> if (effect is OrientationEffect.RequestStop) {
                if (result.result.state in acceptedStates && receipt.stopObserved) {
                    OrientationEffectOutcome.STOP_CONFIRMED
                } else {
                    OrientationEffectOutcome.STOP_UNCONFIRMED
                }
            } else when {
                result.result.state in acceptedStates -> OrientationEffectOutcome.MOVE_ACCEPTED
                result.result.failure?.code == io.redi.robot.capability.api.CapabilityFailureCode.SAFETY_REJECTED ->
                    OrientationEffectOutcome.SAFETY_REJECTED
                result.result.failure?.code == io.redi.robot.capability.api.CapabilityFailureCode.PROVIDER_UNAVAILABLE ->
                    OrientationEffectOutcome.UNAVAILABLE
                else -> OrientationEffectOutcome.FAILED
            }
        }

    private fun moveInput(effect: OrientationEffect.ProposeTurn): CapabilityData {
        require(effect.signedMagnitude == FirstLifeOrientationEnvelope.SIGNED_MAGNITUDE)
        val direction = when (effect.direction) {
            OrientationTurnDirection.LEFT -> "left"
            OrientationTurnDirection.RIGHT -> "right"
        }
        return envelope(CapabilityData.create(
            descriptor(CapabilityContractReference.parse(MOVE_CONTRACT)),
            mapOf(
                envelope(CapabilityArgumentName.parse("direction")) to
                    envelope(CapabilityArgumentValue.Text.create(direction)),
            ),
        ))
    }

    private fun reference(raw: String): CapabilityReference = when (val result = CapabilityReference.parse(raw)) {
        is io.redi.robot.capability.api.CapabilityIdentityResult.Success -> result.value
        is io.redi.robot.capability.api.CapabilityIdentityResult.Failure -> error(result.error)
    }
    private fun time(value: Long) = envelope(MonotonicTimestampMs.create(value))
    private fun <T> descriptor(result: io.redi.robot.capability.api.CapabilityDescriptorResult<T>) =
        (result as io.redi.robot.capability.api.CapabilityDescriptorResult.Success).value
    private fun <T> envelope(result: CapabilityEnvelopeResult<T>) = (result as CapabilityEnvelopeResult.Success).value

    private companion object {
        const val MOVE_CAPABILITY = "mobility.move@1.0"
        const val STOP_CAPABILITY = "mobility.stop@1.0"
        const val MOVE_CONTRACT = "hugo.contract.mobility_move_input@1.0"
        const val REQUEST_DEADLINE_MS = 500L
        val acceptedStates = setOf(
            CapabilityResultState.ACCEPTED,
            CapabilityResultState.STARTED,
            CapabilityResultState.COMPLETED,
        )
    }
}
