package io.redi.robot.voice.api

import io.redi.robot.interaction.localintent.LocalIntentRouteStatus
import io.redi.robot.voice.session.AudioMonotonicTimeMs
import io.redi.robot.voice.session.AudioSessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class LocalSpeechInteractionCoordinatorTest {
    @Test
    fun `fake transcript routes exact local status intent before any AI or execution path`() {
        val harness = Harness()
        val request = recognitionRequest()
        assertEquals(SpeechInteractionStartDisposition.ACCEPTED, harness.coordinator.startRecognition(request).disposition)

        harness.recognition.emit(
            0,
            SpeechRecognitionEvent.Started(OPERATION, SESSION, time(10)),
        )
        val transcript = transcript("connection status")
        harness.recognition.emit(
            0,
            SpeechRecognitionEvent.FinalTranscript(OPERATION, SESSION, time(20), transcript),
        )

        assertTrue(transcript.consumed)
        assertEquals(2, harness.events.size)
        val routed = harness.events[1] as SpeechInteractionEvent.LocalIntentRouted
        assertEquals(LocalIntentRouteStatus.PROPOSED, routed.report.status)
        assertEquals("provider.connection.status@1.0", routed.report.proposal!!.capability.wireValue)
        assertFalse(routed.executesCapability)
        assertFalse(routed.selectsProvider)
        assertFalse(routed.grantsAuthorization)
        assertFalse(harness.coordinator.snapshot().active)
        assertEquals(listOf(OPERATION), harness.focus.releases)
        assertFalse(routed.toString().contains("connection status"))
    }

    @Test
    fun `ambiguous and unknown transcripts remain non executable local routing evidence`() {
        val harness = Harness()
        harness.coordinator.startRecognition(recognitionRequest())
        harness.recognition.emit(
            0,
            SpeechRecognitionEvent.FinalTranscript(
                OPERATION,
                SESSION,
                time(20),
                transcript("connection status", "lower contact status"),
            ),
        )
        val ambiguous = harness.events.single() as SpeechInteractionEvent.LocalIntentRouted
        assertEquals(LocalIntentRouteStatus.AMBIGUOUS, ambiguous.report.status)
        assertNull(ambiguous.report.proposal)

        harness.coordinator.startRecognition(recognitionRequest(operation = OPERATION_2))
        harness.recognition.emit(
            1,
            SpeechRecognitionEvent.FinalTranscript(
                OPERATION_2,
                SESSION,
                time(40),
                transcript("do something nearby"),
            ),
        )
        val noMatch = harness.events.last() as SpeechInteractionEvent.LocalIntentRouted
        assertEquals(LocalIntentRouteStatus.NO_MATCH, noMatch.report.status)
        assertFalse(noMatch.executesCapability)
    }

    @Test
    fun `interruption cancels fake TTS releases focus and rejects late completion`() {
        val harness = Harness()
        val request = synthesisRequest()
        assertEquals(SpeechInteractionStartDisposition.ACCEPTED, harness.coordinator.startSynthesis(request).disposition)
        harness.synthesis.emit(0, SpeechSynthesisEvent.Started(OPERATION, SESSION, time(10)))

        assertEquals(
            SpeechInterruptionDisposition.REQUESTED,
            harness.coordinator.interrupt(SESSION, time(20), SpeechInterruptionReason.NEW_SPEECH),
        )
        assertEquals(listOf(OPERATION), harness.synthesis.cancellations)
        assertEquals(listOf(OPERATION), harness.focus.releases)
        assertFalse(harness.coordinator.snapshot().active)
        val interrupted = harness.events.last() as SpeechInteractionEvent.Interrupted
        assertEquals(SpeechInteractionMode.SYNTHESIS, interrupted.mode)
        assertEquals(SpeechInterruptionReason.NEW_SPEECH, interrupted.reason)
        assertEquals(SpeechAdapterCancelDisposition.REQUESTED, interrupted.cancellation)

        harness.synthesis.emit(0, SpeechSynthesisEvent.Completed(OPERATION, SESSION, time(30)))
        assertEquals(2, harness.events.size)
        assertFalse(harness.events.joinToString().contains("private spoken response"))
    }

    @Test
    fun `audio focus loss cancels active output exactly once`() {
        val harness = Harness()
        harness.coordinator.startSynthesis(synthesisRequest())
        harness.focus.emitLoss(0, time(15), SpeechAudioFocusLoss.PERMANENT)
        harness.focus.emitLoss(0, time(16), SpeechAudioFocusLoss.PERMANENT)

        assertEquals(listOf(OPERATION), harness.synthesis.cancellations)
        assertEquals(listOf(OPERATION), harness.focus.releases)
        val event = harness.events.single() as SpeechInteractionEvent.Interrupted
        assertEquals(SpeechInterruptionReason.AUDIO_FOCUS_LOST, event.reason)
    }

    @Test
    fun `focus denial and adapter rejection fail closed without leaking ownership`() {
        val denied = Harness().also { it.focus.acquireDisposition = SpeechAudioFocusAcquireDisposition.DENIED }
        assertEquals(
            SpeechInteractionStartDisposition.FOCUS_DENIED,
            denied.coordinator.startRecognition(recognitionRequest()).disposition,
        )
        assertEquals(0, denied.recognition.starts.size)
        assertFalse(denied.coordinator.snapshot().active)

        val rejected = Harness().also { it.recognition.startDisposition = SpeechAdapterStartDisposition.UNSUPPORTED_LOCALE }
        assertEquals(
            SpeechInteractionStartDisposition.UNSUPPORTED_LOCALE,
            rejected.coordinator.startRecognition(recognitionRequest()).disposition,
        )
        assertEquals(listOf(OPERATION), rejected.focus.releases)
        assertFalse(rejected.coordinator.snapshot().active)
    }

    @Test
    fun `terminal adapter results release focus and preserve typed failures`() {
        val recognition = Harness()
        recognition.coordinator.startRecognition(recognitionRequest())
        recognition.recognition.emit(
            0,
            SpeechRecognitionEvent.Failed(
                OPERATION,
                SESSION,
                time(20),
                SpeechRecognitionFailure.AUDIO_INPUT_UNAVAILABLE,
            ),
        )
        val recognitionFailure = recognition.events.single() as SpeechInteractionEvent.AdapterFailed
        assertEquals(
            SpeechInteractionFailure.Recognition(SpeechRecognitionFailure.AUDIO_INPUT_UNAVAILABLE),
            recognitionFailure.failure,
        )
        assertEquals(listOf(OPERATION), recognition.focus.releases)

        val synthesis = Harness()
        synthesis.coordinator.startSynthesis(synthesisRequest())
        synthesis.synthesis.emit(0, SpeechSynthesisEvent.Completed(OPERATION, SESSION, time(20)))
        assertTrue(synthesis.events.single() is SpeechInteractionEvent.SynthesisCompleted)
        assertEquals(listOf(OPERATION), synthesis.focus.releases)
    }

    @Test
    fun `deadline cancels adapter and stale or wrong correlation never advances current work`() {
        val harness = Harness()
        harness.coordinator.startRecognition(recognitionRequest(deadline = 100))
        harness.recognition.emit(
            0,
            SpeechRecognitionEvent.Started(OPERATION, SESSION, time(0)),
        )
        harness.recognition.emit(
            0,
            SpeechRecognitionEvent.Started(OPERATION_2, SESSION, time(10)),
        )
        assertTrue(harness.events.isEmpty())

        val lateTranscript = transcript("connection status")
        harness.recognition.emit(
            0,
            SpeechRecognitionEvent.FinalTranscript(OPERATION, SESSION, time(100), lateTranscript),
        )
        assertTrue(lateTranscript.consumed)
        assertEquals(listOf(OPERATION), harness.recognition.cancellations)
        assertTrue(harness.events.single() is SpeechInteractionEvent.DeadlineExceeded)
        assertFalse(harness.coordinator.snapshot().active)
    }

    @Test
    fun `captured attempt ordinal rejects stale callback when operation id is reused`() {
        val harness = Harness()
        harness.coordinator.startRecognition(recognitionRequest())
        harness.recognition.emit(
            0,
            SpeechRecognitionEvent.FinalTranscript(OPERATION, SESSION, time(20), transcript("connection status")),
        )
        harness.coordinator.startRecognition(recognitionRequest(requested = 30, deadline = 200))

        val stale = transcript("lower contact status")
        harness.recognition.emit(
            0,
            SpeechRecognitionEvent.FinalTranscript(OPERATION, SESSION, time(40), stale),
        )
        assertTrue(stale.consumed)
        assertTrue(harness.coordinator.snapshot().active)

        harness.recognition.emit(
            1,
            SpeechRecognitionEvent.FinalTranscript(OPERATION, SESSION, time(50), transcript("connection status")),
        )
        assertFalse(harness.coordinator.snapshot().active)
        assertEquals(2, harness.events.filterIsInstance<SpeechInteractionEvent.LocalIntentRouted>().size)
    }

    @Test
    fun `wrong session stale and repeated interruptions are deterministic`() {
        val harness = Harness()
        harness.coordinator.startSynthesis(synthesisRequest(requested = 10, deadline = 200))
        assertEquals(
            SpeechInterruptionDisposition.WRONG_SESSION,
            harness.coordinator.interrupt(SESSION_2, time(20), SpeechInterruptionReason.USER_REQUEST),
        )
        assertEquals(
            SpeechInterruptionDisposition.STALE_TIME,
            harness.coordinator.interrupt(SESSION, time(9), SpeechInterruptionReason.USER_REQUEST),
        )
        assertEquals(
            SpeechInterruptionDisposition.REQUESTED,
            harness.coordinator.interrupt(SESSION, time(20), SpeechInterruptionReason.BACKGROUNDED),
        )
        assertEquals(
            SpeechInterruptionDisposition.NOT_ACTIVE,
            harness.coordinator.interrupt(SESSION, time(21), SpeechInterruptionReason.BACKGROUNDED),
        )
        assertEquals(1, harness.synthesis.cancellations.size)
        assertEquals(1, harness.focus.releases.size)
    }

    @Test
    fun `contract bounds redaction and one shot transcript semantics fail closed`() {
        assertTrue(SpeechOperationId.parse(OPERATION.value) is SpeechContractResult.Success)
        assertEquals(
            SpeechContractError.INVALID_OPERATION_ID,
            (SpeechOperationId.parse("not-an-operation") as SpeechContractResult.Failure).error,
        )
        assertEquals(
            SpeechContractError.INVALID_LOCALE,
            (SpeechLocale.parse("en_us") as SpeechContractResult.Failure).error,
        )
        assertEquals(
            SpeechContractError.INVALID_TEXT,
            (SpeechText.create("bad\ntext") as SpeechContractResult.Failure).error,
        )
        assertEquals(
            SpeechContractError.INVALID_TRANSCRIPT,
            (EphemeralSpeechTranscript.create(emptyList()) as SpeechContractResult.Failure).error,
        )
        val oneShot = transcript("private phrase")
        assertEquals(listOf("private phrase"), oneShot.consume { it })
        assertTrue(oneShot.consumed)
        assertFailsState { oneShot.consume { it } }
        assertFalse(oneShot.toString().contains("private phrase"))

        val recognition = recognitionRequest()
        assertTrue(recognition.requiresExplicitCapture)
        assertEquals(320, recognition.captureConfiguration.samplesPerFrame)
        assertFalse(recognition.toString().contains(OPERATION.value))
        assertFalse(synthesisRequest().toString().contains("private spoken response"))
        assertFailsArgument { recognitionRequest(deadline = 30_002) }
        assertFailsArgument { synthesisRequest(deadline = 60_002) }
    }

    @Test
    fun `deterministic conversation trace contains state and semantic evidence but no content`() {
        val harness = Harness()
        harness.coordinator.startRecognition(recognitionRequest())
        harness.recognition.emit(0, SpeechRecognitionEvent.Started(OPERATION, SESSION, time(10)))
        harness.recognition.emit(
            0,
            SpeechRecognitionEvent.FinalTranscript(OPERATION, SESSION, time(20), transcript("connection status")),
        )
        harness.coordinator.startSynthesis(synthesisRequest(operation = OPERATION_2, requested = 30, deadline = 200))
        harness.synthesis.emit(0, SpeechSynthesisEvent.Started(OPERATION_2, SESSION, time(40)))
        harness.coordinator.interrupt(SESSION, time(50), SpeechInterruptionReason.NEW_SPEECH)

        val trace = harness.events.map(::trace)
        assertEquals(
            listOf(
                "RECOGNITION_STARTED",
                "LOCAL_INTENT_PROPOSED:provider.connection.status@1.0:execution=false",
                "SYNTHESIS_STARTED",
                "SYNTHESIS_INTERRUPTED:NEW_SPEECH:REQUESTED",
            ),
            trace,
        )
        val joined = trace.joinToString()
        assertFalse(joined.contains("connection status"))
        assertFalse(joined.contains("private spoken response"))
    }

    @Test
    fun `speech API remains Android provider credential transport and First Life independent`() {
        val source = locate("app/src/main/java/io/redi/robot/voice/api")
            .walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        listOf(
            "import android.",
            "import androidx.",
            "SpeechRecognizer",
            "TextToSpeech",
            "AudioManager",
            "Bluetooth",
            "Gatt",
            "LOOI",
            "firstlife",
            "ApiKey",
            "apiKey",
            "Bearer",
            "Authorization: Bearer",
            "http://",
            "https://",
        ).forEach { forbidden -> assertFalse("speech API leak: $forbidden", source.contains(forbidden)) }
    }

    private fun trace(event: SpeechInteractionEvent): String = when (event) {
        is SpeechInteractionEvent.AdapterStarted -> "${event.mode.name}_STARTED"
        is SpeechInteractionEvent.LocalIntentRouted ->
            "LOCAL_INTENT_${event.report.status.name}:${event.report.proposal?.capability?.wireValue}:execution=false"
        is SpeechInteractionEvent.RecognitionNoMatch -> "RECOGNITION_NO_MATCH"
        is SpeechInteractionEvent.SynthesisCompleted -> "SYNTHESIS_COMPLETED"
        is SpeechInteractionEvent.AdapterFailed -> "${event.mode.name}_FAILED:${event.failure}"
        is SpeechInteractionEvent.DeadlineExceeded -> "${event.mode.name}_DEADLINE_EXCEEDED"
        is SpeechInteractionEvent.Interrupted ->
            "${event.mode.name}_INTERRUPTED:${event.reason.name}:${event.cancellation.name}"
    }

    private fun recognitionRequest(
        operation: SpeechOperationId = OPERATION,
        requested: Long = 1,
        deadline: Long = 1_000,
    ) = SpeechRecognitionRequest(operation, SESSION, locale(), time(requested), time(deadline))

    private fun synthesisRequest(
        operation: SpeechOperationId = OPERATION,
        requested: Long = 1,
        deadline: Long = 1_000,
    ) = SpeechSynthesisRequest(
        operation,
        SESSION,
        locale(),
        time(requested),
        time(deadline),
        model(SpeechText.create("private spoken response")),
    )

    private fun transcript(vararg alternatives: String): EphemeralSpeechTranscript =
        model(EphemeralSpeechTranscript.create(alternatives.toList()))

    private fun locale(): SpeechLocale = model(SpeechLocale.parse("en-US"))
    private fun time(value: Long) = AudioMonotonicTimeMs(value)

    private fun <T> model(result: SpeechContractResult<T>): T = when (result) {
        is SpeechContractResult.Success -> result.value
        is SpeechContractResult.Failure -> error("invalid test model: ${result.error}")
    }

    private fun locate(relative: String): File = sequenceOf(File(relative), File("../$relative"), File("../../$relative"))
        .firstOrNull(File::exists) ?: error("Unable to locate $relative")

    private fun assertFailsArgument(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("expected IllegalArgumentException", failed)
    }

    private fun assertFailsState(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue("expected IllegalStateException", failed)
    }

    private class Harness {
        val recognition = FakeRecognitionPort()
        val synthesis = FakeSynthesisPort()
        val focus = FakeFocusPort()
        val events = mutableListOf<SpeechInteractionEvent>()
        val coordinator = LocalSpeechInteractionCoordinator(recognition, synthesis, focus, listener = events::add)
    }

    private class FakeRecognitionPort : SpeechRecognitionPort {
        var startDisposition = SpeechAdapterStartDisposition.ACCEPTED
        val starts = mutableListOf<SpeechRecognitionRequest>()
        val cancellations = mutableListOf<SpeechOperationId>()
        private val registrations = mutableListOf<Pair<SpeechRecognitionRequest, SpeechRecognitionListener>>()
        private var activeRegistration: Int? = null

        private val active: SpeechOperationId?
            get() = activeRegistration?.let { registrations[it].first.operationId }

        override fun start(
            request: SpeechRecognitionRequest,
            listener: SpeechRecognitionListener,
        ): SpeechAdapterStartResult {
            starts += request
            if (startDisposition == SpeechAdapterStartDisposition.ACCEPTED) {
                registrations += request to listener
                activeRegistration = registrations.lastIndex
            }
            return SpeechAdapterStartResult(
                startDisposition,
                operationOwned = startDisposition == SpeechAdapterStartDisposition.ACCEPTED,
            )
        }

        override fun cancel(operationId: SpeechOperationId): SpeechAdapterCancelDisposition {
            val current = active ?: return SpeechAdapterCancelDisposition.NOT_ACTIVE
            if (current != operationId) return SpeechAdapterCancelDisposition.WRONG_OPERATION
            cancellations += operationId
            activeRegistration = null
            return SpeechAdapterCancelDisposition.REQUESTED
        }

        override fun snapshot() = SpeechAdapterSnapshot(active != null, false, active)

        fun emit(index: Int, event: SpeechRecognitionEvent) {
            val terminal = event is SpeechRecognitionEvent.FinalTranscript ||
                event is SpeechRecognitionEvent.NoMatch ||
                event is SpeechRecognitionEvent.Cancelled ||
                event is SpeechRecognitionEvent.Failed
            registrations[index].second.onEvent(event)
            if (terminal && activeRegistration == index) activeRegistration = null
        }
    }

    private class FakeSynthesisPort : SpeechSynthesisPort {
        var startDisposition = SpeechAdapterStartDisposition.ACCEPTED
        val starts = mutableListOf<SpeechSynthesisRequest>()
        val cancellations = mutableListOf<SpeechOperationId>()
        private val registrations = mutableListOf<Pair<SpeechSynthesisRequest, SpeechSynthesisListener>>()
        private var activeRegistration: Int? = null

        private val active: SpeechOperationId?
            get() = activeRegistration?.let { registrations[it].first.operationId }

        override fun start(
            request: SpeechSynthesisRequest,
            listener: SpeechSynthesisListener,
        ): SpeechAdapterStartResult {
            starts += request
            if (startDisposition == SpeechAdapterStartDisposition.ACCEPTED) {
                registrations += request to listener
                activeRegistration = registrations.lastIndex
            }
            return SpeechAdapterStartResult(
                startDisposition,
                operationOwned = startDisposition == SpeechAdapterStartDisposition.ACCEPTED,
            )
        }

        override fun cancel(operationId: SpeechOperationId): SpeechAdapterCancelDisposition {
            val current = active ?: return SpeechAdapterCancelDisposition.NOT_ACTIVE
            if (current != operationId) return SpeechAdapterCancelDisposition.WRONG_OPERATION
            cancellations += operationId
            activeRegistration = null
            return SpeechAdapterCancelDisposition.REQUESTED
        }

        override fun snapshot() = SpeechAdapterSnapshot(active != null, false, active)

        fun emit(index: Int, event: SpeechSynthesisEvent) {
            val terminal = event is SpeechSynthesisEvent.Completed ||
                event is SpeechSynthesisEvent.Cancelled ||
                event is SpeechSynthesisEvent.Failed
            registrations[index].second.onEvent(event)
            if (terminal && activeRegistration == index) activeRegistration = null
        }
    }

    private class FakeFocusPort : SpeechAudioFocusPort {
        var acquireDisposition = SpeechAudioFocusAcquireDisposition.GRANTED
        val releases = mutableListOf<SpeechOperationId>()
        private val registrations = mutableListOf<Pair<SpeechAudioFocusRequest, SpeechAudioFocusListener>>()
        private var active: SpeechOperationId? = null

        override fun acquire(
            request: SpeechAudioFocusRequest,
            listener: SpeechAudioFocusListener,
        ): SpeechAudioFocusAcquireResult {
            if (active != null) return SpeechAudioFocusAcquireResult(SpeechAudioFocusAcquireDisposition.BUSY, false)
            registrations += request to listener
            if (acquireDisposition == SpeechAudioFocusAcquireDisposition.GRANTED) active = request.operationId
            return SpeechAudioFocusAcquireResult(
                acquireDisposition,
                ownsFocus = acquireDisposition == SpeechAudioFocusAcquireDisposition.GRANTED,
            )
        }

        override fun release(operationId: SpeechOperationId): SpeechAudioFocusReleaseDisposition {
            val current = active ?: return SpeechAudioFocusReleaseDisposition.NOT_OWNED
            if (current != operationId) return SpeechAudioFocusReleaseDisposition.WRONG_OPERATION
            releases += operationId
            active = null
            return SpeechAudioFocusReleaseDisposition.RELEASED
        }

        override fun snapshot() = SpeechAudioFocusSnapshot(active != null, active)

        fun emitLoss(index: Int, at: AudioMonotonicTimeMs, loss: SpeechAudioFocusLoss) {
            val (request, listener) = registrations[index]
            listener.onFocusEvent(SpeechAudioFocusEvent(request.operationId, request.sessionId, at, loss))
        }
    }

    companion object {
        private val OPERATION = SpeechOperationId.fromUuid(UUID.fromString("00000000-0000-0000-0000-000000000049"))
        private val OPERATION_2 = SpeechOperationId.fromUuid(UUID.fromString("00000000-0000-0000-0000-000000000149"))
        private val SESSION = AudioSessionId.fromUuid(UUID.fromString("10000000-0000-0000-0000-000000000049"))
        private val SESSION_2 = AudioSessionId.fromUuid(UUID.fromString("20000000-0000-0000-0000-000000000049"))
    }
}
