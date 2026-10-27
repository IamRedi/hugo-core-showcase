package io.redi.robot.trust.runtime

import io.redi.robot.capability.api.CapabilityActorKind
import io.redi.robot.capability.api.CapabilityAuditId
import io.redi.robot.capability.api.CapabilityAuthorizationReference
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.CapabilityRequest
import io.redi.robot.capability.api.CapabilityRequestId
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.negotiation.CapabilityNegotiationReport
import io.redi.robot.capability.negotiation.CapabilityNegotiationId
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.connection.api.ConnectionSessionId
import io.redi.robot.context.provider.ActiveBodyState
import io.redi.robot.context.provider.EmbodimentHost
import io.redi.robot.trust.domain.ApprovalRecord
import io.redi.robot.trust.domain.AuthorityActor
import io.redi.robot.trust.domain.AuthoritySessionId
import io.redi.robot.trust.domain.ExecutionOrigin
import io.redi.robot.trust.domain.PermissionGrant
import io.redi.robot.trust.domain.PermissionScopeKey
import io.redi.robot.trust.domain.PermissionScopeValue
import io.redi.robot.trust.domain.ProviderTrustRecord
import io.redi.robot.trust.domain.RevocationRecord
import java.util.Collections
import java.util.UUID

@JvmInline
value class AuthorizationDecisionId private constructor(val value: String) {
    companion object {
        fun fromUuid(uuid: UUID): AuthorizationGateModelResult<AuthorizationDecisionId> =
            AuthorizationGateModelResult.Success(AuthorizationDecisionId(uuid.toString()))

        fun parse(raw: String): AuthorizationGateModelResult<AuthorizationDecisionId> = runCatching {
            UUID.fromString(raw).toString().takeIf {
                it == raw && raw[14] == '4' && raw[19] in "89ab"
            }
        }.getOrNull()?.let { AuthorizationGateModelResult.Success(AuthorizationDecisionId(it)) }
            ?: AuthorizationGateModelResult.Failure(AuthorizationGateModelError.INVALID_DECISION_ID)
    }
}

/** Bounded project-owned reference. It cannot carry a credential, endpoint, or raw protocol value. */
@JvmInline
value class AuthorizationEvidenceReference private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z0-9][a-z0-9._/-]{2,159}$")

        fun parse(raw: String): AuthorizationGateModelResult<AuthorizationEvidenceReference> = if (
            pattern.matches(raw) && !raw.contains("//") && !raw.contains("..")
        ) {
            AuthorizationGateModelResult.Success(AuthorizationEvidenceReference(raw))
        } else {
            AuthorizationGateModelResult.Failure(AuthorizationGateModelError.INVALID_EVIDENCE_REFERENCE)
        }

        internal fun projectOwned(raw: String): AuthorizationEvidenceReference =
            AuthorizationEvidenceReference(raw)
    }
}

@JvmInline
value class AuthorizationPolicyReference private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._/-][a-z0-9]+){1,7}$")

        fun parse(raw: String): AuthorizationGateModelResult<AuthorizationPolicyReference> = if (
            raw.length in 3..120 && pattern.matches(raw)
        ) {
            AuthorizationGateModelResult.Success(AuthorizationPolicyReference(raw))
        } else {
            AuthorizationGateModelResult.Failure(AuthorizationGateModelError.INVALID_POLICY_REFERENCE)
        }
    }
}

class PhysicalPresenceEvidence private constructor(
    val present: Boolean,
    val observedAt: MonotonicTimestampMs,
    val expiresAt: MonotonicTimestampMs,
    val reference: AuthorizationEvidenceReference,
) {
    fun isFreshAt(now: MonotonicTimestampMs): Boolean =
        now.value >= observedAt.value && now.value < expiresAt.value

    companion object {
        fun create(
            present: Boolean,
            observedAt: MonotonicTimestampMs,
            expiresAt: MonotonicTimestampMs,
            reference: AuthorizationEvidenceReference,
        ): AuthorizationGateModelResult<PhysicalPresenceEvidence> = if (
            expiresAt.value <= observedAt.value
        ) {
            AuthorizationGateModelResult.Failure(AuthorizationGateModelError.INVALID_EVIDENCE_FRESHNESS)
        } else {
            AuthorizationGateModelResult.Success(
                PhysicalPresenceEvidence(present, observedAt, expiresAt, reference),
            )
        }
    }
}

data class AuthorizationHostContext(
    val host: EmbodimentHost,
    val executionOrigin: ExecutionOrigin,
    val authoritySessionId: AuthoritySessionId?,
)

class AuthorizationRuntimeContext private constructor(
    val providerIdentity: ProviderIdentity,
    val providerGeneration: ProviderGeneration,
    connectionSessionIds: List<ConnectionSessionId>,
    val hostContext: AuthorizationHostContext,
    val physicalPresence: PhysicalPresenceEvidence?,
    val embodiment: ActiveBodyState?,
) {
    val connectionSessionIds: List<ConnectionSessionId> = Collections.unmodifiableList(
        connectionSessionIds.distinct().sortedBy { it.value },
    )

    companion object {
        fun create(
            providerIdentity: ProviderIdentity,
            providerGeneration: ProviderGeneration,
            connectionSessionIds: List<ConnectionSessionId>,
            hostContext: AuthorizationHostContext,
            physicalPresence: PhysicalPresenceEvidence?,
            embodiment: ActiveBodyState?,
        ): AuthorizationGateModelResult<AuthorizationRuntimeContext> = when {
            connectionSessionIds.size > 16 ->
                AuthorizationGateModelResult.Failure(AuthorizationGateModelError.TOO_MANY_CONNECTION_SESSIONS)
            embodiment?.bodies.orEmpty().sumOf { it.evidence.size } > 64 ->
                AuthorizationGateModelResult.Failure(AuthorizationGateModelError.TOO_MANY_EMBODIMENT_EVIDENCE)
            else -> {
            AuthorizationGateModelResult.Success(
                AuthorizationRuntimeContext(
                    providerIdentity,
                    providerGeneration,
                    connectionSessionIds,
                    hostContext,
                    physicalPresence,
                    embodiment,
                ),
            )
            }
        }
    }
}

class AuthorizationAuthorityState private constructor(
    val trustRecord: ProviderTrustRecord?,
    val grant: PermissionGrant?,
    val approval: ApprovalRecord?,
    revocations: List<RevocationRecord>,
) {
    val revocations: List<RevocationRecord> = Collections.unmodifiableList(
        revocations.sortedBy { it.id.value },
    )

    companion object {
        fun create(
            trustRecord: ProviderTrustRecord?,
            grant: PermissionGrant?,
            approval: ApprovalRecord?,
            revocations: List<RevocationRecord>,
        ): AuthorizationGateModelResult<AuthorizationAuthorityState> = when {
            revocations.size > 64 ->
                AuthorizationGateModelResult.Failure(AuthorizationGateModelError.TOO_MANY_REVOCATIONS)
            revocations.map { it.id }.distinct().size != revocations.size ->
                AuthorizationGateModelResult.Failure(AuthorizationGateModelError.CONFLICTING_REVOCATIONS)
            else -> AuthorizationGateModelResult.Success(
                AuthorizationAuthorityState(
                    trustRecord,
                    grant,
                    approval,
                    revocations,
                ),
            )
        }
    }
}

class AuthorizationGateInput private constructor(
    val decisionId: AuthorizationDecisionId,
    val request: CapabilityRequest,
    val actor: AuthorityActor,
    val negotiation: CapabilityNegotiationReport,
    val runtime: AuthorizationRuntimeContext,
    val authority: AuthorizationAuthorityState,
    requestedScope: Map<PermissionScopeKey, Set<PermissionScopeValue>>,
    policyReferences: List<AuthorizationPolicyReference>,
    val now: MonotonicTimestampMs,
) {
    val requestedScope: Map<PermissionScopeKey, Set<PermissionScopeValue>> = Collections.unmodifiableMap(
        requestedScope.entries.sortedBy { it.key.value }.associate { (key, values) ->
            key to Collections.unmodifiableSet(values.sortedBy { it.value }.toSet())
        },
    )
    val policyReferences: List<AuthorizationPolicyReference> = Collections.unmodifiableList(
        policyReferences.distinct().sortedBy { it.value },
    )

    companion object {
        @Suppress("LongParameterList")
        fun create(
            decisionId: AuthorizationDecisionId,
            request: CapabilityRequest,
            actor: AuthorityActor,
            negotiation: CapabilityNegotiationReport,
            runtime: AuthorizationRuntimeContext,
            authority: AuthorizationAuthorityState,
            requestedScope: Map<PermissionScopeKey, Set<PermissionScopeValue>>,
            policyReferences: List<AuthorizationPolicyReference>,
            now: MonotonicTimestampMs,
        ): AuthorizationGateModelResult<AuthorizationGateInput> = when {
            policyReferences.isEmpty() || policyReferences.size > 32 ->
                AuthorizationGateModelResult.Failure(AuthorizationGateModelError.INVALID_POLICY_REFERENCES)
            requestedScope.size > 32 || requestedScope.values.any { it.isEmpty() || it.size > 32 } ->
                AuthorizationGateModelResult.Failure(AuthorizationGateModelError.INVALID_REQUESTED_SCOPE)
            else -> AuthorizationGateModelResult.Success(
                AuthorizationGateInput(
                    decisionId,
                    request,
                    actor,
                    negotiation,
                    runtime,
                    authority,
                    requestedScope,
                    policyReferences,
                    now,
                ),
            )
        }
    }
}

enum class AuthorizationDecisionStatus {
    AUTHORIZED,
    TRUST_REQUIRED,
    PERMISSION_REQUIRED,
    APPROVAL_REQUIRED,
    EXPIRED,
    REVOKED,
    SCOPE_MISMATCH,
    ACTOR_MISMATCH,
    PROVIDER_MISMATCH,
    CAPABILITY_MISMATCH,
    SESSION_MISMATCH,
    HOST_RESTRICTED,
    PRESENCE_REQUIRED,
    MOUNTED_HOST_REQUIRED,
    PROXIMITY_REQUIRED,
    ACTIVE_BODY_REQUIRED,
    STALE_NEGOTIATION,
    PROHIBITED,
    REPLAY_REJECTED,
    INVALID,
    FAILED,
}

enum class AuthorizationDecisionReason {
    AUTHORITY_VALID,
    REQUEST_NOT_CURRENT,
    REQUEST_REQUIRES_EXACT_PROVIDER,
    REQUEST_PROVIDER_MISMATCH,
    REQUEST_ACTOR_MISMATCH,
    REQUEST_CAPABILITY_MISMATCH,
    REQUEST_CONTRACT_MISMATCH,
    NEGOTIATION_NOT_SUCCESSFUL,
    NEGOTIATED_CAPABILITY_MISSING,
    NEGOTIATED_CAPABILITY_CONFLICT,
    NEGOTIATION_PROVIDER_MISMATCH,
    NEGOTIATION_GENERATION_STALE,
    NEGOTIATION_SESSION_STALE,
    NEGOTIATION_EXPIRED,
    TRUST_MISSING,
    TRUST_WRONG_PROVIDER,
    TRUST_INSUFFICIENT,
    TRUST_EXPIRED,
    TRUST_REVOKED,
    GRANT_MISSING,
    AUTHORIZATION_REFERENCE_MISSING,
    AUTHORIZATION_REFERENCE_MISMATCH,
    GRANT_EXPIRED,
    GRANT_REVOKED,
    WRONG_PROVIDER,
    WRONG_CAPABILITY,
    WRONG_ACTOR,
    WRONG_PERMISSION_INTENT,
    SCOPE_NOT_GRANTED,
    WRONG_AUTHORITY_SESSION,
    WRONG_ONE_TIME_REQUEST,
    WRONG_HOST,
    REMOTE_EXECUTION_NOT_ALLOWED,
    PHYSICAL_PRESENCE_REQUIRED,
    MOUNTED_HOST_REQUIRED,
    NEARBY_PROVIDER_REQUIRED,
    ACTIVE_BODY_REQUIRED,
    APPROVAL_REQUIRED,
    APPROVAL_MISMATCH,
    APPROVAL_EXPIRED,
    APPROVAL_REVOKED,
    APPROVAL_DENIED,
    APPROVAL_REPLAYED,
    PROHIBITED,
    APPROVAL_CONSUMED,
    APPROVAL_CONSUMPTION_CONFLICT,
    APPROVAL_CONSUMPTION_FAILED,
}

enum class AuthorizationAuditDimension {
    REQUEST,
    NEGOTIATION,
    PROVIDER,
    CONNECTION_SESSION,
    TRUST,
    PERMISSION,
    APPROVAL,
    HOST,
    PRESENCE,
    EMBODIMENT,
    POLICY,
    APPROVAL_CONSUMPTION,
}

enum class AuthorizationAuditCheckStatus {
    MATCHED,
    INFORMATIONAL,
    MISSING,
    STALE,
    DENIED,
    CONFLICTING,
    CONSUMED,
    REPLAYED,
    FAILED,
}

enum class AuthorizationEvidenceSource {
    REQUEST,
    NEGOTIATION,
    PROVIDER_IDENTITY,
    CONNECTION_SESSION,
    TRUST_RECORD,
    PERMISSION_GRANT,
    APPROVAL_RECORD,
    REVOCATION_RECORD,
    HOST_CONTEXT,
    PHYSICAL_PRESENCE,
    EMBODIMENT,
    POLICY,
    APPROVAL_CONSUMPTION,
}

data class AuthorizationAuditEvidence(
    val source: AuthorizationEvidenceSource,
    val reference: AuthorizationEvidenceReference,
)

data class AuthorizationAuditCheck(
    val dimension: AuthorizationAuditDimension,
    val checkId: String,
    val status: AuthorizationAuditCheckStatus,
    val reason: AuthorizationDecisionReason,
    val requiredValues: List<String>,
    val observedValues: List<String>,
    val evidence: List<AuthorizationAuditEvidence>,
)

class AuthorizationAuditRecord internal constructor(
    val decisionId: AuthorizationDecisionId,
    val requestId: CapabilityRequestId,
    val requestAuditId: CapabilityAuditId,
    val actorKind: CapabilityActorKind,
    val providerInstanceId: ProviderInstanceId,
    val capability: CapabilityReference,
    val providerGeneration: ProviderGeneration,
    val negotiationId: CapabilityNegotiationId,
    val decidedAt: MonotonicTimestampMs,
    val authorizationReferenceConsidered: Boolean,
    policyReferences: List<AuthorizationPolicyReference>,
    evidence: List<AuthorizationAuditEvidence>,
    checks: List<AuthorizationAuditCheck>,
    val approvalConsumption: ApprovalConsumptionReceipt?,
) {
    val policyReferences: List<AuthorizationPolicyReference> = Collections.unmodifiableList(
        policyReferences.distinct().sortedBy { it.value },
    )
    val evidence: List<AuthorizationAuditEvidence> = Collections.unmodifiableList(
        evidence.distinct().sortedWith(compareBy({ it.source.ordinal }, { it.reference.value })),
    )
    val checks: List<AuthorizationAuditCheck> = Collections.unmodifiableList(
        checks.map {
            it.copy(
                requiredValues = it.requiredValues.distinct().sorted(),
                observedValues = it.observedValues.distinct().sorted(),
                evidence = it.evidence.distinct().sortedWith(
                    compareBy({ item -> item.source.ordinal }, { item -> item.reference.value }),
                ),
            )
        }.sortedWith(compareBy({ it.dimension.ordinal }, { it.checkId })),
    )
}

class AuthorizationDecision internal constructor(
    val status: AuthorizationDecisionStatus,
    val reason: AuthorizationDecisionReason,
    val authorizationReference: CapabilityAuthorizationReference?,
    val audit: AuthorizationAuditRecord,
) {
    val isAuthorized: Boolean get() = status == AuthorizationDecisionStatus.AUTHORIZED
    val grantsSafetyAuthority: Boolean get() = false
    val provesPhysicalFeasibility: Boolean get() = false
    val dispatchesCapability: Boolean get() = false
    val recordsExecutionSuccess: Boolean get() = false

    override fun toString(): String = "AuthorizationDecision(" +
        "status=$status, reason=$reason, decisionId=${audit.decisionId.value}, " +
        "capability=${audit.capability.wireValue}, checks=${audit.checks.size}, " +
        "approvalConsumed=${audit.approvalConsumption != null})"
}

sealed interface AuthorizationGateModelResult<out T> {
    data class Success<T>(val value: T) : AuthorizationGateModelResult<T>
    data class Failure(val error: AuthorizationGateModelError) : AuthorizationGateModelResult<Nothing>
}

enum class AuthorizationGateModelError {
    INVALID_DECISION_ID,
    INVALID_EVIDENCE_REFERENCE,
    INVALID_POLICY_REFERENCE,
    INVALID_EVIDENCE_FRESHNESS,
    TOO_MANY_CONNECTION_SESSIONS,
    TOO_MANY_EMBODIMENT_EVIDENCE,
    TOO_MANY_REVOCATIONS,
    CONFLICTING_REVOCATIONS,
    INVALID_POLICY_REFERENCES,
    INVALID_REQUESTED_SCOPE,
}
