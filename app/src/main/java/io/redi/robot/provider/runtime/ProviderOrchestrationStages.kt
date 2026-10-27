package io.redi.robot.provider.runtime

import io.redi.robot.adapter.compatibility.CompatibilityMatcher
import io.redi.robot.adapter.compatibility.CompatibilityRequest
import io.redi.robot.adapter.registry.AdapterManifestRegistryQuery
import io.redi.robot.adapter.registry.queryManifestCompatibility
import io.redi.robot.capability.registry.ProviderRegistrySnapshot
import io.redi.robot.discovery.identity.IdentityResolution
import io.redi.robot.discovery.identity.IdentityResolutionRequest
import io.redi.robot.discovery.identity.IdentityResolver
import io.redi.robot.manifest.resolution.ManifestResolution
import io.redi.robot.manifest.resolution.ManifestResolutionRequest
import io.redi.robot.manifest.resolution.ManifestResolver
import io.redi.robot.manifest.resolution.ResolvedManifestDefinition

fun interface ProviderIdentityResolutionStage {
    fun resolve(
        request: IdentityResolutionRequest,
        knownProviders: ProviderRegistrySnapshot,
    ): IdentityResolution
}

fun interface ProviderManifestResolutionStage {
    fun resolve(request: ProviderOrchestrationRequest, identity: IdentityResolution): ManifestResolution
}

fun interface ProviderCompatibilityStage {
    fun evaluate(
        request: ProviderOrchestrationRequest,
        manifest: ResolvedManifestDefinition,
    ): ProviderCompatibilityEvaluation
}

object CanonicalProviderIdentityResolutionStage : ProviderIdentityResolutionStage {
    override fun resolve(
        request: IdentityResolutionRequest,
        knownProviders: ProviderRegistrySnapshot,
    ): IdentityResolution = IdentityResolver.resolve(request, knownProviders)
}

object CanonicalProviderManifestResolutionStage : ProviderManifestResolutionStage {
    override fun resolve(
        request: ProviderOrchestrationRequest,
        identity: IdentityResolution,
    ): ManifestResolution = ManifestResolver.resolve(
        ManifestResolutionRequest.create(
            identityResolution = identity,
            manifestCandidates = request.manifestCandidates,
            adapterCandidates = request.adapterRegistrySnapshot.records.map { it.descriptor },
        ),
    )
}

object CanonicalProviderCompatibilityStage : ProviderCompatibilityStage {
    override fun evaluate(
        request: ProviderOrchestrationRequest,
        manifest: ResolvedManifestDefinition,
    ): ProviderCompatibilityEvaluation {
        val query = AdapterManifestRegistryQuery(
            resolvedManifest = manifest,
            hugoRuntime = request.hugoRuntime,
            providerVersions = request.providerVersions,
        )
        val candidates = request.adapterRegistrySnapshot.queryManifestCompatibility(query)
        val requiredAdapterId = manifest.common.manifest.adapter.adapterId.value
        val hasRelevantCandidate = candidates.candidates.any {
            it.record.key.adapterId.wireValue == requiredAdapterId
        }
        val missingReport = if (hasRelevantCandidate) {
            null
        } else {
            CompatibilityMatcher.match(
                CompatibilityRequest.create(
                    resolvedManifest = manifest,
                    adapter = null,
                    hugoRuntime = request.hugoRuntime,
                    connectionMethods = emptyList(),
                    extensionSchemas = emptyList(),
                    providerVersions = request.providerVersions,
                ),
            )
        }
        return ProviderCompatibilityEvaluation(candidates, missingReport)
    }
}
