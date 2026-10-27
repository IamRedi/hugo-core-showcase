package io.redi.robot.body.looi.firstlife

import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.negotiation.CapabilityNegotiationReport
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.connection.api.ConnectionLifecycleSnapshot
import io.redi.robot.context.provider.ActiveBodyEvent
import io.redi.robot.context.provider.ActiveBodyReducer
import io.redi.robot.context.provider.ActiveBodyActivationMode
import io.redi.robot.context.provider.ActiveBodyDisposition
import io.redi.robot.context.provider.ActiveBodySelection
import io.redi.robot.context.provider.ActiveBodySelectionReference
import io.redi.robot.context.provider.ActiveBodyState
import io.redi.robot.context.provider.BodyAvailability
import io.redi.robot.context.provider.BodyProviderReference
import io.redi.robot.context.provider.EmbodimentConfidence
import io.redi.robot.context.provider.EmbodimentEvidence
import io.redi.robot.context.provider.EmbodimentEvidenceId
import io.redi.robot.context.provider.EmbodimentEvidenceKind
import io.redi.robot.context.provider.EmbodimentEvidenceProvenance
import io.redi.robot.context.provider.EmbodimentGeneration
import io.redi.robot.context.provider.EmbodimentHost
import io.redi.robot.context.provider.EmbodimentHostReference
import io.redi.robot.context.provider.EmbodimentResult
import io.redi.robot.context.provider.MountingEvidencePolicy
import io.redi.robot.firstlife.runtime.FirstLifeRuntimeCoordinator
import io.redi.robot.firstlife.runtime.FirstLifeRuntimeEnvelope
import io.redi.robot.firstlife.runtime.FirstLifeRuntimeEvent
import io.redi.robot.firstlife.runtime.FirstLifeRuntimeSequence
import io.redi.robot.firstlife.runtime.FirstLifeRuntimeSessionId
import io.redi.robot.firstlife.runtime.FirstLifeRuntimeSnapshot
import io.redi.robot.firstlife.runtime.FirstLifeRuntimeTimeMs
import io.redi.robot.firstlife.runtime.FirstLifeOrientationAuthorityContext
import io.redi.robot.hugo.identity.HugoIdentity
import io.redi.robot.trust.domain.HugoHostRole
import java.util.UUID

enum class LooiActiveBodySelectionStatus {
    SELECTED,
    DEACTIVATED,
    EXPIRED,
    UNCHANGED,
    REJECTED_NOT_AVAILABLE,
}

data class LooiActiveBodySelectionResult(
    val status: LooiActiveBodySelectionStatus,
    val snapshot: FirstLifeRuntimeSnapshot,
    val grantsExecutionAuthority: Boolean = false,
)

/** Narrow FL005/FL008 evidence binding. It cannot connect, authorize, or execute. */
class LooiFirstLifeRuntimeBinding(identity: HugoIdentity, startedAtMs: Long) {
    private val sessionId = FirstLifeRuntimeSessionId.fromUuid(UUID.randomUUID())
    private var sequence = 0L
    private val coordinator = FirstLifeRuntimeCoordinator(
        identity,
        sessionId,
        FirstLifeRuntimeTimeMs(startedAtMs),
        LooiFirstLifeDefinition.capabilityReferences.toSet(),
    )
    private val host = EmbodimentHost(
        embodiment(EmbodimentHostReference.parse("android.first-life")),
        HugoHostRole.MOBILE_HOST,
    )
    private val bodyReducer = ActiveBodyReducer(embodiment(MountingEvidencePolicy.create(
        embodiment(EmbodimentConfidence.create(1000)),
    )))
    private var bodyState = ActiveBodyState.initial(host)
    private var currentBody: BodyProviderReference? = null

    fun snapshot(): FirstLifeRuntimeSnapshot = coordinator.snapshot()

    /** Explicit run-local mounting/alignment confirmation; availability or selection never calls it. */
    fun confirmOrientationMounting(occurredAtMs: Long): Boolean {
        val body = currentBody ?: return false
        val generation = bodyState.generation ?: return false
        val runtime = coordinator.snapshot()
        if (!runtime.bodyReady || !runtime.activeBodySelected) return false
        val now = timestamp(occurredAtMs)
        val mountingEvidence = embodiment(EmbodimentEvidence.create(
            embodiment(EmbodimentEvidenceId.parse("firstlife.orientation.mounting")),
            EmbodimentEvidenceKind.MOUNTING_VERIFIED,
            EmbodimentEvidenceProvenance.HARDWARE_VERIFIED,
            embodiment(EmbodimentConfidence.create(1000)),
            now,
            timestamp(occurredAtMs + ORIENTATION_MOUNTING_DURATION_MS),
        ))
        val reduction = bodyReducer.reduce(
            bodyState,
            ActiveBodyEvent.MountEvidence(generation, body, listOf(mountingEvidence), now),
        )
        if (reduction.disposition !is ActiveBodyDisposition.Applied) return false
        bodyState = reduction.state
        accept(FirstLifeRuntimeEvent.BodyState(bodyState), occurredAtMs)
        return bodyState.body(body)?.mounting == io.redi.robot.context.provider.MountingState.MOUNTED
    }

    fun orientationAuthorityContext(report: CapabilityNegotiationReport): FirstLifeOrientationAuthorityContext? {
        val body = currentBody ?: return null
        if (body.identity.instanceId != report.providerInstanceId) return null
        return FirstLifeOrientationAuthorityContext(body.identity, report, bodyState, host)
    }

    fun providerReady(
        provider: LooiFirstLifeProviderSession,
        connection: ConnectionLifecycleSnapshot,
        report: CapabilityNegotiationReport,
        occurredAtMs: Long,
    ) {
        val generation = embodiment(EmbodimentGeneration.create(report.providerGeneration.value))
        if (bodyState.generation != null && bodyState.generation != generation) {
            coordinator.snapshot().providerGeneration?.let { current ->
                accept(FirstLifeRuntimeEvent.ProviderLost(current), occurredAtMs)
            }
            bodyState = ActiveBodyState.initial(host)
            currentBody = null
        }
        accept(FirstLifeRuntimeEvent.ProviderState(provider.preparedProvider, provider.providerRegistrySnapshot), occurredAtMs)
        accept(FirstLifeRuntimeEvent.ConnectionState(report.providerGeneration, connection), occurredAtMs)
        accept(FirstLifeRuntimeEvent.NegotiationState(report), occurredAtMs)
        val body = embodiment(BodyProviderReference.create(provider.identity))
        currentBody = body
        bodyState = bodyReducer.reduce(
            bodyState,
            availableBody(body, report, generation, occurredAtMs),
        ).state
        accept(FirstLifeRuntimeEvent.BodyState(bodyState), occurredAtMs)
        check(!coordinator.snapshot().activeBodySelected)
    }

    fun providerLost(generation: ProviderGeneration, occurredAtMs: Long) {
        accept(FirstLifeRuntimeEvent.ProviderLost(generation), occurredAtMs)
        bodyState = ActiveBodyState.initial(host)
        currentBody = null
    }

    fun selectActiveBody(occurredAtMs: Long): LooiActiveBodySelectionResult {
        val runtime = coordinator.snapshot()
        val body = currentBody
        val generation = bodyState.generation
        if (!runtime.bodyReady || body == null || generation == null) {
            return LooiActiveBodySelectionResult(
                LooiActiveBodySelectionStatus.REJECTED_NOT_AVAILABLE,
                runtime,
            )
        }
        val selectedAt = timestamp(occurredAtMs)
        val selection = embodiment(ActiveBodySelection.create(
            body,
            ActiveBodyActivationMode.REMOTE,
            embodiment(ActiveBodySelectionReference.parse("firstlife.user.selection")),
            selectedAt,
            timestamp(occurredAtMs + ACTIVE_SELECTION_DURATION_MS),
        ))
        val reduction = bodyReducer.reduce(bodyState, ActiveBodyEvent.Select(generation, selection))
        if (reduction.disposition !is ActiveBodyDisposition.Applied) {
            return LooiActiveBodySelectionResult(
                LooiActiveBodySelectionStatus.REJECTED_NOT_AVAILABLE,
                runtime,
            )
        }
        bodyState = reduction.state
        accept(FirstLifeRuntimeEvent.BodyState(bodyState), occurredAtMs)
        return LooiActiveBodySelectionResult(LooiActiveBodySelectionStatus.SELECTED, coordinator.snapshot())
    }

    fun deactivateActiveBody(occurredAtMs: Long): LooiActiveBodySelectionResult {
        val generation = bodyState.generation ?: return LooiActiveBodySelectionResult(
            LooiActiveBodySelectionStatus.UNCHANGED,
            coordinator.snapshot(),
        )
        if (bodyState.activeSelection == null) return LooiActiveBodySelectionResult(
            LooiActiveBodySelectionStatus.UNCHANGED,
            coordinator.snapshot(),
        )
        bodyState = bodyReducer.reduce(bodyState, ActiveBodyEvent.Deactivate(generation)).state
        accept(FirstLifeRuntimeEvent.BodyState(bodyState), occurredAtMs)
        return LooiActiveBodySelectionResult(LooiActiveBodySelectionStatus.DEACTIVATED, coordinator.snapshot())
    }

    fun expireActiveBody(occurredAtMs: Long): LooiActiveBodySelectionResult {
        val generation = bodyState.generation
        val selection = bodyState.activeSelection
        if (generation == null || selection == null || selection.isActiveAt(timestamp(occurredAtMs))) {
            return LooiActiveBodySelectionResult(LooiActiveBodySelectionStatus.UNCHANGED, coordinator.snapshot())
        }
        bodyState = bodyReducer.reduce(
            bodyState,
            ActiveBodyEvent.Expire(generation, timestamp(occurredAtMs)),
        ).state
        accept(FirstLifeRuntimeEvent.BodyState(bodyState), occurredAtMs)
        return LooiActiveBodySelectionResult(LooiActiveBodySelectionStatus.EXPIRED, coordinator.snapshot())
    }

    private fun accept(event: FirstLifeRuntimeEvent, time: Long) {
        coordinator.accept(FirstLifeRuntimeEnvelope(
            sessionId,
            FirstLifeRuntimeSequence(++sequence),
            FirstLifeRuntimeTimeMs(time),
            event,
        ))
    }

    private fun availableBody(
        body: BodyProviderReference,
        report: CapabilityNegotiationReport,
        generation: EmbodimentGeneration,
        time: Long,
    ): ActiveBodyEvent.Observe {
        val now = timestamp(time)
        val remoteControlObserved = setOf("mobility.stop@1.0", "mobility.move@1.0").all { required ->
            report.negotiatedCapabilities.any { it.capability.wireValue == required }
        }
        val evidence = mutableListOf(
            evidence("looi.runtime.connection", EmbodimentEvidenceKind.TRANSPORT_CONNECTED, now, time),
        )
        if (remoteControlObserved) {
            evidence += evidence(
                "looi.runtime.remote-control",
                EmbodimentEvidenceKind.REMOTE_CONTROL_OBSERVED,
                now,
                time,
            )
        }
        return ActiveBodyEvent.Observe(
            generation, body, BodyAvailability.AVAILABLE,
            nearby = true, connected = true, remotelyControlled = remoteControlObserved,
            evidence = evidence, observedAt = now,
        )
    }

    private fun evidence(
        id: String,
        kind: EmbodimentEvidenceKind,
        now: MonotonicTimestampMs,
        time: Long,
    ): EmbodimentEvidence = embodiment(EmbodimentEvidence.create(
        embodiment(EmbodimentEvidenceId.parse(id)),
        kind,
        EmbodimentEvidenceProvenance.RUNTIME_OBSERVED,
        embodiment(EmbodimentConfidence.create(1000)),
        now,
        timestamp(time + BODY_EVIDENCE_DURATION_MS),
    ))

    private companion object {
        const val ACTIVE_SELECTION_DURATION_MS = 60_000L
        const val BODY_EVIDENCE_DURATION_MS = 60_000L
        const val ORIENTATION_MOUNTING_DURATION_MS = 60_000L
    }
}

private fun timestamp(value: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(value)) {
    is CapabilityEnvelopeResult.Success -> result.value
    is CapabilityEnvelopeResult.Failure -> error(result.error)
}
private fun <T> embodiment(result: EmbodimentResult<T>): T = (result as EmbodimentResult.Success).value
