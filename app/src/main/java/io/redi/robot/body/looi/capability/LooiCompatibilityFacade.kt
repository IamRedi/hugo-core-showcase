package io.redi.robot.body.looi.capability

import io.redi.robot.body.looi.manifest.ExistingLooiImplementationBoundary
import io.redi.robot.body.looi.manifest.LooiCapabilityCatalog
import io.redi.robot.body.looi.manifest.LooiCapabilityLookupResult
import io.redi.robot.body.looi.manifest.LooiCapabilityMapping
import io.redi.robot.capability.api.CapabilityAuditId
import io.redi.robot.capability.api.CapabilityCancellationSemantics
import io.redi.robot.capability.api.CapabilityData
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.CapabilityFailure
import io.redi.robot.capability.api.CapabilityFailureCode
import io.redi.robot.capability.api.CapabilityOutcomeEvidence
import io.redi.robot.capability.api.CapabilityProviderTarget
import io.redi.robot.capability.api.CapabilityReasonCode
import io.redi.robot.capability.api.CapabilityRequest
import io.redi.robot.capability.api.CapabilityRequestId
import io.redi.robot.capability.api.CapabilityResult
import io.redi.robot.capability.api.CapabilityResultState
import io.redi.robot.capability.api.CapabilityExecutionContext
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.CapabilityNegotiationEntry
import io.redi.robot.capability.provider.CapabilityNegotiationRequest
import io.redi.robot.capability.provider.CapabilityNegotiationResult
import io.redi.robot.capability.provider.CapabilityNegotiationState
import io.redi.robot.capability.provider.CapabilityProvider
import io.redi.robot.capability.provider.CapabilityProviderPortResult
import io.redi.robot.capability.provider.CapabilityCancellationRequest
import io.redi.robot.capability.provider.CapabilityRegistration
import io.redi.robot.capability.provider.CapabilityRegistrationRequest
import io.redi.robot.capability.provider.CapabilityRegistrationResult
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.capability.provider.ProviderRuntimeSnapshot

/**
 * Adapter-side H018 facade. It is deliberately not a safety controller, authority, connection
 * manager, or protocol implementation. The only execution collaborator is [LooiVerifiedRuntimePort].
 */
class LooiCompatibilityFacade(
    override val identity: ProviderIdentity,
    private val runtime: LooiVerifiedRuntimePort,
) : CapabilityProvider {
    override fun runtimeSnapshot(): ProviderRuntimeSnapshot = runtime.runtimeSnapshot()

    override fun negotiate(request: CapabilityNegotiationRequest): CapabilityNegotiationResult =
        requireProviderResult(
            CapabilityNegotiationResult.create(
                providerInstanceId = identity.instanceId,
                request = request,
                entries = request.requestedCapabilities.map { reference ->
                    val mapping = LooiCapabilityCatalog.lookup(reference)
                    when {
                        request.generation != runtime.runtimeSnapshot().generation ->
                            CapabilityNegotiationEntry(reference, CapabilityNegotiationState.INCOMPATIBLE)
                        mapping is LooiCapabilityLookupResult.Supported ->
                            CapabilityNegotiationEntry(reference, CapabilityNegotiationState.SUPPORTED, mapping.mapping.descriptor.reference)
                        else -> CapabilityNegotiationEntry(reference, CapabilityNegotiationState.UNSUPPORTED)
                    }
                },
            ),
        )

    override fun registerCapabilities(request: CapabilityRegistrationRequest): CapabilityRegistrationResult =
        requireProviderResult(
            CapabilityRegistrationResult.create(
                providerInstanceId = identity.instanceId,
                request = request,
                registrations = if (request.generation == runtime.runtimeSnapshot().generation) {
                    LooiCapabilityCatalog.supported.map { CapabilityRegistration(it.descriptor) }
                } else {
                    emptyList()
                },
            ),
        )

    override fun execute(request: CapabilityRequest): CapabilityResult {
        val snapshot = runtime.runtimeSnapshot()
        val mapping = LooiCapabilityCatalog.lookup(request.capability)
        return when {
            !targetsThisProvider(request.providerTarget) -> rejected(
                request, snapshot.generation, CapabilityFailureCode.INVALID_REQUEST, "provider.target.mismatch",
            )
            request.executionContext.epoch != snapshot.generation.value -> rejected(
                request, snapshot.generation, CapabilityFailureCode.INVALID_REQUEST, "provider.generation.stale",
            )
            snapshot.availability != ProviderRuntimeAvailability.AVAILABLE -> rejected(
                request, snapshot.generation, CapabilityFailureCode.PROVIDER_UNAVAILABLE, "provider.unavailable",
            )
            mapping is LooiCapabilityLookupResult.Unsupported -> rejected(
                request, snapshot.generation, CapabilityFailureCode.UNSUPPORTED_CAPABILITY, "capability.unsupported",
            )
            mapping is LooiCapabilityLookupResult.Supported ->
                executeMapped(request, snapshot.generation, mapping.mapping)
            else -> rejected(
                request, snapshot.generation, CapabilityFailureCode.UNSUPPORTED_CAPABILITY, "capability.unsupported",
            )
        }
    }

    override fun cancel(request: CapabilityCancellationRequest): CapabilityResult {
        val snapshot = runtime.runtimeSnapshot()
        val mapping = LooiCapabilityCatalog.lookup(request.capability)
        val syntheticRequest = FacadeCorrelation(
            request.requestId, request.capability, request.providerTarget, request.executionContext,
            request.auditId, snapshot.generation,
        )
        return when {
            !targetsThisProvider(request.providerTarget) -> result(
                syntheticRequest, request.requestedAt, CapabilityResultState.REJECTED,
                CapabilityFailure(CapabilityFailureCode.INVALID_REQUEST, reason("provider.target.mismatch")),
                CapabilityOutcomeEvidence.PROVIDER_REPORTED,
            )
            request.executionContext.epoch != snapshot.generation.value -> result(
                syntheticRequest, request.requestedAt, CapabilityResultState.REJECTED,
                CapabilityFailure(CapabilityFailureCode.INVALID_REQUEST, reason("provider.generation.stale")),
                CapabilityOutcomeEvidence.PROVIDER_REPORTED,
            )
            mapping is LooiCapabilityLookupResult.Unsupported -> result(
                syntheticRequest, request.requestedAt, CapabilityResultState.REJECTED,
                CapabilityFailure(CapabilityFailureCode.UNSUPPORTED_CAPABILITY, reason("capability.unsupported")),
                CapabilityOutcomeEvidence.PROVIDER_REPORTED,
            )
            mapping is LooiCapabilityLookupResult.Supported -> mapOutcome(
                runtime.cancel(invocation(syntheticRequest, mapping.mapping, null)),
                syntheticRequest,
            )
            else -> result(
                syntheticRequest, request.requestedAt, CapabilityResultState.REJECTED,
                CapabilityFailure(CapabilityFailureCode.UNSUPPORTED_CAPABILITY, reason("capability.unsupported")),
                CapabilityOutcomeEvidence.PROVIDER_REPORTED,
            )
        }
    }

    private fun executeMapped(
        request: CapabilityRequest,
        generation: ProviderGeneration,
        mapping: LooiCapabilityMapping,
    ): CapabilityResult {
        val semanticInput = semanticInputFor(request, mapping)
        if (semanticInput is SemanticInput.Invalid) {
            return rejected(request, generation, CapabilityFailureCode.INVALID_REQUEST, "capability.input.invalid")
        }
        semanticInput as SemanticInput.Valid
        return mapOutcome(
            runtime.execute(invocation(correlation(request, generation), mapping, semanticInput.input)),
            correlation(request, generation),
        )
    }

    private fun semanticInputFor(
        request: CapabilityRequest,
        mapping: LooiCapabilityMapping,
    ): SemanticInput = when (mapping.boundary) {
        ExistingLooiImplementationBoundary.EXISTING_DRIVE_SAFETY_PATH -> {
            if (mapping.descriptor.reference.wireValue == "mobility.stop@1.0") {
                if (request.input == null) SemanticInput.Valid(null) else SemanticInput.Invalid
            } else {
                request.input?.let(SemanticInput::Valid) ?: SemanticInput.Invalid
            }
        }
        ExistingLooiImplementationBoundary.EXISTING_HEAD_SAFETY_PATH,
        ExistingLooiImplementationBoundary.EXISTING_LIGHT_SAFETY_PATH,
        -> request.input?.let(SemanticInput::Valid) ?: SemanticInput.Invalid
        ExistingLooiImplementationBoundary.EXISTING_LOWER_CONTACT_SAFETY_OBSERVATION,
        ExistingLooiImplementationBoundary.EXISTING_CONNECTION_STATE,
        -> if (request.input == null) SemanticInput.Valid(null) else SemanticInput.Invalid
    }

    private fun invocation(
        correlation: FacadeCorrelation,
        mapping: LooiCapabilityMapping,
        input: CapabilityData?,
    ): LooiRuntimeInvocation = LooiRuntimeInvocation(
        correlation = correlation,
        boundary = mapping.boundary,
        input = input,
        priority = if (mapping.descriptor.reference.wireValue == "mobility.stop@1.0") {
            LooiRuntimePriority.STOP
        } else {
            LooiRuntimePriority.NORMAL
        },
    )

    private fun mapOutcome(outcome: LooiVerifiedRuntimeOutcome, expected: FacadeCorrelation): CapabilityResult {
        if (outcome.correlation != expected) {
            return result(
                expected, outcome.occurredAt, CapabilityResultState.FAILED,
                CapabilityFailure(CapabilityFailureCode.MALFORMED_RESULT, reason("runtime.correlation.mismatch")),
                CapabilityOutcomeEvidence.PROVIDER_REPORTED,
            )
        }
        return when (outcome) {
            is LooiVerifiedRuntimeOutcome.Accepted -> result(
                expected, outcome.occurredAt, CapabilityResultState.ACCEPTED, null,
                CapabilityOutcomeEvidence.REQUEST_ACCEPTED,
            )
            is LooiVerifiedRuntimeOutcome.Completed -> result(
                expected, outcome.occurredAt, CapabilityResultState.COMPLETED, null,
                CapabilityOutcomeEvidence.PROVIDER_REPORTED, outcome.output,
            )
            is LooiVerifiedRuntimeOutcome.SafetyRejected -> result(
                expected, outcome.occurredAt, CapabilityResultState.REJECTED,
                CapabilityFailure(CapabilityFailureCode.SAFETY_REJECTED, outcome.reason),
                CapabilityOutcomeEvidence.PROVIDER_REPORTED,
            )
            is LooiVerifiedRuntimeOutcome.Unavailable -> result(
                expected, outcome.occurredAt, CapabilityResultState.REJECTED,
                CapabilityFailure(CapabilityFailureCode.PROVIDER_UNAVAILABLE, outcome.reason),
                CapabilityOutcomeEvidence.PROVIDER_REPORTED,
            )
            is LooiVerifiedRuntimeOutcome.TransportFailure -> result(
                expected, outcome.occurredAt, CapabilityResultState.FAILED,
                CapabilityFailure(CapabilityFailureCode.TRANSPORT_FAILURE, outcome.reason),
                CapabilityOutcomeEvidence.PROVIDER_REPORTED,
            )
            is LooiVerifiedRuntimeOutcome.ProviderFailure -> result(
                expected, outcome.occurredAt, CapabilityResultState.FAILED,
                CapabilityFailure(CapabilityFailureCode.PROVIDER_FAILURE, outcome.reason),
                CapabilityOutcomeEvidence.PROVIDER_REPORTED,
            )
            is LooiVerifiedRuntimeOutcome.Cancelled -> result(
                expected, outcome.occurredAt, CapabilityResultState.CANCELLED,
                CapabilityFailure(CapabilityFailureCode.CANCELLED, outcome.reason),
                CapabilityOutcomeEvidence.PROVIDER_REPORTED,
            )
            is LooiVerifiedRuntimeOutcome.TimedOut -> result(
                expected, outcome.occurredAt, CapabilityResultState.TIMED_OUT,
                CapabilityFailure(CapabilityFailureCode.TIMEOUT, outcome.reason),
                CapabilityOutcomeEvidence.PROVIDER_REPORTED,
            )
        }
    }

    private fun rejected(
        request: CapabilityRequest,
        generation: ProviderGeneration,
        code: CapabilityFailureCode,
        reason: String,
    ): CapabilityResult = result(
        correlation(request, generation), request.createdAt, CapabilityResultState.REJECTED,
        CapabilityFailure(code, reason(reason)), CapabilityOutcomeEvidence.PROVIDER_REPORTED,
    )

    private fun result(
        correlation: FacadeCorrelation,
        occurredAt: MonotonicTimestampMs,
        state: CapabilityResultState,
        failure: CapabilityFailure?,
        evidence: CapabilityOutcomeEvidence,
        output: CapabilityData? = null,
    ): CapabilityResult = requireEnvelope(
        CapabilityResult.create(
            correlation.requestId, correlation.capability, identity.instanceId, correlation.executionContext,
            occurredAt, state, failure, evidence, output, correlation.auditId,
        ),
    )

    private fun correlation(request: CapabilityRequest, generation: ProviderGeneration): FacadeCorrelation =
        FacadeCorrelation(
            request.requestId, request.capability, request.providerTarget, request.executionContext,
            request.auditId, generation,
        )

    private fun targetsThisProvider(target: CapabilityProviderTarget): Boolean = target !is CapabilityProviderTarget.Exact ||
        target.providerInstanceId == identity.instanceId

    private fun reason(value: String): CapabilityReasonCode = requireEnvelope(CapabilityReasonCode.parse(value))

    private fun <T> requireProviderResult(result: CapabilityProviderPortResult<T>): T = when (result) {
        is CapabilityProviderPortResult.Success -> result.value
        is CapabilityProviderPortResult.Failure -> error("Invalid static provider result: $result")
    }

    private fun <T> requireEnvelope(result: CapabilityEnvelopeResult<T>): T = when (result) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> error("Invalid facade envelope: $result")
    }
}

private sealed interface SemanticInput {
    data class Valid(val input: CapabilityData?) : SemanticInput

    data object Invalid : SemanticInput
}

/** Correlation is immutable and includes H007 request/audit/epoch/sequence plus provider generation. */
data class FacadeCorrelation(
    val requestId: CapabilityRequestId,
    val capability: io.redi.robot.capability.api.CapabilityReference,
    val providerTarget: CapabilityProviderTarget,
    val executionContext: CapabilityExecutionContext,
    val auditId: CapabilityAuditId,
    val providerGeneration: ProviderGeneration,
)

enum class LooiRuntimePriority { NORMAL, STOP }

data class LooiRuntimeInvocation(
    val correlation: FacadeCorrelation,
    val boundary: ExistingLooiImplementationBoundary,
    val input: CapabilityData?,
    val priority: LooiRuntimePriority,
)

/**
 * The only H018 bridge to legacy code. A production implementation must delegate to the existing
 * T032 safety/authority/dead-man/E-stop/sensor-gated path; it must not expose those internals.
 */
interface LooiVerifiedRuntimePort {
    fun runtimeSnapshot(): ProviderRuntimeSnapshot

    fun execute(invocation: LooiRuntimeInvocation): LooiVerifiedRuntimeOutcome

    fun cancel(invocation: LooiRuntimeInvocation): LooiVerifiedRuntimeOutcome
}

sealed interface LooiVerifiedRuntimeOutcome {
    val correlation: FacadeCorrelation
    val occurredAt: MonotonicTimestampMs

    data class Accepted(
        override val correlation: FacadeCorrelation,
        override val occurredAt: MonotonicTimestampMs,
    ) : LooiVerifiedRuntimeOutcome

    data class Completed(
        override val correlation: FacadeCorrelation,
        override val occurredAt: MonotonicTimestampMs,
        val output: CapabilityData,
    ) : LooiVerifiedRuntimeOutcome

    data class SafetyRejected(
        override val correlation: FacadeCorrelation,
        override val occurredAt: MonotonicTimestampMs,
        val reason: CapabilityReasonCode,
    ) : LooiVerifiedRuntimeOutcome

    data class Unavailable(
        override val correlation: FacadeCorrelation,
        override val occurredAt: MonotonicTimestampMs,
        val reason: CapabilityReasonCode,
    ) : LooiVerifiedRuntimeOutcome

    data class TransportFailure(
        override val correlation: FacadeCorrelation,
        override val occurredAt: MonotonicTimestampMs,
        val reason: CapabilityReasonCode,
    ) : LooiVerifiedRuntimeOutcome

    data class ProviderFailure(
        override val correlation: FacadeCorrelation,
        override val occurredAt: MonotonicTimestampMs,
        val reason: CapabilityReasonCode,
    ) : LooiVerifiedRuntimeOutcome

    data class Cancelled(
        override val correlation: FacadeCorrelation,
        override val occurredAt: MonotonicTimestampMs,
        val reason: CapabilityReasonCode,
    ) : LooiVerifiedRuntimeOutcome

    data class TimedOut(
        override val correlation: FacadeCorrelation,
        override val occurredAt: MonotonicTimestampMs,
        val reason: CapabilityReasonCode,
    ) : LooiVerifiedRuntimeOutcome
}
