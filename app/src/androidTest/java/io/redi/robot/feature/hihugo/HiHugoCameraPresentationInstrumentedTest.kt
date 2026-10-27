package io.redi.robot.feature.hihugo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.redi.robot.feature.hihugo.presentation.HiHugoFaceState
import io.redi.robot.feature.hihugo.presentation.HiHugoPresentationModel
import io.redi.robot.feature.hihugo.ui.HiHugoScreen
import io.redi.robot.firstlife.identity.HugoBootstrapRunReference
import io.redi.robot.firstlife.vision.CameraGeneration
import io.redi.robot.firstlife.vision.CameraSessionEvidence
import io.redi.robot.firstlife.vision.CameraSessionModel
import io.redi.robot.firstlife.vision.CameraSessionPhase
import io.redi.robot.firstlife.vision.CameraSessionRequest
import io.redi.robot.firstlife.vision.CameraSessionRequestId
import io.redi.robot.firstlife.vision.CameraFrameMetadata
import io.redi.robot.firstlife.vision.NormalizedBoundingBox
import io.redi.robot.firstlife.vision.PersonDetectorPerformance
import io.redi.robot.firstlife.vision.PersonDetectorTelemetry
import io.redi.robot.firstlife.vision.PersonDirection
import io.redi.robot.firstlife.vision.PersonDirectionEvidence
import io.redi.robot.firstlife.vision.PersonEvidenceReason
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HiHugoCameraPresentationInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun idleCameraRequiresExplicitOpenAndStatesPrivacyBoundary() {
        var opens = 0
        composeRule.setContent {
            HiHugoScreen(face(), onStartCameraSession = { opens++ }, onDeveloperStateRequested = {}, reducedMotion = true)
        }
        composeRule.onNodeWithTag("hihugo-open-camera").performScrollTo().performClick()
        composeRule.onNodeWithTag("hihugo-camera-privacy").assertExists()
        assertEquals(1, opens)
    }

    @Test
    fun observingShowsVisiblePreviewIndicatorAndClose() {
        var closes = 0
        val request = CameraSessionRequest(CameraSessionRequestId("ui-camera"), CameraGeneration(1))
        val model = CameraSessionModel(
            CameraSessionPhase.OBSERVING,
            activeRequest = request,
            evidence = CameraSessionEvidence(
                request.generation,
                io.redi.robot.firstlife.vision.CameraLensFacing.FRONT,
                visiblePreview = true,
                previewMirrored = true,
            ),
            lastFrameMetadata = CameraFrameMetadata(request.id, request.generation, 1, 100, 640, 480, 90),
            personEvidence = PersonDirectionEvidence(
                request.id,
                request.generation,
                1,
                100,
                250_000_100,
                PersonDirection.CENTER,
                PersonEvidenceReason.STABLE_PERSON,
                NormalizedBoundingBox(0.3f, 0.1f, 0.7f, 0.9f),
                0f,
                0.9f,
            ),
            detectorTelemetry = PersonDetectorTelemetry(1, 42),
            detectorPerformance = PersonDetectorPerformance(30, 40, 65, 70),
        )
        composeRule.setContent {
            HiHugoScreen(
                face().copy(faceState = HiHugoFaceState.OBSERVING),
                cameraSession = model,
                onCloseCameraSession = { closes++ },
                cameraPreview = { androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Blue)) },
                onDeveloperStateRequested = {},
                reducedMotion = true,
            )
        }
        composeRule.onNodeWithTag("hihugo-camera-preview").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("hihugo-camera-active-indicator").assertExists()
        composeRule.onNodeWithTag("hihugo-camera-frame-current").assertExists()
        composeRule.onNodeWithTag("hihugo-person-evidence").assertExists()
        composeRule.onNodeWithTag("hihugo-person-detector-performance").assertExists()
        composeRule.onNodeWithTag("hihugo-close-camera").performScrollTo().performClick()
        assertEquals(1, closes)
    }

    private fun face() = HiHugoPresentationModel(
        HiHugoFaceState.IDLE,
        identity = null,
        safeDisplayIdentity = null,
        bootstrapRunReference = HugoBootstrapRunReference.fromUuid(UUID.fromString("90000000-0000-4000-8000-000000000011")),
    )
}
