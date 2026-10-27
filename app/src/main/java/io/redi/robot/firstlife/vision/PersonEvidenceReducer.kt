package io.redi.robot.firstlife.vision

/** Deterministic, bounded reducer for uncertain coarse person direction. */
class PersonEvidenceReducer {
    private data class Candidate(val direction: PersonDirection, val observedAt: Long)

    private var request: CameraSessionRequest? = null
    private var lastSequence = 0L
    private var lastObservedAt = -1L
    private val window = ArrayDeque<Candidate>(REQUIRED_CONSECUTIVE)

    fun beginSession(request: CameraSessionRequest) {
        this.request = request
        lastSequence = 0
        lastObservedAt = -1
        window.clear()
    }

    fun closeSession() {
        request = null
        lastSequence = 0
        lastObservedAt = -1
        window.clear()
    }

    fun reduce(observation: PersonDetectionObservation, nowElapsedNanos: Long): PersonDirectionEvidence? {
        val active = request ?: return null
        if (observation.requestId != active.id || observation.generation != active.generation) return null
        if (observation.sequence <= lastSequence || observation.observedAtElapsedNanos <= lastObservedAt) {
            window.clear()
            return unknown(observation, PersonEvidenceReason.OUT_OF_ORDER)
        }
        lastSequence = observation.sequence
        lastObservedAt = observation.observedAtElapsedNanos
        if (nowElapsedNanos - observation.observedAtElapsedNanos > MAX_OBSERVATION_AGE_NANOS) {
            window.clear()
            return unknown(observation, PersonEvidenceReason.STALE)
        }

        val qualifying = observation.detections.filter { it.confidence >= CONFIDENCE_THRESHOLD }
        if (qualifying.size > 1) {
            window.clear()
            return unknown(observation, PersonEvidenceReason.MULTIPLE_PEOPLE)
        }
        if (qualifying.isEmpty()) {
            window.clear()
            val reason = if (observation.detections.isEmpty()) PersonEvidenceReason.NO_PERSON else PersonEvidenceReason.LOW_CONFIDENCE
            return unknown(observation, reason, confidence = observation.detections.maxOfOrNull { it.confidence })
        }

        val detection = qualifying.single()
        if (!detection.boundingBox.isValid()) {
            window.clear()
            return unknown(observation, PersonEvidenceReason.INVALID_GEOMETRY, confidence = detection.confidence)
        }
        val rawDirection = directionFor(detection.boundingBox.centerX)
        if (rawDirection == PersonDirection.UNKNOWN) {
            window.clear()
            return unknown(
                observation,
                PersonEvidenceReason.WARMING_UP,
                detection.boundingBox,
                detection.confidence,
            )
        }

        if (window.lastOrNull()?.direction != rawDirection ||
            window.firstOrNull()?.let { observation.observedAtElapsedNanos - it.observedAt > MAX_WINDOW_NANOS } == true
        ) window.clear()
        window.addLast(Candidate(rawDirection, observation.observedAtElapsedNanos))
        while (window.size > REQUIRED_CONSECUTIVE) window.removeFirst()

        val stable = window.size == REQUIRED_CONSECUTIVE &&
            observation.observedAtElapsedNanos - window.first().observedAt <= MAX_WINDOW_NANOS
        return evidence(
            observation = observation,
            direction = if (stable) rawDirection else PersonDirection.UNKNOWN,
            reason = if (stable) PersonEvidenceReason.STABLE_PERSON else PersonEvidenceReason.WARMING_UP,
            box = detection.boundingBox,
            confidence = detection.confidence,
        )
    }

    fun expire(current: PersonDirectionEvidence, nowElapsedNanos: Long): PersonDirectionEvidence? {
        val active = request ?: return null
        if (current.requestId != active.id || current.generation != active.generation) return null
        if (current.isFreshAt(nowElapsedNanos)) return current
        window.clear()
        return current.copy(
            direction = PersonDirection.UNKNOWN,
            reason = PersonEvidenceReason.STALE,
            boundingBox = null,
            horizontalCenterOffset = null,
            confidence = null,
        )
    }

    private fun unknown(
        observation: PersonDetectionObservation,
        reason: PersonEvidenceReason,
        box: NormalizedBoundingBox? = null,
        confidence: Float? = null,
    ) = evidence(observation, PersonDirection.UNKNOWN, reason, box, confidence)

    private fun evidence(
        observation: PersonDetectionObservation,
        direction: PersonDirection,
        reason: PersonEvidenceReason,
        box: NormalizedBoundingBox?,
        confidence: Float?,
    ) = PersonDirectionEvidence(
        requestId = observation.requestId,
        generation = observation.generation,
        sequence = observation.sequence,
        observedAtElapsedNanos = observation.observedAtElapsedNanos,
        expiresAtElapsedNanos = observation.observedAtElapsedNanos + MAX_OBSERVATION_AGE_NANOS,
        direction = direction,
        reason = reason,
        boundingBox = box,
        horizontalCenterOffset = box?.centerX?.let { (it - 0.5f) * 2f },
        confidence = confidence,
    )

    companion object {
        const val CONFIDENCE_THRESHOLD = 0.60f
        const val REQUIRED_CONSECUTIVE = 3
        const val MAX_OBSERVATION_AGE_NANOS = 250_000_000L
        const val MAX_WINDOW_NANOS = 500_000_000L

        fun directionFor(centerX: Float): PersonDirection = when {
            !centerX.isFinite() || centerX !in 0f..1f -> PersonDirection.UNKNOWN
            centerX < 0.38f -> PersonDirection.LEFT
            centerX in 0.42f..0.58f -> PersonDirection.CENTER
            centerX > 0.62f -> PersonDirection.RIGHT
            else -> PersonDirection.UNKNOWN
        }
    }
}
