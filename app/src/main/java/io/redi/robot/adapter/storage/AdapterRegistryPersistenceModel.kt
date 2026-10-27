package io.redi.robot.adapter.storage

import io.redi.robot.adapter.registry.AdapterRegistry
import io.redi.robot.adapter.registry.AdapterRegistryRevision

data class RegistryPersistenceSchemaVersion private constructor(
    val major: Int,
    val minor: Int,
) : Comparable<RegistryPersistenceSchemaVersion> {
    val wireValue: String = "$major.$minor"

    override fun compareTo(other: RegistryPersistenceSchemaVersion): Int = compareValuesBy(
        this,
        other,
        RegistryPersistenceSchemaVersion::major,
        RegistryPersistenceSchemaVersion::minor,
    )

    companion object {
        val CURRENT: RegistryPersistenceSchemaVersion = RegistryPersistenceSchemaVersion(1, 0)
        private val pattern = Regex("^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$")

        fun parse(raw: String): RegistryPersistenceModelResult<RegistryPersistenceSchemaVersion> {
            if (!pattern.matches(raw)) {
                return RegistryPersistenceModelResult.Failure(RegistryPersistenceModelError.INVALID_SCHEMA_VERSION)
            }
            val parts = raw.split('.').map(String::toIntOrNull)
            if (parts.any { it == null }) {
                return RegistryPersistenceModelResult.Failure(RegistryPersistenceModelError.INVALID_SCHEMA_VERSION)
            }
            return RegistryPersistenceModelResult.Success(
                RegistryPersistenceSchemaVersion(checkNotNull(parts[0]), checkNotNull(parts[1])),
            )
        }
    }
}

enum class RegistrySchemaDisposition {
    CURRENT,
    MIGRATION_REQUIRED,
    UNSUPPORTED,
}

object RegistryPersistenceSchemaPolicy {
    fun classify(version: RegistryPersistenceSchemaVersion): RegistrySchemaDisposition = when {
        version == RegistryPersistenceSchemaVersion.CURRENT -> RegistrySchemaDisposition.CURRENT
        version < RegistryPersistenceSchemaVersion.CURRENT -> RegistrySchemaDisposition.MIGRATION_REQUIRED
        else -> RegistrySchemaDisposition.UNSUPPORTED
    }
}

@JvmInline
value class RegistryMetadataDigest private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val pattern = Regex("^sha256:[0-9a-f]{64}$")

        fun parse(raw: String): RegistryPersistenceModelResult<RegistryMetadataDigest> = if (pattern.matches(raw)) {
            RegistryPersistenceModelResult.Success(RegistryMetadataDigest(raw))
        } else {
            RegistryPersistenceModelResult.Failure(RegistryPersistenceModelError.INVALID_METADATA_DIGEST)
        }

        internal fun fromHex(hex: String): RegistryMetadataDigest = RegistryMetadataDigest("sha256:$hex")
    }
}

enum class RegistryStorageSlot {
    PRIMARY,
    BACKUP,
}

sealed interface RegistryStorageReadResult {
    data class Content(val text: String) : RegistryStorageReadResult
    data object Missing : RegistryStorageReadResult
    data object Failure : RegistryStorageReadResult
}

sealed interface RegistryStorageWriteResult {
    data object Committed : RegistryStorageWriteResult
    data object FailedPreservingPrevious : RegistryStorageWriteResult
}

sealed interface RegistryStorageResetResult {
    data object Reset : RegistryStorageResetResult
    data object Failed : RegistryStorageResetResult
}

/** Must be backed by application-private storage. It never accepts paths, credentials, or runtime artifacts. */
interface ProtectedAdapterRegistryStorage {
    fun read(slot: RegistryStorageSlot): RegistryStorageReadResult
    fun writeAtomically(text: String): RegistryStorageWriteResult
    fun repairPrimaryAtomically(text: String): RegistryStorageWriteResult
    fun reset(): RegistryStorageResetResult
}

data class RegistryRestoreEvidence(
    val source: RegistryStorageSlot,
    val schemaVersion: RegistryPersistenceSchemaVersion,
    val revision: AdapterRegistryRevision,
    val metadataDigest: RegistryMetadataDigest,
    val rejectedPrimary: RegistryPersistenceError?,
)

sealed interface AdapterRegistryPersistResult {
    data class Persisted(
        val revision: AdapterRegistryRevision,
        val metadataDigest: RegistryMetadataDigest,
    ) : AdapterRegistryPersistResult

    data class Failed(val error: RegistryPersistenceError) : AdapterRegistryPersistResult
}

sealed interface AdapterRegistryRestoreResult {
    data class Restored(
        val registry: AdapterRegistry,
        val evidence: RegistryRestoreEvidence,
    ) : AdapterRegistryRestoreResult

    data class RecoveredFromBackup(
        val registry: AdapterRegistry,
        val evidence: RegistryRestoreEvidence,
    ) : AdapterRegistryRestoreResult

    data object Absent : AdapterRegistryRestoreResult

    data class Failed(
        val primaryError: RegistryPersistenceError?,
        val backupError: RegistryPersistenceError?,
    ) : AdapterRegistryRestoreResult
}

sealed interface AdapterRegistryResetResult {
    data object Reset : AdapterRegistryResetResult
    data class Failed(val error: RegistryPersistenceError) : AdapterRegistryResetResult
}

sealed interface RegistrySnapshotDecodeResult {
    data class Success(
        val snapshot: io.redi.robot.adapter.registry.AdapterRegistrySnapshot,
        val schemaVersion: RegistryPersistenceSchemaVersion,
        val metadataDigest: RegistryMetadataDigest,
    ) : RegistrySnapshotDecodeResult

    data class Failure(val error: RegistryPersistenceError) : RegistrySnapshotDecodeResult
}

sealed interface RegistryPersistenceError {
    data object STORAGE_READ_FAILED : RegistryPersistenceError
    data object ATOMIC_WRITE_FAILED : RegistryPersistenceError
    data object RESET_FAILED : RegistryPersistenceError
    data object MALFORMED_DOCUMENT : RegistryPersistenceError
    data object METADATA_DIGEST_MISMATCH : RegistryPersistenceError
    data object INVALID_REVISION : RegistryPersistenceError
    data class SCHEMA_NOT_CURRENT(
        val found: RegistryPersistenceSchemaVersion,
        val disposition: RegistrySchemaDisposition,
    ) : RegistryPersistenceError
    data class INVALID_RECORD(val index: Int, val reason: RegistryRecordError) : RegistryPersistenceError
    data class DUPLICATE_RECORD(val index: Int) : RegistryPersistenceError
    data class CONFLICTING_RECORD(val index: Int) : RegistryPersistenceError
}

enum class RegistryRecordError {
    INVALID_DESCRIPTOR,
    INVALID_DESCRIPTOR_REFERENCE,
    INVALID_ARTIFACT_REFERENCE,
    INVALID_KNOWLEDGE_STATE,
    INVALID_CONNECTION_METHOD,
    INVALID_EXTENSION_SCHEMA,
    INVALID_RECORD_METADATA,
}

sealed interface RegistryPersistenceModelResult<out T> {
    data class Success<T>(val value: T) : RegistryPersistenceModelResult<T>
    data class Failure(val error: RegistryPersistenceModelError) : RegistryPersistenceModelResult<Nothing>
}

enum class RegistryPersistenceModelError {
    INVALID_SCHEMA_VERSION,
    INVALID_METADATA_DIGEST,
}
