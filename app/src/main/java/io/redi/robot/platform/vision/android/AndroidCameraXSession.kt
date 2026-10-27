package io.redi.robot.platform.vision.android

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import io.redi.robot.firstlife.vision.CameraBlockedReason
import io.redi.robot.firstlife.vision.CameraFrameMetadata
import io.redi.robot.firstlife.vision.CameraLensFacing
import io.redi.robot.firstlife.vision.CameraSessionListener
import io.redi.robot.firstlife.vision.CameraSessionPort
import io.redi.robot.firstlife.vision.CameraSessionRequest
import io.redi.robot.firstlife.vision.CameraSessionRequestId
import io.redi.robot.firstlife.vision.LatestFrameGate
import io.redi.robot.firstlife.vision.PersonDetectionObservation
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class AndroidCameraXSession(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : CameraSessionPort {
    private data class AndroidFrame(val image: ImageProxy)

    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val providerFuture = ProcessCameraProvider.getInstance(appContext)
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var owner: CameraSessionRequest? = null
    private var listener: CameraSessionListener? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var frameGate: LatestFrameGate<AndroidFrame>? = null
    private var personDetector: AndroidMediaPipePersonDetector? = null
    private val sequence = AtomicLong(0)
    private var destroyed = false

    override fun open(request: CameraSessionRequest, listener: CameraSessionListener) {
        if (destroyed) {
            listener.onFailure(request, CameraBlockedReason.FRONT_CAMERA_UNAVAILABLE)
            return
        }
        closeCurrent()
        owner = request
        this.listener = listener
        sequence.set(0)
        bindWhenReady(request)
    }

    override fun close(requestId: CameraSessionRequestId) {
        if (owner?.id != requestId) return
        closeCurrent()
    }

    fun attachSurfaceProvider(provider: Preview.SurfaceProvider) {
        surfaceProvider = provider
        owner?.let(::bindWhenReady)
    }

    fun detachSurfaceProvider(provider: Preview.SurfaceProvider) {
        if (surfaceProvider !== provider) return
        surfaceProvider = null
        closeCurrent()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        closeCurrent()
        analysisExecutor.shutdownNow()
    }

    private fun bindWhenReady(request: CameraSessionRequest) {
        val surface = surfaceProvider ?: return
        providerFuture.addListener({
            if (destroyed || owner != request || surfaceProvider !== surface) return@addListener
            val provider = runCatching { providerFuture.get() }.getOrElse {
                listener?.onFailure(request, CameraBlockedReason.FRONT_CAMERA_UNAVAILABLE)
                return@addListener
            }
            val localPreview = Preview.Builder().build().also { it.setSurfaceProvider(surface) }
            val localAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            val localListener = listener ?: return@addListener
            val detector = runCatching { AndroidMediaPipePersonDetector(appContext) }.getOrElse {
                localAnalysis.clearAnalyzer()
                provider.unbind(localPreview, localAnalysis)
                if (owner == request) localListener.onFailure(request, CameraBlockedReason.DETECTOR_UNAVAILABLE)
                return@addListener
            }
            val gate = LatestFrameGate<AndroidFrame>(
                process = { frame, done ->
                    val image = frame.image
                    val metadata = CameraFrameMetadata(
                        requestId = request.id,
                        generation = request.generation,
                        sequence = sequence.incrementAndGet(),
                        observedAtElapsedNanos = image.imageInfo.timestamp.coerceAtLeast(0L),
                        width = image.width,
                        height = image.height,
                        rotationDegrees = image.imageInfo.rotationDegrees,
                        lensFacing = CameraLensFacing.FRONT,
                        // CameraX mirrors PreviewView for the front lens, while ImageAnalysis stays
                        // in its non-mirrored buffer coordinate space. BODY direction uses this space.
                        mirrored = false,
                    )
                    mainExecutor.execute { if (owner == request) localListener.onFrame(metadata) }
                    detector.detect(
                        imageProxy = image,
                        metadata = metadata,
                        onResult = { observation: PersonDetectionObservation, _ ->
                            mainExecutor.execute {
                                if (owner == request) localListener.onPersonObservation(observation)
                            }
                        },
                        onFailure = {
                            mainExecutor.execute {
                                if (owner == request) localListener.onFailure(request, CameraBlockedReason.DETECTOR_UNAVAILABLE)
                            }
                        },
                        onComplete = done,
                    )
                },
                release = { it.image.close() },
            )
            localAnalysis.setAnalyzer(analysisExecutor) { image -> gate.offer(AndroidFrame(image)) }
            try {
                provider.unbind(localPreview, localAnalysis)
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, localPreview, localAnalysis)
                if (owner != request) {
                    localAnalysis.clearAnalyzer()
                    gate.close()
                    detector.close()
                    provider.unbind(localPreview, localAnalysis)
                    return@addListener
                }
                preview = localPreview
                analysis = localAnalysis
                frameGate = gate
                personDetector = detector
                localListener.onOpened(request)
            } catch (_: Throwable) {
                localAnalysis.clearAnalyzer()
                gate.close()
                detector.close()
                provider.unbind(localPreview, localAnalysis)
                if (owner == request) localListener.onFailure(request, CameraBlockedReason.BIND_FAILED)
            }
        }, mainExecutor)
    }

    private fun closeCurrent() {
        val localPreview = preview
        val localAnalysis = analysis
        owner = null
        listener = null
        preview = null
        analysis = null
        localAnalysis?.clearAnalyzer()
        personDetector?.close()
        personDetector = null
        // Mark the detector closed before the gate releases an ImageProxy that may already have
        // entered detect(). That makes the close/read race stale cleanup instead of an app crash.
        frameGate?.close()
        frameGate = null
        if (providerFuture.isDone) {
            runCatching { providerFuture.get().unbind(*listOfNotNull(localPreview, localAnalysis).toTypedArray()) }
        }
    }
}

@Composable
fun AndroidFrontCameraPreview(
    session: AndroidCameraXSession,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                session.attachSurfaceProvider(surfaceProvider)
            }
        },
        onRelease = { view -> session.detachSurfaceProvider(view.surfaceProvider) },
    )
}
