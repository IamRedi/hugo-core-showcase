package io.redi.robot.manifest.domain

import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderIdentityResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecializedProviderManifestTest {
    @Test
    fun validBodySpecializationBindsToACommonBodyManifestAndRoundTrips() {
        val common = commonDocument(ProviderFamily.BODY_ADAPTER, listOf(
            "body.move@1.0", "body.stop@1.0", "body.mount.observe@1.0",
        ))
        val body = bodyManifest(CommonManifestBinding.from(common))

        val encoded = SpecializedProviderManifestCodec.encode(body)
        val decoded = manifestSuccess(SpecializedProviderManifestCodec.decode(encoded))

        assertEquals(SpecializedManifestValidation.Valid, SpecializedManifestValidator.validate(body, common))
        assertEquals(SpecializedManifestValidation.Valid, SpecializedManifestValidator.validate(decoded.manifest, common))
        assertEquals(encoded, SpecializedProviderManifestCodec.encode(decoded.manifest))
        assertTrue(decoded.manifest is BodyManifest)
    }

    @Test
    fun validDeviceAndServiceSpecializationsAreIndependentOfBodyFields() {
        val deviceCommon = commonDocument(
            ProviderFamily.DEVICE_ADAPTER,
            listOf("environment.light.control@1.0", "vision.observe@1.0"),
        )
        val serviceCommon = commonDocument(
            ProviderFamily.SERVICE_CONNECTOR,
            listOf("service.calendar.read@1.0", "service.calendar.write@1.0"),
        )
        val device = deviceManifest(CommonManifestBinding.from(deviceCommon))
        val service = serviceManifest(CommonManifestBinding.from(serviceCommon))

        assertEquals(SpecializedManifestValidation.Valid, SpecializedManifestValidator.validate(device, deviceCommon))
        assertEquals(SpecializedManifestValidation.Valid, SpecializedManifestValidator.validate(service, serviceCommon))
        assertTrue(SpecializedProviderManifestCodec.encode(device).contains("placementSemantics"))
        assertTrue(SpecializedProviderManifestCodec.encode(service).contains("authenticationScheme"))
        assertFalse(SpecializedProviderManifestCodec.encode(device).contains("mobility"))
        assertFalse(SpecializedProviderManifestCodec.encode(service).contains("mountingSupport"))
    }

    @Test
    fun wrongProviderFamilyAndInvalidCommonBindingsFailClosed() {
        val deviceCommon = commonDocument(
            ProviderFamily.DEVICE_ADAPTER,
            listOf("body.move@1.0", "body.stop@1.0", "body.mount.observe@1.0"),
        )
        val body = bodyManifest(CommonManifestBinding.from(deviceCommon))

        assertEquals(
            SpecializedManifestValidation.Invalid(
                SpecializedManifestError.WrongProviderFamily(
                    ProviderFamily.BODY_ADAPTER,
                    ProviderFamily.DEVICE_ADAPTER,
                ),
            ),
            SpecializedManifestValidator.validate(body, deviceCommon),
        )

        val wrongDigest = bodyManifest(
            CommonManifestBinding(
                deviceCommon.manifest.provider.definitionId,
                commonSuccess(ManifestDigest.parse("sha256:" + "cd".repeat(32))),
            ),
        )
        assertEquals(
            SpecializedManifestValidation.Invalid(SpecializedManifestError.CommonDigestMismatch),
            SpecializedManifestValidator.validate(wrongDigest, deviceCommon),
        )
    }

    @Test
    fun duplicateGroupsAndUnsupportedSpecializationVersionsAreRejected() {
        val common = CommonManifestBinding(
            providerDefinition("io.hugo.body/reference"),
            commonSuccess(ManifestDigest.parse("sha256:" + "ab".repeat(32))),
        )
        val group = group("group.mobility/default@1.0", listOf("body.move@1.0"))

        assertEquals(
            SpecializedManifestResult.Failure(
                SpecializedManifestError.DuplicateCapabilityGroup(group.reference.wireValue),
            ),
            BodyManifest.create(
                schemaVersion("1.0"), common, contract("body.mobile/wheeled@1.0"),
                BodyMobilityClass.GROUND, listOf(group, group), null,
                BodyMountingSupport(false, emptyList()), BodyPowerRelationship.BATTERY_POWER,
                null, emptyList(), emptyList(), emptyList(), emptyList(),
            ),
        )
        assertEquals(
            SpecializedManifestResult.Failure(
                SpecializedManifestError.UnsupportedSpecializationVersion(
                    SpecializedManifestKind.DEVICE,
                    "2.0",
                ),
            ),
            DeviceManifest.create(
                schemaVersion("2.0"), common, DeviceCategory.LIGHT, emptyList(), emptyList(),
                DevicePowerCharacteristic.MAINS_POWERED, DeviceControlLocality.LOCAL, emptyList(),
            ),
        )
    }

    @Test
    fun geometryIsOnlyADigestedReferenceAndServiceClaimsCarryNoCredentials() {
        val body = bodyManifest(commonBinding(ProviderFamily.BODY_ADAPTER))
        val service = serviceManifest(commonBinding(ProviderFamily.SERVICE_CONNECTOR))

        val bodyEncoded = SpecializedProviderManifestCodec.encode(body).lowercase()
        val serviceEncoded = SpecializedProviderManifestCodec.encode(service).lowercase()

        assertTrue(bodyEncoded.contains("geometryprofile"))
        listOf("width", "height", "footprint", "camera_pose", "mounted_state").forEach {
            assertFalse(bodyEncoded.contains(it))
        }
        listOf("credential", "token", "secret", "accountidentity").forEach {
            assertFalse(serviceEncoded.contains(it))
        }
        assertTrue(serviceEncoded.contains("authorizationscopes"))
    }

    @Test
    fun unknownRootFieldsFailAndExplicitUnknownExtensionsRoundTrip() {
        val device = deviceManifest(commonBinding(ProviderFamily.DEVICE_ADAPTER))
        val encoded = SpecializedProviderManifestCodec.encode(device)
        val withUnknownField = encoded.dropLast(1) + ",\"runtimeConnection\":\"connected\"}"

        assertEquals(
            SpecializedManifestResult.Failure(
                SpecializedManifestError.UnexpectedFields(
                    "specializedManifest",
                    sortedSetOf("runtimeConnection"),
                ),
            ),
            SpecializedProviderManifestCodec.decode(withUnknownField),
        )

        val extension = extension("org.example.future/device_extension")
        val extended = manifestSuccess(DeviceManifest.create(
            schemaVersion("1.0"), commonBinding(ProviderFamily.DEVICE_ADAPTER), DeviceCategory.SENSOR,
            emptyList(), emptyList(), DevicePowerCharacteristic.SELF_POWERED,
            DeviceControlLocality.REMOTE, listOf(extension),
        ))
        val decoded = manifestSuccess(
            SpecializedProviderManifestCodec.decode(SpecializedProviderManifestCodec.encode(extended)),
        )
        assertEquals(extension, decoded.manifest.extensions.single())
    }

    @Test
    fun specializedPublicTypesRemainVendorTransportAndRuntimeFree() {
        val names = SpecializedProviderManifestCodec::class.java.methods.flatMap { method ->
            method.parameterTypes.asList() + method.returnType
        }.map { it.name.lowercase() }

        assertFalse(names.any { it.contains("android") || it.contains("bluetooth") || it.contains("looi") })
    }

    private fun bodyManifest(binding: CommonManifestBinding): BodyManifest = manifestSuccess(
        BodyManifest.create(
            schemaVersion = schemaVersion("1.0"),
            common = binding,
            bodyClass = contract("body.mobile/wheeled@1.0"),
            mobility = BodyMobilityClass.GROUND,
            capabilityGroups = listOf(
                group("group.mobility/default@1.0", listOf("body.move@1.0", "body.stop@1.0")),
            ),
            safetyController = BodySafetyControllerClaim(
                contract("safety.local/controller@1.0"),
                localStopRequired = true,
            ),
            mountingSupport = BodyMountingSupport(true, listOf(BodyMountRole.MOBILE_HOST)),
            powerRelationship = BodyPowerRelationship.BATTERY_POWER,
            geometryProfile = BodyGeometryProfileReference(
                extension("world.geometry/body_profile"),
            ),
            calibrationRequirements = listOf(contract("calibration.body/required@1.0")),
            surfaceConstraints = listOf(contract("surface.safe/ground@1.0")),
            mountingEvidenceCapabilities = listOf(capability("body.mount.observe@1.0")),
            extensions = emptyList(),
        ),
    )

    private fun deviceManifest(binding: CommonManifestBinding): DeviceManifest = manifestSuccess(
        DeviceManifest.create(
            schemaVersion("1.0"), binding, DeviceCategory.LIGHT,
            listOf(group("group.control/default@1.0", listOf("environment.light.control@1.0"))),
            listOf(contract("placement.environment/fixed@1.0")),
            DevicePowerCharacteristic.MAINS_POWERED, DeviceControlLocality.LOCAL, emptyList(),
        ),
    )

    private fun serviceManifest(binding: CommonManifestBinding): ServiceManifest = manifestSuccess(
        ServiceManifest.create(
            schemaVersion("1.0"), binding, ServiceCategory.CALENDAR,
            contract("auth.oauth/authorization_code@1.0"),
            listOf(contract("scope.calendar/read@1.0")),
            range("1.0.0", "2.0.0"),
            listOf(contract("usage.rate_limit/default@1.0")), ServiceReachability.CLOUD,
            listOf(group("group.observe/default@1.0", listOf("service.calendar.read@1.0"))),
            emptyList(),
        ),
    )

    private fun commonBinding(family: ProviderFamily): CommonManifestBinding = CommonManifestBinding(
        providerDefinition(definitionFor(family)),
        commonSuccess(ManifestDigest.parse("sha256:" + "ab".repeat(32))),
    )

    private fun commonDocument(
        family: ProviderFamily,
        capabilities: List<String>,
    ): CommonProviderManifestDocument {
        val manifest = commonSuccess(CommonProviderManifest.create(
            schemaVersion = schemaVersion("1.0"),
            provider = ManifestProviderDeclaration(
                providerDefinition(definitionFor(family)), family, text("Reference"), null,
            ),
            adapter = AdapterCompatibilityReference(
                commonSuccess(AdapterDefinitionReference.parse("io.hugo.adapter/reference")),
                range("1.0.0", "2.0.0"),
            ),
            capabilities = capabilities.map(::capability),
            connectionMethods = emptyList(), requirements = emptyList(), compatibility = emptyList(),
            extensions = emptyList(),
            provenance = ManifestProvenance(
                ManifestSource.PROJECT, text("HUGO project"),
                commonSuccess(ManifestSourceReference.parse("docs/reference")),
                CapabilityEvidenceClass.DOCUMENTED,
            ),
        ))
        return commonSuccess(CommonProviderManifestCodec.decode(CommonProviderManifestCodec.encode(manifest)))
    }

    private fun group(reference: String, capabilities: List<String>) = SpecializedCapabilityGroup(
        contract(reference), capabilities.map(::capability),
    )

    private fun extension(id: String) = ManifestExtensionReference(
        commonSuccess(ManifestExtensionId.parse(id)), schemaVersion("1.0"),
        commonSuccess(ManifestDigest.parse("sha256:" + "cd".repeat(32))),
    )

    private fun range(minimum: String, maximum: String): ManifestVersionRange = commonSuccess(
        ManifestVersionRange.create(version(minimum), version(maximum)),
    )

    private fun capability(raw: String): CapabilityReference = when (val result = CapabilityReference.parse(raw)) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> throw AssertionError(result.error)
    }

    private fun providerDefinition(raw: String): ProviderDefinitionId = when (
        val result = ProviderDefinitionId.parse(raw)
    ) {
        is ProviderIdentityResult.Success -> result.value
        is ProviderIdentityResult.Failure -> throw AssertionError(result.error)
    }

    private fun contract(raw: String): ManifestContractReference = commonSuccess(
        ManifestContractReference.parse(raw),
    )

    private fun text(raw: String): ManifestText = commonSuccess(ManifestText.parse(raw))

    private fun version(raw: String): ManifestVersion = commonSuccess(ManifestVersion.parse(raw))

    private fun schemaVersion(raw: String): ManifestSchemaVersion = commonSuccess(
        ManifestSchemaVersion.parse(raw),
    )

    private fun definitionFor(family: ProviderFamily): String = when (family) {
        ProviderFamily.BODY_ADAPTER -> "io.hugo.body/reference"
        ProviderFamily.DEVICE_ADAPTER -> "io.hugo.device/reference"
        ProviderFamily.SERVICE_CONNECTOR -> "io.hugo.service/reference"
    }

    private fun <T> commonSuccess(result: CommonManifestResult<T>): T = when (result) {
        is CommonManifestResult.Success -> result.value
        is CommonManifestResult.Failure -> throw AssertionError(result.error)
    }

    private fun <T> manifestSuccess(result: SpecializedManifestResult<T>): T = when (result) {
        is SpecializedManifestResult.Success -> result.value
        is SpecializedManifestResult.Failure -> throw AssertionError(result.error)
    }
}
