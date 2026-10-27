package io.redi.robot.platform.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.redi.robot.provider.storage.AUTHENTICATED_ENCRYPTION_ALGORITHM
import io.redi.robot.provider.storage.ProtectedProviderInstanceStorage
import io.redi.robot.provider.storage.ProtectedProviderStorePayload
import io.redi.robot.provider.storage.ProviderInstanceProtection
import io.redi.robot.provider.storage.ProviderProtectionError
import io.redi.robot.provider.storage.ProviderProtectionResult
import io.redi.robot.provider.storage.ProviderStoreKeyVersion
import io.redi.robot.provider.storage.ProviderStoreStorageReadResult
import io.redi.robot.provider.storage.ProviderStoreStorageResetResult
import io.redi.robot.provider.storage.ProviderStoreStorageSlot
import io.redi.robot.provider.storage.ProviderStoreStorageWriteResult
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore AES-256-GCM implementation. No key material leaves the platform boundary. */
class AndroidKeystoreProviderInstanceProtection(
    private val keyVersion: ProviderStoreKeyVersion,
) : ProviderInstanceProtection {
    private val alias: String = "hugo.provider-instance-store.v${keyVersion.value}"

    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ProviderProtectionResult<ProtectedProviderStorePayload> = try {
        val cipher = Cipher.getInstance(AUTHENTICATED_ENCRYPTION_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        cipher.updateAAD(associatedData)
        ProviderProtectionResult.Success(
            ProtectedProviderStorePayload(
                AUTHENTICATED_ENCRYPTION_ALGORITHM,
                keyVersion,
                cipher.iv,
                cipher.doFinal(plaintext),
            ),
        )
    } catch (_: Exception) {
        ProviderProtectionResult.Failure(ProviderProtectionError.ENCRYPTION_FAILED)
    }

    override fun decrypt(
        payload: ProtectedProviderStorePayload,
        associatedData: ByteArray,
    ): ProviderProtectionResult<ByteArray> {
        if (payload.keyVersion != keyVersion) {
            return ProviderProtectionResult.Failure(ProviderProtectionError.KEY_UNAVAILABLE)
        }
        val key = try {
            existingKey()
        } catch (_: Exception) {
            return ProviderProtectionResult.Failure(ProviderProtectionError.KEY_UNAVAILABLE)
        } ?: return ProviderProtectionResult.Failure(ProviderProtectionError.KEY_LOST)
        return try {
            val cipher = Cipher.getInstance(AUTHENTICATED_ENCRYPTION_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, payload.nonce))
            cipher.updateAAD(associatedData)
            ProviderProtectionResult.Success(cipher.doFinal(payload.ciphertext))
        } catch (_: AEADBadTagException) {
            ProviderProtectionResult.Failure(ProviderProtectionError.AUTHENTICATION_FAILED)
        } catch (_: Exception) {
            ProviderProtectionResult.Failure(ProviderProtectionError.DECRYPTION_FAILED)
        }
    }

    /** Explicit destructive recovery for an operator-approved store reset after irrecoverable key loss. */
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

/** Atomic same-directory primary/backup storage under an application-private directory. */
class AtomicFileProviderInstanceStorage(directory: File) : ProtectedProviderInstanceStorage {
    private val directory = directory.absoluteFile
    private val primary = File(this.directory, "provider-instances.enc")
    private val backup = File(this.directory, "provider-instances.backup.enc")
    private val pending = File(this.directory, "provider-instances.pending")
    private val rollback = File(this.directory, "provider-instances.rollback")

    override fun read(slot: ProviderStoreStorageSlot): ProviderStoreStorageReadResult {
        val file = when (slot) {
            ProviderStoreStorageSlot.PRIMARY -> primary
            ProviderStoreStorageSlot.BACKUP -> if (rollback.exists()) rollback else backup
        }
        if (!file.exists()) return ProviderStoreStorageReadResult.Missing
        if (!file.isFile || file.length() !in 1..MAXIMUM_BYTES) return ProviderStoreStorageReadResult.Failed
        return runCatching { ProviderStoreStorageReadResult.Content(file.readBytes()) }
            .getOrElse { ProviderStoreStorageReadResult.Failed }
    }

    override fun writeAtomically(bytes: ByteArray): ProviderStoreStorageWriteResult = write(bytes, false)

    override fun repairPrimaryAtomically(bytes: ByteArray): ProviderStoreStorageWriteResult = write(bytes, true)

    private fun write(bytes: ByteArray, preserveBackup: Boolean): ProviderStoreStorageWriteResult {
        if (bytes.isEmpty() || bytes.size > MAXIMUM_BYTES) {
            return ProviderStoreStorageWriteResult.FailedPreservingPrevious
        }
        return try {
            Files.createDirectories(directory.toPath())
            FileOutputStream(pending, false).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (rollback.exists() && primary.exists()) atomicMove(rollback, backup)
            var movedPrimary = false
            if (primary.exists()) {
                atomicMove(primary, rollback)
                movedPrimary = true
            }
            try {
                atomicMove(pending, primary)
            } catch (failure: Exception) {
                if (movedPrimary && !primary.exists() && rollback.exists()) {
                    runCatching { atomicMove(rollback, primary) }
                }
                throw failure
            }
            if (rollback.exists()) {
                if (preserveBackup) Files.deleteIfExists(rollback.toPath()) else atomicMove(rollback, backup)
            }
            ProviderStoreStorageWriteResult.Committed
        } catch (_: Exception) {
            runCatching { Files.deleteIfExists(pending.toPath()) }
            ProviderStoreStorageWriteResult.FailedPreservingPrevious
        }
    }

    override fun reset(): ProviderStoreStorageResetResult = try {
        Files.deleteIfExists(pending.toPath())
        Files.deleteIfExists(rollback.toPath())
        Files.deleteIfExists(primary.toPath())
        Files.deleteIfExists(backup.toPath())
        ProviderStoreStorageResetResult.Reset
    } catch (_: Exception) {
        ProviderStoreStorageResetResult.Failed
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
        private const val MAXIMUM_BYTES = 4 * 1024 * 1024
    }
}
