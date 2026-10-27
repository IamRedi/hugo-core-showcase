package io.redi.robot.manifest.domain

import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderIdentityResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommonProviderManifestTest {
    @Test
    fun validManifestRoundTripsCanonicallyWithVerifiedDigest() {
        val manifest = manifest()

        val encoded = CommonProviderManifestCodec.encode(manifest)
        val decoded = success(CommonProviderManifestCodec.decode(encoded))

        assertEquals(encoded, CommonProviderManifestCodec.encode(decoded.manifest))
        assertTrue(decoded.digest.value.matches(Regex("^sha256:[0-9a-f]{64}$")))
        assertEquals(ProviderFamily.DEVICE_ADAPTER, decoded.manifest.provider.family)
        assertEquals(listOf("environment.status@1.0"), decoded.manifest.capabilities.map { it.wireValue })
    }

    @Test
    fun malformedAndUnsupportedSchemaDocumentsFailWithTypedErrors() {
        assertEquals(
            CommonManifestResult.Failure(CommonManifestError.MalformedDocument),
            CommonProviderManifestCodec.decode("{"),
        )

        val unsupported = CommonProviderManifestCodec.encode(manifest()).replaceFirst(
            "\"schemaVersion\":\"1.0\"",
            "\"schemaVersion\":\"2.0\"",
        )
        assertEquals(
            CommonManifestResult.Failure(CommonManifestError.UnsupportedSchemaVersion("2.0")),
            CommonProviderManifestCodec.decode(unsupported),
        )
    }

    @Test
    fun providerIdentityFamilyAndCapabilityReferencesFailClosed() {
        val encoded = CommonProviderManifestCodec.encode(manifest())

        assertInvalidPath(
            CommonProviderManifestCodec.decode(encoded.replace("device_adapter", "unknown_family")),
            "provider.family",
        )
        assertInvalidPath(
            CommonProviderManifestCodec.decode(
                encoded.replace("io.hugo.device/reference", "Private Bluetooth Device"),
            ),
            "provider.definitionId",
        )
        assertInvalidPath(
            CommonProviderManifestCodec.decode(
                encoded.replace("environment.status@1.0", "vendor.raw_packet"),
            ),
            "capabilities[0]",
        )
    }

    @Test
    fun duplicateCapabilityIdsAndConflictingCompatibilityFailClosed() {
        val first = capability("environment.status@1.0")
        val second = capability("environment.status@1.1")
        assertEquals(
            CommonManifestResult.Failure(
                CommonManifestError.DuplicateCapability("environment.status"),
            ),
            createManifest(capabilities = listOf(first, second)),
        )

        val firmware = compatibility(ManifestCompatibilitySubject.PROVIDER_FIRMWARE, "1.0.0", "2.0.0")
        assertEquals(
            CommonManifestResult.Failure(CommonManifestError.DuplicateCompatibilitySubject),
            createManifest(compatibility = listOf(firmware, firmware)),
        )
    }

    @Test
    fun invalidVersionRangesAreRejectedBeforeResolution() {
        assertEquals(
            CommonManifestResult.Failure(
                CommonManifestError.InvalidField(
                    "versionRange",
                    ManifestFieldProblem.REVERSED_RANGE,
                ),
            ),
            ManifestVersionRange.create(version("2.0.0"), version("1.0.0")),
        )
        assertEquals(
            CommonManifestResult.Failure(
                CommonManifestError.InvalidField(
                    "versionRange",
                    ManifestFieldProblem.MISSING_BOUNDS,
                ),
            ),
            ManifestVersionRange.create(null, null),
        )
    }

    @Test
    fun unknownRootFieldsFailButUnknownExtensionReferencesRoundTrip() {
        val encoded = CommonProviderManifestCodec.encode(manifest())
        val unknownField = encoded.dropLast(1) + ",\"transportAddress\":\"private\"}"

        assertEquals(
            CommonManifestResult.Failure(
                CommonManifestError.UnexpectedFields("manifest", sortedSetOf("transportAddress")),
            ),
            CommonProviderManifestCodec.decode(unknownField),
        )

        val unknownExtension = extension("org.example.future/provider_geometry")
        val withExtension = success(createManifest(extensions = listOf(unknownExtension)))
        val decoded = success(
            CommonProviderManifestCodec.decode(CommonProviderManifestCodec.encode(withExtension)),
        )
        assertEquals(unknownExtension, decoded.manifest.extensions.single())
    }

    @Test
    fun tamperedClaimsFailDigestValidation() {
        val encoded = CommonProviderManifestCodec.encode(manifest())
        val tampered = encoded.replace("Reference device", "Changed device")

        val result = CommonProviderManifestCodec.decode(tampered)

        assertTrue(result is CommonManifestResult.Failure)
        assertTrue((result as CommonManifestResult.Failure).error is CommonManifestError.DigestMismatch)
    }

    @Test
    fun commonSchemaHasNoPrivateRuntimeOrTransportConfiguration() {
        val encoded = CommonProviderManifestCodec.encode(manifest()).lowercase()
        val forbidden = listOf(
            "providerinstanceid",
            "transportaddress",
            "bluetooth",
            "gatt",
            "endpoint",
            "credential",
            "connectionstate",
            "currentavailability",
            "geometrypayload",
        )

        forbidden.forEach { assertFalse("Unexpected common field: $it", encoded.contains(it)) }
        assertTrue(encoded.contains("connection.local/session@1.0"))
    }

    @Test
    fun machineReadableSchemaIsStrictAndProviderNeutral() {
        val text = checkNotNull(
            checkNotNull(javaClass.classLoader).getResourceAsStream(
                "schemas/hugo-common-provider-manifest-1.0.schema.json",
            ),
        ).bufferedReader().use { it.readText() }
        val schema = Json.parseToJsonElement(text) as JsonObject

        assertEquals("HUGO Common Provider Manifest 1.0", schema["title"]?.toString()?.trim('"'))
        assertEquals("false", schema["additionalProperties"].toString())
        assertFalse(text.contains("Bluetooth"))
        assertFalse(text.contains("LOOI"))
    }

    private fun manifest(): CommonProviderManifest = success(createManifest())

    private fun createManifest(
        capabilities: List<CapabilityReference> = listOf(capability("environment.status@1.0")),
        compatibility: List<ManifestCompatibilityConstraint> = listOf(
            compatibility(ManifestCompatibilitySubject.PROVIDER_SOFTWARE, "1.0.0", "2.0.0"),
        ),
        extensions: List<ManifestExtensionReference> = emptyList(),
    ): CommonManifestResult<CommonProviderManifest> = CommonProviderManifest.create(
        schemaVersion = schemaVersion("1.0"),
        provider = ManifestProviderDeclaration(
            definitionId = providerDefinition("io.hugo.device/reference"),
            family = ProviderFamily.DEVICE_ADAPTER,
            displayName = text("Reference device"),
            product = success(
                ManifestProductMetadata.create(
                    vendor = text("Example vendor"),
                    model = text("Model One"),
                    version = null,
                ),
            ),
        ),
        adapter = AdapterCompatibilityReference(
            adapterId = success(AdapterDefinitionReference.parse("io.hugo.adapter/reference")),
            versions = range("1.0.0", "2.0.0"),
        ),
        capabilities = capabilities,
        connectionMethods = listOf(
            ManifestConnectionMethod(
                reference = contract("connection.local/session@1.0"),
                required = true,
            ),
        ),
        requirements = listOf(
            ManifestRequirementReference(
                kind = ManifestRequirementKind.PERMISSION,
                reference = contract("permission.observe/basic@1.0"),
                required = true,
            ),
        ),
        compatibility = compatibility,
        extensions = extensions,
        provenance = ManifestProvenance(
            source = ManifestSource.PROJECT,
            issuer = text("HUGO project"),
            sourceReference = success(ManifestSourceReference.parse("docs/reference-provider")),
            evidence = CapabilityEvidenceClass.DOCUMENTED,
        ),
    )

    private fun compatibility(
        subject: ManifestCompatibilitySubject,
        minimum: String,
        maximum: String,
    ) = ManifestCompatibilityConstraint(subject, range(minimum, maximum))

    private fun extension(id: String) = ManifestExtensionReference(
        id = success(ManifestExtensionId.parse(id)),
        schemaVersion = schemaVersion("1.0"),
        digest = success(
            ManifestDigest.parse("sha256:" + "ab".repeat(32)),
        ),
    )

    private fun range(minimum: String, maximum: String): ManifestVersionRange = success(
        ManifestVersionRange.create(version(minimum), version(maximum)),
    )

    private fun capability(raw: String): CapabilityReference = when (
        val result = CapabilityReference.parse(raw)
    ) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> throw AssertionError(result.error)
    }

    private fun providerDefinition(raw: String): ProviderDefinitionId = when (
        val result = ProviderDefinitionId.parse(raw)
    ) {
        is ProviderIdentityResult.Success -> result.value
        is ProviderIdentityResult.Failure -> throw AssertionError(result.error)
    }

    private fun text(raw: String): ManifestText = success(ManifestText.parse(raw))

    private fun version(raw: String): ManifestVersion = success(ManifestVersion.parse(raw))

    private fun schemaVersion(raw: String): ManifestSchemaVersion = success(
        ManifestSchemaVersion.parse(raw),
    )

    private fun contract(raw: String): ManifestContractReference = success(
        ManifestContractReference.parse(raw),
    )

    private fun assertInvalidPath(result: CommonManifestResult<*>, expectedPath: String) {
        assertTrue(result is CommonManifestResult.Failure)
        assertEquals(
            expectedPath,
            ((result as CommonManifestResult.Failure).error as CommonManifestError.InvalidField).path,
        )
    }

    private fun <T> success(result: CommonManifestResult<T>): T = when (result) {
        is CommonManifestResult.Success -> result.value
        is CommonManifestResult.Failure -> throw AssertionError(result.error)
    }
}
