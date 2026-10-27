package io.redi.robot.feature.providers

import io.redi.robot.adapter.compatibility.CompatibilityFindingCode
import io.redi.robot.adapter.compatibility.CompatibilityReportStatus
import io.redi.robot.capability.negotiation.CapabilityNegotiationReason
import io.redi.robot.capability.negotiation.CapabilityNegotiationStatus
import io.redi.robot.capability.provider.ProviderAvailabilityReason
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.connection.api.ConnectionAvailability
import io.redi.robot.connection.api.ConnectionAvailabilityReason
import io.redi.robot.discovery.identity.IdentityResolutionStatus
import io.redi.robot.manifest.resolution.ManifestResolutionStatus
import io.redi.robot.provider.calibration.CalibrationItemId
import io.redi.robot.provider.calibration.CalibrationNegotiationReadinessStatus
import io.redi.robot.provider.calibration.CalibrationStatus
import io.redi.robot.provider.storage.ProviderInstanceKnowledgeStatus
import io.redi.robot.trust.runtime.AuthorizationDecisionStatus

sealed interface ProviderBlockedEvidence {
    val alias: RedactedProviderAlias?

    data class Identity(
        val status: IdentityResolutionStatus,
        override val alias: RedactedProviderAlias? = null,
    ) : ProviderBlockedEvidence

    data class Manifest(
        val status: ManifestResolutionStatus,
        override val alias: RedactedProviderAlias? = null,
    ) : ProviderBlockedEvidence

    data class Compatibility(
        val status: CompatibilityReportStatus,
        val findings: Set<CompatibilityFindingCode> = emptySet(),
        override val alias: RedactedProviderAlias? = null,
    ) : ProviderBlockedEvidence

    data class Connection(
        val availability: ConnectionAvailability,
        val reason: ConnectionAvailabilityReason?,
        override val alias: RedactedProviderAlias? = null,
    ) : ProviderBlockedEvidence

    data class Negotiation(
        val status: CapabilityNegotiationStatus,
        val reason: CapabilityNegotiationReason,
        override val alias: RedactedProviderAlias? = null,
    ) : ProviderBlockedEvidence

    data class Authorization(
        val status: AuthorizationDecisionStatus,
        override val alias: RedactedProviderAlias? = null,
    ) : ProviderBlockedEvidence

    data class Calibration(
        val status: CalibrationStatus,
        val unmetItems: List<CalibrationItemId> = emptyList(),
        override val alias: RedactedProviderAlias? = null,
    ) : ProviderBlockedEvidence

    data class CalibrationReadiness(
        val status: CalibrationNegotiationReadinessStatus,
        val unmetItems: List<CalibrationItemId> = emptyList(),
        override val alias: RedactedProviderAlias? = null,
    ) : ProviderBlockedEvidence

    data class RuntimeAvailability(
        val availability: ProviderRuntimeAvailability,
        val reason: ProviderAvailabilityReason?,
        override val alias: RedactedProviderAlias? = null,
    ) : ProviderBlockedEvidence

    data class StoredKnowledge(
        val status: ProviderInstanceKnowledgeStatus,
        override val alias: RedactedProviderAlias? = null,
    ) : ProviderBlockedEvidence

    /** No raw failure detail is accepted or rendered. */
    data class Unmapped(override val alias: RedactedProviderAlias? = null) : ProviderBlockedEvidence
}

/** Pure deterministic translation of accepted H022-H032 states into presentation-only truth. */
object ProviderBlockedStateMapper {
    fun map(evidence: ProviderBlockedEvidence): ProviderBlockedMappingResult = when (evidence) {
        is ProviderBlockedEvidence.Identity -> identity(evidence)
        is ProviderBlockedEvidence.Manifest -> manifest(evidence)
        is ProviderBlockedEvidence.Compatibility -> compatibility(evidence)
        is ProviderBlockedEvidence.Connection -> connection(evidence)
        is ProviderBlockedEvidence.Negotiation -> negotiation(evidence)
        is ProviderBlockedEvidence.Authorization -> authorization(evidence)
        is ProviderBlockedEvidence.Calibration -> calibration(evidence)
        is ProviderBlockedEvidence.CalibrationReadiness -> calibrationReadiness(evidence)
        is ProviderBlockedEvidence.RuntimeAvailability -> runtime(evidence)
        is ProviderBlockedEvidence.StoredKnowledge -> storedKnowledge(evidence)
        is ProviderBlockedEvidence.Unmapped -> generic(evidence.alias)
    }

    private fun identity(value: ProviderBlockedEvidence.Identity) = when (value.status) {
        IdentityResolutionStatus.MATCHED_KNOWN_PROVIDER,
        IdentityResolutionStatus.NEW_PROVIDER_CANDIDATE,
        -> ProviderBlockedMappingResult.NotBlocked
        IdentityResolutionStatus.AMBIGUOUS -> state(
            ProviderBlockedKind.IDENTITY_AMBIGUOUS,
            value.alias,
            "Identity is ambiguous",
            "I found more than one plausible provider identity.",
            "I cannot confirm which provider instance this is.",
            "Choosing one without evidence could bind the wrong provider.",
            "Review or confirm the provider identity when identification support is available.",
            ProviderResolutionAvailability.FUTURE_FEATURE_REQUIRED,
        )
        IdentityResolutionStatus.CONFLICTING_EVIDENCE -> state(
            ProviderBlockedKind.IDENTITY_CONFLICT,
            value.alias,
            "Identity evidence conflicts",
            "I detected identity evidence that points in different directions.",
            "I do not know which evidence is correct.",
            "Conflicting evidence cannot establish a safe identity.",
            "Resolve or re-confirm the provider identity before continuing.",
            ProviderResolutionAvailability.FUTURE_FEATURE_REQUIRED,
        )
        IdentityResolutionStatus.INSUFFICIENT_EVIDENCE -> unknownIdentity(value.alias)
        IdentityResolutionStatus.STALE_EVIDENCE -> stale(value.alias)
        IdentityResolutionStatus.REJECTED -> generic(value.alias)
    }

    private fun manifest(value: ProviderBlockedEvidence.Manifest) = when (value.status) {
        ManifestResolutionStatus.RESOLVED -> ProviderBlockedMappingResult.NotBlocked
        ManifestResolutionStatus.NOT_FOUND -> state(
            ProviderBlockedKind.MANIFEST_MISSING,
            value.alias,
            "Provider definition is missing",
            "I have provider identity evidence.",
            "I do not have a validated manifest describing this provider.",
            "Capabilities and adapter requirements cannot be established without that definition.",
            "Add a validated manifest through a future provider setup workflow.",
            ProviderResolutionAvailability.FUTURE_FEATURE_REQUIRED,
        )
        ManifestResolutionStatus.AMBIGUOUS,
        ManifestResolutionStatus.CONFLICTING_MANIFESTS,
        ManifestResolutionStatus.DIGEST_MISMATCH,
        -> manifestConflict(value.alias)
        ManifestResolutionStatus.IDENTITY_MISMATCH,
        ManifestResolutionStatus.INCOMPATIBLE_PROVIDER_FAMILY,
        -> providerIncompatible(value.alias)
        ManifestResolutionStatus.INCOMPATIBLE_ADAPTER -> adapterIncompatible(value.alias)
        ManifestResolutionStatus.UNSUPPORTED_SCHEMA -> unsupported(value.alias)
        ManifestResolutionStatus.REJECTED -> generic(value.alias)
    }

    private fun compatibility(value: ProviderBlockedEvidence.Compatibility): ProviderBlockedMappingResult {
        if (value.status == CompatibilityReportStatus.COMPATIBLE) return ProviderBlockedMappingResult.NotBlocked
        if (value.status == CompatibilityReportStatus.MISSING_ADAPTER ||
            CompatibilityFindingCode.MISSING_ADAPTER in value.findings
        ) return adapterMissing(value.alias)
        if (CompatibilityFindingCode.PROVIDER_FAMILY_MISMATCH in value.findings ||
            CompatibilityFindingCode.IDENTITY_MISMATCH in value.findings
        ) return providerIncompatible(value.alias)
        if (CompatibilityFindingCode.ADAPTER_IDENTITY_MISMATCH in value.findings ||
            CompatibilityFindingCode.UNSUPPORTED_VERSION in value.findings
        ) return adapterIncompatible(value.alias)
        if (CompatibilityFindingCode.UNSUPPORTED_CONNECTION_METHOD in value.findings) {
            return connectionUnavailable(value.alias)
        }
        if (CompatibilityFindingCode.UNSUPPORTED_SCHEMA in value.findings) return unsupported(value.alias)
        return when (value.status) {
            CompatibilityReportStatus.PARTIALLY_COMPATIBLE,
            CompatibilityReportStatus.INCOMPATIBLE,
            -> providerIncompatible(value.alias)
            CompatibilityReportStatus.UNKNOWN -> generic(value.alias)
            CompatibilityReportStatus.COMPATIBLE,
            CompatibilityReportStatus.MISSING_ADAPTER,
            -> error("Handled above")
        }
    }

    private fun connection(value: ProviderBlockedEvidence.Connection) =
        if (value.availability == ConnectionAvailability.AVAILABLE) ProviderBlockedMappingResult.NotBlocked
        else connectionUnavailable(value.alias)

    private fun negotiation(value: ProviderBlockedEvidence.Negotiation) = when (value.status) {
        CapabilityNegotiationStatus.NEGOTIATED,
        CapabilityNegotiationStatus.PARTIALLY_NEGOTIATED,
        -> ProviderBlockedMappingResult.NotBlocked
        CapabilityNegotiationStatus.RUNTIME_UNAVAILABLE,
        CapabilityNegotiationStatus.PROVIDER_NOT_READY,
        -> providerUnavailable(value.alias)
        CapabilityNegotiationStatus.STALE_SESSION -> stale(value.alias)
        CapabilityNegotiationStatus.UNSUPPORTED,
        CapabilityNegotiationStatus.VERSION_MISMATCH,
        CapabilityNegotiationStatus.MISSING_PREREQUISITE,
        CapabilityNegotiationStatus.CONFLICTING_DECLARATION,
        -> capabilityNotNegotiated(value.alias)
        CapabilityNegotiationStatus.FAILED,
        CapabilityNegotiationStatus.CANCELLED,
        -> generic(value.alias)
    }

    private fun authorization(value: ProviderBlockedEvidence.Authorization) = when (value.status) {
        AuthorizationDecisionStatus.AUTHORIZED -> ProviderBlockedMappingResult.NotBlocked
        AuthorizationDecisionStatus.TRUST_REQUIRED -> authority(
            ProviderBlockedKind.TRUST_REQUIRED,
            value.alias,
            "Trust is required",
            "The provider and capability request are known.",
            "No valid trust decision is available.",
            "Identity knowledge does not grant trust.",
            "Complete a future trust workflow before continuing.",
        )
        AuthorizationDecisionStatus.PERMISSION_REQUIRED -> authority(
            ProviderBlockedKind.PERMISSION_REQUIRED,
            value.alias,
            "Permission is required",
            "The requested capability is known.",
            "The required permission has not been granted.",
            "A known capability cannot run without explicit permission.",
            "Ask an authorized user to grant the required permission.",
        )
        AuthorizationDecisionStatus.APPROVAL_REQUIRED -> authority(
            ProviderBlockedKind.APPROVAL_REQUIRED,
            value.alias,
            "Approval is required",
            "The request reached the authorization gate.",
            "No valid approval is available for this request.",
            "The request requires explicit approval before dispatch.",
            "Complete a future approval workflow.",
        )
        AuthorizationDecisionStatus.PROHIBITED -> prohibited(value.alias)
        AuthorizationDecisionStatus.STALE_NEGOTIATION,
        AuthorizationDecisionStatus.EXPIRED,
        -> stale(value.alias)
        else -> generic(value.alias)
    }

    private fun calibration(value: ProviderBlockedEvidence.Calibration) = when (value.status) {
        CalibrationStatus.VERIFIED -> ProviderBlockedMappingResult.NotBlocked
        CalibrationStatus.REQUIRED,
        CalibrationStatus.NOT_CALIBRATED,
        CalibrationStatus.IN_PROGRESS,
        CalibrationStatus.CANDIDATE,
        -> calibrationRequired(value.alias, value.unmetItems)
        CalibrationStatus.EXPIRED,
        CalibrationStatus.INVALIDATED,
        CalibrationStatus.FAILED,
        -> calibrationInvalid(value.alias, value.unmetItems)
        CalibrationStatus.UNSUPPORTED -> unsupported(value.alias)
    }

    private fun calibrationReadiness(value: ProviderBlockedEvidence.CalibrationReadiness) = when (value.status) {
        CalibrationNegotiationReadinessStatus.READY -> ProviderBlockedMappingResult.NotBlocked
        CalibrationNegotiationReadinessStatus.CALIBRATION_REQUIRED ->
            calibrationRequired(value.alias, value.unmetItems)
        CalibrationNegotiationReadinessStatus.STALE_NEGOTIATION -> stale(value.alias)
        CalibrationNegotiationReadinessStatus.INVALID -> calibrationInvalid(value.alias, value.unmetItems)
    }

    private fun runtime(value: ProviderBlockedEvidence.RuntimeAvailability) =
        if (value.availability == ProviderRuntimeAvailability.AVAILABLE) ProviderBlockedMappingResult.NotBlocked
        else providerUnavailable(value.alias)

    private fun storedKnowledge(value: ProviderBlockedEvidence.StoredKnowledge) = when (value.status) {
        ProviderInstanceKnowledgeStatus.KNOWN -> ProviderBlockedMappingResult.NotBlocked
        ProviderInstanceKnowledgeStatus.DISABLED,
        ProviderInstanceKnowledgeStatus.RETIRED,
        -> prohibited(value.alias)
    }

    private fun unknownIdentity(alias: RedactedProviderAlias?) = state(
        ProviderBlockedKind.IDENTITY_UNKNOWN,
        alias,
        "Identity is not confirmed",
        "I detected a possible provider.",
        "I cannot safely confirm its identity.",
        "Proceeding could associate the wrong provider.",
        "Identify or confirm this provider through a future setup workflow.",
        ProviderResolutionAvailability.FUTURE_FEATURE_REQUIRED,
    )

    private fun stale(alias: RedactedProviderAlias?) = state(
        ProviderBlockedKind.STALE_EVIDENCE,
        alias,
        "Provider information is stale",
        "I have older provider evidence.",
        "I do not know whether it still describes the current provider state.",
        "Stale evidence cannot authorize a current action.",
        "Wait for fresh provider evidence, then check again.",
        ProviderResolutionAvailability.WAIT_AND_RETRY,
    )

    private fun manifestConflict(alias: RedactedProviderAlias?) = state(
        ProviderBlockedKind.MANIFEST_CONFLICT,
        alias,
        "Provider definitions conflict",
        "I found provider manifest information.",
        "I cannot establish one validated definition.",
        "Conflicting or invalid definitions cannot be selected safely.",
        "Resolve the manifest conflict through a future provider-management workflow.",
        ProviderResolutionAvailability.FUTURE_FEATURE_REQUIRED,
    )

    private fun adapterMissing(alias: RedactedProviderAlias?) = state(
        ProviderBlockedKind.ADAPTER_MISSING,
        alias,
        "A compatible adapter is missing",
        "I recognize the provider definition.",
        "I do not know a compatible adapter that can integrate it.",
        "A manifest alone cannot connect to or execute this provider.",
        "Install or develop a compatible adapter through a future workflow.",
        ProviderResolutionAvailability.FUTURE_FEATURE_REQUIRED,
    )

    private fun adapterIncompatible(alias: RedactedProviderAlias?) = state(
        ProviderBlockedKind.ADAPTER_INCOMPATIBLE,
        alias,
        "The adapter is incompatible",
        "I found an adapter candidate for this provider.",
        "I do not have evidence that its identity and version are compatible.",
        "An incompatible adapter cannot be selected safely.",
        "Provide a compatible adapter version through a future setup workflow.",
        ProviderResolutionAvailability.FUTURE_FEATURE_REQUIRED,
    )

    private fun providerIncompatible(alias: RedactedProviderAlias?) = state(
        ProviderBlockedKind.PROVIDER_INCOMPATIBLE,
        alias,
        "The provider is incompatible",
        "I have provider definition or compatibility evidence.",
        "I cannot establish compatibility with the current HUGO contracts.",
        "Unsupported family, version, or constraints prevent safe integration.",
        "Use a compatible provider definition and adapter.",
        ProviderResolutionAvailability.FUTURE_FEATURE_REQUIRED,
    )

    private fun connectionUnavailable(alias: RedactedProviderAlias?) = state(
        ProviderBlockedKind.CONNECTION_UNAVAILABLE,
        alias,
        "Connection is unavailable",
        "I know which connection method is required.",
        "That method is not currently available.",
        "No connection was established, so the provider cannot proceed.",
        "Restore the required connection method or platform permission, then check again.",
        ProviderResolutionAvailability.USER_CAN_RESOLVE_NOW,
    )

    private fun capabilityNotNegotiated(alias: RedactedProviderAlias?) = state(
        ProviderBlockedKind.CAPABILITY_NOT_NEGOTIATED,
        alias,
        "Capability is not negotiated",
        "The provider is known to the integration flow.",
        "I do not have a current compatible capability contract.",
        "Only negotiated capabilities can proceed to authorization.",
        "Resolve the reported compatibility or runtime prerequisite, then negotiate again.",
        ProviderResolutionAvailability.USER_CAN_RESOLVE_NOW,
    )

    private fun calibrationRequired(alias: RedactedProviderAlias?, items: List<CalibrationItemId>) = state(
        ProviderBlockedKind.CALIBRATION_REQUIRED,
        alias,
        "Calibration is required",
        "This provider declares calibration requirements for the requested capability.",
        "I do not have verified current calibration for every required item.",
        "HUGO does not guess calibration values.",
        "Complete the required calibration through a future calibration workflow.",
        ProviderResolutionAvailability.FUTURE_FEATURE_REQUIRED,
        items.map { "Required calibration: ${it.value}" },
    )

    private fun calibrationInvalid(alias: RedactedProviderAlias?, items: List<CalibrationItemId>) = state(
        ProviderBlockedKind.CALIBRATION_INVALID,
        alias,
        "Calibration is no longer valid",
        "Calibration evidence exists for this provider.",
        "I cannot verify that it is current and valid for this provider state.",
        "Expired, invalidated, or failed calibration cannot enable the capability.",
        "Recalibrate through a future calibration workflow.",
        ProviderResolutionAvailability.FUTURE_FEATURE_REQUIRED,
        items.map { "Calibration needing attention: ${it.value}" },
    )

    private fun providerUnavailable(alias: RedactedProviderAlias?) = state(
        ProviderBlockedKind.PROVIDER_UNAVAILABLE,
        alias,
        "Provider is unavailable",
        "The provider or capability definition is known.",
        "I do not have current runtime availability evidence.",
        "Known identity does not mean the provider is available now.",
        "Restore provider availability and wait for fresh runtime evidence.",
        ProviderResolutionAvailability.WAIT_AND_RETRY,
    )

    private fun unsupported(alias: RedactedProviderAlias?) = state(
        ProviderBlockedKind.UNSUPPORTED_PROVIDER,
        alias,
        "Provider is not supported",
        "I found provider information.",
        "I do not have supported contracts for this provider state.",
        "Unsupported schemas or capabilities cannot proceed safely.",
        "A future HUGO or adapter update is required.",
        ProviderResolutionAvailability.FUTURE_FEATURE_REQUIRED,
    )

    private fun prohibited(alias: RedactedProviderAlias?) = state(
        ProviderBlockedKind.PROHIBITED,
        alias,
        "This action is prohibited",
        "The request was evaluated against current policy.",
        "I cannot treat a prohibited request as authorized.",
        "Current policy explicitly prevents this action.",
        "An authorized user or policy owner must review the restriction.",
        ProviderResolutionAvailability.ADMIN_OR_POLICY_REQUIRED,
    )

    private fun generic(alias: RedactedProviderAlias?) = state(
        ProviderBlockedKind.GENERIC_BLOCKED,
        alias,
        "Provider cannot proceed",
        "I received a blocked provider result.",
        "I do not have a more specific safe explanation.",
        "Unknown failures must remain blocked rather than being guessed.",
        "Review provider diagnostics or wait for a supported resolution workflow.",
        ProviderResolutionAvailability.FUTURE_FEATURE_REQUIRED,
    )

    private fun authority(
        kind: ProviderBlockedKind,
        alias: RedactedProviderAlias?,
        title: String,
        known: String,
        unknown: String,
        reason: String,
        next: String,
    ) = state(
        kind,
        alias,
        title,
        known,
        unknown,
        reason,
        next,
        ProviderResolutionAvailability.ADMIN_OR_POLICY_REQUIRED,
    )

    @Suppress("LongParameterList")
    private fun state(
        kind: ProviderBlockedKind,
        alias: RedactedProviderAlias?,
        title: String,
        known: String,
        unknown: String,
        reason: String,
        next: String,
        availability: ProviderResolutionAvailability,
        details: List<String> = emptyList(),
    ) = ProviderBlockedMappingResult.Blocked(
        ProviderBlockedUiState(kind, safeAlias(alias), title, known, unknown, reason, next, availability, details),
    )

    private fun safeAlias(alias: RedactedProviderAlias?): RedactedProviderAlias? {
        val value = alias?.value ?: return null
        return alias.takeUnless {
            uuidText.containsMatchIn(value) || ipv4Address.containsMatchIn(value) ||
                macAddress.containsMatchIn(value) || accountLikeText.containsMatchIn(value)
        }
    }

    private val uuidText = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val ipv4Address = Regex("(?:^|[^0-9])(?:[0-9]{1,3}[.]){3}[0-9]{1,3}(?:$|[^0-9])")
    private val macAddress = Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
    private val accountLikeText = Regex("[^@\\s]+@[^@\\s]+[.][^@\\s]+")
}
