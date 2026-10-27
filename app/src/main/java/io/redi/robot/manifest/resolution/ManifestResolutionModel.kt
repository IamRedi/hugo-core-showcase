package io.redi.robot.manifest.resolution

import io.redi.robot.adapter.api.AdapterDescriptor
import io.redi.robot.adapter.api.AdapterIdentity
import io.redi.robot.adapter.api.AdapterPackageDigest
import io.redi.robot.adapter.api.AdapterProvenance
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.discovery.identity.IdentityResolution
import io.redi.robot.manifest.domain.AdapterDefinitionReference
import io.redi.robot.manifest.domain.CommonProviderManifestDocument
import io.redi.robot.manifest.domain.SpecializedManifestDocument
import java.util.Collections
import java.util.UUID

@JvmInline
value class ManifestCandidateId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun fromUuid(uuid: UUID): ManifestCandidateId = ManifestCandidateId(uuid.toString())

        fun parse(raw: String): ManifestCandidateResult<ManifestCandidateId> = runCatching {
            UUID.fromString(raw).toString().takeIf { it == raw }
        }.getOrNull()?.let { ManifestCandidateResult.Success(ManifestCandidateId(it)) }
            ?: ManifestCandidateResult.Failure(ManifestCandidateError.InvalidCandidateId)
    }
}

/** Opaque catalog entry reference; it is not a URL, endpoint, package path, or authority. */
@JvmInline
value class ManifestCatalogReference private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._/-][a-z0-9]+){1,7}$")

        fun parse(raw: String): ManifestCandidateResult<ManifestCatalogReference> = if (
            raw.length in 3..120 && pattern.matches(raw)
        ) {
            ManifestCandidateResult.Success(ManifestCatalogReference(raw))
        } else {
            ManifestCandidateResult.Failure(ManifestCandidateError.InvalidCatalogReference)
        }
    }
}

enum class ManifestCandidateSource {
    BUILT_IN_HUGO,
    OFFICIAL_VENDOR,
    HUGO_MAINTAINED,
    LOCAL_USER_APPROVED,
    DEVELOPER_MODE_CANDIDATE,
    ADAPTER_GENERATED,
}

data class ManifestCandidateProvenance(
    val source: ManifestCandidateSource,
    val catalogReference: ManifestCatalogReference,
)

/** Raw protected candidate documents are decoded only by the canonical H010/H011 codecs. */
class ManifestDefinitionCandidate private constructor(
    val candidateId: ManifestCandidateId,
    val provenance: ManifestCandidateProvenance,
    val commonDocument: String,
    val specializedDocument: String?,
) {
    companion object {
        private const val maximumDocumentLength = 512_000

        fun create(
            candidateId: ManifestCandidateId,
            provenance: ManifestCandidateProvenance,
            commonDocument: String,
            specializedDocument: String?,
        ): ManifestCandidateResult<ManifestDefinitionCandidate> = when {
            commonDocument.isBlank() || commonDocument.length > maximumDocumentLength ->
                ManifestCandidateResult.Failure(ManifestCandidateError.InvalidCommonDocument)
            specializedDocument != null &&
                (specializedDocument.isBlank() || specializedDocument.length > maximumDocumentLength) ->
                ManifestCandidateResult.Failure(ManifestCandidateError.InvalidSpecializedDocument)
            else -> ManifestCandidateResult.Success(
                ManifestDefinitionCandidate(
                    candidateId = candidateId,
                    provenance = provenance,
                    commonDocument = commonDocument,
                    specializedDocument = specializedDocument,
                ),
            )
        }
    }
}

sealed interface ManifestCandidateResult<out T> {
    data class Success<T>(val value: T) : ManifestCandidateResult<T>
    data class Failure(val error: ManifestCandidateError) : ManifestCandidateResult<Nothing>
}

enum class ManifestCandidateError {
    InvalidCandidateId,
    InvalidCatalogReference,
    InvalidCommonDocument,
    InvalidSpecializedDocument,
}

enum class ManifestResolutionStatus {
    RESOLVED,
    NOT_FOUND,
    AMBIGUOUS,
    IDENTITY_MISMATCH,
    DIGEST_MISMATCH,
    UNSUPPORTED_SCHEMA,
    INCOMPATIBLE_PROVIDER_FAMILY,
    INCOMPATIBLE_ADAPTER,
    CONFLICTING_MANIFESTS,
    REJECTED,
}

enum class ManifestResolutionReason {
    EXACT_VALIDATED_DEFINITION,
    NO_MANIFEST_CANDIDATES,
    SPECIALIZED_MANIFEST_NOT_FOUND,
    IDENTITY_RESULT_AMBIGUOUS,
    DUPLICATE_EQUIVALENT_CANDIDATES,
    PROVIDER_DEFINITION_MISMATCH,
    COMMON_DIGEST_MISMATCH,
    SPECIALIZED_DIGEST_MISMATCH,
    COMMON_BINDING_DIGEST_MISMATCH,
    COMMON_SCHEMA_UNSUPPORTED,
    SPECIALIZED_SCHEMA_UNSUPPORTED,
    PROVIDER_FAMILY_MISMATCH,
    SPECIALIZATION_KIND_MISMATCH,
    MANIFEST_CLAIMS_CONFLICT,
    SPECIALIZED_CAPABILITY_CONFLICT,
    ADAPTER_VERSION_INCOMPATIBLE,
    ADAPTER_FAMILY_INCOMPATIBLE,
    ADAPTER_COMMON_SCHEMA_INCOMPATIBLE,
    ADAPTER_SPECIALIZATION_INCOMPATIBLE,
    CAPABILITY_REFERENCE_INCOMPATIBLE,
    DUPLICATE_CANDIDATE_ID,
    INVALID_IDENTITY_RESULT,
    INVALID_COMMON_MANIFEST,
    INVALID_SPECIALIZED_MANIFEST,
    INVALID_PROVENANCE,
}

data class RedactedIdentityManifestBinding(
    val definitionId: ProviderDefinitionId,
    val family: ProviderFamily?,
    val referencesKnownProviderInstance: Boolean,
)

class IdentityManifestBinding internal constructor(
    val definitionId: ProviderDefinitionId,
    val family: ProviderFamily?,
    val instanceId: ProviderInstanceId?,
) {
    fun redacted(): RedactedIdentityManifestBinding = RedactedIdentityManifestBinding(
        definitionId = definitionId,
        family = family,
        referencesKnownProviderInstance = instanceId != null,
    )

    override fun toString(): String = redacted().toString()
}

enum class AdapterCandidateAssessmentStatus {
    NO_MATCHING_DESCRIPTOR,
    COMPATIBLE_DESCRIPTORS,
}

data class CompatibleAdapterCandidate(
    val identity: AdapterIdentity,
    val packageDigest: AdapterPackageDigest,
    val provenance: AdapterProvenance,
)

class AdapterCandidateAssessment(
    val requiredAdapter: AdapterDefinitionReference,
    val status: AdapterCandidateAssessmentStatus,
    compatibleCandidates: List<CompatibleAdapterCandidate>,
) {
    val compatibleCandidates: List<CompatibleAdapterCandidate> = Collections.unmodifiableList(
        compatibleCandidates.sortedWith(
            compareBy(
                { it.identity.id.wireValue },
                { it.identity.version.wireValue },
                { it.packageDigest.wireValue },
                { it.provenance.source.name },
                { it.provenance.sourceReference.wireValue },
            ),
        ),
    )

    override fun toString(): String = "AdapterCandidateAssessment(" +
        "requiredAdapter=$requiredAdapter, status=$status, compatibleCandidates=$compatibleCandidates)"
}

class ResolvedManifestDefinition internal constructor(
    val candidateId: ManifestCandidateId,
    val candidateProvenance: ManifestCandidateProvenance,
    val identityBinding: IdentityManifestBinding,
    val common: CommonProviderManifestDocument,
    val specialized: SpecializedManifestDocument,
    val adapterAssessment: AdapterCandidateAssessment,
) {
    override fun toString(): String = "ResolvedManifestDefinition(" +
        "candidateId=$candidateId, candidateProvenance=$candidateProvenance, " +
        "identityBinding=${identityBinding.redacted()}, " +
        "definitionId=${common.manifest.provider.definitionId}, " +
        "family=${common.manifest.provider.family}, specialization=${specialized.manifest.kind}, " +
        "adapterAssessment=$adapterAssessment)"
}

data class RedactedManifestResolution(
    val status: ManifestResolutionStatus,
    val reason: ManifestResolutionReason,
    val identityBinding: RedactedIdentityManifestBinding?,
    val candidateIds: List<ManifestCandidateId>,
    val resolved: ResolvedManifestDefinition?,
)

class ManifestResolution internal constructor(
    val status: ManifestResolutionStatus,
    val reason: ManifestResolutionReason,
    val identityBinding: IdentityManifestBinding?,
    candidateIds: List<ManifestCandidateId>,
    val resolved: ResolvedManifestDefinition?,
) {
    val candidateIds: List<ManifestCandidateId> = Collections.unmodifiableList(
        candidateIds.distinct().sortedBy { it.value },
    )

    fun redacted(): RedactedManifestResolution = RedactedManifestResolution(
        status = status,
        reason = reason,
        identityBinding = identityBinding?.redacted(),
        candidateIds = candidateIds,
        resolved = resolved,
    )

    override fun toString(): String = redacted().toString()
}

class ManifestResolutionRequest private constructor(
    val identityResolution: IdentityResolution,
    manifestCandidates: List<ManifestDefinitionCandidate>,
    adapterCandidates: List<AdapterDescriptor>,
) {
    val manifestCandidates: List<ManifestDefinitionCandidate> = Collections.unmodifiableList(
        manifestCandidates.toList(),
    )
    val adapterCandidates: List<AdapterDescriptor> = Collections.unmodifiableList(
        adapterCandidates.toList(),
    )

    companion object {
        fun create(
            identityResolution: IdentityResolution,
            manifestCandidates: List<ManifestDefinitionCandidate>,
            adapterCandidates: List<AdapterDescriptor>,
        ): ManifestResolutionRequest = ManifestResolutionRequest(
            identityResolution = identityResolution,
            manifestCandidates = manifestCandidates,
            adapterCandidates = adapterCandidates,
        )
    }
}
