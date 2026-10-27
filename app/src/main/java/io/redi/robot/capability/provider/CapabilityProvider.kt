package io.redi.robot.capability.provider

import io.redi.robot.capability.api.CapabilityCompatibility
import io.redi.robot.capability.api.CapabilityDescriptor
import io.redi.robot.capability.api.CapabilityRequest
import io.redi.robot.capability.api.CapabilityRequestId
import io.redi.robot.capability.api.CapabilityResult
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.CapabilityExecutionContext
import io.redi.robot.capability.api.CapabilityProviderTarget
import io.redi.robot.capability.api.CapabilityAuditId
import io.redi.robot.capability.api.MonotonicTimestampMs

/**
 * Universal, provider-neutral port through which HUGO may interact with a capability provider.
 *
 * Implementations may be body adapters, device adapters, or service connectors. This port exposes
 * neither vendor/transport details nor authorization, safety, trust, or feasibility decisions.
 * Those decisions remain outside the provider boundary. H009 owns lifecycle transitions; the
 * snapshot here is an observation only.
 */
interface CapabilityProvider {
    val identity: ProviderIdentity

    fun runtimeSnapshot(): ProviderRuntimeSnapshot

    fun negotiate(request: CapabilityNegotiationRequest): CapabilityNegotiationResult

    /**
     * Reports this provider's descriptors for the supplied runtime generation. It does not insert
     * them into a registry, establish trust, or authorize use; H015 owns registry behavior.
     */
    fun registerCapabilities(request: CapabilityRegistrationRequest): CapabilityRegistrationResult

    /** Returns an H007-correlated outcome for one concrete request. */
    fun execute(request: CapabilityRequest): CapabilityResult

    /** Requests cancellation of a concrete invocation and returns its H007-correlated outcome. */
    fun cancel(request: CapabilityCancellationRequest): CapabilityResult
}

@JvmInline
value class ProviderGeneration private constructor(val value: Long) {
    companion object {
        fun create(value: Long): CapabilityProviderPortResult<ProviderGeneration> = if (value < 0L) {
            CapabilityProviderPortResult.Failure(
                CapabilityProviderPortError.InvalidGeneration(value),
            )
        } else {
            CapabilityProviderPortResult.Success(ProviderGeneration(value))
        }
    }
}

/** Runtime observation; it is intentionally separate from [ProviderIdentity] and declarations. */
enum class ProviderRuntimeAvailability {
    AVAILABLE,
    DEGRADED,
    UNAVAILABLE,
}

enum class ProviderAvailabilityReason {
    NOT_READY,
    TEMPORARILY_UNAVAILABLE,
    PERMISSION_UNAVAILABLE,
    CONNECTION_UNAVAILABLE,
    PROVIDER_FAILURE,
    UNKNOWN,
}

class ProviderRuntimeSnapshot private constructor(
    val generation: ProviderGeneration,
    val availability: ProviderRuntimeAvailability,
    val observedAt: MonotonicTimestampMs,
    val reason: ProviderAvailabilityReason?,
) {
    companion object {
        fun create(
            generation: ProviderGeneration,
            availability: ProviderRuntimeAvailability,
            observedAt: MonotonicTimestampMs,
            reason: ProviderAvailabilityReason?,
        ): CapabilityProviderPortResult<ProviderRuntimeSnapshot> {
            if ((availability == ProviderRuntimeAvailability.AVAILABLE) != (reason == null)) {
                return CapabilityProviderPortResult.Failure(
                    CapabilityProviderPortError.AvailabilityReasonConflict(availability, reason),
                )
            }
            return CapabilityProviderPortResult.Success(
                ProviderRuntimeSnapshot(generation, availability, observedAt, reason),
            )
        }
    }
}

class CapabilityNegotiationRequest private constructor(
    val generation: ProviderGeneration,
    val requestedCapabilities: List<CapabilityReference>,
) {
    companion object {
        private const val maximumCapabilities = 64

        fun create(
            generation: ProviderGeneration,
            requestedCapabilities: List<CapabilityReference>,
        ): CapabilityProviderPortResult<CapabilityNegotiationRequest> = when {
            requestedCapabilities.isEmpty() -> CapabilityProviderPortResult.Failure(
                CapabilityProviderPortError.EmptyNegotiation,
            )
            requestedCapabilities.size > maximumCapabilities -> CapabilityProviderPortResult.Failure(
                CapabilityProviderPortError.TooManyCapabilities(maximumCapabilities),
            )
            requestedCapabilities.toSet().size != requestedCapabilities.size ->
                CapabilityProviderPortResult.Failure(
                    CapabilityProviderPortError.DuplicateCapabilityReferences,
                )
            else -> CapabilityProviderPortResult.Success(
                CapabilityNegotiationRequest(generation, requestedCapabilities.toList()),
            )
        }
    }
}

enum class CapabilityNegotiationState {
    SUPPORTED,
    UNSUPPORTED,
    INCOMPATIBLE,
}

data class CapabilityNegotiationEntry(
    val requested: CapabilityReference,
    val state: CapabilityNegotiationState,
    val offered: CapabilityReference? = null,
)

class CapabilityNegotiationResult private constructor(
    val providerInstanceId: ProviderInstanceId,
    val generation: ProviderGeneration,
    val entries: List<CapabilityNegotiationEntry>,
) {
    companion object {
        fun create(
            providerInstanceId: ProviderInstanceId,
            request: CapabilityNegotiationRequest,
            entries: List<CapabilityNegotiationEntry>,
        ): CapabilityProviderPortResult<CapabilityNegotiationResult> {
            if (entries.map { it.requested }.toSet() != request.requestedCapabilities.toSet() ||
                entries.size != request.requestedCapabilities.size
            ) {
                return CapabilityProviderPortResult.Failure(
                    CapabilityProviderPortError.NegotiationRequestMismatch,
                )
            }
            if (entries.any { entry ->
                    when (entry.state) {
                        CapabilityNegotiationState.SUPPORTED -> entry.offered == null ||
                            entry.offered.compatibilityWith(entry.requested) is CapabilityCompatibility.Incompatible
                        CapabilityNegotiationState.UNSUPPORTED,
                        CapabilityNegotiationState.INCOMPATIBLE,
                        -> entry.offered != null
                    }
                }
            ) {
                return CapabilityProviderPortResult.Failure(
                    CapabilityProviderPortError.InvalidNegotiationEntry,
                )
            }
            return CapabilityProviderPortResult.Success(
                CapabilityNegotiationResult(
                    providerInstanceId = providerInstanceId,
                    generation = request.generation,
                    entries = entries.toList(),
                ),
            )
        }
    }
}

class CapabilityRegistrationRequest private constructor(
    val generation: ProviderGeneration,
) {
    companion object {
        fun create(generation: ProviderGeneration): CapabilityRegistrationRequest =
            CapabilityRegistrationRequest(generation)
    }
}

data class CapabilityRegistration(
    val descriptor: CapabilityDescriptor,
)

class CapabilityRegistrationResult private constructor(
    val providerInstanceId: ProviderInstanceId,
    val generation: ProviderGeneration,
    val registrations: List<CapabilityRegistration>,
) {
    companion object {
        fun create(
            providerInstanceId: ProviderInstanceId,
            request: CapabilityRegistrationRequest,
            registrations: List<CapabilityRegistration>,
        ): CapabilityProviderPortResult<CapabilityRegistrationResult> {
            if (registrations.map { it.descriptor.reference }.toSet().size != registrations.size) {
                return CapabilityProviderPortResult.Failure(
                    CapabilityProviderPortError.DuplicateCapabilityReferences,
                )
            }
            return CapabilityProviderPortResult.Success(
                CapabilityRegistrationResult(
                    providerInstanceId = providerInstanceId,
                    generation = request.generation,
                    registrations = registrations.toList(),
                ),
            )
        }
    }
}

/** A cancellation envelope that preserves the original H007 correlation tuple. */
class CapabilityCancellationRequest private constructor(
    val requestId: CapabilityRequestId,
    val capability: CapabilityReference,
    val providerTarget: CapabilityProviderTarget,
    val executionContext: CapabilityExecutionContext,
    val auditId: CapabilityAuditId,
    val requestedAt: MonotonicTimestampMs,
) {
    companion object {
        fun from(
            request: CapabilityRequest,
            requestedAt: MonotonicTimestampMs,
        ): CapabilityCancellationRequest = CapabilityCancellationRequest(
            requestId = request.requestId,
            capability = request.capability,
            providerTarget = request.providerTarget,
            executionContext = request.executionContext,
            auditId = request.auditId,
            requestedAt = requestedAt,
        )
    }
}

sealed interface CapabilityProviderPortResult<out T> {
    data class Success<T>(val value: T) : CapabilityProviderPortResult<T>

    data class Failure(val error: CapabilityProviderPortError) : CapabilityProviderPortResult<Nothing>
}

sealed interface CapabilityProviderPortError {
    data class InvalidGeneration(val value: Long) : CapabilityProviderPortError

    data object EmptyNegotiation : CapabilityProviderPortError

    data class TooManyCapabilities(val maximum: Int) : CapabilityProviderPortError

    data object DuplicateCapabilityReferences : CapabilityProviderPortError

    data object NegotiationRequestMismatch : CapabilityProviderPortError

    data object InvalidNegotiationEntry : CapabilityProviderPortError

    data class AvailabilityReasonConflict(
        val availability: ProviderRuntimeAvailability,
        val reason: ProviderAvailabilityReason?,
    ) : CapabilityProviderPortError
}
