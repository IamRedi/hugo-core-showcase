package io.redi.robot.adapter.service.googlecalendar

import io.redi.robot.service.auth.CredentialBoundaryResult
import io.redi.robot.service.auth.CredentialDocumentReadResult
import io.redi.robot.service.auth.CredentialDocumentResetResult
import io.redi.robot.service.auth.CredentialDocumentWriteResult
import io.redi.robot.service.auth.CredentialEpochMillis
import io.redi.robot.service.auth.CredentialModelResult
import io.redi.robot.service.auth.ProtectedServiceCredentialStorage
import io.redi.robot.service.auth.ServiceCredentialBoundary
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCalendarReadOnlySandboxTest {
    @Test
    fun `selected profile is one exact observe-only H040 operation`() {
        val profile = GoogleCalendarReadOnlyProfile
        val operation = profile.contract.operation(profile.capability)

        assertEquals("google.calendar/service", profile.providerDefinitionId.value)
        assertEquals("calendar.events.list@1.0", profile.capability.wireValue)
        assertTrue(profile.descriptor.isReadOnly)
        assertEquals(setOf(profile.semanticScope), profile.contract.supportedScopes)
        assertEquals(setOf(profile.semanticScope), operation?.requiredScopes)
        assertEquals(profile.connectionMethod, operation?.connectionMethod)
        assertEquals("https://www.googleapis.com/auth/calendar.events.readonly", profile.OAUTH_SCOPE)
        assertFalse(profile.OAUTH_SCOPE.contains("calendar.events\""))
        assertFalse(profile.contract.operations.any { it.capability.wireValue.contains("create") })
    }

    @Test
    fun `request is bounded deterministic GET with no body or action route`() {
        val request = readRequest(1_700_000_000_000, 1_700_086_400_000, 7)
        val second = readRequest(1_700_000_000_000, 1_700_086_400_000, 7)

        assertEquals(request.uri, second.uri)
        assertTrue(request.uri.startsWith(GoogleCalendarReadOnlyProfile.EVENTS_ENDPOINT))
        assertTrue(request.uri.contains("singleEvents=true"))
        assertTrue(request.uri.contains("orderBy=startTime"))
        assertTrue(request.uri.contains("maxResults=7"))
        assertFalse(request.uri.contains("insert", ignoreCase = true))
        assertFalse(request.uri.contains("update", ignoreCase = true))
        assertEquals(GoogleCalendarHttpMethod.GET, GoogleCalendarHttpMethod.entries.single())
        assertFailure(GoogleCalendarReadRequest.create(10, 10, 1), GoogleCalendarFailure.INVALID_REQUEST)
        assertFailure(GoogleCalendarReadRequest.create(10, 20, 11), GoogleCalendarFailure.INVALID_REQUEST)
        assertFailure(
            GoogleCalendarReadRequest.create(0, GoogleCalendarReadRequest.MAXIMUM_WINDOW_MS + 1, 1),
            GoogleCalendarFailure.INVALID_REQUEST,
        )
    }

    @Test
    fun `connect stores exact scoped credential and recreated boundary reads redacted evidence`() {
        val storage = MemoryStorage()
        val transport = FakeTransport(successBody())
        val token = "access-token-never-log".toByteArray(StandardCharsets.UTF_8)
        val connection = sandbox(storage, transport).connect(
            token,
            setOf(GoogleCalendarReadOnlyProfile.OAUTH_SCOPE),
            time(1_000),
            time(20_000),
        ).success()

        val read = sandbox(storage, transport).read(
            connection,
            readRequest(1_700_000_000_000, 1_700_086_400_000, 3),
            time(2_000),
        ).success()

        assertEquals(2, read.eventCount)
        assertTrue(read.hasMore)
        assertEquals(0, read.retainedEventPayloads)
        assertEquals(GoogleCalendarHttpMethod.GET, transport.lastRequest?.method)
        assertTrue(transport.lastRequest?.acceptsJson == true)
        assertTrue(transport.observedTokenReference?.all { it == 0.toByte() } == true)
        assertFalse(read.toString().contains("Private appointment"))
        assertFalse(connection.toString().contains(connection.credentialId.value))
        assertFalse(connection.grantsTrust)
        assertFalse(connection.grantsAuthorization)
        assertFalse(connection.establishesConnection)
        assertFalse(connection.registersProvider)
        assertFalse(connection.executesCapability)
        token.fill(0)
    }

    @Test
    fun `connect rejects missing extra or write scope without storing`() {
        listOf(
            emptySet(),
            setOf(GoogleCalendarReadOnlyProfile.OAUTH_SCOPE, "https://www.googleapis.com/auth/userinfo.email"),
            setOf("https://www.googleapis.com/auth/calendar.events"),
        ).forEach { scopes ->
            val storage = MemoryStorage()
            val result = sandbox(storage, FakeTransport(successBody())).connect(
                byteArrayOf(1),
                scopes,
                time(1),
                time(10),
            )
            assertFailure(result, GoogleCalendarFailure.SCOPE_MISMATCH)
            assertEquals(CredentialDocumentReadResult.Missing, storage.read())
        }
    }

    @Test
    fun `credential failures fail closed and never invoke transport`() {
        val request = readRequest(100, 200, 1)
        val missingTransport = FakeTransport(successBody())
        val missingConnection = connectionFixture()
        assertFailure(
            sandbox(MemoryStorage(), missingTransport).read(missingConnection, request, time(2_000)),
            GoogleCalendarFailure.CREDENTIAL_MISSING,
        )
        assertEquals(0, missingTransport.readCalls)

        val storage = MemoryStorage()
        val transport = FakeTransport(successBody())
        val connection = sandbox(storage, transport).connect(
            byteArrayOf(1, 2, 3),
            setOf(GoogleCalendarReadOnlyProfile.OAUTH_SCOPE),
            time(1_000),
            time(2_000),
        ).success()
        assertFailure(
            sandbox(storage, transport).read(connection, request, time(2_000)),
            GoogleCalendarFailure.CREDENTIAL_EXPIRED,
        )
        assertEquals(0, transport.readCalls)

        storage.forcedRead = CredentialDocumentReadResult.Corrupt
        assertFailure(
            sandbox(storage, transport).read(connection, request, time(1_500)),
            GoogleCalendarFailure.STORAGE_FAILURE,
        )
        assertEquals(0, transport.readCalls)
    }

    @Test
    fun `malformed oversized and contradictory responses fail closed`() {
        val connectionAndStorage = connectFixture()
        val request = readRequest(100, 200, 1)
        listOf(
            "not-json",
            "{}",
            """{"kind":"wrong","items":[]}""",
            """{"kind":"calendar#events","items":[{},{}]}""",
            """{"kind":"calendar#events","items":[],"nextPageToken":""}""",
        ).forEach { body ->
            val result = sandbox(
                connectionAndStorage.storage,
                FakeTransport(body.toByteArray()),
            ).read(connectionAndStorage.connection, request, time(1_500))
            assertFailure(result, GoogleCalendarFailure.MALFORMED_RESPONSE)
        }
        assertFailure(
            GoogleCalendarWireResponse.copyOf(
                200,
                ByteArray(GoogleCalendarWireResponse.MAXIMUM_RESPONSE_BYTES + 1),
            ),
            GoogleCalendarFailure.MALFORMED_RESPONSE,
        )
    }

    @Test
    fun `remote status timeout cancellation and network failures remain typed`() {
        val fixture = connectFixture()
        val request = readRequest(100, 200, 1)
        mapOf(
            401 to GoogleCalendarFailure.CREDENTIAL_EXPIRED,
            403 to GoogleCalendarFailure.REMOTE_REJECTED,
            429 to GoogleCalendarFailure.RATE_LIMITED,
            500 to GoogleCalendarFailure.REMOTE_REJECTED,
        ).forEach { (status, failure) ->
            val transport = FakeTransport(successBody(), status)
            assertFailure(
                sandbox(fixture.storage, transport).read(fixture.connection, request, time(1_500)),
                failure,
            )
        }
        listOf(
            GoogleCalendarTransportResult.TimedOut to GoogleCalendarFailure.TIMEOUT,
            GoogleCalendarTransportResult.Cancelled to GoogleCalendarFailure.CANCELLED,
            GoogleCalendarTransportResult.Unavailable to GoogleCalendarFailure.NETWORK_UNAVAILABLE,
        ).forEach { (transportResult, failure) ->
            val transport = FakeTransport(successBody()).apply { forcedRead = transportResult }
            assertFailure(
                sandbox(fixture.storage, transport).read(fixture.connection, request, time(1_500)),
                failure,
            )
        }
    }

    @Test
    fun `revoke succeeds remotely before local H041 tombstone`() {
        val fixture = connectFixture()
        val transport = FakeTransport(successBody())
        val result = sandbox(fixture.storage, transport).revoke(fixture.connection, time(2_000)).success()

        assertTrue(result.remoteRevoked)
        assertTrue(result.localCredentialRevoked)
        assertEquals(1, transport.revokeCalls)
        assertTrue(transport.observedTokenReference?.all { it == 0.toByte() } == true)
        assertEquals(
            CredentialBoundaryResult.Revoked,
            ServiceCredentialBoundary(fixture.storage).withCredential(
                fixture.connection.credentialId,
                time(2_001),
            ) { error("revoked credential must not be exposed") },
        )
        assertFalse(result.grantsTrust)
        assertFalse(result.grantsAuthorization)
        assertFalse(result.executesCapability)
    }

    @Test
    fun `failed remote revoke preserves local credential for explicit retry`() {
        val fixture = connectFixture()
        val transport = FakeTransport(successBody()).apply { revokeStatus = 400 }
        assertFailure(
            sandbox(fixture.storage, transport).revoke(fixture.connection, time(2_000)),
            GoogleCalendarFailure.REMOTE_REJECTED,
        )
        val retryTransport = FakeTransport(successBody())
        val read = sandbox(fixture.storage, retryTransport).read(
            fixture.connection,
            readRequest(100, 200, 2),
            time(2_001),
        )
        assertTrue(read is GoogleCalendarSandboxResult.Success)
    }

    @Test
    fun `equivalent response order produces equivalent privacy preserving evidence`() {
        val fixture = connectFixture()
        val first = """{"kind":"calendar#events","items":[{"summary":"A"},{"summary":"B"}]}"""
        val second = """{"items":[{"summary":"B"},{"summary":"A"}],"kind":"calendar#events"}"""
        val request = readRequest(100, 200, 2)

        val a = sandbox(fixture.storage, FakeTransport(first.toByteArray())).read(
            fixture.connection,
            request,
            time(2_000),
        ).success()
        val b = sandbox(fixture.storage, FakeTransport(second.toByteArray())).read(
            fixture.connection,
            request,
            time(2_000),
        ).success()

        assertEquals(a, b)
        assertFalse(a.toString().contains("summary"))
        assertFalse(a.toString().contains("A"))
    }

    private data class ConnectedFixture(
        val storage: MemoryStorage,
        val connection: GoogleCalendarConnectionEvidence,
    )

    private fun connectFixture(): ConnectedFixture {
        val storage = MemoryStorage()
        val connection = sandbox(storage, FakeTransport(successBody())).connect(
            "token".toByteArray(),
            setOf(GoogleCalendarReadOnlyProfile.OAUTH_SCOPE),
            time(1_000),
            time(10_000),
        ).success()
        return ConnectedFixture(storage, connection)
    }

    private fun connectionFixture(): GoogleCalendarConnectionEvidence {
        val connected = connectFixture().connection
        return connected
    }

    private fun sandbox(
        storage: MemoryStorage,
        transport: GoogleCalendarReadOnlyTransport,
    ): GoogleCalendarReadOnlySandbox = GoogleCalendarReadOnlySandbox(
        ServiceCredentialBoundary(storage),
        transport,
    )

    private fun readRequest(start: Long, end: Long, maximum: Int): GoogleCalendarReadRequest =
        GoogleCalendarReadRequest.create(start, end, maximum).success()

    private fun successBody(): ByteArray = """
        {"kind":"calendar#events","nextPageToken":"redacted-by-adapter","items":[
          {"summary":"Private appointment","location":"private"},
          {"summary":"Another private event","attendees":[{"email":"private@example.test"}]}
        ]}
    """.trimIndent().toByteArray(StandardCharsets.UTF_8)

    private fun time(value: Long): CredentialEpochMillis =
        (CredentialEpochMillis.create(value) as CredentialModelResult.Success).value

    private fun <T> GoogleCalendarSandboxResult<T>.success(): T =
        (this as GoogleCalendarSandboxResult.Success).value

    private fun assertFailure(result: Any, expected: GoogleCalendarFailure) {
        val failure = result as GoogleCalendarSandboxResult.Failure
        assertEquals(expected, failure.reason)
    }

    private class FakeTransport(
        private val body: ByteArray,
        private val status: Int = 200,
    ) : GoogleCalendarReadOnlyTransport {
        var lastRequest: GoogleCalendarWireRequest? = null
        var observedTokenReference: ByteArray? = null
        var readCalls: Int = 0
        var revokeCalls: Int = 0
        var revokeStatus: Int = 200
        var forcedRead: GoogleCalendarTransportResult<GoogleCalendarWireResponse>? = null

        override fun execute(
            request: GoogleCalendarWireRequest,
            accessToken: ByteArray,
        ): GoogleCalendarTransportResult<GoogleCalendarWireResponse> {
            readCalls += 1
            lastRequest = request
            observedTokenReference = accessToken
            return forcedRead ?: GoogleCalendarTransportResult.Success(
                (GoogleCalendarWireResponse.copyOf(status, body) as
                    GoogleCalendarSandboxResult.Success).value,
            )
        }

        override fun revoke(accessToken: ByteArray): GoogleCalendarTransportResult<Int> {
            revokeCalls += 1
            observedTokenReference = accessToken
            return GoogleCalendarTransportResult.Success(revokeStatus)
        }
    }

    private class MemoryStorage : ProtectedServiceCredentialStorage {
        private var content: ByteArray? = null
        var forcedRead: CredentialDocumentReadResult? = null

        override fun read(): CredentialDocumentReadResult = forcedRead ?: content?.let {
            CredentialDocumentReadResult.Content(it.copyOf())
        } ?: CredentialDocumentReadResult.Missing

        override fun writeAtomically(plaintext: ByteArray): CredentialDocumentWriteResult {
            content?.fill(0)
            content = plaintext.copyOf()
            return CredentialDocumentWriteResult.COMMITTED
        }

        override fun reset(): CredentialDocumentResetResult {
            content?.fill(0)
            content = null
            forcedRead = null
            return CredentialDocumentResetResult.RESET
        }
    }
}
