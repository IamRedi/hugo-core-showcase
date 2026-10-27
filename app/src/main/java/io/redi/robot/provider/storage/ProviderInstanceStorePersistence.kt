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
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

enum class ProviderStoreStorageSlot { PRIMARY, BACKUP }

sealed interface ProviderStoreStorageReadResult {
    data class Content(val bytes: ByteArray) : ProviderStoreStorageReadResult
    data object Missing : ProviderStoreStorageReadResult
    data object Failed : ProviderStoreStorageReadResult
}

sealed interface ProviderStoreStorageWriteResult {
    data object Committed : ProviderStoreStorageWriteResult
    data object FailedPreservingPrevious : ProviderStoreStorageWriteResult
}

sealed interface ProviderStoreStorageResetResult {
    data object Reset : ProviderStoreStorageResetResult
    data object Failed : ProviderStoreStorageResetResult
}

/** Implementations must use application-private storage and atomic primary/backup replacement. */
interface ProtectedProviderInstanceStorage {
    fun read(slot: ProviderStoreStorageSlot): ProviderStoreStorageReadResult
    fun writeAtomically(bytes: ByteArray): ProviderStoreStorageWriteResult
    fun repairPrimaryAtomically(bytes: ByteArray): ProviderStoreStorageWriteResult
    fun reset(): ProviderStoreStorageResetResult
}

data class ProviderStoreKeyVersion private constructor(val value: Int) {
    companion object {
        fun create(value: Int): ProviderProtectionResult<ProviderStoreKeyVersion> =
            if (value > 0) ProviderProtectionResult.Success(ProviderStoreKeyVersion(value))
            else ProviderProtectionResult.Failure(ProviderProtectionError.INVALID_KEY_VERSION)
    }
}

data class ProtectedProviderStorePayload(
    val algorithm: String,
    val keyVersion: ProviderStoreKeyVersion,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
) {
    init {
        require(algorithm == AUTHENTICATED_ENCRYPTION_ALGORITHM)
        require(nonce.size == 12)
        require(ciphertext.isNotEmpty())
    }

    override fun equals(other: Any?): Boolean = other is ProtectedProviderStorePayload &&
        algorithm == other.algorithm && keyVersion == other.keyVersion &&
        nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = 31 * (31 * (31 * algorithm.hashCode() + keyVersion.hashCode()) +
        nonce.contentHashCode()) + ciphertext.contentHashCode()
}

sealed interface ProviderProtectionResult<out T> {
    data class Success<T>(val value: T) : ProviderProtectionResult<T>
    data class Failure(val error: ProviderProtectionError) : ProviderProtectionResult<Nothing>
}

enum class ProviderProtectionError {
    INVALID_KEY_VERSION,
    KEY_UNAVAILABLE,
    KEY_LOST,
    ENCRYPTION_FAILED,
    AUTHENTICATION_FAILED,
    DECRYPTION_FAILED,
}

/** Authenticated-encryption port. Android Keystore types remain in platform/storage. */
interface ProviderInstanceProtection {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ProviderProtectionResult<ProtectedProviderStorePayload>
    fun decrypt(payload: ProtectedProviderStorePayload, associatedData: ByteArray): ProviderProtectionResult<ByteArray>
}

sealed interface ProviderInstancePersistResult {
    data class Persisted(val revision: ProviderInstanceStoreRevision) : ProviderInstancePersistResult
    data class Failed(val error: ProviderInstancePersistenceError) : ProviderInstancePersistResult
}

sealed interface ProviderInstanceRestoreResult {
    data class Restored(val store: ProviderInstanceStore, val evidence: ProviderStoreRestoreEvidence) :
        ProviderInstanceRestoreResult
    data class RecoveredFromBackup(val store: ProviderInstanceStore, val evidence: ProviderStoreRestoreEvidence) :
        ProviderInstanceRestoreResult
    data object Absent : ProviderInstanceRestoreResult
    data class Failed(
        val primaryError: ProviderInstancePersistenceError?,
        val backupError: ProviderInstancePersistenceError?,
    ) : ProviderInstanceRestoreResult
}

data class ProviderStoreRestoreEvidence(
    val source: ProviderStoreStorageSlot,
    val schemaVersion: ProviderInstanceStoreSchemaVersion,
    val revision: ProviderInstanceStoreRevision,
    val keyVersion: ProviderStoreKeyVersion,
    val rejectedPrimary: ProviderInstancePersistenceError?,
)

sealed interface ProviderInstanceResetResult {
    data object Reset : ProviderInstanceResetResult
    data class Failed(val error: ProviderInstancePersistenceError) : ProviderInstanceResetResult
}

sealed interface ProviderInstancePersistenceError {
    data object STORAGE_READ_FAILED : ProviderInstancePersistenceError
    data object ATOMIC_WRITE_FAILED : ProviderInstancePersistenceError
    data object RESET_FAILED : ProviderInstancePersistenceError
    data object MALFORMED_ENVELOPE : ProviderInstancePersistenceError
    data object MALFORMED_PAYLOAD : ProviderInstancePersistenceError
    data object UNSUPPORTED_SCHEMA : ProviderInstancePersistenceError
    data class PROTECTION_FAILED(val reason: ProviderProtectionError) : ProviderInstancePersistenceError
    data class INVALID_RECORD(val index: Int, val reason: ProviderInstanceStoreError) :
        ProviderInstancePersistenceError
}

class ProviderInstanceStorePersistence(
    private val storage: ProtectedProviderInstanceStorage,
    private val protection: ProviderInstanceProtection,
) {
    fun persist(store: ProviderInstanceStore): ProviderInstancePersistResult = persist(store.snapshot())

    fun persist(snapshot: ProviderInstanceStoreSnapshot): ProviderInstancePersistResult {
        val plaintext = ProviderInstanceStoreSnapshotCodec.encode(snapshot).toByteArray(StandardCharsets.UTF_8)
        val protected = when (val result = protection.encrypt(plaintext, ASSOCIATED_DATA)) {
            is ProviderProtectionResult.Success -> result.value
            is ProviderProtectionResult.Failure -> return ProviderInstancePersistResult.Failed(
                ProviderInstancePersistenceError.PROTECTION_FAILED(result.error),
            )
        }
        val bytes = ProviderStoreEnvelopeCodec.encode(protected).toByteArray(StandardCharsets.UTF_8)
        return when (storage.writeAtomically(bytes)) {
            ProviderStoreStorageWriteResult.Committed -> ProviderInstancePersistResult.Persisted(snapshot.revision)
            ProviderStoreStorageWriteResult.FailedPreservingPrevious -> ProviderInstancePersistResult.Failed(
                ProviderInstancePersistenceError.ATOMIC_WRITE_FAILED,
            )
        }
    }

    fun restore(): ProviderInstanceRestoreResult = when (val primary = storage.read(ProviderStoreStorageSlot.PRIMARY)) {
        is ProviderStoreStorageReadResult.Content -> when (val decoded = decode(primary.bytes)) {
            is DecodeResult.Success -> restored(decoded)
            is DecodeResult.Failure -> recover(decoded.error)
        }
        ProviderStoreStorageReadResult.Failed -> recover(ProviderInstancePersistenceError.STORAGE_READ_FAILED)
        ProviderStoreStorageReadResult.Missing -> when (val backup = storage.read(ProviderStoreStorageSlot.BACKUP)) {
            ProviderStoreStorageReadResult.Missing -> ProviderInstanceRestoreResult.Absent
            ProviderStoreStorageReadResult.Failed -> ProviderInstanceRestoreResult.Failed(
                null,
                ProviderInstancePersistenceError.STORAGE_READ_FAILED,
            )
            is ProviderStoreStorageReadResult.Content -> when (val decoded = decode(backup.bytes)) {
                is DecodeResult.Success -> recovered(decoded, null)
                is DecodeResult.Failure -> ProviderInstanceRestoreResult.Failed(null, decoded.error)
            }
        }
    }

    fun commitRecovery(recovery: ProviderInstanceRestoreResult.RecoveredFromBackup): ProviderInstancePersistResult {
        val snapshot = recovery.store.snapshot()
        val plaintext = ProviderInstanceStoreSnapshotCodec.encode(snapshot).toByteArray(StandardCharsets.UTF_8)
        val protected = when (val result = protection.encrypt(plaintext, ASSOCIATED_DATA)) {
            is ProviderProtectionResult.Success -> result.value
            is ProviderProtectionResult.Failure -> return ProviderInstancePersistResult.Failed(
                ProviderInstancePersistenceError.PROTECTION_FAILED(result.error),
            )
        }
        val bytes = ProviderStoreEnvelopeCodec.encode(protected).toByteArray(StandardCharsets.UTF_8)
        return when (storage.repairPrimaryAtomically(bytes)) {
            ProviderStoreStorageWriteResult.Committed -> ProviderInstancePersistResult.Persisted(snapshot.revision)
            ProviderStoreStorageWriteResult.FailedPreservingPrevious -> ProviderInstancePersistResult.Failed(
                ProviderInstancePersistenceError.ATOMIC_WRITE_FAILED,
            )
        }
    }

    fun reset(): ProviderInstanceResetResult = when (storage.reset()) {
        ProviderStoreStorageResetResult.Reset -> ProviderInstanceResetResult.Reset
        ProviderStoreStorageResetResult.Failed -> ProviderInstanceResetResult.Failed(
            ProviderInstancePersistenceError.RESET_FAILED,
        )
    }

    private fun recover(primaryError: ProviderInstancePersistenceError): ProviderInstanceRestoreResult =
        when (val backup = storage.read(ProviderStoreStorageSlot.BACKUP)) {
            ProviderStoreStorageReadResult.Missing -> ProviderInstanceRestoreResult.Failed(primaryError, null)
            ProviderStoreStorageReadResult.Failed -> ProviderInstanceRestoreResult.Failed(
                primaryError,
                ProviderInstancePersistenceError.STORAGE_READ_FAILED,
            )
            is ProviderStoreStorageReadResult.Content -> when (val decoded = decode(backup.bytes)) {
                is DecodeResult.Success -> recovered(decoded, primaryError)
                is DecodeResult.Failure -> ProviderInstanceRestoreResult.Failed(primaryError, decoded.error)
            }
        }

    private fun decode(bytes: ByteArray): DecodeResult {
        val protected = when (val envelope = ProviderStoreEnvelopeCodec.decode(bytes.toString(StandardCharsets.UTF_8))) {
            is EnvelopeResult.Success -> envelope.value
            EnvelopeResult.Failure -> return DecodeResult.Failure(ProviderInstancePersistenceError.MALFORMED_ENVELOPE)
        }
        val plaintext = when (val result = protection.decrypt(protected, ASSOCIATED_DATA)) {
            is ProviderProtectionResult.Success -> result.value
            is ProviderProtectionResult.Failure -> return DecodeResult.Failure(
                ProviderInstancePersistenceError.PROTECTION_FAILED(result.error),
            )
        }
        return when (val snapshot = ProviderInstanceStoreSnapshotCodec.decode(plaintext.toString(StandardCharsets.UTF_8))) {
            is SnapshotDecodeResult.Success -> DecodeResult.Success(snapshot.value, protected.keyVersion)
            is SnapshotDecodeResult.Failure -> DecodeResult.Failure(snapshot.error)
        }
    }

    private fun restored(decoded: DecodeResult.Success) = ProviderInstanceRestoreResult.Restored(
        ProviderInstanceStore.restore(decoded.snapshot),
        evidence(ProviderStoreStorageSlot.PRIMARY, decoded, null),
    )

    private fun recovered(decoded: DecodeResult.Success, primaryError: ProviderInstancePersistenceError?) =
        ProviderInstanceRestoreResult.RecoveredFromBackup(
            ProviderInstanceStore.restore(decoded.snapshot),
            evidence(ProviderStoreStorageSlot.BACKUP, decoded, primaryError),
        )

    private fun evidence(
        source: ProviderStoreStorageSlot,
        decoded: DecodeResult.Success,
        rejectedPrimary: ProviderInstancePersistenceError?,
    ) = ProviderStoreRestoreEvidence(
        source,
        decoded.snapshot.schemaVersion,
        decoded.snapshot.revision,
        decoded.keyVersion,
        rejectedPrimary,
    )

    private sealed interface DecodeResult {
        data class Success(val snapshot: ProviderInstanceStoreSnapshot, val keyVersion: ProviderStoreKeyVersion) :
            DecodeResult
        data class Failure(val error: ProviderInstancePersistenceError) : DecodeResult
    }

    companion object {
        private val ASSOCIATED_DATA = "hugo.provider-instance-store/envelope@1".toByteArray(StandardCharsets.UTF_8)
    }
}

private object ProviderStoreEnvelopeCodec {
    private val json = Json { ignoreUnknownKeys = false }
    private val fields = setOf("schemaVersion", "algorithm", "keyVersion", "nonce", "ciphertext")

    fun encode(payload: ProtectedProviderStorePayload): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(ENVELOPE_SCHEMA))
            put("algorithm", JsonPrimitive(payload.algorithm))
            put("keyVersion", JsonPrimitive(payload.keyVersion.value))
            put("nonce", JsonPrimitive(Base64.getEncoder().encodeToString(payload.nonce)))
            put("ciphertext", JsonPrimitive(Base64.getEncoder().encodeToString(payload.ciphertext)))
        },
    )

    fun decode(text: String): EnvelopeResult = runCatching {
        val root = Json.parseToJsonElement(text) as JsonObject
        require(root.keys == fields)
        require(root.string("schemaVersion") == ENVELOPE_SCHEMA)
        require(root.string("algorithm") == AUTHENTICATED_ENCRYPTION_ALGORITHM)
        val keyVersion = protectionSuccess(ProviderStoreKeyVersion.create(root.int("keyVersion")))
        val nonce = Base64.getDecoder().decode(root.string("nonce"))
        val ciphertext = Base64.getDecoder().decode(root.string("ciphertext"))
        EnvelopeResult.Success(
            ProtectedProviderStorePayload(AUTHENTICATED_ENCRYPTION_ALGORITHM, keyVersion, nonce, ciphertext),
        )
    }.getOrElse { EnvelopeResult.Failure }
}

private sealed interface EnvelopeResult {
    data class Success(val value: ProtectedProviderStorePayload) : EnvelopeResult
    data object Failure : EnvelopeResult
}

internal object ProviderInstanceStoreSnapshotCodec {
    private val json = Json { ignoreUnknownKeys = false }
    private val rootFields = setOf("schemaVersion", "revision", "records")
    private val recordFields = setOf(
        "entryReference", "instanceId", "definitionId", "family", "alias", "manifest", "adapter",
        "trustReferences", "calibrationReferences", "identityBindings", "firstSeenAt", "lastSeenAt",
        "lastSuccessfulUseAt", "recordRevision", "provenanceSource", "provenanceReference", "status",
    )
    private val manifestFields = setOf(
        "definitionId", "schemaVersion", "manifestVersion", "digest", "specialization",
    )
    private val adapterFields = setOf("id", "version")

    fun encode(snapshot: ProviderInstanceStoreSnapshot): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(snapshot.schemaVersion.wireValue))
            put("revision", JsonPrimitive(snapshot.revision.value))
            put("records", buildJsonArray { snapshot.records.forEach { add(encodeRecord(it)) } })
        },
    )

    fun decode(text: String): SnapshotDecodeResult = try {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: fail(ProviderInstancePersistenceError.MALFORMED_PAYLOAD)
        if (root.keys != rootFields) fail(ProviderInstancePersistenceError.MALFORMED_PAYLOAD)
        val schema = storeSuccess(ProviderInstanceStoreSchemaVersion.parse(root.string("schemaVersion")))
        if (schema != ProviderInstanceStoreSchemaVersion.CURRENT) {
            fail(ProviderInstancePersistenceError.UNSUPPORTED_SCHEMA)
        }
        val revision = storeSuccess(ProviderInstanceStoreRevision.create(root.long("revision")))
        val records = root.array("records").mapIndexed { index, element ->
            try {
                decodeRecord(element as JsonObject)
            } catch (failure: RecordFailure) {
                fail(ProviderInstancePersistenceError.INVALID_RECORD(index, failure.reason))
            }
        }
        val snapshot = when (val result = ProviderInstanceStoreSnapshot.create(schema, revision, records)) {
            is ProviderInstanceStoreResult.Success -> result.value
            is ProviderInstanceStoreResult.Failure -> fail(
                ProviderInstancePersistenceError.INVALID_RECORD(-1, result.error),
            )
        }
        SnapshotDecodeResult.Success(snapshot)
    } catch (failure: DecodeFailure) {
        SnapshotDecodeResult.Failure(failure.error)
    } catch (_: Exception) {
        SnapshotDecodeResult.Failure(ProviderInstancePersistenceError.MALFORMED_PAYLOAD)
    }

    private fun encodeRecord(record: ProviderInstanceRecord): JsonObject = buildJsonObject {
        put("entryReference", JsonPrimitive(record.entryReference.value))
        put("instanceId", JsonPrimitive(record.instanceId.value))
        put("definitionId", JsonPrimitive(record.definitionId.value))
        put("family", JsonPrimitive(record.family.wireValue))
        put("alias", JsonPrimitive(record.alias.value))
        put("manifest", record.manifest?.let(::encodeManifest) ?: JsonNull)
        put("adapter", record.adapter?.let(::encodeAdapter) ?: JsonNull)
        put("trustReferences", strings(record.trustRecordReferences.map { it.value }))
        put("calibrationReferences", strings(record.calibrationProfileReferences.map { it.value }))
        put("identityBindings", strings(record.protectedIdentityBindings.map { it.value }))
        put("firstSeenAt", JsonPrimitive(record.firstSeenAt.epochMilliseconds))
        put("lastSeenAt", JsonPrimitive(record.lastSeenAt.epochMilliseconds))
        put("lastSuccessfulUseAt", record.lastSuccessfulUseAt?.let { JsonPrimitive(it.epochMilliseconds) } ?: JsonNull)
        put("recordRevision", JsonPrimitive(record.revision.value))
        put("provenanceSource", JsonPrimitive(record.provenance.source.name))
        put("provenanceReference", JsonPrimitive(record.provenance.reference.value))
        put("status", JsonPrimitive(record.status.name))
    }

    private fun encodeManifest(value: StoredManifestReference) = buildJsonObject {
        put("definitionId", JsonPrimitive(value.providerDefinitionId.value))
        put("schemaVersion", JsonPrimitive(value.schemaVersion.wireValue))
        put("manifestVersion", JsonPrimitive(value.manifestVersion.wireValue))
        put("digest", JsonPrimitive(value.digest.value))
        put("specialization", value.specialization?.let { JsonPrimitive(it.name) } ?: JsonNull)
    }

    private fun encodeAdapter(value: StoredAdapterReference) = buildJsonObject {
        put("id", JsonPrimitive(value.id.wireValue))
        put("version", JsonPrimitive(value.version.wireValue))
    }

    private fun decodeRecord(root: JsonObject): ProviderInstanceRecord {
        if (root.keys != recordFields) recordFail(ProviderInstanceStoreError.INVALID_RECORD_SHAPE)
        val manifest = root.optionalObject("manifest")?.let {
            if (it.keys != manifestFields) recordFail(ProviderInstanceStoreError.INVALID_MANIFEST_REFERENCE)
            StoredManifestReference(
                providerIdentitySuccess(ProviderDefinitionId.parse(it.string("definitionId"))),
                manifestSuccess(ManifestSchemaVersion.parse(it.string("schemaVersion"))),
                manifestSuccess(ManifestVersion.parse(it.string("manifestVersion"))),
                manifestSuccess(ManifestDigest.parse(it.string("digest"))),
                it.optionalString("specialization")?.let { raw -> enumValueOf<SpecializedManifestKind>(raw) },
            )
        }
        val adapter = root.optionalObject("adapter")?.let {
            if (it.keys != adapterFields) recordFail(ProviderInstanceStoreError.INVALID_ADAPTER_REFERENCE)
            StoredAdapterReference(
                adapterSuccess(AdapterId.parse(it.string("id"))),
                adapterSuccess(AdapterVersion.parse(it.string("version"))),
            )
        }
        val record = ProviderInstanceRecord.create(
            storeSuccess(ProviderStoreEntryReference.parse(root.string("entryReference"))),
            providerIdentitySuccess(ProviderInstanceId.parse(root.string("instanceId"))),
            providerIdentitySuccess(ProviderDefinitionId.parse(root.string("definitionId"))),
            ProviderFamily.fromWireValue(root.string("family")) ?: recordFail(ProviderInstanceStoreError.CONFLICTING_DEFINITION_BINDING),
            providerIdentitySuccess(RedactedProviderAlias.parse(root.string("alias"))),
            manifest,
            adapter,
            root.strings("trustReferences").map { trustSuccess(TrustRecordId.parse(it)) }.toSet(),
            root.strings("calibrationReferences").map { calibrationSuccess(CalibrationProfileId.parse(it)) }.toSet(),
            root.strings("identityBindings").map {
                identityEvidenceSuccess(ProtectedIdentityEvidenceReference.parse(it))
            }.toSet(),
            storeSuccess(ProviderStoreTimestamp.create(root.long("firstSeenAt"))),
            storeSuccess(ProviderStoreTimestamp.create(root.long("lastSeenAt"))),
            root.optionalLong("lastSuccessfulUseAt")?.let { storeSuccess(ProviderStoreTimestamp.create(it)) },
            storeSuccess(ProviderInstanceRecordRevision.create(root.long("recordRevision"))),
            ProviderInstanceProvenance(
                enumValueOf(root.string("provenanceSource")),
                storeSuccess(ProviderConfigurationReference.parse(root.string("provenanceReference"))),
            ),
            enumValueOf(root.string("status")),
        )
        return when (record) {
            is ProviderInstanceStoreResult.Success -> record.value
            is ProviderInstanceStoreResult.Failure -> recordFail(record.error)
        }
    }

    private fun strings(values: List<String>) = buildJsonArray { values.sorted().forEach { add(JsonPrimitive(it)) } }
    private fun fail(error: ProviderInstancePersistenceError): Nothing = throw DecodeFailure(error)
    private fun recordFail(error: ProviderInstanceStoreError): Nothing = throw RecordFailure(error)
    private class DecodeFailure(val error: ProviderInstancePersistenceError) : RuntimeException()
    private class RecordFailure(val reason: ProviderInstanceStoreError) : RuntimeException()
}

internal sealed interface SnapshotDecodeResult {
    data class Success(val value: ProviderInstanceStoreSnapshot) : SnapshotDecodeResult
    data class Failure(val error: ProviderInstancePersistenceError) : SnapshotDecodeResult
}

private fun JsonObject.string(name: String): String = (get(name) as? JsonPrimitive)
    ?.takeIf { it.isString }?.content ?: error("Invalid $name")
private fun JsonObject.int(name: String): Int = (get(name) as? JsonPrimitive)?.content?.toIntOrNull()
    ?: error("Invalid $name")
private fun JsonObject.long(name: String): Long = (get(name) as? JsonPrimitive)?.content?.toLongOrNull()
    ?: error("Invalid $name")
private fun JsonObject.optionalLong(name: String): Long? = when (val value = get(name)) {
    JsonNull -> null
    is JsonPrimitive -> value.content.toLongOrNull() ?: error("Invalid $name")
    else -> error("Invalid $name")
}
private fun JsonObject.optionalString(name: String): String? = when (val value = get(name)) {
    JsonNull -> null
    is JsonPrimitive -> value.takeIf { it.isString }?.content ?: error("Invalid $name")
    else -> error("Invalid $name")
}
private fun JsonObject.optionalObject(name: String): JsonObject? = when (val value = get(name)) {
    JsonNull -> null
    is JsonObject -> value
    else -> error("Invalid $name")
}
private fun JsonObject.array(name: String): JsonArray = get(name) as? JsonArray ?: error("Invalid $name")
private fun JsonObject.strings(name: String): List<String> = array(name).map {
    (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: error("Invalid $name")
}

private fun <T> storeSuccess(result: ProviderInstanceStoreResult<T>): T = when (result) {
    is ProviderInstanceStoreResult.Success -> result.value
    is ProviderInstanceStoreResult.Failure -> throw IllegalArgumentException(result.error.name)
}
private fun <T> providerIdentitySuccess(result: ProviderIdentityResult<T>): T =
    (result as ProviderIdentityResult.Success).value
private fun <T> manifestSuccess(result: CommonManifestResult<T>): T = (result as CommonManifestResult.Success).value
private fun <T> adapterSuccess(result: AdapterResult<T>): T = (result as AdapterResult.Success).value
private fun <T> trustSuccess(result: TrustDomainResult<T>): T = (result as TrustDomainResult.Success).value
private fun <T> calibrationSuccess(result: CalibrationDomainResult<T>): T =
    (result as CalibrationDomainResult.Success).value
private fun <T> identityEvidenceSuccess(result: IdentityEvidenceResult<T>): T =
    (result as IdentityEvidenceResult.Success).value
private fun <T> protectionSuccess(result: ProviderProtectionResult<T>): T =
    (result as ProviderProtectionResult.Success).value

internal const val AUTHENTICATED_ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding"
private const val ENVELOPE_SCHEMA = "1.0"
