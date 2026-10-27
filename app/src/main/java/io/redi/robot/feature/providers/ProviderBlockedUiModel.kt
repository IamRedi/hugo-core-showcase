package io.redi.robot.feature.providers

import io.redi.robot.capability.provider.RedactedProviderAlias
import java.util.Collections

enum class ProviderBlockedKind {
    IDENTITY_UNKNOWN,
    IDENTITY_AMBIGUOUS,
    IDENTITY_CONFLICT,
    MANIFEST_MISSING,
    MANIFEST_CONFLICT,
    ADAPTER_MISSING,
    ADAPTER_INCOMPATIBLE,
    PROVIDER_INCOMPATIBLE,
    CONNECTION_UNAVAILABLE,
    CAPABILITY_NOT_NEGOTIATED,
    TRUST_REQUIRED,
    PERMISSION_REQUIRED,
    APPROVAL_REQUIRED,
    CALIBRATION_REQUIRED,
    CALIBRATION_INVALID,
    PROVIDER_UNAVAILABLE,
    STALE_EVIDENCE,
    UNSUPPORTED_PROVIDER,
    PROHIBITED,
    GENERIC_BLOCKED,
}

enum class ProviderResolutionAvailability {
    USER_CAN_RESOLVE_NOW,
    WAIT_AND_RETRY,
    ADMIN_OR_POLICY_REQUIRED,
    FUTURE_FEATURE_REQUIRED,
}

/**
 * Immutable presentation data only. It deliberately has no command, callback, endpoint, provider
 * instance ID, permission token, connection request, or exploratory-control field.
 */
class ProviderBlockedUiState internal constructor(
    val kind: ProviderBlockedKind,
    val providerAlias: RedactedProviderAlias?,
    val title: String,
    val whatIsKnown: String,
    val whatIsUnknown: String,
    val whyBlocked: String,
    val nextStep: String,
    val resolutionAvailability: ProviderResolutionAvailability,
    details: List<String>,
) {
    val details: List<String> = Collections.unmodifiableList(details.distinct().sorted())
    val exposesControlAction: Boolean get() = false
    val permitsTryAnyway: Boolean get() = false
    val grantsTrust: Boolean get() = false
    val grantsPermission: Boolean get() = false
    val startsConnection: Boolean get() = false
    val executesCapability: Boolean get() = false

    override fun toString(): String = "ProviderBlockedUiState(" +
        "kind=$kind, alias=${providerAlias?.value}, resolution=$resolutionAvailability, details=$details)"
}

sealed interface ProviderBlockedMappingResult {
    data class Blocked(val state: ProviderBlockedUiState) : ProviderBlockedMappingResult
    data object NotBlocked : ProviderBlockedMappingResult
}

data class ProviderBlockedTextContent(
    val heading: String,
    val providerLabel: String?,
    val sections: List<Pair<String, String>>,
    val details: List<String>,
    val resolutionLabel: String,
)

/** Pure rendering projection shared by Compose and JVM presentation tests. */
fun ProviderBlockedUiState.textContent(): ProviderBlockedTextContent = ProviderBlockedTextContent(
    heading = title,
    providerLabel = providerAlias?.value,
    sections = listOf(
        "What HUGO knows" to whatIsKnown,
        "What HUGO does not know" to whatIsUnknown,
        "Why HUGO cannot proceed" to whyBlocked,
        "Safe next step" to nextStep,
    ),
    details = details,
    resolutionLabel = when (resolutionAvailability) {
        ProviderResolutionAvailability.USER_CAN_RESOLVE_NOW -> "You can resolve this when the required setup is available."
        ProviderResolutionAvailability.WAIT_AND_RETRY -> "Wait for fresh provider evidence, then check again."
        ProviderResolutionAvailability.ADMIN_OR_POLICY_REQUIRED -> "An authorized user or policy change is required."
        ProviderResolutionAvailability.FUTURE_FEATURE_REQUIRED -> "This requires a future HUGO feature."
    },
)
