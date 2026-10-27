package io.redi.robot.provider.calibration

import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.negotiation.CapabilityNegotiationId
import io.redi.robot.capability.negotiation.CapabilityNegotiationReport
import io.redi.robot.capability.negotiation.CapabilityNegotiationStatus
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderInstanceId
import java.util.Collections

enum class CalibrationNegotiationReadinessStatus {
    READY,
    CALIBRATION_REQUIRED,
    STALE_NEGOTIATION,
    INVALID,
}

class CalibrationNegotiationReadiness internal constructor(
    val profileId: CalibrationProfileId,
    val negotiationId: CapabilityNegotiationId,
    val providerInstanceId: ProviderInstanceId,
    val providerGeneration: ProviderGeneration,
    val status: CalibrationNegotiationReadinessStatus,
    blockedCapabilities: List<CapabilityReference>,
    unmetItems: List<CalibrationItemId>,
) {
    val blockedCapabilities: List<CapabilityReference> = Collections.unmodifiableList(
        blockedCapabilities.distinct().sortedBy { it.wireValue },
    )
    val unmetItems: List<CalibrationItemId> = Collections.unmodifiableList(
        unmetItems.distinct().sortedBy { it.value },
    )
    val grantsSafetyApproval: Boolean get() = false
    val grantsExecutionAuthority: Boolean get() = false
    val grantsAuthorization: Boolean get() = false
}

/**
 * Pure H029/H031 correlation only. READY says current negotiated capabilities have no unmet H031
 * requirement; H030 authorization and deterministic safety remain separate mandatory gates.
 */
object CalibrationNegotiationReadinessEvaluator {
    fun evaluate(
        assessment: CalibrationAssessment,
        negotiation: CapabilityNegotiationReport,
        now: MonotonicTimestampMs,
    ): CalibrationNegotiationReadiness {
        val correlated = negotiation.providerInstanceId == assessment.providerInstanceId &&
            negotiation.providerGeneration == assessment.providerGeneration
        val successful = negotiation.status == CapabilityNegotiationStatus.NEGOTIATED ||
            negotiation.status == CapabilityNegotiationStatus.PARTIALLY_NEGOTIATED
        val recordsCorrelated = negotiation.negotiatedCapabilities.all {
            it.providerInstanceId == assessment.providerInstanceId &&
                it.providerGeneration == assessment.providerGeneration &&
                it.negotiationId == negotiation.negotiationId
        }
        val recordsFresh = negotiation.negotiatedCapabilities.all {
            now.value >= it.negotiatedAt.value && now.value < it.validUntil.value
        }
        if (!correlated || !successful || !recordsCorrelated || !recordsFresh) {
            return readiness(
                assessment,
                negotiation,
                CalibrationNegotiationReadinessStatus.STALE_NEGOTIATION,
            )
        }
        if (assessment.status == CalibrationStatus.FAILED) {
            return readiness(
                assessment,
                negotiation,
                CalibrationNegotiationReadinessStatus.INVALID,
            )
        }

        val negotiated = negotiation.negotiatedCapabilities.map { it.capability }.toSet()
        val blocked = assessment.blockedCapabilities.filter { it in negotiated }
        val unmetItems = assessment.items.filter { item ->
            item.necessity == CalibrationNecessity.REQUIRED &&
                item.status != CalibrationStatus.VERIFIED &&
                item.affectedCapabilities.any { it in negotiated }
        }.map { it.itemId }
        return readiness(
            assessment,
            negotiation,
            if (blocked.isEmpty()) {
                CalibrationNegotiationReadinessStatus.READY
            } else CalibrationNegotiationReadinessStatus.CALIBRATION_REQUIRED,
            blocked,
            unmetItems,
        )
    }

    private fun readiness(
        assessment: CalibrationAssessment,
        negotiation: CapabilityNegotiationReport,
        status: CalibrationNegotiationReadinessStatus,
        blocked: List<CapabilityReference> = emptyList(),
        unmetItems: List<CalibrationItemId> = emptyList(),
    ) = CalibrationNegotiationReadiness(
        assessment.profileId,
        negotiation.negotiationId,
        assessment.providerInstanceId,
        assessment.providerGeneration,
        status,
        blocked,
        unmetItems,
    )
}
