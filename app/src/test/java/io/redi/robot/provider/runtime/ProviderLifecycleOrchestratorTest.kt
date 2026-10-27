package io.redi.robot.provider.runtime

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
import io.redi.robot.adapter.compatibility.CompatibilityReportStatus
import io.redi.robot.adapter.compatibility.HugoRuntimeEvidence
import io.redi.robot.adapter.registry.AdapterArtifactReference
import io.redi.robot.adapter.registry.AdapterKnowledgeState
import io.redi.robot.adapter.registry.AdapterRegistry
import io.redi.robot.adapter.registry.AdapterRegistryModelResult
import io.redi.robot.adapter.registry.AdapterRegistryMutationResult
import io.redi.robot.adapter.registry.AdapterRegistryRecord
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.CapabilityProviderPortResult
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleState
import io.redi.robot.capability.registry.InMemoryProviderRegistry
import io.redi.robot.discovery.api.DiscoveryConfidence
import io.redi.robot.discovery.api.DiscoveryEvidence
import io.redi.robot.discovery.api.DiscoveryEvidenceKind
import io.redi.robot.discovery.api.DiscoveryHostReference
import io.redi.robot.discovery.api.DiscoveryObservation
import io.redi.robot.discovery.api.DiscoveryObservationId
import io.redi.robot.discovery.api.DiscoveryObservationResult
import io.redi.robot.discovery.api.DiscoveryPrivacyClassification
import io.redi.robot.discovery.api.DiscoverySourceType
import io.redi.robot.discovery.identity.IdentityResolution
import io.redi.robot.discovery.identity.IdentityResolutionReason
import io.redi.robot.discovery.identity.IdentityResolutionRequest
import io.redi.robot.discovery.identity.IdentityResolutionStatus
import io.redi.robot.discovery.identity.KnownProviderIdentityCandidate
import io.redi.robot.discovery.identity.NewProviderIdentityCandidate
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
import io.redi.robot.manifest.domain.ManifestContractReference
import io.redi.robot.manifest.domain.ManifestProvenance
import io.redi.robot.manifest.domain.ManifestProviderDeclaration
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLifecycleOrchestratorTest {
    @Test
    fun `happy path deterministically prepares one compatible known provider without connecting`() {
        val fixture = fixture(identity = knownIdentityResolution())
        val providerRegistry = InMemoryProviderRegistry()
        val request = fixture.request(providerRegistry = providerRegistry)
        val before = providerRegistry.snapshot().entries
        val orchestrator = fixture.orchestrator()

        val completed = runToTerminal(orchestrator, request)

        assertEquals(ProviderOrchestrationPhase.PREPARED, completed.phase)
        assertEquals(CompatibilityReportStatus.COMPATIBLE, completed.preparation?.report?.status)
        assertTrue(completed.providerLifecycle.state is ProviderLifecycleState.Identified)
        assertEquals(before, providerRegistry.snapshot().entries)
        assertNotNull(completed.manifestCorrelation)
        assertEquals(
            listOf(
                ProviderOrchestrationPhase.OBSERVED,
                ProviderOrchestrationPhase.RESOLVING_IDENTITY,
                ProviderOrchestrationPhase.IDENTITY_RESOLVED,
                ProviderOrchestrationPhase.RESOLVING_MANIFEST,
                ProviderOrchestrationPhase.MANIFEST_RESOLVED,
                ProviderOrchestrationPhase.CHECKING_COMPATIBILITY,
                ProviderOrchestrationPhase.PREPARED,
                ProviderOrchestrationPhase.PREPARED,
            ),
            completed.audit.map { it.phase },
        )
    }

    @Test
    fun `new provider candidate reaches preparation without inventing instance identity`() {
        val fixture = fixture(identity = newIdentityResolution())

        val result = runToTerminal(fixture.orchestrator(), fixture.request())

        assertEquals(ProviderOrchestrationPhase.PREPARED, result.phase)
        assertNull(result.manifestResolution?.resolved?.identityBinding?.instanceId)
        assertEquals(IdentityResolutionStatus.NEW_PROVIDER_CANDIDATE, result.identityResolution?.status)
    }

    @Test
    fun `ambiguous conflicting and stale identity outcomes stop distinctly`() {
        val cases = listOf(
            identityOutcome(IdentityResolutionStatus.AMBIGUOUS, IdentityResolutionReason.MULTIPLE_PLAUSIBLE_CANDIDATES) to
                (ProviderOrchestrationPhase.AMBIGUOUS to ProviderOrchestrationStopReason.AMBIGUOUS_IDENTITY),
            identityOutcome(IdentityResolutionStatus.CONFLICTING_EVIDENCE, IdentityResolutionReason.STRONG_EVIDENCE_DISAGREES) to
                (ProviderOrchestrationPhase.BLOCKED to ProviderOrchestrationStopReason.CONFLICTING_IDENTITY_EVIDENCE),
            identityOutcome(IdentityResolutionStatus.STALE_EVIDENCE, IdentityResolutionReason.NO_FRESH_EVIDENCE) to
                (ProviderOrchestrationPhase.BLOCKED to ProviderOrchestrationStopReason.STALE_OBSERVATION),
        )

        cases.forEach { (identity, expected) ->
            val fixture = fixture(identity = identity)
            val orchestrator = fixture.orchestrator()
            val request = fixture.request()
            orchestrator.start(request)
            orchestrator.beginIdentity(request.correlation)
            val result = orchestrator.runIdentity(request.correlation).snapshot
            assertEquals(expected.first, result.phase)
            assertEquals(expected.second, result.stopReason)
            assertNull(result.manifestResolution)
        }
    }

    @Test
    fun `missing and conflicting manifests stop before compatibility`() {
        val cases = listOf(
            manifestOutcome(ManifestResolutionStatus.NOT_FOUND, ManifestResolutionReason.NO_MANIFEST_CANDIDATES) to
                ProviderOrchestrationStopReason.MANIFEST_NOT_FOUND,
            manifestOutcome(ManifestResolutionStatus.CONFLICTING_MANIFESTS, ManifestResolutionReason.MANIFEST_CLAIMS_CONFLICT) to
                ProviderOrchestrationStopReason.CONFLICTING_MANIFESTS,
        )
        cases.forEach { (manifest, reason) ->
            val fixture = fixture(manifest = manifest)
            val orchestrator = fixture.orchestrator()
            val request = fixture.request()
            advanceTo(orchestrator, request, ProviderOrchestrationPhase.RESOLVING_MANIFEST)
            val result = orchestrator.runManifest(request.correlation).snapshot

            assertEquals(ProviderOrchestrationPhase.BLOCKED, result.phase)
            assertEquals(reason, result.stopReason)
            assertNull(result.compatibility)
        }
    }

    @Test
    fun `missing adapter is a deterministic development need rather than generic failure`() {
        val fixture = fixture(adapterRegistry = AdapterRegistry())

        val result = runToTerminal(fixture.orchestrator(), fixture.request())

        assertEquals(ProviderOrchestrationPhase.NEEDS_ADAPTER, result.phase)
        assertEquals(ProviderOrchestrationStopReason.MISSING_ADAPTER, result.stopReason)
        assertEquals(CompatibilityReportStatus.MISSING_ADAPTER, result.compatibility?.missingAdapterReport?.status)
        assertTrue(result.providerLifecycle.state is ProviderLifecycleState.Identified)
    }

    @Test
    fun `incompatible runtime blocks without trying another adapter or connection`() {
        val fixture = fixture(runtime = "3.0.0")

        val result = runToTerminal(fixture.orchestrator(), fixture.request())

        assertEquals(ProviderOrchestrationPhase.BLOCKED, result.phase)
        assertEquals(ProviderOrchestrationStopReason.INCOMPATIBLE_ADAPTER_OR_RUNTIME, result.stopReason)
        assertTrue(result.compatibility?.candidates?.candidates?.single()?.report?.status == CompatibilityReportStatus.INCOMPATIBLE)
        assertNull(result.preparation)
    }

    @Test
    fun `multiple compatible adapter versions remain ambiguous and unselected`() {
        val registry = AdapterRegistry()
        register(registry, adapterRecord("1.1.0"))
        register(registry, adapterRecord("1.2.0"))
        val fixture = fixture(adapterRegistry = registry)

        val result = runToTerminal(fixture.orchestrator(), fixture.request())

        assertEquals(ProviderOrchestrationPhase.AMBIGUOUS, result.phase)
        assertEquals(ProviderOrchestrationStopReason.MULTIPLE_COMPATIBLE_ADAPTERS, result.stopReason)
        assertNull(result.preparation)
    }

    @Test
    fun `cancellation is deterministic and idempotent at every major preparation stage`() {
        val phases = listOf(
            ProviderOrchestrationPhase.OBSERVED,
            ProviderOrchestrationPhase.RESOLVING_IDENTITY,
            ProviderOrchestrationPhase.IDENTITY_RESOLVED,
            ProviderOrchestrationPhase.RESOLVING_MANIFEST,
            ProviderOrchestrationPhase.MANIFEST_RESOLVED,
            ProviderOrchestrationPhase.CHECKING_COMPATIBILITY,
        )
        phases.forEach { phase ->
            val fixture = fixture()
            val orchestrator = fixture.orchestrator()
            val request = fixture.request()
            advanceTo(orchestrator, request, phase)

            val cancelled = orchestrator.cancel(request.correlation)
            val duplicate = orchestrator.cancel(request.correlation)

            assertEquals(ProviderOrchestrationPhase.CANCELLED, cancelled.snapshot.phase)
            assertTrue(cancelled.snapshot.providerLifecycle.state is ProviderLifecycleState.Cancelled)
            assertEquals(
                ProviderOrchestrationIgnoredReason.DUPLICATE_EVENT,
                (duplicate.disposition as ProviderOrchestrationDisposition.Ignored).reason,
            )
        }
    }

    @Test
    fun `late old-run and duplicate stage results cannot advance current run`() {
        val fixture = fixture()
        val orchestrator = fixture.orchestrator()
        val first = fixture.request(generation = 1L, runSuffix = "1")
        orchestrator.start(first)
        orchestrator.beginIdentity(first.correlation)
        orchestrator.cancel(first.correlation)
        val second = fixture.request(generation = 2L, runSuffix = "2")
        orchestrator.start(second)
        orchestrator.beginIdentity(second.correlation)

        val stale = orchestrator.acceptIdentity(first.correlation, knownIdentityResolution())
        val accepted = orchestrator.acceptIdentity(second.correlation, knownIdentityResolution())
        val duplicate = orchestrator.acceptIdentity(second.correlation, knownIdentityResolution())

        assertEquals(ProviderOrchestrationIgnoredReason.STALE_GENERATION, ignoredReason(stale))
        assertEquals(ProviderOrchestrationPhase.IDENTITY_RESOLVED, accepted.snapshot.phase)
        assertEquals(ProviderOrchestrationIgnoredReason.DUPLICATE_EVENT, ignoredReason(duplicate))
        assertEquals(second.correlation, orchestrator.snapshot().correlation)
    }

    @Test
    fun `dependency failure is typed and has no hidden retry`() {
        var calls = 0
        val fixture = fixture()
        val orchestrator = ProviderLifecycleOrchestrator(
            identityStage = ProviderIdentityResolutionStage { _, _ ->
                calls += 1
                error("redacted dependency failure")
            },
            manifestStage = fixture.manifestStage,
            compatibilityStage = CanonicalProviderCompatibilityStage,
        )
        val request = fixture.request()
        orchestrator.start(request)
        orchestrator.beginIdentity(request.correlation)

        val result = orchestrator.runIdentity(request.correlation).snapshot

        assertEquals(ProviderOrchestrationPhase.FAILED, result.phase)
        assertEquals(ProviderOrchestrationStopReason.DEPENDENCY_FAILURE, result.stopReason)
        assertEquals(1, calls)
    }

    @Test
    fun `equivalent orchestration inputs produce equivalent ordered audit outcomes`() {
        val fixture = fixture()
        val request = fixture.request()
        val first = runToTerminal(fixture.orchestrator(), request)
        val second = runToTerminal(fixture.orchestrator(), request)

        assertEquals(first.phase, second.phase)
        assertEquals(first.stopReason, second.stopReason)
        assertEquals(first.audit, second.audit)
        assertEquals(first.preparation?.report?.view(), second.preparation?.report?.view())
    }

    @Test
    fun `orchestration types imply no trust permission connection registration active body or vendor runtime`() {
        val fields = ProviderOrchestrationSnapshot::class.java.declaredFields.map { it.name.lowercase() }
        val methods = ProviderLifecycleOrchestrator::class.java.methods.map { it.name.lowercase() }
        val forbidden = listOf(
            "trust",
            "permission",
            "authoriz",
            "connect",
            "registerprovider",
            "activebody",
            "execute",
            "bluetooth",
            "gatt",
            "looi",
        )

        assertTrue(forbidden.none { word -> fields.any { word in it } || methods.any { word in it } })
        assertFalse(ProviderLifecycleOrchestrator::class.java.name.lowercase().contains("vendor"))
    }

    private fun fixture(
        identity: IdentityResolution = knownIdentityResolution(),
        manifest: ManifestResolution? = null,
        adapterRegistry: AdapterRegistry = AdapterRegistry().also { register(it, adapterRecord("1.2.0")) },
        runtime: String = "1.5.0",
    ): Fixture {
        val resolved = resolvedManifest()
        val manifestResult = manifest ?: resolvedManifestOutcome(resolved)
        return Fixture(
            identityStage = ProviderIdentityResolutionStage { _, _ -> identity },
            manifestStage = ProviderManifestResolutionStage { _, _ -> manifestResult },
            adapterRegistry = adapterRegistry,
            runtime = runtime,
        )
    }

    private inner class Fixture(
        val identityStage: ProviderIdentityResolutionStage,
        val manifestStage: ProviderManifestResolutionStage,
        val adapterRegistry: AdapterRegistry,
        val runtime: String,
    ) {
        fun orchestrator(): ProviderLifecycleOrchestrator = ProviderLifecycleOrchestrator(
            identityStage,
            manifestStage,
            CanonicalProviderCompatibilityStage,
        )

        fun request(
            generation: Long = 1L,
            runSuffix: String = "1",
            providerRegistry: InMemoryProviderRegistry = InMemoryProviderRegistry(),
        ): ProviderOrchestrationRequest = ProviderOrchestrationRequest.create(
            correlation = correlation(generation, runSuffix),
            identityRequest = identityRequest(),
            providerRegistrySnapshot = providerRegistry.snapshot(),
            adapterRegistrySnapshot = adapterRegistry.snapshot(),
            manifestCandidates = emptyList(),
            hugoRuntime = HugoRuntimeEvidence(runtimeVersion(runtime), evidence("test.runtime.current")),
            providerVersions = emptyList(),
        )
    }

    private fun runToTerminal(
        orchestrator: ProviderLifecycleOrchestrator,
        request: ProviderOrchestrationRequest,
    ): ProviderOrchestrationSnapshot {
        advanceTo(orchestrator, request, ProviderOrchestrationPhase.CHECKING_COMPATIBILITY)
        return orchestrator.runCompatibility(request.correlation).snapshot
    }

    private fun advanceTo(
        orchestrator: ProviderLifecycleOrchestrator,
        request: ProviderOrchestrationRequest,
        target: ProviderOrchestrationPhase,
    ) {
        if (orchestrator.snapshot().phase == ProviderOrchestrationPhase.INACTIVE) orchestrator.start(request)
        if (target == ProviderOrchestrationPhase.OBSERVED) return
        if (orchestrator.snapshot().phase == ProviderOrchestrationPhase.OBSERVED) orchestrator.beginIdentity(request.correlation)
        if (target == ProviderOrchestrationPhase.RESOLVING_IDENTITY) return
        if (orchestrator.snapshot().phase == ProviderOrchestrationPhase.RESOLVING_IDENTITY) orchestrator.runIdentity(request.correlation)
        if (target == ProviderOrchestrationPhase.IDENTITY_RESOLVED) return
        if (orchestrator.snapshot().phase == ProviderOrchestrationPhase.IDENTITY_RESOLVED) orchestrator.beginManifest(request.correlation)
        if (target == ProviderOrchestrationPhase.RESOLVING_MANIFEST) return
        if (orchestrator.snapshot().phase == ProviderOrchestrationPhase.RESOLVING_MANIFEST) orchestrator.runManifest(request.correlation)
        if (target == ProviderOrchestrationPhase.MANIFEST_RESOLVED) return
        if (orchestrator.snapshot().phase == ProviderOrchestrationPhase.MANIFEST_RESOLVED) {
            orchestrator.beginCompatibility(request.correlation)
        }
    }

    private fun knownIdentityResolution(): IdentityResolution {
        val identity = ProviderIdentity(
            definitionId(),
            providerSuccess(ProviderInstanceId.parse("10000000-0000-0000-0000-000000000001")),
            ProviderFamily.BODY_ADAPTER,
            providerSuccess(RedactedProviderAlias.parse("Reference body")),
        )
        return IdentityResolution(
            IdentityResolutionStatus.MATCHED_KNOWN_PROVIDER,
            IdentityResolutionReason.STRONG_VERIFIED_INSTANCE_EVIDENCE,
            setOf(observationId()),
            listOf(KnownProviderIdentityCandidate(identity, emptySet())),
            emptySet(),
        )
    }

    private fun newIdentityResolution(): IdentityResolution = IdentityResolution(
        IdentityResolutionStatus.NEW_PROVIDER_CANDIDATE,
        IdentityResolutionReason.DEFINITION_CANDIDATE_ONLY,
        setOf(observationId()),
        listOf(NewProviderIdentityCandidate(definitionId(), ProviderFamily.BODY_ADAPTER, null, emptySet())),
        emptySet(),
    )

    private fun identityOutcome(
        status: IdentityResolutionStatus,
        reason: IdentityResolutionReason,
    ): IdentityResolution = IdentityResolution(status, reason, setOf(observationId()), emptyList(), emptySet())

    private fun resolvedManifestOutcome(resolved: ResolvedManifestDefinition): ManifestResolution = ManifestResolution(
        ManifestResolutionStatus.RESOLVED,
        ManifestResolutionReason.EXACT_VALIDATED_DEFINITION,
        resolved.identityBinding,
        listOf(resolved.candidateId),
        resolved,
    )

    private fun manifestOutcome(
        status: ManifestResolutionStatus,
        reason: ManifestResolutionReason,
    ): ManifestResolution = ManifestResolution(status, reason, null, emptyList(), null)

    private fun resolvedManifest(): ResolvedManifestDefinition {
        val common = commonDocument()
        val body = specializedSuccess(
            BodyManifest.create(
                schema(),
                CommonManifestBinding.from(common),
                contract("body.class/mobile@1.0"),
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
        val binding = IdentityManifestBinding(definitionId(), ProviderFamily.BODY_ADAPTER, null)
        return ResolvedManifestDefinition(
            candidateId(),
            ManifestCandidateProvenance(ManifestCandidateSource.HUGO_MAINTAINED, catalogReference()),
            binding,
            common,
            specialized,
            AdapterCandidateAssessment(
                adapterDefinition(),
                AdapterCandidateAssessmentStatus.NO_MATCHING_DESCRIPTOR,
                emptyList(),
            ),
        )
    }

    private fun commonDocument(): CommonProviderManifestDocument {
        val manifest = commonSuccess(
            CommonProviderManifest.create(
                schema(),
                ManifestProviderDeclaration(definitionId(), ProviderFamily.BODY_ADAPTER, text("Reference body"), null),
                AdapterCompatibilityReference(adapterDefinition(), range("1.0.0", "2.0.0")),
                listOf(capability()),
                emptyList(),
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

    private fun adapterRecord(version: String): AdapterRegistryRecord = registrySuccess(
        AdapterRegistryRecord.create(
            adapterDescriptor(version),
            evidence("test.adapter.$version"),
            registrySuccess(AdapterArtifactReference.parse("test.package.$version")),
            AdapterKnowledgeState.DEFINITION,
            emptyList(),
            emptyList(),
        ),
    )

    private fun adapterDescriptor(version: String): AdapterDescriptor = adapterSuccess(
        AdapterDescriptor.create(
            adapterSuccess(AdapterSchemaVersion.parse("1.0")),
            AdapterIdentity(adapterId(), adapterVersion(version)),
            adapterSuccess(AdapterPackageDigest.parse("sha256:${"ab".repeat(32)}")),
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
            listOf(capability()),
            adapterSuccess(HugoRuntimeCompatibility.create(runtimeVersion("1.0.0"), runtimeVersion("2.0.0"))),
        ),
    )

    private fun identityRequest(): IdentityResolutionRequest {
        val host = discoverySuccess(DiscoveryHostReference.parse("host_ref"))
        return IdentityResolutionRequest.create(
            observations = listOf(observation(host)),
            evidence = emptyList(),
            evaluationTimes = mapOf(host to time(2L)),
        )
    }

    private fun observation(host: DiscoveryHostReference): DiscoveryObservation = discoverySuccess(
        DiscoveryObservation.create(
            observationId(),
            discoverySuccess(DiscoverySourceType.parse("local.test")),
            host,
            time(1L),
            time(10L),
            null,
            ProviderFamily.BODY_ADAPTER,
            emptyList(),
            listOf(DiscoveryEvidence(discoverySuccess(DiscoveryEvidenceKind.parse("provider_hint")), time(1L), null)),
            discoverySuccess(DiscoveryConfidence.create(1.0)),
            DiscoveryPrivacyClassification.PRIVATE,
            null,
            emptyMap(),
        ),
    )

    private fun correlation(generation: Long, suffix: String): ProviderOrchestrationCorrelation =
        ProviderOrchestrationCorrelation(
            runId = orchestrationSuccess(
                ProviderOrchestrationRunId.parse("20000000-0000-0000-0000-00000000000$suffix"),
            ),
            generation = generation(generation),
        )

    private fun ignoredReason(update: ProviderOrchestrationUpdate): ProviderOrchestrationIgnoredReason =
        (update.disposition as ProviderOrchestrationDisposition.Ignored).reason

    private fun register(registry: AdapterRegistry, record: AdapterRegistryRecord) {
        check(registry.register(record, registry.snapshot().revision) is AdapterRegistryMutationResult.Registered)
    }

    private fun evidence(raw: String): CompatibilityEvidenceReference = when (
        val result = CompatibilityEvidenceReference.parse(raw)
    ) {
        is CompatibilityInputResult.Success -> result.value
        is CompatibilityInputResult.Failure -> throw AssertionError(result.error)
    }
    private fun observationId(): DiscoveryObservationId = discoverySuccess(
        DiscoveryObservationId.parse("30000000-0000-0000-0000-000000000001"),
    )
    private fun time(value: Long): MonotonicTimestampMs = envelopeSuccess(MonotonicTimestampMs.create(value))
    private fun generation(value: Long): ProviderGeneration = providerPortSuccess(ProviderGeneration.create(value))
    private fun definitionId(): ProviderDefinitionId = providerSuccess(ProviderDefinitionId.parse("hugo.body/reference"))
    private fun adapterId(): AdapterId = adapterSuccess(AdapterId.parse("io.hugo.adapter/reference"))
    private fun adapterVersion(raw: String): AdapterVersion = adapterSuccess(AdapterVersion.parse(raw))
    private fun runtimeVersion(raw: String): HugoRuntimeVersion = adapterSuccess(HugoRuntimeVersion.parse(raw))
    private fun capability(): CapabilityReference = when (val result = CapabilityReference.parse("body.move@1.0")) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> throw AssertionError(result.error)
    }
    private fun schema(): ManifestSchemaVersion = commonSuccess(ManifestSchemaVersion.parse("1.0"))
    private fun adapterDefinition(): AdapterDefinitionReference = commonSuccess(
        AdapterDefinitionReference.parse("io.hugo.adapter/reference"),
    )
    private fun contract(raw: String): ManifestContractReference = commonSuccess(ManifestContractReference.parse(raw))
    private fun text(raw: String): ManifestText = commonSuccess(ManifestText.parse(raw))
    private fun range(minimum: String, maximum: String): ManifestVersionRange = commonSuccess(
        ManifestVersionRange.create(
            commonSuccess(ManifestVersion.parse(minimum)),
            commonSuccess(ManifestVersion.parse(maximum)),
        ),
    )
    private fun candidateId(): ManifestCandidateId = candidateSuccess(
        ManifestCandidateId.parse("40000000-0000-0000-0000-000000000001"),
    )
    private fun catalogReference(): ManifestCatalogReference = candidateSuccess(
        ManifestCatalogReference.parse("hugo.catalog.reference"),
    )

    private fun <T> orchestrationSuccess(result: ProviderOrchestrationModelResult<T>): T = when (result) {
        is ProviderOrchestrationModelResult.Success -> result.value
        is ProviderOrchestrationModelResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> registrySuccess(result: AdapterRegistryModelResult<T>): T = when (result) {
        is AdapterRegistryModelResult.Success -> result.value
        is AdapterRegistryModelResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> adapterSuccess(result: AdapterResult<T>): T = when (result) {
        is AdapterResult.Success -> result.value
        is AdapterResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> providerSuccess(result: ProviderIdentityResult<T>): T = when (result) {
        is ProviderIdentityResult.Success -> result.value
        is ProviderIdentityResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> providerPortSuccess(result: CapabilityProviderPortResult<T>): T = when (result) {
        is CapabilityProviderPortResult.Success -> result.value
        is CapabilityProviderPortResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> envelopeSuccess(result: CapabilityEnvelopeResult<T>): T = when (result) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> discoverySuccess(result: DiscoveryObservationResult<T>): T = when (result) {
        is DiscoveryObservationResult.Success -> result.value
        is DiscoveryObservationResult.Failure -> throw AssertionError(result.error)
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
}
