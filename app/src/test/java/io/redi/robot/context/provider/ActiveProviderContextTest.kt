package io.redi.robot.context.provider

import io.redi.robot.adapter.api.AdapterId
import io.redi.robot.adapter.api.AdapterIdentity
import io.redi.robot.adapter.api.AdapterPackageDigest
import io.redi.robot.adapter.api.AdapterResult
import io.redi.robot.adapter.api.AdapterVersion
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.CapabilityProviderPortResult
import io.redi.robot.capability.provider.ProviderAvailabilityReason
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.capability.provider.ProviderRuntimeSnapshot
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleState
import io.redi.robot.capability.registry.ProviderAdapterReference
import io.redi.robot.capability.registry.ProviderManifestReference
import io.redi.robot.capability.registry.ProviderRegistryRecord
import io.redi.robot.capability.registry.ProviderRegistryResult
import io.redi.robot.capability.registry.ProviderRegistryRevision
import io.redi.robot.capability.registry.ProviderRegistryRuntimeSnapshot
import io.redi.robot.capability.registry.ProviderRegistrySnapshot
import io.redi.robot.capability.registry.RegisteredProviderSnapshot
import io.redi.robot.context.api.ContextValue
import io.redi.robot.context.runtime.ContextSnapshotCodec
import io.redi.robot.context.runtime.ContextSnapshotResolution
import io.redi.robot.manifest.domain.CommonManifestBinding
import io.redi.robot.manifest.domain.CommonManifestResult
import io.redi.robot.manifest.domain.ManifestDigest
import io.redi.robot.manifest.domain.SpecializedManifestKind
import io.redi.robot.trust.domain.HugoHostRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveProviderContextTest {
    @Test
    fun `initial state has no host provider selection or availability`() {
        val state = ActiveProviderContextState.initial()

        assertNull(state.revision)
        assertNull(state.snapshot)
    }

    @Test
    fun `refresh distinguishes host connected providers selected body active body and available services`() {
        val body = identity(1, ProviderFamily.BODY_ADAPTER)
        val device = identity(2, ProviderFamily.DEVICE_ADAPTER)
        val service = identity(3, ProviderFamily.SERVICE_CONNECTOR)
        val registry = registry(
            record(body, lifecycle = { ProviderLifecycleState.Registered(it) }),
            record(device, lifecycle = { ProviderLifecycleState.Connected(it) }),
            record(service, lifecycle = { ProviderLifecycleState.Registered(it) }),
        )
        val snapshot = refresh(
            ActiveProviderContextState.initial(),
            revision = 1,
            registry = registry,
            embodiment = embodiment(body, selected = true, bodyConnected = true),
        ).snapshot!!

        assertEquals(HugoHostRole.MOBILE_HOST, snapshot.host.role)
        assertEquals(3, snapshot.connectedProviders.size)
        assertEquals(1, snapshot.connectedBodies.size)
        assertEquals(1, snapshot.connectedDevices.size)
        assertEquals(1, snapshot.connectedServices.size)
        assertEquals(1, snapshot.availableServices.size)
        assertEquals(body.instanceId, snapshot.selectedBody?.identity?.instanceId)
        assertEquals(body.instanceId, snapshot.activeBody?.identity?.instanceId)
        assertEquals("3", knownValue(snapshot, "provider.connected.count"))
        assertEquals("true", knownValue(snapshot, "provider.body.selected"))
        assertEquals("true", knownValue(snapshot, "provider.body.active"))
        assertEquals("mobile_host", knownValue(snapshot, "provider.host.role"))
    }

    @Test
    fun `disconnect removes service availability even when stale runtime flag says available`() {
        val service = identity(3, ProviderFamily.SERVICE_CONNECTOR)
        val initial = refresh(
            ActiveProviderContextState.initial(),
            1,
            registry(record(service, lifecycle = { ProviderLifecycleState.Registered(it) })),
            embodiment(),
        )
        val disconnected = refresh(
            initial,
            2,
            registry(record(service, lifecycle = { ProviderLifecycleState.Disconnected(it) })),
            embodiment(),
        ).snapshot!!

        assertTrue(disconnected.connectedServices.isEmpty())
        assertTrue(disconnected.availableServices.isEmpty())
        assertEquals(ProviderContextConnection.NOT_CONNECTED, disconnected.providers.single().connection)
        assertEquals(ProviderContextAvailability.UNAVAILABLE, disconnected.providers.single().availability)
        assertEquals("0", knownValue(disconnected, "provider.service.available_count"))
    }

    @Test
    fun `body can remain explicitly selected after disconnect but is no longer active`() {
        val body = identity(1, ProviderFamily.BODY_ADAPTER)
        val connected = refresh(
            ActiveProviderContextState.initial(),
            1,
            registry(record(body, lifecycle = { ProviderLifecycleState.Registered(it) })),
            embodiment(body, selected = true, bodyConnected = true),
        )
        val disconnected = refresh(
            connected,
            2,
            registry(record(body, lifecycle = { ProviderLifecycleState.Disconnecting(it, io.redi.robot.capability.provider.lifecycle.ProviderLifecycleTerminalState.DISCONNECTED) })),
            embodiment(body, selected = true, bodyConnected = true),
        ).snapshot!!

        assertEquals(body.instanceId, disconnected.selectedBody?.identity?.instanceId)
        assertNull(disconnected.activeBody)
        assertEquals("true", knownValue(disconnected, "provider.body.selected"))
        assertEquals("false", knownValue(disconnected, "provider.body.active"))
        assertFalse(disconnected.grantsAuthorization)
        assertFalse(disconnected.grantsExecutionAuthority)
    }

    @Test
    fun `degraded service remains connected but is not presented as available`() {
        val service = identity(3, ProviderFamily.SERVICE_CONNECTOR)
        val snapshot = refresh(
            ActiveProviderContextState.initial(),
            1,
            registry(
                record(
                    service,
                    lifecycle = { ProviderLifecycleState.Registered(it) },
                    availability = ProviderRuntimeAvailability.DEGRADED,
                ),
            ),
            embodiment(),
        ).snapshot!!

        assertEquals(1, snapshot.connectedServices.size)
        assertTrue(snapshot.availableServices.isEmpty())
        assertEquals(ProviderContextAvailability.DEGRADED, snapshot.connectedServices.single().availability)
    }

    @Test
    fun `expired H016 selection is neither selected nor active`() {
        val body = identity(1, ProviderFamily.BODY_ADAPTER)
        val snapshot = refresh(
            ActiveProviderContextState.initial(),
            1,
            registry(record(body, lifecycle = { ProviderLifecycleState.Registered(it) })),
            embodiment(body, selected = true, bodyConnected = true, selectionExpiresAt = 100),
            observedAt = 100,
        ).snapshot!!

        assertNull(snapshot.selectedBody)
        assertNull(snapshot.activeBody)
    }

    @Test
    fun `future H016 selection is neither selected nor active`() {
        val body = identity(1, ProviderFamily.BODY_ADAPTER)
        val futureSelection = success<ActiveBodySelection>(
            ActiveBodySelection.create(
                success(BodyProviderReference.create(body)),
                ActiveBodyActivationMode.REMOTE,
                success(ActiveBodySelectionReference.parse("future-selection")),
                time(110),
                time(150),
            ),
        )
        val embodiment = ActiveBodyState.create(
            success(EmbodimentGeneration.create(1)),
            EmbodimentHost(
                success(EmbodimentHostReference.parse("mobile-host.primary")),
                HugoHostRole.MOBILE_HOST,
            ),
            listOf(
                BodyEmbodimentSnapshot.create(
                    success(BodyProviderReference.create(body)),
                    BodyAvailability.AVAILABLE,
                    discovered = true,
                    nearby = true,
                    connected = true,
                    remotelyControlled = true,
                    mounting = MountingState.UNKNOWN,
                    evidence = emptyList(),
                ),
            ),
            futureSelection,
        )
        val snapshot = refresh(
            ActiveProviderContextState.initial(),
            1,
            registry(record(body, lifecycle = { ProviderLifecycleState.Registered(it) })),
            embodiment,
            observedAt = 100,
        ).snapshot!!

        assertNull(snapshot.selectedBody)
        assertNull(snapshot.activeBody)
    }

    @Test
    fun `expire clears working snapshot exactly at expiry and does not invent empty facts`() {
        val state = refresh(
            ActiveProviderContextState.initial(),
            1,
            registry(),
            embodiment(),
            observedAt = 100,
            expiresAt = 120,
        )
        val early = reduction(
            ActiveProviderContextReducer.reduce(state, ActiveProviderContextEvent.Expire(revision(2), time(119))),
        )
        assertSame(state, early.state)
        assertEquals(
            ActiveProviderContextDisposition.Ignored(ActiveProviderContextIgnoredReason.NOT_EXPIRED),
            early.disposition,
        )

        val expired = reduction(
            ActiveProviderContextReducer.reduce(state, ActiveProviderContextEvent.Expire(revision(2), time(120))),
        )
        assertEquals(ActiveProviderContextDisposition.Applied, expired.disposition)
        assertEquals(2L, expired.state.revision?.value)
        assertNull(expired.state.snapshot)
    }

    @Test
    fun `stale and reused revisions cannot replace current context`() {
        val current = refresh(ActiveProviderContextState.initial(), 5, registry(), embodiment())

        val stale = reduction(
            ActiveProviderContextReducer.reduce(
                current,
                refreshEvent(4, registry(), embodiment()),
            ),
        )
        val reused = reduction(
            ActiveProviderContextReducer.reduce(
                current,
                refreshEvent(5, registry(), embodiment()),
            ),
        )

        assertSame(current, stale.state)
        assertEquals(
            ActiveProviderContextDisposition.Ignored(ActiveProviderContextIgnoredReason.STALE_REVISION),
            stale.disposition,
        )
        assertEquals(
            ActiveProviderContextDisposition.Ignored(ActiveProviderContextIgnoredReason.REUSED_REVISION),
            reused.disposition,
        )
    }

    @Test
    fun `future provider evidence and invalid refresh windows fail closed`() {
        val service = identity(3, ProviderFamily.SERVICE_CONNECTOR)
        val future = ActiveProviderContextReducer.reduce(
            ActiveProviderContextState.initial(),
            refreshEvent(
                1,
                registry(record(service, observedAt = 101, lifecycle = { ProviderLifecycleState.Registered(it) })),
                embodiment(),
                observedAt = 100,
            ),
        )
        assertTrue(future is ActiveProviderContextResult.Failure)
        assertTrue((future as ActiveProviderContextResult.Failure).error is ActiveProviderContextError.FutureProviderEvidence)

        assertEquals(
            ActiveProviderContextResult.Failure(ActiveProviderContextError.InvalidFreshnessWindow),
            ActiveProviderContextReducer.reduce(
                ActiveProviderContextState.initial(),
                refreshEvent(1, registry(), embodiment(), observedAt = 100, expiresAt = 100),
            ),
        )
    }

    @Test
    fun `registry ordering cannot change provider or H037 projection ordering`() {
        val body = identity(1, ProviderFamily.BODY_ADAPTER)
        val service = identity(3, ProviderFamily.SERVICE_CONNECTOR)
        val bodyRecord = registered(record(body, lifecycle = { ProviderLifecycleState.Registered(it) }))
        val serviceRecord = registered(record(service, lifecycle = { ProviderLifecycleState.Registered(it) }))
        val first = refresh(
            ActiveProviderContextState.initial(),
            1,
            ProviderRegistrySnapshot.of(listOf(serviceRecord, bodyRecord)),
            embodiment(body, selected = true, bodyConnected = true),
        ).snapshot!!
        val second = refresh(
            ActiveProviderContextState.initial(),
            1,
            ProviderRegistrySnapshot.of(listOf(bodyRecord, serviceRecord)),
            embodiment(body, selected = true, bodyConnected = true),
        ).snapshot!!

        assertEquals(
            first.providers.map { it.identity.instanceId },
            second.providers.map { it.identity.instanceId },
        )
        assertEquals(
            ContextSnapshotCodec.encode(first.contextSnapshot),
            ContextSnapshotCodec.encode(second.contextSnapshot),
        )
    }

    @Test
    fun `H037 projection is private aggregate evidence with no provider identity or authority leakage`() {
        val body = identity(1, ProviderFamily.BODY_ADAPTER)
        val registry = registry(record(body, lifecycle = { ProviderLifecycleState.Registered(it) }))
        val entriesBefore = registry.entries.toList()
        val snapshot = refresh(
            ActiveProviderContextState.initial(),
            1,
            registry,
            embodiment(body, selected = true, bodyConnected = true),
        ).snapshot!!
        val encoded = ContextSnapshotCodec.encode(snapshot.contextSnapshot)

        assertFalse(encoded.contains(body.instanceId.value))
        assertFalse(encoded.contains(body.displayAlias.value))
        assertFalse(encoded.contains("authorized", ignoreCase = true))
        assertFalse(encoded.contains("execution", ignoreCase = true))
        assertEquals(entriesBefore, registry.entries)
        assertTrue(snapshot.contextSnapshot.items.all {
            it.effectivePrivacyClassification == io.redi.robot.context.api.ContextPrivacyClassification.PRIVATE
        })
    }

    @Test
    fun `connected body then disconnect state trace matches portable fixture`() {
        val body = identity(1, ProviderFamily.BODY_ADAPTER)
        val connected = refresh(
            ActiveProviderContextState.initial(),
            1,
            registry(record(body, lifecycle = { ProviderLifecycleState.Registered(it) })),
            embodiment(body, selected = true, bodyConnected = true),
        )
        val disconnected = refresh(
            connected,
            2,
            registry(record(body, lifecycle = { ProviderLifecycleState.Disconnected(it) })),
            embodiment(body, selected = true, bodyConnected = true),
        )
        val actual = "[${trace(connected.snapshot!!)},${trace(disconnected.snapshot!!)}]"
        val expected = checkNotNull(javaClass.getResource("/fixtures/h038-active-provider-state-trace.json"))
            .readText()
            .trim()

        assertEquals(expected, actual)
    }

    private fun refresh(
        state: ActiveProviderContextState,
        revision: Long,
        registry: ProviderRegistrySnapshot,
        embodiment: ActiveBodyState,
        observedAt: Long = 100,
        expiresAt: Long = 150,
    ): ActiveProviderContextState = reduction(
        ActiveProviderContextReducer.reduce(
            state,
            refreshEvent(revision, registry, embodiment, observedAt, expiresAt),
        ),
    ).state

    private fun refreshEvent(
        revision: Long,
        registry: ProviderRegistrySnapshot,
        embodiment: ActiveBodyState,
        observedAt: Long = 100,
        expiresAt: Long = 150,
    ) = ActiveProviderContextEvent.Refresh(
        revision(revision),
        registry,
        embodiment,
        time(observedAt),
        time(expiresAt),
    )

    private fun embodiment(
        bodyIdentity: ProviderIdentity? = null,
        selected: Boolean = false,
        bodyConnected: Boolean = false,
        bodyAvailability: BodyAvailability = BodyAvailability.AVAILABLE,
        selectionExpiresAt: Long = 150,
    ): ActiveBodyState {
        val body = bodyIdentity?.let { success<BodyProviderReference>(BodyProviderReference.create(it)) }
        val bodySnapshot = body?.let {
            BodyEmbodimentSnapshot.create(
                body = it,
                availability = bodyAvailability,
                discovered = true,
                nearby = true,
                connected = bodyConnected,
                remotelyControlled = true,
                mounting = MountingState.UNKNOWN,
                evidence = emptyList(),
            )
        }
        val selection = if (selected && body != null) {
            success<ActiveBodySelection>(
                ActiveBodySelection.create(
                    body,
                    ActiveBodyActivationMode.REMOTE,
                    success(ActiveBodySelectionReference.parse("selection")),
                    time(90),
                    time(selectionExpiresAt),
                ),
            )
        } else {
            null
        }
        return ActiveBodyState.create(
            success(EmbodimentGeneration.create(1)),
            EmbodimentHost(success(EmbodimentHostReference.parse("mobile-host.primary")), HugoHostRole.MOBILE_HOST),
            listOfNotNull(bodySnapshot),
            selection,
        )
    }

    private fun registry(vararg records: ProviderRegistryRecord): ProviderRegistrySnapshot =
        ProviderRegistrySnapshot.of(records.map(::registered))

    private fun registered(record: ProviderRegistryRecord): RegisteredProviderSnapshot =
        RegisteredProviderSnapshot(record, success(ProviderRegistryRevision.create(0)))

    private fun record(
        identity: ProviderIdentity,
        lifecycle: (ProviderGeneration) -> ProviderLifecycleState,
        availability: ProviderRuntimeAvailability = ProviderRuntimeAvailability.AVAILABLE,
        observedAt: Long = 90,
    ): ProviderRegistryRecord {
        val generation = success<ProviderGeneration>(ProviderGeneration.create(1))
        val runtimeAvailability = success<ProviderRuntimeSnapshot>(
            ProviderRuntimeSnapshot.create(
                generation,
                availability,
                time(observedAt),
                if (availability == ProviderRuntimeAvailability.AVAILABLE) {
                    null
                } else {
                    ProviderAvailabilityReason.TEMPORARILY_UNAVAILABLE
                },
            ),
        )
        val runtime = success<ProviderRegistryRuntimeSnapshot>(
            ProviderRegistryRuntimeSnapshot.create(generation, lifecycle(generation), runtimeAvailability),
        )
        return success(
            ProviderRegistryRecord.create(
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
                    AdapterIdentity(
                        success(AdapterId.parse("test.provider/adapter")),
                        success(AdapterVersion.parse("1.0.0")),
                    ),
                    success(AdapterPackageDigest.parse("sha256:" + "a".repeat(64))),
                ),
                emptyList(),
                runtime,
            ),
        )
    }

    private fun identity(index: Int, family: ProviderFamily): ProviderIdentity = ProviderIdentity(
        success(ProviderDefinitionId.parse("test/provider")),
        success(ProviderInstanceId.parse("00000000-0000-0000-0000-${index.toString().padStart(12, '0')}")),
        family,
        success(RedactedProviderAlias.parse("Test provider $index")),
    )

    private fun knownValue(snapshot: ActiveProviderContextSnapshot, key: String): String {
        val resolution = snapshot.contextSnapshot.items.single { it.key.value == key }.resolution
            as ContextSnapshotResolution.Known
        return resolution.value.canonicalValue
    }

    private fun trace(snapshot: ActiveProviderContextSnapshot): String =
        "{\"revision\":${snapshot.revision.value},\"connectedProviders\":${snapshot.connectedProviders.size}," +
            "\"selectedBody\":${snapshot.selectedBody != null},\"activeBody\":${snapshot.activeBody != null}," +
            "\"availableServices\":${snapshot.availableServices.size}}"

    private fun revision(value: Long): ActiveProviderContextRevision =
        success(ActiveProviderContextRevision.create(value))

    private fun time(value: Long): MonotonicTimestampMs = success(MonotonicTimestampMs.create(value))

    private fun reduction(
        result: ActiveProviderContextResult<ActiveProviderContextReduction>,
    ): ActiveProviderContextReduction = success(result)

    @Suppress("UNCHECKED_CAST")
    private fun <T> success(result: Any): T = when (result) {
        is ActiveProviderContextResult.Success<*> -> result.value as T
        is EmbodimentResult.Success<*> -> result.value as T
        is ProviderRegistryResult.Success<*> -> result.value as T
        is ProviderIdentityResult.Success<*> -> result.value as T
        is CapabilityProviderPortResult.Success<*> -> result.value as T
        is CapabilityEnvelopeResult.Success<*> -> result.value as T
        is AdapterResult.Success<*> -> result.value as T
        is CommonManifestResult.Success<*> -> result.value as T
        else -> error("Expected success, got $result")
    }
}
