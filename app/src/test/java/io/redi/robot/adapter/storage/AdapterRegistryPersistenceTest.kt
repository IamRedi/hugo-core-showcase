package io.redi.robot.adapter.storage

import io.redi.robot.adapter.api.AdapterDescriptor
import io.redi.robot.adapter.api.AdapterId
import io.redi.robot.adapter.api.AdapterIdentity
import io.redi.robot.adapter.api.AdapterManifestKind
import io.redi.robot.adapter.api.AdapterManifestSupport
import io.redi.robot.adapter.api.AdapterPackageDigest
import io.redi.robot.adapter.api.AdapterProvenance
import io.redi.robot.adapter.api.AdapterProvenanceSource
import io.redi.robot.adapter.api.AdapterPublisherReference
import io.redi.robot.adapter.api.AdapterResult
import io.redi.robot.adapter.api.AdapterSchemaVersion
import io.redi.robot.adapter.api.AdapterSourceReference
import io.redi.robot.adapter.api.AdapterSpecializedManifestSupport
import io.redi.robot.adapter.api.AdapterVersion
import io.redi.robot.adapter.api.HugoRuntimeCompatibility
import io.redi.robot.adapter.api.HugoRuntimeVersion
import io.redi.robot.adapter.compatibility.CompatibilityEvidenceReference
import io.redi.robot.adapter.compatibility.CompatibilityInputResult
import io.redi.robot.adapter.registry.AdapterArtifactReference
import io.redi.robot.adapter.registry.AdapterExtensionSchemaClaim
import io.redi.robot.adapter.registry.AdapterKnowledgeState
import io.redi.robot.adapter.registry.AdapterRegistry
import io.redi.robot.adapter.registry.AdapterRegistryModelResult
import io.redi.robot.adapter.registry.AdapterRegistryMutationResult
import io.redi.robot.adapter.registry.AdapterRegistryRecord
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.connection.api.ConnectionMethodId
import io.redi.robot.connection.api.ConnectionMethodReference
import io.redi.robot.connection.api.ConnectionMethodVersion
import io.redi.robot.connection.api.ConnectionResult
import io.redi.robot.manifest.domain.CommonManifestResult
import io.redi.robot.manifest.domain.ManifestExtensionId
import io.redi.robot.manifest.domain.ManifestSchemaVersion
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterRegistryPersistenceTest {
    @Test
    fun `persist and restore preserves deterministic ordering revision versions and exact digests`() {
        val registry = AdapterRegistry()
        val v2 = record("2.0.0", "cd")
        val v1 = record("1.0.0", "ab")
        register(registry, v2)
        register(registry, v1)
        val persistence = AdapterRegistryPersistence(MemoryAtomicStorage())

        val persisted = persistence.persist(registry)
        val restored = persistence.restore() as AdapterRegistryRestoreResult.Restored

        assertTrue(persisted is AdapterRegistryPersistResult.Persisted)
        assertEquals(2L, restored.registry.snapshot().revision.value)
        assertEquals(listOf("1.0.0", "2.0.0"), restored.registry.snapshot().records.map { it.key.version.wireValue })
        assertEquals(listOf(digest("ab"), digest("cd")), restored.registry.snapshot().records.map { it.packageDigest })
        assertEquals(RegistryStorageSlot.PRIMARY, restored.evidence.source)
        assertEquals((persisted as AdapterRegistryPersistResult.Persisted).metadataDigest, restored.evidence.metadataDigest)
    }

    @Test
    fun `restored registry continues from persisted revision without replay`() {
        val storage = MemoryAtomicStorage()
        val persistence = AdapterRegistryPersistence(storage)
        val original = AdapterRegistry().also { register(it, record("1.0.0")) }
        persistence.persist(original)
        val restored = (persistence.restore() as AdapterRegistryRestoreResult.Restored).registry

        val mutation = restored.register(record("2.0.0"), restored.snapshot().revision)

        assertTrue(mutation is AdapterRegistryMutationResult.Registered)
        assertEquals(2L, restored.snapshot().revision.value)
    }

    @Test
    fun `metadata corruption fails closed without an invented empty registry`() {
        val storage = MemoryAtomicStorage()
        val persistence = AdapterRegistryPersistence(storage)
        persistence.persist(AdapterRegistry().also { register(it, record("1.0.0")) })
        storage.primary = checkNotNull(storage.primary).replace("body.move@1.0", "body.move@1.1")

        val restored = persistence.restore()

        assertTrue(restored is AdapterRegistryRestoreResult.Failed)
        assertEquals(
            RegistryPersistenceError.METADATA_DIGEST_MISMATCH,
            (restored as AdapterRegistryRestoreResult.Failed).primaryError,
        )
    }

    @Test
    fun `truncated primary recovers only a verified backup and records exact evidence`() {
        val storage = MemoryAtomicStorage()
        val persistence = AdapterRegistryPersistence(storage)
        val registry = AdapterRegistry()
        register(registry, record("1.0.0"))
        persistence.persist(registry)
        register(registry, record("2.0.0"))
        persistence.persist(registry)
        storage.primary = checkNotNull(storage.primary).take(41)

        val recovery = persistence.restore() as AdapterRegistryRestoreResult.RecoveredFromBackup

        assertEquals(1L, recovery.registry.snapshot().revision.value)
        assertEquals(RegistryStorageSlot.BACKUP, recovery.evidence.source)
        assertEquals(RegistryPersistenceError.MALFORMED_DOCUMENT, recovery.evidence.rejectedPrimary)
        assertTrue(persistence.commitRecovery(recovery) is AdapterRegistryPersistResult.Persisted)
        assertTrue(persistence.restore() is AdapterRegistryRestoreResult.Restored)
        assertTrue(checkNotNull(storage.backup).contains("\"revision\":1"))
    }

    @Test
    fun `unsupported and older schemas fail closed with migration disposition`() {
        val current = AdapterRegistrySnapshotCodec.encode(
            AdapterRegistry().also { register(it, record("1.0.0")) }.snapshot(),
        )
        val older = AdapterRegistrySnapshotCodec.decode(current.replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"0.9\""))
        val future = AdapterRegistrySnapshotCodec.decode(current.replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"2.0\""))

        assertSchemaDisposition(older, RegistrySchemaDisposition.MIGRATION_REQUIRED)
        assertSchemaDisposition(future, RegistrySchemaDisposition.UNSUPPORTED)
    }

    @Test
    fun `duplicate and conflicting persisted keys are rejected before restore`() {
        val encoded = AdapterRegistrySnapshotCodec.encode(
            AdapterRegistry().also { register(it, record("1.0.0")) }.snapshot(),
        )
        val root = Json.parseToJsonElement(encoded) as JsonObject
        val records = root.getValue("records") as JsonArray
        val duplicate = withRecords(root, JsonArray(listOf(records.single(), records.single())))
        val conflictingRecord = changeDescriptorDigest(records.single() as JsonObject, "ef")
        val conflicting = withRecords(root, JsonArray(listOf(records.single(), conflictingRecord)))

        val duplicateResult = AdapterRegistrySnapshotCodec.decode(duplicate)
        val conflictResult = AdapterRegistrySnapshotCodec.decode(conflicting)

        assertEquals(
            RegistryPersistenceError.DUPLICATE_RECORD(1),
            (duplicateResult as RegistrySnapshotDecodeResult.Failure).error,
        )
        assertEquals(
            RegistryPersistenceError.CONFLICTING_RECORD(1),
            (conflictResult as RegistrySnapshotDecodeResult.Failure).error,
        )
    }

    @Test
    fun `contradictory revision and unique record count is rejected`() {
        val registry = AdapterRegistry().also {
            register(it, record("1.0.0"))
            register(it, record("2.0.0"))
        }
        val encoded = AdapterRegistrySnapshotCodec.encode(registry.snapshot())
            .replace("\"revision\":2", "\"revision\":1")

        val decoded = AdapterRegistrySnapshotCodec.decode(encoded)

        assertEquals(
            RegistryPersistenceError.INVALID_REVISION,
            (decoded as RegistrySnapshotDecodeResult.Failure).error,
        )
    }

    @Test
    fun `failed atomic replacement preserves the previously committed snapshot`() {
        val storage = MemoryAtomicStorage()
        val persistence = AdapterRegistryPersistence(storage)
        val registry = AdapterRegistry()
        register(registry, record("1.0.0"))
        persistence.persist(registry)
        val committed = storage.primary
        register(registry, record("2.0.0"))
        storage.failNextWrite = true

        val failed = persistence.persist(registry)
        val restored = persistence.restore() as AdapterRegistryRestoreResult.Restored

        assertEquals(committed, storage.primary)
        assertEquals(AdapterRegistryPersistResult.Failed(RegistryPersistenceError.ATOMIC_WRITE_FAILED), failed)
        assertEquals(1L, restored.registry.snapshot().revision.value)
    }

    @Test
    fun `file storage atomically replaces primary and retains a recoverable prior snapshot`() {
        val directory = Files.createTempDirectory("h026-registry-test").toFile()
        try {
            val persistence = AdapterRegistryPersistence(AtomicFileAdapterRegistryStorage(directory))
            val registry = AdapterRegistry()
            register(registry, record("1.0.0"))
            assertTrue(persistence.persist(registry) is AdapterRegistryPersistResult.Persisted)
            register(registry, record("2.0.0"))
            assertTrue(persistence.persist(registry) is AdapterRegistryPersistResult.Persisted)

            assertEquals(2L, (persistence.restore() as AdapterRegistryRestoreResult.Restored).registry.snapshot().revision.value)
            Files.write(directory.resolve("adapter-registry.json").toPath(), "{truncated".toByteArray())
            assertEquals(
                1L,
                (persistence.restore() as AdapterRegistryRestoreResult.RecoveredFromBackup).registry.snapshot().revision.value,
            )
            assertTrue(persistence.reset() is AdapterRegistryResetResult.Reset)
            assertTrue(persistence.restore() is AdapterRegistryRestoreResult.Absent)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `persisted knowledge and metadata expose no execution authority or secret transport fields`() {
        val registry = AdapterRegistry().also { register(it, record("1.0.0")) }
        val encoded = AdapterRegistrySnapshotCodec.encode(registry.snapshot()).lowercase()
        val forbiddenDocumentFields = listOf(
            "credential",
            "password",
            "token",
            "secret",
            "providerinstance",
            "transportaddress",
            "bluetooth",
            "gatt",
        )
        val authorityWords = listOf("installed", "trusted", "enabled", "loaded", "available", "authorized", "executing")
        val publicFields = listOf(
            RegistryRestoreEvidence::class.java,
            AdapterRegistryRestoreResult.Restored::class.java,
        ).flatMap { it.declaredFields.map { field -> field.name.lowercase() } }

        assertTrue(forbiddenDocumentFields.none(encoded::contains))
        assertTrue(authorityWords.none { word -> publicFields.any { word in it } })
        assertFalse(encoded.contains("looi"))
    }

    private fun withRecords(root: JsonObject, records: JsonArray): String = JsonObject(
        linkedMapOf<String, JsonElement>().apply {
            putAll(root)
            put("records", records)
        },
    ).toString()

    private fun changeDescriptorDigest(record: JsonObject, pair: String): JsonObject {
        val descriptor = record.getValue("descriptor") as JsonObject
        val changedDescriptor = JsonObject(
            linkedMapOf<String, JsonElement>().apply {
                putAll(descriptor)
                put("packageDigest", JsonPrimitive("sha256:${pair.repeat(32)}"))
            },
        )
        return JsonObject(
            linkedMapOf<String, JsonElement>().apply {
                putAll(record)
                put("descriptor", changedDescriptor)
            },
        )
    }

    private fun assertSchemaDisposition(result: RegistrySnapshotDecodeResult, expected: RegistrySchemaDisposition) {
        val error = (result as RegistrySnapshotDecodeResult.Failure).error
        assertTrue(error is RegistryPersistenceError.SCHEMA_NOT_CURRENT)
        assertEquals(expected, (error as RegistryPersistenceError.SCHEMA_NOT_CURRENT).disposition)
    }

    private fun register(registry: AdapterRegistry, record: AdapterRegistryRecord) {
        val result = registry.register(record, registry.snapshot().revision)
        check(result is AdapterRegistryMutationResult.Registered)
    }

    private fun record(version: String, pair: String = "ab"): AdapterRegistryRecord = registrySuccess(
        AdapterRegistryRecord.create(
            descriptor(version, pair),
            evidence("test.adapter.$version"),
            artifact("test.package.$version"),
            AdapterKnowledgeState.DEFINITION,
            listOf(connection()),
            listOf(AdapterExtensionSchemaClaim(extensionId(), schema())),
        ),
    )

    private fun descriptor(version: String, pair: String): AdapterDescriptor = adapterSuccess(
        AdapterDescriptor.create(
            adapterSuccess(AdapterSchemaVersion.parse("1.0")),
            AdapterIdentity(adapterId(), adapterVersion(version)),
            digest(pair),
            AdapterProvenance(
                AdapterProvenanceSource.HUGO_MAINTAINED,
                adapterSuccess(AdapterPublisherReference.parse("io.hugo.maintainers")),
                adapterSuccess(AdapterSourceReference.parse("hugo.adapter.reference")),
            ),
            listOf(
                AdapterManifestSupport(
                    ProviderFamily.BODY_ADAPTER,
                    1,
                    AdapterSpecializedManifestSupport(AdapterManifestKind.BODY, 1),
                ),
            ),
            listOf(capability()),
            adapterSuccess(HugoRuntimeCompatibility.create(runtimeVersion("1.0.0"), runtimeVersion("3.0.0"))),
        ),
    )

    private fun evidence(raw: String): CompatibilityEvidenceReference = when (
        val result = CompatibilityEvidenceReference.parse(raw)
    ) {
        is CompatibilityInputResult.Success -> result.value
        is CompatibilityInputResult.Failure -> throw AssertionError(result.error)
    }
    private fun artifact(raw: String): AdapterArtifactReference = registrySuccess(AdapterArtifactReference.parse(raw))
    private fun adapterId(): AdapterId = adapterSuccess(AdapterId.parse("io.hugo.adapter/reference"))
    private fun adapterVersion(raw: String): AdapterVersion = adapterSuccess(AdapterVersion.parse(raw))
    private fun digest(pair: String): AdapterPackageDigest = adapterSuccess(
        AdapterPackageDigest.parse("sha256:${pair.repeat(32)}"),
    )
    private fun capability(): CapabilityReference = when (val result = CapabilityReference.parse("body.move@1.0")) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> throw AssertionError(result.error)
    }
    private fun runtimeVersion(raw: String): HugoRuntimeVersion = adapterSuccess(HugoRuntimeVersion.parse(raw))
    private fun connection(): ConnectionMethodReference = ConnectionMethodReference(
        connectionSuccess(ConnectionMethodId.parse("hugo.connection/local")),
        connectionSuccess(ConnectionMethodVersion.parse("1.0")),
    )
    private fun extensionId(): ManifestExtensionId = commonSuccess(ManifestExtensionId.parse("hugo.extension/profile"))
    private fun schema(): ManifestSchemaVersion = commonSuccess(ManifestSchemaVersion.parse("1.0"))

    private fun <T> registrySuccess(result: AdapterRegistryModelResult<T>): T = when (result) {
        is AdapterRegistryModelResult.Success -> result.value
        is AdapterRegistryModelResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> adapterSuccess(result: AdapterResult<T>): T = when (result) {
        is AdapterResult.Success -> result.value
        is AdapterResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> connectionSuccess(result: ConnectionResult<T>): T = when (result) {
        is ConnectionResult.Success -> result.value
        is ConnectionResult.Failure -> throw AssertionError(result.error)
    }
    private fun <T> commonSuccess(result: CommonManifestResult<T>): T = when (result) {
        is CommonManifestResult.Success -> result.value
        is CommonManifestResult.Failure -> throw AssertionError(result.error)
    }

    private class MemoryAtomicStorage : ProtectedAdapterRegistryStorage {
        var primary: String? = null
        var backup: String? = null
        var failNextWrite: Boolean = false

        override fun read(slot: RegistryStorageSlot): RegistryStorageReadResult = when (
            val content = if (slot == RegistryStorageSlot.PRIMARY) primary else backup
        ) {
            null -> RegistryStorageReadResult.Missing
            else -> RegistryStorageReadResult.Content(content)
        }

        override fun writeAtomically(text: String): RegistryStorageWriteResult {
            if (failNextWrite) {
                failNextWrite = false
                return RegistryStorageWriteResult.FailedPreservingPrevious
            }
            backup = primary
            primary = text
            return RegistryStorageWriteResult.Committed
        }

        override fun repairPrimaryAtomically(text: String): RegistryStorageWriteResult {
            if (failNextWrite) {
                failNextWrite = false
                return RegistryStorageWriteResult.FailedPreservingPrevious
            }
            primary = text
            return RegistryStorageWriteResult.Committed
        }

        override fun reset(): RegistryStorageResetResult {
            primary = null
            backup = null
            return RegistryStorageResetResult.Reset
        }
    }
}
