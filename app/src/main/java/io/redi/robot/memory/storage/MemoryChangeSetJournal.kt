package io.redi.robot.memory.storage

import io.redi.robot.memory.domain.MemoryDomainResult
import io.redi.robot.memory.domain.MemoryEnvelope
import io.redi.robot.memory.domain.MemoryEnvelopeCodec
import io.redi.robot.memory.domain.MemoryRecordId
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * H061 additive H059 extension: a durable redo-journal boundary for atomic multi-record memory
 * updates. The journal contains only encrypted slot documents plus strict operation metadata.
 *
 * A production journal implementation may return Written only after the intent file and its
 * directory entry are durably synchronized. The supported repository writer model is one
 * MemoryEnvelopeStore instance in one process for a memory directory; cross-process writers are
 * not supported by this protocol and would require a separate locking/transaction architecture.
 */
interface MemoryChangeSetJournal {
    fun writeIntent(bytes: ByteArray): MemoryJournalWriteResult

    fun readIntent(): MemoryJournalReadResult

    fun clearIntent(): MemoryJournalClearResult
}

sealed interface MemoryJournalWriteResult {
    data object Written : MemoryJournalWriteResult
    data class Failed(val reason: MemoryJournalFailure) : MemoryJournalWriteResult
}

sealed interface MemoryJournalReadResult {
    data class Content(val bytes: ByteArray) : MemoryJournalReadResult {
        override fun equals(other: Any?): Boolean =
            other is Content && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data object Empty : MemoryJournalReadResult
    data class Failed(val reason: MemoryJournalFailure) : MemoryJournalReadResult
}

sealed interface MemoryJournalClearResult {
    data object Cleared : MemoryJournalClearResult
    data class Failed(val reason: MemoryJournalFailure) : MemoryJournalClearResult
}

enum class MemoryJournalFailure {
    IO_FAILED,
    OVERSIZED_JOURNAL,
    MALFORMED_JOURNAL,
    ;
}

/**
 * One supersession redo intent. operationId is the public idempotency identity (the successor
 * record id). payloadBindingSha256 is a stable logical binding over the authenticated desired
 * successor + target post-state, independent of encryption nonce/ciphertext bytes.
 */
class MemoryChangeSetIntent private constructor(
    val operationId: String,
    val successorRecordId: MemoryRecordId,
    val targetRecordId: MemoryRecordId,
    val payloadBindingSha256: String,
    val entries: List<MemoryChangeSetEntry>,
) {
    override fun toString(): String =
        "MemoryChangeSetIntent(operationId=$operationId, entries=${entries.size})"

    companion object {
        /**
         * Compatibility constructor used by corruption tests. Legitimate supersession writes
         * must use the explicit metadata overload below.
         */
        fun create(
            changeSetId: String,
            entries: List<MemoryChangeSetEntry>,
        ): MemoryChangeSetIntent {
            require(entries.isNotEmpty()) { "change-set must contain at least one entry" }
            val successor = entries.first().recordId
            val target = entries.getOrNull(1)?.recordId ?: successor
            return create(
                operationId = changeSetId,
                successorRecordId = successor,
                targetRecordId = target,
                payloadBindingSha256 = fallbackEntryBinding(entries),
                entries = entries,
            )
        }

        fun create(
            operationId: String,
            successorRecordId: MemoryRecordId,
            targetRecordId: MemoryRecordId,
            payloadBindingSha256: String,
            entries: List<MemoryChangeSetEntry>,
        ): MemoryChangeSetIntent {
            require(operationId.isNotBlank()) { "operationId must not be blank" }
            require(PAYLOAD_SHA256.matches(payloadBindingSha256)) { "invalid payload binding" }
            require(entries.isNotEmpty()) { "change-set must contain at least one entry" }
            val distinct = entries.map { it.recordId.value }.toSet()
            require(distinct.size == entries.size) { "duplicate record ids in change-set" }
            return MemoryChangeSetIntent(
                operationId,
                successorRecordId,
                targetRecordId,
                payloadBindingSha256,
                entries.toList(),
            )
        }

        private fun fallbackEntryBinding(entries: List<MemoryChangeSetEntry>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            updateFramed(digest, "hugo.memory-change-set-test-binding@1".toByteArray(Charsets.UTF_8))
            entries.forEach { entry ->
                updateFramed(digest, entry.recordId.value.toByteArray(Charsets.UTF_8))
                updateFramed(digest, entry.slotDocument)
            }
            return digest.digest().toLowerHex()
        }

        private val PAYLOAD_SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

class MemoryChangeSetEntry(
    val recordId: MemoryRecordId,
    val slotDocument: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is MemoryChangeSetEntry &&
        recordId == other.recordId && slotDocument.contentEquals(other.slotDocument)

    override fun hashCode(): Int = 31 * recordId.hashCode() + slotDocument.contentHashCode()
}

/**
 * Stable logical operation binding. Both envelopes have already passed normal domain validation;
 * replay decrypts/authenticates both before recomputing this digest and before any slot write.
 */
object MemoryChangeSetPayloadBinding {
    fun forSupersession(successor: MemoryEnvelope, targetPostState: MemoryEnvelope): String {
        val digest = MessageDigest.getInstance("SHA-256")
        updateFramed(digest, DOMAIN.toByteArray(Charsets.UTF_8))
        updateFramed(digest, successor.recordId.value.toByteArray(Charsets.UTF_8))
        updateFramed(digest, targetPostState.recordId.value.toByteArray(Charsets.UTF_8))
        updateFramed(digest, MemoryEnvelopeCodec.encode(successor).toByteArray(Charsets.UTF_8))
        updateFramed(digest, MemoryEnvelopeCodec.encode(targetPostState).toByteArray(Charsets.UTF_8))
        return digest.digest().toLowerHex()
    }

    private const val DOMAIN = "hugo.memory-supersession-payload@1"
}

/**
 * Strict journal codec. It rejects unsupported versions, unknown/missing fields, duplicate ids,
 * malformed base64, invalid ids/bindings, and oversized serialized input before converting the
 * input to a String or decoding document payloads.
 */
object MemoryChangeSetJournalCodec {
    private val json = Json
    private val rootFields = setOf(
        "schemaVersion",
        "operationId",
        "successorRecordId",
        "targetRecordId",
        "payloadSha256",
        "entries",
    )
    private val entryFields = setOf("recordId", "document")
    private val payloadSha256 = Regex("^[0-9a-f]{64}$")

    fun encode(intent: MemoryChangeSetIntent): ByteArray = buildJsonObject {
        put("schemaVersion", JsonPrimitive(SCHEMA_VERSION))
        put("operationId", JsonPrimitive(intent.operationId))
        put("successorRecordId", JsonPrimitive(intent.successorRecordId.value))
        put("targetRecordId", JsonPrimitive(intent.targetRecordId.value))
        put("payloadSha256", JsonPrimitive(intent.payloadBindingSha256))
        put(
            "entries",
            JsonArray(intent.entries.map { entry ->
                buildJsonObject {
                    put("recordId", JsonPrimitive(entry.recordId.value))
                    put("document", JsonPrimitive(Base64.getEncoder().encodeToString(entry.slotDocument)))
                }
            }),
        )
    }.toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): MemoryChangeSetIntent? {
        if (bytes.size > MAXIMUM_SERIALIZED_JOURNAL_BYTES) return null
        return try {
            val root = json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject ?: return null
            if (root.keys != rootFields) return null
            if (root.stringField("schemaVersion") != SCHEMA_VERSION) return null

            val operationId = root.stringField("operationId") ?: return null
            if (operationId.isBlank()) return null
            val successorRecordId = parseRecordId(root.stringField("successorRecordId") ?: return null) ?: return null
            val targetRecordId = parseRecordId(root.stringField("targetRecordId") ?: return null) ?: return null
            val binding = root.stringField("payloadSha256") ?: return null
            if (!payloadSha256.matches(binding)) return null

            val array = root["entries"] as? JsonArray ?: return null
            // Supersession is a fixed two-record transaction. Reject cardinality before
            // allocating/decoding any entry document bytes.
            if (array.size != EXPECTED_SUPERSESSION_ENTRY_COUNT) return null
            val parsed = array.map { element ->
                val obj = element as? JsonObject ?: return null
                if (obj.keys != entryFields) return null
                val recordId = parseRecordId(obj.stringField("recordId") ?: return null) ?: return null
                val encodedDocument = obj.stringField("document") ?: return null
                val document = Base64.getDecoder().decode(encodedDocument)
                if (document.size > MAXIMUM_ENTRY_DOCUMENT_BYTES) return null
                MemoryChangeSetEntry(recordId, document)
            }
            if (parsed.map { it.recordId.value }.toSet().size != parsed.size) return null

            MemoryChangeSetIntent.create(
                operationId = operationId,
                successorRecordId = successorRecordId,
                targetRecordId = targetRecordId,
                payloadBindingSha256 = binding,
                entries = parsed,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.stringField(name: String): String? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content
    }

    private fun parseRecordId(raw: String): MemoryRecordId? =
        when (val parsed = MemoryRecordId.parse(raw)) {
            is MemoryDomainResult.Success -> parsed.value
            is MemoryDomainResult.Failure -> null
        }

    const val SCHEMA_VERSION = "2.0"
    const val MAXIMUM_SERIALIZED_JOURNAL_BYTES = 128 * 1024
    private const val EXPECTED_SUPERSESSION_ENTRY_COUNT = 2
    private const val MAXIMUM_ENTRY_DOCUMENT_BYTES = 64 * 1024
}

private fun updateFramed(digest: MessageDigest, bytes: ByteArray) {
    val size = bytes.size
    digest.update(
        byteArrayOf(
            ((size ushr 24) and 0xff).toByte(),
            ((size ushr 16) and 0xff).toByte(),
            ((size ushr 8) and 0xff).toByte(),
            (size and 0xff).toByte(),
        ),
    )
    digest.update(bytes)
}

private fun ByteArray.toLowerHex(): String {
    val alphabet = "0123456789abcdef"
    val chars = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        chars[index * 2] = alphabet[value ushr 4]
        chars[index * 2 + 1] = alphabet[value and 0x0f]
    }
    return String(chars)
}
