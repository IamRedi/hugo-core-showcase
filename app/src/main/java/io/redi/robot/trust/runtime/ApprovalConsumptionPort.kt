package io.redi.robot.trust.runtime

import io.redi.robot.capability.api.CapabilityAuthorizationReference
import io.redi.robot.capability.api.CapabilityRequestId
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.trust.domain.ApprovalId

data class ApprovalConsumptionAttempt(
    val approvalId: ApprovalId,
    val authorizationReference: CapabilityAuthorizationReference,
    val requestId: CapabilityRequestId,
    val decisionId: AuthorizationDecisionId,
    val consumedAt: MonotonicTimestampMs,
)

data class ApprovalConsumptionReceipt(
    val approvalId: ApprovalId,
    val authorizationReference: CapabilityAuthorizationReference,
    val requestId: CapabilityRequestId,
    val decisionId: AuthorizationDecisionId,
    val consumedAt: MonotonicTimestampMs,
    val evidenceReference: AuthorizationEvidenceReference,
)

sealed interface ApprovalConsumptionResult {
    data class Consumed(val receipt: ApprovalConsumptionReceipt) : ApprovalConsumptionResult
    data class AlreadyConsumed(val receipt: ApprovalConsumptionReceipt) : ApprovalConsumptionResult
    data object BindingConflict : ApprovalConsumptionResult
    data object Failed : ApprovalConsumptionResult
}

/**
 * Atomic compare-and-consume boundary. Implementations must permit at most one [Consumed] result
 * for an approval ID, even when concurrent callers present the same still-approved H014 snapshot.
 * Durable persistence and approval issuance are deliberately outside H030.
 */
fun interface ApprovalConsumptionPort {
    fun consumeOnce(attempt: ApprovalConsumptionAttempt): ApprovalConsumptionResult
}
