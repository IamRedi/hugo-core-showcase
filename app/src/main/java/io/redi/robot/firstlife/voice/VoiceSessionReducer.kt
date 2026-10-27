package io.redi.robot.firstlife.voice

object VoiceSessionReducer {
    fun reduce(current: VoiceSessionModel, event: VoiceSessionEvent): VoiceSessionReduction {
        if (event is VoiceSessionEvent.Reset) {
            val effects = current.activeRequest?.takeIf { current.recognizerOwned }
                ?.let { listOf(VoiceSessionEffect.ReleaseRecognizer(it.id)) }
                .orEmpty()
            return VoiceSessionReduction(VoiceSessionModel.IDLE, effects, VoiceReductionStatus.APPLIED)
        }
        if (event is VoiceSessionEvent.ExplicitStart) return start(current, event)
        if (event is VoiceSessionEvent.ExplicitSystemFallbackReview) return systemFallbackReview(current, event)
        val requestId = event.requestId() ?: return ignored(current, VoiceReductionStatus.IGNORED_INVALID_STATE)
        if (current.activeRequest?.id != requestId) return ignored(current, VoiceReductionStatus.IGNORED_WRONG_REQUEST)

        return when (event) {
            is VoiceSessionEvent.PermissionResult -> permission(current, event)
            is VoiceSessionEvent.SystemFallbackConsent -> systemFallbackConsent(current, event)
            is VoiceSessionEvent.SupportResult -> support(current, event)
            is VoiceSessionEvent.CaptureStarted -> captureStarted(current)
            is VoiceSessionEvent.SpeechEnded -> speechEnded(current)
            is VoiceSessionEvent.Results -> results(current, event)
            is VoiceSessionEvent.Failure -> failure(current, event.failure)
            is VoiceSessionEvent.DeadlineElapsed -> deadline(current, event)
            is VoiceSessionEvent.Cancel -> terminate(current, VoiceSessionBlockedReason.CANCELLED)
            is VoiceSessionEvent.Backgrounded -> terminate(current, VoiceSessionBlockedReason.BACKGROUNDED)
            is VoiceSessionEvent.ExplicitStart,
            is VoiceSessionEvent.ExplicitSystemFallbackReview,
            VoiceSessionEvent.Reset,
            -> error("handled before dispatch")
        }
    }

    private fun start(current: VoiceSessionModel, event: VoiceSessionEvent.ExplicitStart): VoiceSessionReduction {
        require(event.request.tier == VoiceRecognitionTier.ON_DEVICE)
        if (current.phase == VoiceSessionPhase.AWAITING_SYSTEM_CONSENT ||
            current.phase == VoiceSessionPhase.AWAITING_PERMISSION ||
            current.phase == VoiceSessionPhase.CHECKING_SUPPORT ||
            current.phase == VoiceSessionPhase.LISTENING ||
            current.phase == VoiceSessionPhase.PROCESSING
        ) return ignored(current, VoiceReductionStatus.IGNORED_INVALID_STATE)
        val model = VoiceSessionModel(
            phase = VoiceSessionPhase.AWAITING_PERMISSION,
            activeRequest = event.request,
            recognizerOwned = false,
            evidence = null,
            blockedReason = null,
        )
        return applied(model, VoiceSessionEffect.RequestMicrophonePermission(event.request.id))
    }

    private fun systemFallbackReview(
        current: VoiceSessionModel,
        event: VoiceSessionEvent.ExplicitSystemFallbackReview,
    ): VoiceSessionReduction {
        if (event.request.tier != VoiceRecognitionTier.SYSTEM_NETWORK_ASSISTED) return invalid(current)
        val eligible = current.phase == VoiceSessionPhase.BLOCKED && current.blockedReason in setOf(
            VoiceSessionBlockedReason.ON_DEVICE_RECOGNIZER_UNAVAILABLE,
            VoiceSessionBlockedReason.ALBANIAN_MODEL_DOWNLOAD_REQUIRED,
            VoiceSessionBlockedReason.ALBANIAN_MODEL_UNAVAILABLE,
        )
        if (!eligible) return invalid(current)
        return applied(
            VoiceSessionModel(
                VoiceSessionPhase.AWAITING_SYSTEM_CONSENT,
                event.request,
                recognizerOwned = false,
                evidence = null,
                blockedReason = null,
            ),
        )
    }

    private fun systemFallbackConsent(
        current: VoiceSessionModel,
        event: VoiceSessionEvent.SystemFallbackConsent,
    ): VoiceSessionReduction {
        if (current.phase != VoiceSessionPhase.AWAITING_SYSTEM_CONSENT ||
            current.activeRequest?.tier != VoiceRecognitionTier.SYSTEM_NETWORK_ASSISTED
        ) return invalid(current)
        val captureRequest = current.activeRequest.copy(
            startedAtMs = event.consentedAtMs,
            deadlineAtMs = event.consentedAtMs + VoiceCapturePolicy.MAX_DURATION_MS,
        )
        return applied(
            current.copy(phase = VoiceSessionPhase.AWAITING_PERMISSION, activeRequest = captureRequest),
            VoiceSessionEffect.RequestMicrophonePermission(event.requestId),
        )
    }

    private fun permission(current: VoiceSessionModel, event: VoiceSessionEvent.PermissionResult): VoiceSessionReduction {
        if (current.phase != VoiceSessionPhase.AWAITING_PERMISSION) return invalid(current)
        if (!event.granted) {
            return blocked(
                current,
                if (event.canAskAgain) VoiceSessionBlockedReason.PERMISSION_DENIED else VoiceSessionBlockedReason.PERMISSION_PERMANENTLY_DENIED,
            )
        }
        val request = requireNotNull(current.activeRequest)
        return if (request.tier == VoiceRecognitionTier.ON_DEVICE) {
            applied(
                current.copy(phase = VoiceSessionPhase.CHECKING_SUPPORT),
                VoiceSessionEffect.CheckOnDeviceSupport(event.requestId),
            )
        } else {
            applied(
                current.copy(phase = VoiceSessionPhase.LISTENING, recognizerOwned = true),
                VoiceSessionEffect.StartSystemCapture(request),
            )
        }
    }

    private fun support(current: VoiceSessionModel, event: VoiceSessionEvent.SupportResult): VoiceSessionReduction {
        if (current.phase != VoiceSessionPhase.CHECKING_SUPPORT) return invalid(current)
        return when (event.support) {
            VoiceOnDeviceSupport.SUPPORTED -> {
                val request = requireNotNull(current.activeRequest)
                applied(
                    current.copy(phase = VoiceSessionPhase.LISTENING, recognizerOwned = true),
                    VoiceSessionEffect.StartOnDeviceCapture(request),
                )
            }
            VoiceOnDeviceSupport.RECOGNIZER_UNAVAILABLE -> blocked(current, VoiceSessionBlockedReason.ON_DEVICE_RECOGNIZER_UNAVAILABLE)
            VoiceOnDeviceSupport.ALBANIAN_MODEL_DOWNLOAD_REQUIRED -> blocked(current, VoiceSessionBlockedReason.ALBANIAN_MODEL_DOWNLOAD_REQUIRED)
            VoiceOnDeviceSupport.ALBANIAN_MODEL_UNAVAILABLE -> blocked(current, VoiceSessionBlockedReason.ALBANIAN_MODEL_UNAVAILABLE)
        }
    }

    private fun captureStarted(current: VoiceSessionModel): VoiceSessionReduction =
        if (current.phase == VoiceSessionPhase.LISTENING) idempotent(current) else invalid(current)

    private fun speechEnded(current: VoiceSessionModel): VoiceSessionReduction {
        if (current.phase != VoiceSessionPhase.LISTENING) return invalid(current)
        return applied(current.copy(phase = VoiceSessionPhase.PROCESSING))
    }

    private fun results(current: VoiceSessionModel, event: VoiceSessionEvent.Results): VoiceSessionReduction {
        if (current.phase != VoiceSessionPhase.LISTENING && current.phase != VoiceSessionPhase.PROCESSING) return invalid(current)
        val transcript = event.candidates.values.firstOrNull() ?: return terminate(current, VoiceSessionBlockedReason.NO_MATCH)
        val evidence = VoiceRecognitionEvidence(
            requireNotNull(current.activeRequest).tier,
            requireNotNull(current.activeRequest).localeTag,
            event.candidates.values.size,
            transcript.codePointCount,
        )
        val model = current.copy(
            phase = VoiceSessionPhase.COMPLETED,
            recognizerOwned = false,
            evidence = evidence,
            blockedReason = null,
        )
        return applied(
            model,
            VoiceSessionEffect.ReleaseRecognizer(event.requestId),
            VoiceSessionEffect.DeliverTranscript(event.requestId, transcript),
        )
    }

    private fun failure(current: VoiceSessionModel, failure: VoiceRecognizerFailure): VoiceSessionReduction = terminate(
        current,
        when (failure) {
            VoiceRecognizerFailure.NO_MATCH -> VoiceSessionBlockedReason.NO_MATCH
            VoiceRecognizerFailure.SPEECH_TIMEOUT -> VoiceSessionBlockedReason.TIMEOUT
            VoiceRecognizerFailure.BUSY -> VoiceSessionBlockedReason.RECOGNIZER_BUSY
            VoiceRecognizerFailure.AUDIO -> VoiceSessionBlockedReason.AUDIO_UNAVAILABLE
            VoiceRecognizerFailure.NETWORK -> if (current.activeRequest?.tier == VoiceRecognitionTier.ON_DEVICE) {
                VoiceSessionBlockedReason.NETWORK_ATTEMPT_REJECTED
            } else {
                VoiceSessionBlockedReason.RECOGNIZER_FAILURE
            }
            VoiceRecognizerFailure.CLIENT_CANCELLED -> VoiceSessionBlockedReason.CANCELLED
            VoiceRecognizerFailure.OTHER -> VoiceSessionBlockedReason.RECOGNIZER_FAILURE
        },
    )

    private fun deadline(current: VoiceSessionModel, event: VoiceSessionEvent.DeadlineElapsed): VoiceSessionReduction {
        val deadline = current.activeRequest?.deadlineAtMs ?: return invalid(current)
        if (event.nowMs < deadline) return ignored(current, VoiceReductionStatus.IGNORED_EARLY)
        return terminate(current, VoiceSessionBlockedReason.TIMEOUT)
    }

    private fun terminate(current: VoiceSessionModel, reason: VoiceSessionBlockedReason): VoiceSessionReduction {
        if (current.phase == VoiceSessionPhase.BLOCKED || current.phase == VoiceSessionPhase.COMPLETED || current.phase == VoiceSessionPhase.IDLE) {
            return idempotent(current)
        }
        val release = current.activeRequest?.takeIf { current.recognizerOwned }
            ?.let { listOf(VoiceSessionEffect.ReleaseRecognizer(it.id)) }
            .orEmpty()
        return VoiceSessionReduction(
            current.copy(
                phase = VoiceSessionPhase.BLOCKED,
                recognizerOwned = false,
                evidence = null,
                blockedReason = reason,
            ),
            release,
            VoiceReductionStatus.APPLIED,
        )
    }

    private fun blocked(current: VoiceSessionModel, reason: VoiceSessionBlockedReason): VoiceSessionReduction =
        VoiceSessionReduction(
            current.copy(phase = VoiceSessionPhase.BLOCKED, recognizerOwned = false, evidence = null, blockedReason = reason),
            emptyList(),
            VoiceReductionStatus.APPLIED,
        )

    private fun VoiceSessionEvent.requestId(): VoiceSessionRequestId? = when (this) {
        is VoiceSessionEvent.PermissionResult -> requestId
        is VoiceSessionEvent.SystemFallbackConsent -> requestId
        is VoiceSessionEvent.SupportResult -> requestId
        is VoiceSessionEvent.CaptureStarted -> requestId
        is VoiceSessionEvent.SpeechEnded -> requestId
        is VoiceSessionEvent.Results -> requestId
        is VoiceSessionEvent.Failure -> requestId
        is VoiceSessionEvent.DeadlineElapsed -> requestId
        is VoiceSessionEvent.Cancel -> requestId
        is VoiceSessionEvent.Backgrounded -> requestId
        is VoiceSessionEvent.ExplicitStart -> request.id
        is VoiceSessionEvent.ExplicitSystemFallbackReview -> request.id
        VoiceSessionEvent.Reset -> null
    }

    private fun applied(model: VoiceSessionModel, vararg effects: VoiceSessionEffect) =
        VoiceSessionReduction(model, effects.toList(), VoiceReductionStatus.APPLIED)

    private fun idempotent(model: VoiceSessionModel) =
        VoiceSessionReduction(model, emptyList(), VoiceReductionStatus.IDEMPOTENT)

    private fun invalid(model: VoiceSessionModel) = ignored(model, VoiceReductionStatus.IGNORED_INVALID_STATE)

    private fun ignored(model: VoiceSessionModel, status: VoiceReductionStatus) =
        VoiceSessionReduction(model, emptyList(), status)
}
