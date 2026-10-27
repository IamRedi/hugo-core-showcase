package io.redi.robot.trust.runtime

import io.redi.robot.capability.api.CapabilityApprovalRequirement
import io.redi.robot.capability.api.CapabilityProviderTarget
import io.redi.robot.capability.negotiation.CapabilityNegotiationStatus
import io.redi.robot.capability.negotiation.NegotiatedCapabilityRecord
import io.redi.robot.context.provider.BodyProviderReference
import io.redi.robot.context.provider.EmbodimentEvidenceKind
import io.redi.robot.context.provider.MountingState
import io.redi.robot.trust.domain.ApprovalState
import io.redi.robot.trust.domain.AuthorityPolicyInputs
import io.redi.robot.trust.domain.NonAuthorityEvidence
import io.redi.robot.trust.domain.TrustPermissionDecision
import io.redi.robot.trust.domain.TrustPermissionDenial
import io.redi.robot.trust.domain.TrustPermissionEvaluationInput
import io.redi.robot.trust.domain.TrustPermissionEvaluator

/**
 * Security-critical H030 runtime gate. It validates current semantic negotiation evidence, delegates
 * authority semantics to H014, and atomically consumes request-bound approvals. It never dispatches.
 */
class AuthorizationGate(
    private val approvalConsumption: ApprovalConsumptionPort,
) {
    fun authorize(input: AuthorizationGateInput): AuthorizationDecision {
        val checks = mutableListOf<AuthorizationAuditCheck>()
        val evidence = evidenceConsidered(input)

        if (input.now.value < input.request.createdAt.value || input.now.value >= input.request.deadline.value) {
            checks += check(
                AuthorizationAuditDimension.REQUEST,
                "request.current",
                AuthorizationAuditCheckStatus.STALE,
                AuthorizationDecisionReason.REQUEST_NOT_CURRENT,
                listOf("createdAt<=now<deadline"),
                listOf("createdAt=${input.request.createdAt.value}", "now=${input.now.value}", "deadline=${input.request.deadline.value}"),
                evidenceFor(evidence, AuthorizationEvidenceSource.REQUEST),
            )
            return decision(input, AuthorizationDecisionStatus.EXPIRED, AuthorizationDecisionReason.REQUEST_NOT_CURRENT, checks, evidence)
        }
        checks += matched(
            AuthorizationAuditDimension.REQUEST,
            "request.current",
            listOf("current"),
            evidenceFor(evidence, AuthorizationEvidenceSource.REQUEST),
        )

        val exactTarget = input.request.providerTarget as? CapabilityProviderTarget.Exact
        if (exactTarget == null) {
            checks += denied(
                AuthorizationAuditDimension.PROVIDER,
                "request.provider.exact",
                AuthorizationDecisionReason.REQUEST_REQUIRES_EXACT_PROVIDER,
                listOf("exact-provider"),
                listOf("any-compatible"),
                evidenceFor(evidence, AuthorizationEvidenceSource.REQUEST),
            )
            return decision(
                input,
                AuthorizationDecisionStatus.PROVIDER_MISMATCH,
                AuthorizationDecisionReason.REQUEST_REQUIRES_EXACT_PROVIDER,
                checks,
                evidence,
            )
        }
        if (exactTarget.providerInstanceId != input.runtime.providerIdentity.instanceId) {
            checks += denied(
                AuthorizationAuditDimension.PROVIDER,
                "request.provider.binding",
                AuthorizationDecisionReason.REQUEST_PROVIDER_MISMATCH,
                listOf(input.runtime.providerIdentity.instanceId.value),
                listOf(exactTarget.providerInstanceId.value),
                evidenceFor(evidence, AuthorizationEvidenceSource.PROVIDER_IDENTITY),
            )
            return decision(
                input,
                AuthorizationDecisionStatus.PROVIDER_MISMATCH,
                AuthorizationDecisionReason.REQUEST_PROVIDER_MISMATCH,
                checks,
                evidence,
            )
        }
        checks += matched(
            AuthorizationAuditDimension.PROVIDER,
            "request.provider.binding",
            listOf("exact"),
            evidenceFor(evidence, AuthorizationEvidenceSource.PROVIDER_IDENTITY),
        )

        if (input.negotiation.status !in successfulNegotiationStatuses) {
            checks += denied(
                AuthorizationAuditDimension.NEGOTIATION,
                "negotiation.status",
                AuthorizationDecisionReason.NEGOTIATION_NOT_SUCCESSFUL,
                successfulNegotiationStatuses.map { it.name },
                listOf(input.negotiation.status.name),
                evidenceFor(evidence, AuthorizationEvidenceSource.NEGOTIATION),
            )
            return stale(input, AuthorizationDecisionReason.NEGOTIATION_NOT_SUCCESSFUL, checks, evidence)
        }

        val candidates = input.negotiation.negotiatedCapabilities.filter {
            it.capability == input.request.capability
        }
        if (candidates.isEmpty()) {
            checks += denied(
                AuthorizationAuditDimension.NEGOTIATION,
                "negotiation.capability",
                AuthorizationDecisionReason.NEGOTIATED_CAPABILITY_MISSING,
                listOf(input.request.capability.wireValue),
                input.negotiation.negotiatedCapabilities.map { it.capability.wireValue },
                evidenceFor(evidence, AuthorizationEvidenceSource.NEGOTIATION),
            )
            return decision(
                input,
                AuthorizationDecisionStatus.CAPABILITY_MISMATCH,
                AuthorizationDecisionReason.NEGOTIATED_CAPABILITY_MISSING,
                checks,
                evidence,
            )
        }
        if (candidates.size != 1) {
            checks += check(
                AuthorizationAuditDimension.NEGOTIATION,
                "negotiation.capability.unique",
                AuthorizationAuditCheckStatus.CONFLICTING,
                AuthorizationDecisionReason.NEGOTIATED_CAPABILITY_CONFLICT,
                listOf("one-record"),
                listOf("count=${candidates.size}"),
                evidenceFor(evidence, AuthorizationEvidenceSource.NEGOTIATION),
            )
            return decision(
                input,
                AuthorizationDecisionStatus.INVALID,
                AuthorizationDecisionReason.NEGOTIATED_CAPABILITY_CONFLICT,
                checks,
                evidence,
            )
        }
        val negotiated = candidates.single()

        validateNegotiationCorrelation(input, negotiated, checks, evidence)?.let { return it }
        validateRequestContract(input, negotiated, checks, evidence)?.let { return it }

        val policyInputs = policyInputs(input)
        val authorityDecision = TrustPermissionEvaluator.evaluate(
            TrustPermissionEvaluationInput(
                request = input.request,
                actor = input.actor,
                providerInstanceId = input.runtime.providerIdentity.instanceId,
                descriptor = negotiated.descriptor,
                trustRecord = input.authority.trustRecord,
                grant = input.authority.grant,
                approval = input.authority.approval,
                policyInputs = policyInputs,
                requestedScope = input.requestedScope,
                nonAuthorityEvidence = NonAuthorityEvidence(
                    providerDiscovered = true,
                    manifestCompatible = true,
                    connectionEstablished = negotiated.connectionSessionIds.isEmpty() ||
                        negotiated.connectionSessionIds == input.runtime.connectionSessionIds,
                ),
                revocations = input.authority.revocations,
                now = input.now,
            ),
        )

        return when (authorityDecision) {
            is TrustPermissionDecision.ApprovalRequired -> {
                checks += denied(
                    AuthorizationAuditDimension.APPROVAL,
                    "authority.approval",
                    AuthorizationDecisionReason.APPROVAL_REQUIRED,
                    listOf(authorityDecision.requirement.name),
                    listOf("missing"),
                    evidenceFor(evidence, AuthorizationEvidenceSource.APPROVAL_RECORD),
                )
                decision(
                    input,
                    AuthorizationDecisionStatus.APPROVAL_REQUIRED,
                    AuthorizationDecisionReason.APPROVAL_REQUIRED,
                    checks,
                    evidence,
                )
            }
            is TrustPermissionDecision.Denied -> {
                val mapped = mapDenial(authorityDecision.reason)
                checks += denied(
                    mapped.dimension,
                    "authority.h014",
                    mapped.reason,
                    listOf("h014-permitted"),
                    listOf(authorityDecision.reason.name),
                    evidence,
                )
                decision(input, mapped.status, mapped.reason, checks, evidence)
            }
            is TrustPermissionDecision.Permitted -> authorizeAfterConsumption(
                input,
                negotiated,
                authorityDecision,
                checks,
                evidence,
            )
        }
    }

    private fun validateNegotiationCorrelation(
        input: AuthorizationGateInput,
        negotiated: NegotiatedCapabilityRecord,
        checks: MutableList<AuthorizationAuditCheck>,
        evidence: List<AuthorizationAuditEvidence>,
    ): AuthorizationDecision? {
        val expectedProvider = input.runtime.providerIdentity.instanceId
        if (input.negotiation.providerInstanceId != expectedProvider || negotiated.providerInstanceId != expectedProvider) {
            checks += denied(
                AuthorizationAuditDimension.PROVIDER,
                "negotiation.provider.binding",
                AuthorizationDecisionReason.NEGOTIATION_PROVIDER_MISMATCH,
                listOf(expectedProvider.value),
                listOf(input.negotiation.providerInstanceId.value, negotiated.providerInstanceId.value),
                evidenceFor(evidence, AuthorizationEvidenceSource.NEGOTIATION, AuthorizationEvidenceSource.PROVIDER_IDENTITY),
            )
            return decision(
                input,
                AuthorizationDecisionStatus.PROVIDER_MISMATCH,
                AuthorizationDecisionReason.NEGOTIATION_PROVIDER_MISMATCH,
                checks,
                evidence,
            )
        }

        val expectedGeneration = input.runtime.providerGeneration
        if (input.negotiation.providerGeneration != expectedGeneration ||
            negotiated.providerGeneration != expectedGeneration ||
            negotiated.negotiationId != input.negotiation.negotiationId
        ) {
            checks += check(
                AuthorizationAuditDimension.NEGOTIATION,
                "negotiation.generation",
                AuthorizationAuditCheckStatus.STALE,
                AuthorizationDecisionReason.NEGOTIATION_GENERATION_STALE,
                listOf(expectedGeneration.value.toString()),
                listOf(input.negotiation.providerGeneration.value.toString(), negotiated.providerGeneration.value.toString()),
                evidenceFor(evidence, AuthorizationEvidenceSource.NEGOTIATION),
            )
            return stale(input, AuthorizationDecisionReason.NEGOTIATION_GENERATION_STALE, checks, evidence)
        }
        checks += matched(
            AuthorizationAuditDimension.NEGOTIATION,
            "negotiation.generation",
            listOf(expectedGeneration.value.toString()),
            evidenceFor(evidence, AuthorizationEvidenceSource.NEGOTIATION),
        )

        if (input.now.value < negotiated.negotiatedAt.value || input.now.value >= negotiated.validUntil.value) {
            checks += check(
                AuthorizationAuditDimension.NEGOTIATION,
                "negotiation.freshness",
                AuthorizationAuditCheckStatus.STALE,
                AuthorizationDecisionReason.NEGOTIATION_EXPIRED,
                listOf("negotiatedAt<=now<validUntil"),
                listOf(
                    "negotiatedAt=${negotiated.negotiatedAt.value}",
                    "now=${input.now.value}",
                    "validUntil=${negotiated.validUntil.value}",
                ),
                evidenceFor(evidence, AuthorizationEvidenceSource.NEGOTIATION),
            )
            return stale(input, AuthorizationDecisionReason.NEGOTIATION_EXPIRED, checks, evidence)
        }
        checks += matched(
            AuthorizationAuditDimension.NEGOTIATION,
            "negotiation.freshness",
            listOf("fresh"),
            evidenceFor(evidence, AuthorizationEvidenceSource.NEGOTIATION),
        )

        if (negotiated.connectionSessionIds.isNotEmpty() &&
            negotiated.connectionSessionIds != input.runtime.connectionSessionIds
        ) {
            checks += check(
                AuthorizationAuditDimension.CONNECTION_SESSION,
                "negotiation.session",
                AuthorizationAuditCheckStatus.STALE,
                AuthorizationDecisionReason.NEGOTIATION_SESSION_STALE,
                negotiated.connectionSessionIds.map { it.value },
                input.runtime.connectionSessionIds.map { it.value },
                evidenceFor(evidence, AuthorizationEvidenceSource.CONNECTION_SESSION),
            )
            return stale(input, AuthorizationDecisionReason.NEGOTIATION_SESSION_STALE, checks, evidence)
        }
        checks += matched(
            AuthorizationAuditDimension.CONNECTION_SESSION,
            "negotiation.session",
            if (negotiated.connectionSessionIds.isEmpty()) listOf("not-required")
            else negotiated.connectionSessionIds.map { it.value },
            evidenceFor(evidence, AuthorizationEvidenceSource.CONNECTION_SESSION),
        )
        return null
    }

    private fun validateRequestContract(
        input: AuthorizationGateInput,
        negotiated: NegotiatedCapabilityRecord,
        checks: MutableList<AuthorizationAuditCheck>,
        evidence: List<AuthorizationAuditEvidence>,
    ): AuthorizationDecision? {
        if (input.request.actor != input.actor.kind) {
            checks += denied(
                AuthorizationAuditDimension.REQUEST,
                "request.actor",
                AuthorizationDecisionReason.REQUEST_ACTOR_MISMATCH,
                listOf(input.actor.kind.name),
                listOf(input.request.actor.name),
                evidenceFor(evidence, AuthorizationEvidenceSource.REQUEST),
            )
            return decision(
                input,
                AuthorizationDecisionStatus.ACTOR_MISMATCH,
                AuthorizationDecisionReason.REQUEST_ACTOR_MISMATCH,
                checks,
                evidence,
            )
        }

        if (input.request.capability != negotiated.descriptor.reference) {
            checks += denied(
                AuthorizationAuditDimension.REQUEST,
                "request.capability",
                AuthorizationDecisionReason.REQUEST_CAPABILITY_MISMATCH,
                listOf(negotiated.descriptor.reference.wireValue),
                listOf(input.request.capability.wireValue),
                evidenceFor(evidence, AuthorizationEvidenceSource.REQUEST, AuthorizationEvidenceSource.NEGOTIATION),
            )
            return decision(
                input,
                AuthorizationDecisionStatus.CAPABILITY_MISMATCH,
                AuthorizationDecisionReason.REQUEST_CAPABILITY_MISMATCH,
                checks,
                evidence,
            )
        }

        val requestContract = input.request.input?.contract
        val contractMatches = when (val negotiatedContract = negotiated.inputContract) {
            null -> requestContract == null
            else -> requestContract == negotiatedContract
        }
        if (!contractMatches) {
            checks += denied(
                AuthorizationAuditDimension.REQUEST,
                "request.input.contract",
                AuthorizationDecisionReason.REQUEST_CONTRACT_MISMATCH,
                listOf(negotiated.inputContract?.wireValue ?: "none"),
                listOf(requestContract?.wireValue ?: "none"),
                evidenceFor(evidence, AuthorizationEvidenceSource.REQUEST, AuthorizationEvidenceSource.NEGOTIATION),
            )
            return decision(
                input,
                AuthorizationDecisionStatus.INVALID,
                AuthorizationDecisionReason.REQUEST_CONTRACT_MISMATCH,
                checks,
                evidence,
            )
        }
        checks += matched(
            AuthorizationAuditDimension.REQUEST,
            "request.semantic.binding",
            listOf("actor", "capability", "input-contract"),
            evidenceFor(evidence, AuthorizationEvidenceSource.REQUEST, AuthorizationEvidenceSource.NEGOTIATION),
        )
        return null
    }

    private fun authorizeAfterConsumption(
        input: AuthorizationGateInput,
        negotiated: NegotiatedCapabilityRecord,
        permitted: TrustPermissionDecision.Permitted,
        checks: MutableList<AuthorizationAuditCheck>,
        evidence: List<AuthorizationAuditEvidence>,
    ): AuthorizationDecision {
        if (!permitted.deterministicSafetyStillRequired) {
            checks += check(
                AuthorizationAuditDimension.POLICY,
                "authority.safety.boundary",
                AuthorizationAuditCheckStatus.FAILED,
                AuthorizationDecisionReason.APPROVAL_CONSUMPTION_FAILED,
                listOf("safety-still-required"),
                listOf("false"),
                evidenceFor(evidence, AuthorizationEvidenceSource.POLICY),
            )
            return decision(
                input,
                AuthorizationDecisionStatus.FAILED,
                AuthorizationDecisionReason.APPROVAL_CONSUMPTION_FAILED,
                checks,
                evidence,
            )
        }
        checks += matched(
            AuthorizationAuditDimension.POLICY,
            "authority.h014",
            listOf("permitted", "safety-still-required"),
            evidence,
        )

        val approvalId = approvalToConsume(input, negotiated, permitted)
        if (approvalId == null) {
            return decision(
                input,
                AuthorizationDecisionStatus.AUTHORIZED,
                AuthorizationDecisionReason.AUTHORITY_VALID,
                checks,
                evidence,
                authorizationReference = permitted.authorizationReference,
            )
        }

        val attempt = ApprovalConsumptionAttempt(
                approvalId = approvalId,
                authorizationReference = permitted.authorizationReference,
                requestId = input.request.requestId,
                decisionId = input.decisionId,
                consumedAt = input.now,
            )
        val consumptionResult = approvalConsumption.consumeOnce(attempt)
        val returnedReceipt = when (consumptionResult) {
            is ApprovalConsumptionResult.Consumed -> consumptionResult.receipt
            is ApprovalConsumptionResult.AlreadyConsumed -> consumptionResult.receipt
            ApprovalConsumptionResult.BindingConflict,
            ApprovalConsumptionResult.Failed,
            -> null
        }
        val receiptBindingValid = returnedReceipt == null || (
            returnedReceipt.approvalId == attempt.approvalId &&
                returnedReceipt.authorizationReference == attempt.authorizationReference &&
                returnedReceipt.requestId == attempt.requestId &&
                returnedReceipt.consumedAt.value <= input.now.value
            )
        val newReceiptValid = consumptionResult !is ApprovalConsumptionResult.Consumed ||
            (consumptionResult.receipt.decisionId == attempt.decisionId &&
                consumptionResult.receipt.consumedAt == attempt.consumedAt)
        if (!receiptBindingValid || !newReceiptValid) {
            checks += check(
                AuthorizationAuditDimension.APPROVAL_CONSUMPTION,
                "approval.consume-once",
                AuthorizationAuditCheckStatus.CONFLICTING,
                AuthorizationDecisionReason.APPROVAL_CONSUMPTION_CONFLICT,
                listOf("correlated-receipt"),
                listOf("invalid-receipt-binding"),
                evidenceFor(evidence, AuthorizationEvidenceSource.APPROVAL_RECORD),
            )
            return decision(
                input,
                AuthorizationDecisionStatus.INVALID,
                AuthorizationDecisionReason.APPROVAL_CONSUMPTION_CONFLICT,
                checks,
                evidence,
            )
        }
        return when (consumptionResult) {
            is ApprovalConsumptionResult.Consumed -> {
                val consumptionEvidence = AuthorizationAuditEvidence(
                    AuthorizationEvidenceSource.APPROVAL_CONSUMPTION,
                    consumptionResult.receipt.evidenceReference,
                )
                val finalEvidence = evidence + consumptionEvidence
                checks += check(
                    AuthorizationAuditDimension.APPROVAL_CONSUMPTION,
                    "approval.consume-once",
                    AuthorizationAuditCheckStatus.CONSUMED,
                    AuthorizationDecisionReason.APPROVAL_CONSUMED,
                    listOf("unconsumed"),
                    listOf("consumed"),
                    listOf(consumptionEvidence),
                )
                decision(
                    input,
                    AuthorizationDecisionStatus.AUTHORIZED,
                    AuthorizationDecisionReason.APPROVAL_CONSUMED,
                    checks,
                    finalEvidence,
                    authorizationReference = permitted.authorizationReference,
                    approvalConsumption = consumptionResult.receipt,
                )
            }
            is ApprovalConsumptionResult.AlreadyConsumed -> {
                val consumptionEvidence = AuthorizationAuditEvidence(
                    AuthorizationEvidenceSource.APPROVAL_CONSUMPTION,
                    consumptionResult.receipt.evidenceReference,
                )
                val finalEvidence = evidence + consumptionEvidence
                checks += check(
                    AuthorizationAuditDimension.APPROVAL_CONSUMPTION,
                    "approval.consume-once",
                    AuthorizationAuditCheckStatus.REPLAYED,
                    AuthorizationDecisionReason.APPROVAL_REPLAYED,
                    listOf("unconsumed"),
                    listOf("already-consumed"),
                    listOf(consumptionEvidence),
                )
                decision(
                    input,
                    AuthorizationDecisionStatus.REPLAY_REJECTED,
                    AuthorizationDecisionReason.APPROVAL_REPLAYED,
                    checks,
                    finalEvidence,
                    approvalConsumption = consumptionResult.receipt,
                )
            }
            ApprovalConsumptionResult.BindingConflict -> {
                checks += check(
                    AuthorizationAuditDimension.APPROVAL_CONSUMPTION,
                    "approval.consume-once",
                    AuthorizationAuditCheckStatus.CONFLICTING,
                    AuthorizationDecisionReason.APPROVAL_CONSUMPTION_CONFLICT,
                    listOf("matching-binding"),
                    listOf("conflict"),
                    evidenceFor(evidence, AuthorizationEvidenceSource.APPROVAL_RECORD),
                )
                decision(
                    input,
                    AuthorizationDecisionStatus.INVALID,
                    AuthorizationDecisionReason.APPROVAL_CONSUMPTION_CONFLICT,
                    checks,
                    evidence,
                )
            }
            ApprovalConsumptionResult.Failed -> {
                checks += check(
                    AuthorizationAuditDimension.APPROVAL_CONSUMPTION,
                    "approval.consume-once",
                    AuthorizationAuditCheckStatus.FAILED,
                    AuthorizationDecisionReason.APPROVAL_CONSUMPTION_FAILED,
                    listOf("atomic-consumption"),
                    listOf("failed"),
                    evidenceFor(evidence, AuthorizationEvidenceSource.APPROVAL_RECORD),
                )
                decision(
                    input,
                    AuthorizationDecisionStatus.FAILED,
                    AuthorizationDecisionReason.APPROVAL_CONSUMPTION_FAILED,
                    checks,
                    evidence,
                )
            }
        }
    }

    private fun approvalToConsume(
        input: AuthorizationGateInput,
        negotiated: NegotiatedCapabilityRecord,
        permitted: TrustPermissionDecision.Permitted,
    ) = permitted.approvalToConsume ?: input.authority.approval?.takeIf { approval ->
        approval.state == ApprovalState.APPROVED &&
            approval.requirement == CapabilityApprovalRequirement.EVERY_REQUEST &&
            (negotiated.descriptor.approvalRequirement == CapabilityApprovalRequirement.EVERY_REQUEST ||
                input.authority.grant?.approvalRequirement == CapabilityApprovalRequirement.EVERY_REQUEST)
    }?.id

    private fun policyInputs(input: AuthorizationGateInput): AuthorityPolicyInputs {
        val presence = input.runtime.physicalPresence?.let {
            it.present && it.isFreshAt(input.now)
        } == true
        val embodiment = input.runtime.embodiment?.takeIf {
            it.host == input.runtime.hostContext.host
        }
        val bodyReference = when (val result = BodyProviderReference.create(input.runtime.providerIdentity)) {
            is io.redi.robot.context.provider.EmbodimentResult.Success -> result.value
            is io.redi.robot.context.provider.EmbodimentResult.Failure -> null
        }
        val body = bodyReference?.let { embodiment?.body(it) }
        val freshEvidence = body?.evidence.orEmpty().filter { it.isFreshAt(input.now) }
        val mounted = body?.mounting == MountingState.MOUNTED && freshEvidence.any {
            it.kind == EmbodimentEvidenceKind.MOUNTING_VERIFIED
        }
        val nearby = body?.nearby == true && freshEvidence.any {
            it.kind == EmbodimentEvidenceKind.PROXIMITY_OBSERVED
        }
        val active = bodyReference != null && embodiment?.activeSelection?.let {
            it.body == bodyReference && it.isActiveAt(input.now)
        } == true
        return AuthorityPolicyInputs(
            hostRole = input.runtime.hostContext.host.role,
            executionOrigin = input.runtime.hostContext.executionOrigin,
            sessionId = input.runtime.hostContext.authoritySessionId,
            userPhysicallyPresent = presence,
            hostMountedOnBody = mounted,
            providerNearby = nearby,
            requestedProviderIsActiveBody = active,
        )
    }

    private fun evidenceConsidered(input: AuthorizationGateInput): List<AuthorizationAuditEvidence> {
        val evidence = mutableListOf(
            evidence(AuthorizationEvidenceSource.REQUEST, input.request.auditId.value),
            evidence(AuthorizationEvidenceSource.NEGOTIATION, input.negotiation.negotiationId.value),
            evidence(AuthorizationEvidenceSource.PROVIDER_IDENTITY, input.runtime.providerIdentity.instanceId.value),
            evidence(AuthorizationEvidenceSource.HOST_CONTEXT, input.runtime.hostContext.host.reference.value),
        )
        input.runtime.connectionSessionIds.forEach {
            evidence += evidence(AuthorizationEvidenceSource.CONNECTION_SESSION, it.value)
        }
        input.authority.trustRecord?.let {
            evidence += evidence(AuthorizationEvidenceSource.TRUST_RECORD, it.id.value)
        }
        input.authority.grant?.let {
            evidence += evidence(AuthorizationEvidenceSource.PERMISSION_GRANT, it.authorizationReference.value)
        }
        input.authority.approval?.let {
            evidence += evidence(AuthorizationEvidenceSource.APPROVAL_RECORD, it.id.value)
        }
        input.authority.revocations.forEach {
            evidence += evidence(AuthorizationEvidenceSource.REVOCATION_RECORD, it.id.value)
        }
        input.runtime.physicalPresence?.let {
            evidence += AuthorizationAuditEvidence(AuthorizationEvidenceSource.PHYSICAL_PRESENCE, it.reference)
        }
        input.runtime.embodiment?.bodies.orEmpty().flatMap { it.evidence }.forEach {
            evidence += evidence(AuthorizationEvidenceSource.EMBODIMENT, it.id.value)
        }
        input.policyReferences.forEach {
            evidence += evidence(AuthorizationEvidenceSource.POLICY, it.value)
        }
        return evidence.distinct().sortedWith(compareBy({ it.source.ordinal }, { it.reference.value }))
    }

    private fun evidence(source: AuthorizationEvidenceSource, value: String) = AuthorizationAuditEvidence(
        source,
        AuthorizationEvidenceReference.projectOwned(value),
    )

    private fun evidenceFor(
        evidence: List<AuthorizationAuditEvidence>,
        vararg sources: AuthorizationEvidenceSource,
    ): List<AuthorizationAuditEvidence> = evidence.filter { it.source in sources.toSet() }

    private fun matched(
        dimension: AuthorizationAuditDimension,
        id: String,
        observed: List<String>,
        evidence: List<AuthorizationAuditEvidence>,
    ) = check(
        dimension,
        id,
        AuthorizationAuditCheckStatus.MATCHED,
        AuthorizationDecisionReason.AUTHORITY_VALID,
        observed,
        observed,
        evidence,
    )

    private fun denied(
        dimension: AuthorizationAuditDimension,
        id: String,
        reason: AuthorizationDecisionReason,
        required: List<String>,
        observed: List<String>,
        evidence: List<AuthorizationAuditEvidence>,
    ) = check(
        dimension,
        id,
        AuthorizationAuditCheckStatus.DENIED,
        reason,
        required,
        observed,
        evidence,
    )

    private fun check(
        dimension: AuthorizationAuditDimension,
        id: String,
        status: AuthorizationAuditCheckStatus,
        reason: AuthorizationDecisionReason,
        required: List<String>,
        observed: List<String>,
        evidence: List<AuthorizationAuditEvidence>,
    ) = AuthorizationAuditCheck(
        dimension,
        id,
        status,
        reason,
        required.distinct().sorted(),
        observed.distinct().sorted(),
        evidence.distinct().sortedWith(compareBy({ it.source.ordinal }, { it.reference.value })),
    )

    private fun stale(
        input: AuthorizationGateInput,
        reason: AuthorizationDecisionReason,
        checks: List<AuthorizationAuditCheck>,
        evidence: List<AuthorizationAuditEvidence>,
    ) = decision(input, AuthorizationDecisionStatus.STALE_NEGOTIATION, reason, checks, evidence)

    private fun decision(
        input: AuthorizationGateInput,
        status: AuthorizationDecisionStatus,
        reason: AuthorizationDecisionReason,
        checks: List<AuthorizationAuditCheck>,
        evidence: List<AuthorizationAuditEvidence>,
        authorizationReference: io.redi.robot.capability.api.CapabilityAuthorizationReference? = null,
        approvalConsumption: ApprovalConsumptionReceipt? = null,
    ): AuthorizationDecision = AuthorizationDecision(
        status,
        reason,
        authorizationReference,
        AuthorizationAuditRecord(
            decisionId = input.decisionId,
            requestId = input.request.requestId,
            requestAuditId = input.request.auditId,
            actorKind = input.request.actor,
            providerInstanceId = input.runtime.providerIdentity.instanceId,
            capability = input.request.capability,
            providerGeneration = input.runtime.providerGeneration,
            negotiationId = input.negotiation.negotiationId,
            decidedAt = input.now,
            authorizationReferenceConsidered = input.request.authorizationReference != null,
            policyReferences = input.policyReferences,
            evidence = evidence,
            checks = checks,
            approvalConsumption = approvalConsumption,
        ),
    )

    private fun mapDenial(denial: TrustPermissionDenial): MappedDenial = when (denial) {
        TrustPermissionDenial.PROHIBITED -> mapped(AuthorizationDecisionStatus.PROHIBITED, AuthorizationDecisionReason.PROHIBITED, AuthorizationAuditDimension.POLICY)
        TrustPermissionDenial.REQUEST_REQUIRES_EXACT_PROVIDER -> mapped(AuthorizationDecisionStatus.PROVIDER_MISMATCH, AuthorizationDecisionReason.REQUEST_REQUIRES_EXACT_PROVIDER, AuthorizationAuditDimension.PROVIDER)
        TrustPermissionDenial.REQUEST_PROVIDER_MISMATCH -> mapped(AuthorizationDecisionStatus.PROVIDER_MISMATCH, AuthorizationDecisionReason.REQUEST_PROVIDER_MISMATCH, AuthorizationAuditDimension.PROVIDER)
        TrustPermissionDenial.REQUEST_ACTOR_MISMATCH -> mapped(AuthorizationDecisionStatus.ACTOR_MISMATCH, AuthorizationDecisionReason.REQUEST_ACTOR_MISMATCH, AuthorizationAuditDimension.REQUEST)
        TrustPermissionDenial.REQUEST_CAPABILITY_MISMATCH -> mapped(AuthorizationDecisionStatus.CAPABILITY_MISMATCH, AuthorizationDecisionReason.REQUEST_CAPABILITY_MISMATCH, AuthorizationAuditDimension.REQUEST)
        TrustPermissionDenial.TRUST_MISSING -> mapped(AuthorizationDecisionStatus.TRUST_REQUIRED, AuthorizationDecisionReason.TRUST_MISSING, AuthorizationAuditDimension.TRUST)
        TrustPermissionDenial.TRUST_WRONG_PROVIDER -> mapped(AuthorizationDecisionStatus.PROVIDER_MISMATCH, AuthorizationDecisionReason.TRUST_WRONG_PROVIDER, AuthorizationAuditDimension.TRUST)
        TrustPermissionDenial.TRUST_INSUFFICIENT -> mapped(AuthorizationDecisionStatus.TRUST_REQUIRED, AuthorizationDecisionReason.TRUST_INSUFFICIENT, AuthorizationAuditDimension.TRUST)
        TrustPermissionDenial.TRUST_EXPIRED -> mapped(AuthorizationDecisionStatus.EXPIRED, AuthorizationDecisionReason.TRUST_EXPIRED, AuthorizationAuditDimension.TRUST)
        TrustPermissionDenial.TRUST_REVOKED -> mapped(AuthorizationDecisionStatus.REVOKED, AuthorizationDecisionReason.TRUST_REVOKED, AuthorizationAuditDimension.TRUST)
        TrustPermissionDenial.GRANT_MISSING -> mapped(AuthorizationDecisionStatus.PERMISSION_REQUIRED, AuthorizationDecisionReason.GRANT_MISSING, AuthorizationAuditDimension.PERMISSION)
        TrustPermissionDenial.AUTHORIZATION_REFERENCE_MISSING -> mapped(AuthorizationDecisionStatus.PERMISSION_REQUIRED, AuthorizationDecisionReason.AUTHORIZATION_REFERENCE_MISSING, AuthorizationAuditDimension.PERMISSION)
        TrustPermissionDenial.AUTHORIZATION_REFERENCE_MISMATCH -> mapped(AuthorizationDecisionStatus.PERMISSION_REQUIRED, AuthorizationDecisionReason.AUTHORIZATION_REFERENCE_MISMATCH, AuthorizationAuditDimension.PERMISSION)
        TrustPermissionDenial.GRANT_EXPIRED -> mapped(AuthorizationDecisionStatus.EXPIRED, AuthorizationDecisionReason.GRANT_EXPIRED, AuthorizationAuditDimension.PERMISSION)
        TrustPermissionDenial.GRANT_REVOKED -> mapped(AuthorizationDecisionStatus.REVOKED, AuthorizationDecisionReason.GRANT_REVOKED, AuthorizationAuditDimension.PERMISSION)
        TrustPermissionDenial.WRONG_PROVIDER -> mapped(AuthorizationDecisionStatus.PROVIDER_MISMATCH, AuthorizationDecisionReason.WRONG_PROVIDER, AuthorizationAuditDimension.PERMISSION)
        TrustPermissionDenial.WRONG_CAPABILITY -> mapped(AuthorizationDecisionStatus.CAPABILITY_MISMATCH, AuthorizationDecisionReason.WRONG_CAPABILITY, AuthorizationAuditDimension.PERMISSION)
        TrustPermissionDenial.WRONG_ACTOR -> mapped(AuthorizationDecisionStatus.ACTOR_MISMATCH, AuthorizationDecisionReason.WRONG_ACTOR, AuthorizationAuditDimension.PERMISSION)
        TrustPermissionDenial.WRONG_PERMISSION_INTENT -> mapped(AuthorizationDecisionStatus.PERMISSION_REQUIRED, AuthorizationDecisionReason.WRONG_PERMISSION_INTENT, AuthorizationAuditDimension.PERMISSION)
        TrustPermissionDenial.SCOPE_NOT_GRANTED -> mapped(AuthorizationDecisionStatus.SCOPE_MISMATCH, AuthorizationDecisionReason.SCOPE_NOT_GRANTED, AuthorizationAuditDimension.PERMISSION)
        TrustPermissionDenial.WRONG_SESSION -> mapped(AuthorizationDecisionStatus.SESSION_MISMATCH, AuthorizationDecisionReason.WRONG_AUTHORITY_SESSION, AuthorizationAuditDimension.PERMISSION)
        TrustPermissionDenial.WRONG_ONE_TIME_REQUEST -> mapped(AuthorizationDecisionStatus.SCOPE_MISMATCH, AuthorizationDecisionReason.WRONG_ONE_TIME_REQUEST, AuthorizationAuditDimension.PERMISSION)
        TrustPermissionDenial.WRONG_HOST -> mapped(AuthorizationDecisionStatus.HOST_RESTRICTED, AuthorizationDecisionReason.WRONG_HOST, AuthorizationAuditDimension.HOST)
        TrustPermissionDenial.REMOTE_EXECUTION_NOT_ALLOWED -> mapped(AuthorizationDecisionStatus.HOST_RESTRICTED, AuthorizationDecisionReason.REMOTE_EXECUTION_NOT_ALLOWED, AuthorizationAuditDimension.HOST)
        TrustPermissionDenial.PHYSICAL_PRESENCE_REQUIRED -> mapped(AuthorizationDecisionStatus.PRESENCE_REQUIRED, AuthorizationDecisionReason.PHYSICAL_PRESENCE_REQUIRED, AuthorizationAuditDimension.PRESENCE)
        TrustPermissionDenial.MOUNTED_HOST_REQUIRED -> mapped(AuthorizationDecisionStatus.MOUNTED_HOST_REQUIRED, AuthorizationDecisionReason.MOUNTED_HOST_REQUIRED, AuthorizationAuditDimension.EMBODIMENT)
        TrustPermissionDenial.NEARBY_PROVIDER_REQUIRED -> mapped(AuthorizationDecisionStatus.PROXIMITY_REQUIRED, AuthorizationDecisionReason.NEARBY_PROVIDER_REQUIRED, AuthorizationAuditDimension.EMBODIMENT)
        TrustPermissionDenial.ACTIVE_BODY_REQUIRED -> mapped(AuthorizationDecisionStatus.ACTIVE_BODY_REQUIRED, AuthorizationDecisionReason.ACTIVE_BODY_REQUIRED, AuthorizationAuditDimension.EMBODIMENT)
        TrustPermissionDenial.APPROVAL_MISMATCH -> mapped(AuthorizationDecisionStatus.APPROVAL_REQUIRED, AuthorizationDecisionReason.APPROVAL_MISMATCH, AuthorizationAuditDimension.APPROVAL)
        TrustPermissionDenial.APPROVAL_EXPIRED -> mapped(AuthorizationDecisionStatus.EXPIRED, AuthorizationDecisionReason.APPROVAL_EXPIRED, AuthorizationAuditDimension.APPROVAL)
        TrustPermissionDenial.APPROVAL_REVOKED -> mapped(AuthorizationDecisionStatus.REVOKED, AuthorizationDecisionReason.APPROVAL_REVOKED, AuthorizationAuditDimension.APPROVAL)
        TrustPermissionDenial.APPROVAL_DENIED -> mapped(AuthorizationDecisionStatus.APPROVAL_REQUIRED, AuthorizationDecisionReason.APPROVAL_DENIED, AuthorizationAuditDimension.APPROVAL)
        TrustPermissionDenial.APPROVAL_REPLAYED -> mapped(AuthorizationDecisionStatus.REPLAY_REJECTED, AuthorizationDecisionReason.APPROVAL_REPLAYED, AuthorizationAuditDimension.APPROVAL)
    }

    private fun mapped(
        status: AuthorizationDecisionStatus,
        reason: AuthorizationDecisionReason,
        dimension: AuthorizationAuditDimension,
    ) = MappedDenial(status, reason, dimension)

    private data class MappedDenial(
        val status: AuthorizationDecisionStatus,
        val reason: AuthorizationDecisionReason,
        val dimension: AuthorizationAuditDimension,
    )

    private companion object {
        val successfulNegotiationStatuses = setOf(
            CapabilityNegotiationStatus.NEGOTIATED,
            CapabilityNegotiationStatus.PARTIALLY_NEGOTIATED,
        )
    }
}
