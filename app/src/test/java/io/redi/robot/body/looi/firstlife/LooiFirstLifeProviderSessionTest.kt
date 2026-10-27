package io.redi.robot.body.looi.firstlife

import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.negotiation.CapabilityNegotiationStatus
import io.redi.robot.capability.provider.CapabilityProviderPortResult
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.connection.api.ConnectionConnectRequest
import io.redi.robot.connection.api.ConnectionEndpointReference
import io.redi.robot.connection.api.ConnectionGeneration
import io.redi.robot.connection.api.ConnectionLifecycleEvent
import io.redi.robot.connection.api.ConnectionLifecycleReducer
import io.redi.robot.connection.api.ConnectionLifecycleSnapshot
import io.redi.robot.connection.api.ConnectionMethodId
import io.redi.robot.connection.api.ConnectionMethodReference
import io.redi.robot.connection.api.ConnectionMethodVersion
import io.redi.robot.connection.api.ConnectionRequestId
import io.redi.robot.connection.api.ConnectionResult
import io.redi.robot.connection.api.ConnectionSessionId
import io.redi.robot.connection.api.ConnectionMetadata
import io.redi.robot.manifest.domain.SpecializedManifestKind
import io.redi.robot.adapter.compatibility.CompatibilityMatcher
import io.redi.robot.adapter.compatibility.CompatibilityRequest
import io.redi.robot.adapter.compatibility.CompatibilityReportStatus
import io.redi.robot.adapter.compatibility.HugoRuntimeEvidence
import io.redi.robot.adapter.compatibility.CompatibilityEvidenceReference
import io.redi.robot.adapter.compatibility.CompatibilityInputResult
import io.redi.robot.adapter.compatibility.ConnectionMethodSupport
import io.redi.robot.adapter.api.AdapterResult
import io.redi.robot.adapter.api.HugoRuntimeVersion
import io.redi.robot.manifest.resolution.ManifestCandidateId
import io.redi.robot.manifest.resolution.ManifestCandidateProvenance
import io.redi.robot.manifest.resolution.ManifestCandidateResult
import io.redi.robot.manifest.resolution.ManifestCandidateSource
import io.redi.robot.manifest.resolution.ManifestCatalogReference
import io.redi.robot.manifest.resolution.ManifestDefinitionCandidate
import io.redi.robot.manifest.resolution.ManifestResolutionRequest
import io.redi.robot.manifest.resolution.ManifestResolutionStatus
import io.redi.robot.manifest.resolution.ManifestResolver
import io.redi.robot.hugo.identity.HugoIdentity
import io.redi.robot.hugo.identity.HugoIdentityId
import io.redi.robot.feature.hihugo.presentation.HiHugoBodyPresentationProjection
import io.redi.robot.feature.hihugo.presentation.HiHugoBodyStage
import io.redi.robot.discovery.identity.IdentityEvidenceResult
import io.redi.robot.discovery.identity.ProtectedIdentityEvidenceReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LooiFirstLifeProviderSessionTest {
    private val exactCapabilities = listOf(
        "body.head.position.control@1.0",
        "body.light.control@1.0",
        "mobility.move@1.0",
        "mobility.stop@1.0",
        "provider.connection.status@1.0",
        "safety.lower_contact.status@1.0",
    )

    @Test
    fun `built-in BODY manifest and known adapter resolve with exact H017 set`() {
        assertEquals(ProviderFamily.BODY_ADAPTER, LooiFirstLifeDefinition.commonDocument.manifest.provider.family)
        assertEquals(SpecializedManifestKind.BODY, LooiFirstLifeDefinition.bodyDocument.manifest.kind)
        assertEquals(exactCapabilities, LooiFirstLifeDefinition.capabilityReferences.map { it.wireValue })
        assertEquals(exactCapabilities, LooiFirstLifeDefinition.adapterDescriptor.supportedCapabilities.map { it.wireValue })
        assertEquals(LooiFirstLifeDefinition.adapterId, LooiFirstLifeDefinition.adapterRecord.key.adapterId)
        assertFalse(LooiFirstLifeDefinition.adapterRecord.knowledgeState.name.contains("INSTALLED"))
        assertTrue(session(1).isValidated())
    }

    @Test
    fun `protocol-ready current session negotiates H029 exact live subset without authority`() {
        val report = session(1).negotiate(connected(1), protocolReady = true, nowMs = 100)
        assertEquals(CapabilityNegotiationStatus.NEGOTIATED, report?.status)
        assertEquals(exactCapabilities, report?.negotiatedCapabilities?.map { it.capability.wireValue })
    }

    @Test
    fun `expired H029 evidence can be re-evaluated on the same current session without changing its TTL`() {
        val provider = session(1)
        val connection = connected(1)
        val first = checkNotNull(provider.negotiate(connection, protocolReady = true, nowMs = 100))
        assertTrue(first.isCurrentForFirstLife(60_099))
        assertFalse(first.isCurrentForFirstLife(60_100))

        val refreshed = checkNotNull(provider.negotiate(connection, protocolReady = true, nowMs = 60_100))
        assertFalse(first.negotiationId == refreshed.negotiationId)
        assertEquals(exactCapabilities, refreshed.negotiatedCapabilities.map { it.capability.wireValue })
        assertTrue(refreshed.isCurrentForFirstLife(60_100))
        assertFalse(refreshed.isCurrentForFirstLife(120_100))
    }

    @Test
    fun `handshake missing stale generation and disconnected lifecycle fail closed`() {
        assertNull(session(1).negotiate(connected(1), protocolReady = false, nowMs = 100))
        assertNull(session(1).negotiate(connected(2), protocolReady = true, nowMs = 100))
        assertNull(session(1).negotiate(ConnectionLifecycleSnapshot.initial(), protocolReady = true, nowMs = 100))
    }

    @Test
    fun `provider wiring domain leaks no Android BLE or protocol types`() {
        val types = listOf(LooiFirstLifeDefinition::class.java, LooiFirstLifeProviderSession::class.java)
        val names = types.flatMap { it.declaredMethods.toList() }
            .flatMap { listOf(it.returnType.name) + it.parameterTypes.map(Class<*>::getName) }
        assertFalse(names.any { it.startsWith("android.") || it.contains("Bluetooth") || it.contains("Gatt") || it.contains("handshake") })
    }

    @Test
    fun `H021 mapping is protected redacted and correlated into H022 evidence`() {
        val mapping = LooiFirstLifeIdentityMappingFactory.create(
            identity(),
            identityEvidence(ProtectedIdentityEvidenceReference.parse("iref_aaaaaaaaaaaaaaaa")),
            timestamp(10),
            verified = true,
        )
        val session = LooiFirstLifeProviderSession(identity(), provider(ProviderGeneration.create(1)), mapping)
        assertTrue(mapping.evidence.redacted().hasProtectedValueReference)
        assertFalse(mapping.evidence.toString().contains("iref_"))
        assertEquals(setOf(mapping.evidence.evidenceId), session.preparedProvider.identityResolution?.consideredEvidenceIds)
    }

    @Test
    fun `definition candidate resolves manifest but cannot negotiate before verified binding`() {
        val mapping = LooiFirstLifeIdentityMappingFactory.create(
            identity(),
            identityEvidence(ProtectedIdentityEvidenceReference.parse("iref_bbbbbbbbbbbbbbbb")),
            timestamp(10),
            verified = false,
        )
        val session = LooiFirstLifeProviderSession(identity(), provider(ProviderGeneration.create(1)), mapping)
        assertTrue(session.isValidated())
        assertNull(session.preparedProvider.manifestResolution?.resolved?.identityBinding?.instanceId)
        assertNull(session.negotiate(connected(1), protocolReady = true, nowMs = 100))
        assertFalse(mapping.evidence.redacted().hasProtectedValueReference)
    }

    @Test
    fun `genuine LOOI evidence reaches FL005 and provider loss clears readiness`() {
        val provider = session(1)
        val connection = connected(1)
        val report = checkNotNull(provider.negotiate(connection, protocolReady = true, nowMs = 100))
        val binding = LooiFirstLifeRuntimeBinding(
            HugoIdentity(HugoIdentityId.fromUuid(java.util.UUID.fromString("40000000-0000-4000-8000-000000000001"))),
            1,
        )
        binding.providerReady(provider, connection, report, 101)
        val ready = binding.snapshot()
        assertTrue(ready.providerPrepared)
        assertTrue(ready.providerRegistered)
        assertTrue(ready.bodyReady)
        assertFalse(ready.activeBodySelected)
        assertFalse(ready.authorized)
        assertFalse(ready.actionReady)

        binding.providerLost(provider(ProviderGeneration.create(1)), 102)
        assertFalse(binding.snapshot().bodyReady)
        assertTrue(binding.snapshot().negotiatedCapabilities.isEmpty())
    }

    @Test
    fun `FL008 explicit active body selection expires deactivates and grants no authority`() {
        val provider = session(1)
        val connection = connected(1)
        val report = checkNotNull(provider.negotiate(connection, protocolReady = true, nowMs = 100))
        val binding = LooiFirstLifeRuntimeBinding(
            HugoIdentity(HugoIdentityId.fromUuid(java.util.UUID.fromString("40000000-0000-4000-8000-000000000002"))),
            1,
        )

        assertEquals(
            LooiActiveBodySelectionStatus.REJECTED_NOT_AVAILABLE,
            binding.selectActiveBody(50).status,
        )
        binding.providerReady(provider, connection, report, 101)
        val available = HiHugoBodyPresentationProjection.project(binding.snapshot(), null)
        assertEquals(HiHugoBodyStage.AVAILABLE, available.stage)
        assertEquals(exactCapabilities, available.capabilities)

        val selected = binding.selectActiveBody(102)
        assertEquals(LooiActiveBodySelectionStatus.SELECTED, selected.status)
        assertTrue(selected.snapshot.activeBodySelected)
        assertFalse(selected.grantsExecutionAuthority)
        assertFalse(selected.snapshot.authorized)
        assertFalse(selected.snapshot.actionReady)
        assertEquals(HiHugoBodyStage.SELECTED, HiHugoBodyPresentationProjection.project(selected.snapshot, null).stage)

        val unchanged = binding.expireActiveBody(60_101)
        assertEquals(LooiActiveBodySelectionStatus.UNCHANGED, unchanged.status)
        val expired = binding.expireActiveBody(60_102)
        assertEquals(LooiActiveBodySelectionStatus.EXPIRED, expired.status)
        assertFalse(expired.snapshot.activeBodySelected)

        binding.selectActiveBody(60_103)
        val deactivated = binding.deactivateActiveBody(60_104)
        assertEquals(LooiActiveBodySelectionStatus.DEACTIVATED, deactivated.status)
        assertFalse(deactivated.snapshot.activeBodySelected)
    }

    @Test
    fun `FL008 generation change and provider loss clear prior selection`() {
        val identity = HugoIdentity(HugoIdentityId.fromUuid(java.util.UUID.fromString("40000000-0000-4000-8000-000000000003")))
        val binding = LooiFirstLifeRuntimeBinding(identity, 1)
        val first = session(1)
        val firstConnection = connected(1)
        binding.providerReady(first, firstConnection, checkNotNull(first.negotiate(firstConnection, true, 100)), 101)
        assertTrue(binding.selectActiveBody(102).snapshot.activeBodySelected)

        val second = session(2)
        val secondConnection = connected(2)
        binding.providerReady(second, secondConnection, checkNotNull(second.negotiate(secondConnection, true, 200)), 201)
        assertFalse(binding.snapshot().activeBodySelected)
        assertTrue(binding.snapshot().bodyReady)
        assertEquals(identity, binding.snapshot().hugoIdentity)

        binding.selectActiveBody(202)
        binding.providerLost(provider(ProviderGeneration.create(2)), 203)
        assertFalse(binding.snapshot().activeBodySelected)
        assertFalse(binding.snapshot().bodyReady)
        assertEquals(identity, binding.snapshot().hugoIdentity)
    }

    @Test
    fun `missing adapter remains explicit compatibility evidence`() {
        val resolved = checkNotNull(session(1).preparedProvider.manifestResolution?.resolved)
        val report = CompatibilityMatcher.match(CompatibilityRequest.create(
            resolved,
            null,
            HugoRuntimeEvidence(adapter(HugoRuntimeVersion.parse("1.0.0")), evidence("hugo.runtime.test")),
            listOf(ConnectionMethodSupport(
                LOOI_BLE_CONNECTION_METHOD,
                evidence("hugo.connection.test"),
            )),
            emptyList(),
            emptyList(),
        ))
        assertEquals(CompatibilityReportStatus.MISSING_ADAPTER, report.status)
    }

    @Test
    fun `tampered built-in manifest fails closed instead of preparing provider`() {
        val original = LooiFirstLifeDefinition.manifestCandidate
        val tampered = candidate(ManifestDefinitionCandidate.create(
            candidate(ManifestCandidateId.parse("50000000-0000-4000-8000-000000000001")),
            ManifestCandidateProvenance(
                ManifestCandidateSource.BUILT_IN_HUGO,
                candidate(ManifestCatalogReference.parse("hugo.builtin.looi-reference.tampered")),
            ),
            original.commonDocument.replace("LOOI Reference Body", "Tampered Body"),
            original.specializedDocument,
        ))
        val result = ManifestResolver.resolve(ManifestResolutionRequest.create(
            checkNotNull(session(1).preparedProvider.identityResolution),
            listOf(tampered),
            listOf(LooiFirstLifeDefinition.adapterDescriptor),
        ))
        assertTrue(result.status in setOf(ManifestResolutionStatus.DIGEST_MISMATCH, ManifestResolutionStatus.REJECTED))
    }

    @Test
    fun `H034 diagnostics are redacted and retain exact negotiation evidence`() {
        val provider = session(1)
        val connection = connected(1)
        val report = checkNotNull(provider.negotiate(connection, true, 100))
        val diagnostics = provider.diagnostics(connection, null, report, 101)
        val rendered = diagnostics.toString()
        assertFalse(rendered.contains(provider.identity.instanceId.value))
        assertFalse(rendered.contains("private-handle"))
        assertEquals(exactCapabilities, diagnostics.negotiation?.capabilities?.map { it.wireValue })
    }

    private fun session(generation: Long) = LooiFirstLifeProviderSession(identity(), provider(ProviderGeneration.create(generation)))

    private fun identity() = ProviderIdentity(
        LooiFirstLifeDefinition.definitionId,
        providerIdentity(ProviderInstanceId.parse("10000000-0000-4000-8000-000000000001")),
        ProviderFamily.BODY_ADAPTER,
        providerIdentity(RedactedProviderAlias.parse("LOOI Reference Body")),
    )

    private fun connected(generation: Long): ConnectionLifecycleSnapshot {
        val method = ConnectionMethodReference(
            connection(ConnectionMethodId.parse("io.hugo.connection/bluetooth-le")),
            connection(ConnectionMethodVersion.parse("1.0")),
        )
        val request = connection(ConnectionConnectRequest.create(
            connection(ConnectionRequestId.parse("20000000-0000-4000-8000-000000000001")),
            connection(ConnectionGeneration.create(generation)),
            method,
            connection(ConnectionEndpointReference.parse("looi.endpoint.current")),
            null,
            timestamp(10),
            timestamp(1000),
        ))
        val connecting = ConnectionLifecycleReducer.reduce(
            ConnectionLifecycleSnapshot.initial(), ConnectionLifecycleEvent.ConnectRequested(request),
        ).snapshot
        return ConnectionLifecycleReducer.reduce(
            connecting,
            ConnectionLifecycleEvent.ChannelEstablished(
                request.generation,
                request.requestId,
                connection(ConnectionSessionId.parse("30000000-0000-4000-8000-000000000001")),
                ConnectionMetadata(method, request.endpoint, null, timestamp(20)),
            ),
        ).snapshot
    }

    private fun timestamp(value: Long) = (MonotonicTimestampMs.create(value) as CapabilityEnvelopeResult.Success).value
    private fun <T> connection(result: ConnectionResult<T>) = (result as ConnectionResult.Success).value
    private fun <T> provider(result: CapabilityProviderPortResult<T>) = (result as CapabilityProviderPortResult.Success).value
    private fun <T> providerIdentity(result: ProviderIdentityResult<T>) = (result as ProviderIdentityResult.Success).value
    private fun <T> identityEvidence(result: IdentityEvidenceResult<T>) = (result as IdentityEvidenceResult.Success).value
    private fun <T> adapter(result: AdapterResult<T>) = (result as AdapterResult.Success).value
    private fun evidence(raw: String) = (CompatibilityEvidenceReference.parse(raw) as CompatibilityInputResult.Success).value
    private fun <T> candidate(result: ManifestCandidateResult<T>) = (result as ManifestCandidateResult.Success).value
}
