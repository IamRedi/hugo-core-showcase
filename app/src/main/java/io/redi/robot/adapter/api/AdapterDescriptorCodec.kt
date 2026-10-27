package io.redi.robot.adapter.api

import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.provider.ProviderFamily
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Strict, canonical serialization for a declarative adapter descriptor; not an install format. */
object AdapterDescriptorCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    fun encode(descriptor: AdapterDescriptor): String = encodeJson(
        JsonObject(
            linkedMapOf(
                "schemaVersion" to text(descriptor.schemaVersion.wireValue),
                "identity" to JsonObject(linkedMapOf(
                    "id" to text(descriptor.identity.id.wireValue),
                    "version" to text(descriptor.identity.version.wireValue),
                )),
                "packageDigest" to text(descriptor.packageDigest.wireValue),
                "provenance" to JsonObject(linkedMapOf(
                    "source" to text(descriptor.provenance.source.name),
                    "publisher" to text(descriptor.provenance.publisher.wireValue),
                    "sourceReference" to text(descriptor.provenance.sourceReference.wireValue),
                )),
                "manifestSupport" to JsonArray(descriptor.manifestSupport.map(::encodeSupport)),
                "supportedCapabilities" to JsonArray(
                    descriptor.supportedCapabilities.map { text(it.wireValue) },
                ),
                "runtimeCompatibility" to encodeRuntimeCompatibility(descriptor.runtimeCompatibility),
            ),
        ),
    )

    fun decode(text: String): AdapterResult<AdapterDescriptor> = try {
        val root = parseRoot(text)
        root.requireExactFields(
            "adapterDescriptor",
            setOf(
                "schemaVersion", "identity", "packageDigest", "provenance", "manifestSupport",
                "supportedCapabilities", "runtimeCompatibility",
            ),
        )
        val identity = root.obj("identity").also {
            it.requireExactFields("identity", setOf("id", "version"))
        }
        val provenance = root.obj("provenance").also {
            it.requireExactFields("provenance", setOf("source", "publisher", "sourceReference"))
        }
        val descriptor = unwrap(AdapterDescriptor.create(
            schemaVersion = unwrap(AdapterSchemaVersion.parse(root.string("schemaVersion"))),
            identity = AdapterIdentity(
                unwrap(AdapterId.parse(identity.string("id"))),
                unwrap(AdapterVersion.parse(identity.string("version"))),
            ),
            packageDigest = unwrap(AdapterPackageDigest.parse(root.string("packageDigest"))),
            provenance = AdapterProvenance(
                enumValue<AdapterProvenanceSource>(provenance.string("source"), "provenance.source"),
                unwrap(AdapterPublisherReference.parse(provenance.string("publisher"))),
                unwrap(AdapterSourceReference.parse(provenance.string("sourceReference"))),
            ),
            manifestSupport = root.array("manifestSupport").mapIndexed { index, element ->
                parseSupport(element.obj("manifestSupport[$index]"), index)
            },
            supportedCapabilities = parseCapabilities(root.array("supportedCapabilities")),
            runtimeCompatibility = parseRuntimeCompatibility(root["runtimeCompatibility"]),
        ))
        AdapterResult.Success(descriptor)
    } catch (exception: DecodeException) {
        AdapterResult.Failure(exception.error)
    }

    private fun encodeSupport(support: AdapterManifestSupport): JsonObject = JsonObject(
        linkedMapOf(
            "providerFamily" to text(support.providerFamily.name),
            "commonManifestSchemaMajor" to JsonPrimitive(support.commonManifestSchemaMajor),
            "specialization" to (support.specialization?.let {
                JsonObject(linkedMapOf(
                    "kind" to text(it.kind.name),
                    "schemaMajor" to JsonPrimitive(it.schemaMajor),
                ))
            } ?: JsonNull),
        ),
    )

    private fun encodeRuntimeCompatibility(value: HugoRuntimeCompatibility?): JsonElement = value?.let {
        JsonObject(linkedMapOf(
            "minimumInclusive" to (it.minimumInclusive?.let { version -> text(version.wireValue) } ?: JsonNull),
            "maximumExclusive" to (it.maximumExclusive?.let { version -> text(version.wireValue) } ?: JsonNull),
        ))
    } ?: JsonNull

    private fun parseSupport(root: JsonObject, index: Int): AdapterManifestSupport {
        root.requireExactFields(
            "manifestSupport[$index]",
            setOf("providerFamily", "commonManifestSchemaMajor", "specialization"),
        )
        val specialization = when (val value = root["specialization"]) {
            JsonNull -> null
            is JsonObject -> value.also {
                it.requireExactFields("manifestSupport[$index].specialization", setOf("kind", "schemaMajor"))
            }.let {
                AdapterSpecializedManifestSupport(
                    enumValue<AdapterManifestKind>(it.string("kind"), "manifestSupport[$index].specialization.kind"),
                    it.positiveInt("schemaMajor"),
                )
            }
            else -> fail("manifestSupport[$index].specialization")
        }
        return AdapterManifestSupport(
            providerFamily = enumValue<ProviderFamily>(root.string("providerFamily"), "manifestSupport[$index].providerFamily"),
            commonManifestSchemaMajor = root.positiveInt("commonManifestSchemaMajor"),
            specialization = specialization,
        )
    }

    private fun parseCapabilities(values: JsonArray): List<CapabilityReference> = values.mapIndexed { index, value ->
        when (val result = CapabilityReference.parse(value.requiredString("supportedCapabilities[$index]"))) {
            is CapabilityIdentityResult.Success -> result.value
            is CapabilityIdentityResult.Failure -> fail("supportedCapabilities[$index]")
        }
    }

    private fun parseRuntimeCompatibility(value: JsonElement?): HugoRuntimeCompatibility? = when (value) {
        JsonNull -> null
        is JsonObject -> {
            value.requireExactFields("runtimeCompatibility", setOf("minimumInclusive", "maximumExclusive"))
            unwrap(HugoRuntimeCompatibility.create(
                nullableVersion(value["minimumInclusive"], "runtimeCompatibility.minimumInclusive"),
                nullableVersion(value["maximumExclusive"], "runtimeCompatibility.maximumExclusive"),
            ))
        }
        else -> fail("runtimeCompatibility")
    }

    private fun nullableVersion(value: JsonElement?, path: String): HugoRuntimeVersion? = when (value) {
        JsonNull -> null
        else -> unwrap(HugoRuntimeVersion.parse(value?.requiredString(path) ?: fail(path)))
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String, path: String): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fail(path)

    private fun parseRoot(text: String): JsonObject = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject
    }.getOrNull() ?: throw DecodeException(AdapterError.MalformedDocument)

    private fun encodeJson(element: JsonElement): String = json.encodeToString(
        JsonElement.serializer(),
        element,
    )

    private fun JsonObject.requireExactFields(path: String, expected: Set<String>) {
        val missing = expected - keys
        if (missing.isNotEmpty()) throw DecodeException(AdapterError.MissingFields(path, missing.toSortedSet()))
        val unexpected = keys - expected
        if (unexpected.isNotEmpty()) throw DecodeException(AdapterError.UnexpectedFields(path, unexpected.toSortedSet()))
    }

    private fun JsonObject.string(field: String): String = get(field)?.requiredString(field) ?: fail(field)

    private fun JsonElement.requiredString(path: String): String {
        val primitive = this as? JsonPrimitive ?: fail(path)
        return primitive.content.takeIf { primitive.isString } ?: fail(path)
    }

    private fun JsonObject.positiveInt(field: String): Int {
        val primitive = get(field) as? JsonPrimitive ?: fail(field)
        if (primitive.isString) fail(field)
        return primitive.intOrNull?.takeIf { it > 0 } ?: fail(field)
    }

    private fun JsonObject.obj(field: String): JsonObject = get(field) as? JsonObject ?: fail(field)

    private fun JsonElement.obj(path: String): JsonObject = this as? JsonObject ?: fail(path)

    private fun JsonObject.array(field: String): JsonArray = get(field) as? JsonArray ?: fail(field)

    private fun text(value: String): JsonPrimitive = JsonPrimitive(value)

    private fun <T> unwrap(result: AdapterResult<T>): T = when (result) {
        is AdapterResult.Success -> result.value
        is AdapterResult.Failure -> throw DecodeException(result.error)
    }

    private fun fail(path: String): Nothing = throw DecodeException(AdapterError.InvalidField(path))

    private class DecodeException(val error: AdapterError) : RuntimeException()
}
