package io.redi.robot.firstlife.runtime

import io.redi.robot.capability.negotiation.CapabilityNegotiationId
import io.redi.robot.capability.negotiation.CapabilityNegotiationReport
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.registry.ProviderRegistrySnapshot
import io.redi.robot.connection.api.ConnectionLifecycleSnapshot
import io.redi.robot.context.provider.ActiveBodyState
import io.redi.robot.provider.calibration.CalibrationNegotiationReadiness
import io.redi.robot.provider.diagnostic.BlockedStateDiagnostic
import io.redi.robot.provider.diagnostic.ProviderDiagnosticId
import io.redi.robot.provider.runtime.ProviderOrchestrationSnapshot
import io.redi.robot.trust.runtime.AuthorizationDecision

data class FirstLifeRuntimeEnvelope(
    val sessionId: FirstLifeRuntimeSessionId,
    val sequence: FirstLifeRuntimeSequence,
    val occurredAt: FirstLifeRuntimeTimeMs,
    val event: FirstLifeRuntimeEvent,
)

sealed interface FirstLifeRuntimeEvent {
    data class ProviderState(
        val orchestration: ProviderOrchestrationSnapshot,
        val registry: ProviderRegistrySnapshot,
        val diagnosticReference: ProviderDiagnosticId? = null,
        val blockedState: BlockedStateDiagnostic? = null,
    ) : FirstLifeRuntimeEvent

    data class ConnectionState(
        val providerGeneration: ProviderGeneration,
        val lifecycle: ConnectionLifecycleSnapshot,
    ) : FirstLifeRuntimeEvent

    data class NegotiationState(val report: CapabilityNegotiationReport) : FirstLifeRuntimeEvent

    data class ExplicitDemoConsent(
        val providerGeneration: ProviderGeneration,
        val negotiationId: CapabilityNegotiationId,
    ) : FirstLifeRuntimeEvent

    data class AuthorizationState(val decision: AuthorizationDecision) : FirstLifeRuntimeEvent

    data class CalibrationState(val readiness: CalibrationNegotiationReadiness) : FirstLifeRuntimeEvent

    data class BodyState(val state: ActiveBodyState) : FirstLifeRuntimeEvent

    /** Accepts only the canonical H033-to-H034 redacted projection, never raw failure detail. */
    data class Blocked(
        val providerGeneration: ProviderGeneration?,
        val state: BlockedStateDiagnostic,
        val diagnosticReference: ProviderDiagnosticId? = null,
    ) : FirstLifeRuntimeEvent

    data class NegotiationExpired(
        val providerGeneration: ProviderGeneration,
        val negotiationId: CapabilityNegotiationId,
    ) : FirstLifeRuntimeEvent

    data class AuthorizationLost(
        val providerGeneration: ProviderGeneration,
        val negotiationId: CapabilityNegotiationId,
    ) : FirstLifeRuntimeEvent

    data class ProviderLost(val providerGeneration: ProviderGeneration) : FirstLifeRuntimeEvent

    data class Reset(val nextSessionId: FirstLifeRuntimeSessionId) : FirstLifeRuntimeEvent
}
