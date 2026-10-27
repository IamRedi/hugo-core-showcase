package io.redi.robot.conversation.runtime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.redi.robot.MainActivity
import io.redi.robot.ai.gateway.GatewayClient
import io.redi.robot.ai.gateway.GatewayClientModelResult
import io.redi.robot.ai.gateway.GatewayHttpStreamChannel
import io.redi.robot.ai.gateway.GatewayHttpStreamPort
import io.redi.robot.ai.gateway.GatewayStreamReadResult
import io.redi.robot.ai.gateway.GatewayTransportCancellationDelivery
import io.redi.robot.ai.gateway.GatewayTransportOpenResult
import io.redi.robot.ai.gateway.GatewayTransportReadFailure
import io.redi.robot.conversation.context.ConversationContextBuilder
import io.redi.robot.conversation.context.ConversationContextBudget
import io.redi.robot.conversation.context.ConversationContextPrivacyPolicy
import io.redi.robot.conversation.context.ConversationContextResult
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
import io.redi.robot.gateway.contract.GatewayContractCodec
import io.redi.robot.gateway.contract.GatewayContractResult
import io.redi.robot.interaction.localintent.LocalIntentRouter
import io.redi.robot.platform.voice.android.AndroidAudioRecordCapturePort
import io.redi.robot.voice.api.EphemeralSpeechTranscript
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
import io.redi.robot.voice.api.SpeechRecognitionFailure
import io.redi.robot.voice.api.SpeechRecognitionListener
import io.redi.robot.voice.api.SpeechRecognitionPort
import io.redi.robot.voice.api.SpeechRecognitionRequest
import io.redi.robot.voice.api.SpeechSynthesisEvent
import io.redi.robot.voice.api.SpeechSynthesisFailure
import io.redi.robot.voice.api.SpeechSynthesisListener
import io.redi.robot.voice.api.SpeechSynthesisPort
import io.redi.robot.voice.api.SpeechSynthesisRequest
import io.redi.robot.voice.api.SpeechText
import io.redi.robot.voice.capture.AudioCaptureConfiguration
import io.redi.robot.voice.capture.AudioCaptureEvent
import io.redi.robot.voice.capture.AudioCaptureStartDisposition
import io.redi.robot.voice.capture.AudioCaptureStopReason
import io.redi.robot.voice.capture.ExplicitAudioCaptureRequest
import io.redi.robot.voice.session.AudioMonotonicTimeMs
import io.redi.robot.voice.session.AudioSessionCancellationReason
import io.redi.robot.voice.session.AudioSessionId
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * H056 Android-audio device evidence. The pure turn coordinator runs one activation-gated turn per
 * scenario on a physical phone with a real `AudioRecord` listen window, real local TTS output, and
 * a fake H053 stream (no network; H057 owns the configured gateway). Barge-in and provider-loss
 * cancellation are measured with the monotonic clock.
 */
@RunWith(AndroidJUnit4::class)
class ConversationTurnAndroidAudioInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val locale = (SpeechLocale.parse("en-US") as SpeechContractResult.Success).value

    @Test
    fun fullTurnListensWithRealMicrophoneAndSpeaksWithLocalTts() {
        val harness = deviceHarness()
        try {
            harness.launchActivity()
            val startedAt = SystemClock.elapsedRealtime()
            assertEquals(
                ConversationTurnStartDisposition.ACCEPTED,
                harness.coordinator.activate(sessionId(1), locale, AudioMonotonicTimeMs(startedAt)),
            )

            harness.transportScript.frames = { requestId ->
                arrayOf(
                    harness.frame(0, requestId, STARTED_PAYLOAD),
                    harness.frame(1, requestId, "{\"type\":\"partial_text\",\"text\":\"hi there\"}"),
                    harness.frame(2, requestId, "{\"type\":\"completed\",\"reason\":\"complete\"}"),
                )
            }
            assertTrue(
                "turn did not complete in time",
                harness.pump(timeoutMs = 20_000L) { harness.coordinator.lastTurnRecord() != null },
            )

            val record = harness.coordinator.lastTurnRecord()!!
            assertEquals(ConversationTurnOutcome.AI_COMPLETED, record.outcome)
            assertNotNull(record.latency.listeningMs)
            assertNotNull(record.latency.speakingMs)
            assertTrue("listening phase was not a real capture", record.latency.listeningMs!! >= CAPTURE_WINDOW_MS - 200L)
            assertTrue("turn latency missing", record.latency.totalMs!! > 0L)
            assertTrue(harness.tts.spokeUtterances.toSet().contains("hi there"))
            assertFalse(harness.coordinator.snapshot().active)
            println(
                "H056_DEVICE_FULL_TURN=outcome=AI_COMPLETED totalMs=${record.latency.totalMs} " +
                    "listeningMs=${record.latency.listeningMs} generationMs=${record.latency.generationMs} " +
                    "speakingMs=${record.latency.speakingMs} frames=${harness.capture.framesObserved} " +
                    "micReleased=${!harness.capture.port.snapshot().microphoneOwned}",
            )
        } finally {
            harness.close()
        }
    }

    @Test
    fun bargeInDuringRealSpeechCancelsOutputImmediately() {
        val harness = deviceHarness()
        try {
            harness.launchActivity()
            harness.coordinator.activate(sessionId(2), locale, AudioMonotonicTimeMs(SystemClock.elapsedRealtime()))
            harness.transportScript.frames = { requestId ->
                arrayOf(
                    harness.frame(0, requestId, STARTED_PAYLOAD),
                    harness.frame(1, requestId, "{\"type\":\"partial_text\",\"text\":\"$BARGE_TEXT\"}"),
                    harness.frame(2, requestId, "{\"type\":\"completed\",\"reason\":\"complete\"}"),
                )
            }
            assertTrue(
                "speech output never started",
                harness.pump(timeoutMs = 20_000L) { harness.tts.speaking },
            )

            val bargeAt = SystemClock.elapsedRealtime()
            assertEquals(
                ConversationTurnStartDisposition.BARGE_IN_ACCEPTED,
                harness.coordinator.activate(sessionId(3), locale, AudioMonotonicTimeMs(bargeAt)),
            )
            val cancelLatencyMs = SystemClock.elapsedRealtime() - bargeAt

            val record = harness.coordinator.lastTurnRecord()!!
            assertEquals(ConversationTurnOutcome.CANCELLED, record.outcome)
            assertEquals(AudioSessionCancellationReason.INTERRUPTED, record.cancellationReason)
            assertFalse("TTS kept speaking after barge-in", harness.tts.speaking)
            assertTrue("barge-in cancellation was not immediate: $cancelLatencyMs ms", cancelLatencyMs < 2_000L)
            // The barged-out turn's real AudioRecord releases asynchronously on hardware. Retry
            // activation within a bounded monotonic window; every LISTENING_FAILED attempt is an
            // honest completed turn that the coordinator auto-resets on the next activation.
            val freshTurnDeadline = SystemClock.elapsedRealtime() + FRESH_TURN_RETRY_DEADLINE_MS
            var freshTurn = harness.coordinator.activate(
                sessionId(4),
                locale,
                AudioMonotonicTimeMs(SystemClock.elapsedRealtime()),
            )
            while (
                freshTurn == ConversationTurnStartDisposition.LISTENING_FAILED &&
                SystemClock.elapsedRealtime() < freshTurnDeadline
            ) {
                SystemClock.sleep(FRESH_TURN_RETRY_INTERVAL_MS)
                freshTurn = harness.coordinator.activate(
                    sessionId(4),
                    locale,
                    AudioMonotonicTimeMs(SystemClock.elapsedRealtime()),
                )
            }
            assertEquals(
                "local stop/barge-in must leave a fresh turn listening within mic-release bound",
                ConversationTurnStartDisposition.ACCEPTED,
                freshTurn,
            )
            println("H056_DEVICE_BARGE_IN=cancelLatencyMs=$cancelLatencyMs outcome=CANCELLED reason=INTERRUPTED")
        } finally {
            harness.close()
        }
    }

    @Test
    fun providerLossDuringGenerationSpeaksLocalFallbackOnDevice() {
        val harness = deviceHarness()
        try {
            harness.launchActivity()
            harness.coordinator.activate(sessionId(5), locale, AudioMonotonicTimeMs(SystemClock.elapsedRealtime()))
            harness.transportScript.frames = { requestId ->
                arrayOf(
                    harness.frame(0, requestId, STARTED_PAYLOAD),
                    GatewayStreamReadResult.Failure(GatewayTransportReadFailure.CONNECTION_LOST),
                )
            }
            assertTrue(
                "fallback turn did not complete in time",
                harness.pump(timeoutMs = 20_000L) { harness.coordinator.lastTurnRecord() != null },
            )

            val record = harness.coordinator.lastTurnRecord()!!
            assertEquals(ConversationTurnOutcome.AI_FALLBACK, record.outcome)
            assertEquals(ConversationTurnFallbackReason.TRANSPORT_FAILURE, record.fallbackReason)
            assertTrue(harness.tts.spokeUtterances.toSet().contains("provider connection was lost"))
            println(
                "H056_DEVICE_PROVIDER_LOSS=outcome=AI_FALLBACK reason=TRANSPORT_FAILURE " +
                    "fallbackSpokenLocally=true totalMs=${record.latency.totalMs}",
            )
        } finally {
            harness.close()
        }
    }

    private inner class DeviceHarness(
        val capture: DeviceCaptureRecognitionPort,
        val tts: DeviceTtsSynthesisPort,
        val transportScript: TransportScript,
    ) {
        val focus = GrantingFocusPort()
        val transport = ScriptedTransport(transportScript)
        val client: GatewayClient
        val coordinator: ConversationTurnCoordinator
        private var activity: android.app.Activity? = null

        init {
            val endpoint = (GatewayEndpointOrigin.parse("https://gateway.test") as GatewayAuthModelResult.Success).value
            val policy = (GatewayEndpointPolicy.create(GatewayBuildMode.RELEASE, setOf(endpoint))
                as GatewayAuthModelResult.Success).value
            val repository = GatewayTokenRepository(MemoryTokenStorage(), policy)
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

            fun text(value: String): SpeechText = (SpeechText.create(value) as SpeechContractResult.Success).value
            coordinator = ConversationTurnCoordinator(
                gateway = client,
                recognitionPort = capture,
                synthesisPort = tts,
                focusPort = focus,
                endpoint = endpoint,
                fallbacks = ConversationFallbackMessages.create(
                    clarification = text("which command did you mean"),
                    unsupportedLocale = text("locale not available locally"),
                    invalidRoute = text("command not understood"),
                    providerUnavailable = text("provider is unavailable on this device"),
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
                authEpochNow = { epoch(1_100L) },
                intentRouter = LocalIntentRouter(),
                listener = { },
            )
        }

        fun launchActivity() {
            activity = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        fun pump(timeoutMs: Long, until: () -> Boolean): Boolean {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                coordinator.poll(AudioMonotonicTimeMs(SystemClock.elapsedRealtime()))
                if (until()) return true
                SystemClock.sleep(50L)
            }
            return until()
        }

        fun frame(sequence: Int, requestId: String, payload: String): GatewayStreamReadResult.Frame {
            val tail = (sequence + 1).toString().padStart(12, '0')
            val json = """{"schemaVersion":"1.0","messageType":"event","requestId":"$requestId",""" +
                """"eventId":"20000000-0000-4000-8000-$tail","sequence":$sequence,"operation":"text","event":$payload}"""
            return when (val created = GatewayStreamReadResult.Frame.copyOf(json.toByteArray(StandardCharsets.UTF_8))) {
                is GatewayClientModelResult.Success -> created.value
                is GatewayClientModelResult.Failure -> error("synthetic device frame exceeded bounds")
            }
        }

        fun close() {
            coordinator.cancel(AudioSessionCancellationReason.BACKGROUNDED, AudioMonotonicTimeMs(SystemClock.elapsedRealtime()))
            tts.release()
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
        }
    }

    private fun deviceHarness(): DeviceHarness {
        assertEquals(
            "Grant microphone permission explicitly before the H056 device test",
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO),
        )
        val capture = DeviceCaptureRecognitionPort(context)
        val tts = DeviceTtsSynthesisPort(context)
        assertTrue("device TTS engine failed to initialize for en-US", tts.ready())
        return DeviceHarness(
            capture,
            tts,
            TransportScript(),
        )
    }

    /** Real bounded `AudioRecord` listen window; the transcript is a fixed synthetic alternative. */
    private class DeviceCaptureRecognitionPort(private val context: Context) : SpeechRecognitionPort {
        val port = AndroidAudioRecordCapturePort(context)
        var framesObserved = 0
        private var listener: SpeechRecognitionListener? = null
        private var request: SpeechRecognitionRequest? = null

        override fun start(
            request: SpeechRecognitionRequest,
            listener: SpeechRecognitionListener,
        ): SpeechAdapterStartResult {
            this.request = request
            this.listener = listener
            val now = SystemClock.elapsedRealtime()
            val captureRequest = ExplicitAudioCaptureRequest(
                request.sessionId,
                AudioMonotonicTimeMs(now),
                AudioMonotonicTimeMs(now + CAPTURE_WINDOW_MS),
                AudioCaptureConfiguration(maximumDurationMs = CAPTURE_WINDOW_MS),
            )
            val started = port.start(captureRequest) { event -> onCaptureEvent(request, listener, event) }
            return if (started.disposition == AudioCaptureStartDisposition.ACCEPTED) {
                SpeechAdapterStartResult(SpeechAdapterStartDisposition.ACCEPTED, true)
            } else {
                this.listener = null
                SpeechAdapterStartResult(SpeechAdapterStartDisposition.UNAVAILABLE, false)
            }
        }

        override fun cancel(operationId: SpeechOperationId): SpeechAdapterCancelDisposition {
            request?.sessionId?.let { port.stop(it, AudioCaptureStopReason.CANCELLED) }
            return SpeechAdapterCancelDisposition.REQUESTED
        }

        override fun snapshot(): SpeechAdapterSnapshot =
            SpeechAdapterSnapshot(active = false, cancelling = false, activeOperationId = null)

        private fun onCaptureEvent(
            request: SpeechRecognitionRequest,
            listener: SpeechRecognitionListener,
            event: AudioCaptureEvent,
        ) {
            when (event) {
                is AudioCaptureEvent.Started -> listener.onEvent(
                    SpeechRecognitionEvent.Started(
                        request.operationId,
                        request.sessionId,
                        AudioMonotonicTimeMs(SystemClock.elapsedRealtime()),
                    ),
                )
                is AudioCaptureEvent.VoiceActivity -> framesObserved++
                is AudioCaptureEvent.Stopped -> {
                    val at = AudioMonotonicTimeMs(SystemClock.elapsedRealtime())
                    if (event.reason == AudioCaptureStopReason.DEADLINE_REACHED) {
                        val transcript = (EphemeralSpeechTranscript.create(listOf("what time is it"))
                            as SpeechContractResult.Success).value
                        listener.onEvent(
                            SpeechRecognitionEvent.FinalTranscript(
                                request.operationId,
                                request.sessionId,
                                at,
                                transcript,
                            ),
                        )
                    } else {
                        listener.onEvent(
                            SpeechRecognitionEvent.Cancelled(request.operationId, request.sessionId, at),
                        )
                    }
                }
                is AudioCaptureEvent.Failed -> listener.onEvent(
                    SpeechRecognitionEvent.Failed(
                        request.operationId,
                        request.sessionId,
                        AudioMonotonicTimeMs(SystemClock.elapsedRealtime()),
                        SpeechRecognitionFailure.AUDIO_INPUT_UNAVAILABLE,
                    ),
                )
            }
        }
    }

    /** Real local TTS output bound to the H049 synthesis port. */
    private class DeviceTtsSynthesisPort(context: Context) : SpeechSynthesisPort {
        private val appContext = context.applicationContext
        private val ready = CountDownLatch(1)
        private var engine: TextToSpeech? = null
        private var listener: SpeechSynthesisListener? = null
        private var request: SpeechSynthesisRequest? = null
        val spokeUtterances = java.util.Collections.synchronizedList(mutableListOf<String>())
        val speaking: Boolean get() = speakingFlag.get()

        private val speakingFlag = java.util.concurrent.atomic.AtomicBoolean(false)

        init {
            engine = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    engine?.language = Locale.US
                }
                ready.countDown()
            }
        }

        fun ready(): Boolean {
            if (!ready.await(10, TimeUnit.SECONDS)) return false
            val current = engine ?: return false
            return current.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE
        }

        override fun start(
            request: SpeechSynthesisRequest,
            listener: SpeechSynthesisListener,
        ): SpeechAdapterStartResult {
            val current = engine ?: return SpeechAdapterStartResult(SpeechAdapterStartDisposition.UNAVAILABLE, false)
            this.request = request
            this.listener = listener
            current.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId == request.operationId.value) {
                        speakingFlag.set(true)
                        listener.onEvent(
                            SpeechSynthesisEvent.Started(
                                request.operationId,
                                request.sessionId,
                                AudioMonotonicTimeMs(SystemClock.elapsedRealtime()),
                            ),
                        )
                    }
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == request.operationId.value) {
                        speakingFlag.set(false)
                        listener.onEvent(
                            SpeechSynthesisEvent.Completed(
                                request.operationId,
                                request.sessionId,
                                AudioMonotonicTimeMs(SystemClock.elapsedRealtime()),
                            ),
                        )
                    }
                }

                @Deprecated("Android callback")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == request.operationId.value) {
                        speakingFlag.set(false)
                        listener.onEvent(
                            SpeechSynthesisEvent.Failed(
                                request.operationId,
                                request.sessionId,
                                AudioMonotonicTimeMs(SystemClock.elapsedRealtime()),
                                SpeechSynthesisFailure.INTERNAL_FAILURE,
                            ),
                        )
                    }
                }
            })
            val queued = current.speak(
                request.text.value,
                TextToSpeech.QUEUE_FLUSH,
                null,
                request.operationId.value,
            )
            return if (queued == TextToSpeech.SUCCESS) {
                spokeUtterances.add(request.text.value)
                SpeechAdapterStartResult(SpeechAdapterStartDisposition.ACCEPTED, true)
            } else {
                SpeechAdapterStartResult(SpeechAdapterStartDisposition.UNAVAILABLE, false)
            }
        }

        override fun cancel(operationId: SpeechOperationId): SpeechAdapterCancelDisposition {
            engine?.stop()
            speakingFlag.set(false)
            return SpeechAdapterCancelDisposition.REQUESTED
        }

        override fun snapshot(): SpeechAdapterSnapshot =
            SpeechAdapterSnapshot(active = false, cancelling = false, activeOperationId = null)

        fun release() {
            engine?.stop()
            engine?.shutdown()
            engine = null
        }
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

    private class TransportScript {
        var frames: (String) -> Array<GatewayStreamReadResult> = { _ -> arrayOf() }
    }

    private class ScriptedTransport(
        private val script: TransportScript,
    ) : GatewayHttpStreamPort {
        var lastRequestJson: String? = null

        override fun open(
            endpoint: GatewayEndpointOrigin,
            bearerToken: ByteArray,
            requestDocument: ByteArray,
        ): GatewayTransportOpenResult {
            lastRequestJson = requestDocument.toString(StandardCharsets.UTF_8)
            return GatewayTransportOpenResult.Accepted(ScriptedChannel(script, lastRequestJson!!))
        }
    }

    private class ScriptedChannel(
        private val script: TransportScript,
        private val requestJson: String,
    ) : GatewayHttpStreamChannel {
        private val pending = java.util.concurrent.ConcurrentLinkedDeque<GatewayStreamReadResult>()
        var cancelCalls = 0
        var closeCalls = 0

        override fun readNext(): GatewayStreamReadResult {
            if (pending.isEmpty()) {
                val requestId = ((GatewayContractCodec.decodeRequest(requestJson)
                    as GatewayContractResult.Success<io.redi.robot.gateway.contract.GatewayRequest>).value)
                    .requestId.value
                script.frames(requestId).forEach(pending::addLast)
            }
            return pending.poll() ?: GatewayStreamReadResult.WouldBlock
        }

        override fun cancel(cancellationDocument: ByteArray): GatewayTransportCancellationDelivery {
            cancelCalls++
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

    private fun emptySnapshot(): ContextSnapshot {
        val budget = (ContextSnapshotBudget.create(16, 8, 32, 16_384) as ContextSnapshotResult.Success).value
        val request = (ContextSnapshotRequest.create(
            timestampMs(100L),
            null,
            ContextPrivacyClassification.entries.toSet(),
            budget,
        ) as ContextSnapshotResult.Success).value
        return (ContextSnapshotEngine.build(emptyList(), request) as ContextSnapshotResult.Success).value
    }

    private fun epoch(value: Long): GatewayAuthEpochSeconds =
        (GatewayAuthEpochSeconds.create(value) as GatewayAuthModelResult.Success).value

    private fun timestampMs(value: Long) =
        (io.redi.robot.capability.api.MonotonicTimestampMs.create(value)
            as io.redi.robot.capability.api.CapabilityEnvelopeResult.Success).value

    companion object {
        private const val STARTED_PAYLOAD = "{\"type\":\"started\"}"
        private const val CAPTURE_WINDOW_MS = 800L
        private const val FRESH_TURN_RETRY_DEADLINE_MS = 3_000L
        private const val FRESH_TURN_RETRY_INTERVAL_MS = 100L
        private const val BARGE_TEXT = "this spoken reply is deliberately long so barge in can interrupt it"

        private fun sessionId(index: Int): AudioSessionId =
            AudioSessionId.fromUuid(UUID.fromString("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"))
    }
}
