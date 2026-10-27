package io.redi.robot.platform.service.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.redi.robot.service.auth.CredentialDocumentReadResult
import io.redi.robot.service.auth.CredentialDocumentResetResult
import io.redi.robot.service.auth.CredentialDocumentWriteResult
import io.redi.robot.service.auth.ProtectedServiceCredentialStorage
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val ENVELOPE_VERSION = "1"
private const val KEY_VERSION = "1"
private const val ALGORITHM = "AES/GCM/NoPadding"
private const val ANDROID_KEY_STORE = "AndroidKeyStore"
private const val GCM_TAG_BITS = 128
private const val MAXIMUM_FILE_BYTES = 2 * 1024 * 1024L
private val ASSOCIATED_DATA =
    "io.redi.robot.service.credentials|envelope=1|key=1".toByteArray(StandardCharsets.UTF_8)

private data class CredentialEnvelope(val nonce: ByteArray, val ciphertext: ByteArray)

private sealed interface EnvelopeDecode {
    data class Success(val envelope: CredentialEnvelope) : EnvelopeDecode
    data class Unsupported(val found: String) : EnvelopeDecode
    data object Corrupt : EnvelopeDecode
}

private object CredentialEnvelopeCodec {
    private val fields = setOf("envelopeVersion", "algorithm", "keyVersion", "nonce", "ciphertext")

    fun encode(envelope: CredentialEnvelope): ByteArray = buildJsonObject {
        put("envelopeVersion", JsonPrimitive(ENVELOPE_VERSION))
        put("algorithm", JsonPrimitive(ALGORITHM))
        put("keyVersion", JsonPrimitive(KEY_VERSION))
        put("nonce", JsonPrimitive(Base64.getEncoder().encodeToString(envelope.nonce)))
        put("ciphertext", JsonPrimitive(Base64.getEncoder().encodeToString(envelope.ciphertext)))
    }.toString().toByteArray(StandardCharsets.UTF_8)

    fun decode(bytes: ByteArray): EnvelopeDecode {
        val root = runCatching {
            Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)) as? JsonObject
        }.getOrNull() ?: return EnvelopeDecode.Corrupt
        if (root.keys != fields) return EnvelopeDecode.Corrupt
        val envelopeVersion = root.string("envelopeVersion") ?: return EnvelopeDecode.Corrupt
        if (envelopeVersion != ENVELOPE_VERSION) return EnvelopeDecode.Unsupported(envelopeVersion)
        if (root.string("algorithm") != ALGORITHM) return EnvelopeDecode.Corrupt
        val keyVersion = root.string("keyVersion") ?: return EnvelopeDecode.Corrupt
        if (keyVersion != KEY_VERSION) return EnvelopeDecode.Unsupported(keyVersion)
        val nonce = root.base64("nonce") ?: return EnvelopeDecode.Corrupt
        val ciphertext = root.base64("ciphertext") ?: return EnvelopeDecode.Corrupt
        if (nonce.size != 12 || ciphertext.size !in 16..MAXIMUM_FILE_BYTES.toInt()) {
            return EnvelopeDecode.Corrupt
        }
        return EnvelopeDecode.Success(CredentialEnvelope(nonce, ciphertext))
    }

    private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content

    private fun JsonObject.base64(name: String): ByteArray? = string(name)?.let {
        runCatching { Base64.getDecoder().decode(it) }.getOrNull()
    }
}

private sealed interface CipherResult<out T> {
    data class Success<T>(val value: T) : CipherResult<T>
    data object Corrupt : CipherResult<Nothing>
    data object Unavailable : CipherResult<Nothing>
}

/** Non-exportable key owner. Credential bytes never become a String or leave this storage boundary. */
private class AndroidCredentialCipher(private val alias: String) {
    fun encrypt(plaintext: ByteArray): CipherResult<CredentialEnvelope> = try {
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        cipher.updateAAD(ASSOCIATED_DATA)
        CipherResult.Success(CredentialEnvelope(cipher.iv, cipher.doFinal(plaintext)))
    } catch (_: Exception) {
        CipherResult.Unavailable
    }

    fun decrypt(envelope: CredentialEnvelope): CipherResult<ByteArray> {
        val key = try {
            existingKey()
        } catch (_: Exception) {
            return CipherResult.Unavailable
        } ?: return CipherResult.Corrupt
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, envelope.nonce))
            cipher.updateAAD(ASSOCIATED_DATA)
            CipherResult.Success(cipher.doFinal(envelope.ciphertext))
        } catch (_: AEADBadTagException) {
            CipherResult.Corrupt
        } catch (_: Exception) {
            CipherResult.Unavailable
        }
    }

    fun deleteKey(): Boolean = runCatching {
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
}

/** Authenticated credential document storage under a caller-supplied app-private no-backup directory. */
class AndroidProtectedServiceCredentialStorage(
    directory: File,
    keyAlias: String = "hugo.service.credentials.v1",
) : ProtectedServiceCredentialStorage {
    private val directory = directory.absoluteFile
    private val primary = File(this.directory, "service-credentials.enc")
    private val pending = File(this.directory, "service-credentials.pending")
    private val lockFile = File(this.directory, "service-credentials.lock")
    private val cipher = AndroidCredentialCipher(keyAlias)

    override fun read(): CredentialDocumentReadResult = withLock(CredentialDocumentReadResult.Unavailable) {
        readUnlocked()
    }

    override fun writeAtomically(plaintext: ByteArray): CredentialDocumentWriteResult = withLock(
        CredentialDocumentWriteResult.FAILED_PRESERVING_PREVIOUS,
    ) {
        val envelope = when (val protected = cipher.encrypt(plaintext)) {
            is CipherResult.Success -> protected.value
            else -> return@withLock CredentialDocumentWriteResult.FAILED_PRESERVING_PREVIOUS
        }
        val bytes = CredentialEnvelopeCodec.encode(envelope)
        if (bytes.isEmpty() || bytes.size > MAXIMUM_FILE_BYTES) {
            return@withLock CredentialDocumentWriteResult.FAILED_PRESERVING_PREVIOUS
        }
        try {
            FileOutputStream(pending, false).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            Files.move(
                pending.toPath(),
                primary.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            CredentialDocumentWriteResult.COMMITTED
        } catch (_: Exception) {
            runCatching { Files.deleteIfExists(pending.toPath()) }
            CredentialDocumentWriteResult.FAILED_PRESERVING_PREVIOUS
        } finally {
            bytes.fill(0)
            envelope.nonce.fill(0)
            envelope.ciphertext.fill(0)
        }
    }

    override fun reset(): CredentialDocumentResetResult = withLock(CredentialDocumentResetResult.FAILED) {
        // Delete the key first: an interrupted reset cannot leave readable credential material.
        if (!cipher.deleteKey()) return@withLock CredentialDocumentResetResult.FAILED
        try {
            Files.deleteIfExists(pending.toPath())
            Files.deleteIfExists(primary.toPath())
            CredentialDocumentResetResult.RESET
        } catch (_: Exception) {
            CredentialDocumentResetResult.FAILED
        }
    }

    private fun readUnlocked(): CredentialDocumentReadResult {
        if (!primary.exists()) return CredentialDocumentReadResult.Missing
        if (!primary.isFile || primary.length() !in 1..MAXIMUM_FILE_BYTES) {
            return CredentialDocumentReadResult.Corrupt
        }
        val bytes = runCatching { primary.readBytes() }
            .getOrElse { return CredentialDocumentReadResult.Unavailable }
        return try {
            when (val decoded = CredentialEnvelopeCodec.decode(bytes)) {
                EnvelopeDecode.Corrupt -> CredentialDocumentReadResult.Corrupt
                is EnvelopeDecode.Unsupported -> CredentialDocumentReadResult.UnsupportedVersion(decoded.found)
                is EnvelopeDecode.Success -> try {
                    when (val decrypted = cipher.decrypt(decoded.envelope)) {
                        CipherResult.Corrupt -> CredentialDocumentReadResult.Corrupt
                        CipherResult.Unavailable -> CredentialDocumentReadResult.Unavailable
                        is CipherResult.Success -> CredentialDocumentReadResult.Content(decrypted.value)
                    }
                } finally {
                    decoded.envelope.nonce.fill(0)
                    decoded.envelope.ciphertext.fill(0)
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun <T> withLock(onFailure: T, action: () -> T): T = try {
        Files.createDirectories(directory.toPath())
        FileChannel.open(
            lockFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel -> channel.lock().use { action() } }
    } catch (_: Exception) {
        onFailure
    }
}
