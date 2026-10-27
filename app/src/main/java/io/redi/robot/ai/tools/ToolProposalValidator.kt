package io.redi.robot.ai.tools

import io.redi.robot.capability.api.CapabilityApprovalRequirement
import io.redi.robot.capability.api.CapabilityArgumentValue
import io.redi.robot.capability.api.CapabilityProviderTarget
import io.redi.robot.capability.api.CapabilityRiskClass
import io.redi.robot.capability.api.CapabilitySafetyClass
import io.redi.robot.capability.negotiation.CapabilityNegotiationStatus
import io.redi.robot.gateway.contract.GatewayEventPayload
import io.redi.robot.gateway.contract.GatewayScalar
import io.redi.robot.skills.api.AuthorizedSkillInvocation
import io.redi.robot.skills.api.SkillDefinition
import io.redi.robot.skills.api.SkillInvocationError
import io.redi.robot.skills.api.SkillInvocationResult
import io.redi.robot.skills.api.SkillRegistry
import io.redi.robot.trust.runtime.AuthorizationDecisionStatus

class ToolProposalValidator(
    private val registry: SkillRegistry,
    private val maximumLedgerEntries: Int = DEFAULT_MAXIMUM_LEDGER_ENTRIES,
) {
    private val proposalLedger = mutableSetOf<String>()
    private val requestLedger = mutableSetOf<String>()
    private val executionLedger = mutableSetOf<String>()

    init {
        require(maximumLedgerEntries in 1..HARD_MAXIMUM_LEDGER_ENTRIES)
    }

    @Synchronized
    fun validate(input: ToolProposalValidationInput): ToolProposalValidationResult {
        val checks = mutableListOf<ToolProposalAuditCheck>()
        fun matched(dimension: ToolProposalValidationDimension, id: String) {
            checks += ToolProposalAuditCheck(dimension, id, ToolProposalCheckStatus.MATCHED, null)
        }
        fun reject(
            dimension: ToolProposalValidationDimension,
            id: String,
            status: ToolProposalCheckStatus,
            reason: ToolProposalRejectionReason,
        ): ToolProposalValidationResult.Rejected {
            checks += ToolProposalAuditCheck(dimension, id, status, reason)
            return ToolProposalValidationResult.Rejected(reason, audit(input, checks, reason))
        }

        val streamMatches = input.stream.toolProposals.count {
            it.proposalId == input.proposal.proposalId && it == input.proposal
        } == 1 && input.stream.events.any { event ->
            val payload = event.payload as? GatewayEventPayload.ToolProposal
            payload?.proposal == input.proposal
        }
        if (!streamMatches) return reject(
            ToolProposalValidationDimension.STREAM,
            "stream.proposal.exact",
            ToolProposalCheckStatus.MISSING,
            ToolProposalRejectionReason.PROPOSAL_NOT_IN_STREAM,
        )
        matched(ToolProposalValidationDimension.STREAM, "stream.proposal.exact")

        if (
            input.context.requestId != input.stream.request.requestId ||
            input.context.operation != input.stream.request.operation
        ) return reject(
            ToolProposalValidationDimension.CONTEXT,
            "context.stream.correlation",
            ToolProposalCheckStatus.MISMATCH,
            ToolProposalRejectionReason.STREAM_CONTEXT_MISMATCH,
        )
        matched(ToolProposalValidationDimension.CONTEXT, "context.stream.correlation")

        val contextSchema = input.context.tools.singleOrNull { it.reference == input.proposal.skill }
            ?: return reject(
                ToolProposalValidationDimension.CONTEXT,
                "context.skill.declared",
                ToolProposalCheckStatus.MISSING,
                ToolProposalRejectionReason.SKILL_NOT_IN_CONTEXT,
            )
        matched(ToolProposalValidationDimension.CONTEXT, "context.skill.declared")

        reserveIdempotency(input, checks)?.let { return it }

        val definition = registry.findExact(input.proposal.skill) ?: run {
            val sameId = registry.snapshot.definitions.any { it.reference.id == input.proposal.skill.id }
            return reject(
                ToolProposalValidationDimension.REGISTRY,
                "registry.skill.exact-version",
                ToolProposalCheckStatus.MISSING,
                if (sameId) ToolProposalRejectionReason.UNSUPPORTED_SKILL_VERSION
                else ToolProposalRejectionReason.UNKNOWN_SKILL,
            )
        }
        matched(ToolProposalValidationDimension.REGISTRY, "registry.skill.exact-version")

        if (!contextSchema.matches(definition)) return reject(
            ToolProposalValidationDimension.CONTEXT,
            "context.skill.schema",
            ToolProposalCheckStatus.MISMATCH,
            ToolProposalRejectionReason.CONTEXT_SCHEMA_MISMATCH,
        )
        matched(ToolProposalValidationDimension.CONTEXT, "context.skill.schema")

        if (input.proposal.arguments.canonicalArguments() != definition.canonicalFixedInput()) return reject(
            ToolProposalValidationDimension.SCHEMA,
            "proposal.arguments.exact",
            ToolProposalCheckStatus.MISMATCH,
            ToolProposalRejectionReason.ARGUMENT_SCHEMA_MISMATCH,
        )
        matched(ToolProposalValidationDimension.SCHEMA, "proposal.arguments.exact")

        when (definition.forbiddenSemantic(input.proposal.arguments.keys)) {
            ToolProposalRejectionReason.MOTION_NOT_ALLOWED -> return reject(
                ToolProposalValidationDimension.SEMANTIC_POLICY,
                "semantic.non-motion",
                ToolProposalCheckStatus.DENIED,
                ToolProposalRejectionReason.MOTION_NOT_ALLOWED,
            )
            ToolProposalRejectionReason.FORBIDDEN_SEMANTIC -> return reject(
                ToolProposalValidationDimension.SEMANTIC_POLICY,
                "semantic.forbidden-control",
                ToolProposalCheckStatus.DENIED,
                ToolProposalRejectionReason.FORBIDDEN_SEMANTIC,
            )
            else -> matched(ToolProposalValidationDimension.SEMANTIC_POLICY, "semantic.allowlist")
        }

        val request = input.capabilityRequest
        if (request.capability != definition.capability) return reject(
            ToolProposalValidationDimension.REQUEST, "request.capability", ToolProposalCheckStatus.MISMATCH,
            ToolProposalRejectionReason.REQUEST_CAPABILITY_MISMATCH,
        )
        if (request.actor != definition.actor) return reject(
            ToolProposalValidationDimension.REQUEST, "request.actor", ToolProposalCheckStatus.MISMATCH,
            ToolProposalRejectionReason.REQUEST_ACTOR_MISMATCH,
        )
        if (request.providerTarget !is CapabilityProviderTarget.Exact) return reject(
            ToolProposalValidationDimension.REQUEST, "request.provider.exact", ToolProposalCheckStatus.MISSING,
            ToolProposalRejectionReason.EXACT_PROVIDER_REQUIRED,
        )
        if (!definition.acceptsInput(request.input)) return reject(
            ToolProposalValidationDimension.REQUEST, "request.input", ToolProposalCheckStatus.MISMATCH,
            ToolProposalRejectionReason.REQUEST_INPUT_MISMATCH,
        )
        if (input.now.value < request.createdAt.value || input.now.value >= request.deadline.value) return reject(
            ToolProposalValidationDimension.REQUEST, "request.current", ToolProposalCheckStatus.STALE,
            ToolProposalRejectionReason.REQUEST_NOT_CURRENT,
        )
        matched(ToolProposalValidationDimension.REQUEST, "request.binding-current")

        val negotiation = input.negotiation
        val audit = input.authorization.audit
        val target = (request.providerTarget as CapabilityProviderTarget.Exact).providerInstanceId
        if (
            negotiation.negotiationId != audit.negotiationId ||
            negotiation.providerInstanceId != target ||
            negotiation.providerGeneration != audit.providerGeneration ||
            negotiation.status !in setOf(
                CapabilityNegotiationStatus.NEGOTIATED,
                CapabilityNegotiationStatus.PARTIALLY_NEGOTIATED,
            )
        ) return reject(
            ToolProposalValidationDimension.NEGOTIATION, "negotiation.correlation",
            ToolProposalCheckStatus.MISMATCH, ToolProposalRejectionReason.NEGOTIATION_MISMATCH,
        )
        val negotiated = negotiation.negotiatedCapabilities.singleOrNull {
            it.capability == definition.capability && it.providerInstanceId == target
        } ?: return reject(
            ToolProposalValidationDimension.NEGOTIATION, "negotiation.capability",
            ToolProposalCheckStatus.MISSING, ToolProposalRejectionReason.NEGOTIATED_CAPABILITY_MISSING,
        )
        if (
            input.now.value < negotiated.negotiatedAt.value ||
            input.now.value >= negotiated.validUntil.value
        ) return reject(
            ToolProposalValidationDimension.NEGOTIATION, "negotiation.current",
            ToolProposalCheckStatus.STALE, ToolProposalRejectionReason.NEGOTIATION_NOT_CURRENT,
        )
        if (
            negotiated.descriptor.reference != definition.capability ||
            negotiated.inputContract != definition.inputContract ||
            negotiated.outputContract != definition.outputContract
        ) return reject(
            ToolProposalValidationDimension.NEGOTIATION, "negotiation.definition",
            ToolProposalCheckStatus.MISMATCH, ToolProposalRejectionReason.NEGOTIATION_MISMATCH,
        )
        matched(ToolProposalValidationDimension.NEGOTIATION, "negotiation.current-exact")

        val descriptor = negotiated.descriptor
        if (descriptor.approvalRequirement == CapabilityApprovalRequirement.PROHIBITED) return reject(
            ToolProposalValidationDimension.RISK, "risk.prohibited", ToolProposalCheckStatus.DENIED,
            ToolProposalRejectionReason.CAPABILITY_PROHIBITED,
        )
        if (
            descriptor.risk !in setOf(CapabilityRiskClass.INFORMATIONAL, CapabilityRiskClass.LOW) ||
            descriptor.safety == CapabilitySafetyClass.SAFETY_CRITICAL_ACTION
        ) return reject(
            ToolProposalValidationDimension.RISK, "risk.allowed", ToolProposalCheckStatus.DENIED,
            ToolProposalRejectionReason.RISK_NOT_ALLOWED,
        )
        matched(ToolProposalValidationDimension.RISK, "risk.allowed")

        if (!input.authorization.isAuthorized) return reject(
            ToolProposalValidationDimension.AUTHORIZATION, "authorization.H030",
            ToolProposalCheckStatus.DENIED, input.authorization.status.toProposalReason(),
        )
        if (
            descriptor.approvalRequirement == CapabilityApprovalRequirement.EVERY_REQUEST &&
            input.authorization.audit.approvalConsumption == null
        ) return reject(
            ToolProposalValidationDimension.APPROVAL, "approval.every-request-consumed",
            ToolProposalCheckStatus.MISSING, ToolProposalRejectionReason.CONFIRMATION_REQUIRED,
        )
        matched(ToolProposalValidationDimension.APPROVAL, "approval.H030")
        val invocation = when (
            val result = AuthorizedSkillInvocation.create(input.executionId, request, input.authorization)
        ) {
            is SkillInvocationResult.Success -> result.value
            is SkillInvocationResult.Failure -> return reject(
                ToolProposalValidationDimension.AUTHORIZATION,
                "authorization.correlation",
                ToolProposalCheckStatus.MISMATCH,
                when (result.error) {
                    SkillInvocationError.EXACT_PROVIDER_REQUIRED -> ToolProposalRejectionReason.EXACT_PROVIDER_REQUIRED
                    else -> ToolProposalRejectionReason.AUTHORIZATION_CORRELATION_MISMATCH
                },
            )
        }
        matched(ToolProposalValidationDimension.AUTHORIZATION, "authorization.H030-correlated")

        val acceptedAudit = audit(input, checks, null)
        return ToolProposalValidationResult.Accepted(
            ValidatedToolProposal(input.proposal, definition, invocation, acceptedAudit),
        )
    }

    private fun reserveIdempotency(
        input: ToolProposalValidationInput,
        checks: MutableList<ToolProposalAuditCheck>,
    ): ToolProposalValidationResult.Rejected? {
        val proposal = input.proposal.proposalId.value
        val request = input.capabilityRequest.requestId.value
        val execution = input.executionId.value
        val replay = when {
            proposal in proposalLedger -> ToolProposalRejectionReason.PROPOSAL_REPLAYED
            request in requestLedger -> ToolProposalRejectionReason.REQUEST_REPLAYED
            execution in executionLedger -> ToolProposalRejectionReason.EXECUTION_REPLAYED
            else -> null
        }
        if (replay != null) {
            checks += ToolProposalAuditCheck(
                ToolProposalValidationDimension.IDEMPOTENCY,
                "idempotency.unique",
                ToolProposalCheckStatus.REPLAYED,
                replay,
            )
            return ToolProposalValidationResult.Rejected(replay, audit(input, checks, replay))
        }
        if (proposalLedger.size >= maximumLedgerEntries) {
            val reason = ToolProposalRejectionReason.IDEMPOTENCY_CAPACITY_EXHAUSTED
            checks += ToolProposalAuditCheck(
                ToolProposalValidationDimension.IDEMPOTENCY,
                "idempotency.capacity",
                ToolProposalCheckStatus.EXHAUSTED,
                reason,
            )
            return ToolProposalValidationResult.Rejected(reason, audit(input, checks, reason))
        }
        proposalLedger += proposal
        requestLedger += request
        executionLedger += execution
        checks += ToolProposalAuditCheck(
            ToolProposalValidationDimension.IDEMPOTENCY,
            "idempotency.reserved",
            ToolProposalCheckStatus.MATCHED,
            null,
        )
        return null
    }

    private fun audit(
        input: ToolProposalValidationInput,
        checks: List<ToolProposalAuditCheck>,
        reason: ToolProposalRejectionReason?,
    ) = ToolProposalValidationAudit(
        input.proposal.proposalId.value,
        input.proposal.skill.wireValue,
        checks,
        reason,
    )

    private fun io.redi.robot.conversation.context.ConversationToolContextSchema.matches(
        definition: SkillDefinition,
    ): Boolean =
        capability == definition.capability.wireValue &&
            actor == definition.actor.name.lowercase() &&
            skillClass == definition.skillClass.name.lowercase() &&
            inputContract == definition.inputContract?.wireValue &&
            fixedInput == definition.canonicalFixedInput() &&
            outputContract == definition.outputContract?.wireValue &&
            maximumDurationMs == definition.maximumDurationMs &&
            cancellationRequired == definition.cancellationRequired

    private fun SkillDefinition.canonicalFixedInput(): Map<String, String> =
        fixedInput?.values.orEmpty().entries.associate { (name, value) -> name.value to value.canonical() }

    private fun Map<String, GatewayScalar>.canonicalArguments(): Map<String, String> =
        entries.associate { (name, value) -> name to value.canonicalValue }

    private fun CapabilityArgumentValue.canonical(): String = when (this) {
        is CapabilityArgumentValue.Text -> "text:$value"
        is CapabilityArgumentValue.Integer -> "integer:$value"
        is CapabilityArgumentValue.Decimal -> "decimal:${java.lang.Double.toHexString(value)}"
        is CapabilityArgumentValue.Flag -> "flag:$value"
    }

    private fun SkillDefinition.forbiddenSemantic(argumentNames: Set<String>): ToolProposalRejectionReason? {
        val semantic = "${reference.id.value}.${capability.id.wireValue}".lowercase()
        val tokens = semantic.split(Regex("[^a-z0-9]+")) + argumentNames.flatMap {
            it.lowercase().split(Regex("[^a-z0-9]+"))
        }
        if ("mobility" in tokens || "move" in tokens || "turn" in tokens) {
            return ToolProposalRejectionReason.MOTION_NOT_ALLOWED
        }
        val rawTransport = setOf("ble", "bluetooth", "gatt", "packet", "protocol", "mac", "uuid")
        if (tokens.any { it in rawTransport }) return ToolProposalRejectionReason.FORBIDDEN_SEMANTIC
        val authorityOrLifecycle = setOf(
            "grant", "permission", "authorize", "authorization", "trust", "approval",
            "install", "uninstall", "enable", "disable", "reset", "override",
        )
        if (tokens.any { it in authorityOrLifecycle }) return ToolProposalRejectionReason.FORBIDDEN_SEMANTIC
        if (
            ("estop" in tokens || ("e" in tokens && "stop" in tokens)) &&
            tokens.any { it in setOf("reset", "clear", "disable", "override") }
        ) return ToolProposalRejectionReason.FORBIDDEN_SEMANTIC
        if (tokens.any { it in setOf("limit", "limits") }) return ToolProposalRejectionReason.FORBIDDEN_SEMANTIC
        return null
    }

    private fun AuthorizationDecisionStatus.toProposalReason(): ToolProposalRejectionReason = when (this) {
        AuthorizationDecisionStatus.TRUST_REQUIRED -> ToolProposalRejectionReason.TRUST_REQUIRED
        AuthorizationDecisionStatus.PERMISSION_REQUIRED,
        AuthorizationDecisionStatus.SCOPE_MISMATCH,
        -> ToolProposalRejectionReason.GRANT_REQUIRED
        AuthorizationDecisionStatus.APPROVAL_REQUIRED -> ToolProposalRejectionReason.APPROVAL_REQUIRED
        AuthorizationDecisionStatus.REPLAY_REJECTED -> ToolProposalRejectionReason.AUTHORIZATION_REPLAYED
        else -> ToolProposalRejectionReason.AUTHORIZATION_DENIED
    }

    companion object {
        const val DEFAULT_MAXIMUM_LEDGER_ENTRIES = 256
        const val HARD_MAXIMUM_LEDGER_ENTRIES = 1_024
    }
}
