package io.redi.robot.platform.vision.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import io.redi.robot.firstlife.vision.CameraFrameMetadata
import io.redi.robot.firstlife.vision.DetectorBoundingBoxPixels
import io.redi.robot.firstlife.vision.DetectorCandidate
import io.redi.robot.firstlife.vision.PersonDetectionObservation
import io.redi.robot.firstlife.vision.PersonDetectorResultAdapter

/** Android/model boundary. Pixels and MediaPipe types never leave this package. */
internal class AndroidMediaPipePersonDetector(context: Context) : AutoCloseable {
    private data class InFlight(
        val metadata: CameraFrameMetadata,
        val image: MPImage,
        val bitmap: Bitmap,
        val startedAtNanos: Long,
        val complete: () -> Unit,
        val result: (PersonDetectionObservation, Long) -> Unit,
        val failure: () -> Unit,
    )

    private val detector = ObjectDetector.createFromOptions(
        context.applicationContext,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET_PATH)
                    .build(),
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setMaxResults(PersonDetectionObservation.MAX_RESULTS)
            .setScoreThreshold(SCORE_THRESHOLD)
            .setCategoryAllowlist(listOf(PERSON_CATEGORY))
            .setResultListener(::onResult)
            .setErrorListener { onError() }
            .build(),
    )

    private var inFlight: InFlight? = null
    private var closed = false

    fun detect(
        imageProxy: ImageProxy,
        metadata: CameraFrameMetadata,
        onResult: (PersonDetectionObservation, Long) -> Unit,
        onFailure: () -> Unit,
        onComplete: () -> Unit,
    ) {
        val bitmap = try {
            copyAndRotate(imageProxy)
        } catch (_: IllegalStateException) {
            // closeCurrent() can race with an analyzer callback that CameraX already dispatched.
            // Once the detector is closed, an already-closed ImageProxy is lifecycle cleanup only.
            val staleCleanup = synchronized(this) { closed }
            try {
                if (!staleCleanup) onFailure()
            } finally {
                onComplete()
            }
            return
        }
        val mpImage = BitmapImageBuilder(bitmap).build()
        val startedAt = SystemClock.elapsedRealtimeNanos()
        val frame = InFlight(metadata, mpImage, bitmap, startedAt, onComplete, onResult, onFailure)
        val accepted = synchronized(this) {
            if (closed) {
                false
            } else {
                check(inFlight == null) { "Only one detector input may be in flight" }
                inFlight = frame
                true
            }
        }
        if (!accepted) {
            // CameraX may already have dispatched an analyzer callback while close is
            // clearing the analyzer. Treat that stale frame as lifecycle cleanup,
            // never as a detector failure and never as a new session observation.
            frame.release()
            return
        }
        try {
            detector.detectAsync(mpImage, metadata.observedAtElapsedNanos / NANOS_PER_MILLISECOND)
        } catch (_: RuntimeException) {
            finishFailure(frame)
        }
    }

    override fun close() {
        val pending = synchronized(this) {
            if (closed) return
            closed = true
            inFlight.also { inFlight = null }
        }
        runCatching { detector.close() }
        pending?.release()
    }

    private fun onResult(result: ObjectDetectorResult, input: MPImage) {
        val frame = takeCurrent() ?: return
        val completedAt = SystemClock.elapsedRealtimeNanos().coerceAtLeast(frame.metadata.observedAtElapsedNanos)
        val candidates = result.detections().mapNotNull { detection ->
            val category = detection.categories().firstOrNull()
                ?: return@mapNotNull null
            val bounds = detection.boundingBox()
            DetectorCandidate(
                category = category.categoryName(),
                confidence = category.score().coerceIn(0f, 1f),
                boundingBox = DetectorBoundingBoxPixels(
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom,
                ),
            )
        }
        val detections = PersonDetectorResultAdapter.adapt(candidates, input.width, input.height)
        val inferenceLatencyMs = (completedAt - frame.startedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLISECOND
        val observation = PersonDetectionObservation(
            requestId = frame.metadata.requestId,
            generation = frame.metadata.generation,
            sequence = frame.metadata.sequence,
            observedAtElapsedNanos = frame.metadata.observedAtElapsedNanos,
            completedAtElapsedNanos = completedAt,
            detectorLatencyMs = inferenceLatencyMs,
            detections = detections,
        )
        try {
            frame.result(observation, inferenceLatencyMs)
        } finally {
            frame.release()
        }
    }

    private fun onError() {
        val frame = synchronized(this) { inFlight.also { inFlight = null } } ?: return
        try {
            frame.failure()
        } finally {
            frame.release()
        }
    }

    private fun finishFailure(frame: InFlight) {
        synchronized(this) { if (inFlight === frame) inFlight = null }
        try {
            frame.failure()
        } finally {
            frame.release()
        }
    }

    private fun takeCurrent(): InFlight? = synchronized(this) { inFlight.also { inFlight = null } }

    private fun InFlight.release() {
        runCatching { image.close() }
        if (!bitmap.isRecycled) bitmap.recycle()
        complete()
    }

    private fun copyAndRotate(image: ImageProxy): Bitmap {
        val source = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            image.planes[0].buffer.rewind()
            source.copyPixelsFromBuffer(image.planes[0].buffer)
            val rotation = image.imageInfo.rotationDegrees
            if (rotation == 0) return source
            return Bitmap.createBitmap(
                source,
                0,
                0,
                source.width,
                source.height,
                Matrix().apply { postRotate(rotation.toFloat()) },
                true,
            ).also { source.recycle() }
        } catch (error: Throwable) {
            if (!source.isRecycled) source.recycle()
            throw error
        }
    }

    companion object {
        const val MODEL_ASSET_PATH = "models/efficientdet_lite0_int8_v1.tflite"
        const val PERSON_CATEGORY = PersonDetectorResultAdapter.PERSON_CATEGORY
        const val SCORE_THRESHOLD = 0.60f
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
