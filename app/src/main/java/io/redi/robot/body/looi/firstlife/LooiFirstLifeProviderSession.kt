package io.redi.robot.body.looi.firstlife

import io.redi.robot.adapter.api.HugoRuntimeVersion
import io.redi.robot.adapter.compatibility.CompatibilityEvidenceReference
import io.redi.robot.adapter.compatibility.CompatibilityInputResult
import io.redi.robot.adapter.compatibility.CompatibilityReportStatus
import io.redi.robot.adapter.compatibility.HugoRuntimeEvidence
import io.redi.robot.adapter.registry.AdapterCompatibilityCandidate
import io.redi.robot.adapter.registry.AdapterCompatibilityCandidateSet
import io.redi.robot.adapter.registry.AdapterManifestRegistryQuery
import io.redi.robot.adapter.registry.AdapterRegistry
import io.redi.robot.adapter.registry.AdapterRegistryMutationResult
import io.redi.robot.adapter.registry.queryManifestCompatibility
import io.redi.robot.body.looi.capability.FacadeCorrelation
import io.redi.robot.body.looi.capability.LooiCompatibilityFacade
import io.redi.robot.body.looi.capability.LooiRuntimeInvocation
import io.redi.robot.body.looi.capability.LooiVerifiedRuntimeOutcome
import io.redi.robot.body.looi.capability.LooiVerifiedRuntimePort
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.negotiation.CapabilityConnectionEvidence
import io.redi.robot.capability.negotiation.CapabilityDescriptorEvidence
import io.redi.robot.capability.negotiation.CapabilityNegotiationCorrelation
import io.redi.robot.capability.negotiation.CapabilityNegotiationId
import io.redi.robot.capability.negotiation.CapabilityNegotiationInput
import io.redi.robot.capability.negotiation.CapabilityNegotiationReport
import io.redi.robot.capability.negotiation.CapabilityNegotiator
import io.redi.robot.capability.negotiation.ProviderCapabilityRuntimeEvidence
import io.redi.robot.capability.provider.CapabilityNegotiationRequest
import io.redi.robot.capability.provider.CapabilityProvider
import io.redi.robot.capability.provider.CapabilityProviderPortResult
import io.redi.robot.capability.provider.CapabilityRegistrationRequest
import io.redi.robot.capability.provider.ProviderAvailabilityReason
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.capability.provider.ProviderRuntimeSnapshot
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleEvent
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleReducer
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleSnapshot
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleState
import io.redi.robot.capability.registry.InMemoryProviderRegistry
import io.redi.robot.capability.registry.ProviderAdapterReference
import io.redi.robot.capability.registry.ProviderManifestReference
import io.redi.robot.capability.registry.ProviderRegistryRecord
import io.redi.robot.capability.registry.ProviderRegistryResult
import io.redi.robot.capability.registry.ProviderRegistryRuntimeSnapshot
import io.redi.robot.capability.registry.ProviderRegistrySnapshot
import io.redi.robot.connection.api.ConnectionLifecycleSnapshot
import io.redi.robot.connection.api.ConnectionLifecycleState
import io.redi.robot.discovery.identity.IdentityResolution
import io.redi.robot.discovery.identity.IdentityResolutionReason
import io.redi.robot.discovery.identity.IdentityResolutionStatus
import io.redi.robot.discovery.identity.KnownProviderIdentityCandidate
import io.redi.robot.discovery.identity.NewProviderIdentityCandidate
import io.redi.robot.manifest.domain.CommonManifestBinding
import io.redi.robot.manifest.domain.SpecializedManifestKind
import io.redi.robot.manifest.resolution.ManifestResolutionRequest
import io.redi.robot.manifest.resolution.ManifestResolutionStatus
import io.redi.robot.manifest.resolution.ManifestResolver
import io.redi.robot.provider.runtime.ProviderCompatibilityEvaluation
import io.redi.robot.provider.runtime.ProviderOrchestrationCorrelation
import io.redi.robot.provider.runtime.ProviderOrchestrationPhase
import io.redi.robot.provider.runtime.ProviderOrchestrationRunId
import io.redi.robot.provider.runtime.ProviderOrchestrationSnapshot
import io.redi.robot.provider.runtime.ProviderPreparationEvidence
import java.util.UUID

/**
 * Adapter-owned LOOI composition over accepted HUGO contracts. It creates evidence only: this type
 * has no connect, selection, trust, authorization, or execution entry point.
 */
class LooiFirstLifeProviderSession(
    val identity: ProviderIdentity,
    private val generation: ProviderGeneration,
    identityMapping: LooiFirstLifeIdentityMapping? = null,
) {
    private val adapterRegistry = AdapterRegistry().also {
        check(it.register(LooiFirstLifeDefinition.adapterRecord, it.snapshot().revision) is
            AdapterRegistryMutationResult.Registered)
    }
    private val identityResolution = identityMapping?.takeIf {
        it.evidence.target.instanceId == null
    }?.let { mapping ->
        IdentityResolution(
            IdentityResolutionStatus.NEW_PROVIDER_CANDIDATE,
            IdentityResolutionReason.DEFINITION_CANDIDATE_ONLY,
            setOf(mapping.observation.observationId),
            listOf(NewProviderIdentityCandidate(
                identity.definitionId,
                identity.family,
                null,
                setOf(mapping.evidence.evidenceId),
            )),
            setOf(mapping.evidence.evidenceId),
        )
    } ?: IdentityResolution(
        IdentityResolutionStatus.MATCHED_KNOWN_PROVIDER,
        IdentityResolutionReason.STRONG_VERIFIED_INSTANCE_EVIDENCE,
        identityMapping?.let { setOf(it.observation.observationId) }.orEmpty(),
        listOf(KnownProviderIdentityCandidate(
            identity,
            identityMapping?.let { setOf(it.evidence.evidenceId) }.orEmpty(),
        )),
        identityMapping?.let { setOf(it.evidence.evidenceId) }.orEmpty(),
    )
    private val manifestResolution = ManifestResolver.resolve(ManifestResolutionRequest.create(
        identityResolution,
        listOf(LooiFirstLifeDefinition.manifestCandidate),
        listOf(LooiFirstLifeDefinition.adapterDescriptor),
    ))
    private val resolved = checkNotNull(manifestResolution.resolved) {
        "Built-in LOOI manifest failed resolution: ${manifestResolution.status}/${manifestResolution.reason}"
    }
    private val runtimeEvidence = HugoRuntimeEvidence(
        adapter(io.redi.robot.adapter.api.HugoRuntimeVersion.parse("1.0.0")),
        evidence("hugo.runtime.first-life.v1"),
    )
    private val compatible = adapterRegistry.snapshot().queryManifestCompatibility(
        AdapterManifestRegistryQuery(resolved, runtimeEvidence, emptyList()),
    ).candidates.single().also {
        check(it.report.status == CompatibilityReportStatus.COMPATIBLE)
    }
    val preparedProvider: ProviderOrchestrationSnapshot = preparedSnapshot(compatible)
    var providerRegistrySnapshot: ProviderRegistrySnapshot = io.redi.robot.capability.registry.ProviderRegistrySnapshot.empty()
        private set

    fun negotiate(
        connection: ConnectionLifecycleSnapshot,
        protocolReady: Boolean,
        nowMs: Long,
    ): CapabilityNegotiationReport? {
        val connected = connection.state as? ConnectionLifecycleState.Connected ?: return null
        if (!protocolReady || connection.latestGeneration?.value != generation.value ||
            connected.session.generation.value != generation.value ||
            resolved.identityBinding.instanceId != identity.instanceId
        ) return null
        val now = timestamp(nowMs)
        val runtime = ReadinessOnlyRuntime(generation, now)
        val facade = LooiCompatibilityFacade(identity, runtime)
        val negotiationRequest = provider(CapabilityNegotiationRequest.create(
            generation,
            LooiFirstLifeDefinition.capabilityReferences,
        ))
        val providerNegotiation = facade.negotiate(negotiationRequest)
        val registration = facade.registerCapabilities(CapabilityRegistrationRequest.create(generation))
        val providerLifecycle = lifecycle(generation, negotiating = true)
        val registry = InMemoryProviderRegistry()
        val registryRuntime = providerRegistry(ProviderRegistryRuntimeSnapshot.create(
            generation,
            ProviderLifecycleState.Registered(generation),
            runtime.runtimeSnapshot(),
        ))
        val registryRecord = providerRegistry(ProviderRegistryRecord.create(
            identity,
            ProviderManifestReference(CommonManifestBinding.from(resolved.common), SpecializedManifestKind.BODY),
            ProviderAdapterReference(
                LooiFirstLifeDefinition.adapterDescriptor.identity,
                LooiFirstLifeDefinition.adapterDescriptor.packageDigest,
            ),
            registration.registrations.map { it.descriptor },
            registryRuntime,
        ))
        providerRegistry(registry.register(registryRecord))
        providerRegistrySnapshot = registry.snapshot()
        val negotiationId = CapabilityNegotiationId.fromUuid(UUID.randomUUID())
        val report = CapabilityNegotiator.negotiate(CapabilityNegotiationInput.create(
            correlation = CapabilityNegotiationCorrelation.create(
                negotiationId,
                identity.instanceId,
                generation,
                listOf(connected.session.id),
            ),
            preparedProvider = preparedProvider,
            providerIdentity = identity,
            providerLifecycle = providerLifecycle,
            providerRegistry = registry.snapshot(),
            providerRegistryReference = evidence("hugo.registry.looi.current"),
            descriptorEvidence = registration.registrations.map {
                CapabilityDescriptorEvidence(it.descriptor, evidence("hugo.h017.looi.${it.descriptor.reference.id.name.value}"))
            },
            runtimeEvidence = ProviderCapabilityRuntimeEvidence.create(
                runtime.runtimeSnapshot(),
                providerNegotiation,
                registration,
                timestamp(nowMs + NEGOTIATION_TTL_MS),
                evidence("hugo.looi.runtime.current"),
                evidence("hugo.looi.negotiation.current"),
                evidence("hugo.looi.registration.current"),
            ),
            connections = listOf(CapabilityConnectionEvidence(
                generation,
                connection,
                evidence("hugo.looi.connection.current"),
            )),
            negotiatedAt = now,
            validUntil = timestamp(nowMs + NEGOTIATION_TTL_MS),
        ))
        check(runtime.executionAttempts == 0)
        return report
    }

    fun isValidated(): Boolean = manifestResolution.status == ManifestResolutionStatus.RESOLVED &&
        compatible.report.status == CompatibilityReportStatus.COMPATIBLE

    /** FL007 execution facade; authority remains outside and runtime safety remains in the delegate. */
    fun capabilityProvider(runtime: LooiVerifiedRuntimePort): CapabilityProvider {
        check(isValidated())
        return LooiCompatibilityFacade(identity, runtime)
    }

    private fun preparedSnapshot(candidate: AdapterCompatibilityCandidate): ProviderOrchestrationSnapshot =
        ProviderOrchestrationSnapshot(
            correlation = ProviderOrchestrationCorrelation(ProviderOrchestrationRunId.fromUuid(UUID.randomUUID()), generation),
            phase = ProviderOrchestrationPhase.PREPARED,
            stopReason = null,
            observationIds = emptyList(),
            providerLifecycle = lifecycle(generation, negotiating = false),
            identityResolution = identityResolution,
            manifestResolution = manifestResolution,
            manifestCorrelation = null,
            compatibility = ProviderCompatibilityEvaluation(AdapterCompatibilityCandidateSet(listOf(candidate)), null),
            preparation = ProviderPreparationEvidence(candidate, candidate.report),
            audit = emptyList(),
        )

    private class ReadinessOnlyRuntime(
        private val generation: ProviderGeneration,
        private val now: MonotonicTimestampMs,
    ) : LooiVerifiedRuntimePort {
        var executionAttempts: Int = 0
            private set

        override fun runtimeSnapshot(): ProviderRuntimeSnapshot = provider(
            ProviderRuntimeSnapshot.create(generation, ProviderRuntimeAvailability.AVAILABLE, now, null),
        )

        override fun execute(invocation: LooiRuntimeInvocation): LooiVerifiedRuntimeOutcome {
            executionAttempts++
            return unavailable(invocation.correlation)
        }

        override fun cancel(invocation: LooiRuntimeInvocation): LooiVerifiedRuntimeOutcome {
            executionAttempts++
            return unavailable(invocation.correlation)
        }

        private fun unavailable(correlation: FacadeCorrelation) = LooiVerifiedRuntimeOutcome.Unavailable(
            correlation,
            now,
            reason("provider.first-life.dispatch.not-wired"),
        )
    }

    private companion object {
        const val NEGOTIATION_TTL_MS = 60_000L
    }
}

/** H029 lease check used before an explicit hold; it does not refresh or grant anything itself. */
internal fun CapabilityNegotiationReport.isCurrentForFirstLife(nowMs: Long): Boolean =
    status == io.redi.robot.capability.negotiation.CapabilityNegotiationStatus.NEGOTIATED &&
        negotiatedCapabilities.isNotEmpty() &&
        negotiatedCapabilities.all { record ->
            nowMs >= record.negotiatedAt.value && nowMs < record.validUntil.value
        }

private fun lifecycle(generation: ProviderGeneration, negotiating: Boolean): ProviderLifecycleSnapshot {
    var value = ProviderLifecycleSnapshot.initial()
    value = ProviderLifecycleReducer.reduce(value, ProviderLifecycleEvent.Discovered(generation)).snapshot
    value = ProviderLifecycleReducer.reduce(value, ProviderLifecycleEvent.IdentityResolved(generation)).snapshot
    value = ProviderLifecycleReducer.reduce(value, ProviderLifecycleEvent.ConnectRequested(generation)).snapshot
    value = ProviderLifecycleReducer.reduce(value, ProviderLifecycleEvent.ConnectionEstablished(generation)).snapshot
    if (negotiating) value = ProviderLifecycleReducer.reduce(value, ProviderLifecycleEvent.NegotiationRequested(generation)).snapshot
    return value
}

private fun timestamp(value: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(value)) {
    is CapabilityEnvelopeResult.Success -> result.value
    is CapabilityEnvelopeResult.Failure -> error(result.error)
}
private fun evidence(raw: String): CompatibilityEvidenceReference = when (
    val result = CompatibilityEvidenceReference.parse(raw)
) {
    is CompatibilityInputResult.Success -> result.value
    is CompatibilityInputResult.Failure -> error(result.error)
}
private fun <T> provider(result: CapabilityProviderPortResult<T>): T =
    (result as CapabilityProviderPortResult.Success).value
private fun <T> providerRegistry(result: ProviderRegistryResult<T>): T =
    (result as ProviderRegistryResult.Success).value
private fun <T> adapter(result: io.redi.robot.adapter.api.AdapterResult<T>): T =
    (result as io.redi.robot.adapter.api.AdapterResult.Success).value
private fun reason(raw: String) = when (val result = io.redi.robot.capability.api.CapabilityReasonCode.parse(raw)) {
    is CapabilityEnvelopeResult.Success -> result.value
    is CapabilityEnvelopeResult.Failure -> error(result.error)
}
