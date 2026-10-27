package io.redi.robot.firstlife.vision

enum class PersonDirection { LEFT, CENTER, RIGHT, UNKNOWN }

enum class PersonEvidenceReason {
    STABLE_PERSON,
    WARMING_UP,
    NO_PERSON,
    MULTIPLE_PEOPLE,
    LOW_CONFIDENCE,
    INVALID_GEOMETRY,
    STALE,
    OUT_OF_ORDER,
    DETECTOR_UNAVAILABLE,
}

data class NormalizedBoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f

    fun isValid(): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            left >= 0f && top >= 0f && right <= 1f && bottom <= 1f &&
            left < right && top < bottom
}

data class PersonDetection(
    val confidence: Float,
    val boundingBox: NormalizedBoundingBox,
) {
    init { require(confidence.isFinite() && confidence in 0f..1f) }
}

/** Project-owned output from a local detector. It deliberately contains no pixels or identity. */
data class PersonDetectionObservation(
    val requestId: CameraSessionRequestId,
    val generation: CameraGeneration,
    val sequence: Long,
    val observedAtElapsedNanos: Long,
    val completedAtElapsedNanos: Long,
    val detectorLatencyMs: Long,
    val detections: List<PersonDetection>,
) {
    init {
        require(sequence > 0)
        require(observedAtElapsedNanos >= 0)
        require(completedAtElapsedNanos >= observedAtElapsedNanos)
        require(detectorLatencyMs >= 0)
        require(detections.size <= MAX_RESULTS)
    }

    companion object { const val MAX_RESULTS = 2 }
}

data class PersonDirectionEvidence(
    val requestId: CameraSessionRequestId,
    val generation: CameraGeneration,
    val sequence: Long,
    val observedAtElapsedNanos: Long,
    val expiresAtElapsedNanos: Long,
    val direction: PersonDirection,
    val reason: PersonEvidenceReason,
    val boundingBox: NormalizedBoundingBox? = null,
    val horizontalCenterOffset: Float? = null,
    val confidence: Float? = null,
) {
    init {
        require(sequence > 0)
        require(expiresAtElapsedNanos >= observedAtElapsedNanos)
        require(horizontalCenterOffset == null || horizontalCenterOffset.isFinite() && horizontalCenterOffset in -1f..1f)
        require(confidence == null || confidence.isFinite() && confidence in 0f..1f)
        require(direction == PersonDirection.UNKNOWN || reason == PersonEvidenceReason.STABLE_PERSON)
        require(reason != PersonEvidenceReason.STABLE_PERSON || boundingBox?.isValid() == true)
    }

    fun isFreshAt(nowElapsedNanos: Long): Boolean = nowElapsedNanos <= expiresAtElapsedNanos

    override fun toString(): String =
        "PersonDirectionEvidence(request=redacted, generation=${generation.value}, sequence=$sequence, " +
            "direction=$direction, reason=$reason, confidence=${confidence?.let { "%.2f".format(it) }})"
}

data class PersonDetectorTelemetry(
    val sequence: Long,
    val inferenceLatencyMs: Long,
) {
    init {
        require(sequence > 0)
        require(inferenceLatencyMs >= 0)
    }
}

data class PersonDetectorPerformance(
    val sampleCount: Int,
    val medianLatencyMs: Long,
    val p95LatencyMs: Long,
    val maxLatencyMs: Long,
) {
    init {
        require(sampleCount in 1..PersonDetectorLatencyWindow.MAX_SAMPLES)
        require(medianLatencyMs >= 0 && p95LatencyMs >= medianLatencyMs && maxLatencyMs >= p95LatencyMs)
    }
}

class PersonDetectorLatencyWindow {
    private val values = ArrayDeque<Long>(MAX_SAMPLES)

    fun add(latencyMs: Long): PersonDetectorPerformance {
        require(latencyMs >= 0)
        if (values.size == MAX_SAMPLES) values.removeFirst()
        values.addLast(latencyMs)
        val sorted = values.sorted()
        return PersonDetectorPerformance(
            sampleCount = sorted.size,
            medianLatencyMs = sorted[(sorted.size - 1) / 2],
            p95LatencyMs = sorted[((sorted.size * 95 + 99) / 100 - 1).coerceAtLeast(0)],
            maxLatencyMs = sorted.last(),
        )
    }

    fun clear() = values.clear()

    companion object { const val MAX_SAMPLES = 120 }
}
