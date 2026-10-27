package io.redi.robot.firstlife.runtime

import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.negotiation.CapabilityNegotiationId
import io.redi.robot.capability.negotiation.CapabilityNegotiationStatus
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.connection.api.ConnectionReadiness
import io.redi.robot.context.provider.BodyAvailability
import io.redi.robot.firstlife.interaction.FirstLifePresentationIntent
import io.redi.robot.hugo.identity.HugoIdentity
import io.redi.robot.provider.calibration.CalibrationNegotiationReadinessStatus
import io.redi.robot.provider.diagnostic.BlockedStateDiagnostic
import io.redi.robot.provider.diagnostic.ProviderDiagnosticId
import io.redi.robot.provider.runtime.ProviderOrchestrationPhase
import io.redi.robot.trust.runtime.AuthorizationDecisionStatus
import java.util.Collections
import java.util.UUID

@JvmInline
value class FirstLifeRuntimeSessionId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun fromUuid(uuid: UUID): FirstLifeRuntimeSessionId = FirstLifeRuntimeSessionId(uuid.toString())
    }
}

@JvmInline
value class FirstLifeRuntimeSequence(val value: Long) {
    init { require(value > 0L) }
}

@JvmInline
value class FirstLifeRuntimeTimeMs(val value: Long) {
    init { require(value >= 0L) }
}

data class FirstLifeAuthorizationReadiness(
    val capability: CapabilityReference,
    val status: AuthorizationDecisionStatus,
)

/**
 * Redacted, presentation-safe First Life projection. It is evidence only: none of its booleans
 * grants trust, authorization, selection, safety, physical feasibility, or execution authority.
 */
class FirstLifeRuntimeSnapshot internal constructor(
    val hugoIdentity: HugoIdentity,
    val runtimeSessionId: FirstLifeRuntimeSessionId,
    val lastSequence: Long,
    val observedAt: FirstLifeRuntimeTimeMs,
    val providerPhase: ProviderOrchestrationPhase,
    val providerPrepared: Boolean,
    val providerRegistered: Boolean,
    val providerAlias: RedactedProviderAlias?,
    val providerFamily: ProviderFamily?,
    val providerGeneration: ProviderGeneration?,
    val runtimeAvailability: ProviderRuntimeAvailability?,
    val connectionReadiness: ConnectionReadiness,
    val negotiationId: CapabilityNegotiationId?,
    val negotiationStatus: CapabilityNegotiationStatus?,
    negotiatedCapabilities: List<CapabilityReference>,
    authorization: List<FirstLifeAuthorizationReadiness>,
    val explicitDemoConsent: Boolean,
    val calibrationReadiness: CalibrationNegotiationReadinessStatus?,
    val bodyAvailability: BodyAvailability,
    val activeBodySelected: Boolean,
    val activeBodySelectionExpiresAt: FirstLifeRuntimeTimeMs?,
    val bodyReady: Boolean,
    val actionAuthorizationReady: Boolean,
    val actionReady: Boolean,
    val blockedState: BlockedStateDiagnostic?,
    val diagnosticReference: ProviderDiagnosticId?,
) {
    val negotiatedCapabilities: List<CapabilityReference> = Collections.unmodifiableList(
        negotiatedCapabilities.distinct().sortedBy { it.wireValue },
    )
    val authorization: List<FirstLifeAuthorizationReadiness> = Collections.unmodifiableList(
        authorization.distinctBy { it.capability }.sortedBy { it.capability.wireValue },
    )

    val runtimeReady: Boolean get() = true
    val connected: Boolean get() = connectionReadiness == ConnectionReadiness.CHANNEL_ESTABLISHED
    val negotiated: Boolean get() = negotiationStatus in successfulNegotiationStatuses
    val authorized: Boolean get() = actionAuthorizationReady
    val safeToMove: Boolean get() = false
    val executesCapabilities: Boolean get() = false

    companion object {
        internal fun initial(
            identity: HugoIdentity,
            sessionId: FirstLifeRuntimeSessionId,
            startedAt: FirstLifeRuntimeTimeMs,
        ) = FirstLifeRuntimeSnapshot(
            identity, sessionId, 0L, startedAt, ProviderOrchestrationPhase.INACTIVE,
            providerPrepared = false, providerRegistered = false, providerAlias = null,
            providerFamily = null, providerGeneration = null, runtimeAvailability = null,
            connectionReadiness = ConnectionReadiness.NOT_CONNECTED, negotiationId = null,
            negotiationStatus = null, negotiatedCapabilities = emptyList(), authorization = emptyList(),
            explicitDemoConsent = false, calibrationReadiness = null,
            bodyAvailability = BodyAvailability.UNAVAILABLE, activeBodySelected = false,
            activeBodySelectionExpiresAt = null,
            bodyReady = false, actionAuthorizationReady = false, actionReady = false,
            blockedState = null, diagnosticReference = null,
        )
    }
}

sealed interface FirstLifeRuntimeInteractionSignal {
    data object RuntimeReady : FirstLifeRuntimeInteractionSignal
    data object BodyAvailable : FirstLifeRuntimeInteractionSignal
    data object BodyUnavailable : FirstLifeRuntimeInteractionSignal
    data object ProviderBlocked : FirstLifeRuntimeInteractionSignal
    data class CapabilityReady(val capability: CapabilityReference) : FirstLifeRuntimeInteractionSignal
    data object RuntimeLost : FirstLifeRuntimeInteractionSignal
}

enum class FirstLifeRuntimeDisposition {
    APPLIED,
    IGNORED_DUPLICATE,
    IGNORED_STALE_SEQUENCE,
    IGNORED_STALE_TIME,
    IGNORED_WRONG_SESSION,
    IGNORED_STALE_GENERATION,
    IGNORED_WRONG_CORRELATION,
    IGNORED_INVALID_STAGE,
}

class FirstLifeRuntimeUpdate(
    val snapshot: FirstLifeRuntimeSnapshot,
    interactionSignals: List<FirstLifeRuntimeInteractionSignal>,
    val presentationIntent: FirstLifePresentationIntent?,
    val disposition: FirstLifeRuntimeDisposition,
) {
    val interactionSignals: List<FirstLifeRuntimeInteractionSignal> = Collections.unmodifiableList(
        interactionSignals.toList(),
    )
}

internal val successfulNegotiationStatuses = setOf(
    CapabilityNegotiationStatus.NEGOTIATED,
    CapabilityNegotiationStatus.PARTIALLY_NEGOTIATED,
)
