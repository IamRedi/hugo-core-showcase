package io.redi.robot.gateway.auth

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

sealed interface GatewayTokenDocumentReadResult {
    data class Content(val plaintext: ByteArray) : GatewayTokenDocumentReadResult
    data object Missing : GatewayTokenDocumentReadResult
    data object Corrupt : GatewayTokenDocumentReadResult
    data class UnsupportedVersion(val found: String) : GatewayTokenDocumentReadResult
    data object Unavailable : GatewayTokenDocumentReadResult
}

enum class GatewayTokenDocumentWriteResult { COMMITTED, FAILED_PRESERVING_PREVIOUS }
enum class GatewayTokenDocumentResetResult { RESET, FAILED }

/** Implementations must provide authenticated, application-private, atomic storage. */
interface ProtectedGatewayTokenStorage {
    fun read(): GatewayTokenDocumentReadResult
    fun writeAtomically(plaintext: ByteArray): GatewayTokenDocumentWriteResult
    fun reset(): GatewayTokenDocumentResetResult
}

sealed interface GatewayAuthResult<out T> {
    data class Success<T>(val value: T) : GatewayAuthResult<T>
    data object Absent : GatewayAuthResult<Nothing>
    data object Expired : GatewayAuthResult<Nothing>
    data object Revoked : GatewayAuthResult<Nothing>
    data object Duplicate : GatewayAuthResult<Nothing>
    data object UnexpectedCurrentToken : GatewayAuthResult<Nothing>
    data object TokenMismatch : GatewayAuthResult<Nothing>
    data object EndpointDenied : GatewayAuthResult<Nothing>
    data object AudienceMismatch : GatewayAuthResult<Nothing>
    data object InsufficientScope : GatewayAuthResult<Nothing>
    data object InvalidRequest : GatewayAuthResult<Nothing>
    data object Corrupt : GatewayAuthResult<Nothing>
    data class UnsupportedVersion(val found: String) : GatewayAuthResult<Nothing>
    data object StorageUnavailable : GatewayAuthResult<Nothing>
}

class ExplicitGatewayAuthReset private constructor() {
    companion object {
        fun afterOperatorConfirmation(): ExplicitGatewayAuthReset = ExplicitGatewayAuthReset()
    }
}

/**
 * Stores one short-lived app-to-gateway token. It does not acquire/refresh a token, contact an
 * endpoint, or grant HUGO/provider/capability authority.
 */
class GatewayTokenRepository(
    private val storage: ProtectedGatewayTokenStorage,
    private val endpointPolicy: GatewayEndpointPolicy,
) {
    @Synchronized
    fun snapshot(now: GatewayAuthEpochSeconds): GatewayAuthResult<GatewayAuthSnapshot> = when (val restored = restore()) {
        is Restore.Success -> try {
            GatewayAuthResult.Success(GatewayAuthSnapshot(restored.document.token?.metadata, now))
        } finally {
            restored.document.clearSecret()
        }
        is Restore.Failure -> restored.toResult()
    }

    /** Exact expected-current identity is mandatory for rotation; no token is silently replaced. */
    @Synchronized
    fun store(
        request: StoreGatewayTokenRequest,
        expectedCurrentTokenId: GatewayTokenId?,
    ): GatewayAuthResult<GatewayTokenMutationEvidence> {
        if (endpointPolicy.evaluate(request.metadata.audience) !is GatewayEndpointDecision.Allowed) {
            return GatewayAuthResult.EndpointDenied
        }
        val document = when (val restored = restore()) {
            is Restore.Success -> restored.document
            is Restore.Failure -> return restored.toResult()
        }
        val current = document.token
        if (current == null && expectedCurrentTokenId != null) {
            return GatewayAuthResult.TokenMismatch
        }
        if (current != null && expectedCurrentTokenId == null) {
            document.clearSecret()
            return GatewayAuthResult.UnexpectedCurrentToken
        }
        if (current != null && current.metadata.tokenId != expectedCurrentTokenId) {
            document.clearSecret()
            return GatewayAuthResult.TokenMismatch
        }
        if (current?.metadata?.tokenId == request.metadata.tokenId) {
            document.clearSecret()
            return GatewayAuthResult.Duplicate
        }
        val bytes = try {
            request.token.copyBytes()
        } catch (_: IllegalStateException) {
            document.clearSecret()
            return GatewayAuthResult.InvalidRequest
        }
        val revision = (current?.metadata?.revision ?: 0) + 1
        current?.secret?.fill(0)
        val metadata = request.metadata.copy(revision = revision)
        document.token = StoredGatewayToken(metadata, bytes)
        return if (persist(document)) {
            GatewayAuthResult.Success(
                GatewayTokenMutationEvidence(metadata.tokenId, revision, GatewayTokenState.ACTIVE),
            )
        } else {
            GatewayAuthResult.StorageUnavailable
        }
    }

    @Synchronized
    fun <T> withValidToken(
        endpoint: GatewayEndpointOrigin,
        requiredScopes: Set<GatewayAuthScope>,
        now: GatewayAuthEpochSeconds,
        block: (ByteArray) -> T,
    ): GatewayAuthResult<T> {
        if (requiredScopes.isEmpty()) return GatewayAuthResult.InvalidRequest
        if (endpointPolicy.evaluate(endpoint) !is GatewayEndpointDecision.Allowed) {
            return GatewayAuthResult.EndpointDenied
        }
        val document = when (val restored = restore()) {
            is Restore.Success -> restored.document
            is Restore.Failure -> return restored.toResult()
        }
        val record = document.token ?: return GatewayAuthResult.Absent
        return try {
            when (record.metadata.stateAt(now)) {
                GatewayTokenState.EXPIRED -> GatewayAuthResult.Expired
                GatewayTokenState.REVOKED -> GatewayAuthResult.Revoked
                GatewayTokenState.ACTIVE -> when {
                    record.metadata.audience != endpoint -> GatewayAuthResult.AudienceMismatch
                    !record.metadata.scopes.containsAll(requiredScopes) -> GatewayAuthResult.InsufficientScope
                    record.secret == null -> GatewayAuthResult.Corrupt
                    else -> {
                        val transient = record.secret.copyOf()
                        try {
                            GatewayAuthResult.Success(block(transient))
                        } finally {
                            transient.fill(0)
                        }
                    }
                }
            }
        } finally {
            document.clearSecret()
        }
    }

    @Synchronized
    fun revoke(
        expectedTokenId: GatewayTokenId,
        now: GatewayAuthEpochSeconds,
        reason: GatewayTokenRevocationReason,
    ): GatewayAuthResult<GatewayTokenMutationEvidence> {
        val document = when (val restored = restore()) {
            is Restore.Success -> restored.document
            is Restore.Failure -> return restored.toResult()
        }
        val old = document.token ?: return GatewayAuthResult.Absent
        if (old.metadata.tokenId != expectedTokenId) {
            document.clearSecret()
            return GatewayAuthResult.TokenMismatch
        }
        if (old.metadata.revokedAt != null) {
            document.clearSecret()
            return GatewayAuthResult.Revoked
        }
        if (now < old.metadata.issuedAt) {
            document.clearSecret()
            return GatewayAuthResult.InvalidRequest
        }
        old.secret?.fill(0)
        val metadata = old.metadata.copy(
            revision = old.metadata.revision + 1,
            revokedAt = now,
            revocationReason = reason,
        )
        document.token = StoredGatewayToken(metadata, null)
        return if (persist(document)) {
            GatewayAuthResult.Success(
                GatewayTokenMutationEvidence(metadata.tokenId, metadata.revision, GatewayTokenState.REVOKED),
            )
        } else {
            GatewayAuthResult.StorageUnavailable
        }
    }

    fun reset(command: ExplicitGatewayAuthReset): GatewayAuthResult<Unit> {
        command.hashCode()
        return when (storage.reset()) {
            GatewayTokenDocumentResetResult.RESET -> GatewayAuthResult.Success(Unit)
            GatewayTokenDocumentResetResult.FAILED -> GatewayAuthResult.StorageUnavailable
        }
    }

    private fun restore(): Restore = when (val read = storage.read()) {
        GatewayTokenDocumentReadResult.Missing -> Restore.Success(GatewayTokenDocument(null))
        GatewayTokenDocumentReadResult.Corrupt -> Restore.Failure.Corrupt
        GatewayTokenDocumentReadResult.Unavailable -> Restore.Failure.Unavailable
        is GatewayTokenDocumentReadResult.UnsupportedVersion -> Restore.Failure.Unsupported(read.found)
        is GatewayTokenDocumentReadResult.Content -> try {
            when (val decoded = GatewayTokenDocumentCodec.decode(read.plaintext)) {
                is GatewayTokenDocumentDecode.Success -> Restore.Success(decoded.document)
                GatewayTokenDocumentDecode.Corrupt -> Restore.Failure.Corrupt
                is GatewayTokenDocumentDecode.Unsupported -> Restore.Failure.Unsupported(decoded.found)
            }
        } finally {
            read.plaintext.fill(0)
        }
    }

    private fun persist(document: GatewayTokenDocument): Boolean {
        val encoded = GatewayTokenDocumentCodec.encode(document)
        return try {
            storage.writeAtomically(encoded) == GatewayTokenDocumentWriteResult.COMMITTED
        } finally {
            encoded.fill(0)
            document.clearSecret()
        }
    }

    private sealed interface Restore {
        data class Success(val document: GatewayTokenDocument) : Restore
        sealed interface Failure : Restore {
            data object Corrupt : Failure
            data object Unavailable : Failure
            data class Unsupported(val found: String) : Failure
        }
    }

    private fun Restore.Failure.toResult(): GatewayAuthResult<Nothing> = when (this) {
        Restore.Failure.Corrupt -> GatewayAuthResult.Corrupt
        Restore.Failure.Unavailable -> GatewayAuthResult.StorageUnavailable
        is Restore.Failure.Unsupported -> GatewayAuthResult.UnsupportedVersion(found)
    }
}

private data class StoredGatewayToken(
    val metadata: GatewayTokenMetadata,
    val secret: ByteArray?,
)

private data class GatewayTokenDocument(var token: StoredGatewayToken?) {
    fun clearSecret() {
        token?.secret?.fill(0)
    }
}

private sealed interface GatewayTokenDocumentDecode {
    data class Success(val document: GatewayTokenDocument) : GatewayTokenDocumentDecode
    data class Unsupported(val found: String) : GatewayTokenDocumentDecode
    data object Corrupt : GatewayTokenDocumentDecode
}

private object GatewayTokenDocumentCodec {
    private const val MAGIC = 0x48474154 // HGAT
    private const val VERSION = 1
    private const val MAXIMUM_DOCUMENT_BYTES = 32 * 1024

    fun encode(document: GatewayTokenDocument): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            val token = document.token
            data.writeBoolean(token != null)
            if (token != null) {
                val metadata = token.metadata
                data.writeUTF(metadata.tokenId.value)
                data.writeUTF(metadata.clientSessionId.value)
                data.writeUTF(metadata.audience.wireValue)
                data.writeInt(metadata.scopes.size)
                metadata.scopes.sortedBy { it.wireValue }.forEach { data.writeUTF(it.wireValue) }
                data.writeLong(metadata.issuedAt.value)
                data.writeLong(metadata.expiresAt.value)
                data.writeLong(metadata.revision)
                data.writeBoolean(metadata.revokedAt != null)
                metadata.revokedAt?.let { data.writeLong(it.value) }
                data.writeBoolean(metadata.revocationReason != null)
                metadata.revocationReason?.let { data.writeUTF(it.name) }
                val secret = token.secret
                data.writeInt(secret?.size ?: -1)
                secret?.let(data::write)
            }
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): GatewayTokenDocumentDecode {
        if (bytes.isEmpty() || bytes.size > MAXIMUM_DOCUMENT_BYTES) return GatewayTokenDocumentDecode.Corrupt
        var secret: ByteArray? = null
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                if (data.readInt() != MAGIC) return GatewayTokenDocumentDecode.Corrupt
                val version = data.readInt()
                if (version != VERSION) return GatewayTokenDocumentDecode.Unsupported(version.toString())
                val hasToken = data.readBoolean()
                val token = if (hasToken) {
                    val tokenId = model(GatewayTokenId.parse(data.readUTF()))
                    val client = model(GatewayClientSessionId.parse(data.readUTF()))
                    val audience = model(GatewayEndpointOrigin.parse(data.readUTF()))
                    val scopeCount = data.readInt()
                    if (scopeCount !in 1..GatewayAuthScope.entries.size) return GatewayTokenDocumentDecode.Corrupt
                    val scopes = (0 until scopeCount).map {
                        GatewayAuthScope.fromWireValue(data.readUTF()) ?: return GatewayTokenDocumentDecode.Corrupt
                    }
                    if (scopes.distinct().size != scopes.size || scopes != scopes.sortedBy { it.wireValue }) {
                        return GatewayTokenDocumentDecode.Corrupt
                    }
                    val issuedAt = model(GatewayAuthEpochSeconds.create(data.readLong()))
                    val expiresAt = model(GatewayAuthEpochSeconds.create(data.readLong()))
                    val lifetime = expiresAt.value - issuedAt.value
                    if (lifetime !in StoreGatewayTokenRequest.MINIMUM_LIFETIME_SECONDS..
                        StoreGatewayTokenRequest.MAXIMUM_LIFETIME_SECONDS
                    ) return GatewayTokenDocumentDecode.Corrupt
                    val revision = data.readLong().takeIf { it > 0 } ?: return GatewayTokenDocumentDecode.Corrupt
                    val revokedAt = if (data.readBoolean()) {
                        model(GatewayAuthEpochSeconds.create(data.readLong()))
                    } else null
                    val reason = if (data.readBoolean()) {
                        runCatching { GatewayTokenRevocationReason.valueOf(data.readUTF()) }.getOrNull()
                            ?: return GatewayTokenDocumentDecode.Corrupt
                    } else null
                    if ((revokedAt == null) != (reason == null) || revokedAt?.let { it < issuedAt } == true) {
                        return GatewayTokenDocumentDecode.Corrupt
                    }
                    val secretLength = data.readInt()
                    secret = when {
                        secretLength == -1 -> null
                        secretLength in 1..GatewayAccessToken.MAXIMUM_BYTES -> ByteArray(secretLength).also(data::readFully)
                        else -> return GatewayTokenDocumentDecode.Corrupt
                    }
                    if ((revokedAt == null) != (secret != null)) return GatewayTokenDocumentDecode.Corrupt
                    StoredGatewayToken(
                        GatewayTokenMetadata(
                            tokenId,
                            client,
                            audience,
                            scopes.toSet(),
                            issuedAt,
                            expiresAt,
                            revision,
                            revokedAt,
                            reason,
                        ),
                        secret,
                    )
                } else null
                if (data.available() != 0) {
                    secret?.fill(0)
                    GatewayTokenDocumentDecode.Corrupt
                } else {
                    GatewayTokenDocumentDecode.Success(GatewayTokenDocument(token))
                }
            }
        } catch (_: Exception) {
            secret?.fill(0)
            GatewayTokenDocumentDecode.Corrupt
        }
    }

    private fun <T> model(result: GatewayAuthModelResult<T>): T = when (result) {
        is GatewayAuthModelResult.Success -> result.value
        is GatewayAuthModelResult.Failure -> throw IllegalArgumentException(result.error.name)
    }
}
