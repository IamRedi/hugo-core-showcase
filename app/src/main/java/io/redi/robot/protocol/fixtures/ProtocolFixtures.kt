package io.redi.robot.protocol.fixtures

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Reproducible, redacted BLE capture schema used by protocol experiments and fixtures.
 *
 * The schema is split from interpretation; payload bytes and GATT inventory are captured
 * separately from any claimed profile meaning.
 */
data class ProtocolFixture(
    val schemaVersion: String,
    val run: ProtocolRunMetadata,
    val redaction: FixtureRedaction,
    val gattInventory: GattInventory,
    val events: List<FixtureEvent>,
    val firmwareUnknowns: List<FirmwareUnknown> = emptyList(),
    val notes: String? = null,
) {
    fun toJsonElement(): JsonElement = buildJsonObject {
        put("schemaVersion", JsonPrimitive(schemaVersion))
        put("run", run.toJsonElement())
        put("redaction", redaction.toJsonElement())
        put("gattInventory", gattInventory.toJsonElement())
        put(
            "events",
            buildJsonArray {
                events.forEach { add(it.toJsonElement()) }
            },
        )
        put(
            "firmwareUnknowns",
            buildJsonArray {
                firmwareUnknowns.forEach { add(it.toJsonElement()) }
            },
        )
        notes?.let { put("notes", JsonPrimitive(it)) }
    }
}

data class ProtocolRunMetadata(
    val runId: String,
    val capturedAtUnixMs: Long,
    val capturedMonotonicMs: Long,
    val operatorTag: String? = null,
) {
    fun toJsonElement(): JsonElement = buildJsonObject {
        put("runId", JsonPrimitive(runId))
        put("capturedAtUnixMs", JsonPrimitive(capturedAtUnixMs))
        put("capturedMonotonicMs", JsonPrimitive(capturedMonotonicMs))
        operatorTag?.let { put("operatorTag", JsonPrimitive(it)) }
    }
}

data class FixtureRedaction(
    val redactedDeviceId: String,
    val redactedHardwareHint: String? = null,
) {
    fun toJsonElement(): JsonElement = buildJsonObject {
        put("redactedDeviceId", JsonPrimitive(redactedDeviceId))
        redactedHardwareHint?.let { put("redactedHardwareHint", JsonPrimitive(it)) }
    }
}

data class GattInventory(
    val services: List<GattService>,
) {
    fun toJsonElement(): JsonObject = buildJsonObject {
        put(
            "services",
            buildJsonArray {
                services.forEach { add(it.toJsonElement()) }
            },
        )
    }
}

data class GattService(
    val uuid: String,
    val isPrimary: Boolean,
    val characteristics: List<GattCharacteristic>,
) {
    fun toJsonElement(): JsonObject = buildJsonObject {
        put("uuid", JsonPrimitive(uuid))
        put("isPrimary", JsonPrimitive(isPrimary))
        put(
            "characteristics",
            buildJsonArray {
                characteristics.forEach { add(it.toJsonElement()) }
            },
        )
    }
}

data class GattCharacteristic(
    val uuid: String,
    val properties: List<String>,
    val descriptors: List<String> = emptyList(),
) {
    fun toJsonElement(): JsonObject = buildJsonObject {
        put("uuid", JsonPrimitive(uuid))
        put(
            "properties",
            buildJsonArray {
                properties.forEach { add(JsonPrimitive(it)) }
            },
        )
        put(
            "descriptors",
            buildJsonArray {
                descriptors.forEach { add(JsonPrimitive(it)) }
            },
        )
    }
}

data class FirmwareUnknown(
    val component: String,
    val summary: String,
) {
    fun toJsonElement(): JsonElement = buildJsonObject {
        put("component", JsonPrimitive(component))
        put("summary", JsonPrimitive(summary))
    }
}

sealed interface FixtureEvent {
    val timestampMs: Long
    val source: String
    val serviceUuid: String
    val characteristicUuid: String
    val payload: List<Int>

    fun toJsonElement(): JsonElement
}

data class ReadEvent(
    override val timestampMs: Long,
    override val source: String,
    override val serviceUuid: String,
    override val characteristicUuid: String,
    override val payload: List<Int>,
) : FixtureEvent {
    override fun toJsonElement(): JsonElement = buildJsonObject {
        put("type", JsonPrimitive("read"))
        put("timestampMs", JsonPrimitive(timestampMs))
        put("source", JsonPrimitive(source))
        put("serviceUuid", JsonPrimitive(serviceUuid))
        put("characteristicUuid", JsonPrimitive(characteristicUuid))
        put("payloadHex", JsonPrimitive(bytesToHex(payload)))
    }
}

data class WriteEvent(
    override val timestampMs: Long,
    override val source: String,
    override val serviceUuid: String,
    override val characteristicUuid: String,
    override val payload: List<Int>,
    val withResponse: Boolean,
) : FixtureEvent {
    override fun toJsonElement(): JsonElement = buildJsonObject {
        put("type", JsonPrimitive("write"))
        put("timestampMs", JsonPrimitive(timestampMs))
        put("source", JsonPrimitive(source))
        put("serviceUuid", JsonPrimitive(serviceUuid))
        put("characteristicUuid", JsonPrimitive(characteristicUuid))
        put("payloadHex", JsonPrimitive(bytesToHex(payload)))
        put("withResponse", JsonPrimitive(withResponse))
    }
}

data class NotificationEvent(
    override val timestampMs: Long,
    override val source: String,
    override val serviceUuid: String,
    override val characteristicUuid: String,
    override val payload: List<Int>,
    val isIndication: Boolean = false,
) : FixtureEvent {
    override fun toJsonElement(): JsonElement = buildJsonObject {
        put("type", JsonPrimitive("notification"))
        put("timestampMs", JsonPrimitive(timestampMs))
        put("source", JsonPrimitive(source))
        put("serviceUuid", JsonPrimitive(serviceUuid))
        put("characteristicUuid", JsonPrimitive(characteristicUuid))
        put("payloadHex", JsonPrimitive(bytesToHex(payload)))
        put("isIndication", JsonPrimitive(isIndication))
    }
}

data class ProtocolFixtureParseException(
    override val message: String,
    val path: String,
    override val cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

object ProtocolFixtureParser {
    private const val currentSchemaVersion = "1.0"
    private val json = Json { ignoreUnknownKeys = false; prettyPrint = true }
    private val macPattern = Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
    private val forbiddenKeys = setOf(
        "macAddress",
        "mac",
        "bluetoothAddress",
        "deviceAddress",
        "serial",
        "serialNumber",
        "peripheralId",
    )

    fun parse(jsonText: String): ProtocolFixture {
        val root = runCatching { Json.parseToJsonElement(jsonText) }
            .getOrElse { throw ProtocolFixtureParseException("Invalid JSON", "root", it) }
        val rootObject = root as? JsonObject
            ?: throw ProtocolFixtureParseException("Root must be an object", "root")

        validateNoStableIdentifiers(rootObject, "root")
        return parseRoot(rootObject)
    }

    fun parseFixtureInventoryFromJson(jsonText: String): GattInventory =
        parse(jsonText).gattInventory

    fun serialize(fixture: ProtocolFixture): String = json.encodeToString(
        JsonElement.serializer(),
        fixture.toJsonElement(),
    )

    private fun parseRoot(root: JsonObject): ProtocolFixture {
        val schemaVersion = stringField(root, "schemaVersion", "schemaVersion")
        if (schemaVersion != currentSchemaVersion) {
            throw ProtocolFixtureParseException(
                "Unsupported schemaVersion '$schemaVersion'; expected '$currentSchemaVersion'",
                "schemaVersion",
            )
        }

        val run = parseRun(requireObjectField(root, "run", "run"), "run")
        val redaction = parseRedaction(
            requireObjectField(root, "redaction", "redaction"),
            "redaction",
        )
        val gattInventory = parseInventory(
            requireObjectField(root, "gattInventory", "gattInventory"),
            "gattInventory",
        )
        val events = parseEvents(
            requireArrayField(root, "events", "events"),
            "events",
        )
        val firmwareUnknowns = parseFirmwareUnknowns(
            requireArrayFieldOrEmpty(root, "firmwareUnknowns", "firmwareUnknowns"),
        )
        val notes = stringFieldOrNull(root, "notes")

        return ProtocolFixture(
            schemaVersion = schemaVersion,
            run = run,
            redaction = redaction,
            gattInventory = gattInventory,
            events = events,
            firmwareUnknowns = firmwareUnknowns,
            notes = notes,
        )
    }

    private fun parseRun(node: JsonObject, path: String): ProtocolRunMetadata {
        return ProtocolRunMetadata(
            runId = stringField(node, "runId", "$path.runId"),
            capturedAtUnixMs = longField(node, "capturedAtUnixMs", "$path.capturedAtUnixMs"),
            capturedMonotonicMs = longField(node, "capturedMonotonicMs", "$path.capturedMonotonicMs"),
            operatorTag = stringFieldOrNull(node, "operatorTag"),
        )
    }

    private fun parseRedaction(node: JsonObject, path: String): FixtureRedaction {
        return FixtureRedaction(
            redactedDeviceId = stringField(node, "redactedDeviceId", "$path.redactedDeviceId"),
            redactedHardwareHint = stringFieldOrNull(node, "redactedHardwareHint"),
        )
    }

    private fun parseInventory(node: JsonObject, path: String): GattInventory {
        val services = parseServices(requireArrayField(node, "services", "$path.services"), "$path.services")
        return GattInventory(services)
    }

    private fun parseServices(array: JsonArray, path: String): List<GattService> = array.mapIndexed { i, item ->
        val servicePath = "$path[$i]"
        val obj = requireObject(item, servicePath)
        GattService(
            uuid = stringField(obj, "uuid", "$servicePath.uuid"),
            isPrimary = booleanField(obj, "isPrimary", "$servicePath.isPrimary"),
            characteristics = parseCharacteristics(
                requireArrayField(obj, "characteristics", "$servicePath.characteristics"),
                "$servicePath.characteristics",
            ),
        )
    }

    private fun parseCharacteristics(array: JsonArray, path: String): List<GattCharacteristic> = array.mapIndexed { i, item ->
        val charPath = "$path[$i]"
        val obj = requireObject(item, charPath)
        GattCharacteristic(
            uuid = stringField(obj, "uuid", "$charPath.uuid"),
            properties = parseStringArray(obj, "properties", "$charPath.properties"),
            descriptors = parseStringArrayOrEmpty(obj, "descriptors", "$charPath.descriptors"),
        )
    }

    private fun parseEvents(array: JsonArray, path: String): List<FixtureEvent> = array.mapIndexed { i, item ->
        val eventPath = "$path[$i]"
        val obj = requireObject(item, eventPath)
        val type = stringField(obj, "type", "$eventPath.type")
        when (type) {
            "read" -> ReadEvent(
                timestampMs = longField(obj, "timestampMs", "$eventPath.timestampMs"),
                source = stringField(obj, "source", "$eventPath.source"),
                serviceUuid = stringField(obj, "serviceUuid", "$eventPath.serviceUuid"),
                characteristicUuid = stringField(
                    obj,
                    "characteristicUuid",
                    "$eventPath.characteristicUuid",
                ),
                payload = parsePayloadHex(payloadHexField(obj, "payloadHex"), "$eventPath.payloadHex"),
            )
            "write" -> WriteEvent(
                timestampMs = longField(obj, "timestampMs", "$eventPath.timestampMs"),
                source = stringField(obj, "source", "$eventPath.source"),
                serviceUuid = stringField(obj, "serviceUuid", "$eventPath.serviceUuid"),
                characteristicUuid = stringField(
                    obj,
                    "characteristicUuid",
                    "$eventPath.characteristicUuid",
                ),
                payload = parsePayloadHex(
                    payloadHexField(obj, "payloadHex"),
                    "$eventPath.payloadHex",
                ),
                withResponse = booleanField(obj, "withResponse", "$eventPath.withResponse"),
            )
            "notification" -> NotificationEvent(
                timestampMs = longField(obj, "timestampMs", "$eventPath.timestampMs"),
                source = stringField(obj, "source", "$eventPath.source"),
                serviceUuid = stringField(obj, "serviceUuid", "$eventPath.serviceUuid"),
                characteristicUuid = stringField(
                    obj,
                    "characteristicUuid",
                    "$eventPath.characteristicUuid",
                ),
                payload = parsePayloadHex(
                    payloadHexField(obj, "payloadHex"),
                    "$eventPath.payloadHex",
                ),
                isIndication = booleanFieldOrDefault(
                    obj,
                    "isIndication",
                    false,
                    "$eventPath.isIndication",
                ),
            )
            else -> throw ProtocolFixtureParseException("Unknown event type '$type'", eventPath)
        }
    }

    private fun parseFirmwareUnknowns(array: JsonArray): List<FirmwareUnknown> = array.mapIndexed { i, item ->
        val obj = requireObject(item, "firmwareUnknowns[$i]")
        FirmwareUnknown(
            component = stringField(obj, "component", "firmwareUnknowns[$i].component"),
            summary = stringField(obj, "summary", "firmwareUnknowns[$i].summary"),
        )
    }

    private fun parseStringArray(node: JsonObject, key: String, path: String): List<String> {
        val values = requireArrayField(node, key, path)
        return values.mapIndexed { i, item ->
            if (item !is JsonPrimitive || !item.isString) {
                throw ProtocolFixtureParseException("Expected string array entries", "$path[$i]")
            }
            item.content
        }
    }

    private fun parseStringArrayOrEmpty(
        node: JsonObject,
        key: String,
        path: String,
    ): List<String> = if (node.containsKey(key)) parseStringArray(node, key, path) else emptyList()

    private fun parsePayloadHex(value: String, path: String): List<Int> {
        if (value.isBlank()) return emptyList()
        if (!value.matches(Regex("(?i)^[0-9a-f]*$"))) {
            throw ProtocolFixtureParseException("Payload must be hex-encoded", path)
        }
        if (value.length % 2 != 0) {
            throw ProtocolFixtureParseException("Payload length must be even", path)
        }
        return value.chunked(2).map {
            it.toInt(16)
        }
    }

    private fun validateNoStableIdentifiers(node: JsonElement, path: String) {
        when (node) {
            is JsonObject -> {
                for (entry in node.entries) {
                    if (forbiddenKeys.any { forbidden ->
                        entry.key.equals(forbidden, ignoreCase = true)
                    }) {
                        throw ProtocolFixtureParseException(
                            "Fixture contains forbidden stable identifier field '${entry.key}'",
                            "$path.${entry.key}",
                        )
                    }
                    validateNoStableIdentifiers(entry.value, "$path.${entry.key}")
                }
            }
            is JsonArray -> {
                node.forEachIndexed { index, item ->
                    validateNoStableIdentifiers(item, "$path[$index]")
                }
            }
            is JsonPrimitive -> {
                val value = node.content
                if (node.isString && macPattern.matches(value)) {
                    throw ProtocolFixtureParseException(
                        "Fixture contains a stable-looking MAC address literal",
                        "$path",
                    )
                }
            }
        }
    }

    private fun requireObject(node: JsonElement, path: String): JsonObject {
        return node as? JsonObject ?: throw ProtocolFixtureParseException("Expected object", path)
    }

    private fun requireObjectField(node: JsonObject, key: String, path: String): JsonObject {
        val value = node[key] ?: throw ProtocolFixtureParseException("Missing required field '$key'", path)
        return value as? JsonObject ?: throw ProtocolFixtureParseException("Expected object field '$key'", path)
    }

    private fun requireArrayField(node: JsonObject, key: String, path: String): JsonArray {
        val value = node[key] ?: throw ProtocolFixtureParseException("Missing required array '$key'", path)
        return value as? JsonArray ?: throw ProtocolFixtureParseException("Expected array '$key'", path)
    }

    private fun requireArrayFieldOrEmpty(node: JsonObject, key: String, path: String): JsonArray {
        val value = node[key] ?: return JsonArray(emptyList())
        return value as? JsonArray ?: throw ProtocolFixtureParseException("Expected array '$key'", path)
    }

    private fun stringField(node: JsonObject, key: String, path: String): String {
        val value = node[key] ?: throw ProtocolFixtureParseException("Missing required field '$key'", path)
        if (value !is JsonPrimitive || !value.isString || value.content.isBlank()) {
            throw ProtocolFixtureParseException("Expected non-empty string field '$key'", path)
        }
        return value.content
    }

    private fun payloadHexField(node: JsonObject, key: String): String {
        val value = node[key] ?: throw ProtocolFixtureParseException("Missing required field '$key'", "payloadHex")
        if (value !is JsonPrimitive || !value.isString) {
            throw ProtocolFixtureParseException("Expected payloadHex field '$key'", "payloadHex")
        }
        return value.content
    }

    private fun stringFieldOrNull(node: JsonObject, key: String): String? {
        val value = node[key] ?: return null
        if (value !is JsonPrimitive || !value.isString) {
            throw ProtocolFixtureParseException("Expected string field '$key'", key)
        }
        return value.content.takeIf { it.isNotBlank() }
    }

    private fun longField(node: JsonObject, key: String, path: String): Long {
        val value = node[key] ?: throw ProtocolFixtureParseException("Missing required field '$key'", path)
        if (value !is JsonPrimitive || value.content.toLongOrNull() == null) {
            throw ProtocolFixtureParseException("Expected numeric field '$key'", path)
        }
        return value.content.toLong()
    }

    private fun booleanField(node: JsonObject, key: String, path: String): Boolean {
        val value = node[key] ?: throw ProtocolFixtureParseException("Missing required field '$key'", path)
        if (value !is JsonPrimitive || value.content != "true" && value.content != "false") {
            throw ProtocolFixtureParseException("Expected boolean field '$key'", path)
        }
        return value.content.toBoolean()
    }

    private fun booleanFieldOrDefault(
        node: JsonObject,
        key: String,
        defaultValue: Boolean,
        path: String,
    ): Boolean {
        val value = node[key] ?: return defaultValue
        if (value !is JsonPrimitive || value.content != "true" && value.content != "false") {
            throw ProtocolFixtureParseException("Expected boolean field '$key'", path)
        }
        return value.content.toBoolean()
    }
}

private fun bytesToHex(bytes: List<Int>): String {
    return bytes.joinToString(separator = "") { byte -> byte.toString(16).padStart(2, '0') }
}
