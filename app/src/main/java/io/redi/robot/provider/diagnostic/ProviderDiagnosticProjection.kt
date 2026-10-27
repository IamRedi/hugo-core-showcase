package io.redi.robot.provider.diagnostic

import io.redi.robot.adapter.compatibility.CompatibilityReport
import io.redi.robot.adapter.registry.AdapterRegistryRecord
import io.redi.robot.capability.negotiation.CapabilityNegotiationReport
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.capability.provider.RedactedProviderIdentity
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleState
import io.redi.robot.connection.api.ConnectionAvailabilitySnapshot
import io.redi.robot.connection.api.ConnectionLifecycleSnapshot
import io.redi.robot.connection.api.ConnectionLifecycleState
import io.redi.robot.connection.api.ConnectionReadiness
import io.redi.robot.discovery.api.RedactedDiscoveryObservation
import io.redi.robot.discovery.identity.RedactedIdentityResolution
import io.redi.robot.manifest.resolution.RedactedManifestResolution
import io.redi.robot.provider.calibration.CalibrationAssessment
import io.redi.robot.provider.calibration.CalibrationNegotiationReadiness
import io.redi.robot.provider.runtime.ProviderOrchestrationSnapshot

/** Safe H021-H033 projections. Every source correlation becomes an export-only one-way label. */
object ProviderDiagnosticProjection {
    fun summary(
        exportReference: String,
        identity: RedactedProviderIdentity?,
        definitionId: io.redi.robot.capability.provider.ProviderDefinitionId? = identity?.definitionId,
        family: ProviderFamily? = identity?.family,
        alias: RedactedProviderAlias? = identity?.displayAlias,
    ): ProviderSummaryDiagnostic = ProviderSummaryDiagnostic(
        diagnosticId = ProviderDiagnosticId.derive("provider-export", exportReference),
        definitionId = definitionId,
        family = family,
        alias = alias?.takeIf(::safeAlias),
        identityKnown = identity != null,
    )

    fun lifecycle(snapshot: ProviderOrchestrationSnapshot): LifecycleDiagnostic = LifecycleDiagnostic(
        orchestrationPhase = snapshot.phase,
        stopReason = snapshot.stopReason,
        lifecycleState = lifecycleState(snapshot.providerLifecycle.state),
        generation = snapshot.providerLifecycle.latestGeneration?.value,
    )

    fun identity(value: RedactedIdentityResolution): IdentityDiagnostic = IdentityDiagnostic(
        status = value.status,
        reason = value.reason,
        candidateCount = value.candidates.size,
        evidenceReferences = value.consideredEvidenceIds.map {
            ProviderDiagnosticId.derive("identity-evidence", it.value)
        },
        freshness = when (value.status) {
            io.redi.robot.discovery.identity.IdentityResolutionStatus.STALE_EVIDENCE -> DiagnosticFreshness.STALE
            else -> DiagnosticFreshness.FRESH
        },
    )

    fun manifest(value: RedactedManifestResolution): ManifestDiagnostic = ManifestDiagnostic(
        status = value.status,
        reason = value.reason,
        candidateReferences = value.candidateIds.map {
            ProviderDiagnosticId.derive("manifest-candidate", it.value)
        },
    )

    fun compatibility(report: CompatibilityReport): CompatibilityDiagnostic = CompatibilityDiagnostic(
        status = report.status,
        checks = report.checks.map { check ->
            CompatibilityCheckDiagnostic(
                dimension = check.dimension,
                checkId = safeValue(check.checkId),
                status = check.status,
                finding = check.finding,
                requiredValues = check.requiredValues.map(::safeValue),
                observedValues = check.observedValues.map(::safeValue),
                evidenceReferences = check.evidence.map {
                    ProviderDiagnosticId.derive("compatibility-evidence", it.reference.value)
                },
            )
        },
    )

    fun adapter(record: AdapterRegistryRecord?): AdapterDiagnostic = if (record == null) {
        AdapterDiagnostic(false, null, null, null, null)
    } else {
        AdapterDiagnostic(
            known = true,
            knowledgeState = record.knowledgeState,
            adapterId = record.key.adapterId,
            adapterVersion = record.key.version,
            descriptorReference = ProviderDiagnosticId.derive(
                "adapter-descriptor",
                record.descriptorReference.value,
            ),
        )
    }

    fun connection(
        availability: ConnectionAvailabilitySnapshot?,
        lifecycle: ConnectionLifecycleSnapshot,
    ): ConnectionDiagnostic {
        val connected = lifecycle.state as? ConnectionLifecycleState.Connected
        val connecting = lifecycle.state as? ConnectionLifecycleState.Connecting
        val method = connected?.session?.metadata?.method ?: connecting?.request?.method ?: availability?.method
        return ConnectionDiagnostic(
            availability = availability?.availability,
            readiness = if (connected == null) ConnectionReadiness.NOT_CONNECTED else ConnectionReadiness.CHANNEL_ESTABLISHED,
            method = method,
            sessionReference = connected?.session?.id?.value?.let {
                ProviderDiagnosticId.derive("connection-session", it)
            },
            freshness = if (availability == null) DiagnosticFreshness.UNKNOWN else DiagnosticFreshness.FRESH,
        )
    }

    fun negotiation(report: CapabilityNegotiationReport, now: Long): NegotiatedCapabilitiesDiagnostic {
        val records = report.negotiatedCapabilities
        val freshness = when {
            records.isEmpty() -> DiagnosticFreshness.UNKNOWN
            records.all { now >= it.negotiatedAt.value && now < it.validUntil.value } -> DiagnosticFreshness.FRESH
            else -> DiagnosticFreshness.STALE
        }
        return NegotiatedCapabilitiesDiagnostic(
            status = report.status,
            reason = report.reason,
            capabilities = records.map { it.capability }.distinct(),
            negotiationReference = ProviderDiagnosticId.derive("negotiation", report.negotiationId.value),
            freshness = freshness,
        )
    }

    fun calibration(
        readiness: CalibrationNegotiationReadiness,
        assessment: CalibrationAssessment? = null,
    ): CalibrationReadinessDiagnostic = CalibrationReadinessDiagnostic(
        readiness = readiness.status,
        status = assessment?.status,
        profileReference = ProviderDiagnosticId.derive("calibration-profile", readiness.profileId.value),
        unmetItems = readiness.unmetItems,
    )

    fun discoveryEvidence(
        observations: List<RedactedDiscoveryObservation>,
        now: Long,
    ): List<ProviderDiagnosticEvidence> = observations.map { observation ->
        ProviderDiagnosticEvidence(
            reference = ProviderDiagnosticId.derive("discovery-observation", observation.observationId.value),
            source = DiagnosticEvidenceSource.DISCOVERY,
            provenance = safeValue("discovery.observation"),
            observedAt = observation.observedAt.value,
            validUntil = observation.expiresAt.value,
            freshness = if (now < observation.expiresAt.value) DiagnosticFreshness.FRESH else DiagnosticFreshness.STALE,
        )
    }

    private fun lifecycleState(state: ProviderLifecycleState): DiagnosticLifecycleState = when (state) {
        ProviderLifecycleState.Inactive -> DiagnosticLifecycleState.INACTIVE
        is ProviderLifecycleState.Discovered -> DiagnosticLifecycleState.DISCOVERED
        is ProviderLifecycleState.Identified -> DiagnosticLifecycleState.IDENTIFIED
        is ProviderLifecycleState.Connecting -> DiagnosticLifecycleState.CONNECTING
        is ProviderLifecycleState.Connected -> DiagnosticLifecycleState.CONNECTED
        is ProviderLifecycleState.Negotiating -> DiagnosticLifecycleState.NEGOTIATING
        is ProviderLifecycleState.Registered -> DiagnosticLifecycleState.REGISTERED
        is ProviderLifecycleState.Cancelling -> DiagnosticLifecycleState.CANCELLING
        is ProviderLifecycleState.Disconnecting -> DiagnosticLifecycleState.DISCONNECTING
        is ProviderLifecycleState.Cancelled -> DiagnosticLifecycleState.CANCELLED
        is ProviderLifecycleState.Disconnected -> DiagnosticLifecycleState.DISCONNECTED
        is ProviderLifecycleState.Failed -> DiagnosticLifecycleState.FAILED
    }

    private fun safeValue(raw: String): DiagnosticSafeValue = when (val parsed = DiagnosticSafeValue.parse(raw)) {
        is ProviderDiagnosticResult.Success -> parsed.value
        is ProviderDiagnosticResult.Failure -> checkNotNull(
            (DiagnosticSafeValue.parse("redacted") as ProviderDiagnosticResult.Success).value,
        )
    }

    private fun safeAlias(alias: RedactedProviderAlias): Boolean {
        val value = alias.value
        return !Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            .containsMatchIn(value) &&
            !Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}").containsMatchIn(value) &&
            !Regex("(?:^|[^0-9])(?:[0-9]{1,3}[.]){3}[0-9]{1,3}(?:$|[^0-9])").containsMatchIn(value) &&
            !value.contains('@')
    }
}
