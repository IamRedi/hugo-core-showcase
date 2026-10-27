package io.redi.robot.memory.storage

import io.redi.robot.memory.domain.MemoryRecordId
import java.util.UUID

/**
 * H059 encrypted local memory repository ports. Pure, Android-free, storage-technology-neutral:
 * the durable boundary is a set of per-record atomic ciphertext slots plus an authenticated-
 * encryption port, mirroring the governed H032/H052 Keystore AES-GCM document-store pattern with
 * memory-specific naming. The store above these ports is the authoritative canonical memory
 * persistence; no derived index, vector store, summary, or provider storage is authoritative.
 */
interface MemoryRecordSlots {
    fun read(recordId: MemoryRecordId): MemorySlotReadResult

    /** Atomic all-or-nothing replacement of the record slot; never a partial write. */
    fun writeAtomically(recordId: MemoryRecordId, bytes: ByteArray): MemorySlotWriteResult

    /**
     * Bounded, stable-order listing of persisted record ids; never a whole-store load.
     * With [afterId] set, only ids strictly greater than it are listed (keyset continuation);
     * the default null lists from the beginning. The per-page [limit] bound always applies.
     */
    fun storedRecordIds(limit: Int, afterId: MemoryRecordId? = null): MemorySlotListResult

    /** Explicit destructive purge of every slot for operator-approved key-loss recovery. */
    fun reset(): MemorySlotResetResult
}

sealed interface MemorySlotReadResult {
    data class Content(val bytes: ByteArray) : MemorySlotReadResult {
        override fun equals(other: Any?): Boolean = other is Content && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data object Missing : MemorySlotReadResult

    data class Failed(val reason: MemorySlotFailure) : MemorySlotReadResult
}

sealed interface MemorySlotWriteResult {
    data object Written : MemorySlotWriteResult

    data class Failed(val reason: MemorySlotFailure) : MemorySlotWriteResult
}

sealed interface MemorySlotListResult {
    data class Ids(val recordIds: List<MemoryRecordId>) : MemorySlotListResult

    data class Failed(val reason: MemorySlotFailure) : MemorySlotListResult
}

sealed interface MemorySlotResetResult {
    data class Reset(val purgedSlots: Int) : MemorySlotResetResult

    data class Failed(val reason: MemorySlotFailure) : MemorySlotResetResult
}

enum class MemorySlotFailure {
    IO_FAILED,
    CORRUPT_SLOT_NAME,
    OVERSIZED_SLOT,
    INVALID_LIMIT,
    ;
}

/**
 * Authenticated-encryption port for one record slot. Platform implementations keep every key
 * inside Android Keystore; no key material crosses this boundary in either direction.
 */
interface MemoryRecordProtection {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): MemoryProtectionResult<ProtectedMemoryRecordPayload>

    fun decrypt(payload: ProtectedMemoryRecordPayload, associatedData: ByteArray): MemoryProtectionResult<ByteArray>
}

data class MemoryStoreKeyVersion private constructor(val value: Int) {
    companion object {
        fun create(value: Int): MemoryProtectionResult<MemoryStoreKeyVersion> =
            if (value > 0) {
                MemoryProtectionResult.Success(MemoryStoreKeyVersion(value))
            } else {
                MemoryProtectionResult.Failure(MemoryProtectionError.INVALID_KEY_VERSION)
            }
    }
}

data class ProtectedMemoryRecordPayload(
    val algorithm: String,
    val keyVersion: MemoryStoreKeyVersion,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
) {
    init {
        require(algorithm == AUTHENTICATED_ENCRYPTION_ALGORITHM)
        require(nonce.size == GCM_NONCE_BYTES)
        require(ciphertext.isNotEmpty())
    }

    override fun equals(other: Any?): Boolean = other is ProtectedMemoryRecordPayload &&
        algorithm == other.algorithm && keyVersion == other.keyVersion &&
        nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = 31 * (31 * (31 * algorithm.hashCode() + keyVersion.hashCode()) +
        nonce.contentHashCode()) + ciphertext.contentHashCode()
}

sealed interface MemoryProtectionResult<out T> {
    data class Success<T>(val value: T) : MemoryProtectionResult<T>
    data class Failure(val error: MemoryProtectionError) : MemoryProtectionResult<Nothing>
}

enum class MemoryProtectionError {
    INVALID_KEY_VERSION,
    KEY_UNAVAILABLE,
    KEY_LOST,
    ENCRYPTION_FAILED,
    AUTHENTICATION_FAILED,
    DECRYPTION_FAILED,
    ;
}

internal const val AUTHENTICATED_ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding"
internal const val GCM_NONCE_BYTES = 12
