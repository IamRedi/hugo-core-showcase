package io.redi.robot.manifest.resolution

import io.redi.robot.adapter.api.AdapterDescriptor
import io.redi.robot.adapter.api.AdapterManifestCompatibilityFailure
import io.redi.robot.adapter.api.AdapterManifestCompatibilityMatcher
import io.redi.robot.adapter.api.AdapterManifestCompatibilityResult
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.discovery.identity.IdentityResolutionStatus
import io.redi.robot.discovery.identity.KnownProviderIdentityCandidate
import io.redi.robot.discovery.identity.NewProviderIdentityCandidate
import io.redi.robot.manifest.domain.BodyManifest
import io.redi.robot.manifest.domain.CommonManifestError
import io.redi.robot.manifest.domain.CommonManifestResult
import io.redi.robot.manifest.domain.CommonProviderManifestCodec
import io.redi.robot.manifest.domain.CommonProviderManifestDocument
import io.redi.robot.manifest.domain.DeviceManifest
import io.redi.robot.manifest.domain.ManifestSource
import io.redi.robot.manifest.domain.ServiceManifest
import io.redi.robot.manifest.domain.SpecializedManifestError
import io.redi.robot.manifest.domain.SpecializedManifestResult
import io.redi.robot.manifest.domain.SpecializedManifestValidation
import io.redi.robot.manifest.domain.SpecializedManifestValidator
import io.redi.robot.manifest.domain.SpecializedProviderManifest
import io.redi.robot.manifest.domain.SpecializedProviderManifestCodec
import io.redi.robot.manifest.domain.SpecializedManifestDocument

/**
 * Pure H023 resolver. It validates claims and compatibility only; it performs no lookup, download,
 * install, trust, authorization, connection, registration, execution, or registry mutation.
 */
object ManifestResolver {
    fun resolve(request: ManifestResolutionRequest): ManifestResolution {
        val bindingResult = bindingFrom(request)
        if (bindingResult.terminal != null) return bindingResult.terminal
        val binding = checkNotNull(bindingResult.binding)
        val candidates = request.manifestCandidates.sortedBy { it.candidateId.value }
        if (candidates.isEmpty()) {
            return outcome(
                ManifestResolutionStatus.NOT_FOUND,
                ManifestResolutionReason.NO_MANIFEST_CANDIDATES,
                binding,
            )
        }
        if (candidates.map { it.candidateId }.distinct().size != candidates.size) {
            return outcome(
                ManifestResolutionStatus.REJECTED,
                ManifestResolutionReason.DUPLICATE_CANDIDATE_ID,
                binding,
                candidates,
            )
        }

        val decoded = candidates.map { candidate -> decode(candidate) }
        val commonFailures = decoded.mapNotNull { it.failure }
        if (commonFailures.isNotEmpty()) return collapseFailures(binding, candidates, commonFailures)

        val commonValid = decoded.mapNotNull { it.common }
        val definitionMatches = commonValid.filter {
            it.common.manifest.provider.definitionId == binding.definitionId
        }
        if (definitionMatches.isEmpty()) {
            return outcome(
                ManifestResolutionStatus.IDENTITY_MISMATCH,
                ManifestResolutionReason.PROVIDER_DEFINITION_MISMATCH,
                binding,
                candidates,
            )
        }
        val familyMatches = definitionMatches.filter {
            binding.family == null || it.common.manifest.provider.family == binding.family
        }
        if (familyMatches.isEmpty()) {
            return outcome(
                ManifestResolutionStatus.INCOMPATIBLE_PROVIDER_FAMILY,
                ManifestResolutionReason.PROVIDER_FAMILY_MISMATCH,
                binding,
                definitionMatches.map { it.candidate },
            )
        }

        val completed = familyMatches.map { validateSpecialization(it) }
        val specializationFailures = completed.mapNotNull { it.failure }
        if (specializationFailures.isNotEmpty()) {
            return collapseFailures(binding, familyMatches.map { it.candidate }, specializationFailures)
        }
        val valid = completed.mapNotNull { it.valid }
        if (valid.size > 1) {
            val signatures = valid.map(::claimSignature).distinct()
            return if (signatures.size == 1) {
                outcome(
                    ManifestResolutionStatus.AMBIGUOUS,
                    ManifestResolutionReason.DUPLICATE_EQUIVALENT_CANDIDATES,
                    binding,
                    valid.map { it.candidate },
                )
            } else {
                outcome(
                    ManifestResolutionStatus.CONFLICTING_MANIFESTS,
                    ManifestResolutionReason.MANIFEST_CLAIMS_CONFLICT,
                    binding,
                    valid.map { it.candidate },
                )
            }
        }

        val selected = valid.single()
        val adapterResult = assessAdapters(selected, request.adapterCandidates)
        if (adapterResult.failure != null) {
            return outcome(
                ManifestResolutionStatus.INCOMPATIBLE_ADAPTER,
                adapterResult.failure,
                binding,
                listOf(selected.candidate),
            )
        }
        return ManifestResolution(
            status = ManifestResolutionStatus.RESOLVED,
            reason = ManifestResolutionReason.EXACT_VALIDATED_DEFINITION,
            identityBinding = binding,
            candidateIds = listOf(selected.candidate.candidateId),
            resolved = ResolvedManifestDefinition(
                candidateId = selected.candidate.candidateId,
                candidateProvenance = selected.candidate.provenance,
                identityBinding = binding,
                common = selected.common,
                specialized = selected.specialized,
                adapterAssessment = checkNotNull(adapterResult.assessment),
            ),
        )
    }

    private fun bindingFrom(request: ManifestResolutionRequest): BindingResult {
        val identity = request.identityResolution
        return when (identity.status) {
            IdentityResolutionStatus.MATCHED_KNOWN_PROVIDER -> {
                val candidate = identity.candidates.singleOrNull() as? KnownProviderIdentityCandidate
                    ?: return BindingResult(terminal = outcome(
                        ManifestResolutionStatus.REJECTED,
                        ManifestResolutionReason.INVALID_IDENTITY_RESULT,
                    ))
                BindingResult(
                    binding = IdentityManifestBinding(
                        definitionId = candidate.identity.definitionId,
                        family = candidate.identity.family,
                        instanceId = candidate.identity.instanceId,
                    ),
                )
            }
            IdentityResolutionStatus.NEW_PROVIDER_CANDIDATE -> {
                val candidate = identity.candidates.singleOrNull() as? NewProviderIdentityCandidate
                    ?: return BindingResult(terminal = outcome(
                        ManifestResolutionStatus.REJECTED,
                        ManifestResolutionReason.INVALID_IDENTITY_RESULT,
                    ))
                BindingResult(
                    binding = IdentityManifestBinding(
                        definitionId = candidate.definitionId,
                        family = candidate.family,
                        instanceId = null,
                    ),
                )
            }
            IdentityResolutionStatus.AMBIGUOUS -> BindingResult(terminal = outcome(
                ManifestResolutionStatus.AMBIGUOUS,
                ManifestResolutionReason.IDENTITY_RESULT_AMBIGUOUS,
            ))
            else -> BindingResult(terminal = outcome(
                ManifestResolutionStatus.REJECTED,
                ManifestResolutionReason.INVALID_IDENTITY_RESULT,
            ))
        }
    }

    private fun decode(candidate: ManifestDefinitionCandidate): DecodedCandidate {
        val common = when (val result = CommonProviderManifestCodec.decode(candidate.commonDocument)) {
            is CommonManifestResult.Success -> result.value
            is CommonManifestResult.Failure -> return DecodedCandidate(
                failure = mapCommonFailure(candidate, result.error),
            )
        }
        if (!provenanceMatches(candidate, common)) {
            return DecodedCandidate(
                failure = CandidateFailure(
                    candidate.candidateId,
                    ManifestResolutionStatus.REJECTED,
                    ManifestResolutionReason.INVALID_PROVENANCE,
                ),
            )
        }
        return DecodedCandidate(common = CommonDecodedCandidate(candidate, common))
    }

    private fun validateSpecialization(candidate: CommonDecodedCandidate): CompletedCandidate {
        val text = candidate.candidate.specializedDocument ?: return CompletedCandidate(
            failure = CandidateFailure(
                candidate.candidate.candidateId,
                ManifestResolutionStatus.NOT_FOUND,
                ManifestResolutionReason.SPECIALIZED_MANIFEST_NOT_FOUND,
            ),
        )
        val specialized = when (val result = SpecializedProviderManifestCodec.decode(text)) {
            is SpecializedManifestResult.Success -> result.value
            is SpecializedManifestResult.Failure -> return CompletedCandidate(
                failure = mapSpecializedFailure(candidate.candidate, result.error),
            )
        }
        if (specialized.manifest.kind.providerFamily != candidate.common.manifest.provider.family) {
            return CompletedCandidate(
                failure = CandidateFailure(
                    candidate.candidate.candidateId,
                    ManifestResolutionStatus.INCOMPATIBLE_PROVIDER_FAMILY,
                    ManifestResolutionReason.SPECIALIZATION_KIND_MISMATCH,
                ),
            )
        }
        return when (val validation = SpecializedManifestValidator.validate(specialized.manifest, candidate.common)) {
            SpecializedManifestValidation.Valid -> CompletedCandidate(
                valid = ValidCandidate(candidate.candidate, candidate.common, specialized),
            )
            is SpecializedManifestValidation.Invalid -> CompletedCandidate(
                failure = mapSpecializedValidation(candidate.candidate, validation.error),
            )
        }
    }

    private fun assessAdapters(
        candidate: ValidCandidate,
        adapters: List<AdapterDescriptor>,
    ): AdapterAssessmentResult {
        val required = candidate.common.manifest.adapter.adapterId
        val matching = adapters.filter { it.identity.id.wireValue == required.value }
            .sortedWith(compareBy({ it.identity.id.wireValue }, { it.identity.version.wireValue }))
        if (matching.isEmpty()) {
            return AdapterAssessmentResult(
                assessment = AdapterCandidateAssessment(
                    requiredAdapter = required,
                    status = AdapterCandidateAssessmentStatus.NO_MATCHING_DESCRIPTOR,
                    compatibleCandidates = emptyList(),
                ),
            )
        }
        val evaluated = matching.map { adapter ->
            adapter to AdapterManifestCompatibilityMatcher.match(
                adapter = adapter,
                common = candidate.common,
                specialized = candidate.specialized.manifest,
            )
        }
        val compatible = evaluated.filter { it.second is AdapterManifestCompatibilityResult.Compatible }
            .map {
                CompatibleAdapterCandidate(
                    identity = it.first.identity,
                    packageDigest = it.first.packageDigest,
                    provenance = it.first.provenance,
                )
            }
        if (compatible.isNotEmpty()) {
            return AdapterAssessmentResult(
                assessment = AdapterCandidateAssessment(
                    requiredAdapter = required,
                    status = AdapterCandidateAssessmentStatus.COMPATIBLE_DESCRIPTORS,
                    compatibleCandidates = compatible,
                ),
            )
        }
        val reasons = evaluated.mapNotNull {
            (it.second as? AdapterManifestCompatibilityResult.Incompatible)?.reason
        }
        return AdapterAssessmentResult(failure = mapAdapterFailure(reasons.first()))
    }

    private fun provenanceMatches(
        candidate: ManifestDefinitionCandidate,
        document: CommonProviderManifestDocument,
    ): Boolean = document.manifest.provenance.source in when (candidate.provenance.source) {
        ManifestCandidateSource.BUILT_IN_HUGO,
        ManifestCandidateSource.HUGO_MAINTAINED,
        -> setOf(ManifestSource.PROJECT)
        ManifestCandidateSource.OFFICIAL_VENDOR -> setOf(ManifestSource.VENDOR)
        ManifestCandidateSource.LOCAL_USER_APPROVED -> setOf(
            ManifestSource.ADMINISTRATOR,
            ManifestSource.LOCAL_CONFIGURATION,
        )
        ManifestCandidateSource.DEVELOPER_MODE_CANDIDATE -> setOf(ManifestSource.LOCAL_CONFIGURATION)
        ManifestCandidateSource.ADAPTER_GENERATED -> setOf(ManifestSource.ADAPTER_PACKAGE)
    }

    private fun mapCommonFailure(
        candidate: ManifestDefinitionCandidate,
        error: CommonManifestError,
    ): CandidateFailure = when (error) {
        is CommonManifestError.UnsupportedSchemaVersion -> CandidateFailure(
            candidate.candidateId,
            ManifestResolutionStatus.UNSUPPORTED_SCHEMA,
            ManifestResolutionReason.COMMON_SCHEMA_UNSUPPORTED,
        )
        is CommonManifestError.DigestMismatch -> CandidateFailure(
            candidate.candidateId,
            ManifestResolutionStatus.DIGEST_MISMATCH,
            ManifestResolutionReason.COMMON_DIGEST_MISMATCH,
        )
        else -> CandidateFailure(
            candidate.candidateId,
            ManifestResolutionStatus.REJECTED,
            ManifestResolutionReason.INVALID_COMMON_MANIFEST,
        )
    }

    private fun mapSpecializedFailure(
        candidate: ManifestDefinitionCandidate,
        error: SpecializedManifestError,
    ): CandidateFailure = when (error) {
        is SpecializedManifestError.UnsupportedSpecializationVersion -> CandidateFailure(
            candidate.candidateId,
            ManifestResolutionStatus.UNSUPPORTED_SCHEMA,
            ManifestResolutionReason.SPECIALIZED_SCHEMA_UNSUPPORTED,
        )
        is SpecializedManifestError.DigestMismatch -> CandidateFailure(
            candidate.candidateId,
            ManifestResolutionStatus.DIGEST_MISMATCH,
            ManifestResolutionReason.SPECIALIZED_DIGEST_MISMATCH,
        )
        else -> CandidateFailure(
            candidate.candidateId,
            ManifestResolutionStatus.REJECTED,
            ManifestResolutionReason.INVALID_SPECIALIZED_MANIFEST,
        )
    }

    private fun mapSpecializedValidation(
        candidate: ManifestDefinitionCandidate,
        error: SpecializedManifestError,
    ): CandidateFailure = when (error) {
        SpecializedManifestError.CommonDefinitionMismatch -> CandidateFailure(
            candidate.candidateId,
            ManifestResolutionStatus.IDENTITY_MISMATCH,
            ManifestResolutionReason.PROVIDER_DEFINITION_MISMATCH,
        )
        SpecializedManifestError.CommonDigestMismatch -> CandidateFailure(
            candidate.candidateId,
            ManifestResolutionStatus.DIGEST_MISMATCH,
            ManifestResolutionReason.COMMON_BINDING_DIGEST_MISMATCH,
        )
        is SpecializedManifestError.WrongProviderFamily -> CandidateFailure(
            candidate.candidateId,
            ManifestResolutionStatus.INCOMPATIBLE_PROVIDER_FAMILY,
            ManifestResolutionReason.SPECIALIZATION_KIND_MISMATCH,
        )
        is SpecializedManifestError.CapabilityNotClaimedByCommonManifest -> CandidateFailure(
            candidate.candidateId,
            ManifestResolutionStatus.CONFLICTING_MANIFESTS,
            ManifestResolutionReason.SPECIALIZED_CAPABILITY_CONFLICT,
        )
        else -> CandidateFailure(
            candidate.candidateId,
            ManifestResolutionStatus.REJECTED,
            ManifestResolutionReason.INVALID_SPECIALIZED_MANIFEST,
        )
    }

    private fun mapAdapterFailure(reason: AdapterManifestCompatibilityFailure): ManifestResolutionReason = when (reason) {
        AdapterManifestCompatibilityFailure.ADAPTER_VERSION_OUTSIDE_MANIFEST_RANGE,
        AdapterManifestCompatibilityFailure.MANIFEST_ADAPTER_REFERENCE_MISMATCH,
        -> ManifestResolutionReason.ADAPTER_VERSION_INCOMPATIBLE
        AdapterManifestCompatibilityFailure.UNSUPPORTED_PROVIDER_FAMILY ->
            ManifestResolutionReason.ADAPTER_FAMILY_INCOMPATIBLE
        AdapterManifestCompatibilityFailure.UNSUPPORTED_COMMON_MANIFEST_SCHEMA ->
            ManifestResolutionReason.ADAPTER_COMMON_SCHEMA_INCOMPATIBLE
        AdapterManifestCompatibilityFailure.INVALID_SPECIALIZED_MANIFEST_BINDING,
        AdapterManifestCompatibilityFailure.UNSUPPORTED_SPECIALIZED_MANIFEST,
        AdapterManifestCompatibilityFailure.UNSUPPORTED_SPECIALIZED_MANIFEST_SCHEMA,
        -> ManifestResolutionReason.ADAPTER_SPECIALIZATION_INCOMPATIBLE
        AdapterManifestCompatibilityFailure.UNSUPPORTED_CAPABILITY ->
            ManifestResolutionReason.CAPABILITY_REFERENCE_INCOMPATIBLE
    }

    private fun collapseFailures(
        binding: IdentityManifestBinding,
        candidates: List<ManifestDefinitionCandidate>,
        failures: List<CandidateFailure>,
    ): ManifestResolution {
        val ordered = listOf(
            ManifestResolutionStatus.DIGEST_MISMATCH,
            ManifestResolutionStatus.UNSUPPORTED_SCHEMA,
            ManifestResolutionStatus.CONFLICTING_MANIFESTS,
            ManifestResolutionStatus.IDENTITY_MISMATCH,
            ManifestResolutionStatus.INCOMPATIBLE_PROVIDER_FAMILY,
            ManifestResolutionStatus.INCOMPATIBLE_ADAPTER,
            ManifestResolutionStatus.NOT_FOUND,
            ManifestResolutionStatus.REJECTED,
        )
        val selectedStatus = ordered.first { status -> failures.any { it.status == status } }
        val selected = failures.filter { it.status == selectedStatus }.minBy { it.candidateId.value }
        return outcome(selected.status, selected.reason, binding, candidates)
    }

    private fun claimSignature(candidate: ValidCandidate): String = buildString {
        val common = candidate.common.manifest
        append(common.schemaVersion.wireValue)
        append('|').append(common.provider.definitionId.value)
        append('|').append(common.provider.family.wireValue)
        append('|').append(common.provider.displayName.value)
        append('|').append(common.provider.product)
        append('|').append(common.adapter.adapterId.value)
        append('|').append(common.adapter.versions)
        append('|').append(common.capabilities.joinToString { it.wireValue })
        append('|').append(common.connectionMethods)
        append('|').append(common.requirements)
        append('|').append(common.compatibility)
        append('|').append(common.extensions)
        append('|').append(specializedSignature(candidate.specialized.manifest))
    }

    private fun specializedSignature(manifest: SpecializedProviderManifest): String = when (manifest) {
        is BodyManifest -> listOf(
            manifest.kind, manifest.schemaVersion, manifest.bodyClass, manifest.mobility,
            manifest.capabilityGroups, manifest.safetyController, manifest.mountingSupport,
            manifest.powerRelationship, manifest.geometryProfile, manifest.calibrationRequirements,
            manifest.surfaceConstraints, manifest.mountingEvidenceCapabilities, manifest.extensions,
        ).joinToString("|")
        is DeviceManifest -> listOf(
            manifest.kind, manifest.schemaVersion, manifest.category, manifest.capabilityGroups,
            manifest.placementSemantics, manifest.powerCharacteristic, manifest.controlLocality,
            manifest.extensions,
        ).joinToString("|")
        is ServiceManifest -> listOf(
            manifest.kind, manifest.schemaVersion, manifest.category, manifest.authenticationScheme,
            manifest.authorizationScopes, manifest.apiCompatibility, manifest.usagePolicyReferences,
            manifest.reachability, manifest.capabilityGroups, manifest.extensions,
        ).joinToString("|")
    }

    private fun outcome(
        status: ManifestResolutionStatus,
        reason: ManifestResolutionReason,
        binding: IdentityManifestBinding? = null,
        candidates: List<ManifestDefinitionCandidate> = emptyList(),
    ): ManifestResolution = ManifestResolution(
        status = status,
        reason = reason,
        identityBinding = binding,
        candidateIds = candidates.map { it.candidateId },
        resolved = null,
    )

    private data class BindingResult(
        val binding: IdentityManifestBinding? = null,
        val terminal: ManifestResolution? = null,
    )

    private data class CandidateFailure(
        val candidateId: ManifestCandidateId,
        val status: ManifestResolutionStatus,
        val reason: ManifestResolutionReason,
    )

    private data class DecodedCandidate(
        val common: CommonDecodedCandidate? = null,
        val failure: CandidateFailure? = null,
    )

    private data class CommonDecodedCandidate(
        val candidate: ManifestDefinitionCandidate,
        val common: CommonProviderManifestDocument,
    )

    private data class CompletedCandidate(
        val valid: ValidCandidate? = null,
        val failure: CandidateFailure? = null,
    )

    private data class ValidCandidate(
        val candidate: ManifestDefinitionCandidate,
        val common: CommonProviderManifestDocument,
        val specialized: SpecializedManifestDocument,
    )

    private data class AdapterAssessmentResult(
        val assessment: AdapterCandidateAssessment? = null,
        val failure: ManifestResolutionReason? = null,
    )
}
