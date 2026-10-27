package io.redi.robot.memory.storage

import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextValue
import io.redi.robot.memory.domain.MemoryAllowedUse
import io.redi.robot.memory.domain.MemoryCreator
import io.redi.robot.memory.domain.MemoryInstanceVersionToken
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
import io.redi.robot.hugo.identity.HugoIdentityId
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEnvelopeStoreTest {
    @Test
    fun `persist and load round trips every governed scope and state faithfully`() {
        val store = newStore()
        listOf(
            MemoryScopeCase(MemoryLayer.PERSONAL, MemoryScope.GlobalPersonal),
            MemoryScopeCase(MemoryLayer.BODY, MemoryScope.BodyInstance(instance(), versionToken("profile_a"))),
            MemoryScopeCase(MemoryLayer.DEVICE, MemoryScope.DeviceInstance(instance(), null)),
            MemoryScopeCase(MemoryLayer.STRATEGY, MemoryScope.ProviderInstance(instance(), versionToken("v2"))),
        ).forEach { scopeCase ->
            val envelope = validEnvelope(indexFor(scopeCase), layer = scopeCase.layer, scope = scopeCase.scope)
            assertEquals(MemoryPersistResult.Persisted, store.persist(envelope))
            when (val loaded = store.load(envelope.recordId)) {
                is MemoryLoadResult.Found -> assertEquals(
                    MemoryEnvelopeCodec.encode(envelope),
                    MemoryEnvelopeCodec.encode(loaded.envelope),
                )
                else -> error("expected found, got $loaded")
            }
        }
    }

    @Test
    fun `provenance lineage privacy and validation state survive persistence unchanged`() {
        val store = newStore()
        val envelope = MemoryEnvelope.create(
            recordId = recordId(2),
            ownerIdentity = identity(),
            layer = MemoryLayer.SEMANTIC,
            type = token("fact"),
            scope = MemoryScope.GlobalPersonal,
            subjects = listOf(subject("language"), subject("answers")),
            content = textValue("maybe a fact"),
            provenance = provenance(MemoryProvenanceOrigin.MODEL_INFERRED, modelGenerated = true),
            confidence = confidence(400),
            validationState = MemoryValidationState.CANDIDATE,
            disposition = MemoryDisposition.ACTIVE,
            privacy = ContextPrivacyClassification.PRIVATE,
            allowedUse = MemoryAllowedUse.GATEWAY_ALLOWED,
            retention = retention(MemoryRetentionClass.POLICY_CONTROLLED, null),
            supersedes = null,
            conflictsWith = listOf(recordId(7)),
            lineage = (MemoryLineage.create(listOf(recordId(9))) as MemoryDomainResult.Success).value,
            createdAtEpochSecond = 2_000L,
            updatedAtEpochSecond = null,
        ).success()
        assertEquals(MemoryPersistResult.Persisted, store.persist(envelope))
        val loaded = (store.load(envelope.recordId) as MemoryLoadResult.Found).envelope
        assertEquals(MemoryEnvelopeCodec.encode(envelope), MemoryEnvelopeCodec.encode(loaded))
    }

    @Test
    fun `tombstoned envelope atomically replaces the active record which cannot reappear`() {
        val store = newStore()
        val active = validEnvelope(1)
        assertEquals(MemoryPersistResult.Persisted, store.persist(active))
        val tombstoned = MemoryEnvelope.tombstoneOf(active, creator("user_control"), 8_888L).success()
        assertEquals(MemoryPersistResult.Persisted, store.persist(tombstoned))

        val loaded = store.load(active.recordId) as MemoryLoadResult.Found
        assertEquals(MemoryDisposition.TOMBSTONED, loaded.envelope.disposition)
        assertEquals(null, loaded.envelope.content)
        assertEquals(1, fakeSlots(store).snapshot.size)
        assertFalse(fakeSlots(store).snapshot.values.any { String(it, Charsets.ISO_8859_1).contains("prefers") })
    }

    @Test
    fun `missing record loads as missing rather than failing`() {
        val store = newStore()
        assertEquals(MemoryLoadResult.Missing, store.load(recordId(42)))
    }

    @Test
    fun `corrupt persisted record is rejected typed without plaintext exposure`() {
        val store = newStore()
        val envelope = validEnvelope(1)
        store.persist(envelope)
        fakeSlots(store).corrupt(envelope.recordId, "garbage bytes".toByteArray())

        val result = store.load(envelope.recordId)
        assertEquals(MemoryPersistenceError.CORRUPT_RECORD, (result as MemoryLoadResult.Failed).error)
    }

    @Test
    fun `tampered ciphertext fails authentication closed`() {
        val store = newStore()
        val envelope = validEnvelope(1)
        store.persist(envelope)
        fakeSlots(store).tamperLastCiphertextByte(envelope.recordId)

        val result = store.load(envelope.recordId)
        assertEquals(
            MemoryPersistenceError.AUTHENTICATION_FAILED,
            (result as MemoryLoadResult.Failed).error,
        )
    }

    @Test
    fun `unsupported persisted schema version is an explicit typed failure`() {
        val store = newStore()
        val envelope = validEnvelope(1)
        store.persist(envelope)
        val slots = fakeSlots(store)
        val protection = fakeProtection(store)
        val document = String(
            protection.decrypt(
                slots.decodePayload(String(slots.snapshot.getValue(envelope.recordId), Charsets.UTF_8)),
                MemoryEnvelopeStore.ASSOCIATED_DATA,
            ).let { result -> (result as MemoryProtectionResult.Success).value },
            Charsets.UTF_8,
        )
        val mutated = document.replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"9.9\"")
            .toByteArray(Charsets.UTF_8)
        val protected = (protection.encrypt(mutated, MemoryEnvelopeStore.ASSOCIATED_DATA)
            as MemoryProtectionResult.Success).value
        slots.snapshot[envelope.recordId] = slots.encodeSlotDocumentForTest(protected)

        val result = store.load(envelope.recordId)
        assertEquals(
            MemoryPersistenceError.UNSUPPORTED_SCHEMA_VERSION,
            (result as MemoryLoadResult.Failed).error,
        )
    }

    @Test
    fun `wrong key version cannot decrypt and never exposes plaintext`() {
        val slots = FakeSlots()
        val store = MemoryEnvelopeStore(
            slots,
            FakeProtection(1),
            MemoryStoreKeyVersion.create(1).success(),
        )
        val envelope = validEnvelope(1)
        store.persist(envelope)
        val stored = slots.snapshot[envelope.recordId]!!
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("prefers"))

        val otherStore = MemoryEnvelopeStore(
            slots,
            FakeProtection(2),
            MemoryStoreKeyVersion.create(2).success(),
        )
        val result = otherStore.load(envelope.recordId)
        assertEquals(MemoryPersistenceError.KEY_UNAVAILABLE, (result as MemoryLoadResult.Failed).error)
        val rendered = String(slots.snapshot.getValue(envelope.recordId), Charsets.ISO_8859_1)
        assertFalse(rendered.contains("prefers"))
    }

    @Test
    fun `key loss is an explicit typed failure and recovery is a separate explicit purge`() {
        val store = newStore()
        val envelope = validEnvelope(1)
        store.persist(envelope)
        fakeProtection(store).keyLost = true
        assertEquals(
            MemoryPersistenceError.KEY_LOST,
            (store.load(envelope.recordId) as MemoryLoadResult.Failed).error,
        )
        assertEquals(
            MemoryPersistenceError.KEY_LOST,
            (store.persist(envelope) as MemoryPersistResult.Failed).error,
        )

        val reset = store.resetForLostKey() as MemoryResetResult.Reset
        assertEquals(1, reset.purgedSlots)
        assertEquals(MemoryLoadResult.Missing, store.load(envelope.recordId))
    }

    @Test
    fun `failed encryption leaves the previous persisted state intact with no partial write`() {
        val store = newStore()
        val first = validEnvelope(1)
        store.persist(first)
        val second = validEnvelope(1, content = textValue("updated content"))
        fakeProtection(store).failEncryption = true

        assertEquals(
            MemoryPersistenceError.ENCRYPTION_FAILED,
            (store.persist(second) as MemoryPersistResult.Failed).error,
        )
        val loaded = store.load(first.recordId) as MemoryLoadResult.Found
        assertEquals(MemoryEnvelopeCodec.encode(first), MemoryEnvelopeCodec.encode(loaded.envelope))
    }

    @Test
    fun `oversized records and invalid list limits are bounded failures`() {
        val store = newStore()
        fakeSlots(store).maximumSlotBytes = 16
        assertEquals(
            MemoryPersistenceError.OVERSIZED_RECORD,
            (store.persist(validEnvelope(1)) as MemoryPersistResult.Failed).error,
        )
        fakeSlots(store).maximumSlotBytes = 64 * 1024
        assertEquals(
            MemoryPersistenceError.INVALID_LIMIT,
            (store.storedRecordIds(0) as MemoryListResult.Failed).error,
        )
        assertEquals(
            MemoryPersistenceError.INVALID_LIMIT,
            (store.storedRecordIds(MemoryEnvelopeStore.MAXIMUM_LIST_LIMIT + 1) as MemoryListResult.Failed).error,
        )
    }

    @Test
    fun `bounded listing is stable ordered and excludes nothing persisted`() {
        val store = newStore()
        (1..5).forEach { index -> store.persist(validEnvelope(index)) }
        val ids = (store.storedRecordIds(3) as MemoryListResult.Ids).recordIds
        assertEquals(3, ids.size)
        assertEquals(ids.sortedBy { it.value }, ids)
        val all = (store.storedRecordIds(MemoryEnvelopeStore.MAXIMUM_LIST_LIMIT) as MemoryListResult.Ids).recordIds
        assertEquals(5, all.size)
    }

    @Test
    fun `concurrent persistence is deterministic and never reports partial success`() {
        val store = newStore()
        val workers = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        (1..24).forEach { index ->
            workers.submit {
                start.await(5, TimeUnit.SECONDS)
                try {
                    repeat(4) { attempt ->
                        val envelope = validEnvelope(index, content = textValue("attempt $attempt"))
                        val persisted = store.persist(envelope)
                        if (persisted != MemoryPersistResult.Persisted) {
                            failures.add(IllegalStateException("unexpected $persisted"))
                        }
                        when (val loaded = store.load(envelope.recordId)) {
                            is MemoryLoadResult.Found ->
                                assertEquals(recordId(index), loaded.envelope.recordId)
                            else -> failures.add(IllegalStateException("missing after persist"))
                        }
                    }
                } catch (throwable: Throwable) {
                    failures.add(throwable)
                }
            }
        }
        start.countDown()
        workers.shutdown()
        assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS))
        assertTrue("concurrent failures: $failures", failures.isEmpty())
        val all = (store.storedRecordIds(MemoryEnvelopeStore.MAXIMUM_LIST_LIMIT) as MemoryListResult.Ids).recordIds
        assertEquals(24, all.size)
    }

    @Test
    fun `keyset pagination pages every record without duplicates or skips`() {
        val store = newStore()
        (1..10).forEach { index -> store.persist(validEnvelope(index)) }

        var afterId: MemoryRecordId? = null
        val seen = mutableListOf<MemoryRecordId>()
        while (true) {
            val page = (store.storedRecordIds(4, afterId) as MemoryListResult.Ids).recordIds
            assertTrue(page.size <= 4)
            if (page.isEmpty()) break
            assertTrue("page must strictly advance", afterId == null || page.first().value > afterId.value)
            seen.addAll(page)
            afterId = page.last()
        }

        assertEquals((1..10).map { recordId(it) }, seen)
        assertEquals(seen.distinct().size, seen.size)
    }

    @Test
    fun `keyset continuation is strict and does not repeat the cursor`() {
        val store = newStore()
        // Record 4 is absent: the store has an id gap.
        listOf(1, 2, 3, 5, 6).forEach { index -> store.persist(validEnvelope(index)) }

        val page = (store.storedRecordIds(2, recordId(3)) as MemoryListResult.Ids).recordIds
        assertEquals(listOf(recordId(5), recordId(6)), page)

        val pastEnd = (store.storedRecordIds(4, recordId(6)) as MemoryListResult.Ids).recordIds
        assertTrue(pastEnd.isEmpty())

        // A non-stored cursor id inside a gap is legal: strictly-greater semantics hold.
        val acrossGap = (store.storedRecordIds(2, recordId(4)) as MemoryListResult.Ids).recordIds
        assertEquals(listOf(recordId(5), recordId(6)), acrossGap)
    }

    @Test
    fun `per page bound applies with continuation`() {
        val store = newStore()
        (1..10).forEach { index -> store.persist(validEnvelope(index)) }
        assertEquals(
            MemoryPersistenceError.INVALID_LIMIT,
            (store.storedRecordIds(257, recordId(1)) as MemoryListResult.Failed).error,
        )
    }

    @Test
    fun `ciphertext at rest never contains plaintext content or provenance tokens`() {
        val store = newStore()
        val envelope = validEnvelope(1)
        store.persist(envelope)
        val stored = fakeSlots(store).snapshot[envelope.recordId]!!
        val rendered = String(stored, Charsets.ISO_8859_1)
        assertFalse(rendered.contains("prefers"))
        assertFalse(rendered.contains("hugo_runtime"))
        assertFalse(rendered.contains("language"))
        assertTrue(stored.size > 0)
    }

    @Test
    fun `associated data binds records to the memory store schema`() {
        val store = newStore()
        val envelope = validEnvelope(1)
        store.persist(envelope)
        assertEquals("hugo.memory-record-store/envelope@1", fakeProtection(store).observedAssociatedData!!)
    }

    @Test
    fun `record id mismatch between slot and document is a typed failure`() {
        val store = newStore()
        val envelope = validEnvelope(1)
        store.persist(envelope)
        val movedDocument = fakeSlots(store).snapshot[envelope.recordId]!!
        fakeSlots(store).snapshot.clear()
        fakeSlots(store).snapshot[recordId(99)] = movedDocument

        val result = store.load(recordId(99))
        assertEquals(MemoryPersistenceError.RECORD_ID_MISMATCH, (result as MemoryLoadResult.Failed).error)
    }

    // ------------------------------------------------------------------ fakes/helpers

    private fun newStore(keyVersion: Int = 1): MemoryEnvelopeStore {
        val slots = FakeSlots()
        val protection = FakeProtection(keyVersion)
        val store = MemoryEnvelopeStore(slots, protection, MemoryStoreKeyVersion.create(keyVersion).success())
        stores[store] = Pair(slots, protection)
        return store
    }

    private fun fakeSlots(store: MemoryEnvelopeStore): FakeSlots = stores.getValue(store).first
    private fun fakeProtection(store: MemoryEnvelopeStore): FakeProtection = stores.getValue(store).second

    private val stores = ConcurrentHashMap<MemoryEnvelopeStore, Pair<FakeSlots, FakeProtection>>()

    private class FakeSlots : MemoryRecordSlots {
        val snapshot = ConcurrentHashMap<MemoryRecordId, ByteArray>()
        var maximumSlotBytes: Int = 64 * 1024

        override fun read(recordId: MemoryRecordId): MemorySlotReadResult =
            snapshot[recordId]?.let { MemorySlotReadResult.Content(it) } ?: MemorySlotReadResult.Missing

        override fun writeAtomically(recordId: MemoryRecordId, bytes: ByteArray): MemorySlotWriteResult = when {
            bytes.size > maximumSlotBytes -> MemorySlotWriteResult.Failed(MemorySlotFailure.OVERSIZED_SLOT)
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

        fun corrupt(recordId: MemoryRecordId, bytes: ByteArray) {
            snapshot[recordId] = bytes.copyOf()
        }

        fun tamperLastCiphertextByte(recordId: MemoryRecordId) {
            val document = String(snapshot.getValue(recordId), Charsets.UTF_8)
            val marker = "\"ciphertext\":\""
            val start = document.indexOf(marker) + marker.length
            val end = document.indexOf('"', start)
            val cipherText = document.substring(start, end)
            val bytes = Base64.getDecoder().decode(cipherText)
            bytes[bytes.size - 1] = ((bytes[bytes.size - 1].toInt() + 1) % 256).toByte()
            snapshot[recordId] = document.replaceRange(
                start,
                end,
                Base64.getEncoder().encodeToString(bytes),
            ).toByteArray()
        }

        fun encodeSlotDocumentForTest(payload: ProtectedMemoryRecordPayload): ByteArray {
            val nonce = Base64.getEncoder().encodeToString(payload.nonce)
            val ciphertext = Base64.getEncoder().encodeToString(payload.ciphertext)
            return ("{\"schemaVersion\":\"1.0\",\"algorithm\":\"" + payload.algorithm +
                "\",\"keyVersion\":" + payload.keyVersion.value +
                ",\"nonce\":\"" + nonce + "\",\"ciphertext\":\"" + ciphertext + "\"}")
                .toByteArray(Charsets.UTF_8)
        }

        fun decodePayload(document: String): ProtectedMemoryRecordPayload {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(document)
                .let { it as kotlinx.serialization.json.JsonObject }
            val algorithm = (obj["algorithm"] as kotlinx.serialization.json.JsonPrimitive).content
            val keyVersion = (obj["keyVersion"] as kotlinx.serialization.json.JsonPrimitive)
                .content.toInt()
            val nonce = Base64.getDecoder()
                .decode((obj["nonce"] as kotlinx.serialization.json.JsonPrimitive).content)
            val ciphertext = Base64.getDecoder()
                .decode((obj["ciphertext"] as kotlinx.serialization.json.JsonPrimitive).content)
            return ProtectedMemoryRecordPayload(
                algorithm,
                MemoryStoreKeyVersion.create(keyVersion).success(),
                nonce,
                ciphertext,
            )
        }
    }

    private class FakeProtection(private val keyVersion: Int) : MemoryRecordProtection {
        var failEncryption = false
        var keyLost = false
        var observedAssociatedData: String? = null

        override fun encrypt(
            plaintext: ByteArray,
            associatedData: ByteArray,
        ): MemoryProtectionResult<ProtectedMemoryRecordPayload> {
            observedAssociatedData = String(associatedData, Charsets.UTF_8)
            if (failEncryption) {
                return MemoryProtectionResult.Failure(MemoryProtectionError.ENCRYPTION_FAILED)
            }
            if (keyLost) {
                return MemoryProtectionResult.Failure(MemoryProtectionError.KEY_LOST)
            }
            val nonce = ByteArray(12) { (it * 3 + keyVersion).toByte() }
            val ciphertext = xorWithKey(seal(plaintext))
            return MemoryProtectionResult.Success(
                ProtectedMemoryRecordPayload(
                    AUTHENTICATED_ENCRYPTION_ALGORITHM,
                    MemoryStoreKeyVersion.create(keyVersion).success(),
                    nonce,
                    ciphertext,
                ),
            )
        }

        override fun decrypt(
            payload: ProtectedMemoryRecordPayload,
            associatedData: ByteArray,
        ): MemoryProtectionResult<ByteArray> {
            if (payload.keyVersion.value != keyVersion) {
                return MemoryProtectionResult.Failure(MemoryProtectionError.KEY_UNAVAILABLE)
            }
            if (keyLost) {
                return MemoryProtectionResult.Failure(MemoryProtectionError.KEY_LOST)
            }
            val unsealed = xorWithKey(payload.ciphertext)
            val mac = unsealed.copyOfRange(unsealed.size - 2, unsealed.size)
            val body = unsealed.copyOfRange(0, unsealed.size - 2)
            if (macOf(body).contentEquals(mac).not()) {
                return MemoryProtectionResult.Failure(MemoryProtectionError.AUTHENTICATION_FAILED)
            }
            return MemoryProtectionResult.Success(body)
        }

        private fun seal(plaintext: ByteArray): ByteArray =
            plaintext + macOf(plaintext)

        private fun macOf(bytes: ByteArray): ByteArray {
            var value = 0x1F3B
            bytes.forEach { byte -> value = (value * 31 + byte.toInt()) and 0xFFFF }
            return arrayOf((value shr 8).toByte(), value.toByte()).toByteArray()
        }

        private fun xorWithKey(bytes: ByteArray): ByteArray =
            bytes.mapIndexed { index, byte -> (byte.toInt() xor (0x5A + keyVersion + index % 7)).toByte() }.toByteArray()
    }

    private fun indexFor(scopeCase: MemoryScopeCase): Int = when (scopeCase.layer) {
        MemoryLayer.PERSONAL -> 11
        MemoryLayer.BODY -> 12
        MemoryLayer.DEVICE -> 13
        MemoryLayer.STRATEGY -> 14
        else -> 15
    }

    private data class MemoryScopeCase(val layer: MemoryLayer, val scope: MemoryScope)

    private fun validEnvelope(
        index: Int,
        layer: MemoryLayer = MemoryLayer.SEMANTIC,
        scope: MemoryScope = MemoryScope.GlobalPersonal,
        content: ContextValue = textValue("prefers short answers"),
    ): MemoryEnvelope = MemoryEnvelope.create(
        recordId = recordId(index),
        ownerIdentity = identity(),
        layer = layer,
        type = token("fact"),
        scope = scope,
        subjects = listOf(subject("language")),
        content = content,
        provenance = defaultProvenance,
        confidence = confidence(700),
        validationState = MemoryValidationState.CANDIDATE,
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

    private val defaultProvenance = (MemoryProvenance.create(
        MemoryProvenanceOrigin.USER_EXPLICIT,
        modelGenerated = false,
        (io.redi.robot.context.api.ContextSourceType.parse("memory.test")
            as io.redi.robot.context.api.ContextObservationResult.Success).value,
        (io.redi.robot.context.api.ContextSourceReference.parse("memory_test")
            as io.redi.robot.context.api.ContextObservationResult.Success).value,
        (MemoryCreator.parse("hugo_runtime") as MemoryDomainResult.Success).value,
        null,
    ) as MemoryDomainResult.Success).value

    private fun recordId(index: Int): MemoryRecordId = MemoryRecordId.fromUuid(
        UUID.fromString("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"),
    )

    private fun identity(): HugoIdentityId = HugoIdentityId.fromUuid(
        UUID.fromString("ffffffff-0000-4000-8000-000000000001"),
    )

    private fun instance() = ProviderInstanceId.fromUuid(
        UUID.fromString("eeeeeeee-0000-4000-8000-000000000001"),
    )

    private fun token(raw: String): MemoryTypeToken =
        (MemoryTypeToken.parse(raw) as MemoryDomainResult.Success).value
    private fun subject(raw: String): MemorySubjectToken =
        (MemorySubjectToken.parse(raw) as MemoryDomainResult.Success).value
    private fun creator(raw: String): MemoryCreator =
        (MemoryCreator.parse(raw) as MemoryDomainResult.Success).value
    private fun versionToken(raw: String): MemoryInstanceVersionToken =
        (MemoryInstanceVersionToken.parse(raw) as MemoryDomainResult.Success).value

    private fun textValue(raw: String): ContextValue = ContextValue.TextValue(
        (io.redi.robot.context.api.ContextText.create(raw)
            as io.redi.robot.context.api.ContextObservationResult.Success).value,
    )

    private fun confidence(permille: Int): io.redi.robot.context.api.ContextConfidence =
        (io.redi.robot.context.api.ContextConfidence.create(permille)
            as io.redi.robot.context.api.ContextObservationResult.Success).value

    private fun retention(retentionClass: MemoryRetentionClass, expiry: Long?): MemoryRetention =
        (MemoryRetention.create(retentionClass, expiry) as MemoryDomainResult.Success).value

}

private fun <T> MemoryDomainResult<T>.success(): T = when (this) {
    is MemoryDomainResult.Success -> value
    is MemoryDomainResult.Failure -> error("expected success, got $error")
}

private fun <T> MemoryProtectionResult<T>.success(): T = when (this) {
    is MemoryProtectionResult.Success -> value
    is MemoryProtectionResult.Failure -> error("expected success, got $error")
}
