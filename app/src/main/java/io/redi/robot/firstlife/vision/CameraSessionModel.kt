package io.redi.robot.firstlife.vision

@JvmInline
value class CameraSessionRequestId(val value: String) {
    init { require(value.isNotBlank()) }
    override fun toString(): String = "CameraSessionRequestId(redacted)"
}

@JvmInline
value class CameraGeneration(val value: Long) {
    init { require(value > 0) }
}

enum class CameraSessionPhase { IDLE, AWAITING_PERMISSION, OPENING, OBSERVING, BLOCKED }
enum class CameraBlockedReason {
    PERMISSION_DENIED,
    PERMISSION_PERMANENTLY_DENIED,
    FRONT_CAMERA_UNAVAILABLE,
    BIND_FAILED,
    DETECTOR_UNAVAILABLE,
    CANCELLED,
    BACKGROUNDED,
}
enum class CameraLensFacing { FRONT }

data class CameraFrameMetadata(
    val requestId: CameraSessionRequestId,
    val generation: CameraGeneration,
    val sequence: Long,
    val observedAtElapsedNanos: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val lensFacing: CameraLensFacing = CameraLensFacing.FRONT,
    val mirrored: Boolean = false,
) {
    init {
        require(sequence > 0)
        require(observedAtElapsedNanos >= 0)
        require(width in 1..640 && height in 1..640)
        require(rotationDegrees in setOf(0, 90, 180, 270))
        require(!mirrored) { "First Life analysis evidence uses the non-mirrored body frame" }
    }

    override fun toString(): String =
        "CameraFrameMetadata(request=redacted, generation=${generation.value}, sequence=$sequence, " +
            "size=${width}x$height, rotationDegrees=$rotationDegrees, lensFacing=$lensFacing, mirrored=$mirrored)"
}

data class CameraSessionRequest(
    val id: CameraSessionRequestId,
    val generation: CameraGeneration,
)

data class CameraSessionEvidence(
    val generation: CameraGeneration,
    val lensFacing: CameraLensFacing,
    val visiblePreview: Boolean,
    val previewMirrored: Boolean,
)

data class CameraSessionModel(
    val phase: CameraSessionPhase,
    val activeRequest: CameraSessionRequest? = null,
    val blockedReason: CameraBlockedReason? = null,
    val evidence: CameraSessionEvidence? = null,
    val lastFrameMetadata: CameraFrameMetadata? = null,
    val personEvidence: PersonDirectionEvidence? = null,
    val detectorTelemetry: PersonDetectorTelemetry? = null,
    val detectorPerformance: PersonDetectorPerformance? = null,
) {
    init {
        require((phase == CameraSessionPhase.BLOCKED) == (blockedReason != null))
        require(activeRequest == null || phase in ACTIVE_PHASES)
        require(lastFrameMetadata == null || phase == CameraSessionPhase.OBSERVING)
        require(personEvidence == null || phase == CameraSessionPhase.OBSERVING)
        require(detectorTelemetry == null || phase == CameraSessionPhase.OBSERVING)
        require(detectorPerformance == null || phase == CameraSessionPhase.OBSERVING)
    }

    override fun toString(): String =
        "CameraSessionModel(phase=$phase, active=${activeRequest != null}, blocked=$blockedReason, " +
            "evidence=$evidence, frame=${lastFrameMetadata?.copy(requestId = CameraSessionRequestId("redacted"))}, " +
            "person=$personEvidence, detector=$detectorTelemetry, performance=$detectorPerformance)"

    companion object {
        val ACTIVE_PHASES = setOf(CameraSessionPhase.AWAITING_PERMISSION, CameraSessionPhase.OPENING, CameraSessionPhase.OBSERVING)
        val IDLE = CameraSessionModel(CameraSessionPhase.IDLE)
    }
}
