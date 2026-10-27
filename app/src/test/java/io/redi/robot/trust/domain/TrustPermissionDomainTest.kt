package io.redi.robot.trust.domain

import io.redi.robot.capability.api.CapabilityActorKind
import io.redi.robot.capability.api.CapabilityApprovalRequirement
import io.redi.robot.capability.api.CapabilityAuditId
import io.redi.robot.capability.api.CapabilityAuthorizationReference
import io.redi.robot.capability.api.CapabilityAvailabilitySemantics
import io.redi.robot.capability.api.CapabilityCancellationSemantics
import io.redi.robot.capability.api.CapabilityDescription
import io.redi.robot.capability.api.CapabilityDescriptor
import io.redi.robot.capability.api.CapabilityDescriptorResult
import io.redi.robot.capability.api.CapabilityEffect
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityExecutionContext
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityLabel
import io.redi.robot.capability.api.CapabilityPermissionIntent
import io.redi.robot.capability.api.CapabilityProviderTarget
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.CapabilityRequest
import io.redi.robot.capability.api.CapabilityRequestId
import io.redi.robot.capability.api.CapabilityRiskClass
import io.redi.robot.capability.api.CapabilitySafetyClass
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.ProviderInstanceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustPermissionDomainTest {
    @Test
    fun validGrantIsDeterministicAndStillRequiresSafetyDownstream() {
        val descriptor = descriptor("environment.status@1.0", CapabilityEffect.OBSERVE)
        val actor = actor()
        val grant = grant(descriptor, actor = actor)
        val input = input(descriptor, actor, grant = grant)

        val first = TrustPermissionEvaluator.evaluate(input)
        val second = TrustPermissionEvaluator.evaluate(input)

        assertEquals(first, second)
        assertEquals(
            TrustPermissionDecision.Permitted(
                grant.authorizationReference,
                approvalToConsume = null,
                deterministicSafetyStillRequired = true,
            ),
            first,
        )
    }

    @Test
    fun invalidScopeAndUnsupportedSchemaVersionFailClosed() {
        val descriptor = descriptor("environment.status@1.0", CapabilityEffect.OBSERVE)
        val emptyConstraint = mapOf(scopeKey("resource.label") to emptySet<PermissionScopeValue>())

        assertEquals(
            TrustDomainResult.Failure(TrustDomainError.InvalidScopeConstraint),
            PermissionScope.create(
                actor(), providerA(), descriptor.reference, CapabilityPermissionIntent.OBSERVE,
                emptyConstraint,
            ),
        )
        assertEquals(
            TrustDomainResult.Failure(TrustDomainError.UnsupportedSchemaVersion("2.0")),
            TrustSchemaVersion.parse("2.0"),
        )

        val allowed = scopeValue("inbox")
        val denied = scopeValue("trash")
        val actor = actor()
        val scopedGrant = grant(
            descriptor,
            actor = actor,
            scopeConstraints = mapOf(scopeKey("mail.label") to setOf(allowed)),
        )
        assertDenied(
            input(
                descriptor,
                actor,
                grant = scopedGrant,
                requestedScope = mapOf(scopeKey("mail.label") to setOf(denied)),
            ),
            TrustPermissionDenial.SCOPE_NOT_GRANTED,
        )
    }

    @Test
    fun expiredAndRevokedGrantsFailClosed() {
        val descriptor = descriptor("environment.status@1.0", CapabilityEffect.OBSERVE)
        val actor = actor()
        val expired = grant(descriptor, actor = actor, validity = validity(0, 5))
        val active = grant(descriptor, actor = actor)
        val revokedInput = input(
            descriptor,
            actor,
            grant = active,
            revocations = listOf(revocation(RevocationTarget.Grant(active.authorizationReference))),
        )

        assertDenied(input(descriptor, actor, grant = expired), TrustPermissionDenial.GRANT_EXPIRED)
        assertDenied(revokedInput, TrustPermissionDenial.GRANT_REVOKED)
    }

    @Test
    fun wrongProviderCapabilityAndActorAreIndependentlyRejected() {
        val requested = descriptor("environment.status@1.0", CapabilityEffect.OBSERVE)
        val other = descriptor("environment.other@1.0", CapabilityEffect.OBSERVE)
        val actor = actor()

        assertDenied(
            input(requested, actor, grant = grant(requested, actor = actor, provider = providerB())),
            TrustPermissionDenial.WRONG_PROVIDER,
        )
        assertDenied(
            input(requested, actor, grant = grant(other, actor = actor)),
            TrustPermissionDenial.WRONG_CAPABILITY,
        )
        assertDenied(
            input(requested, actor, grant = grant(requested, actor = actor(CapabilityActorKind.LOCAL_SYSTEM))),
            TrustPermissionDenial.WRONG_ACTOR,
        )
    }

    @Test
    fun hostAndRemoteRestrictionsArePolicyInputsNotPermissionFacts() {
        val descriptor = descriptor("body.move@1.0", CapabilityEffect.ACT)
        val actor = actor()
        val grant = grant(
            descriptor,
            actor = actor,
            restrictions = restrictions(setOf(HugoHostRole.MOBILE_HOST), ExecutionLocality.LOCAL_ONLY),
        )

        assertDenied(
            input(
                descriptor,
                actor,
                grant = grant,
                policy = policy(hostRole = HugoHostRole.HOME_CORE),
            ),
            TrustPermissionDenial.WRONG_HOST,
        )
        assertDenied(
            input(
                descriptor,
                actor,
                grant = grant,
                policy = policy(executionOrigin = ExecutionOrigin.REMOTE),
            ),
            TrustPermissionDenial.REMOTE_EXECUTION_NOT_ALLOWED,
        )
    }

    @Test
    fun observeCannotSatisfyActAndActCannotSatisfyModify() {
        val actor = actor()
        val act = descriptor("body.move@1.0", CapabilityEffect.ACT)
        val modify = descriptor("adapter.install@1.0", CapabilityEffect.MODIFY)

        assertDenied(
            input(
                act,
                actor,
                grant = grant(act, actor = actor, overrideIntent = CapabilityPermissionIntent.OBSERVE),
            ),
            TrustPermissionDenial.WRONG_PERMISSION_INTENT,
        )
        assertDenied(
            input(
                modify,
                actor,
                grant = grant(modify, actor = actor, overrideIntent = CapabilityPermissionIntent.ACT),
            ),
            TrustPermissionDenial.WRONG_PERMISSION_INTENT,
        )
    }

    @Test
    fun oneTimeApprovalMustMatchAndCannotBeReplayed() {
        val descriptor = descriptor(
            "body.move@1.0",
            CapabilityEffect.ACT,
            CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION,
        )
        val actor = actor()
        val grant = grant(
            descriptor,
            actor = actor,
            approvalRequirement = CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION,
        )
        val request = request(descriptor, actor, grant.authorizationReference)
        val approved = approval(
            descriptor,
            actor,
            grant,
            request.requestId,
            ApprovalState.APPROVED,
        )
        val consumed = approval(
            descriptor,
            actor,
            grant,
            request.requestId,
            ApprovalState.CONSUMED,
        )

        assertEquals(
            TrustPermissionDecision.Permitted(
                grant.authorizationReference,
                approved.id,
                deterministicSafetyStillRequired = true,
            ),
            TrustPermissionEvaluator.evaluate(
                input(descriptor, actor, grant = grant, request = request, approval = approved),
            ),
        )
        assertDenied(
            input(descriptor, actor, grant = grant, request = request, approval = consumed),
            TrustPermissionDenial.APPROVAL_REPLAYED,
        )
    }

    @Test
    fun prohibitedActionAndMissingAuthorizationReferenceAlwaysFail() {
        val prohibited = descriptor(
            "system.policy.modify@1.0",
            CapabilityEffect.MODIFY,
            CapabilityApprovalRequirement.PROHIBITED,
        )
        val actor = actor()
        val prohibitedGrant = grant(prohibited, actor = actor)
        assertDenied(
            input(prohibited, actor, grant = prohibitedGrant),
            TrustPermissionDenial.PROHIBITED,
        )

        val observe = descriptor("environment.status@1.0", CapabilityEffect.OBSERVE)
        val grant = grant(observe, actor = actor)
        assertDenied(
            input(
                observe,
                actor,
                grant = grant,
                request = request(observe, actor, authorizationReference = null),
            ),
            TrustPermissionDenial.AUTHORIZATION_REFERENCE_MISSING,
        )
    }

    @Test
    fun manifestTrustAndConnectionEvidenceAloneCannotAuthorize() {
        val descriptor = descriptor("environment.status@1.0", CapabilityEffect.OBSERVE)
        val actor = actor()
        val evidence = NonAuthorityEvidence(
            providerDiscovered = true,
            manifestCompatible = true,
            connectionEstablished = true,
        )

        assertDenied(
            input(descriptor, actor, grant = null, nonAuthorityEvidence = evidence),
            TrustPermissionDenial.GRANT_MISSING,
        )
    }

    @Test
    fun revokedTrustAndApprovalInvalidateAuthority() {
        val descriptor = descriptor(
            "body.move@1.0",
            CapabilityEffect.ACT,
            CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION,
        )
        val actor = actor()
        val grant = grant(
            descriptor,
            actor = actor,
            approvalRequirement = CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION,
        )
        val request = request(descriptor, actor, grant.authorizationReference)
        val trust = trustRecord()
        val approval = approval(descriptor, actor, grant, request.requestId, ApprovalState.APPROVED)

        assertDenied(
            input(
                descriptor,
                actor,
                grant = grant,
                request = request,
                approval = approval,
                trust = trust,
                revocations = listOf(revocation(RevocationTarget.Trust(trust.id))),
            ),
            TrustPermissionDenial.TRUST_REVOKED,
        )
        assertDenied(
            input(
                descriptor,
                actor,
                grant = grant,
                request = request,
                approval = approval,
                revocations = listOf(revocation(RevocationTarget.Approval(approval.id))),
            ),
            TrustPermissionDenial.APPROVAL_REVOKED,
        )
    }

    @Test
    fun publicDomainTypesContainNoPlatformCredentialsOrVendorRuntime() {
        val exposed = TrustPermissionEvaluator::class.java.methods.flatMap { method ->
            method.parameterTypes.asList() + method.returnType
        } + TrustPermissionEvaluator::class.java

        assertFalse(exposed.any { it.name.contains("android.") || it.name.contains("bluetooth", true) })
        assertFalse(exposed.any { it.name.contains("credential", true) || it.name.contains("looi", true) })
    }

    private fun input(
        descriptor: CapabilityDescriptor,
        actor: AuthorityActor,
        grant: PermissionGrant?,
        request: CapabilityRequest = request(descriptor, actor, grant?.authorizationReference),
        trust: ProviderTrustRecord? = trustRecord(),
        approval: ApprovalRecord? = null,
        policy: AuthorityPolicyInputs = policy(),
        requestedScope: Map<PermissionScopeKey, Set<PermissionScopeValue>> = emptyMap(),
        nonAuthorityEvidence: NonAuthorityEvidence = NonAuthorityEvidence(false, false, false),
        revocations: List<RevocationRecord> = emptyList(),
    ) = TrustPermissionEvaluationInput(
        request,
        actor,
        providerA(),
        descriptor,
        trust,
        grant,
        approval,
        policy,
        requestedScope,
        nonAuthorityEvidence,
        revocations,
        timestamp(10),
    )

    private fun trustRecord() = trustSuccess(ProviderTrustRecord.create(
        schemaVersion(),
        trustId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
        providerA(),
        ProviderTrustState.TRUSTED,
        validity(0, 100),
        provenance(),
        auditId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
    ))

    private fun grant(
        descriptor: CapabilityDescriptor,
        actor: AuthorityActor,
        provider: ProviderInstanceId = providerA(),
        overrideIntent: CapabilityPermissionIntent = descriptor.permissionIntent,
        approvalRequirement: CapabilityApprovalRequirement = CapabilityApprovalRequirement.WITHIN_GRANT,
        validity: PermissionValidityWindow = validity(0, 100),
        restrictions: PermissionRestrictions = restrictions(),
        scopeConstraints: Map<PermissionScopeKey, Set<PermissionScopeValue>> = emptyMap(),
    ): PermissionGrant {
        val scope = trustSuccess(PermissionScope.create(
            actor,
            provider,
            descriptor.reference,
            overrideIntent,
            scopeConstraints,
        ))
        return trustSuccess(PermissionGrant.create(
            schemaVersion(),
            authorizationReference(),
            scope,
            PermissionGrantLifetime.PERSISTENT,
            sessionId = null,
            oneTimeRequestId = null,
            approvalRequirement,
            validity,
            restrictions,
            provenance(),
            auditId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
        ))
    }

    private fun approval(
        descriptor: CapabilityDescriptor,
        actor: AuthorityActor,
        grant: PermissionGrant,
        requestId: CapabilityRequestId,
        state: ApprovalState,
    ) = trustSuccess(ApprovalRecord.create(
        schemaVersion(),
        approvalId("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
        grant.authorizationReference,
        actor,
        providerA(),
        descriptor.reference,
        CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION,
        requestId,
        sessionId = null,
        state,
        validity(0, 100),
        provenance(),
        auditId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
    ))

    private fun revocation(target: RevocationTarget) = RevocationRecord(
        schemaVersion(),
        revocationId("dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
        target,
        timestamp(5),
        provenance(),
        auditId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
    )

    private fun request(
        descriptor: CapabilityDescriptor,
        actor: AuthorityActor,
        authorizationReference: CapabilityAuthorizationReference?,
    ): CapabilityRequest = envelopeSuccess(CapabilityRequest.create(
        requestId("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"),
        descriptor.reference,
        CapabilityProviderTarget.Exact(providerA()),
        actor.kind,
        envelopeSuccess(CapabilityExecutionContext.create(1, 1)),
        timestamp(5),
        timestamp(50),
        authorizationReference,
        auditId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
        input = null,
    ))

    private fun descriptor(
        raw: String,
        effect: CapabilityEffect,
        approval: CapabilityApprovalRequirement = CapabilityApprovalRequirement.WITHIN_GRANT,
    ): CapabilityDescriptor = descriptorSuccess(CapabilityDescriptor.create(
        capability(raw),
        descriptorSuccess(CapabilityLabel.parse("Test capability")),
        descriptorSuccess(CapabilityDescription.parse("Provider-neutral authority test capability.")),
        inputContract = null,
        outputContract = null,
        effect,
        risk = if (effect == CapabilityEffect.OBSERVE) CapabilityRiskClass.INFORMATIONAL else CapabilityRiskClass.ELEVATED,
        safety = if (effect == CapabilityEffect.OBSERVE) CapabilitySafetyClass.NON_PHYSICAL else CapabilitySafetyClass.PHYSICAL_ACTION,
        permissionIntent = when (effect) {
            CapabilityEffect.OBSERVE -> CapabilityPermissionIntent.OBSERVE
            CapabilityEffect.ACT -> CapabilityPermissionIntent.ACT
            CapabilityEffect.MODIFY -> CapabilityPermissionIntent.MODIFY
        },
        approvalRequirement = approval,
        cancellation = if (effect == CapabilityEffect.OBSERVE) {
            CapabilityCancellationSemantics.NOT_APPLICABLE
        } else CapabilityCancellationSemantics.SUPPORTED,
        availability = CapabilityAvailabilitySemantics.WHILE_REGISTERED,
        evidence = CapabilityEvidenceClass.DECLARED,
    ))

    private fun restrictions(
        hosts: Set<HugoHostRole> = setOf(HugoHostRole.MOBILE_HOST),
        locality: ExecutionLocality = ExecutionLocality.LOCAL_ONLY,
    ) = trustSuccess(PermissionRestrictions.create(hosts, locality))

    private fun policy(
        hostRole: HugoHostRole = HugoHostRole.MOBILE_HOST,
        executionOrigin: ExecutionOrigin = ExecutionOrigin.LOCAL,
    ) = AuthorityPolicyInputs(
        hostRole,
        executionOrigin,
        sessionId = null,
        userPhysicallyPresent = true,
        hostMountedOnBody = true,
        providerNearby = true,
        requestedProviderIsActiveBody = true,
    )

    private fun validity(from: Long, until: Long?) = trustSuccess(
        PermissionValidityWindow.create(timestamp(from), until?.let(::timestamp)),
    )

    private fun actor(kind: CapabilityActorKind = CapabilityActorKind.LOCAL_USER) = AuthorityActor(
        actorId(if (kind == CapabilityActorKind.LOCAL_USER) {
            "11111111-1111-4111-8111-111111111111"
        } else "22222222-2222-4222-8222-222222222222"),
        kind,
    )

    private fun providerA() = provider("33333333-3333-4333-8333-333333333333")
    private fun providerB() = provider("44444444-4444-4444-8444-444444444444")

    private fun assertDenied(input: TrustPermissionEvaluationInput, reason: TrustPermissionDenial) {
        assertEquals(TrustPermissionDecision.Denied(reason), TrustPermissionEvaluator.evaluate(input))
    }

    private fun schemaVersion() = trustSuccess(TrustSchemaVersion.parse("1.0"))
    private fun actorId(raw: String) = trustSuccess(AuthorityActorId.parse(raw))
    private fun trustId(raw: String) = trustSuccess(TrustRecordId.parse(raw))
    private fun approvalId(raw: String) = trustSuccess(ApprovalId.parse(raw))
    private fun revocationId(raw: String) = trustSuccess(RevocationId.parse(raw))
    private fun provenance() = trustSuccess(AuthorityProvenanceReference.parse("local.user.approval"))
    private fun scopeKey(raw: String) = trustSuccess(PermissionScopeKey.parse(raw))
    private fun scopeValue(raw: String) = trustSuccess(PermissionScopeValue.parse(raw))

    private fun provider(raw: String): ProviderInstanceId = when (val result = ProviderInstanceId.parse(raw)) {
        is ProviderIdentityResult.Success -> result.value
        is ProviderIdentityResult.Failure -> throw AssertionError(result.error)
    }

    private fun capability(raw: String): CapabilityReference = when (val result = CapabilityReference.parse(raw)) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> throw AssertionError(result.error)
    }

    private fun authorizationReference() = envelopeSuccess(
        CapabilityAuthorizationReference.parse("55555555-5555-4555-8555-555555555555"),
    )

    private fun requestId(raw: String) = envelopeSuccess(CapabilityRequestId.parse(raw))
    private fun auditId(raw: String) = envelopeSuccess(CapabilityAuditId.parse(raw))
    private fun timestamp(raw: Long) = envelopeSuccess(MonotonicTimestampMs.create(raw))

    private fun <T> trustSuccess(result: TrustDomainResult<T>): T = when (result) {
        is TrustDomainResult.Success -> result.value
        is TrustDomainResult.Failure -> throw AssertionError(result.error)
    }

    private fun <T> envelopeSuccess(result: CapabilityEnvelopeResult<T>): T = when (result) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> throw AssertionError(result.error)
    }

    private fun <T> descriptorSuccess(result: CapabilityDescriptorResult<T>): T = when (result) {
        is CapabilityDescriptorResult.Success -> result.value
        is CapabilityDescriptorResult.Failure -> throw AssertionError(result.error)
    }
}
