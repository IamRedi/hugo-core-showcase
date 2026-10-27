package io.redi.robot.body.looi.protocol.profile

import io.redi.robot.body.looi.protocol.inventory.GattCapabilityInventory
import io.redi.robot.body.looi.protocol.inventory.GattCapabilityService

private const val BLUETOOTH_16BIT_SUFFIX = "-0000-1000-8000-00805f9b34fb"

/** Versioned protocol profile definitions derived only from observed GATT capability evidence. */
enum class LooiProtocolProfile(
    val version: Int,
    val requiredCharacteristicShortIds: Set<String>,
) {
    PROFILE_A(
        version = 1,
        requiredCharacteristicShortIds = setOf(
            "FED0",
            "FED1",
            "FED2",
            "FED8",
            "FED9",
            "FEDA",
        ),
    ),

    PROFILE_B(
        version = 1,
        requiredCharacteristicShortIds = setOf(
            "FED4",
            "FED5",
            "FED6",
            "FED7",
            "FED8",
        ),
    );

    val modelId: String get() = "$name/v$version"
}

enum class LooiProfileSelectionMode {
    COMPATIBLE,
    AMBIGUOUS,
    PARTIAL,
    UNKNOWN,
    RESEARCH_ONLY_UNKNOWN,
}

data class LooiProfileAssessment(
    val profile: LooiProtocolProfile,
    val matched: Set<String>,
    val missing: Set<String>,
    val observed: Set<String>,
)

/**
 * Selection result is intentionally conservative.
 * Only one exact profile match may move the robot forward.
 */
data class LooiProtocolProfileSelection(
    val mode: LooiProfileSelectionMode,
    val selectedProfile: LooiProtocolProfile? = null,
    val compatibleProfiles: Set<LooiProtocolProfile> = emptySet(),
    val partialProfiles: Map<LooiProtocolProfile, Set<String>> = emptyMap(),
    val observedCharacteristicShortIds: Set<String> = emptySet(),
    val rejectionReason: String,
) {
    val canControl: Boolean get() = mode == LooiProfileSelectionMode.COMPATIBLE
}

private fun toShortCharacteristicId(uuid: String): String? {
    val normalized = uuid.lowercase()
    val shortPattern = Regex("^[0-9a-f]{4}$")
    if (shortPattern.matches(normalized)) {
        return normalized.uppercase()
    }

    if (normalized.endsWith(BLUETOOTH_16BIT_SUFFIX)) {
        val beforeSuffix = normalized.substringBefore(BLUETOOTH_16BIT_SUFFIX)
        if (beforeSuffix.length == 8 && beforeSuffix.startsWith("0000")) {
            return beforeSuffix.substring(4).uppercase()
        }
    }

    return null
}

private fun observedCharacteristicIds(inventory: GattCapabilityInventory): Set<String> = inventory
    .services
    .flatMap(GattCapabilityService::characteristics)
    .mapNotNull { toShortCharacteristicId(it.uuid) }
    .toSet()

private fun assessProfile(profile: LooiProtocolProfile, observedShortIds: Set<String>): LooiProfileAssessment {
    val matched = profile.requiredCharacteristicShortIds.intersect(observedShortIds)
    val missing = profile.requiredCharacteristicShortIds - matched
    return LooiProfileAssessment(profile, matched, missing, observedShortIds)
}

fun selectLooiProtocolProfile(inventory: GattCapabilityInventory): LooiProtocolProfileSelection {
    val observedShortIds = observedCharacteristicIds(inventory)

    if (observedShortIds.isEmpty()) {
        return LooiProtocolProfileSelection(
            mode = LooiProfileSelectionMode.RESEARCH_ONLY_UNKNOWN,
            rejectionReason = "No short GATT characteristic ids were observed.",
        )
    }

    val assessments = LooiProtocolProfile.entries.associateWith { assessProfile(it, observedShortIds) }
    val compatibleProfiles = assessments
        .filterValues { it.missing.isEmpty() }
        .keys
        .toSet()

    if (compatibleProfiles.size == 1) {
        return LooiProtocolProfileSelection(
            mode = LooiProfileSelectionMode.COMPATIBLE,
            selectedProfile = compatibleProfiles.single(),
            compatibleProfiles = compatibleProfiles,
            observedCharacteristicShortIds = observedShortIds,
            rejectionReason = "exactly one profile matched current allowlist",
        )
    }

    if (compatibleProfiles.size > 1) {
        return LooiProtocolProfileSelection(
            mode = LooiProfileSelectionMode.AMBIGUOUS,
            compatibleProfiles = compatibleProfiles,
            observedCharacteristicShortIds = observedShortIds,
            rejectionReason = "multiple exact profiles matched; selection is ambiguous and must remain research-only",
        )
    }

    val partialProfiles = assessments
        .filterValues { it.missing.isNotEmpty() && it.matched.isNotEmpty() }
        .mapValues { it.value.missing }

    if (partialProfiles.isNotEmpty()) {
        return LooiProtocolProfileSelection(
            mode = LooiProfileSelectionMode.PARTIAL,
            partialProfiles = partialProfiles,
            observedCharacteristicShortIds = observedShortIds,
            rejectionReason = "no exact profile match; only partial evidence available",
        )
    }

    return LooiProtocolProfileSelection(
        mode = LooiProfileSelectionMode.UNKNOWN,
        observedCharacteristicShortIds = observedShortIds,
        rejectionReason = "no supported profile requirements were observed",
    )
}
