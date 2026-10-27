package io.redi.robot.capability.negotiation

import io.redi.robot.adapter.api.AdapterDescriptor
import io.redi.robot.adapter.api.AdapterId
import io.redi.robot.adapter.api.AdapterIdentity
import io.redi.robot.adapter.api.AdapterManifestKind
import io.redi.robot.adapter.api.AdapterManifestSupport
import io.redi.robot.adapter.api.AdapterPackageDigest
import io.redi.robot.adapter.api.AdapterProvenance
import io.redi.robot.adapter.api.AdapterProvenanceSource
import io.redi.robot.adapter.api.AdapterPublisherReference
import io.redi.robot.adapter.api.AdapterResult
import io.redi.robot.adapter.api.AdapterSchemaVersion
import io.redi.robot.adapter.api.AdapterSourceReference
import io.redi.robot.adapter.api.AdapterSpecializedManifestSupport
import io.redi.robot.adapter.api.AdapterVersion
import io.redi.robot.adapter.api.HugoRuntimeCompatibility
import io.redi.robot.adapter.api.HugoRuntimeVersion
import io.redi.robot.adapter.compatibility.CompatibilityEvidenceReference
import io.redi.robot.adapter.compatibility.CompatibilityInputResult
import io.redi.robot.adapter.compatibility.CompatibilityReport
import io.redi.robot.adapter.compatibility.CompatibilityReportStatus
import io.redi.robot.adapter.registry.AdapterCompatibilityCandidate
import io.redi.robot.adapter.registry.AdapterArtifactReference
import io.redi.robot.adapter.registry.AdapterKnowledgeState
import io.redi.robot.adapter.registry.AdapterRegistryModelResult
import io.redi.robot.adapter.registry.AdapterRegistryRecord
import io.redi.robot.capability.api.CapabilityApprovalRequirement
import io.redi.robot.capability.api.CapabilityAvailabilitySemantics
import io.redi.robot.capability.api.CapabilityCancellationSemantics
import io.redi.robot.capability.api.CapabilityContractReference
import io.redi.robot.capability.api.CapabilityDescription
import io.redi.robot.capability.api.CapabilityDescriptor
import io.redi.robot.capability.api.CapabilityDescriptorResult
import io.redi.robot.capability.api.CapabilityEffect
import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityLabel
import io.redi.robot.capability.api.CapabilityLimitKey
import io.redi.robot.capability.api.CapabilityNumericLimit
import io.redi.robot.capability.api.CapabilityPermissionIntent
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.CapabilityRiskClass
import io.redi.robot.capability.api.CapabilitySafetyClass
import io.redi.robot.capability.api.CapabilityUnit
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.provider.CapabilityNegotiationEntry
import io.redi.robot.capability.provider.CapabilityNegotiationRequest
import io.redi.robot.capability.provider.CapabilityNegotiationResult
import io.redi.robot.capability.provider.CapabilityNegotiationState
import io.redi.robot.capability.provider.CapabilityProviderPortResult
import io.redi.robot.capability.provider.CapabilityRegistration
import io.redi.robot.capability.provider.CapabilityRegistrationRequest
import io.redi.robot.capability.provider.CapabilityRegistrationResult
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
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleEvent
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleReducer
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleSnapshot
import io.redi.robot.capability.registry.ProviderRegistrySnapshot
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
import io.redi.robot.manifest.domain.AdapterCompatibilityReference
import io.redi.robot.manifest.domain.AdapterDefinitionReference
import io.redi.robot.manifest.domain.BodyManifest
import io.redi.robot.manifest.domain.BodyMobilityClass
import io.redi.robot.manifest.domain.BodyMountingSupport
import io.redi.robot.manifest.domain.BodyPowerRelationship
import io.redi.robot.manifest.domain.CommonManifestBinding
import io.redi.robot.manifest.domain.CommonManifestResult
import io.redi.robot.manifest.domain.CommonProviderManifest
import io.redi.robot.manifest.domain.CommonProviderManifestCodec
import io.redi.robot.manifest.domain.CommonProviderManifestDocument
import io.redi.robot.manifest.domain.ManifestConnectionMethod
import io.redi.robot.manifest.domain.ManifestContractReference
import io.redi.robot.manifest.domain.ManifestProviderDeclaration
import io.redi.robot.manifest.domain.ManifestProvenance
import io.redi.robot.manifest.domain.ManifestSchemaVersion
import io.redi.robot.manifest.domain.ManifestSource
import io.redi.robot.manifest.domain.ManifestSourceReference
import io.redi.robot.manifest.domain.ManifestText
import io.redi.robot.manifest.domain.ManifestVersion
import io.redi.robot.manifest.domain.ManifestVersionRange
import io.redi.robot.manifest.domain.SpecializedManifestDocument
import io.redi.robot.manifest.domain.SpecializedManifestResult
import io.redi.robot.manifest.domain.SpecializedProviderManifestCodec
import io.redi.robot.manifest.resolution.AdapterCandidateAssessment
import io.redi.robot.manifest.resolution.AdapterCandidateAssessmentStatus
import io.redi.robot.manifest.resolution.IdentityManifestBinding
import io.redi.robot.manifest.resolution.ManifestCandidateId
import io.redi.robot.manifest.resolution.ManifestCandidateProvenance
import io.redi.robot.manifest.resolution.ManifestCandidateResult
import io.redi.robot.manifest.resolution.ManifestCandidateSource
import io.redi.robot.manifest.resolution.ManifestCatalogReference
import io.redi.robot.manifest.resolution.ManifestResolution
import io.redi.robot.manifest.resolution.ManifestResolutionReason
import io.redi.robot.manifest.resolution.ManifestResolutionStatus
import io.redi.robot.manifest.resolution.ResolvedManifestDefinition
import io.redi.robot.provider.runtime.ProviderCompatibilityEvaluation
import io.redi.robot.provider.runtime.ProviderOrchestrationCorrelation
import io.redi.robot.provider.runtime.ProviderOrchestrationPhase
import io.redi.robot.provider.runtime.ProviderOrchestrationRunId
import io.redi.robot.provider.runtime.ProviderOrchestrationSnapshot
import io.redi.robot.provider.runtime.ProviderPreparationEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CapabilityNegotiatorTest {
    @Test
    fun `full negotiation intersects manifest adapter runtime descriptor and current session`() {
        val fixture = Fixture()
        val report = CapabilityNegotiator.negotiate(fixture.input())

        assertEquals(CapabilityNegotiationStatus.NEGOTIATED, report.status)
        assertEquals(CapabilityNegotiationReason.ALL_DECLARED_CAPABILITIES_NEGOTIATED, report.reason)
        assertEquals(fixture.claims.sortedBy { it.wireValue }, report.negotiatedCapabilities.map { it.capability })
        assertTrue(report.negotiatedCapabilities.all { it.providerInstanceId == fixture.identity.instanceId })
        assertTrue(report.negotiatedCapabilities.all { it.connectionSessionIds == listOf(fixture.sessionId) })
    }

    @Test
    fun `partial negotiation retains supported capability and explains runtime unsupported claim`() {
        val fixture = Fixture(
            runtimeStates = mapOf("body.light.control" to CapabilityNegotiationState.UNSUPPORTED),
        )
        val report = CapabilityNegotiator.negotiate(fixture.input())

        assertEquals(CapabilityNegotiationStatus.PARTIALLY_NEGOTIATED, report.status)
        assertEquals(listOf("mobility.move@1.0"), report.negotiatedCapabilities.map { it.capability.wireValue })
        assertTrue(report.checks.any { it.finding == CapabilityNegotiationFinding.CAPABILITY_UNSUPPORTED_BY_RUNTIME })
    }

    @Test
    fun `manifest claim unsupported by adapter is never negotiated`() {
        val fixture = Fixture(adapterCapabilities = listOf(capability("mobility.move@1.0")))
        val report = CapabilityNegotiator.negotiate(fixture.input())

        assertEquals(CapabilityNegotiationStatus.PARTIALLY_NEGOTIATED, report.status)
        assertTrue(report.checks.any { it.finding == CapabilityNegotiationFinding.ADAPTER_SUPPORT_MISSING })
        assertEquals(listOf("mobility.move@1.0"), report.negotiatedCapabilities.map { it.capability.wireValue })
    }

    @Test
    fun `adapter support absent from manifest is explicitly excluded`() {
        val extra = capability("provider.connection.status@1.0")
        val fixture = Fixture(adapterCapabilities = defaultClaims() + extra)
        val report = CapabilityNegotiator.negotiate(fixture.input())

        assertEquals(CapabilityNegotiationStatus.NEGOTIATED, report.status)
        assertFalse(report.negotiatedCapabilities.any { it.capability == extra })
        assertTrue(report.checks.any {
            it.finding == CapabilityNegotiationFinding.NOT_DECLARED_BY_MANIFEST &&
                it.observedValues == listOf(extra.wireValue)
        })
    }

    @Test
    fun `incompatible runtime capability version produces version mismatch`() {
        val fixture = Fixture(
            claims = listOf(capability("mobility.move@2.0")),
            adapterCapabilities = listOf(capability("mobility.move@2.0")),
            runtimeStates = mapOf("mobility.move" to CapabilityNegotiationState.INCOMPATIBLE),
        )
        val report = CapabilityNegotiator.negotiate(fixture.input())

        assertEquals(CapabilityNegotiationStatus.VERSION_MISMATCH, report.status)
        assertTrue(report.negotiatedCapabilities.isEmpty())
    }

    @Test
    fun `provider family mismatch conflicts with resolved manifest`() {
        val fixture = Fixture()
        val wrongFamily = fixture.identity.copy(family = ProviderFamily.DEVICE_ADAPTER)
        val report = CapabilityNegotiator.negotiate(fixture.input(providerIdentity = wrongFamily))

        assertEquals(CapabilityNegotiationStatus.CONFLICTING_DECLARATION, report.status)
        assertTrue(report.checks.any { it.finding == CapabilityNegotiationFinding.PROVIDER_FAMILY_MISMATCH })
    }

    @Test
    fun `stale provider generation stops before capability intersection`() {
        val fixture = Fixture()
        val staleLifecycle = providerLifecycle(generation(2), negotiating = true)
        val report = CapabilityNegotiator.negotiate(fixture.input(providerLifecycle = staleLifecycle))

        assertEquals(CapabilityNegotiationStatus.STALE_SESSION, report.status)
        assertEquals(CapabilityNegotiationReason.STALE_PROVIDER_GENERATION, report.reason)
        assertTrue(report.negotiatedCapabilities.isEmpty())
    }

    @Test
    fun `wrong connection session and stale connection binding are rejected`() {
        val fixture = Fixture()
        val wrongCorrelation = fixture.input(
            correlation = fixture.correlation(sessionId("99999999-9999-4999-8999-999999999999")),
        )
        val staleBinding = fixture.input(
            connections = listOf(fixture.connectionEvidence(providerGeneration = generation(0))),
        )

        assertEquals(CapabilityNegotiationStatus.STALE_SESSION, CapabilityNegotiator.negotiate(wrongCorrelation).status)
        assertEquals(CapabilityNegotiationStatus.STALE_SESSION, CapabilityNegotiator.negotiate(staleBinding).status)
    }

    @Test
    fun `provider lifecycle must explicitly enter H009 negotiating state`() {
        val fixture = Fixture()
        val connected = providerLifecycle(generation(1), negotiating = false)
        val report = CapabilityNegotiator.negotiate(fixture.input(providerLifecycle = connected))

        assertEquals(CapabilityNegotiationStatus.PROVIDER_NOT_READY, report.status)
        assertEquals(CapabilityNegotiationReason.PROVIDER_LIFECYCLE_NOT_READY, report.reason)
    }

    @Test
    fun `required connection evidence is a prerequisite even after BLE channel compatibility`() {
        val fixture = Fixture()
        val report = CapabilityNegotiator.negotiate(
            fixture.input(connections = emptyList(), correlation = fixture.correlation(null)),
        )

        assertEquals(CapabilityNegotiationStatus.MISSING_PREREQUISITE, report.status)
        assertTrue(report.checks.any { it.finding == CapabilityNegotiationFinding.CONNECTION_EVIDENCE_MISSING })
    }

    @Test
    fun `duplicate accepted descriptor declarations conflict rather than selecting`() {
        val fixture = Fixture()
        val duplicate = fixture.descriptorEvidence + CapabilityDescriptorEvidence(
            fixture.descriptor(fixture.claims.first(), maximum = 0.75),
            evidence("descriptor.duplicate.reference"),
        )
        val report = CapabilityNegotiator.negotiate(fixture.input(descriptors = duplicate))

        assertEquals(CapabilityNegotiationStatus.PARTIALLY_NEGOTIATED, report.status)
        assertTrue(report.checks.any { it.finding == CapabilityNegotiationFinding.DESCRIPTOR_CONFLICT })
    }

    @Test
    fun `runtime limits may narrow accepted envelope`() {
        val fixture = Fixture(runtimeMaximum = 0.5)
        val report = CapabilityNegotiator.negotiate(fixture.input())
        val move = report.negotiatedCapabilities.single { it.capability.id.wireValue == "mobility.move" }

        assertEquals(0.5, move.limits.single().maximum ?: error("missing maximum"), 0.0)
        assertTrue(report.checks.any { it.finding == CapabilityNegotiationFinding.RUNTIME_LIMIT_NARROWED })
    }

    @Test
    fun `runtime capability contract schema mismatch is rejected`() {
        val fixture = Fixture(runtimeContractVersion = "2.0")
        val report = CapabilityNegotiator.negotiate(fixture.input())

        assertEquals(CapabilityNegotiationStatus.CONFLICTING_DECLARATION, report.status)
        assertTrue(report.checks.any { it.finding == CapabilityNegotiationFinding.CONTRACT_MISMATCH })
    }

    @Test
    fun `runtime limit broadening is rejected and never clipped into apparent success`() {
        val fixture = Fixture(runtimeMaximum = 1.5)
        val report = CapabilityNegotiator.negotiate(fixture.input())

        assertEquals(CapabilityNegotiationStatus.PARTIALLY_NEGOTIATED, report.status)
        assertFalse(report.negotiatedCapabilities.any { it.capability.id.wireValue == "mobility.move" })
        assertTrue(report.checks.any { it.finding == CapabilityNegotiationFinding.RUNTIME_LIMIT_BROADENING })
    }

    @Test
    fun `runtime unavailable and expired evidence remain distinct`() {
        val unavailable = Fixture(runtimeAvailability = ProviderRuntimeAvailability.UNAVAILABLE)
        val expired = Fixture(runtimeExpiresAt = 40)

        assertEquals(
            CapabilityNegotiationStatus.RUNTIME_UNAVAILABLE,
            CapabilityNegotiator.negotiate(unavailable.input()).status,
        )
        assertEquals(
            CapabilityNegotiationReason.RUNTIME_EVIDENCE_EXPIRED,
            CapabilityNegotiator.negotiate(expired.input()).reason,
        )
    }

    @Test
    fun `equivalent input ordering produces equivalent ordered reports`() {
        val first = Fixture()
        val second = Fixture(claims = defaultClaims().reversed(), adapterCapabilities = defaultClaims().reversed())
        val firstReport = CapabilityNegotiator.negotiate(first.input(descriptors = first.descriptorEvidence.reversed()))
        val secondReport = CapabilityNegotiator.negotiate(second.input(descriptors = second.descriptorEvidence.reversed()))

        assertEquals(firstReport.status, secondReport.status)
        assertEquals(
            firstReport.negotiatedCapabilities.map { it.capability.wireValue },
            secondReport.negotiatedCapabilities.map { it.capability.wireValue },
        )
        assertEquals(firstReport.checks, secondReport.checks)
        assertEquals(firstReport.toString(), secondReport.toString())
    }

    @Test
    fun `multiple provider instances may negotiate the same capability without selection`() {
        val first = Fixture(instance = "11111111-1111-4111-8111-111111111111")
        val second = Fixture(instance = "22222222-2222-4222-8222-222222222222")
        val firstRecord = CapabilityNegotiator.negotiate(first.input()).negotiatedCapabilities.first()
        val secondRecord = CapabilityNegotiator.negotiate(second.input()).negotiatedCapabilities.first()

        assertEquals(firstRecord.capability, secondRecord.capability)
        assertNotEquals(firstRecord.providerInstanceId, secondRecord.providerInstanceId)
    }

    @Test
    fun `cancel and dependency failure produce evidence without runtime execution`() {
        val fixture = Fixture()
        assertEquals(CapabilityNegotiationStatus.CANCELLED, CapabilityNegotiator.cancelled(fixture.input()).status)
        assertEquals(CapabilityNegotiationStatus.FAILED, CapabilityNegotiator.dependencyFailed(fixture.input()).status)
    }

    @Test
    fun `negotiation does not mutate registry or imply trust safety feasibility active body or transport`() {
        val fixture = Fixture()
        val input = fixture.input()
        val before = input.providerRegistry.entries.toList()
        val report = CapabilityNegotiator.negotiate(input)

        assertEquals(before, input.providerRegistry.entries)
        assertEquals(CapabilityNegotiationStatus.NEGOTIATED, report.status)
        val exposed = NegotiatedCapabilityRecord::class.java.declaredFields.map { it.name.lowercase() }
        val forbiddenFields = listOf("trust", "author", "safe", "feasib", "activebody", "endpoint", "packet", "gatt")
        assertFalse(exposed.any { field -> forbiddenFields.any(field::contains) })

        val sources = listOf(
            sourceFile("capability/negotiation/CapabilityNegotiationModel.kt").readText(),
            sourceFile("capability/negotiation/CapabilityNegotiator.kt").readText(),
        ).joinToString("\n")
        val forbiddenSource = listOf(
            "android.bluetooth", "BluetoothGatt", "BluetoothDevice", "ScanResult",
            "body.looi", "FED0", "FED8", "FED9", "WorldModel", "ActiveBody",
        )
        assertTrue(forbiddenSource.filter(sources::contains).isEmpty())
    }

    private inner class Fixture(
        val claims: List<CapabilityReference> = defaultClaims(),
        val adapterCapabilities: List<CapabilityReference> = claims,
        private val runtimeStates: Map<String, CapabilityNegotiationState> = emptyMap(),
        private val runtimeMaximum: Double = 1.0,
        private val runtimeAvailability: ProviderRuntimeAvailability = ProviderRuntimeAvailability.AVAILABLE,
        private val runtimeExpiresAt: Long = 200,
        private val runtimeContractVersion: String = "1.0",
        instance: String = "11111111-1111-4111-8111-111111111111",
    ) {
        val identity = ProviderIdentity(
            providerDefinition(),
            providerInstance(instance),
            ProviderFamily.BODY_ADAPTER,
            providerSuccess(RedactedProviderAlias.parse("Reference body")),
        )
        val sessionId = sessionId("22222222-2222-4222-8222-222222222222")
        val descriptorEvidence: List<CapabilityDescriptorEvidence> = claims.map { claim ->
            CapabilityDescriptorEvidence(descriptor(claim, maximum = 1.0), evidence("descriptor.${claim.id.name.value}"))
        }
        private val runtimeDescriptors: List<CapabilityDescriptor> = claims.mapNotNull { claim ->
            if (runtimeState(claim) == CapabilityNegotiationState.SUPPORTED) {
                descriptor(
                    claim,
                    maximum = if (claim.id.wireValue == "mobility.move") runtimeMaximum else 1.0,
                    contractVersion = runtimeContractVersion,
                )
            } else {
                null
            }
        }
        private val adapterRecord = adapterRecord(adapterCapabilities)
        private val resolved = resolvedManifest(claims, identity.instanceId)
        private val prepared = preparedSnapshot(resolved, adapterRecord)

        fun correlation(session: ConnectionSessionId? = sessionId): CapabilityNegotiationCorrelation =
            CapabilityNegotiationCorrelation.create(
                negotiationId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                identity.instanceId,
                generation(1),
                listOfNotNull(session),
            )

        fun input(
            correlation: CapabilityNegotiationCorrelation = correlation(),
            providerIdentity: ProviderIdentity = identity,
            providerLifecycle: ProviderLifecycleSnapshot = providerLifecycle(generation(1), negotiating = true),
            descriptors: List<CapabilityDescriptorEvidence> = descriptorEvidence,
            connections: List<CapabilityConnectionEvidence> = listOf(connectionEvidence()),
        ): CapabilityNegotiationInput = CapabilityNegotiationInput.create(
            correlation = correlation,
            preparedProvider = prepared,
            providerIdentity = providerIdentity,
            providerLifecycle = providerLifecycle,
            providerRegistry = ProviderRegistrySnapshot.empty(),
            providerRegistryReference = evidence("registry.snapshot.current"),
            descriptorEvidence = descriptors,
            runtimeEvidence = runtimeEvidence(),
            connections = connections,
            negotiatedAt = timestamp(50),
            validUntil = timestamp(150),
        )

        fun connectionEvidence(providerGeneration: ProviderGeneration = generation(1)) = CapabilityConnectionEvidence(
            providerGeneration,
            connectedSession(sessionId),
            evidence("connection.session.current"),
        )

        fun descriptor(
            reference: CapabilityReference,
            maximum: Double,
            contractVersion: String = "1.0",
        ): CapabilityDescriptor {
            val isMove = reference.id.wireValue == "mobility.move"
            return descriptor(
                reference,
                if (isMove) listOf(limit(-1.0, maximum)) else emptyList(),
                if (isMove) CapabilityEffect.ACT else CapabilityEffect.OBSERVE,
                contractVersion,
            )
        }

        private fun runtimeEvidence(): ProviderCapabilityRuntimeEvidence {
            val request = providerSuccess(CapabilityNegotiationRequest.create(generation(1), claims))
            val entries = claims.map { claim ->
                val state = runtimeState(claim)
                CapabilityNegotiationEntry(claim, state, if (state == CapabilityNegotiationState.SUPPORTED) claim else null)
            }
            val negotiation = providerSuccess(CapabilityNegotiationResult.create(identity.instanceId, request, entries))
            val registrationRequest = CapabilityRegistrationRequest.create(generation(1))
            val registrations = providerSuccess(
                CapabilityRegistrationResult.create(
                    identity.instanceId,
                    registrationRequest,
                    runtimeDescriptors.map(::CapabilityRegistration),
                ),
            )
            val reason = when (runtimeAvailability) {
                ProviderRuntimeAvailability.AVAILABLE -> null
                ProviderRuntimeAvailability.DEGRADED -> ProviderAvailabilityReason.TEMPORARILY_UNAVAILABLE
                ProviderRuntimeAvailability.UNAVAILABLE -> ProviderAvailabilityReason.PROVIDER_FAILURE
            }
            return ProviderCapabilityRuntimeEvidence.create(
                providerSuccess(
                    ProviderRuntimeSnapshot.create(generation(1), runtimeAvailability, timestamp(40), reason),
                ),
                negotiation,
                registrations,
                timestamp(runtimeExpiresAt),
                evidence("runtime.snapshot.current"),
                evidence("runtime.negotiation.current"),
                evidence("runtime.descriptors.current"),
            )
        }

        private fun runtimeState(claim: CapabilityReference): CapabilityNegotiationState =
            runtimeStates[claim.id.wireValue] ?: CapabilityNegotiationState.SUPPORTED
    }

    private fun preparedSnapshot(
        resolved: ResolvedManifestDefinition,
        record: AdapterRegistryRecord,
    ): ProviderOrchestrationSnapshot {
        val report = CompatibilityReport(
            CompatibilityReportStatus.COMPATIBLE,
            resolved.common.manifest.provider.definitionId,
            resolved.common.manifest.provider.family,
            resolved.common.manifest.adapter.adapterId,
            record.key.adapterId,
            emptyList(),
        )
        val candidate = AdapterCompatibilityCandidate(record, report)
        return ProviderOrchestrationSnapshot(
            correlation = ProviderOrchestrationCorrelation(
                providerRunId("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                generation(1),
            ),
            phase = ProviderOrchestrationPhase.PREPARED,
            stopReason = null,
            observationIds = emptyList(),
            providerLifecycle = providerLifecycle(generation(1), negotiating = false),
            identityResolution = null,
            manifestResolution = ManifestResolution(
                ManifestResolutionStatus.RESOLVED,
                ManifestResolutionReason.EXACT_VALIDATED_DEFINITION,
                resolved.identityBinding,
                listOf(resolved.candidateId),
                resolved,
            ),
            manifestCorrelation = null,
            compatibility = ProviderCompatibilityEvaluation(
                io.redi.robot.adapter.registry.AdapterCompatibilityCandidateSet(listOf(candidate)),
                null,
            ),
            preparation = ProviderPreparationEvidence(candidate, report),
            audit = emptyList(),
        )
    }

    private fun resolvedManifest(
        capabilities: List<CapabilityReference>,
        instanceId: ProviderInstanceId,
    ): ResolvedManifestDefinition {
        val common = commonDocument(capabilities)
        val body = specializedSuccess(
            BodyManifest.create(
                schema("1.0"),
                CommonManifestBinding.from(common),
                manifestContract("body.class/mobile@1.0"),
                BodyMobilityClass.GROUND,
                emptyList(),
                null,
                BodyMountingSupport(false, emptyList()),
                BodyPowerRelationship.BATTERY_POWER,
                null,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
            ),
        )
        val specialized = specializedSuccess(
            SpecializedProviderManifestCodec.decode(SpecializedProviderManifestCodec.encode(body)),
        )
        return ResolvedManifestDefinition(
            candidateSuccess(ManifestCandidateId.parse("40000000-0000-4000-8000-000000000001")),
            ManifestCandidateProvenance(
                ManifestCandidateSource.HUGO_MAINTAINED,
                candidateSuccess(ManifestCatalogReference.parse("hugo.catalog.reference")),
            ),
            IdentityManifestBinding(providerDefinition(), ProviderFamily.BODY_ADAPTER, instanceId),
            common,
            specialized,
            AdapterCandidateAssessment(
                common.manifest.adapter.adapterId,
                AdapterCandidateAssessmentStatus.COMPATIBLE_DESCRIPTORS,
                emptyList(),
            ),
        )
    }

    private fun commonDocument(capabilities: List<CapabilityReference>): CommonProviderManifestDocument {
        val manifest = commonSuccess(
            CommonProviderManifest.create(
                schema("1.0"),
                ManifestProviderDeclaration(providerDefinition(), ProviderFamily.BODY_ADAPTER, text("Reference body"), null),
                AdapterCompatibilityReference(adapterDefinition(), range("1.0.0", "2.0.0")),
                capabilities,
                listOf(ManifestConnectionMethod(manifestContract("io.hugo.connection/bluetooth-le@1.0"), true)),
                emptyList(),
                emptyList(),
                emptyList(),
                ManifestProvenance(
                    ManifestSource.PROJECT,
                    text("HUGO project"),
                    commonSuccess(ManifestSourceReference.parse("hugo.manifest.reference")),
                    CapabilityEvidenceClass.DOCUMENTED,
                ),
            ),
        )
        return commonSuccess(CommonProviderManifestCodec.decode(CommonProviderManifestCodec.encode(manifest)))
    }

    private fun adapterRecord(capabilities: List<CapabilityReference>): AdapterRegistryRecord {
        val descriptor = adapterSuccess(
            AdapterDescriptor.create(
                adapterSuccess(AdapterSchemaVersion.parse("1.0")),
                AdapterIdentity(adapterId(), adapterVersion("1.0.0")),
                digest("ab"),
                AdapterProvenance(
                    AdapterProvenanceSource.HUGO_MAINTAINED,
                    adapterSuccess(AdapterPublisherReference.parse("io.hugo.maintainers")),
                    adapterSuccess(AdapterSourceReference.parse("hugo.adapter.reference")),
                ),
                listOf(
                    AdapterManifestSupport(
                        ProviderFamily.BODY_ADAPTER,
                        1,
                        AdapterSpecializedManifestSupport(AdapterManifestKind.BODY, 1),
                    ),
                ),
                capabilities,
                adapterSuccess(
                    HugoRuntimeCompatibility.create(runtimeVersion("1.0.0"), runtimeVersion("2.0.0")),
                ),
            ),
        )
        return registrySuccess(
            AdapterRegistryRecord.create(
                descriptor,
                evidence("adapter.descriptor.current"),
                registrySuccess(AdapterArtifactReference.parse("adapter.package.reference")),
                AdapterKnowledgeState.DEFINITION,
                listOf(connectionMethod()),
                emptyList(),
            ),
        )
    }

    private fun descriptor(
        reference: CapabilityReference,
        limits: List<CapabilityNumericLimit>,
        effect: CapabilityEffect,
        contractVersion: String,
    ): CapabilityDescriptor = descriptorSuccess(
        CapabilityDescriptor.create(
            reference,
            descriptorSuccess(CapabilityLabel.parse(reference.id.name.value)),
            descriptorSuccess(CapabilityDescription.parse("Semantic capability")),
            contract("hugo.capability.input@$contractVersion"),
            contract("hugo.capability.output@$contractVersion"),
            effect,
            if (effect == CapabilityEffect.ACT) CapabilityRiskClass.HIGH else CapabilityRiskClass.INFORMATIONAL,
            if (effect == CapabilityEffect.ACT) CapabilitySafetyClass.PHYSICAL_ACTION else CapabilitySafetyClass.NON_PHYSICAL,
            if (effect == CapabilityEffect.ACT) CapabilityPermissionIntent.ACT else CapabilityPermissionIntent.OBSERVE,
            CapabilityApprovalRequirement.EVERY_REQUEST,
            if (effect == CapabilityEffect.ACT) CapabilityCancellationSemantics.REQUIRED
            else CapabilityCancellationSemantics.NOT_APPLICABLE,
            CapabilityAvailabilitySemantics.SESSION_SCOPED,
            CapabilityEvidenceClass.AUTOMATED_TESTED,
            limits,
        ),
    )

    private fun connectedSession(sessionId: ConnectionSessionId): ConnectionLifecycleSnapshot {
        val request = connectionSuccess(
            ConnectionConnectRequest.create(
                requestId("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                connectionGeneration(7),
                connectionMethod(),
                connectionSuccess(ConnectionEndpointReference.parse("ble_endpoint.current")),
                null,
                timestamp(20),
                timestamp(100),
            ),
        )
        val connecting = ConnectionLifecycleReducer.reduce(
            ConnectionLifecycleSnapshot.initial(),
            ConnectionLifecycleEvent.ConnectRequested(request),
        ).snapshot
        return ConnectionLifecycleReducer.reduce(
            connecting,
            ConnectionLifecycleEvent.ChannelEstablished(
                request.generation,
                request.requestId,
                sessionId,
                io.redi.robot.connection.api.ConnectionMetadata(
                    request.method,
                    request.endpoint,
                    request.peerAlias,
                    timestamp(30),
                ),
            ),
        ).snapshot
    }

    private fun providerLifecycle(generation: ProviderGeneration, negotiating: Boolean): ProviderLifecycleSnapshot {
        var snapshot = ProviderLifecycleSnapshot.initial()
        snapshot = ProviderLifecycleReducer.reduce(snapshot, ProviderLifecycleEvent.Discovered(generation)).snapshot
        snapshot = ProviderLifecycleReducer.reduce(snapshot, ProviderLifecycleEvent.IdentityResolved(generation)).snapshot
        snapshot = ProviderLifecycleReducer.reduce(snapshot, ProviderLifecycleEvent.ConnectRequested(generation)).snapshot
        snapshot = ProviderLifecycleReducer.reduce(snapshot, ProviderLifecycleEvent.ConnectionEstablished(generation)).snapshot
        if (negotiating) {
            snapshot = ProviderLifecycleReducer.reduce(snapshot, ProviderLifecycleEvent.NegotiationRequested(generation)).snapshot
        }
        return snapshot
    }

    private fun defaultClaims() = listOf(capability("mobility.move@1.0"), capability("body.light.control@1.0"))
    private fun capability(raw: String): CapabilityReference = identitySuccess(CapabilityReference.parse(raw))
    private fun providerDefinition(): ProviderDefinitionId = providerSuccess(ProviderDefinitionId.parse("hugo.body/reference"))
    private fun providerInstance(raw: String): ProviderInstanceId = providerSuccess(ProviderInstanceId.parse(raw))
    private fun generation(value: Long): ProviderGeneration = providerSuccess(ProviderGeneration.create(value))
    private fun negotiationId(raw: String): CapabilityNegotiationId = negotiationSuccess(CapabilityNegotiationId.parse(raw))
    private fun providerRunId(raw: String): ProviderOrchestrationRunId = orchestrationSuccess(ProviderOrchestrationRunId.parse(raw))
    private fun sessionId(raw: String): ConnectionSessionId = connectionSuccess(ConnectionSessionId.parse(raw))
    private fun requestId(raw: String): ConnectionRequestId = connectionSuccess(ConnectionRequestId.parse(raw))
    private fun connectionGeneration(value: Long): ConnectionGeneration = connectionSuccess(ConnectionGeneration.create(value))
    private fun connectionMethod(): ConnectionMethodReference = ConnectionMethodReference(
        connectionSuccess(ConnectionMethodId.parse("io.hugo.connection/bluetooth-le")),
        connectionSuccess(ConnectionMethodVersion.parse("1.0")),
    )
    private fun contract(raw: String): CapabilityContractReference = descriptorSuccess(CapabilityContractReference.parse(raw))
    private fun limit(minimum: Double, maximum: Double): CapabilityNumericLimit = descriptorSuccess(
        CapabilityNumericLimit.create(
            descriptorSuccess(CapabilityLimitKey.parse("normalized_speed")),
            minimum,
            maximum,
            descriptorSuccess(CapabilityUnit.parse("ratio")),
        ),
    )
    private fun adapterId(): AdapterId = adapterSuccess(AdapterId.parse("io.hugo.adapter/reference"))
    private fun adapterVersion(raw: String): AdapterVersion = adapterSuccess(AdapterVersion.parse(raw))
    private fun digest(pair: String): AdapterPackageDigest = adapterSuccess(
        AdapterPackageDigest.parse("sha256:${pair.repeat(32)}"),
    )
    private fun runtimeVersion(raw: String): HugoRuntimeVersion = adapterSuccess(HugoRuntimeVersion.parse(raw))
    private fun adapterDefinition(): AdapterDefinitionReference = commonSuccess(
        AdapterDefinitionReference.parse("io.hugo.adapter/reference"),
    )
    private fun schema(raw: String): ManifestSchemaVersion = commonSuccess(ManifestSchemaVersion.parse(raw))
    private fun manifestContract(raw: String): ManifestContractReference = commonSuccess(ManifestContractReference.parse(raw))
    private fun text(raw: String): ManifestText = commonSuccess(ManifestText.parse(raw))
    private fun range(minimum: String, maximum: String): ManifestVersionRange = commonSuccess(
        ManifestVersionRange.create(
            commonSuccess(ManifestVersion.parse(minimum)),
            commonSuccess(ManifestVersion.parse(maximum)),
        ),
    )
    private fun evidence(raw: String): CompatibilityEvidenceReference = compatibilitySuccess(
        CompatibilityEvidenceReference.parse(raw),
    )

    private fun timestamp(value: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(value)) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> throw AssertionError(result.error)
    }

    private fun sourceFile(relativePath: String): File {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"), File("../app/src/main/java"))
        return roots.map { File(it, "io/redi/robot/$relativePath") }.first { it.isFile }
    }

    private fun <T> identitySuccess(result: CapabilityIdentityResult<T>): T = when (result) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> descriptorSuccess(result: CapabilityDescriptorResult<T>): T = when (result) {
        is CapabilityDescriptorResult.Success -> result.value
        is CapabilityDescriptorResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> providerSuccess(result: CapabilityProviderPortResult<T>): T = when (result) {
        is CapabilityProviderPortResult.Success -> result.value
        is CapabilityProviderPortResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> providerSuccess(result: ProviderIdentityResult<T>): T = when (result) {
        is ProviderIdentityResult.Success -> result.value
        is ProviderIdentityResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> adapterSuccess(result: AdapterResult<T>): T = when (result) {
        is AdapterResult.Success -> result.value
        is AdapterResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> registrySuccess(result: AdapterRegistryModelResult<T>): T = when (result) {
        is AdapterRegistryModelResult.Success -> result.value
        is AdapterRegistryModelResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> connectionSuccess(result: ConnectionResult<T>): T = when (result) {
        is ConnectionResult.Success -> result.value
        is ConnectionResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> commonSuccess(result: CommonManifestResult<T>): T = when (result) {
        is CommonManifestResult.Success -> result.value
        is CommonManifestResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> specializedSuccess(result: SpecializedManifestResult<T>): T = when (result) {
        is SpecializedManifestResult.Success -> result.value
        is SpecializedManifestResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> candidateSuccess(result: ManifestCandidateResult<T>): T = when (result) {
        is ManifestCandidateResult.Success -> result.value
        is ManifestCandidateResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> compatibilitySuccess(result: CompatibilityInputResult<T>): T = when (result) {
        is CompatibilityInputResult.Success -> result.value
        is CompatibilityInputResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> negotiationSuccess(result: CapabilityNegotiationModelResult<T>): T = when (result) {
        is CapabilityNegotiationModelResult.Success -> result.value
        is CapabilityNegotiationModelResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> orchestrationSuccess(result: io.redi.robot.provider.runtime.ProviderOrchestrationModelResult<T>): T =
        when (result) {
            is io.redi.robot.provider.runtime.ProviderOrchestrationModelResult.Success -> result.value
            is io.redi.robot.provider.runtime.ProviderOrchestrationModelResult.Failure -> throw AssertionError(result.error)
        }
}
