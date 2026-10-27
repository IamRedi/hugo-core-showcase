package io.redi.robot.firstlife.runtime

import io.redi.robot.adapter.api.AdapterId
import io.redi.robot.adapter.api.AdapterIdentity
import io.redi.robot.adapter.api.AdapterPackageDigest
import io.redi.robot.adapter.api.AdapterResult
import io.redi.robot.adapter.api.AdapterVersion
import io.redi.robot.capability.api.*
import io.redi.robot.capability.negotiation.*
import io.redi.robot.capability.provider.*
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleSnapshot
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleState
import io.redi.robot.capability.registry.*
import io.redi.robot.connection.api.*
import io.redi.robot.context.provider.*
import io.redi.robot.discovery.identity.*
import io.redi.robot.feature.providers.ProviderBlockedEvidence
import io.redi.robot.feature.providers.ProviderBlockedMappingResult
import io.redi.robot.feature.providers.ProviderBlockedStateMapper
import io.redi.robot.feature.providers.toDiagnosticSection
import io.redi.robot.firstlife.interaction.FirstLifePresentationIntent
import io.redi.robot.hugo.identity.HugoIdentity
import io.redi.robot.hugo.identity.HugoIdentityId
import io.redi.robot.manifest.domain.*
import io.redi.robot.provider.calibration.*
import io.redi.robot.provider.diagnostic.ProviderDiagnosticId
import io.redi.robot.provider.runtime.*
import io.redi.robot.trust.runtime.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class FirstLifeRuntimeCoordinatorTest {
    private val identity = HugoIdentity(HugoIdentityId.fromUuid(uuid(900)))
    private val session = FirstLifeRuntimeSessionId.fromUuid(uuid(901))
    private val provider = providerIdentity(ProviderFamily.BODY_ADAPTER)
    private val generation = generation(7)
    private val capabilities = listOf(
        capability("mobility.stop@1.0"),
        capability("mobility.move@1.0"),
        capability("body.head.position.control@1.0"),
        capability("body.light.control@1.0"),
        capability("provider.connection.status@1.0"),
    )

    @Test
    fun `happy fake BODY flow preserves every readiness boundary and deterministic output`() {
        val coordinator = coordinator()
        assertEquals(listOf(FirstLifeRuntimeInteractionSignal.RuntimeReady), coordinator.initialUpdate().interactionSignals)
        assertFalse(coordinator.snapshot().providerPrepared)
        assertTrue(coordinator.snapshot().runtimeReady)

        accept(coordinator, 1, FirstLifeRuntimeEvent.ProviderState(observed(), ProviderRegistrySnapshot.empty()))
        assertEquals(ProviderOrchestrationPhase.OBSERVED, coordinator.snapshot().providerPhase)
        assertFalse(coordinator.snapshot().providerPrepared)
        val providerRegistry = registry()
        val registryBefore = providerRegistry.entries.toList()
        accept(coordinator, 2, FirstLifeRuntimeEvent.ProviderState(prepared(), providerRegistry))
        assertEquals(registryBefore, providerRegistry.entries)
        val prepared = coordinator.snapshot()
        assertTrue(prepared.providerPrepared)
        assertTrue(prepared.providerRegistered)
        assertFalse(prepared.connected)
        assertFalse(prepared.negotiated)
        assertFalse(prepared.authorized)
        assertFalse(prepared.activeBodySelected)
        assertFalse(prepared.safeToMove)

        accept(coordinator, 3, FirstLifeRuntimeEvent.ConnectionState(generation, connection()))
        val negotiationUpdate = accept(coordinator, 4, FirstLifeRuntimeEvent.NegotiationState(negotiation()))
        assertEquals(capabilities.map { it.wireValue }.sorted(), coordinator.snapshot().negotiatedCapabilities.map { it.wireValue })
        assertEquals(
            capabilities.sortedBy { it.wireValue }.map { FirstLifeRuntimeInteractionSignal.CapabilityReady(it) },
            negotiationUpdate.interactionSignals,
        )
        accept(coordinator, 5, FirstLifeRuntimeEvent.ExplicitDemoConsent(generation, negotiationId()))
        capabilities.forEachIndexed { index, capability ->
            accept(coordinator, 6L + index, FirstLifeRuntimeEvent.AuthorizationState(decision(capability)))
        }
        accept(coordinator, 11, FirstLifeRuntimeEvent.CalibrationState(calibration()))
        val ready = accept(coordinator, 12, FirstLifeRuntimeEvent.BodyState(bodyState(selected = true)))

        assertEquals(listOf(FirstLifeRuntimeInteractionSignal.BodyAvailable), ready.interactionSignals)
        assertEquals(FirstLifePresentationIntent.BODY_READY, ready.presentationIntent)
        assertTrue(ready.snapshot.bodyReady)
        assertTrue(ready.snapshot.actionAuthorizationReady)
        assertTrue(ready.snapshot.activeBodySelected)
        assertTrue(ready.snapshot.actionReady)
        assertFalse(ready.snapshot.safeToMove)
        assertFalse(ready.snapshot.executesCapabilities)
        assertSame(identity, ready.snapshot.hugoIdentity)
    }

    @Test
    fun `missing adapter unavailable negotiation and authorization scenarios remain honestly blocked or not ready`() {
        val missing = coordinator()
        val blocked = canonicalBlocked(ProviderBlockedEvidence.Compatibility(
            io.redi.robot.adapter.compatibility.CompatibilityReportStatus.MISSING_ADAPTER,
        ))
        val blockedUpdate = accept(
            missing,
            1,
            FirstLifeRuntimeEvent.Blocked(null, blocked, ProviderDiagnosticId.derive("fl005", "missing-adapter")),
        )
        assertEquals(FirstLifePresentationIntent.BLOCKED, blockedUpdate.presentationIntent)
        assertEquals("ADAPTER_MISSING", blockedUpdate.snapshot.blockedState?.kind?.value)
        assertEquals(listOf(FirstLifeRuntimeInteractionSignal.ProviderBlocked), blockedUpdate.interactionSignals)

        val unavailable = coordinator()
        accept(unavailable, 1, FirstLifeRuntimeEvent.ProviderState(prepared(), registry(ProviderRuntimeAvailability.UNAVAILABLE)))
        assertFalse(unavailable.snapshot().bodyReady)

        val incomplete = coordinator()
        advanceConnected(incomplete)
        accept(incomplete, 3, FirstLifeRuntimeEvent.NegotiationState(negotiation(CapabilityNegotiationStatus.UNSUPPORTED)))
        assertTrue(incomplete.snapshot().negotiatedCapabilities.isEmpty())
        assertFalse(incomplete.snapshot().bodyReady)

        val unauthorized = coordinator()
        advanceNegotiated(unauthorized)
        accept(unauthorized, 4, FirstLifeRuntimeEvent.BodyState(bodyState(selected = true)))
        assertTrue(unauthorized.snapshot().bodyReady)
        assertFalse(unauthorized.snapshot().actionAuthorizationReady)
        assertFalse(unauthorized.snapshot().actionReady)
    }

    @Test
    fun `stale wrong duplicate expiry loss revocation and reset fail closed without changing HUGO`() {
        val coordinator = coordinator()
        advanceNegotiated(coordinator)
        val before = coordinator.snapshot()
        val stale = acceptWith(
            coordinator,
            session,
            4,
            FirstLifeRuntimeEvent.ProviderLost(generation(6)),
        )
        assertEquals(FirstLifeRuntimeDisposition.IGNORED_STALE_GENERATION, stale.disposition)
        assertSame(before.hugoIdentity, stale.snapshot.hugoIdentity)

        val duplicate = accept(coordinator, 4, FirstLifeRuntimeEvent.BodyState(bodyState(false)))
        assertEquals(FirstLifeRuntimeDisposition.APPLIED, duplicate.disposition)
        val replay = accept(coordinator, 4, FirstLifeRuntimeEvent.BodyState(bodyState(true)))
        assertEquals(FirstLifeRuntimeDisposition.IGNORED_DUPLICATE, replay.disposition)
        val wrong = acceptWith(
            coordinator,
            FirstLifeRuntimeSessionId.fromUuid(uuid(999)),
            5,
            FirstLifeRuntimeEvent.ProviderLost(generation),
        )
        assertEquals(FirstLifeRuntimeDisposition.IGNORED_WRONG_SESSION, wrong.disposition)

        val expired = accept(coordinator, 5, FirstLifeRuntimeEvent.NegotiationExpired(generation, negotiationId()))
        assertFalse(expired.snapshot.negotiated)
        assertFalse(expired.snapshot.actionAuthorizationReady)

        accept(coordinator, 6, FirstLifeRuntimeEvent.NegotiationState(negotiation()))
        accept(coordinator, 7, FirstLifeRuntimeEvent.ExplicitDemoConsent(generation, negotiationId()))
        capabilities.forEachIndexed { index, capability ->
            accept(coordinator, 8L + index, FirstLifeRuntimeEvent.AuthorizationState(decision(capability)))
        }
        assertTrue(coordinator.snapshot().actionAuthorizationReady)
        val revoked = accept(coordinator, 13, FirstLifeRuntimeEvent.AuthorizationLost(generation, negotiationId()))
        assertFalse(revoked.snapshot.actionAuthorizationReady)

        val lost = accept(coordinator, 14, FirstLifeRuntimeEvent.ProviderLost(generation))
        assertFalse(lost.snapshot.providerPrepared)
        assertEquals(
            listOf(
                FirstLifeRuntimeInteractionSignal.BodyUnavailable,
                FirstLifeRuntimeInteractionSignal.RuntimeLost,
            ),
            lost.interactionSignals,
        )
        assertSame(identity, lost.snapshot.hugoIdentity)

        val next = FirstLifeRuntimeSessionId.fromUuid(uuid(902))
        val reset = accept(coordinator, 15, FirstLifeRuntimeEvent.Reset(next))
        assertEquals(next, reset.snapshot.runtimeSessionId)
        assertEquals(0, reset.snapshot.lastSequence)
        assertSame(identity, reset.snapshot.hugoIdentity)
        assertFalse(reset.snapshot.providerPrepared)
        assertFalse(reset.snapshot.explicitDemoConsent)
    }

    @Test
    fun `DEVICE and SERVICE fit the same provider neutral harness without embodiment inheritance`() {
        listOf(ProviderFamily.DEVICE_ADAPTER, ProviderFamily.SERVICE_CONNECTOR).forEach { family ->
            val alternate = providerIdentity(family)
            val coordinator = coordinator()
            val result = accept(
                coordinator,
                1,
                FirstLifeRuntimeEvent.ProviderState(prepared(alternate), registry(identity = alternate)),
            )
            assertEquals(family, result.snapshot.providerFamily)
            assertTrue(result.snapshot.providerPrepared)
            assertEquals(BodyAvailability.UNAVAILABLE, result.snapshot.bodyAvailability)
        }
    }

    @Test
    fun `FL004 and FL003 outputs contain semantics only and snapshot has no private provider fields`() {
        val names = FirstLifeRuntimeSnapshot::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(names.any { it.contains("instanceid") || it.contains("endpoint") || it.contains("adapter") })
        val signalFields = FirstLifeRuntimeInteractionSignal::class.java.declaredClasses
            .flatMap { it.declaredFields.toList() }.map { it.type.name }
        assertFalse(signalFields.any { it.contains("registry") || it.contains("manifest") || it.contains("connection") })
        assertEquals(FirstLifePresentationIntent::class.java, coordinator().initialUpdate().presentationIntent!!::class.java)

        val runtimeSources = File("src/main/java/io/redi/robot/firstlife/runtime").walkTopDown()
            .filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        listOf("body.looi", "android.bluetooth", "BluetoothGatt", "FED", "transport address").forEach {
            assertFalse("forbidden runtime coupling: $it", runtimeSources.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `session approval consumption is atomic single use and resettable without persistent authority`() {
        val store = SessionApprovalConsumption()
        val attempt = consumptionAttempt()
        assertTrue(store.consumeOnce(attempt) is ApprovalConsumptionResult.Consumed)
        assertTrue(store.consumeOnce(attempt) is ApprovalConsumptionResult.AlreadyConsumed)
        assertEquals(1, store.consumedCount())
        val conflict = attempt.copy(requestId = requestId(777))
        assertEquals(ApprovalConsumptionResult.BindingConflict, store.consumeOnce(conflict))
        store.clearForRuntimeReset()
        assertEquals(0, store.consumedCount())
        assertTrue(store.consumeOnce(attempt) is ApprovalConsumptionResult.Consumed)
    }

    @Test
    fun `concurrent approval attempts consume exactly once and all replays remain non-authoritative`() {
        val store = SessionApprovalConsumption()
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<ApprovalConsumptionResult>())
        val executor = Executors.newFixedThreadPool(8)
        repeat(24) {
            executor.submit {
                start.await()
                results += store.consumeOnce(consumptionAttempt())
            }
        }
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, results.count { it is ApprovalConsumptionResult.Consumed })
        assertEquals(23, results.count { it is ApprovalConsumptionResult.AlreadyConsumed })
        assertEquals(1, store.consumedCount())
    }

    private fun coordinator() = FirstLifeRuntimeCoordinator(
        identity,
        session,
        time(0),
        capabilities.toSet(),
        capabilities.dropLast(1).toSet(),
    )

    private fun advanceConnected(value: FirstLifeRuntimeCoordinator) {
        accept(value, 1, FirstLifeRuntimeEvent.ProviderState(prepared(), registry()))
        accept(value, 2, FirstLifeRuntimeEvent.ConnectionState(generation, connection()))
    }

    private fun advanceNegotiated(value: FirstLifeRuntimeCoordinator) {
        advanceConnected(value)
        accept(value, 3, FirstLifeRuntimeEvent.NegotiationState(negotiation()))
    }

    private fun accept(coordinator: FirstLifeRuntimeCoordinator, sequence: Long, event: FirstLifeRuntimeEvent) =
        acceptWith(coordinator, session, sequence, event)

    private fun acceptWith(
        coordinator: FirstLifeRuntimeCoordinator,
        sessionId: FirstLifeRuntimeSessionId,
        sequence: Long,
        event: FirstLifeRuntimeEvent,
    ) = coordinator.accept(FirstLifeRuntimeEnvelope(sessionId, FirstLifeRuntimeSequence(sequence), time(sequence), event))

    private fun prepared(identity: ProviderIdentity = provider) = ProviderOrchestrationSnapshot(
        ProviderOrchestrationCorrelation(ProviderOrchestrationRunId.fromUuid(uuid(1)), generation),
        ProviderOrchestrationPhase.PREPARED,
        null,
        emptyList(),
        ProviderLifecycleSnapshot.transitioned(ProviderLifecycleState.Identified(generation)),
        IdentityResolution(
            IdentityResolutionStatus.MATCHED_KNOWN_PROVIDER,
            IdentityResolutionReason.STRONG_VERIFIED_INSTANCE_EVIDENCE,
            emptySet(),
            listOf(KnownProviderIdentityCandidate(identity, emptySet())),
            emptySet(),
        ),
        null, null, null, null, emptyList(),
    )

    private fun observed() = ProviderOrchestrationSnapshot(
        ProviderOrchestrationCorrelation(ProviderOrchestrationRunId.fromUuid(uuid(1)), generation),
        ProviderOrchestrationPhase.OBSERVED,
        null,
        emptyList(),
        ProviderLifecycleSnapshot.transitioned(ProviderLifecycleState.Discovered(generation)),
        null, null, null, null, null, emptyList(),
    )

    private fun registry(
        availability: ProviderRuntimeAvailability = ProviderRuntimeAvailability.AVAILABLE,
        identity: ProviderIdentity = provider,
    ): ProviderRegistrySnapshot {
        val registry = InMemoryProviderRegistry()
        val record = success<ProviderRegistryRecord>(ProviderRegistryRecord.create(
            identity,
            ProviderManifestReference(
                CommonManifestBinding(identity.definitionId, success(ManifestDigest.parse("sha256:" + "b".repeat(64)))),
                when (identity.family) {
                    ProviderFamily.BODY_ADAPTER -> SpecializedManifestKind.BODY
                    ProviderFamily.DEVICE_ADAPTER -> SpecializedManifestKind.DEVICE
                    ProviderFamily.SERVICE_CONNECTOR -> SpecializedManifestKind.SERVICE
                },
            ),
            ProviderAdapterReference(
                AdapterIdentity(success(AdapterId.parse("redi.robot/fake-adapter")), success(AdapterVersion.parse("1.0.0"))),
                success(AdapterPackageDigest.parse("sha256:" + "a".repeat(64))),
            ),
            capabilities.map(::descriptor),
            success(ProviderRegistryRuntimeSnapshot.create(
                generation,
                ProviderLifecycleState.Registered(generation),
                success(ProviderRuntimeSnapshot.create(
                    generation, availability, timestamp(1),
                    if (availability == ProviderRuntimeAvailability.AVAILABLE) null else ProviderAvailabilityReason.NOT_READY,
                )),
            )),
        ))
        success<RegisteredProviderSnapshot>(registry.register(record))
        return registry.snapshot()
    }

    private fun connection(): ConnectionLifecycleSnapshot {
        val connectionGeneration = success<ConnectionGeneration>(ConnectionGeneration.create(generation.value))
        val method = ConnectionMethodReference(
            success(ConnectionMethodId.parse("local.fake/channel")),
            success(ConnectionMethodVersion.parse("1.0")),
        )
        val request = success<ConnectionRequestId>(ConnectionRequestId.parse(uuid(21).toString()))
        val sessionId = success<ConnectionSessionId>(ConnectionSessionId.parse(uuid(22).toString()))
        val metadata = ConnectionMetadata(
            method,
            success(ConnectionEndpointReference.parse("opaque.fake/body")),
            success(ConnectionPeerAlias.parse("Fake body")),
            timestamp(2),
        )
        return ConnectionLifecycleSnapshot(
            ConnectionLifecycleState.Connected(ConnectionSession(sessionId, connectionGeneration, request, metadata)),
            connectionGeneration,
        )
    }

    private fun negotiation(status: CapabilityNegotiationStatus = CapabilityNegotiationStatus.NEGOTIATED): CapabilityNegotiationReport {
        val connectionId = (connection().state as ConnectionLifecycleState.Connected).session.id
        val records = if (status in successfulNegotiationStatuses) capabilities.map { capability ->
            NegotiatedCapabilityRecord(
                capability, descriptor(capability), provider.instanceId, generation, negotiationId(), listOf(connectionId),
                null, null, ProviderRuntimeAvailability.AVAILABLE, emptyList(), timestamp(2), timestamp(1000), emptyList(),
            )
        } else emptyList()
        return CapabilityNegotiationReport(
            negotiationId(), provider.instanceId, generation, status,
            if (status == CapabilityNegotiationStatus.NEGOTIATED) CapabilityNegotiationReason.ALL_DECLARED_CAPABILITIES_NEGOTIATED
            else CapabilityNegotiationReason.NO_DECLARED_CAPABILITY_SUPPORTED,
            null, records, emptyList(),
        )
    }

    private fun decision(capability: CapabilityReference): AuthorizationDecision {
        val audit = AuthorizationAuditRecord(
            success(AuthorizationDecisionId.parse(uuid(30 + capabilities.indexOf(capability)).toString())),
            requestId(30 + capabilities.indexOf(capability)),
            success(CapabilityAuditId.parse(uuid(50 + capabilities.indexOf(capability)).toString())),
            CapabilityActorKind.LOCAL_USER,
            provider.instanceId,
            capability,
            generation,
            negotiationId(),
            timestamp(20),
            true,
            emptyList(), emptyList(), emptyList(), null,
        )
        return AuthorizationDecision(
            AuthorizationDecisionStatus.AUTHORIZED,
            AuthorizationDecisionReason.AUTHORITY_VALID,
            CapabilityAuthorizationReference.fromUuid(uuid(70 + capabilities.indexOf(capability))),
            audit,
        )
    }

    private fun calibration() = CalibrationNegotiationReadiness(
        CalibrationProfileId.fromUuid(uuid(90)), negotiationId(), provider.instanceId,
        generation, CalibrationNegotiationReadinessStatus.READY, emptyList(), emptyList(),
    )

    private fun bodyState(selected: Boolean): ActiveBodyState {
        val host = EmbodimentHost(
            success(EmbodimentHostReference.parse("firstlife.host")),
            io.redi.robot.trust.domain.HugoHostRole.MOBILE_HOST,
        )
        val reference = success<BodyProviderReference>(BodyProviderReference.create(provider))
        val body = BodyEmbodimentSnapshot.create(
            reference, BodyAvailability.AVAILABLE, true, true, true, true, MountingState.UNKNOWN, emptyList(),
        )
        val selection = if (selected) success<ActiveBodySelection>(ActiveBodySelection.create(
            reference, ActiveBodyActivationMode.REMOTE,
            success(ActiveBodySelectionReference.parse("firstlife.selection")), timestamp(1), timestamp(1000),
        )) else null
        return ActiveBodyState.create(success(EmbodimentGeneration.create(1)), host, listOf(body), selection)
    }

    private fun descriptor(reference: CapabilityReference): CapabilityDescriptor = success(CapabilityDescriptor.create(
        reference,
        success(CapabilityLabel.parse("Fake capability")),
        success(CapabilityDescription.parse("Provider-neutral First Life fake capability.")),
        null, null,
        if (reference == capabilities.last()) CapabilityEffect.OBSERVE else CapabilityEffect.ACT,
        CapabilityRiskClass.INFORMATIONAL,
        if (reference == capabilities.last()) CapabilitySafetyClass.NON_PHYSICAL else CapabilitySafetyClass.PHYSICAL_ACTION,
        if (reference == capabilities.last()) CapabilityPermissionIntent.OBSERVE else CapabilityPermissionIntent.ACT,
        CapabilityApprovalRequirement.WITHIN_GRANT,
        CapabilityCancellationSemantics.SUPPORTED,
        CapabilityAvailabilitySemantics.WHILE_REGISTERED,
        CapabilityEvidenceClass.AUTOMATED_TESTED,
    ))

    private fun canonicalBlocked(evidence: ProviderBlockedEvidence) =
        ((ProviderBlockedStateMapper.map(evidence) as ProviderBlockedMappingResult.Blocked).state).toDiagnosticSection()

    private fun consumptionAttempt() = ApprovalConsumptionAttempt(
        success(io.redi.robot.trust.domain.ApprovalId.parse(uuid(80).toString())),
        CapabilityAuthorizationReference.fromUuid(uuid(82)),
        requestId(80),
        success(AuthorizationDecisionId.parse(uuid(81).toString())),
        timestamp(5),
    )

    private fun providerIdentity(family: ProviderFamily) = ProviderIdentity(
        success(ProviderDefinitionId.parse("redi/fake-provider")),
        ProviderInstanceId.fromUuid(uuid(2 + family.ordinal)),
        family,
        success(RedactedProviderAlias.parse("First Life fake")),
    )

    private fun capability(raw: String) = success<CapabilityReference>(CapabilityReference.parse(raw))
    private fun generation(value: Long) = success<ProviderGeneration>(ProviderGeneration.create(value))
    private fun negotiationId() = CapabilityNegotiationId.fromUuid(uuid(20))
    private fun requestId(seed: Int) = success<CapabilityRequestId>(CapabilityRequestId.parse(uuid(seed).toString()))
    private fun timestamp(value: Long) = success<MonotonicTimestampMs>(MonotonicTimestampMs.create(value))
    private fun time(value: Long) = FirstLifeRuntimeTimeMs(value)
    private fun uuid(seed: Int) = UUID.fromString("00000000-0000-4000-8000-${seed.toString(16).padStart(12, '0')}")

    @Suppress("UNCHECKED_CAST")
    private fun <T> success(result: Any): T = when (result) {
        is CapabilityIdentityResult.Success<*> -> result.value as T
        is CapabilityDescriptorResult.Success<*> -> result.value as T
        is CapabilityEnvelopeResult.Success<*> -> result.value as T
        is CapabilityProviderPortResult.Success<*> -> result.value as T
        is ProviderIdentityResult.Success<*> -> result.value as T
        is ProviderRegistryResult.Success<*> -> result.value as T
        is AdapterResult.Success<*> -> result.value as T
        is CommonManifestResult.Success<*> -> result.value as T
        is ConnectionResult.Success<*> -> result.value as T
        is EmbodimentResult.Success<*> -> result.value as T
        is AuthorizationGateModelResult.Success<*> -> result.value as T
        is io.redi.robot.trust.domain.TrustDomainResult.Success<*> -> result.value as T
        is CalibrationDomainResult.Success<*> -> result.value as T
        else -> error("Expected success: $result")
    }
}
