package io.redi.robot.body.looi.firstlife

import io.redi.robot.capability.api.*
import io.redi.robot.capability.negotiation.CapabilityNegotiationReport
import io.redi.robot.context.provider.EmbodimentHost
import io.redi.robot.context.provider.EmbodimentHostReference
import io.redi.robot.context.provider.EmbodimentResult
import io.redi.robot.trust.domain.*
import io.redi.robot.trust.runtime.*
import java.util.UUID

/** Hardware-test-only creation of genuine H030 evidence; no authority is persisted. */
internal class FirstLifePhysicalAuthorization {
    private val consumption = AtomicApprovalConsumption()

    data class AuthorizedRequest(
        val request: CapabilityRequest,
        val decision: AuthorizationDecision,
    )

    fun create(
        capability: CapabilityReference,
        input: CapabilityData?,
        report: CapabilityNegotiationReport,
        sequence: Long,
        now: MonotonicTimestampMs,
    ): AuthorizedRequest {
        val reference = envelope(CapabilityAuthorizationReference.parse(UUID.randomUUID().toString()))
        val request = envelope(CapabilityRequest.create(
            CapabilityRequestId.fromUuid(UUID.randomUUID()), capability,
            CapabilityProviderTarget.Exact(report.providerInstanceId), CapabilityActorKind.HUGO_RUNTIME,
            envelope(CapabilityExecutionContext.create(report.providerGeneration.value, sequence)),
            now, time(now.value + 5_000), reference, CapabilityAuditId.fromUuid(UUID.randomUUID()), input,
        ))
        return AuthorizedRequest(request, authorize(request, report, now))
    }

    private fun authorize(
        request: CapabilityRequest,
        report: CapabilityNegotiationReport,
        now: MonotonicTimestampMs,
    ): AuthorizationDecision {
        val record = checkNotNull(report.negotiatedCapabilities.singleOrNull { it.capability == request.capability })
        val actor = AuthorityActor(trust(AuthorityActorId.parse(UUID.randomUUID().toString())), request.actor)
        val authorizationReference = checkNotNull(request.authorizationReference)
        val validity = trust(PermissionValidityWindow.create(time(0), time(now.value + 60_000)))
        val provenance = trust(AuthorityProvenanceReference.parse("local.fl007.hardware"))
        val auditId = request.auditId
        val trustRecord = trust(ProviderTrustRecord.create(
            schema(), trust(TrustRecordId.parse(UUID.randomUUID().toString())), report.providerInstanceId,
            ProviderTrustState.TRUSTED, validity, provenance, auditId,
        ))
        val scope = trust(PermissionScope.create(
            actor, report.providerInstanceId, request.capability, record.descriptor.permissionIntent, emptyMap(),
        ))
        val restrictions = trust(PermissionRestrictions.create(
            setOf(HugoHostRole.MOBILE_HOST), ExecutionLocality.LOCAL_ONLY,
            requiresPhysicalPresence = false, requiresMountedHost = false,
            requiresNearbyProvider = false, requiresActiveBody = false,
        ))
        val grant = trust(PermissionGrant.create(
            schema(), authorizationReference, scope, PermissionGrantLifetime.SESSION,
            sessionId = trust(AuthoritySessionId.parse(UUID.randomUUID().toString())),
            oneTimeRequestId = null,
            approvalRequirement = record.descriptor.approvalRequirement,
            validity = validity,
            restrictions = restrictions,
            provenance = provenance,
            auditId = auditId,
        ))
        val approval = if (record.descriptor.approvalRequirement in approvalRequired) {
            trust(ApprovalRecord.create(
                schema(), trust(ApprovalId.parse(UUID.randomUUID().toString())), authorizationReference,
                actor, report.providerInstanceId, request.capability, record.descriptor.approvalRequirement,
                requestId = if (record.descriptor.approvalRequirement == CapabilityApprovalRequirement.SESSION_CONFIRMATION) {
                    null
                } else request.requestId,
                sessionId = if (record.descriptor.approvalRequirement == CapabilityApprovalRequirement.SESSION_CONFIRMATION) {
                    grant.sessionId
                } else null,
                state = ApprovalState.APPROVED,
                validity = validity,
                provenance = provenance,
                auditId = auditId,
            ))
        } else null
        val host = EmbodimentHost(
            embodiment(EmbodimentHostReference.parse("android.first-life")),
            HugoHostRole.MOBILE_HOST,
        )
        val runtime = model(AuthorizationRuntimeContext.create(
            providerIdentity = io.redi.robot.capability.provider.ProviderIdentity(
                LooiFirstLifeDefinition.definitionId,
                report.providerInstanceId,
                io.redi.robot.capability.provider.ProviderFamily.BODY_ADAPTER,
                providerAlias(),
            ),
            providerGeneration = report.providerGeneration,
            connectionSessionIds = record.connectionSessionIds,
            hostContext = AuthorizationHostContext(host, ExecutionOrigin.LOCAL, grant.sessionId),
            physicalPresence = null,
            embodiment = null,
        ))
        val authority = model(AuthorizationAuthorityState.create(trustRecord, grant, approval, emptyList()))
        val input = model(AuthorizationGateInput.create(
            model(AuthorizationDecisionId.fromUuid(UUID.randomUUID())), request, actor, report, runtime, authority,
            requestedScope = emptyMap(),
            policyReferences = listOf(model(AuthorizationPolicyReference.parse("policy.fl007.hardware"))),
            now = now,
        ))
        return AuthorizationGate(consumption).authorize(input).also {
            check(it.status == AuthorizationDecisionStatus.AUTHORIZED) { "H030 rejected FL007 request: $it" }
            check(!it.grantsSafetyAuthority && !it.dispatchesCapability && !it.recordsExecutionSuccess)
        }
    }

    private fun providerAlias() = providerIdentity(
        io.redi.robot.capability.provider.RedactedProviderAlias.parse("LOOI Reference Body"),
    )
    private fun schema() = trust(TrustSchemaVersion.parse("1.0"))
    private fun time(value: Long) = envelope(MonotonicTimestampMs.create(value))
    private fun <T> model(result: AuthorizationGateModelResult<T>) = (result as AuthorizationGateModelResult.Success).value
    private fun <T> trust(result: TrustDomainResult<T>) = (result as TrustDomainResult.Success).value
    private fun <T> envelope(result: CapabilityEnvelopeResult<T>) = (result as CapabilityEnvelopeResult.Success).value
    private fun <T> embodiment(result: EmbodimentResult<T>) = (result as EmbodimentResult.Success).value
    private fun <T> providerIdentity(result: io.redi.robot.capability.provider.ProviderIdentityResult<T>) =
        (result as io.redi.robot.capability.provider.ProviderIdentityResult.Success).value

    private class AtomicApprovalConsumption : ApprovalConsumptionPort {
        private val receipts = mutableMapOf<ApprovalId, ApprovalConsumptionReceipt>()
        @Synchronized
        override fun consumeOnce(attempt: ApprovalConsumptionAttempt): ApprovalConsumptionResult {
            receipts[attempt.approvalId]?.let { return ApprovalConsumptionResult.AlreadyConsumed(it) }
            val receipt = ApprovalConsumptionReceipt(
                attempt.approvalId, attempt.authorizationReference, attempt.requestId, attempt.decisionId,
                attempt.consumedAt, model(AuthorizationEvidenceReference.parse("approval.fl007.consumed")),
            )
            receipts[attempt.approvalId] = receipt
            return ApprovalConsumptionResult.Consumed(receipt)
        }

        private fun <T> model(result: AuthorizationGateModelResult<T>) =
            (result as AuthorizationGateModelResult.Success).value
    }

    private companion object {
        val approvalRequired = setOf(
            CapabilityApprovalRequirement.EVERY_REQUEST,
            CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION,
            CapabilityApprovalRequirement.SESSION_CONFIRMATION,
        )
    }
}
