package io.redi.robot.firstlife.runtime

import io.redi.robot.trust.runtime.ApprovalConsumptionAttempt
import io.redi.robot.trust.runtime.ApprovalConsumptionPort
import io.redi.robot.trust.runtime.ApprovalConsumptionReceipt
import io.redi.robot.trust.runtime.ApprovalConsumptionResult
import io.redi.robot.trust.runtime.AuthorizationEvidenceReference

/**
 * Ephemeral H030 compare-and-consume implementation for the First Life demo session. Clearing it
 * on runtime reset restores no approval or authority. It does not issue H014 approval records.
 */
class SessionApprovalConsumption : ApprovalConsumptionPort {
    private val receipts = linkedMapOf<String, ApprovalConsumptionReceipt>()

    @Synchronized
    override fun consumeOnce(attempt: ApprovalConsumptionAttempt): ApprovalConsumptionResult {
        val key = attempt.approvalId.value
        val existing = receipts[key]
        if (existing != null) {
            return if (
                existing.authorizationReference == attempt.authorizationReference &&
                existing.requestId == attempt.requestId
            ) ApprovalConsumptionResult.AlreadyConsumed(existing)
            else ApprovalConsumptionResult.BindingConflict
        }
        val evidence = AuthorizationEvidenceReference.parse("firstlife.approval/consumed")
        val reference = when (evidence) {
            is io.redi.robot.trust.runtime.AuthorizationGateModelResult.Success -> evidence.value
            is io.redi.robot.trust.runtime.AuthorizationGateModelResult.Failure -> return ApprovalConsumptionResult.Failed
        }
        val receipt = ApprovalConsumptionReceipt(
            attempt.approvalId,
            attempt.authorizationReference,
            attempt.requestId,
            attempt.decisionId,
            attempt.consumedAt,
            reference,
        )
        receipts[key] = receipt
        return ApprovalConsumptionResult.Consumed(receipt)
    }

    @Synchronized
    fun clearForRuntimeReset() {
        receipts.clear()
    }

    @Synchronized
    fun consumedCount(): Int = receipts.size
}
