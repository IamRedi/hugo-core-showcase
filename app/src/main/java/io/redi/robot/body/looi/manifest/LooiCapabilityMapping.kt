package io.redi.robot.body.looi.manifest

import io.redi.robot.capability.api.CapabilityApprovalRequirement
import io.redi.robot.capability.api.CapabilityAvailabilitySemantics
import io.redi.robot.capability.api.CapabilityCancellationSemantics
import io.redi.robot.capability.api.CapabilityDescription
import io.redi.robot.capability.api.CapabilityContractReference
import io.redi.robot.capability.api.CapabilityDescriptor
import io.redi.robot.capability.api.CapabilityDescriptorResult
import io.redi.robot.capability.api.CapabilityEffect
import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityLabel
import io.redi.robot.capability.api.CapabilityPermissionIntent
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.CapabilityRiskClass
import io.redi.robot.capability.api.CapabilitySafetyClass

/** Adapter-side H017 ledger; it declares semantics only and never dispatches a request. */
class LooiCapabilityMapping private constructor(
    val descriptor: CapabilityDescriptor,
    evidence: List<LooiCapabilityEvidence>,
    val limits: Set<LooiVerifiedCapabilityLimit>,
    val boundary: ExistingLooiImplementationBoundary,
) {
    val evidence: List<LooiCapabilityEvidence> = evidence.toList()

    /** Actuation can only be delegated later through the already verified local safety path. */
    val preservesExistingSafetyPath: Boolean = descriptor.effect != CapabilityEffect.ACT ||
        boundary in setOf(
            ExistingLooiImplementationBoundary.EXISTING_DRIVE_SAFETY_PATH,
            ExistingLooiImplementationBoundary.EXISTING_HEAD_SAFETY_PATH,
            ExistingLooiImplementationBoundary.EXISTING_LIGHT_SAFETY_PATH,
        )

    companion object {
        internal fun create(
            descriptor: CapabilityDescriptor,
            evidence: List<LooiCapabilityEvidence>,
            limits: Set<LooiVerifiedCapabilityLimit>,
            boundary: ExistingLooiImplementationBoundary,
        ): LooiCapabilityMapping = LooiCapabilityMapping(descriptor, evidence, limits, boundary)
    }
}

/** Hardware evidence sources, not protocol or implementation claims. */
enum class LooiCapabilityEvidence {
    T011_PHYSICAL_CONNECTION_LIFECYCLE,
    T031_PHYSICAL_LOWER_CONTACT_MAPPING,
    T032_PHYSICAL_MANUAL_ACCEPTANCE,
}

/** Semantic constraints only; never byte values, endpoints, or transport configuration. */
enum class LooiVerifiedCapabilityLimit {
    PRIORITIZED_NEUTRAL_STOP,
    CALIBRATED_FOUR_DIRECTION_MANUAL_MOTION,
    RELEASE_TO_NEUTRAL,
    BOUNDED_HEAD_PRESETS,
    LIGHT_OFF_OR_MINIMUM_ON,
    FRESH_DIRECTIONAL_LOWER_CONTACT_STATUS,
    CONNECTION_STATE_OBSERVATION_ONLY,
}

/** Existing implementation ownership; no protocol object is exposed through it. */
enum class ExistingLooiImplementationBoundary {
    EXISTING_DRIVE_SAFETY_PATH,
    EXISTING_HEAD_SAFETY_PATH,
    EXISTING_LIGHT_SAFETY_PATH,
    EXISTING_LOWER_CONTACT_SAFETY_OBSERVATION,
    EXISTING_CONNECTION_STATE,
}

sealed interface LooiCapabilityLookupResult {
    data class Supported(val mapping: LooiCapabilityMapping) : LooiCapabilityLookupResult

    data class Unsupported(val requested: CapabilityReference) : LooiCapabilityLookupResult
}

/** Exact evidence-backed H017 set; any later capability requires a new evidence/mapping task. */
object LooiCapabilityCatalog {
    val supported: List<LooiCapabilityMapping> = listOf(
        mapping(
            "mobility.stop@1.0", "Stop mobility", "Requests the existing prioritized neutral stop path.",
            null, null,
            CapabilityEffect.ACT, CapabilityRiskClass.SAFETY_CRITICAL,
            CapabilitySafetyClass.SAFETY_CRITICAL_ACTION, CapabilityApprovalRequirement.WITHIN_GRANT,
            CapabilityCancellationSemantics.NOT_APPLICABLE,
            listOf(LooiCapabilityEvidence.T032_PHYSICAL_MANUAL_ACCEPTANCE),
            setOf(LooiVerifiedCapabilityLimit.PRIORITIZED_NEUTRAL_STOP),
            ExistingLooiImplementationBoundary.EXISTING_DRIVE_SAFETY_PATH,
        ),
        mapping(
            "mobility.move@1.0", "Move mobility", "Requests existing manually calibrated directional movement only.",
            "hugo.contract.mobility_move_input@1.0", null,
            CapabilityEffect.ACT, CapabilityRiskClass.SAFETY_CRITICAL,
            CapabilitySafetyClass.SAFETY_CRITICAL_ACTION, CapabilityApprovalRequirement.EVERY_REQUEST,
            CapabilityCancellationSemantics.REQUIRED,
            listOf(LooiCapabilityEvidence.T032_PHYSICAL_MANUAL_ACCEPTANCE),
            setOf(
                LooiVerifiedCapabilityLimit.CALIBRATED_FOUR_DIRECTION_MANUAL_MOTION,
                LooiVerifiedCapabilityLimit.RELEASE_TO_NEUTRAL,
            ),
            ExistingLooiImplementationBoundary.EXISTING_DRIVE_SAFETY_PATH,
        ),
        mapping(
            "body.head.position.control@1.0", "Control body head position", "Requests existing bounded manual head positioning.",
            "hugo.contract.body_head_position_input@1.0", null,
            CapabilityEffect.ACT, CapabilityRiskClass.ELEVATED, CapabilitySafetyClass.PHYSICAL_ACTION,
            CapabilityApprovalRequirement.EVERY_REQUEST, CapabilityCancellationSemantics.SUPPORTED,
            listOf(LooiCapabilityEvidence.T032_PHYSICAL_MANUAL_ACCEPTANCE),
            setOf(LooiVerifiedCapabilityLimit.BOUNDED_HEAD_PRESETS),
            ExistingLooiImplementationBoundary.EXISTING_HEAD_SAFETY_PATH,
        ),
        mapping(
            "body.light.control@1.0", "Control body light", "Requests existing light off or minimum-on control only.",
            "hugo.contract.body_light_input@1.0", null,
            CapabilityEffect.ACT, CapabilityRiskClass.LOW, CapabilitySafetyClass.PHYSICAL_ACTION,
            CapabilityApprovalRequirement.WITHIN_GRANT, CapabilityCancellationSemantics.NOT_APPLICABLE,
            listOf(LooiCapabilityEvidence.T032_PHYSICAL_MANUAL_ACCEPTANCE),
            setOf(LooiVerifiedCapabilityLimit.LIGHT_OFF_OR_MINIMUM_ON),
            ExistingLooiImplementationBoundary.EXISTING_LIGHT_SAFETY_PATH,
        ),
        mapping(
            "safety.lower_contact.status@1.0", "Observe lower contact safety status", "Observes verified fresh directional lower-contact safety state.",
            null, "hugo.contract.lower_contact_status_output@1.0",
            CapabilityEffect.OBSERVE, CapabilityRiskClass.ELEVATED, CapabilitySafetyClass.PHYSICAL_OBSERVATION,
            CapabilityApprovalRequirement.WITHIN_GRANT, CapabilityCancellationSemantics.NOT_APPLICABLE,
            listOf(
                LooiCapabilityEvidence.T031_PHYSICAL_LOWER_CONTACT_MAPPING,
                LooiCapabilityEvidence.T032_PHYSICAL_MANUAL_ACCEPTANCE,
            ),
            setOf(LooiVerifiedCapabilityLimit.FRESH_DIRECTIONAL_LOWER_CONTACT_STATUS),
            ExistingLooiImplementationBoundary.EXISTING_LOWER_CONTACT_SAFETY_OBSERVATION,
        ),
        mapping(
            "provider.connection.status@1.0", "Observe provider connection status", "Observes provider connection state without granting capability readiness.",
            null, "hugo.contract.provider_connection_status_output@1.0",
            CapabilityEffect.OBSERVE, CapabilityRiskClass.INFORMATIONAL, CapabilitySafetyClass.NON_PHYSICAL,
            CapabilityApprovalRequirement.WITHIN_GRANT, CapabilityCancellationSemantics.NOT_APPLICABLE,
            listOf(
                LooiCapabilityEvidence.T011_PHYSICAL_CONNECTION_LIFECYCLE,
                LooiCapabilityEvidence.T032_PHYSICAL_MANUAL_ACCEPTANCE,
            ),
            setOf(LooiVerifiedCapabilityLimit.CONNECTION_STATE_OBSERVATION_ONLY),
            ExistingLooiImplementationBoundary.EXISTING_CONNECTION_STATE,
        ),
    )

    fun lookup(reference: CapabilityReference): LooiCapabilityLookupResult = supported.firstOrNull {
        it.descriptor.reference == reference
    }?.let(LooiCapabilityLookupResult::Supported) ?: LooiCapabilityLookupResult.Unsupported(reference)

    @Suppress("LongParameterList")
    private fun mapping(
        reference: String,
        label: String,
        description: String,
        inputContract: String?,
        outputContract: String?,
        effect: CapabilityEffect,
        risk: CapabilityRiskClass,
        safety: CapabilitySafetyClass,
        approval: CapabilityApprovalRequirement,
        cancellation: CapabilityCancellationSemantics,
        evidence: List<LooiCapabilityEvidence>,
        limits: Set<LooiVerifiedCapabilityLimit>,
        boundary: ExistingLooiImplementationBoundary,
    ): LooiCapabilityMapping = LooiCapabilityMapping.create(
        descriptor(reference, label, description, inputContract, outputContract, effect, risk, safety, approval, cancellation),
        evidence,
        limits,
        boundary,
    )

    @Suppress("LongParameterList")
    private fun descriptor(
        reference: String,
        label: String,
        description: String,
        inputContract: String?,
        outputContract: String?,
        effect: CapabilityEffect,
        risk: CapabilityRiskClass,
        safety: CapabilitySafetyClass,
        approval: CapabilityApprovalRequirement,
        cancellation: CapabilityCancellationSemantics,
    ): CapabilityDescriptor = requireDescriptor(
        CapabilityDescriptor.create(
            reference = requireReference(reference),
            label = requireDescriptor(CapabilityLabel.parse(label)),
            description = requireDescriptor(CapabilityDescription.parse(description)),
            inputContract = inputContract?.let(::requireContract),
            outputContract = outputContract?.let(::requireContract),
            effect = effect,
            risk = risk,
            safety = safety,
            permissionIntent = when (effect) {
                CapabilityEffect.OBSERVE -> CapabilityPermissionIntent.OBSERVE
                CapabilityEffect.ACT -> CapabilityPermissionIntent.ACT
                CapabilityEffect.MODIFY -> CapabilityPermissionIntent.MODIFY
            },
            approvalRequirement = approval,
            cancellation = cancellation,
            availability = CapabilityAvailabilitySemantics.WHILE_REGISTERED,
            evidence = CapabilityEvidenceClass.HARDWARE_VERIFIED,
        ),
    )

    private fun requireReference(raw: String): CapabilityReference = when (val result = CapabilityReference.parse(raw)) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> error("Invalid static capability reference: $result")
    }

    private fun requireContract(raw: String): CapabilityContractReference = when (
        val result = CapabilityContractReference.parse(raw)
    ) {
        is CapabilityDescriptorResult.Success -> result.value
        is CapabilityDescriptorResult.Failure -> error("Invalid static capability contract: $result")
    }

    private fun <T> requireDescriptor(result: CapabilityDescriptorResult<T>): T = when (result) {
        is CapabilityDescriptorResult.Success -> result.value
        is CapabilityDescriptorResult.Failure -> error("Invalid static capability descriptor: $result")
    }
}
