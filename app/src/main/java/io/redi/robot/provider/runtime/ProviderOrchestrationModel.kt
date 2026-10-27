package io.redi.robot.provider.runtime

import io.redi.robot.adapter.compatibility.CompatibilityReport
import io.redi.robot.adapter.compatibility.HugoRuntimeEvidence
import io.redi.robot.adapter.compatibility.ProviderVersionEvidence
import io.redi.robot.adapter.registry.AdapterCompatibilityCandidate
import io.redi.robot.adapter.registry.AdapterCompatibilityCandidateSet
import io.redi.robot.adapter.registry.AdapterRegistrySnapshot
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleSnapshot
import io.redi.robot.capability.registry.ProviderRegistrySnapshot
import io.redi.robot.discovery.api.DiscoveryObservationId
import io.redi.robot.discovery.identity.IdentityResolution
import io.redi.robot.discovery.identity.IdentityResolutionRequest
import io.redi.robot.manifest.domain.ManifestDigest
import io.redi.robot.manifest.resolution.ManifestCandidateId
import io.redi.robot.manifest.resolution.ManifestCandidateProvenance
import io.redi.robot.manifest.resolution.ManifestDefinitionCandidate
import io.redi.robot.manifest.resolution.ManifestResolution
import java.util.Collections
import java.util.UUID

@JvmInline
value class ProviderOrchestrationRunId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun fromUuid(uuid: UUID): ProviderOrchestrationRunId = ProviderOrchestrationRunId(uuid.toString())

        fun parse(raw: String): ProviderOrchestrationModelResult<ProviderOrchestrationRunId> = runCatching {
            UUID.fromString(raw).toString().takeIf { it == raw }
        }.getOrNull()?.let { ProviderOrchestrationModelResult.Success(ProviderOrchestrationRunId(it)) }
            ?: ProviderOrchestrationModelResult.Failure(ProviderOrchestrationModelError.INVALID_RUN_ID)
    }
}

data class ProviderOrchestrationCorrelation(
    val runId: ProviderOrchestrationRunId,
    val generation: ProviderGeneration,
)

class ProviderOrchestrationRequest private constructor(
    val correlation: ProviderOrchestrationCorrelation,
    val identityRequest: IdentityResolutionRequest,
    val providerRegistrySnapshot: ProviderRegistrySnapshot,
    val adapterRegistrySnapshot: AdapterRegistrySnapshot,
    manifestCandidates: List<ManifestDefinitionCandidate>,
    val hugoRuntime: HugoRuntimeEvidence,
    providerVersions: List<ProviderVersionEvidence>,
) {
    val manifestCandidates: List<ManifestDefinitionCandidate> = Collections.unmodifiableList(
        manifestCandidates.sortedBy { it.candidateId.value },
    )
    val providerVersions: List<ProviderVersionEvidence> = Collections.unmodifiableList(
        providerVersions.sortedBy { "${it.subject.name}:${it.version.wireValue}:${it.evidenceReference.value}" },
    )
    val observationIds: List<DiscoveryObservationId> = Collections.unmodifiableList(
        identityRequest.observations.map { it.observationId }.sortedBy { it.value },
    )

    companion object {
        fun create(
            correlation: ProviderOrchestrationCorrelation,
            identityRequest: IdentityResolutionRequest,
            providerRegistrySnapshot: ProviderRegistrySnapshot,
            adapterRegistrySnapshot: AdapterRegistrySnapshot,
            manifestCandidates: List<ManifestDefinitionCandidate>,
            hugoRuntime: HugoRuntimeEvidence,
            providerVersions: List<ProviderVersionEvidence>,
        ): ProviderOrchestrationRequest = ProviderOrchestrationRequest(
            correlation,
            identityRequest,
            providerRegistrySnapshot,
            adapterRegistrySnapshot,
            manifestCandidates,
            hugoRuntime,
            providerVersions,
        )
    }
}

enum class ProviderOrchestrationPhase {
    INACTIVE,
    OBSERVED,
    RESOLVING_IDENTITY,
    IDENTITY_RESOLVED,
    RESOLVING_MANIFEST,
    MANIFEST_RESOLVED,
    CHECKING_COMPATIBILITY,
    PREPARED,
    NEEDS_ADAPTER,
    AMBIGUOUS,
    BLOCKED,
    FAILED,
    CANCELLED,
}

enum class ProviderOrchestrationStopReason {
    AMBIGUOUS_IDENTITY,
    CONFLICTING_IDENTITY_EVIDENCE,
    STALE_OBSERVATION,
    INSUFFICIENT_IDENTITY_EVIDENCE,
    IDENTITY_REJECTED,
    MANIFEST_NOT_FOUND,
    AMBIGUOUS_MANIFEST,
    CONFLICTING_MANIFESTS,
    MANIFEST_IDENTITY_OR_FAMILY_MISMATCH,
    MANIFEST_INTEGRITY_OR_SCHEMA_INVALID,
    MANIFEST_REJECTED,
    MISSING_ADAPTER,
    MULTIPLE_COMPATIBLE_ADAPTERS,
    INCOMPATIBLE_ADAPTER_OR_RUNTIME,
    COMPATIBILITY_UNKNOWN,
    DEPENDENCY_FAILURE,
    CANCELLED,
}

enum class ProviderOrchestrationStage {
    IDENTITY,
    MANIFEST,
    COMPATIBILITY,
}

data class ResolvedManifestCorrelation(
    val candidateId: ManifestCandidateId,
    val provenance: ManifestCandidateProvenance,
    val commonDigest: ManifestDigest,
    val specializedDigest: ManifestDigest,
)

class ProviderCompatibilityEvaluation(
    val candidates: AdapterCompatibilityCandidateSet,
    val missingAdapterReport: CompatibilityReport?,
)

data class ProviderPreparationEvidence(
    val compatibleCandidate: AdapterCompatibilityCandidate,
    val report: CompatibilityReport,
)

enum class ProviderOrchestrationAuditEvent {
    OBSERVATION_ACCEPTED,
    IDENTITY_REQUESTED,
    IDENTITY_RESULT_ACCEPTED,
    MANIFEST_REQUESTED,
    MANIFEST_RESULT_ACCEPTED,
    COMPATIBILITY_REQUESTED,
    COMPATIBILITY_RESULT_ACCEPTED,
    PREPARATION_COMPLETED,
    STOPPED,
    DEPENDENCY_FAILED,
    CANCELLED,
}

data class ProviderOrchestrationAuditEntry(
    val sequence: Int,
    val event: ProviderOrchestrationAuditEvent,
    val phase: ProviderOrchestrationPhase,
    val stage: ProviderOrchestrationStage?,
    val outcome: String,
)

class ProviderOrchestrationSnapshot internal constructor(
    val correlation: ProviderOrchestrationCorrelation?,
    val phase: ProviderOrchestrationPhase,
    val stopReason: ProviderOrchestrationStopReason?,
    observationIds: List<DiscoveryObservationId>,
    val providerLifecycle: ProviderLifecycleSnapshot,
    val identityResolution: IdentityResolution?,
    val manifestResolution: ManifestResolution?,
    val manifestCorrelation: ResolvedManifestCorrelation?,
    val compatibility: ProviderCompatibilityEvaluation?,
    val preparation: ProviderPreparationEvidence?,
    audit: List<ProviderOrchestrationAuditEntry>,
) {
    val observationIds: List<DiscoveryObservationId> = Collections.unmodifiableList(observationIds.toList())
    val audit: List<ProviderOrchestrationAuditEntry> = Collections.unmodifiableList(audit.toList())

    companion object {
        fun initial(): ProviderOrchestrationSnapshot = ProviderOrchestrationSnapshot(
            correlation = null,
            phase = ProviderOrchestrationPhase.INACTIVE,
            stopReason = null,
            observationIds = emptyList(),
            providerLifecycle = ProviderLifecycleSnapshot.initial(),
            identityResolution = null,
            manifestResolution = null,
            manifestCorrelation = null,
            compatibility = null,
            preparation = null,
            audit = emptyList(),
        )
    }
}

data class ProviderOrchestrationUpdate(
    val snapshot: ProviderOrchestrationSnapshot,
    val disposition: ProviderOrchestrationDisposition,
)

sealed interface ProviderOrchestrationDisposition {
    data class Transitioned(
        val from: ProviderOrchestrationPhase,
        val to: ProviderOrchestrationPhase,
    ) : ProviderOrchestrationDisposition

    data class Ignored(val reason: ProviderOrchestrationIgnoredReason) : ProviderOrchestrationDisposition
}

enum class ProviderOrchestrationIgnoredReason {
    NO_ACTIVE_RUN,
    ACTIVE_RUN,
    STALE_GENERATION,
    FUTURE_GENERATION,
    REUSED_GENERATION,
    REUSED_RUN_ID,
    RUN_ID_MISMATCH,
    INVALID_PHASE,
    DUPLICATE_EVENT,
    TERMINAL_STATE,
}

sealed interface ProviderOrchestrationModelResult<out T> {
    data class Success<T>(val value: T) : ProviderOrchestrationModelResult<T>
    data class Failure(val error: ProviderOrchestrationModelError) : ProviderOrchestrationModelResult<Nothing>
}

enum class ProviderOrchestrationModelError {
    INVALID_RUN_ID,
}

internal fun ProviderOrchestrationPhase.isTerminal(): Boolean = when (this) {
    ProviderOrchestrationPhase.PREPARED,
    ProviderOrchestrationPhase.NEEDS_ADAPTER,
    ProviderOrchestrationPhase.AMBIGUOUS,
    ProviderOrchestrationPhase.BLOCKED,
    ProviderOrchestrationPhase.FAILED,
    ProviderOrchestrationPhase.CANCELLED,
    -> true
    else -> false
}
