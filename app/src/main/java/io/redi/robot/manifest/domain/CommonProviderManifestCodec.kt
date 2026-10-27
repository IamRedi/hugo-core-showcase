package io.redi.robot.manifest.domain

import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
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

object CommonProviderManifestCodec {
    private val json = Json { ignoreUnknownKeys = false }
    private val rootFields = setOf(
        "schemaVersion",
        "digest",
        "provider",
        "adapter",
        "capabilities",
        "connectionMethods",
        "requirements",
        "compatibility",
        "extensions",
        "provenance",
    )

    fun encode(manifest: CommonProviderManifest): String {
        val payload = encodePayload(manifest)
        val digest = digest(payload)
        return encodeJson(
            buildJsonObject {
                put("schemaVersion", payload.getValue("schemaVersion"))
                put("digest", JsonPrimitive(digest.value))
                rootFields.filterNot { it == "schemaVersion" || it == "digest" }.forEach { field ->
                    put(field, payload.getValue(field))
                }
            },
        )
    }

    fun decode(text: String): CommonManifestResult<CommonProviderManifestDocument> = try {
        val root = parseRoot(text)
        root.requireExactFields("manifest", rootFields)
        val schemaVersion = unwrap(ManifestSchemaVersion.parse(root.string("schemaVersion")))
        if (schemaVersion.major != CommonProviderManifest.supportedSchemaMajor) {
            throw ManifestDecodeException(
                CommonManifestError.UnsupportedSchemaVersion(schemaVersion.wireValue),
            )
        }
        val declaredDigest = unwrap(ManifestDigest.parse(root.string("digest")))
        val manifest = unwrap(
            CommonProviderManifest.create(
                schemaVersion = schemaVersion,
                provider = parseProvider(root.obj("provider")),
                adapter = parseAdapter(root.obj("adapter")),
                capabilities = root.array("capabilities").mapIndexed { index, element ->
                    val value = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: fail("capabilities[$index]")
                    parseCapability(value, "capabilities[$index]")
                },
                connectionMethods = root.array("connectionMethods").mapIndexed { index, element ->
                    parseConnection(element.obj("connectionMethods[$index]"), index)
                },
                requirements = root.array("requirements").mapIndexed { index, element ->
                    parseRequirement(element.obj("requirements[$index]"), index)
                },
                compatibility = root.array("compatibility").mapIndexed { index, element ->
                    parseCompatibility(element.obj("compatibility[$index]"), index)
                },
                extensions = root.array("extensions").mapIndexed { index, element ->
                    parseExtension(element.obj("extensions[$index]"), index)
                },
                provenance = parseProvenance(root.obj("provenance")),
            ),
        )
        val computedDigest = digest(encodePayload(manifest))
        if (declaredDigest != computedDigest) {
            throw ManifestDecodeException(
                CommonManifestError.DigestMismatch(declaredDigest.value, computedDigest.value),
            )
        }
        CommonManifestResult.Success(CommonProviderManifestDocument(manifest, declaredDigest))
    } catch (error: ManifestDecodeException) {
        CommonManifestResult.Failure(error.error)
    }

    private fun encodePayload(manifest: CommonProviderManifest): JsonObject = buildJsonObject {
        put("schemaVersion", JsonPrimitive(manifest.schemaVersion.wireValue))
        put("provider", encodeProvider(manifest.provider))
        put("adapter", encodeAdapter(manifest.adapter))
        put("capabilities", buildJsonArray {
            manifest.capabilities.forEach { add(JsonPrimitive(it.wireValue)) }
        })
        put("connectionMethods", buildJsonArray {
            manifest.connectionMethods.forEach { method ->
                add(buildJsonObject {
                    put("reference", JsonPrimitive(method.reference.wireValue))
                    put("required", JsonPrimitive(method.required))
                })
            }
        })
        put("requirements", buildJsonArray {
            manifest.requirements.forEach { requirement ->
                add(buildJsonObject {
                    put("kind", JsonPrimitive(requirement.kind.name.lowercase()))
                    put("reference", JsonPrimitive(requirement.reference.wireValue))
                    put("required", JsonPrimitive(requirement.required))
                })
            }
        })
        put("compatibility", buildJsonArray {
            manifest.compatibility.forEach { constraint ->
                add(buildJsonObject {
                    put("subject", JsonPrimitive(constraint.subject.name.lowercase()))
                    putVersionRange(constraint.versions)
                })
            }
        })
        put("extensions", buildJsonArray {
            manifest.extensions.forEach { extension ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(extension.id.value))
                    put("schemaVersion", JsonPrimitive(extension.schemaVersion.wireValue))
                    put("digest", JsonPrimitive(extension.digest.value))
                })
            }
        })
        put("provenance", buildJsonObject {
            put("source", JsonPrimitive(manifest.provenance.source.name.lowercase()))
            put("issuer", JsonPrimitive(manifest.provenance.issuer.value))
            put("sourceReference", JsonPrimitive(manifest.provenance.sourceReference.value))
            put("evidence", JsonPrimitive(manifest.provenance.evidence.name.lowercase()))
        })
    }

    private fun encodeProvider(provider: ManifestProviderDeclaration): JsonObject = buildJsonObject {
        put("definitionId", JsonPrimitive(provider.definitionId.value))
        put("family", JsonPrimitive(provider.family.wireValue))
        put("displayName", JsonPrimitive(provider.displayName.value))
        put("product", provider.product?.let { product ->
            buildJsonObject {
                put("vendor", product.vendor?.let { JsonPrimitive(it.value) } ?: JsonNull)
                put("model", product.model?.let { JsonPrimitive(it.value) } ?: JsonNull)
                put("version", product.version?.let { JsonPrimitive(it.value) } ?: JsonNull)
            }
        } ?: JsonNull)
    }

    private fun encodeAdapter(adapter: AdapterCompatibilityReference): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(adapter.adapterId.value))
        putVersionRange(adapter.versions)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putVersionRange(
        range: ManifestVersionRange,
    ) {
        put(
            "minimumVersionInclusive",
            range.minimumInclusive?.let { JsonPrimitive(it.wireValue) } ?: JsonNull,
        )
        put(
            "maximumVersionExclusive",
            range.maximumExclusive?.let { JsonPrimitive(it.wireValue) } ?: JsonNull,
        )
    }

    private fun parseProvider(root: JsonObject): ManifestProviderDeclaration {
        root.requireExactFields(
            "provider",
            setOf("definitionId", "family", "displayName", "product"),
        )
        val definition = when (val parsed = ProviderDefinitionId.parse(root.string("definitionId"))) {
            is ProviderIdentityResult.Success -> parsed.value
            is ProviderIdentityResult.Failure -> fail("provider.definitionId")
        }
        val family = ProviderFamily.fromWireValue(root.string("family")) ?: fail("provider.family")
        return ManifestProviderDeclaration(
            definitionId = definition,
            family = family,
            displayName = text(root.string("displayName"), "provider.displayName"),
            product = root.nullableObject("product")?.let(::parseProduct),
        )
    }

    private fun parseProduct(root: JsonObject): ManifestProductMetadata {
        root.requireExactFields("provider.product", setOf("vendor", "model", "version"))
        return unwrap(
            ManifestProductMetadata.create(
                vendor = root.nullableString("vendor")?.let { text(it, "provider.product.vendor") },
                model = root.nullableString("model")?.let { text(it, "provider.product.model") },
                version = root.nullableString("version")?.let { text(it, "provider.product.version") },
            ),
        )
    }

    private fun parseAdapter(root: JsonObject): AdapterCompatibilityReference {
        root.requireExactFields(
            "adapter",
            setOf("id", "minimumVersionInclusive", "maximumVersionExclusive"),
        )
        return AdapterCompatibilityReference(
            adapterId = unwrap(AdapterDefinitionReference.parse(root.string("id"))),
            versions = parseVersionRange(root, "adapter"),
        )
    }

    private fun parseConnection(root: JsonObject, index: Int): ManifestConnectionMethod {
        val path = "connectionMethods[$index]"
        root.requireExactFields(path, setOf("reference", "required"))
        return ManifestConnectionMethod(
            reference = contract(root.string("reference"), "$path.reference"),
            required = root.boolean("required"),
        )
    }

    private fun parseRequirement(root: JsonObject, index: Int): ManifestRequirementReference {
        val path = "requirements[$index]"
        root.requireExactFields(path, setOf("kind", "reference", "required"))
        return ManifestRequirementReference(
            kind = enum(root.string("kind"), "$path.kind"),
            reference = contract(root.string("reference"), "$path.reference"),
            required = root.boolean("required"),
        )
    }

    private fun parseCompatibility(root: JsonObject, index: Int): ManifestCompatibilityConstraint {
        val path = "compatibility[$index]"
        root.requireExactFields(
            path,
            setOf("subject", "minimumVersionInclusive", "maximumVersionExclusive"),
        )
        return ManifestCompatibilityConstraint(
            subject = enum(root.string("subject"), "$path.subject"),
            versions = parseVersionRange(root, path),
        )
    }

    private fun parseExtension(root: JsonObject, index: Int): ManifestExtensionReference {
        val path = "extensions[$index]"
        root.requireExactFields(path, setOf("id", "schemaVersion", "digest"))
        return ManifestExtensionReference(
            id = unwrap(ManifestExtensionId.parse(root.string("id"))),
            schemaVersion = unwrap(ManifestSchemaVersion.parse(root.string("schemaVersion"))),
            digest = unwrap(ManifestDigest.parse(root.string("digest"))),
        )
    }

    private fun parseProvenance(root: JsonObject): ManifestProvenance {
        root.requireExactFields(
            "provenance",
            setOf("source", "issuer", "sourceReference", "evidence"),
        )
        return ManifestProvenance(
            source = enum(root.string("source"), "provenance.source"),
            issuer = text(root.string("issuer"), "provenance.issuer"),
            sourceReference = unwrap(
                ManifestSourceReference.parse(root.string("sourceReference")),
            ),
            evidence = enum(root.string("evidence"), "provenance.evidence"),
        )
    }

    private fun parseVersionRange(root: JsonObject, path: String): ManifestVersionRange = unwrap(
        ManifestVersionRange.create(
            minimumInclusive = root.nullableString("minimumVersionInclusive")?.let {
                version(it, "$path.minimumVersionInclusive")
            },
            maximumExclusive = root.nullableString("maximumVersionExclusive")?.let {
                version(it, "$path.maximumVersionExclusive")
            },
        ),
    )

    private fun digest(payload: JsonObject): ManifestDigest {
        val bytes = MessageDigest.getInstance("SHA-256").digest(
            encodeJson(payload).toByteArray(Charsets.UTF_8),
        )
        return ManifestDigest.fromSha256(bytes.joinToString("") { "%02x".format(it) })
    }

    private fun parseCapability(raw: String, path: String): CapabilityReference = when (
        val result = CapabilityReference.parse(raw)
    ) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> fail(path)
    }

    private fun contract(raw: String, path: String): ManifestContractReference = when (
        val result = ManifestContractReference.parse(raw)
    ) {
        is CommonManifestResult.Success -> result.value
        is CommonManifestResult.Failure -> fail(path)
    }

    private fun version(raw: String, path: String): ManifestVersion = when (
        val result = ManifestVersion.parse(raw)
    ) {
        is CommonManifestResult.Success -> result.value
        is CommonManifestResult.Failure -> fail(path)
    }

    private fun text(raw: String, path: String): ManifestText = when (
        val result = ManifestText.parse(raw)
    ) {
        is CommonManifestResult.Success -> result.value
        is CommonManifestResult.Failure -> fail(path)
    }

    private inline fun <reified T : Enum<T>> enum(raw: String, path: String): T =
        enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: fail(path)

    private fun parseRoot(text: String): JsonObject = runCatching {
        Json.parseToJsonElement(text) as? JsonObject
    }.getOrNull() ?: throw ManifestDecodeException(CommonManifestError.MalformedDocument)

    private fun encodeJson(element: JsonElement): String = json.encodeToString(
        JsonElement.serializer(),
        element,
    )

    private fun JsonObject.requireExactFields(path: String, expected: Set<String>) {
        val missing = expected - keys
        if (missing.isNotEmpty()) {
            throw ManifestDecodeException(CommonManifestError.MissingFields(path, missing.toSortedSet()))
        }
        val unexpected = keys - expected
        if (unexpected.isNotEmpty()) {
            throw ManifestDecodeException(
                CommonManifestError.UnexpectedFields(path, unexpected.toSortedSet()),
            )
        }
    }

    private fun JsonObject.string(field: String): String {
        val value = get(field) as? JsonPrimitive ?: fail(field)
        return value.content.takeIf { value.isString } ?: fail(field)
    }

    private fun JsonObject.nullableString(field: String): String? = when (val value = get(field)) {
        JsonNull -> null
        is JsonPrimitive -> value.content.takeIf { value.isString } ?: fail(field)
        else -> fail(field)
    }

    private fun JsonObject.boolean(field: String): Boolean {
        val value = get(field) as? JsonPrimitive ?: fail(field)
        if (value.isString) fail(field)
        return value.booleanOrNull ?: fail(field)
    }

    private fun JsonObject.obj(field: String): JsonObject = get(field) as? JsonObject ?: fail(field)

    private fun JsonObject.nullableObject(field: String): JsonObject? = when (val value = get(field)) {
        JsonNull -> null
        is JsonObject -> value
        else -> fail(field)
    }

    private fun JsonObject.array(field: String): JsonArray = get(field) as? JsonArray ?: fail(field)

    private fun JsonElement.obj(path: String): JsonObject = this as? JsonObject ?: fail(path)

    private fun <T> unwrap(result: CommonManifestResult<T>): T = when (result) {
        is CommonManifestResult.Success -> result.value
        is CommonManifestResult.Failure -> throw ManifestDecodeException(result.error)
    }

    private fun fail(path: String): Nothing = throw ManifestDecodeException(
        CommonManifestError.InvalidField(path),
    )

    private class ManifestDecodeException(val error: CommonManifestError) : RuntimeException()
}
