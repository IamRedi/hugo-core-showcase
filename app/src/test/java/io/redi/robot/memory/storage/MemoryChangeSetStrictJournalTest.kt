package io.redi.robot.memory.storage

import io.redi.robot.context.api.ContextConfidence
import io.redi.robot.context.api.ContextObservationResult
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextSourceReference
import io.redi.robot.context.api.ContextSourceType
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
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryChangeSetStrictJournalTest {
    @Test
    fun `codec rejects unsupported version unknown fields missing fields and oversized input`() {
        val intent = legitimateIntent()
        val encoded = String(MemoryChangeSetJournalCodec.encode(intent), Charsets.UTF_8)

        assertNull(
            MemoryChangeSetJournalCodec.decode(
                encoded.replace("\"schemaVersion\":\"2.0\"", "\"schemaVersion\":\"99\"")
                    .toByteArray(),
            ),
        )
        assertNull(
            MemoryChangeSetJournalCodec.decode(
                encoded.replaceFirst("{", "{\"unknown\":true,").toByteArray(),
            ),
        )
        assertNull(
            MemoryChangeSetJournalCodec.decode(
                encoded.replace(Regex(",?\"payloadSha256\":\"[0-9a-f]{64}\""), "")
                    .toByteArray(),
            ),
        )
        assertNull(
            MemoryChangeSetJournalCodec.decode(
                ByteArray(MemoryChangeSetJournalCodec.MAXIMUM_SERIALIZED_JOURNAL_BYTES + 1) { '{'.code.toByte() },
            ),
        )
    }

    @Test
    fun `codec rejects duplicate ids and malformed base64 without constructing an intent`() {
        val intent = legitimateIntent()
        val encoded = String(MemoryChangeSetJournalCodec.encode(intent), Charsets.UTF_8)
        val successorId = intent.successorRecordId.value
        val targetId = intent.targetRecordId.value

        assertNull(
            MemoryChangeSetJournalCodec.decode(
                encoded.replace(targetId, successorId).toByteArray(),
            ),
        )
        assertNull(
            MemoryChangeSetJournalCodec.decode(
                encoded.replace(
                    Regex("\"document\":\"[^\"]+\""),
                    "\"document\":\"%%%not-base64%%%\"",
                ).toByteArray(),
            ),
        )
    }

    @Test
    fun `replay authenticates every entry and rejects forged payload binding before any write`() {
        val slots = CountingSlots()
        val journal = RawJournal()
        val store = MemoryEnvelopeStore(slots, FakeProtection(), keyVersion(), journal)
        val intent = legitimateIntent()
        val encoded = String(MemoryChangeSetJournalCodec.encode(intent), Charsets.UTF_8)
        val forged = encoded.replace(
            intent.payloadBindingSha256,
            "0".repeat(64),
        ).toByteArray()
        journal.content = forged

        assertEquals(
            MemoryChangeSetError.JOURNAL_MALFORMED,
            (store.recoverChangeSet() as MemoryChangeSetRecoveryResult.Failed).error,
        )
        assertEquals(0, slots.writeCount)
        assertTrue(journal.readIntent() is MemoryJournalReadResult.Content)
    }

    @Test
    fun `replay rejects non-user correction authority before any write`() {
        val slots = CountingSlots()
        val journal = RawJournal()
        val store = MemoryEnvelopeStore(slots, FakeProtection(), keyVersion(), journal)
        val target = target(1)
        val targetPost = superseded(target, 3_000L)
        // The forged model-provenance successor must come through the codec restore path: the
        // creation path correctly refuses a model-inferred VALIDATED record outright.
        val forgedSuccessor = MemoryEnvelopeCodec.decode(
            MemoryEnvelopeCodec.encode(
                successor(
                    target = target,
                    index = 2,
                    value = "model-forged",
                    provenance = userProvenance(),
                ),
            ).replace("\"origin\":\"user_explicit\"", "\"origin\":\"model_inferred\"")
                .replace("\"modelGenerated\":false", "\"modelGenerated\":true"),
        ).strictSuccess()
        val intent = MemoryChangeSetIntent.create(
            operationId = forgedSuccessor.recordId.value,
            successorRecordId = forgedSuccessor.recordId,
            targetRecordId = target.recordId,
            payloadBindingSha256 =
                MemoryChangeSetPayloadBinding.forSupersession(forgedSuccessor, targetPost),
            entries = listOf(
                MemoryChangeSetEntry(forgedSuccessor.recordId, slotDocument(forgedSuccessor)),
                MemoryChangeSetEntry(target.recordId, slotDocument(targetPost)),
            ),
        )
        journal.content = MemoryChangeSetJournalCodec.encode(intent)

        assertEquals(
            MemoryChangeSetError.JOURNAL_MALFORMED,
            (store.recoverChangeSet() as MemoryChangeSetRecoveryResult.Failed).error,
        )
        assertEquals(0, slots.writeCount)
    }

    @Test
    fun `exact correction retry returns AlreadyApplied before and after store restart`() {
        val slots = CountingSlots()
        val journal = RawJournal()
        var store = MemoryEnvelopeStore(slots, FakeProtection(), keyVersion(), journal)
        val target = target(1)
        assertEquals(MemoryPersistResult.Persisted, store.persist(target))
        val correction = successor(target, 2, "corrected", userProvenance())

        assertEquals(
            MemoryChangeSetResult.Applied,
            store.persistSupersessionChangeSet(correction, target, 3_000L),
        )
        assertEquals(
            MemoryChangeSetResult.AlreadyApplied,
            store.persistSupersessionChangeSet(correction, target, 3_000L),
        )

        store = MemoryEnvelopeStore(slots, FakeProtection(), keyVersion(), journal)
        assertEquals(
            MemoryChangeSetResult.AlreadyApplied,
            store.persistSupersessionChangeSet(correction, target, 3_000L),
        )
    }

    @Test
    fun `same operation identity with different payload fails closed`() {
        val slots = CountingSlots()
        val journal = RawJournal()
        val store = MemoryEnvelopeStore(slots, FakeProtection(), keyVersion(), journal)
        val target = target(1)
        assertEquals(MemoryPersistResult.Persisted, store.persist(target))
        val first = successor(target, 2, "first", userProvenance())
        assertEquals(
            MemoryChangeSetResult.Applied,
            store.persistSupersessionChangeSet(first, target, 3_000L),
        )

        val changedPayload = successor(target, 2, "different", userProvenance())
        assertEquals(
            MemoryChangeSetError.OPERATION_ID_REUSED,
            (store.persistSupersessionChangeSet(changedPayload, target, 3_000L)
                as MemoryChangeSetResult.Failed).error,
        )
        assertEquals(
            MemoryChangeSetError.DUPLICATE_SUCCESSOR,
            (store.persistSupersessionChangeSet(first, target, 4_000L)
                as MemoryChangeSetResult.Failed).error,
        )
    }

    @Test
    fun `different correction against an already superseded target still fails stale protection`() {
        val slots = CountingSlots()
        val journal = RawJournal()
        val store = MemoryEnvelopeStore(slots, FakeProtection(), keyVersion(), journal)
        val target = target(1)
        assertEquals(MemoryPersistResult.Persisted, store.persist(target))
        val first = successor(target, 2, "first", userProvenance())
        assertEquals(
            MemoryChangeSetResult.Applied,
            store.persistSupersessionChangeSet(first, target, 3_000L),
        )

        val second = successor(target, 3, "second", userProvenance())
        assertEquals(
            MemoryChangeSetError.STALE_WRITE,
            (store.persistSupersessionChangeSet(second, target, 4_000L)
                as MemoryChangeSetResult.Failed).error,
        )
    }

    private fun legitimateIntent(): MemoryChangeSetIntent {
        val target = target(1)
        val targetPost = superseded(target, 3_000L)
        val successor = successor(target, 2, "corrected", userProvenance())
        return MemoryChangeSetIntent.create(
            operationId = successor.recordId.value,
            successorRecordId = successor.recordId,
            targetRecordId = target.recordId,
            payloadBindingSha256 =
                MemoryChangeSetPayloadBinding.forSupersession(successor, targetPost),
            entries = listOf(
                MemoryChangeSetEntry(successor.recordId, slotDocument(successor)),
                MemoryChangeSetEntry(target.recordId, slotDocument(targetPost)),
            ),
        )
    }
}

private class CountingSlots : MemoryRecordSlots {
    val snapshot = LinkedHashMap<MemoryRecordId, ByteArray>()
    var writeCount = 0

    override fun read(recordId: MemoryRecordId): MemorySlotReadResult =
        snapshot[recordId]?.copyOf()?.let { MemorySlotReadResult.Content(it) }
            ?: MemorySlotReadResult.Missing

    override fun writeAtomically(recordId: MemoryRecordId, bytes: ByteArray): MemorySlotWriteResult {
        writeCount++
        snapshot[recordId] = bytes.copyOf()
        return MemorySlotWriteResult.Written
    }

    override fun storedRecordIds(limit: Int, afterId: MemoryRecordId?): MemorySlotListResult =
        MemorySlotListResult.Ids(snapshot.keys.sortedBy { it.value }.take(limit))

    override fun reset(): MemorySlotResetResult {
        val count = snapshot.size
        snapshot.clear()
        return MemorySlotResetResult.Reset(count)
    }
}

private class RawJournal : MemoryChangeSetJournal {
    var content: ByteArray? = null

    override fun writeIntent(bytes: ByteArray): MemoryJournalWriteResult {
        content = bytes.copyOf()
        return MemoryJournalWriteResult.Written
    }

    override fun readIntent(): MemoryJournalReadResult =
        content?.copyOf()?.let { MemoryJournalReadResult.Content(it) }
            ?: MemoryJournalReadResult.Empty

    override fun clearIntent(): MemoryJournalClearResult {
        content = null
        return MemoryJournalClearResult.Cleared
    }
}

private fun target(index: Int): MemoryEnvelope = MemoryEnvelope.create(
    recordId = strictRecordId(index),
    ownerIdentity = strictOwner(),
    layer = MemoryLayer.PERSONAL,
    type = strictType("preference"),
    scope = MemoryScope.GlobalPersonal,
    subjects = listOf(strictSubject("answers")),
    content = strictText("original"),
    provenance = userProvenance(),
    confidence = strictConfidence(900),
    validationState = MemoryValidationState.VALIDATED,
    disposition = MemoryDisposition.ACTIVE,
    privacy = ContextPrivacyClassification.PRIVATE,
    allowedUse = MemoryAllowedUse.GATEWAY_ALLOWED,
    retention = strictRetention(),
    supersedes = null,
    conflictsWith = emptyList(),
    lineage = null,
    createdAtEpochSecond = 1_000L,
    updatedAtEpochSecond = null,
).strictSuccess()

private fun successor(
    target: MemoryEnvelope,
    index: Int,
    value: String,
    provenance: MemoryProvenance,
): MemoryEnvelope = MemoryEnvelope.create(
    recordId = strictRecordId(index),
    ownerIdentity = target.ownerIdentity,
    layer = target.layer,
    type = target.type,
    scope = target.scope,
    subjects = target.subjects,
    content = strictText(value),
    provenance = provenance,
    confidence = strictConfidence(1000),
    validationState = MemoryValidationState.VALIDATED,
    disposition = MemoryDisposition.ACTIVE,
    privacy = target.privacy,
    allowedUse = target.allowedUse,
    retention = target.retention ?: strictRetention(),
    supersedes = target.recordId,
    conflictsWith = emptyList(),
    lineage = (MemoryLineage.create(listOf(target.recordId)) as MemoryDomainResult.Success).value,
    createdAtEpochSecond = 2_000L,
    updatedAtEpochSecond = null,
).strictSuccess()

private fun superseded(target: MemoryEnvelope, at: Long): MemoryEnvelope =
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
        updatedAtEpochSecond = at,
    ).strictSuccess()

private fun slotDocument(envelope: MemoryEnvelope): ByteArray {
    val plaintext = MemoryEnvelopeCodec.encode(envelope).toByteArray(Charsets.UTF_8)
    val payload = (FakeProtection().encrypt(plaintext, MemoryEnvelopeStore.ASSOCIATED_DATA)
        as MemoryProtectionResult.Success).value
    return (
        "{\"schemaVersion\":\"1.0\",\"algorithm\":\"${payload.algorithm}\"," +
            "\"keyVersion\":${payload.keyVersion.value}," +
            "\"nonce\":\"${Base64.getEncoder().encodeToString(payload.nonce)}\"," +
            "\"ciphertext\":\"${Base64.getEncoder().encodeToString(payload.ciphertext)}\"}"
        ).toByteArray(Charsets.UTF_8)
}

private fun userProvenance(): MemoryProvenance = strictProvenance(
    MemoryProvenanceOrigin.USER_EXPLICIT,
    modelGenerated = false,
)

private fun strictProvenance(
    origin: MemoryProvenanceOrigin,
    modelGenerated: Boolean,
): MemoryProvenance =
    (MemoryProvenance.create(
        origin,
        modelGenerated,
        (ContextSourceType.parse("memory.test") as ContextObservationResult.Success).value,
        (ContextSourceReference.parse("memory_test") as ContextObservationResult.Success).value,
        (MemoryCreator.parse("test_actor") as MemoryDomainResult.Success).value,
        null,
    ) as MemoryDomainResult.Success).value

private fun keyVersion(): MemoryStoreKeyVersion =
    (MemoryStoreKeyVersion.create(1) as MemoryProtectionResult.Success).value

private fun strictRecordId(index: Int): MemoryRecordId =
    MemoryRecordId.fromUuid(
        UUID.fromString("10000000-0000-4000-8000-${index.toString().padStart(12, '0')}"),
    )

private fun strictOwner(): HugoIdentityId =
    HugoIdentityId.fromUuid(UUID.fromString("eeeeeeee-0000-4000-8000-000000000001"))

private fun strictType(raw: String): MemoryTypeToken =
    (MemoryTypeToken.parse(raw) as MemoryDomainResult.Success).value

private fun strictSubject(raw: String): MemorySubjectToken =
    (MemorySubjectToken.parse(raw) as MemoryDomainResult.Success).value

private fun strictText(raw: String): ContextValue =
    ContextValue.TextValue((ContextText.create(raw) as ContextObservationResult.Success).value)

private fun strictConfidence(value: Int): ContextConfidence =
    (ContextConfidence.create(value) as ContextObservationResult.Success).value

private fun strictRetention(): MemoryRetention =
    (MemoryRetention.create(MemoryRetentionClass.UNTIL_CORRECTED, null)
        as MemoryDomainResult.Success).value

private fun <T> MemoryDomainResult<T>.strictSuccess(): T = when (this) {
    is MemoryDomainResult.Success -> value
    is MemoryDomainResult.Failure -> error("expected success: $error")
}

private class FakeProtection : MemoryRecordProtection {
    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): MemoryProtectionResult<ProtectedMemoryRecordPayload> =
        MemoryProtectionResult.Success(
            ProtectedMemoryRecordPayload(
                AUTHENTICATED_ENCRYPTION_ALGORITHM,
                keyVersion(),
                ByteArray(12) { (it * 3 + 1).toByte() },
                xor(plaintext),
            ),
        )

    override fun decrypt(
        payload: ProtectedMemoryRecordPayload,
        associatedData: ByteArray,
    ): MemoryProtectionResult<ByteArray> = MemoryProtectionResult.Success(xor(payload.ciphertext))

    private fun xor(bytes: ByteArray): ByteArray =
        bytes.mapIndexed { index, byte ->
            (byte.toInt() xor (0x37 + index % 7)).toByte()
        }.toByteArray()
}
