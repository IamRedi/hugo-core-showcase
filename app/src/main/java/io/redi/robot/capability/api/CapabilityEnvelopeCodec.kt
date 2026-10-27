package io.redi.robot.capability.api

import io.redi.robot.capability.provider.ProviderInstanceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

object CapabilityEnvelopeCodec {
    private const val schemaVersion = "1.0"
    private val json = Json { ignoreUnknownKeys = false }

    fun encodeRequest(request: CapabilityRequest): String = encode(
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(schemaVersion))
            put("requestId", JsonPrimitive(request.requestId.value))
            put("capability", JsonPrimitive(request.capability.wireValue))
            put("providerTarget", encodeTarget(request.providerTarget))
            put("actor", JsonPrimitive(request.actor.name.lowercase()))
            put("executionContext", encodeExecutionContext(request.executionContext))
            put("createdAtMonotonicMs", JsonPrimitive(request.createdAt.value))
            put("deadlineMonotonicMs", JsonPrimitive(request.deadline.value))
            put(
                "authorizationReference",
                request.authorizationReference?.let { JsonPrimitive(it.value) } ?: JsonNull,
            )
            put("auditId", JsonPrimitive(request.auditId.value))
            put("input", request.input?.let(::encodeData) ?: JsonNull)
        },
    )

    fun decodeRequest(text: String): CapabilityEnvelopeResult<CapabilityRequest> = decode {
        val root = parseRoot(text, "request")
        root.requireKeys(
            "request",
            "schemaVersion",
            "requestId",
            "capability",
            "providerTarget",
            "actor",
            "executionContext",
            "createdAtMonotonicMs",
            "deadlineMonotonicMs",
            "authorizationReference",
            "auditId",
            "input",
        )
        requireSchema(root)
        unwrap(
            CapabilityRequest.create(
                requestId = unwrap(CapabilityRequestId.parse(root.string("requestId"))),
                capability = parseCapability(root.string("capability")),
                providerTarget = parseTarget(root.obj("providerTarget")),
                actor = parseEnum<CapabilityActorKind>(root.string("actor"), "actor"),
                executionContext = parseExecutionContext(root.obj("executionContext")),
                createdAt = monotonic(root.long("createdAtMonotonicMs"), "createdAtMonotonicMs"),
                deadline = monotonic(root.long("deadlineMonotonicMs"), "deadlineMonotonicMs"),
                authorizationReference = root.nullableString("authorizationReference")?.let {
                    unwrap(CapabilityAuthorizationReference.parse(it))
                },
                auditId = unwrap(CapabilityAuditId.parse(root.string("auditId"))),
                input = root.nullableObject("input")?.let(::parseData),
            ),
        )
    }

    fun encodeResult(result: CapabilityResult): String = encode(
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(schemaVersion))
            put("requestId", JsonPrimitive(result.requestId.value))
            put("capability", JsonPrimitive(result.capability.wireValue))
            put("providerInstanceId", JsonPrimitive(result.providerInstanceId.value))
            put("executionContext", encodeExecutionContext(result.executionContext))
            put("occurredAtMonotonicMs", JsonPrimitive(result.occurredAt.value))
            put("state", JsonPrimitive(result.state.name.lowercase()))
            put("failure", result.failure?.let(::encodeFailure) ?: JsonNull)
            put("evidence", JsonPrimitive(result.evidence.name.lowercase()))
            put("output", result.output?.let(::encodeData) ?: JsonNull)
            put("auditId", JsonPrimitive(result.auditId.value))
        },
    )

    fun decodeResult(text: String): CapabilityEnvelopeResult<CapabilityResult> = decode {
        val root = parseRoot(text, "result")
        root.requireKeys(
            "result",
            "schemaVersion",
            "requestId",
            "capability",
            "providerInstanceId",
            "executionContext",
            "occurredAtMonotonicMs",
            "state",
            "failure",
            "evidence",
            "output",
            "auditId",
        )
        requireSchema(root)
        unwrap(
            CapabilityResult.create(
                requestId = unwrap(CapabilityRequestId.parse(root.string("requestId"))),
                capability = parseCapability(root.string("capability")),
                providerInstanceId = parseProviderInstance(root.string("providerInstanceId")),
                executionContext = parseExecutionContext(root.obj("executionContext")),
                occurredAt = monotonic(root.long("occurredAtMonotonicMs"), "occurredAtMonotonicMs"),
                state = parseEnum<CapabilityResultState>(root.string("state"), "state"),
                failure = root.nullableObject("failure")?.let(::parseFailure),
                evidence = parseEnum<CapabilityOutcomeEvidence>(root.string("evidence"), "evidence"),
                output = root.nullableObject("output")?.let(::parseData),
                auditId = unwrap(CapabilityAuditId.parse(root.string("auditId"))),
            ),
        )
    }

    private fun encode(element: JsonElement): String = json.encodeToString(
        JsonElement.serializer(),
        element,
    )

    private fun encodeTarget(target: CapabilityProviderTarget): JsonObject = buildJsonObject {
        when (target) {
            is CapabilityProviderTarget.Exact -> {
                put("type", JsonPrimitive("exact"))
                put("providerInstanceId", JsonPrimitive(target.providerInstanceId.value))
            }
            CapabilityProviderTarget.AnyCompatible -> {
                put("type", JsonPrimitive("any_compatible"))
            }
        }
    }

    private fun parseTarget(root: JsonObject): CapabilityProviderTarget = when (root.string("type")) {
        "exact" -> {
            root.requireKeys("providerTarget", "type", "providerInstanceId")
            CapabilityProviderTarget.Exact(parseProviderInstance(root.string("providerInstanceId")))
        }
        "any_compatible" -> {
            root.requireKeys("providerTarget", "type")
            CapabilityProviderTarget.AnyCompatible
        }
        else -> fail("providerTarget.type")
    }

    private fun encodeExecutionContext(context: CapabilityExecutionContext): JsonObject =
        buildJsonObject {
            put("epoch", JsonPrimitive(context.epoch))
            put("sequence", JsonPrimitive(context.sequence))
        }

    private fun parseExecutionContext(root: JsonObject): CapabilityExecutionContext {
        root.requireKeys("executionContext", "epoch", "sequence")
        return unwrap(CapabilityExecutionContext.create(root.long("epoch"), root.long("sequence")))
    }

    private fun encodeData(data: CapabilityData): JsonObject = buildJsonObject {
        put("contract", JsonPrimitive(data.contract.wireValue))
        put(
            "values",
            buildJsonObject {
                data.values.toSortedMap(compareBy { it.value }).forEach { (name, value) ->
                    put(name.value, encodeArgument(value))
                }
            },
        )
    }

    private fun parseData(root: JsonObject): CapabilityData {
        root.requireKeys("data", "contract", "values")
        val valuesNode = root.obj("values")
        val values = valuesNode.map { (name, value) ->
            unwrap(CapabilityArgumentName.parse(name)) to parseArgument(value, "values.$name")
        }.toMap()
        return unwrap(
            CapabilityData.create(
                contract = parseContract(root.string("contract")),
                values = values,
            ),
        )
    }

    private fun encodeArgument(value: CapabilityArgumentValue): JsonObject = buildJsonObject {
        when (value) {
            is CapabilityArgumentValue.Text -> {
                put("type", JsonPrimitive("text"))
                put("value", JsonPrimitive(value.value))
            }
            is CapabilityArgumentValue.Integer -> {
                put("type", JsonPrimitive("integer"))
                put("value", JsonPrimitive(value.value))
            }
            is CapabilityArgumentValue.Decimal -> {
                put("type", JsonPrimitive("decimal"))
                put("value", JsonPrimitive(value.value))
            }
            is CapabilityArgumentValue.Flag -> {
                put("type", JsonPrimitive("flag"))
                put("value", JsonPrimitive(value.value))
            }
        }
    }

    private fun parseArgument(element: JsonElement, path: String): CapabilityArgumentValue {
        val root = element as? JsonObject ?: fail(path)
        root.requireKeys(path, "type", "value")
        return when (root.string("type")) {
            "text" -> unwrap(CapabilityArgumentValue.Text.create(root.string("value")))
            "integer" -> CapabilityArgumentValue.Integer(root.long("value"))
            "decimal" -> unwrap(CapabilityArgumentValue.Decimal.create(root.double("value")))
            "flag" -> CapabilityArgumentValue.Flag(root.boolean("value"))
            else -> fail("$path.type")
        }
    }

    private fun encodeFailure(failure: CapabilityFailure): JsonObject = buildJsonObject {
        put("code", JsonPrimitive(failure.code.name.lowercase()))
        put("reasonCode", failure.reasonCode?.let { JsonPrimitive(it.value) } ?: JsonNull)
    }

    private fun parseFailure(root: JsonObject): CapabilityFailure {
        root.requireKeys("failure", "code", "reasonCode")
        return CapabilityFailure(
            code = parseEnum(root.string("code"), "failure.code"),
            reasonCode = root.nullableString("reasonCode")?.let {
                unwrap(CapabilityReasonCode.parse(it))
            },
        )
    }

    private fun requireSchema(root: JsonObject) {
        val found = root.string("schemaVersion")
        if (found != schemaVersion) {
            throw EnvelopeParseException(CapabilityEnvelopeError.UnsupportedSchemaVersion(found))
        }
    }

    private fun parseCapability(value: String): CapabilityReference = when (
        val result = CapabilityReference.parse(value)
    ) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> fail("capability")
    }

    private fun parseContract(value: String): CapabilityContractReference = when (
        val result = CapabilityContractReference.parse(value)
    ) {
        is CapabilityDescriptorResult.Success -> result.value
        is CapabilityDescriptorResult.Failure -> fail("contract")
    }

    private fun parseProviderInstance(value: String): ProviderInstanceId = when (
        val result = ProviderInstanceId.parse(value)
    ) {
        is io.redi.robot.capability.provider.ProviderIdentityResult.Success -> result.value
        is io.redi.robot.capability.provider.ProviderIdentityResult.Failure -> fail("providerInstanceId")
    }

    private fun monotonic(value: Long, field: String): MonotonicTimestampMs = when (
        val result = MonotonicTimestampMs.create(value)
    ) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> throw EnvelopeParseException(
            CapabilityEnvelopeError.InvalidMonotonicTime(field, value),
        )
    }

    private inline fun <reified T : Enum<T>> parseEnum(value: String, path: String): T =
        enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: fail(path)

    private fun parseRoot(text: String, path: String): JsonObject = runCatching {
        Json.parseToJsonElement(text) as? JsonObject
    }.getOrNull() ?: fail(path)

    private fun JsonObject.requireKeys(path: String, vararg expected: String) {
        if (keys != expected.toSet()) fail(path)
    }

    private fun JsonObject.obj(field: String): JsonObject =
        get(field) as? JsonObject ?: fail(field)

    private fun JsonObject.nullableObject(field: String): JsonObject? = when (val value = get(field)) {
        JsonNull -> null
        is JsonObject -> value
        else -> fail(field)
    }

    private fun JsonObject.string(field: String): String {
        val value = get(field) as? JsonPrimitive ?: fail(field)
        if (!value.isString) fail(field)
        return value.content
    }

    private fun JsonObject.nullableString(field: String): String? = when (val value = get(field)) {
        JsonNull -> null
        is JsonPrimitive -> value.content.takeIf { value.isString } ?: fail(field)
        else -> fail(field)
    }

    private fun JsonObject.long(field: String): Long {
        val value = get(field) as? JsonPrimitive ?: fail(field)
        if (value.isString) fail(field)
        return value.longOrNull ?: fail(field)
    }

    private fun JsonObject.double(field: String): Double {
        val value = get(field) as? JsonPrimitive ?: fail(field)
        if (value.isString) fail(field)
        return value.doubleOrNull ?: fail(field)
    }

    private fun JsonObject.boolean(field: String): Boolean {
        val value = get(field) as? JsonPrimitive ?: fail(field)
        if (value.isString) fail(field)
        return value.booleanOrNull ?: fail(field)
    }

    private fun <T> unwrap(result: CapabilityEnvelopeResult<T>): T = when (result) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> throw EnvelopeParseException(result.error)
    }

    private fun <T> decode(block: () -> T): CapabilityEnvelopeResult<T> = try {
        CapabilityEnvelopeResult.Success(block())
    } catch (error: EnvelopeParseException) {
        CapabilityEnvelopeResult.Failure(error.error)
    }

    private fun fail(path: String): Nothing = throw EnvelopeParseException(
        CapabilityEnvelopeError.InvalidDocument(path),
    )

    private class EnvelopeParseException(val error: CapabilityEnvelopeError) : RuntimeException()
}
