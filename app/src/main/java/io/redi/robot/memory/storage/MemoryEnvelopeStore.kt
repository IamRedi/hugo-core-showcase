package io.redi.robot.memory.storage

import io.redi.robot.memory.domain.MemoryDisposition
import io.redi.robot.memory.domain.MemoryDomainError
import io.redi.robot.memory.domain.MemoryDomainResult
import io.redi.robot.memory.domain.MemoryEnvelope
import io.redi.robot.memory.domain.MemoryEnvelopeCodec
import io.redi.robot.memory.domain.MemoryRecordId
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The authoritative encrypted local memory repository (H059). Persists H058 [MemoryEnvelope]
 * records as strict canonical ciphertext documents in per-record atomic slots, protecting each
 * slot with the injected authenticated-encryption port. Persistence never mutates a caller-owned
 * envelope and never upgrades confidence, authority, privacy, validation state, or allowed use:
 * the stored envelope is the canonical semantic object, byte-for-byte the H058 semantic value.
 *
 * Fail-closed properties: encoding, encryption, slot, and decode failures are typed; wrong or
 * lost keys surface as explicit protection failures with no plaintext exposure; corrupt or
 * unsupported-version persisted documents are rejected; plaintext is never logged and never
 * retained beyond the call. Key-loss recovery is a separate explicit purge, never an automatic
 * fallback to unencrypted storage.
 */
class MemoryEnvelopeStore(
    private val slots: MemoryRecordSlots,
    private val protection: MemoryRecordProtection,
    private val keyVersion: MemoryStoreKeyVersion,
    /**
     * H061-approved additive atomic change-set boundary. Null preserves the plain H059
     * single-record store; when configured it enables all-or-nothing multi-record updates.
     */
    private val changeSetJournal: MemoryChangeSetJournal? = null,
) {
    private val lock = Any()

    /**
     * Persists one envelope (create, update, or tombstone persistence) atomically. The same
     * record id always maps to the same slot, so a tombstoned envelope atomically replaces the
     * previous active record and the active state can never silently reappear.
     */
    fun persist(envelope: MemoryEnvelope): MemoryPersistResult = synchronized(lock) {
        if (!resolvePendingChangeSet()) {
            return MemoryPersistResult.Failed(MemoryPersistenceError.PENDING_CHANGE_SET_UNRESOLVED)
        }
        val document = when (val encoded = MemoryEnvelopeCodec.encode(envelope).toResult()) {
            is MemoryDomainResult.Success -> encoded.value
            is MemoryDomainResult.Failure -> return MemoryPersistResult.Failed(
                MemoryPersistenceError.ENCODING_FAILED,
            )
        }
        val plaintext = document.toByteArray(StandardCharsets.UTF_8)
        if (plaintext.size > MAXIMUM_RECORD_PLAINTEXT_BYTES) {
            return MemoryPersistResult.Failed(MemoryPersistenceError.OVERSIZED_RECORD)
        }
        val protected = when (
            val result = protection.encrypt(plaintext, ASSOCIATED_DATA)
        ) {
            is MemoryProtectionResult.Success -> result.value
            is MemoryProtectionResult.Failure -> return MemoryPersistResult.Failed(result.error.toPersistenceError())
        }
        java.util.Arrays.fill(plaintext, 0)
        return when (val written = slots.writeAtomically(envelope.recordId, encodeSlotDocument(protected))) {
            is MemorySlotWriteResult.Written -> MemoryPersistResult.Persisted
            is MemorySlotWriteResult.Failed -> MemoryPersistResult.Failed(written.reason.toPersistenceError())
        }
    }

    /** Loads one record by id. Missing slots are distinct from failures; decode is strict. */
    fun load(recordId: MemoryRecordId): MemoryLoadResult = synchronized(lock) {
        if (!resolvePendingChangeSet()) {
            return MemoryLoadResult.Failed(MemoryPersistenceError.PENDING_CHANGE_SET_UNRESOLVED)
        }
        return when (val read = slots.read(recordId)) {
            is MemorySlotReadResult.Missing -> MemoryLoadResult.Missing
            is MemorySlotReadResult.Failed ->
                MemoryLoadResult.Failed(read.reason.toPersistenceError())
            is MemorySlotReadResult.Content -> loadContent(recordId, read.bytes)
        }
    }

    /**
     * Bounded listing of persisted record ids in stable ascending order. With [afterId] set,
     * only ids strictly greater than it are listed (keyset continuation across pages); the
     * per-page bound 1..[MAXIMUM_LIST_LIMIT] always applies regardless of continuation.
     */
    fun storedRecordIds(limit: Int, afterId: MemoryRecordId? = null): MemoryListResult = synchronized(lock) {
        if (!resolvePendingChangeSet()) {
            return MemoryListResult.Failed(MemoryPersistenceError.PENDING_CHANGE_SET_UNRESOLVED)
        }
        if (limit <= 0 || limit > MAXIMUM_LIST_LIMIT) {
            return MemoryListResult.Failed(MemoryPersistenceError.INVALID_LIMIT)
        }
        return when (val listed = slots.storedRecordIds(limit, afterId)) {
            is MemorySlotListResult.Ids -> MemoryListResult.Ids(listed.recordIds)
            is MemorySlotListResult.Failed -> MemoryListResult.Failed(listed.reason.toPersistenceError())
        }
    }

    /**
     * Explicit destructive purge for operator-approved recovery after irrecoverable key loss. It
     * is never triggered automatically and never falls back to unencrypted storage. A pending
     * change-set journal blocks the purge: purging slots under a live journal would let the
     * next replay resurrect purged documents.
     */
    fun resetForLostKey(): MemoryResetResult = synchronized(lock) {
        if (!resolvePendingChangeSet()) {
            return MemoryResetResult.Failed(MemoryPersistenceError.PENDING_CHANGE_SET_UNRESOLVED)
        }
        return when (val reset = slots.reset()) {
            is MemorySlotResetResult.Reset -> MemoryResetResult.Reset(reset.purgedSlots)
            is MemorySlotResetResult.Failed -> MemoryResetResult.Failed(reset.reason.toPersistenceError())
        }
    }

    private fun loadContent(recordId: MemoryRecordId, bytes: ByteArray): MemoryLoadResult {
        if (bytes.size > MAXIMUM_RECORD_SLOT_BYTES) {
            return MemoryLoadResult.Failed(MemoryPersistenceError.OVERSIZED_RECORD)
        }
        val protected = decodeSlotDocument(bytes)
            ?: return MemoryLoadResult.Failed(MemoryPersistenceError.CORRUPT_RECORD)
        val plaintext = when (val result = protection.decrypt(protected, ASSOCIATED_DATA)) {
            is MemoryProtectionResult.Success -> result.value
            is MemoryProtectionResult.Failure ->
                return MemoryLoadResult.Failed(result.error.toPersistenceError())
        }
        val decoded = MemoryEnvelopeCodec.decode(String(plaintext, StandardCharsets.UTF_8))
        java.util.Arrays.fill(plaintext, 0)
        return when (decoded) {
            is MemoryDomainResult.Success ->
                if (decoded.value.recordId != recordId) {
                    MemoryLoadResult.Failed(MemoryPersistenceError.RECORD_ID_MISMATCH)
                } else {
                    MemoryLoadResult.Found(decoded.value)
                }
            is MemoryDomainResult.Failure -> when (decoded.error) {
                MemoryDomainError.UnsupportedSchemaVersion ->
                    MemoryLoadResult.Failed(MemoryPersistenceError.UNSUPPORTED_SCHEMA_VERSION)
                else -> MemoryLoadResult.Failed(MemoryPersistenceError.CORRUPT_RECORD)
            }
        }
    }

    private fun String.toResult(): MemoryDomainResult<String> =
        if (isNotEmpty()) MemoryDomainResult.Success(this) else MemoryDomainResult.Failure(MemoryDomainError.InvalidDocument)

    private fun MemorySlotFailure.toPersistenceError(): MemoryPersistenceError = when (this) {
        MemorySlotFailure.IO_FAILED -> MemoryPersistenceError.STORAGE_IO_FAILED
        MemorySlotFailure.CORRUPT_SLOT_NAME -> MemoryPersistenceError.STORAGE_IO_FAILED
        MemorySlotFailure.OVERSIZED_SLOT -> MemoryPersistenceError.OVERSIZED_RECORD
        MemorySlotFailure.INVALID_LIMIT -> MemoryPersistenceError.INVALID_LIMIT
    }

    private fun MemoryJournalFailure.toChangeSetError(): MemoryChangeSetError = when (this) {
        MemoryJournalFailure.IO_FAILED -> MemoryChangeSetError.STORAGE_IO_FAILED
        MemoryJournalFailure.OVERSIZED_JOURNAL -> MemoryChangeSetError.OVERSIZED_RECORD
        MemoryJournalFailure.MALFORMED_JOURNAL -> MemoryChangeSetError.JOURNAL_MALFORMED
    }

    private fun MemorySlotFailure.toChangeSetError(): MemoryChangeSetError = when (this) {
        MemorySlotFailure.IO_FAILED -> MemoryChangeSetError.STORAGE_IO_FAILED
        MemorySlotFailure.CORRUPT_SLOT_NAME -> MemoryChangeSetError.STORAGE_IO_FAILED
        MemorySlotFailure.OVERSIZED_SLOT -> MemoryChangeSetError.OVERSIZED_RECORD
        MemorySlotFailure.INVALID_LIMIT -> MemoryChangeSetError.INVALID_LIMIT
    }

    private fun MemoryProtectionError.toChangeSetError(): MemoryChangeSetError = when (this) {
        MemoryProtectionError.INVALID_KEY_VERSION,
        MemoryProtectionError.KEY_UNAVAILABLE,
        -> MemoryChangeSetError.KEY_UNAVAILABLE
        MemoryProtectionError.KEY_LOST -> MemoryChangeSetError.KEY_LOST
        MemoryProtectionError.ENCRYPTION_FAILED -> MemoryChangeSetError.ENCRYPTION_FAILED
        MemoryProtectionError.AUTHENTICATION_FAILED -> MemoryChangeSetError.AUTHENTICATION_FAILED
        MemoryProtectionError.DECRYPTION_FAILED -> MemoryChangeSetError.DECRYPTION_FAILED
    }

    private fun MemoryProtectionError.toPersistenceError(): MemoryPersistenceError = when (this) {
        MemoryProtectionError.INVALID_KEY_VERSION,
        MemoryProtectionError.KEY_UNAVAILABLE,
        -> MemoryPersistenceError.KEY_UNAVAILABLE
        MemoryProtectionError.KEY_LOST -> MemoryPersistenceError.KEY_LOST
        MemoryProtectionError.ENCRYPTION_FAILED -> MemoryPersistenceError.ENCRYPTION_FAILED
        MemoryProtectionError.AUTHENTICATION_FAILED -> MemoryPersistenceError.AUTHENTICATION_FAILED
        MemoryProtectionError.DECRYPTION_FAILED -> MemoryPersistenceError.DECRYPTION_FAILED
    }

    private fun encodeSlotDocument(payload: ProtectedMemoryRecordPayload): ByteArray = buildString {
        append("{\"schemaVersion\":\"1.0\",\"algorithm\":\"")
        append(payload.algorithm)
        append("\",\"keyVersion\":")
        append(payload.keyVersion.value)
        append(",\"nonce\":\"")
        append(java.util.Base64.getEncoder().encodeToString(payload.nonce))
        append("\",\"ciphertext\":\"")
        append(java.util.Base64.getEncoder().encodeToString(payload.ciphertext))
        append("\"}")
    }.toByteArray(StandardCharsets.UTF_8)

    private fun decodeSlotDocument(bytes: ByteArray): ProtectedMemoryRecordPayload? = try {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(
            String(bytes, StandardCharsets.UTF_8),
        ) as? kotlinx.serialization.json.JsonObject ?: return null
        val algorithm = (root["algorithm"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        val keyVersionValue = (root["keyVersion"] as? JsonPrimitive)?.longOrNull
            ?: return null
        val nonce = java.util.Base64.getDecoder()
            .decode((root["nonce"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null)
        val ciphertext = java.util.Base64.getDecoder()
            .decode((root["ciphertext"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null)
        val keyVersion = when (val parsed = MemoryStoreKeyVersion.create(keyVersionValue.toInt())) {
            is MemoryProtectionResult.Success -> parsed.value
            is MemoryProtectionResult.Failure -> return null
        }
        ProtectedMemoryRecordPayload(algorithm, keyVersion, nonce, ciphertext)
    } catch (_: Exception) {
        null
    }

    /**
     * Durable redo-journal supersession. Supported writer model: one MemoryEnvelopeStore instance
     * in one process for a memory directory. The production file adapters durably establish the
     * journal before returning Written, durably commit every slot before returning Written, and
     * durably remove the journal before a later unrelated operation may pass resolution.
     *
     * The successor record id is the stable public operation identity. Exact retries bind to the
     * same desired authenticated successor + target post-state and return AlreadyApplied.
     */
    fun persistSupersessionChangeSet(
        successor: MemoryEnvelope,
        expectedCurrent: MemoryEnvelope,
        updatedAtEpochSecond: Long,
    ): MemoryChangeSetResult = synchronized(lock) {
        if (!resolvePendingChangeSet()) {
            return MemoryChangeSetResult.Failed(MemoryChangeSetError.PENDING_JOURNAL_UNRESOLVED)
        }
        val journal = changeSetJournal
            ?: return MemoryChangeSetResult.Failed(MemoryChangeSetError.CHANGE_SET_SUPPORT_UNAVAILABLE)
        if (successor.supersedes != expectedCurrent.recordId) {
            return MemoryChangeSetResult.Failed(MemoryChangeSetError.LINK_MISMATCH)
        }
        if (successor.layer != expectedCurrent.layer) {
            return MemoryChangeSetResult.Failed(MemoryChangeSetError.CROSS_LAYER_SUPERSESSION)
        }
        if (expectedCurrent.disposition != MemoryDisposition.ACTIVE) {
            return MemoryChangeSetResult.Failed(MemoryChangeSetError.INVALID_TARGET_STATE)
        }
        if (updatedAtEpochSecond < expectedCurrent.createdAtEpochSecond) {
            return MemoryChangeSetResult.Failed(MemoryChangeSetError.INVALID_UPDATED_AT)
        }
        if (!correctionAuthorityMatches(successor)) {
            return MemoryChangeSetResult.Failed(MemoryChangeSetError.CORRECTION_AUTHORITY_INVALID)
        }
        if (!correctionPairMatches(expectedCurrent, successor)) {
            return MemoryChangeSetResult.Failed(MemoryChangeSetError.CORRECTION_PAIR_MISMATCH)
        }

        val desiredTargetPost = when (val rebuilt = supersededPostStateOf(expectedCurrent, updatedAtEpochSecond)) {
            is MemoryDomainResult.Success -> rebuilt.value
            is MemoryDomainResult.Failure ->
                return MemoryChangeSetResult.Failed(MemoryChangeSetError.INVALID_UPDATED_AT)
        }
        val requestedBinding = MemoryChangeSetPayloadBinding.forSupersession(successor, desiredTargetPost)

        val currentDocument = when (val read = slots.read(expectedCurrent.recordId)) {
            is MemorySlotReadResult.Content -> read.bytes
            is MemorySlotReadResult.Missing ->
                return MemoryChangeSetResult.Failed(MemoryChangeSetError.TARGET_MISSING)
            is MemorySlotReadResult.Failed ->
                return MemoryChangeSetResult.Failed(read.reason.toChangeSetError())
        }
        val current = loadEnvelopeFromSlotDocument(expectedCurrent.recordId, currentDocument)
        java.util.Arrays.fill(currentDocument, 0)
        if (current == null) {
            return MemoryChangeSetResult.Failed(MemoryChangeSetError.CORRUPT_RECORD)
        }

        when (val existingRead = slots.read(successor.recordId)) {
            is MemorySlotReadResult.Content -> {
                val existing = loadEnvelopeFromSlotDocument(successor.recordId, existingRead.bytes)
                java.util.Arrays.fill(existingRead.bytes, 0)
                if (existing == null) {
                    return MemoryChangeSetResult.Failed(MemoryChangeSetError.CORRUPT_RECORD)
                }
                if (MemoryEnvelopeCodec.encode(existing) != MemoryEnvelopeCodec.encode(successor)) {
                    return MemoryChangeSetResult.Failed(MemoryChangeSetError.OPERATION_ID_REUSED)
                }
                val currentBinding = MemoryChangeSetPayloadBinding.forSupersession(existing, current)
                if (currentBinding == requestedBinding &&
                    MemoryEnvelopeCodec.encode(current) == MemoryEnvelopeCodec.encode(desiredTargetPost) &&
                    correctionAuthorityMatches(existing) &&
                    correctionPairMatches(current, existing)
                ) {
                    return MemoryChangeSetResult.AlreadyApplied
                }
                return MemoryChangeSetResult.Failed(MemoryChangeSetError.DUPLICATE_SUCCESSOR)
            }
            is MemorySlotReadResult.Failed ->
                return MemoryChangeSetResult.Failed(existingRead.reason.toChangeSetError())
            MemorySlotReadResult.Missing -> Unit
        }

        if (MemoryEnvelopeCodec.encode(current) != MemoryEnvelopeCodec.encode(expectedCurrent)) {
            return MemoryChangeSetResult.Failed(MemoryChangeSetError.STALE_WRITE)
        }
        if (current.disposition != MemoryDisposition.ACTIVE) {
            return MemoryChangeSetResult.Failed(MemoryChangeSetError.INVALID_TARGET_STATE)
        }
        if (!correctionPairMatches(current, successor)) {
            return MemoryChangeSetResult.Failed(MemoryChangeSetError.CORRECTION_PAIR_MISMATCH)
        }

        val targetPost = when (val rebuilt = supersededPostStateOf(current, updatedAtEpochSecond)) {
            is MemoryDomainResult.Success -> rebuilt.value
            is MemoryDomainResult.Failure ->
                return MemoryChangeSetResult.Failed(MemoryChangeSetError.INVALID_UPDATED_AT)
        }
        val successorDocument = when (val encrypted = encryptRecord(successor)) {
            is MemoryProtectionResult.Success -> encrypted.value
            is MemoryProtectionResult.Failure ->
                return MemoryChangeSetResult.Failed(encrypted.error.toChangeSetError())
        }
        val targetDocument = when (val encrypted = encryptRecord(targetPost)) {
            is MemoryProtectionResult.Success -> encrypted.value
            is MemoryProtectionResult.Failure ->
                return MemoryChangeSetResult.Failed(encrypted.error.toChangeSetError())
        }
        val intent = MemoryChangeSetIntent.create(
            operationId = successor.recordId.value,
            successorRecordId = successor.recordId,
            targetRecordId = targetPost.recordId,
            payloadBindingSha256 = MemoryChangeSetPayloadBinding.forSupersession(successor, targetPost),
            entries = listOf(
                MemoryChangeSetEntry(successor.recordId, encodeSlotDocument(successorDocument)),
                MemoryChangeSetEntry(targetPost.recordId, encodeSlotDocument(targetDocument)),
            ),
        )
        when (val journaled = journal.writeIntent(MemoryChangeSetJournalCodec.encode(intent))) {
            is MemoryJournalWriteResult.Written -> Unit
            is MemoryJournalWriteResult.Failed ->
                return MemoryChangeSetResult.Failed(journaled.reason.toChangeSetError())
        }

        val (_, allSlotsWritten) = applyIntent(intent)
        if (!allSlotsWritten) {
            return MemoryChangeSetResult.Failed(MemoryChangeSetError.STORAGE_IO_FAILED)
        }
        return when (journal.clearIntent()) {
            is MemoryJournalClearResult.Cleared -> MemoryChangeSetResult.Applied
            is MemoryJournalClearResult.Failed ->
                MemoryChangeSetResult.Failed(MemoryChangeSetError.JOURNAL_CLEAR_FAILED)
        }
    }

    /**
     * Strict authenticated replay preflight. Every entry is bounded and decrypted/authenticated
     * before any slot write. The exact V2 intent metadata, operation identity, desired payload
     * binding, correction authority, pair relationship, and governance invariants must all match.
     */
    private fun validateReplayIntent(intent: MemoryChangeSetIntent): Boolean {
        if (intent.entries.size != SUPERSESSION_ENTRY_COUNT) return false
        if (intent.operationId != intent.successorRecordId.value) return false

        val successorEntry = intent.entries[0]
        val targetEntry = intent.entries[1]
        if (successorEntry.recordId != intent.successorRecordId) return false
        if (targetEntry.recordId != intent.targetRecordId) return false
        if (successorEntry.recordId == targetEntry.recordId) return false
        if (successorEntry.slotDocument.size > MAXIMUM_RECORD_SLOT_BYTES ||
            targetEntry.slotDocument.size > MAXIMUM_RECORD_SLOT_BYTES
        ) {
            return false
        }

        // Authenticate/decrypt both entries completely before the first replay write.
        val successorEnvelope =
            loadEnvelopeFromSlotDocument(successorEntry.recordId, successorEntry.slotDocument)
                ?: return false
        val targetEnvelope =
            loadEnvelopeFromSlotDocument(targetEntry.recordId, targetEntry.slotDocument)
                ?: return false

        if (successorEnvelope.disposition != MemoryDisposition.ACTIVE) return false
        if (successorEnvelope.supersedes != targetEntry.recordId) return false
        if (targetEnvelope.disposition != MemoryDisposition.SUPERSEDED) return false
        if (!correctionAuthorityMatches(successorEnvelope)) return false
        if (!correctionPairMatches(targetEnvelope, successorEnvelope)) return false
        if (MemoryChangeSetPayloadBinding.forSupersession(successorEnvelope, targetEnvelope) !=
            intent.payloadBindingSha256
        ) {
            return false
        }
        return true
    }

    private fun correctionAuthorityMatches(successor: MemoryEnvelope): Boolean =
        successor.provenance.origin == io.redi.robot.memory.domain.MemoryProvenanceOrigin.USER_EXPLICIT &&
            !successor.provenance.modelGenerated &&
            successor.validationState == io.redi.robot.memory.domain.MemoryValidationState.VALIDATED

    /**
     * Correction pair integrity: a correction changes content, not governance.
     */
    private fun correctionPairMatches(target: MemoryEnvelope, successor: MemoryEnvelope): Boolean =
        successor.ownerIdentity == target.ownerIdentity &&
            successor.layer == target.layer &&
            successor.type == target.type &&
            successor.scope == target.scope &&
            successor.subjects == target.subjects &&
            successor.privacy == target.privacy &&
            successor.allowedUse == target.allowedUse &&
            successor.retention == target.retention

    /**
     * Resolves a pending change-set journal before an operation proceeds: a present journal is
     * strictly validated and replayed (idempotent forward-apply), then cleared. Returns false
     * when the journal remains unresolved — callers must fail closed without writing.
     */
    private fun resolvePendingChangeSet(): Boolean {
        val journal = changeSetJournal ?: return true
        return when (val read = journal.readIntent()) {
            is MemoryJournalReadResult.Empty -> true
            is MemoryJournalReadResult.Failed -> false
            is MemoryJournalReadResult.Content -> {
                val intent = MemoryChangeSetJournalCodec.decode(read.bytes)
                if (intent == null || !validateReplayIntent(intent)) {
                    false
                } else {
                    val (applied, allWritten) = applyIntent(intent)
                    if (!allWritten) {
                        false
                    } else {
                        // Resolution completes only when the journal is actually cleared: an
                        // uncleared (even if already applied) journal must keep blocking new
                        // operations, or a later replay could overwrite newer committed state.
                        journal.clearIntent() is MemoryJournalClearResult.Cleared
                    }
                }
            }
        }
    }

    private fun applyIntent(intent: MemoryChangeSetIntent): Pair<Int, Boolean> {
        var applied = 0
        var allWritten = true
        intent.entries.forEach { entry ->
            when (val written = slots.writeAtomically(entry.recordId, entry.slotDocument)) {
                is MemorySlotWriteResult.Written -> applied++
                is MemorySlotWriteResult.Failed -> allWritten = false
            }
        }
        return applied to allWritten
    }

    /**
     * Explicit recovery: strictly validates a surviving change-set journal (same preflight as
     * automatic resolution), then replays it deterministically and idempotently (forward
     * re-application of the same encrypted slot documents), then clears it. Nothing to recover
     * is a distinct, non-failing outcome. Validation violations and an unclearable journal fail
     * closed without reporting success.
     */
    fun recoverChangeSet(): MemoryChangeSetRecoveryResult = synchronized(lock) {
        val journal = changeSetJournal
            ?: return MemoryChangeSetRecoveryResult.Failed(MemoryChangeSetError.CHANGE_SET_SUPPORT_UNAVAILABLE)
        return when (val read = journal.readIntent()) {
            is MemoryJournalReadResult.Empty -> MemoryChangeSetRecoveryResult.NothingToRecover
            is MemoryJournalReadResult.Failed ->
                MemoryChangeSetRecoveryResult.Failed(read.reason.toChangeSetError())
            is MemoryJournalReadResult.Content -> when (
                val intent = MemoryChangeSetJournalCodec.decode(read.bytes)
            ) {
                null -> MemoryChangeSetRecoveryResult.Failed(MemoryChangeSetError.JOURNAL_MALFORMED)
                else -> {
                    if (!validateReplayIntent(intent)) {
                        MemoryChangeSetRecoveryResult.Failed(MemoryChangeSetError.JOURNAL_MALFORMED)
                    } else {
                        val (applied, allWritten) = applyIntent(intent)
                        when {
                            !allWritten ->
                                MemoryChangeSetRecoveryResult.Failed(MemoryChangeSetError.STORAGE_IO_FAILED)
                            journal.clearIntent() is MemoryJournalClearResult.Failed ->
                                MemoryChangeSetRecoveryResult.Failed(MemoryChangeSetError.JOURNAL_CLEAR_FAILED)
                            else -> MemoryChangeSetRecoveryResult.Recovered(applied)
                        }
                    }
                }
            }
        }
    }

    /**
     * H061 CAS-style same-record lifecycle persistence (for example the semantic
     * user-confirmation transition): the stored record must still be canonically identical to
     * [expectedCurrent] and ACTIVE at commit time, otherwise the transition is rejected with
     * nothing written. Compare and write serialize on the same store lock, so a concurrently
     * committed correction, tombstone, or competing transition is never overwritten — a
     * superseded or changed record can never be resurrected by a stale lifecycle transition.
     */
    fun persistTransition(
        rebuilt: MemoryEnvelope,
        expectedCurrent: MemoryEnvelope,
    ): MemoryTransitionResult = synchronized(lock) {
        if (!resolvePendingChangeSet()) {
            return MemoryTransitionResult.Failed(MemoryChangeSetError.PENDING_JOURNAL_UNRESOLVED)
        }
        if (rebuilt.recordId != expectedCurrent.recordId) {
            return MemoryTransitionResult.Failed(MemoryChangeSetError.LINK_MISMATCH)
        }
        val document = when (val read = slots.read(expectedCurrent.recordId)) {
            is MemorySlotReadResult.Content -> read.bytes
            is MemorySlotReadResult.Missing ->
                return MemoryTransitionResult.Failed(MemoryChangeSetError.TARGET_MISSING)
            is MemorySlotReadResult.Failed ->
                return MemoryTransitionResult.Failed(read.reason.toChangeSetError())
        }
        val current = loadEnvelopeFromSlotDocument(expectedCurrent.recordId, document)
        java.util.Arrays.fill(document, 0)
        if (current == null) {
            return MemoryTransitionResult.Failed(MemoryChangeSetError.CORRUPT_RECORD)
        }
        if (MemoryEnvelopeCodec.encode(current) != MemoryEnvelopeCodec.encode(expectedCurrent)) {
            return MemoryTransitionResult.Failed(MemoryChangeSetError.STALE_WRITE)
        }
        if (current.disposition != MemoryDisposition.ACTIVE) {
            return MemoryTransitionResult.Failed(MemoryChangeSetError.INVALID_TARGET_STATE)
        }
        val rebuiltDocument = when (val encrypted = encryptRecord(rebuilt)) {
            is MemoryProtectionResult.Success -> encrypted.value
            is MemoryProtectionResult.Failure ->
                return MemoryTransitionResult.Failed(encrypted.error.toChangeSetError())
        }
        return when (val written = slots.writeAtomically(rebuilt.recordId, encodeSlotDocument(rebuiltDocument))) {
            is MemorySlotWriteResult.Written -> MemoryTransitionResult.Applied
            is MemorySlotWriteResult.Failed -> MemoryTransitionResult.Failed(written.reason.toChangeSetError())
        }
    }

    private fun supersededPostStateOf(
        current: MemoryEnvelope,
        updatedAtEpochSecond: Long,
    ): MemoryDomainResult<MemoryEnvelope> {
        val disposition = MemoryDisposition.SUPERSEDED
        val restored = MemoryEnvelope.restore(
            recordId = current.recordId,
            ownerIdentity = current.ownerIdentity,
            layer = current.layer,
            type = current.type,
            scope = current.scope,
            subjects = current.subjects,
            content = current.content,
            provenance = current.provenance,
            confidence = current.confidence,
            validationState = current.validationState,
            disposition = disposition,
            privacy = current.privacy,
            allowedUse = current.allowedUse,
            retention = current.retention,
            supersedes = current.supersedes,
            conflictsWith = current.conflictsWith,
            lineage = current.lineage,
            tombstone = current.tombstone,
            createdAtEpochSecond = current.createdAtEpochSecond,
            updatedAtEpochSecond = updatedAtEpochSecond,
        )
        return restored
    }

    private fun encryptRecord(envelope: MemoryEnvelope): MemoryProtectionResult<ProtectedMemoryRecordPayload> {
        val plaintext = MemoryEnvelopeCodec.encode(envelope).toByteArray(StandardCharsets.UTF_8)
        if (plaintext.size > MAXIMUM_RECORD_PLAINTEXT_BYTES) {
            return MemoryProtectionResult.Failure(MemoryProtectionError.ENCRYPTION_FAILED)
        }
        val result = protection.encrypt(plaintext, ASSOCIATED_DATA)
        java.util.Arrays.fill(plaintext, 0)
        return result
    }

    private fun loadEnvelopeFromSlotDocument(recordId: MemoryRecordId, document: ByteArray): MemoryEnvelope? {
        if (document.size > MAXIMUM_RECORD_SLOT_BYTES) {
            return null
        }
        val payload = decodeSlotDocument(document)
        if (payload == null) {
            return null
        }
        val plaintext = when (val result = protection.decrypt(payload, ASSOCIATED_DATA)) {
            is MemoryProtectionResult.Success -> result.value
            is MemoryProtectionResult.Failure -> return null
        }
        val decoded = MemoryEnvelopeCodec.decode(String(plaintext, StandardCharsets.UTF_8))
        java.util.Arrays.fill(plaintext, 0)
        return when (decoded) {
            is MemoryDomainResult.Success -> if (decoded.value.recordId == recordId) decoded.value else null
            is MemoryDomainResult.Failure -> null
        }
    }

    companion object {
        /** Store/schema binding for authenticated encryption; schema migrations change this. */
        val ASSOCIATED_DATA = "hugo.memory-record-store/envelope@1".toByteArray(StandardCharsets.UTF_8)

        /** A supersession change-set carries exactly two entries: successor + SUPERSEDED target. */
        const val SUPERSESSION_ENTRY_COUNT = 2

        /** H058 field bounds keep documents small; this ceiling is defensive storage policy. */
        const val MAXIMUM_RECORD_PLAINTEXT_BYTES = 32 * 1024

        /** Defensive ceiling for a single persisted slot document. */
        const val MAXIMUM_RECORD_SLOT_BYTES = 64 * 1024

        /** Bounded listing ceiling; callers page through with increasing offsets by id if needed. */
        const val MAXIMUM_LIST_LIMIT = 256
    }
}

sealed interface MemoryPersistResult {
    data object Persisted : MemoryPersistResult

    data class Failed(val error: MemoryPersistenceError) : MemoryPersistResult
}

sealed interface MemoryLoadResult {
    data class Found(val envelope: MemoryEnvelope) : MemoryLoadResult

    data object Missing : MemoryLoadResult

    data class Failed(val error: MemoryPersistenceError) : MemoryLoadResult
}

sealed interface MemoryListResult {
    data class Ids(val recordIds: List<MemoryRecordId>) : MemoryListResult

    data class Failed(val error: MemoryPersistenceError) : MemoryListResult
}

sealed interface MemoryResetResult {
    data class Reset(val purgedSlots: Int) : MemoryResetResult

    data class Failed(val error: MemoryPersistenceError) : MemoryResetResult
}

enum class MemoryPersistenceError {
    PENDING_CHANGE_SET_UNRESOLVED,
    ENCODING_FAILED,
    ENCRYPTION_FAILED,
    DECRYPTION_FAILED,
    AUTHENTICATION_FAILED,
    KEY_UNAVAILABLE,
    KEY_LOST,
    STORAGE_IO_FAILED,
    OVERSIZED_RECORD,
    INVALID_LIMIT,
    CORRUPT_RECORD,
    UNSUPPORTED_SCHEMA_VERSION,
    RECORD_ID_MISMATCH,
    ;
}

sealed interface MemoryChangeSetResult {
    data object Applied : MemoryChangeSetResult

    /** Exact retry of the same stable operation after its durable commit. */
    data object AlreadyApplied : MemoryChangeSetResult

    data class Failed(val error: MemoryChangeSetError) : MemoryChangeSetResult
}

sealed interface MemoryChangeSetRecoveryResult {
    data object NothingToRecover : MemoryChangeSetRecoveryResult

    data class Recovered(val appliedCount: Int) : MemoryChangeSetRecoveryResult

    data class Failed(val error: MemoryChangeSetError) : MemoryChangeSetRecoveryResult
}

sealed interface MemoryTransitionResult {
    data object Applied : MemoryTransitionResult

    data class Failed(val error: MemoryChangeSetError) : MemoryTransitionResult
}

enum class MemoryChangeSetError {
    CHANGE_SET_SUPPORT_UNAVAILABLE,
    JOURNAL_CLEAR_FAILED,
    PENDING_JOURNAL_UNRESOLVED,
    INVALID_LIMIT,
    JOURNAL_WRITE_FAILED,
    JOURNAL_READ_FAILED,
    JOURNAL_MALFORMED,
    TARGET_MISSING,
    STALE_WRITE,
    INVALID_TARGET_STATE,
    DUPLICATE_SUCCESSOR,
    OPERATION_ID_REUSED,
    CORRECTION_AUTHORITY_INVALID,
    LINK_MISMATCH,
    CROSS_LAYER_SUPERSESSION,
    CORRECTION_PAIR_MISMATCH,
    INVALID_UPDATED_AT,
    CORRUPT_RECORD,
    ENCRYPTION_FAILED,
    DECRYPTION_FAILED,
    AUTHENTICATION_FAILED,
    KEY_UNAVAILABLE,
    KEY_LOST,
    STORAGE_IO_FAILED,
    OVERSIZED_RECORD,
    ;
}
