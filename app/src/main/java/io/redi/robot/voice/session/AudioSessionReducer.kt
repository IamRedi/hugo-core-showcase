package io.redi.robot.voice.session

import java.util.Collections

/** Pure H047 lifecycle reducer. Effects describe work; this type performs no audio or provider I/O. */
class AudioSessionReducer(
    private val policy: AudioSessionPolicy = AudioSessionPolicy(),
) {
    fun reduce(current: AudioSessionModel, event: AudioSessionEvent): AudioSessionReduction {
        if (event is AudioSessionEvent.Activate) return activate(current, event)
        if (current.phase == AudioSessionPhase.IDLE) return ignored(current, AudioSessionReductionStatus.IGNORED_INVALID_STATE)
        if (event.sessionId != current.sessionId) return ignored(current, AudioSessionReductionStatus.IGNORED_WRONG_SESSION)
        if (event.occurredAt.value < requireNotNull(current.phaseEnteredAt).value) {
            return ignored(current, AudioSessionReductionStatus.IGNORED_STALE_TIME)
        }
        if (event is AudioSessionEvent.Reset) return reset(current)
        if (current.phase.isTerminal) return ignored(current, AudioSessionReductionStatus.IDEMPOTENT)

        // Cancellation is deliberately handled before phase callbacks.
        if (event is AudioSessionEvent.Cancel) return cancel(current, event.reason, event.occurredAt)
        if (event is AudioSessionEvent.DeadlineElapsed) return deadline(current, event.occurredAt)
        if (event is AudioSessionEvent.Failure) return failure(current, event)

        return when (current.phase) {
            AudioSessionPhase.ACTIVATING -> when (event) {
                is AudioSessionEvent.ActivationReady -> advance(current, AudioSessionPhase.LISTENING, event.occurredAt)
                else -> invalid(current)
            }
            AudioSessionPhase.LISTENING -> when (event) {
                is AudioSessionEvent.ListeningCompleted -> advance(current, AudioSessionPhase.THINKING, event.occurredAt)
                else -> invalid(current)
            }
            AudioSessionPhase.THINKING -> when (event) {
                is AudioSessionEvent.ThinkingCompleted -> advance(current, AudioSessionPhase.SPEAKING, event.occurredAt)
                else -> invalid(current)
            }
            AudioSessionPhase.SPEAKING -> when (event) {
                is AudioSessionEvent.SpeakingCompleted -> complete(current, event.occurredAt)
                else -> invalid(current)
            }
            AudioSessionPhase.CANCELLING -> when (event) {
                is AudioSessionEvent.CancellationCompleted -> cancellationCompleted(current, event.occurredAt)
                else -> invalid(current)
            }
            AudioSessionPhase.IDLE,
            AudioSessionPhase.COMPLETED,
            AudioSessionPhase.CANCELLED,
            AudioSessionPhase.ERROR,
            -> error("handled before phase dispatch")
        }
    }

    private fun activate(
        current: AudioSessionModel,
        event: AudioSessionEvent.Activate,
    ): AudioSessionReduction {
        if (current.phase != AudioSessionPhase.IDLE) return invalid(current)
        if (event.occurredAt.value > Long.MAX_VALUE - policy.overallTimeoutMs) return invalid(current)
        val overallDeadline = AudioMonotonicTimeMs(event.occurredAt.value + policy.overallTimeoutMs)
        val model = AudioSessionModel(
            phase = AudioSessionPhase.ACTIVATING,
            sessionId = event.sessionId,
            startedAt = event.occurredAt,
            phaseEnteredAt = event.occurredAt,
            phaseDeadlineAt = deadlineFor(AudioSessionPhase.ACTIVATING, event.occurredAt, overallDeadline),
            overallDeadlineAt = overallDeadline,
            cancellationReason = null,
            timedOutPhase = null,
            terminal = null,
        )
        return applied(model, AudioSessionEffect.RequestActivation(event.sessionId))
    }

    private fun advance(
        current: AudioSessionModel,
        next: AudioSessionPhase,
        at: AudioMonotonicTimeMs,
    ): AudioSessionReduction {
        if (at.value >= requireNotNull(current.phaseDeadlineAt).value) return beginTimeout(current, at)
        val model = current.copy(
            phase = next,
            phaseEnteredAt = at,
            phaseDeadlineAt = deadlineFor(next, at, requireNotNull(current.overallDeadlineAt)),
        )
        val effect = when (next) {
            AudioSessionPhase.LISTENING -> AudioSessionEffect.RequestListening(requireNotNull(current.sessionId))
            AudioSessionPhase.THINKING -> AudioSessionEffect.RequestThinking(requireNotNull(current.sessionId))
            AudioSessionPhase.SPEAKING -> AudioSessionEffect.RequestSpeaking(requireNotNull(current.sessionId))
            else -> error("unsupported active transition")
        }
        return applied(model, effect)
    }

    private fun complete(current: AudioSessionModel, at: AudioMonotonicTimeMs): AudioSessionReduction {
        if (at.value >= requireNotNull(current.phaseDeadlineAt).value) return beginTimeout(current, at)
        val id = requireNotNull(current.sessionId)
        return applied(
            terminal(current, AudioSessionPhase.COMPLETED, at, AudioSessionTerminal.Completed(at)),
            AudioSessionEffect.ReleaseSession(id),
        )
    }

    private fun cancel(
        current: AudioSessionModel,
        reason: AudioSessionCancellationReason,
        at: AudioMonotonicTimeMs,
    ): AudioSessionReduction {
        if (current.phase == AudioSessionPhase.CANCELLING) return ignored(current, AudioSessionReductionStatus.IDEMPOTENT)
        return beginCancellation(current, reason, null, at)
    }

    private fun deadline(current: AudioSessionModel, at: AudioMonotonicTimeMs): AudioSessionReduction {
        if (at.value < requireNotNull(current.phaseDeadlineAt).value) {
            return ignored(current, AudioSessionReductionStatus.IGNORED_EARLY)
        }
        return if (current.phase == AudioSessionPhase.CANCELLING) {
            applied(
                terminal(current, AudioSessionPhase.ERROR, at, AudioSessionTerminal.CancellationTimedOut(at)),
            )
        } else {
            beginTimeout(current, at)
        }
    }

    private fun beginTimeout(current: AudioSessionModel, at: AudioMonotonicTimeMs): AudioSessionReduction =
        beginCancellation(current, AudioSessionCancellationReason.PHASE_TIMEOUT, current.phase, at)

    private fun beginCancellation(
        current: AudioSessionModel,
        reason: AudioSessionCancellationReason,
        timedOutPhase: AudioSessionPhase?,
        at: AudioMonotonicTimeMs,
    ): AudioSessionReduction {
        val id = requireNotNull(current.sessionId)
        val cancelling = current.copy(
            phase = AudioSessionPhase.CANCELLING,
            phaseEnteredAt = at,
            phaseDeadlineAt = deadlineFor(AudioSessionPhase.CANCELLING, at, requireNotNull(current.overallDeadlineAt)),
            cancellationReason = reason,
            timedOutPhase = timedOutPhase,
        )
        return applied(
            cancelling,
            AudioSessionEffect.CancelPhase(id, current.phase, reason),
            AudioSessionEffect.ReleaseSession(id),
        )
    }

    private fun cancellationCompleted(
        current: AudioSessionModel,
        at: AudioMonotonicTimeMs,
    ): AudioSessionReduction {
        if (at.value >= requireNotNull(current.phaseDeadlineAt).value) {
            return applied(
                terminal(current, AudioSessionPhase.ERROR, at, AudioSessionTerminal.CancellationTimedOut(at)),
            )
        }
        val timeout = current.timedOutPhase
        return if (timeout != null) {
            applied(terminal(current, AudioSessionPhase.ERROR, at, AudioSessionTerminal.TimedOut(timeout, at)))
        } else {
            val reason = requireNotNull(current.cancellationReason)
            applied(terminal(current, AudioSessionPhase.CANCELLED, at, AudioSessionTerminal.Cancelled(reason, at)))
        }
    }

    private fun failure(
        current: AudioSessionModel,
        event: AudioSessionEvent.Failure,
    ): AudioSessionReduction {
        val effects = if (current.phase == AudioSessionPhase.CANCELLING) {
            emptyArray()
        } else {
            arrayOf(AudioSessionEffect.ReleaseSession(requireNotNull(current.sessionId)))
        }
        return applied(
            terminal(current, AudioSessionPhase.ERROR, event.occurredAt, AudioSessionTerminal.Failed(event.reason, event.occurredAt)),
            *effects,
        )
    }

    private fun reset(current: AudioSessionModel): AudioSessionReduction = if (current.phase.isTerminal) {
        applied(AudioSessionModel.IDLE)
    } else {
        invalid(current)
    }

    private fun terminal(
        current: AudioSessionModel,
        phase: AudioSessionPhase,
        at: AudioMonotonicTimeMs,
        terminal: AudioSessionTerminal,
    ): AudioSessionModel = current.copy(
        phase = phase,
        phaseEnteredAt = at,
        phaseDeadlineAt = null,
        cancellationReason = null,
        timedOutPhase = null,
        terminal = terminal,
    )

    private fun deadlineFor(
        phase: AudioSessionPhase,
        enteredAt: AudioMonotonicTimeMs,
        overallDeadline: AudioMonotonicTimeMs,
    ): AudioMonotonicTimeMs {
        val duration = policy.timeoutFor(phase)
        val phaseDeadline = if (enteredAt.value > Long.MAX_VALUE - duration) {
            Long.MAX_VALUE
        } else {
            enteredAt.value + duration
        }
        return AudioMonotonicTimeMs(
            if (phase == AudioSessionPhase.CANCELLING) phaseDeadline else minOf(phaseDeadline, overallDeadline.value),
        )
    }

    private fun applied(model: AudioSessionModel, vararg effects: AudioSessionEffect): AudioSessionReduction =
        AudioSessionReduction(
            model,
            Collections.unmodifiableList(effects.toList()),
            AudioSessionReductionStatus.APPLIED,
        )

    private fun invalid(model: AudioSessionModel): AudioSessionReduction =
        ignored(model, AudioSessionReductionStatus.IGNORED_INVALID_STATE)

    private fun ignored(
        model: AudioSessionModel,
        status: AudioSessionReductionStatus,
    ): AudioSessionReduction = AudioSessionReduction(model, emptyList(), status)
}
