package io.redi.robot.adapter.compatibility

import io.redi.robot.adapter.api.AdapterDescriptor
import io.redi.robot.adapter.api.AdapterId
import io.redi.robot.adapter.api.HugoRuntimeVersion
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.connection.api.ConnectionMethodReference
import io.redi.robot.manifest.domain.AdapterDefinitionReference
import io.redi.robot.manifest.domain.ManifestCompatibilitySubject
import io.redi.robot.manifest.domain.ManifestExtensionId
import io.redi.robot.manifest.domain.ManifestSchemaVersion
import io.redi.robot.manifest.domain.ManifestVersion
import io.redi.robot.manifest.resolution.ResolvedManifestDefinition
import java.util.Collections

/** Opaque local evidence reference. It is not a URL, endpoint, credential, or trust assertion. */
@JvmInline
value class CompatibilityEvidenceReference private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._/-][a-z0-9]+){1,7}$")

        fun parse(raw: String): CompatibilityInputResult<CompatibilityEvidenceReference> = if (
            raw.length in 3..120 && pattern.matches(raw)
        ) {
            CompatibilityInputResult.Success(CompatibilityEvidenceReference(raw))
        } else {
            CompatibilityInputResult.Failure(CompatibilityInputError.INVALID_EVIDENCE_REFERENCE)
        }

        internal fun fromManifest(resolved: ResolvedManifestDefinition): CompatibilityEvidenceReference =
            CompatibilityEvidenceReference(resolved.candidateProvenance.catalogReference.value)
    }
}

enum class CompatibilityEvidenceSource {
    RESOLVED_MANIFEST,
    ADAPTER_DESCRIPTOR,
    HUGO_RUNTIME,
    CONNECTION_CONTRACT,
    EXTENSION_CONTRACT,
    PROVIDER_VERSION,
}

data class CompatibilityEvidence(
    val source: CompatibilityEvidenceSource,
    val reference: CompatibilityEvidenceReference,
)

data class AdapterDescriptorEvidence(
    val descriptor: AdapterDescriptor,
    val evidenceReference: CompatibilityEvidenceReference,
)

data class HugoRuntimeEvidence(
    val version: HugoRuntimeVersion,
    val evidenceReference: CompatibilityEvidenceReference,
)

data class ConnectionMethodSupport(
    val method: ConnectionMethodReference,
    val evidenceReference: CompatibilityEvidenceReference,
)

data class ExtensionSchemaSupport(
    val id: ManifestExtensionId,
    val schemaVersion: ManifestSchemaVersion,
    val evidenceReference: CompatibilityEvidenceReference,
)

data class ProviderVersionEvidence(
    val subject: ManifestCompatibilitySubject,
    val version: ManifestVersion,
    val evidenceReference: CompatibilityEvidenceReference,
)

class CompatibilityRequest private constructor(
    val resolvedManifest: ResolvedManifestDefinition,
    val adapter: AdapterDescriptorEvidence?,
    val hugoRuntime: HugoRuntimeEvidence,
    connectionMethods: List<ConnectionMethodSupport>,
    extensionSchemas: List<ExtensionSchemaSupport>,
    providerVersions: List<ProviderVersionEvidence>,
) {
    val connectionMethods: List<ConnectionMethodSupport> = Collections.unmodifiableList(
        connectionMethods.sortedBy { "${it.method.id.value}@${it.method.version.value}:${it.evidenceReference.value}" },
    )
    val extensionSchemas: List<ExtensionSchemaSupport> = Collections.unmodifiableList(
        extensionSchemas.sortedBy { "${it.id.value}@${it.schemaVersion.wireValue}:${it.evidenceReference.value}" },
    )
    val providerVersions: List<ProviderVersionEvidence> = Collections.unmodifiableList(
        providerVersions.sortedBy { "${it.subject.name}:${it.version.wireValue}:${it.evidenceReference.value}" },
    )

    companion object {
        fun create(
            resolvedManifest: ResolvedManifestDefinition,
            adapter: AdapterDescriptorEvidence?,
            hugoRuntime: HugoRuntimeEvidence,
            connectionMethods: List<ConnectionMethodSupport>,
            extensionSchemas: List<ExtensionSchemaSupport>,
            providerVersions: List<ProviderVersionEvidence>,
        ): CompatibilityRequest = CompatibilityRequest(
            resolvedManifest = resolvedManifest,
            adapter = adapter,
            hugoRuntime = hugoRuntime,
            connectionMethods = connectionMethods,
            extensionSchemas = extensionSchemas,
            providerVersions = providerVersions,
        )
    }
}

enum class CompatibilityReportStatus {
    COMPATIBLE,
    PARTIALLY_COMPATIBLE,
    INCOMPATIBLE,
    UNKNOWN,
    MISSING_ADAPTER,
}

enum class CompatibilityDimension {
    COMMON_MANIFEST_SCHEMA,
    SPECIALIZED_MANIFEST_SCHEMA,
    PROVIDER_DEFINITION,
    PROVIDER_FAMILY,
    ADAPTER_IDENTITY,
    ADAPTER_VERSION,
    HUGO_RUNTIME,
    CAPABILITY,
    CONNECTION_METHOD,
    EXTENSION_SCHEMA,
    DECLARED_CONSTRAINT,
}

enum class CompatibilityRequirement {
    REQUIRED,
    OPTIONAL,
    INFORMATIONAL,
}

enum class CompatibilityCheckStatus {
    MATCHED,
    PARTIAL,
    FAILED,
    MISSING,
    UNKNOWN,
    CONFLICTING,
}

enum class CompatibilityFindingCode {
    MATCHED,
    NO_REQUIREMENT,
    MISSING_ADAPTER,
    UNSUPPORTED_SCHEMA,
    IDENTITY_MISMATCH,
    PROVIDER_FAMILY_MISMATCH,
    ADAPTER_IDENTITY_MISMATCH,
    UNSUPPORTED_VERSION,
    UNSUPPORTED_CAPABILITY,
    UNSUPPORTED_CONNECTION_METHOD,
    EXTENSION_SCHEMA_MISMATCH,
    MISSING_PROVIDER_VERSION,
    CONFLICTING_CONSTRAINT,
}

data class CompatibilityCheck(
    val dimension: CompatibilityDimension,
    val checkId: String,
    val requirement: CompatibilityRequirement,
    val status: CompatibilityCheckStatus,
    val finding: CompatibilityFindingCode,
    val requiredValues: List<String>,
    val observedValues: List<String>,
    val evidence: List<CompatibilityEvidence>,
)

data class CompatibilityReportView(
    val status: CompatibilityReportStatus,
    val providerDefinitionId: ProviderDefinitionId,
    val providerFamily: ProviderFamily,
    val requiredAdapterId: AdapterDefinitionReference,
    val adapterCandidateId: AdapterId?,
    val checks: List<CompatibilityCheck>,
)

class CompatibilityReport internal constructor(
    val status: CompatibilityReportStatus,
    val providerDefinitionId: ProviderDefinitionId,
    val providerFamily: ProviderFamily,
    val requiredAdapterId: AdapterDefinitionReference,
    val adapterCandidateId: AdapterId?,
    checks: List<CompatibilityCheck>,
) {
    val checks: List<CompatibilityCheck> = Collections.unmodifiableList(
        checks.sortedWith(compareBy({ it.dimension.ordinal }, { it.checkId })),
    )

    fun view(): CompatibilityReportView = CompatibilityReportView(
        status = status,
        providerDefinitionId = providerDefinitionId,
        providerFamily = providerFamily,
        requiredAdapterId = requiredAdapterId,
        adapterCandidateId = adapterCandidateId,
        checks = checks,
    )

    override fun toString(): String = view().toString()
}

sealed interface CompatibilityInputResult<out T> {
    data class Success<T>(val value: T) : CompatibilityInputResult<T>
    data class Failure(val error: CompatibilityInputError) : CompatibilityInputResult<Nothing>
}

enum class CompatibilityInputError {
    INVALID_EVIDENCE_REFERENCE,
}
