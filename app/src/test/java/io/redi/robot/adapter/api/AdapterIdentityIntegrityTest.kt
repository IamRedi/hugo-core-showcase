package io.redi.robot.adapter.api

import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.manifest.domain.AdapterCompatibilityReference
import io.redi.robot.manifest.domain.AdapterDefinitionReference
import io.redi.robot.manifest.domain.CommonManifestResult
import io.redi.robot.manifest.domain.CommonProviderManifest
import io.redi.robot.manifest.domain.CommonProviderManifestCodec
import io.redi.robot.manifest.domain.CommonProviderManifestDocument
import io.redi.robot.manifest.domain.ManifestProductMetadata
import io.redi.robot.manifest.domain.ManifestProvenance
import io.redi.robot.manifest.domain.ManifestSchemaVersion
import io.redi.robot.manifest.domain.ManifestSource
import io.redi.robot.manifest.domain.ManifestSourceReference
import io.redi.robot.manifest.domain.ManifestText
import io.redi.robot.manifest.domain.ManifestVersion
import io.redi.robot.manifest.domain.ManifestVersionRange
import io.redi.robot.manifest.domain.ManifestProviderDeclaration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterIdentityIntegrityTest {
    @Test
    fun validIdentityVersionAndDescriptorRoundTripAreCanonical() {
        val descriptor = descriptor()
        val encoded = AdapterDescriptorCodec.encode(descriptor)
        val decoded = success(AdapterDescriptorCodec.decode(encoded))

        assertEquals("io.hugo.adapter/reference", decoded.identity.id.wireValue)
        assertEquals("1.2.3", decoded.identity.version.wireValue)
        assertEquals(encoded, AdapterDescriptorCodec.encode(decoded))
    }

    @Test
    fun malformedIdentityVersionDigestAndUnsupportedSchemaFailClosed() {
        assertEquals(AdapterResult.Failure(AdapterError.MalformedAdapterId), AdapterId.parse("LOOI"))
        assertEquals(AdapterResult.Failure(AdapterError.MalformedVersion("adapterVersion")), AdapterVersion.parse("1.02.0"))
        assertEquals(AdapterResult.Failure(AdapterError.MalformedPackageDigest), AdapterPackageDigest.parse("sha256:xyz"))
        assertEquals(
            AdapterResult.Failure(AdapterError.UnsupportedDescriptorSchemaVersion("2.0")),
            AdapterDescriptor.create(
                schemaVersion("2.0"), identity(), digest("ab"), provenance(), listOf(bodySupport()),
                listOf(capability("body.move@1.0")), null,
            ),
        )
    }

    @Test
    fun runtimeCompatibilityAndManifestVersionCompatibilityAreExplicit() {
        val runtime = success(HugoRuntimeCompatibility.create(runtimeVersion("1.0.0"), runtimeVersion("2.0.0")))
        assertTrue(runtime.supports(runtimeVersion("1.9.9")))
        assertFalse(runtime.supports(runtimeVersion("2.0.0")))

        val compatibleCommon = commonDocument("1.0.0", ProviderFamily.BODY_ADAPTER, listOf("body.move@1.0"))
        val incompatibleCommon = commonDocument("2.0.0", ProviderFamily.BODY_ADAPTER, listOf("body.move@1.0"))
        val adapter = descriptor()

        assertEquals(
            AdapterManifestCompatibilityResult.Compatible,
            AdapterManifestCompatibilityMatcher.match(adapter, compatibleCommon),
        )
        assertEquals(
            AdapterManifestCompatibilityResult.Incompatible(
                AdapterManifestCompatibilityFailure.ADAPTER_VERSION_OUTSIDE_MANIFEST_RANGE,
            ),
            AdapterManifestCompatibilityMatcher.match(adapter, incompatibleCommon),
        )
    }

    @Test
    fun integrityMatchAndDigestMismatchAreDeterministicAndNotTrust() {
        val expected = AdapterIntegrityExpectation(identity(), digest("ab"))
        val matching = AdapterIntegrityVerifier.verify(expected, AdapterIntegrityExpectation(identity(), digest("ab")))
        val mismatching = AdapterIntegrityVerifier.verify(expected, AdapterIntegrityExpectation(identity(), digest("cd")))

        assertEquals(AdapterIntegrityStatus.MATCH, matching.status)
        assertEquals(AdapterIntegrityStatus.PACKAGE_DIGEST_MISMATCH, mismatching.status)
        assertFalse(AdapterIntegrityStatus.values().any { it.name.contains("TRUST") })
    }

    @Test
    fun manifestBindingRequiresAdapterReferenceFamilySchemaAndCapabilityCompatibility() {
        val adapter = descriptor()
        val incompatibleReference = commonDocument(
            adapterVersion = "1.0.0", family = ProviderFamily.BODY_ADAPTER,
            capabilities = listOf("body.move@1.0"), adapterReference = "io.hugo.adapter/other",
        )
        val incompatibleFamily = commonDocument(
            adapterVersion = "1.0.0", family = ProviderFamily.DEVICE_ADAPTER,
            capabilities = listOf("body.move@1.0"),
        )
        val incompatibleCapability = commonDocument(
            adapterVersion = "1.0.0", family = ProviderFamily.BODY_ADAPTER,
            capabilities = listOf("body.move@1.0", "body.stop@1.0"),
        )

        assertEquals(
            AdapterManifestCompatibilityResult.Incompatible(
                AdapterManifestCompatibilityFailure.MANIFEST_ADAPTER_REFERENCE_MISMATCH,
            ),
            AdapterManifestCompatibilityMatcher.match(adapter, incompatibleReference),
        )
        assertEquals(
            AdapterManifestCompatibilityResult.Incompatible(
                AdapterManifestCompatibilityFailure.UNSUPPORTED_PROVIDER_FAMILY,
            ),
            AdapterManifestCompatibilityMatcher.match(adapter, incompatibleFamily),
        )
        assertEquals(
            AdapterManifestCompatibilityResult.Incompatible(
                AdapterManifestCompatibilityFailure.UNSUPPORTED_CAPABILITY,
            ),
            AdapterManifestCompatibilityMatcher.match(adapter, incompatibleCapability),
        )
    }

    @Test
    fun duplicateAndConflictingManifestDeclarationsFailClosed() {
        assertEquals(
            AdapterResult.Failure(AdapterError.DuplicateManifestSupportDeclaration),
            AdapterDescriptor.create(
                schemaVersion("1.0"), identity(), digest("ab"), provenance(),
                listOf(bodySupport(), bodySupport()), listOf(capability("body.move@1.0")), null,
            ),
        )
        assertEquals(
            AdapterResult.Failure(AdapterError.ConflictingManifestSupportDeclaration),
            AdapterDescriptor.create(
                schemaVersion("1.0"), identity(), digest("ab"), provenance(),
                listOf(
                    AdapterManifestSupport(
                        ProviderFamily.BODY_ADAPTER,
                        1,
                        AdapterSpecializedManifestSupport(AdapterManifestKind.DEVICE, 1),
                    ),
                ),
                listOf(capability("body.move@1.0")),
                null,
            ),
        )
    }

    @Test
    fun strictCodecRejectsUnknownFieldsAndPublicTypesDoNotCoupleToVendorTransportOrRuntime() {
        val encoded = AdapterDescriptorCodec.encode(descriptor())
        val unknown = encoded.dropLast(1) + ",\"bluetoothGatt\":\"forbidden\"}"

        assertEquals(
            AdapterResult.Failure(
                AdapterError.UnexpectedFields("adapterDescriptor", sortedSetOf("bluetoothGatt")),
            ),
            AdapterDescriptorCodec.decode(unknown),
        )
        val typeNames = AdapterDescriptorCodec::class.java.methods.flatMap { method ->
            method.parameterTypes.asList() + method.returnType
        }.map { it.name.lowercase() }
        assertFalse(typeNames.any { it.contains("android") || it.contains("bluetooth") || it.contains("looi") || it.contains("transport") })
    }

    private fun descriptor(): AdapterDescriptor = success(AdapterDescriptor.create(
        schemaVersion("1.0"), identity(), digest("ab"), provenance(), listOf(bodySupport()),
        listOf(capability("body.move@1.0")),
        success(HugoRuntimeCompatibility.create(runtimeVersion("1.0.0"), runtimeVersion("2.0.0"))),
    ))

    private fun bodySupport() = AdapterManifestSupport(
        ProviderFamily.BODY_ADAPTER,
        commonManifestSchemaMajor = 1,
        specialization = AdapterSpecializedManifestSupport(AdapterManifestKind.BODY, 1),
    )

    private fun identity() = AdapterIdentity(
        success(AdapterId.parse("io.hugo.adapter/reference")),
        success(AdapterVersion.parse("1.2.3")),
    )

    private fun digest(hexPair: String) = success(AdapterPackageDigest.parse("sha256:" + hexPair.repeat(32)))

    private fun provenance() = AdapterProvenance(
        AdapterProvenanceSource.HUGO_MAINTAINED,
        success(AdapterPublisherReference.parse("io.hugo.maintainers")),
        success(AdapterSourceReference.parse("docs.adapter.reference")),
    )

    private fun commonDocument(
        adapterVersion: String,
        family: ProviderFamily,
        capabilities: List<String>,
        adapterReference: String = "io.hugo.adapter/reference",
    ): CommonProviderManifestDocument {
        val manifest = commonSuccess(CommonProviderManifest.create(
            schemaVersion = commonSchemaVersion("1.0"),
            provider = ManifestProviderDeclaration(
                providerDefinition("io.hugo.body/reference"), family, manifestText("Reference"), null,
            ),
            adapter = AdapterCompatibilityReference(
                commonSuccess(AdapterDefinitionReference.parse(adapterReference)),
                manifestRange(adapterVersion, "3.0.0"),
            ),
            capabilities = capabilities.map(::capability),
            connectionMethods = emptyList(), requirements = emptyList(), compatibility = emptyList(),
            extensions = emptyList(),
            provenance = ManifestProvenance(
                ManifestSource.PROJECT, manifestText("HUGO project"),
                commonSuccess(ManifestSourceReference.parse("docs.reference")),
                CapabilityEvidenceClass.DOCUMENTED,
            ),
        ))
        return commonSuccess(CommonProviderManifestCodec.decode(CommonProviderManifestCodec.encode(manifest)))
    }

    private fun capability(raw: String): CapabilityReference = when (val result = CapabilityReference.parse(raw)) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> throw AssertionError(result.error)
    }

    private fun providerDefinition(raw: String): ProviderDefinitionId = when (val result = ProviderDefinitionId.parse(raw)) {
        is ProviderIdentityResult.Success -> result.value
        is ProviderIdentityResult.Failure -> throw AssertionError(result.error)
    }

    private fun manifestText(raw: String): ManifestText = commonSuccess(ManifestText.parse(raw))

    private fun commonSchemaVersion(raw: String): ManifestSchemaVersion = commonSuccess(ManifestSchemaVersion.parse(raw))

    private fun manifestRange(minimum: String, maximum: String): ManifestVersionRange = commonSuccess(
        ManifestVersionRange.create(
            commonSuccess(ManifestVersion.parse(minimum)),
            commonSuccess(ManifestVersion.parse(maximum)),
        ),
    )

    private fun schemaVersion(raw: String): AdapterSchemaVersion = success(AdapterSchemaVersion.parse(raw))

    private fun runtimeVersion(raw: String): HugoRuntimeVersion = success(HugoRuntimeVersion.parse(raw))

    private fun <T> success(result: AdapterResult<T>): T = when (result) {
        is AdapterResult.Success -> result.value
        is AdapterResult.Failure -> throw AssertionError(result.error)
    }

    private fun <T> commonSuccess(result: CommonManifestResult<T>): T = when (result) {
        is CommonManifestResult.Success -> result.value
        is CommonManifestResult.Failure -> throw AssertionError(result.error)
    }
}
