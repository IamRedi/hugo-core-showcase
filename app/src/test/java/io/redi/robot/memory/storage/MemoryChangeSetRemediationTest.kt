package io.redi.robot.memory.storage

import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextText
import io.redi.robot.context.api.ContextValue
import io.redi.robot.hugo.identity.HugoIdentityId
import io.redi.robot.memory.domain.MemoryAllowedUse
import io.redi.robot.memory.domain.MemoryCreator
import io.redi.robot.memory.domain.MemoryDisposition
import io.redi.robot.memory.domain.MemoryDomainResult
import io.redi.robot.memory.domain.MemoryEnvelope
import io.redi.robot.memory.domain.MemoryEnvelopeCodec
import io.redi.robot.memory.domain.MemoryLayer
import io.redi.robot.memory.domain.MemoryLineage
import io.redi.robot.memory.domain.MemoryProvenance
import io.redi.robot.memory.domain.MemoryProvenanceOrigin
import io.redi.robot.memory.domain.MemoryRecordId
import io.redi.robot.memory.domain.MemoryRetention
import io.redi.robot.memory.domain.MemoryRetentionClass
import io.redi.robot.memory.domain.MemoryScope
import io.redi.robot.memory.domain.MemorySubjectToken
import io.redi.robot.memory.domain.MemoryTypeToken
import io.redi.robot.memory.domain.MemoryValidationState
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H061 remediation guards for the encrypted store: the pending journal gate blocks every public
 * operation until the journal resolves; a failed journal clear never reports success and keeps
 * blocking; replayed journals pass the full strict preflight (entry count, unique ids, size
 * bounds, decrypt/authenticate, envelope-id match, successor/target relationship) before any
 * slot is written; correction pair integrity is enforced at the store boundary; and no store
 * operation emits debug output or plaintext memory state.
 */
class MemoryChangeSetRemediationTest {
    @Test
    fun `pending unresolvable journal blocks persist load listing and new change sets`() {
        val slots = CountingSlots()
        val journal = FakeJournal()
        val store = MemoryEnvelopeStore(slots, FakeProtection(), keyVersion(), journal)
        val target = personalEnvelope(1)
        assertEquals(MemoryPersistResult.Persisted, store.persist(target))

        journal.restoreRaw("not-a-journal-document".toByteArray())

        assertEquals(
            MemoryPersistResult.Failed(MemoryPersistenceError.PENDING_CHANGE_SET_UNRESOLVED),
            store.persist(personalEnvelope(5)),
        )
        assertEquals(
            MemoryLoadResult.Failed(MemoryPersistenceError.PENDING_CHANGE_SET_UNRESOLVED),
            store.load(target.recordId),
        )
        assertEquals(
            MemoryListResult.Failed(MemoryPersistenceError.PENDING_CHANGE_SET_UNRESOLVED),
            store.storedRecordIds(10),
        )
        assertEquals(
            MemoryChangeSetError.PENDING_JOURNAL_UNRESOLVED,
            (store.persistSupersessionChangeSet(correctionFor(target, index = 2, value = "v2"), target, 3_000L)
                as MemoryChangeSetResult.Failed).error,
        )
        assertEquals(
            MemoryChangeSetError.JOURNAL_MALFORMED,
            (store.recoverChangeSet() as MemoryChangeSetRecoveryResult.Failed).error,
        )

        // Once the journal resolves, operations proceed.
        journal.clearIntent()
        assertTrue(store.load(target.recordId) is MemoryLoadResult.Found)
        assertTrue(journal.readIntent() is MemoryJournalReadResult.Empty)
    }

    @Test
    fun `journal clear failure never reports success and blocks until the journal clears`() {
        val slots = CountingSlots()
        val journal = FakeJournal()
        val store = MemoryEnvelopeStore(slots, FakeProtection(), keyVersion(), journal)
        val target = personalEnvelope(1)
        assertEquals(MemoryPersistResult.Persisted, store.persist(target))
        journal.failClear = true

        assertEquals(
            MemoryChangeSetError.JOURNAL_CLEAR_FAILED,
            (store.persistSupersessionChangeSet(correctionFor(target, index = 2, value = "v2"), target, 3_000L)
                as MemoryChangeSetResult.Failed).error,
        )
        // Both slots committed, but the journal is still pending: nothing reports success.
        assertTrue(journal.readIntent() is MemoryJournalReadResult.Content)
        assertEquals(
            MemoryChangeSetError.JOURNAL_CLEAR_FAILED,
            (store.recoverChangeSet() as MemoryChangeSetRecoveryResult.Failed).error,
        )
        assertEquals(
            MemoryLoadResult.Failed(MemoryPersistenceError.PENDING_CHANGE_SET_UNRESOLVED),
            store.load(target.recordId),
        )

        journal.failClear = false
        assertTrue(store.load(target.recordId) is MemoryLoadResult.Found)
        assertEquals(
            MemoryDisposition.SUPERSEDED,
            (store.load(target.recordId) as MemoryLoadResult.Found).envelope.disposition,
        )
        assertTrue(journal.readIntent() is MemoryJournalReadResult.Empty)
    }

    @Test
    fun `replay intent with wrong entry count fails closed without writing`() {
        val slots = CountingSlots()
        val journal = FakeJournal()
        val store = MemoryEnvelopeStore(slots, FakeProtection(), keyVersion(), journal)
        val target = personalEnvelope(1)
        assertEquals(MemoryPersistResult.Persisted, store.persist(target))
        val storedBefore = slots.snapshot.size

        val single = MemoryChangeSetIntent.create(
            "single-entry",
            listOf(MemoryChangeSetEntry(recordId(9), "not-a-slot-document".toByteArray())),
        )
        journal.restoreRaw(MemoryChangeSetJournalCodec.encode(single))

        assertEquals(
            MemoryChangeSetError.JOURNAL_MALFORMED,
            (store.recoverChangeSet() as MemoryChangeSetRecoveryResult.Failed).error,
        )
        assertEquals(storedBefore, slots.snapshot.size)
        assertEquals(
            MemoryLoadResult.Failed(MemoryPersistenceError.PENDING_CHANGE_SET_UNRESOLVED),
            store.load(target.recordId),
        )
    }

    @Test
    fun `tampered journal entries fail the preflight without writing`() {
        val slots = CountingSlots()
        val journal = FakeJournal()
        val store = MemoryEnvelopeStore(slots, FakeProtection(), keyVersion(), journal)
        val target = personalEnvelope(1)
        assertEquals(MemoryPersistResult.Persisted, store.persist(target))
        val storedBefore = slots.snapshot.size

        val forged = MemoryChangeSetIntent.create(
            "forged",
            listOf(
                MemoryChangeSetEntry(recordId(2), "forged successor document".toByteArray()),
                MemoryChangeSetEntry(recordId(1), "forged target document".toByteArray()),
            ),
        )
        journal.restoreRaw(MemoryChangeSetJournalCodec.encode(forged))

        assertEquals(
            MemoryChangeSetError.JOURNAL_MALFORMED,
            (store.recoverChangeSet() as MemoryChangeSetRecoveryResult.Failed).error,
        )
        assertEquals(storedBefore, slots.snapshot.size)
    }

    @Test
    fun `oversized journal entry is rejected by the preflight without writing`() {
        val slots = CountingSlots()
        val journal = FakeJournal()
        val store = MemoryEnvelopeStore(slots, FakeProtection(), keyVersion(), journal)
        val target = personalEnvelope(1)
        assertEquals(MemoryPersistResult.Persisted, store.persist(target))
        val storedBefore = slots.snapshot.size

        val oversized = MemoryChangeSetIntent.create(
            "oversized",
            listOf(
                MemoryChangeSetEntry(recordId(2), ByteArray(MemoryEnvelopeStore.MAXIMUM_RECORD_SLOT_BYTES + 1)),
                MemoryChangeSetEntry(recordId(1), "junk".toByteArray()),
            ),
        )
        journal.restoreRaw(MemoryChangeSetJournalCodec.encode(oversized))

        assertEquals(
            MemoryChangeSetError.JOURNAL_MALFORMED,
            (store.recoverChangeSet() as MemoryChangeSetRecoveryResult.Failed).error,
        )
        assertEquals(storedBefore, slots.snapshot.size)
    }

    @Test
    fun `replay verifies the envelope id match and the successor target relationship`() {
        val slots = CountingSlots()
        val journal = FakeJournal()
        val store = MemoryEnvelopeStore(slots, FakeProtection(), keyVersion(), journal)

        // The successor entry id does not match the envelope inside its slot document.
        val target = personalEnvelope(1)
        val idMismatch = MemoryChangeSetIntent.create(
            "id-mismatch",
            listOf(
                MemoryChangeSetEntry(recordId(5), slotDocumentOf(correctionFor(target, index = 2, value = "v2"))),
                MemoryChangeSetEntry(recordId(1), slotDocumentOf(supersededPostState(target, 3_000L))),
            ),
        )
        journal.restoreRaw(MemoryChangeSetJournalCodec.encode(idMismatch))
        assertEquals(
            MemoryChangeSetError.JOURNAL_MALFORMED,
            (store.recoverChangeSet() as MemoryChangeSetRecoveryResult.Failed).error,
        )
        assertEquals(0, slots.snapshot.size)

        // The target slot document is ACTIVE, not SUPERSEDED: the relationship is invalid.
        val activeTarget = MemoryChangeSetIntent.create(
            "active-target",
            listOf(
                MemoryChangeSetEntry(recordId(2), slotDocumentOf(correctionFor(target, index = 2, value = "v2"))),
                MemoryChangeSetEntry(recordId(1), slotDocumentOf(target)),
            ),
        )
        journal.restoreRaw(MemoryChangeSetJournalCodec.encode(activeTarget))
        assertEquals(
            MemoryChangeSetError.JOURNAL_MALFORMED,
            (store.recoverChangeSet() as MemoryChangeSetRecoveryResult.Failed).error,
        )
        assertEquals(0, slots.snapshot.size)
    }

    @Test
    fun `correction pair mismatch is rejected at the store boundary`() {
        val slots = CountingSlots()
        val journal = FakeJournal()
        val store = MemoryEnvelopeStore(slots, FakeProtection(), keyVersion(), journal)
        val target = personalEnvelope(1)
        assertEquals(MemoryPersistResult.Persisted, store.persist(target))

        // The successor changes the target's subjects: content corrections never change
        // governance fields.
        val tampered = MemoryEnvelope.create(
            recordId = recordId(2),
            ownerIdentity = target.ownerIdentity,
            layer = MemoryLayer.PERSONAL,
            type = target.type,
            scope = target.scope,
            subjects = listOf(subject("other")),
            content = textValue("user correction"),
            provenance = userProvenance(),
            confidence = confidence(1000),
            validationState = MemoryValidationState.VALIDATED,
            disposition = MemoryDisposition.ACTIVE,
            privacy = target.privacy,
            allowedUse = target.allowedUse,
            retention = retention(MemoryRetentionClass.UNTIL_CORRECTED, null),
            supersedes = target.recordId,
            conflictsWith = emptyList(),
            lineage = lineageOf(listOf(target.recordId)),
            createdAtEpochSecond = 2_000L,
            updatedAtEpochSecond = null,
        ).success()

        assertEquals(
            MemoryChangeSetError.CORRECTION_PAIR_MISMATCH,
            (store.persistSupersessionChangeSet(tampered, target, 3_000L) as MemoryChangeSetResult.Failed).error,
        )
        val unchanged = (store.load(target.recordId) as MemoryLoadResult.Found).envelope
        assertEquals(MemoryDisposition.ACTIVE, unchanged.disposition)
        assertEquals("original value 1", unchanged.content?.canonicalValue)
        assertEquals(1, slots.snapshot.size)
    }

    @Test
    fun `store operations emit no debug output or memory state`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        val slots = CountingSlots()
        val journal = FakeJournal()
        val store = MemoryEnvelopeStore(slots, FakeProtection(), keyVersion(), journal)
        try {
            val target = personalEnvelope(1)
            store.persist(target)
            val correction = correctionFor(target, index = 2, value = "user correction")
            store.persistSupersessionChangeSet(correction, target, 3_000L)
            store.load(target.recordId)
            store.load(correction.recordId)
            store.storedRecordIds(16)
            store.recoverChangeSet()
            // Failure paths must stay silent as well.
            journal.restoreRaw("corrupt".toByteArray())
            store.recoverChangeSet()
            store.persist(personalEnvelope(7))
            store.load(recordId(42))
            store.persistSupersessionChangeSet(correction, target, 4_000L)
            store.storedRecordIds(0)
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        assertEquals("", out.toString())
        assertEquals("", err.toString())
    }

    // ---------------------------------------------------------------- helpers

    private fun keyVersion(): MemoryStoreKeyVersion =
        (MemoryStoreKeyVersion.create(1) as MemoryProtectionResult.Success).value

    /** Replicates the store's slot-document encoding for hand-built replay journals. */
    private fun slotDocumentOf(envelope: MemoryEnvelope): ByteArray {
        val plaintext = MemoryEnvelopeCodec.encode(envelope).toByteArray(Charsets.UTF_8)
        val protected = (FakeProtection().encrypt(plaintext, MemoryEnvelopeStore.ASSOCIATED_DATA)
            as MemoryProtectionResult.Success).value
        java.util.Arrays.fill(plaintext, 0)
        return buildString {
            append("{\"schemaVersion\":\"1.0\",\"algorithm\":\"")
            append(protected.algorithm)
            append("\",\"keyVersion\":")
            append(protected.keyVersion.value)
            append(",\"nonce\":\"")
            append(java.util.Base64.getEncoder().encodeToString(protected.nonce))
            append("\",\"ciphertext\":\"")
            append(java.util.Base64.getEncoder().encodeToString(protected.ciphertext))
            append("\"}")
        }.toByteArray(Charsets.UTF_8)
    }

    private fun supersededPostState(target: MemoryEnvelope, updatedAtEpochSecond: Long): MemoryEnvelope =
        MemoryEnvelope.restore(
            recordId = target.recordId,
            ownerIdentity = target.ownerIdentity,
            layer = target.layer,
            type = target.type,
            scope = target.scope,
            subjects = target.subjects,
            content = target.content,
            provenance = target.provenance,
            confidence = target.confidence,
            validationState = target.validationState,
            disposition = MemoryDisposition.SUPERSEDED,
            privacy = target.privacy,
            allowedUse = target.allowedUse,
            retention = target.retention,
            supersedes = target.supersedes,
            conflictsWith = target.conflictsWith,
            lineage = target.lineage,
            tombstone = target.tombstone,
            createdAtEpochSecond = target.createdAtEpochSecond,
            updatedAtEpochSecond = updatedAtEpochSecond,
        ).success()

    private fun personalEnvelope(index: Int): MemoryEnvelope = MemoryEnvelope.create(
        recordId = recordId(index),
        ownerIdentity = owner(),
        layer = MemoryLayer.PERSONAL,
        type = token("preference"),
        scope = MemoryScope.GlobalPersonal,
        subjects = listOf(subject("answers")),
        content = textValue("original value $index"),
        provenance = userProvenance(),
        confidence = confidence(900),
        validationState = MemoryValidationState.VALIDATED,
        disposition = MemoryDisposition.ACTIVE,
        privacy = ContextPrivacyClassification.PRIVATE,
        allowedUse = MemoryAllowedUse.GATEWAY_ALLOWED,
        retention = retention(MemoryRetentionClass.UNTIL_CORRECTED, null),
        supersedes = null,
        conflictsWith = emptyList(),
        lineage = null,
        createdAtEpochSecond = 1_000L,
        updatedAtEpochSecond = null,
    ).success()

    private fun correctionFor(
        target: MemoryEnvelope,
        index: Int,
        value: String,
    ): MemoryEnvelope = MemoryEnvelope.create(
        recordId = recordId(index),
        ownerIdentity = target.ownerIdentity,
        layer = MemoryLayer.PERSONAL,
        type = target.type,
        scope = target.scope,
        subjects = target.subjects,
        content = textValue(value),
        provenance = userProvenance(),
        confidence = confidence(1000),
        validationState = MemoryValidationState.VALIDATED,
        disposition = MemoryDisposition.ACTIVE,
        privacy = target.privacy,
        allowedUse = target.allowedUse,
        retention = target.retention ?: retention(MemoryRetentionClass.UNTIL_CORRECTED, null),
        supersedes = target.recordId,
        conflictsWith = emptyList(),
        lineage = lineageOf(listOf(target.recordId)),
        createdAtEpochSecond = 2_000L,
        updatedAtEpochSecond = null,
    ).success()

    private fun <T> MemoryDomainResult<T>.success(): T = when (this) {
        is MemoryDomainResult.Success -> value
        is MemoryDomainResult.Failure -> error("expected success, got $error")
    }

    private fun <T> MemoryProtectionResult<T>.success(): T = when (this) {
        is MemoryProtectionResult.Success -> value
        is MemoryProtectionResult.Failure -> error("expected success, got $error")
    }

    private fun recordId(index: Int): MemoryRecordId = MemoryRecordId.fromUuid(
        UUID.fromString("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"),
    )

    private fun owner(): HugoIdentityId = HugoIdentityId.fromUuid(
        UUID.fromString("ffffffff-0000-4000-8000-000000000001"),
    )

    private fun token(raw: String): MemoryTypeToken =
        (MemoryTypeToken.parse(raw) as MemoryDomainResult.Success).value

    private fun subject(raw: String): MemorySubjectToken =
        (MemorySubjectToken.parse(raw) as MemoryDomainResult.Success).value

    private fun creator(raw: String): MemoryCreator =
        (MemoryCreator.parse(raw) as MemoryDomainResult.Success).value

    private fun userProvenance(): MemoryProvenance =
        (MemoryProvenance.create(
            MemoryProvenanceOrigin.USER_EXPLICIT,
            modelGenerated = false,
            (io.redi.robot.context.api.ContextSourceType.parse("memory.correction")
                as io.redi.robot.context.api.ContextObservationResult.Success).value,
            (io.redi.robot.context.api.ContextSourceReference.parse("memory_correction")
                as io.redi.robot.context.api.ContextObservationResult.Success).value,
            creator("user_correction"),
            null,
        ) as MemoryDomainResult.Success).value

    private fun confidence(permille: Int): io.redi.robot.context.api.ContextConfidence =
        (io.redi.robot.context.api.ContextConfidence.create(permille)
            as io.redi.robot.context.api.ContextObservationResult.Success).value

    private fun textValue(raw: String): ContextValue = ContextValue.TextValue(
        (ContextText.create(raw) as io.redi.robot.context.api.ContextObservationResult.Success).value,
    )

    private fun retention(retentionClass: MemoryRetentionClass, expiry: Long?): MemoryRetention =
        (MemoryRetention.create(retentionClass, expiry) as MemoryDomainResult.Success).value

    private fun lineageOf(ids: List<MemoryRecordId>): MemoryLineage =
        (MemoryLineage.create(ids) as MemoryDomainResult.Success).value

    private class CountingSlots : MemoryRecordSlots {
        val snapshot = LinkedHashMap<MemoryRecordId, ByteArray>()
        val failWritesFor = mutableSetOf<MemoryRecordId>()

        override fun read(recordId: MemoryRecordId): MemorySlotReadResult =
            snapshot[recordId]?.let { MemorySlotReadResult.Content(it.copyOf()) } ?: MemorySlotReadResult.Missing

        override fun writeAtomically(recordId: MemoryRecordId, bytes: ByteArray): MemorySlotWriteResult = when {
            recordId in failWritesFor -> MemorySlotWriteResult.Failed(MemorySlotFailure.IO_FAILED)
            else -> {
                snapshot[recordId] = bytes.copyOf()
                MemorySlotWriteResult.Written
            }
        }

        override fun storedRecordIds(limit: Int, afterId: MemoryRecordId?): MemorySlotListResult = when {
            limit <= 0 -> MemorySlotListResult.Failed(MemorySlotFailure.INVALID_LIMIT)
            else -> {
                var ids = snapshot.keys.sortedBy { it.value }.asSequence()
                if (afterId != null) ids = ids.dropWhile { it.value <= afterId.value }
                MemorySlotListResult.Ids(ids.take(limit).toList())
            }
        }

        override fun reset(): MemorySlotResetResult {
            val count = snapshot.size
            snapshot.clear()
            return MemorySlotResetResult.Reset(count)
        }
    }

    private class FakeJournal : MemoryChangeSetJournal {
        var failClear = false
        private var content: ByteArray? = null

        override fun writeIntent(bytes: ByteArray): MemoryJournalWriteResult {
            content = bytes.copyOf()
            return MemoryJournalWriteResult.Written
        }

        override fun readIntent(): MemoryJournalReadResult = content?.let {
            MemoryJournalReadResult.Content(it.copyOf())
        } ?: MemoryJournalReadResult.Empty

        override fun clearIntent(): MemoryJournalClearResult = when {
            failClear -> MemoryJournalClearResult.Failed(MemoryJournalFailure.IO_FAILED)
            else -> {
                content = null
                MemoryJournalClearResult.Cleared
            }
        }

        /** Injects arbitrary journal bytes (malformed, forged, or uncleared crash state). */
        fun restoreRaw(bytes: ByteArray) {
            content = bytes.copyOf()
        }
    }

    private class FakeProtection : MemoryRecordProtection {
        override fun encrypt(
            plaintext: ByteArray,
            associatedData: ByteArray,
        ): MemoryProtectionResult<ProtectedMemoryRecordPayload> {
            val nonce = ByteArray(12) { (it * 7 + 2).toByte() }
            return MemoryProtectionResult.Success(
                ProtectedMemoryRecordPayload(
                    AUTHENTICATED_ENCRYPTION_ALGORITHM,
                    (MemoryStoreKeyVersion.create(1) as MemoryProtectionResult.Success).value,
                    nonce,
                    xor(plaintext),
                ),
            )
        }

        override fun decrypt(
            payload: ProtectedMemoryRecordPayload,
            associatedData: ByteArray,
        ): MemoryProtectionResult<ByteArray> =
            MemoryProtectionResult.Success(xor(payload.ciphertext))

        private fun xor(bytes: ByteArray): ByteArray =
            bytes.mapIndexed { index, byte -> (byte.toInt() xor (0x5B + index % 4)).toByte() }.toByteArray()
    }
}
