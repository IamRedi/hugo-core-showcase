package io.redi.robot.firstlife.vision

interface CameraSessionListener {
    fun onOpened(request: CameraSessionRequest)
    fun onFrame(metadata: CameraFrameMetadata)
    fun onPersonObservation(observation: PersonDetectionObservation)
    fun onFailure(request: CameraSessionRequest, reason: CameraBlockedReason)
}

interface CameraSessionPort {
    fun open(request: CameraSessionRequest, listener: CameraSessionListener)
    fun close(requestId: CameraSessionRequestId)
}

class CameraSessionCoordinator(
    private val port: CameraSessionPort,
    private val requestIdFactory: () -> CameraSessionRequestId,
    private val onPermissionRequested: (CameraSessionRequestId) -> Unit,
    private val onModelChanged: (CameraSessionModel) -> Unit,
    private val personReducer: PersonEvidenceReducer = PersonEvidenceReducer(),
    private val nowElapsedNanos: () -> Long = System::nanoTime,
) : CameraSessionListener {
    var model = CameraSessionModel.IDLE
        private set
    private var generation = 0L
    private val latencyWindow = PersonDetectorLatencyWindow()

    fun startExplicitSession(): Boolean {
        if (model.phase in CameraSessionModel.ACTIVE_PHASES) return false
        val request = CameraSessionRequest(requestIdFactory(), CameraGeneration(++generation))
        update(CameraSessionModel(CameraSessionPhase.AWAITING_PERMISSION, activeRequest = request))
        onPermissionRequested(request.id)
        return true
    }

    fun onPermissionResult(requestId: CameraSessionRequestId, granted: Boolean, canAskAgain: Boolean) {
        val request = current(requestId, CameraSessionPhase.AWAITING_PERMISSION) ?: return
        if (!granted) {
            block(if (canAskAgain) CameraBlockedReason.PERMISSION_DENIED else CameraBlockedReason.PERMISSION_PERMANENTLY_DENIED)
            return
        }
        update(CameraSessionModel(CameraSessionPhase.OPENING, activeRequest = request))
        port.open(request, this)
    }

    fun close(background: Boolean = false) {
        val request = model.activeRequest ?: return
        port.close(request.id)
        personReducer.closeSession()
        latencyWindow.clear()
        block(if (background) CameraBlockedReason.BACKGROUNDED else CameraBlockedReason.CANCELLED)
    }

    override fun onOpened(request: CameraSessionRequest) {
        val current = current(request.id, CameraSessionPhase.OPENING) ?: return
        if (current.generation != request.generation) return
        personReducer.beginSession(request)
        latencyWindow.clear()
        update(
            CameraSessionModel(
                CameraSessionPhase.OBSERVING,
                activeRequest = request,
                evidence = CameraSessionEvidence(
                    request.generation,
                    CameraLensFacing.FRONT,
                    visiblePreview = true,
                    previewMirrored = true,
                ),
            ),
        )
    }

    override fun onFrame(metadata: CameraFrameMetadata) {
        val request = current(metadata.requestId, CameraSessionPhase.OBSERVING) ?: return
        if (request.generation != metadata.generation) return
        val previous = model.lastFrameMetadata
        if (previous != null && metadata.sequence <= previous.sequence) return
        update(model.copy(lastFrameMetadata = metadata))
    }

    override fun onPersonObservation(observation: PersonDetectionObservation) {
        if (model.phase != CameraSessionPhase.OBSERVING) return
        val evidence = personReducer.reduce(observation, nowElapsedNanos()) ?: return
        update(
            model.copy(
                personEvidence = evidence,
                detectorTelemetry = PersonDetectorTelemetry(observation.sequence, observation.detectorLatencyMs),
                detectorPerformance = latencyWindow.add(observation.detectorLatencyMs),
            ),
        )
    }

    fun expirePersonEvidence() {
        val current = model.personEvidence ?: return
        val expired = personReducer.expire(current, nowElapsedNanos()) ?: return
        if (expired != current) update(model.copy(personEvidence = expired))
    }

    override fun onFailure(request: CameraSessionRequest, reason: CameraBlockedReason) {
        val current = model.activeRequest ?: return
        if (current != request || model.phase !in CameraSessionModel.ACTIVE_PHASES) return
        port.close(request.id)
        personReducer.closeSession()
        latencyWindow.clear()
        block(reason)
    }

    private fun current(id: CameraSessionRequestId, phase: CameraSessionPhase): CameraSessionRequest? =
        model.activeRequest?.takeIf { it.id == id && model.phase == phase }

    private fun block(reason: CameraBlockedReason) = update(CameraSessionModel(CameraSessionPhase.BLOCKED, blockedReason = reason))
    private fun update(next: CameraSessionModel) {
        model = next
        onModelChanged(next)
    }
}
