package io.redi.robot.ai.tools

import io.redi.robot.ai.gateway.GatewayStreamReducer
import io.redi.robot.ai.gateway.GatewayStreamReductionResult
import io.redi.robot.ai.gateway.GatewayStreamState
import io.redi.robot.capability.api.CapabilityActorKind
import io.redi.robot.capability.api.CapabilityApprovalRequirement
import io.redi.robot.capability.api.CapabilityAuditId
import io.redi.robot.capability.api.CapabilityAuthorizationReference
import io.redi.robot.capability.api.CapabilityAvailabilitySemantics
import io.redi.robot.capability.api.CapabilityCancellationSemantics
import io.redi.robot.capability.api.CapabilityContractReference
import io.redi.robot.capability.api.CapabilityData
import io.redi.robot.capability.api.CapabilityDescription
import io.redi.robot.capability.api.CapabilityDescriptor
import io.redi.robot.capability.api.CapabilityDescriptorResult
import io.redi.robot.capability.api.CapabilityEffect
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityExecutionContext
import io.redi.robot.capability.api.CapabilityLabel
import io.redi.robot.capability.api.CapabilityPermissionIntent
import io.redi.robot.capability.api.CapabilityProviderTarget
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.CapabilityRequest
import io.redi.robot.capability.api.CapabilityRequestId
import io.redi.robot.capability.api.CapabilityRiskClass
import io.redi.robot.capability.api.CapabilitySafetyClass
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.negotiation.CapabilityNegotiationId
import io.redi.robot.capability.negotiation.CapabilityNegotiationReason
import io.redi.robot.capability.negotiation.CapabilityNegotiationReport
import io.redi.robot.capability.negotiation.CapabilityNegotiationStatus
import io.redi.robot.capability.negotiation.NegotiatedCapabilityRecord
import io.redi.robot.capability.provider.CapabilityProviderPortResult
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.runtime.ContextSnapshot
import io.redi.robot.context.runtime.ContextSnapshotBudget
import io.redi.robot.context.runtime.ContextSnapshotEngine
import io.redi.robot.context.runtime.ContextSnapshotRequest
import io.redi.robot.context.runtime.ContextSnapshotResult
import io.redi.robot.conversation.context.ConversationContextBudget
import io.redi.robot.conversation.context.ConversationContextBuildResult
import io.redi.robot.conversation.context.ConversationContextBuilder
import io.redi.robot.conversation.context.ConversationContextPrivacyPolicy
import io.redi.robot.conversation.context.ConversationContextProvenanceId
import io.redi.robot.conversation.context.ConversationContextResult
import io.redi.robot.gateway.contract.GatewayContractCodec
import io.redi.robot.gateway.contract.GatewayContractResult
import io.redi.robot.gateway.contract.GatewayEvent
import io.redi.robot.skills.api.LocalSkillClass
import io.redi.robot.skills.api.SkillDefinition
import io.redi.robot.skills.api.SkillExecutionId
import io.redi.robot.skills.api.SkillModelResult
import io.redi.robot.skills.api.SkillReference
import io.redi.robot.skills.api.SkillRegistry
import io.redi.robot.skills.local.HugoLocalSkillCatalog
import io.redi.robot.trust.runtime.AuthorizationAuditRecord
import io.redi.robot.trust.runtime.AuthorizationDecision
import io.redi.robot.trust.runtime.AuthorizationDecisionId
import io.redi.robot.trust.runtime.AuthorizationDecisionReason
import io.redi.robot.trust.runtime.AuthorizationDecisionStatus
import io.redi.robot.trust.runtime.AuthorizationGateModelResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ToolProposalValidatorTest {
    @Test
    fun `exact H053 H054 H050 H030 proposal validates without invoking anything`() {
        val fixture = Fixture()
        val result = fixture.validator.validate(fixture.input()) as ToolProposalValidationResult.Accepted

        assertEquals(fixture.definition.reference, result.value.definition.reference)
        assertEquals(fixture.capabilityRequest, result.value.invocation.request)
        assertTrue(result.value.invocation.authorization.isAuthorized)
        assertFalse(result.value.grantsNewAuthority)
        assertFalse(result.value.bypassesAuthorization)
        assertFalse(result.value.approvesSafety)
        assertFalse(result.value.executesSkill)
        assertFalse(result.value.executesCapability)
        assertEquals(null, result.value.audit.rejectionReason)
    }

    @Test
    fun `fixed light-off schema accepts only exact enabled false argument`() {
        val definition = HugoLocalSkillCatalog.definitions.single { it.reference.wireValue.endsWith("light_off@1.0") }
        val accepted = Fixture(
            definition = definition,
            argumentsJson = """{"enabled":{"type":"flag","value":false}}""",
        )
        assertTrue(accepted.validator.validate(accepted.input()) is ToolProposalValidationResult.Accepted)

        val injected = Fixture(
            index = 2,
            definition = definition,
            argumentsJson = """{"enabled":{"type":"flag","value":false},"authorization":{"type":"flag","value":true}}""",
        )
        assertRejected(injected.validator.validate(injected.input()), ToolProposalRejectionReason.ARGUMENT_SCHEMA_MISMATCH)

        val changed = Fixture(
            index = 3,
            definition = definition,
            argumentsJson = """{"enabled":{"type":"flag","value":true}}""",
        )
        assertRejected(changed.validator.validate(changed.input()), ToolProposalRejectionReason.ARGUMENT_SCHEMA_MISMATCH)
    }

    @Test
    fun `proposal must exist exactly in correlated H053 stream and H054 context`() {
        val fixture = Fixture()
        val foreign = Fixture(index = 2)
        assertRejected(
            fixture.validator.validate(fixture.input(proposal = foreign.proposal)),
            ToolProposalRejectionReason.PROPOSAL_NOT_IN_STREAM,
        )
        assertRejected(
            Fixture(index = 3).let { it.validator.validate(it.input(context = fixture.context)) },
            ToolProposalRejectionReason.STREAM_CONTEXT_MISMATCH,
        )
    }

    @Test
    fun `registry allowlist rejects unknown skill and unsupported exact version`() {
        val unknown = Fixture(
            index = 7,
            definition = customDefinition("hugo.local.unknown_status@1.0", "provider.unknown.status@1.0"),
            validator = ToolProposalValidator(HugoLocalSkillCatalog.registry),
        )
        assertRejected(unknown.validator.validate(unknown.input()), ToolProposalRejectionReason.UNKNOWN_SKILL)

        val unsupportedVersion = Fixture(
            index = 8,
            definition = customDefinition(
                "hugo.local.connection_status@9.0",
                "provider.connection.status@1.0",
            ),
            validator = ToolProposalValidator(HugoLocalSkillCatalog.registry),
        )
        assertRejected(
            unsupportedVersion.validator.validate(unsupportedVersion.input()),
            ToolProposalRejectionReason.UNSUPPORTED_SKILL_VERSION,
        )
    }

    @Test
    fun `H030 trust grant approval and replay statuses stay distinct`() {
        listOf(
            AuthorizationDecisionStatus.TRUST_REQUIRED to ToolProposalRejectionReason.TRUST_REQUIRED,
            AuthorizationDecisionStatus.PERMISSION_REQUIRED to ToolProposalRejectionReason.GRANT_REQUIRED,
            AuthorizationDecisionStatus.SCOPE_MISMATCH to ToolProposalRejectionReason.GRANT_REQUIRED,
            AuthorizationDecisionStatus.APPROVAL_REQUIRED to ToolProposalRejectionReason.APPROVAL_REQUIRED,
            AuthorizationDecisionStatus.REPLAY_REJECTED to ToolProposalRejectionReason.AUTHORIZATION_REPLAYED,
            AuthorizationDecisionStatus.PROHIBITED to ToolProposalRejectionReason.AUTHORIZATION_DENIED,
        ).forEachIndexed { index, (status, expected) ->
            val fixture = Fixture(index = index + 10, authorizationStatus = status)
            assertRejected(fixture.validator.validate(fixture.input()), expected)
        }
    }

    @Test
    fun `request requires exact capability actor provider input and current deadline`() {
        val fixture = Fixture()
        val wrongCapabilityDefinition = customDefinition("hugo.local.other_status@1.0", "provider.other.status@1.0")
        val wrongCapability = fixture.capabilityRequest(wrongCapabilityDefinition)
        assertRejected(
            fixture.validator.validate(fixture.input(capabilityRequest = wrongCapability)),
            ToolProposalRejectionReason.REQUEST_CAPABILITY_MISMATCH,
        )

        val actorFixture = Fixture(index = 2)
        assertRejected(
            actorFixture.validator.validate(
                actorFixture.input(capabilityRequest = actorFixture.capabilityRequest(actor = CapabilityActorKind.HUGO_RUNTIME)),
            ),
            ToolProposalRejectionReason.REQUEST_ACTOR_MISMATCH,
        )

        val targetFixture = Fixture(index = 3)
        assertRejected(
            targetFixture.validator.validate(
                targetFixture.input(capabilityRequest = targetFixture.capabilityRequest(target = CapabilityProviderTarget.AnyCompatible)),
            ),
            ToolProposalRejectionReason.EXACT_PROVIDER_REQUIRED,
        )

        val staleFixture = Fixture(index = 4)
        assertRejected(
            staleFixture.validator.validate(staleFixture.input(now = time(1_000))),
            ToolProposalRejectionReason.REQUEST_NOT_CURRENT,
        )
    }

    @Test
    fun `negotiation identity provider generation capability and freshness are exact`() {
        val fixture = Fixture()
        val wrongId = fixture.negotiation(negotiationId = negotiationId(99))
        assertRejected(
            fixture.validator.validate(fixture.input(negotiation = wrongId)),
            ToolProposalRejectionReason.NEGOTIATION_MISMATCH,
        )

        val stale = Fixture(index = 2)
        assertRejected(
            stale.validator.validate(stale.input(now = time(950))),
            ToolProposalRejectionReason.NEGOTIATION_NOT_CURRENT,
        )

        val missing = Fixture(index = 3)
        assertRejected(
            missing.validator.validate(missing.input(negotiation = missing.negotiation(includeRecord = false))),
            ToolProposalRejectionReason.NEGOTIATED_CAPABILITY_MISSING,
        )
    }

    @Test
    fun `elevated high safety critical and prohibited capabilities fail risk policy`() {
        listOf(CapabilityRiskClass.ELEVATED, CapabilityRiskClass.HIGH)
            .forEachIndexed { index, risk ->
                val fixture = Fixture(index = index + 20, risk = risk)
                assertRejected(fixture.validator.validate(fixture.input()), ToolProposalRejectionReason.RISK_NOT_ALLOWED)
            }
        val safetyCritical = Fixture(
            index = 25,
            definition = HugoLocalSkillCatalog.definitions.single { it.reference.wireValue.endsWith("light_off@1.0") },
            argumentsJson = """{"enabled":{"type":"flag","value":false}}""",
            risk = CapabilityRiskClass.SAFETY_CRITICAL,
        )
        assertRejected(
            safetyCritical.validator.validate(safetyCritical.input()),
            ToolProposalRejectionReason.RISK_NOT_ALLOWED,
        )
        val prohibited = Fixture(index = 30, approval = CapabilityApprovalRequirement.PROHIBITED)
        assertRejected(
            prohibited.validator.validate(prohibited.input()),
            ToolProposalRejectionReason.CAPABILITY_PROHIBITED,
        )
        val confirmation = Fixture(index = 31, approval = CapabilityApprovalRequirement.EVERY_REQUEST)
        assertRejected(
            confirmation.validator.validate(confirmation.input()),
            ToolProposalRejectionReason.CONFIRMATION_REQUIRED,
        )
    }

    @Test
    fun `motion raw transport e-stop reset and limit semantics are denied even if registered`() {
        listOf(
            customDefinition("hugo.local.mobility_move@1.0", "mobility.move@1.0") to
                ToolProposalRejectionReason.MOTION_NOT_ALLOWED,
            customDefinition("hugo.local.ble_packet@1.0", "device.raw_control@1.0") to
                ToolProposalRejectionReason.FORBIDDEN_SEMANTIC,
            customDefinition("hugo.local.estop_reset@1.0", "safety.estop.reset@1.0") to
                ToolProposalRejectionReason.FORBIDDEN_SEMANTIC,
            customDefinition("hugo.local.safety_limit@1.0", "safety.limit.modify@1.0") to
                ToolProposalRejectionReason.FORBIDDEN_SEMANTIC,
            customDefinition("hugo.local.adapter_install@1.0", "adapter.install.package@1.0") to
                ToolProposalRejectionReason.FORBIDDEN_SEMANTIC,
        ).forEachIndexed { index, (definition, expected) ->
            val fixture = Fixture(index = index + 40, definition = definition)
            assertRejected(fixture.validator.validate(fixture.input()), expected)
        }
    }

    @Test
    fun `proposal request and execution replays are rejected and ledger fails closed when full`() {
        val fixture = Fixture()
        assertTrue(fixture.validator.validate(fixture.input()) is ToolProposalValidationResult.Accepted)
        assertRejected(fixture.validator.validate(fixture.input()), ToolProposalRejectionReason.PROPOSAL_REPLAYED)

        val rejectedThenRetried = Fixture(index = 4, authorizationStatus = AuthorizationDecisionStatus.TRUST_REQUIRED)
        assertRejected(
            rejectedThenRetried.validator.validate(rejectedThenRetried.input()),
            ToolProposalRejectionReason.TRUST_REQUIRED,
        )
        assertRejected(
            rejectedThenRetried.validator.validate(rejectedThenRetried.input()),
            ToolProposalRejectionReason.PROPOSAL_REPLAYED,
        )

        val requestReplayFixture = Fixture(index = 2)
        val first = requestReplayFixture.input()
        assertTrue(requestReplayFixture.validator.validate(first) is ToolProposalValidationResult.Accepted)
        val differentProposal = Fixture(index = 3)
        assertRejected(
            requestReplayFixture.validator.validate(
                differentProposal.input(capabilityRequest = first.capabilityRequest, authorization = first.authorization),
            ),
            ToolProposalRejectionReason.REQUEST_REPLAYED,
        )

        val executionReplayValidator = ToolProposalValidator(HugoLocalSkillCatalog.registry)
        val executionFirst = Fixture(index = 7, validator = executionReplayValidator)
        val executionSecond = Fixture(index = 8, validator = executionReplayValidator)
        assertTrue(executionReplayValidator.validate(executionFirst.input()) is ToolProposalValidationResult.Accepted)
        assertRejected(
            executionReplayValidator.validate(
                executionSecond.input().copy(executionId = executionFirst.executionId),
            ),
            ToolProposalRejectionReason.EXECUTION_REPLAYED,
        )

        val capacityValidator = ToolProposalValidator(HugoLocalSkillCatalog.registry, maximumLedgerEntries = 1)
        val one = Fixture(index = 5, validator = capacityValidator)
        val two = Fixture(index = 6, validator = capacityValidator)
        assertTrue(capacityValidator.validate(one.input()) is ToolProposalValidationResult.Accepted)
        assertRejected(capacityValidator.validate(two.input()), ToolProposalRejectionReason.IDEMPOTENCY_CAPACITY_EXHAUSTED)
    }

    @Test
    fun `H051 schema fuzz rejects extra missing unknown and unsupported fields before H055`() {
        val prefix = """{"schemaVersion":"1.0","messageType":"event","requestId":"10000000-0000-4000-8000-000000000001","eventId":"20000000-0000-4000-8000-000000000001","sequence":0,"operation":"text","event":"""
        val suffix = "}"
        listOf(
            """{"type":"tool_proposal","proposalId":"30000000-0000-4000-8000-000000000001","skill":"hugo.local.connection_status@1.0","arguments":{},"grant":true}""",
            """{"type":"tool_proposal","proposalId":"30000000-0000-4000-8000-000000000001","skill":"hugo.local.connection_status@1.0"}""",
            """{"type":"tool_proposal","proposalId":"30000000-0000-4000-8000-000000000001","skill":"hugo.local.connection_status@01.0","arguments":{}}""",
            """{"type":"tool_proposal","proposalId":"30000000-0000-4000-8000-000000000001","skill":"hugo.local.connection_status@1.0","arguments":{"x":{"type":"authority","value":true}}}""",
            """{"type":"tool_proposal","proposalId":"30000000-0000-4000-8000-000000000001","skill":"hugo.local.connection_status@1.0","arguments":{"x":{"type":"flag","value":true,"extra":1}}}""",
        ).forEach { payload ->
            assertTrue(GatewayContractCodec.decodeEvent(prefix + payload + suffix) is GatewayContractResult.Failure)
        }
        val unsupportedSchema = (prefix +
            """{"type":"tool_proposal","proposalId":"30000000-0000-4000-8000-000000000001","skill":"hugo.local.connection_status@1.0","arguments":{}}""" +
            suffix).replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"2.0\"")
        assertTrue(GatewayContractCodec.decodeEvent(unsupportedSchema) is GatewayContractResult.Failure)
    }

    @Test
    fun `audit ordering is deterministic content free and never reports execution`() {
        val first = Fixture()
        val second = Fixture()
        val a = (first.validator.validate(first.input()) as ToolProposalValidationResult.Accepted).value
        val b = (second.validator.validate(second.input()) as ToolProposalValidationResult.Accepted).value
        assertEquals(a.audit.checks, b.audit.checks)
        assertFalse(a.toString().contains("authorization=true"))
        assertFalse(a.audit.toString().contains("hello"))
        assertTrue(a.audit.checks.map { it.dimension }.containsAll(ToolProposalValidationDimension.entries))
    }

    @Test
    fun `validator source has no executor dispatch Android provider implementation or raw protocol coupling`() {
        val source = sourceFiles().joinToString("\n") { it.readText(Charsets.UTF_8) }
        listOf(
            "import android.", "import androidx.", "CapabilityBackedSkillExecutor",
            "AuthorizedSkillDispatchPort", ".start(", "io.redi.robot.body.looi", "BluetoothGatt",
            "FED9", "SafetyController", "ApprovalConsumptionPort", "println(", "Log.",
        ).forEach { assertFalse("forbidden H055 coupling: $it", source.contains(it)) }
    }

    private inner class Fixture(
        val index: Int = 1,
        val definition: SkillDefinition = HugoLocalSkillCatalog.definitions.first(),
        val argumentsJson: String = "{}",
        val authorizationStatus: AuthorizationDecisionStatus = AuthorizationDecisionStatus.AUTHORIZED,
        val risk: CapabilityRiskClass = CapabilityRiskClass.INFORMATIONAL,
        val approval: CapabilityApprovalRequirement = CapabilityApprovalRequirement.WITHIN_GRANT,
        val validator: ToolProposalValidator = ToolProposalValidator(
            model(SkillRegistry.create(listOf(definition))),
        ),
    ) {
        val provider = ProviderInstanceId.fromUuid(uuid(700 + index))
        val generation = providerPort(ProviderGeneration.create(index.toLong()))
        val negotiationId = negotiationId(index)
        val gatewayRequest = gatewayRequest(definition, index)
        val stream = stream(gatewayRequest, definition, index, argumentsJson)
        val proposal = stream.toolProposals.single()
        val context = context(gatewayRequest, definition, index)
        val capabilityRequest = capabilityRequest()
        val authorization = decision(capabilityRequest, authorizationStatus)
        val negotiation = negotiation()
        val executionId = SkillExecutionId.fromUuid(uuid(800 + index))

        fun input(
            proposal: io.redi.robot.gateway.contract.GatewayToolProposal = this.proposal,
            context: io.redi.robot.conversation.context.ConversationContextPacket = this.context,
            capabilityRequest: CapabilityRequest = this.capabilityRequest,
            authorization: AuthorizationDecision = this.authorization,
            negotiation: CapabilityNegotiationReport = this.negotiation,
            now: MonotonicTimestampMs = time(100),
        ) = ToolProposalValidationInput(
            proposal, stream, context, capabilityRequest, authorization, negotiation, executionId, now,
        )

        fun capabilityRequest(
            definition: SkillDefinition = this.definition,
            actor: CapabilityActorKind = definition.actor,
            target: CapabilityProviderTarget = CapabilityProviderTarget.Exact(provider),
        ): CapabilityRequest = envelope(
            CapabilityRequest.create(
                CapabilityRequestId.fromUuid(uuid(900 + index)),
                definition.capability,
                target,
                actor,
                envelope(CapabilityExecutionContext.create(index.toLong(), 1)),
                time(50),
                time(1_000),
                AUTHORIZATION,
                CapabilityAuditId.fromUuid(uuid(1_000 + index)),
                definition.fixedInput,
            ),
        )

        fun negotiation(
            negotiationId: CapabilityNegotiationId = this.negotiationId,
            includeRecord: Boolean = true,
        ): CapabilityNegotiationReport {
            val descriptor = descriptor(definition, risk, approval)
            val records = if (includeRecord) listOf(
                NegotiatedCapabilityRecord(
                    definition.capability,
                    descriptor,
                    provider,
                    generation,
                    negotiationId,
                    emptyList(),
                    definition.inputContract,
                    definition.outputContract,
                    ProviderRuntimeAvailability.AVAILABLE,
                    emptyList(),
                    time(10),
                    time(900),
                    emptyList(),
                ),
            ) else emptyList()
            return CapabilityNegotiationReport(
                negotiationId,
                provider,
                generation,
                CapabilityNegotiationStatus.NEGOTIATED,
                CapabilityNegotiationReason.ALL_DECLARED_CAPABILITIES_NEGOTIATED,
                null,
                records,
                emptyList(),
            )
        }

        private fun decision(request: CapabilityRequest, status: AuthorizationDecisionStatus): AuthorizationDecision {
            val audit = AuthorizationAuditRecord(
                gate(AuthorizationDecisionId.fromUuid(uuid(1_100 + index))),
                request.requestId,
                request.auditId,
                request.actor,
                provider,
                request.capability,
                generation,
                negotiationId,
                time(75),
                true,
                emptyList(),
                emptyList(),
                emptyList(),
                null,
            )
            return AuthorizationDecision(
                status,
                if (status == AuthorizationDecisionStatus.AUTHORIZED) AuthorizationDecisionReason.AUTHORITY_VALID
                else reason(status),
                if (status == AuthorizationDecisionStatus.AUTHORIZED) AUTHORIZATION else null,
                audit,
            )
        }
    }

    private fun reason(status: AuthorizationDecisionStatus): AuthorizationDecisionReason = when (status) {
        AuthorizationDecisionStatus.TRUST_REQUIRED -> AuthorizationDecisionReason.TRUST_MISSING
        AuthorizationDecisionStatus.PERMISSION_REQUIRED -> AuthorizationDecisionReason.GRANT_MISSING
        AuthorizationDecisionStatus.SCOPE_MISMATCH -> AuthorizationDecisionReason.SCOPE_NOT_GRANTED
        AuthorizationDecisionStatus.APPROVAL_REQUIRED -> AuthorizationDecisionReason.APPROVAL_REQUIRED
        AuthorizationDecisionStatus.REPLAY_REJECTED -> AuthorizationDecisionReason.APPROVAL_REPLAYED
        AuthorizationDecisionStatus.PROHIBITED -> AuthorizationDecisionReason.PROHIBITED
        else -> AuthorizationDecisionReason.REQUEST_NOT_CURRENT
    }

    private fun gatewayRequest(definition: SkillDefinition, index: Int) = successfulGateway(
        GatewayContractCodec.decodeRequest(
            """{"schemaVersion":"1.0","messageType":"request","requestId":"${uuid(100 + index)}","operation":"text","payload":{"prompt":"hello","declaredSkills":["${definition.reference.wireValue}"],"budget":{"maximumOutputUnits":32,"timeoutMs":1000}}}""",
        ),
    )

    private fun stream(
        request: io.redi.robot.gateway.contract.GatewayRequest,
        definition: SkillDefinition,
        index: Int,
        arguments: String,
    ): GatewayStreamState {
        val events = listOf(
            event(request, index, 0, """{"type":"started"}"""),
            event(
                request,
                index,
                1,
                """{"type":"tool_proposal","proposalId":"${uuid(300 + index)}","skill":"${definition.reference.wireValue}","arguments":$arguments}""",
            ),
        )
        var state = GatewayStreamState.initial(request)
        events.forEach {
            state = (GatewayStreamReducer.reduce(state, it) as GatewayStreamReductionResult.Accepted).state
        }
        return state
    }

    private fun event(
        request: io.redi.robot.gateway.contract.GatewayRequest,
        index: Int,
        sequence: Int,
        payload: String,
    ): GatewayEvent = successfulGateway(
        GatewayContractCodec.decodeEvent(
            """{"schemaVersion":"1.0","messageType":"event","requestId":"${request.requestId.value}","eventId":"${uuid(200 + index * 10 + sequence)}","sequence":$sequence,"operation":"text","event":$payload}""",
        ),
    )

    private fun context(
        request: io.redi.robot.gateway.contract.GatewayRequest,
        definition: SkillDefinition,
        index: Int,
    ) = (ConversationContextBuilder().build(
        request,
        ConversationContextProvenanceId.fromUuid(uuid(400 + index)),
        ContextPrivacyClassification.PRIVATE,
        null,
        emptySnapshot(),
        contextModel(
            ConversationContextPrivacyPolicy.create(setOf(ContextPrivacyClassification.PRIVATE)),
        ),
        contextModel(ConversationContextBudget.create(8, 4_096, 16_384)),
        listOf(definition),
    ) as ConversationContextBuildResult.Built).packet

    private fun emptySnapshot(): ContextSnapshot {
        val request = snapshot(
            ContextSnapshotRequest.create(
                time(100),
                null,
                setOf(ContextPrivacyClassification.PRIVATE),
                snapshot(ContextSnapshotBudget.create(8, 4, 8, 4_096)),
            ),
        )
        return snapshot(ContextSnapshotEngine.build(emptyList(), request))
    }

    private fun descriptor(
        definition: SkillDefinition,
        risk: CapabilityRiskClass,
        approval: CapabilityApprovalRequirement,
    ): CapabilityDescriptor = descriptorResult(
        CapabilityDescriptor.create(
            definition.capability,
            descriptorResult(CapabilityLabel.parse("Test skill")),
            descriptorResult(CapabilityDescription.parse("Synthetic H055 validation descriptor.")),
            definition.inputContract,
            definition.outputContract,
            if (definition.skillClass == LocalSkillClass.OBSERVATION) CapabilityEffect.OBSERVE else CapabilityEffect.ACT,
            risk,
            if (risk == CapabilityRiskClass.SAFETY_CRITICAL) CapabilitySafetyClass.SAFETY_CRITICAL_ACTION
            else if (definition.skillClass == LocalSkillClass.OBSERVATION) CapabilitySafetyClass.NON_PHYSICAL
            else CapabilitySafetyClass.PHYSICAL_ACTION,
            if (definition.skillClass == LocalSkillClass.OBSERVATION) CapabilityPermissionIntent.OBSERVE
            else CapabilityPermissionIntent.ACT,
            approval,
            CapabilityCancellationSemantics.REQUIRED,
            CapabilityAvailabilitySemantics.DYNAMIC_STATE,
            CapabilityEvidenceClass.AUTOMATED_TESTED,
        ),
    )

    private fun customDefinition(skill: String, capability: String): SkillDefinition = model(
        SkillDefinition.create(
            model(SkillReference.parse(skill)),
            identity(CapabilityReference.parse(capability)),
            CapabilityActorKind.LOCAL_USER,
            LocalSkillClass.NON_MOTION_CONTROL,
            null,
            null,
            null,
            1_000,
            true,
        ),
    )

    private fun assertRejected(result: ToolProposalValidationResult, reason: ToolProposalRejectionReason) {
        val rejected = result as ToolProposalValidationResult.Rejected
        assertEquals(reason, rejected.reason)
        assertEquals(reason, rejected.audit.rejectionReason)
    }

    private fun negotiationId(index: Int) = CapabilityNegotiationId.fromUuid(uuid(600 + index))
    private fun time(value: Long) = (MonotonicTimestampMs.create(value) as CapabilityEnvelopeResult.Success).value
    private fun uuid(index: Int) = UUID.fromString("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}")

    private fun <T> successfulGateway(value: GatewayContractResult<T>) = (value as GatewayContractResult.Success).value
    private fun <T> envelope(value: CapabilityEnvelopeResult<T>) = (value as CapabilityEnvelopeResult.Success).value
    private fun <T> descriptorResult(value: CapabilityDescriptorResult<T>) =
        (value as CapabilityDescriptorResult.Success).value
    private fun <T> model(value: SkillModelResult<T>) = (value as SkillModelResult.Success).value
    private fun <T> identity(value: io.redi.robot.capability.api.CapabilityIdentityResult<T>) =
        (value as io.redi.robot.capability.api.CapabilityIdentityResult.Success).value
    private fun <T> providerPort(value: CapabilityProviderPortResult<T>) =
        (value as CapabilityProviderPortResult.Success).value
    private fun <T> gate(value: AuthorizationGateModelResult<T>) =
        (value as AuthorizationGateModelResult.Success).value
    private fun <T> contextModel(value: ConversationContextResult<T>) =
        (value as ConversationContextResult.Success).value
    private fun <T> snapshot(value: ContextSnapshotResult<T>) = (value as ContextSnapshotResult.Success).value

    private fun sourceFiles(): List<File> {
        val relative = "app/src/main/java/io/redi/robot/ai/tools"
        val roots = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }.take(8)
        val root = roots.firstOrNull { File(it, relative).isDirectory } ?: error("Unable to locate H055 sources")
        return File(root, relative).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    companion object {
        val AUTHORIZATION: CapabilityAuthorizationReference =
            (CapabilityAuthorizationReference.parse("00000000-0000-4000-8000-000000000777") as
                CapabilityEnvelopeResult.Success).value
    }
}
