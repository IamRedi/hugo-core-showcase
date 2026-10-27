package io.redi.robot.firstlife.vision

data class DetectorBoundingBoxPixels(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class DetectorCandidate(
    val category: String,
    val confidence: Float,
    val boundingBox: DetectorBoundingBoxPixels,
)

/** Converts bounded model output into project-owned, non-identifying detections. */
object PersonDetectorResultAdapter {
    const val PERSON_CATEGORY = "person"

    fun adapt(candidates: List<DetectorCandidate>, frameWidth: Int, frameHeight: Int): List<PersonDetection> {
        if (frameWidth <= 0 || frameHeight <= 0) return emptyList()
        return candidates.asSequence()
            .filter { it.category == PERSON_CATEGORY }
            .filter { it.confidence.isFinite() && it.confidence in 0f..1f }
            .map {
                val left = it.boundingBox.left.coerceIn(0f, frameWidth.toFloat())
                val top = it.boundingBox.top.coerceIn(0f, frameHeight.toFloat())
                val right = it.boundingBox.right.coerceIn(0f, frameWidth.toFloat())
                val bottom = it.boundingBox.bottom.coerceIn(0f, frameHeight.toFloat())
                PersonDetection(
                    confidence = it.confidence,
                    boundingBox = NormalizedBoundingBox(
                        left = left / frameWidth,
                        top = top / frameHeight,
                        right = right / frameWidth,
                        bottom = bottom / frameHeight,
                    ),
                )
            }
            .take(PersonDetectionObservation.MAX_RESULTS)
            .toList()
    }
}
