package io.redi.robot.provider.storage

import io.redi.robot.adapter.api.AdapterId
import io.redi.robot.adapter.api.AdapterResult
import io.redi.robot.adapter.api.AdapterVersion
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.discovery.identity.IdentityEvidenceResult
import io.redi.robot.discovery.identity.ProtectedIdentityEvidenceReference
import io.redi.robot.manifest.domain.CommonManifestResult
import io.redi.robot.manifest.domain.ManifestDigest
import io.redi.robot.manifest.domain.ManifestSchemaVersion
import io.redi.robot.manifest.domain.ManifestVersion
import io.redi.robot.manifest.domain.SpecializedManifestKind
import io.redi.robot.provider.calibration.CalibrationDomainResult
import io.redi.robot.provider.calibration.CalibrationProfileId
import io.redi.robot.trust.domain.TrustDomainResult
import io.redi.robot.trust.domain.TrustRecordId
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderInstanceStoreTest {
    @Test
    fun `create lookup and immutable snapshot preserve private identity`() {
        val store = ProviderInstanceStore.empty()
        val record = record(instance = INSTANCE_A)

        assertTrue(store.create(record, revision(0)) is ProviderInstanceStoreResult.Success)
        assertEquals(instance(INSTANCE_A), store.lookup(instance(INSTANCE_A))?.instanceId)
        assertEquals(1, store.snapshot().records.size)
        assertEquals(1, store.snapshot().revision.value)
        assertTrue(runCatching { (store.snapshot().records as MutableList).clear() }.isFailure)
    }

    @Test
    fun `alias manifest and adapter updates preserve identity`() {
        val store = storeWith(record())
        val originalId = checkNotNull(store.lookup(instance(INSTANCE_A))).instanceId

        val aliasResult = store.updateAlias(instance(INSTANCE_A), alias("Kitchen body"), revision(1), recordRevision(1))
        assertTrue(aliasResult is ProviderInstanceStoreResult.Success)
        val associationResult = store.updateAssociations(
            instance(INSTANCE_A), manifest("2.0.0"), adapter("2.1.0"), timestamp(30), timestamp(29),
            revision(2), recordRevision(2),
        )
        assertTrue(associationResult is ProviderInstanceStoreResult.Success)

        val updated = checkNotNull(store.lookup(originalId))
        assertEquals(originalId, updated.instanceId)
        assertEquals("Kitchen body", updated.alias.value)
        assertEquals("2.0.0", updated.manifest?.manifestVersion?.wireValue)
        assertEquals("2.1.0", updated.adapter?.version?.wireValue)
    }

    @Test
    fun `two same definition devices remain distinct`() {
        val store = ProviderInstanceStore.empty()
        assertTrue(store.create(record(instance = INSTANCE_A, binding = BINDING_A), revision(0)) is ProviderInstanceStoreResult.Success)
        assertTrue(
            store.create(
                record(
                    entry = "20000000-0000-4000-8000-000000000002",
                    instance = INSTANCE_B,
                    binding = BINDING_B,
                ),
                revision(1),
            ) is ProviderInstanceStoreResult.Success,
        )
        assertNotEquals(store.snapshot().records[0].instanceId, store.snapshot().records[1].instanceId)
    }

    @Test
    fun `duplicate instance and conflicting protected binding are rejected`() {
        val store = storeWith(record())
        assertFailure(
            ProviderInstanceStoreError.DUPLICATE_INSTANCE_ID,
            store.create(record(instance = INSTANCE_A, binding = BINDING_B), revision(1)),
        )
        assertFailure(
            ProviderInstanceStoreError.CONFLICTING_IDENTITY_EVIDENCE,
            store.create(
                record(
                    entry = "20000000-0000-4000-8000-000000000002",
                    instance = INSTANCE_B,
                    binding = BINDING_A,
                ),
                revision(1),
            ),
        )
        assertEquals(1, store.snapshot().revision.value)
    }

    @Test
    fun `conflicting manifest definition and wrong specialization are rejected`() {
        assertFailure(
            ProviderInstanceStoreError.CONFLICTING_DEFINITION_BINDING,
            ProviderInstanceRecord.create(
                entry("20000000-0000-4000-8000-000000000001"), instance(INSTANCE_A), definition("other/device"),
                ProviderFamily.DEVICE_ADAPTER, alias("Device"), manifest(), adapter(), emptySet(), emptySet(),
                setOf(binding(BINDING_A)), timestamp(10), timestamp(20), null, provenance = provenance(),
            ),
        )
        val wrongSpecialization = StoredManifestReference(
            definition(DEFINITION), schema("1.0"), manifestVersion("1.0.0"), digest(), SpecializedManifestKind.BODY,
        )
        assertFailure(
            ProviderInstanceStoreError.INVALID_MANIFEST_REFERENCE,
            ProviderInstanceRecord.create(
                entry("20000000-0000-4000-8000-000000000002"), instance(INSTANCE_A), definition(DEFINITION),
                ProviderFamily.DEVICE_ADAPTER, alias("Device"), wrongSpecialization, adapter(), emptySet(), emptySet(),
                setOf(binding(BINDING_A)), timestamp(10), timestamp(20), null, provenance = provenance(),
            ),
        )
    }

    @Test
    fun `stale revisions fail without mutation`() {
        val store = storeWith(record())
        assertFailure(
            ProviderInstanceStoreError.STALE_STORE_REVISION,
            store.updateAlias(instance(INSTANCE_A), alias("New"), revision(0), recordRevision(1)),
        )
        assertFailure(
            ProviderInstanceStoreError.STALE_RECORD_REVISION,
            store.updateAlias(instance(INSTANCE_A), alias("New"), revision(1), recordRevision(9)),
        )
        assertEquals("Desk device", store.lookup(instance(INSTANCE_A))?.alias?.value)
    }

    @Test
    fun `retirement preserves identity and deletion makes rediscovery unknown`() {
        val store = storeWith(record())
        assertTrue(
            store.setStatus(
                instance(INSTANCE_A), ProviderInstanceKnowledgeStatus.RETIRED, revision(1), recordRevision(1),
            ) is ProviderInstanceStoreResult.Success,
        )
        assertEquals(ProviderInstanceKnowledgeStatus.RETIRED, store.lookup(instance(INSTANCE_A))?.status)
        assertEquals(ProviderInstanceKnowledgeStatus.RETIRED, store.identityProjection().single().status)

        assertTrue(store.delete(instance(INSTANCE_A), revision(2)) is ProviderInstanceStoreResult.Success)
        assertNull(store.lookup(instance(INSTANCE_A)))
        assertTrue(store.identityProjection().isEmpty())
        assertFailure(
            ProviderInstanceStoreError.UNKNOWN_PROVIDER,
            store.delete(instance(INSTANCE_A), revision(3)),
        )
    }

    @Test
    fun `diagnostics and H022 projection do not expose private instance identity`() {
        val store = storeWith(record())
        val diagnostics = store.diagnostics().single()
        val projection = store.identityProjection().single()

        assertFalse(diagnostics.toString().contains(INSTANCE_A))
        assertFalse(projection.toString().contains(INSTANCE_A))
        assertEquals("[protected]", projection.protectedIdentityBindings.single().toString())
        assertFalse(ProviderInstanceId::class.java in KnownProviderInstanceProjection::class.java.declaredFields.map { it.type })
    }

    @Test
    fun `trust and calibration links are references without authority or payload`() {
        val stored = record(
            trustReferences = setOf(trust("40000000-0000-4000-8000-000000000001")),
            calibrationReferences = setOf(calibration("50000000-0000-4000-8000-000000000001")),
        )
        assertEquals(1, stored.trustRecordReferences.size)
        assertEquals(1, stored.calibrationProfileReferences.size)
        val fieldTypes = ProviderInstanceRecord::class.java.declaredFields.map { it.type.name }
        assertFalse(fieldTypes.any { it.contains("ProviderTrustRecord") || it.contains("CalibrationResult") })
        assertFalse(fieldTypes.any { it.contains("Permission") || it.contains("Session") || it.contains("Credential") })
    }

    @Test
    fun `persist and restart restore exact identity and revision while encrypting private ID`() {
        val storage = MemoryStorage()
        val protection = TestAesGcmProtection(KEY_A)
        val persistence = ProviderInstanceStorePersistence(storage, protection)
        val store = storeWith(record())

        assertEquals(ProviderInstancePersistResult.Persisted(revision(1)), persistence.persist(store))
        val persistedText = storage.primary!!.toString(StandardCharsets.UTF_8)
        assertFalse(persistedText.contains(INSTANCE_A))
        assertFalse(persistedText.contains(DEFINITION))
        assertTrue(persistedText.contains("AES/GCM/NoPadding"))

        val restored = persistence.restore() as ProviderInstanceRestoreResult.Restored
        assertEquals(instance(INSTANCE_A), restored.store.lookup(instance(INSTANCE_A))?.instanceId)
        assertEquals(1, restored.store.snapshot().revision.value)
        assertEquals(1, restored.evidence.keyVersion.value)
    }

    @Test
    fun `restart restores no grants approvals runtime sessions or active body`() {
        val storage = MemoryStorage()
        val persistence = ProviderInstanceStorePersistence(storage, TestAesGcmProtection(KEY_A))
        persistence.persist(storeWith(record()))
        val restored = (persistence.restore() as ProviderInstanceRestoreResult.Restored).store
        val names = restored.lookup(instance(INSTANCE_A))!!::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(names.any { it.contains("grant") || it.contains("approval") || it.contains("session") })
        assertFalse(names.any { it.contains("connection") || it.contains("activebody") || it.contains("memory") })
    }

    @Test
    fun `corrupted ciphertext and wrong or missing key fail closed`() {
        val storage = MemoryStorage()
        val writer = ProviderInstanceStorePersistence(storage, TestAesGcmProtection(KEY_A))
        writer.persist(storeWith(record()))
        val original = storage.primary!!.copyOf()

        val envelope = original.toString(StandardCharsets.UTF_8)
        val ciphertextMarker = "\"ciphertext\":\""
        val ciphertextStart = envelope.indexOf(ciphertextMarker) + ciphertextMarker.length
        require(ciphertextStart >= ciphertextMarker.length)
        val replacement = if (envelope[ciphertextStart] == 'A') "B" else "A"
        storage.primary = envelope.replaceRange(ciphertextStart, ciphertextStart + 1, replacement)
            .toByteArray(StandardCharsets.UTF_8)
        val corrupted = writer.restore() as ProviderInstanceRestoreResult.Failed
        assertEquals(
            ProviderInstancePersistenceError.PROTECTION_FAILED(ProviderProtectionError.AUTHENTICATION_FAILED),
            corrupted.primaryError,
        )

        storage.primary = original
        val wrongKey = ProviderInstanceStorePersistence(storage, TestAesGcmProtection(KEY_B))
        val wrong = wrongKey.restore() as ProviderInstanceRestoreResult.Failed
        assertEquals(
            ProviderInstancePersistenceError.PROTECTION_FAILED(ProviderProtectionError.AUTHENTICATION_FAILED),
            wrong.primaryError,
        )

        val missingKey = ProviderInstanceStorePersistence(storage, MissingKeyProtection())
        val missing = missingKey.restore() as ProviderInstanceRestoreResult.Failed
        assertEquals(
            ProviderInstancePersistenceError.PROTECTION_FAILED(ProviderProtectionError.KEY_LOST),
            missing.primaryError,
        )
    }

    @Test
    fun `valid backup recovers corrupt primary with exact evidence`() {
        val storage = MemoryStorage()
        val persistence = ProviderInstanceStorePersistence(storage, TestAesGcmProtection(KEY_A))
        persistence.persist(storeWith(record()))
        storage.backup = storage.primary!!.copyOf()
        storage.primary = "truncated".toByteArray()

        val recovered = persistence.restore() as ProviderInstanceRestoreResult.RecoveredFromBackup
        assertEquals(ProviderStoreStorageSlot.BACKUP, recovered.evidence.source)
        assertEquals(ProviderInstancePersistenceError.MALFORMED_ENVELOPE, recovered.evidence.rejectedPrimary)
        assertEquals(instance(INSTANCE_A), recovered.store.lookup(instance(INSTANCE_A))?.instanceId)
        assertTrue(persistence.commitRecovery(recovered) is ProviderInstancePersistResult.Persisted)
    }

    @Test
    fun `failed atomic write preserves prior snapshot`() {
        val storage = MemoryStorage()
        val persistence = ProviderInstanceStorePersistence(storage, TestAesGcmProtection(KEY_A))
        persistence.persist(storeWith(record()))
        val original = storage.primary!!.copyOf()
        storage.failWrites = true

        assertEquals(
            ProviderInstancePersistResult.Failed(ProviderInstancePersistenceError.ATOMIC_WRITE_FAILED),
            persistence.persist(storeWith(record(instance = INSTANCE_B, binding = BINDING_B))),
        )
        assertArrayEquals(original, storage.primary)
    }

    @Test
    fun `reset is explicit and removes persisted identity knowledge`() {
        val storage = MemoryStorage()
        val persistence = ProviderInstanceStorePersistence(storage, TestAesGcmProtection(KEY_A))
        persistence.persist(storeWith(record()))

        assertEquals(ProviderInstanceResetResult.Reset, persistence.reset())
        assertEquals(ProviderInstanceRestoreResult.Absent, persistence.restore())
    }

    @Test
    fun `unsupported persistence schema fails closed`() {
        val decoded = ProviderInstanceStoreSnapshotCodec.decode(
            """{"schemaVersion":"9.0","revision":0,"records":[]}""",
        )
        assertEquals(
            SnapshotDecodeResult.Failure(ProviderInstancePersistenceError.UNSUPPORTED_SCHEMA),
            decoded,
        )
    }

    @Test
    fun `equivalent snapshots encode deterministically independent of insertion order`() {
        val a = record(instance = INSTANCE_A, binding = BINDING_A)
        val b = record(
            entry = "20000000-0000-4000-8000-000000000002",
            instance = INSTANCE_B,
            binding = BINDING_B,
        )
        val first = snapshot(listOf(a, b), 2)
        val second = snapshot(listOf(b, a), 2)
        assertEquals(
            ProviderInstanceStoreSnapshotCodec.encode(first),
            ProviderInstanceStoreSnapshotCodec.encode(second),
        )
    }

    @Test
    fun `domain has no Android types and aliases reject stable transport identifiers`() {
        val domainClasses = listOf(
            ProviderInstanceRecord::class.java,
            ProviderInstanceStore::class.java,
            ProviderInstanceStorePersistence::class.java,
            ProviderInstanceProtection::class.java,
        )
        assertFalse(domainClasses.flatMap { it.declaredMethods.toList() }.any {
            it.returnType.name.startsWith("android.") || it.parameterTypes.any { type -> type.name.startsWith("android.") }
        })
        assertTrue(RedactedProviderAlias.parse("AA:BB:CC:DD:EE:FF") is ProviderIdentityResult.Failure)
        assertFailure(
            ProviderInstanceStoreError.INVALID_ALIAS,
            ProviderInstanceRecord.create(
                entry("20000000-0000-4000-8000-000000000009"), instance(INSTANCE_A), definition(DEFINITION),
                ProviderFamily.DEVICE_ADAPTER, alias("Device 192.0.2.1"), manifest(), adapter(), emptySet(),
                emptySet(), setOf(binding(BINDING_A)), timestamp(10), timestamp(20), null,
                provenance = provenance(),
            ),
        )
        assertTrue(
            ProtectedIdentityEvidenceReference.parse("192.0.2.1") is IdentityEvidenceResult.Failure,
        )
        val fields = ProviderInstanceRecord::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(fields.any { it.contains("mac") || it.contains("ipaddress") || it.contains("blehandle") })
    }

    private fun storeWith(record: ProviderInstanceRecord): ProviderInstanceStore = ProviderInstanceStore.empty().also {
        assertTrue(it.create(record, revision(0)) is ProviderInstanceStoreResult.Success)
    }

    private fun snapshot(records: List<ProviderInstanceRecord>, revision: Long): ProviderInstanceStoreSnapshot =
        storeSuccess(
            ProviderInstanceStoreSnapshot.create(
                ProviderInstanceStoreSchemaVersion.CURRENT,
                revision(revision),
                records,
            ),
        )

    @Suppress("LongParameterList")
    private fun record(
        entry: String = "20000000-0000-4000-8000-000000000001",
        instance: String = INSTANCE_A,
        binding: String = BINDING_A,
        trustReferences: Set<TrustRecordId> = emptySet(),
        calibrationReferences: Set<CalibrationProfileId> = emptySet(),
    ): ProviderInstanceRecord = storeSuccess(
        ProviderInstanceRecord.create(
            entry(entry), instance(instance), definition(DEFINITION), ProviderFamily.DEVICE_ADAPTER,
            alias("Desk device"), manifest(), adapter(), trustReferences, calibrationReferences,
            setOf(binding(binding)), timestamp(10), timestamp(20), timestamp(19), provenance = provenance(),
        ),
    )

    private fun manifest(version: String = "1.0.0") = StoredManifestReference(
        definition(DEFINITION), schema("1.0"), manifestVersion(version), digest(), SpecializedManifestKind.DEVICE,
    )
    private fun adapter(version: String = "1.0.0") = StoredAdapterReference(
        adapterSuccess(AdapterId.parse("redi.hugo/generic-device")),
        adapterSuccess(AdapterVersion.parse(version)),
    )
    private fun provenance() = ProviderInstanceProvenance(
        ProviderInstanceConfigurationSource.USER_APPROVED,
        storeSuccess(ProviderConfigurationReference.parse("local.user-approved")),
    )
    private fun entry(raw: String) = storeSuccess(ProviderStoreEntryReference.parse(raw))
    private fun instance(raw: String) = providerSuccess(ProviderInstanceId.parse(raw))
    private fun definition(raw: String) = providerSuccess(ProviderDefinitionId.parse(raw))
    private fun alias(raw: String) = providerSuccess(RedactedProviderAlias.parse(raw))
    private fun binding(raw: String) = identitySuccess(ProtectedIdentityEvidenceReference.parse(raw))
    private fun revision(raw: Long) = storeSuccess(ProviderInstanceStoreRevision.create(raw))
    private fun recordRevision(raw: Long) = storeSuccess(ProviderInstanceRecordRevision.create(raw))
    private fun timestamp(raw: Long) = storeSuccess(ProviderStoreTimestamp.create(raw))
    private fun schema(raw: String) = manifestSuccess(ManifestSchemaVersion.parse(raw))
    private fun manifestVersion(raw: String) = manifestSuccess(ManifestVersion.parse(raw))
    private fun digest() = manifestSuccess(ManifestDigest.parse("sha256:${"ab".repeat(32)}"))
    private fun trust(raw: String) = trustSuccess(TrustRecordId.parse(raw))
    private fun calibration(raw: String) = calibrationSuccess(CalibrationProfileId.parse(raw))

    private fun <T> storeSuccess(result: ProviderInstanceStoreResult<T>): T =
        (result as ProviderInstanceStoreResult.Success).value
    private fun <T> providerSuccess(result: ProviderIdentityResult<T>): T =
        (result as ProviderIdentityResult.Success).value
    private fun <T> identitySuccess(result: IdentityEvidenceResult<T>): T =
        (result as IdentityEvidenceResult.Success).value
    private fun <T> manifestSuccess(result: CommonManifestResult<T>): T =
        (result as CommonManifestResult.Success).value
    private fun <T> adapterSuccess(result: AdapterResult<T>): T = (result as AdapterResult.Success).value
    private fun <T> trustSuccess(result: TrustDomainResult<T>): T = (result as TrustDomainResult.Success).value
    private fun <T> calibrationSuccess(result: CalibrationDomainResult<T>): T =
        (result as CalibrationDomainResult.Success).value

    private fun assertFailure(expected: ProviderInstanceStoreError, result: ProviderInstanceStoreResult<*>) {
        assertEquals(ProviderInstanceStoreResult.Failure(expected), result)
    }

    private class MemoryStorage : ProtectedProviderInstanceStorage {
        var primary: ByteArray? = null
        var backup: ByteArray? = null
        var failWrites = false

        override fun read(slot: ProviderStoreStorageSlot): ProviderStoreStorageReadResult =
            (if (slot == ProviderStoreStorageSlot.PRIMARY) primary else backup)?.let {
                ProviderStoreStorageReadResult.Content(it.copyOf())
            } ?: ProviderStoreStorageReadResult.Missing

        override fun writeAtomically(bytes: ByteArray): ProviderStoreStorageWriteResult {
            if (failWrites) return ProviderStoreStorageWriteResult.FailedPreservingPrevious
            backup = primary?.copyOf()
            primary = bytes.copyOf()
            return ProviderStoreStorageWriteResult.Committed
        }

        override fun repairPrimaryAtomically(bytes: ByteArray): ProviderStoreStorageWriteResult {
            if (failWrites) return ProviderStoreStorageWriteResult.FailedPreservingPrevious
            primary = bytes.copyOf()
            return ProviderStoreStorageWriteResult.Committed
        }

        override fun reset(): ProviderStoreStorageResetResult {
            primary = null
            backup = null
            return ProviderStoreStorageResetResult.Reset
        }
    }

    private class TestAesGcmProtection(keyBytes: ByteArray) : ProviderInstanceProtection {
        private val key: SecretKey = SecretKeySpec(keyBytes, "AES")
        private val keyVersion = (ProviderStoreKeyVersion.create(1) as ProviderProtectionResult.Success).value

        override fun encrypt(
            plaintext: ByteArray,
            associatedData: ByteArray,
        ): ProviderProtectionResult<ProtectedProviderStorePayload> = try {
            val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance(AUTHENTICATED_ENCRYPTION_ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(associatedData)
            ProviderProtectionResult.Success(
                ProtectedProviderStorePayload(
                    AUTHENTICATED_ENCRYPTION_ALGORITHM,
                    keyVersion,
                    nonce,
                    cipher.doFinal(plaintext),
                ),
            )
        } catch (_: Exception) {
            ProviderProtectionResult.Failure(ProviderProtectionError.ENCRYPTION_FAILED)
        }

        override fun decrypt(
            payload: ProtectedProviderStorePayload,
            associatedData: ByteArray,
        ): ProviderProtectionResult<ByteArray> = try {
            val cipher = Cipher.getInstance(AUTHENTICATED_ENCRYPTION_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.nonce))
            cipher.updateAAD(associatedData)
            ProviderProtectionResult.Success(cipher.doFinal(payload.ciphertext))
        } catch (_: AEADBadTagException) {
            ProviderProtectionResult.Failure(ProviderProtectionError.AUTHENTICATION_FAILED)
        } catch (_: Exception) {
            ProviderProtectionResult.Failure(ProviderProtectionError.DECRYPTION_FAILED)
        }
    }

    private class MissingKeyProtection : ProviderInstanceProtection {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) =
            ProviderProtectionResult.Failure(ProviderProtectionError.KEY_LOST)
        override fun decrypt(payload: ProtectedProviderStorePayload, associatedData: ByteArray) =
            ProviderProtectionResult.Failure(ProviderProtectionError.KEY_LOST)
    }

    companion object {
        private const val INSTANCE_A = "10000000-0000-4000-8000-000000000001"
        private const val INSTANCE_B = "10000000-0000-4000-8000-000000000002"
        private const val DEFINITION = "acme/device"
        private const val BINDING_A = "iref_aaaaaaaaaaaaaaaa"
        private const val BINDING_B = "iref_bbbbbbbbbbbbbbbb"
        private val KEY_A = ByteArray(32) { it.toByte() }
        private val KEY_B = ByteArray(32) { (it + 1).toByte() }
    }
}
