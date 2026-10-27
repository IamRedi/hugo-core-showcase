package io.redi.robot.manifest.domain

import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderIdentityResult
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

object SpecializedProviderManifestCodec {
    private val json = Json { ignoreUnknownKeys = false }
    private val rootFields = setOf("kind", "schemaVersion", "digest", "common", "extensions", "details")

    fun encode(manifest: SpecializedProviderManifest): String {
        val payload = encodePayload(manifest)
        val digest = digest(payload)
        return encodeJson(buildJsonObject {
            put("kind", payload.getValue("kind"))
            put("schemaVersion", payload.getValue("schemaVersion"))
            put("digest", JsonPrimitive(digest.value))
            put("common", payload.getValue("common"))
            put("extensions", payload.getValue("extensions"))
            put("details", payload.getValue("details"))
        })
    }

    fun decode(text: String): SpecializedManifestResult<SpecializedManifestDocument> = try {
        val root = parseRoot(text)
        root.requireExactFields("specializedManifest", rootFields)
        val kind = enum<SpecializedManifestKind>(root.string("kind"), "kind")
        val schemaVersion = unwrap(ManifestSchemaVersion.parse(root.string("schemaVersion")))
        if (schemaVersion.major != CommonProviderManifest.supportedSchemaMajor) {
            throw DecodeException(
                SpecializedManifestError.UnsupportedSpecializationVersion(kind, schemaVersion.wireValue),
            )
        }
        val declaredDigest = unwrap(ManifestDigest.parse(root.string("digest")))
        val common = parseCommon(root.obj("common"))
        val extensions = root.array("extensions").mapIndexed { index, element ->
            parseExtension(element.obj("extensions[$index]"), index)
        }
        val details = root.obj("details")
        val manifest = when (kind) {
            SpecializedManifestKind.BODY -> parseBody(schemaVersion, common, extensions, details)
            SpecializedManifestKind.DEVICE -> parseDevice(schemaVersion, common, extensions, details)
            SpecializedManifestKind.SERVICE -> parseService(schemaVersion, common, extensions, details)
        }
        val computedDigest = digest(encodePayload(manifest))
        if (declaredDigest != computedDigest) {
            throw DecodeException(
                SpecializedManifestError.DigestMismatch(declaredDigest.value, computedDigest.value),
            )
        }
        SpecializedManifestResult.Success(SpecializedManifestDocument(manifest, declaredDigest))
    } catch (error: DecodeException) {
        SpecializedManifestResult.Failure(error.error)
    }

    private fun encodePayload(manifest: SpecializedProviderManifest): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive(manifest.kind.name.lowercase()))
        put("schemaVersion", JsonPrimitive(manifest.schemaVersion.wireValue))
        put("common", encodeCommon(manifest.common))
        put("extensions", encodeExtensions(manifest.extensions))
        put("details", when (manifest) {
            is BodyManifest -> encodeBody(manifest)
            is DeviceManifest -> encodeDevice(manifest)
            is ServiceManifest -> encodeService(manifest)
        })
    }

    private fun encodeCommon(common: CommonManifestBinding): JsonObject = buildJsonObject {
        put("providerDefinitionId", JsonPrimitive(common.providerDefinitionId.value))
        put("digest", JsonPrimitive(common.digest.value))
    }

    private fun encodeBody(manifest: BodyManifest): JsonObject = buildJsonObject {
        put("bodyClass", JsonPrimitive(manifest.bodyClass.wireValue))
        put("mobility", JsonPrimitive(manifest.mobility.name.lowercase()))
        put("capabilityGroups", encodeGroups(manifest.capabilityGroups))
        put("safetyController", manifest.safetyController?.let { safety ->
            buildJsonObject {
                put("reference", JsonPrimitive(safety.reference.wireValue))
                put("localStopRequired", JsonPrimitive(safety.localStopRequired))
            }
        } ?: JsonNull)
        put("mountingSupport", buildJsonObject {
            put("supportsMounts", JsonPrimitive(manifest.mountingSupport.supportsMounts))
            put("supportedRoles", buildJsonArray {
                manifest.mountingSupport.supportedRoles.forEach { add(JsonPrimitive(it.name.lowercase())) }
            })
        })
        put("powerRelationship", JsonPrimitive(manifest.powerRelationship.name.lowercase()))
        put("geometryProfile", manifest.geometryProfile?.let { geometry ->
            encodeExtension(geometry.extension)
        } ?: JsonNull)
        put("calibrationRequirements", encodeReferences(manifest.calibrationRequirements))
        put("surfaceConstraints", encodeReferences(manifest.surfaceConstraints))
        put("mountingEvidenceCapabilities", encodeCapabilities(manifest.mountingEvidenceCapabilities))
    }

    private fun encodeDevice(manifest: DeviceManifest): JsonObject = buildJsonObject {
        put("category", JsonPrimitive(manifest.category.name.lowercase()))
        put("capabilityGroups", encodeGroups(manifest.capabilityGroups))
        put("placementSemantics", encodeReferences(manifest.placementSemantics))
        put("powerCharacteristic", JsonPrimitive(manifest.powerCharacteristic.name.lowercase()))
        put("controlLocality", JsonPrimitive(manifest.controlLocality.name.lowercase()))
    }

    private fun encodeService(manifest: ServiceManifest): JsonObject = buildJsonObject {
        put("category", JsonPrimitive(manifest.category.name.lowercase()))
        put("authenticationScheme", JsonPrimitive(manifest.authenticationScheme.wireValue))
        put("authorizationScopes", encodeReferences(manifest.authorizationScopes))
        put("apiCompatibility", encodeVersionRange(manifest.apiCompatibility))
        put("usagePolicyReferences", encodeReferences(manifest.usagePolicyReferences))
        put("reachability", JsonPrimitive(manifest.reachability.name.lowercase()))
        put("capabilityGroups", encodeGroups(manifest.capabilityGroups))
    }

    private fun encodeGroups(groups: List<SpecializedCapabilityGroup>): JsonArray = buildJsonArray {
        groups.forEach { group ->
            add(buildJsonObject {
                put("reference", JsonPrimitive(group.reference.wireValue))
                put("capabilities", encodeCapabilities(group.capabilities))
            })
        }
    }

    private fun encodeCapabilities(capabilities: List<CapabilityReference>): JsonArray = buildJsonArray {
        capabilities.forEach { add(JsonPrimitive(it.wireValue)) }
    }

    private fun encodeReferences(references: List<ManifestContractReference>): JsonArray = buildJsonArray {
        references.forEach { add(JsonPrimitive(it.wireValue)) }
    }

    private fun encodeExtensions(extensions: List<ManifestExtensionReference>): JsonArray = buildJsonArray {
        extensions.forEach { add(encodeExtension(it)) }
    }

    private fun encodeExtension(extension: ManifestExtensionReference): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(extension.id.value))
        put("schemaVersion", JsonPrimitive(extension.schemaVersion.wireValue))
        put("digest", JsonPrimitive(extension.digest.value))
    }

    private fun encodeVersionRange(range: ManifestVersionRange): JsonObject = buildJsonObject {
        put(
            "minimumVersionInclusive",
            range.minimumInclusive?.let { JsonPrimitive(it.wireValue) } ?: JsonNull,
        )
        put(
            "maximumVersionExclusive",
            range.maximumExclusive?.let { JsonPrimitive(it.wireValue) } ?: JsonNull,
        )
    }

    private fun parseCommon(root: JsonObject): CommonManifestBinding {
        root.requireExactFields("common", setOf("providerDefinitionId", "digest"))
        val definition = when (val parsed = ProviderDefinitionId.parse(root.string("providerDefinitionId"))) {
            is ProviderIdentityResult.Success -> parsed.value
            is ProviderIdentityResult.Failure -> fail("common.providerDefinitionId")
        }
        return CommonManifestBinding(definition, unwrap(ManifestDigest.parse(root.string("digest"))))
    }

    private fun parseBody(
        schemaVersion: ManifestSchemaVersion,
        common: CommonManifestBinding,
        extensions: List<ManifestExtensionReference>,
        root: JsonObject,
    ): BodyManifest {
        root.requireExactFields(
            "details",
            setOf(
                "bodyClass",
                "mobility",
                "capabilityGroups",
                "safetyController",
                "mountingSupport",
                "powerRelationship",
                "geometryProfile",
                "calibrationRequirements",
                "surfaceConstraints",
                "mountingEvidenceCapabilities",
            ),
        )
        val mounting = root.obj("mountingSupport").also {
            it.requireExactFields("details.mountingSupport", setOf("supportsMounts", "supportedRoles"))
        }
        return unwrap(BodyManifest.create(
            schemaVersion = schemaVersion,
            common = common,
            bodyClass = contract(root.string("bodyClass"), "details.bodyClass"),
            mobility = enum(root.string("mobility"), "details.mobility"),
            capabilityGroups = parseGroups(root.array("capabilityGroups"), "details.capabilityGroups"),
            safetyController = root.nullableObject("safetyController")?.let { safety ->
                safety.requireExactFields(
                    "details.safetyController",
                    setOf("reference", "localStopRequired"),
                )
                BodySafetyControllerClaim(
                    contract(safety.string("reference"), "details.safetyController.reference"),
                    safety.boolean("localStopRequired"),
                )
            },
            mountingSupport = BodyMountingSupport(
                mounting.boolean("supportsMounts"),
                mounting.array("supportedRoles").mapIndexed { index, value ->
                    enum(
                        value.requiredString("details.mountingSupport.supportedRoles[$index]"),
                        "roles",
                    )
                },
            ),
            powerRelationship = enum(
                root.string("powerRelationship"),
                "details.powerRelationship",
            ),
            geometryProfile = root.nullableObject("geometryProfile")?.let { geometry ->
                BodyGeometryProfileReference(parseExtension(geometry, -1))
            },
            calibrationRequirements = parseReferences(
                root.array("calibrationRequirements"),
                "details.calibrationRequirements",
            ),
            surfaceConstraints = parseReferences(
                root.array("surfaceConstraints"),
                "details.surfaceConstraints",
            ),
            mountingEvidenceCapabilities = parseCapabilities(
                root.array("mountingEvidenceCapabilities"),
                "details.mountingEvidenceCapabilities",
            ),
            extensions = extensions,
        ))
    }

    private fun parseDevice(
        schemaVersion: ManifestSchemaVersion,
        common: CommonManifestBinding,
        extensions: List<ManifestExtensionReference>,
        root: JsonObject,
    ): DeviceManifest {
        root.requireExactFields(
            "details",
            setOf(
                "category",
                "capabilityGroups",
                "placementSemantics",
                "powerCharacteristic",
                "controlLocality",
            ),
        )
        return unwrap(DeviceManifest.create(
            schemaVersion = schemaVersion,
            common = common,
            category = enum(root.string("category"), "details.category"),
            capabilityGroups = parseGroups(root.array("capabilityGroups"), "details.capabilityGroups"),
            placementSemantics = parseReferences(
                root.array("placementSemantics"),
                "details.placementSemantics",
            ),
            powerCharacteristic = enum(
                root.string("powerCharacteristic"),
                "details.powerCharacteristic",
            ),
            controlLocality = enum(root.string("controlLocality"), "details.controlLocality"),
            extensions = extensions,
        ))
    }

    private fun parseService(
        schemaVersion: ManifestSchemaVersion,
        common: CommonManifestBinding,
        extensions: List<ManifestExtensionReference>,
        root: JsonObject,
    ): ServiceManifest {
        root.requireExactFields(
            "details",
            setOf(
                "category",
                "authenticationScheme",
                "authorizationScopes",
                "apiCompatibility",
                "usagePolicyReferences",
                "reachability",
                "capabilityGroups",
            ),
        )
        return unwrap(ServiceManifest.create(
            schemaVersion = schemaVersion,
            common = common,
            category = enum(root.string("category"), "details.category"),
            authenticationScheme = contract(
                root.string("authenticationScheme"),
                "details.authenticationScheme",
            ),
            authorizationScopes = parseReferences(
                root.array("authorizationScopes"),
                "details.authorizationScopes",
            ),
            apiCompatibility = parseVersionRange(root.obj("apiCompatibility"), "details.apiCompatibility"),
            usagePolicyReferences = parseReferences(
                root.array("usagePolicyReferences"),
                "details.usagePolicyReferences",
            ),
            reachability = enum(root.string("reachability"), "details.reachability"),
            capabilityGroups = parseGroups(root.array("capabilityGroups"), "details.capabilityGroups"),
            extensions = extensions,
        ))
    }

    private fun parseGroups(array: JsonArray, path: String): List<SpecializedCapabilityGroup> =
        array.mapIndexed { index, element ->
            val itemPath = "$path[$index]"
            val root = element.obj(itemPath)
            root.requireExactFields(itemPath, setOf("reference", "capabilities"))
            SpecializedCapabilityGroup(
                reference = contract(root.string("reference"), "$itemPath.reference"),
                capabilities = parseCapabilities(root.array("capabilities"), "$itemPath.capabilities"),
            )
        }

    private fun parseReferences(array: JsonArray, path: String): List<ManifestContractReference> =
        array.mapIndexed { index, element ->
            val itemPath = "$path[$index]"
            contract(element.requiredString(itemPath), itemPath)
        }

    private fun parseCapabilities(array: JsonArray, path: String): List<CapabilityReference> =
        array.mapIndexed { index, element ->
            val raw = element.requiredString("$path[$index]")
            when (val parsed = CapabilityReference.parse(raw)) {
                is CapabilityIdentityResult.Success -> parsed.value
                is CapabilityIdentityResult.Failure -> fail("$path[$index]")
            }
        }

    private fun parseExtension(root: JsonObject, index: Int): ManifestExtensionReference {
        val path = if (index >= 0) "extensions[$index]" else "details.geometryProfile"
        root.requireExactFields(path, setOf("id", "schemaVersion", "digest"))
        return ManifestExtensionReference(
            id = unwrap(ManifestExtensionId.parse(root.string("id"))),
            schemaVersion = unwrap(ManifestSchemaVersion.parse(root.string("schemaVersion"))),
            digest = unwrap(ManifestDigest.parse(root.string("digest"))),
        )
    }

    private fun parseVersionRange(root: JsonObject, path: String): ManifestVersionRange {
        root.requireExactFields(path, setOf("minimumVersionInclusive", "maximumVersionExclusive"))
        return unwrap(ManifestVersionRange.create(
            minimumInclusive = root.nullableString("minimumVersionInclusive")?.let {
                version(it, "$path.minimumVersionInclusive")
            },
            maximumExclusive = root.nullableString("maximumVersionExclusive")?.let {
                version(it, "$path.maximumVersionExclusive")
            },
        ))
    }

    private fun contract(raw: String, path: String): ManifestContractReference = when (
        val parsed = ManifestContractReference.parse(raw)
    ) {
        is CommonManifestResult.Success -> parsed.value
        is CommonManifestResult.Failure -> fail(path)
    }

    private fun version(raw: String, path: String): ManifestVersion = when (
        val parsed = ManifestVersion.parse(raw)
    ) {
        is CommonManifestResult.Success -> parsed.value
        is CommonManifestResult.Failure -> fail(path)
    }

    private inline fun <reified T : Enum<T>> enum(raw: String, path: String): T =
        enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: fail(path)

    private fun digest(payload: JsonObject): ManifestDigest {
        val bytes = MessageDigest.getInstance("SHA-256").digest(
            encodeJson(payload).toByteArray(Charsets.UTF_8),
        )
        return ManifestDigest.fromSha256(bytes.joinToString("") { "%02x".format(it) })
    }

    private fun parseRoot(text: String): JsonObject = runCatching {
        Json.parseToJsonElement(text) as? JsonObject
    }.getOrNull() ?: throw DecodeException(SpecializedManifestError.MalformedDocument)

    private fun encodeJson(element: JsonElement): String = json.encodeToString(
        JsonElement.serializer(),
        element,
    )

    private fun JsonObject.requireExactFields(path: String, expected: Set<String>) {
        val missing = expected - keys
        if (missing.isNotEmpty()) {
            throw DecodeException(SpecializedManifestError.MissingFields(path, missing.toSortedSet()))
        }
        val unexpected = keys - expected
        if (unexpected.isNotEmpty()) {
            throw DecodeException(
                SpecializedManifestError.UnexpectedFields(path, unexpected.toSortedSet()),
            )
        }
    }

    private fun JsonObject.string(field: String): String {
        val value = get(field) as? JsonPrimitive ?: fail(field)
        return value.content.takeIf { value.isString } ?: fail(field)
    }

    private fun JsonElement.requiredString(path: String): String {
        val value = this as? JsonPrimitive ?: fail(path)
        return value.content.takeIf { value.isString } ?: fail(path)
    }

    private fun JsonObject.boolean(field: String): Boolean {
        val value = get(field) as? JsonPrimitive ?: fail(field)
        if (value.isString) fail(field)
        return value.booleanOrNull ?: fail(field)
    }

    private fun JsonObject.nullableString(field: String): String? = when (val value = get(field)) {
        JsonNull -> null
        is JsonPrimitive -> value.content.takeIf { value.isString } ?: fail(field)
        else -> fail(field)
    }

    private fun JsonObject.obj(field: String): JsonObject = get(field) as? JsonObject ?: fail(field)

    private fun JsonObject.nullableObject(field: String): JsonObject? = when (val value = get(field)) {
        JsonNull -> null
        is JsonObject -> value
        else -> fail(field)
    }

    private fun JsonObject.array(field: String): JsonArray = get(field) as? JsonArray ?: fail(field)

    private fun JsonElement.obj(path: String): JsonObject = this as? JsonObject ?: fail(path)

    private fun <T> unwrap(result: SpecializedManifestResult<T>): T = when (result) {
        is SpecializedManifestResult.Success -> result.value
        is SpecializedManifestResult.Failure -> throw DecodeException(result.error)
    }

    private fun <T> unwrap(result: CommonManifestResult<T>): T = when (result) {
        is CommonManifestResult.Success -> result.value
        is CommonManifestResult.Failure -> throw DecodeException(SpecializedManifestError.InvalidField("common"))
    }

    private fun fail(path: String): Nothing = throw DecodeException(SpecializedManifestError.InvalidField(path))

    private class DecodeException(val error: SpecializedManifestError) : RuntimeException()
}
