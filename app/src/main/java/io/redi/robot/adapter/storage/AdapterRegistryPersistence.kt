package io.redi.robot.adapter.storage

import io.redi.robot.adapter.registry.AdapterRegistry
import io.redi.robot.adapter.registry.AdapterRegistrySnapshot
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AdapterRegistryPersistence(
    private val storage: ProtectedAdapterRegistryStorage,
) {
    fun persist(registry: AdapterRegistry): AdapterRegistryPersistResult = persist(registry.snapshot())

    fun persist(snapshot: AdapterRegistrySnapshot): AdapterRegistryPersistResult {
        val encoded = AdapterRegistrySnapshotCodec.encode(snapshot)
        return persistEncoded(snapshot, encoded, storage.writeAtomically(encoded))
    }

    private fun persistEncoded(
        snapshot: AdapterRegistrySnapshot,
        encoded: String,
        result: RegistryStorageWriteResult,
    ): AdapterRegistryPersistResult = when (result) {
        RegistryStorageWriteResult.Committed -> {
            val verified = AdapterRegistrySnapshotCodec.decode(encoded) as RegistrySnapshotDecodeResult.Success
            AdapterRegistryPersistResult.Persisted(snapshot.revision, verified.metadataDigest)
        }
        RegistryStorageWriteResult.FailedPreservingPrevious ->
            AdapterRegistryPersistResult.Failed(RegistryPersistenceError.ATOMIC_WRITE_FAILED)
    }

    fun restore(): AdapterRegistryRestoreResult = when (val primary = storage.read(RegistryStorageSlot.PRIMARY)) {
        is RegistryStorageReadResult.Content -> when (val decoded = AdapterRegistrySnapshotCodec.decode(primary.text)) {
            is RegistrySnapshotDecodeResult.Success -> restored(decoded)
            is RegistrySnapshotDecodeResult.Failure -> recoverFromBackup(decoded.error)
        }
        RegistryStorageReadResult.Failure -> recoverFromBackup(RegistryPersistenceError.STORAGE_READ_FAILED)
        RegistryStorageReadResult.Missing -> when (val backup = storage.read(RegistryStorageSlot.BACKUP)) {
            RegistryStorageReadResult.Missing -> AdapterRegistryRestoreResult.Absent
            RegistryStorageReadResult.Failure -> AdapterRegistryRestoreResult.Failed(
                primaryError = null,
                backupError = RegistryPersistenceError.STORAGE_READ_FAILED,
            )
            is RegistryStorageReadResult.Content -> when (val decoded = AdapterRegistrySnapshotCodec.decode(backup.text)) {
                is RegistrySnapshotDecodeResult.Success -> recovered(decoded, null)
                is RegistrySnapshotDecodeResult.Failure -> AdapterRegistryRestoreResult.Failed(null, decoded.error)
            }
        }
    }

    /** Explicitly rewrites a recovered backup as the primary through the same atomic protocol. */
    fun commitRecovery(recovery: AdapterRegistryRestoreResult.RecoveredFromBackup): AdapterRegistryPersistResult {
        val snapshot = recovery.registry.snapshot()
        val encoded = AdapterRegistrySnapshotCodec.encode(snapshot)
        return persistEncoded(snapshot, encoded, storage.repairPrimaryAtomically(encoded))
    }

    fun reset(): AdapterRegistryResetResult = when (storage.reset()) {
        RegistryStorageResetResult.Reset -> AdapterRegistryResetResult.Reset
        RegistryStorageResetResult.Failed -> AdapterRegistryResetResult.Failed(RegistryPersistenceError.RESET_FAILED)
    }

    private fun recoverFromBackup(primaryError: RegistryPersistenceError): AdapterRegistryRestoreResult =
        when (val backup = storage.read(RegistryStorageSlot.BACKUP)) {
            RegistryStorageReadResult.Missing -> AdapterRegistryRestoreResult.Failed(primaryError, null)
            RegistryStorageReadResult.Failure -> AdapterRegistryRestoreResult.Failed(
                primaryError,
                RegistryPersistenceError.STORAGE_READ_FAILED,
            )
            is RegistryStorageReadResult.Content -> when (val decoded = AdapterRegistrySnapshotCodec.decode(backup.text)) {
                is RegistrySnapshotDecodeResult.Success -> recovered(decoded, primaryError)
                is RegistrySnapshotDecodeResult.Failure -> AdapterRegistryRestoreResult.Failed(primaryError, decoded.error)
            }
        }

    private fun restored(decoded: RegistrySnapshotDecodeResult.Success): AdapterRegistryRestoreResult.Restored =
        AdapterRegistryRestoreResult.Restored(
            AdapterRegistry.restore(decoded.snapshot),
            evidence(RegistryStorageSlot.PRIMARY, decoded, null),
        )

    private fun recovered(
        decoded: RegistrySnapshotDecodeResult.Success,
        primaryError: RegistryPersistenceError?,
    ): AdapterRegistryRestoreResult.RecoveredFromBackup = AdapterRegistryRestoreResult.RecoveredFromBackup(
        AdapterRegistry.restore(decoded.snapshot),
        evidence(RegistryStorageSlot.BACKUP, decoded, primaryError),
    )

    private fun evidence(
        source: RegistryStorageSlot,
        decoded: RegistrySnapshotDecodeResult.Success,
        rejectedPrimary: RegistryPersistenceError?,
    ): RegistryRestoreEvidence = RegistryRestoreEvidence(
        source,
        decoded.schemaVersion,
        decoded.snapshot.revision,
        decoded.metadataDigest,
        rejectedPrimary,
    )
}

/** Same-directory fsync + atomic-move storage. The supplied directory must be application-private. */
class AtomicFileAdapterRegistryStorage(
    directory: File,
) : ProtectedAdapterRegistryStorage {
    private val directory = directory.absoluteFile
    private val primary = File(this.directory, "adapter-registry.json")
    private val backup = File(this.directory, "adapter-registry.backup.json")
    private val temporary = File(this.directory, "adapter-registry.pending")
    private val rollback = File(this.directory, "adapter-registry.rollback")

    override fun read(slot: RegistryStorageSlot): RegistryStorageReadResult {
        val file = when (slot) {
            RegistryStorageSlot.PRIMARY -> primary
            RegistryStorageSlot.BACKUP -> if (rollback.exists()) rollback else backup
        }
        if (!file.exists()) return RegistryStorageReadResult.Missing
        if (!file.isFile || file.length() !in 1..MAXIMUM_SNAPSHOT_BYTES) {
            return RegistryStorageReadResult.Failure
        }
        return runCatching {
            RegistryStorageReadResult.Content(file.readText(StandardCharsets.UTF_8))
        }.getOrElse { RegistryStorageReadResult.Failure }
    }

    override fun writeAtomically(text: String): RegistryStorageWriteResult = write(text, preserveBackup = false)

    override fun repairPrimaryAtomically(text: String): RegistryStorageWriteResult = write(text, preserveBackup = true)

    private fun write(text: String, preserveBackup: Boolean): RegistryStorageWriteResult {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAXIMUM_SNAPSHOT_BYTES) {
            return RegistryStorageWriteResult.FailedPreservingPrevious
        }
        return try {
            Files.createDirectories(directory.toPath())
            FileOutputStream(temporary, false).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (rollback.exists() && primary.exists()) {
                atomicMove(rollback, backup)
            }
            var primaryMoved = false
            if (primary.exists()) {
                atomicMove(primary, rollback)
                primaryMoved = true
            }
            try {
                atomicMove(temporary, primary)
            } catch (failure: Exception) {
                if (primaryMoved && !primary.exists() && rollback.exists()) {
                    runCatching { atomicMove(rollback, primary) }
                }
                throw failure
            }
            if (rollback.exists()) {
                if (preserveBackup) {
                    runCatching { Files.deleteIfExists(rollback.toPath()) }
                } else {
                    runCatching { atomicMove(rollback, backup) }
                }
            }
            RegistryStorageWriteResult.Committed
        } catch (_: Exception) {
            runCatching { Files.deleteIfExists(temporary.toPath()) }
            RegistryStorageWriteResult.FailedPreservingPrevious
        }
    }

    override fun reset(): RegistryStorageResetResult = try {
        Files.deleteIfExists(temporary.toPath())
        Files.deleteIfExists(rollback.toPath())
        Files.deleteIfExists(primary.toPath())
        Files.deleteIfExists(backup.toPath())
        RegistryStorageResetResult.Reset
    } catch (_: Exception) {
        RegistryStorageResetResult.Failed
    }

    private fun atomicMove(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (failure: AtomicMoveNotSupportedException) {
            throw failure
        }
    }

    companion object {
        private const val MAXIMUM_SNAPSHOT_BYTES: Long = 4L * 1024L * 1024L
    }
}
