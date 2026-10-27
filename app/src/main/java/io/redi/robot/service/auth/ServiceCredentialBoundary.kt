package io.redi.robot.service.auth

import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.service.api.ServiceAuthenticationSchemeReference
import io.redi.robot.service.api.ServiceAuthorizationScopeReference
import java.util.Collections
import java.util.UUID

/** Local identifier for one protected credential record; never an external account identifier. */
@JvmInline
value class ServiceCredentialId private constructor(val value: String) : Comparable<ServiceCredentialId> {
    override fun compareTo(other: ServiceCredentialId): Int = value.compareTo(other.value)

    companion object {
        private val pattern = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )

        fun newReference(): ServiceCredentialId = ServiceCredentialId(UUID.randomUUID().toString())

        fun parse(raw: String): CredentialModelResult<ServiceCredentialId> = if (pattern.matches(raw)) {
            CredentialModelResult.Success(ServiceCredentialId(raw))
        } else {
            CredentialModelResult.Failure(CredentialModelError.MALFORMED_CREDENTIAL_ID)
        }
    }
}

@JvmInline
value class CredentialStoreGeneration private constructor(val value: Long) {
    fun next(): CredentialModelResult<CredentialStoreGeneration> = create(value + 1)

    companion object {
        fun initial(): CredentialStoreGeneration = CredentialStoreGeneration(0)
        fun create(value: Long): CredentialModelResult<CredentialStoreGeneration> = if (value >= 0) {
            CredentialModelResult.Success(CredentialStoreGeneration(value))
        } else {
            CredentialModelResult.Failure(CredentialModelError.INVALID_GENERATION)
        }
    }
}

@JvmInline
value class CredentialRecordRevision private constructor(val value: Long) {
    fun next(): CredentialModelResult<CredentialRecordRevision> = create(value + 1)

    companion object {
        fun first(): CredentialRecordRevision = CredentialRecordRevision(1)
        fun create(value: Long): CredentialModelResult<CredentialRecordRevision> = if (value > 0) {
            CredentialModelResult.Success(CredentialRecordRevision(value))
        } else {
            CredentialModelResult.Failure(CredentialModelError.INVALID_RECORD_REVISION)
        }
    }
}

@JvmInline
value class CredentialEpochMillis private constructor(val value: Long) : Comparable<CredentialEpochMillis> {
    override fun compareTo(other: CredentialEpochMillis): Int = value.compareTo(other.value)

    companion object {
        fun create(value: Long): CredentialModelResult<CredentialEpochMillis> = if (value >= 0) {
            CredentialModelResult.Success(CredentialEpochMillis(value))
        } else {
            CredentialModelResult.Failure(CredentialModelError.INVALID_TIMESTAMP)
        }
    }
}

/** Short-lived mutable input. It is defensively copied and zeroed when closed. */
class ServiceCredentialSecret private constructor(private val bytes: ByteArray) : AutoCloseable {
    private var closed = false

    internal fun copyBytes(): ByteArray {
        check(!closed) { "Credential secret is closed" }
        return bytes.copyOf()
    }

    override fun close() {
        bytes.fill(0)
        closed = true
    }

    override fun toString(): String = "ServiceCredentialSecret(REDACTED)"

    companion object {
        const val MAXIMUM_BYTES: Int = 64 * 1024

        fun copyOf(bytes: ByteArray): CredentialModelResult<ServiceCredentialSecret> = if (
            bytes.isNotEmpty() && bytes.size <= MAXIMUM_BYTES
        ) {
            CredentialModelResult.Success(ServiceCredentialSecret(bytes.copyOf()))
        } else {
            CredentialModelResult.Failure(CredentialModelError.INVALID_SECRET_SIZE)
        }
    }
}

enum class ServiceCredentialKnowledgeState { ACTIVE, EXPIRED, REVOKED }

enum class ServiceCredentialRevocationReason { LOGOUT, USER_REVOKED, PROVIDER_REJECTED, SECURITY_RECOVERY }

class ServiceCredentialMetadata internal constructor(
    val id: ServiceCredentialId,
    val providerDefinitionId: ProviderDefinitionId,
    val authenticationScheme: ServiceAuthenticationSchemeReference,
    scopes: Set<ServiceAuthorizationScopeReference>,
    val createdAt: CredentialEpochMillis,
    val expiresAt: CredentialEpochMillis?,
    val revision: CredentialRecordRevision,
    val revokedAt: CredentialEpochMillis?,
    val revocationReason: ServiceCredentialRevocationReason?,
) {
    val scopes: Set<ServiceAuthorizationScopeReference> = Collections.unmodifiableSet(scopes.toSortedSet())

    fun stateAt(now: CredentialEpochMillis): ServiceCredentialKnowledgeState = when {
        revokedAt != null -> ServiceCredentialKnowledgeState.REVOKED
        expiresAt != null && now >= expiresAt -> ServiceCredentialKnowledgeState.EXPIRED
        else -> ServiceCredentialKnowledgeState.ACTIVE
    }

    override fun toString(): String = "ServiceCredentialMetadata(id=redacted, provider=$providerDefinitionId, " +
        "scheme=${authenticationScheme.wireValue}, scopeCount=${scopes.size}, revision=${revision.value}, " +
        "state=${if (revokedAt == null) "stored" else "revoked"})"
}

class ServiceCredentialSnapshot internal constructor(
    val generation: CredentialStoreGeneration,
    records: List<ServiceCredentialMetadata>,
    val observedAt: CredentialEpochMillis,
) {
    val records: List<ServiceCredentialMetadata> = Collections.unmodifiableList(records.sortedBy { it.id })

    val grantsTrust: Boolean = false
    val grantsAuthorization: Boolean = false
    val establishesConnection: Boolean = false
    val executesCapability: Boolean = false
}

class StoreServiceCredentialRequest private constructor(
    val id: ServiceCredentialId,
    val providerDefinitionId: ProviderDefinitionId,
    val authenticationScheme: ServiceAuthenticationSchemeReference,
    scopes: Set<ServiceAuthorizationScopeReference>,
    val createdAt: CredentialEpochMillis,
    val expiresAt: CredentialEpochMillis?,
    internal val secret: ServiceCredentialSecret,
) {
    val scopes: Set<ServiceAuthorizationScopeReference> = Collections.unmodifiableSet(scopes.toSortedSet())

    companion object {
        const val MAXIMUM_SCOPES: Int = 64

        @Suppress("LongParameterList")
        fun create(
            id: ServiceCredentialId,
            providerDefinitionId: ProviderDefinitionId,
            authenticationScheme: ServiceAuthenticationSchemeReference,
            scopes: Set<ServiceAuthorizationScopeReference>,
            createdAt: CredentialEpochMillis,
            expiresAt: CredentialEpochMillis?,
            secret: ServiceCredentialSecret,
        ): CredentialModelResult<StoreServiceCredentialRequest> = when {
            scopes.size > MAXIMUM_SCOPES -> CredentialModelResult.Failure(CredentialModelError.TOO_MANY_SCOPES)
            expiresAt != null && expiresAt <= createdAt ->
                CredentialModelResult.Failure(CredentialModelError.INVALID_EXPIRY)
            else -> CredentialModelResult.Success(
                StoreServiceCredentialRequest(
                    id,
                    providerDefinitionId,
                    authenticationScheme,
                    scopes,
                    createdAt,
                    expiresAt,
                    secret,
                ),
            )
        }
    }
}

sealed interface CredentialModelResult<out T> {
    data class Success<T>(val value: T) : CredentialModelResult<T>
    data class Failure(val error: CredentialModelError) : CredentialModelResult<Nothing>
}

enum class CredentialModelError {
    MALFORMED_CREDENTIAL_ID,
    INVALID_GENERATION,
    INVALID_RECORD_REVISION,
    INVALID_TIMESTAMP,
    INVALID_SECRET_SIZE,
    INVALID_EXPIRY,
    TOO_MANY_SCOPES,
}
