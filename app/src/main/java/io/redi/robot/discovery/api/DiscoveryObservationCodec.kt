package io.redi.robot.discovery.api

import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderFamily
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Strict protected-storage codec. Diagnostics must use [DiscoveryObservation.redacted]. */
object DiscoveryObservationCodec {
    private const val schemaVersion = "1.0"
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(observation: DiscoveryObservation): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(schemaVersion))
            put("observationId", JsonPrimitive(observation.observationId.value))
            put("sourceType", JsonPrimitive(observation.sourceType.value))
            put("observingHostReference", JsonPrimitive(observation.observingHost.value))
            put("observedAtMonotonicMs", JsonPrimitive(observation.observedAt.value))
            put("expiresAtMonotonicMs", JsonPrimitive(observation.expiresAt.value))
            put("displayHint", observation.displayHint?.let { JsonPrimitive(it.value) } ?: JsonNull)
            put(
                "providerFamilyHint",
                observation.providerFamilyHint?.let { JsonPrimitive(it.wireValue) } ?: JsonNull,
            )
            put(
                "protocolServiceHints",
                buildJsonArray {
                    observation.protocolServiceHints.sortedBy { it.value }.forEach { add(JsonPrimitive(it.value)) }
                },
            )
            put(
                "evidence",
                buildJsonArray {
                    observation.evidence.forEach { evidence ->
                        add(
                            buildJsonObject {
                                put("kind", JsonPrimitive(evidence.kind.value))
                                put("observedAtMonotonicMs", JsonPrimitive(evidence.observedAt.value))
                                put("summary", evidence.summary?.let { JsonPrimitive(it.value) } ?: JsonNull)
                            },
                        )
                    }
                },
            )
            put("confidence", JsonPrimitive(observation.confidence.value))
            put("privacyClassification", JsonPrimitive(observation.privacyClassification.wireValue))
            put(
                "sourceLocalReference",
                observation.sourceLocalReference?.let { JsonPrimitive(it.value) } ?: JsonNull,
            )
            put(
                "metadata",
                buildJsonObject {
                    observation.metadata.toSortedMap(compareBy { it.value }).forEach { (key, value) ->
                        put(key.value, JsonPrimitive(value.value))
                    }
                },
            )
        },
    )

    fun decode(text: String): DiscoveryObservationResult<DiscoveryObservation> = decode {
        val root = parseRoot(text)
        root.requireRootKeys(
            "schemaVersion",
            "observationId",
            "sourceType",
            "observingHostReference",
            "observedAtMonotonicMs",
            "expiresAtMonotonicMs",
            "displayHint",
            "providerFamilyHint",
            "protocolServiceHints",
            "evidence",
            "confidence",
            "privacyClassification",
            "sourceLocalReference",
            "metadata",
        )
        requireSchema(root)
        unwrap(
            DiscoveryObservation.create(
                observationId = unwrap(DiscoveryObservationId.parse(root.string("observationId"))),
                sourceType = unwrap(DiscoverySourceType.parse(root.string("sourceType"))),
                observingHost = unwrap(DiscoveryHostReference.parse(root.string("observingHostReference"))),
                observedAt = monotonic(root.long("observedAtMonotonicMs"), "observedAtMonotonicMs"),
                expiresAt = monotonic(root.long("expiresAtMonotonicMs"), "expiresAtMonotonicMs"),
                displayHint = root.nullableString("displayHint")?.let {
                    unwrap(DiscoveryDisplayHint.create(it))
                },
                providerFamilyHint = root.nullableString("providerFamilyHint")?.let(::providerFamily),
                protocolServiceHints = root.array("protocolServiceHints").mapIndexed { index, element ->
                    unwrap(DiscoveryProtocolServiceHint.parse(element.string("protocolServiceHints[$index]")))
                },
                evidence = root.array("evidence").mapIndexed(::parseEvidence),
                confidence = unwrap(DiscoveryConfidence.create(root.double("confidence"))),
                privacyClassification = privacyClassification(root.string("privacyClassification")),
                sourceLocalReference = root.nullableString("sourceLocalReference")?.let {
                    unwrap(OpaqueDiscoveryReference.parse(it))
                },
                metadata = parseMetadata(root.obj("metadata")),
            ),
        )
    }

    private fun parseEvidence(index: Int, element: JsonElement): DiscoveryEvidence {
        val root = element as? JsonObject ?: fail("evidence[$index]")
        root.requireKeys("evidence[$index]", "kind", "observedAtMonotonicMs", "summary")
        return DiscoveryEvidence(
            kind = unwrap(DiscoveryEvidenceKind.parse(root.string("kind"))),
            observedAt = monotonic(root.long("observedAtMonotonicMs"), "evidence[$index].observedAtMonotonicMs"),
            summary = root.nullableString("summary")?.let { unwrap(DiscoveryEvidenceSummary.create(it)) },
        )
    }

    private fun parseMetadata(root: JsonObject): Map<DiscoveryMetadataKey, DiscoveryMetadataValue> =
        root.entries.associate { (key, value) ->
            unwrap(DiscoveryMetadataKey.parse(key)) to unwrap(
                DiscoveryMetadataValue.create(value.string("metadata.$key")),
            )
        }

    private fun providerFamily(raw: String): ProviderFamily = ProviderFamily.fromWireValue(raw)
        ?: fail("providerFamilyHint")

    private fun privacyClassification(raw: String): DiscoveryPrivacyClassification =
        DiscoveryPrivacyClassification.fromWireValue(raw) ?: fail("privacyClassification")

    private fun requireSchema(root: JsonObject) {
        val found = root.string("schemaVersion")
        if (found != schemaVersion) {
            throw DiscoveryParseException(DiscoveryObservationError.UnsupportedSchemaVersion(found))
        }
    }

    private fun parseRoot(text: String): JsonObject = runCatching {
        Json.parseToJsonElement(text) as? JsonObject
    }.getOrNull() ?: fail("observation")

    private fun JsonObject.requireRootKeys(vararg expected: String) {
        val expectedSet = expected.toSet()
        val missing = expectedSet - keys
        if (missing.isNotEmpty()) {
            throw DiscoveryParseException(DiscoveryObservationError.MissingField(missing.sorted().first()))
        }
        val unexpected = keys - expectedSet
        if (unexpected.isNotEmpty()) {
            throw DiscoveryParseException(DiscoveryObservationError.UnexpectedFields(unexpected.toSortedSet()))
        }
    }

    private fun JsonObject.requireKeys(path: String, vararg expected: String) {
        if (keys != expected.toSet()) fail(path)
    }

    private fun JsonObject.obj(field: String): JsonObject = get(field) as? JsonObject ?: fail(field)

    private fun JsonObject.array(field: String): JsonArray = get(field) as? JsonArray ?: fail(field)

    private fun JsonObject.string(field: String): String {
        val value = get(field) as? JsonPrimitive ?: fail(field)
        return value.content.takeIf { value.isString } ?: fail(field)
    }

    private fun JsonObject.nullableString(field: String): String? = when (val value = get(field)) {
        JsonNull -> null
        is JsonPrimitive -> value.content.takeIf { value.isString } ?: fail(field)
        else -> fail(field)
    }

    private fun JsonObject.long(field: String): Long {
        val value = get(field) as? JsonPrimitive ?: fail(field)
        return value.longOrNull?.takeIf { !value.isString } ?: fail(field)
    }

    private fun JsonObject.double(field: String): Double {
        val value = get(field) as? JsonPrimitive ?: fail(field)
        return value.doubleOrNull?.takeIf { !value.isString } ?: fail(field)
    }

    private fun JsonElement.string(path: String): String {
        val value = this as? JsonPrimitive ?: fail(path)
        return value.content.takeIf { value.isString } ?: fail(path)
    }

    private fun monotonic(value: Long, field: String): MonotonicTimestampMs = when (
        val result = MonotonicTimestampMs.create(value)
    ) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> fail(field)
    }

    private fun <T> unwrap(result: DiscoveryObservationResult<T>): T = when (result) {
        is DiscoveryObservationResult.Success -> result.value
        is DiscoveryObservationResult.Failure -> throw DiscoveryParseException(result.error)
    }

    private fun <T> decode(block: () -> T): DiscoveryObservationResult<T> = try {
        DiscoveryObservationResult.Success(block())
    } catch (error: DiscoveryParseException) {
        DiscoveryObservationResult.Failure(error.error)
    }

    private fun fail(field: String): Nothing = throw DiscoveryParseException(
        DiscoveryObservationError.InvalidDocument(field),
    )

    private class DiscoveryParseException(val error: DiscoveryObservationError) : RuntimeException()
}
