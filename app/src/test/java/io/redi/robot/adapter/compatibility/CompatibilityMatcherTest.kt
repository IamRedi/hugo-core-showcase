package io.redi.robot.adapter.compatibility

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
import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.registry.InMemoryProviderRegistry
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
import io.redi.robot.manifest.domain.ManifestCompatibilityConstraint
import io.redi.robot.manifest.domain.ManifestCompatibilitySubject
import io.redi.robot.manifest.domain.ManifestConnectionMethod
import io.redi.robot.manifest.domain.ManifestContractReference
import io.redi.robot.manifest.domain.ManifestDigest
import io.redi.robot.manifest.domain.ManifestExtensionId
import io.redi.robot.manifest.domain.ManifestExtensionReference
import io.redi.robot.manifest.domain.ManifestProvenance
import io.redi.robot.manifest.domain.ManifestProviderDeclaration
import io.redi.robot.manifest.domain.ManifestSchemaVersion
import io.redi.robot.manifest.domain.ManifestSource
import io.redi.robot.manifest.domain.ManifestSourceReference
import io.redi.robot.manifest.domain.ManifestText
import io.redi.robot.manifest.domain.ManifestVersion
import io.redi.robot.manifest.domain.ManifestVersionRange
import io.redi.robot.manifest.domain.ServiceCategory
import io.redi.robot.manifest.domain.ServiceManifest
import io.redi.robot.manifest.domain.ServiceReachability
import io.redi.robot.manifest.domain.SpecializedManifestDocument
import io.redi.robot.manifest.domain.SpecializedManifestResult
import io.redi.robot.manifest.domain.SpecializedProviderManifest
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
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityMatcherTest {
    @Test
    fun `fully compatible provider reports every dimension separately`() {
        val fixture = fullFixture()
        val report = CompatibilityMatcher.match(fixture.request())

        assertEquals(CompatibilityReportStatus.COMPATIBLE, report.status)
        assertTrue(report.checks.map { it.dimension }.containsAll(CompatibilityDimension.entries))
        assertTrue(report.checks.all {
            it.status == CompatibilityCheckStatus.MATCHED
        })
        assertTrue(report.checks.all { it.evidence.isNotEmpty() })
    }

    @Test
    fun `missing adapter preserves understood manifest and creates explicit adapter need`() {
        val fixture = fullFixture()
        val report = CompatibilityMatcher.match(fixture.request(adapter = null))

        assertEquals(CompatibilityReportStatus.MISSING_ADAPTER, report.status)
        assertEquals(fixture.resolved.common.manifest.provider.definitionId, report.providerDefinitionId)
        assertTrue(report.checks.any { it.finding == CompatibilityFindingCode.MISSING_ADAPTER })
        assertTrue(report.checks.any {
            it.dimension == CompatibilityDimension.COMMON_MANIFEST_SCHEMA &&
                it.status == CompatibilityCheckStatus.MATCHED
        })
    }

    @Test
    fun `adapter version outside manifest range is unsupported version`() {
        val fixture = fullFixture()
        val report = CompatibilityMatcher.match(
            fixture.request(adapter = adapterEvidence(adapter(version = "2.0.0"))),
        )

        assertEquals(CompatibilityReportStatus.INCOMPATIBLE, report.status)
        assertFinding(report, CompatibilityDimension.ADAPTER_VERSION, CompatibilityFindingCode.UNSUPPORTED_VERSION)
    }

    @Test
    fun `runtime version outside adapter range is unsupported version`() {
        val fixture = fullFixture()
        val report = CompatibilityMatcher.match(
            fixture.request(runtime = runtimeEvidence("2.0.0")),
        )

        assertEquals(CompatibilityReportStatus.INCOMPATIBLE, report.status)
        assertFinding(report, CompatibilityDimension.HUGO_RUNTIME, CompatibilityFindingCode.UNSUPPORTED_VERSION)
    }

    @Test
    fun `wrong adapter provider family is incompatible`() {
        val fixture = fullFixture()
        val report = CompatibilityMatcher.match(
            fixture.request(
                adapter = adapterEvidence(adapter(family = ProviderFamily.DEVICE_ADAPTER)),
            ),
        )

        assertEquals(CompatibilityReportStatus.INCOMPATIBLE, report.status)
        assertFinding(report, CompatibilityDimension.PROVIDER_FAMILY, CompatibilityFindingCode.PROVIDER_FAMILY_MISMATCH)
    }

    @Test
    fun `capability version mismatch is explicit`() {
        val fixture = fullFixture()
        val report = CompatibilityMatcher.match(
            fixture.request(
                adapter = adapterEvidence(adapter(capabilities = listOf("body.move@2.0"))),
            ),
        )

        assertEquals(CompatibilityReportStatus.INCOMPATIBLE, report.status)
        assertFinding(report, CompatibilityDimension.CAPABILITY, CompatibilityFindingCode.UNSUPPORTED_CAPABILITY)
    }

    @Test
    fun `unsupported required connection method is incompatible`() {
        val fixture = fullFixture()
        val report = CompatibilityMatcher.match(
            fixture.request(connections = emptyList()),
        )

        assertEquals(CompatibilityReportStatus.INCOMPATIBLE, report.status)
        assertFinding(
            report,
            CompatibilityDimension.CONNECTION_METHOD,
            CompatibilityFindingCode.UNSUPPORTED_CONNECTION_METHOD,
        )
    }

    @Test
    fun `extension schema mismatch is incompatible`() {
        val fixture = fullFixture()
        val report = CompatibilityMatcher.match(
            fixture.request(extensions = listOf(extensionSupport("2.0"))),
        )

        assertEquals(CompatibilityReportStatus.INCOMPATIBLE, report.status)
        assertFinding(report, CompatibilityDimension.EXTENSION_SCHEMA, CompatibilityFindingCode.EXTENSION_SCHEMA_MISMATCH)
    }

    @Test
    fun `disjoint common and specialized API constraints are conflicting`() {
        val resolved = serviceResolvedWithConflictingApiRanges()
        val request = CompatibilityRequest.create(
            resolvedManifest = resolved,
            adapter = adapterEvidence(
                adapter(
                    family = ProviderFamily.SERVICE_CONNECTOR,
                    capabilities = listOf("service.call@1.0"),
                ),
            ),
            hugoRuntime = runtimeEvidence("1.5.0"),
            connectionMethods = emptyList(),
            extensionSchemas = emptyList(),
            providerVersions = listOf(providerVersion(ManifestCompatibilitySubject.PROVIDER_API, "1.5.0")),
        )

        val report = CompatibilityMatcher.match(request)

        assertEquals(CompatibilityReportStatus.INCOMPATIBLE, report.status)
        assertFinding(report, CompatibilityDimension.DECLARED_CONSTRAINT, CompatibilityFindingCode.CONFLICTING_CONSTRAINT)
    }

    @Test
    fun `missing optional connection support is partial compatibility`() {
        val resolved = bodyResolved(
            connections = listOf(ManifestConnectionMethod(contract("hugo.connection/local@1.0"), false)),
        )
        val request = CompatibilityRequest.create(
            resolvedManifest = resolved,
            adapter = adapterEvidence(adapter()),
            hugoRuntime = runtimeEvidence("1.5.0"),
            connectionMethods = emptyList(),
            extensionSchemas = emptyList(),
            providerVersions = emptyList(),
        )

        val report = CompatibilityMatcher.match(request)

        assertEquals(CompatibilityReportStatus.PARTIALLY_COMPATIBLE, report.status)
        assertTrue(report.checks.any { it.status == CompatibilityCheckStatus.PARTIAL })
    }

    @Test
    fun `missing constrained provider version produces unknown report`() {
        val resolved = bodyResolved(
            constraints = listOf(
                ManifestCompatibilityConstraint(
                    ManifestCompatibilitySubject.PROVIDER_FIRMWARE,
                    manifestRange("1.0.0", "2.0.0"),
                ),
            ),
        )
        val report = CompatibilityMatcher.match(
            CompatibilityRequest.create(
                resolved,
                adapterEvidence(adapter()),
                runtimeEvidence("1.5.0"),
                emptyList(),
                emptyList(),
                emptyList(),
            ),
        )

        assertEquals(CompatibilityReportStatus.UNKNOWN, report.status)
        assertFinding(report, CompatibilityDimension.DECLARED_CONSTRAINT, CompatibilityFindingCode.MISSING_PROVIDER_VERSION)
    }

    @Test
    fun `report ordering is deterministic and equivalent inputs are equivalent`() {
        val fixture = fullFixture()
        val first = CompatibilityMatcher.match(
            fixture.request(
                connections = fixture.connections.reversed(),
                extensions = fixture.extensions.reversed(),
                providerVersions = fixture.providerVersions.reversed(),
            ),
        ).view()
        val second = CompatibilityMatcher.match(fixture.request()).view()

        assertEquals(second, first)
        assertEquals(first.checks.sortedWith(compareBy({ it.dimension.ordinal }, { it.checkId })), first.checks)
    }

    @Test
    fun `compatibility does not imply trust permission availability connection safety or selection`() {
        val report = CompatibilityMatcher.match(fullFixture().request())
        val fields = CompatibilityReport::class.java.declaredFields.map { it.name.lowercase() }

        assertEquals(CompatibilityReportStatus.COMPATIBLE, report.status)
        assertTrue(fields.none {
            it.contains("trust") || it.contains("permission") || it.contains("authoriz") ||
                it.contains("connected") || it.contains("available") || it.contains("safe") ||
                it.contains("feasible") || it.contains("selected") || it.contains("installed")
        })
    }

    @Test
    fun `compatibility has no registry mutation path`() {
        val registry = InMemoryProviderRegistry()
        val before = registry.snapshot().entries

        val report = CompatibilityMatcher.match(fullFixture().request())

        assertEquals(CompatibilityReportStatus.COMPATIBLE, report.status)
        assertEquals(before, registry.snapshot().entries)
        assertTrue(CompatibilityRequest::class.java.declaredFields.none {
            it.type.name.contains("ProviderRegistry")
        })
    }

    @Test
    fun `public compatibility types have no concrete vendor or transport coupling`() {
        val types = listOf(
            CompatibilityMatcher::class.java,
            CompatibilityRequest::class.java,
            CompatibilityReport::class.java,
        ).flatMap { type -> type.methods.flatMap { it.parameterTypes.asList() + it.returnType } }
            .map { it.name.lowercase() }

        assertFalse(types.any {
            it.contains("android") || it.contains("bluetooth") || it.contains("gatt") ||
                it.contains("looi") || it.contains("packet") || it.contains("socket")
        })
    }

    private fun fullFixture(): FullFixture {
        val connection = connectionSupport("1.0")
        val extension = extensionSupport("1.0")
        val version = providerVersion(ManifestCompatibilitySubject.PROVIDER_FIRMWARE, "1.5.0")
        val resolved = bodyResolved(
            connections = listOf(ManifestConnectionMethod(contract("hugo.connection/local@1.0"), true)),
            extensions = listOf(extensionReference("1.0")),
            constraints = listOf(
                ManifestCompatibilityConstraint(
                    ManifestCompatibilitySubject.PROVIDER_FIRMWARE,
                    manifestRange("1.0.0", "2.0.0"),
                ),
            ),
        )
        return FullFixture(
            resolved,
            adapterEvidence(adapter()),
            runtimeEvidence("1.5.0"),
            listOf(connection),
            listOf(extension),
            listOf(version),
        )
    }

    private fun bodyResolved(
        connections: List<ManifestConnectionMethod> = emptyList(),
        extensions: List<ManifestExtensionReference> = emptyList(),
        constraints: List<ManifestCompatibilityConstraint> = emptyList(),
    ): ResolvedManifestDefinition {
        val common = commonDocument(
            family = ProviderFamily.BODY_ADAPTER,
            capabilities = listOf("body.move@1.0"),
            connections = connections,
            extensions = extensions,
            constraints = constraints,
        )
        val body = specializedDocument(
            specializedSuccess(
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
            ),
        )
        return resolved(common, body)
    }

    private fun serviceResolvedWithConflictingApiRanges(): ResolvedManifestDefinition {
        val common = commonDocument(
            family = ProviderFamily.SERVICE_CONNECTOR,
            capabilities = listOf("service.call@1.0"),
            constraints = listOf(
                ManifestCompatibilityConstraint(
                    ManifestCompatibilitySubject.PROVIDER_API,
                    manifestRange("1.0.0", "2.0.0"),
                ),
            ),
        )
        val service = specializedDocument(
            specializedSuccess(
                ServiceManifest.create(
                    schemaVersion = schema("1.0"),
                    common = CommonManifestBinding.from(common),
                    category = ServiceCategory.OTHER,
                    authenticationScheme = contract("service.auth/opaque@1.0"),
                    authorizationScopes = emptyList(),
                    apiCompatibility = manifestRange("2.0.0", "3.0.0"),
                    usagePolicyReferences = emptyList(),
                    reachability = ServiceReachability.REMOTE,
                    capabilityGroups = emptyList(),
                    extensions = emptyList(),
                ),
            ),
        )
        return resolved(common, service)
    }

    private fun commonDocument(
        family: ProviderFamily,
        capabilities: List<String>,
        connections: List<ManifestConnectionMethod> = emptyList(),
        extensions: List<ManifestExtensionReference> = emptyList(),
        constraints: List<ManifestCompatibilityConstraint> = emptyList(),
    ): CommonProviderManifestDocument {
        val manifest = commonSuccess(
            CommonProviderManifest.create(
                schemaVersion = schema("1.0"),
                provider = ManifestProviderDeclaration(
                    providerDefinition(if (family == ProviderFamily.SERVICE_CONNECTOR) "hugo.service/reference" else "hugo.body/reference"),
                    family,
                    text("Reference provider"),
                    null,
                ),
                adapter = AdapterCompatibilityReference(
                    adapterDefinition("io.hugo.adapter/reference"),
                    manifestRange("1.0.0", "2.0.0"),
                ),
                capabilities = capabilities.map(::capability),
                connectionMethods = connections,
                requirements = emptyList(),
                compatibility = constraints,
                extensions = extensions,
                provenance = ManifestProvenance(
                    ManifestSource.PROJECT,
                    text("HUGO project"),
                    sourceReference("hugo.manifest.reference"),
                    CapabilityEvidenceClass.DOCUMENTED,
                ),
            ),
        )
        return commonSuccess(CommonProviderManifestCodec.decode(CommonProviderManifestCodec.encode(manifest)))
    }

    private fun resolved(
        common: CommonProviderManifestDocument,
        specialized: SpecializedManifestDocument,
    ): ResolvedManifestDefinition = ResolvedManifestDefinition(
        candidateId = manifestCandidateId(),
        candidateProvenance = ManifestCandidateProvenance(
            ManifestCandidateSource.HUGO_MAINTAINED,
            catalogReference("hugo.catalog.reference"),
        ),
        identityBinding = IdentityManifestBinding(
            common.manifest.provider.definitionId,
            common.manifest.provider.family,
            null,
        ),
        common = common,
        specialized = specialized,
        adapterAssessment = AdapterCandidateAssessment(
            common.manifest.adapter.adapterId,
            AdapterCandidateAssessmentStatus.NO_MATCHING_DESCRIPTOR,
            emptyList(),
        ),
    )

    private fun adapter(
        version: String = "1.2.3",
        family: ProviderFamily = ProviderFamily.BODY_ADAPTER,
        capabilities: List<String> = listOf("body.move@1.0"),
    ): AdapterDescriptor {
        val kind = when (family) {
            ProviderFamily.BODY_ADAPTER -> AdapterManifestKind.BODY
            ProviderFamily.DEVICE_ADAPTER -> AdapterManifestKind.DEVICE
            ProviderFamily.SERVICE_CONNECTOR -> AdapterManifestKind.SERVICE
        }
        return adapterSuccess(
            AdapterDescriptor.create(
                adapterSuccess(AdapterSchemaVersion.parse("1.0")),
                AdapterIdentity(
                    adapterSuccess(AdapterId.parse("io.hugo.adapter/reference")),
                    adapterSuccess(AdapterVersion.parse(version)),
                ),
                adapterSuccess(AdapterPackageDigest.parse("sha256:${"ab".repeat(32)}")),
                AdapterProvenance(
                    AdapterProvenanceSource.HUGO_MAINTAINED,
                    adapterSuccess(AdapterPublisherReference.parse("io.hugo.maintainers")),
                    adapterSuccess(AdapterSourceReference.parse("hugo.adapter.reference")),
                ),
                listOf(
                    AdapterManifestSupport(
                        family,
                        1,
                        AdapterSpecializedManifestSupport(kind, 1),
                    ),
                ),
                capabilities.map(::capability),
                adapterSuccess(
                    HugoRuntimeCompatibility.create(runtimeVersion("1.0.0"), runtimeVersion("2.0.0")),
                ),
            ),
        )
    }

    private fun adapterEvidence(adapter: AdapterDescriptor): AdapterDescriptorEvidence =
        AdapterDescriptorEvidence(adapter, evidence("test.adapter.descriptor"))

    private fun runtimeEvidence(version: String): HugoRuntimeEvidence =
        HugoRuntimeEvidence(runtimeVersion(version), evidence("test.runtime.current"))

    private fun connectionSupport(version: String): ConnectionMethodSupport = ConnectionMethodSupport(
        ConnectionMethodReference(connectionMethodId("hugo.connection/local"), connectionVersion(version)),
        evidence("test.connection.local"),
    )

    private fun extensionSupport(version: String): ExtensionSchemaSupport = ExtensionSchemaSupport(
        extensionId("hugo.extension/profile"),
        schema(version),
        evidence("test.extension.profile"),
    )

    private fun providerVersion(
        subject: ManifestCompatibilitySubject,
        version: String,
    ): ProviderVersionEvidence = ProviderVersionEvidence(
        subject,
        manifestVersion(version),
        evidence("test.provider.${subject.name.lowercase()}"),
    )

    private fun extensionReference(version: String): ManifestExtensionReference = ManifestExtensionReference(
        extensionId("hugo.extension/profile"),
        schema(version),
        digest("cd"),
    )

    private fun specializedDocument(manifest: SpecializedProviderManifest): SpecializedManifestDocument =
        specializedSuccess(
            SpecializedProviderManifestCodec.decode(SpecializedProviderManifestCodec.encode(manifest)),
        )

    private fun assertFinding(
        report: CompatibilityReport,
        dimension: CompatibilityDimension,
        finding: CompatibilityFindingCode,
    ) {
        assertTrue(report.checks.any { it.dimension == dimension && it.finding == finding })
    }

    private fun evidence(raw: String): CompatibilityEvidenceReference = when (
        val result = CompatibilityEvidenceReference.parse(raw)
    ) {
        is CompatibilityInputResult.Success -> result.value
        is CompatibilityInputResult.Failure -> throw AssertionError(result.error)
    }

    private fun providerDefinition(raw: String): ProviderDefinitionId = providerSuccess(ProviderDefinitionId.parse(raw))
    private fun adapterDefinition(raw: String): AdapterDefinitionReference = commonSuccess(AdapterDefinitionReference.parse(raw))
    private fun schema(raw: String): ManifestSchemaVersion = commonSuccess(ManifestSchemaVersion.parse(raw))
    private fun manifestVersion(raw: String): ManifestVersion = commonSuccess(ManifestVersion.parse(raw))
    private fun manifestRange(minimum: String, maximum: String): ManifestVersionRange = commonSuccess(
        ManifestVersionRange.create(manifestVersion(minimum), manifestVersion(maximum)),
    )
    private fun text(raw: String): ManifestText = commonSuccess(ManifestText.parse(raw))
    private fun sourceReference(raw: String): ManifestSourceReference = commonSuccess(ManifestSourceReference.parse(raw))
    private fun contract(raw: String): ManifestContractReference = commonSuccess(ManifestContractReference.parse(raw))
    private fun extensionId(raw: String): ManifestExtensionId = commonSuccess(ManifestExtensionId.parse(raw))
    private fun digest(pair: String): ManifestDigest = commonSuccess(ManifestDigest.parse("sha256:${pair.repeat(32)}"))
    private fun runtimeVersion(raw: String): HugoRuntimeVersion = adapterSuccess(HugoRuntimeVersion.parse(raw))
    private fun connectionMethodId(raw: String): ConnectionMethodId = connectionSuccess(ConnectionMethodId.parse(raw))
    private fun connectionVersion(raw: String): ConnectionMethodVersion = connectionSuccess(ConnectionMethodVersion.parse(raw))
    private fun manifestCandidateId(): ManifestCandidateId = candidateSuccess(
        ManifestCandidateId.parse("30000000-0000-0000-0000-000000000001"),
    )
    private fun catalogReference(raw: String): ManifestCatalogReference = candidateSuccess(
        ManifestCatalogReference.parse(raw),
    )
    private fun capability(raw: String): CapabilityReference = when (val result = CapabilityReference.parse(raw)) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> throw AssertionError(result.error)
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
    private fun <T> connectionSuccess(result: ConnectionResult<T>): T = when (result) {
        is ConnectionResult.Success -> result.value
        is ConnectionResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> candidateSuccess(result: ManifestCandidateResult<T>): T = when (result) {
        is ManifestCandidateResult.Success -> result.value
        is ManifestCandidateResult.Failure -> throw AssertionError(result.error)
    }

    private data class FullFixture(
        val resolved: ResolvedManifestDefinition,
        val adapter: AdapterDescriptorEvidence,
        val runtime: HugoRuntimeEvidence,
        val connections: List<ConnectionMethodSupport>,
        val extensions: List<ExtensionSchemaSupport>,
        val providerVersions: List<ProviderVersionEvidence>,
    ) {
        fun request(
            adapter: AdapterDescriptorEvidence? = this.adapter,
            runtime: HugoRuntimeEvidence = this.runtime,
            connections: List<ConnectionMethodSupport> = this.connections,
            extensions: List<ExtensionSchemaSupport> = this.extensions,
            providerVersions: List<ProviderVersionEvidence> = this.providerVersions,
        ): CompatibilityRequest = CompatibilityRequest.create(
            resolved,
            adapter,
            runtime,
            connections,
            extensions,
            providerVersions,
        )
    }
}
