package io.redi.robot.memory.semantic

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
import io.redi.robot.memory.storage.MemoryChangeSetError
import io.redi.robot.memory.storage.MemoryChangeSetJournal
import io.redi.robot.memory.storage.MemoryEnvelopeStore
import io.redi.robot.memory.storage.MemoryJournalClearResult
import io.redi.robot.memory.storage.MemoryJournalFailure
import io.redi.robot.memory.storage.MemoryJournalReadResult
import io.redi.robot.memory.storage.MemoryJournalWriteResult
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
import io.redi.robot.memory.storage.MemoryTransitionResult
import io.redi.robot.memory.storage.ProtectedMemoryRecordPayload
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SemanticMemoryTest {
    @Test
    fun `user explicit fact can be created validated`() {
        val envelope = createdValidated()
        assertEquals(MemoryValidationState.VALIDATED, envelope.validationState)
        assertEquals(MemoryLayer.SEMANTIC, envelope.layer)
        assertEquals(MemoryScope.GlobalPersonal, envelope.scope)
        assertFalse(envelope.grantsAuthority)
        assertFalse(envelope.authorizesAnyUse)
    }

    @Test
    fun `model inferred fact must start as candidate`() {
        val store = newStore()
        val candidate = semanticCandidate(store)
        assertEquals(MemoryValidationState.CANDIDATE, candidate.validationState)
        assertTrue(candidate.provenance.modelGenerated)
        assertEquals(MemoryProvenanceOrigin.MODEL_INFERRED, candidate.provenance.origin)
    }

    @Test
    fun `non user explicit provenance can never self validate at creation`() {
        listOf(
            MemoryProvenanceOrigin.MODEL_INFERRED to true,
            MemoryProvenanceOrigin.OBSERVED to false,
            MemoryProvenanceOrigin.TOOL_RESULT to false,
            MemoryProvenanceOrigin.CONVERSATION_SUMMARY to false,
        ).forEach { (origin, modelGenerated) ->
            val result = semanticFactory().create(
                token("fact"), listOf(subject("language")), textValue("x"),
                provenance(origin, modelGenerated = modelGenerated),
                confidence(500), MemoryValidationState.VALIDATED,
                ContextPrivacyClassification.PRIVATE, MemoryAllowedUse.GATEWAY_ALLOWED,
                retention(MemoryRetentionClass.UNTIL_CORRECTED, null), null, emptyList(), 1_000L,
            )
            assertEquals(
                SemanticMemoryError.SELF_VALIDATION_FORBIDDEN,
                (result as SemanticCreationResult.Failed).error,
            )
        }
    }

    @Test
    fun `disputed state and session retention are rejected at creation`() {
        val disputed = semanticFactory().create(
            token("fact"), listOf(subject("language")), textValue("x"),
            provenance(MemoryProvenanceOrigin.USER_EXPLICIT, modelGenerated = false),
            confidence(500), MemoryValidationState.DISPUTED,
            ContextPrivacyClassification.PRIVATE, MemoryAllowedUse.LOCAL_ONLY,
            retention(MemoryRetentionClass.UNTIL_CORRECTED, null), null, emptyList(), 1_000L,
        )
        assertEquals(
            SemanticMemoryError.DISPUTED_NOT_CREATABLE,
            (disputed as SemanticCreationResult.Failed).error,
        )

        val session = semanticFactory().create(
            token("fact"), listOf(subject("language")), textValue("x"),
            provenance(MemoryProvenanceOrigin.USER_EXPLICIT, modelGenerated = false),
            confidence(500), MemoryValidationState.CANDIDATE,
            ContextPrivacyClassification.PRIVATE, MemoryAllowedUse.LOCAL_ONLY,
            retention(MemoryRetentionClass.SESSION_SCOPED, null), null, emptyList(), 1_000L,
        )
        assertEquals(
            SemanticMemoryError.SESSION_RETENTION_FORBIDDEN,
            (session as SemanticCreationResult.Failed).error,
        )
    }

    @Test
    fun `explicit user confirmation transitions candidate to validated preserving provenance`() {
        val store = newStore()
        val candidate = semanticCandidate(store)

        val confirmation = SemanticConfirmationService(store)
        val confirmResult = confirmation.confirm(
            SemanticConfirmationRequest(
                candidate.recordId, userConfirmed = true,
                creator("user_control"), atEpochSecond = 5_000L,
            ),
        )
        val confirmed = when (val result = confirmResult) {
            is SemanticConfirmationResult.Confirmed -> result.envelope
            is SemanticConfirmationResult.Failed -> error("confirm failed: " + result.error)
        }

        assertEquals(MemoryValidationState.VALIDATED, confirmed.validationState)
        assertEquals(MemoryProvenanceOrigin.MODEL_INFERRED, confirmed.provenance.origin)
        assertTrue(confirmed.provenance.modelGenerated)
        assertEquals(candidate.content?.canonicalValue, confirmed.content?.canonicalValue)
        assertEquals(candidate.createdAtEpochSecond, confirmed.createdAtEpochSecond)
        assertEquals(5_000L, confirmed.updatedAtEpochSecond)
    }

    @Test
    fun `confirmation requires the explicit user confirmed flag`() {
        val store = newStore()
        val candidate = semanticCandidate(store)
        val confirmation = SemanticConfirmationService(store)

        val result = confirmation.confirm(
            SemanticConfirmationRequest(
                candidate.recordId, userConfirmed = false,
                creator("user_control"), atEpochSecond = 5_000L,
            ),
        )
        assertEquals(
            SemanticMemoryError.USER_CONFIRMATION_REQUIRED,
            (result as SemanticConfirmationResult.Failed).error,
        )
    }

    @Test
    fun `confirmation rejections are typed for wrong layer state and missing records`() {
        val store = newStore()
        val confirmation = SemanticConfirmationService(store)
        val foreignLayer = workingLayerCandidate()
        store.persist(foreignLayer)

        val wrongLayer = confirmation.confirm(
            SemanticConfirmationRequest(foreignLayer.recordId, true, creator("user_control"), 5_000L),
        )
        assertEquals(SemanticMemoryError.WRONG_LAYER, (wrongLayer as SemanticConfirmationResult.Failed).error)

        val missing = confirmation.confirm(
            SemanticConfirmationRequest(recordId(99), true, creator("user_control"), 5_000L),
        )
        assertEquals(SemanticMemoryError.RECORD_NOT_FOUND, (missing as SemanticConfirmationResult.Failed).error)

        val validated = createdValidated()
        store.persist(validated)
        val notCandidate = confirmation.confirm(
            SemanticConfirmationRequest(validated.recordId, true, creator("user_control"), 5_000L),
        )
        assertEquals(SemanticMemoryError.NOT_CANDIDATE, (notCandidate as SemanticConfirmationResult.Failed).error)
    }

    @Test
    fun `confirmed fact round trips through the encrypted store`() {
        val store = newStore()
        val confirmation = SemanticConfirmationService(store)
        val candidate = semanticCandidate(store)
        store.persist(candidate)

        val confirmed = (
            confirmation.confirm(
                SemanticConfirmationRequest(
                    candidate.recordId, userConfirmed = true, creator("user_control"), 5_000L,
                ),
            ) as SemanticConfirmationResult.Confirmed
        ).envelope

        val reloaded = (store.load(candidate.recordId) as MemoryLoadResult.Found).envelope
        assertEquals(
            MemoryEnvelopeCodec.encode(confirmed),
            MemoryEnvelopeCodec.encode(reloaded),
        )
    }

    @Test
    fun `explicit user correction supersedes an inferred semantic fact`() {
        val store = newStoreWithJournal()
        val inferred = semanticCandidate(store)

        val correction = (
            semanticFactory().createCorrection(
                inferred, textValue("actually the opposite"), confidence(1000), 3_000L,
            ) as SemanticCreationResult.Created
        ).envelope
        assertTrue(
            SemanticCorrectionService(store).correct(inferred, correction, 3_000L)
                is SemanticCorrectionResult.Applied,
        )

        val superseded = (store.load(inferred.recordId) as MemoryLoadResult.Found).envelope
        assertEquals(MemoryDisposition.SUPERSEDED, superseded.disposition)
        assertTrue(superseded.provenance.modelGenerated)
        val successor = (store.load(correction.recordId) as MemoryLoadResult.Found).envelope
        assertEquals(MemoryValidationState.VALIDATED, successor.validationState)
        assertEquals(MemoryProvenanceOrigin.USER_EXPLICIT, successor.provenance.origin)
        assertFalse(successor.provenance.modelGenerated)
        // The successor stays bound to the target's governance fields.
        assertEquals(inferred.layer, successor.layer)
        assertEquals(inferred.type, successor.type)
        assertEquals(inferred.scope, successor.scope)
        assertEquals(inferred.subjects, successor.subjects)
        assertEquals(inferred.privacy, successor.privacy)
        assertEquals(inferred.allowedUse, successor.allowedUse)
    }

    @Test
    fun `model inferred correction can never supersede a semantic fact`() {
        val store = newStoreWithJournal()
        val target = createdValidated()
        store.persist(target)

        val forged = MemoryEnvelopeCodec.decode(
            MemoryEnvelopeCodec.encode(
                (semanticFactory().createCorrection(
                    target, textValue("model says"), confidence(900), 3_000L,
                ) as SemanticCreationResult.Created).envelope,
            ).replace("\"origin\":\"user_explicit\"", "\"origin\":\"model_inferred\"")
                .replace("\"modelGenerated\":false", "\"modelGenerated\":true"),
        ).success()
        assertEquals(
            SemanticMemoryError.CORRECTION_PROVENANCE_FORBIDDEN,
            (SemanticCorrectionService(store).correct(target, forged, 3_000L)
                as SemanticCorrectionResult.Failed).error,
        )
        val unchanged = (store.load(target.recordId) as MemoryLoadResult.Found).envelope
        assertEquals(MemoryDisposition.ACTIVE, unchanged.disposition)
    }

    @Test
    fun `semantic correction of a foreign layer target is rejected`() {
        val foreignTarget = MemoryEnvelopeCodec.decode(
            MemoryEnvelopeCodec.encode(createdValidated())
                .replace("\"layer\":\"SEMANTIC\"", "\"layer\":\"PERSONAL\""),
        ).success()
        assertEquals(
            SemanticMemoryError.CORRECTION_TARGET_MISMATCH,
            (semanticFactory().createCorrection(
                foreignTarget, textValue("x"), confidence(900), 3_000L,
            ) as SemanticCreationResult.Failed).error,
        )
    }

    @Test
    fun `confirmation of an expired record fails closed`() {
        val store = newStore()
        val candidate = (semanticFactory().create(
            token("fact"), listOf(subject("language")), textValue("expiring fact"),
            provenance(MemoryProvenanceOrigin.MODEL_INFERRED, modelGenerated = true),
            confidence(300), MemoryValidationState.CANDIDATE,
            ContextPrivacyClassification.PRIVATE, MemoryAllowedUse.LOCAL_ONLY,
            retention(MemoryRetentionClass.FIXED_INTERVAL, 2_000L), null, emptyList(), 1_000L,
        ) as SemanticCreationResult.Created).envelope
        store.persist(candidate)

        val result = SemanticConfirmationService(store).confirm(
            SemanticConfirmationRequest(candidate.recordId, true, creator("user_control"), 5_000L),
        )
        assertEquals(SemanticMemoryError.RETENTION_EXPIRED, (result as SemanticConfirmationResult.Failed).error)
    }

    @Test
    fun `confirmation with unresolved conflicts fails closed`() {
        val store = newStore()
        val candidate = (semanticFactory().create(
            token("fact"), listOf(subject("language")), textValue("conflicting fact"),
            provenance(MemoryProvenanceOrigin.MODEL_INFERRED, modelGenerated = true),
            confidence(300), MemoryValidationState.CANDIDATE,
            ContextPrivacyClassification.PRIVATE, MemoryAllowedUse.LOCAL_ONLY,
            retention(MemoryRetentionClass.POLICY_CONTROLLED, null), null, listOf(recordId(7)), 1_000L,
        ) as SemanticCreationResult.Created).envelope
        store.persist(candidate)

        val result = SemanticConfirmationService(store).confirm(
            SemanticConfirmationRequest(candidate.recordId, true, creator("user_control"), 5_000L),
        )
        assertEquals(SemanticMemoryError.UNRESOLVED_CONFLICTS, (result as SemanticConfirmationResult.Failed).error)
    }

    @Test
    fun `stale lifecycle transition is rejected with nothing written`() {
        val store = newStore()
        val candidate = semanticCandidate(store)
        // The store moved on after the caller read the candidate.
        val movedOn = MemoryEnvelopeCodec.decode(
            MemoryEnvelopeCodec.encode(candidate)
                .replace("\"validationState\":\"CANDIDATE\"", "\"validationState\":\"VALIDATED\"")
                .replace("\"confidence\":300", "\"confidence\":301"),
        ).success()
        store.persist(movedOn)

        // A stale transition built from the pre-change candidate must be rejected and must not
        // overwrite the concurrently committed state.
        val rebuilt = MemoryEnvelopeCodec.decode(
            MemoryEnvelopeCodec.encode(candidate)
                .replace("\"validationState\":\"CANDIDATE\"", "\"validationState\":\"VALIDATED\""),
        ).success()
        val result = store.persistTransition(rebuilt, candidate)
        assertEquals(MemoryChangeSetError.STALE_WRITE, (result as MemoryTransitionResult.Failed).error)

        val stored = (store.load(candidate.recordId) as MemoryLoadResult.Found).envelope
        assertEquals(MemoryEnvelopeCodec.encode(movedOn), MemoryEnvelopeCodec.encode(stored))
    }
}

private fun newStore(): MemoryEnvelopeStore =
    MemoryEnvelopeStore(FakeSlots(), FakeProtection(), MemoryStoreKeyVersion.create(1).success())

private fun newStoreWithJournal(): MemoryEnvelopeStore =
    MemoryEnvelopeStore(FakeSlots(), FakeProtection(), MemoryStoreKeyVersion.create(1).success(), FakeJournal())

private fun semanticFactory(): SemanticFactFactory =
    SemanticFactFactory.open(owner(), recordIds = { recordId(nextId.getAndIncrement()) })

private val nextId = java.util.concurrent.atomic.AtomicLong(20L)

private fun semanticCandidate(store: MemoryEnvelopeStore): MemoryEnvelope =
    (semanticFactory().create(
        token("fact"), listOf(subject("language")), textValue("inferred fact"),
        provenance(MemoryProvenanceOrigin.MODEL_INFERRED, modelGenerated = true),
        confidence(300), MemoryValidationState.CANDIDATE,
        ContextPrivacyClassification.PRIVATE, MemoryAllowedUse.LOCAL_ONLY,
        retention(MemoryRetentionClass.POLICY_CONTROLLED, null), null, emptyList(), 1_000L,
    ) as SemanticCreationResult.Created).envelope.also { store.persist(it) }

private fun createdValidated(): MemoryEnvelope =
    (semanticFactory().create(
        token("fact"), listOf(subject("language")), textValue("user stated a fact"),
        provenance(MemoryProvenanceOrigin.USER_EXPLICIT, modelGenerated = false),
        confidence(900), MemoryValidationState.VALIDATED,
        ContextPrivacyClassification.PRIVATE, MemoryAllowedUse.GATEWAY_ALLOWED,
        retention(MemoryRetentionClass.UNTIL_CORRECTED, null), null, emptyList(), 1_000L,
    ) as SemanticCreationResult.Created).envelope

private fun workingLayerCandidate(): MemoryEnvelope = MemoryEnvelope.create(
    recordId = recordId(80),
    ownerIdentity = owner(),
    layer = MemoryLayer.WORKING,
    type = token("scratch"),
    scope = MemoryScope.GlobalPersonal,
    subjects = listOf(subject("language")),
    content = textValue("working scratch"),
    provenance = provenance(MemoryProvenanceOrigin.USER_EXPLICIT, modelGenerated = false),
    confidence = confidence(500),
    validationState = MemoryValidationState.CANDIDATE,
    disposition = MemoryDisposition.ACTIVE,
    privacy = ContextPrivacyClassification.PRIVATE,
    allowedUse = MemoryAllowedUse.LOCAL_ONLY,
    retention = retention(MemoryRetentionClass.SESSION_SCOPED, null),
    supersedes = null,
    conflictsWith = emptyList(),
    lineage = null,
    createdAtEpochSecond = 1_000L,
    updatedAtEpochSecond = null,
).success()

private fun <T> MemoryDomainResult<T>.success(): T = when (this) {
    is MemoryDomainResult.Success -> value
    is MemoryDomainResult.Failure -> error("expected success, got $error")
}

private fun recordId(index: Int): MemoryRecordId = recordId(index.toLong())

private fun recordId(index: Long): MemoryRecordId = MemoryRecordId.fromUuid(
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

private fun provenance(origin: MemoryProvenanceOrigin, modelGenerated: Boolean): MemoryProvenance =
    (MemoryProvenance.create(
        origin,
        modelGenerated,
        (io.redi.robot.context.api.ContextSourceType.parse("memory.test")
            as io.redi.robot.context.api.ContextObservationResult.Success).value,
        (io.redi.robot.context.api.ContextSourceReference.parse("memory_test")
            as io.redi.robot.context.api.ContextObservationResult.Success).value,
        creator("hugo_runtime"),
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

private class FakeSlots : MemoryRecordSlots {
    val snapshot = mutableMapOf<MemoryRecordId, ByteArray>()

    override fun read(recordId: MemoryRecordId): MemorySlotReadResult =
        snapshot[recordId]?.let { MemorySlotReadResult.Content(it.copyOf()) } ?: MemorySlotReadResult.Missing

    override fun writeAtomically(recordId: MemoryRecordId, bytes: ByteArray): MemorySlotWriteResult {
        snapshot[recordId] = bytes.copyOf()
        return MemorySlotWriteResult.Written
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
    override fun writeIntent(bytes: ByteArray): MemoryJournalWriteResult = MemoryJournalWriteResult.Written

    override fun readIntent(): MemoryJournalReadResult = MemoryJournalReadResult.Empty

    override fun clearIntent(): MemoryJournalClearResult = MemoryJournalClearResult.Cleared
}

private class FakeProtection : MemoryRecordProtection {
    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): MemoryProtectionResult<ProtectedMemoryRecordPayload> {
        val nonce = ByteArray(12) { (it * 11 + 3).toByte() }
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
        bytes.mapIndexed { index, byte -> (byte.toInt() xor (0x41 + index % 7)).toByte() }.toByteArray()
}

private fun <T> MemoryProtectionResult<T>.success(): T = when (this) {
    is MemoryProtectionResult.Success -> value
    is MemoryProtectionResult.Failure -> error("expected success, got $error")
}
