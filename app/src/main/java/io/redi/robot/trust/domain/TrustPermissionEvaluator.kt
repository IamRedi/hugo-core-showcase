package io.redi.robot.trust.domain

import io.redi.robot.capability.api.CapabilityApprovalRequirement
import io.redi.robot.capability.api.CapabilityAuthorizationReference
import io.redi.robot.capability.api.CapabilityDescriptor
import io.redi.robot.capability.api.CapabilityProviderTarget
import io.redi.robot.capability.api.CapabilityRequest
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderInstanceId

data class TrustPermissionEvaluationInput(
    val request: CapabilityRequest,
    val actor: AuthorityActor,
    val providerInstanceId: ProviderInstanceId,
    val descriptor: CapabilityDescriptor,
    val trustRecord: ProviderTrustRecord?,
    val grant: PermissionGrant?,
    val approval: ApprovalRecord?,
    val policyInputs: AuthorityPolicyInputs,
    val requestedScope: Map<PermissionScopeKey, Set<PermissionScopeValue>>,
    val nonAuthorityEvidence: NonAuthorityEvidence,
    val revocations: List<RevocationRecord>,
    val now: MonotonicTimestampMs,
)

sealed interface TrustPermissionDecision {
    /** Permission gate passed; physical/provider safety and execution gates remain mandatory. */
    data class Permitted(
        val authorizationReference: CapabilityAuthorizationReference,
        val approvalToConsume: ApprovalId?,
        val deterministicSafetyStillRequired: Boolean,
    ) : TrustPermissionDecision

    data class ApprovalRequired(val requirement: CapabilityApprovalRequirement) : TrustPermissionDecision

    data class Denied(val reason: TrustPermissionDenial) : TrustPermissionDecision
}

enum class TrustPermissionDenial {
    PROHIBITED,
    REQUEST_REQUIRES_EXACT_PROVIDER,
    REQUEST_PROVIDER_MISMATCH,
    REQUEST_ACTOR_MISMATCH,
    REQUEST_CAPABILITY_MISMATCH,
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
    WRONG_SESSION,
    WRONG_ONE_TIME_REQUEST,
    WRONG_HOST,
    REMOTE_EXECUTION_NOT_ALLOWED,
    PHYSICAL_PRESENCE_REQUIRED,
    MOUNTED_HOST_REQUIRED,
    NEARBY_PROVIDER_REQUIRED,
    ACTIVE_BODY_REQUIRED,
    APPROVAL_MISMATCH,
    APPROVAL_EXPIRED,
    APPROVAL_REVOKED,
    APPROVAL_DENIED,
    APPROVAL_REPLAYED,
}

/**
 * Deterministic H014 policy evaluation only. It does not dispatch, mint execution authority,
 * consume approvals, revoke records, or make safety/feasibility decisions.
 */
object TrustPermissionEvaluator {
    fun evaluate(input: TrustPermissionEvaluationInput): TrustPermissionDecision {
        if (input.descriptor.approvalRequirement == CapabilityApprovalRequirement.PROHIBITED ||
            input.grant?.approvalRequirement == CapabilityApprovalRequirement.PROHIBITED
        ) return denied(TrustPermissionDenial.PROHIBITED)

        when (val target = input.request.providerTarget) {
            CapabilityProviderTarget.AnyCompatible -> return denied(
                TrustPermissionDenial.REQUEST_REQUIRES_EXACT_PROVIDER,
            )
            is CapabilityProviderTarget.Exact -> if (target.providerInstanceId != input.providerInstanceId) {
                return denied(TrustPermissionDenial.REQUEST_PROVIDER_MISMATCH)
            }
        }
        if (input.request.actor != input.actor.kind) return denied(TrustPermissionDenial.REQUEST_ACTOR_MISMATCH)
        if (input.request.capability != input.descriptor.reference) {
            return denied(TrustPermissionDenial.REQUEST_CAPABILITY_MISMATCH)
        }

        val trust = input.trustRecord ?: return denied(TrustPermissionDenial.TRUST_MISSING)
        if (trust.providerInstanceId != input.providerInstanceId) {
            return denied(TrustPermissionDenial.TRUST_WRONG_PROVIDER)
        }
        if (trust.state != ProviderTrustState.TRUSTED) return denied(TrustPermissionDenial.TRUST_INSUFFICIENT)
        if (isRevoked(input.revocations, RevocationTarget.Trust(trust.id), input.now)) {
            return denied(TrustPermissionDenial.TRUST_REVOKED)
        }
        if (!trust.validity.contains(input.now)) return denied(TrustPermissionDenial.TRUST_EXPIRED)

        val grant = input.grant ?: return denied(TrustPermissionDenial.GRANT_MISSING)
        val authorizationReference = input.request.authorizationReference
            ?: return denied(TrustPermissionDenial.AUTHORIZATION_REFERENCE_MISSING)
        if (authorizationReference != grant.authorizationReference) {
            return denied(TrustPermissionDenial.AUTHORIZATION_REFERENCE_MISMATCH)
        }
        if (isRevoked(input.revocations, RevocationTarget.Grant(grant.authorizationReference), input.now)) {
            return denied(TrustPermissionDenial.GRANT_REVOKED)
        }
        if (!grant.validity.contains(input.now)) return denied(TrustPermissionDenial.GRANT_EXPIRED)
        if (grant.scope.providerInstanceId != input.providerInstanceId) return denied(TrustPermissionDenial.WRONG_PROVIDER)
        if (grant.scope.capability != input.request.capability) return denied(TrustPermissionDenial.WRONG_CAPABILITY)
        if (grant.scope.actor != input.actor) return denied(TrustPermissionDenial.WRONG_ACTOR)
        if (grant.scope.intent != input.descriptor.permissionIntent) {
            return denied(TrustPermissionDenial.WRONG_PERMISSION_INTENT)
        }
        if (!scopeAllowed(grant.scope.constraints, input.requestedScope)) {
            return denied(TrustPermissionDenial.SCOPE_NOT_GRANTED)
        }

        when (grant.lifetime) {
            PermissionGrantLifetime.SESSION -> if (grant.sessionId != input.policyInputs.sessionId) {
                return denied(TrustPermissionDenial.WRONG_SESSION)
            }
            PermissionGrantLifetime.ONE_TIME -> if (grant.oneTimeRequestId != input.request.requestId) {
                return denied(TrustPermissionDenial.WRONG_ONE_TIME_REQUEST)
            }
            PermissionGrantLifetime.PERSISTENT -> Unit
        }

        val restrictions = grant.restrictions
        if (input.policyInputs.hostRole !in restrictions.allowedHostRoles) {
            return denied(TrustPermissionDenial.WRONG_HOST)
        }
        if (input.policyInputs.executionOrigin == ExecutionOrigin.REMOTE &&
            restrictions.locality == ExecutionLocality.LOCAL_ONLY
        ) return denied(TrustPermissionDenial.REMOTE_EXECUTION_NOT_ALLOWED)
        if (restrictions.requiresPhysicalPresence && !input.policyInputs.userPhysicallyPresent) {
            return denied(TrustPermissionDenial.PHYSICAL_PRESENCE_REQUIRED)
        }
        if (restrictions.requiresMountedHost && !input.policyInputs.hostMountedOnBody) {
            return denied(TrustPermissionDenial.MOUNTED_HOST_REQUIRED)
        }
        if (restrictions.requiresNearbyProvider && !input.policyInputs.providerNearby) {
            return denied(TrustPermissionDenial.NEARBY_PROVIDER_REQUIRED)
        }
        if (restrictions.requiresActiveBody && !input.policyInputs.requestedProviderIsActiveBody) {
            return denied(TrustPermissionDenial.ACTIVE_BODY_REQUIRED)
        }

        return evaluateApproval(input, grant, effectiveApproval(input.descriptor, grant))
    }

    private fun evaluateApproval(
        input: TrustPermissionEvaluationInput,
        grant: PermissionGrant,
        requirement: CapabilityApprovalRequirement,
    ): TrustPermissionDecision {
        if (requirement == CapabilityApprovalRequirement.WITHIN_GRANT) return permitted(grant, null)
        if (requirement == CapabilityApprovalRequirement.PROHIBITED) return denied(TrustPermissionDenial.PROHIBITED)
        val approval = input.approval ?: return TrustPermissionDecision.ApprovalRequired(requirement)
        if (approval.authorizationReference != grant.authorizationReference ||
            approval.actor != input.actor || approval.providerInstanceId != input.providerInstanceId ||
            approval.capability != input.request.capability || approval.requirement != requirement
        ) return denied(TrustPermissionDenial.APPROVAL_MISMATCH)
        if (isRevoked(input.revocations, RevocationTarget.Approval(approval.id), input.now)) {
            return denied(TrustPermissionDenial.APPROVAL_REVOKED)
        }
        if (!approval.validity.contains(input.now)) return denied(TrustPermissionDenial.APPROVAL_EXPIRED)
        if (approval.state == ApprovalState.DENIED) return denied(TrustPermissionDenial.APPROVAL_DENIED)
        if (approval.state == ApprovalState.CONSUMED) return denied(TrustPermissionDenial.APPROVAL_REPLAYED)
        when (requirement) {
            CapabilityApprovalRequirement.SESSION_CONFIRMATION -> if (
                approval.sessionId != input.policyInputs.sessionId
            ) return denied(TrustPermissionDenial.APPROVAL_MISMATCH)
            CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION,
            CapabilityApprovalRequirement.EVERY_REQUEST,
            -> if (approval.requestId != input.request.requestId) {
                return denied(TrustPermissionDenial.APPROVAL_MISMATCH)
            }
            CapabilityApprovalRequirement.WITHIN_GRANT,
            CapabilityApprovalRequirement.PROHIBITED,
            -> error("Handled before approval record validation")
        }
        val consume = if (requirement == CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION) approval.id else null
        return permitted(grant, consume)
    }

    private fun effectiveApproval(
        descriptor: CapabilityDescriptor,
        grant: PermissionGrant,
    ): CapabilityApprovalRequirement = listOf(
        descriptor.approvalRequirement,
        grant.approvalRequirement,
    ).maxBy(::approvalStrength)

    private fun approvalStrength(requirement: CapabilityApprovalRequirement): Int = when (requirement) {
        CapabilityApprovalRequirement.WITHIN_GRANT -> 0
        CapabilityApprovalRequirement.SESSION_CONFIRMATION -> 1
        CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION -> 2
        CapabilityApprovalRequirement.EVERY_REQUEST -> 3
        CapabilityApprovalRequirement.PROHIBITED -> 4
    }

    private fun isRevoked(
        records: List<RevocationRecord>,
        target: RevocationTarget,
        now: MonotonicTimestampMs,
    ): Boolean = records.any { it.target == target && it.revokedAt.value <= now.value }

    private fun scopeAllowed(
        granted: Map<PermissionScopeKey, Set<PermissionScopeValue>>,
        requested: Map<PermissionScopeKey, Set<PermissionScopeValue>>,
    ): Boolean {
        if (granted.isEmpty()) return requested.isEmpty()
        if (requested.keys != granted.keys || requested.values.any { it.isEmpty() }) return false
        return requested.all { (key, values) -> granted[key]?.containsAll(values) == true }
    }

    private fun permitted(
        grant: PermissionGrant,
        approvalToConsume: ApprovalId?,
    ): TrustPermissionDecision = TrustPermissionDecision.Permitted(
        authorizationReference = grant.authorizationReference,
        approvalToConsume = approvalToConsume,
        deterministicSafetyStillRequired = true,
    )

    private fun denied(reason: TrustPermissionDenial): TrustPermissionDecision.Denied =
        TrustPermissionDecision.Denied(reason)
}
