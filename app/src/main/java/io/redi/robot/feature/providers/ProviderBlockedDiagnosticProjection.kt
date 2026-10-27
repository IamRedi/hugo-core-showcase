package io.redi.robot.feature.providers

import io.redi.robot.provider.diagnostic.BlockedStateDiagnostic
import io.redi.robot.provider.diagnostic.DiagnosticSafeValue
import io.redi.robot.provider.diagnostic.ProviderDiagnosticResult

/** H034 bridge: exports H033 codes verbatim without creating a second blocked-state taxonomy. */
fun ProviderBlockedUiState.toDiagnosticSection(): BlockedStateDiagnostic = BlockedStateDiagnostic(
    kind = diagnosticValue(kind.name),
    resolutionAvailability = diagnosticValue(resolutionAvailability.name),
)

private fun diagnosticValue(raw: String): DiagnosticSafeValue =
    (DiagnosticSafeValue.parse(raw) as ProviderDiagnosticResult.Success).value
