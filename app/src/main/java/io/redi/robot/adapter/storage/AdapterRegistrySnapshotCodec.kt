package io.redi.robot.adapter.storage

import io.redi.robot.adapter.api.AdapterDescriptor
import io.redi.robot.adapter.api.AdapterDescriptorCodec
import io.redi.robot.adapter.api.AdapterResult
import io.redi.robot.adapter.compatibility.CompatibilityEvidenceReference
import io.redi.robot.adapter.compatibility.CompatibilityInputResult
import io.redi.robot.adapter.registry.AdapterArtifactReference
import io.redi.robot.adapter.registry.AdapterExtensionSchemaClaim
import io.redi.robot.adapter.registry.AdapterKnowledgeState
import io.redi.robot.adapter.registry.AdapterRegistryKey
import io.redi.robot.adapter.registry.AdapterRegistryModelResult
import io.redi.robot.adapter.registry.AdapterRegistryRecord
import io.redi.robot.adapter.registry.AdapterRegistryRevision
import io.redi.robot.adapter.registry.AdapterRegistrySnapshot
import io.redi.robot.connection.api.ConnectionMethodId
import io.redi.robot.connection.api.ConnectionMethodReference
import io.redi.robot.connection.api.ConnectionMethodVersion
import io.redi.robot.connection.api.ConnectionResult
import io.redi.robot.manifest.domain.CommonManifestResult
import io.redi.robot.manifest.domain.ManifestExtensionId
import io.redi.robot.manifest.domain.ManifestSchemaVersion
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

object AdapterRegistrySnapshotCodec {
    private val json = Json { prettyPrint = false; explicitNulls = true }

    fun encode(snapshot: AdapterRegistrySnapshot): String {
        val payload = payload(snapshot)
        val digest = digest(payload)
        return encodeJson(
            JsonObject(
                linkedMapOf<String, JsonElement>().apply {
                    putAll(payload)
                    put("metadataDigest", JsonPrimitive(digest.value))
                },
            ),
        )
    }

    fun decode(text: String): RegistrySnapshotDecodeResult {
        return try {
            val root = parseRoot(text)
            root.requireExactFields(setOf("schemaVersion", "revision", "records", "metadataDigest"))
            val schema = parseSchema(root.string("schemaVersion"))
            val disposition = RegistryPersistenceSchemaPolicy.classify(schema)
            if (disposition != RegistrySchemaDisposition.CURRENT) {
                return RegistrySnapshotDecodeResult.Failure(
                    RegistryPersistenceError.SCHEMA_NOT_CURRENT(schema, disposition),
                )
            }
            val revision = parseRevision(root.long("revision"))
            val records = parseRecords(root.array("records"))
            rejectDuplicateOrConflicting(records)?.let { return RegistrySnapshotDecodeResult.Failure(it) }
            if (revision.value < records.size.toLong()) {
                return RegistrySnapshotDecodeResult.Failure(RegistryPersistenceError.INVALID_REVISION)
            }
            val snapshot = AdapterRegistrySnapshot(revision, records)
            val declaredDigest = parseMetadataDigest(root.string("metadataDigest"))
            val actualDigest = digest(payload(snapshot))
            if (!MessageDigest.isEqual(
                    declaredDigest.value.toByteArray(Charsets.US_ASCII),
                    actualDigest.value.toByteArray(Charsets.US_ASCII),
                )
            ) {
                return RegistrySnapshotDecodeResult.Failure(RegistryPersistenceError.METADATA_DIGEST_MISMATCH)
            }
            RegistrySnapshotDecodeResult.Success(snapshot, schema, declaredDigest)
        } catch (failure: DecodeFailure) {
            RegistrySnapshotDecodeResult.Failure(failure.error)
        } catch (_: RuntimeException) {
            RegistrySnapshotDecodeResult.Failure(RegistryPersistenceError.MALFORMED_DOCUMENT)
        }
    }

    private fun payload(snapshot: AdapterRegistrySnapshot): JsonObject = buildJsonObject {
        put("schemaVersion", RegistryPersistenceSchemaVersion.CURRENT.wireValue)
        put("revision", snapshot.revision.value)
        put("records", buildJsonArray {
            snapshot.records.forEach { add(encodeRecord(it)) }
        })
    }

    private fun encodeRecord(record: AdapterRegistryRecord): JsonObject = buildJsonObject {
        put("descriptor", Json.parseToJsonElement(AdapterDescriptorCodec.encode(record.indexedDescriptor)))
        put("descriptorReference", record.descriptorReference.value)
        put("artifactReference", record.artifactReference?.let { JsonPrimitive(it.value) } ?: JsonNull)
        put("knowledgeState", record.knowledgeState.name)
        put("connectionMethods", buildJsonArray {
            record.connectionMethods.forEach { method ->
                add(buildJsonObject {
                    put("id", method.id.value)
                    put("version", method.version.value)
                })
            }
        })
        put("extensionSchemas", buildJsonArray {
            record.extensionSchemas.forEach { extension ->
                add(buildJsonObject {
                    put("id", extension.id.value)
                    put("schemaVersion", extension.schemaVersion.wireValue)
                })
            }
        })
    }

    private fun parseRecords(array: JsonArray): List<AdapterRegistryRecord> = array.mapIndexed { index, element ->
        val root = element as? JsonObject ?: invalidRecord(index, RegistryRecordError.INVALID_RECORD_METADATA)
        root.requireExactFields(
            setOf(
                "descriptor",
                "descriptorReference",
                "artifactReference",
                "knowledgeState",
                "connectionMethods",
                "extensionSchemas",
            ),
            index,
        )
        val descriptor = parseDescriptor(root.getValue("descriptor"), index)
        val descriptorReference = parseDescriptorReference(root.string("descriptorReference"), index)
        val artifactReference = parseArtifactReference(root, index)
        val knowledgeState = runCatching { AdapterKnowledgeState.valueOf(root.string("knowledgeState")) }
            .getOrElse { invalidRecord(index, RegistryRecordError.INVALID_KNOWLEDGE_STATE) }
        val connectionMethods = root.array("connectionMethods").map { parseConnection(it, index) }
        val extensions = root.array("extensionSchemas").map { parseExtension(it, index) }
        when (
            val result = AdapterRegistryRecord.create(
                descriptor,
                descriptorReference,
                artifactReference,
                knowledgeState,
                connectionMethods,
                extensions,
            )
        ) {
            is AdapterRegistryModelResult.Success -> result.value
            is AdapterRegistryModelResult.Failure -> invalidRecord(index, RegistryRecordError.INVALID_RECORD_METADATA)
        }
    }

    private fun parseDescriptor(element: JsonElement, index: Int): AdapterDescriptor = when (
        val result = AdapterDescriptorCodec.decode(encodeJson(element))
    ) {
        is AdapterResult.Success -> result.value
        is AdapterResult.Failure -> invalidRecord(index, RegistryRecordError.INVALID_DESCRIPTOR)
    }

    private fun parseDescriptorReference(raw: String, index: Int): CompatibilityEvidenceReference = when (
        val result = CompatibilityEvidenceReference.parse(raw)
    ) {
        is CompatibilityInputResult.Success -> result.value
        is CompatibilityInputResult.Failure -> invalidRecord(index, RegistryRecordError.INVALID_DESCRIPTOR_REFERENCE)
    }

    private fun parseArtifactReference(root: JsonObject, index: Int): AdapterArtifactReference? = when (
        val value = root["artifactReference"]
    ) {
        JsonNull -> null
        is JsonPrimitive -> value.contentOrNull?.let { raw ->
            when (val result = AdapterArtifactReference.parse(raw)) {
                is AdapterRegistryModelResult.Success -> result.value
                is AdapterRegistryModelResult.Failure -> invalidRecord(index, RegistryRecordError.INVALID_ARTIFACT_REFERENCE)
            }
        } ?: invalidRecord(index, RegistryRecordError.INVALID_ARTIFACT_REFERENCE)
        else -> invalidRecord(index, RegistryRecordError.INVALID_ARTIFACT_REFERENCE)
    }

    private fun parseConnection(element: JsonElement, index: Int): ConnectionMethodReference {
        val root = element as? JsonObject ?: invalidRecord(index, RegistryRecordError.INVALID_CONNECTION_METHOD)
        root.requireExactFields(setOf("id", "version"), index)
        val id = when (val result = ConnectionMethodId.parse(root.string("id"))) {
            is ConnectionResult.Success -> result.value
            is ConnectionResult.Failure -> invalidRecord(index, RegistryRecordError.INVALID_CONNECTION_METHOD)
        }
        val version = when (val result = ConnectionMethodVersion.parse(root.string("version"))) {
            is ConnectionResult.Success -> result.value
            is ConnectionResult.Failure -> invalidRecord(index, RegistryRecordError.INVALID_CONNECTION_METHOD)
        }
        return ConnectionMethodReference(id, version)
    }

    private fun parseExtension(element: JsonElement, index: Int): AdapterExtensionSchemaClaim {
        val root = element as? JsonObject ?: invalidRecord(index, RegistryRecordError.INVALID_EXTENSION_SCHEMA)
        root.requireExactFields(setOf("id", "schemaVersion"), index)
        val id = when (val result = ManifestExtensionId.parse(root.string("id"))) {
            is CommonManifestResult.Success -> result.value
            is CommonManifestResult.Failure -> invalidRecord(index, RegistryRecordError.INVALID_EXTENSION_SCHEMA)
        }
        val schema = when (val result = ManifestSchemaVersion.parse(root.string("schemaVersion"))) {
            is CommonManifestResult.Success -> result.value
            is CommonManifestResult.Failure -> invalidRecord(index, RegistryRecordError.INVALID_EXTENSION_SCHEMA)
        }
        return AdapterExtensionSchemaClaim(id, schema)
    }

    private fun rejectDuplicateOrConflicting(records: List<AdapterRegistryRecord>): RegistryPersistenceError? {
        val seen = mutableMapOf<AdapterRegistryKey, AdapterRegistryRecord>()
        records.forEachIndexed { index, record ->
            val existing = seen.putIfAbsent(record.key, record) ?: return@forEachIndexed
            return if (existing.canonicalValue == record.canonicalValue) {
                RegistryPersistenceError.DUPLICATE_RECORD(index)
            } else {
                RegistryPersistenceError.CONFLICTING_RECORD(index)
            }
        }
        return null
    }

    private fun parseSchema(raw: String): RegistryPersistenceSchemaVersion = when (
        val result = RegistryPersistenceSchemaVersion.parse(raw)
    ) {
        is RegistryPersistenceModelResult.Success -> result.value
        is RegistryPersistenceModelResult.Failure -> fail(RegistryPersistenceError.MALFORMED_DOCUMENT)
    }

    private fun parseRevision(value: Long): AdapterRegistryRevision = when (
        val result = AdapterRegistryRevision.create(value)
    ) {
        is AdapterRegistryModelResult.Success -> result.value
        is AdapterRegistryModelResult.Failure -> fail(RegistryPersistenceError.INVALID_REVISION)
    }

    private fun parseMetadataDigest(raw: String): RegistryMetadataDigest = when (
        val result = RegistryMetadataDigest.parse(raw)
    ) {
        is RegistryPersistenceModelResult.Success -> result.value
        is RegistryPersistenceModelResult.Failure -> fail(RegistryPersistenceError.MALFORMED_DOCUMENT)
    }

    private fun digest(payload: JsonObject): RegistryMetadataDigest {
        val bytes = MessageDigest.getInstance("SHA-256").digest(encodeJson(payload).toByteArray(Charsets.UTF_8))
        return RegistryMetadataDigest.fromHex(bytes.joinToString("") { "%02x".format(it) })
    }

    private fun parseRoot(text: String): JsonObject = runCatching {
        Json.parseToJsonElement(text) as? JsonObject
    }.getOrNull() ?: fail(RegistryPersistenceError.MALFORMED_DOCUMENT)

    private fun encodeJson(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), element)

    private fun JsonObject.requireExactFields(expected: Set<String>, recordIndex: Int? = null) {
        if (keys != expected) {
            if (recordIndex == null) fail(RegistryPersistenceError.MALFORMED_DOCUMENT)
            else invalidRecord(recordIndex, RegistryRecordError.INVALID_RECORD_METADATA)
        }
    }

    private fun JsonObject.string(field: String): String = (get(field) as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
        ?: fail(RegistryPersistenceError.MALFORMED_DOCUMENT)

    private fun JsonObject.long(field: String): Long = (get(field) as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.longOrNull
        ?: fail(RegistryPersistenceError.MALFORMED_DOCUMENT)

    private fun JsonObject.array(field: String): JsonArray = get(field) as? JsonArray
        ?: fail(RegistryPersistenceError.MALFORMED_DOCUMENT)

    private fun invalidRecord(index: Int, reason: RegistryRecordError): Nothing =
        fail(RegistryPersistenceError.INVALID_RECORD(index, reason))

    private fun fail(error: RegistryPersistenceError): Nothing = throw DecodeFailure(error)

    private class DecodeFailure(val error: RegistryPersistenceError) : RuntimeException()
}
