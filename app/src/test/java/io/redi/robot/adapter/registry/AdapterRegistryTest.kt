package io.redi.robot.adapter.registry

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
import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.connection.api.ConnectionMethodId
import io.redi.robot.connection.api.ConnectionMethodReference
import io.redi.robot.connection.api.ConnectionMethodVersion
import io.redi.robot.connection.api.ConnectionResult
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
import io.redi.robot.manifest.domain.ManifestDigest
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
import io.redi.robot.manifest.resolution.ResolvedManifestDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class AdapterRegistryTest {
    @Test
    fun `valid adapter registers and exact lookup returns knowledge record`() {
        val registry = AdapterRegistry()
        val record = record()

        val result = registry.register(record, AdapterRegistryRevision.INITIAL)

        assertTrue(result is AdapterRegistryMutationResult.Registered)
        assertEquals(record, registry.exact(record.key))
        assertEquals(1L, registry.snapshot().revision.value)
    }

    @Test
    fun `multiple versions coexist and queries are deterministic`() {
        val registry = AdapterRegistry()
        val v2 = record(version = "2.0.0", capability = "body.speak@1.0")
        val v1 = record(version = "1.0.0")
        registry.register(v2, registry.snapshot().revision)
        registry.register(v1, registry.snapshot().revision)

        val versions = registry.versions(adapterId())

        assertEquals(listOf("1.0.0", "2.0.0"), versions.map { it.key.version.wireValue })
        assertEquals(versions, registry.snapshot().records)
    }

    @Test
    fun `duplicate exact registration does not change revision`() {
        val registry = AdapterRegistry()
        val record = record()
        registry.register(record, registry.snapshot().revision)
        val revision = registry.snapshot().revision

        val duplicate = registry.register(record(), revision)

        assertTrue(duplicate is AdapterRegistryMutationResult.Duplicate)
        assertEquals(revision, registry.snapshot().revision)
        assertEquals(1, registry.snapshot().records.size)
    }

    @Test
    fun `same identity and version with conflicting digest is retained as conflict`() {
        val registry = AdapterRegistry()
        val first = record(digestPair = "ab")
        registry.register(first, registry.snapshot().revision)

        val conflict = registry.register(record(digestPair = "cd"), registry.snapshot().revision)

        assertTrue(conflict is AdapterRegistryMutationResult.Conflict)
        assertEquals(AdapterRegistryConflict.PACKAGE_DIGEST, (conflict as AdapterRegistryMutationResult.Conflict).reason)
        assertEquals(first.packageDigest, registry.exact(first.key)?.packageDigest)
    }

    @Test
    fun `same identity version and digest with conflicting descriptor is rejected`() {
        val registry = AdapterRegistry()
        val first = record(capability = "body.move@1.0")
        registry.register(first, registry.snapshot().revision)

        val conflict = registry.register(
            record(capability = "body.speak@1.0"),
            registry.snapshot().revision,
        )

        assertTrue(conflict is AdapterRegistryMutationResult.Conflict)
        assertEquals(AdapterRegistryConflict.DESCRIPTOR, (conflict as AdapterRegistryMutationResult.Conflict).reason)
        assertEquals(listOf(capability("body.move@1.0")), registry.exact(first.key)?.descriptor?.supportedCapabilities)
    }

    @Test
    fun `provider family and exact capability queries retain all matching versions`() {
        val registry = AdapterRegistry()
        val body = record(version = "1.0.0")
        val device = record(
            version = "1.0.0",
            id = "io.hugo.adapter/device",
            family = ProviderFamily.DEVICE_ADAPTER,
            capability = "device.status@1.0",
        )
        registry.register(device, registry.snapshot().revision)
        registry.register(body, registry.snapshot().revision)

        assertEquals(listOf(body.key), registry.byProviderFamily(ProviderFamily.BODY_ADAPTER).map { it.key })
        assertEquals(listOf(body.key), registry.byCapability(capability("body.move@1.0")).map { it.key })
        assertTrue(registry.byCapability(capability("body.move@2.0")).isEmpty())
    }

    @Test
    fun `manifest requirement query delegates candidate evaluation to H024`() {
        val registry = AdapterRegistry()
        val compatible = record(version = "1.2.0")
        val incompatible = record(version = "2.0.0")
        registry.register(incompatible, registry.snapshot().revision)
        registry.register(compatible, registry.snapshot().revision)

        val candidates = registry.queryManifestCompatibility(
            AdapterManifestRegistryQuery(
                resolvedManifest = resolvedManifest(),
                hugoRuntime = HugoRuntimeEvidence(runtimeVersion("1.5.0"), evidence("test.runtime.current")),
                providerVersions = emptyList(),
            ),
        )

        assertEquals(2, candidates.candidates.size)
        assertEquals(listOf(compatible.key), candidates.satisfyingCandidates.map { it.record.key })
        assertEquals(CompatibilityReportStatus.COMPATIBLE, candidates.satisfyingCandidates.single().report.status)
        assertEquals(CompatibilityReportStatus.INCOMPATIBLE, candidates.candidates.last().report.status)
    }

    @Test
    fun `stale revision cannot update and explicit replace uses identity and digest compare-and-set`() {
        val registry = AdapterRegistry()
        val first = record()
        registry.register(first, registry.snapshot().revision)
        val current = registry.snapshot().revision
        val replacement = record(knowledgeState = AdapterKnowledgeState.PACKAGE_REFERENCE)

        val stale = registry.replace(first.key, first.packageDigest, replacement, AdapterRegistryRevision.INITIAL)
        val replaced = registry.replace(first.key, first.packageDigest, replacement, current)

        assertTrue(stale is AdapterRegistryMutationResult.StaleRevision)
        assertTrue(replaced is AdapterRegistryMutationResult.Replaced)
        assertEquals(AdapterKnowledgeState.PACKAGE_REFERENCE, registry.exact(first.key)?.knowledgeState)
        assertEquals(current.value + 1L, registry.snapshot().revision.value)
    }

    @Test
    fun `removal requires current revision and expected digest`() {
        val registry = AdapterRegistry()
        val record = record()
        registry.register(record, registry.snapshot().revision)
        val before = registry.snapshot()

        val wrongDigest = registry.remove(record.key, digest("cd"), before.revision)
        assertNotNull(registry.exact(record.key))
        val removed = registry.remove(record.key, record.packageDigest, before.revision)

        assertTrue(wrongDigest is AdapterRegistryMutationResult.Conflict)
        assertTrue(removed is AdapterRegistryMutationResult.Removed)
        assertNull(registry.exact(record.key))
    }

    @Test
    fun `snapshots and record collections are immutable and isolated from later writes`() {
        val connections = mutableListOf(connection("1.0"))
        val record = record(connectionMethods = connections)
        connections.clear()
        val registry = AdapterRegistry()
        registry.register(record, registry.snapshot().revision)
        val oldSnapshot = registry.snapshot()
        registry.register(record(version = "1.1.0"), registry.snapshot().revision)

        assertEquals(1, oldSnapshot.records.size)
        assertEquals(1, record.connectionMethods.size)
        assertTrue(oldSnapshot.records is java.util.RandomAccess)
        assertThrowsUnsupported { (oldSnapshot.records as MutableList).add(record) }
        assertThrowsUnsupported { (record.connectionMethods as MutableList).clear() }
        try {
            (record.descriptor.supportedCapabilities as MutableList).clear()
        } catch (_: UnsupportedOperationException) {
            // The H012 copy may itself provide an immutable list.
        }
        assertEquals(1, record.descriptor.supportedCapabilities.size)
    }

    @Test
    fun `registry presence carries no install trust enable load availability or execution state`() {
        val recordFields = AdapterRegistryRecord::class.java.declaredFields.map { it.name.lowercase() }
        val registryMethods = AdapterRegistry::class.java.methods.map { it.name.lowercase() }
        val forbidden = listOf("installed", "trusted", "enabled", "loaded", "available", "authorized", "executing")

        assertTrue(forbidden.none { word -> recordFields.any { word in it } })
        assertTrue(forbidden.none { word -> registryMethods.any { word in it } })
        assertEquals(AdapterKnowledgeState.DEFINITION, record().knowledgeState)
    }

    @Test
    fun `public registry boundary has no vendor transport or runtime implementation coupling`() {
        val types = listOf(AdapterRegistry::class.java, AdapterRegistryRecord::class.java, AdapterRegistrySnapshot::class.java)
            .flatMap { type -> type.methods.flatMap { it.parameterTypes.asList() + it.returnType } }
            .map { it.name.lowercase() }

        assertFalse(types.any {
            it.contains("android") || it.contains("bluetooth") || it.contains("gatt") ||
                it.contains("looi") || it.contains("socket") || it.contains("loader")
        })
    }

    private fun record(
        version: String = "1.2.0",
        id: String = "io.hugo.adapter/reference",
        digestPair: String = "ab",
        family: ProviderFamily = ProviderFamily.BODY_ADAPTER,
        capability: String = "body.move@1.0",
        knowledgeState: AdapterKnowledgeState = AdapterKnowledgeState.DEFINITION,
        connectionMethods: List<ConnectionMethodReference> = listOf(connection("1.0")),
    ): AdapterRegistryRecord = registrySuccess(
        AdapterRegistryRecord.create(
            descriptor = descriptor(id, version, digestPair, family, capability),
            descriptorReference = evidence("test.adapter.$version"),
            artifactReference = artifact("test.package.$version"),
            knowledgeState = knowledgeState,
            connectionMethods = connectionMethods,
            extensionSchemas = emptyList(),
        ),
    )

    private fun descriptor(
        id: String,
        version: String,
        digestPair: String,
        family: ProviderFamily,
        capability: String,
    ): AdapterDescriptor {
        val kind = when (family) {
            ProviderFamily.BODY_ADAPTER -> AdapterManifestKind.BODY
            ProviderFamily.DEVICE_ADAPTER -> AdapterManifestKind.DEVICE
            ProviderFamily.SERVICE_CONNECTOR -> AdapterManifestKind.SERVICE
        }
        return adapterSuccess(
            AdapterDescriptor.create(
                adapterSuccess(AdapterSchemaVersion.parse("1.0")),
                AdapterIdentity(adapterId(id), adapterVersion(version)),
                digest(digestPair),
                AdapterProvenance(
                    AdapterProvenanceSource.HUGO_MAINTAINED,
                    adapterSuccess(AdapterPublisherReference.parse("io.hugo.maintainers")),
                    adapterSuccess(AdapterSourceReference.parse("hugo.adapter.reference")),
                ),
                listOf(AdapterManifestSupport(family, 1, AdapterSpecializedManifestSupport(kind, 1))),
                listOf(capability(capability)),
                adapterSuccess(HugoRuntimeCompatibility.create(runtimeVersion("1.0.0"), runtimeVersion("2.0.0"))),
            ),
        )
    }

    private fun resolvedManifest(): ResolvedManifestDefinition {
        val common = commonDocument()
        val body = specializedSuccess(
            BodyManifest.create(
                schema("1.0"),
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
        return ResolvedManifestDefinition(
            candidateSuccess(ManifestCandidateId.parse("40000000-0000-0000-0000-000000000001")),
            ManifestCandidateProvenance(
                ManifestCandidateSource.HUGO_MAINTAINED,
                candidateSuccess(ManifestCatalogReference.parse("hugo.catalog.reference")),
            ),
            IdentityManifestBinding(common.manifest.provider.definitionId, ProviderFamily.BODY_ADAPTER, null),
            common,
            specialized,
            AdapterCandidateAssessment(
                common.manifest.adapter.adapterId,
                AdapterCandidateAssessmentStatus.NO_MATCHING_DESCRIPTOR,
                emptyList(),
            ),
        )
    }

    private fun commonDocument(): CommonProviderManifestDocument {
        val manifest = commonSuccess(
            CommonProviderManifest.create(
                schema("1.0"),
                ManifestProviderDeclaration(providerDefinition(), ProviderFamily.BODY_ADAPTER, text("Reference body"), null),
                AdapterCompatibilityReference(adapterDefinition(), range("1.0.0", "2.0.0")),
                listOf(capability("body.move@1.0")),
                listOf(ManifestConnectionMethod(contract("hugo.connection/local@1.0"), true)),
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

    private fun assertThrowsUnsupported(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) {
            // Expected immutable view.
        }
    }

    private fun evidence(raw: String): CompatibilityEvidenceReference = when (
        val result = CompatibilityEvidenceReference.parse(raw)
    ) {
        is CompatibilityInputResult.Success -> result.value
        is CompatibilityInputResult.Failure -> throw AssertionError(result.error)
    }

    private fun artifact(raw: String): AdapterArtifactReference = registrySuccess(AdapterArtifactReference.parse(raw))
    private fun adapterId(raw: String = "io.hugo.adapter/reference"): AdapterId = adapterSuccess(AdapterId.parse(raw))
    private fun adapterVersion(raw: String): AdapterVersion = adapterSuccess(AdapterVersion.parse(raw))
    private fun digest(pair: String): AdapterPackageDigest = adapterSuccess(
        AdapterPackageDigest.parse("sha256:${pair.repeat(32)}"),
    )
    private fun runtimeVersion(raw: String): HugoRuntimeVersion = adapterSuccess(HugoRuntimeVersion.parse(raw))
    private fun connection(version: String): ConnectionMethodReference = ConnectionMethodReference(
        connectionSuccess(ConnectionMethodId.parse("hugo.connection/local")),
        connectionSuccess(ConnectionMethodVersion.parse(version)),
    )
    private fun capability(raw: String): CapabilityReference = when (val result = CapabilityReference.parse(raw)) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> throw AssertionError(result.error)
    }
    private fun providerDefinition(): ProviderDefinitionId = providerSuccess(
        ProviderDefinitionId.parse("hugo.body/reference"),
    )
    private fun adapterDefinition(): AdapterDefinitionReference = commonSuccess(
        AdapterDefinitionReference.parse("io.hugo.adapter/reference"),
    )
    private fun schema(raw: String): ManifestSchemaVersion = commonSuccess(ManifestSchemaVersion.parse(raw))
    private fun contract(raw: String): ManifestContractReference = commonSuccess(ManifestContractReference.parse(raw))
    private fun text(raw: String): ManifestText = commonSuccess(ManifestText.parse(raw))
    private fun range(minimum: String, maximum: String): ManifestVersionRange = commonSuccess(
        ManifestVersionRange.create(
            commonSuccess(ManifestVersion.parse(minimum)),
            commonSuccess(ManifestVersion.parse(maximum)),
        ),
    )

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
}
