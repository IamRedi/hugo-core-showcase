package io.redi.robot.body.looi.firstlife

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
import io.redi.robot.adapter.registry.AdapterArtifactReference
import io.redi.robot.adapter.registry.AdapterKnowledgeState
import io.redi.robot.adapter.registry.AdapterRegistryModelResult
import io.redi.robot.adapter.registry.AdapterRegistryRecord
import io.redi.robot.adapter.compatibility.CompatibilityEvidenceReference
import io.redi.robot.body.looi.manifest.LooiCapabilityCatalog
import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.connection.api.ConnectionMethodReference
import io.redi.robot.connection.api.ConnectionMethodId
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
import io.redi.robot.manifest.domain.ManifestConnectionMethod
import io.redi.robot.manifest.domain.ManifestContractReference
import io.redi.robot.manifest.domain.ManifestProductMetadata
import io.redi.robot.manifest.domain.ManifestProvenance
import io.redi.robot.manifest.domain.ManifestSchemaVersion
import io.redi.robot.manifest.domain.ManifestSource
import io.redi.robot.manifest.domain.ManifestSourceReference
import io.redi.robot.manifest.domain.ManifestText
import io.redi.robot.manifest.domain.ManifestVersion
import io.redi.robot.manifest.domain.ManifestVersionRange
import io.redi.robot.manifest.domain.ManifestProviderDeclaration
import io.redi.robot.manifest.domain.SpecializedManifestResult
import io.redi.robot.manifest.domain.SpecializedProviderManifestCodec
import io.redi.robot.manifest.resolution.ManifestCandidateId
import io.redi.robot.manifest.resolution.ManifestCandidateProvenance
import io.redi.robot.manifest.resolution.ManifestCandidateResult
import io.redi.robot.manifest.resolution.ManifestCandidateSource
import io.redi.robot.manifest.resolution.ManifestCatalogReference
import io.redi.robot.manifest.resolution.ManifestDefinitionCandidate

val LOOI_BLE_CONNECTION_METHOD = ConnectionMethodReference(
    connection(ConnectionMethodId.parse("io.hugo.connection/bluetooth-le")),
    connection(ConnectionMethodVersion.parse("1.0")),
)

/** Adapter-owned built-in definition for the already verified LOOI implementation. */
object LooiFirstLifeDefinition {
    val definitionId: ProviderDefinitionId = provider(ProviderDefinitionId.parse("io.hugo.body/looi-reference"))
    val adapterId: AdapterId = adapter(AdapterId.parse("io.hugo.adapter/looi-reference"))
    val adapterVersion: AdapterVersion = adapter(AdapterVersion.parse("1.0.0"))
    val capabilityReferences = LooiCapabilityCatalog.supported.map { it.descriptor.reference }
        .sortedBy { it.wireValue }

    val commonDocument by lazy {
        common(CommonProviderManifestCodec.decode(CommonProviderManifestCodec.encode(commonManifest())))
    }
    val bodyDocument by lazy {
        specialized(SpecializedProviderManifestCodec.decode(SpecializedProviderManifestCodec.encode(bodyManifest())))
    }
    val manifestCandidate by lazy {
        candidate(ManifestDefinitionCandidate.create(
            candidateId = candidate(ManifestCandidateId.parse("6f006000-0000-4000-8000-000000000001")),
            provenance = ManifestCandidateProvenance(
                ManifestCandidateSource.BUILT_IN_HUGO,
                candidate(ManifestCatalogReference.parse("hugo.builtin.looi-reference.v1")),
            ),
            commonDocument = CommonProviderManifestCodec.encode(commonDocument.manifest),
            specializedDocument = SpecializedProviderManifestCodec.encode(bodyDocument.manifest),
        ))
    }
    val adapterDescriptor by lazy {
        adapter(AdapterDescriptor.create(
            schemaVersion = adapter(AdapterSchemaVersion.parse("1.0")),
            identity = AdapterIdentity(adapterId, adapterVersion),
            packageDigest = adapter(AdapterPackageDigest.parse("sha256:${"6f".repeat(32)}")),
            provenance = AdapterProvenance(
                AdapterProvenanceSource.BUILT_IN,
                adapter(AdapterPublisherReference.parse("io.hugo.project")),
                adapter(AdapterSourceReference.parse("hugo.builtin.looi-reference.v1")),
            ),
            manifestSupport = listOf(AdapterManifestSupport(
                ProviderFamily.BODY_ADAPTER,
                1,
                AdapterSpecializedManifestSupport(AdapterManifestKind.BODY, 1),
            )),
            supportedCapabilities = capabilityReferences,
            runtimeCompatibility = adapter(HugoRuntimeCompatibility.create(
                adapter(HugoRuntimeVersion.parse("1.0.0")),
                adapter(HugoRuntimeVersion.parse("2.0.0")),
            )),
        ))
    }
    val adapterRecord by lazy {
        registry(AdapterRegistryRecord.create(
            descriptor = adapterDescriptor,
            descriptorReference = compatibility("hugo.builtin.looi-reference.adapter-descriptor.v1"),
            artifactReference = registry(AdapterArtifactReference.parse("hugo.builtin.looi-reference.package.v1")),
            knowledgeState = AdapterKnowledgeState.DEFINITION,
            connectionMethods = listOf(LOOI_BLE_CONNECTION_METHOD),
            extensionSchemas = emptyList(),
        ))
    }

    fun supportsExactly(expected: Collection<String>): Boolean =
        capabilityReferences.map { it.wireValue } == expected.sorted()

    private fun commonManifest(): CommonProviderManifest = common(CommonProviderManifest.create(
        schemaVersion = schema(),
        provider = ManifestProviderDeclaration(
            definitionId,
            ProviderFamily.BODY_ADAPTER,
            text("LOOI Reference Body"),
            common(ManifestProductMetadata.create(text("TangibleFuture"), text("LOOI"), null)),
        ),
        adapter = AdapterCompatibilityReference(
            common(AdapterDefinitionReference.parse(adapterId.wireValue)),
            common(ManifestVersionRange.create(version("1.0.0"), version("2.0.0"))),
        ),
        capabilities = capabilityReferences,
        connectionMethods = listOf(ManifestConnectionMethod(contract("io.hugo.connection/bluetooth-le@1.0"), true)),
        requirements = emptyList(),
        compatibility = emptyList(),
        extensions = emptyList(),
        provenance = ManifestProvenance(
            ManifestSource.PROJECT,
            text("HUGO built-in LOOI adapter"),
            common(ManifestSourceReference.parse("hugo.builtin.looi-reference.manifest.v1")),
            CapabilityEvidenceClass.HARDWARE_VERIFIED,
        ),
    ))

    private fun bodyManifest(): BodyManifest = specialized(BodyManifest.create(
        schemaVersion = schema(),
        common = CommonManifestBinding.from(commonDocument),
        bodyClass = contract("body.class/mobile@1.0"),
        mobility = BodyMobilityClass.GROUND,
        capabilityGroups = emptyList(),
        safetyController = null,
        mountingSupport = BodyMountingSupport(false, emptyList()),
        powerRelationship = BodyPowerRelationship.BATTERY_POWER,
        geometryProfile = null,
        calibrationRequirements = emptyList(),
        surfaceConstraints = emptyList(),
        mountingEvidenceCapabilities = emptyList(),
        extensions = emptyList(),
    ))

    private fun schema() = common(ManifestSchemaVersion.parse("1.0"))
    private fun version(raw: String) = common(ManifestVersion.parse(raw))
    private fun text(raw: String) = common(ManifestText.parse(raw))
    private fun contract(raw: String) = common(ManifestContractReference.parse(raw))
    private fun compatibility(raw: String) = when (val result = CompatibilityEvidenceReference.parse(raw)) {
        is io.redi.robot.adapter.compatibility.CompatibilityInputResult.Success -> result.value
        is io.redi.robot.adapter.compatibility.CompatibilityInputResult.Failure -> error("Invalid evidence: ${result.error}")
    }
    private fun <T> common(result: CommonManifestResult<T>): T = when (result) {
        is CommonManifestResult.Success -> result.value
        is CommonManifestResult.Failure -> error("Invalid built-in LOOI manifest: ${result.error}")
    }
    private fun <T> specialized(result: SpecializedManifestResult<T>): T = when (result) {
        is SpecializedManifestResult.Success -> result.value
        is SpecializedManifestResult.Failure -> error("Invalid built-in LOOI specialization: ${result.error}")
    }
    private fun <T> adapter(result: AdapterResult<T>): T = when (result) {
        is AdapterResult.Success -> result.value
        is AdapterResult.Failure -> error("Invalid built-in LOOI adapter: ${result.error}")
    }
    private fun <T> registry(result: AdapterRegistryModelResult<T>): T = when (result) {
        is AdapterRegistryModelResult.Success -> result.value
        is AdapterRegistryModelResult.Failure -> error("Invalid built-in LOOI registry record: ${result.error}")
    }
    private fun <T> provider(result: ProviderIdentityResult<T>): T = when (result) {
        is ProviderIdentityResult.Success -> result.value
        is ProviderIdentityResult.Failure -> error("Invalid built-in LOOI identity: ${result.error}")
    }
    private fun <T> candidate(result: ManifestCandidateResult<T>): T = when (result) {
        is ManifestCandidateResult.Success -> result.value
        is ManifestCandidateResult.Failure -> error("Invalid built-in LOOI candidate: ${result.error}")
    }
}

fun ConnectionMethodReference.isLooiBuiltInMethod(): Boolean = this == LOOI_BLE_CONNECTION_METHOD

private fun <T> connection(result: ConnectionResult<T>): T = when (result) {
    is ConnectionResult.Success -> result.value
    is ConnectionResult.Failure -> error("Invalid built-in LOOI connection method: ${result.error}")
}
