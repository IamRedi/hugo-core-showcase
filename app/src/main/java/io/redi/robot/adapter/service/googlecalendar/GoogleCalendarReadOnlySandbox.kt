package io.redi.robot.adapter.service.googlecalendar

import io.redi.robot.capability.api.CapabilityApprovalRequirement
import io.redi.robot.capability.api.CapabilityAvailabilitySemantics
import io.redi.robot.capability.api.CapabilityCancellationSemantics
import io.redi.robot.capability.api.CapabilityContractReference
import io.redi.robot.capability.api.CapabilityDescription
import io.redi.robot.capability.api.CapabilityDescriptor
import io.redi.robot.capability.api.CapabilityDescriptorResult
import io.redi.robot.capability.api.CapabilityEffect
import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityLabel
import io.redi.robot.capability.api.CapabilityPermissionIntent
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.CapabilityRiskClass
import io.redi.robot.capability.api.CapabilitySafetyClass
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.connection.api.ConnectionMethodId
import io.redi.robot.connection.api.ConnectionMethodReference
import io.redi.robot.connection.api.ConnectionMethodVersion
import io.redi.robot.connection.api.ConnectionResult
import io.redi.robot.service.api.ServiceAuthenticationSchemeReference
import io.redi.robot.service.api.ServiceAuthorizationScopeReference
import io.redi.robot.service.api.ServiceConnectorContract
import io.redi.robot.service.api.ServiceConnectorResult
import io.redi.robot.service.api.ServiceConnectorSchemaVersion
import io.redi.robot.service.api.ServiceOperationDefinition
import io.redi.robot.service.auth.CredentialBoundaryResult
import io.redi.robot.service.auth.CredentialEpochMillis
import io.redi.robot.service.auth.CredentialModelResult
import io.redi.robot.service.auth.CredentialRecordRevision
import io.redi.robot.service.auth.CredentialStoreGeneration
import io.redi.robot.service.auth.ServiceCredentialBoundary
import io.redi.robot.service.auth.ServiceCredentialId
import io.redi.robot.service.auth.ServiceCredentialRevocationReason
import io.redi.robot.service.auth.ServiceCredentialSecret
import io.redi.robot.service.auth.StoreServiceCredentialRequest
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * H043's selected adapter profile. Vendor strings live here, never in H040/HUGO Core contracts.
 * This object declares one observe-only operation and does not authenticate, connect, or execute it.
 */
object GoogleCalendarReadOnlyProfile {
    const val OAUTH_SCOPE: String = "https://www.googleapis.com/auth/calendar.events.readonly"
    const val EVENTS_ENDPOINT: String =
        "https://www.googleapis.com/calendar/v3/calendars/primary/events"
    const val REVOCATION_ENDPOINT: String = "https://oauth2.googleapis.com/revoke"
    const val MAXIMUM_RESULTS: Int = 10

    val providerDefinitionId: ProviderDefinitionId = ProviderDefinitionId.parse(
        "google.calendar/service",
    ).providerValue()
    val capability: CapabilityReference = CapabilityReference.parse(
        "calendar.events.list@1.0",
    ).identityValue()
    val authenticationScheme: ServiceAuthenticationSchemeReference =
        ServiceAuthenticationSchemeReference.parse("auth.standard/oauth@1.0").serviceValue()
    val semanticScope: ServiceAuthorizationScopeReference =
        ServiceAuthorizationScopeReference.parse(
            "scope.calendar/events_readonly@1.0",
        ).serviceValue()
    val connectionMethod: ConnectionMethodReference = ConnectionMethodReference(
        ConnectionMethodId.parse("network.http/request-response").connectionValue(),
        ConnectionMethodVersion.parse("1.0").connectionValue(),
    )
    val descriptor: CapabilityDescriptor = CapabilityDescriptor.create(
        reference = capability,
        label = CapabilityLabel.parse("List calendar events").descriptorValue(),
        description = CapabilityDescription.parse(
            "Observe a bounded window of calendar events without modifying the calendar.",
        ).descriptorValue(),
        inputContract = CapabilityContractReference.parse(
            "calendar.events.list.request@1.0",
        ).descriptorValue(),
        outputContract = CapabilityContractReference.parse(
            "calendar.events.list.observation@1.0",
        ).descriptorValue(),
        effect = CapabilityEffect.OBSERVE,
        risk = CapabilityRiskClass.INFORMATIONAL,
        safety = CapabilitySafetyClass.NON_PHYSICAL,
        permissionIntent = CapabilityPermissionIntent.OBSERVE,
        approvalRequirement = CapabilityApprovalRequirement.WITHIN_GRANT,
        cancellation = CapabilityCancellationSemantics.SUPPORTED,
        availability = CapabilityAvailabilitySemantics.SESSION_SCOPED,
        evidence = CapabilityEvidenceClass.AUTOMATED_TESTED,
    ).descriptorValue()
    val contract: ServiceConnectorContract = ServiceConnectorContract.create(
        schemaVersion = ServiceConnectorSchemaVersion.parse("1.0").serviceValue(),
        providerDefinitionId = providerDefinitionId,
        authenticationScheme = authenticationScheme,
        supportedScopes = setOf(semanticScope),
        operations = listOf(
            ServiceOperationDefinition.create(
                capability = capability,
                requiredScopes = setOf(semanticScope),
                connectionMethod = connectionMethod,
                ratePolicyId = null,
                rateCostUnits = 0,
            ).serviceValue(),
        ),
        ratePolicies = emptyList(),
    ).serviceValue()
}

class GoogleCalendarReadRequest private constructor(
    val timeMinEpochMs: Long,
    val timeMaxEpochMs: Long,
    val maximumResults: Int,
) {
    val uri: String = buildString {
        append(GoogleCalendarReadOnlyProfile.EVENTS_ENDPOINT)
        append("?singleEvents=true&orderBy=startTime&showDeleted=false")
        append("&maxResults=")
        append(maximumResults)
        append("&timeMin=")
        append(Instant.ofEpochMilli(timeMinEpochMs).toString())
        append("&timeMax=")
        append(Instant.ofEpochMilli(timeMaxEpochMs).toString())
    }

    companion object {
        const val MAXIMUM_WINDOW_MS: Long = 31L * 24L * 60L * 60L * 1_000L

        fun create(
            timeMinEpochMs: Long,
            timeMaxEpochMs: Long,
            maximumResults: Int,
        ): GoogleCalendarSandboxResult<GoogleCalendarReadRequest> = when {
            timeMinEpochMs < 0L || timeMaxEpochMs <= timeMinEpochMs ->
                GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.INVALID_REQUEST)
            timeMaxEpochMs - timeMinEpochMs > MAXIMUM_WINDOW_MS ->
                GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.INVALID_REQUEST)
            maximumResults !in 1..GoogleCalendarReadOnlyProfile.MAXIMUM_RESULTS ->
                GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.INVALID_REQUEST)
            else -> GoogleCalendarSandboxResult.Success(
                GoogleCalendarReadRequest(timeMinEpochMs, timeMaxEpochMs, maximumResults),
            )
        }
    }
}

enum class GoogleCalendarHttpMethod { GET }

data class GoogleCalendarWireRequest(
    val method: GoogleCalendarHttpMethod,
    val uri: String,
    val acceptsJson: Boolean,
) {
    init {
        require(method == GoogleCalendarHttpMethod.GET)
        require(uri.startsWith(GoogleCalendarReadOnlyProfile.EVENTS_ENDPOINT))
        require(acceptsJson)
    }
}

class GoogleCalendarWireResponse private constructor(
    val statusCode: Int,
    private val content: ByteArray,
) : AutoCloseable {
    fun <T> withContent(block: (ByteArray) -> T): T {
        val transient = content.copyOf()
        return try {
            block(transient)
        } finally {
            transient.fill(0)
        }
    }

    override fun close() = content.fill(0)

    override fun toString(): String = "GoogleCalendarWireResponse(status=$statusCode, body=redacted)"

    companion object {
        const val MAXIMUM_RESPONSE_BYTES: Int = 256 * 1024

        fun copyOf(statusCode: Int, content: ByteArray): GoogleCalendarSandboxResult<GoogleCalendarWireResponse> =
            if (statusCode !in 100..599 || content.size > MAXIMUM_RESPONSE_BYTES) {
                GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.MALFORMED_RESPONSE)
            } else {
                GoogleCalendarSandboxResult.Success(GoogleCalendarWireResponse(statusCode, content.copyOf()))
            }
    }
}

sealed interface GoogleCalendarTransportResult<out T> {
    data class Success<T>(val value: T) : GoogleCalendarTransportResult<T>
    data object TimedOut : GoogleCalendarTransportResult<Nothing>
    data object Cancelled : GoogleCalendarTransportResult<Nothing>
    data object Unavailable : GoogleCalendarTransportResult<Nothing>
}

/** Adapter-specific transport. Implementations may use HTTP/SDK types internally only. */
interface GoogleCalendarReadOnlyTransport {
    fun execute(
        request: GoogleCalendarWireRequest,
        accessToken: ByteArray,
    ): GoogleCalendarTransportResult<GoogleCalendarWireResponse>

    fun revoke(accessToken: ByteArray): GoogleCalendarTransportResult<Int>
}

enum class GoogleCalendarFailure {
    INVALID_REQUEST,
    SCOPE_MISMATCH,
    CREDENTIAL_MISSING,
    CREDENTIAL_EXPIRED,
    CREDENTIAL_REVOKED,
    STORAGE_FAILURE,
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    CANCELLED,
    REMOTE_REJECTED,
    RATE_LIMITED,
    MALFORMED_RESPONSE,
}

sealed interface GoogleCalendarSandboxResult<out T> {
    data class Success<T>(val value: T) : GoogleCalendarSandboxResult<T>
    data class Failure(
        val reason: GoogleCalendarFailure,
        val remoteRevoked: Boolean = false,
    ) : GoogleCalendarSandboxResult<Nothing>
}

data class GoogleCalendarConnectionEvidence(
    val credentialId: ServiceCredentialId,
    val storeGeneration: CredentialStoreGeneration,
    val recordRevision: CredentialRecordRevision,
    val expiresAt: CredentialEpochMillis,
) {
    val authenticatedScopeCount: Int = 1
    val grantsTrust: Boolean = false
    val grantsAuthorization: Boolean = false
    val establishesConnection: Boolean = false
    val registersProvider: Boolean = false
    val executesCapability: Boolean = false

    override fun toString(): String =
        "GoogleCalendarConnectionEvidence(credential=redacted, scopeCount=1, authority=false)"
}

data class GoogleCalendarReadEvidence(
    val eventCount: Int,
    val hasMore: Boolean,
    val observedAt: CredentialEpochMillis,
) {
    val retainedEventPayloads: Int = 0
    val grantsTrust: Boolean = false
    val grantsAuthorization: Boolean = false
    val establishesConnection: Boolean = false
    val registersProvider: Boolean = false

    override fun toString(): String =
        "GoogleCalendarReadEvidence(eventCount=$eventCount, hasMore=$hasMore, payload=discarded)"
}

data class GoogleCalendarRevocationEvidence(
    val remoteRevoked: Boolean,
    val localCredentialRevoked: Boolean,
    val storeGeneration: CredentialStoreGeneration,
    val recordRevision: CredentialRecordRevision,
) {
    val grantsTrust: Boolean = false
    val grantsAuthorization: Boolean = false
    val establishesConnection: Boolean = false
    val executesCapability: Boolean = false
}

/**
 * Isolated feasibility orchestrator. It uses H041 for token lifetime and H040 for declarations,
 * but is deliberately not registered or wired into production provider/runtime composition.
 */
class GoogleCalendarReadOnlySandbox(
    private val credentials: ServiceCredentialBoundary,
    private val transport: GoogleCalendarReadOnlyTransport,
) {
    fun connect(
        tokenBytes: ByteArray,
        grantedExternalScopes: Set<String>,
        createdAt: CredentialEpochMillis,
        expiresAt: CredentialEpochMillis,
    ): GoogleCalendarSandboxResult<GoogleCalendarConnectionEvidence> {
        if (grantedExternalScopes != setOf(GoogleCalendarReadOnlyProfile.OAUTH_SCOPE)) {
            return GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.SCOPE_MISMATCH)
        }
        val snapshot = when (val result = credentials.snapshot(createdAt)) {
            is CredentialBoundaryResult.Success -> result.value
            else -> return result.toSandboxFailure()
        }
        val secret = when (val result = ServiceCredentialSecret.copyOf(tokenBytes)) {
            is CredentialModelResult.Success -> result.value
            is CredentialModelResult.Failure ->
                return GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.INVALID_REQUEST)
        }
        val id = ServiceCredentialId.newReference()
        return try {
            val request = when (
                val result = StoreServiceCredentialRequest.create(
                    id = id,
                    providerDefinitionId = GoogleCalendarReadOnlyProfile.providerDefinitionId,
                    authenticationScheme = GoogleCalendarReadOnlyProfile.authenticationScheme,
                    scopes = setOf(GoogleCalendarReadOnlyProfile.semanticScope),
                    createdAt = createdAt,
                    expiresAt = expiresAt,
                    secret = secret,
                )
            ) {
                is CredentialModelResult.Success -> result.value
                is CredentialModelResult.Failure ->
                    return GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.INVALID_REQUEST)
            }
            when (val stored = credentials.store(request, snapshot.generation)) {
                is CredentialBoundaryResult.Success -> GoogleCalendarSandboxResult.Success(
                    GoogleCalendarConnectionEvidence(
                        id,
                        stored.value.generation,
                        stored.value.recordRevision,
                        expiresAt,
                    ),
                )
                else -> stored.toSandboxFailure()
            }
        } finally {
            secret.close()
        }
    }

    fun read(
        connection: GoogleCalendarConnectionEvidence,
        request: GoogleCalendarReadRequest,
        now: CredentialEpochMillis,
    ): GoogleCalendarSandboxResult<GoogleCalendarReadEvidence> = when (
        val access = credentials.withCredential(connection.credentialId, now) { token ->
            val wire = GoogleCalendarWireRequest(
                GoogleCalendarHttpMethod.GET,
                request.uri,
                acceptsJson = true,
            )
            transport.execute(wire, token).toReadResult(request, now)
        }
    ) {
        is CredentialBoundaryResult.Success -> access.value
        else -> access.toSandboxFailure()
    }

    fun revoke(
        connection: GoogleCalendarConnectionEvidence,
        now: CredentialEpochMillis,
    ): GoogleCalendarSandboxResult<GoogleCalendarRevocationEvidence> {
        val remote = when (
            val access = credentials.withCredential(connection.credentialId, now) { token ->
                transport.revoke(token)
            }
        ) {
            is CredentialBoundaryResult.Success -> access.value
            else -> return access.toSandboxFailure()
        }
        when (remote) {
            GoogleCalendarTransportResult.Cancelled ->
                return GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.CANCELLED)
            GoogleCalendarTransportResult.TimedOut ->
                return GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.TIMEOUT)
            GoogleCalendarTransportResult.Unavailable ->
                return GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.NETWORK_UNAVAILABLE)
            is GoogleCalendarTransportResult.Success -> if (remote.value != 200) {
                return GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.REMOTE_REJECTED)
            }
        }
        return when (
            val local = credentials.revoke(
                connection.credentialId,
                connection.storeGeneration,
                connection.recordRevision,
                now,
                ServiceCredentialRevocationReason.USER_REVOKED,
            )
        ) {
            is CredentialBoundaryResult.Success -> GoogleCalendarSandboxResult.Success(
                GoogleCalendarRevocationEvidence(
                    remoteRevoked = true,
                    localCredentialRevoked = true,
                    storeGeneration = local.value.generation,
                    recordRevision = local.value.recordRevision,
                ),
            )
            else -> GoogleCalendarSandboxResult.Failure(
                GoogleCalendarFailure.STORAGE_FAILURE,
                remoteRevoked = true,
            )
        }
    }
}

private fun GoogleCalendarTransportResult<GoogleCalendarWireResponse>.toReadResult(
    request: GoogleCalendarReadRequest,
    now: CredentialEpochMillis,
): GoogleCalendarSandboxResult<GoogleCalendarReadEvidence> = when (this) {
    GoogleCalendarTransportResult.Cancelled ->
        GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.CANCELLED)
    GoogleCalendarTransportResult.TimedOut ->
        GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.TIMEOUT)
    GoogleCalendarTransportResult.Unavailable ->
        GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.NETWORK_UNAVAILABLE)
    is GoogleCalendarTransportResult.Success -> value.use { response ->
        when (response.statusCode) {
            200 -> response.withContent { parseReadEvidence(it, request, now) }
            401 -> GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.CREDENTIAL_EXPIRED)
            403 -> GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.REMOTE_REJECTED)
            429 -> GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.RATE_LIMITED)
            else -> GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.REMOTE_REJECTED)
        }
    }
}

private fun parseReadEvidence(
    bytes: ByteArray,
    request: GoogleCalendarReadRequest,
    now: CredentialEpochMillis,
): GoogleCalendarSandboxResult<GoogleCalendarReadEvidence> {
    if (bytes.isEmpty() || bytes.size > GoogleCalendarWireResponse.MAXIMUM_RESPONSE_BYTES) {
        return GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.MALFORMED_RESPONSE)
    }
    val root = runCatching {
        Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)) as? JsonObject
    }.getOrNull() ?: return GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.MALFORMED_RESPONSE)
    val kind = (root["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val items = root["items"] as? JsonArray
        ?: return GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.MALFORMED_RESPONSE)
    val nextPage = root["nextPageToken"]
    if (kind != "calendar#events" || items.size > request.maximumResults) {
        return GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.MALFORMED_RESPONSE)
    }
    if (nextPage != null && (nextPage !is JsonPrimitive || !nextPage.isString || nextPage.content.isBlank())) {
        return GoogleCalendarSandboxResult.Failure(GoogleCalendarFailure.MALFORMED_RESPONSE)
    }
    return GoogleCalendarSandboxResult.Success(
        GoogleCalendarReadEvidence(items.size, nextPage != null, now),
    )
}

private fun CredentialBoundaryResult<*>.toSandboxFailure(): GoogleCalendarSandboxResult.Failure =
    GoogleCalendarSandboxResult.Failure(
        when (this) {
            CredentialBoundaryResult.Absent -> GoogleCalendarFailure.CREDENTIAL_MISSING
            CredentialBoundaryResult.Expired -> GoogleCalendarFailure.CREDENTIAL_EXPIRED
            CredentialBoundaryResult.Revoked -> GoogleCalendarFailure.CREDENTIAL_REVOKED
            CredentialBoundaryResult.InvalidRequest -> GoogleCalendarFailure.INVALID_REQUEST
            is CredentialBoundaryResult.Success -> error("Success is not a failure")
            else -> GoogleCalendarFailure.STORAGE_FAILURE
        },
    )

private fun <T> ProviderIdentityResult<T>.providerValue(): T =
    (this as ProviderIdentityResult.Success).value

private fun <T> CapabilityIdentityResult<T>.identityValue(): T =
    (this as CapabilityIdentityResult.Success).value

private fun <T> CapabilityDescriptorResult<T>.descriptorValue(): T =
    (this as CapabilityDescriptorResult.Success).value

private fun <T> ConnectionResult<T>.connectionValue(): T =
    (this as ConnectionResult.Success).value

private fun <T> ServiceConnectorResult<T>.serviceValue(): T =
    (this as ServiceConnectorResult.Success).value
