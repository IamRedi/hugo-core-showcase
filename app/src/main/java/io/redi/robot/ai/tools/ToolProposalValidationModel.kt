package io.redi.robot.ai.tools

import io.redi.robot.ai.gateway.GatewayStreamState
import io.redi.robot.capability.api.CapabilityRequest
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.negotiation.CapabilityNegotiationReport
import io.redi.robot.conversation.context.ConversationContextPacket
import io.redi.robot.gateway.contract.GatewayToolProposal
import io.redi.robot.skills.api.AuthorizedSkillInvocation
import io.redi.robot.skills.api.SkillDefinition
import io.redi.robot.skills.api.SkillExecutionId
import io.redi.robot.trust.runtime.AuthorizationDecision
import java.util.Collections

data class ToolProposalValidationInput(
    val proposal: GatewayToolProposal,
    val stream: GatewayStreamState,
    val context: ConversationContextPacket,
    val capabilityRequest: CapabilityRequest,
    val authorization: AuthorizationDecision,
    val negotiation: CapabilityNegotiationReport,
    val executionId: SkillExecutionId,
    val now: MonotonicTimestampMs,
)

enum class ToolProposalValidationDimension {
    STREAM,
    CONTEXT,
    IDEMPOTENCY,
    REGISTRY,
    SCHEMA,
    SEMANTIC_POLICY,
    REQUEST,
    NEGOTIATION,
    RISK,
    APPROVAL,
    AUTHORIZATION,
}

enum class ToolProposalCheckStatus { MATCHED, MISSING, MISMATCH, DENIED, STALE, REPLAYED, EXHAUSTED }

enum class ToolProposalRejectionReason {
    PROPOSAL_NOT_IN_STREAM,
    STREAM_CONTEXT_MISMATCH,
    SKILL_NOT_IN_CONTEXT,
    CONTEXT_SCHEMA_MISMATCH,
    PROPOSAL_REPLAYED,
    REQUEST_REPLAYED,
    EXECUTION_REPLAYED,
    IDEMPOTENCY_CAPACITY_EXHAUSTED,
    UNKNOWN_SKILL,
    UNSUPPORTED_SKILL_VERSION,
    ARGUMENT_SCHEMA_MISMATCH,
    FORBIDDEN_SEMANTIC,
    MOTION_NOT_ALLOWED,
    REQUEST_CAPABILITY_MISMATCH,
    REQUEST_ACTOR_MISMATCH,
    EXACT_PROVIDER_REQUIRED,
    REQUEST_INPUT_MISMATCH,
    REQUEST_NOT_CURRENT,
    NEGOTIATION_MISMATCH,
    NEGOTIATION_NOT_CURRENT,
    NEGOTIATED_CAPABILITY_MISSING,
    RISK_NOT_ALLOWED,
    CAPABILITY_PROHIBITED,
    CONFIRMATION_REQUIRED,
    TRUST_REQUIRED,
    GRANT_REQUIRED,
    APPROVAL_REQUIRED,
    AUTHORIZATION_REPLAYED,
    AUTHORIZATION_DENIED,
    AUTHORIZATION_CORRELATION_MISMATCH,
}

data class ToolProposalAuditCheck(
    val dimension: ToolProposalValidationDimension,
    val checkId: String,
    val status: ToolProposalCheckStatus,
    val reason: ToolProposalRejectionReason?,
)

class ToolProposalValidationAudit internal constructor(
    val proposalId: String,
    val skill: String,
    checks: List<ToolProposalAuditCheck>,
    val rejectionReason: ToolProposalRejectionReason?,
) {
    val checks: List<ToolProposalAuditCheck> = Collections.unmodifiableList(checks.toList())

    override fun toString(): String = "ToolProposalValidationAudit(proposal=redacted, skill=$skill, " +
        "checks=${checks.size}, rejection=$rejectionReason, content=[redacted])"
}

class ValidatedToolProposal internal constructor(
    val proposal: GatewayToolProposal,
    val definition: SkillDefinition,
    val invocation: AuthorizedSkillInvocation,
    val audit: ToolProposalValidationAudit,
) {
    val grantsNewAuthority = false
    val bypassesAuthorization = false
    val approvesSafety = false
    val executesSkill = false
    val executesCapability = false

    override fun toString(): String = "ValidatedToolProposal(proposal=redacted, skill=${definition.reference}, " +
        "authorization=H030-verified, executed=false)"
}

sealed interface ToolProposalValidationResult {
    data class Accepted(val value: ValidatedToolProposal) : ToolProposalValidationResult
    data class Rejected(
        val reason: ToolProposalRejectionReason,
        val audit: ToolProposalValidationAudit,
    ) : ToolProposalValidationResult
}
