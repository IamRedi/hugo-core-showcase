package io.redi.robot.capability.negotiation

import io.redi.robot.adapter.compatibility.CompatibilityEvidenceReference
import io.redi.robot.capability.api.CapabilityContractReference
import io.redi.robot.capability.api.CapabilityDescriptor
import io.redi.robot.capability.api.CapabilityNumericLimit
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.CapabilityNegotiationResult
import io.redi.robot.capability.provider.CapabilityRegistrationResult
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.capability.provider.ProviderRuntimeSnapshot
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleSnapshot
import io.redi.robot.capability.registry.ProviderRegistrySnapshot
import io.redi.robot.connection.api.ConnectionLifecycleSnapshot
import io.redi.robot.connection.api.ConnectionMethodReference
import io.redi.robot.connection.api.ConnectionSessionId
import io.redi.robot.manifest.resolution.ManifestCandidateId
import io.redi.robot.provider.runtime.ProviderOrchestrationSnapshot
import java.util.Collections
import java.util.UUID

@JvmInline
value class CapabilityNegotiationId private constructor(val value: String) {
    companion object {
        fun fromUuid(uuid: UUID): CapabilityNegotiationId = CapabilityNegotiationId(uuid.toString())

        fun parse(raw: String): CapabilityNegotiationModelResult<CapabilityNegotiationId> = runCatching {
            UUID.fromString(raw).toString().takeIf { it == raw }
        }.getOrNull()?.let { CapabilityNegotiationModelResult.Success(CapabilityNegotiationId(it)) }
            ?: CapabilityNegotiationModelResult.Failure(CapabilityNegotiationModelError.INVALID_NEGOTIATION_ID)
    }
}

class CapabilityNegotiationCorrelation private constructor(
    val negotiationId: CapabilityNegotiationId,
    val providerInstanceId: ProviderInstanceId,
    val providerGeneration: ProviderGeneration,
    connectionSessionIds: List<ConnectionSessionId>,
) {
    val connectionSessionIds: List<ConnectionSessionId> = Collections.unmodifiableList(
        connectionSessionIds.distinct().sortedBy { it.value },
    )

    companion object {
        fun create(
            negotiationId: CapabilityNegotiationId,
            providerInstanceId: ProviderInstanceId,
            providerGeneration: ProviderGeneration,
            connectionSessionIds: List<ConnectionSessionId>,
        ): CapabilityNegotiationCorrelation = CapabilityNegotiationCorrelation(
            negotiationId,
            providerInstanceId,
            providerGeneration,
            connectionSessionIds,
        )
    }
}

data class CapabilityDescriptorEvidence(
    val descriptor: CapabilityDescriptor,
    val evidenceReference: CompatibilityEvidenceReference,
)

class ProviderCapabilityRuntimeEvidence private constructor(
    val runtime: ProviderRuntimeSnapshot,
    val negotiation: CapabilityNegotiationResult,
    val registrations: CapabilityRegistrationResult,
    val expiresAt: MonotonicTimestampMs,
    val runtimeReference: CompatibilityEvidenceReference,
    val negotiationReference: CompatibilityEvidenceReference,
    val registrationReference: CompatibilityEvidenceReference,
) {
    companion object {
        fun create(
            runtime: ProviderRuntimeSnapshot,
            negotiation: CapabilityNegotiationResult,
            registrations: CapabilityRegistrationResult,
            expiresAt: MonotonicTimestampMs,
            runtimeReference: CompatibilityEvidenceReference,
            negotiationReference: CompatibilityEvidenceReference,
            registrationReference: CompatibilityEvidenceReference,
        ): ProviderCapabilityRuntimeEvidence = ProviderCapabilityRuntimeEvidence(
            runtime,
            negotiation,
            registrations,
            expiresAt,
            runtimeReference,
            negotiationReference,
            registrationReference,
        )
    }
}

data class CapabilityConnectionEvidence(
    val providerGeneration: ProviderGeneration,
    val lifecycle: ConnectionLifecycleSnapshot,
    val evidenceReference: CompatibilityEvidenceReference,
)

class CapabilityNegotiationInput private constructor(
    val correlation: CapabilityNegotiationCorrelation,
    val preparedProvider: ProviderOrchestrationSnapshot,
    val providerIdentity: ProviderIdentity,
    val providerLifecycle: ProviderLifecycleSnapshot,
    val providerRegistry: ProviderRegistrySnapshot,
    val providerRegistryReference: CompatibilityEvidenceReference,
    descriptorEvidence: List<CapabilityDescriptorEvidence>,
    val runtimeEvidence: ProviderCapabilityRuntimeEvidence,
    connections: List<CapabilityConnectionEvidence>,
    val negotiatedAt: MonotonicTimestampMs,
    val validUntil: MonotonicTimestampMs,
) {
    val descriptorEvidence: List<CapabilityDescriptorEvidence> = Collections.unmodifiableList(
        descriptorEvidence.sortedBy {
            "${it.descriptor.reference.wireValue}:${it.evidenceReference.value}"
        },
    )
    val connections: List<CapabilityConnectionEvidence> = Collections.unmodifiableList(
        connections.sortedBy {
            val state = it.lifecycle.state
            val session = state as? io.redi.robot.connection.api.ConnectionLifecycleState.Connected
            "${session?.session?.id?.value ?: "-"}:${it.evidenceReference.value}"
        },
    )

    companion object {
        @Suppress("LongParameterList")
        fun create(
            correlation: CapabilityNegotiationCorrelation,
            preparedProvider: ProviderOrchestrationSnapshot,
            providerIdentity: ProviderIdentity,
            providerLifecycle: ProviderLifecycleSnapshot,
            providerRegistry: ProviderRegistrySnapshot,
            providerRegistryReference: CompatibilityEvidenceReference,
            descriptorEvidence: List<CapabilityDescriptorEvidence>,
            runtimeEvidence: ProviderCapabilityRuntimeEvidence,
            connections: List<CapabilityConnectionEvidence>,
            negotiatedAt: MonotonicTimestampMs,
            validUntil: MonotonicTimestampMs,
        ): CapabilityNegotiationInput = CapabilityNegotiationInput(
            correlation,
            preparedProvider,
            providerIdentity,
            providerLifecycle,
            providerRegistry,
            providerRegistryReference,
            descriptorEvidence,
            runtimeEvidence,
            connections,
            negotiatedAt,
            validUntil,
        )
    }
}

enum class CapabilityNegotiationStatus {
    NEGOTIATED,
    PARTIALLY_NEGOTIATED,
    UNSUPPORTED,
    VERSION_MISMATCH,
    RUNTIME_UNAVAILABLE,
    PROVIDER_NOT_READY,
    STALE_SESSION,
    MISSING_PREREQUISITE,
    CONFLICTING_DECLARATION,
    FAILED,
    CANCELLED,
}

enum class CapabilityNegotiationReason {
    ALL_DECLARED_CAPABILITIES_NEGOTIATED,
    SOME_DECLARED_CAPABILITIES_NEGOTIATED,
    NO_DECLARED_CAPABILITY_SUPPORTED,
    CAPABILITY_VERSION_INCOMPATIBLE,
    RUNTIME_EVIDENCE_UNAVAILABLE,
    RUNTIME_EVIDENCE_EXPIRED,
    RUNTIME_DEGRADED,
    PROVIDER_LIFECYCLE_NOT_READY,
    STALE_PROVIDER_GENERATION,
    STALE_OR_WRONG_CONNECTION_SESSION,
    REQUIRED_EVIDENCE_MISSING,
    DECLARATIONS_CONFLICT,
    DEPENDENCY_FAILURE,
    CANCELLED_BY_CALLER,
}

enum class CapabilityNegotiationDimension {
    PREPARATION,
    PROVIDER_IDENTITY,
    PROVIDER_FAMILY,
    PROVIDER_REGISTRY,
    PROVIDER_LIFECYCLE,
    PROVIDER_RUNTIME,
    CONNECTION_SESSION,
    MANIFEST_DECLARATION,
    ADAPTER_SUPPORT,
    CAPABILITY_VERSION,
    CAPABILITY_CONTRACT,
    RUNTIME_DESCRIPTOR,
    RUNTIME_LIMIT,
}

enum class CapabilityNegotiationCheckStatus {
    MATCHED,
    NARROWED,
    EXCLUDED,
    MISSING,
    FAILED,
    STALE,
    CONFLICTING,
    INFORMATIONAL,
}

enum class CapabilityNegotiationFinding {
    MATCHED,
    PROVIDER_NOT_PREPARED,
    PROVIDER_IDENTITY_MISMATCH,
    PROVIDER_FAMILY_MISMATCH,
    PROVIDER_NOT_REGISTERED,
    REGISTRY_CORRELATION_MISMATCH,
    PROVIDER_LIFECYCLE_NOT_READY,
    STALE_PROVIDER_GENERATION,
    RUNTIME_UNAVAILABLE,
    RUNTIME_EVIDENCE_EXPIRED,
    CONNECTION_NOT_REQUIRED,
    CONNECTION_EVIDENCE_MISSING,
    CONNECTION_METHOD_MISMATCH,
    CONNECTION_SESSION_STALE,
    NOT_DECLARED_BY_MANIFEST,
    ADAPTER_SUPPORT_MISSING,
    CAPABILITY_UNSUPPORTED_BY_RUNTIME,
    CAPABILITY_VERSION_MISMATCH,
    DESCRIPTOR_MISSING,
    DESCRIPTOR_CONFLICT,
    CONTRACT_MISMATCH,
    RUNTIME_LIMIT_NARROWED,
    RUNTIME_LIMIT_BROADENING,
    RUNTIME_LIMIT_CONFLICT,
    CANCELLED,
    DEPENDENCY_FAILURE,
}

enum class CapabilityNegotiationEvidenceSource {
    RESOLVED_MANIFEST,
    COMPATIBLE_ADAPTER,
    PROVIDER_REGISTRY,
    PROVIDER_RUNTIME,
    PROVIDER_NEGOTIATION,
    PROVIDER_DESCRIPTOR,
    CONNECTION_SESSION,
}

data class CapabilityNegotiationEvidence(
    val source: CapabilityNegotiationEvidenceSource,
    val reference: CompatibilityEvidenceReference,
)

data class CapabilityNegotiationCheck(
    val dimension: CapabilityNegotiationDimension,
    val checkId: String,
    val status: CapabilityNegotiationCheckStatus,
    val finding: CapabilityNegotiationFinding,
    val requiredValues: List<String>,
    val observedValues: List<String>,
    val evidence: List<CapabilityNegotiationEvidence>,
)

class NegotiatedCapabilityRecord internal constructor(
    val capability: CapabilityReference,
    val descriptor: CapabilityDescriptor,
    val providerInstanceId: ProviderInstanceId,
    val providerGeneration: ProviderGeneration,
    val negotiationId: CapabilityNegotiationId,
    connectionSessionIds: List<ConnectionSessionId>,
    val inputContract: CapabilityContractReference?,
    val outputContract: CapabilityContractReference?,
    val runtimeAvailability: ProviderRuntimeAvailability,
    limits: List<CapabilityNumericLimit>,
    val negotiatedAt: MonotonicTimestampMs,
    val validUntil: MonotonicTimestampMs,
    provenance: List<CapabilityNegotiationEvidence>,
) {
    val connectionSessionIds: List<ConnectionSessionId> = Collections.unmodifiableList(
        connectionSessionIds.distinct().sortedBy { it.value },
    )
    val limits: List<CapabilityNumericLimit> = Collections.unmodifiableList(
        limits.sortedBy { it.key.value },
    )
    val provenance: List<CapabilityNegotiationEvidence> = Collections.unmodifiableList(
        provenance.distinct().sortedWith(compareBy({ it.source.ordinal }, { it.reference.value })),
    )

    override fun toString(): String = "NegotiatedCapabilityRecord(" +
        "capability=$capability, providerInstanceId=$providerInstanceId, " +
        "providerGeneration=$providerGeneration, negotiationId=$negotiationId, " +
        "connectionSessionIds=$connectionSessionIds, inputContract=$inputContract, " +
        "outputContract=$outputContract, runtimeAvailability=$runtimeAvailability, limits=$limits, " +
        "negotiatedAt=$negotiatedAt, validUntil=$validUntil, provenance=$provenance)"
}

class CapabilityNegotiationReport internal constructor(
    val negotiationId: CapabilityNegotiationId,
    val providerInstanceId: ProviderInstanceId,
    val providerGeneration: ProviderGeneration,
    val status: CapabilityNegotiationStatus,
    val reason: CapabilityNegotiationReason,
    val manifestCandidateId: ManifestCandidateId?,
    negotiatedCapabilities: List<NegotiatedCapabilityRecord>,
    checks: List<CapabilityNegotiationCheck>,
) {
    val negotiatedCapabilities: List<NegotiatedCapabilityRecord> = Collections.unmodifiableList(
        negotiatedCapabilities.sortedBy { it.capability.wireValue },
    )
    val checks: List<CapabilityNegotiationCheck> = Collections.unmodifiableList(
        checks.sortedWith(compareBy({ it.dimension.ordinal }, { it.checkId })),
    )

    override fun toString(): String = "CapabilityNegotiationReport(" +
        "negotiationId=$negotiationId, providerInstanceId=$providerInstanceId, " +
        "providerGeneration=$providerGeneration, status=$status, reason=$reason, " +
        "manifestCandidateId=$manifestCandidateId, capabilities=${negotiatedCapabilities.map { it.capability }}, " +
        "checks=$checks)"
}

sealed interface CapabilityNegotiationModelResult<out T> {
    data class Success<T>(val value: T) : CapabilityNegotiationModelResult<T>
    data class Failure(val error: CapabilityNegotiationModelError) : CapabilityNegotiationModelResult<Nothing>
}

enum class CapabilityNegotiationModelError {
    INVALID_NEGOTIATION_ID,
}
