package io.redi.robot.body.looi.firstlife

import io.redi.robot.capability.negotiation.CapabilityNegotiationReport
import io.redi.robot.capability.provider.redacted
import io.redi.robot.connection.api.ConnectionAvailabilitySnapshot
import io.redi.robot.connection.api.ConnectionLifecycleSnapshot
import io.redi.robot.provider.diagnostic.AdapterDiagnostic
import io.redi.robot.provider.diagnostic.CompatibilityDiagnostic
import io.redi.robot.provider.diagnostic.ConnectionDiagnostic
import io.redi.robot.provider.diagnostic.IdentityDiagnostic
import io.redi.robot.provider.diagnostic.LifecycleDiagnostic
import io.redi.robot.provider.diagnostic.ManifestDiagnostic
import io.redi.robot.provider.diagnostic.NegotiatedCapabilitiesDiagnostic
import io.redi.robot.provider.diagnostic.ProviderDiagnosticProjection
import io.redi.robot.provider.diagnostic.ProviderSummaryDiagnostic

/** H034-only projection; it contains redacted evidence and cannot mutate provider/runtime state. */
data class LooiFirstLifeDiagnostics(
    val summary: ProviderSummaryDiagnostic,
    val lifecycle: LifecycleDiagnostic,
    val identity: IdentityDiagnostic,
    val manifest: ManifestDiagnostic,
    val compatibility: CompatibilityDiagnostic,
    val adapter: AdapterDiagnostic,
    val connection: ConnectionDiagnostic,
    val negotiation: NegotiatedCapabilitiesDiagnostic?,
)

fun LooiFirstLifeProviderSession.diagnostics(
    connection: ConnectionLifecycleSnapshot,
    availability: ConnectionAvailabilitySnapshot?,
    negotiation: CapabilityNegotiationReport?,
    nowMs: Long,
): LooiFirstLifeDiagnostics {
    val identityResolution = checkNotNull(preparedProvider.identityResolution)
    val manifestResolution = checkNotNull(preparedProvider.manifestResolution)
    val compatibilityReport = checkNotNull(preparedProvider.preparation).report
    return LooiFirstLifeDiagnostics(
        summary = ProviderDiagnosticProjection.summary(
            "looi-reference-${preparedProvider.correlation?.generation?.value ?: 0}",
            identity.redacted(),
        ),
        lifecycle = ProviderDiagnosticProjection.lifecycle(preparedProvider),
        identity = ProviderDiagnosticProjection.identity(identityResolution.redacted()),
        manifest = ProviderDiagnosticProjection.manifest(manifestResolution.redacted()),
        compatibility = ProviderDiagnosticProjection.compatibility(compatibilityReport),
        adapter = ProviderDiagnosticProjection.adapter(LooiFirstLifeDefinition.adapterRecord),
        connection = ProviderDiagnosticProjection.connection(availability, connection),
        negotiation = negotiation?.let { ProviderDiagnosticProjection.negotiation(it, nowMs) },
    )
}
