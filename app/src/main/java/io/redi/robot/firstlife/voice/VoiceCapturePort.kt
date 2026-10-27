package io.redi.robot.firstlife.voice

interface VoiceCapturePort {
    fun checkOnDeviceSupport(
        requestId: VoiceSessionRequestId,
        callback: (VoiceSessionRequestId, VoiceOnDeviceSupport) -> Unit,
    )

    fun startOnDeviceCapture(request: VoiceSessionRequest, listener: VoiceCaptureListener)

    fun startSystemCapture(request: VoiceSessionRequest, listener: VoiceCaptureListener)

    fun release(requestId: VoiceSessionRequestId)
}

interface VoiceCaptureListener {
    fun onCaptureStarted(requestId: VoiceSessionRequestId)
    fun onSpeechEnded(requestId: VoiceSessionRequestId)
    fun onResults(requestId: VoiceSessionRequestId, candidates: VoiceTranscriptCandidates)
    fun onFailure(requestId: VoiceSessionRequestId, failure: VoiceRecognizerFailure)
}

class VoiceSessionCoordinator(
    private val port: VoiceCapturePort,
    private val nowMs: () -> Long,
    private val requestIdFactory: () -> VoiceSessionRequestId,
    private val onPermissionRequested: (VoiceSessionRequestId) -> Unit,
    private val onTranscript: (VoiceTranscript) -> Unit,
    private val onModelChanged: (VoiceSessionModel) -> Unit,
) : VoiceCaptureListener {
    private val lock = Any()
    private var current = VoiceSessionModel.IDLE

    val model: VoiceSessionModel get() = synchronized(lock) { current }

    fun startExplicitSession() {
        val start = nowMs()
        dispatch(
            VoiceSessionEvent.ExplicitStart(
                VoiceSessionRequest(requestIdFactory(), start, start + VoiceCapturePolicy.MAX_DURATION_MS),
            ),
        )
    }

    fun reviewSystemFallback() {
        val start = nowMs()
        dispatch(
            VoiceSessionEvent.ExplicitSystemFallbackReview(
                VoiceSessionRequest(
                    requestIdFactory(),
                    start,
                    start + VoiceCapturePolicy.MAX_DURATION_MS,
                    VoiceRecognitionTier.SYSTEM_NETWORK_ASSISTED,
                    VoiceCapturePolicy.SYSTEM_FALLBACK_LOCALE_TAG,
                ),
            ),
        )
    }

    fun consentToSystemFallback() {
        model.activeRequest?.let { dispatch(VoiceSessionEvent.SystemFallbackConsent(it.id, nowMs())) }
    }

    fun onPermissionResult(requestId: VoiceSessionRequestId, granted: Boolean, canAskAgain: Boolean) {
        dispatch(VoiceSessionEvent.PermissionResult(requestId, granted, canAskAgain))
    }

    fun cancel() {
        model.activeRequest?.let { dispatch(VoiceSessionEvent.Cancel(it.id)) }
    }

    fun background() {
        model.activeRequest?.let { dispatch(VoiceSessionEvent.Backgrounded(it.id)) }
    }

    fun checkDeadline() {
        model.activeRequest?.let { dispatch(VoiceSessionEvent.DeadlineElapsed(it.id, nowMs())) }
    }

    fun reset() {
        dispatch(VoiceSessionEvent.Reset)
    }

    override fun onCaptureStarted(requestId: VoiceSessionRequestId) {
        dispatch(VoiceSessionEvent.CaptureStarted(requestId))
    }

    override fun onSpeechEnded(requestId: VoiceSessionRequestId) {
        dispatch(VoiceSessionEvent.SpeechEnded(requestId))
    }

    override fun onResults(requestId: VoiceSessionRequestId, candidates: VoiceTranscriptCandidates) {
        dispatch(VoiceSessionEvent.Results(requestId, candidates))
    }

    override fun onFailure(requestId: VoiceSessionRequestId, failure: VoiceRecognizerFailure) {
        dispatch(VoiceSessionEvent.Failure(requestId, failure))
    }

    private fun dispatch(event: VoiceSessionEvent) {
        val reduction = synchronized(lock) {
            VoiceSessionReducer.reduce(current, event).also { current = it.model }
        }
        if (reduction.status == VoiceReductionStatus.APPLIED) onModelChanged(reduction.model)
        reduction.effects.forEach(::execute)
    }

    private fun execute(effect: VoiceSessionEffect) {
        when (effect) {
            is VoiceSessionEffect.RequestMicrophonePermission -> onPermissionRequested(effect.requestId)
            is VoiceSessionEffect.CheckOnDeviceSupport -> port.checkOnDeviceSupport(effect.requestId) { requestId, support ->
                dispatch(VoiceSessionEvent.SupportResult(requestId, support))
            }
            is VoiceSessionEffect.StartOnDeviceCapture -> port.startOnDeviceCapture(effect.request, this)
            is VoiceSessionEffect.StartSystemCapture -> port.startSystemCapture(effect.request, this)
            is VoiceSessionEffect.ReleaseRecognizer -> port.release(effect.requestId)
            is VoiceSessionEffect.DeliverTranscript -> onTranscript(effect.transcript)
        }
    }
}
