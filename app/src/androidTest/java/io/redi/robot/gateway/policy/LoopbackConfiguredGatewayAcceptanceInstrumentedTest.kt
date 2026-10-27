package io.redi.robot.gateway.policy

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.redi.robot.ai.gateway.GatewayClient
import io.redi.robot.ai.gateway.GatewayClientModelResult
import io.redi.robot.ai.gateway.GatewayHttpStreamChannel
import io.redi.robot.ai.gateway.GatewayHttpStreamPort
import io.redi.robot.ai.gateway.GatewayStreamReadResult
import io.redi.robot.ai.gateway.GatewayTransportCancellationDelivery
import io.redi.robot.ai.gateway.GatewayTransportOpenResult
import io.redi.robot.conversation.context.ConversationContextBudget
import io.redi.robot.conversation.context.ConversationContextBuilder
import io.redi.robot.conversation.context.ConversationContextPrivacyPolicy
import io.redi.robot.conversation.context.ConversationContextResult
import io.redi.robot.conversation.runtime.ConversationCancelDisposition
import io.redi.robot.conversation.runtime.ConversationFallbackMessages
import io.redi.robot.conversation.runtime.ConversationTurnCoordinator
import io.redi.robot.conversation.runtime.ConversationTurnFallbackReason
import io.redi.robot.conversation.runtime.ConversationTurnOutcome
import io.redi.robot.conversation.runtime.ConversationTurnStartDisposition
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.runtime.ContextSnapshot
import io.redi.robot.context.runtime.ContextSnapshotBudget
import io.redi.robot.context.runtime.ContextSnapshotEngine
import io.redi.robot.context.runtime.ContextSnapshotRequest
import io.redi.robot.context.runtime.ContextSnapshotResult
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
import io.redi.robot.interaction.localintent.LocalIntentRouter
import io.redi.robot.voice.api.SpeechAdapterCancelDisposition
import io.redi.robot.voice.api.SpeechAdapterSnapshot
import io.redi.robot.voice.api.SpeechAdapterStartDisposition
import io.redi.robot.voice.api.SpeechAdapterStartResult
import io.redi.robot.voice.api.SpeechAudioFocusAcquireDisposition
import io.redi.robot.voice.api.SpeechAudioFocusAcquireResult
import io.redi.robot.voice.api.SpeechAudioFocusListener
import io.redi.robot.voice.api.SpeechAudioFocusPort
import io.redi.robot.voice.api.SpeechAudioFocusReleaseDisposition
import io.redi.robot.voice.api.SpeechAudioFocusRequest
import io.redi.robot.voice.api.SpeechAudioFocusSnapshot
import io.redi.robot.voice.api.SpeechContractResult
import io.redi.robot.voice.api.SpeechLocale
import io.redi.robot.voice.api.SpeechOperationId
import io.redi.robot.voice.api.SpeechRecognitionEvent
import io.redi.robot.voice.api.SpeechRecognitionListener
import io.redi.robot.voice.api.SpeechRecognitionPort
import io.redi.robot.voice.api.SpeechRecognitionRequest
import io.redi.robot.voice.api.SpeechSynthesisEvent
import io.redi.robot.voice.api.SpeechSynthesisListener
import io.redi.robot.voice.api.SpeechSynthesisPort
import io.redi.robot.voice.api.SpeechSynthesisRequest
import io.redi.robot.voice.api.SpeechText
import io.redi.robot.voice.session.AudioMonotonicTimeMs
import io.redi.robot.voice.session.AudioSessionCancellationReason
import io.redi.robot.voice.session.AudioSessionId
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * H057 Android acceptance against an androidTest-only loopback configured test gateway on
 * 127.0.0.1, authorized through the real H052 DEBUG loopback endpoint policy and a real stored
 * scoped token. The event framing (one H051 event document per line) is test-only and is not a
 * production transport contract. Speech ports are hermetic fakes: the H056 audio class already
 * carries the real microphone/TTS device evidence. No provider, credential, or backend is used.
 */
@RunWith(AndroidJUnit4::class)
class LoopbackConfiguredGatewayAcceptanceInstrumentedTest {
    private val locale = (SpeechLocale.parse("en-US") as SpeechContractResult.Success).value
    private var server: LoopbackGatewayServer? = null

    @Before
    fun setUp() {
        server = LoopbackGatewayServer()
        server!!.start()
    }

    @After
    fun tearDown() {
        server!!.stop()
    }

    @Test
    fun configuredGatewayCompletesTurnWithMeasuredUsageAndCancellation() {
        val engine = policyEngine(circuitThreshold = 2)
        val harness = harness(engine)
        try {
            harness.launchPump()
            assertEquals(
                ConversationTurnStartDisposition.ACCEPTED,
                harness.coordinator.activate(sessionId(1), locale, now()),
            )
            server!!.script = { requestDocument ->
                eventsFor(
                    requestDocument,
                    STARTED,
                    EVENT_PARTIAL,
                    EVENT_USAGE,
                    EVENT_COMPLETED,
                )
            }
            harness.emitTranscript(listOf("what time is it"))
            assertTrue("turn did not complete", harness.awaitRecord(20_000L))

            val record = harness.coordinator.lastTurnRecord()!!
            assertEquals(ConversationTurnOutcome.AI_COMPLETED, record.outcome)
            val snapshot = engine.snapshot(SystemClock.elapsedRealtime())
            assertEquals(1, snapshot.sessionRequests)
            assertEquals(9L, snapshot.sessionOutputUnits)
            assertEquals(0, snapshot.consecutiveHealthFailures)
            assertEquals(1, server!!.operationsServed.get())
            assertTrue(record.latency.generationMs != null && record.latency.generationMs >= 0L)

            // Barge-in over the real socket transport cancels the turn and reaches the gateway
            // cancellation path.
            // Barge-in over the real socket transport cancels the turn and reaches the gateway
            // cancellation path. The scripted stream holds open so THINKING is in flight.
            server!!.streamHoldMs = BARGE_HOLD_MS
            assertEquals(
                ConversationTurnStartDisposition.ACCEPTED,
                harness.coordinator.activate(sessionId(2), locale, now()),
            )
            harness.emitTranscript(listOf("what time is it"))
            assertTrue("second turn never generated", harness.awaitGatewayOperationCount(2, 20_000L))
            val bargeAt = SystemClock.elapsedRealtime()
            assertEquals(
                ConversationTurnStartDisposition.BARGE_IN_ACCEPTED,
                harness.coordinator.activate(sessionId(3), locale, AudioMonotonicTimeMs(bargeAt)),
            )
            val recordTwo = harness.coordinator.lastTurnRecord()!!
            assertEquals(ConversationTurnOutcome.CANCELLED, recordTwo.outcome)
            val cancelMs = SystemClock.elapsedRealtime() - bargeAt
            assertTrue("barge-in cancel latency " + cancelMs + "ms", cancelMs < 2_000L)
            // The cancellation POST is processed concurrently with the held stream; observe it
            // asynchronously within a bounded window.
            assertTrue("cancellation never reached the gateway path", awaitCancellations(1, 5_000L))
            server!!.streamHoldMs = STREAM_HOLD_MS

            println(
                "H057_DEVICE_GATEWAY_ROUNDTRIP=outcome=AI_COMPLETED " +
                    "generationMs=${record.latency.generationMs} usageOutputUnits=9 " +
                    "bargeInCancelMs=$cancelMs " +
                    "cancellationsServed=${server!!.cancellationsServed.get()}",
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun scriptedProviderFailuresOpenCircuitFailFastAndRecoverThroughHalfOpenProbe() {
        val engine = policyEngine(circuitThreshold = 2, cooldownMs = 3_000L)
        val harness = harness(engine)
        try {
            harness.launchPump()
            // Two consecutive post-start provider-health failures (H053 PROTOCOL_ERROR from a
            // prematurely severed accepted stream) open the circuit.
            repeat(2) { index ->
                server!!.dropConnections.set(true)
                harness.activateAndRunTurn(sessionId(10 + index), "what time is it")
                val failureRecord = harness.coordinator.lastTurnRecord()!!
                assertEquals(ConversationTurnOutcome.AI_FALLBACK, failureRecord.outcome)
                assertEquals(
                    io.redi.robot.ai.gateway.GatewayClientTerminalStatus.PROTOCOL_ERROR,
                    failureRecord.gatewayTerminal,
                )
                assertEquals(ConversationTurnFallbackReason.PROTOCOL_ERROR, failureRecord.fallbackReason)
            }
            server!!.dropConnections.set(false)
            assertEquals(
                ModelPolicyBlockReason.CIRCUIT_OPEN,
                (engine.evaluate(ModelTaskClass.CONVERSATION_TEXT, ContextPrivacyClassification.PRIVATE, now().value, 0)
                    as ModelPolicyDecision.Blocked).reason,
            )

            // The third turn fails fast to the local fallback without contacting the gateway.
            val servedBefore = server!!.operationsServed.get()
            harness.activateAndRunTurn(sessionId(12), "what time is it")
            assertEquals(ConversationTurnFallbackReason.POLICY_BLOCKED, harness.coordinator.lastTurnRecord()!!.fallbackReason)
            assertEquals(servedBefore, server!!.operationsServed.get())

            // After the cooldown the next user-initiated request is the single probe and recovers.
            SystemClock.sleep(COOLDOWN_MS + 200L)
            server!!.script = { requestDocument ->
                eventsFor(requestDocument, STARTED, "{\"type\":\"partial_text\",\"text\":\"recovered\"}",
                    "{\"type\":\"usage\",\"evidence\":\"measured\",\"inputUnits\":3,\"outputUnits\":1}",
                    "{\"type\":\"completed\",\"reason\":\"complete\"}")
            }
            harness.activateAndRunTurn(sessionId(13), "what time is it")
            val recovered = harness.coordinator.lastTurnRecord()!!
            assertEquals(ConversationTurnOutcome.AI_COMPLETED, recovered.outcome)
            assertEquals(ModelCircuitState.CLOSED, engine.snapshot(now().value).circuitState)

            println(
                "H057_DEVICE_CIRCUIT=failures=2 open=true failFast=true probeRecovered=true " +
                    "recoveryGenerationMs=${recovered.latency.generationMs} " +
                    "cooldownMs=$COOLDOWN_MS",
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun sessionBudgetExhaustionBlocksSafelyWithTypedLocalFallback() {
        val engine = policyEngine(sessionMaximumRequests = 1)
        val harness = harness(engine)
        try {
            harness.launchPump()
            server!!.script = { requestDocument ->
                eventsFor(requestDocument, STARTED, "{\"type\":\"partial_text\",\"text\":\"hi\"}",
                    "{\"type\":\"usage\",\"evidence\":\"measured\",\"inputUnits\":3,\"outputUnits\":2}",
                    "{\"type\":\"completed\",\"reason\":\"complete\"}")
            }
            harness.activateAndRunTurn(sessionId(20), "what time is it")
            assertEquals(ConversationTurnOutcome.AI_COMPLETED, harness.coordinator.lastTurnRecord()!!.outcome)

            val servedBefore = server!!.operationsServed.get()
            harness.activateAndRunTurn(sessionId(21), "what time is it")
            assertEquals(servedBefore, server!!.operationsServed.get())
            val record = harness.coordinator.lastTurnRecord()!!
            assertEquals(ConversationTurnOutcome.AI_FALLBACK, record.outcome)
            assertEquals(ConversationTurnFallbackReason.POLICY_BLOCKED, record.fallbackReason)

            // Local stop remains independent of policy and gateway state: an active turn with the
            // budget exhausted is still user-cancellable.
            assertEquals(
                ConversationTurnStartDisposition.ACCEPTED,
                harness.coordinator.activate(sessionId(22), locale, now()),
            )
            assertEquals(
                ConversationCancelDisposition.REQUESTED,
                harness.coordinator.cancel(AudioSessionCancellationReason.USER_REQUEST, now()),
            )
            assertTrue(harness.awaitRecordFor(sessionId(22), 10_000L))
            val cancelled = harness.coordinator.lastTurnRecord()!!
            assertEquals(ConversationTurnOutcome.CANCELLED, cancelled.outcome)
            assertEquals(AudioSessionCancellationReason.USER_REQUEST, cancelled.cancellationReason)
            println(
                "H057_DEVICE_EXHAUSTION=blocked=POLICY_BLOCKED transportOpensAvoided=true " +
                    "totalMs=${record.latency.totalMs} localStopStillWorks=true",
            )
        } finally {
            harness.close()
        }
    }

    // ------------------------------------------------------------------ harness

    /** Bounded asynchronous observation of gateway-side cancellation accounting. */
    private fun awaitCancellations(count: Int, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (server!!.cancellationsServed.get() >= count) return true
            SystemClock.sleep(25L)
        }
        return server!!.cancellationsServed.get() >= count
    }

    private fun policyEngine(circuitThreshold: Int = 2, cooldownMs: Long = COOLDOWN_MS, sessionMaximumRequests: Int = 8):
        ModelPolicyEngine {
        val taskPolicy = (ModelTaskPolicy.create(
            (io.redi.robot.gateway.contract.GatewayBudget.create(512, 10_000L)
                as io.redi.robot.gateway.contract.GatewayContractResult.Success).value,
            4_096,
            setOf(ContextPrivacyClassification.PUBLIC, ContextPrivacyClassification.PRIVATE),
            sessionMaximumRequests,
            100_000,
            1_000,
            1_000_000,
        ) as ModelPolicyResult.Success).value
        val configuration = (ModelPolicyConfiguration.create(
            mapOf(ModelTaskClass.CONVERSATION_TEXT to taskPolicy),
            (ModelCircuitPolicy.create(circuitThreshold, cooldownMs) as ModelPolicyResult.Success).value,
        ) as ModelPolicyResult.Success).value
        return ModelPolicyEngine(configuration)
    }

    private fun harness(engine: ModelPolicyEngine) = AcceptanceHarness(server!!, engine)

    private inner class AcceptanceHarness(
        private val server: LoopbackGatewayServer,
        private val engine: ModelPolicyEngine,
    ) {
        val recognition = ScriptedRecognitionPort()
        val synthesis = RecordingSynthesisPort()
        val focus = GrantingFocusPort()
        private val endpoint = (GatewayEndpointOrigin.parse("http://127.0.0.1:${server.port}")
            as GatewayAuthModelResult.Success).value
        private val repository = GatewayTokenRepository(
            MemoryTokenStorage(),
            (GatewayEndpointPolicy.create(GatewayBuildMode.DEBUG, emptySet()) as GatewayAuthModelResult.Success).value,
        )
        private val client: GatewayClient
        private val transport = SocketGatewayStreamTransport()
        val coordinator: ConversationTurnCoordinator
        private var pump: Thread? = null
        private val running = AtomicBoolean(false)

        init {
            val token = (GatewayAccessToken.copyOf(ByteArray(48) { (it * 5 + 19).toByte() })
                as GatewayAuthModelResult.Success).value
            val stored = (StoreGatewayTokenRequest.create(
                GatewayTokenId.newReference(),
                GatewayClientSessionId.newReference(),
                endpoint,
                setOf(GatewayAuthScope.TEXT),
                epoch(1_000L),
                epoch(1_600L),
                token,
            ) as GatewayAuthModelResult.Success).value
            repository.store(stored, null)
            token.close()
            client = GatewayClient(repository, transport)

            fun text(value: String): SpeechText =
                (SpeechText.create(value) as SpeechContractResult.Success).value

            coordinator = ConversationTurnCoordinator(
                gateway = client,
                recognitionPort = recognition,
                synthesisPort = synthesis,
                focusPort = focus,
                endpoint = endpoint,
                fallbacks = ConversationFallbackMessages.create(
                    clarification = text("which command did you mean"),
                    unsupportedLocale = text("locale not available locally"),
                    invalidRoute = text("command not understood"),
                    providerUnavailable = text("generation is unavailable right now"),
                    providerLost = text("provider connection was lost"),
                    generationFailed = text("generation failed"),
                    generationTimedOut = text("generation timed out"),
                ),
                contextBuilder = ConversationContextBuilder(),
                contextPrivacyPolicy = (ConversationContextPrivacyPolicy.create(
                    setOf(ContextPrivacyClassification.PUBLIC, ContextPrivacyClassification.PRIVATE),
                ) as ConversationContextResult.Success).value,
                contextBudget = (ConversationContextBudget.create(32, 8_192, 32_768)
                    as ConversationContextResult.Success).value,
                snapshotProvider = { emptySnapshot() },
                authEpochNow = { (GatewayAuthEpochSeconds.create(1_100L) as GatewayAuthModelResult.Success).value },
                intentRouter = LocalIntentRouter(),
                generationPolicy = engine,
                listener = { _ -> },
            )
        }

        fun launchPump() {
            running.set(true)
            pump = Thread {
                while (running.get()) {
                    try {
                        coordinator.poll(AudioMonotonicTimeMs(SystemClock.elapsedRealtime()))
                    } catch (_: Exception) {
                        // Keep pumping; turn failures are recorded honestly by the coordinator.
                    }
                    SystemClock.sleep(20L)
                }
            }
            pump!!.start()
        }

        fun activateAndRunTurn(session: AudioSessionId, transcript: String) {
            assertEquals(
                ConversationTurnStartDisposition.ACCEPTED,
                coordinator.activate(session, locale, now()),
            )
            val servedBefore = server.operationsServed.get()
            emitTranscript(listOf(transcript))
            awaitTransportActivity(servedBefore, session)
            awaitRecordFor(session, 20_000L)
        }

        fun emitTranscript(alternatives: List<String>) {
            recognition.emit(alternatives)
        }

        /** Completion is associated with the exact newly activated session, never a prior turn. */
        fun awaitRecordFor(session: AudioSessionId, timeoutMs: Long): Boolean {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                if (coordinator.lastTurnRecord()?.sessionId == session) return true
                SystemClock.sleep(25L)
            }
            return coordinator.lastTurnRecord()?.sessionId == session
        }

        /** Waits until the gateway serves a new operation or this turn ends, whichever is first. */
        private fun awaitTransportActivity(servedBefore: Int, session: AudioSessionId): Boolean {
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                if (server.operationsServed.get() > servedBefore) return true
                if (coordinator.lastTurnRecord()?.sessionId == session) return false
                SystemClock.sleep(20L)
            }
            return false
        }

        fun awaitGatewayOperationCount(count: Int, timeoutMs: Long): Boolean {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                if (server.operationsServed.get() >= count) return true
                SystemClock.sleep(20L)
            }
            return server.operationsServed.get() >= count
        }

        fun awaitRecord(timeoutMs: Long): Boolean {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                if (coordinator.lastTurnRecord() != null) return true
                SystemClock.sleep(25L)
            }
            return coordinator.lastTurnRecord() != null
        }

        fun close() {
            running.set(false)
            pump?.join(2_000L)
            coordinator.cancel(AudioSessionCancellationReason.BACKGROUNDED, now())
        }
    }

    // --------------------------------------------------- loopback configured test gateway

    /** Test-only HTTP server for the two H051 OpenAPI paths; one H051 event document per line. */
    private class LoopbackGatewayServer {
        private var serverSocket: ServerSocket? = null
        private var acceptThread: Thread? = null
        private val closed = AtomicBoolean(false)
        val operationsServed = AtomicInteger(0)
        val cancellationsServed = AtomicInteger(0)
        val dropConnections = AtomicBoolean(false)

        /** Maps the decoded request document to the scripted H051 event documents. */
        @Volatile
        var script: (String) -> List<String> = { _ -> emptyList() }

        /** How long the scripted stream stays open before closing; long holds support barge-in. */
        @Volatile
        var streamHoldMs: Long = STREAM_HOLD_MS

        val port: Int get() = serverSocket!!.localPort

        fun start() {
            serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            acceptThread = Thread {
                while (!closed.get()) {
                    val socket = try {
                        serverSocket!!.accept()
                    } catch (_: Exception) {
                        return@Thread
                    }
                    // Connections are handled concurrently so a deliberately held generation
                    // stream never delays an incoming cancellation POST.
                    val worker = Thread { handle(socket) }
                    worker.isDaemon = true
                    worker.start()
                }
            }
            acceptThread!!.isDaemon = true
            acceptThread!!.start()
        }

        fun stop() {
            closed.set(true)
            runCatching { serverSocket!!.close() }
            acceptThread?.join(2_000L)
        }

        private fun handle(socket: Socket) {
            socket.use { connection ->
                connection.soTimeout = SOCKET_TIMEOUT_MS
                val reader = BufferedReader(InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                var contentLength = 0
                while (true) {
                    val header = reader.readLine() ?: return
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                val body = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = reader.read(body, read, contentLength - read)
                    if (count < 0) break
                    read += count
                }
                val requestDocument = String(body, 0, read)

                if (requestLine.startsWith("POST /v1/operations ")) {
                    operationsServed.incrementAndGet()
                    if (dropConnections.get()) {
                        // Scripted provider loss: the start handshake succeeds, then the accepted
                        // stream is severed before any terminal event, so H053 classifies the
                        // genuine post-start premature end as PROTOCOL_ERROR (a G5 provider-health
                        // failure). Start/open failures would stay circuit-neutral by design.
                        connection.getOutputStream().write(header(0).toByteArray(StandardCharsets.UTF_8))
                        connection.getOutputStream().flush()
                        return
                    }
                    val events = try {
                        script(requestDocument)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val payload = buildString {
                        events.forEach { event ->
                            append(event)
                            append('\n')
                        }
                    }.toByteArray(StandardCharsets.UTF_8)
                    connection.getOutputStream().write(header(payload.size).toByteArray(StandardCharsets.UTF_8))
                    connection.getOutputStream().write(payload)
                    connection.getOutputStream().flush()
                    Thread.sleep(streamHoldMs)
                } else if (requestLine.startsWith("POST /v1/operations/") && "/cancellations" in requestLine) {
                    cancellationsServed.incrementAndGet()
                    connection.getOutputStream().write(header(0).toByteArray(StandardCharsets.UTF_8))
                    connection.getOutputStream().flush()
                }
            }
        }

        private fun header(length: Int): String =
            "HTTP/1.1 202 Accepted\r\nContent-Type: application/json\r\n" +
                "Content-Length: $length\r\nConnection: close\r\n\r\n"
    }

    /** Test-only stream transport over the loopback HTTP server. Not a production transport. */
    private inner class SocketGatewayStreamTransport : GatewayHttpStreamPort {
        override fun open(
            endpoint: GatewayEndpointOrigin,
            bearerToken: ByteArray,
            requestDocument: ByteArray,
        ): GatewayTransportOpenResult {
            val port = endpoint.wireValue.substringAfterLast(':').toIntOrNull()
                ?: return GatewayTransportOpenResult.Rejected(
                    io.redi.robot.ai.gateway.GatewayTransportOpenFailure.UNAVAILABLE,
                )
            val socket = try {
                Socket("127.0.0.1", port)
            } catch (_: Exception) {
                return GatewayTransportOpenResult.Rejected(
                    io.redi.robot.ai.gateway.GatewayTransportOpenFailure.UNAVAILABLE,
                )
            }
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val body = requestDocument
            val header = "POST /v1/operations HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${body.size}\r\n" +
                "Authorization: Bearer test\r\nConnection: close\r\n\r\n"
            try {
                socket.getOutputStream().write(header.toByteArray(StandardCharsets.UTF_8))
                socket.getOutputStream().write(body)
                socket.getOutputStream().flush()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val status = reader.readLine()
                if (status == null || !status.contains("202")) {
                    socket.close()
                    return GatewayTransportOpenResult.Rejected(
                        io.redi.robot.ai.gateway.GatewayTransportOpenFailure.UNAVAILABLE,
                    )
                }
                while (true) {
                    val headerLine = reader.readLine() ?: break
                    if (headerLine.isEmpty()) break
                }
                return GatewayTransportOpenResult.Accepted(SocketGatewayChannel(socket, reader))
            } catch (_: Exception) {
                runCatching { socket.close() }
                return GatewayTransportOpenResult.Rejected(
                    io.redi.robot.ai.gateway.GatewayTransportOpenFailure.UNAVAILABLE,
                )
            }
        }
    }

    private class SocketGatewayChannel(
        private val socket: Socket,
        private val reader: BufferedReader,
    ) : GatewayHttpStreamChannel {
        override fun readNext(): GatewayStreamReadResult = try {
            val line = reader.readLine()
            when {
                line == null -> GatewayStreamReadResult.EndOfStream
                line.isEmpty() -> GatewayStreamReadResult.WouldBlock
                else -> when (val frame = GatewayStreamReadResult.Frame.copyOf(line.toByteArray(StandardCharsets.UTF_8))) {
                    is GatewayClientModelResult.Success -> frame.value
                    is GatewayClientModelResult.Failure -> GatewayStreamReadResult.EndOfStream
                }
            }
        } catch (_: Exception) {
            GatewayStreamReadResult.Failure(io.redi.robot.ai.gateway.GatewayTransportReadFailure.CONNECTION_LOST)
        }

        override fun cancel(cancellationDocument: ByteArray): GatewayTransportCancellationDelivery {
            // The OpenAPI cancellation path is a separate POST; issue it on its own connection.
            return try {
                val port = socket.port
                val cancellation = Socket("127.0.0.1", port)
                cancellation.use { connection ->
                    val header = "POST /v1/operations/00000000-0000-4000-8000-000000000001/cancellations HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:$port\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${cancellationDocument.size}\r\nConnection: close\r\n\r\n"
                    connection.getOutputStream().write(header.toByteArray(StandardCharsets.UTF_8))
                    connection.getOutputStream().write(cancellationDocument)
                    connection.getOutputStream().flush()
                }
                GatewayTransportCancellationDelivery.DELIVERED
            } catch (_: Exception) {
                GatewayTransportCancellationDelivery.FAILED
            }
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    // ------------------------------------------------------------- hermetic speech fakes

    private class ScriptedRecognitionPort : SpeechRecognitionPort {
        private var listener: SpeechRecognitionListener? = null
        private var request: SpeechRecognitionRequest? = null

        override fun start(
            request: SpeechRecognitionRequest,
            listener: SpeechRecognitionListener,
        ): SpeechAdapterStartResult {
            this.request = request
            this.listener = listener
            return SpeechAdapterStartResult(SpeechAdapterStartDisposition.ACCEPTED, true)
        }

        override fun cancel(operationId: SpeechOperationId): SpeechAdapterCancelDisposition =
            SpeechAdapterCancelDisposition.REQUESTED

        override fun snapshot(): SpeechAdapterSnapshot =
            SpeechAdapterSnapshot(active = false, cancelling = false, activeOperationId = null)

        fun emit(alternatives: List<String>) {
            val request = this.request ?: return
            val listener = this.listener ?: return
            Thread {
                SystemClock.sleep(TRANSCRIPT_DELAY_MS)
                val transcript = (io.redi.robot.voice.api.EphemeralSpeechTranscript.create(alternatives)
                    as SpeechContractResult.Success).value
                listener.onEvent(
                    SpeechRecognitionEvent.FinalTranscript(
                        request.operationId,
                        request.sessionId,
                        AudioMonotonicTimeMs(SystemClock.elapsedRealtime()),
                        transcript,
                    ),
                )
            }.start()
        }
    }

    private class RecordingSynthesisPort : SpeechSynthesisPort {
        val spoken = Collections.synchronizedList(mutableListOf<String>())

        override fun start(
            request: SpeechSynthesisRequest,
            listener: SpeechSynthesisListener,
        ): SpeechAdapterStartResult {
            spoken.add(request.text.value)
            Thread {
                SystemClock.sleep(SPEAK_DELAY_MS)
                listener.onEvent(
                    SpeechSynthesisEvent.Completed(
                        request.operationId,
                        request.sessionId,
                        AudioMonotonicTimeMs(SystemClock.elapsedRealtime()),
                    ),
                )
            }.start()
            return SpeechAdapterStartResult(SpeechAdapterStartDisposition.ACCEPTED, true)
        }

        override fun cancel(operationId: SpeechOperationId): SpeechAdapterCancelDisposition =
            SpeechAdapterCancelDisposition.REQUESTED

        override fun snapshot(): SpeechAdapterSnapshot =
            SpeechAdapterSnapshot(active = false, cancelling = false, activeOperationId = null)
    }

    private class GrantingFocusPort : SpeechAudioFocusPort {
        override fun acquire(
            request: SpeechAudioFocusRequest,
            listener: SpeechAudioFocusListener,
        ): SpeechAudioFocusAcquireResult =
            SpeechAudioFocusAcquireResult(SpeechAudioFocusAcquireDisposition.GRANTED, true)

        override fun release(operationId: SpeechOperationId): SpeechAudioFocusReleaseDisposition =
            SpeechAudioFocusReleaseDisposition.RELEASED

        override fun snapshot(): SpeechAudioFocusSnapshot =
            SpeechAudioFocusSnapshot(owned = false, operationId = null)
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

    // ------------------------------------------------------------------------ helpers

    private fun emptySnapshot(): ContextSnapshot {
        val budget = (ContextSnapshotBudget.create(16, 8, 32, 16_384) as ContextSnapshotResult.Success).value
        val request = (ContextSnapshotRequest.create(
            (io.redi.robot.capability.api.MonotonicTimestampMs.create(100L)
                as io.redi.robot.capability.api.CapabilityEnvelopeResult.Success).value,
            null,
            ContextPrivacyClassification.entries.toSet(),
            budget,
        ) as ContextSnapshotResult.Success).value
        return (ContextSnapshotEngine.build(emptyList(), request) as ContextSnapshotResult.Success).value
    }

    private fun now(): AudioMonotonicTimeMs = AudioMonotonicTimeMs(SystemClock.elapsedRealtime())

    private fun epoch(value: Long): GatewayAuthEpochSeconds =
        (GatewayAuthEpochSeconds.create(value) as GatewayAuthModelResult.Success).value

    private fun sessionId(index: Int): AudioSessionId =
        AudioSessionId.fromUuid(UUID.fromString("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"))

    private companion object {
        private const val STARTED = "{\"type\":\"started\"}"
        private const val COOLDOWN_MS = 3_000L
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val STREAM_HOLD_MS = 150L
        private const val BARGE_HOLD_MS = 2_500L
        private const val TRANSCRIPT_DELAY_MS = 150L
        private const val SPEAK_DELAY_MS = 60L

        private const val EVENT_PARTIAL =
            "{\"type\":\"partial_text\",\"text\":\"hi from the loopback gateway\"}"
        private const val EVENT_USAGE =
            "{\"type\":\"usage\",\"evidence\":\"measured\",\"inputUnits\":12,\"outputUnits\":9}"
        private const val EVENT_COMPLETED = "{\"type\":\"completed\",\"reason\":\"complete\"}"

        private fun EVENT(sequence: Int, requestId: String, payload: String): String {
            val tail = (sequence + 1).toString().padStart(12, '0')
            return "{\"schemaVersion\":\"1.0\",\"messageType\":\"event\",\"requestId\":\"$requestId\"," +
                "\"eventId\":\"20000000-0000-4000-8000-$tail\",\"sequence\":$sequence,\"operation\":\"text\"," +
                "\"event\":$payload}"
        }

        /** Builds a scripted event stream bound to the real request id of the request document. */
        private fun eventsFor(requestDocument: String, vararg payloads: String): List<String> {
            val match = Regex("\"requestId\":\"([^\"]+)\"").find(requestDocument)
            val requestId = match?.groupValues?.get(1) ?: return emptyList()
            return payloads.mapIndexed { index, payload -> EVENT(index, requestId, payload) }
        }
    }
}
