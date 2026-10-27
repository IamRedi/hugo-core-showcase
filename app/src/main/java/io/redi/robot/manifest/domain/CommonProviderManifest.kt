package io.redi.robot.manifest.domain

import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily

data class ManifestSchemaVersion private constructor(
    val major: Int,
    val minor: Int,
) {
    val wireValue: String = "$major.$minor"

    companion object {
        private val pattern = Regex("^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$")

        fun parse(raw: String): CommonManifestResult<ManifestSchemaVersion> {
            val parts = parseVersionParts(raw, pattern, expectedParts = 2)
                ?: return failure("schemaVersion", ManifestFieldProblem.MALFORMED)
            if (parts[0] <= 0) return failure("schemaVersion", ManifestFieldProblem.MALFORMED)
            return CommonManifestResult.Success(ManifestSchemaVersion(parts[0], parts[1]))
        }
    }
}

data class ManifestVersion private constructor(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<ManifestVersion> {
    val wireValue: String = "$major.$minor.$patch"

    override fun compareTo(other: ManifestVersion): Int =
        compareValuesBy(this, other, ManifestVersion::major, ManifestVersion::minor, ManifestVersion::patch)

    companion object {
        private val pattern = Regex(
            "^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$",
        )

        fun parse(raw: String): CommonManifestResult<ManifestVersion> {
            val parts = parseVersionParts(raw, pattern, expectedParts = 3)
                ?: return failure("version", ManifestFieldProblem.MALFORMED)
            return CommonManifestResult.Success(ManifestVersion(parts[0], parts[1], parts[2]))
        }
    }
}

data class ManifestVersionRange private constructor(
    val minimumInclusive: ManifestVersion?,
    val maximumExclusive: ManifestVersion?,
) {
    companion object {
        fun create(
            minimumInclusive: ManifestVersion?,
            maximumExclusive: ManifestVersion?,
        ): CommonManifestResult<ManifestVersionRange> = when {
            minimumInclusive == null && maximumExclusive == null -> failure(
                "versionRange",
                ManifestFieldProblem.MISSING_BOUNDS,
            )
            minimumInclusive != null && maximumExclusive != null &&
                minimumInclusive >= maximumExclusive -> failure(
                "versionRange",
                ManifestFieldProblem.REVERSED_RANGE,
            )
            else -> CommonManifestResult.Success(
                ManifestVersionRange(minimumInclusive, maximumExclusive),
            )
        }
    }
}

@JvmInline
value class ManifestText private constructor(val value: String) {
    companion object {
        fun parse(raw: String): CommonManifestResult<ManifestText> = validateText(
            raw = raw,
            field = "text",
            maximumLength = 160,
            construct = ::ManifestText,
        )
    }
}

@JvmInline
value class ManifestSourceReference private constructor(val value: String) {
    companion object {
        fun parse(raw: String): CommonManifestResult<ManifestSourceReference> = validateText(
            raw = raw,
            field = "sourceReference",
            maximumLength = 256,
            construct = ::ManifestSourceReference,
        )
    }
}

@JvmInline
value class AdapterDefinitionReference private constructor(val value: String) {
    companion object {
        private val pattern = Regex(
            "^[a-z][a-z0-9]*(?:[.][a-z][a-z0-9]*)*/[a-z][a-z0-9]*(?:[-_][a-z0-9]+)*$",
        )

        fun parse(raw: String): CommonManifestResult<AdapterDefinitionReference> = if (
            pattern.matches(raw)
        ) {
            CommonManifestResult.Success(AdapterDefinitionReference(raw))
        } else {
            failure("adapter.id", ManifestFieldProblem.MALFORMED)
        }
    }
}

@JvmInline
value class ManifestContractId private constructor(val value: String) {
    companion object {
        private val pattern = Regex(
            "^[a-z][a-z0-9]*(?:[.][a-z][a-z0-9_]*)*/[a-z][a-z0-9_]*(?:[-_][a-z0-9]+)*$",
        )

        fun parse(raw: String): CommonManifestResult<ManifestContractId> = if (pattern.matches(raw)) {
            CommonManifestResult.Success(ManifestContractId(raw))
        } else {
            failure("contract.id", ManifestFieldProblem.MALFORMED)
        }
    }
}

data class ManifestContractReference(
    val id: ManifestContractId,
    val version: ManifestSchemaVersion,
) {
    val wireValue: String = "${id.value}@${version.wireValue}"

    companion object {
        fun parse(raw: String): CommonManifestResult<ManifestContractReference> {
            if (raw.count { it == '@' } != 1) {
                return failure("contract.reference", ManifestFieldProblem.MALFORMED)
            }
            val (idText, versionText) = raw.split('@', limit = 2)
            val id = unwrapOrReturn(ManifestContractId.parse(idText)) ?: return failure(
                "contract.reference",
                ManifestFieldProblem.MALFORMED,
            )
            val version = unwrapOrReturn(ManifestSchemaVersion.parse(versionText)) ?: return failure(
                "contract.reference",
                ManifestFieldProblem.MALFORMED,
            )
            return CommonManifestResult.Success(ManifestContractReference(id, version))
        }
    }
}

@JvmInline
value class ManifestExtensionId private constructor(val value: String) {
    companion object {
        private val pattern = Regex(
            "^[a-z][a-z0-9]*(?:[.][a-z][a-z0-9]*)*/[a-z][a-z0-9]*(?:[-_][a-z0-9]+)*$",
        )

        fun parse(raw: String): CommonManifestResult<ManifestExtensionId> = if (pattern.matches(raw)) {
            CommonManifestResult.Success(ManifestExtensionId(raw))
        } else {
            failure("extension.id", ManifestFieldProblem.MALFORMED)
        }
    }
}

@JvmInline
value class ManifestDigest private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^sha256:[0-9a-f]{64}$")

        fun parse(raw: String): CommonManifestResult<ManifestDigest> = if (pattern.matches(raw)) {
            CommonManifestResult.Success(ManifestDigest(raw))
        } else {
            failure("digest", ManifestFieldProblem.MALFORMED)
        }

        internal fun fromSha256(hex: String): ManifestDigest = ManifestDigest("sha256:$hex")
    }
}

data class ManifestProductMetadata private constructor(
    val vendor: ManifestText?,
    val model: ManifestText?,
    val version: ManifestText?,
) {
    companion object {
        fun create(
            vendor: ManifestText?,
            model: ManifestText?,
            version: ManifestText?,
        ): CommonManifestResult<ManifestProductMetadata> = if (
            vendor == null && model == null && version == null
        ) {
            failure("provider.product", ManifestFieldProblem.EMPTY_OBJECT)
        } else {
            CommonManifestResult.Success(ManifestProductMetadata(vendor, model, version))
        }
    }
}

data class ManifestProviderDeclaration(
    val definitionId: ProviderDefinitionId,
    val family: ProviderFamily,
    val displayName: ManifestText,
    val product: ManifestProductMetadata?,
)

data class AdapterCompatibilityReference(
    val adapterId: AdapterDefinitionReference,
    val versions: ManifestVersionRange,
)

data class ManifestConnectionMethod(
    val reference: ManifestContractReference,
    val required: Boolean,
)

enum class ManifestRequirementKind {
    AUTHENTICATION,
    PERMISSION,
    SAFETY,
    RISK,
    LIFECYCLE,
    AVAILABILITY,
}

data class ManifestRequirementReference(
    val kind: ManifestRequirementKind,
    val reference: ManifestContractReference,
    val required: Boolean,
)

enum class ManifestCompatibilitySubject {
    PROVIDER_FIRMWARE,
    PROVIDER_SOFTWARE,
    PROVIDER_API,
}

data class ManifestCompatibilityConstraint(
    val subject: ManifestCompatibilitySubject,
    val versions: ManifestVersionRange,
)

data class ManifestExtensionReference(
    val id: ManifestExtensionId,
    val schemaVersion: ManifestSchemaVersion,
    val digest: ManifestDigest,
)

enum class ManifestSource {
    PROJECT,
    ADAPTER_PACKAGE,
    VENDOR,
    ADMINISTRATOR,
    LOCAL_CONFIGURATION,
}

data class ManifestProvenance(
    val source: ManifestSource,
    val issuer: ManifestText,
    val sourceReference: ManifestSourceReference,
    val evidence: CapabilityEvidenceClass,
)

class CommonProviderManifest private constructor(
    val schemaVersion: ManifestSchemaVersion,
    val provider: ManifestProviderDeclaration,
    val adapter: AdapterCompatibilityReference,
    val capabilities: List<CapabilityReference>,
    val connectionMethods: List<ManifestConnectionMethod>,
    val requirements: List<ManifestRequirementReference>,
    val compatibility: List<ManifestCompatibilityConstraint>,
    val extensions: List<ManifestExtensionReference>,
    val provenance: ManifestProvenance,
) {
    companion object {
        const val supportedSchemaMajor: Int = 1
        private const val maximumCapabilities = 128
        private const val maximumDeclarations = 32

        @Suppress("LongParameterList")
        fun create(
            schemaVersion: ManifestSchemaVersion,
            provider: ManifestProviderDeclaration,
            adapter: AdapterCompatibilityReference,
            capabilities: List<CapabilityReference>,
            connectionMethods: List<ManifestConnectionMethod>,
            requirements: List<ManifestRequirementReference>,
            compatibility: List<ManifestCompatibilityConstraint>,
            extensions: List<ManifestExtensionReference>,
            provenance: ManifestProvenance,
        ): CommonManifestResult<CommonProviderManifest> {
            if (schemaVersion.major != supportedSchemaMajor) {
                return CommonManifestResult.Failure(
                    CommonManifestError.UnsupportedSchemaVersion(schemaVersion.wireValue),
                )
            }
            if (capabilities.size > maximumCapabilities) {
                return CommonManifestResult.Failure(
                    CommonManifestError.TooManyEntries("capabilities", maximumCapabilities),
                )
            }
            if (listOf(
                    connectionMethods.size,
                    requirements.size,
                    compatibility.size,
                    extensions.size,
                ).any { it > maximumDeclarations }
            ) {
                return CommonManifestResult.Failure(
                    CommonManifestError.TooManyEntries("declarations", maximumDeclarations),
                )
            }
            val duplicateCapability = capabilities.groupBy { it.id }.entries.firstOrNull {
                it.value.size > 1
            }
            if (duplicateCapability != null) {
                return CommonManifestResult.Failure(
                    CommonManifestError.DuplicateCapability(duplicateCapability.key.wireValue),
                )
            }
            if (connectionMethods.distinctBy { it.reference }.size != connectionMethods.size) {
                return CommonManifestResult.Failure(CommonManifestError.DuplicateConnectionMethod)
            }
            if (requirements.distinctBy { it.kind to it.reference }.size != requirements.size) {
                return CommonManifestResult.Failure(CommonManifestError.DuplicateRequirement)
            }
            if (compatibility.distinctBy { it.subject }.size != compatibility.size) {
                return CommonManifestResult.Failure(
                    CommonManifestError.DuplicateCompatibilitySubject,
                )
            }
            if (extensions.distinctBy { it.id }.size != extensions.size) {
                return CommonManifestResult.Failure(CommonManifestError.DuplicateExtension)
            }

            return CommonManifestResult.Success(
                CommonProviderManifest(
                    schemaVersion = schemaVersion,
                    provider = provider,
                    adapter = adapter,
                    capabilities = capabilities.sortedBy { it.wireValue },
                    connectionMethods = connectionMethods.sortedBy { it.reference.wireValue },
                    requirements = requirements.sortedWith(
                        compareBy({ it.kind.name }, { it.reference.wireValue }),
                    ),
                    compatibility = compatibility.sortedBy { it.subject.name },
                    extensions = extensions.sortedBy { it.id.value },
                    provenance = provenance,
                ),
            )
        }
    }
}

data class CommonProviderManifestDocument(
    val manifest: CommonProviderManifest,
    val digest: ManifestDigest,
)

sealed interface CommonManifestResult<out T> {
    data class Success<T>(val value: T) : CommonManifestResult<T>

    data class Failure(val error: CommonManifestError) : CommonManifestResult<Nothing>
}

sealed interface CommonManifestError {
    data object MalformedDocument : CommonManifestError

    data class UnsupportedSchemaVersion(val found: String) : CommonManifestError

    data class InvalidField(
        val path: String,
        val problem: ManifestFieldProblem = ManifestFieldProblem.MALFORMED,
    ) : CommonManifestError

    data class UnexpectedFields(val path: String, val fields: Set<String>) : CommonManifestError

    data class MissingFields(val path: String, val fields: Set<String>) : CommonManifestError

    data class TooManyEntries(val path: String, val maximum: Int) : CommonManifestError

    data class DuplicateCapability(val capabilityId: String) : CommonManifestError

    data object DuplicateConnectionMethod : CommonManifestError

    data object DuplicateRequirement : CommonManifestError

    data object DuplicateCompatibilitySubject : CommonManifestError

    data object DuplicateExtension : CommonManifestError

    data class DigestMismatch(val declared: String, val computed: String) : CommonManifestError
}

enum class ManifestFieldProblem {
    BLANK,
    TOO_LONG,
    CONTROL_CHARACTER,
    MALFORMED,
    EMPTY_OBJECT,
    MISSING_BOUNDS,
    REVERSED_RANGE,
}

private fun <T> failure(
    field: String,
    problem: ManifestFieldProblem,
): CommonManifestResult<T> = CommonManifestResult.Failure(
    CommonManifestError.InvalidField(field, problem),
)

private fun <T> validateText(
    raw: String,
    field: String,
    maximumLength: Int,
    construct: (String) -> T,
): CommonManifestResult<T> = when {
    raw.isBlank() -> failure(field, ManifestFieldProblem.BLANK)
    raw.length > maximumLength -> failure(field, ManifestFieldProblem.TOO_LONG)
    raw.any(Char::isISOControl) -> failure(field, ManifestFieldProblem.CONTROL_CHARACTER)
    else -> CommonManifestResult.Success(construct(raw))
}

private fun parseVersionParts(
    raw: String,
    pattern: Regex,
    expectedParts: Int,
): List<Int>? {
    if (!pattern.matches(raw)) return null
    val parts = raw.split('.')
    if (parts.size != expectedParts) return null
    return parts.map { it.toIntOrNull() ?: return null }
}

private fun <T> unwrapOrReturn(result: CommonManifestResult<T>): T? = when (result) {
    is CommonManifestResult.Success -> result.value
    is CommonManifestResult.Failure -> null
}
