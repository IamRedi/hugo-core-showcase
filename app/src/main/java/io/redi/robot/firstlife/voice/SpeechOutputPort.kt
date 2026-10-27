package io.redi.robot.firstlife.voice

interface SpeechOutputListener {
    fun onStarted(requestId: SpeechOutputRequestId)
    fun onCompleted(requestId: SpeechOutputRequestId)
    fun onFailed(requestId: SpeechOutputRequestId, reason: SpeechOutputBlockedReason)
}

interface LocalSpeechOutputPort {
    fun checkSupport(requestId: SpeechOutputRequestId, callback: (SpeechOutputRequestId, LocalTtsSupport) -> Unit)
    fun speak(request: SpeechOutputRequest, listener: SpeechOutputListener)
    fun release(requestId: SpeechOutputRequestId)
}

class SpeechOutputCoordinator(
    private val port: LocalSpeechOutputPort,
    private val requestIdFactory: () -> SpeechOutputRequestId,
    private val onModelChanged: (SpeechOutputModel) -> Unit,
) : SpeechOutputListener {
    var model: SpeechOutputModel = SpeechOutputModel.IDLE
        private set
    private var ordinal = 0L

    fun speak(kind: SpeechOutputKind, text: LocalSpeechText): Boolean {
        if (model.phase in SpeechOutputModel.ACTIVE_PHASES) return false
        val request = SpeechOutputRequest(requestIdFactory(), ++ordinal, kind, text = text)
        update(SpeechOutputModel(SpeechOutputPhase.CHECKING_SUPPORT, activeRequest = request))
        port.checkSupport(request.id, ::onSupport)
        return true
    }

    fun cancel(background: Boolean = false) {
        val request = model.activeRequest ?: return
        port.release(request.id)
        block(if (background) SpeechOutputBlockedReason.BACKGROUNDED else SpeechOutputBlockedReason.CANCELLED)
    }

    fun interruptForMicrophone(): Boolean {
        if (model.phase !in SpeechOutputModel.ACTIVE_PHASES) return true
        val request = model.activeRequest ?: return true
        port.release(request.id)
        block(SpeechOutputBlockedReason.INTERRUPTED)
        return false
    }

    private fun onSupport(requestId: SpeechOutputRequestId, support: LocalTtsSupport) {
        val request = model.activeRequest ?: return
        if (request.id != requestId || model.phase != SpeechOutputPhase.CHECKING_SUPPORT) return
        val blocked = when (support) {
            LocalTtsSupport.SUPPORTED -> null
            LocalTtsSupport.ENGINE_UNAVAILABLE -> SpeechOutputBlockedReason.ENGINE_UNAVAILABLE
            LocalTtsSupport.ALBANIAN_LANGUAGE_UNAVAILABLE -> SpeechOutputBlockedReason.ALBANIAN_LANGUAGE_UNAVAILABLE
            LocalTtsSupport.VOICE_DATA_MISSING -> SpeechOutputBlockedReason.VOICE_DATA_MISSING
        }
        if (blocked != null) {
            port.release(request.id)
            block(blocked)
        } else {
            update(SpeechOutputModel(SpeechOutputPhase.QUEUED, activeRequest = request))
            port.speak(request, this)
        }
    }

    override fun onStarted(requestId: SpeechOutputRequestId) {
        val request = current(requestId, SpeechOutputPhase.QUEUED) ?: return
        update(SpeechOutputModel(SpeechOutputPhase.SPEAKING, activeRequest = request))
    }

    override fun onCompleted(requestId: SpeechOutputRequestId) {
        val request = current(requestId, SpeechOutputPhase.SPEAKING) ?: return
        port.release(request.id)
        update(
            SpeechOutputModel(
                SpeechOutputPhase.COMPLETED,
                evidence = SpeechOutputEvidence(request.kind, request.localeTag, localVoice = true),
            ),
        )
    }

    override fun onFailed(requestId: SpeechOutputRequestId, reason: SpeechOutputBlockedReason) {
        val request = model.activeRequest ?: return
        if (request.id != requestId || model.phase !in SpeechOutputModel.ACTIVE_PHASES) return
        port.release(request.id)
        block(reason)
    }

    private fun current(id: SpeechOutputRequestId, expected: SpeechOutputPhase): SpeechOutputRequest? =
        model.activeRequest?.takeIf { it.id == id && model.phase == expected }

    private fun block(reason: SpeechOutputBlockedReason) =
        update(SpeechOutputModel(SpeechOutputPhase.BLOCKED, blockedReason = reason))

    private fun update(next: SpeechOutputModel) {
        model = next
        onModelChanged(next)
    }
}
