package io.redi.robot.context.api

import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Strict deterministic interchange codec. Persistence and snapshot ownership belong to later tasks. */
object ContextObservationCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(observation: ContextObservation): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(ContextObservation.SCHEMA_VERSION))
            put("observationId", JsonPrimitive(observation.observationId.value))
            put("sourceType", JsonPrimitive(observation.sourceType.value))
            put("sourceReference", JsonPrimitive(observation.sourceReference.value))
            put("key", JsonPrimitive(observation.key.value))
            put("claim", encodeClaim(observation.claim))
            put("confidencePermille", JsonPrimitive(observation.confidence.permille))
            put("privacyClassification", JsonPrimitive(observation.privacyClassification.wireValue))
            put("observedAtMonotonicMs", JsonPrimitive(observation.observedAt.value))
            put("expiresAtMonotonicMs", JsonPrimitive(observation.expiresAt.value))
        },
    )

    fun decode(text: String): ContextObservationResult<ContextObservation> = decode {
        val root = parseRoot(text)
        root.requireRootKeys(
            "schemaVersion",
            "observationId",
            "sourceType",
            "sourceReference",
            "key",
            "claim",
            "confidencePermille",
            "privacyClassification",
            "observedAtMonotonicMs",
            "expiresAtMonotonicMs",
        )
        val version = root.string("schemaVersion")
        if (version != ContextObservation.SCHEMA_VERSION) {
            throw ContextParseException(ContextObservationError.UnsupportedSchemaVersion(version))
        }
        unwrap(
            ContextObservation.create(
                observationId = unwrap(ContextObservationId.parse(root.string("observationId"))),
                sourceType = unwrap(ContextSourceType.parse(root.string("sourceType"))),
                sourceReference = unwrap(ContextSourceReference.parse(root.string("sourceReference"))),
                key = unwrap(ContextKey.parse(root.string("key"))),
                claim = parseClaim(root.obj("claim")),
                confidence = unwrap(ContextConfidence.create(root.int("confidencePermille"))),
                privacyClassification = privacy(root.string("privacyClassification")),
                observedAt = monotonic(root.long("observedAtMonotonicMs"), "observedAtMonotonicMs"),
                expiresAt = monotonic(root.long("expiresAtMonotonicMs"), "expiresAtMonotonicMs"),
            ),
        )
    }

    private fun encodeClaim(claim: ContextObservationClaim): JsonObject = buildJsonObject {
        put("status", JsonPrimitive(claim.wireStatus))
        when (claim) {
            is ContextObservationClaim.Known -> put("value", encodeValue(claim.value))
            is ContextObservationClaim.Unknown -> put("reason", JsonPrimitive(claim.reason.wireValue))
            is ContextObservationClaim.Conflict -> put(
                "candidates",
                buildJsonArray { claim.candidates.forEach { add(encodeValue(it)) } },
            )
        }
    }

    private fun encodeValue(value: ContextValue): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(value.wireType))
        put(
            "value",
            when (value) {
                is ContextValue.BooleanValue -> JsonPrimitive(value.value)
                is ContextValue.IntegerValue -> JsonPrimitive(value.value)
                is ContextValue.DecimalValue -> JsonPrimitive(value.canonicalValue)
                is ContextValue.TextValue -> JsonPrimitive(value.value.value)
                is ContextValue.SymbolValue -> JsonPrimitive(value.value.value)
            },
        )
    }

    private fun parseClaim(root: JsonObject): ContextObservationClaim = when (root.string("status")) {
        "known" -> {
            root.requireKeys("claim", "status", "value")
            ContextObservationClaim.Known(parseValue(root.obj("value"), "claim.value"))
        }
        "unknown" -> {
            root.requireKeys("claim", "status", "reason")
            val reason = ContextUnknownReason.fromWireValue(root.string("reason")) ?: fail("claim.reason")
            ContextObservationClaim.Unknown(reason)
        }
        "conflict" -> {
            root.requireKeys("claim", "status", "candidates")
            val candidates = root.array("candidates").mapIndexed { index, element ->
                parseValue(element as? JsonObject ?: fail("claim.candidates[$index]"), "claim.candidates[$index]")
            }
            unwrap(ContextObservationClaim.Conflict.create(candidates))
        }
        else -> fail("claim.status")
    }

    private fun parseValue(root: JsonObject, path: String): ContextValue {
        root.requireKeys(path, "type", "value")
        return when (root.string("type")) {
            "boolean" -> ContextValue.BooleanValue(root.boolean("value"))
            "integer" -> ContextValue.IntegerValue(root.long("value"))
            "decimal" -> unwrap(ContextValue.DecimalValue.parse(root.string("value")))
            "text" -> ContextValue.TextValue(unwrap(ContextText.create(root.string("value"))))
            "symbol" -> ContextValue.SymbolValue(unwrap(ContextSymbol.parse(root.string("value"))))
            else -> fail("$path.type")
        }
    }

    private fun privacy(raw: String): ContextPrivacyClassification =
        ContextPrivacyClassification.fromWireValue(raw) ?: fail("privacyClassification")

    private fun parseRoot(text: String): JsonObject = runCatching {
        Json.parseToJsonElement(text) as? JsonObject
    }.getOrNull() ?: fail("observation")

    private fun JsonObject.requireRootKeys(vararg expected: String) {
        val expectedSet = expected.toSet()
        val missing = expectedSet - keys
        if (missing.isNotEmpty()) {
            throw ContextParseException(ContextObservationError.MissingField(missing.sorted().first()))
        }
        val unexpected = keys - expectedSet
        if (unexpected.isNotEmpty()) {
            throw ContextParseException(ContextObservationError.UnexpectedFields(unexpected.toSortedSet()))
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

    private fun JsonObject.long(field: String): Long {
        val value = get(field) as? JsonPrimitive ?: fail(field)
        return value.longOrNull?.takeIf { !value.isString } ?: fail(field)
    }

    private fun JsonObject.int(field: String): Int {
        val value = get(field) as? JsonPrimitive ?: fail(field)
        return value.intOrNull?.takeIf { !value.isString } ?: fail(field)
    }

    private fun JsonObject.boolean(field: String): Boolean {
        val value = get(field) as? JsonPrimitive ?: fail(field)
        return value.booleanOrNull?.takeIf { !value.isString } ?: fail(field)
    }

    private fun monotonic(value: Long, field: String): MonotonicTimestampMs = when (
        val result = MonotonicTimestampMs.create(value)
    ) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> fail(field)
    }

    private fun <T> unwrap(result: ContextObservationResult<T>): T = when (result) {
        is ContextObservationResult.Success -> result.value
        is ContextObservationResult.Failure -> throw ContextParseException(result.error)
    }

    private fun <T> decode(block: () -> T): ContextObservationResult<T> = try {
        ContextObservationResult.Success(block())
    } catch (error: ContextParseException) {
        ContextObservationResult.Failure(error.error)
    }

    private fun fail(field: String): Nothing = throw ContextParseException(
        ContextObservationError.InvalidDocument(field),
    )

    private class ContextParseException(val error: ContextObservationError) : RuntimeException()
}
