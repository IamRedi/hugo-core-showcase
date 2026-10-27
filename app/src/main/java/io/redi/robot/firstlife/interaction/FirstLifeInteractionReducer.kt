package io.redi.robot.firstlife.interaction

object FirstLifeInteractionReducer {
    fun reduce(
        current: FirstLifeInteractionModel,
        event: FirstLifeInteractionEvent,
    ): FirstLifeInteractionReduction {
        if (event.sessionId != current.sessionId) return ignored(current, FirstLifeReductionStatus.IGNORED_WRONG_SESSION)
        if (event.sequence.value <= current.lastSequence || event.occurredAt.value < current.lastOccurredAt.value) {
            return ignored(current, FirstLifeReductionStatus.IGNORED_STALE)
        }
        if (event is FirstLifeInteractionEvent.FatalFailure) {
            return if (current.state == FirstLifeInteractionState.FAILED) idempotent(current, event) else fatal(current, event)
        }
        if (event is FirstLifeInteractionEvent.ResetSession) return reset(current, event)
        if (event is FirstLifeInteractionEvent.BodyStatusChanged) return bodyChanged(current, event)
        if (event is FirstLifeInteractionEvent.CancelSession) return cancel(current, event)
        if (event is FirstLifeInteractionEvent.CancellationCompleted) return cancellationCompleted(current, event)
        if (current.state == FirstLifeInteractionState.CANCELLING || current.state == FirstLifeInteractionState.FAILED) {
            return ignored(current, FirstLifeReductionStatus.IGNORED_INVALID_STATE)
        }

        return when (event) {
            is FirstLifeInteractionEvent.StartIntroduction -> startIntroduction(current, event)
            is FirstLifeInteractionEvent.SpeechStarted -> speechStarted(current, event)
            is FirstLifeInteractionEvent.SpeechCompleted -> speechCompleted(current, event)
            is FirstLifeInteractionEvent.SpeechUnavailable -> speechFailure(
                current,
                event.sequence,
                event.occurredAt,
                event.request,
                FirstLifeBlockedReason.SPEECH_OUTPUT_UNAVAILABLE,
            )
            is FirstLifeInteractionEvent.SpeechCancelled -> speechFailure(
                current,
                event.sequence,
                event.occurredAt,
                event.request,
                if (event.request.kind == FirstLifeRequestKind.INTRODUCTION_SPEECH) {
                    FirstLifeBlockedReason.INTRODUCTION_CANCELLED
                } else {
                    FirstLifeBlockedReason.SPEECH_CANCELLED
                },
            )
            is FirstLifeInteractionEvent.RequestNameInput -> requestNameInput(current, event)
            is FirstLifeInteractionEvent.NameInputStarted -> nameInputStarted(current, event)
            is FirstLifeInteractionEvent.NameInputCompleted -> nameInputCompleted(current, event)
            is FirstLifeInteractionEvent.NameInputDeadlineElapsed -> nameInputDeadline(current, event)
            is FirstLifeInteractionEvent.BodyStatusChanged,
            is FirstLifeInteractionEvent.CancelSession,
            is FirstLifeInteractionEvent.CancellationCompleted,
            is FirstLifeInteractionEvent.ResetSession,
            is FirstLifeInteractionEvent.FatalFailure,
            -> error("handled before dispatch")
        }
    }

    private fun startIntroduction(
        current: FirstLifeInteractionModel,
        event: FirstLifeInteractionEvent.StartIntroduction,
    ): FirstLifeInteractionReduction {
        if (current.hugoIdentity == null || current.state != FirstLifeInteractionState.READY) {
            return ignored(current, FirstLifeReductionStatus.IGNORED_INVALID_STATE)
        }
        val request = FirstLifeRequestReference(current.sessionId, FirstLifeRequestKind.INTRODUCTION_SPEECH, event.sequence.value)
        val model = current.advance(event, FirstLifeInteractionState.INTRODUCTION_READY, request = FirstLifeActiveRequest(request))
        return applied(
            model,
            FirstLifeInteractionEffect.SpeakText(request, FirstLifeDialogue.introduction()),
            present(model),
        )
    }

    private fun speechStarted(
        current: FirstLifeInteractionModel,
        event: FirstLifeInteractionEvent.SpeechStarted,
    ): FirstLifeInteractionReduction {
        if (!current.matches(event.request)) return ignored(current, FirstLifeReductionStatus.IGNORED_WRONG_REQUEST)
        val target = when {
            current.state == FirstLifeInteractionState.INTRODUCTION_READY &&
                event.request.kind == FirstLifeRequestKind.INTRODUCTION_SPEECH -> FirstLifeInteractionState.INTRO_SPEAKING
            current.state == FirstLifeInteractionState.GREETING_READY &&
                event.request.kind == FirstLifeRequestKind.GREETING_SPEECH -> FirstLifeInteractionState.GREETING_SPEAKING
            else -> return ignored(current, FirstLifeReductionStatus.IGNORED_INVALID_STATE)
        }
        val model = current.advance(event, target, request = current.activeRequest)
        return applied(model, present(model))
    }

    private fun speechCompleted(
        current: FirstLifeInteractionModel,
        event: FirstLifeInteractionEvent.SpeechCompleted,
    ): FirstLifeInteractionReduction {
        if (!current.matches(event.request)) return ignored(current, FirstLifeReductionStatus.IGNORED_WRONG_REQUEST)
        return when {
            current.state == FirstLifeInteractionState.INTRO_SPEAKING &&
                event.request.kind == FirstLifeRequestKind.INTRODUCTION_SPEECH -> {
                val model = current.advance(event, FirstLifeInteractionState.AWAITING_LISTEN_START)
                applied(model, present(model))
            }
            current.state == FirstLifeInteractionState.GREETING_SPEAKING &&
                event.request.kind == FirstLifeRequestKind.GREETING_SPEECH -> {
                val model = current.advance(event, FirstLifeInteractionState.READY_FOR_NEXT_PHASE)
                applied(
                    model,
                    present(model),
                    FirstLifeInteractionEffect.ProceedToObservationPhase(model.sessionId),
                )
            }
            else -> ignored(current, FirstLifeReductionStatus.IGNORED_INVALID_STATE)
        }
    }

    private fun speechFailure(
        current: FirstLifeInteractionModel,
        sequence: FirstLifeEventSequence,
        occurredAt: FirstLifeMonotonicTimeMs,
        request: FirstLifeRequestReference,
        reason: FirstLifeBlockedReason,
    ): FirstLifeInteractionReduction {
        if (!current.matches(request)) return ignored(current, FirstLifeReductionStatus.IGNORED_WRONG_REQUEST)
        val model = current.block(sequence, occurredAt, reason)
        return applied(model, present(model))
    }

    private fun requestNameInput(
        current: FirstLifeInteractionModel,
        event: FirstLifeInteractionEvent.RequestNameInput,
    ): FirstLifeInteractionReduction {
        if (current.state != FirstLifeInteractionState.AWAITING_LISTEN_START) {
            return ignored(current, FirstLifeReductionStatus.IGNORED_INVALID_STATE)
        }
        if (event.deadline.value <= event.occurredAt.value) {
            return ignored(current, FirstLifeReductionStatus.IGNORED_LATE)
        }
        val request = FirstLifeRequestReference(current.sessionId, FirstLifeRequestKind.NAME_INPUT, event.sequence.value)
        val model = current.advance(
            event,
            FirstLifeInteractionState.NAME_INPUT_REQUESTED,
            request = FirstLifeActiveRequest(request, event.deadline),
        )
        return applied(
            model,
            FirstLifeInteractionEffect.RequestNameInput(request, event.deadline),
            present(model),
        )
    }

    private fun nameInputStarted(
        current: FirstLifeInteractionModel,
        event: FirstLifeInteractionEvent.NameInputStarted,
    ): FirstLifeInteractionReduction {
        if (!current.matches(event.request)) return ignored(current, FirstLifeReductionStatus.IGNORED_WRONG_REQUEST)
        if (current.state != FirstLifeInteractionState.NAME_INPUT_REQUESTED) {
            return ignored(current, FirstLifeReductionStatus.IGNORED_INVALID_STATE)
        }
        if (current.isAfterDeadline(event.occurredAt)) return ignored(current, FirstLifeReductionStatus.IGNORED_LATE)
        val model = current.advance(event, FirstLifeInteractionState.LISTENING, request = current.activeRequest)
        return applied(model, present(model))
    }

    private fun nameInputCompleted(
        current: FirstLifeInteractionModel,
        event: FirstLifeInteractionEvent.NameInputCompleted,
    ): FirstLifeInteractionReduction {
        if (!current.matches(event.request)) return ignored(current, FirstLifeReductionStatus.IGNORED_WRONG_REQUEST)
        if (current.state != FirstLifeInteractionState.LISTENING) {
            return ignored(current, FirstLifeReductionStatus.IGNORED_INVALID_STATE)
        }
        if (current.isAfterDeadline(event.occurredAt) && event.result != FirstLifeNameInputResult.TimedOut) {
            return ignored(current, FirstLifeReductionStatus.IGNORED_LATE)
        }
        return when (val result = event.result) {
            is FirstLifeNameInputResult.Success -> acceptOrRejectName(current, event, result)
            FirstLifeNameInputResult.NoSpeech -> blockNameInput(current, event, FirstLifeBlockedReason.NO_SPEECH)
            FirstLifeNameInputResult.Ambiguous -> blockNameInput(current, event, FirstLifeBlockedReason.AMBIGUOUS_NAME)
            FirstLifeNameInputResult.Cancelled -> blockNameInput(current, event, FirstLifeBlockedReason.NAME_INPUT_CANCELLED)
            FirstLifeNameInputResult.TimedOut -> blockNameInput(current, event, FirstLifeBlockedReason.NAME_INPUT_TIMEOUT)
        }
    }

    private fun acceptOrRejectName(
        current: FirstLifeInteractionModel,
        event: FirstLifeInteractionEvent.NameInputCompleted,
        result: FirstLifeNameInputResult.Success,
    ): FirstLifeInteractionReduction = when (val validation = FirstLifeNameValidator.validate(result.rawText)) {
        is FirstLifeNameValidation.Invalid -> blockNameInput(current, event, FirstLifeBlockedReason.INVALID_NAME)
        is FirstLifeNameValidation.Valid -> {
            val greetingRequest = FirstLifeRequestReference(
                current.sessionId,
                FirstLifeRequestKind.GREETING_SPEECH,
                event.sequence.value,
            )
            val model = current.advance(
                event,
                FirstLifeInteractionState.GREETING_READY,
                name = validation.name,
                request = FirstLifeActiveRequest(greetingRequest),
            )
            applied(
                model,
                FirstLifeInteractionEffect.CancelNameInput(event.request),
                present(model),
                FirstLifeInteractionEffect.SpeakText(greetingRequest, FirstLifeDialogue.greeting(validation.name)),
            )
        }
    }

    private fun blockNameInput(
        current: FirstLifeInteractionModel,
        event: FirstLifeInteractionEvent.NameInputCompleted,
        reason: FirstLifeBlockedReason,
    ): FirstLifeInteractionReduction {
        val model = current.block(event.sequence, event.occurredAt, reason)
        return applied(
            model,
            FirstLifeInteractionEffect.CancelNameInput(event.request),
            FirstLifeInteractionEffect.NameRetryRequired(reason),
            present(model),
        )
    }

    private fun nameInputDeadline(
        current: FirstLifeInteractionModel,
        event: FirstLifeInteractionEvent.NameInputDeadlineElapsed,
    ): FirstLifeInteractionReduction {
        if (!current.matches(event.request)) return ignored(current, FirstLifeReductionStatus.IGNORED_WRONG_REQUEST)
        if (current.state != FirstLifeInteractionState.NAME_INPUT_REQUESTED && current.state != FirstLifeInteractionState.LISTENING) {
            return ignored(current, FirstLifeReductionStatus.IGNORED_INVALID_STATE)
        }
        val deadline = current.activeRequest?.deadline ?: return ignored(current, FirstLifeReductionStatus.IGNORED_WRONG_REQUEST)
        if (event.occurredAt.value < deadline.value) return ignored(current, FirstLifeReductionStatus.IGNORED_LATE)
        val model = current.block(event.sequence, event.occurredAt, FirstLifeBlockedReason.NAME_INPUT_TIMEOUT)
        return applied(
            model,
            FirstLifeInteractionEffect.CancelNameInput(event.request),
            FirstLifeInteractionEffect.NameRetryRequired(FirstLifeBlockedReason.NAME_INPUT_TIMEOUT),
            present(model),
        )
    }

    private fun bodyChanged(
        current: FirstLifeInteractionModel,
        event: FirstLifeInteractionEvent.BodyStatusChanged,
    ): FirstLifeInteractionReduction {
        if (current.bodyStatus == event.status) return idempotent(current, event)
        val model = current.copy(
            bodyStatus = event.status,
            lastSequence = event.sequence.value,
            lastOccurredAt = event.occurredAt,
        )
        return applied(model, present(model))
    }

    private fun cancel(
        current: FirstLifeInteractionModel,
        event: FirstLifeInteractionEvent.CancelSession,
    ): FirstLifeInteractionReduction {
        if (current.state == FirstLifeInteractionState.CANCELLING || current.state == FirstLifeInteractionState.READY) {
            return idempotent(current, event)
        }
        val cleanup = when (current.activeRequest?.reference?.kind) {
            FirstLifeRequestKind.INTRODUCTION_SPEECH,
            FirstLifeRequestKind.GREETING_SPEECH,
            -> listOf(FirstLifeInteractionEffect.StopSpeaking(current.activeRequest.reference))
            FirstLifeRequestKind.NAME_INPUT -> listOf(FirstLifeInteractionEffect.CancelNameInput(current.activeRequest.reference))
            null -> emptyList()
        }
        val model = current.advance(event, FirstLifeInteractionState.CANCELLING, name = null)
        return applied(model, *(cleanup + present(model)).toTypedArray())
    }

    private fun cancellationCompleted(
        current: FirstLifeInteractionModel,
        event: FirstLifeInteractionEvent.CancellationCompleted,
    ): FirstLifeInteractionReduction {
        if (current.state != FirstLifeInteractionState.CANCELLING) {
            return idempotent(current, event)
        }
        val model = current.advance(event, FirstLifeInteractionState.READY, name = null)
        return applied(model, present(model))
    }

    private fun reset(
        current: FirstLifeInteractionModel,
        event: FirstLifeInteractionEvent.ResetSession,
    ): FirstLifeInteractionReduction {
        if (event.newSessionId == current.sessionId) return ignored(current, FirstLifeReductionStatus.IGNORED_INVALID_STATE)
        val cleanup = when (current.activeRequest?.reference?.kind) {
            FirstLifeRequestKind.INTRODUCTION_SPEECH,
            FirstLifeRequestKind.GREETING_SPEECH,
            -> listOf(FirstLifeInteractionEffect.StopSpeaking(current.activeRequest.reference))
            FirstLifeRequestKind.NAME_INPUT -> listOf(FirstLifeInteractionEffect.CancelNameInput(current.activeRequest.reference))
            null -> emptyList()
        }
        val reset = FirstLifeInteractionModel.start(current.hugoIdentity, event.newSessionId, event.occurredAt)
            .copy(bodyStatus = current.bodyStatus)
        return FirstLifeInteractionReduction(
            reset,
            cleanup + FirstLifeInteractionEffect.PresentState(FirstLifePresentationProjection.project(reset)),
            FirstLifeReductionStatus.APPLIED,
        )
    }

    private fun fatal(
        current: FirstLifeInteractionModel,
        event: FirstLifeInteractionEvent.FatalFailure,
    ): FirstLifeInteractionReduction {
        val cleanup = when (current.activeRequest?.reference?.kind) {
            FirstLifeRequestKind.INTRODUCTION_SPEECH,
            FirstLifeRequestKind.GREETING_SPEECH,
            -> listOf(FirstLifeInteractionEffect.StopSpeaking(current.activeRequest.reference))
            FirstLifeRequestKind.NAME_INPUT -> listOf(FirstLifeInteractionEffect.CancelNameInput(current.activeRequest.reference))
            null -> emptyList()
        }
        val model = current.copy(
            state = FirstLifeInteractionState.FAILED,
            lastSequence = event.sequence.value,
            lastOccurredAt = event.occurredAt,
            sessionName = null,
            activeRequest = null,
            blockedReason = null,
            fatalReason = event.reason,
        )
        return FirstLifeInteractionReduction(
            model,
            cleanup + FirstLifeInteractionEffect.PresentState(FirstLifePresentationIntent.ERROR),
            FirstLifeReductionStatus.APPLIED,
        )
    }

    private fun FirstLifeInteractionModel.advance(
        event: FirstLifeInteractionEvent,
        target: FirstLifeInteractionState,
        name: FirstLifeSessionName? = sessionName,
        request: FirstLifeActiveRequest? = null,
    ): FirstLifeInteractionModel = copy(
        state = target,
        lastSequence = event.sequence.value,
        lastOccurredAt = event.occurredAt,
        sessionName = name,
        activeRequest = request,
        blockedReason = null,
        fatalReason = null,
    )

    private fun FirstLifeInteractionModel.block(
        sequence: FirstLifeEventSequence,
        occurredAt: FirstLifeMonotonicTimeMs,
        reason: FirstLifeBlockedReason,
    ): FirstLifeInteractionModel = copy(
        state = FirstLifeInteractionState.BLOCKED,
        lastSequence = sequence.value,
        lastOccurredAt = occurredAt,
        activeRequest = null,
        blockedReason = reason,
        fatalReason = null,
    )

    private fun FirstLifeInteractionModel.matches(reference: FirstLifeRequestReference): Boolean =
        reference.sessionId == sessionId && activeRequest?.reference == reference

    private fun FirstLifeInteractionModel.isAfterDeadline(at: FirstLifeMonotonicTimeMs): Boolean =
        activeRequest?.deadline?.let { at.value > it.value } ?: false

    private fun present(model: FirstLifeInteractionModel): FirstLifeInteractionEffect.PresentState =
        FirstLifeInteractionEffect.PresentState(FirstLifePresentationProjection.project(model))

    private fun applied(
        model: FirstLifeInteractionModel,
        vararg effects: FirstLifeInteractionEffect,
    ): FirstLifeInteractionReduction = FirstLifeInteractionReduction(
        model,
        effects.toList(),
        FirstLifeReductionStatus.APPLIED,
    )

    private fun ignored(
        model: FirstLifeInteractionModel,
        status: FirstLifeReductionStatus,
    ): FirstLifeInteractionReduction = FirstLifeInteractionReduction(model, emptyList(), status)

    private fun idempotent(
        model: FirstLifeInteractionModel,
        event: FirstLifeInteractionEvent,
    ): FirstLifeInteractionReduction = FirstLifeInteractionReduction(
        model.copy(lastSequence = event.sequence.value, lastOccurredAt = event.occurredAt),
        emptyList(),
        FirstLifeReductionStatus.IDEMPOTENT,
    )
}
