package io.redi.robot.platform.storage

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.memory.domain.MemoryAllowedUse
import io.redi.robot.memory.domain.MemoryDisposition
import io.redi.robot.memory.domain.MemoryEnvelope
import io.redi.robot.memory.domain.MemoryLayer
import io.redi.robot.memory.domain.MemoryCreator
import io.redi.robot.memory.domain.MemoryDomainResult
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
import io.redi.robot.memory.storage.MemoryPersistenceError
import io.redi.robot.memory.storage.MemoryLoadResult
import io.redi.robot.memory.storage.MemoryPersistResult
import io.redi.robot.memory.storage.MemoryProtectionResult
import io.redi.robot.memory.storage.ProtectedMemoryRecordPayload
import io.redi.robot.memory.storage.MemoryStoreKeyVersion
import io.redi.robot.hugo.identity.HugoIdentityId
import io.redi.robot.capability.provider.ProviderInstanceId
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * H059 Android verification: real Android Keystore AES-256-GCM protection and real atomic
 * per-record file slots for the authoritative memory record store. Proves encrypted-at-rest
 * behavior, key-loss/corruption fail-closed handling, and deterministic persistence on device.
 * No LOOI, provider, network, or user data is involved; the record content is synthetic.
 */
@RunWith(AndroidJUnit4::class)
class AndroidMemoryRecordStorageInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun keystoreProtectionRoundTripsAndFailsClosedOnTamperAndWrongKey() {
        val keyVersion = (MemoryStoreKeyVersion.create(1) as MemoryProtectionResult.Success).value
        val protection = AndroidKeystoreMemoryRecordProtection(keyVersion)
        val plaintext = "synthetic memory payload for H059".toByteArray(Charsets.UTF_8)
        val associatedData = MemoryEnvelopeStore.ASSOCIATED_DATA

        val payload = (protection.encrypt(plaintext, associatedData)
            as MemoryProtectionResult.Success).value
        assertEquals("AES/GCM/NoPadding", payload.algorithm)
        assertEquals(12, payload.nonce.size)
        assertFalse(payload.ciphertext.contentEquals(plaintext))

        val decrypted = (protection.decrypt(payload, associatedData)
            as MemoryProtectionResult.Success).value
        assertTrue(plaintext.contentEquals(decrypted))

        // Tampering authenticates as failed; no plaintext is exposed.
        val tampered = ProtectedMemoryRecordPayload(
            payload.algorithm,
            payload.keyVersion,
            payload.nonce,
            payload.ciphertext.clone().also { bytes ->
                bytes[bytes.size - 1] = ((bytes[bytes.size - 1].toInt() + 1) % 256).toByte()
            },
        )
        assertTrue(
            protection.decrypt(tampered, associatedData)
                is MemoryProtectionResult.Failure,
        )

        // Wrong key version cannot decrypt another store's payload.
        val otherKey = (MemoryStoreKeyVersion.create(2) as MemoryProtectionResult.Success).value
        val otherProtection = AndroidKeystoreMemoryRecordProtection(otherKey)
        assertTrue(
            otherProtection.decrypt(payload, associatedData) is MemoryProtectionResult.Failure,
        )
    }

    @Test
    fun atomicFileStorePersistsEncryptedRecordsWithoutPlaintextAtRest() {
        val directory = File(context.cacheDir, "h059-memory-" + UUID.randomUUID())
        val slots = AtomicFileMemoryRecordSlots(directory)
        val keyVersion = (MemoryStoreKeyVersion.create(1) as MemoryProtectionResult.Success).value
        val store = MemoryEnvelopeStore(
            slots,
            AndroidKeystoreMemoryRecordProtection(keyVersion),
            keyVersion,
        )
        try {
            val envelope = validEnvelope(1)
            assertEquals(MemoryPersistResult.Persisted, store.persist(envelope))

            // Encrypted at rest: the raw slot file carries no plaintext memory content.
            val rawFile = File(directory, "memory-record-${envelope.recordId.value}.enc")
            assertTrue(rawFile.isFile)
            val rawBytes = rawFile.readBytes()
            val rendered = String(rawBytes, Charsets.ISO_8859_1)
            assertFalse(rendered.contains("synthetic"))
            assertFalse(rendered.contains("hugo_runtime"))
            assertFalse(rendered.contains("memory_test"))

            // Restart-equivalent reload through a fresh store over the same slots.
            val reloadedStore = MemoryEnvelopeStore(
                slots,
                AndroidKeystoreMemoryRecordProtection(keyVersion),
                keyVersion,
            )
            when (val loaded = reloadedStore.load(envelope.recordId)) {
                is MemoryLoadResult.Found -> {
                    assertEquals(envelope.disposition, loaded.envelope.disposition)
                    assertEquals(envelope.privacy, loaded.envelope.privacy)
                    assertEquals(envelope.provenance.origin, loaded.envelope.provenance.origin)
                }
                else -> error("expected the persisted record to load")
            }

            // Corruption fails closed with a typed error and no partial exposure.
            rawFile.writeBytes("definitely not a valid slot document".toByteArray(Charsets.UTF_8))
            assertEquals(
                MemoryPersistenceError.CORRUPT_RECORD,
                (reloadedStore.load(envelope.recordId) as MemoryLoadResult.Failed).error,
            )

            // Explicit key-loss style purge removes the record; nothing silently remains.
            assertEquals(1, (store.resetForLostKey() as io.redi.robot.memory.storage.MemoryResetResult.Reset).purgedSlots)
            assertEquals(MemoryLoadResult.Missing, reloadedStore.load(envelope.recordId))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun boundedListingAndTombstonePersistenceOnDevice() {
        val directory = File(context.cacheDir, "h059-memory-" + UUID.randomUUID())
        val slots = AtomicFileMemoryRecordSlots(directory)
        val keyVersion = (MemoryStoreKeyVersion.create(1) as MemoryProtectionResult.Success).value
        val store = MemoryEnvelopeStore(
            slots,
            AndroidKeystoreMemoryRecordProtection(keyVersion),
            keyVersion,
        )
        try {
            (1..4).forEach { index -> assertEquals(MemoryPersistResult.Persisted, store.persist(validEnvelope(index))) }
            assertEquals(3, (store.storedRecordIds(3) as io.redi.robot.memory.storage.MemoryListResult.Ids).recordIds.size)

            val active = validEnvelope(5)
            store.persist(active)
            val tombstoned = MemoryEnvelope.tombstoneOf(active, creator("user_control"), 9_999L)
                .let { result -> (result as io.redi.robot.memory.domain.MemoryDomainResult.Success).value }
            store.persist(tombstoned)
            when (val loaded = store.load(active.recordId)) {
                is MemoryLoadResult.Found ->
                    assertEquals(MemoryDisposition.TOMBSTONED, loaded.envelope.disposition)
                else -> error("expected the tombstoned record to load")
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun validEnvelope(index: Int): MemoryEnvelope = MemoryEnvelope.create(
        recordId = recordId(index),
        ownerIdentity = identity(),
        layer = MemoryLayer.PERSONAL,
        type = (MemoryTypeToken.parse("preference") as io.redi.robot.memory.domain.MemoryDomainResult.Success).value,
        scope = MemoryScope.GlobalPersonal,
        subjects = listOf(
            (MemorySubjectToken.parse("language") as io.redi.robot.memory.domain.MemoryDomainResult.Success).value,
        ),
        content = io.redi.robot.context.api.ContextValue.TextValue(
            (io.redi.robot.context.api.ContextText.create("synthetic memory content")
                as io.redi.robot.context.api.ContextObservationResult.Success).value,
        ),
        provenance = (MemoryProvenance.create(
            MemoryProvenanceOrigin.USER_EXPLICIT,
            modelGenerated = false,
            (io.redi.robot.context.api.ContextSourceType.parse("memory.test")
                as io.redi.robot.context.api.ContextObservationResult.Success).value,
            (io.redi.robot.context.api.ContextSourceReference.parse("memory_test")
                as io.redi.robot.context.api.ContextObservationResult.Success).value,
            (MemoryCreator.parse("hugo_runtime") as io.redi.robot.memory.domain.MemoryDomainResult.Success).value,
            null,
        ) as io.redi.robot.memory.domain.MemoryDomainResult.Success).value,
        confidence = (io.redi.robot.context.api.ContextConfidence.create(800)
            as io.redi.robot.context.api.ContextObservationResult.Success).value,
        validationState = MemoryValidationState.VALIDATED,
        disposition = MemoryDisposition.ACTIVE,
        privacy = ContextPrivacyClassification.PRIVATE,
        allowedUse = MemoryAllowedUse.GATEWAY_ALLOWED,
        retention = (MemoryRetention.create(MemoryRetentionClass.UNTIL_CORRECTED, null)
            as io.redi.robot.memory.domain.MemoryDomainResult.Success).value,
        supersedes = null,
        conflictsWith = emptyList(),
        lineage = null,
        createdAtEpochSecond = 1_000L,
        updatedAtEpochSecond = null,
    ).let { result -> (result as io.redi.robot.memory.domain.MemoryDomainResult.Success).value }

    private fun recordId(index: Int): MemoryRecordId = MemoryRecordId.fromUuid(
        UUID.fromString("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"),
    )

    private fun identity(): HugoIdentityId = HugoIdentityId.fromUuid(
        UUID.fromString("ffffffff-0000-4000-8000-000000000001"),
    )

    private fun instance(): ProviderInstanceId = ProviderInstanceId.fromUuid(
        UUID.fromString("eeeeeeee-0000-4000-8000-000000000001"),
    )

    private fun creator(raw: String): MemoryCreator =
        (MemoryCreator.parse(raw) as MemoryDomainResult.Success).value
}
