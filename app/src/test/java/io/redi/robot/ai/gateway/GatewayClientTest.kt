package io.redi.robot.ai.gateway

import io.redi.robot.gateway.auth.GatewayAccessToken
import io.redi.robot.gateway.auth.GatewayAuthEpochSeconds
import io.redi.robot.gateway.auth.GatewayAuthModelResult
import io.redi.robot.gateway.auth.GatewayAuthScope
import io.redi.robot.gateway.auth.GatewayBuildMode
import io.redi.robot.gateway.auth.GatewayClientSessionId
import io.redi.robot.gateway.auth.GatewayEndpointOrigin
import io.redi.robot.gateway.auth.GatewayEndpointPolicy
import io.redi.robot.gateway.auth.GatewayTokenDocumentReadResult
import io.redi.robot.gateway.auth.GatewayTokenDocumentResetResult
import io.redi.robot.gateway.auth.GatewayTokenDocumentWriteResult
import io.redi.robot.gateway.auth.GatewayTokenId
import io.redi.robot.gateway.auth.GatewayTokenRepository
import io.redi.robot.gateway.auth.ProtectedGatewayTokenStorage
import io.redi.robot.gateway.auth.StoreGatewayTokenRequest
import io.redi.robot.gateway.contract.GatewayCancellationId
import io.redi.robot.gateway.contract.GatewayCancellationReason
import io.redi.robot.gateway.contract.GatewayContractCodec
import io.redi.robot.gateway.contract.GatewayContractResult
import io.redi.robot.gateway.contract.GatewayEventPayload
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayClientTest {
    @Test
    fun `fake stream reduces partial text usage and completion then closes exactly once`() {
        val harness = Harness()
        val request = textRequest()
        harness.channel.enqueue(
            frame(event(0, "text", "{\"type\":\"started\"}")),
            frame(event(1, "text", "{\"type\":\"partial_text\",\"text\":\"Hello\"}")),
            frame(event(2, "text", "{\"type\":\"usage\",\"evidence\":\"unknown\"}")),
            frame(event(3, "text", "{\"type\":\"completed\",\"reason\":\"complete\"}")),
        )

        val started = harness.start(request)
        assertTrue(started is GatewayClientStartResult.Started)
        assertTrue(harness.transport.tokenReference!!.all { it == 0.toByte() })
        assertTrue(harness.transport.requestReference!!.all { it == 0.toByte() })
        assertArrayEquals(harness.tokenBytes, harness.transport.tokenCopy)
        assertEquals(GatewayContractCodec.encodeRequest(request), harness.transport.requestCopy!!.toString(StandardCharsets.UTF_8))

        repeat(3) { assertTrue(harness.client.poll(time((101 + it).toLong())) is GatewayClientPollResult.Active) }
        val terminal = (harness.client.poll(time(104)) as GatewayClientPollResult.Terminal).report

        assertEquals(GatewayClientTerminalStatus.COMPLETED, terminal.status)
        assertTrue(terminal.gatewayTerminalConfirmed)
        assertEquals(listOf("Hello"), terminal.stream.partialText.map { it.value })
        assertEquals(1, terminal.stream.usage?.let { 1 })
        assertEquals(1, harness.channel.closeCalls)
        assertFalse(terminal.grantsAuthorization)
        assertFalse(terminal.executesSkill)
        assertFalse(terminal.executesCapability)
        assertFalse(terminal.toString().contains("Hello"))
    }

    @Test
    fun `tool proposal remains buffered untrusted output with no execution`() {
        val harness = Harness()
        val request = textRequest(skills = "[\"hugo.local.connection_status@1.0\"]")
        harness.channel.enqueue(
            frame(event(0, "text", "{\"type\":\"started\"}")),
            frame(
                event(
                    1,
                    "text",
                    "{\"type\":\"tool_proposal\",\"proposalId\":\"30000000-0000-4000-8000-000000000001\",\"skill\":\"hugo.local.connection_status@1.0\",\"arguments\":{}}",
                ),
            ),
            frame(event(2, "text", "{\"type\":\"completed\",\"reason\":\"complete\"}")),
        )
        harness.start(request)
        harness.client.poll(time(101))
        harness.client.poll(time(102))
        val report = (harness.client.poll(time(103)) as GatewayClientPollResult.Terminal).report

        assertEquals("hugo.local.connection_status@1.0", report.stream.toolProposals.single().skill.wireValue)
        assertTrue(report.redactedTrace().any { it.contains("TOOL_PROPOSAL:authority=false") })
        assertFalse(report.stream.executesSkill)
        assertFalse(report.stream.grantsAuthorization)
    }

    @Test
    fun `all gateway terminal outcomes remain distinct and confirmed`() {
        val outcomes = listOf(
            "{\"type\":\"refusal\",\"reason\":\"policy\"}" to GatewayClientTerminalStatus.REFUSED,
            "{\"type\":\"failed\",\"code\":\"unavailable\",\"retryable\":true}" to
                GatewayClientTerminalStatus.FAILED,
            "{\"type\":\"cancelled\",\"reason\":\"user\"}" to GatewayClientTerminalStatus.GATEWAY_CANCELLED,
            "{\"type\":\"timed_out\"}" to GatewayClientTerminalStatus.GATEWAY_TIMED_OUT,
        )
        outcomes.forEach { (payload, expected) ->
            val harness = Harness()
            harness.channel.enqueue(
                frame(event(0, "text", "{\"type\":\"started\"}")),
                frame(event(1, "text", payload)),
            )
            harness.start(textRequest())
            harness.client.poll(time(101))
            val report = (harness.client.poll(time(102)) as GatewayClientPollResult.Terminal).report
            assertEquals(expected, report.status)
            assertTrue(report.gatewayTerminalConfirmed)
            assertEquals(1, harness.channel.closeCalls)
        }
    }

    @Test
    fun `out of order duplicate wrong correlation and malformed frames fail protocol closed`() {
        val cases = listOf(
            event(1, "text", "{\"type\":\"started\"}"),
            event(0, "text", "{\"type\":\"partial_text\",\"text\":\"x\"}"),
            event(0, "text", "{\"type\":\"started\"}", requestId = OTHER_REQUEST_ID),
            "not-json",
        )
        cases.forEach { document ->
            val harness = Harness()
            harness.channel.enqueue(frame(document))
            harness.start(textRequest())

            val report = (harness.client.poll(time(101)) as GatewayClientPollResult.Terminal).report

            assertEquals(GatewayClientTerminalStatus.PROTOCOL_ERROR, report.status)
            assertFalse(report.gatewayTerminalConfirmed)
            assertEquals(1, harness.channel.closeCalls)
        }

        val invalidUtf8 = Harness()
        invalidUtf8.channel.enqueue(frame(byteArrayOf(0xC3.toByte(), 0x28)))
        invalidUtf8.start(textRequest())
        assertEquals(
            GatewayClientTerminalStatus.PROTOCOL_ERROR,
            (invalidUtf8.client.poll(time(101)) as GatewayClientPollResult.Terminal).report.status,
        )
    }

    @Test
    fun `local cancellation sends typed document closes once and never invents gateway confirmation`() {
        val harness = Harness()
        harness.start(textRequest())

        val report = (harness.client.cancel(GatewayCancellationReason.BARGE_IN) as
            GatewayClientCancelResult.Cancelled).report

        assertEquals(GatewayClientTerminalStatus.CLIENT_CANCELLED, report.status)
        assertFalse(report.gatewayTerminalConfirmed)
        assertEquals(GatewayTransportCancellationDelivery.DELIVERED, report.cancellationDelivery)
        assertEquals(1, harness.channel.cancelCalls)
        assertEquals(1, harness.channel.closeCalls)
        val cancellation = successfulContract(
            GatewayContractCodec.decodeCancellation(harness.channel.cancellationCopy!!.toString(StandardCharsets.UTF_8)),
        )
        assertEquals(GatewayCancellationReason.BARGE_IN, cancellation.reason)
        assertEquals(REQUEST_ID, cancellation.requestId.value)
        assertTrue(harness.channel.cancellationReference!!.all { it == 0.toByte() })

        val repeated = harness.client.cancel(GatewayCancellationReason.USER)
        assertTrue(repeated is GatewayClientCancelResult.AlreadyTerminal)
        assertEquals(1, harness.channel.cancelCalls)
        assertEquals(1, harness.channel.closeCalls)
    }

    @Test
    fun `timeout fires at exact monotonic deadline before reading and closes safely`() {
        val harness = Harness()
        harness.start(textRequest(timeoutMs = 1_000), monotonic = 100)
        assertTrue(harness.client.poll(time(1_099)) is GatewayClientPollResult.Active)

        val report = (harness.client.poll(time(1_100)) as GatewayClientPollResult.Terminal).report

        assertEquals(GatewayClientTerminalStatus.CLIENT_TIMED_OUT, report.status)
        assertFalse(report.gatewayTerminalConfirmed)
        assertEquals(1, harness.channel.readCalls)
        assertEquals(1, harness.channel.cancelCalls)
        assertEquals(1, harness.channel.closeCalls)
        val cancellation = successfulContract(
            GatewayContractCodec.decodeCancellation(harness.channel.cancellationCopy!!.toString(StandardCharsets.UTF_8)),
        )
        assertEquals(GatewayCancellationReason.DEADLINE, cancellation.reason)
    }

    @Test
    fun `authentication absence expiry scope audience and endpoint failures never open transport`() {
        val absent = Harness(storeToken = false)
        assertEquals(
            GatewayClientStartResult.Rejected(GatewayClientStartFailure.TOKEN_ABSENT),
            absent.start(textRequest()),
        )
        val expired = Harness(expiresAt = 1_100)
        assertEquals(
            GatewayClientStartResult.Rejected(GatewayClientStartFailure.TOKEN_EXPIRED),
            expired.start(textRequest(), authNow = 1_100),
        )
        val scope = Harness(scopes = setOf(GatewayAuthScope.TEXT))
        assertEquals(
            GatewayClientStartResult.Rejected(GatewayClientStartFailure.INSUFFICIENT_SCOPE),
            scope.start(visionRequest()),
        )
        val audience = Harness(
            policyOrigins = setOf(endpoint(), endpoint("https://other.test")),
        )
        assertEquals(
            GatewayClientStartResult.Rejected(GatewayClientStartFailure.AUDIENCE_MISMATCH),
            audience.start(textRequest(), endpoint = endpoint("https://other.test")),
        )
        val denied = Harness()
        assertEquals(
            GatewayClientStartResult.Rejected(GatewayClientStartFailure.ENDPOINT_DENIED),
            denied.start(textRequest(), endpoint = endpoint("https://denied.test")),
        )
        listOf(absent, expired, scope, audience, denied).forEach { assertEquals(0, it.transport.openCalls) }
    }

    @Test
    fun `transport open read and premature end failures stay explicit`() {
        GatewayTransportOpenFailure.entries.forEach { failure ->
            val harness = Harness(openResult = GatewayTransportOpenResult.Rejected(failure))
            val result = harness.start(textRequest()) as GatewayClientStartResult.Rejected
            assertNotNull(result.reason)
            assertEquals(0, harness.channel.closeCalls)
        }

        val readFailure = Harness()
        readFailure.channel.enqueue(GatewayStreamReadResult.Failure(GatewayTransportReadFailure.CONNECTION_LOST))
        readFailure.start(textRequest())
        val transportReport = (readFailure.client.poll(time(101)) as GatewayClientPollResult.Terminal).report
        assertEquals(GatewayClientTerminalStatus.TRANSPORT_FAILURE, transportReport.status)
        assertEquals(GatewayTransportReadFailure.CONNECTION_LOST, transportReport.transportFailure)

        val ended = Harness()
        ended.channel.enqueue(GatewayStreamReadResult.EndOfStream)
        ended.start(textRequest())
        val endedReport = (ended.client.poll(time(101)) as GatewayClientPollResult.Terminal).report
        assertEquals(GatewayClientTerminalStatus.PROTOCOL_ERROR, endedReport.status)
        assertFalse(endedReport.gatewayTerminalConfirmed)
    }

    @Test
    fun `text buffer holds exact limit and rejects the next partial without unbounded growth`() {
        val harness = Harness()
        harness.channel.enqueue(frame(event(0, "text", "{\"type\":\"started\"}")))
        (1..9).forEach { sequence ->
            harness.channel.enqueue(
                frame(event(sequence, "text", "{\"type\":\"partial_text\",\"text\":\"${"x".repeat(8_192)}\"}")),
            )
        }
        harness.start(textRequest())
        repeat(9) { assertTrue(harness.client.poll(time((101 + it).toLong())) is GatewayClientPollResult.Active) }

        val report = (harness.client.poll(time(110)) as GatewayClientPollResult.Terminal).report

        assertEquals(GatewayClientTerminalStatus.PROTOCOL_ERROR, report.status)
        assertEquals(GatewayStreamReductionError.TEXT_BUFFER_EXCEEDED, report.protocolError)
        assertEquals(GatewayStreamReducer.MAXIMUM_TEXT_BYTES, report.stream.partialTextBytes)
        assertEquals(8, report.stream.partialText.size)
    }

    @Test
    fun `undeclared tools duplicate usage and invalid embedding evidence are rejected`() {
        val undeclared = reduce(
            textRequest(),
            eventObject(0, "text", "{\"type\":\"started\"}"),
            eventObject(
                1,
                "text",
                "{\"type\":\"tool_proposal\",\"proposalId\":\"30000000-0000-4000-8000-000000000001\",\"skill\":\"hugo.local.connection_status@1.0\",\"arguments\":{}}",
            ),
        )
        assertEquals(GatewayStreamReductionError.UNDECLARED_SKILL_PROPOSAL, undeclared.error)

        val duplicateUsage = reduce(
            textRequest(),
            eventObject(0, "text", "{\"type\":\"started\"}"),
            eventObject(1, "text", "{\"type\":\"usage\",\"evidence\":\"unknown\"}"),
            eventObject(2, "text", "{\"type\":\"usage\",\"evidence\":\"unknown\"}"),
        )
        assertEquals(GatewayStreamReductionError.DUPLICATE_USAGE, duplicateUsage.error)

        val invalidEmbedding = reduce(
            embeddingRequest(),
            eventObject(0, "embedding", "{\"type\":\"started\"}"),
            eventObject(1, "embedding", "{\"type\":\"embedding\",\"inputIndex\":1,\"vector\":[0.1]}"),
        )
        assertEquals(GatewayStreamReductionError.INVALID_EMBEDDING_INDEX, invalidEmbedding.error)
    }

    @Test
    fun `busy client rejects overlap and a new request may start only after terminal cleanup`() {
        val harness = Harness()
        harness.channel.enqueue(
            frame(event(0, "text", "{\"type\":\"started\"}")),
            frame(event(1, "text", "{\"type\":\"completed\",\"reason\":\"complete\"}")),
        )
        assertTrue(harness.start(textRequest()) is GatewayClientStartResult.Started)
        assertEquals(
            GatewayClientStartResult.Rejected(GatewayClientStartFailure.BUSY),
            harness.start(textRequest()),
        )
        harness.client.poll(time(101))
        harness.client.poll(time(102))

        harness.transport.channel = FakeChannel()
        assertTrue(harness.start(textRequest()) is GatewayClientStartResult.Started)
        assertEquals(2, harness.transport.openCalls)
    }

    @Test
    fun `equivalent streams produce equivalent redacted reports`() {
        fun run(): GatewayClientTerminalReport {
            val harness = Harness()
            harness.channel.enqueue(
                frame(event(0, "text", "{\"type\":\"started\"}")),
                frame(event(1, "text", "{\"type\":\"partial_text\",\"text\":\"same\"}")),
                frame(event(2, "text", "{\"type\":\"completed\",\"reason\":\"complete\"}")),
            )
            harness.start(textRequest())
            harness.client.poll(time(101))
            harness.client.poll(time(102))
            return (harness.client.poll(time(103)) as GatewayClientPollResult.Terminal).report
        }

        val first = run()
        val second = run()
        assertEquals(first.status, second.status)
        assertEquals(first.stream.partialText.map { it.value }, second.stream.partialText.map { it.value })
        assertEquals(first.redactedTrace(), second.redactedTrace())
    }

    @Test
    fun `client source contains no persistence provider SDK raw robot or execution coupling`() {
        val sourceRoot = projectFile("src/main/java/io/redi/robot/ai/gateway")
        val source = sourceRoot.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        listOf(
            "import android.",
            "import androidx.",
            "okhttp3.",
            "retrofit2.",
            "SharedPreferences",
            "RoomDatabase",
            "FileOutputStream",
            "BluetoothGatt",
            "FED9",
            "CapabilityRequest",
            "AuthorizationDecision",
            "SafetyController",
            "io.redi.robot.body.looi",
        ).forEach { assertFalse("forbidden H053 coupling: $it", source.contains(it)) }
        assertFalse(source.contains("println("))
        assertFalse(source.contains("Log."))
    }

    private inner class Harness(
        storeToken: Boolean = true,
        expiresAt: Long = 1_600,
        scopes: Set<GatewayAuthScope> = GatewayAuthScope.entries.toSet(),
        policyOrigins: Set<GatewayEndpointOrigin> = setOf(endpoint()),
        openResult: GatewayTransportOpenResult? = null,
    ) {
        val tokenBytes = ByteArray(48) { (it * 5 + 19).toByte() }
        val storage = MemoryTokenStorage()
        val channel = FakeChannel()
        val transport = FakeTransport(openResult, channel)
        val client: GatewayClient

        init {
            val policy = GatewayEndpointPolicy.create(GatewayBuildMode.RELEASE, policyOrigins).successModel()
            val repository = GatewayTokenRepository(storage, policy)
            if (storeToken) {
                val token = GatewayAccessToken.copyOf(tokenBytes).successModel()
                val request = StoreGatewayTokenRequest.create(
                    GatewayTokenId.newReference(),
                    GatewayClientSessionId.newReference(),
                    endpoint(),
                    scopes,
                    epoch(1_000),
                    epoch(expiresAt),
                    token,
                ).successModel()
                repository.store(request, null)
                token.close()
            }
            client = GatewayClient(repository, transport) {
                successfulContract(GatewayCancellationId.parse("40000000-0000-4000-8000-000000000001"))
            }
        }

        fun start(
            request: io.redi.robot.gateway.contract.GatewayRequest,
            endpoint: GatewayEndpointOrigin = endpoint(),
            authNow: Long = 1_100,
            monotonic: Long = 100,
        ): GatewayClientStartResult = client.start(request, endpoint, epoch(authNow), time(monotonic))
    }

    private class FakeTransport(
        private val fixedResult: GatewayTransportOpenResult?,
        var channel: FakeChannel,
    ) : GatewayHttpStreamPort {
        var openCalls = 0
        var tokenReference: ByteArray? = null
        var requestReference: ByteArray? = null
        var tokenCopy: ByteArray? = null
        var requestCopy: ByteArray? = null

        override fun open(
            endpoint: GatewayEndpointOrigin,
            bearerToken: ByteArray,
            requestDocument: ByteArray,
        ): GatewayTransportOpenResult {
            openCalls++
            tokenReference = bearerToken
            requestReference = requestDocument
            tokenCopy = bearerToken.copyOf()
            requestCopy = requestDocument.copyOf()
            return fixedResult ?: GatewayTransportOpenResult.Accepted(channel)
        }
    }

    private class FakeChannel : GatewayHttpStreamChannel {
        private val reads = ArrayDeque<GatewayStreamReadResult>()
        var readCalls = 0
        var cancelCalls = 0
        var closeCalls = 0
        var cancellationReference: ByteArray? = null
        var cancellationCopy: ByteArray? = null

        fun enqueue(vararg values: GatewayStreamReadResult) {
            values.forEach(reads::addLast)
        }

        override fun readNext(): GatewayStreamReadResult {
            readCalls++
            return reads.removeFirstOrNull() ?: GatewayStreamReadResult.WouldBlock
        }

        override fun cancel(cancellationDocument: ByteArray): GatewayTransportCancellationDelivery {
            cancelCalls++
            cancellationReference = cancellationDocument
            cancellationCopy = cancellationDocument.copyOf()
            return GatewayTransportCancellationDelivery.DELIVERED
        }

        override fun close() {
            closeCalls++
        }
    }

    private class MemoryTokenStorage : ProtectedGatewayTokenStorage {
        private var content: ByteArray? = null
        override fun read(): GatewayTokenDocumentReadResult = content?.copyOf()?.let {
            GatewayTokenDocumentReadResult.Content(it)
        } ?: GatewayTokenDocumentReadResult.Missing

        override fun writeAtomically(plaintext: ByteArray): GatewayTokenDocumentWriteResult {
            content = plaintext.copyOf()
            return GatewayTokenDocumentWriteResult.COMMITTED
        }

        override fun reset(): GatewayTokenDocumentResetResult {
            content?.fill(0)
            content = null
            return GatewayTokenDocumentResetResult.RESET
        }
    }

    private fun reduce(
        request: io.redi.robot.gateway.contract.GatewayRequest,
        vararg events: io.redi.robot.gateway.contract.GatewayEvent,
    ): GatewayStreamReductionResult.Rejected {
        var state = GatewayStreamState.initial(request)
        events.forEach { event ->
            when (val reduced = GatewayStreamReducer.reduce(state, event)) {
                is GatewayStreamReductionResult.Accepted -> state = reduced.state
                is GatewayStreamReductionResult.Rejected -> return reduced
            }
        }
        error("Expected reduction rejection")
    }

    private fun textRequest(skills: String = "[]", timeoutMs: Long = 1_000): io.redi.robot.gateway.contract.GatewayRequest =
        request(
            """{"schemaVersion":"1.0","messageType":"request","requestId":"$REQUEST_ID","operation":"text","payload":{"prompt":"Hello","declaredSkills":$skills,"budget":{"maximumOutputUnits":512,"timeoutMs":$timeoutMs}}}""",
        )

    private fun visionRequest(): io.redi.robot.gateway.contract.GatewayRequest = request(
        """{"schemaVersion":"1.0","messageType":"request","requestId":"$REQUEST_ID","operation":"vision","payload":{"prompt":"Look","attachmentReference":"frame:Abcdefgh123","declaredSkills":[],"budget":{"maximumOutputUnits":512,"timeoutMs":1000}}}""",
    )

    private fun embeddingRequest(): io.redi.robot.gateway.contract.GatewayRequest = request(
        """{"schemaVersion":"1.0","messageType":"request","requestId":"$REQUEST_ID","operation":"embedding","payload":{"embeddingInputs":["one"],"declaredSkills":[],"budget":{"maximumOutputUnits":512,"timeoutMs":1000}}}""",
    )

    private fun request(json: String): io.redi.robot.gateway.contract.GatewayRequest =
        successfulContract(GatewayContractCodec.decodeRequest(json))

    private fun eventObject(
        sequence: Int,
        operation: String,
        payload: String,
    ): io.redi.robot.gateway.contract.GatewayEvent =
        successfulContract(GatewayContractCodec.decodeEvent(event(sequence, operation, payload)))

    private fun event(
        sequence: Int,
        operation: String,
        payload: String,
        requestId: String = REQUEST_ID,
    ): String {
        val tail = (sequence + 1).toString().padStart(12, '0')
        return """{"schemaVersion":"1.0","messageType":"event","requestId":"$requestId","eventId":"20000000-0000-4000-8000-$tail","sequence":$sequence,"operation":"$operation","event":$payload}"""
    }

    private fun frame(json: String): GatewayStreamReadResult.Frame = frame(json.toByteArray(StandardCharsets.UTF_8))
    private fun frame(bytes: ByteArray): GatewayStreamReadResult.Frame =
        (GatewayStreamReadResult.Frame.copyOf(bytes) as GatewayClientModelResult.Success).value

    private fun endpoint(raw: String = "https://gateway.test"): GatewayEndpointOrigin =
        GatewayEndpointOrigin.parse(raw).successModel()

    private fun epoch(value: Long): GatewayAuthEpochSeconds = GatewayAuthEpochSeconds.create(value).successModel()
    private fun time(value: Long): GatewayClientMonotonicMs =
        (GatewayClientMonotonicMs.create(value) as GatewayClientModelResult.Success).value

    private fun projectFile(relative: String): File = sequenceOf(File(relative), File("app/$relative"), File("../app/$relative"))
        .first { it.exists() }

    private fun <T> GatewayAuthModelResult<T>.successModel(): T = (this as GatewayAuthModelResult.Success).value
    private fun <T> successfulContract(result: GatewayContractResult<T>): T =
        (result as GatewayContractResult.Success).value

    companion object {
        private const val REQUEST_ID = "10000000-0000-4000-8000-000000000001"
        private const val OTHER_REQUEST_ID = "10000000-0000-4000-8000-000000000099"
    }
}
