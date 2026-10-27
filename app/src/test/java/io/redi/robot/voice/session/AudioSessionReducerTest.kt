package io.redi.robot.voice.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class AudioSessionReducerTest {
    private val session = AudioSessionId.fromUuid(UUID.fromString("00000000-0000-0000-0000-000000000047"))
    private val other = AudioSessionId.fromUuid(UUID.fromString("00000000-0000-0000-0000-000000000048"))
    private val reducer = AudioSessionReducer()

    @Test
    fun `happy path traverses every conversational phase and releases once`() {
        var reduction = reducer.reduce(AudioSessionModel.IDLE, AudioSessionEvent.Activate(session, time(1_000)))
        assertPhase(reduction, AudioSessionPhase.ACTIVATING, AudioSessionEffect.RequestActivation::class.java)
        assertEquals(6_000L, reduction.model.phaseDeadlineAt?.value)

        reduction = reducer.reduce(reduction.model, AudioSessionEvent.ActivationReady(session, time(1_100)))
        assertPhase(reduction, AudioSessionPhase.LISTENING, AudioSessionEffect.RequestListening::class.java)
        assertEquals(16_100L, reduction.model.phaseDeadlineAt?.value)

        reduction = reducer.reduce(reduction.model, AudioSessionEvent.ListeningCompleted(session, time(1_200)))
        assertPhase(reduction, AudioSessionPhase.THINKING, AudioSessionEffect.RequestThinking::class.java)

        reduction = reducer.reduce(reduction.model, AudioSessionEvent.ThinkingCompleted(session, time(1_300)))
        assertPhase(reduction, AudioSessionPhase.SPEAKING, AudioSessionEffect.RequestSpeaking::class.java)

        reduction = reducer.reduce(reduction.model, AudioSessionEvent.SpeakingCompleted(session, time(1_400)))
        assertEquals(AudioSessionPhase.COMPLETED, reduction.model.phase)
        assertTrue(reduction.model.terminal is AudioSessionTerminal.Completed)
        assertEquals(1, reduction.effects.filterIsInstance<AudioSessionEffect.ReleaseSession>().size)
        assertNull(reduction.model.phaseDeadlineAt)
    }

    @Test
    fun `cancel has priority in every active work phase and releases exactly once`() {
        listOf(
            active(AudioSessionPhase.ACTIVATING),
            active(AudioSessionPhase.LISTENING),
            active(AudioSessionPhase.THINKING),
            active(AudioSessionPhase.SPEAKING),
        ).forEach { model ->
            val cancelled = reducer.reduce(
                model,
                AudioSessionEvent.Cancel(session, time(model.phaseEnteredAt!!.value + 1), AudioSessionCancellationReason.USER_REQUEST),
            )
            assertEquals(AudioSessionPhase.CANCELLING, cancelled.model.phase)
            assertEquals(AudioSessionReductionStatus.APPLIED, cancelled.status)
            assertEquals(1, cancelled.effects.filterIsInstance<AudioSessionEffect.CancelPhase>().size)
            assertEquals(1, cancelled.effects.filterIsInstance<AudioSessionEffect.ReleaseSession>().size)
            assertEquals(model.phase, (cancelled.effects.first() as AudioSessionEffect.CancelPhase).interruptedPhase)

            val repeated = reducer.reduce(
                cancelled.model,
                AudioSessionEvent.Cancel(session, time(cancelled.model.phaseEnteredAt!!.value + 1), AudioSessionCancellationReason.INTERRUPTED),
            )
            assertEquals(AudioSessionReductionStatus.IDEMPOTENT, repeated.status)
            assertTrue(repeated.effects.isEmpty())
        }
    }

    @Test
    fun `cancellation acknowledgement becomes typed terminal cancellation`() {
        val cancelling = cancel(active(AudioSessionPhase.LISTENING), AudioSessionCancellationReason.BACKGROUNDED)
        val result = reducer.reduce(
            cancelling,
            AudioSessionEvent.CancellationCompleted(session, time(cancelling.phaseEnteredAt!!.value + 1)),
        )
        assertEquals(AudioSessionPhase.CANCELLED, result.model.phase)
        val terminal = result.model.terminal as AudioSessionTerminal.Cancelled
        assertEquals(AudioSessionCancellationReason.BACKGROUNDED, terminal.reason)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun `each work phase timeout cancels first then records exact timed out phase`() {
        val timedReducer = AudioSessionReducer(shortPolicy())
        listOf(
            AudioSessionPhase.ACTIVATING,
            AudioSessionPhase.LISTENING,
            AudioSessionPhase.THINKING,
            AudioSessionPhase.SPEAKING,
        ).forEach { phase ->
            val model = active(phase, timedReducer)
            val timeout = timedReducer.reduce(
                model,
                AudioSessionEvent.DeadlineElapsed(session, model.phaseDeadlineAt!!),
            )
            assertEquals(AudioSessionPhase.CANCELLING, timeout.model.phase)
            assertEquals(AudioSessionCancellationReason.PHASE_TIMEOUT, timeout.model.cancellationReason)
            assertEquals(phase, timeout.model.timedOutPhase)
            assertEquals(1, timeout.effects.filterIsInstance<AudioSessionEffect.CancelPhase>().size)
            assertEquals(1, timeout.effects.filterIsInstance<AudioSessionEffect.ReleaseSession>().size)

            val completedAt = time(timeout.model.phaseEnteredAt!!.value + 1)
            val terminal = timedReducer.reduce(
                timeout.model,
                AudioSessionEvent.CancellationCompleted(session, completedAt),
            ).model
            assertEquals(AudioSessionPhase.ERROR, terminal.phase)
            assertEquals(phase, (terminal.terminal as AudioSessionTerminal.TimedOut).phase)
        }
    }

    @Test
    fun `cancellation itself times out deterministically without a second release`() {
        val timedReducer = AudioSessionReducer(shortPolicy())
        val cancelling = cancel(active(AudioSessionPhase.THINKING, timedReducer), reducer = timedReducer)
        val result = timedReducer.reduce(
            cancelling,
            AudioSessionEvent.DeadlineElapsed(session, cancelling.phaseDeadlineAt!!),
        )
        assertEquals(AudioSessionPhase.ERROR, result.model.phase)
        assertTrue(result.model.terminal is AudioSessionTerminal.CancellationTimedOut)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun `overall deadline clamps work but preserves a bounded cleanup window`() {
        val policy = AudioSessionPolicy(100, 100, 100, 100, 7, 25)
        val timedReducer = AudioSessionReducer(policy)
        var model = timedReducer.reduce(AudioSessionModel.IDLE, AudioSessionEvent.Activate(session, time(0))).model
        model = timedReducer.reduce(model, AudioSessionEvent.ActivationReady(session, time(5))).model
        assertEquals(25L, model.phaseDeadlineAt?.value)

        val cancelling = timedReducer.reduce(model, AudioSessionEvent.DeadlineElapsed(session, time(25))).model
        assertEquals(32L, cancelling.phaseDeadlineAt?.value)
        assertTrue(cancelling.phaseDeadlineAt!!.value > cancelling.overallDeadlineAt!!.value)
    }

    @Test
    fun `wrong session stale callback and early clock cannot mutate current session`() {
        val listening = active(AudioSessionPhase.LISTENING)
        val wrong = reducer.reduce(listening, AudioSessionEvent.ListeningCompleted(other, time(2_000)))
        assertEquals(AudioSessionReductionStatus.IGNORED_WRONG_SESSION, wrong.status)
        assertEquals(listening, wrong.model)

        val stale = reducer.reduce(
            listening,
            AudioSessionEvent.ListeningCompleted(session, time(listening.phaseEnteredAt!!.value - 1)),
        )
        assertEquals(AudioSessionReductionStatus.IGNORED_STALE_TIME, stale.status)
        assertEquals(listening, stale.model)

        val early = reducer.reduce(
            listening,
            AudioSessionEvent.DeadlineElapsed(session, time(listening.phaseDeadlineAt!!.value - 1)),
        )
        assertEquals(AudioSessionReductionStatus.IGNORED_EARLY, early.status)
        assertEquals(listening, early.model)
    }

    @Test
    fun `a new activation never replaces or implicitly cancels an active session`() {
        val listening = active(AudioSessionPhase.LISTENING)
        val result = reducer.reduce(listening, AudioSessionEvent.Activate(other, time(2_000)))
        assertEquals(AudioSessionReductionStatus.IGNORED_INVALID_STATE, result.status)
        assertEquals(listening, result.model)
        assertTrue(result.effects.isEmpty())
    }

    @Test
    fun `out of order callbacks cannot skip or regress phases`() {
        val cases = listOf(
            active(AudioSessionPhase.ACTIVATING) to AudioSessionEvent.ListeningCompleted(session, time(2_000)),
            active(AudioSessionPhase.LISTENING) to AudioSessionEvent.ThinkingCompleted(session, time(2_000)),
            active(AudioSessionPhase.THINKING) to AudioSessionEvent.SpeakingCompleted(session, time(2_000)),
            active(AudioSessionPhase.SPEAKING) to AudioSessionEvent.ActivationReady(session, time(2_000)),
        )
        cases.forEach { (model, event) ->
            val result = reducer.reduce(model, event)
            assertEquals(AudioSessionReductionStatus.IGNORED_INVALID_STATE, result.status)
            assertEquals(model, result.model)
            assertTrue(result.effects.isEmpty())
        }
    }

    @Test
    fun `typed failures terminate each work phase and release at most once`() {
        val cases = listOf(
            AudioSessionPhase.ACTIVATING to AudioSessionFailureReason.ACTIVATION_FAILED,
            AudioSessionPhase.LISTENING to AudioSessionFailureReason.LISTENING_FAILED,
            AudioSessionPhase.THINKING to AudioSessionFailureReason.THINKING_FAILED,
            AudioSessionPhase.SPEAKING to AudioSessionFailureReason.SPEAKING_FAILED,
        )
        cases.forEach { (phase, reason) ->
            val model = active(phase)
            val failed = reducer.reduce(
                model,
                AudioSessionEvent.Failure(session, time(model.phaseEnteredAt!!.value + 1), reason),
            )
            assertEquals(AudioSessionPhase.ERROR, failed.model.phase)
            assertEquals(reason, (failed.model.terminal as AudioSessionTerminal.Failed).reason)
            assertEquals(1, failed.effects.filterIsInstance<AudioSessionEffect.ReleaseSession>().size)
        }

        val cancelling = cancel(active(AudioSessionPhase.LISTENING))
        val failed = reducer.reduce(
            cancelling,
            AudioSessionEvent.Failure(session, time(cancelling.phaseEnteredAt!!.value + 1), AudioSessionFailureReason.RESOURCE_FAILURE),
        )
        assertTrue(failed.effects.isEmpty())
    }

    @Test
    fun `terminal state is idempotent and correlated reset is its only exit`() {
        val complete = completed()
        val repeated = reducer.reduce(
            complete,
            AudioSessionEvent.SpeakingCompleted(session, time(complete.phaseEnteredAt!!.value + 1)),
        )
        assertEquals(AudioSessionReductionStatus.IDEMPOTENT, repeated.status)
        assertTrue(repeated.effects.isEmpty())

        val wrongReset = reducer.reduce(complete, AudioSessionEvent.Reset(other, time(2_000)))
        assertEquals(AudioSessionReductionStatus.IGNORED_WRONG_SESSION, wrongReset.status)

        val reset = reducer.reduce(complete, AudioSessionEvent.Reset(session, time(2_000)))
        assertEquals(AudioSessionModel.IDLE, reset.model)
        assertEquals(AudioSessionReductionStatus.APPLIED, reset.status)
    }

    @Test
    fun `phase and cancellation transition table leaves every nonterminal state`() {
        val activePhases = AudioSessionPhase.entries.filter { it.isActive && it != AudioSessionPhase.CANCELLING }
        activePhases.forEach { phase ->
            val model = active(phase)
            val result = reducer.reduce(
                model,
                AudioSessionEvent.Cancel(session, time(model.phaseEnteredAt!!.value + 1), AudioSessionCancellationReason.INTERRUPTED),
            )
            assertEquals(AudioSessionPhase.CANCELLING, result.model.phase)
        }
        val cancelling = cancel(active(AudioSessionPhase.ACTIVATING))
        val exit = reducer.reduce(
            cancelling,
            AudioSessionEvent.CancellationCompleted(session, time(cancelling.phaseEnteredAt!!.value + 1)),
        )
        assertTrue(exit.model.phase.isTerminal)
        AudioSessionPhase.entries.filter { it.isTerminal }.forEach { phase ->
            val terminal = terminalFor(phase)
            assertEquals(
                AudioSessionPhase.IDLE,
                reducer.reduce(terminal, AudioSessionEvent.Reset(session, time(terminal.phaseEnteredAt!!.value + 1))).model.phase,
            )
        }
    }

    @Test
    fun `session IDs policy bounds and near-overflow activation fail closed`() {
        assertTrue(AudioSessionId.parse("00000000-0000-0000-0000-000000000047") is AudioSessionModelResult.Success)
        listOf("", "audio-1", "00000000-0000-0000-0000-000000000047 ").forEach {
            assertTrue(AudioSessionId.parse(it) is AudioSessionModelResult.Failure)
        }
        assertFails { AudioSessionPolicy(activationTimeoutMs = 0) }
        assertFails { AudioSessionPolicy(overallTimeoutMs = AudioSessionPolicy.MAX_OVERALL_TIMEOUT_MS + 1) }

        val overflow = reducer.reduce(
            AudioSessionModel.IDLE,
            AudioSessionEvent.Activate(session, time(Long.MAX_VALUE)),
        )
        assertEquals(AudioSessionReductionStatus.IGNORED_INVALID_STATE, overflow.status)
        assertEquals(AudioSessionModel.IDLE, overflow.model)
    }

    @Test
    fun `model is content free redacted and provider platform neutral`() {
        val listening = active(AudioSessionPhase.LISTENING)
        assertFalse(listening.toString().contains(session.value))
        val fieldNames = AudioSessionModel::class.java.declaredFields.map { it.name.lowercase() }
        listOf("transcript", "audio", "text", "provider", "authorization", "permission", "body").forEach { forbidden ->
            assertFalse("model field leak: $forbidden", fieldNames.any { it.contains(forbidden) })
        }

        val root = File("src/main/java/io/redi/robot/voice/session")
        val source = root.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        listOf(
            "import android.",
            "AudioRecord",
            "SpeechRecognizer",
            "TextToSpeech",
            "Bluetooth",
            "Gatt",
            "Looi",
            "CapabilityRequest",
            "AuthorizationGate",
            ".execute(",
            ".dispatch(",
        ).forEach { forbidden -> assertFalse("forbidden coupling: $forbidden", source.contains(forbidden)) }
    }

    private fun active(
        target: AudioSessionPhase,
        using: AudioSessionReducer = reducer,
    ): AudioSessionModel {
        var model = using.reduce(AudioSessionModel.IDLE, AudioSessionEvent.Activate(session, time(1_000))).model
        if (target == AudioSessionPhase.ACTIVATING) return model
        model = using.reduce(model, AudioSessionEvent.ActivationReady(session, time(1_001))).model
        if (target == AudioSessionPhase.LISTENING) return model
        model = using.reduce(model, AudioSessionEvent.ListeningCompleted(session, time(1_002))).model
        if (target == AudioSessionPhase.THINKING) return model
        model = using.reduce(model, AudioSessionEvent.ThinkingCompleted(session, time(1_003))).model
        if (target == AudioSessionPhase.SPEAKING) return model
        error("Unsupported active target: $target")
    }

    private fun cancel(
        model: AudioSessionModel,
        reason: AudioSessionCancellationReason = AudioSessionCancellationReason.USER_REQUEST,
        reducer: AudioSessionReducer = this.reducer,
    ): AudioSessionModel = reducer.reduce(
        model,
        AudioSessionEvent.Cancel(session, time(model.phaseEnteredAt!!.value + 1), reason),
    ).model

    private fun completed(): AudioSessionModel {
        val speaking = active(AudioSessionPhase.SPEAKING)
        return reducer.reduce(
            speaking,
            AudioSessionEvent.SpeakingCompleted(session, time(speaking.phaseEnteredAt!!.value + 1)),
        ).model
    }

    private fun terminalFor(phase: AudioSessionPhase): AudioSessionModel = when (phase) {
        AudioSessionPhase.COMPLETED -> completed()
        AudioSessionPhase.CANCELLED -> {
            val cancelling = cancel(active(AudioSessionPhase.LISTENING))
            reducer.reduce(
                cancelling,
                AudioSessionEvent.CancellationCompleted(session, time(cancelling.phaseEnteredAt!!.value + 1)),
            ).model
        }
        AudioSessionPhase.ERROR -> {
            val model = active(AudioSessionPhase.THINKING)
            reducer.reduce(
                model,
                AudioSessionEvent.Failure(session, time(model.phaseEnteredAt!!.value + 1), AudioSessionFailureReason.UNKNOWN_FAILURE),
            ).model
        }
        else -> error("Not terminal: $phase")
    }

    private fun assertPhase(
        reduction: AudioSessionReduction,
        phase: AudioSessionPhase,
        effectType: Class<out AudioSessionEffect>,
    ) {
        assertEquals(phase, reduction.model.phase)
        assertEquals(AudioSessionReductionStatus.APPLIED, reduction.status)
        assertEquals(1, reduction.effects.size)
        assertTrue(effectType.isInstance(reduction.effects.single()))
    }

    private fun shortPolicy() = AudioSessionPolicy(10, 10, 10, 10, 10, 100)

    private fun time(value: Long) = AudioMonotonicTimeMs(value)

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("expected IllegalArgumentException", failed)
    }
}
