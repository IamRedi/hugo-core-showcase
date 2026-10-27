package io.redi.robot.conversation.context

import io.redi.robot.context.api.ContextValue
import io.redi.robot.context.runtime.ContextSnapshotResolution
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** Canonical outbound projection. Build evidence and filtered candidates are never serialized. */
object ConversationContextCodec {
    private val json = Json { encodeDefaults = true }

    fun encode(packet: ConversationContextPacket): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(ConversationContextPacket.SCHEMA_VERSION))
            put("requestId", JsonPrimitive(packet.requestId.value))
            put("operation", JsonPrimitive(packet.operation.wireValue))
            put("budget", buildJsonObject {
                put("maximumItems", JsonPrimitive(packet.budget.maximumItems))
                put("maximumInputUnits", JsonPrimitive(packet.budget.maximumInputUnits))
                put("maximumUtf8Bytes", JsonPrimitive(packet.budget.maximumUtf8Bytes))
            })
            put("itemCount", JsonPrimitive(packet.itemCount))
            put("currentTurn", text(packet.currentTurn))
            packet.summary?.let { put("summary", text(it)) }
            put("context", buildJsonArray { packet.snapshotItems.forEach { add(contextItem(it)) } })
            put("memories", buildJsonArray { packet.memories.forEach { add(memory(it)) } })
            put("tools", buildJsonArray { packet.tools.forEach { add(tool(it)) } })
        },
    )

    private fun text(value: ConversationContextText) = buildJsonObject {
        put("provenanceId", JsonPrimitive(value.provenanceId.value))
        put("privacy", JsonPrimitive(value.privacyClassification.wireValue))
        put("text", JsonPrimitive(value.text.value))
    }

    private fun memory(value: ConversationMemoryContextItem) = buildJsonObject {
        put("provenanceId", JsonPrimitive(value.provenanceId.value))
        put("privacy", JsonPrimitive(value.privacyClassification.wireValue))
        put("text", JsonPrimitive(value.text.value))
    }

    private fun contextItem(value: ConversationSnapshotContextItem) = buildJsonObject {
        put("key", JsonPrimitive(value.key.value))
        put("privacy", JsonPrimitive(value.privacyClassification.wireValue))
        put("resolution", resolution(value.resolution))
        put("provenanceIds", buildJsonArray { value.provenanceIds.forEach { add(JsonPrimitive(it)) } })
    }

    private fun resolution(value: ContextSnapshotResolution) = buildJsonObject {
        when (value) {
            is ContextSnapshotResolution.Known -> {
                put("status", JsonPrimitive("known"))
                put("value", contextValue(value.value))
            }
            is ContextSnapshotResolution.Unknown -> {
                put("status", JsonPrimitive("unknown"))
                put("reasons", buildJsonArray { value.reasons.forEach { add(JsonPrimitive(it.wireValue)) } })
            }
            is ContextSnapshotResolution.Conflict -> {
                put("status", JsonPrimitive("conflict"))
                put("candidates", buildJsonArray { value.candidates.forEach { add(contextValue(it)) } })
            }
        }
    }

    private fun contextValue(value: ContextValue) = buildJsonObject {
        put("type", JsonPrimitive(value.wireType))
        put("value", when (value) {
            is ContextValue.BooleanValue -> JsonPrimitive(value.value)
            is ContextValue.IntegerValue -> JsonPrimitive(value.value)
            is ContextValue.DecimalValue -> JsonPrimitive(value.canonicalValue)
            is ContextValue.TextValue -> JsonPrimitive(value.value.value)
            is ContextValue.SymbolValue -> JsonPrimitive(value.value.value)
        })
    }

    private fun tool(value: ConversationToolContextSchema) = buildJsonObject {
        put("reference", JsonPrimitive(value.reference.wireValue))
        put("capability", JsonPrimitive(value.capability))
        put("actor", JsonPrimitive(value.actor))
        put("skillClass", JsonPrimitive(value.skillClass))
        value.inputContract?.let { put("inputContract", JsonPrimitive(it)) }
        put("fixedInput", buildJsonObject {
            value.fixedInput.forEach { (name, scalar) -> put(name, JsonPrimitive(scalar)) }
        })
        value.outputContract?.let { put("outputContract", JsonPrimitive(it)) }
        put("maximumDurationMs", JsonPrimitive(value.maximumDurationMs))
        put("cancellationRequired", JsonPrimitive(value.cancellationRequired))
        put("authority", JsonPrimitive(false))
    }
}
