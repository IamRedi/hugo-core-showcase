package io.redi.robot.platform.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.redi.robot.firstlife.identity.HugoIdentityBootstrapStorage
import io.redi.robot.firstlife.identity.HugoIdentityStorageCreateResult
import io.redi.robot.firstlife.identity.HugoIdentityStorageReadResult
import io.redi.robot.firstlife.identity.HugoIdentityStorageResetResult
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

private const val IDENTITY_ENVELOPE_VERSION = "1"
private const val IDENTITY_KEY_VERSION = "1"
private const val IDENTITY_ALGORITHM = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val MAXIMUM_IDENTITY_FILE_BYTES = 16 * 1024L
private const val ANDROID_KEY_STORE = "AndroidKeyStore"
private val IDENTITY_ASSOCIATED_DATA =
    "io.redi.robot.firstlife.hugo-identity|envelope=1|key=1".toByteArray(StandardCharsets.UTF_8)

private data class AuthenticatedIdentityEnvelope(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

private sealed interface IdentityEnvelopeDecodeResult {
    data class Success(val envelope: AuthenticatedIdentityEnvelope) : IdentityEnvelopeDecodeResult
    data class Unsupported(val found: String) : IdentityEnvelopeDecodeResult
    data object Corrupt : IdentityEnvelopeDecodeResult
}

private object AuthenticatedIdentityEnvelopeCodec {
    private val fields = setOf("envelopeVersion", "algorithm", "keyVersion", "nonce", "ciphertext")

    fun encode(envelope: AuthenticatedIdentityEnvelope): String = buildJsonObject {
        put("envelopeVersion", JsonPrimitive(IDENTITY_ENVELOPE_VERSION))
        put("algorithm", JsonPrimitive(IDENTITY_ALGORITHM))
        put("keyVersion", JsonPrimitive(IDENTITY_KEY_VERSION))
        put("nonce", JsonPrimitive(Base64.getEncoder().encodeToString(envelope.nonce)))
        put("ciphertext", JsonPrimitive(Base64.getEncoder().encodeToString(envelope.ciphertext)))
    }.toString()

    fun decode(text: String): IdentityEnvelopeDecodeResult {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return IdentityEnvelopeDecodeResult.Corrupt
        if (root.keys != fields) return IdentityEnvelopeDecodeResult.Corrupt
        val envelopeVersion = root.string("envelopeVersion") ?: return IdentityEnvelopeDecodeResult.Corrupt
        if (envelopeVersion != IDENTITY_ENVELOPE_VERSION) {
            return IdentityEnvelopeDecodeResult.Unsupported(envelopeVersion)
        }
        if (root.string("algorithm") != IDENTITY_ALGORITHM) return IdentityEnvelopeDecodeResult.Corrupt
        val keyVersion = root.string("keyVersion") ?: return IdentityEnvelopeDecodeResult.Corrupt
        if (keyVersion != IDENTITY_KEY_VERSION) return IdentityEnvelopeDecodeResult.Unsupported(keyVersion)
        val nonce = root.base64("nonce") ?: return IdentityEnvelopeDecodeResult.Corrupt
        val ciphertext = root.base64("ciphertext") ?: return IdentityEnvelopeDecodeResult.Corrupt
        if (nonce.size != 12 || ciphertext.size !in 16..MAXIMUM_IDENTITY_FILE_BYTES.toInt()) {
            return IdentityEnvelopeDecodeResult.Corrupt
        }
        return IdentityEnvelopeDecodeResult.Success(AuthenticatedIdentityEnvelope(nonce, ciphertext))
    }

    private fun JsonObject.string(name: String): String? {
        val primitive = get(name) as? JsonPrimitive ?: return null
        return primitive.content.takeIf { primitive.isString }
    }

    private fun JsonObject.base64(name: String): ByteArray? =
        string(name)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
}

private sealed interface IdentityCipherResult<out T> {
    data class Success<T>(val value: T) : IdentityCipherResult<T>
    data object Corrupt : IdentityCipherResult<Nothing>
    data object Unavailable : IdentityCipherResult<Nothing>
}

/** Key material never leaves Android Keystore. */
private class AndroidKeystoreHugoIdentityCipher(private val alias: String) {
    fun encrypt(plaintext: ByteArray): IdentityCipherResult<AuthenticatedIdentityEnvelope> = try {
        val cipher = Cipher.getInstance(IDENTITY_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        cipher.updateAAD(IDENTITY_ASSOCIATED_DATA)
        IdentityCipherResult.Success(AuthenticatedIdentityEnvelope(cipher.iv, cipher.doFinal(plaintext)))
    } catch (_: Exception) {
        IdentityCipherResult.Unavailable
    }

    fun decrypt(envelope: AuthenticatedIdentityEnvelope): IdentityCipherResult<ByteArray> {
        val key = try {
            existingKey()
        } catch (_: Exception) {
            return IdentityCipherResult.Unavailable
        } ?: return IdentityCipherResult.Corrupt
        return try {
            val cipher = Cipher.getInstance(IDENTITY_ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, envelope.nonce))
            cipher.updateAAD(IDENTITY_ASSOCIATED_DATA)
            IdentityCipherResult.Success(cipher.doFinal(envelope.ciphertext))
        } catch (_: AEADBadTagException) {
            IdentityCipherResult.Corrupt
        } catch (_: Exception) {
            IdentityCipherResult.Unavailable
        }
    }

    /** Called only by the explicit reset flow. Deleting first makes any reset failure fail closed. */
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

/**
 * Authenticated, create-once identity storage. Callers pass an application-private no-backup
 * directory; no Context or other Android type crosses the First Life identity boundary.
 */
class AndroidHugoIdentityStorage(
    directory: File,
    keyAlias: String = "hugo.first-life.identity.v1",
) : HugoIdentityBootstrapStorage {
    private val directory = directory.absoluteFile
    private val primary = File(this.directory, "hugo-identity.enc")
    private val pending = File(this.directory, "hugo-identity.pending")
    private val lockFile = File(this.directory, "hugo-identity.lock")
    private val cipher = AndroidKeystoreHugoIdentityCipher(keyAlias)

    override fun read(): HugoIdentityStorageReadResult = withLock(
        onFailure = HugoIdentityStorageReadResult.Unavailable,
    ) { readUnlocked() }

    override fun createIfAbsent(encodedIdentity: String): HugoIdentityStorageCreateResult = withLock(
        onFailure = HugoIdentityStorageCreateResult.Unavailable,
    ) {
        if (primary.exists()) return@withLock HugoIdentityStorageCreateResult.Existing(readUnlocked())
        val encrypted = when (val result = cipher.encrypt(encodedIdentity.toByteArray(StandardCharsets.UTF_8))) {
            is IdentityCipherResult.Success -> result.value
            else -> return@withLock HugoIdentityStorageCreateResult.Unavailable
        }
        val bytes = AuthenticatedIdentityEnvelopeCodec.encode(encrypted).toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAXIMUM_IDENTITY_FILE_BYTES) {
            return@withLock HugoIdentityStorageCreateResult.Unavailable
        }
        try {
            FileOutputStream(pending, false).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            Files.move(pending.toPath(), primary.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            runCatching { Files.deleteIfExists(pending.toPath()) }
            return@withLock HugoIdentityStorageCreateResult.Unavailable
        }
        when (val verified = readUnlocked()) {
            is HugoIdentityStorageReadResult.Content -> if (verified.encodedIdentity == encodedIdentity) {
                HugoIdentityStorageCreateResult.Created
            } else {
                HugoIdentityStorageCreateResult.Existing(HugoIdentityStorageReadResult.Corrupt)
            }
            else -> HugoIdentityStorageCreateResult.Existing(verified)
        }
    }

    override fun reset(): HugoIdentityStorageResetResult = withLock(
        onFailure = HugoIdentityStorageResetResult.FAILED,
    ) {
        if (!cipher.deleteKey()) return@withLock HugoIdentityStorageResetResult.FAILED
        try {
            Files.deleteIfExists(pending.toPath())
            Files.deleteIfExists(primary.toPath())
            HugoIdentityStorageResetResult.RESET
        } catch (_: Exception) {
            HugoIdentityStorageResetResult.FAILED
        }
    }

    private fun readUnlocked(): HugoIdentityStorageReadResult {
        if (!primary.exists()) return HugoIdentityStorageReadResult.Missing
        if (!primary.isFile || primary.length() !in 1..MAXIMUM_IDENTITY_FILE_BYTES) {
            return HugoIdentityStorageReadResult.Corrupt
        }
        val text = runCatching { primary.readText(StandardCharsets.UTF_8) }
            .getOrElse { return HugoIdentityStorageReadResult.Unavailable }
        return when (val decoded = AuthenticatedIdentityEnvelopeCodec.decode(text)) {
            IdentityEnvelopeDecodeResult.Corrupt -> HugoIdentityStorageReadResult.Corrupt
            is IdentityEnvelopeDecodeResult.Unsupported ->
                HugoIdentityStorageReadResult.UnsupportedVersion(decoded.found)
            is IdentityEnvelopeDecodeResult.Success -> when (val decrypted = cipher.decrypt(decoded.envelope)) {
                IdentityCipherResult.Corrupt -> HugoIdentityStorageReadResult.Corrupt
                IdentityCipherResult.Unavailable -> HugoIdentityStorageReadResult.Unavailable
                is IdentityCipherResult.Success -> HugoIdentityStorageReadResult.Content(
                    decrypted.value.toString(StandardCharsets.UTF_8),
                )
            }
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
