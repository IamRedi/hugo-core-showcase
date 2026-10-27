package io.redi.robot.memory.storage

import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.hugo.identity.HugoIdentityId
import io.redi.robot.memory.domain.MemoryAllowedUse
import io.redi.robot.memory.domain.MemoryDisposition
import io.redi.robot.memory.domain.MemoryDomainResult
import io.redi.robot.memory.domain.MemoryEnvelope
import io.redi.robot.memory.domain.MemoryEnvelopeCodec
import io.redi.robot.memory.domain.MemoryCreator
import io.redi.robot.memory.domain.MemoryLayer
import io.redi.robot.memory.domain.MemoryProvenance
import io.redi.robot.memory.domain.MemoryProvenanceOrigin
import io.redi.robot.memory.domain.MemoryRecordId
import io.redi.robot.memory.domain.MemoryRetention
import io.redi.robot.memory.domain.MemoryRetentionClass
import io.redi.robot.memory.domain.MemoryScope
import io.redi.robot.memory.domain.MemorySubjectToken
import io.redi.robot.memory.domain.MemoryTypeToken
import io.redi.robot.memory.domain.MemoryValidationState
import io.redi.robot.context.api.ContextText
import io.redi.robot.context.api.ContextValue
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H061-approved atomic change-set tests for the encrypted memory store: a correction successor
 * and its SUPERSEDED target commit all-or-nothing; stale writes, duplicate successors, link
 * mismatches, and cross-layer supersession are rejected; a surviving journal is replayed
 * deterministically and idempotently; slot-write failures leave the journal in place for
 * recovery.
 */
class MemoryChangeSetTest {
    @Test
    fun `correction change set applies successor and superseded target atomically`() {
        val h = changeSetHarness()
        val target = h.persistedTarget(1)
        val correction = h.correctionFor(target, index = 2, value = "corrected value")

        assertEquals(
            MemoryChangeSetResult.Applied,
            h.store.persistSupersessionChangeSet(correction, target, updatedAtEpochSecond = 3_000L),
        )

        val loadedTarget = (h.store.load(target.recordId) as MemoryLoadResult.Found).envelope
        assertEquals(MemoryDisposition.SUPERSEDED, loadedTarget.disposition)
        assertEquals(target.validationState, loadedTarget.validationState)
        assertEquals(target.provenance.origin, loadedTarget.provenance.origin)
        assertEquals(target.provenance.modelGenerated, loadedTarget.provenance.modelGenerated)
        assertEquals(target.provenance.creator, loadedTarget.provenance.creator)
        assertEquals(target.provenance.sourceReference, loadedTarget.provenance.sourceReference)
        assertEquals(3_000L, loadedTarget.updatedAtEpochSecond)

        val loadedSuccessor = (h.store.load(correction.recordId) as MemoryLoadResult.Found).envelope
        assertEquals("corrected value", loadedSuccessor.content?.canonicalValue)
        assertTrue(h.journal.readIntent() is MemoryJournalReadResult.Empty)
    }

    @Test
    fun `stale expected target is rejected before any write`() {
        val h = changeSetHarness()
        val target = h.persistedTarget(1)

        // The store moved on after the caller read the target: drift is detected.
        // Another writer legitimately updated the stored target after this caller read it.
        val driftedDocument = MemoryEnvelopeCodec.decode(MemoryEnvelopeCodec.encode(target))
            .success().let { baseline ->
                val driftedJson = MemoryEnvelopeCodec.encode(baseline).replace(
                    "\"confidence\":900",
                    "\"confidence\":901",
                )
                MemoryEnvelopeCodec.decode(driftedJson).success()
            }
        h.store.persist(driftedDocument)

        // The correction was built from the caller's now-stale view and must be rejected.
        val correction = h.correctionFor(target, index = 2, value = "corrected")
        assertEquals(
            MemoryChangeSetError.STALE_WRITE,
            (h.store.persistSupersessionChangeSet(correction, target, 3_000L)
                as MemoryChangeSetResult.Failed).error,
        )
    }

    @Test
    fun `already superseded target cannot be corrected again`() {
        val h = changeSetHarness()
        val target = h.persistedTarget(1)
        val first = h.correctionFor(target, index = 2, value = "v2")
        assertEquals(MemoryChangeSetResult.Applied, h.store.persistSupersessionChangeSet(first, target, 2_000L))
        val superseded = (h.store.load(target.recordId) as MemoryLoadResult.Found).envelope

        val second = h.correctionFor(superseded, index = 3, value = "v3")
        assertEquals(
            MemoryChangeSetError.INVALID_TARGET_STATE,
            (h.store.persistSupersessionChangeSet(second, superseded, 4_000L) as MemoryChangeSetResult.Failed).error,
        )
    }

    @Test
    fun `duplicate successor record id is rejected without touching the target`() {
        val h = changeSetHarness()
        val target = h.persistedTarget(1)
        val unrelated = h.persistedTarget(2)

        val forged = MemoryEnvelopeCodec.decode(
            MemoryEnvelopeCodec.encode(h.correctionFor(target, index = 3, value = "v3")).replace(
                "\"recordId\":\"${recordId(3).value}\"",
                "\"recordId\":\"${unrelated.recordId.value}\"",
            ),
        ).success()

        assertEquals(
            MemoryChangeSetError.OPERATION_ID_REUSED,
            (h.store.persistSupersessionChangeSet(forged, target, 3_000L) as MemoryChangeSetResult.Failed).error,
        )
        val unchanged = (h.store.load(unrelated.recordId) as MemoryLoadResult.Found).envelope
        assertEquals("original value 2", unchanged.content?.canonicalValue)
    }

    @Test
    fun `link mismatch and cross layer supersession are rejected`() {
        val h = changeSetHarness()
        val target = h.persistedTarget(1)

        val badLinkDocument = MemoryEnvelopeCodec.encode(h.correctionFor(target, index = 2, value = "v2"))
            .replace("\"supersedes\":\"${target.recordId.value}\"", "\"supersedes\":\"${recordId(9).value}\"")
        val badLink = MemoryEnvelopeCodec.decode(badLinkDocument).success()
        assertEquals(
            MemoryChangeSetError.LINK_MISMATCH,
            (h.store.persistSupersessionChangeSet(badLink, target, 3_000L) as MemoryChangeSetResult.Failed).error,
        )

        val crossLayerDocument = MemoryEnvelopeCodec.encode(h.correctionFor(target, index = 2, value = "v2"))
            .replace("\"layer\":\"PERSONAL\"", "\"layer\":\"BODY\"")
        val crossLayer = MemoryEnvelopeCodec.decode(crossLayerDocument).success()
        assertTrue(crossLayer.layer == io.redi.robot.memory.domain.MemoryLayer.BODY)
        assertEquals(
            MemoryChangeSetError.CROSS_LAYER_SUPERSESSION,
            (h.store.persistSupersessionChangeSet(crossLayer, target, 3_000L) as MemoryChangeSetResult.Failed).error,
        )
    }

    @Test
    fun `missing target is a typed failure`() {
        val h = changeSetHarness()
        val ghost = h.persistedTarget(9)
        h.slots.snapshot.clear()

        val correction = h.correctionFor(ghost, index = 2, value = "v2")
        assertEquals(
            MemoryChangeSetError.TARGET_MISSING,
            (h.store.persistSupersessionChangeSet(correction, ghost, 3_000L) as MemoryChangeSetResult.Failed).error,
        )
    }

    @Test
    fun `mid write slot failure keeps the journal for recovery and heals deterministically`() {
        val h = changeSetHarness()
        val target = h.persistedTarget(1)
        val correction = h.correctionFor(target, index = 2, value = "v2")

        // The successor slot write succeeds; the target slot write fails (simulated IO fault).
        h.slots.failWritesFor.add(target.recordId)
        assertEquals(
            MemoryChangeSetError.STORAGE_IO_FAILED,
            (h.store.persistSupersessionChangeSet(correction, target, 3_000L) as MemoryChangeSetResult.Failed).error,
        )
        // The journal is intentionally retained for recovery.
        assertTrue(h.journal.readIntent() is MemoryJournalReadResult.Content)

        // The fault clears; recovery replays both encrypted documents deterministically.
        h.slots.failWritesFor.clear()
        val recovered = h.store.recoverChangeSet() as MemoryChangeSetRecoveryResult.Recovered
        assertEquals(2, recovered.appliedCount)

        val loadedCorrection = (h.store.load(correction.recordId) as MemoryLoadResult.Found).envelope
        assertEquals("v2", loadedCorrection.content?.canonicalValue)
        val loadedTarget = (h.store.load(target.recordId) as MemoryLoadResult.Found).envelope
        assertEquals(MemoryDisposition.SUPERSEDED, loadedTarget.disposition)
        assertTrue(h.journal.readIntent() is MemoryJournalReadResult.Empty)
    }

    @Test
    fun `replay is idempotent and a clean store has nothing to recover`() {
        val h = changeSetHarness()
        assertEquals(MemoryChangeSetRecoveryResult.NothingToRecover, h.store.recoverChangeSet())

        val target = h.persistedTarget(1)
        val correction = h.correctionFor(target, index = 2, value = "v2")
        h.store.persistSupersessionChangeSet(correction, target, 3_000L)
        val before = MemoryEnvelopeCodec.encode(
            (h.store.load(correction.recordId) as MemoryLoadResult.Found).envelope,
        )

        // A manually restored (uncleared) journal replays forward without changing state.
        h.journal.restoreUncleared()
        h.store.recoverChangeSet()
        val after = MemoryEnvelopeCodec.encode(
            (h.store.load(correction.recordId) as MemoryLoadResult.Found).envelope,
        )
        assertEquals(before, after)
    }

    @Test
    fun `stores without a journal cannot run change sets`() {
        val h = changeSetHarness(withJournal = false)
        val target = h.persistedTarget(1)
        val correction = h.correctionFor(target, index = 2, value = "v2")
        assertEquals(
            MemoryChangeSetError.CHANGE_SET_SUPPORT_UNAVAILABLE,
            (h.store.persistSupersessionChangeSet(correction, target, 3_000L)
                as MemoryChangeSetResult.Failed).error,
        )
        assertEquals(
            MemoryChangeSetError.CHANGE_SET_SUPPORT_UNAVAILABLE,
            (h.store.recoverChangeSet() as MemoryChangeSetRecoveryResult.Failed).error,
        )
    }

    // ------------------------------------------------------------------ harness

    private fun changeSetHarness(withJournal: Boolean = true) = ChangeSetHarness(withJournal)

    private class ChangeSetHarness(withJournal: Boolean) {
        val slots = CountingSlots()
        val journal = FakeJournal()
        val store: MemoryEnvelopeStore

        init {
            val keyVersion = (MemoryStoreKeyVersion.create(1) as MemoryProtectionResult.Success).value
            store = MemoryEnvelopeStore(
                slots,
                FakeProtection(),
                keyVersion,
                if (withJournal) journal else null,
            )
        }

        fun persistedTarget(index: Int): MemoryEnvelope {
            val envelope = personalEnvelope(index, "original value $index")
            assertEquals(MemoryPersistResult.Persisted, store.persist(envelope))
            return envelope
        }

        fun correctionFor(
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
            confidence = confidence(900),
            validationState = MemoryValidationState.VALIDATED,
            disposition = MemoryDisposition.ACTIVE,
            privacy = target.privacy,
            allowedUse = target.allowedUse,
            retention = retention(MemoryRetentionClass.UNTIL_CORRECTED, null),
            supersedes = target.recordId,
            conflictsWith = emptyList(),
            lineage = null,
            createdAtEpochSecond = 2_000L,
            updatedAtEpochSecond = null,
        ).success()

        fun personalEnvelope(index: Int, value: String): MemoryEnvelope = MemoryEnvelope.create(
            recordId = recordId(index),
            ownerIdentity = owner(),
            layer = MemoryLayer.PERSONAL,
            type = token("preference"),
            scope = MemoryScope.GlobalPersonal,
            subjects = listOf(subject("answers")),
            content = textValue(value),
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
    }

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
        var failWrites = false
        private var content: ByteArray? = null
        private var lastWrittenSnapshot: ByteArray? = null

        override fun writeIntent(bytes: ByteArray): MemoryJournalWriteResult = when {
            failWrites -> MemoryJournalWriteResult.Failed(MemoryJournalFailure.IO_FAILED)
            else -> {
                content = bytes.copyOf()
                lastWrittenSnapshot = bytes.copyOf()
                MemoryJournalWriteResult.Written
            }
        }

        override fun readIntent(): MemoryJournalReadResult = content?.let {
            MemoryJournalReadResult.Content(it.copyOf())
        } ?: MemoryJournalReadResult.Empty

        override fun clearIntent(): MemoryJournalClearResult {
            content = null
            return MemoryJournalClearResult.Cleared
        }

        /** Simulates a crash before the store cleared the journal after a full apply. */
        fun restoreUncleared() {
            content = lastWrittenSnapshot?.copyOf()
        }
    }

    private class FakeProtection : MemoryRecordProtection {
        override fun encrypt(
            plaintext: ByteArray,
            associatedData: ByteArray,
        ): MemoryProtectionResult<ProtectedMemoryRecordPayload> {
            val nonce = ByteArray(12) { (it * 5 + 1).toByte() }
            return MemoryProtectionResult.Success(
                ProtectedMemoryRecordPayload(
                    AUTHENTICATED_ENCRYPTION_ALGORITHM,
                    MemoryStoreKeyVersion.create(1).success(),
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
            bytes.mapIndexed { index, byte -> (byte.toInt() xor (0x2F + index % 3)).toByte() }.toByteArray()
    }

}


private fun <T> MemoryProtectionResult<T>.success(): T = when (this) {
    is MemoryProtectionResult.Success -> value
    is MemoryProtectionResult.Failure -> error("expected success, got $error")
}

private fun <T> MemoryDomainResult<T>.success(): T = when (this) {
    is MemoryDomainResult.Success -> value
    is MemoryDomainResult.Failure -> error("expected success, got $error")
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
        (io.redi.robot.context.api.ContextSourceType.parse("memory.test")
            as io.redi.robot.context.api.ContextObservationResult.Success).value,
        (io.redi.robot.context.api.ContextSourceReference.parse("memory_test")
            as io.redi.robot.context.api.ContextObservationResult.Success).value,
        creator("user_control"),
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
