package io.redi.robot.gateway.contract

import io.redi.robot.skills.api.SkillModelResult
import io.redi.robot.skills.api.SkillReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Strict canonical wire codec. It owns no transport, credentials, provider choice, or execution. */
object GatewayContractCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun encodeRequest(request: GatewayRequest): String = encode(
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(request.schemaVersion.wireValue))
            put("messageType", JsonPrimitive("request"))
            put("requestId", JsonPrimitive(request.requestId.value))
            put("operation", JsonPrimitive(request.operation.wireValue))
            put(
                "payload",
                buildJsonObject {
                    request.prompt?.let { put("prompt", JsonPrimitive(it.value)) }
                    request.attachmentReference?.let { put("attachmentReference", JsonPrimitive(it.value)) }
                    if (request.embeddingInputs.isNotEmpty()) {
                        put("embeddingInputs", buildJsonArray {
                            request.embeddingInputs.forEach { add(JsonPrimitive(it.value)) }
                        })
                    }
                    request.realtime?.let { realtime ->
                        put(
                            "realtime",
                            buildJsonObject {
                                put("inputModalities", modalityArray(realtime.inputModalities))
                                put("outputModalities", modalityArray(realtime.outputModalities))
                            },
                        )
                    }
                    put(
                        "declaredSkills",
                        buildJsonArray { request.declaredSkills.forEach { add(JsonPrimitive(it.wireValue)) } },
                    )
                    put(
                        "budget",
                        buildJsonObject {
                            put("maximumOutputUnits", JsonPrimitive(request.budget.maximumOutputUnits))
                            put("timeoutMs", JsonPrimitive(request.budget.timeoutMs))
                        },
                    )
                },
            )
        },
    )

    fun decodeRequest(text: String): GatewayContractResult<GatewayRequest> = decode {
        val root = root(text)
        root.requireKeys("schemaVersion", "messageType", "requestId", "operation", "payload")
        if (root.string("messageType") != "request") fail(GatewayContractError.MALFORMED_DOCUMENT)
        val operation = GatewayOperation.fromWireValue(root.string("operation")) ?: fail()
        val payload = root.obj("payload")
        val expectedPayloadKeys = mutableSetOf("declaredSkills", "budget")
        when (operation) {
            GatewayOperation.TEXT -> expectedPayloadKeys += "prompt"
            GatewayOperation.VISION -> expectedPayloadKeys += setOf("prompt", "attachmentReference")
            GatewayOperation.EMBEDDING -> expectedPayloadKeys += "embeddingInputs"
            GatewayOperation.REALTIME -> expectedPayloadKeys += "realtime"
        }
        payload.requireKeys(*expectedPayloadKeys.toTypedArray())
        val skills = payload.array("declaredSkills").map { element ->
            val raw = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail()
            unwrapSkill(SkillReference.parse(raw))
        }
        val budgetRoot = payload.obj("budget").also {
            it.requireKeys("maximumOutputUnits", "timeoutMs")
        }
        unwrap(
            GatewayRequest.create(
                schemaVersion = schema(root.string("schemaVersion")),
                requestId = requestId(root.string("requestId")),
                operation = operation,
                prompt = payload.optionalString("prompt")?.let { gatewayText(it) },
                attachmentReference = payload.optionalString("attachmentReference")?.let { attachment(it) },
                embeddingInputs = payload.optionalArray("embeddingInputs")?.map { element ->
                    gatewayText((element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail())
                }.orEmpty(),
                realtime = payload.optionalObject("realtime")?.let(::parseRealtime),
                declaredSkills = skills,
                budget = unwrap(GatewayBudget.create(budgetRoot.int("maximumOutputUnits"), budgetRoot.long("timeoutMs"))),
            ),
        )
    }

    fun encodeCancellation(cancellation: GatewayCancellation): String = encode(
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(cancellation.schemaVersion.wireValue))
            put("messageType", JsonPrimitive("cancel"))
            put("requestId", JsonPrimitive(cancellation.requestId.value))
            put("cancellationId", JsonPrimitive(cancellation.cancellationId.value))
            put("reason", JsonPrimitive(cancellation.reason.wireValue))
        },
    )

    fun decodeCancellation(text: String): GatewayContractResult<GatewayCancellation> = decode {
        val root = root(text)
        root.requireKeys("schemaVersion", "messageType", "requestId", "cancellationId", "reason")
        if (root.string("messageType") != "cancel") fail()
        GatewayCancellation(
            schemaVersion = schema(root.string("schemaVersion")),
            requestId = requestId(root.string("requestId")),
            cancellationId = cancellationId(root.string("cancellationId")),
            reason = GatewayCancellationReason.fromWireValue(root.string("reason")) ?: fail(),
        )
    }

    fun encodeEvent(event: GatewayEvent): String = encode(
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(event.schemaVersion.wireValue))
            put("messageType", JsonPrimitive("event"))
            put("requestId", JsonPrimitive(event.requestId.value))
            put("eventId", JsonPrimitive(event.eventId.value))
            put("sequence", JsonPrimitive(event.sequence))
            put("operation", JsonPrimitive(event.operation.wireValue))
            put("event", encodePayload(event.payload))
        },
    )

    fun decodeEvent(text: String): GatewayContractResult<GatewayEvent> = decode {
        val root = root(text)
        root.requireKeys("schemaVersion", "messageType", "requestId", "eventId", "sequence", "operation", "event")
        if (root.string("messageType") != "event") fail()
        unwrap(
            GatewayEvent.create(
                schemaVersion = schema(root.string("schemaVersion")),
                requestId = requestId(root.string("requestId")),
                eventId = eventId(root.string("eventId")),
                sequence = root.int("sequence"),
                operation = GatewayOperation.fromWireValue(root.string("operation")) ?: fail(),
                payload = parsePayload(root.obj("event")),
            ),
        )
    }

    private fun encodePayload(payload: GatewayEventPayload): JsonObject = buildJsonObject {
        when (payload) {
            GatewayEventPayload.Started -> put("type", JsonPrimitive("started"))
            is GatewayEventPayload.PartialText -> {
                put("type", JsonPrimitive("partial_text"))
                put("text", JsonPrimitive(payload.text.value))
            }
            is GatewayEventPayload.ToolProposal -> {
                put("type", JsonPrimitive("tool_proposal"))
                put("proposalId", JsonPrimitive(payload.proposal.proposalId.value))
                put("skill", JsonPrimitive(payload.proposal.skill.wireValue))
                put(
                    "arguments",
                    buildJsonObject {
                        payload.proposal.arguments.forEach { (name, value) -> put(name, encodeScalar(value)) }
                    },
                )
            }
            is GatewayEventPayload.Refusal -> {
                put("type", JsonPrimitive("refusal"))
                put("reason", JsonPrimitive(payload.reason.wireValue))
            }
            is GatewayEventPayload.Embedding -> {
                put("type", JsonPrimitive("embedding"))
                put("inputIndex", JsonPrimitive(payload.inputIndex))
                put("vector", buildJsonArray { payload.vector.forEach { add(JsonPrimitive(it)) } })
            }
            is GatewayEventPayload.Usage -> {
                put("type", JsonPrimitive("usage"))
                put("evidence", JsonPrimitive(payload.usage.evidence.wireValue))
                payload.usage.inputUnits?.let { put("inputUnits", JsonPrimitive(it)) }
                payload.usage.outputUnits?.let { put("outputUnits", JsonPrimitive(it)) }
                payload.usage.estimatedCost?.let { cost ->
                    put(
                        "estimatedCost",
                        buildJsonObject {
                            put("currency", JsonPrimitive(cost.currency))
                            put("micros", JsonPrimitive(cost.micros))
                        },
                    )
                }
            }
            is GatewayEventPayload.Completed -> {
                put("type", JsonPrimitive("completed"))
                put("reason", JsonPrimitive(payload.reason.wireValue))
            }
            is GatewayEventPayload.Failed -> {
                put("type", JsonPrimitive("failed"))
                put("code", JsonPrimitive(payload.code.wireValue))
                put("retryable", JsonPrimitive(payload.retryable))
            }
            is GatewayEventPayload.Cancelled -> {
                put("type", JsonPrimitive("cancelled"))
                put("reason", JsonPrimitive(payload.reason.wireValue))
            }
            GatewayEventPayload.TimedOut -> put("type", JsonPrimitive("timed_out"))
        }
    }

    private fun parsePayload(root: JsonObject): GatewayEventPayload = when (root.string("type")) {
        "started" -> {
            root.requireKeys("type")
            GatewayEventPayload.Started
        }
        "partial_text" -> {
            root.requireKeys("type", "text")
            GatewayEventPayload.PartialText(gatewayText(root.string("text")))
        }
        "tool_proposal" -> {
            root.requireKeys("type", "proposalId", "skill", "arguments")
            val arguments = root.obj("arguments").mapValues { (_, value) ->
                parseScalar(value as? JsonObject ?: fail())
            }
            GatewayEventPayload.ToolProposal(
                unwrap(
                    GatewayToolProposal.create(
                        proposalId = eventId(root.string("proposalId")),
                        skill = unwrapSkill(SkillReference.parse(root.string("skill"))),
                        arguments = arguments,
                    ),
                ),
            )
        }
        "refusal" -> {
            root.requireKeys("type", "reason")
            GatewayEventPayload.Refusal(GatewayRefusalReason.fromWireValue(root.string("reason")) ?: fail())
        }
        "embedding" -> {
            root.requireKeys("type", "inputIndex", "vector")
            GatewayEventPayload.Embedding(
                root.int("inputIndex"),
                root.array("vector").map { element ->
                    (element as? JsonPrimitive)?.doubleOrNull?.takeIf { !element.isString } ?: fail()
                },
            )
        }
        "usage" -> parseUsage(root)
        "completed" -> {
            root.requireKeys("type", "reason")
            GatewayEventPayload.Completed(GatewayFinishReason.fromWireValue(root.string("reason")) ?: fail())
        }
        "failed" -> {
            root.requireKeys("type", "code", "retryable")
            GatewayEventPayload.Failed(
                GatewayFailureCode.fromWireValue(root.string("code")) ?: fail(),
                root.boolean("retryable"),
            )
        }
        "cancelled" -> {
            root.requireKeys("type", "reason")
            GatewayEventPayload.Cancelled(GatewayCancellationReason.fromWireValue(root.string("reason")) ?: fail())
        }
        "timed_out" -> {
            root.requireKeys("type")
            GatewayEventPayload.TimedOut
        }
        else -> fail()
    }

    private fun parseUsage(root: JsonObject): GatewayEventPayload.Usage {
        val expected = mutableSetOf("type", "evidence")
        if (root.containsKey("inputUnits")) expected += "inputUnits"
        if (root.containsKey("outputUnits")) expected += "outputUnits"
        if (root.containsKey("estimatedCost")) expected += "estimatedCost"
        root.requireKeys(*expected.toTypedArray())
        val cost = root.optionalObject("estimatedCost")?.let {
            it.requireKeys("currency", "micros")
            unwrap(GatewayCost.create(it.string("currency"), it.long("micros")))
        }
        return GatewayEventPayload.Usage(
            unwrap(
                GatewayUsage.create(
                    inputUnits = root.optionalLong("inputUnits"),
                    outputUnits = root.optionalLong("outputUnits"),
                    evidence = GatewayUsageEvidence.fromWireValue(root.string("evidence")) ?: fail(),
                    estimatedCost = cost,
                ),
            ),
        )
    }

    private fun encodeScalar(value: GatewayScalar): JsonObject = buildJsonObject {
        when (value) {
            is GatewayScalar.Text -> {
                put("type", JsonPrimitive("text"))
                put("value", JsonPrimitive(value.value))
            }
            is GatewayScalar.Integer -> {
                put("type", JsonPrimitive("integer"))
                put("value", JsonPrimitive(value.value))
            }
            is GatewayScalar.Decimal -> {
                put("type", JsonPrimitive("decimal"))
                put("value", JsonPrimitive(value.value))
            }
            is GatewayScalar.Flag -> {
                put("type", JsonPrimitive("flag"))
                put("value", JsonPrimitive(value.value))
            }
        }
    }

    private fun parseScalar(root: JsonObject): GatewayScalar {
        root.requireKeys("type", "value")
        return when (root.string("type")) {
            "text" -> unwrap(GatewayScalar.Text.create(root.string("value")))
            "integer" -> GatewayScalar.Integer(root.long("value"))
            "decimal" -> unwrap(GatewayScalar.Decimal.create(root.double("value")))
            "flag" -> GatewayScalar.Flag(root.boolean("value"))
            else -> fail()
        }
    }

    private fun parseRealtime(root: JsonObject): GatewayRealtimeConfiguration {
        root.requireKeys("inputModalities", "outputModalities")
        return unwrap(
            GatewayRealtimeConfiguration.create(
                modalitySet(root.array("inputModalities")),
                modalitySet(root.array("outputModalities")),
            ),
        )
    }

    private fun modalityArray(values: Set<GatewayRealtimeModality>): JsonArray = buildJsonArray {
        values.sortedBy { it.wireValue }.forEach { add(JsonPrimitive(it.wireValue)) }
    }

    private fun modalitySet(values: JsonArray): Set<GatewayRealtimeModality> = values.map { element ->
        val raw = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail()
        GatewayRealtimeModality.fromWireValue(raw) ?: fail()
    }.toSet().also { if (it.size != values.size) fail(GatewayContractError.MALFORMED_DOCUMENT) }

    private fun encode(element: JsonObject): String = json.encodeToString(JsonElement.serializer(), element)

    private fun root(text: String): JsonObject = runCatching {
        Json.parseToJsonElement(text) as? JsonObject
    }.getOrNull() ?: fail()

    private fun schema(raw: String): GatewaySchemaVersion = unwrap(GatewaySchemaVersion.parse(raw)).also { version ->
        when (version.compatibilityWith(GatewaySchemaVersion.CURRENT)) {
            GatewaySchemaCompatibility.EXACT,
            GatewaySchemaCompatibility.COMPATIBLE_ADDITIVE_MINOR,
            -> Unit
            GatewaySchemaCompatibility.UNSUPPORTED_OLDER_MINOR,
            GatewaySchemaCompatibility.INCOMPATIBLE_MAJOR,
            -> fail(GatewayContractError.UNSUPPORTED_SCHEMA_VERSION)
        }
    }
    private fun requestId(raw: String): GatewayRequestId = unwrap(GatewayRequestId.parse(raw))
    private fun eventId(raw: String): GatewayEventId = unwrap(GatewayEventId.parse(raw))
    private fun cancellationId(raw: String): GatewayCancellationId = unwrap(GatewayCancellationId.parse(raw))
    private fun gatewayText(raw: String): GatewayText = unwrap(GatewayText.create(raw))
    private fun attachment(raw: String): GatewayAttachmentReference = unwrap(GatewayAttachmentReference.parse(raw))

    private inline fun <T> decode(block: () -> T): GatewayContractResult<T> = try {
        GatewayContractResult.Success(block())
    } catch (failure: GatewayParseException) {
        GatewayContractResult.Failure(failure.error)
    } catch (_: RuntimeException) {
        GatewayContractResult.Failure(GatewayContractError.MALFORMED_DOCUMENT)
    }

    private fun <T> unwrap(result: GatewayContractResult<T>): T = when (result) {
        is GatewayContractResult.Success -> result.value
        is GatewayContractResult.Failure -> fail(result.error)
    }

    private fun <T> unwrapSkill(result: SkillModelResult<T>): T = when (result) {
        is SkillModelResult.Success -> result.value
        is SkillModelResult.Failure -> fail()
    }

    private fun JsonObject.requireKeys(vararg expected: String) {
        val expectedSet = expected.toSet()
        val missing = expectedSet - keys
        if (missing.isNotEmpty()) fail(GatewayContractError.MISSING_FIELD)
        if ((keys - expectedSet).isNotEmpty()) fail(GatewayContractError.UNEXPECTED_FIELD)
    }

    private fun JsonObject.obj(field: String): JsonObject = get(field) as? JsonObject ?: fail()
    private fun JsonObject.array(field: String): JsonArray = get(field) as? JsonArray ?: fail()
    private fun JsonObject.optionalArray(field: String): JsonArray? = get(field)?.let { it as? JsonArray ?: fail() }
    private fun JsonObject.optionalObject(field: String): JsonObject? = get(field)?.let { it as? JsonObject ?: fail() }

    private fun JsonObject.string(field: String): String {
        val value = get(field) as? JsonPrimitive ?: fail()
        return value.content.takeIf { value.isString } ?: fail()
    }

    private fun JsonObject.optionalString(field: String): String? = get(field)?.let {
        val value = it as? JsonPrimitive ?: fail()
        value.content.takeIf { value.isString } ?: fail()
    }

    private fun JsonObject.long(field: String): Long {
        val value = get(field) as? JsonPrimitive ?: fail()
        return value.longOrNull?.takeIf { !value.isString } ?: fail()
    }

    private fun JsonObject.optionalLong(field: String): Long? = get(field)?.let {
        val value = it as? JsonPrimitive ?: fail()
        value.longOrNull?.takeIf { !value.isString } ?: fail()
    }

    private fun JsonObject.int(field: String): Int {
        val value = get(field) as? JsonPrimitive ?: fail()
        return value.intOrNull?.takeIf { !value.isString } ?: fail()
    }

    private fun JsonObject.double(field: String): Double {
        val value = get(field) as? JsonPrimitive ?: fail()
        return value.doubleOrNull?.takeIf { !value.isString && it.isFinite() } ?: fail()
    }

    private fun JsonObject.boolean(field: String): Boolean {
        val value = get(field) as? JsonPrimitive ?: fail()
        return value.booleanOrNull?.takeIf { !value.isString } ?: fail()
    }

    private fun fail(error: GatewayContractError = GatewayContractError.MALFORMED_DOCUMENT): Nothing =
        throw GatewayParseException(error)

    private class GatewayParseException(val error: GatewayContractError) : RuntimeException()
}
