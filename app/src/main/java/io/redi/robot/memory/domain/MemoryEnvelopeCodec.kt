package io.redi.robot.memory.domain

import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.context.api.ContextConfidence
import io.redi.robot.context.api.ContextObservationResult
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextSourceReference
import io.redi.robot.context.api.ContextSourceType
import io.redi.robot.context.api.ContextSymbol
import io.redi.robot.context.api.ContextText
import io.redi.robot.context.api.ContextValue
import io.redi.robot.hugo.identity.HugoIdentityId
import io.redi.robot.hugo.identity.HugoIdentityResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Strict canonical JSON codec for the H058 memory envelope, following the H003/H036 codec
 * conventions: one fixed schema version, exact field sets, unknown/missing fields and malformed
 * documents fail closed, and canonical re-encoding is deterministic.
 *
 * Decode performs structural restoration only: it validates the schema version, field shapes,
 * token/UUID formats, bounds, null-ness, and ordering invariants. It deliberately does not
 * re-apply creation policy (model-generated candidate rule, layer/scope matrix, sensitive
 * allowed-use restriction), because stored records may legitimately carry post-validation states.
 */
object MemoryEnvelopeCodec {
    private val json = Json

    private val topLevelFields = setOf(
        "schemaVersion",
        "recordId",
        "ownerIdentity",
        "layer",
        "type",
        "scope",
        "subjects",
        "content",
        "provenance",
        "confidence",
        "validationState",
        "disposition",
        "privacy",
        "allowedUse",
        "retention",
        "supersedes",
        "conflictsWith",
        "lineage",
        "tombstone",
        "createdAtEpochSecond",
        "updatedAtEpochSecond",
    )

    private val scopeFields = setOf("kind", "instanceId", "version")
    private val provenanceFields = setOf(
        "origin",
        "modelGenerated",
        "sourceType",
        "sourceReference",
        "creator",
        "observedAtEpochSecond",
    )
    private val retentionFields = setOf("retentionClass", "expiresAtEpochSecond")
    private val lineageFields = setOf("derivedFrom")
    private val tombstoneFields = setOf("tombstonedAtEpochSecond", "actor")
    private val contentFields = setOf("wireType", "value")

    fun encode(envelope: MemoryEnvelope): String = buildJsonObject {
        put("schemaVersion", JsonPrimitive(envelope.schemaVersion.wireValue))
        put("recordId", JsonPrimitive(envelope.recordId.value))
        put("ownerIdentity", JsonPrimitive(envelope.ownerIdentity.value))
        put("layer", JsonPrimitive(envelope.layer.name))
        put("type", JsonPrimitive(envelope.type.value))
        put("scope", encodeScope(envelope.scope))
        put("subjects", JsonArray(envelope.subjects.map { JsonPrimitive(it.value) }))
        put("content", envelope.content?.let(::encodeContent) ?: JsonNull)
        put("provenance", encodeProvenance(envelope.provenance))
        put(
            "confidence",
            envelope.confidence?.let { JsonPrimitive(it.permille) } ?: JsonNull,
        )
        put("validationState", JsonPrimitive(envelope.validationState.name))
        put("disposition", JsonPrimitive(envelope.disposition.name))
        put("privacy", JsonPrimitive(envelope.privacy.wireValue))
        put("allowedUse", JsonPrimitive(envelope.allowedUse.wireValue))
        put("retention", envelope.retention?.let(::encodeRetention) ?: JsonNull)
        put("supersedes", envelope.supersedes?.let { JsonPrimitive(it.value) } ?: JsonNull)
        put("conflictsWith", JsonArray(envelope.conflictsWith.map { JsonPrimitive(it.value) }))
        put("lineage", envelope.lineage?.let(::encodeLineage) ?: JsonNull)
        put("tombstone", envelope.tombstone?.let(::encodeTombstone) ?: JsonNull)
        put("createdAtEpochSecond", JsonPrimitive(envelope.createdAtEpochSecond))
        put(
            "updatedAtEpochSecond",
            envelope.updatedAtEpochSecond?.let { JsonPrimitive(it) } ?: JsonNull,
        )
    }.toString()

    fun decode(text: String): MemoryDomainResult<MemoryEnvelope> {
        val root = try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            return MemoryDomainResult.Failure(MemoryDomainError.MalformedDocument)
        }
        if (root !is JsonObject) return MemoryDomainResult.Failure(MemoryDomainError.MalformedDocument)
        val fields = checkFields(root, topLevelFields)
            ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidDocument)
        if (fields.isNotEmpty()) {
            return if (fields.any { it.second == FieldProblem.MISSING }) {
                MemoryDomainResult.Failure(MemoryDomainError.MissingField)
            } else {
                MemoryDomainResult.Failure(MemoryDomainError.UnexpectedField)
            }
        }
        if ((root["schemaVersion"] as? JsonPrimitive)?.content != MemorySchemaVersion.V1.wireValue) {
            return MemoryDomainResult.Failure(MemoryDomainError.UnsupportedSchemaVersion)
        }

        val recordId = decodeId(root["recordId"])
            ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidField)
        val ownerIdentity = when (
            val parsed = HugoIdentityId.parse(primitiveOrNull(root["ownerIdentity"]) ?: "")
        ) {
            is HugoIdentityResult.Success -> parsed.value
            is HugoIdentityResult.Failure ->
                return MemoryDomainResult.Failure(MemoryDomainError.InvalidField)
        }
        val layer = MemoryLayer.entries.firstOrNull { it.name == primitiveOrNull(root["layer"]) }
            ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidField)
        val type = resultOrNull(MemoryTypeToken.parse(primitiveOrNull(root["type"]) ?: ""))
            ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidField)
        val scope = decodeScope(root["scope"])
            ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidField)
        val subjects = decodeSubjects(root["subjects"])
            ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidField)
        val content = if (root["content"] is JsonNull) {
            null
        } else {
            decodeContent(root["content"]) ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidContentValue)
        }
        val provenance = decodeProvenance(root["provenance"])
            ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidField)
        val confidence = if (root["confidence"] is JsonNull) {
            null
        } else {
            decodeConfidence(root["confidence"]) ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidField)
        }
        val validationState = MemoryValidationState.entries.firstOrNull {
            it.name == primitiveOrNull(root["validationState"])
        } ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidField)
        val disposition = MemoryDisposition.entries.firstOrNull {
            it.name == primitiveOrNull(root["disposition"])
        } ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidField)
        val privacy = decodePrivacy(primitiveOrNull(root["privacy"]))
            ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidPrivacyValue)
        val allowedUse = MemoryAllowedUse.fromWireValue(primitiveOrNull(root["allowedUse"]) ?: "")
            ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidAllowedUseValue)
        val retention = if (root["retention"] is JsonNull) {
            null
        } else {
            decodeRetention(root["retention"]) ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidField)
        }
        val supersedes = if (root["supersedes"] is JsonNull) {
            null
        } else {
            decodeId(root["supersedes"]) ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidField)
        }
        val conflictsWith = decodeIdList(root["conflictsWith"])
            ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidField)
        val lineage = if (root["lineage"] is JsonNull) {
            null
        } else {
            decodeLineage(root["lineage"]) ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidLineage)
        }
        val tombstone = if (root["tombstone"] is JsonNull) {
            null
        } else {
            decodeTombstone(root["tombstone"]) ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidField)
        }
        val createdAt = (root["createdAtEpochSecond"] as? JsonPrimitive)?.longOrNull
            ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidField)
        val updatedAt = if (root["updatedAtEpochSecond"] is JsonNull) {
            null
        } else {
            (root["updatedAtEpochSecond"] as? JsonPrimitive)?.longOrNull
                ?: return MemoryDomainResult.Failure(MemoryDomainError.InvalidField)
        }

        return MemoryEnvelope.restore(
            recordId,
            ownerIdentity,
            layer,
            type,
            scope,
            subjects,
            content,
            provenance,
            confidence,
            validationState,
            disposition,
            privacy,
            allowedUse,
            retention,
            supersedes,
            conflictsWith,
            lineage,
            tombstone,
            createdAt,
            updatedAt,
        )
    }

    private enum class FieldProblem { MISSING, UNEXPECTED }

    /** Returns an empty set when the field set matches exactly, otherwise the mismatches. */
    private fun checkFields(obj: JsonObject, expected: Set<String>): Set<Pair<String, FieldProblem>> {
        val problems = mutableSetOf<Pair<String, FieldProblem>>()
        expected.forEach { field -> if (field !in obj) problems.add(field to FieldProblem.MISSING) }
        obj.keys.forEach { field -> if (field !in expected) problems.add(field to FieldProblem.UNEXPECTED) }
        return problems
    }

    private fun primitiveOrNull(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.content

    private fun longField(obj: JsonObject, field: String): Long? =
        (obj[field] as? JsonPrimitive)?.longOrNull

    private fun booleanField(obj: JsonObject, field: String): Boolean? =
        (obj[field] as? JsonPrimitive)?.booleanOrNull

    private fun <T> resultOrNull(result: MemoryDomainResult<T>): T? = when (result) {
        is MemoryDomainResult.Success -> result.value
        is MemoryDomainResult.Failure -> null
    }

    private fun decodeId(element: JsonElement?): MemoryRecordId? =
        resultOrNull(MemoryRecordId.parse(primitiveOrNull(element) ?: ""))

    private fun decodeProviderInstance(element: JsonElement?): ProviderInstanceId? = when (
        val parsed = ProviderInstanceId.parse(primitiveOrNull(element) ?: "")
    ) {
        is ProviderIdentityResult.Success -> parsed.value
        is ProviderIdentityResult.Failure -> null
    }

    private fun decodeIdList(element: JsonElement?): List<MemoryRecordId>? {
        val array = element as? JsonArray ?: return null
        val ids = mutableListOf<MemoryRecordId>()
        array.forEach { item ->
            ids.add(decodeId(item) ?: return null)
        }
        return ids
    }

    private fun decodeSubjects(element: JsonElement?): List<MemorySubjectToken>? {
        val array = element as? JsonArray ?: return null
        if (array.isEmpty() || array.size > MemoryEnvelope.MAXIMUM_SUBJECTS) return null
        val subjects = mutableListOf<MemorySubjectToken>()
        array.forEach { item ->
            subjects.add(
                resultOrNull(MemorySubjectToken.parse(primitiveOrNull(item) ?: "")) ?: return null,
            )
        }
        return subjects
    }

    private fun decodeScope(element: JsonElement?): MemoryScope? {
        val obj = element as? JsonObject ?: return null
        if (checkFields(obj, scopeFields).isNotEmpty()) return null
        val kind = primitiveOrNull(obj["kind"]) ?: return null
        val instanceId = decodeProviderInstance(obj["instanceId"])
        val versionElement = obj["version"]
        val version = if (versionElement is JsonNull) {
            null
        } else {
            resultOrNull(MemoryInstanceVersionToken.parse(primitiveOrNull(versionElement) ?: ""))
                ?: return null
        }
        return when (kind) {
            "global_personal" -> if (instanceId == null) MemoryScope.GlobalPersonal else null
            "body_instance" -> instanceId?.let { MemoryScope.BodyInstance(it, version) }
            "device_instance" -> instanceId?.let { MemoryScope.DeviceInstance(it, version) }
            "provider_instance" -> instanceId?.let { MemoryScope.ProviderInstance(it, version) }
            else -> null
        }
    }

    private fun decodeProvenance(element: JsonElement?): MemoryProvenance? {
        val obj = element as? JsonObject ?: return null
        if (checkFields(obj, provenanceFields).isNotEmpty()) return null
        val origin = MemoryProvenanceOrigin.fromWireValue(primitiveOrNull(obj["origin"]) ?: "")
            ?: return null
        val modelGenerated = booleanField(obj, "modelGenerated") ?: return null
        val sourceType = (ContextSourceType.parse(primitiveOrNull(obj["sourceType"]) ?: "")
            as? ContextObservationResult.Success)?.value ?: return null
        val sourceReference = (ContextSourceReference.parse(primitiveOrNull(obj["sourceReference"]) ?: "")
            as? ContextObservationResult.Success)?.value ?: return null
        val creator = resultOrNull(MemoryCreator.parse(primitiveOrNull(obj["creator"]) ?: ""))
            ?: return null
        val observedAt = longField(obj, "observedAtEpochSecond")
        return MemoryProvenance.restore(origin, modelGenerated, sourceType, sourceReference, creator, observedAt)
    }

    private fun decodeConfidence(element: JsonElement?): ContextConfidence? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        val permille = primitive.longOrNull ?: return null
        return (ContextConfidence.create(permille.toInt()) as? ContextObservationResult.Success)?.value
    }

    private fun decodeRetention(element: JsonElement?): MemoryRetention? {
        val obj = element as? JsonObject ?: return null
        if (checkFields(obj, retentionFields).isNotEmpty()) return null
        val retentionClass = MemoryRetentionClass.entries.firstOrNull {
            it.name == primitiveOrNull(obj["retentionClass"])
        } ?: return null
        val expiresAt = longField(obj, "expiresAtEpochSecond")
        return resultOrNull(MemoryRetention.create(retentionClass, expiresAt))
    }

    private fun decodeLineage(element: JsonElement?): MemoryLineage? {
        if (element is JsonNull) return null
        val obj = element as? JsonObject ?: return null
        if (checkFields(obj, lineageFields).isNotEmpty()) return null
        val ids = decodeIdList(obj["derivedFrom"]) ?: return null
        return resultOrNull(MemoryLineage.create(ids))
    }

    private fun decodeTombstone(element: JsonElement?): MemoryTombstone? {
        if (element is JsonNull) return null
        val obj = element as? JsonObject ?: return null
        if (checkFields(obj, tombstoneFields).isNotEmpty()) return null
        val at = longField(obj, "tombstonedAtEpochSecond") ?: return null
        val actor = resultOrNull(MemoryCreator.parse(primitiveOrNull(obj["actor"]) ?: "")) ?: return null
        return MemoryTombstone.create(actor, at)
    }

    private fun decodeContent(element: JsonElement?): ContextValue? {
        val obj = element as? JsonObject ?: return null
        if (checkFields(obj, contentFields).isNotEmpty()) return null
        val wireType = primitiveOrNull(obj["wireType"]) ?: return null
        val value = obj["value"]
        return when (wireType) {
            "boolean" -> (value as? JsonPrimitive)?.booleanOrNull?.let { ContextValue.BooleanValue(it) }
            "integer" -> (value as? JsonPrimitive)?.longOrNull?.let { ContextValue.IntegerValue(it) }
            "decimal" -> primitiveOrNull(value)
                ?.let { (ContextValue.DecimalValue.parse(it) as? ContextObservationResult.Success)?.value }
            "text" -> primitiveOrNull(value)
                ?.let { (ContextText.create(it) as? ContextObservationResult.Success)?.value }
                ?.let { ContextValue.TextValue(it) }
            "symbol" -> primitiveOrNull(value)
                ?.let { (ContextSymbol.parse(it) as? ContextObservationResult.Success)?.value }
                ?.let { ContextValue.SymbolValue(it) }
            else -> null
        }
    }

    private fun decodePrivacy(raw: String?): ContextPrivacyClassification? = when (raw) {
        "public" -> ContextPrivacyClassification.PUBLIC
        "private" -> ContextPrivacyClassification.PRIVATE
        "sensitive" -> ContextPrivacyClassification.SENSITIVE
        "restricted" -> ContextPrivacyClassification.RESTRICTED
        else -> null
    }

    private fun encodeScope(scope: MemoryScope): JsonObject = when (scope) {
        is MemoryScope.GlobalPersonal -> buildJsonObject {
            put("kind", JsonPrimitive(scope.kind))
            put("instanceId", JsonNull)
            put("version", JsonNull)
        }
        is MemoryScope.BodyInstance -> scopeJsonObject(scope.kind, scope.instanceId, scope.version)
        is MemoryScope.DeviceInstance -> scopeJsonObject(scope.kind, scope.instanceId, scope.version)
        is MemoryScope.ProviderInstance -> scopeJsonObject(scope.kind, scope.instanceId, scope.version)
    }

    private fun scopeJsonObject(
        kind: String,
        instanceId: ProviderInstanceId,
        version: MemoryInstanceVersionToken?,
    ): JsonObject = buildJsonObject {
        put("kind", JsonPrimitive(kind))
        put("instanceId", JsonPrimitive(instanceId.value))
        put("version", version?.let { JsonPrimitive(it.value) } ?: JsonNull)
    }

    private fun encodeProvenance(provenance: MemoryProvenance): JsonObject = buildJsonObject {
        put("origin", JsonPrimitive(provenance.origin.wireValue))
        put("modelGenerated", JsonPrimitive(provenance.modelGenerated))
        put("sourceType", JsonPrimitive(provenance.sourceType.value))
        put("sourceReference", JsonPrimitive(provenance.sourceReference.value))
        put("creator", JsonPrimitive(provenance.creator.value))
        put(
            "observedAtEpochSecond",
            provenance.observedAtEpochSecond?.let { JsonPrimitive(it) } ?: JsonNull,
        )
    }

    private fun encodeRetention(retention: MemoryRetention): JsonObject = buildJsonObject {
        put("retentionClass", JsonPrimitive(retention.retentionClass.name))
        put(
            "expiresAtEpochSecond",
            retention.expiresAtEpochSecond?.let { JsonPrimitive(it) } ?: JsonNull,
        )
    }

    private fun encodeLineage(lineage: MemoryLineage): JsonObject = buildJsonObject {
        put("derivedFrom", JsonArray(lineage.derivedFrom.map { JsonPrimitive(it.value) }))
    }

    private fun encodeTombstone(tombstone: MemoryTombstone): JsonObject = buildJsonObject {
        put("tombstonedAtEpochSecond", JsonPrimitive(tombstone.tombstonedAtEpochSecond))
        put("actor", JsonPrimitive(tombstone.actor.value))
    }

    private fun encodeContent(content: ContextValue): JsonObject = buildJsonObject {
        put("wireType", JsonPrimitive(content.wireType))
        when (content) {
            is ContextValue.BooleanValue -> put("value", JsonPrimitive(content.value))
            is ContextValue.IntegerValue -> put("value", JsonPrimitive(content.value))
            is ContextValue.DecimalValue -> put("value", JsonPrimitive(content.canonicalValue))
            is ContextValue.TextValue -> put("value", JsonPrimitive(content.value.value))
            is ContextValue.SymbolValue -> put("value", JsonPrimitive(content.value.value))
        }
    }
}
