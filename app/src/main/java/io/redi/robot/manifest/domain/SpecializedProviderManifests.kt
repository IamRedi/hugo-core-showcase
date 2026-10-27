package io.redi.robot.manifest.domain

import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily

enum class SpecializedManifestKind(val providerFamily: ProviderFamily) {
    BODY(ProviderFamily.BODY_ADAPTER),
    DEVICE(ProviderFamily.DEVICE_ADAPTER),
    SERVICE(ProviderFamily.SERVICE_CONNECTOR),
}

data class CommonManifestBinding(
    val providerDefinitionId: ProviderDefinitionId,
    val digest: ManifestDigest,
) {
    companion object {
        fun from(document: CommonProviderManifestDocument): CommonManifestBinding = CommonManifestBinding(
            providerDefinitionId = document.manifest.provider.definitionId,
            digest = document.digest,
        )
    }
}

data class SpecializedCapabilityGroup(
    val reference: ManifestContractReference,
    val capabilities: List<CapabilityReference>,
)

sealed interface SpecializedProviderManifest {
    val kind: SpecializedManifestKind
    val schemaVersion: ManifestSchemaVersion
    val common: CommonManifestBinding
    val extensions: List<ManifestExtensionReference>
    val capabilityGroups: List<SpecializedCapabilityGroup>
}

enum class BodyMobilityClass {
    STATIONARY,
    GROUND,
    AERIAL,
    MARINE,
    OTHER,
}

enum class BodyPowerRelationship {
    NONE,
    EXTERNAL_POWER,
    BATTERY_POWER,
    DOCK_CHARGING,
}

enum class BodyMountRole {
    MOBILE_HOST,
    CAMERA,
    SENSOR,
    OTHER,
}

data class BodySafetyControllerClaim(
    val reference: ManifestContractReference,
    val localStopRequired: Boolean,
)

data class BodyMountingSupport(
    val supportsMounts: Boolean,
    val supportedRoles: List<BodyMountRole>,
)

/** A versioned geometry extension reference, never dimensions, pose, or runtime attachment state. */
data class BodyGeometryProfileReference(
    val extension: ManifestExtensionReference,
)

class BodyManifest private constructor(
    override val schemaVersion: ManifestSchemaVersion,
    override val common: CommonManifestBinding,
    val bodyClass: ManifestContractReference,
    val mobility: BodyMobilityClass,
    override val capabilityGroups: List<SpecializedCapabilityGroup>,
    val safetyController: BodySafetyControllerClaim?,
    val mountingSupport: BodyMountingSupport,
    val powerRelationship: BodyPowerRelationship,
    val geometryProfile: BodyGeometryProfileReference?,
    val calibrationRequirements: List<ManifestContractReference>,
    val surfaceConstraints: List<ManifestContractReference>,
    val mountingEvidenceCapabilities: List<CapabilityReference>,
    override val extensions: List<ManifestExtensionReference>,
) : SpecializedProviderManifest {
    override val kind: SpecializedManifestKind = SpecializedManifestKind.BODY

    companion object {
        @Suppress("LongParameterList")
        fun create(
            schemaVersion: ManifestSchemaVersion,
            common: CommonManifestBinding,
            bodyClass: ManifestContractReference,
            mobility: BodyMobilityClass,
            capabilityGroups: List<SpecializedCapabilityGroup>,
            safetyController: BodySafetyControllerClaim?,
            mountingSupport: BodyMountingSupport,
            powerRelationship: BodyPowerRelationship,
            geometryProfile: BodyGeometryProfileReference?,
            calibrationRequirements: List<ManifestContractReference>,
            surfaceConstraints: List<ManifestContractReference>,
            mountingEvidenceCapabilities: List<CapabilityReference>,
            extensions: List<ManifestExtensionReference>,
        ): SpecializedManifestResult<BodyManifest> {
            validateVersion(SpecializedManifestKind.BODY, schemaVersion)?.let {
                return specializedFailure(it)
            }
            validateGroups(capabilityGroups)?.let { return specializedFailure(it) }
            if (!mountingSupport.supportsMounts && mountingSupport.supportedRoles.isNotEmpty()) {
                return specializedFailure(SpecializedManifestError.InvalidMountingSupport)
            }
            if (mountingSupport.supportsMounts && mountingSupport.supportedRoles.isEmpty()) {
                return specializedFailure(SpecializedManifestError.InvalidMountingSupport)
            }
            validateDistinct(calibrationRequirements, "calibrationRequirements")?.let {
                return specializedFailure(it)
            }
            validateDistinct(surfaceConstraints, "surfaceConstraints")?.let {
                return specializedFailure(it)
            }
            validateDistinct(mountingEvidenceCapabilities, "mountingEvidenceCapabilities")?.let {
                return specializedFailure(it)
            }
            validateExtensions(extensions)?.let { return specializedFailure(it) }
            return SpecializedManifestResult.Success(
                BodyManifest(
                    schemaVersion = schemaVersion,
                    common = common,
                    bodyClass = bodyClass,
                    mobility = mobility,
                    capabilityGroups = canonicalGroups(capabilityGroups),
                    safetyController = safetyController,
                    mountingSupport = BodyMountingSupport(
                        mountingSupport.supportsMounts,
                        mountingSupport.supportedRoles.distinct().sortedBy { it.name },
                    ),
                    powerRelationship = powerRelationship,
                    geometryProfile = geometryProfile,
                    calibrationRequirements = calibrationRequirements.sortedBy { it.wireValue },
                    surfaceConstraints = surfaceConstraints.sortedBy { it.wireValue },
                    mountingEvidenceCapabilities = mountingEvidenceCapabilities.sortedBy { it.wireValue },
                    extensions = canonicalExtensions(extensions),
                ),
            )
        }
    }
}

enum class DeviceCategory {
    LIGHT,
    DISPLAY,
    CAMERA,
    SENSOR,
    APPLIANCE,
    OTHER,
}

enum class DevicePowerCharacteristic {
    MAINS_POWERED,
    BATTERY_POWERED,
    SELF_POWERED,
    UNKNOWN,
}

enum class DeviceControlLocality {
    NONE,
    LOCAL,
    REMOTE,
    HYBRID,
}

class DeviceManifest private constructor(
    override val schemaVersion: ManifestSchemaVersion,
    override val common: CommonManifestBinding,
    val category: DeviceCategory,
    override val capabilityGroups: List<SpecializedCapabilityGroup>,
    val placementSemantics: List<ManifestContractReference>,
    val powerCharacteristic: DevicePowerCharacteristic,
    val controlLocality: DeviceControlLocality,
    override val extensions: List<ManifestExtensionReference>,
) : SpecializedProviderManifest {
    override val kind: SpecializedManifestKind = SpecializedManifestKind.DEVICE

    companion object {
        fun create(
            schemaVersion: ManifestSchemaVersion,
            common: CommonManifestBinding,
            category: DeviceCategory,
            capabilityGroups: List<SpecializedCapabilityGroup>,
            placementSemantics: List<ManifestContractReference>,
            powerCharacteristic: DevicePowerCharacteristic,
            controlLocality: DeviceControlLocality,
            extensions: List<ManifestExtensionReference>,
        ): SpecializedManifestResult<DeviceManifest> {
            validateVersion(SpecializedManifestKind.DEVICE, schemaVersion)?.let {
                return specializedFailure(it)
            }
            validateGroups(capabilityGroups)?.let { return specializedFailure(it) }
            validateDistinct(placementSemantics, "placementSemantics")?.let {
                return specializedFailure(it)
            }
            validateExtensions(extensions)?.let { return specializedFailure(it) }
            return SpecializedManifestResult.Success(
                DeviceManifest(
                    schemaVersion = schemaVersion,
                    common = common,
                    category = category,
                    capabilityGroups = canonicalGroups(capabilityGroups),
                    placementSemantics = placementSemantics.sortedBy { it.wireValue },
                    powerCharacteristic = powerCharacteristic,
                    controlLocality = controlLocality,
                    extensions = canonicalExtensions(extensions),
                ),
            )
        }
    }
}

enum class ServiceCategory {
    COMMUNICATION,
    CALENDAR,
    MEDIA,
    AUTOMATION,
    REPOSITORY,
    OTHER,
}

enum class ServiceReachability {
    LOCAL,
    REMOTE,
    CLOUD,
    HYBRID,
}

class ServiceManifest private constructor(
    override val schemaVersion: ManifestSchemaVersion,
    override val common: CommonManifestBinding,
    val category: ServiceCategory,
    val authenticationScheme: ManifestContractReference,
    val authorizationScopes: List<ManifestContractReference>,
    val apiCompatibility: ManifestVersionRange,
    val usagePolicyReferences: List<ManifestContractReference>,
    val reachability: ServiceReachability,
    override val capabilityGroups: List<SpecializedCapabilityGroup>,
    override val extensions: List<ManifestExtensionReference>,
) : SpecializedProviderManifest {
    override val kind: SpecializedManifestKind = SpecializedManifestKind.SERVICE

    companion object {
        @Suppress("LongParameterList")
        fun create(
            schemaVersion: ManifestSchemaVersion,
            common: CommonManifestBinding,
            category: ServiceCategory,
            authenticationScheme: ManifestContractReference,
            authorizationScopes: List<ManifestContractReference>,
            apiCompatibility: ManifestVersionRange,
            usagePolicyReferences: List<ManifestContractReference>,
            reachability: ServiceReachability,
            capabilityGroups: List<SpecializedCapabilityGroup>,
            extensions: List<ManifestExtensionReference>,
        ): SpecializedManifestResult<ServiceManifest> {
            validateVersion(SpecializedManifestKind.SERVICE, schemaVersion)?.let {
                return specializedFailure(it)
            }
            validateGroups(capabilityGroups)?.let { return specializedFailure(it) }
            validateDistinct(authorizationScopes, "authorizationScopes")?.let {
                return specializedFailure(it)
            }
            validateDistinct(usagePolicyReferences, "usagePolicyReferences")?.let {
                return specializedFailure(it)
            }
            validateExtensions(extensions)?.let { return specializedFailure(it) }
            return SpecializedManifestResult.Success(
                ServiceManifest(
                    schemaVersion = schemaVersion,
                    common = common,
                    category = category,
                    authenticationScheme = authenticationScheme,
                    authorizationScopes = authorizationScopes.sortedBy { it.wireValue },
                    apiCompatibility = apiCompatibility,
                    usagePolicyReferences = usagePolicyReferences.sortedBy { it.wireValue },
                    reachability = reachability,
                    capabilityGroups = canonicalGroups(capabilityGroups),
                    extensions = canonicalExtensions(extensions),
                ),
            )
        }
    }
}

object SpecializedManifestValidator {
    fun validate(
        manifest: SpecializedProviderManifest,
        common: CommonProviderManifestDocument,
    ): SpecializedManifestValidation {
        val binding = CommonManifestBinding.from(common)
        if (manifest.common.providerDefinitionId != binding.providerDefinitionId) {
            return SpecializedManifestValidation.Invalid(
                SpecializedManifestError.CommonDefinitionMismatch,
            )
        }
        if (manifest.common.digest != binding.digest) {
            return SpecializedManifestValidation.Invalid(SpecializedManifestError.CommonDigestMismatch)
        }
        if (common.manifest.provider.family != manifest.kind.providerFamily) {
            return SpecializedManifestValidation.Invalid(
                SpecializedManifestError.WrongProviderFamily(
                    expected = manifest.kind.providerFamily,
                    actual = common.manifest.provider.family,
                ),
            )
        }
        val declared = common.manifest.capabilities.toSet()
        val referenced = manifest.capabilityGroups.flatMap { it.capabilities } + when (manifest) {
            is BodyManifest -> manifest.mountingEvidenceCapabilities
            is DeviceManifest,
            is ServiceManifest,
            -> emptyList()
        }
        val missing = referenced.firstOrNull { it !in declared }
        return if (missing == null) {
            SpecializedManifestValidation.Valid
        } else {
            SpecializedManifestValidation.Invalid(
                SpecializedManifestError.CapabilityNotClaimedByCommonManifest(missing.wireValue),
            )
        }
    }
}

sealed interface SpecializedManifestValidation {
    data object Valid : SpecializedManifestValidation

    data class Invalid(val error: SpecializedManifestError) : SpecializedManifestValidation
}

data class SpecializedManifestDocument(
    val manifest: SpecializedProviderManifest,
    val digest: ManifestDigest,
)

sealed interface SpecializedManifestResult<out T> {
    data class Success<T>(val value: T) : SpecializedManifestResult<T>

    data class Failure(val error: SpecializedManifestError) : SpecializedManifestResult<Nothing>
}

sealed interface SpecializedManifestError {
    data class UnsupportedSpecializationVersion(
        val kind: SpecializedManifestKind,
        val found: String,
    ) : SpecializedManifestError

    data class DuplicateCapabilityGroup(val reference: String) : SpecializedManifestError

    data class DuplicateCapabilityInGroups(val capability: String) : SpecializedManifestError

    data class DuplicateReference(val path: String) : SpecializedManifestError

    data object DuplicateExtension : SpecializedManifestError

    data object InvalidMountingSupport : SpecializedManifestError

    data object CommonDefinitionMismatch : SpecializedManifestError

    data object CommonDigestMismatch : SpecializedManifestError

    data class WrongProviderFamily(
        val expected: ProviderFamily,
        val actual: ProviderFamily,
    ) : SpecializedManifestError

    data class CapabilityNotClaimedByCommonManifest(val capability: String) : SpecializedManifestError

    data object MalformedDocument : SpecializedManifestError

    data class InvalidField(val path: String) : SpecializedManifestError

    data class MissingFields(val path: String, val fields: Set<String>) : SpecializedManifestError

    data class UnexpectedFields(val path: String, val fields: Set<String>) : SpecializedManifestError

    data class DigestMismatch(val declared: String, val computed: String) : SpecializedManifestError
}

private fun <T> specializedFailure(error: SpecializedManifestError): SpecializedManifestResult<T> =
    SpecializedManifestResult.Failure(error)

private fun validateVersion(
    kind: SpecializedManifestKind,
    version: ManifestSchemaVersion,
): SpecializedManifestError? = if (version.major != CommonProviderManifest.supportedSchemaMajor) {
    SpecializedManifestError.UnsupportedSpecializationVersion(kind, version.wireValue)
} else {
    null
}

private fun validateGroups(
    groups: List<SpecializedCapabilityGroup>,
): SpecializedManifestError? {
    val duplicateGroup = groups.groupBy { it.reference }.entries.firstOrNull { it.value.size > 1 }
    if (duplicateGroup != null) {
        return SpecializedManifestError.DuplicateCapabilityGroup(duplicateGroup.key.wireValue)
    }
    val capabilities = groups.flatMap { it.capabilities }
    val duplicateCapability = capabilities.groupBy { it }.entries.firstOrNull { it.value.size > 1 }
    return duplicateCapability?.let { SpecializedManifestError.DuplicateCapabilityInGroups(it.key.wireValue) }
}

private fun <T> validateDistinct(
    values: List<T>,
    path: String,
): SpecializedManifestError? = if (values.distinct().size != values.size) {
    SpecializedManifestError.DuplicateReference(path)
} else {
    null
}

private fun validateExtensions(
    extensions: List<ManifestExtensionReference>,
): SpecializedManifestError? = if (extensions.distinctBy { it.id }.size != extensions.size) {
    SpecializedManifestError.DuplicateExtension
} else {
    null
}

private fun canonicalGroups(groups: List<SpecializedCapabilityGroup>): List<SpecializedCapabilityGroup> =
    groups.sortedBy { it.reference.wireValue }.map { group ->
        SpecializedCapabilityGroup(group.reference, group.capabilities.sortedBy { it.wireValue })
    }

private fun canonicalExtensions(
    extensions: List<ManifestExtensionReference>,
): List<ManifestExtensionReference> = extensions.sortedBy { it.id.value }
