package io.redi.robot.memory.episodic

import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.hugo.identity.HugoIdentityId
import io.redi.robot.memory.domain.MemoryAllowedUse
import io.redi.robot.memory.domain.MemoryDisposition
import io.redi.robot.memory.domain.MemoryDomainResult
import io.redi.robot.memory.domain.MemoryEnvelope
import io.redi.robot.memory.domain.MemoryEnvelopeCodec
import io.redi.robot.memory.domain.MemoryCreator
import io.redi.robot.memory.working.WorkingMemoryPolicy
import io.redi.robot.memory.working.WorkingPolicyResult
import io.redi.robot.memory.working.WorkingPromotionResult
import io.redi.robot.memory.working.WorkingSessionMemory
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
import io.redi.robot.memory.storage.MemoryEnvelopeStore
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
import io.redi.robot.memory.storage.AUTHENTICATED_ENCRYPTION_ALGORITHM
import io.redi.robot.memory.storage.ProtectedMemoryRecordPayload
import io.redi.robot.context.api.ContextText
import io.redi.robot.context.api.ContextValue
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodicRecordTest {
    @Test
    fun `factory creates episodic event envelopes with deterministic expiry`() {
        val factory = factory()
        val created = factory.create(
            eventType = token("event"),
            subjects = listOf(subject("session-42")),
            content = textValue("user completed a bounded setup flow"),
            provenance = provenance(MemoryProvenanceOrigin.OBSERVED, modelGenerated = false, observedAt = 1_500L),
            confidence = confidence(650),
            validationState = MemoryValidationState.CANDIDATE,
            privacy = ContextPrivacyClassification.PRIVATE,
            allowedUse = MemoryAllowedUse.GATEWAY_ALLOWED,
            retention = retention(MemoryRetentionClass.FIXED_INTERVAL, 5_000L),
            lineage = null,
            createdAtEpochSecond = 1_000L,
        ) as EpisodicCreationResult.Created

        assertEquals(MemoryLayer.EPISODIC, created.envelope.layer)
        assertEquals(MemoryScope.GlobalPersonal, created.envelope.scope)
        assertEquals(MemoryDisposition.ACTIVE, created.envelope.disposition)
        assertEquals(5_000L, created.envelope.retention?.expiresAtEpochSecond)
    }

    @Test
    fun `expired retention windows and session retention are rejected`() {
        val factory = factory()
        val expired = factory.create(
            eventType = token("event"),
            subjects = listOf(subject("session-42")),
            content = textValue("x"),
            provenance = provenance(MemoryProvenanceOrigin.OBSERVED, modelGenerated = false, observedAt = null),
            confidence = confidence(500),
            validationState = MemoryValidationState.CANDIDATE,
            privacy = ContextPrivacyClassification.PRIVATE,
            allowedUse = MemoryAllowedUse.LOCAL_ONLY,
            retention = retention(MemoryRetentionClass.FIXED_INTERVAL, 999L),
            lineage = null,
            createdAtEpochSecond = 1_000L,
        )
        assertTrue(expired is EpisodicCreationResult.Failed)

        val session = factory.create(
            eventType = token("event"),
            subjects = listOf(subject("session-42")),
            content = textValue("x"),
            provenance = provenance(MemoryProvenanceOrigin.OBSERVED, modelGenerated = false, observedAt = null),
            confidence = confidence(500),
            validationState = MemoryValidationState.CANDIDATE,
            privacy = ContextPrivacyClassification.PRIVATE,
            allowedUse = MemoryAllowedUse.LOCAL_ONLY,
            retention = retention(MemoryRetentionClass.SESSION_SCOPED, null),
            lineage = null,
            createdAtEpochSecond = 1_000L,
        )
        assertEquals(
            EpisodicRecordError.SESSION_RETENTION_FORBIDDEN,
            (session as EpisodicCreationResult.Failed).error,
        )
    }

    @Test
    fun `derived events carry lineage and never erase source references`() {
        val factory = factory()
        val created = factory.create(
            eventType = token("event"),
            subjects = listOf(subject("session-42")),
            content = textValue("derived event"),
            provenance = provenance(MemoryProvenanceOrigin.CONVERSATION_SUMMARY, modelGenerated = true, observedAt = 1_200L),
            confidence = confidence(300),
            validationState = MemoryValidationState.CANDIDATE,
            privacy = ContextPrivacyClassification.PRIVATE,
            allowedUse = MemoryAllowedUse.LOCAL_ONLY,
            retention = retention(MemoryRetentionClass.POLICY_CONTROLLED, null),
            lineage = lineageOf(listOf(recordId(91), recordId(92))),
            createdAtEpochSecond = 1_000L,
        ) as EpisodicCreationResult.Created

        assertEquals(
            listOf(recordId(91), recordId(92)),
            created.envelope.lineage?.derivedFrom,
        )
        assertTrue(created.envelope.provenance.modelGenerated)
        assertEquals(MemoryValidationState.CANDIDATE, created.envelope.validationState)
    }

    @Test
    fun `repository records loads and lists episodic events through the encrypted store`() {
        val repository = repository()
        val envelope = episodicEnvelope(1, occurredAt = 1_500L, expiry = 9_000L)
        assertEquals(EpisodicPersistResult.Persisted, repository.record(envelope))

        when (val loaded = repository.event(envelope.recordId)) {
            is EpisodicLoadResult.Found ->
                assertEquals(
                    MemoryEnvelopeCodec.encode(envelope),
                    MemoryEnvelopeCodec.encode(loaded.envelope),
                )
            else -> error("expected found, got $loaded")
        }

        val events = (repository.events(10) as EpisodicListResult.Events).envelopes
        assertEquals(listOf(envelope.recordId), events.map { it.recordId })
    }

    @Test
    fun `non episodic envelopes are rejected before touching the store`() {
        val repository = repository()
        val working = workingEnvelope(2)
        assertEquals(
            EpisodicRecordError.WRONG_LAYER,
            (repository.record(working) as EpisodicPersistResult.Failed).error,
        )
        assertEquals(0, fakeSlots(repository).snapshot.size)
    }

    @Test
    fun `episodic events remain reachable past more than 256 non episodic records`() {
        val repository = repository()
        val store = checkNotNull(store)

        // 300 non-episodic records fill the first three H059 listing pages (256 + 44).
        (1..300).forEach { index ->
            assertEquals(MemoryPersistResult.Persisted, store.persist(workingEnvelope(index)))
        }
        // Episodic events follow, beyond the old single-page listing ceiling.
        val episodic = listOf(
            episodicEnvelope(301, occurredAt = 1_500L, expiry = null),
            episodicEnvelope(302, occurredAt = 1_600L, expiry = null),
        )
        episodic.forEach { envelope ->
            assertEquals(EpisodicPersistResult.Persisted, repository.record(envelope))
        }

        // Keyset continuation reaches past the non-episodic prefix: no empty result, no skips.
        val one = (repository.events(1) as EpisodicListResult.Events).envelopes
        assertEquals(listOf(episodic[0].recordId), one.map { it.recordId })

        val both = (repository.events(2) as EpisodicListResult.Events).envelopes
        assertEquals(episodic.map { it.recordId }, both.map { it.recordId })

        val events = (repository.events(10) as EpisodicListResult.Events).envelopes
        assertEquals(episodic.map { it.recordId }, events.map { it.recordId })
    }

    @Test
    fun `non episodic record before an episodic event never consumes the episodic result limit`() {
        val repository = repository()
        val store = checkNotNull(store)

        // A non-episodic record whose id sorts before the episodic event's id is persisted
        // directly through the H059 store, as another memory layer would.
        val foreign = workingEnvelope(1)
        assertEquals(MemoryPersistResult.Persisted, store.persist(foreign))

        val episodic = episodicEnvelope(2, occurredAt = 1_500L, expiry = null)
        assertEquals(EpisodicPersistResult.Persisted, repository.record(episodic))

        // The mixed store must still serve the requested episodic record: the non-episodic
        // predecessor may not consume the limit or empty the result.
        val events = (repository.events(1) as EpisodicListResult.Events).envelopes
        assertEquals(listOf(episodic.recordId), events.map { it.recordId })

        val two = (repository.events(2) as EpisodicListResult.Events).envelopes
        assertEquals(listOf(episodic.recordId), two.map { it.recordId })
    }

    @Test
    fun `missing ids and invalid limits are typed`() {
        val repository = repository()
        assertEquals(EpisodicLoadResult.Missing, repository.event(recordId(77)))
        assertEquals(
            EpisodicRecordError.INVALID_LIMIT,
            (repository.events(0) as EpisodicListResult.Failed).error,
        )
    }

    @Test
    fun `full durability path working promotion to persisted episodic record`() {
        val working = WorkingSessionMemory.open(
            (WorkingMemoryPolicy.create(4) as WorkingPolicyResult.Success).policy,
            identity(),
        )
        working.remember(workingEnvelope(6))

        val promoted = (working.promote(
            recordId(6),
            retention(MemoryRetentionClass.POLICY_CONTROLLED, null),
            updatedAtEpochSecond = 2_000L,
        ) as WorkingPromotionResult.Promoted).envelope

        val repository = repository()
        assertEquals(EpisodicPersistResult.Persisted, repository.record(promoted))
        val loaded = (repository.event(recordId(6)) as EpisodicLoadResult.Found).envelope

        // The persisted record is the canonical H058 value: provenance, candidate state, and
        // privacy preserved; the working session no longer holds it.
        assertEquals(MemoryEnvelopeCodec.encode(promoted), MemoryEnvelopeCodec.encode(loaded))
        assertEquals(MemoryValidationState.CANDIDATE, loaded.validationState)
        assertEquals(MemoryProvenanceOrigin.USER_EXPLICIT, loaded.provenance.origin)
        assertFalse(loaded.provenance.modelGenerated)
        assertEquals(MemoryLayer.EPISODIC, loaded.layer)
        assertEquals(0, working.size())
    }

    @Test
    fun `storage corruption is surfaced typed through the episodic facade`() {
        val repository = repository()
        val envelope = episodicEnvelope(1, occurredAt = 1_400L, expiry = null)
        repository.record(envelope)
        fakeSlots(repository).corrupt(envelope.recordId, "garbage".toByteArray())

        assertEquals(
            EpisodicRecordError.STORAGE_FAILED,
            (repository.event(envelope.recordId) as EpisodicLoadResult.Failed).error,
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun episodicEnvelope(index: Int, occurredAt: Long?, expiry: Long?): MemoryEnvelope =
        (factory().create(
            eventType = token("event"),
            subjects = listOf(subject("session")),
            content = textValue("episodic event $index"),
            provenance = provenance(MemoryProvenanceOrigin.USER_EXPLICIT, modelGenerated = false, observedAt = occurredAt),
            confidence = confidence(700),
            validationState = MemoryValidationState.CANDIDATE,
            privacy = ContextPrivacyClassification.PRIVATE,
            allowedUse = MemoryAllowedUse.GATEWAY_ALLOWED,
            retention = if (expiry == null) {
                retention(MemoryRetentionClass.UNTIL_CORRECTED, null)
            } else {
                retention(MemoryRetentionClass.FIXED_INTERVAL, expiry)
            },
            lineage = null,
            createdAtEpochSecond = 1_000L,
        ) as EpisodicCreationResult.Created).envelope

    private fun workingEnvelope(index: Int): MemoryEnvelope = MemoryEnvelope.create(
        recordId = recordId(index),
        ownerIdentity = identity(),
        layer = MemoryLayer.WORKING,
        type = token("scratch"),
        scope = MemoryScope.GlobalPersonal,
        subjects = listOf(subject("session")),
        content = textValue("working content $index"),
        provenance = provenance(MemoryProvenanceOrigin.USER_EXPLICIT, modelGenerated = false, observedAt = null),
        confidence = confidence(600),
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

    private fun factory(): EpisodicEventFactory = EpisodicEventFactory.open(
        identity(),
        recordIds = { recordId(nextId.getAndIncrement().toInt()) },
    )

    private val nextId = java.util.concurrent.atomic.AtomicLong(100)

    private var store: MemoryEnvelopeStore? = null
    private var slotsRef: FakeSlots? = null
    private var protectionRef: FakeProtection? = null

    private fun repository(): EpisodicRecordRepository {
        val slots = FakeSlots()
        val protection = FakeProtection()
        slotsRef = slots
        protectionRef = protection
        val keyVersion = (MemoryStoreKeyVersion.create(1) as MemoryProtectionResult.Success).value
        store = MemoryEnvelopeStore(slots, protection, keyVersion)
        return EpisodicRecordRepository(store!!)
    }

    private fun fakeSlots(repository: EpisodicRecordRepository): FakeSlots {
        checkNotNull(slotsRef) { "repository not initialised" }
        return slotsRef!!
    }

    private fun <T> MemoryDomainResult<T>.success(): T = when (this) {
        is MemoryDomainResult.Success -> value
        is MemoryDomainResult.Failure -> error("expected success, got $error")
    }

    private fun recordId(index: Int): MemoryRecordId = MemoryRecordId.fromUuid(
        UUID.fromString("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"),
    )

    private fun identity(): HugoIdentityId = HugoIdentityId.fromUuid(
        UUID.fromString("ffffffff-0000-4000-8000-000000000001"),
    )

    private fun token(raw: String): MemoryTypeToken =
        (MemoryTypeToken.parse(raw) as MemoryDomainResult.Success).value
    private fun subject(raw: String): MemorySubjectToken =
        (MemorySubjectToken.parse(raw) as MemoryDomainResult.Success).value
    private fun creator(raw: String): MemoryCreator =
        (MemoryCreator.parse(raw) as MemoryDomainResult.Success).value

    private fun provenance(origin: MemoryProvenanceOrigin, modelGenerated: Boolean, observedAt: Long?): MemoryProvenance =
        (MemoryProvenance.create(
            origin,
            modelGenerated,
            (io.redi.robot.context.api.ContextSourceType.parse("memory.test")
                as io.redi.robot.context.api.ContextObservationResult.Success).value,
            (io.redi.robot.context.api.ContextSourceReference.parse("memory_test")
                as io.redi.robot.context.api.ContextObservationResult.Success).value,
            creator("hugo_runtime"),
            observedAt,
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

    private class FakeSlots : MemoryRecordSlots {
        val snapshot = mutableMapOf<MemoryRecordId, ByteArray>()

        override fun read(recordId: MemoryRecordId): MemorySlotReadResult =
            snapshot[recordId]?.let { MemorySlotReadResult.Content(it) } ?: MemorySlotReadResult.Missing

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

        fun corrupt(recordId: MemoryRecordId, bytes: ByteArray) {
            snapshot[recordId] = bytes.copyOf()
        }
    }

    /** XOR "cipher" with a 2-byte integrity trailer so tampering authenticates as failed. */
    private class FakeProtection : MemoryRecordProtection {
        override fun encrypt(
            plaintext: ByteArray,
            associatedData: ByteArray,
        ): MemoryProtectionResult<ProtectedMemoryRecordPayload> {
            val nonce = ByteArray(12) { (it * 7 + 1).toByte() }
            val sealed = plaintext + macOf(plaintext)
            return MemoryProtectionResult.Success(
                ProtectedMemoryRecordPayload(
                    AUTHENTICATED_ENCRYPTION_ALGORITHM,
                    MemoryStoreKeyVersion.create(1).success(),
                    nonce,
                    xor(sealed),
                ),
            )
        }

        override fun decrypt(
            payload: ProtectedMemoryRecordPayload,
            associatedData: ByteArray,
        ): MemoryProtectionResult<ByteArray> {
            val unsealed = xor(payload.ciphertext)
            if (unsealed.size < 2) {
                return MemoryProtectionResult.Failure(io.redi.robot.memory.storage.MemoryProtectionError.DECRYPTION_FAILED)
            }
            val body = unsealed.copyOfRange(0, unsealed.size - 2)
            val mac = unsealed.copyOfRange(unsealed.size - 2, unsealed.size)
            if (!macOf(body).contentEquals(mac)) {
                return MemoryProtectionResult.Failure(
                    io.redi.robot.memory.storage.MemoryProtectionError.AUTHENTICATION_FAILED,
                )
            }
            return MemoryProtectionResult.Success(body)
        }

        private fun macOf(bytes: ByteArray): ByteArray {
            var value = 0x51ED
            bytes.forEach { byte -> value = (value * 17 + byte.toInt()) and 0xFFFF }
            return arrayOf((value shr 8).toByte(), value.toByte()).toByteArray()
        }

        private fun xor(bytes: ByteArray): ByteArray =
            bytes.mapIndexed { index, byte -> (byte.toInt() xor (0x33 + index % 5)).toByte() }.toByteArray()
    }

    private fun <T> MemoryProtectionResult<T>.success(): T = when (this) {
        is MemoryProtectionResult.Success -> value
        is MemoryProtectionResult.Failure -> error("expected success, got $error")
    }
}

private fun <T> io.redi.robot.memory.storage.MemoryProtectionResult<T>.success(): T = when (this) {
    is io.redi.robot.memory.storage.MemoryProtectionResult.Success -> value
    is io.redi.robot.memory.storage.MemoryProtectionResult.Failure -> error("expected success, got $error")
}
