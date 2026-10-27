package io.redi.robot.platform.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.redi.robot.memory.domain.MemoryRecordId
import io.redi.robot.memory.storage.MemoryProtectionResult
import io.redi.robot.memory.storage.MemoryProtectionError
import io.redi.robot.memory.storage.MemoryRecordProtection
import io.redi.robot.memory.storage.MemoryRecordSlots
import io.redi.robot.memory.storage.MemorySlotFailure
import io.redi.robot.memory.storage.MemorySlotListResult
import io.redi.robot.memory.storage.MemoryJournalFailure
import io.redi.robot.memory.storage.MemoryJournalClearResult
import io.redi.robot.memory.storage.MemoryJournalReadResult
import io.redi.robot.memory.storage.MemoryJournalWriteResult
import io.redi.robot.memory.storage.MemorySlotReadResult
import io.redi.robot.memory.storage.MemorySlotResetResult
import io.redi.robot.memory.storage.MemorySlotWriteResult
import io.redi.robot.memory.storage.MemoryStoreKeyVersion
import io.redi.robot.memory.storage.AUTHENTICATED_ENCRYPTION_ALGORITHM
import io.redi.robot.memory.storage.GCM_NONCE_BYTES
import io.redi.robot.memory.storage.ProtectedMemoryRecordPayload
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore AES-256-GCM protection for the memory record store (H059). The non-exportable
 * key never leaves the platform boundary; the alias is versioned per the governed H032/H052
 * key-management architecture. No plaintext memory content and no key material is logged.
 */
class AndroidKeystoreMemoryRecordProtection(
    private val keyVersion: MemoryStoreKeyVersion,
) : MemoryRecordProtection {
    private val alias: String = "hugo.memory-record-store.v${keyVersion.value}"

    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): MemoryProtectionResult<ProtectedMemoryRecordPayload> = try {
        val cipher = Cipher.getInstance(AUTHENTICATED_ENCRYPTION_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        cipher.updateAAD(associatedData)
        MemoryProtectionResult.Success(
            ProtectedMemoryRecordPayload(
                AUTHENTICATED_ENCRYPTION_ALGORITHM,
                keyVersion,
                cipher.iv,
                cipher.doFinal(plaintext),
            ),
        )
    } catch (_: Exception) {
        MemoryProtectionResult.Failure(MemoryProtectionError.ENCRYPTION_FAILED)
    }

    override fun decrypt(
        payload: ProtectedMemoryRecordPayload,
        associatedData: ByteArray,
    ): MemoryProtectionResult<ByteArray> {
        if (payload.keyVersion != keyVersion) {
            return MemoryProtectionResult.Failure(MemoryProtectionError.KEY_UNAVAILABLE)
        }
        val key = try {
            existingKey()
        } catch (_: Exception) {
            return MemoryProtectionResult.Failure(MemoryProtectionError.KEY_UNAVAILABLE)
        } ?: return MemoryProtectionResult.Failure(MemoryProtectionError.KEY_LOST)
        return try {
            val cipher = Cipher.getInstance(AUTHENTICATED_ENCRYPTION_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, payload.nonce))
            cipher.updateAAD(associatedData)
            MemoryProtectionResult.Success(cipher.doFinal(payload.ciphertext))
        } catch (_: AEADBadTagException) {
            MemoryProtectionResult.Failure(MemoryProtectionError.AUTHENTICATION_FAILED)
        } catch (_: Exception) {
            MemoryProtectionResult.Failure(MemoryProtectionError.DECRYPTION_FAILED)
        }
    }

    /** Explicit destructive recovery for an operator-approved reset after irrecoverable key loss. */
    fun deleteKeyForExplicitReset(): Boolean = runCatching {
        keyStore().deleteEntry(alias)
        true
    }.getOrDefault(false)

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val GCM_TAG_BITS = 128
    }
}

/**
 * Production durability contract for the memory redo protocol. The implementation uses
 * FileDescriptor.sync() for file contents and a real FileChannel.force(true) on the containing
 * application-private directory — the supported directory-durability primitive on
 * Android/Linux. There is deliberately no fallback: where the platform cannot provide the
 * primitive (JVMs that cannot open directories, e.g. Windows), syncDirectory throws and every
 * durable journal/slot operation fails closed — durable capability is unavailable there rather
 * than weakened.
 */
interface MemoryFileDurability {
    fun syncFile(output: FileOutputStream)
    fun syncDirectory(directory: File)
}

object PlatformMemoryFileDurability : MemoryFileDurability {
    override fun syncFile(output: FileOutputStream) {
        output.fd.sync()
    }

    override fun syncDirectory(directory: File) {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }
}

/** Test-only fault boundary also documents the durable ordering stages. */
enum class MemoryDurabilityStage {
    RECORD_PENDING_FILE_SYNCED,
    RECORD_RENAMED,
    RECORD_DIRECTORY_SYNCED,
    JOURNAL_PENDING_FILE_SYNCED,
    JOURNAL_RENAMED,
    JOURNAL_DIRECTORY_SYNCED,
    JOURNAL_DELETED,
    JOURNAL_DELETE_DIRECTORY_SYNCED,
}

fun interface MemoryDurabilityFaultInjector {
    fun after(stage: MemoryDurabilityStage)
}

/**
 * Durable per-record ciphertext slots. Written is returned only after:
 * pending file write -> file sync -> rename/replace -> directory sync.
 *
 * The repository supports one MemoryEnvelopeStore writer instance in one process per memory
 * directory. Cross-process/multi-instance writer coordination is deliberately not claimed.
 */
class AtomicFileMemoryRecordSlots(
    directory: File,
    private val durability: MemoryFileDurability = PlatformMemoryFileDurability,
    private val faultInjector: MemoryDurabilityFaultInjector = MemoryDurabilityFaultInjector { },
) : MemoryRecordSlots {
    private val directory = directory.absoluteFile

    init {
        directory.mkdirs()
    }

    override fun read(recordId: MemoryRecordId): MemorySlotReadResult {
        val file = slotFile(recordId)
            ?: return MemorySlotReadResult.Failed(MemorySlotFailure.CORRUPT_SLOT_NAME)
        return if (!file.isFile) {
            MemorySlotReadResult.Missing
        } else {
            try {
                MemorySlotReadResult.Content(Files.readAllBytes(file.toPath()))
            } catch (_: Exception) {
                MemorySlotReadResult.Failed(MemorySlotFailure.IO_FAILED)
            }
        }
    }

    override fun writeAtomically(recordId: MemoryRecordId, bytes: ByteArray): MemorySlotWriteResult {
        if (bytes.size > MAXIMUM_SLOT_BYTES) {
            return MemorySlotWriteResult.Failed(MemorySlotFailure.OVERSIZED_SLOT)
        }
        val file = slotFile(recordId)
            ?: return MemorySlotWriteResult.Failed(MemorySlotFailure.CORRUPT_SLOT_NAME)
        val pending = File(directory, "${file.name}.pending")
        return try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.flush()
                durability.syncFile(output)
            }
            faultInjector.after(MemoryDurabilityStage.RECORD_PENDING_FILE_SYNCED)
            moveReplacing(pending, file)
            faultInjector.after(MemoryDurabilityStage.RECORD_RENAMED)
            durability.syncDirectory(directory)
            faultInjector.after(MemoryDurabilityStage.RECORD_DIRECTORY_SYNCED)
            MemorySlotWriteResult.Written
        } catch (_: Exception) {
            cleanup(pending)
            MemorySlotWriteResult.Failed(MemorySlotFailure.IO_FAILED)
        }
    }

    override fun storedRecordIds(limit: Int, afterId: MemoryRecordId?): MemorySlotListResult {
        if (limit <= 0) return MemorySlotListResult.Failed(MemorySlotFailure.INVALID_LIMIT)
        val files = directory.listFiles { file -> file.isFile && file.name.startsWith(SLOT_PREFIX) }
            ?: return MemorySlotListResult.Failed(MemorySlotFailure.IO_FAILED)
        var ids = files.asSequence()
            .mapNotNull { slotRecordId(it.name) }
            .sortedBy { it.value }
        if (afterId != null) {
            ids = ids.dropWhile { it.value <= afterId.value }
        }
        return MemorySlotListResult.Ids(ids.take(limit).toList())
    }

    override fun reset(): MemorySlotResetResult {
        val files = directory.listFiles { file -> file.isFile && file.name.startsWith(SLOT_PREFIX) }
            ?: return MemorySlotResetResult.Failed(MemorySlotFailure.IO_FAILED)
        var purged = 0
        files.forEach { file ->
            if (runCatching { file.delete() }.getOrDefault(false)) purged++
        }
        return MemorySlotResetResult.Reset(purged)
    }

    private fun slotFile(recordId: MemoryRecordId): File? =
        if (SLOT_RECORD_ID.matches(recordId.value)) {
            File(directory, "$SLOT_PREFIX${recordId.value}$SLOT_SUFFIX")
        } else {
            null
        }

    private fun slotRecordId(fileName: String): MemoryRecordId? {
        if (!fileName.startsWith(SLOT_PREFIX) || !fileName.endsWith(SLOT_SUFFIX)) return null
        val raw = fileName.removePrefix(SLOT_PREFIX).removeSuffix(SLOT_SUFFIX)
        return (MemoryRecordId.parse(raw) as? io.redi.robot.memory.domain.MemoryDomainResult.Success)?.value
    }

    private fun moveReplacing(pending: File, target: File) {
        try {
            Files.move(
                pending.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun cleanup(pending: File) {
        runCatching { pending.delete() }
    }

    companion object {
        private const val SLOT_PREFIX = "memory-record-"
        private const val SLOT_SUFFIX = ".enc"
        private val SLOT_RECORD_ID =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        const val MAXIMUM_SLOT_BYTES = 64 * 1024
    }
}

/**
 * Durable single-file redo journal. Written means:
 * pending write -> file sync -> rename/replace -> directory sync.
 *
 * Cleared means the live journal path and the removal tombstone no longer exist and the
 * containing directory has been synchronized. A failed delete, a still-existing path, or a
 * directory-sync failure never reports Cleared.
 *
 * Removal is two-phase and crash-verifiable: the live journal is first renamed to a removal
 * tombstone and the directory is synced (the removal intent itself becomes durable), then the
 * tombstone is unlinked and the directory is synced again. Correctness therefore never depends
 * on in-memory state — there are no process-local flags.
 *
 * Restart durability barrier, stateless: every readIntent() that finds a live journal first
 * re-establishes the directory durability barrier (sync) before returning any bytes, so a
 * fresh instance that finds a pending journal — however it got there, including a crash after
 * the rename but before the write-side directory sync — re-establishes durability BEFORE the
 * store can run any replay slot write. If the barrier cannot be established, readIntent fails
 * closed and the store writes nothing. When only a removal tombstone is found, readIntent
 * first completes the removal durably (barrier sync, unlink, final sync) and reports Empty;
 * any failure there also fails closed. This keeps the protocol ordering
 * A. durable journal -> B. durable slot writes -> C. durable journal removal
 * -> D. later unrelated operations across a restart between any stages.
 *
 * The repository supports one MemoryEnvelopeStore writer instance in one process per memory
 * directory. Cross-process/multi-instance writer coordination is deliberately not claimed.
 */
class AtomicFileMemoryChangeSetJournal(
    directory: File,
    private val durability: MemoryFileDurability = PlatformMemoryFileDurability,
    private val faultInjector: MemoryDurabilityFaultInjector = MemoryDurabilityFaultInjector { },
    private val deleteJournalFile: (File) -> Boolean = { it.delete() },
) : io.redi.robot.memory.storage.MemoryChangeSetJournal {
    private val directory = directory.absoluteFile
    private val journal = File(this.directory, JOURNAL_FILE_NAME)
    private val removing = File(this.directory, "$JOURNAL_FILE_NAME$REMOVING_SUFFIX")

    init {
        directory.mkdirs()
    }

    @Synchronized
    override fun writeIntent(bytes: ByteArray): io.redi.robot.memory.storage.MemoryJournalWriteResult {
        if (bytes.size > MAXIMUM_JOURNAL_BYTES) {
            return io.redi.robot.memory.storage.MemoryJournalWriteResult.Failed(
                MemoryJournalFailure.OVERSIZED_JOURNAL,
            )
        }
        if (journal.exists() || removing.exists()) {
            // A pending journal or an unfinished removal must be resolved by readIntent()
            // before a new intent may be durably established.
            return io.redi.robot.memory.storage.MemoryJournalWriteResult.Failed(
                MemoryJournalFailure.IO_FAILED,
            )
        }

        val pending = File(directory, "$JOURNAL_FILE_NAME.pending")
        return try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.flush()
                durability.syncFile(output)
            }
            faultInjector.after(MemoryDurabilityStage.JOURNAL_PENDING_FILE_SYNCED)
            moveReplacing(pending, journal)
            faultInjector.after(MemoryDurabilityStage.JOURNAL_RENAMED)
            durability.syncDirectory(directory)
            faultInjector.after(MemoryDurabilityStage.JOURNAL_DIRECTORY_SYNCED)
            io.redi.robot.memory.storage.MemoryJournalWriteResult.Written
        } catch (_: Exception) {
            runCatching { pending.delete() }
            io.redi.robot.memory.storage.MemoryJournalWriteResult.Failed(MemoryJournalFailure.IO_FAILED)
        }
    }

    @Synchronized
    override fun readIntent(): io.redi.robot.memory.storage.MemoryJournalReadResult {
        if (journal.exists()) {
            if (!journal.isFile) {
                return io.redi.robot.memory.storage.MemoryJournalReadResult.Failed(
                    MemoryJournalFailure.IO_FAILED,
                )
            }
            if (removing.exists()) {
                return io.redi.robot.memory.storage.MemoryJournalReadResult.Failed(
                    MemoryJournalFailure.IO_FAILED,
                )
            }
            // Restart durability barrier: make the pending journal's directory entry durable
            // BEFORE its bytes may feed any replay slot write. A failure here fails closed.
            return try {
                durability.syncDirectory(directory)
                val length = journal.length()
                if (length > MAXIMUM_JOURNAL_BYTES) {
                    io.redi.robot.memory.storage.MemoryJournalReadResult.Failed(
                        MemoryJournalFailure.OVERSIZED_JOURNAL,
                    )
                } else {
                    val bytes = Files.readAllBytes(journal.toPath())
                    if (bytes.size > MAXIMUM_JOURNAL_BYTES) {
                        io.redi.robot.memory.storage.MemoryJournalReadResult.Failed(
                            MemoryJournalFailure.OVERSIZED_JOURNAL,
                        )
                    } else {
                        io.redi.robot.memory.storage.MemoryJournalReadResult.Content(bytes)
                    }
                }
            } catch (_: Exception) {
                io.redi.robot.memory.storage.MemoryJournalReadResult.Failed(MemoryJournalFailure.IO_FAILED)
            }
        }
        if (removing.exists()) {
            // A removal is in progress (crash or failure during clear). Finish it durably
            // before reporting Empty: barrier sync, unlink, final directory sync.
            return try {
                durability.syncDirectory(directory)
                faultInjector.after(MemoryDurabilityStage.JOURNAL_DELETED)
                if (!deleteJournalFile(removing)) {
                    return io.redi.robot.memory.storage.MemoryJournalReadResult.Failed(
                        MemoryJournalFailure.IO_FAILED,
                    )
                }
                if (removing.exists()) {
                    return io.redi.robot.memory.storage.MemoryJournalReadResult.Failed(
                        MemoryJournalFailure.IO_FAILED,
                    )
                }
                durability.syncDirectory(directory)
                faultInjector.after(MemoryDurabilityStage.JOURNAL_DELETE_DIRECTORY_SYNCED)
                if (journal.exists() || removing.exists()) {
                    io.redi.robot.memory.storage.MemoryJournalReadResult.Failed(MemoryJournalFailure.IO_FAILED)
                } else {
                    io.redi.robot.memory.storage.MemoryJournalReadResult.Empty
                }
            } catch (_: Exception) {
                io.redi.robot.memory.storage.MemoryJournalReadResult.Failed(MemoryJournalFailure.IO_FAILED)
            }
        }
        if (!directory.isDirectory) {
            return io.redi.robot.memory.storage.MemoryJournalReadResult.Failed(MemoryJournalFailure.IO_FAILED)
        }
        return io.redi.robot.memory.storage.MemoryJournalReadResult.Empty
    }

    @Synchronized
    override fun clearIntent(): io.redi.robot.memory.storage.MemoryJournalClearResult {
        if (journal.exists()) {
            if (!journal.isFile || removing.exists()) {
                return io.redi.robot.memory.storage.MemoryJournalClearResult.Failed(
                    MemoryJournalFailure.IO_FAILED,
                )
            }
            // Phase 1: make the removal intent itself durable — atomic rename to the
            // tombstone, then a directory sync.
            try {
                moveReplacing(journal, removing)
                durability.syncDirectory(directory)
            } catch (_: Exception) {
                return io.redi.robot.memory.storage.MemoryJournalClearResult.Failed(
                    MemoryJournalFailure.IO_FAILED,
                )
            }
        }
        if (!removing.exists()) {
            return io.redi.robot.memory.storage.MemoryJournalClearResult.Cleared
        }
        return try {
            // Phase 2: unlink the tombstone and make the removal final. A leftover tombstone
            // (from a crashed or failed clear) is completed here the same way.
            faultInjector.after(MemoryDurabilityStage.JOURNAL_DELETED)
            if (!deleteJournalFile(removing)) {
                return io.redi.robot.memory.storage.MemoryJournalClearResult.Failed(
                    MemoryJournalFailure.IO_FAILED,
                )
            }
            if (removing.exists()) {
                return io.redi.robot.memory.storage.MemoryJournalClearResult.Failed(
                    MemoryJournalFailure.IO_FAILED,
                )
            }
            durability.syncDirectory(directory)
            faultInjector.after(MemoryDurabilityStage.JOURNAL_DELETE_DIRECTORY_SYNCED)
            if (journal.exists() || removing.exists()) {
                io.redi.robot.memory.storage.MemoryJournalClearResult.Failed(
                    MemoryJournalFailure.IO_FAILED,
                )
            } else {
                io.redi.robot.memory.storage.MemoryJournalClearResult.Cleared
            }
        } catch (_: Exception) {
            io.redi.robot.memory.storage.MemoryJournalClearResult.Failed(MemoryJournalFailure.IO_FAILED)
        }
    }

    private fun moveReplacing(pending: File, target: File) {
        try {
            Files.move(
                pending.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val JOURNAL_FILE_NAME = "memory-changeset.journal"
        const val REMOVING_SUFFIX = ".removing"

        /** Defensive ceiling; bound to the strict codec's serialized-journal ceiling. */
        const val MAXIMUM_JOURNAL_BYTES =
            io.redi.robot.memory.storage.MemoryChangeSetJournalCodec.MAXIMUM_SERIALIZED_JOURNAL_BYTES
    }
}
