package io.redi.robot.capability.negotiation

import io.redi.robot.adapter.compatibility.CompatibilityEvidenceReference
import io.redi.robot.adapter.compatibility.CompatibilityReportStatus
import io.redi.robot.capability.api.CapabilityCompatibility
import io.redi.robot.capability.api.CapabilityContractReference
import io.redi.robot.capability.api.CapabilityDescriptor
import io.redi.robot.capability.api.CapabilityDescriptorResult
import io.redi.robot.capability.api.CapabilityNumericLimit
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.provider.CapabilityNegotiationState
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleState
import io.redi.robot.connection.api.ConnectionLifecycleState
import io.redi.robot.connection.api.ConnectionSessionId
import io.redi.robot.manifest.domain.ManifestConnectionMethod
import io.redi.robot.manifest.resolution.ResolvedManifestDefinition
import io.redi.robot.provider.runtime.ProviderOrchestrationPhase

object CapabilityNegotiator {
    fun negotiate(input: CapabilityNegotiationInput): CapabilityNegotiationReport {
        val prepared = input.preparedProvider
        val resolved = prepared.manifestResolution?.resolved
        val preparation = prepared.preparation
        if (
            prepared.phase != ProviderOrchestrationPhase.PREPARED ||
            resolved == null ||
            preparation == null ||
            preparation.report.status !in setOf(
                CompatibilityReportStatus.COMPATIBLE,
                CompatibilityReportStatus.PARTIALLY_COMPATIBLE,
            )
        ) {
            return blocked(
                input,
                CapabilityNegotiationStatus.MISSING_PREREQUISITE,
                CapabilityNegotiationReason.REQUIRED_EVIDENCE_MISSING,
                null,
                check(
                    CapabilityNegotiationDimension.PREPARATION,
                    "prepared_provider",
                    CapabilityNegotiationCheckStatus.MISSING,
                    CapabilityNegotiationFinding.PROVIDER_NOT_PREPARED,
                ),
            )
        }

        val manifestEvidence = manifestEvidence(resolved)
        val adapterEvidence = CapabilityNegotiationEvidence(
            CapabilityNegotiationEvidenceSource.COMPATIBLE_ADAPTER,
            preparation.compatibleCandidate.record.descriptorReference,
        )
        val identityBinding = resolved.identityBinding
        if (identityBinding.instanceId == null) {
            return blocked(
                input,
                CapabilityNegotiationStatus.MISSING_PREREQUISITE,
                CapabilityNegotiationReason.REQUIRED_EVIDENCE_MISSING,
                resolved,
                check(
                    CapabilityNegotiationDimension.PROVIDER_IDENTITY,
                    "provider_instance",
                    CapabilityNegotiationCheckStatus.MISSING,
                    CapabilityNegotiationFinding.PROVIDER_IDENTITY_MISMATCH,
                    required = listOf("known_provider_instance"),
                    evidence = listOf(manifestEvidence),
                ),
            )
        }
        if (
            identityBinding.instanceId != input.providerIdentity.instanceId ||
            input.correlation.providerInstanceId != input.providerIdentity.instanceId ||
            resolved.common.manifest.provider.definitionId != input.providerIdentity.definitionId
        ) {
            return blocked(
                input,
                CapabilityNegotiationStatus.CONFLICTING_DECLARATION,
                CapabilityNegotiationReason.DECLARATIONS_CONFLICT,
                resolved,
                check(
                    CapabilityNegotiationDimension.PROVIDER_IDENTITY,
                    "provider_identity",
                    CapabilityNegotiationCheckStatus.CONFLICTING,
                    CapabilityNegotiationFinding.PROVIDER_IDENTITY_MISMATCH,
                    required = listOf(
                        identityBinding.instanceId.value,
                        resolved.common.manifest.provider.definitionId.value,
                    ),
                    observed = listOf(
                        input.providerIdentity.instanceId.value,
                        input.providerIdentity.definitionId.value,
                    ),
                    evidence = listOf(manifestEvidence),
                ),
            )
        }
        if (resolved.common.manifest.provider.family != input.providerIdentity.family) {
            return blocked(
                input,
                CapabilityNegotiationStatus.CONFLICTING_DECLARATION,
                CapabilityNegotiationReason.DECLARATIONS_CONFLICT,
                resolved,
                check(
                    CapabilityNegotiationDimension.PROVIDER_FAMILY,
                    "provider_family",
                    CapabilityNegotiationCheckStatus.CONFLICTING,
                    CapabilityNegotiationFinding.PROVIDER_FAMILY_MISMATCH,
                    required = listOf(resolved.common.manifest.provider.family.wireValue),
                    observed = listOf(input.providerIdentity.family.wireValue),
                    evidence = listOf(manifestEvidence),
                ),
            )
        }

        val checks = mutableListOf<CapabilityNegotiationCheck>()
        checks += check(
            CapabilityNegotiationDimension.PREPARATION,
            "prepared_provider",
            CapabilityNegotiationCheckStatus.MATCHED,
            CapabilityNegotiationFinding.MATCHED,
            required = listOf("PREPARED"),
            observed = listOf(prepared.phase.name, preparation.report.status.name),
            evidence = listOf(manifestEvidence, adapterEvidence),
        )
        checks += check(
            CapabilityNegotiationDimension.PROVIDER_IDENTITY,
            "provider_identity",
            CapabilityNegotiationCheckStatus.MATCHED,
            CapabilityNegotiationFinding.MATCHED,
            required = listOf(identityBinding.instanceId.value, identityBinding.definitionId.value),
            observed = listOf(input.providerIdentity.instanceId.value, input.providerIdentity.definitionId.value),
            evidence = listOf(manifestEvidence),
        )
        checks += check(
            CapabilityNegotiationDimension.PROVIDER_FAMILY,
            "provider_family",
            CapabilityNegotiationCheckStatus.MATCHED,
            CapabilityNegotiationFinding.MATCHED,
            required = listOf(resolved.common.manifest.provider.family.wireValue),
            observed = listOf(input.providerIdentity.family.wireValue),
            evidence = listOf(manifestEvidence, adapterEvidence),
        )

        validateRegistry(input, resolved, preparation.compatibleCandidate.record.key.version.wireValue)?.let {
            return blocked(input, it.status, it.reason, resolved, checks + it.check)
        } ?: run {
            val registered = input.providerRegistry.find(input.providerIdentity.instanceId)
            checks += check(
                CapabilityNegotiationDimension.PROVIDER_REGISTRY,
                "provider_registry",
                if (registered == null) CapabilityNegotiationCheckStatus.INFORMATIONAL
                else CapabilityNegotiationCheckStatus.MATCHED,
                if (registered == null) CapabilityNegotiationFinding.PROVIDER_NOT_REGISTERED
                else CapabilityNegotiationFinding.MATCHED,
                observed = listOf(if (registered == null) "not_registered" else "known"),
                evidence = listOf(registryEvidence(input)),
            )
        }

        if (input.providerLifecycle.latestGeneration != input.correlation.providerGeneration) {
            return blocked(
                input,
                CapabilityNegotiationStatus.STALE_SESSION,
                CapabilityNegotiationReason.STALE_PROVIDER_GENERATION,
                resolved,
                checks + check(
                    CapabilityNegotiationDimension.PROVIDER_LIFECYCLE,
                    "provider_generation",
                    CapabilityNegotiationCheckStatus.STALE,
                    CapabilityNegotiationFinding.STALE_PROVIDER_GENERATION,
                    required = listOf(input.correlation.providerGeneration.value.toString()),
                    observed = listOf(input.providerLifecycle.latestGeneration?.value?.toString() ?: "missing"),
                ),
            )
        }
        if (input.providerLifecycle.state !is ProviderLifecycleState.Negotiating) {
            return blocked(
                input,
                CapabilityNegotiationStatus.PROVIDER_NOT_READY,
                CapabilityNegotiationReason.PROVIDER_LIFECYCLE_NOT_READY,
                resolved,
                checks + check(
                    CapabilityNegotiationDimension.PROVIDER_LIFECYCLE,
                    "provider_lifecycle",
                    CapabilityNegotiationCheckStatus.FAILED,
                    CapabilityNegotiationFinding.PROVIDER_LIFECYCLE_NOT_READY,
                    required = listOf("NEGOTIATING"),
                    observed = listOf(input.providerLifecycle.state::class.simpleName ?: "unknown"),
                ),
            )
        }
        checks += check(
            CapabilityNegotiationDimension.PROVIDER_LIFECYCLE,
            "provider_lifecycle",
            CapabilityNegotiationCheckStatus.MATCHED,
            CapabilityNegotiationFinding.MATCHED,
            required = listOf("NEGOTIATING", input.correlation.providerGeneration.value.toString()),
            observed = listOf("NEGOTIATING", input.providerLifecycle.latestGeneration?.value.toString()),
        )

        validateRuntime(input)?.let {
            return blocked(input, it.status, it.reason, resolved, checks + it.check)
        }
        val runtime = input.runtimeEvidence
        val runtimeEvidence = CapabilityNegotiationEvidence(
            CapabilityNegotiationEvidenceSource.PROVIDER_RUNTIME,
            runtime.runtimeReference,
        )
        checks += check(
            CapabilityNegotiationDimension.PROVIDER_RUNTIME,
            "provider_runtime",
            if (runtime.runtime.availability == ProviderRuntimeAvailability.DEGRADED) {
                CapabilityNegotiationCheckStatus.INFORMATIONAL
            } else {
                CapabilityNegotiationCheckStatus.MATCHED
            },
            CapabilityNegotiationFinding.MATCHED,
            required = listOf("AVAILABLE_OR_DEGRADED"),
            observed = listOf(runtime.runtime.availability.name),
            evidence = listOf(runtimeEvidence),
        )

        val connectionValidation = validateConnections(input, resolved, manifestEvidence)
        checks += connectionValidation.checks
        if (connectionValidation.block != null) {
            val block = connectionValidation.block
            return blocked(input, block.status, block.reason, resolved, checks)
        }

        val adapter = preparation.compatibleCandidate.record.descriptor
        val manifestCapabilities = resolved.common.manifest.capabilities.sortedBy { it.wireValue }
        val manifestIds = manifestCapabilities.map { it.id }.toSet()
        val negotiated = mutableListOf<NegotiatedCapabilityRecord>()
        val capabilityFailures = mutableListOf<CapabilityFailure>()

        manifestCapabilities.forEach { claim ->
            checks += check(
                CapabilityNegotiationDimension.MANIFEST_DECLARATION,
                "manifest:${claim.wireValue}",
                CapabilityNegotiationCheckStatus.MATCHED,
                CapabilityNegotiationFinding.MATCHED,
                required = listOf(claim.wireValue),
                observed = listOf(claim.wireValue),
                evidence = listOf(manifestEvidence),
            )
            val outcome = negotiateCapability(input, claim, adapter.supportedCapabilities, manifestEvidence, adapterEvidence)
            checks += outcome.checks
            if (outcome.record != null) negotiated += outcome.record
            if (outcome.failure != null) capabilityFailures += outcome.failure
        }

        adapter.supportedCapabilities.filter { it.id !in manifestIds }.sortedBy { it.wireValue }.forEach { extra ->
            checks += check(
                CapabilityNegotiationDimension.MANIFEST_DECLARATION,
                "adapter_only:${extra.wireValue}",
                CapabilityNegotiationCheckStatus.EXCLUDED,
                CapabilityNegotiationFinding.NOT_DECLARED_BY_MANIFEST,
                required = manifestCapabilities.map { it.wireValue },
                observed = listOf(extra.wireValue),
                evidence = listOf(manifestEvidence, adapterEvidence),
            )
        }

        val degraded = runtime.runtime.availability == ProviderRuntimeAvailability.DEGRADED
        val (status, reason) = when {
            negotiated.size == manifestCapabilities.size && !degraded ->
                CapabilityNegotiationStatus.NEGOTIATED to
                    CapabilityNegotiationReason.ALL_DECLARED_CAPABILITIES_NEGOTIATED
            negotiated.isNotEmpty() -> CapabilityNegotiationStatus.PARTIALLY_NEGOTIATED to
                if (degraded && capabilityFailures.isEmpty()) CapabilityNegotiationReason.RUNTIME_DEGRADED
                else CapabilityNegotiationReason.SOME_DECLARED_CAPABILITIES_NEGOTIATED
            capabilityFailures.any { it == CapabilityFailure.CONFLICT } ->
                CapabilityNegotiationStatus.CONFLICTING_DECLARATION to
                    CapabilityNegotiationReason.DECLARATIONS_CONFLICT
            capabilityFailures.any { it == CapabilityFailure.VERSION } ->
                CapabilityNegotiationStatus.VERSION_MISMATCH to
                    CapabilityNegotiationReason.CAPABILITY_VERSION_INCOMPATIBLE
            else -> CapabilityNegotiationStatus.UNSUPPORTED to
                CapabilityNegotiationReason.NO_DECLARED_CAPABILITY_SUPPORTED
        }
        return report(input, status, reason, resolved, negotiated, checks)
    }

    fun cancelled(input: CapabilityNegotiationInput): CapabilityNegotiationReport = report(
        input,
        CapabilityNegotiationStatus.CANCELLED,
        CapabilityNegotiationReason.CANCELLED_BY_CALLER,
        input.preparedProvider.manifestResolution?.resolved,
        emptyList(),
        listOf(
            check(
                CapabilityNegotiationDimension.PREPARATION,
                "negotiation_cancelled",
                CapabilityNegotiationCheckStatus.EXCLUDED,
                CapabilityNegotiationFinding.CANCELLED,
            ),
        ),
    )

    fun dependencyFailed(input: CapabilityNegotiationInput): CapabilityNegotiationReport = report(
        input,
        CapabilityNegotiationStatus.FAILED,
        CapabilityNegotiationReason.DEPENDENCY_FAILURE,
        input.preparedProvider.manifestResolution?.resolved,
        emptyList(),
        listOf(
            check(
                CapabilityNegotiationDimension.PROVIDER_RUNTIME,
                "dependency_failure",
                CapabilityNegotiationCheckStatus.FAILED,
                CapabilityNegotiationFinding.DEPENDENCY_FAILURE,
            ),
        ),
    )

    private fun validateRegistry(
        input: CapabilityNegotiationInput,
        resolved: ResolvedManifestDefinition,
        adapterVersion: String,
    ): Block? {
        val registered = input.providerRegistry.find(input.providerIdentity.instanceId) ?: return null
        val record = registered.record
        val expectedManifest = resolved.specialized.manifest.common
        val conflict = record.identity != input.providerIdentity ||
            record.manifest.common != expectedManifest ||
            record.adapter.identity.id != input.preparedProvider.preparation?.compatibleCandidate?.record?.key?.adapterId ||
            record.adapter.identity.version.wireValue != adapterVersion
        if (conflict) {
            return Block(
                CapabilityNegotiationStatus.CONFLICTING_DECLARATION,
                CapabilityNegotiationReason.DECLARATIONS_CONFLICT,
                check(
                    CapabilityNegotiationDimension.PROVIDER_REGISTRY,
                    "provider_registry",
                    CapabilityNegotiationCheckStatus.CONFLICTING,
                    CapabilityNegotiationFinding.REGISTRY_CORRELATION_MISMATCH,
                    evidence = listOf(registryEvidence(input)),
                ),
            )
        }
        if (record.runtime.generation != input.correlation.providerGeneration) {
            return Block(
                CapabilityNegotiationStatus.STALE_SESSION,
                CapabilityNegotiationReason.STALE_PROVIDER_GENERATION,
                check(
                    CapabilityNegotiationDimension.PROVIDER_REGISTRY,
                    "provider_registry_generation",
                    CapabilityNegotiationCheckStatus.STALE,
                    CapabilityNegotiationFinding.STALE_PROVIDER_GENERATION,
                    required = listOf(input.correlation.providerGeneration.value.toString()),
                    observed = listOf(record.runtime.generation.value.toString()),
                    evidence = listOf(registryEvidence(input)),
                ),
            )
        }
        return null
    }

    private fun validateRuntime(input: CapabilityNegotiationInput): Block? {
        val runtime = input.runtimeEvidence
        val expectedInstance = input.correlation.providerInstanceId
        val expectedGeneration = input.correlation.providerGeneration
        if (
            runtime.runtime.generation != expectedGeneration ||
            runtime.negotiation.generation != expectedGeneration ||
            runtime.registrations.generation != expectedGeneration ||
            runtime.negotiation.providerInstanceId != expectedInstance ||
            runtime.registrations.providerInstanceId != expectedInstance
        ) {
            return Block(
                CapabilityNegotiationStatus.STALE_SESSION,
                CapabilityNegotiationReason.STALE_PROVIDER_GENERATION,
                check(
                    CapabilityNegotiationDimension.PROVIDER_RUNTIME,
                    "runtime_correlation",
                    CapabilityNegotiationCheckStatus.STALE,
                    CapabilityNegotiationFinding.STALE_PROVIDER_GENERATION,
                    required = listOf(expectedInstance.value, expectedGeneration.value.toString()),
                    observed = listOf(
                        runtime.negotiation.providerInstanceId.value,
                        runtime.runtime.generation.value.toString(),
                        runtime.negotiation.generation.value.toString(),
                        runtime.registrations.generation.value.toString(),
                    ),
                    evidence = runtimeEvidence(input),
                ),
            )
        }
        if (
            input.negotiatedAt.value < runtime.runtime.observedAt.value ||
            input.negotiatedAt.value >= runtime.expiresAt.value ||
            input.validUntil.value <= input.negotiatedAt.value
        ) {
            return Block(
                CapabilityNegotiationStatus.RUNTIME_UNAVAILABLE,
                CapabilityNegotiationReason.RUNTIME_EVIDENCE_EXPIRED,
                check(
                    CapabilityNegotiationDimension.PROVIDER_RUNTIME,
                    "runtime_freshness",
                    CapabilityNegotiationCheckStatus.STALE,
                    CapabilityNegotiationFinding.RUNTIME_EVIDENCE_EXPIRED,
                    required = listOf("observedAt<=negotiatedAt<expiresAt", "validUntil>negotiatedAt"),
                    observed = listOf(
                        runtime.runtime.observedAt.value.toString(),
                        input.negotiatedAt.value.toString(),
                        runtime.expiresAt.value.toString(),
                        input.validUntil.value.toString(),
                    ),
                    evidence = runtimeEvidence(input),
                ),
            )
        }
        if (runtime.runtime.availability == ProviderRuntimeAvailability.UNAVAILABLE) {
            val notReady = runtime.runtime.reason == io.redi.robot.capability.provider.ProviderAvailabilityReason.NOT_READY
            return Block(
                if (notReady) CapabilityNegotiationStatus.PROVIDER_NOT_READY
                else CapabilityNegotiationStatus.RUNTIME_UNAVAILABLE,
                if (notReady) CapabilityNegotiationReason.PROVIDER_LIFECYCLE_NOT_READY
                else CapabilityNegotiationReason.RUNTIME_EVIDENCE_UNAVAILABLE,
                check(
                    CapabilityNegotiationDimension.PROVIDER_RUNTIME,
                    "runtime_availability",
                    CapabilityNegotiationCheckStatus.FAILED,
                    CapabilityNegotiationFinding.RUNTIME_UNAVAILABLE,
                    required = listOf("AVAILABLE_OR_DEGRADED"),
                    observed = listOf(runtime.runtime.availability.name, runtime.runtime.reason?.name ?: "none"),
                    evidence = runtimeEvidence(input),
                ),
            )
        }
        return null
    }

    private fun validateConnections(
        input: CapabilityNegotiationInput,
        resolved: ResolvedManifestDefinition,
        manifestEvidence: CapabilityNegotiationEvidence,
    ): ConnectionValidation {
        val declared = resolved.common.manifest.connectionMethods
        val required = declared.filter(ManifestConnectionMethod::required)
        if (declared.isEmpty()) {
            return ConnectionValidation(
                listOf(
                    check(
                        CapabilityNegotiationDimension.CONNECTION_SESSION,
                        "connection_session",
                        CapabilityNegotiationCheckStatus.INFORMATIONAL,
                        CapabilityNegotiationFinding.CONNECTION_NOT_REQUIRED,
                        evidence = listOf(manifestEvidence),
                    ),
                ),
                emptyList(),
                null,
            )
        }
        if (required.isNotEmpty() && input.connections.isEmpty()) {
            return ConnectionValidation(
                listOf(
                    check(
                        CapabilityNegotiationDimension.CONNECTION_SESSION,
                        "connection_session",
                        CapabilityNegotiationCheckStatus.MISSING,
                        CapabilityNegotiationFinding.CONNECTION_EVIDENCE_MISSING,
                        required = required.map { it.reference.wireValue },
                        evidence = listOf(manifestEvidence),
                    ),
                ),
                emptyList(),
                Block(
                    CapabilityNegotiationStatus.MISSING_PREREQUISITE,
                    CapabilityNegotiationReason.REQUIRED_EVIDENCE_MISSING,
                    check(
                        CapabilityNegotiationDimension.CONNECTION_SESSION,
                        "connection_session",
                        CapabilityNegotiationCheckStatus.MISSING,
                        CapabilityNegotiationFinding.CONNECTION_EVIDENCE_MISSING,
                    ),
                ),
            )
        }

        val connected = input.connections.mapNotNull { evidence ->
            val state = evidence.lifecycle.state as? ConnectionLifecycleState.Connected ?: return@mapNotNull null
            Triple(evidence, state.session, state.session.metadata.method)
        }
        val actualSessionIds = connected.map { it.second.id }.distinct().sortedBy { it.value }
        val correlationMismatch = actualSessionIds != input.correlation.connectionSessionIds ||
            input.connections.any { it.providerGeneration != input.correlation.providerGeneration } ||
            connected.size != input.connections.size ||
            connected.any { it.second.metadata.observedAt.value > input.negotiatedAt.value }
        if (correlationMismatch) {
            return ConnectionValidation(
                listOf(
                    check(
                        CapabilityNegotiationDimension.CONNECTION_SESSION,
                        "connection_correlation",
                        CapabilityNegotiationCheckStatus.STALE,
                        CapabilityNegotiationFinding.CONNECTION_SESSION_STALE,
                        required = input.correlation.connectionSessionIds.map { it.value },
                        observed = actualSessionIds.map { it.value },
                        evidence = input.connections.map {
                            CapabilityNegotiationEvidence(
                                CapabilityNegotiationEvidenceSource.CONNECTION_SESSION,
                                it.evidenceReference,
                            )
                        },
                    ),
                ),
                actualSessionIds,
                Block(
                    CapabilityNegotiationStatus.STALE_SESSION,
                    CapabilityNegotiationReason.STALE_OR_WRONG_CONNECTION_SESSION,
                    check(
                        CapabilityNegotiationDimension.CONNECTION_SESSION,
                        "connection_correlation",
                        CapabilityNegotiationCheckStatus.STALE,
                        CapabilityNegotiationFinding.CONNECTION_SESSION_STALE,
                    ),
                ),
            )
        }

        val checks = declared.sortedBy { it.reference.wireValue }.map { declaration ->
            val matches = connected.filter { (_, _, method) ->
                method.id.value == declaration.reference.id.value &&
                    method.version.value == declaration.reference.version.wireValue
            }
            check(
                CapabilityNegotiationDimension.CONNECTION_SESSION,
                "connection:${declaration.reference.wireValue}",
                when {
                    matches.isNotEmpty() -> CapabilityNegotiationCheckStatus.MATCHED
                    declaration.required -> CapabilityNegotiationCheckStatus.MISSING
                    else -> CapabilityNegotiationCheckStatus.INFORMATIONAL
                },
                if (matches.isNotEmpty() || !declaration.required) CapabilityNegotiationFinding.MATCHED
                else CapabilityNegotiationFinding.CONNECTION_METHOD_MISMATCH,
                required = listOf(declaration.reference.wireValue),
                observed = connected.map { (_, _, method) -> method.wireValue() },
                evidence = listOf(manifestEvidence) + matches.map {
                    CapabilityNegotiationEvidence(
                        CapabilityNegotiationEvidenceSource.CONNECTION_SESSION,
                        it.first.evidenceReference,
                    )
                },
            )
        }
        val missingRequired = checks.any {
            it.status == CapabilityNegotiationCheckStatus.MISSING &&
                it.finding == CapabilityNegotiationFinding.CONNECTION_METHOD_MISMATCH
        }
        return ConnectionValidation(
            checks,
            actualSessionIds,
            if (missingRequired) {
                Block(
                    CapabilityNegotiationStatus.MISSING_PREREQUISITE,
                    CapabilityNegotiationReason.REQUIRED_EVIDENCE_MISSING,
                    checks.first { it.status == CapabilityNegotiationCheckStatus.MISSING },
                )
            } else {
                null
            },
        )
    }

    private fun negotiateCapability(
        input: CapabilityNegotiationInput,
        claim: CapabilityReference,
        adapterCapabilities: List<CapabilityReference>,
        manifestEvidence: CapabilityNegotiationEvidence,
        adapterEvidence: CapabilityNegotiationEvidence,
    ): CapabilityOutcome {
        val checks = mutableListOf<CapabilityNegotiationCheck>()
        val adapterSameId = adapterCapabilities.filter { it.id == claim.id }
        val adapterCompatible = adapterSameId.filter {
            it.compatibilityWith(claim) !is CapabilityCompatibility.Incompatible
        }
        if (adapterCompatible.isEmpty()) {
            val version = adapterSameId.isNotEmpty()
            checks += check(
                CapabilityNegotiationDimension.ADAPTER_SUPPORT,
                "adapter:${claim.wireValue}",
                CapabilityNegotiationCheckStatus.FAILED,
                if (version) CapabilityNegotiationFinding.CAPABILITY_VERSION_MISMATCH
                else CapabilityNegotiationFinding.ADAPTER_SUPPORT_MISSING,
                required = listOf(claim.wireValue),
                observed = adapterSameId.map { it.wireValue },
                evidence = listOf(manifestEvidence, adapterEvidence),
            )
            return CapabilityOutcome(null, checks, if (version) CapabilityFailure.VERSION else CapabilityFailure.UNSUPPORTED)
        }
        checks += check(
            CapabilityNegotiationDimension.ADAPTER_SUPPORT,
            "adapter:${claim.wireValue}",
            CapabilityNegotiationCheckStatus.MATCHED,
            CapabilityNegotiationFinding.MATCHED,
            required = listOf(claim.wireValue),
            observed = adapterCompatible.map { it.wireValue },
            evidence = listOf(adapterEvidence),
        )

        val runtimeEntry = input.runtimeEvidence.negotiation.entries.singleOrNull { it.requested == claim }
        if (runtimeEntry == null) {
            checks += check(
                CapabilityNegotiationDimension.PROVIDER_RUNTIME,
                "runtime:${claim.wireValue}",
                CapabilityNegotiationCheckStatus.CONFLICTING,
                CapabilityNegotiationFinding.DESCRIPTOR_CONFLICT,
                required = listOf(claim.wireValue),
                evidence = runtimeEvidence(input),
            )
            return CapabilityOutcome(null, checks, CapabilityFailure.CONFLICT)
        }
        if (runtimeEntry.state != CapabilityNegotiationState.SUPPORTED || runtimeEntry.offered == null) {
            val version = runtimeEntry.state == CapabilityNegotiationState.INCOMPATIBLE
            checks += check(
                CapabilityNegotiationDimension.CAPABILITY_VERSION,
                "runtime:${claim.wireValue}",
                CapabilityNegotiationCheckStatus.FAILED,
                if (version) CapabilityNegotiationFinding.CAPABILITY_VERSION_MISMATCH
                else CapabilityNegotiationFinding.CAPABILITY_UNSUPPORTED_BY_RUNTIME,
                required = listOf(claim.wireValue),
                observed = listOf(runtimeEntry.state.name),
                evidence = listOf(
                    CapabilityNegotiationEvidence(
                        CapabilityNegotiationEvidenceSource.PROVIDER_NEGOTIATION,
                        input.runtimeEvidence.negotiationReference,
                    ),
                ),
            )
            return CapabilityOutcome(null, checks, if (version) CapabilityFailure.VERSION else CapabilityFailure.UNSUPPORTED)
        }
        val offered = runtimeEntry.offered
        if (offered.compatibilityWith(claim) is CapabilityCompatibility.Incompatible) {
            checks += check(
                CapabilityNegotiationDimension.CAPABILITY_VERSION,
                "runtime:${claim.wireValue}",
                CapabilityNegotiationCheckStatus.FAILED,
                CapabilityNegotiationFinding.CAPABILITY_VERSION_MISMATCH,
                required = listOf(claim.wireValue),
                observed = listOf(offered.wireValue),
                evidence = runtimeEvidence(input),
            )
            return CapabilityOutcome(null, checks, CapabilityFailure.VERSION)
        }
        checks += check(
            CapabilityNegotiationDimension.CAPABILITY_VERSION,
            "runtime:${claim.wireValue}",
            CapabilityNegotiationCheckStatus.MATCHED,
            CapabilityNegotiationFinding.MATCHED,
            required = listOf(claim.wireValue),
            observed = listOf(offered.wireValue),
            evidence = runtimeEvidence(input),
        )

        val acceptedDescriptors = input.descriptorEvidence.filter { it.descriptor.reference.id == claim.id }
        if (acceptedDescriptors.size != 1) {
            checks += check(
                CapabilityNegotiationDimension.RUNTIME_DESCRIPTOR,
                "descriptor:${claim.wireValue}",
                if (acceptedDescriptors.isEmpty()) CapabilityNegotiationCheckStatus.MISSING
                else CapabilityNegotiationCheckStatus.CONFLICTING,
                if (acceptedDescriptors.isEmpty()) CapabilityNegotiationFinding.DESCRIPTOR_MISSING
                else CapabilityNegotiationFinding.DESCRIPTOR_CONFLICT,
                required = listOf(claim.wireValue),
                observed = acceptedDescriptors.map { it.descriptor.reference.wireValue },
                evidence = acceptedDescriptors.map {
                    CapabilityNegotiationEvidence(
                        CapabilityNegotiationEvidenceSource.PROVIDER_DESCRIPTOR,
                        it.evidenceReference,
                    )
                },
            )
            return CapabilityOutcome(null, checks, CapabilityFailure.CONFLICT)
        }
        val acceptedEvidence = acceptedDescriptors.single()
        if (acceptedEvidence.descriptor.reference.compatibilityWith(claim) is CapabilityCompatibility.Incompatible) {
            checks += check(
                CapabilityNegotiationDimension.RUNTIME_DESCRIPTOR,
                "descriptor:${claim.wireValue}",
                CapabilityNegotiationCheckStatus.FAILED,
                CapabilityNegotiationFinding.CAPABILITY_VERSION_MISMATCH,
                required = listOf(claim.wireValue),
                observed = listOf(acceptedEvidence.descriptor.reference.wireValue),
                evidence = listOf(descriptorEvidence(acceptedEvidence)),
            )
            return CapabilityOutcome(null, checks, CapabilityFailure.VERSION)
        }

        val runtimeDescriptors = input.runtimeEvidence.registrations.registrations
            .map { it.descriptor }
            .filter { it.reference.id == claim.id }
        if (runtimeDescriptors.size != 1 || runtimeDescriptors.single().reference != offered) {
            checks += check(
                CapabilityNegotiationDimension.RUNTIME_DESCRIPTOR,
                "runtime_descriptor:${claim.wireValue}",
                if (runtimeDescriptors.isEmpty()) CapabilityNegotiationCheckStatus.MISSING
                else CapabilityNegotiationCheckStatus.CONFLICTING,
                if (runtimeDescriptors.isEmpty()) CapabilityNegotiationFinding.DESCRIPTOR_MISSING
                else CapabilityNegotiationFinding.DESCRIPTOR_CONFLICT,
                required = listOf(offered.wireValue),
                observed = runtimeDescriptors.map { it.reference.wireValue },
                evidence = runtimeEvidence(input),
            )
            return CapabilityOutcome(null, checks, CapabilityFailure.CONFLICT)
        }
        val accepted = acceptedEvidence.descriptor
        val runtimeDescriptor = runtimeDescriptors.single()
        if (!sameDescriptorSemantics(accepted, runtimeDescriptor)) {
            checks += check(
                CapabilityNegotiationDimension.RUNTIME_DESCRIPTOR,
                "runtime_descriptor:${claim.wireValue}",
                CapabilityNegotiationCheckStatus.CONFLICTING,
                CapabilityNegotiationFinding.DESCRIPTOR_CONFLICT,
                required = listOf(accepted.reference.wireValue),
                observed = listOf(runtimeDescriptor.reference.wireValue),
                evidence = listOf(descriptorEvidence(acceptedEvidence)) + runtimeEvidence(input),
            )
            return CapabilityOutcome(null, checks, CapabilityFailure.CONFLICT)
        }
        if (
            !contractCompatible(runtimeDescriptor.inputContract, accepted.inputContract) ||
            !contractCompatible(runtimeDescriptor.outputContract, accepted.outputContract)
        ) {
            checks += check(
                CapabilityNegotiationDimension.CAPABILITY_CONTRACT,
                "contract:${claim.wireValue}",
                CapabilityNegotiationCheckStatus.FAILED,
                CapabilityNegotiationFinding.CONTRACT_MISMATCH,
                required = listOfNotNull(accepted.inputContract?.wireValue, accepted.outputContract?.wireValue),
                observed = listOfNotNull(
                    runtimeDescriptor.inputContract?.wireValue,
                    runtimeDescriptor.outputContract?.wireValue,
                ),
                evidence = listOf(descriptorEvidence(acceptedEvidence)) + runtimeEvidence(input),
            )
            return CapabilityOutcome(null, checks, CapabilityFailure.CONFLICT)
        }
        checks += check(
            CapabilityNegotiationDimension.CAPABILITY_CONTRACT,
            "contract:${claim.wireValue}",
            CapabilityNegotiationCheckStatus.MATCHED,
            CapabilityNegotiationFinding.MATCHED,
            required = listOfNotNull(accepted.inputContract?.wireValue, accepted.outputContract?.wireValue),
            observed = listOfNotNull(
                runtimeDescriptor.inputContract?.wireValue,
                runtimeDescriptor.outputContract?.wireValue,
            ),
            evidence = listOf(descriptorEvidence(acceptedEvidence)) + runtimeEvidence(input),
        )

        val limits = intersectLimits(accepted, runtimeDescriptor)
        if (limits is LimitIntersection.Rejected) {
            checks += check(
                CapabilityNegotiationDimension.RUNTIME_LIMIT,
                "limits:${claim.wireValue}",
                CapabilityNegotiationCheckStatus.CONFLICTING,
                limits.finding,
                required = accepted.limits.map(::limitWireValue),
                observed = runtimeDescriptor.limits.map(::limitWireValue),
                evidence = listOf(descriptorEvidence(acceptedEvidence)) + runtimeEvidence(input),
            )
            return CapabilityOutcome(null, checks, CapabilityFailure.CONFLICT)
        }
        limits as LimitIntersection.Accepted
        checks += check(
            CapabilityNegotiationDimension.RUNTIME_LIMIT,
            "limits:${claim.wireValue}",
            if (limits.narrowed) CapabilityNegotiationCheckStatus.NARROWED
            else CapabilityNegotiationCheckStatus.MATCHED,
            if (limits.narrowed) CapabilityNegotiationFinding.RUNTIME_LIMIT_NARROWED
            else CapabilityNegotiationFinding.MATCHED,
            required = accepted.limits.map(::limitWireValue),
            observed = limits.limits.map(::limitWireValue),
            evidence = listOf(descriptorEvidence(acceptedEvidence)) + runtimeEvidence(input),
        )

        val validUntilValue = minOf(input.validUntil.value, input.runtimeEvidence.expiresAt.value)
        val provenance = sortedEvidence(
            listOf(manifestEvidence, adapterEvidence, descriptorEvidence(acceptedEvidence)) +
                runtimeEvidence(input) + input.connections.map {
                    CapabilityNegotiationEvidence(
                        CapabilityNegotiationEvidenceSource.CONNECTION_SESSION,
                        it.evidenceReference,
                    )
                },
        )
        return CapabilityOutcome(
            NegotiatedCapabilityRecord(
                capability = claim,
                descriptor = accepted,
                providerInstanceId = input.correlation.providerInstanceId,
                providerGeneration = input.correlation.providerGeneration,
                negotiationId = input.correlation.negotiationId,
                connectionSessionIds = input.correlation.connectionSessionIds,
                inputContract = accepted.inputContract,
                outputContract = accepted.outputContract,
                runtimeAvailability = input.runtimeEvidence.runtime.availability,
                limits = limits.limits,
                negotiatedAt = input.negotiatedAt,
                validUntil = timestamp(validUntilValue, input),
                provenance = provenance,
            ),
            checks,
            null,
        )
    }

    private fun sameDescriptorSemantics(accepted: CapabilityDescriptor, runtime: CapabilityDescriptor): Boolean =
        accepted.effect == runtime.effect &&
            accepted.risk == runtime.risk &&
            accepted.safety == runtime.safety &&
            accepted.permissionIntent == runtime.permissionIntent &&
            accepted.approvalRequirement == runtime.approvalRequirement &&
            accepted.cancellation == runtime.cancellation &&
            accepted.availability == runtime.availability

    private fun contractCompatible(
        offered: CapabilityContractReference?,
        required: CapabilityContractReference?,
    ): Boolean = when {
        offered == null || required == null -> offered == required
        offered.id != required.id -> false
        offered.version.major != required.version.major -> false
        else -> offered.version.minor >= required.version.minor
    }

    private fun intersectLimits(
        accepted: CapabilityDescriptor,
        runtime: CapabilityDescriptor,
    ): LimitIntersection {
        val acceptedByKey = accepted.limits.associateBy { it.key }
        val runtimeByKey = runtime.limits.associateBy { it.key }
        if ((runtimeByKey.keys - acceptedByKey.keys).isNotEmpty()) {
            return LimitIntersection.Rejected(CapabilityNegotiationFinding.RUNTIME_LIMIT_CONFLICT)
        }
        var narrowed = false
        val result = accepted.limits.sortedBy { it.key.value }.map { base ->
            val observed = runtimeByKey[base.key] ?: return@map base
            if (observed.unit != base.unit) {
                return LimitIntersection.Rejected(CapabilityNegotiationFinding.RUNTIME_LIMIT_CONFLICT)
            }
            if (
                base.minimum != null && observed.minimum != null && observed.minimum < base.minimum ||
                base.maximum != null && observed.maximum != null && observed.maximum > base.maximum
            ) {
                return LimitIntersection.Rejected(CapabilityNegotiationFinding.RUNTIME_LIMIT_BROADENING)
            }
            val minimum = listOfNotNull(base.minimum, observed.minimum).maxOrNull()
            val maximum = listOfNotNull(base.maximum, observed.maximum).minOrNull()
            if (minimum != null && maximum != null && minimum > maximum) {
                return LimitIntersection.Rejected(CapabilityNegotiationFinding.RUNTIME_LIMIT_CONFLICT)
            }
            val intersection = when (
                val created = CapabilityNumericLimit.create(base.key, minimum, maximum, base.unit)
            ) {
                is CapabilityDescriptorResult.Success -> created.value
                is CapabilityDescriptorResult.Failure ->
                    return LimitIntersection.Rejected(CapabilityNegotiationFinding.RUNTIME_LIMIT_CONFLICT)
            }
            if (intersection != base) narrowed = true
            intersection
        }
        return LimitIntersection.Accepted(result, narrowed)
    }

    private fun blocked(
        input: CapabilityNegotiationInput,
        status: CapabilityNegotiationStatus,
        reason: CapabilityNegotiationReason,
        resolved: ResolvedManifestDefinition?,
        check: CapabilityNegotiationCheck,
    ): CapabilityNegotiationReport = blocked(input, status, reason, resolved, listOf(check))

    private fun blocked(
        input: CapabilityNegotiationInput,
        status: CapabilityNegotiationStatus,
        reason: CapabilityNegotiationReason,
        resolved: ResolvedManifestDefinition?,
        checks: List<CapabilityNegotiationCheck>,
    ): CapabilityNegotiationReport = report(input, status, reason, resolved, emptyList(), checks)

    private fun report(
        input: CapabilityNegotiationInput,
        status: CapabilityNegotiationStatus,
        reason: CapabilityNegotiationReason,
        resolved: ResolvedManifestDefinition?,
        negotiated: List<NegotiatedCapabilityRecord>,
        checks: List<CapabilityNegotiationCheck>,
    ) = CapabilityNegotiationReport(
        negotiationId = input.correlation.negotiationId,
        providerInstanceId = input.correlation.providerInstanceId,
        providerGeneration = input.correlation.providerGeneration,
        status = status,
        reason = reason,
        manifestCandidateId = resolved?.candidateId,
        negotiatedCapabilities = negotiated,
        checks = checks,
    )

    private fun check(
        dimension: CapabilityNegotiationDimension,
        id: String,
        status: CapabilityNegotiationCheckStatus,
        finding: CapabilityNegotiationFinding,
        required: List<String> = emptyList(),
        observed: List<String> = emptyList(),
        evidence: List<CapabilityNegotiationEvidence> = emptyList(),
    ) = CapabilityNegotiationCheck(
        dimension,
        id,
        status,
        finding,
        required.distinct().sorted(),
        observed.distinct().sorted(),
        sortedEvidence(evidence),
    )

    private fun manifestEvidence(resolved: ResolvedManifestDefinition) = CapabilityNegotiationEvidence(
        CapabilityNegotiationEvidenceSource.RESOLVED_MANIFEST,
        CompatibilityEvidenceReference.fromManifest(resolved),
    )

    private fun registryEvidence(input: CapabilityNegotiationInput) = CapabilityNegotiationEvidence(
        CapabilityNegotiationEvidenceSource.PROVIDER_REGISTRY,
        input.providerRegistryReference,
    )

    private fun descriptorEvidence(evidence: CapabilityDescriptorEvidence) = CapabilityNegotiationEvidence(
        CapabilityNegotiationEvidenceSource.PROVIDER_DESCRIPTOR,
        evidence.evidenceReference,
    )

    private fun runtimeEvidence(input: CapabilityNegotiationInput): List<CapabilityNegotiationEvidence> = listOf(
        CapabilityNegotiationEvidence(
            CapabilityNegotiationEvidenceSource.PROVIDER_RUNTIME,
            input.runtimeEvidence.runtimeReference,
        ),
        CapabilityNegotiationEvidence(
            CapabilityNegotiationEvidenceSource.PROVIDER_NEGOTIATION,
            input.runtimeEvidence.negotiationReference,
        ),
        CapabilityNegotiationEvidence(
            CapabilityNegotiationEvidenceSource.PROVIDER_DESCRIPTOR,
            input.runtimeEvidence.registrationReference,
        ),
    )

    private fun sortedEvidence(evidence: List<CapabilityNegotiationEvidence>): List<CapabilityNegotiationEvidence> =
        evidence.distinct().sortedWith(compareBy({ it.source.ordinal }, { it.reference.value }))

    private fun io.redi.robot.connection.api.ConnectionMethodReference.wireValue(): String =
        "${id.value}@${version.value}"

    private fun limitWireValue(limit: CapabilityNumericLimit): String =
        "${limit.key.value}:${limit.minimum ?: "-"}:${limit.maximum ?: "-"}:${limit.unit?.value ?: "-"}"

    private fun timestamp(value: Long, input: CapabilityNegotiationInput): io.redi.robot.capability.api.MonotonicTimestampMs =
        if (value == input.validUntil.value) input.validUntil else input.runtimeEvidence.expiresAt

    private data class Block(
        val status: CapabilityNegotiationStatus,
        val reason: CapabilityNegotiationReason,
        val check: CapabilityNegotiationCheck,
    )

    private data class ConnectionValidation(
        val checks: List<CapabilityNegotiationCheck>,
        val sessionIds: List<ConnectionSessionId>,
        val block: Block?,
    )

    private data class CapabilityOutcome(
        val record: NegotiatedCapabilityRecord?,
        val checks: List<CapabilityNegotiationCheck>,
        val failure: CapabilityFailure?,
    )

    private enum class CapabilityFailure { UNSUPPORTED, VERSION, CONFLICT }

    private sealed interface LimitIntersection {
        data class Accepted(
            val limits: List<CapabilityNumericLimit>,
            val narrowed: Boolean,
        ) : LimitIntersection

        data class Rejected(val finding: CapabilityNegotiationFinding) : LimitIntersection
    }
}
