package io.redi.robot.feature.hihugo.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.redi.robot.R
import io.redi.robot.feature.hihugo.presentation.HiHugoAccent
import io.redi.robot.feature.hihugo.presentation.HiHugoBodyStage
import io.redi.robot.feature.hihugo.presentation.HiHugoFaceState
import io.redi.robot.feature.hihugo.presentation.HiHugoMotion
import io.redi.robot.feature.hihugo.presentation.HiHugoPresentationModel
import kotlinx.coroutines.delay

internal object HugoFaceSvgAsset {
    const val PATH = "hugo/hugo-face.svg"
    const val SHA256 = "7d1acd1d43bb79c5212ba28f158f544939530a036f39d562006482be08046684"
    const val RASTER_SHA256 = "c65e0601a8b3e5d143060a88086fa01f4e1a8370ecd29f0a3383b963a1c2f5a2"
    const val BOOT_RASTER_SHA256 = "b33a134e09657c6583a48f904807ce6b1eab029c3020f23ab1294fc0e9d59080"
    const val BASE_RASTER_SHA256 = "293f26d39f15682e37898c2c53dfc24574efcca0be68e195644f8a300fd33b33"
    const val LEFT_EYE_RASTER_SHA256 = "1cd38511e15a0a3e1ff6e01cc7c6e67d519a430d7f1d62dcc6058b3191f84c2b"
    const val RIGHT_EYE_RASTER_SHA256 = "0e17058d21316903b2f8d317247b37a5a751ea62953240d4db5506bd67c3d168"
    const val LEFT_SOCKET_RASTER_SHA256 = "a5cbad7b54ac109e541a630ac6cc00d9c930a77eda70649246b6cda4029751c3"
    const val LEFT_GLOBE_RASTER_SHA256 = "f719c63bca39639e1655cb51004311fb06c783f1a1c7c83f84d7b6aab9433980"
    const val RIGHT_SOCKET_RASTER_SHA256 = "14aebc4f9d89ef94fab73f992ebd06f2b02aa5069d3159d0c3456404e11caad3"
    const val RIGHT_GLOBE_RASTER_SHA256 = "1b43b82fde53d50885d4c84850d85e6ead112781f7be7daf6c50c3636cb79f6a"
    const val LEFT_READY_RASTER_SHA256 = "9a0c3fd90fe6bdc4d9e9cb14b29040d51c5784cd44c373faf1aaac3eb39ec463"
    const val RIGHT_READY_RASTER_SHA256 = "c7d8b349274e29957317b3b1ce1ceb1aff4d03dff14c00c534884730d4e0e14a"
    const val MOUTH_RASTER_SHA256 = "2e4a1cddff5790841c73a789aa3e5fffe8589901050acceed3f1d7225a1e3588"
}

/** Local derivative of the retained SVG master plus state-driven Compose overlays. */
@Composable
internal fun HiHugoFace(
    model: HiHugoPresentationModel,
    bodyStage: HiHugoBodyStage,
    animationActive: Boolean,
) {
    val spec = model.visualSpec
    var phase by remember(model.faceState, bodyStage) { mutableStateOf(false) }
    LaunchedEffect(model.faceState, bodyStage, animationActive) {
        phase = false
        if (!animationActive) return@LaunchedEffect
        while (true) {
            delay(spec.motionIntervalMillis.coerceAtLeast(420L))
            phase = !phase
            if (spec.motion == HiHugoMotion.BLINK) {
                delay(135)
                phase = false
            }
        }
    }
    val motion by animateFloatAsState(
        targetValue = if (phase && animationActive) 1f else 0f,
        animationSpec = tween(if (spec.motion == HiHugoMotion.BLINK) 75 else 520),
        label = "HUGO SVG state motion",
    )
    val bootVisibility by animateFloatAsState(
        targetValue = if (model.faceState == HiHugoFaceState.BOOTING && animationActive) .68f + motion * .32f else 1f,
        animationSpec = tween(520),
        label = "HUGO SVG boot reveal",
    )

    Box(
        Modifier.fillMaxWidth().height(292.dp)
            .semantics {
                contentDescription = "HUGO face"
                stateDescription = "${spec.label}; body ${bodyStage.name.lowercase()}; animation ${if (animationActive) "active" else "paused"}"
            }
            .testTag("hihugo-face"),
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = bootVisibility
                val breath = if (animationActive) .992f + motion * .008f else 1f
                scaleX = breath
                scaleY = breath
            },
        ) {
            Image(
                painter = painterResource(R.drawable.hugo_face_base),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            OriginalHugoEye(
                fullDrawableId = R.drawable.hugo_left_eye,
                socketDrawableId = R.drawable.hugo_left_eye_socket,
                globeDrawableId = R.drawable.hugo_left_eye_globe,
                readyDrawableId = R.drawable.hugo_left_eye_ready,
                pivot = TransformOrigin(.29f, .625f),
                state = model.faceState,
                motionKind = spec.motion,
                motion = if (animationActive) motion else 0f,
                leftEye = true,
            )
            OriginalHugoEye(
                fullDrawableId = R.drawable.hugo_right_eye,
                socketDrawableId = R.drawable.hugo_right_eye_socket,
                globeDrawableId = R.drawable.hugo_right_eye_globe,
                readyDrawableId = R.drawable.hugo_right_eye_ready,
                pivot = TransformOrigin(.72f, .625f),
                state = model.faceState,
                motionKind = spec.motion,
                motion = if (animationActive) motion else 0f,
                leftEye = false,
            )
            OriginalHugoMouth(
                motionKind = spec.motion,
                motion = if (animationActive) motion else 0f,
            )
        }
        HugoStateOverlay(
            state = model.faceState,
            motionKind = spec.motion,
            accent = spec.accent.toColor(),
            bodyStage = bodyStage,
            motion = motion,
            animationActive = animationActive,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Keeps the original socket fixed while its exact globe pixels move inside it. */
@Composable
private fun OriginalHugoEye(
    fullDrawableId: Int,
    socketDrawableId: Int,
    globeDrawableId: Int,
    readyDrawableId: Int,
    pivot: TransformOrigin,
    state: HiHugoFaceState,
    motionKind: HiHugoMotion,
    motion: Float,
    leftEye: Boolean,
) {
    val gazeMovesInsideSocket = motionKind == HiHugoMotion.THINK || motionKind == HiHugoMotion.OBSERVE
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            transformOrigin = pivot
            scaleY = when {
                motionKind == HiHugoMotion.BOOT -> .10f
                motionKind == HiHugoMotion.BLINK -> 1f - motion * .93f
                motionKind == HiHugoMotion.ATTEND -> 1.02f + motion * .035f
                state == HiHugoFaceState.BLOCKED -> .72f
                state == HiHugoFaceState.ERROR -> .82f
                else -> 1f
            }
            rotationZ = if (motionKind == HiHugoMotion.BOOT) {
                if (leftEye) 3.2f else -3.2f
            } else {
                0f
            }
        },
    ) {
        when {
            state == HiHugoFaceState.BODY_READY -> OriginalHugoLayer(readyDrawableId)
            gazeMovesInsideSocket -> {
                OriginalHugoLayer(socketDrawableId)
                Image(
                    painter = painterResource(globeDrawableId),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        val unit = minOf(size.width, size.height)
                        transformOrigin = pivot
                        translationX = when (motionKind) {
                            HiHugoMotion.THINK -> (motion * 2f - 1f) * unit * .006f
                            HiHugoMotion.OBSERVE -> (motion * 2f - 1f) * unit * .012f
                            else -> 0f
                        }
                        translationY = when (motionKind) {
                            HiHugoMotion.THINK -> -unit * .007f
                            HiHugoMotion.OBSERVE -> unit * .002f
                            else -> 0f
                        }
                        rotationZ = when (motionKind) {
                            HiHugoMotion.THINK -> (motion * 2f - 1f) * .55f
                            HiHugoMotion.OBSERVE -> (motion * 2f - 1f) * .30f
                            else -> 0f
                        }
                    },
                )
            }
            else -> OriginalHugoLayer(fullDrawableId)
        }
    }
}

@Composable
private fun OriginalHugoLayer(drawableId: Int) {
    Image(
        painter = painterResource(drawableId),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun OriginalHugoMouth(motionKind: HiHugoMotion, motion: Float) {
    Image(
        painter = painterResource(R.drawable.hugo_mouth),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            transformOrigin = TransformOrigin(.5f, .879f)
            if (motionKind == HiHugoMotion.SPEAK) {
                scaleX = 1f + motion * .06f
                scaleY = 1f + motion * .12f
            }
        },
    )
}

@Composable
private fun HugoStateOverlay(
    state: HiHugoFaceState,
    motionKind: HiHugoMotion,
    accent: Color,
    bodyStage: HiHugoBodyStage,
    motion: Float,
    animationActive: Boolean,
    modifier: Modifier,
) {
    Canvas(modifier.testTag("hihugo-face-animation-layer")) {
        val unit = minOf(size.width, size.height)
        val activeMotion = if (animationActive) motion else 0f

        when (motionKind) {
            HiHugoMotion.BOOT -> drawBootOverlay(accent, activeMotion)
            HiHugoMotion.BLINK -> Unit
            HiHugoMotion.ATTEND -> drawListeningOverlay(accent, activeMotion, unit)
            HiHugoMotion.THINK -> drawThinkingOverlay(accent, activeMotion, unit)
            HiHugoMotion.SPEAK -> Unit
            HiHugoMotion.OBSERVE -> drawObservingOverlay(accent, activeMotion, unit)
            HiHugoMotion.READY -> Unit
            HiHugoMotion.NONE -> Unit
        }
        when (bodyStage) {
            HiHugoBodyStage.CONNECTING -> drawConnectionOverlay(Color(0xFF5DE7FF), activeMotion, unit)
            HiHugoBodyStage.AVAILABLE,
            HiHugoBodyStage.SELECTED,
            -> drawBodyReadyMarkers(Color(0xFF75E6A6), activeMotion, unit)
            HiHugoBodyStage.BLOCKED -> drawBodyBlockedMarkers(unit)
            HiHugoBodyStage.NO_BODY -> Unit
        }
        if (state == HiHugoFaceState.ERROR) drawErrorOverlay(unit)
        if (state == HiHugoFaceState.BLOCKED) drawBlockedOverlay(unit)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBootOverlay(accent: Color, motion: Float) {
    val y = size.height * (.15f + motion * .70f)
    repeat(5) { index ->
        val start = .10f + index * .17f
        val width = .08f + (index % 3) * .035f
        drawLine(
            accent.copy(alpha = .15f + index * .035f),
            Offset(size.width * start, y + index * size.height * .012f),
            Offset(size.width * (start + width), y + index * size.height * .012f),
            size.height * .006f,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawListeningOverlay(accent: Color, motion: Float, unit: Float) {
    listOf(.135f to true, .865f to false).forEach { (x, leftSide) ->
        repeat(3) { index ->
            val radius = unit * (.035f + index * .027f + motion * .006f)
            val center = Offset(size.width * x, size.height * .625f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val start = if (leftSide) 144f else -36f
            drawArc(
                accent.copy(alpha = .20f + index * .08f + motion * .10f),
                start,
                72f,
                false,
                topLeft,
                Size(radius * 2f, radius * 2f),
                style = Stroke(unit * .006f),
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThinkingOverlay(accent: Color, motion: Float, unit: Float) {
    // Exact SVG-derived globe pixels move inside OriginalHugoEye; this is only a subtle thought cue.
    val y = size.height * (.51f + motion * .015f)
    drawLine(
        accent.copy(alpha = .16f + motion * .10f),
        Offset(size.width * .43f, y),
        Offset(size.width * .58f, y),
        unit * .004f,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawObservingOverlay(accent: Color, motion: Float, unit: Float) {
    val y = size.height * (.30f + motion * .43f)
    drawLine(accent.copy(alpha = .22f), Offset(size.width * .17f, y), Offset(size.width * .83f, y), unit * .005f)
    listOf(.29f, .72f).forEach { x ->
        val eye = Offset(size.width * x, size.height * .625f)
        val bracket = unit * .105f
        drawLine(accent.copy(alpha = .28f), Offset(eye.x - bracket, eye.y - bracket * .62f), Offset(eye.x - bracket * .72f, eye.y - bracket * .62f), unit * .005f)
        drawLine(accent.copy(alpha = .28f), Offset(eye.x + bracket * .72f, eye.y + bracket * .62f), Offset(eye.x + bracket, eye.y + bracket * .62f), unit * .005f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawConnectionOverlay(accent: Color, motion: Float, unit: Float) {
    val inset = unit * (.02f + motion * .045f)
    val strokes = listOf(
        Offset(size.width * .05f + inset, size.height * .30f) to Offset(size.width * .18f + inset, size.height * .38f),
        Offset(size.width * .95f - inset, size.height * .30f) to Offset(size.width * .82f - inset, size.height * .38f),
        Offset(size.width * .07f + inset, size.height * .77f) to Offset(size.width * .20f + inset, size.height * .70f),
        Offset(size.width * .93f - inset, size.height * .77f) to Offset(size.width * .80f - inset, size.height * .70f),
    )
    strokes.forEachIndexed { index, (start, end) ->
        drawLine(accent.copy(alpha = .20f + index * .035f), start, end, unit * .007f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBodyReadyMarkers(accent: Color, motion: Float, unit: Float) {
    val y = size.height * .945f
    val spread = unit * (.10f + motion * .018f)
    drawLine(accent.copy(alpha = .42f), Offset(size.width / 2f - spread, y), Offset(size.width / 2f - unit * .025f, y), unit * .007f)
    drawLine(accent.copy(alpha = .42f), Offset(size.width / 2f + unit * .025f, y), Offset(size.width / 2f + spread, y), unit * .007f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBodyBlockedMarkers(unit: Float) {
    val color = Color(0xFFFFC66D).copy(alpha = .50f)
    drawLine(color, Offset(size.width * .10f, size.height * .48f), Offset(size.width * .17f, size.height * .48f), unit * .006f)
    drawLine(color, Offset(size.width * .83f, size.height * .48f), Offset(size.width * .90f, size.height * .48f), unit * .006f)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawErrorOverlay(unit: Float) {
    repeat(5) { index ->
        val y = size.height * (.28f + index * .115f)
        val offset = if (index % 2 == 0) unit * .035f else -unit * .025f
        drawLine(Color(0xFFFF1738).copy(alpha = .30f), Offset(size.width * .19f + offset, y), Offset(size.width * .81f + offset, y), unit * .006f)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBlockedOverlay(unit: Float) {
    drawLine(Color(0xFFFFC66D).copy(alpha = .70f), Offset(size.width * .43f, size.height * .885f), Offset(size.width * .60f, size.height * .885f), unit * .008f)
}

private fun HiHugoAccent.toColor(): Color = when (this) {
    HiHugoAccent.CYAN -> Color(0xFF5DE7FF)
    HiHugoAccent.VIOLET -> Color(0xFFB99BFF)
    HiHugoAccent.BLUE -> Color(0xFF7BB7FF)
    HiHugoAccent.GREEN -> Color(0xFF75E6A6)
    HiHugoAccent.AMBER -> Color(0xFFFFC66D)
    HiHugoAccent.RED -> Color(0xFFFF7F8D)
}
