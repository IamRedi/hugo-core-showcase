package io.redi.robot.manifest.resolution

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
import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.capability.registry.InMemoryProviderRegistry
import io.redi.robot.discovery.identity.IdentityResolution
import io.redi.robot.discovery.identity.IdentityResolutionReason
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
import io.redi.robot.manifest.domain.DeviceCategory
import io.redi.robot.manifest.domain.DeviceControlLocality
import io.redi.robot.manifest.domain.DeviceManifest
import io.redi.robot.manifest.domain.DevicePowerCharacteristic
import io.redi.robot.manifest.domain.ManifestContractReference
import io.redi.robot.manifest.domain.ManifestProductMetadata
import io.redi.robot.manifest.domain.ManifestProvenance
import io.redi.robot.manifest.domain.ManifestProviderDeclaration
import io.redi.robot.manifest.domain.ManifestSchemaVersion
import io.redi.robot.manifest.domain.ManifestSource
import io.redi.robot.manifest.domain.ManifestSourceReference
import io.redi.robot.manifest.domain.ManifestText
import io.redi.robot.manifest.domain.ManifestVersion
import io.redi.robot.manifest.domain.ManifestVersionRange
import io.redi.robot.manifest.domain.SpecializedManifestResult
import io.redi.robot.manifest.domain.SpecializedProviderManifest
import io.redi.robot.manifest.domain.SpecializedProviderManifestCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestResolverTest {
    private val definition = providerDefinition("redi.looi/mobile-body")
    private val identity = ProviderIdentity(
        definitionId = definition,
        instanceId = providerInstance("123e4567-e89b-12d3-a456-426614174111"),
        family = ProviderFamily.BODY_ADAPTER,
        displayAlias = providerAlias("Living room LOOI"),
    )

    @Test
    fun `exact known manifest resolves with validated specialization and compatible adapter`() {
        val bundle = bodyBundle()
        val result = resolve(knownIdentity(), listOf(candidate(1, bundle)), listOf(adapter()))

        assertEquals(ManifestResolutionStatus.RESOLVED, result.status)
        assertEquals(definition, result.resolved!!.common.manifest.provider.definitionId)
        assertEquals(ProviderFamily.BODY_ADAPTER, result.resolved!!.specialized.manifest.kind.providerFamily)
        assertEquals(
            AdapterCandidateAssessmentStatus.COMPATIBLE_DESCRIPTORS,
            result.resolved!!.adapterAssessment.status,
        )
        assertEquals(identity.instanceId, result.identityBinding!!.instanceId)
    }

    @Test
    fun `new-provider definition resolves without inventing provider-instance identity`() {
        val result = resolve(newIdentity(), listOf(candidate(1, bodyBundle())), emptyList())

        assertEquals(ManifestResolutionStatus.RESOLVED, result.status)
        assertNull(result.identityBinding!!.instanceId)
        assertEquals(
            AdapterCandidateAssessmentStatus.NO_MATCHING_DESCRIPTOR,
            result.resolved!!.adapterAssessment.status,
        )
    }

    @Test
    fun `missing manifest returns not found`() {
        val result = resolve(knownIdentity(), emptyList(), emptyList())

        assertEquals(ManifestResolutionStatus.NOT_FOUND, result.status)
        assertEquals(ManifestResolutionReason.NO_MANIFEST_CANDIDATES, result.reason)
    }

    @Test
    fun `duplicate equivalent candidates remain ambiguous`() {
        val bundle = bodyBundle()
        val result = resolve(
            knownIdentity(),
            listOf(candidate(1, bundle), candidate(2, bundle)),
            emptyList(),
        )

        assertEquals(ManifestResolutionStatus.AMBIGUOUS, result.status)
        assertEquals(ManifestResolutionReason.DUPLICATE_EQUIVALENT_CANDIDATES, result.reason)
    }

    @Test
    fun `different valid claims for the same identity are conflicting manifests`() {
        val first = bodyBundle(capabilities = listOf("body.move@1.0"), displayName = "LOOI")
        val second = bodyBundle(capabilities = listOf("body.stop@1.0"), displayName = "LOOI alternate")
        val result = resolve(
            knownIdentity(),
            listOf(candidate(1, first), candidate(2, second)),
            emptyList(),
        )

        assertEquals(ManifestResolutionStatus.CONFLICTING_MANIFESTS, result.status)
    }

    @Test
    fun `wrong provider family fails before manifest selection`() {
        val device = deviceBundle()
        val result = resolve(knownIdentity(), listOf(candidate(1, device)), emptyList())

        assertEquals(ManifestResolutionStatus.INCOMPATIBLE_PROVIDER_FAMILY, result.status)
        assertEquals(ManifestResolutionReason.PROVIDER_FAMILY_MISMATCH, result.reason)
    }

    @Test
    fun `manifest definition must match the identity conclusion`() {
        val otherDefinition = providerDefinition("vendor.other/other-body")
        val common = commonDocument(
            family = ProviderFamily.BODY_ADAPTER,
            capabilities = listOf("body.move@1.0"),
            providerDefinition = otherDefinition,
        )
        val bundle = ManifestBundle(
            CommonProviderManifestCodec.encode(common.manifest),
            SpecializedProviderManifestCodec.encode(bodySpecialization(common)),
        )

        val result = resolve(knownIdentity(), listOf(candidate(1, bundle)), emptyList())

        assertEquals(ManifestResolutionStatus.IDENTITY_MISMATCH, result.status)
    }

    @Test
    fun `wrong specialization is never silently applied`() {
        val common = commonDocument(ProviderFamily.BODY_ADAPTER, listOf("body.move@1.0"))
        val wrongSpecialization = deviceSpecialization(common)
        val bundle = ManifestBundle(
            CommonProviderManifestCodec.encode(common.manifest),
            SpecializedProviderManifestCodec.encode(wrongSpecialization),
        )

        val result = resolve(knownIdentity(), listOf(candidate(1, bundle)), emptyList())

        assertEquals(ManifestResolutionStatus.INCOMPATIBLE_PROVIDER_FAMILY, result.status)
        assertEquals(ManifestResolutionReason.SPECIALIZATION_KIND_MISMATCH, result.reason)
    }

    @Test
    fun `common digest mismatch fails closed`() {
        val bundle = bodyBundle()
        val tampered = bundle.copy(
            common = bundle.common.replaceFirst(
                Regex("sha256:[0-9a-f]{64}"),
                "sha256:${"0".repeat(64)}",
            ),
        )

        val result = resolve(knownIdentity(), listOf(candidate(1, tampered)), emptyList())

        assertEquals(ManifestResolutionStatus.DIGEST_MISMATCH, result.status)
        assertEquals(ManifestResolutionReason.COMMON_DIGEST_MISMATCH, result.reason)
    }

    @Test
    fun `specialized digest mismatch fails closed`() {
        val bundle = bodyBundle()
        val tampered = bundle.copy(
            specialized = bundle.specialized.replaceFirst(
                Regex("sha256:[0-9a-f]{64}"),
                "sha256:${"0".repeat(64)}",
            ),
        )

        val result = resolve(knownIdentity(), listOf(candidate(1, tampered)), emptyList())

        assertEquals(ManifestResolutionStatus.DIGEST_MISMATCH, result.status)
        assertEquals(ManifestResolutionReason.SPECIALIZED_DIGEST_MISMATCH, result.reason)
    }

    @Test
    fun `unsupported common schema is explicit`() {
        val bundle = bodyBundle()
        val unsupported = bundle.copy(
            common = bundle.common.replaceFirst("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"2.0\""),
        )

        val result = resolve(knownIdentity(), listOf(candidate(1, unsupported)), emptyList())

        assertEquals(ManifestResolutionStatus.UNSUPPORTED_SCHEMA, result.status)
        assertEquals(ManifestResolutionReason.COMMON_SCHEMA_UNSUPPORTED, result.reason)
    }

    @Test
    fun `candidate acquisition provenance must agree with embedded provenance`() {
        val bundle = bodyBundle()
        val candidate = candidateSuccess(
            ManifestDefinitionCandidate.create(
                candidateId = candidateId(1),
                provenance = ManifestCandidateProvenance(
                    ManifestCandidateSource.OFFICIAL_VENDOR,
                    catalogReference("vendor.catalog.entry1"),
                ),
                commonDocument = bundle.common,
                specializedDocument = bundle.specialized,
            ),
        )

        val result = resolve(knownIdentity(), listOf(candidate), emptyList())

        assertEquals(ManifestResolutionStatus.REJECTED, result.status)
        assertEquals(ManifestResolutionReason.INVALID_PROVENANCE, result.reason)
    }

    @Test
    fun `adapter version outside manifest range is incompatible but not invalid manifest`() {
        val result = resolve(
            knownIdentity(),
            listOf(candidate(1, bodyBundle())),
            listOf(adapter(version = "3.0.0")),
        )

        assertEquals(ManifestResolutionStatus.INCOMPATIBLE_ADAPTER, result.status)
        assertEquals(ManifestResolutionReason.ADAPTER_VERSION_INCOMPATIBLE, result.reason)
    }

    @Test
    fun `capability version mismatch is adapter incompatibility`() {
        val result = resolve(
            knownIdentity(),
            listOf(candidate(1, bodyBundle(capabilities = listOf("body.move@2.0")))),
            listOf(adapter(capabilities = listOf("body.move@1.0"))),
        )

        assertEquals(ManifestResolutionStatus.INCOMPATIBLE_ADAPTER, result.status)
        assertEquals(ManifestResolutionReason.CAPABILITY_REFERENCE_INCOMPATIBLE, result.reason)
    }

    @Test
    fun `resolution is deterministic independent of candidate ordering`() {
        val first = candidate(1, bodyBundle(displayName = "First"))
        val second = candidate(2, bodyBundle(displayName = "Second"))

        val ordered = resolve(knownIdentity(), listOf(first, second), emptyList()).redacted()
        val reversed = resolve(knownIdentity(), listOf(second, first), emptyList()).redacted()

        assertEquals(ordered, reversed)
    }

    @Test
    fun `resolution carries no trust permission connection or execution implication`() {
        val result = resolve(knownIdentity(), listOf(candidate(1, bodyBundle())), emptyList())
        val fields = ManifestResolution::class.java.declaredFields.map { it.name.lowercase() }
        val text = result.toString().lowercase()

        assertTrue(fields.none { it.contains("trust") || it.contains("permission") || it.contains("authoriz") })
        assertTrue(fields.none { it.contains("connection") || it.contains("execution") || it.contains("registered") })
        assertFalse(text.contains(identity.instanceId.value))
    }

    @Test
    fun `manifest resolution has no registry mutation path`() {
        val registry = InMemoryProviderRegistry()
        val before = registry.snapshot().entries

        val result = resolve(knownIdentity(), listOf(candidate(1, bodyBundle())), emptyList())

        assertEquals(ManifestResolutionStatus.RESOLVED, result.status)
        assertEquals(before, registry.snapshot().entries)
        assertTrue(ManifestResolutionRequest::class.java.declaredFields.none {
            it.type.name.contains("ProviderRegistry")
        })
    }

    @Test
    fun `public resolver types contain no vendor transport or platform coupling`() {
        val publicTypes = listOf(
            ManifestResolver::class.java,
            ManifestResolution::class.java,
            ManifestResolutionRequest::class.java,
            ManifestDefinitionCandidate::class.java,
        ).flatMap { type ->
            type.methods.flatMap { it.parameterTypes.asList() + it.returnType }
        }.map { it.name.lowercase() }

        assertTrue(publicTypes.none {
            it.contains("android") || it.contains("bluetooth") || it.contains("gatt") ||
                it.contains("looi") || it.contains("transport") || it.contains("vendor")
        })
    }

    private fun resolve(
        identity: IdentityResolution,
        manifests: List<ManifestDefinitionCandidate>,
        adapters: List<AdapterDescriptor>,
    ): ManifestResolution = ManifestResolver.resolve(
        ManifestResolutionRequest.create(identity, manifests, adapters),
    )

    private fun knownIdentity(): IdentityResolution = IdentityResolution(
        status = IdentityResolutionStatus.MATCHED_KNOWN_PROVIDER,
        reason = IdentityResolutionReason.STRONG_VERIFIED_INSTANCE_EVIDENCE,
        observationIds = emptySet(),
        candidates = listOf(KnownProviderIdentityCandidate(identity, emptySet())),
        consideredEvidenceIds = emptySet(),
    )

    private fun newIdentity(): IdentityResolution = IdentityResolution(
        status = IdentityResolutionStatus.NEW_PROVIDER_CANDIDATE,
        reason = IdentityResolutionReason.DEFINITION_CANDIDATE_ONLY,
        observationIds = emptySet(),
        candidates = listOf(
            NewProviderIdentityCandidate(
                definitionId = definition,
                family = ProviderFamily.BODY_ADAPTER,
                displayHint = null,
                evidenceIds = emptySet(),
            ),
        ),
        consideredEvidenceIds = emptySet(),
    )

    private fun candidate(id: Int, bundle: ManifestBundle): ManifestDefinitionCandidate = candidateSuccess(
        ManifestDefinitionCandidate.create(
            candidateId = candidateId(id),
            provenance = ManifestCandidateProvenance(
                ManifestCandidateSource.HUGO_MAINTAINED,
                catalogReference("hugo.catalog.entry$id"),
            ),
            commonDocument = bundle.common,
            specializedDocument = bundle.specialized,
        ),
    )

    private fun bodyBundle(
        capabilities: List<String> = listOf("body.move@1.0"),
        displayName: String = "LOOI",
    ): ManifestBundle {
        val common = commonDocument(
            family = ProviderFamily.BODY_ADAPTER,
            capabilities = capabilities,
            displayName = displayName,
        )
        val specialized = bodySpecialization(common)
        return ManifestBundle(
            common = CommonProviderManifestCodec.encode(common.manifest),
            specialized = SpecializedProviderManifestCodec.encode(specialized),
        )
    }

    private fun deviceBundle(): ManifestBundle {
        val common = commonDocument(ProviderFamily.DEVICE_ADAPTER, listOf("device.observe@1.0"))
        val specialized = deviceSpecialization(common)
        return ManifestBundle(
            CommonProviderManifestCodec.encode(common.manifest),
            SpecializedProviderManifestCodec.encode(specialized),
        )
    }

    private fun commonDocument(
        family: ProviderFamily,
        capabilities: List<String>,
        displayName: String = "Provider",
        providerDefinition: ProviderDefinitionId = definition,
    ): CommonProviderManifestDocument {
        val manifest = commonSuccess(
            CommonProviderManifest.create(
                schemaVersion = schema("1.0"),
                provider = ManifestProviderDeclaration(
                    definitionId = providerDefinition,
                    family = family,
                    displayName = text(displayName),
                    product = commonSuccess(
                        ManifestProductMetadata.create(text("REDI"), text("Reference"), null),
                    ),
                ),
                adapter = AdapterCompatibilityReference(
                    adapterDefinition("io.hugo.adapter/reference"),
                    versionRange("1.0.0", "2.0.0"),
                ),
                capabilities = capabilities.map(::capability),
                connectionMethods = emptyList(),
                requirements = emptyList(),
                compatibility = emptyList(),
                extensions = emptyList(),
                provenance = ManifestProvenance(
                    ManifestSource.PROJECT,
                    text("HUGO maintainers"),
                    sourceReference("hugo.manifest.reference"),
                    CapabilityEvidenceClass.DOCUMENTED,
                ),
            ),
        )
        return commonSuccess(CommonProviderManifestCodec.decode(CommonProviderManifestCodec.encode(manifest)))
    }

    private fun bodySpecialization(common: CommonProviderManifestDocument): SpecializedProviderManifest =
        specializedSuccess(
            BodyManifest.create(
                schemaVersion = schema("1.0"),
                common = CommonManifestBinding.from(common),
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
            ),
        )

    private fun deviceSpecialization(common: CommonProviderManifestDocument): SpecializedProviderManifest =
        specializedSuccess(
            DeviceManifest.create(
                schemaVersion = schema("1.0"),
                common = CommonManifestBinding.from(common),
                category = DeviceCategory.OTHER,
                capabilityGroups = emptyList(),
                placementSemantics = emptyList(),
                powerCharacteristic = DevicePowerCharacteristic.UNKNOWN,
                controlLocality = DeviceControlLocality.REMOTE,
                extensions = emptyList(),
            ),
        )

    private fun adapter(
        version: String = "1.2.3",
        capabilities: List<String> = listOf("body.move@1.0"),
    ): AdapterDescriptor = adapterSuccess(
        AdapterDescriptor.create(
            schemaVersion = adapterSuccess(AdapterSchemaVersion.parse("1.0")),
            identity = AdapterIdentity(
                adapterSuccess(AdapterId.parse("io.hugo.adapter/reference")),
                adapterSuccess(AdapterVersion.parse(version)),
            ),
            packageDigest = adapterSuccess(AdapterPackageDigest.parse("sha256:${"ab".repeat(32)}")),
            provenance = AdapterProvenance(
                AdapterProvenanceSource.HUGO_MAINTAINED,
                adapterSuccess(AdapterPublisherReference.parse("io.hugo.maintainers")),
                adapterSuccess(AdapterSourceReference.parse("hugo.adapter.reference")),
            ),
            manifestSupport = listOf(
                AdapterManifestSupport(
                    providerFamily = ProviderFamily.BODY_ADAPTER,
                    commonManifestSchemaMajor = 1,
                    specialization = AdapterSpecializedManifestSupport(AdapterManifestKind.BODY, 1),
                ),
            ),
            supportedCapabilities = capabilities.map(::capability),
            runtimeCompatibility = null,
        ),
    )

    private fun candidateId(id: Int): ManifestCandidateId = candidateSuccess(
        ManifestCandidateId.parse("20000000-0000-0000-0000-${id.toString().padStart(12, '0')}"),
    )

    private fun catalogReference(raw: String): ManifestCatalogReference = candidateSuccess(
        ManifestCatalogReference.parse(raw),
    )

    private fun providerDefinition(raw: String): ProviderDefinitionId = providerSuccess(
        ProviderDefinitionId.parse(raw),
    )

    private fun providerInstance(raw: String): ProviderInstanceId = providerSuccess(ProviderInstanceId.parse(raw))

    private fun providerAlias(raw: String): RedactedProviderAlias = providerSuccess(RedactedProviderAlias.parse(raw))

    private fun schema(raw: String): ManifestSchemaVersion = commonSuccess(ManifestSchemaVersion.parse(raw))

    private fun text(raw: String): ManifestText = commonSuccess(ManifestText.parse(raw))

    private fun sourceReference(raw: String): ManifestSourceReference = commonSuccess(
        ManifestSourceReference.parse(raw),
    )

    private fun adapterDefinition(raw: String): AdapterDefinitionReference = commonSuccess(
        AdapterDefinitionReference.parse(raw),
    )

    private fun versionRange(minimum: String, maximum: String): ManifestVersionRange = commonSuccess(
        ManifestVersionRange.create(
            commonSuccess(ManifestVersion.parse(minimum)),
            commonSuccess(ManifestVersion.parse(maximum)),
        ),
    )

    private fun capability(raw: String): CapabilityReference = when (val result = CapabilityReference.parse(raw)) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> throw AssertionError(result.error)
    }

    private fun contract(raw: String): ManifestContractReference = commonSuccess(ManifestContractReference.parse(raw))

    private fun <T> candidateSuccess(result: ManifestCandidateResult<T>): T = when (result) {
        is ManifestCandidateResult.Success -> result.value
        is ManifestCandidateResult.Failure -> throw AssertionError(result.error)
    }

    private fun <T> providerSuccess(result: ProviderIdentityResult<T>): T = when (result) {
        is ProviderIdentityResult.Success -> result.value
        is ProviderIdentityResult.Failure -> throw AssertionError(result.error)
    }

    private fun <T> commonSuccess(result: CommonManifestResult<T>): T = when (result) {
        is CommonManifestResult.Success -> result.value
        is CommonManifestResult.Failure -> throw AssertionError(result.error)
    }

    private fun <T> specializedSuccess(result: SpecializedManifestResult<T>): T = when (result) {
        is SpecializedManifestResult.Success -> result.value
        is SpecializedManifestResult.Failure -> throw AssertionError(result.error)
    }

    private fun <T> adapterSuccess(result: AdapterResult<T>): T = when (result) {
        is AdapterResult.Success -> result.value
        is AdapterResult.Failure -> throw AssertionError(result.error)
    }

    private data class ManifestBundle(val common: String, val specialized: String)
}
