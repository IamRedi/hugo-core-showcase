package io.redi.robot.service.auth

import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.service.api.ServiceAuthenticationSchemeReference
import io.redi.robot.service.api.ServiceAuthorizationScopeReference
import io.redi.robot.service.api.ServiceConnectorResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

sealed interface CredentialDocumentReadResult {
    data class Content(val plaintext: ByteArray) : CredentialDocumentReadResult
    data object Missing : CredentialDocumentReadResult
    data object Corrupt : CredentialDocumentReadResult
    data class UnsupportedVersion(val found: String) : CredentialDocumentReadResult
    data object Unavailable : CredentialDocumentReadResult
}

enum class CredentialDocumentWriteResult { COMMITTED, FAILED_PRESERVING_PREVIOUS }
enum class CredentialDocumentResetResult { RESET, FAILED }

/** Platform implementations must provide authenticated application-private storage. */
interface ProtectedServiceCredentialStorage {
    fun read(): CredentialDocumentReadResult
    fun writeAtomically(plaintext: ByteArray): CredentialDocumentWriteResult
    fun reset(): CredentialDocumentResetResult
}

sealed interface CredentialBoundaryResult<out T> {
    data class Success<T>(val value: T) : CredentialBoundaryResult<T>
    data object Absent : CredentialBoundaryResult<Nothing>
    data object Expired : CredentialBoundaryResult<Nothing>
    data object Revoked : CredentialBoundaryResult<Nothing>
    data object Duplicate : CredentialBoundaryResult<Nothing>
    data object CapacityExceeded : CredentialBoundaryResult<Nothing>
    data object InvalidRequest : CredentialBoundaryResult<Nothing>
    data object StaleGeneration : CredentialBoundaryResult<Nothing>
    data object StaleRecordRevision : CredentialBoundaryResult<Nothing>
    data object Corrupt : CredentialBoundaryResult<Nothing>
    data class UnsupportedVersion(val found: String) : CredentialBoundaryResult<Nothing>
    data object StorageUnavailable : CredentialBoundaryResult<Nothing>
}

data class CredentialMutationEvidence(
    val credentialId: ServiceCredentialId,
    val generation: CredentialStoreGeneration,
    val recordRevision: CredentialRecordRevision,
    val state: ServiceCredentialKnowledgeState,
) {
    val grantsTrust: Boolean = false
    val grantsAuthorization: Boolean = false
    val establishesConnection: Boolean = false
    val executesCapability: Boolean = false

    override fun toString(): String = "CredentialMutationEvidence(id=redacted, generation=${generation.value}, " +
        "revision=${recordRevision.value}, state=$state)"
}

class ExplicitCredentialStoreReset private constructor() {
    companion object {
        fun afterOperatorConfirmation(): ExplicitCredentialStoreReset = ExplicitCredentialStoreReset()
    }
}

/**
 * Deterministic credential lifecycle boundary. It never authenticates a service or grants HUGO
 * authority. Secret bytes are supplied only to a bounded callback and are zeroed immediately after.
 */
class ServiceCredentialBoundary(private val storage: ProtectedServiceCredentialStorage) {
    @Synchronized
    fun snapshot(now: CredentialEpochMillis): CredentialBoundaryResult<ServiceCredentialSnapshot> =
        when (val restored = restore()) {
            is Restore.Success -> {
                val snapshot = ServiceCredentialSnapshot(
                    restored.document.generation,
                    restored.document.records.map { it.metadata },
                    now,
                )
                restored.document.clearSecrets()
                CredentialBoundaryResult.Success(snapshot)
            }
            Restore.Absent -> CredentialBoundaryResult.Success(
                ServiceCredentialSnapshot(CredentialStoreGeneration.initial(), emptyList(), now),
            )
            is Restore.Failure -> restored.toBoundaryFailure()
        }

    @Synchronized
    fun store(
        request: StoreServiceCredentialRequest,
        expectedGeneration: CredentialStoreGeneration,
    ): CredentialBoundaryResult<CredentialMutationEvidence> {
        val document = when (val restored = restore()) {
            is Restore.Success -> restored.document
            Restore.Absent -> CredentialDocument(CredentialStoreGeneration.initial(), mutableListOf())
            is Restore.Failure -> return restored.toBoundaryFailure()
        }
        if (document.generation != expectedGeneration) {
            document.clearSecrets()
            return CredentialBoundaryResult.StaleGeneration
        }
        if (document.records.any { it.metadata.id == request.id }) {
            document.clearSecrets()
            return CredentialBoundaryResult.Duplicate
        }
        if (document.records.size >= CredentialDocumentCodec.MAXIMUM_RECORDS) {
            document.clearSecrets()
            return CredentialBoundaryResult.CapacityExceeded
        }
        val bytes = try {
            request.secret.copyBytes()
        } catch (_: IllegalStateException) {
            document.clearSecrets()
            return CredentialBoundaryResult.StorageUnavailable
        }
        val metadata = ServiceCredentialMetadata(
            request.id,
            request.providerDefinitionId,
            request.authenticationScheme,
            request.scopes,
            request.createdAt,
            request.expiresAt,
            CredentialRecordRevision.first(),
            null,
            null,
        )
        document.records += StoredCredential(metadata, bytes)
        val next = document.generation.next().successOrNull() ?: return CredentialBoundaryResult.StorageUnavailable.also {
            document.clearSecrets()
        }
        document.generation = next
        return if (persist(document)) {
            CredentialBoundaryResult.Success(
                CredentialMutationEvidence(request.id, next, metadata.revision, metadata.stateAt(request.createdAt)),
            )
        } else {
            CredentialBoundaryResult.StorageUnavailable
        }
    }

    @Synchronized
    fun <T> withCredential(
        id: ServiceCredentialId,
        now: CredentialEpochMillis,
        block: (ByteArray) -> T,
    ): CredentialBoundaryResult<T> {
        val document = when (val restored = restore()) {
            is Restore.Success -> restored.document
            Restore.Absent -> return CredentialBoundaryResult.Absent
            is Restore.Failure -> return restored.toBoundaryFailure()
        }
        val record = document.records.firstOrNull { it.metadata.id == id }
            ?: return CredentialBoundaryResult.Absent.also { document.clearSecrets() }
        return try {
            when (record.metadata.stateAt(now)) {
                ServiceCredentialKnowledgeState.REVOKED -> CredentialBoundaryResult.Revoked
                ServiceCredentialKnowledgeState.EXPIRED -> CredentialBoundaryResult.Expired
                ServiceCredentialKnowledgeState.ACTIVE -> {
                    val transient = record.secret?.copyOf() ?: return CredentialBoundaryResult.Corrupt
                    try {
                        CredentialBoundaryResult.Success(block(transient))
                    } finally {
                        transient.fill(0)
                    }
                }
            }
        } finally {
            document.clearSecrets()
        }
    }

    @Synchronized
    fun logout(
        id: ServiceCredentialId,
        expectedGeneration: CredentialStoreGeneration,
        expectedRevision: CredentialRecordRevision,
        now: CredentialEpochMillis,
    ): CredentialBoundaryResult<CredentialMutationEvidence> = invalidate(
        id,
        expectedGeneration,
        expectedRevision,
        now,
        ServiceCredentialRevocationReason.LOGOUT,
    )

    @Synchronized
    fun revoke(
        id: ServiceCredentialId,
        expectedGeneration: CredentialStoreGeneration,
        expectedRevision: CredentialRecordRevision,
        now: CredentialEpochMillis,
        reason: ServiceCredentialRevocationReason,
    ): CredentialBoundaryResult<CredentialMutationEvidence> = invalidate(
        id,
        expectedGeneration,
        expectedRevision,
        now,
        reason,
    )

    @Synchronized
    fun reset(command: ExplicitCredentialStoreReset): CredentialBoundaryResult<Unit> {
        command.hashCode() // Require an explicit typed command at the call boundary.
        return when (storage.reset()) {
            CredentialDocumentResetResult.RESET -> CredentialBoundaryResult.Success(Unit)
            CredentialDocumentResetResult.FAILED -> CredentialBoundaryResult.StorageUnavailable
        }
    }

    private fun invalidate(
        id: ServiceCredentialId,
        expectedGeneration: CredentialStoreGeneration,
        expectedRevision: CredentialRecordRevision,
        now: CredentialEpochMillis,
        reason: ServiceCredentialRevocationReason,
    ): CredentialBoundaryResult<CredentialMutationEvidence> {
        val document = when (val restored = restore()) {
            is Restore.Success -> restored.document
            Restore.Absent -> return CredentialBoundaryResult.Absent
            is Restore.Failure -> return restored.toBoundaryFailure()
        }
        if (document.generation != expectedGeneration) {
            document.clearSecrets()
            return CredentialBoundaryResult.StaleGeneration
        }
        val index = document.records.indexOfFirst { it.metadata.id == id }
        if (index < 0) return CredentialBoundaryResult.Absent.also { document.clearSecrets() }
        val old = document.records[index]
        if (old.metadata.revision != expectedRevision) {
            document.clearSecrets()
            return CredentialBoundaryResult.StaleRecordRevision
        }
        if (old.metadata.revokedAt != null) {
            document.clearSecrets()
            return CredentialBoundaryResult.Revoked
        }
        if (now < old.metadata.createdAt) {
            document.clearSecrets()
            return CredentialBoundaryResult.InvalidRequest
        }
        val revision = old.metadata.revision.next().successOrNull()
            ?: return CredentialBoundaryResult.StorageUnavailable.also { document.clearSecrets() }
        old.secret?.fill(0)
        val revoked = ServiceCredentialMetadata(
            old.metadata.id,
            old.metadata.providerDefinitionId,
            old.metadata.authenticationScheme,
            old.metadata.scopes,
            old.metadata.createdAt,
            old.metadata.expiresAt,
            revision,
            now,
            reason,
        )
        document.records[index] = StoredCredential(revoked, null)
        val generation = document.generation.next().successOrNull()
            ?: return CredentialBoundaryResult.StorageUnavailable.also { document.clearSecrets() }
        document.generation = generation
        return if (persist(document)) {
            CredentialBoundaryResult.Success(
                CredentialMutationEvidence(id, generation, revision, ServiceCredentialKnowledgeState.REVOKED),
            )
        } else {
            CredentialBoundaryResult.StorageUnavailable
        }
    }

    private fun restore(): Restore = when (val result = storage.read()) {
        CredentialDocumentReadResult.Missing -> Restore.Absent
        CredentialDocumentReadResult.Corrupt -> Restore.Failure(FailureKind.CORRUPT)
        CredentialDocumentReadResult.Unavailable -> Restore.Failure(FailureKind.UNAVAILABLE)
        is CredentialDocumentReadResult.UnsupportedVersion -> Restore.Failure(
            FailureKind.UNSUPPORTED,
            result.found,
        )
        is CredentialDocumentReadResult.Content -> try {
            when (val decoded = CredentialDocumentCodec.decode(result.plaintext)) {
                is DocumentDecode.Success -> Restore.Success(decoded.document)
                DocumentDecode.Corrupt -> Restore.Failure(FailureKind.CORRUPT)
                is DocumentDecode.Unsupported -> Restore.Failure(FailureKind.UNSUPPORTED, decoded.found)
            }
        } finally {
            result.plaintext.fill(0)
        }
    }

    private fun persist(document: CredentialDocument): Boolean {
        val plaintext = CredentialDocumentCodec.encode(document)
        return try {
            storage.writeAtomically(plaintext) == CredentialDocumentWriteResult.COMMITTED
        } finally {
            plaintext.fill(0)
            document.clearSecrets()
        }
    }
}

private data class StoredCredential(val metadata: ServiceCredentialMetadata, var secret: ByteArray?)
private data class CredentialDocument(var generation: CredentialStoreGeneration, val records: MutableList<StoredCredential>) {
    fun clearSecrets() = records.forEach { it.secret?.fill(0) }
}

private sealed interface Restore {
    data class Success(val document: CredentialDocument) : Restore
    data object Absent : Restore
    data class Failure(val kind: FailureKind, val found: String = "") : Restore
}

private enum class FailureKind { CORRUPT, UNSUPPORTED, UNAVAILABLE }

private fun Restore.Failure.toBoundaryFailure(): CredentialBoundaryResult<Nothing> = when (kind) {
    FailureKind.CORRUPT -> CredentialBoundaryResult.Corrupt
    FailureKind.UNSUPPORTED -> CredentialBoundaryResult.UnsupportedVersion(found)
    FailureKind.UNAVAILABLE -> CredentialBoundaryResult.StorageUnavailable
}

private fun <T> CredentialModelResult<T>.successOrNull(): T? = (this as? CredentialModelResult.Success)?.value

private sealed interface DocumentDecode {
    data class Success(val document: CredentialDocument) : DocumentDecode
    data object Corrupt : DocumentDecode
    data class Unsupported(val found: String) : DocumentDecode
}

private object CredentialDocumentCodec {
    private const val MAGIC = 0x48435244 // HCRD
    private const val VERSION = 1
    const val MAXIMUM_RECORDS = 64
    private const val MAXIMUM_DOCUMENT_BYTES = 1024 * 1024

    fun encode(document: CredentialDocument): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeLong(document.generation.value)
            val records = document.records.sortedBy { it.metadata.id }
            data.writeInt(records.size)
            records.forEach { record ->
                val metadata = record.metadata
                data.writeUTF(metadata.id.value)
                data.writeUTF(metadata.providerDefinitionId.value)
                data.writeUTF(metadata.authenticationScheme.wireValue)
                data.writeInt(metadata.scopes.size)
                metadata.scopes.sorted().forEach { data.writeUTF(it.wireValue) }
                data.writeLong(metadata.createdAt.value)
                data.writeLong(metadata.expiresAt?.value ?: -1L)
                data.writeLong(metadata.revision.value)
                data.writeLong(metadata.revokedAt?.value ?: -1L)
                data.writeInt(metadata.revocationReason?.ordinal ?: -1)
                val secret = record.secret
                data.writeInt(secret?.size ?: -1)
                if (secret != null) data.write(secret)
            }
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): DocumentDecode {
        if (bytes.isEmpty() || bytes.size > MAXIMUM_DOCUMENT_BYTES) return DocumentDecode.Corrupt
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                if (data.readInt() != MAGIC) return DocumentDecode.Corrupt
                val version = data.readInt()
                if (version != VERSION) return DocumentDecode.Unsupported(version.toString())
                val generation = CredentialStoreGeneration.create(data.readLong()).successOrNull()
                    ?: return DocumentDecode.Corrupt
                val count = data.readInt()
                if (count !in 0..MAXIMUM_RECORDS) return DocumentDecode.Corrupt
                val records = ArrayList<StoredCredential>(count)
                repeat(count) {
                    val id = ServiceCredentialId.parse(data.readUTF()).successOrNull()
                        ?: return DocumentDecode.Corrupt
                    val provider = when (val parsed = ProviderDefinitionId.parse(data.readUTF())) {
                        is ProviderIdentityResult.Success -> parsed.value
                        is ProviderIdentityResult.Failure -> return DocumentDecode.Corrupt
                    }
                    val scheme = when (val parsed = ServiceAuthenticationSchemeReference.parse(data.readUTF())) {
                        is ServiceConnectorResult.Success -> parsed.value
                        is ServiceConnectorResult.Failure -> return DocumentDecode.Corrupt
                    }
                    val scopeCount = data.readInt()
                    if (scopeCount !in 0..StoreServiceCredentialRequest.MAXIMUM_SCOPES) return DocumentDecode.Corrupt
                    val scopes = linkedSetOf<ServiceAuthorizationScopeReference>()
                    repeat(scopeCount) {
                        val scope = when (val parsed = ServiceAuthorizationScopeReference.parse(data.readUTF())) {
                            is ServiceConnectorResult.Success -> parsed.value
                            is ServiceConnectorResult.Failure -> return DocumentDecode.Corrupt
                        }
                        if (!scopes.add(scope)) return DocumentDecode.Corrupt
                    }
                    val created = CredentialEpochMillis.create(data.readLong()).successOrNull()
                        ?: return DocumentDecode.Corrupt
                    val expiryRaw = data.readLong()
                    val expiry = if (expiryRaw == -1L) null else CredentialEpochMillis.create(expiryRaw).successOrNull()
                        ?: return DocumentDecode.Corrupt
                    if (expiry != null && expiry <= created) return DocumentDecode.Corrupt
                    val revision = CredentialRecordRevision.create(data.readLong()).successOrNull()
                        ?: return DocumentDecode.Corrupt
                    val revokedRaw = data.readLong()
                    val revoked = if (revokedRaw == -1L) null else CredentialEpochMillis.create(revokedRaw).successOrNull()
                        ?: return DocumentDecode.Corrupt
                    if (revoked != null && revoked < created) return DocumentDecode.Corrupt
                    val reasonOrdinal = data.readInt()
                    val reason = if (reasonOrdinal == -1) null else ServiceCredentialRevocationReason.entries.getOrNull(reasonOrdinal)
                        ?: return DocumentDecode.Corrupt
                    if ((revoked == null) != (reason == null)) return DocumentDecode.Corrupt
                    val secretSize = data.readInt()
                    val secret = when {
                        secretSize == -1 -> null
                        secretSize !in 1..ServiceCredentialSecret.MAXIMUM_BYTES -> return DocumentDecode.Corrupt
                        else -> ByteArray(secretSize).also { data.readFully(it) }
                    }
                    if ((revoked == null) != (secret != null)) return DocumentDecode.Corrupt
                    records += StoredCredential(
                        ServiceCredentialMetadata(
                            id,
                            provider,
                            scheme,
                            scopes,
                            created,
                            expiry,
                            revision,
                            revoked,
                            reason,
                        ),
                        secret,
                    )
                }
                if (data.available() != 0 || records.map { it.metadata.id }.distinct().size != records.size) {
                    records.forEach { it.secret?.fill(0) }
                    return DocumentDecode.Corrupt
                }
                DocumentDecode.Success(CredentialDocument(generation, records))
            }
        } catch (_: Exception) {
            DocumentDecode.Corrupt
        }
    }
}
