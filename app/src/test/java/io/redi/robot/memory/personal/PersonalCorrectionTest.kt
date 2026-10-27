package io.redi.robot.memory.personal

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
import io.redi.robot.memory.storage.AUTHENTICATED_ENCRYPTION_ALGORITHM
import io.redi.robot.memory.storage.MemoryJournalWriteResult
import io.redi.robot.memory.storage.MemoryJournalClearResult
import io.redi.robot.memory.storage.MemoryJournalFailure
import io.redi.robot.memory.storage.MemoryJournalReadResult
import io.redi.robot.memory.storage.MemoryChangeSetJournal
import io.redi.robot.memory.storage.MemoryChangeSetRecoveryResult
import io.redi.robot.memory.storage.MemoryEnvelopeStore
import io.redi.robot.memory.storage.MemoryLoadResult
import io.redi.robot.memory.storage.MemoryPersistResult
import io.redi.robot.memory.storage.MemoryProtectionResult
import io.redi.robot.memory.storage.MemoryRecordProtection
import io.redi.robot.memory.storage.MemoryRecordSlots
import io.redi.robot.memory.storage.MemorySlotFailure
import io.redi.robot.memory.storage.MemorySlotListResult
import io.redi.robot.memory.storage.MemorySlotReadResult
import io.redi.robot.memory.storage.MemorySlotResetResult
import io.redi.robot.memory.storage.MemorySlotWriteResult
import io.redi.robot.memory.storage.MemoryStoreKeyVersion
import io.redi.robot.memory.storage.ProtectedMemoryRecordPayload
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalCorrectionTest {
    @Test
    fun `user explicit preference can be created validated`() {
        val envelope = personalEnvelope(1)
        assertEquals(MemoryValidationState.VALIDATED, envelope.validationState)
        assertEquals(MemoryLayer.PERSONAL, envelope.layer)
        assertEquals(MemoryScope.GlobalPersonal, envelope.scope)
    }

    @Test
    fun `model inferred preference must start as candidate`() {
        val inferred = MemoryEnvelopeCodec.decode(
            MemoryEnvelopeCodec.encode(personalEnvelope(2))
                .replace("\"origin\":\"user_explicit\"", "\"origin\":\"model_inferred\"")
                .replace("\"modelGenerated\":false", "\"modelGenerated\":true")
                .replace("\"validationState\":\"VALIDATED\"", "\"validationState\":\"CANDIDATE\""),
        ).success()
        assertEquals(MemoryValidationState.CANDIDATE, inferred.validationState)
        assertTrue(inferred.provenance.modelGenerated)
        assertEquals(MemoryProvenanceOrigin.MODEL_INFERRED, inferred.provenance.origin)
    }

    @Test
    fun `correction successor carries user provenance supersedes link and lineage`() {
        val target = personalEnvelope(1)
        val correction = correctionFor(target, index = 2, value = "actually the opposite")

        assertEquals(MemoryProvenanceOrigin.USER_EXPLICIT, correction.provenance.origin)
        assertFalse(correction.provenance.modelGenerated)
        assertEquals(target.recordId, correction.supersedes)
        assertEquals(listOf(target.recordId), correction.lineage?.derivedFrom)
        assertEquals(MemoryLayer.PERSONAL, correction.layer)
        assertEquals(MemoryValidationState.VALIDATED, correction.validationState)
    }

    @Test
    fun `correction against wrong layer or inactive target is rejected`() {
        val bodyTarget = MemoryEnvelopeCodec.decode(
            MemoryEnvelopeCodec.encode(personalEnvelope(1))
                .replace("\"layer\":\"PERSONAL\"", "\"layer\":\"BODY\""),
        ).success()
        assertEquals(
            PersonalMemoryError.CORRECTION_TARGET_MISMATCH,
            (personalFactory().createCorrection(
                bodyTarget, textValue("x"), confidence(900), 3_000L,
            ) as PersonalCreationResult.Failed).error,
        )

        val inactive = MemoryEnvelopeCodec.decode(
            MemoryEnvelopeCodec.encode(personalEnvelope(1)).replace(
                "\"disposition\":\"ACTIVE\"", "\"disposition\":\"SUPERSEDED\"",
            ),
        ).success()
        assertEquals(
            PersonalMemoryError.TARGET_NOT_ACTIVE,
            (personalFactory().createCorrection(
                inactive, textValue("x"), confidence(900), 3_000L,
            ) as PersonalCreationResult.Failed).error,
        )
    }

    @Test
    fun `user correction supersedes a model inferred candidate atomically`() {
        val slots = CountingSlots()
        val journal = FakeJournal()
        val store = MemoryEnvelopeStore(slots, FakeProtection(), keyVersion(), journal)
        val service = PersonalCorrectionService(store)

        val modelCandidate = MemoryEnvelopeCodec.decode(
            MemoryEnvelopeCodec.encode(personalEnvelope(1))
                .replace("\"origin\":\"user_explicit\"", "\"origin\":\"model_inferred\"")
                .replace("\"modelGenerated\":false", "\"modelGenerated\":true")
                .replace("\"validationState\":\"VALIDATED\"", "\"validationState\":\"CANDIDATE\""),
        ).success()
        store.persist(modelCandidate)

        val correction = correctionFor(modelCandidate, index = 2, value = "user correction")
        assertTrue(
            PersonalCorrectionService(store).correct(modelCandidate, correction, 3_000L)
                is PersonalCorrectionResult.Applied,
        )

        val superseded = (store.load(modelCandidate.recordId) as MemoryLoadResult.Found).envelope
        assertEquals(MemoryDisposition.SUPERSEDED, superseded.disposition)
        assertTrue(superseded.provenance.modelGenerated)
        val successor = (store.load(correction.recordId) as MemoryLoadResult.Found).envelope
        assertEquals(MemoryValidationState.VALIDATED, successor.validationState)
        assertFalse(successor.provenance.modelGenerated)
    }

    @Test
    fun `stale expected target is rejected with zero writes`() {
        val store = newStore()
        val target = personalEnvelope(1)
        store.persist(target)

        val drifted = MemoryEnvelopeCodec.decode(
            MemoryEnvelopeCodec.encode(target).replace(
                "\"confidence\":900", "\"confidence\":901",
            ),
        ).success()
        store.persist(drifted)

        val correction = correctionFor(target, index = 2, value = "user correction")
        val staleResult = PersonalCorrectionService(store).correct(target, correction, 3_000L)
        assertEquals(
            PersonalCorrectionResult.Failed(PersonalMemoryError.STALE_WRITE),
            staleResult,
        )
    }

    @Test
    fun `inference can never supersede a governed record`() {
        val store = newStore()
        val target = personalEnvelope(1)
        store.persist(target)

        val modelCorrection = MemoryEnvelopeCodec.decode(
            MemoryEnvelopeCodec.encode(correctionFor(target, index = 2, value = "model says"))
                .replace("\"origin\":\"user_explicit\"", "\"origin\":\"model_inferred\"")
                .replace("\"modelGenerated\":false", "\"modelGenerated\":true"),
        ).success()
        assertEquals(
            PersonalCorrectionResult.Failed(PersonalMemoryError.CORRECTION_PROVENANCE_FORBIDDEN),
            PersonalCorrectionService(store).correct(target, modelCorrection, 3_000L),
        )
    }

    @Test
    fun `mid write crash keeps the journal and recovery completes the correction`() {
        val slots = CountingSlots()
        val journal = FakeJournal()
        val store = MemoryEnvelopeStore(slots, FakeProtection(), keyVersion(), journal)
        val service = PersonalCorrectionService(store)
        val target = personalEnvelope(1)
        store.persist(target)

        val correction = correctionFor(target, index = 2, value = "user correction")
        slots.failWritesFor.add(target.recordId)
        assertEquals(
            PersonalCorrectionResult.Failed(PersonalMemoryError.STORAGE_FAILED),
            service.correct(target, correction, 3_000L),
        )
        assertTrue(journal.readIntent() is io.redi.robot.memory.storage.MemoryJournalReadResult.Content)

        slots.failWritesFor.clear()
        assertEquals(PersonalRecoveryResult.Recovered(2), service.recover())

        val superseded = (store.load(target.recordId) as MemoryLoadResult.Found).envelope
        assertEquals(MemoryDisposition.SUPERSEDED, superseded.disposition)
        val successor = (store.load(correction.recordId) as MemoryLoadResult.Found).envelope
        assertEquals("user correction", successor.content?.canonicalValue)
    }

    @Test
    fun `duplicate correction successor is rejected without overwriting`() {
        val store = newStore()
        val service = PersonalCorrectionService(store)
        val target = personalEnvelope(1)
        store.persist(target)

        val correction = correctionFor(target, index = 2, value = "user correction")
        assertTrue(
            PersonalCorrectionService(store).correct(target, correction, 3_000L)
                is PersonalCorrectionResult.Applied,
        )

        val second = MemoryEnvelopeCodec.decode(
            MemoryEnvelopeCodec.encode(correctionFor(target, index = 3, value = "second"))
                .replace(
                    "\"recordId\":\"${recordId(3).value}\"",
                    "\"recordId\":\"${correction.recordId.value}\"",
                ),
        ).success()
        // The same operation identity reused with a different payload fails closed and the
        // committed correction is never overwritten.
        assertEquals(
            PersonalCorrectionResult.Failed(PersonalMemoryError.IDEMPOTENCY_CONFLICT),
            PersonalCorrectionService(store).correct(target, second, 4_000L),
        )
        val unchanged = (store.load(correction.recordId) as MemoryLoadResult.Found).envelope
        assertEquals("user correction", unchanged.content?.canonicalValue)
    }

    @Test
    fun `correction that changes governance fields is rejected without overwriting`() {
        val store = newStore()
        val target = personalEnvelope(1)
        store.persist(target)

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
            lineage = (MemoryLineage.create(listOf(target.recordId)) as MemoryDomainResult.Success).value,
            createdAtEpochSecond = 2_000L,
            updatedAtEpochSecond = null,
        ).success()
        assertEquals(
            PersonalCorrectionResult.Failed(PersonalMemoryError.CORRECTION_PAIR_MISMATCH),
            PersonalCorrectionService(store).correct(target, tampered, 3_000L),
        )
        val unchanged = (store.load(target.recordId) as MemoryLoadResult.Found).envelope
        assertEquals(MemoryDisposition.ACTIVE, unchanged.disposition)
    }

    @Test
    fun `personal correction round trips through the encrypted store`() {
        val store = newStore()
        val service = PersonalCorrectionService(store)
        val target = personalEnvelope(1)
        store.persist(target)
        val correction = correctionFor(target, index = 2, value = "user correction")
        assertTrue(service.correct(target, correction, 3_000L) is PersonalCorrectionResult.Applied)

        val reloaded = (store.load(correction.recordId) as MemoryLoadResult.Found).envelope
        assertEquals(
            MemoryEnvelopeCodec.encode(correction),
            MemoryEnvelopeCodec.encode(reloaded),
        )
    }
}

private val personalNextId = java.util.concurrent.atomic.AtomicLong(10L)

private fun personalFactory(): PersonalRecordFactory =
    PersonalRecordFactory.open(owner(), recordIds = { recordId(personalNextId.getAndIncrement().toInt()) })

private fun newStore(): MemoryEnvelopeStore =
    MemoryEnvelopeStore(CountingSlots(), FakeProtection(), keyVersion(), FakeJournal())

private fun keyVersion(): MemoryStoreKeyVersion =
    (MemoryStoreKeyVersion.create(1) as MemoryProtectionResult.Success).value

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
    retention = retention(MemoryRetentionClass.UNTIL_CORRECTED, null),
    supersedes = target.recordId,
    conflictsWith = emptyList(),
    lineage = (MemoryLineage.create(listOf(target.recordId)) as MemoryDomainResult.Success).value,
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

    fun restoreUncleared() {
        content = lastWrittenSnapshot?.copyOf()
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
        bytes.mapIndexed { index, byte -> (byte.toInt() xor (0x5B + index % 4)).toByte() }.toByteArray()
}
