package io.redi.robot.memory

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
import io.redi.robot.memory.personal.PersonalCorrectionResult
import io.redi.robot.memory.personal.PersonalCorrectionService
import io.redi.robot.memory.personal.PersonalCreationResult
import io.redi.robot.memory.personal.PersonalMemoryError
import io.redi.robot.memory.personal.PersonalRecordFactory
import io.redi.robot.memory.semantic.SemanticCorrectionResult
import io.redi.robot.memory.semantic.SemanticCorrectionService
import io.redi.robot.memory.semantic.SemanticCreationResult
import io.redi.robot.memory.semantic.SemanticFactFactory
import io.redi.robot.memory.storage.AUTHENTICATED_ENCRYPTION_ALGORITHM
import io.redi.robot.memory.storage.MemoryChangeSetJournal
import io.redi.robot.memory.storage.MemoryEnvelopeStore
import io.redi.robot.memory.storage.MemoryJournalClearResult
import io.redi.robot.memory.storage.MemoryJournalReadResult
import io.redi.robot.memory.storage.MemoryJournalWriteResult
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
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionPublicIdempotencyTest {
    @Test
    fun `personal correction exact retry is AlreadyApplied across restart and conflicts fail closed`() {
        val h = PublicHarness()
        var store = h.newStore()
        val target = target(MemoryLayer.PERSONAL)
        assertEquals(MemoryPersistResult.Persisted, store.persist(target))

        val correction = (
            PersonalRecordFactory.open(owner()) { recordId(2) }.createCorrection(
                target,
                text("corrected"),
                confidence(1000),
                2_000L,
            ) as PersonalCreationResult.Created
        ).envelope

        assertTrue(
            PersonalCorrectionService(store).correct(target, correction, 3_000L) is
                PersonalCorrectionResult.Applied,
        )
        assertTrue(
            PersonalCorrectionService(store).correct(target, correction, 3_000L) is
                PersonalCorrectionResult.AlreadyApplied,
        )

        store = h.newStore()
        assertTrue(
            PersonalCorrectionService(store).correct(target, correction, 3_000L) is
                PersonalCorrectionResult.AlreadyApplied,
        )

        val sameOperationDifferentPayload = (
            PersonalRecordFactory.open(owner()) { recordId(2) }.createCorrection(
                target,
                text("forged-different-payload"),
                confidence(1000),
                2_000L,
            ) as PersonalCreationResult.Created
        ).envelope
        assertEquals(
            PersonalCorrectionResult.Failed(PersonalMemoryError.IDEMPOTENCY_CONFLICT),
            PersonalCorrectionService(store).correct(target, sameOperationDifferentPayload, 3_000L),
        )

        val differentCorrection = (
            PersonalRecordFactory.open(owner()) { recordId(3) }.createCorrection(
                target,
                text("different-operation"),
                confidence(1000),
                2_000L,
            ) as PersonalCreationResult.Created
        ).envelope
        assertEquals(
            PersonalCorrectionResult.Failed(PersonalMemoryError.STALE_WRITE),
            PersonalCorrectionService(store).correct(target, differentCorrection, 4_000L),
        )
    }

    @Test
    fun `semantic correction exact retry is AlreadyApplied across restart`() {
        val h = PublicHarness()
        var store = h.newStore()
        val target = target(MemoryLayer.SEMANTIC)
        assertEquals(MemoryPersistResult.Persisted, store.persist(target))

        val correction = (
            SemanticFactFactory.open(owner()) { recordId(12) }.createCorrection(
                target,
                text("corrected semantic"),
                confidence(1000),
                2_000L,
            ) as SemanticCreationResult.Created
        ).envelope

        assertTrue(
            SemanticCorrectionService(store).correct(target, correction, 3_000L) is
                SemanticCorrectionResult.Applied,
        )
        assertTrue(
            SemanticCorrectionService(store).correct(target, correction, 3_000L) is
                SemanticCorrectionResult.AlreadyApplied,
        )

        store = h.newStore()
        assertTrue(
            SemanticCorrectionService(store).correct(target, correction, 3_000L) is
                SemanticCorrectionResult.AlreadyApplied,
        )
    }
}

private class PublicHarness {
    private val slots = PublicSlots()
    private val journal = PublicJournal()
    private val protection = PublicProtection()

    fun newStore(): MemoryEnvelopeStore =
        MemoryEnvelopeStore(slots, protection, keyVersion(), journal)
}

private class PublicSlots : MemoryRecordSlots {
    private val values = LinkedHashMap<MemoryRecordId, ByteArray>()

    override fun read(recordId: MemoryRecordId): MemorySlotReadResult =
        values[recordId]?.copyOf()?.let { MemorySlotReadResult.Content(it) }
            ?: MemorySlotReadResult.Missing

    override fun writeAtomically(recordId: MemoryRecordId, bytes: ByteArray): MemorySlotWriteResult {
        values[recordId] = bytes.copyOf()
        return MemorySlotWriteResult.Written
    }

    override fun storedRecordIds(limit: Int, afterId: MemoryRecordId?): MemorySlotListResult {
        if (limit <= 0) return MemorySlotListResult.Failed(MemorySlotFailure.INVALID_LIMIT)
        var ids = values.keys.sortedBy { it.value }.asSequence()
        if (afterId != null) ids = ids.dropWhile { it.value <= afterId.value }
        return MemorySlotListResult.Ids(ids.take(limit).toList())
    }

    override fun reset(): MemorySlotResetResult {
        val count = values.size
        values.clear()
        return MemorySlotResetResult.Reset(count)
    }
}

private class PublicJournal : MemoryChangeSetJournal {
    private var bytes: ByteArray? = null

    override fun writeIntent(bytes: ByteArray): MemoryJournalWriteResult {
        this.bytes = bytes.copyOf()
        return MemoryJournalWriteResult.Written
    }

    override fun readIntent(): MemoryJournalReadResult =
        bytes?.copyOf()?.let { MemoryJournalReadResult.Content(it) } ?: MemoryJournalReadResult.Empty

    override fun clearIntent(): MemoryJournalClearResult {
        bytes = null
        return MemoryJournalClearResult.Cleared
    }
}

private class PublicProtection : MemoryRecordProtection {
    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): MemoryProtectionResult<ProtectedMemoryRecordPayload> =
        MemoryProtectionResult.Success(
            ProtectedMemoryRecordPayload(
                AUTHENTICATED_ENCRYPTION_ALGORITHM,
                keyVersion(),
                ByteArray(12) { (it * 7 + 1).toByte() },
                xor(plaintext),
            ),
        )

    override fun decrypt(
        payload: ProtectedMemoryRecordPayload,
        associatedData: ByteArray,
    ): MemoryProtectionResult<ByteArray> =
        MemoryProtectionResult.Success(xor(payload.ciphertext))

    private fun xor(bytes: ByteArray): ByteArray =
        bytes.mapIndexed { index, byte ->
            (byte.toInt() xor (0x4d + index % 5)).toByte()
        }.toByteArray()
}

private fun target(layer: MemoryLayer): MemoryEnvelope =
    MemoryEnvelope.create(
        recordId = recordId(if (layer == MemoryLayer.PERSONAL) 1 else 11),
        ownerIdentity = owner(),
        layer = layer,
        type = type(if (layer == MemoryLayer.PERSONAL) "preference" else "fact"),
        scope = MemoryScope.GlobalPersonal,
        subjects = listOf(subject("answers")),
        content = text("original"),
        provenance = provenance(),
        confidence = confidence(900),
        validationState = MemoryValidationState.VALIDATED,
        disposition = MemoryDisposition.ACTIVE,
        privacy = ContextPrivacyClassification.PRIVATE,
        allowedUse = MemoryAllowedUse.GATEWAY_ALLOWED,
        retention = retention(),
        supersedes = null,
        conflictsWith = emptyList(),
        lineage = null,
        createdAtEpochSecond = 1_000L,
        updatedAtEpochSecond = null,
    ).success()

private fun provenance(): MemoryProvenance =
    (MemoryProvenance.create(
        MemoryProvenanceOrigin.USER_EXPLICIT,
        modelGenerated = false,
        (ContextSourceType.parse("memory.test") as ContextObservationResult.Success).value,
        (ContextSourceReference.parse("memory_test") as ContextObservationResult.Success).value,
        (MemoryCreator.parse("user") as MemoryDomainResult.Success).value,
        null,
    ) as MemoryDomainResult.Success).value

private fun keyVersion(): MemoryStoreKeyVersion =
    (MemoryStoreKeyVersion.create(1) as MemoryProtectionResult.Success).value

private fun owner(): HugoIdentityId =
    HugoIdentityId.fromUuid(UUID.fromString("dddddddd-0000-4000-8000-000000000001"))

private fun recordId(index: Int): MemoryRecordId =
    MemoryRecordId.fromUuid(
        UUID.fromString("30000000-0000-4000-8000-${index.toString().padStart(12, '0')}"),
    )

private fun type(raw: String): MemoryTypeToken =
    (MemoryTypeToken.parse(raw) as MemoryDomainResult.Success).value

private fun subject(raw: String): MemorySubjectToken =
    (MemorySubjectToken.parse(raw) as MemoryDomainResult.Success).value

private fun text(raw: String): ContextValue =
    ContextValue.TextValue((ContextText.create(raw) as ContextObservationResult.Success).value)

private fun confidence(value: Int): ContextConfidence =
    (ContextConfidence.create(value) as ContextObservationResult.Success).value

private fun retention(): MemoryRetention =
    (MemoryRetention.create(MemoryRetentionClass.UNTIL_CORRECTED, null)
        as MemoryDomainResult.Success).value

private fun <T> MemoryDomainResult<T>.success(): T = when (this) {
    is MemoryDomainResult.Success -> value
    is MemoryDomainResult.Failure -> error("expected success: $error")
}
