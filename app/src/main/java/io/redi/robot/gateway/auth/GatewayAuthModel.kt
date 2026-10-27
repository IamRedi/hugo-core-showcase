package io.redi.robot.gateway.auth

import java.net.URI
import java.util.Collections
import java.util.UUID

@JvmInline
value class GatewayAuthEpochSeconds private constructor(val value: Long) : Comparable<GatewayAuthEpochSeconds> {
    override fun compareTo(other: GatewayAuthEpochSeconds): Int = value.compareTo(other.value)

    companion object {
        fun create(value: Long): GatewayAuthModelResult<GatewayAuthEpochSeconds> = if (value >= 0) {
            GatewayAuthModelResult.Success(GatewayAuthEpochSeconds(value))
        } else {
            GatewayAuthModelResult.Failure(GatewayAuthModelError.INVALID_TIMESTAMP)
        }
    }
}

@JvmInline
value class GatewayClientSessionId private constructor(val value: String) {
    override fun toString(): String = "gateway-client-session:[redacted]"

    companion object {
        fun newReference(): GatewayClientSessionId = GatewayClientSessionId(UUID.randomUUID().toString())
        fun parse(raw: String): GatewayAuthModelResult<GatewayClientSessionId> = parseUuid(raw, ::GatewayClientSessionId)
    }
}

@JvmInline
value class GatewayTokenId private constructor(val value: String) {
    override fun toString(): String = "gateway-token:[redacted]"

    companion object {
        fun newReference(): GatewayTokenId = GatewayTokenId(UUID.randomUUID().toString())
        fun parse(raw: String): GatewayAuthModelResult<GatewayTokenId> = parseUuid(raw, ::GatewayTokenId)
    }
}

enum class GatewayAuthScope(val wireValue: String) {
    TEXT("gateway.operation.text"),
    REALTIME("gateway.operation.realtime"),
    VISION("gateway.operation.vision"),
    EMBEDDING("gateway.operation.embedding");

    companion object {
        fun fromWireValue(raw: String): GatewayAuthScope? = entries.firstOrNull { it.wireValue == raw }
    }
}

class GatewayAccessToken private constructor(private val bytes: ByteArray) : AutoCloseable {
    private var closed = false

    internal fun copyBytes(): ByteArray {
        check(!closed) { "Gateway access token is closed" }
        return bytes.copyOf()
    }

    override fun close() {
        bytes.fill(0)
        closed = true
    }

    override fun toString(): String = "GatewayAccessToken(REDACTED)"

    companion object {
        const val MAXIMUM_BYTES = 8 * 1024

        fun copyOf(bytes: ByteArray): GatewayAuthModelResult<GatewayAccessToken> = if (
            bytes.isNotEmpty() && bytes.size <= MAXIMUM_BYTES
        ) {
            GatewayAuthModelResult.Success(GatewayAccessToken(bytes.copyOf()))
        } else {
            GatewayAuthModelResult.Failure(GatewayAuthModelError.INVALID_TOKEN_SIZE)
        }
    }
}

data class GatewayEndpointOrigin private constructor(
    val scheme: String,
    val host: String,
    val port: Int?,
) : Comparable<GatewayEndpointOrigin> {
    val wireValue: String = buildString {
        append(scheme)
        append("://")
        if (host == "::1") append("[::1]") else append(host)
        port?.let { append(":$it") }
    }

    override fun compareTo(other: GatewayEndpointOrigin): Int = wireValue.compareTo(other.wireValue)
    override fun toString(): String = "gateway-endpoint-origin:[redacted]"

    companion object {
        private val dnsName = Regex("^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$")

        fun parse(raw: String): GatewayAuthModelResult<GatewayEndpointOrigin> {
            val uri = runCatching { URI(raw) }.getOrNull()
                ?: return GatewayAuthModelResult.Failure(GatewayAuthModelError.INVALID_ENDPOINT)
            val scheme = uri.scheme?.lowercase()
                ?: return GatewayAuthModelResult.Failure(GatewayAuthModelError.INVALID_ENDPOINT)
            val rawHost = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
                ?: return GatewayAuthModelResult.Failure(GatewayAuthModelError.INVALID_ENDPOINT)
            if (scheme !in setOf("https", "http") || rawHost.isBlank() ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                (uri.rawPath.isNotEmpty() && uri.rawPath != "/") ||
                uri.port == 0 || uri.port > 65_535 ||
                (rawHost != "::1" && !dnsName.matches(rawHost))
            ) return GatewayAuthModelResult.Failure(GatewayAuthModelError.INVALID_ENDPOINT)
            val defaultPort = (scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)
            return GatewayAuthModelResult.Success(
                GatewayEndpointOrigin(scheme, rawHost, uri.port.takeIf { it > 0 && !defaultPort }),
            )
        }
    }
}

enum class GatewayBuildMode { DEBUG, RELEASE }

enum class GatewayEndpointDenialReason {
    ENDPOINT_NOT_ALLOWLISTED,
    CLEARTEXT_FORBIDDEN,
    CLEARTEXT_NOT_LOOPBACK,
}

sealed interface GatewayEndpointDecision {
    data object Allowed : GatewayEndpointDecision
    data class Denied(val reason: GatewayEndpointDenialReason) : GatewayEndpointDecision
}

class GatewayEndpointPolicy private constructor(
    val buildMode: GatewayBuildMode,
    allowedHttpsOrigins: Set<GatewayEndpointOrigin>,
) {
    val allowedHttpsOrigins: Set<GatewayEndpointOrigin> = Collections.unmodifiableSet(allowedHttpsOrigins.toSortedSet())

    fun evaluate(origin: GatewayEndpointOrigin): GatewayEndpointDecision {
        if (origin.scheme == "https") {
            return if (origin in allowedHttpsOrigins) GatewayEndpointDecision.Allowed else
                GatewayEndpointDecision.Denied(GatewayEndpointDenialReason.ENDPOINT_NOT_ALLOWLISTED)
        }
        if (buildMode != GatewayBuildMode.DEBUG) {
            return GatewayEndpointDecision.Denied(GatewayEndpointDenialReason.CLEARTEXT_FORBIDDEN)
        }
        return if (origin.host in DEBUG_LOOPBACK_HOSTS) GatewayEndpointDecision.Allowed else
            GatewayEndpointDecision.Denied(GatewayEndpointDenialReason.CLEARTEXT_NOT_LOOPBACK)
    }

    companion object {
        private val DEBUG_LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

        fun create(
            buildMode: GatewayBuildMode,
            allowedHttpsOrigins: Set<GatewayEndpointOrigin>,
        ): GatewayAuthModelResult<GatewayEndpointPolicy> {
            if (allowedHttpsOrigins.any {
                    it.scheme != "https" || it.host == "localhost" || it.host.matches(Regex("^[0-9.]+$"))
                }
            ) return GatewayAuthModelResult.Failure(GatewayAuthModelError.INVALID_ENDPOINT_ALLOWLIST)
            return GatewayAuthModelResult.Success(GatewayEndpointPolicy(buildMode, allowedHttpsOrigins.toSet()))
        }
    }
}

enum class GatewayTokenState { ACTIVE, EXPIRED, REVOKED }

enum class GatewayTokenRevocationReason { LOGOUT, USER_REVOKED, GATEWAY_REJECTED, SECURITY_RECOVERY }

data class GatewayTokenMetadata internal constructor(
    val tokenId: GatewayTokenId,
    val clientSessionId: GatewayClientSessionId,
    val audience: GatewayEndpointOrigin,
    val scopes: Set<GatewayAuthScope>,
    val issuedAt: GatewayAuthEpochSeconds,
    val expiresAt: GatewayAuthEpochSeconds,
    val revision: Long,
    val revokedAt: GatewayAuthEpochSeconds?,
    val revocationReason: GatewayTokenRevocationReason?,
) {
    fun stateAt(now: GatewayAuthEpochSeconds): GatewayTokenState = when {
        revokedAt != null -> GatewayTokenState.REVOKED
        now >= expiresAt -> GatewayTokenState.EXPIRED
        else -> GatewayTokenState.ACTIVE
    }

    override fun toString(): String = "GatewayTokenMetadata(token=redacted, client=redacted, " +
        "audience=redacted, scopeCount=${scopes.size}, revision=$revision, state=${if (revokedAt == null) "stored" else "revoked"})"
}

class StoreGatewayTokenRequest private constructor(
    val metadata: GatewayTokenMetadata,
    internal val token: GatewayAccessToken,
) {
    companion object {
        const val MAXIMUM_LIFETIME_SECONDS = 15 * 60L
        const val MINIMUM_LIFETIME_SECONDS = 30L

        @Suppress("LongParameterList")
        fun create(
            tokenId: GatewayTokenId,
            clientSessionId: GatewayClientSessionId,
            audience: GatewayEndpointOrigin,
            scopes: Set<GatewayAuthScope>,
            issuedAt: GatewayAuthEpochSeconds,
            expiresAt: GatewayAuthEpochSeconds,
            token: GatewayAccessToken,
        ): GatewayAuthModelResult<StoreGatewayTokenRequest> {
            val lifetime = expiresAt.value - issuedAt.value
            if (scopes.isEmpty()) return GatewayAuthModelResult.Failure(GatewayAuthModelError.EMPTY_SCOPES)
            if (lifetime !in MINIMUM_LIFETIME_SECONDS..MAXIMUM_LIFETIME_SECONDS) {
                return GatewayAuthModelResult.Failure(GatewayAuthModelError.INVALID_TOKEN_LIFETIME)
            }
            return GatewayAuthModelResult.Success(
                StoreGatewayTokenRequest(
                    GatewayTokenMetadata(
                        tokenId,
                        clientSessionId,
                        audience,
                        Collections.unmodifiableSet(scopes.toSortedSet(compareBy { it.wireValue })),
                        issuedAt,
                        expiresAt,
                        revision = 1,
                        revokedAt = null,
                        revocationReason = null,
                    ),
                    token,
                ),
            )
        }
    }
}

data class GatewayAuthSnapshot(
    val metadata: GatewayTokenMetadata?,
    val observedAt: GatewayAuthEpochSeconds,
) {
    val hasActiveToken: Boolean = metadata?.stateAt(observedAt) == GatewayTokenState.ACTIVE
    val authenticatesGatewayNow: Boolean = false
    val grantsTrust: Boolean = false
    val grantsAuthorization: Boolean = false
    val approvesSafety: Boolean = false
    val establishesConnection: Boolean = false
    val executesCapability: Boolean = false
}

data class GatewayTokenMutationEvidence(
    val tokenId: GatewayTokenId,
    val revision: Long,
    val state: GatewayTokenState,
) {
    val grantsTrust: Boolean = false
    val grantsAuthorization: Boolean = false
    val approvesSafety: Boolean = false
    val executesCapability: Boolean = false

    override fun toString(): String = "GatewayTokenMutationEvidence(token=redacted, revision=$revision, state=$state)"
}

enum class GatewayAuthModelError {
    INVALID_TIMESTAMP,
    INVALID_IDENTIFIER,
    INVALID_TOKEN_SIZE,
    INVALID_ENDPOINT,
    INVALID_ENDPOINT_ALLOWLIST,
    EMPTY_SCOPES,
    INVALID_TOKEN_LIFETIME,
}

sealed interface GatewayAuthModelResult<out T> {
    data class Success<T>(val value: T) : GatewayAuthModelResult<T>
    data class Failure(val error: GatewayAuthModelError) : GatewayAuthModelResult<Nothing>
}

private fun <T> parseUuid(raw: String, create: (String) -> T): GatewayAuthModelResult<T> {
    val canonical = runCatching { UUID.fromString(raw).toString() }.getOrNull()
    return if (canonical == raw && raw[14] == '4' && raw[19] in "89ab") {
        GatewayAuthModelResult.Success(create(raw))
    } else {
        GatewayAuthModelResult.Failure(GatewayAuthModelError.INVALID_IDENTIFIER)
    }
}
