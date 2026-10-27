package io.redi.robot.trust.runtime

import io.redi.robot.adapter.compatibility.CompatibilityEvidenceReference
import io.redi.robot.adapter.compatibility.CompatibilityInputResult
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
import io.redi.robot.capability.negotiation.CapabilityNegotiationEvidence
import io.redi.robot.capability.negotiation.CapabilityNegotiationEvidenceSource
import io.redi.robot.capability.negotiation.CapabilityNegotiationId
import io.redi.robot.capability.negotiation.CapabilityNegotiationModelResult
import io.redi.robot.capability.negotiation.CapabilityNegotiationReason
import io.redi.robot.capability.negotiation.CapabilityNegotiationReport
import io.redi.robot.capability.negotiation.CapabilityNegotiationStatus
import io.redi.robot.capability.negotiation.NegotiatedCapabilityRecord
import io.redi.robot.capability.provider.CapabilityProviderPortResult
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.connection.api.ConnectionResult
import io.redi.robot.connection.api.ConnectionSessionId
import io.redi.robot.context.provider.ActiveBodyActivationMode
import io.redi.robot.context.provider.ActiveBodySelection
import io.redi.robot.context.provider.ActiveBodyState
import io.redi.robot.context.provider.BodyAvailability
import io.redi.robot.context.provider.BodyEmbodimentSnapshot
import io.redi.robot.context.provider.BodyProviderReference
import io.redi.robot.context.provider.EmbodimentConfidence
import io.redi.robot.context.provider.EmbodimentEvidence
import io.redi.robot.context.provider.EmbodimentEvidenceId
import io.redi.robot.context.provider.EmbodimentEvidenceKind
import io.redi.robot.context.provider.EmbodimentEvidenceProvenance
import io.redi.robot.context.provider.EmbodimentGeneration
import io.redi.robot.context.provider.EmbodimentHost
import io.redi.robot.context.provider.EmbodimentHostReference
import io.redi.robot.context.provider.EmbodimentResult
import io.redi.robot.context.provider.MountingState
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
import io.redi.robot.trust.domain.PermissionScopeKey
import io.redi.robot.trust.domain.PermissionScopeValue
import io.redi.robot.trust.domain.PermissionValidityWindow
import io.redi.robot.trust.domain.ProviderTrustRecord
import io.redi.robot.trust.domain.ProviderTrustState
import io.redi.robot.trust.domain.RevocationId
import io.redi.robot.trust.domain.RevocationRecord
import io.redi.robot.trust.domain.RevocationTarget
import io.redi.robot.trust.domain.TrustDomainResult
import io.redi.robot.trust.domain.TrustRecordId
import io.redi.robot.trust.domain.TrustSchemaVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AuthorizationGateTest {
    @Test
    fun `valid observe act and modify requests require exact H014 intent and authorize`() {
        val cases = listOf(
            CapabilityEffect.OBSERVE to CapabilityPermissionIntent.OBSERVE,
            CapabilityEffect.ACT to CapabilityPermissionIntent.ACT,
            CapabilityEffect.MODIFY to CapabilityPermissionIntent.MODIFY,
        )

        cases.forEach { (effect, intent) ->
            val fixture = Fixture(effect = effect)
            assertEquals(intent, fixture.descriptor.permissionIntent)
            val decision = fixture.gate.authorize(fixture.input())
            assertEquals(AuthorizationDecisionStatus.AUTHORIZED, decision.status)
            assertEquals(AuthorizationDecisionReason.AUTHORITY_VALID, decision.reason)
            assertEquals(fixture.authorizationReference, decision.authorizationReference)
        }
    }

    @Test
    fun `observe cannot perform act and act cannot perform modify`() {
        val act = Fixture(effect = CapabilityEffect.ACT)
        val observeGrant = act.grant(overrideIntent = CapabilityPermissionIntent.OBSERVE)
        assertDecision(
            act.gate.authorize(act.input(grant = observeGrant)),
            AuthorizationDecisionStatus.PERMISSION_REQUIRED,
            AuthorizationDecisionReason.WRONG_PERMISSION_INTENT,
        )

        val modify = Fixture(effect = CapabilityEffect.MODIFY)
        val actGrant = modify.grant(overrideIntent = CapabilityPermissionIntent.ACT)
        assertDecision(
            modify.gate.authorize(modify.input(grant = actGrant)),
            AuthorizationDecisionStatus.PERMISSION_REQUIRED,
            AuthorizationDecisionReason.WRONG_PERMISSION_INTENT,
        )
    }

    @Test
    fun `missing expired and revoked trust fail closed`() {
        val fixture = Fixture()
        assertDecision(
            fixture.gate.authorize(fixture.input(trust = null)),
            AuthorizationDecisionStatus.TRUST_REQUIRED,
            AuthorizationDecisionReason.TRUST_MISSING,
        )
        val expired = fixture.trust(validity = fixture.validity(0, 20))
        assertDecision(
            fixture.gate.authorize(fixture.input(trust = expired)),
            AuthorizationDecisionStatus.EXPIRED,
            AuthorizationDecisionReason.TRUST_EXPIRED,
        )
        val revocation = fixture.revocation(RevocationTarget.Trust(fixture.trustRecord.id), 10)
        assertDecision(
            fixture.gate.authorize(fixture.input(revocations = listOf(revocation))),
            AuthorizationDecisionStatus.REVOKED,
            AuthorizationDecisionReason.TRUST_REVOKED,
        )
    }

    @Test
    fun `missing expired and revoked grants fail closed and reference alone is not authority`() {
        val fixture = Fixture()
        assertNotNull(fixture.request().authorizationReference)
        assertDecision(
            fixture.gate.authorize(fixture.input(grant = null)),
            AuthorizationDecisionStatus.PERMISSION_REQUIRED,
            AuthorizationDecisionReason.GRANT_MISSING,
        )
        val expired = fixture.grant(validity = fixture.validity(0, 20))
        assertDecision(
            fixture.gate.authorize(fixture.input(grant = expired)),
            AuthorizationDecisionStatus.EXPIRED,
            AuthorizationDecisionReason.GRANT_EXPIRED,
        )
        val revocation = fixture.revocation(
            RevocationTarget.Grant(fixture.baseGrant.authorizationReference),
            10,
        )
        assertDecision(
            fixture.gate.authorize(fixture.input(revocations = listOf(revocation))),
            AuthorizationDecisionStatus.REVOKED,
            AuthorizationDecisionReason.GRANT_REVOKED,
        )
    }

    @Test
    fun `wrong actor provider capability and resource scope are distinct denials`() {
        val fixture = Fixture()
        val otherActor = fixture.actor(CapabilityActorKind.HUGO_RUNTIME)
        assertDecision(
            fixture.gate.authorize(fixture.input(grant = fixture.grant(actor = otherActor))),
            AuthorizationDecisionStatus.ACTOR_MISMATCH,
            AuthorizationDecisionReason.WRONG_ACTOR,
        )
        assertDecision(
            fixture.gate.authorize(fixture.input(grant = fixture.grant(provider = fixture.providerB.instanceId))),
            AuthorizationDecisionStatus.PROVIDER_MISMATCH,
            AuthorizationDecisionReason.WRONG_PROVIDER,
        )
        val otherDescriptor = fixture.descriptor("test.other@1.0", CapabilityEffect.OBSERVE)
        assertDecision(
            fixture.gate.authorize(fixture.input(grant = fixture.grant(descriptor = otherDescriptor))),
            AuthorizationDecisionStatus.CAPABILITY_MISMATCH,
            AuthorizationDecisionReason.WRONG_CAPABILITY,
        )

        val label = fixture.scopeKey("mail.label")
        val granted = fixture.scopeValue("inbox")
        val requested = fixture.scopeValue("trash")
        val scopedGrant = fixture.grant(scope = mapOf(label to setOf(granted)))
        assertDecision(
            fixture.gate.authorize(
                fixture.input(
                    grant = scopedGrant,
                    requestedScope = mapOf(label to setOf(requested)),
                ),
            ),
            AuthorizationDecisionStatus.SCOPE_MISMATCH,
            AuthorizationDecisionReason.SCOPE_NOT_GRANTED,
        )
    }

    @Test
    fun `request must target the exact current provider instance`() {
        val fixture = Fixture()
        assertDecision(
            fixture.gate.authorize(
                fixture.input(request = fixture.request(target = CapabilityProviderTarget.AnyCompatible)),
            ),
            AuthorizationDecisionStatus.PROVIDER_MISMATCH,
            AuthorizationDecisionReason.REQUEST_REQUIRES_EXACT_PROVIDER,
        )
        assertDecision(
            fixture.gate.authorize(
                fixture.input(
                    request = fixture.request(
                        target = CapabilityProviderTarget.Exact(fixture.providerB.instanceId),
                    ),
                ),
            ),
            AuthorizationDecisionStatus.PROVIDER_MISMATCH,
            AuthorizationDecisionReason.REQUEST_PROVIDER_MISMATCH,
        )
    }

    @Test
    fun `host role and local origin restrictions are enforced`() {
        val fixture = Fixture()
        val homeOnly = fixture.grant(
            restrictions = fixture.restrictions(hosts = setOf(HugoHostRole.HOME_CORE)),
        )
        assertDecision(
            fixture.gate.authorize(fixture.input(grant = homeOnly)),
            AuthorizationDecisionStatus.HOST_RESTRICTED,
            AuthorizationDecisionReason.WRONG_HOST,
        )

        val remoteRuntime = fixture.runtime(
            hostContext = AuthorizationHostContext(fixture.host, ExecutionOrigin.REMOTE, null),
        )
        assertDecision(
            fixture.gate.authorize(fixture.input(runtime = remoteRuntime)),
            AuthorizationDecisionStatus.HOST_RESTRICTED,
            AuthorizationDecisionReason.REMOTE_EXECUTION_NOT_ALLOWED,
        )
    }

    @Test
    fun `physical presence required fails for missing false and stale evidence`() {
        val fixture = Fixture()
        val restricted = fixture.grant(
            restrictions = fixture.restrictions(requiresPhysicalPresence = true),
        )
        listOf(
            fixture.runtime(physicalPresence = null),
            fixture.runtime(physicalPresence = fixture.presence(false, 10, 60)),
            fixture.runtime(physicalPresence = fixture.presence(true, 0, 20)),
        ).forEach { runtime ->
            assertDecision(
                fixture.gate.authorize(fixture.input(grant = restricted, runtime = runtime)),
                AuthorizationDecisionStatus.PRESENCE_REQUIRED,
                AuthorizationDecisionReason.PHYSICAL_PRESENCE_REQUIRED,
            )
        }
    }

    @Test
    fun `mounted proximity and active body restrictions require fresh H016 evidence`() {
        val fixture = Fixture()
        val cases = listOf(
            fixture.restrictions(requiresMountedHost = true) to
                (AuthorizationDecisionStatus.MOUNTED_HOST_REQUIRED to AuthorizationDecisionReason.MOUNTED_HOST_REQUIRED),
            fixture.restrictions(requiresNearbyProvider = true) to
                (AuthorizationDecisionStatus.PROXIMITY_REQUIRED to AuthorizationDecisionReason.NEARBY_PROVIDER_REQUIRED),
            fixture.restrictions(requiresActiveBody = true) to
                (AuthorizationDecisionStatus.ACTIVE_BODY_REQUIRED to AuthorizationDecisionReason.ACTIVE_BODY_REQUIRED),
        )
        cases.forEach { (restrictions, expected) ->
            val decision = fixture.gate.authorize(
                fixture.input(grant = fixture.grant(restrictions = restrictions)),
            )
            assertDecision(decision, expected.first, expected.second)
        }
    }

    @Test
    fun `fresh presence mounting proximity and active body evidence satisfy contextual restrictions`() {
        val fixture = Fixture()
        val restrictions = fixture.restrictions(
            requiresPhysicalPresence = true,
            requiresMountedHost = true,
            requiresNearbyProvider = true,
            requiresActiveBody = true,
        )
        val runtime = fixture.runtime(
            physicalPresence = fixture.presence(true, 10, 60),
            embodiment = fixture.embodiment(mounted = true, nearby = true, active = true),
        )
        assertEquals(
            AuthorizationDecisionStatus.AUTHORIZED,
            fixture.gate.authorize(fixture.input(grant = fixture.grant(restrictions = restrictions), runtime = runtime)).status,
        )
    }

    @Test
    fun `session and one-time grant lifetimes require exact bindings`() {
        val fixture = Fixture()
        val session = fixture.authoritySession("77777777-7777-4777-8777-777777777777")
        val wrongSession = fixture.authoritySession("88888888-8888-4888-8888-888888888888")
        val sessionGrant = fixture.grant(
            lifetime = PermissionGrantLifetime.SESSION,
            sessionId = session,
        )
        val runtime = fixture.runtime(
            hostContext = AuthorizationHostContext(fixture.host, ExecutionOrigin.LOCAL, wrongSession),
        )
        assertDecision(
            fixture.gate.authorize(fixture.input(grant = sessionGrant, runtime = runtime)),
            AuthorizationDecisionStatus.SESSION_MISMATCH,
            AuthorizationDecisionReason.WRONG_AUTHORITY_SESSION,
        )

        val oneTime = fixture.grant(
            lifetime = PermissionGrantLifetime.ONE_TIME,
            oneTimeRequestId = fixture.requestId("99999999-9999-4999-8999-999999999999"),
        )
        assertDecision(
            fixture.gate.authorize(fixture.input(grant = oneTime)),
            AuthorizationDecisionStatus.SCOPE_MISMATCH,
            AuthorizationDecisionReason.WRONG_ONE_TIME_REQUEST,
        )
    }

    @Test
    fun `one-time approval succeeds once and replay is rejected atomically`() {
        val store = AtomicApprovalStore()
        val fixture = Fixture(
            descriptorApproval = CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION,
            store = store,
        )
        val approval = fixture.approval(
            fixture.baseGrant,
            CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION,
            fixture.requestId,
        )
        val input = fixture.input(approval = approval)
        val first = fixture.gate.authorize(input)
        assertDecision(first, AuthorizationDecisionStatus.AUTHORIZED, AuthorizationDecisionReason.APPROVAL_CONSUMED)
        assertNotNull(first.audit.approvalConsumption)
        val replay = fixture.gate.authorize(input)
        assertDecision(replay, AuthorizationDecisionStatus.REPLAY_REJECTED, AuthorizationDecisionReason.APPROVAL_REPLAYED)
    }

    @Test
    fun `concurrent one-time approval consumption authorizes exactly one request`() {
        val fixture = Fixture(
            descriptorApproval = CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION,
            store = AtomicApprovalStore(),
        )
        val approval = fixture.approval(
            fixture.baseGrant,
            CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION,
            fixture.requestId,
        )
        val input = fixture.input(approval = approval)
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (1..32).map {
                executor.submit<AuthorizationDecision> {
                    start.await()
                    fixture.gate.authorize(input)
                }
            }
            start.countDown()
            val decisions = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, decisions.count { it.status == AuthorizationDecisionStatus.AUTHORIZED })
            assertEquals(31, decisions.count { it.status == AuthorizationDecisionStatus.REPLAY_REJECTED })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `every-request confirmation is required and its approval cannot be reused`() {
        val fixture = Fixture(
            descriptorApproval = CapabilityApprovalRequirement.EVERY_REQUEST,
            store = AtomicApprovalStore(),
        )
        assertDecision(
            fixture.gate.authorize(fixture.input()),
            AuthorizationDecisionStatus.APPROVAL_REQUIRED,
            AuthorizationDecisionReason.APPROVAL_REQUIRED,
        )
        val approval = fixture.approval(
            fixture.baseGrant,
            CapabilityApprovalRequirement.EVERY_REQUEST,
            fixture.requestId,
        )
        val input = fixture.input(approval = approval)
        assertEquals(AuthorizationDecisionStatus.AUTHORIZED, fixture.gate.authorize(input).status)
        assertEquals(AuthorizationDecisionStatus.REPLAY_REJECTED, fixture.gate.authorize(input).status)
    }

    @Test
    fun `expired revoked denied and consumed approvals fail closed`() {
        val fixture = Fixture(descriptorApproval = CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION)
        val approved = fixture.approval(
            fixture.baseGrant,
            CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION,
            fixture.requestId,
            validity = fixture.validity(0, 20),
        )
        assertDecision(
            fixture.gate.authorize(fixture.input(approval = approved)),
            AuthorizationDecisionStatus.EXPIRED,
            AuthorizationDecisionReason.APPROVAL_EXPIRED,
        )

        val live = fixture.approval(
            fixture.baseGrant,
            CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION,
            fixture.requestId,
        )
        assertDecision(
            fixture.gate.authorize(
                fixture.input(
                    approval = live,
                    revocations = listOf(fixture.revocation(RevocationTarget.Approval(live.id), 10)),
                ),
            ),
            AuthorizationDecisionStatus.REVOKED,
            AuthorizationDecisionReason.APPROVAL_REVOKED,
        )
        val denied = fixture.approval(
            fixture.baseGrant,
            CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION,
            fixture.requestId,
            state = ApprovalState.DENIED,
        )
        assertDecision(
            fixture.gate.authorize(fixture.input(approval = denied)),
            AuthorizationDecisionStatus.APPROVAL_REQUIRED,
            AuthorizationDecisionReason.APPROVAL_DENIED,
        )
        val consumed = fixture.approval(
            fixture.baseGrant,
            CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION,
            fixture.requestId,
            state = ApprovalState.CONSUMED,
        )
        assertDecision(
            fixture.gate.authorize(fixture.input(approval = consumed)),
            AuthorizationDecisionStatus.REPLAY_REJECTED,
            AuthorizationDecisionReason.APPROVAL_REPLAYED,
        )
    }

    @Test
    fun `prohibited capability is never authorized`() {
        val fixture = Fixture(descriptorApproval = CapabilityApprovalRequirement.PROHIBITED)
        assertDecision(
            fixture.gate.authorize(fixture.input()),
            AuthorizationDecisionStatus.PROHIBITED,
            AuthorizationDecisionReason.PROHIBITED,
        )
    }

    @Test
    fun `stale unsuccessful and missing H029 negotiations fail closed`() {
        val fixture = Fixture()
        assertDecision(
            fixture.gate.authorize(fixture.input(report = fixture.report(validUntil = 20))),
            AuthorizationDecisionStatus.STALE_NEGOTIATION,
            AuthorizationDecisionReason.NEGOTIATION_EXPIRED,
        )
        assertDecision(
            fixture.gate.authorize(
                fixture.input(report = fixture.report(status = CapabilityNegotiationStatus.RUNTIME_UNAVAILABLE)),
            ),
            AuthorizationDecisionStatus.STALE_NEGOTIATION,
            AuthorizationDecisionReason.NEGOTIATION_NOT_SUCCESSFUL,
        )
        assertDecision(
            fixture.gate.authorize(fixture.input(report = fixture.report(records = emptyList()))),
            AuthorizationDecisionStatus.CAPABILITY_MISMATCH,
            AuthorizationDecisionReason.NEGOTIATED_CAPABILITY_MISSING,
        )
    }

    @Test
    fun `wrong provider generation and connection session invalidate negotiation`() {
        val fixture = Fixture(withConnection = true)
        val wrongGeneration = fixture.runtime(generation = fixture.generation(8))
        assertDecision(
            fixture.gate.authorize(fixture.input(runtime = wrongGeneration)),
            AuthorizationDecisionStatus.STALE_NEGOTIATION,
            AuthorizationDecisionReason.NEGOTIATION_GENERATION_STALE,
        )
        val wrongSession = fixture.runtime(
            sessions = listOf(fixture.session("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")),
        )
        assertDecision(
            fixture.gate.authorize(fixture.input(runtime = wrongSession)),
            AuthorizationDecisionStatus.STALE_NEGOTIATION,
            AuthorizationDecisionReason.NEGOTIATION_SESSION_STALE,
        )
    }

    @Test
    fun `authorization reference is correlation only and a mismatched value cannot authorize`() {
        val fixture = Fixture()
        val otherReference = fixture.authorizationReference("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        val request = fixture.request(authorizationReference = otherReference)
        assertDecision(
            fixture.gate.authorize(fixture.input(request = request)),
            AuthorizationDecisionStatus.PERMISSION_REQUIRED,
            AuthorizationDecisionReason.AUTHORIZATION_REFERENCE_MISMATCH,
        )
    }

    @Test
    fun `successful authorization grants no safety feasibility dispatch or success authority`() {
        val fixture = Fixture(effect = CapabilityEffect.ACT)
        val decision = fixture.gate.authorize(fixture.input())
        assertEquals(AuthorizationDecisionStatus.AUTHORIZED, decision.status)
        assertFalse(decision.grantsSafetyAuthority)
        assertFalse(decision.provesPhysicalFeasibility)
        assertFalse(decision.dispatchesCapability)
        assertFalse(decision.recordsExecutionSuccess)
        assertNull(decision.audit.approvalConsumption)
    }

    @Test
    fun `equivalent inputs produce deterministic canonically ordered audit evidence`() {
        val fixture = Fixture()
        val futureA = fixture.revocation(RevocationTarget.Grant(fixture.baseGrant.authorizationReference), 90, 1)
        val futureB = fixture.revocation(RevocationTarget.Trust(fixture.trustRecord.id), 90, 2)
        val inputA = fixture.input(
            revocations = listOf(futureB, futureA),
            policies = listOf(fixture.policy("policy.runtime.default"), fixture.policy("policy.security.base")),
        )
        val inputB = fixture.input(
            revocations = listOf(futureA, futureB),
            policies = listOf(fixture.policy("policy.security.base"), fixture.policy("policy.runtime.default")),
        )
        val first = fixture.gate.authorize(inputA)
        val second = fixture.gate.authorize(inputB)
        assertEquals(first.toString(), second.toString())
        assertEquals(first.audit.policyReferences, second.audit.policyReferences)
        assertEquals(first.audit.evidence, second.audit.evidence)
        assertEquals(first.audit.checks, second.audit.checks)
        assertEquals(
            first.audit.checks.sortedWith(compareBy({ it.dimension.ordinal }, { it.checkId })),
            first.audit.checks,
        )
    }

    @Test
    fun `invalid bounded model inputs fail before runtime authority evaluation`() {
        val fixture = Fixture()
        assertEquals(
            AuthorizationGateModelResult.Failure(AuthorizationGateModelError.INVALID_POLICY_REFERENCES),
            AuthorizationGateInput.create(
                fixture.decisionId,
                fixture.request(),
                fixture.localActor,
                fixture.report(),
                fixture.runtime(),
                fixture.authority(),
                emptyMap(),
                emptyList(),
                fixture.now,
            ),
        )
        val excessiveRevocations = List(65) { index ->
            fixture.revocation(RevocationTarget.Trust(fixture.trustRecord.id), 90, index + 10)
        }
        assertEquals(
            AuthorizationGateModelResult.Failure(AuthorizationGateModelError.TOO_MANY_REVOCATIONS),
            AuthorizationAuthorityState.create(fixture.trustRecord, fixture.baseGrant, null, excessiveRevocations),
        )
    }

    @Test
    fun `public runtime boundary has no vendor transport credential AI or LOOI coupling`() {
        val exposed = AuthorizationGate::class.java.declaredMethods.flatMap {
            it.parameterTypes.toList() + it.returnType
        } + AuthorizationDecision::class.java + AuthorizationGateInput::class.java
        val names = exposed.map { it.name.lowercase() }
        assertFalse(names.any { "android" in it || "bluetooth" in it || "gatt" in it })
        assertFalse(names.any { "credential" in it || "token" in it || "looi" in it })
        assertFalse(names.any { "planner" in it || "ai." in it || "safety" in it })
    }

    private fun assertDecision(
        decision: AuthorizationDecision,
        status: AuthorizationDecisionStatus,
        reason: AuthorizationDecisionReason,
    ) {
        assertEquals(status, decision.status)
        assertEquals(reason, decision.reason)
        if (status != AuthorizationDecisionStatus.AUTHORIZED) {
            assertNull(decision.authorizationReference)
        }
    }

    private class AtomicApprovalStore : ApprovalConsumptionPort {
        private val receipts = mutableMapOf<ApprovalId, ApprovalConsumptionReceipt>()
        private val bindings = mutableMapOf<ApprovalId, CapabilityAuthorizationReference>()

        @Synchronized
        override fun consumeOnce(attempt: ApprovalConsumptionAttempt): ApprovalConsumptionResult {
            val prior = receipts[attempt.approvalId]
            if (prior != null) {
                return if (bindings[attempt.approvalId] == attempt.authorizationReference) {
                    ApprovalConsumptionResult.AlreadyConsumed(prior)
                } else {
                    ApprovalConsumptionResult.BindingConflict
                }
            }
            val receipt = ApprovalConsumptionReceipt(
                attempt.approvalId,
                attempt.authorizationReference,
                attempt.requestId,
                attempt.decisionId,
                attempt.consumedAt,
                modelSuccess(AuthorizationEvidenceReference.parse("approval.consumed.runtime")),
            )
            receipts[attempt.approvalId] = receipt
            bindings[attempt.approvalId] = attempt.authorizationReference
            return ApprovalConsumptionResult.Consumed(receipt)
        }
    }

    private class Fixture(
        val effect: CapabilityEffect = CapabilityEffect.OBSERVE,
        descriptorApproval: CapabilityApprovalRequirement = CapabilityApprovalRequirement.WITHIN_GRANT,
        private val withConnection: Boolean = false,
        store: ApprovalConsumptionPort = AtomicApprovalStore(),
    ) {
        val now = timestamp(20)
        val providerA = providerIdentity("33333333-3333-4333-8333-333333333333", "Test body A")
        val providerB = providerIdentity("44444444-4444-4444-8444-444444444444", "Test body B")
        val localActor = actor(CapabilityActorKind.LOCAL_USER)
        val descriptor = descriptor("test.capability@1.0", effect, descriptorApproval)
        val authorizationReference = authorizationReference("55555555-5555-4555-8555-555555555555")
        val requestId = requestId("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
        val decisionId = modelSuccess(
            AuthorizationDecisionId.parse("66666666-6666-4666-8666-666666666666"),
        )
        val providerGeneration = generation(7)
        val connectionSession = session("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        val host = EmbodimentHost(
            embodimentSuccess(EmbodimentHostReference.parse("host.mobile.primary")),
            HugoHostRole.MOBILE_HOST,
        )
        val trustRecord = trust()
        val baseGrant = grant()
        val gate = AuthorizationGate(store)

        fun input(
            request: CapabilityRequest = request(),
            report: CapabilityNegotiationReport = report(),
            runtime: AuthorizationRuntimeContext = runtime(),
            trust: ProviderTrustRecord? = trustRecord,
            grant: PermissionGrant? = baseGrant,
            approval: ApprovalRecord? = null,
            revocations: List<RevocationRecord> = emptyList(),
            requestedScope: Map<PermissionScopeKey, Set<PermissionScopeValue>> = emptyMap(),
            policies: List<AuthorizationPolicyReference> = listOf(policy("policy.runtime.default")),
        ): AuthorizationGateInput = modelSuccess(
            AuthorizationGateInput.create(
                decisionId,
                request,
                localActor,
                report,
                runtime,
                authority(trust, grant, approval, revocations),
                requestedScope,
                policies,
                now,
            ),
        )

        fun authority(
            trust: ProviderTrustRecord? = trustRecord,
            grant: PermissionGrant? = baseGrant,
            approval: ApprovalRecord? = null,
            revocations: List<RevocationRecord> = emptyList(),
        ) = modelSuccess(AuthorizationAuthorityState.create(trust, grant, approval, revocations))

        fun runtime(
            identity: ProviderIdentity = providerA,
            generation: ProviderGeneration = providerGeneration,
            sessions: List<ConnectionSessionId> = if (withConnection) listOf(connectionSession) else emptyList(),
            hostContext: AuthorizationHostContext = AuthorizationHostContext(host, ExecutionOrigin.LOCAL, null),
            physicalPresence: PhysicalPresenceEvidence? = presence(true, 10, 60),
            embodiment: ActiveBodyState? = null,
        ) = modelSuccess(
            AuthorizationRuntimeContext.create(
                identity,
                generation,
                sessions,
                hostContext,
                physicalPresence,
                embodiment,
            ),
        )

        fun report(
            status: CapabilityNegotiationStatus = CapabilityNegotiationStatus.NEGOTIATED,
            provider: ProviderInstanceId = providerA.instanceId,
            generation: ProviderGeneration = providerGeneration,
            validUntil: Long = 60,
            records: List<NegotiatedCapabilityRecord>? = null,
        ): CapabilityNegotiationReport {
            val negotiationId = negotiationSuccess(
                CapabilityNegotiationId.parse("12121212-1212-4212-8212-121212121212"),
            )
            val defaultRecord = NegotiatedCapabilityRecord(
                capability = descriptor.reference,
                descriptor = descriptor,
                providerInstanceId = provider,
                providerGeneration = generation,
                negotiationId = negotiationId,
                connectionSessionIds = if (withConnection) listOf(connectionSession) else emptyList(),
                inputContract = descriptor.inputContract,
                outputContract = descriptor.outputContract,
                runtimeAvailability = ProviderRuntimeAvailability.AVAILABLE,
                limits = descriptor.limits,
                negotiatedAt = timestamp(10),
                validUntil = timestamp(validUntil),
                provenance = listOf(
                    CapabilityNegotiationEvidence(
                        CapabilityNegotiationEvidenceSource.PROVIDER_NEGOTIATION,
                        compatibilityEvidence("runtime.negotiation.evidence"),
                    ),
                ),
            )
            return CapabilityNegotiationReport(
                negotiationId = negotiationId,
                providerInstanceId = provider,
                providerGeneration = generation,
                status = status,
                reason = if (status == CapabilityNegotiationStatus.NEGOTIATED) {
                    CapabilityNegotiationReason.ALL_DECLARED_CAPABILITIES_NEGOTIATED
                } else CapabilityNegotiationReason.RUNTIME_EVIDENCE_UNAVAILABLE,
                manifestCandidateId = null,
                negotiatedCapabilities = records ?: listOf(defaultRecord),
                checks = emptyList(),
            )
        }

        fun request(
            descriptor: CapabilityDescriptor = this.descriptor,
            target: CapabilityProviderTarget = CapabilityProviderTarget.Exact(providerA.instanceId),
            actorKind: CapabilityActorKind = localActor.kind,
            authorizationReference: CapabilityAuthorizationReference? = this.authorizationReference,
            requestId: CapabilityRequestId = this.requestId,
        ) = envelopeSuccess(
            CapabilityRequest.create(
                requestId,
                descriptor.reference,
                target,
                actorKind,
                envelopeSuccess(CapabilityExecutionContext.create(1, 1)),
                timestamp(10),
                timestamp(60),
                authorizationReference,
                auditId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                input = null,
            ),
        )

        fun trust(
            provider: ProviderInstanceId = providerA.instanceId,
            state: ProviderTrustState = ProviderTrustState.TRUSTED,
            validity: PermissionValidityWindow = validity(0, 100),
        ) = trustSuccess(
            ProviderTrustRecord.create(
                schemaVersion(),
                trustId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                provider,
                state,
                validity,
                provenance(),
                auditId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            ),
        )

        fun grant(
            descriptor: CapabilityDescriptor = this.descriptor,
            actor: AuthorityActor = localActor,
            provider: ProviderInstanceId = providerA.instanceId,
            overrideIntent: CapabilityPermissionIntent = descriptor.permissionIntent,
            approvalRequirement: CapabilityApprovalRequirement = CapabilityApprovalRequirement.WITHIN_GRANT,
            validity: PermissionValidityWindow = validity(0, 100),
            restrictions: PermissionRestrictions = restrictions(),
            scope: Map<PermissionScopeKey, Set<PermissionScopeValue>> = emptyMap(),
            lifetime: PermissionGrantLifetime = PermissionGrantLifetime.PERSISTENT,
            sessionId: AuthoritySessionId? = null,
            oneTimeRequestId: CapabilityRequestId? = null,
        ): PermissionGrant {
            val permissionScope = trustSuccess(
                PermissionScope.create(actor, provider, descriptor.reference, overrideIntent, scope),
            )
            return trustSuccess(
                PermissionGrant.create(
                    schemaVersion(),
                    authorizationReference,
                    permissionScope,
                    lifetime,
                    sessionId,
                    oneTimeRequestId,
                    approvalRequirement,
                    validity,
                    restrictions,
                    provenance(),
                    auditId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                ),
            )
        }

        fun approval(
            grant: PermissionGrant,
            requirement: CapabilityApprovalRequirement,
            requestId: CapabilityRequestId,
            state: ApprovalState = ApprovalState.APPROVED,
            validity: PermissionValidityWindow = validity(0, 100),
        ) = trustSuccess(
            ApprovalRecord.create(
                schemaVersion(),
                approvalId("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                grant.authorizationReference,
                localActor,
                providerA.instanceId,
                descriptor.reference,
                requirement,
                requestId,
                sessionId = null,
                state,
                validity,
                provenance(),
                auditId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            ),
        )

        fun restrictions(
            hosts: Set<HugoHostRole> = setOf(HugoHostRole.MOBILE_HOST),
            locality: ExecutionLocality = ExecutionLocality.LOCAL_ONLY,
            requiresPhysicalPresence: Boolean = false,
            requiresMountedHost: Boolean = false,
            requiresNearbyProvider: Boolean = false,
            requiresActiveBody: Boolean = false,
        ) = trustSuccess(
            PermissionRestrictions.create(
                hosts,
                locality,
                requiresPhysicalPresence,
                requiresMountedHost,
                requiresNearbyProvider,
                requiresActiveBody,
            ),
        )

        fun presence(present: Boolean, observedAt: Long, expiresAt: Long) = modelSuccess(
            PhysicalPresenceEvidence.create(
                present,
                timestamp(observedAt),
                timestamp(expiresAt),
                modelSuccess(AuthorizationEvidenceReference.parse("presence.local.evidence")),
            ),
        )

        fun embodiment(mounted: Boolean, nearby: Boolean, active: Boolean): ActiveBodyState {
            val body = embodimentSuccess(BodyProviderReference.create(providerA))
            val evidence = buildList {
                if (mounted) add(embodimentEvidence("mounting.verified", EmbodimentEvidenceKind.MOUNTING_VERIFIED))
                if (nearby) add(embodimentEvidence("proximity.observed", EmbodimentEvidenceKind.PROXIMITY_OBSERVED))
            }
            val snapshot = BodyEmbodimentSnapshot.create(
                body,
                BodyAvailability.AVAILABLE,
                discovered = true,
                nearby = nearby,
                connected = true,
                remotelyControlled = true,
                mounting = if (mounted) MountingState.MOUNTED else MountingState.UNKNOWN,
                evidence = evidence,
            )
            val selection = if (active) {
                embodimentSuccess(
                    ActiveBodySelection.create(
                        body,
                        ActiveBodyActivationMode.MOUNTED,
                        reference = null,
                        selectedAt = timestamp(10),
                        expiresAt = timestamp(60),
                    ),
                )
            } else null
            return ActiveBodyState.create(
                embodimentSuccess(EmbodimentGeneration.create(1)),
                host,
                listOf(snapshot),
                selection,
            )
        }

        private fun embodimentEvidence(id: String, kind: EmbodimentEvidenceKind) = embodimentSuccess(
            EmbodimentEvidence.create(
                embodimentSuccess(EmbodimentEvidenceId.parse(id)),
                kind,
                EmbodimentEvidenceProvenance.HARDWARE_VERIFIED,
                embodimentSuccess(EmbodimentConfidence.create(1000)),
                timestamp(10),
                timestamp(60),
            ),
        )

        fun revocation(target: RevocationTarget, revokedAt: Long, variant: Int = 0) = RevocationRecord(
            schemaVersion(),
            revocationId(String.format("dddddddd-dddd-4ddd-8ddd-%012d", variant)),
            target,
            timestamp(revokedAt),
            provenance(),
            auditId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
        )

        fun descriptor(
            raw: String,
            effect: CapabilityEffect,
            approval: CapabilityApprovalRequirement = CapabilityApprovalRequirement.WITHIN_GRANT,
        ): CapabilityDescriptor = descriptorSuccess(
            CapabilityDescriptor.create(
                capability(raw),
                descriptorSuccess(CapabilityLabel.parse("Test capability")),
                descriptorSuccess(CapabilityDescription.parse("Provider-neutral authorization test capability.")),
                inputContract = null,
                outputContract = null,
                effect = effect,
                risk = if (effect == CapabilityEffect.OBSERVE) {
                    CapabilityRiskClass.INFORMATIONAL
                } else CapabilityRiskClass.ELEVATED,
                safety = if (effect == CapabilityEffect.OBSERVE) {
                    CapabilitySafetyClass.NON_PHYSICAL
                } else CapabilitySafetyClass.PHYSICAL_ACTION,
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
            ),
        )

        fun actor(kind: CapabilityActorKind) = AuthorityActor(
            actorId(if (kind == CapabilityActorKind.LOCAL_USER) {
                "11111111-1111-4111-8111-111111111111"
            } else "22222222-2222-4222-8222-222222222222"),
            kind,
        )

        fun validity(from: Long, until: Long?) = trustSuccess(
            PermissionValidityWindow.create(timestamp(from), until?.let(::timestamp)),
        )

        fun policy(raw: String) = modelSuccess(AuthorizationPolicyReference.parse(raw))
        fun scopeKey(raw: String) = trustSuccess(PermissionScopeKey.parse(raw))
        fun scopeValue(raw: String) = trustSuccess(PermissionScopeValue.parse(raw))
        fun authoritySession(raw: String) = trustSuccess(AuthoritySessionId.parse(raw))
        fun requestId(raw: String) = envelopeSuccess(CapabilityRequestId.parse(raw))
        fun authorizationReference(raw: String) = envelopeSuccess(CapabilityAuthorizationReference.parse(raw))
        fun generation(raw: Long) = providerSuccess(ProviderGeneration.create(raw))
        fun session(raw: String) = connectionSuccess(ConnectionSessionId.parse(raw))

        private fun providerIdentity(instance: String, alias: String) = ProviderIdentity(
            providerDefinition("test/body"),
            providerInstance(instance),
            ProviderFamily.BODY_ADAPTER,
            providerAlias(alias),
        )

        private fun capability(raw: String) = identitySuccess(CapabilityReference.parse(raw))
        private fun schemaVersion() = trustSuccess(TrustSchemaVersion.parse("1.0"))
        private fun provenance() = trustSuccess(AuthorityProvenanceReference.parse("local.user.approval"))
        private fun trustId(raw: String) = trustSuccess(TrustRecordId.parse(raw))
        private fun approvalId(raw: String) = trustSuccess(ApprovalId.parse(raw))
        private fun revocationId(raw: String) = trustSuccess(RevocationId.parse(raw))
        private fun actorId(raw: String) = trustSuccess(AuthorityActorId.parse(raw))
        private fun auditId(raw: String) = envelopeSuccess(CapabilityAuditId.parse(raw))
        private fun timestamp(raw: Long) = envelopeSuccess(MonotonicTimestampMs.create(raw))
        private fun providerDefinition(raw: String) = providerIdentitySuccess(ProviderDefinitionId.parse(raw))
        private fun providerInstance(raw: String) = providerIdentitySuccess(ProviderInstanceId.parse(raw))
        private fun providerAlias(raw: String) = providerIdentitySuccess(RedactedProviderAlias.parse(raw))
        private fun compatibilityEvidence(raw: String) = compatibilitySuccess(CompatibilityEvidenceReference.parse(raw))
    }

    private companion object {
        fun <T> modelSuccess(result: AuthorizationGateModelResult<T>): T = when (result) {
            is AuthorizationGateModelResult.Success -> result.value
            is AuthorizationGateModelResult.Failure -> throw AssertionError(result.error)
        }

        fun <T> trustSuccess(result: TrustDomainResult<T>): T = when (result) {
            is TrustDomainResult.Success -> result.value
            is TrustDomainResult.Failure -> throw AssertionError(result.error)
        }

        fun <T> envelopeSuccess(result: CapabilityEnvelopeResult<T>): T = when (result) {
            is CapabilityEnvelopeResult.Success -> result.value
            is CapabilityEnvelopeResult.Failure -> throw AssertionError(result.error)
        }

        fun <T> descriptorSuccess(result: CapabilityDescriptorResult<T>): T = when (result) {
            is CapabilityDescriptorResult.Success -> result.value
            is CapabilityDescriptorResult.Failure -> throw AssertionError(result.error)
        }

        fun <T> identitySuccess(result: CapabilityIdentityResult<T>): T = when (result) {
            is CapabilityIdentityResult.Success -> result.value
            is CapabilityIdentityResult.Failure -> throw AssertionError(result.error)
        }

        fun <T> providerIdentitySuccess(result: ProviderIdentityResult<T>): T = when (result) {
            is ProviderIdentityResult.Success -> result.value
            is ProviderIdentityResult.Failure -> throw AssertionError(result.error)
        }

        fun <T> providerSuccess(result: CapabilityProviderPortResult<T>): T = when (result) {
            is CapabilityProviderPortResult.Success -> result.value
            is CapabilityProviderPortResult.Failure -> throw AssertionError(result.error)
        }

        fun <T> connectionSuccess(result: ConnectionResult<T>): T = when (result) {
            is ConnectionResult.Success -> result.value
            is ConnectionResult.Failure -> throw AssertionError(result.error)
        }

        fun <T> embodimentSuccess(result: EmbodimentResult<T>): T = when (result) {
            is EmbodimentResult.Success -> result.value
            is EmbodimentResult.Failure -> throw AssertionError(result.error)
        }

        fun <T> compatibilitySuccess(result: CompatibilityInputResult<T>): T = when (result) {
            is CompatibilityInputResult.Success -> result.value
            is CompatibilityInputResult.Failure -> throw AssertionError(result.error)
        }

        fun <T> negotiationSuccess(result: CapabilityNegotiationModelResult<T>): T = when (result) {
            is CapabilityNegotiationModelResult.Success -> result.value
            is CapabilityNegotiationModelResult.Failure -> throw AssertionError(result.error)
        }
    }
}
