package io.redi.robot.feature.hihugo

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import io.redi.robot.feature.hihugo.presentation.HiHugoBodyPresentationModel
import io.redi.robot.feature.hihugo.presentation.HiHugoBodyStage
import io.redi.robot.feature.hihugo.presentation.HiHugoFaceState
import io.redi.robot.feature.hihugo.presentation.HiHugoPresentationModel
import io.redi.robot.feature.hihugo.ui.HiHugoScreen
import io.redi.robot.firstlife.identity.HugoBootstrapRunReference
import io.redi.robot.firstlife.vision.CameraGeneration
import io.redi.robot.firstlife.vision.CameraSessionModel
import io.redi.robot.firstlife.vision.CameraSessionPhase
import io.redi.robot.firstlife.vision.CameraSessionRequest
import io.redi.robot.firstlife.vision.CameraSessionRequestId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HiHugoOrientationPresentationInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun readyControlBeginsOnDownAndStopsOnReleaseRatherThanOrdinaryClickAuthority() {
        var begins = 0
        var releases = 0
        composeRule.setContent {
            HiHugoScreen(
                model = face(),
                bodyPresentation = selectedBody(),
                cameraSession = observingCamera(),
                orientationMountingConfirmed = true,
                orientationSafetyReady = true,
                onBeginOrientationHold = { begins++ },
                onReleaseOrientationHold = { releases++ },
                onDeveloperStateRequested = {},
                reducedMotion = true,
            )
        }

        composeRule.onNodeWithTag("hihugo-orientation-hold").performScrollTo().performTouchInput {
            down(center)
            advanceEventTime(20)
            up()
        }

        assertEquals(1, begins)
        assertEquals(1, releases)
        composeRule.onNodeWithTag("hihugo-orientation-envelope").assertExists()
        composeRule.onNodeWithTag("hihugo-orientation-boundary").assertExists()
    }

    @Test
    fun preparationCancelAndLocalEmergencyStopRemainExplicitControls() {
        var prepares = 0
        var cancels = 0
        var emergencyStops = 0
        composeRule.setContent {
            HiHugoScreen(
                model = face(),
                bodyPresentation = selectedBody(),
                onPrepareOrientation = { prepares++ },
                onCancelOrientation = { cancels++ },
                onOrientationEmergencyStop = { emergencyStops++ },
                onDeveloperStateRequested = {},
                reducedMotion = true,
            )
        }

        composeRule.onNodeWithTag("hihugo-orientation-prepare").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hihugo-orientation-cancel").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hihugo-orientation-estop").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(1, prepares)
        assertEquals(1, cancels)
        assertEquals(1, emergencyStops)
    }

    private fun observingCamera() = CameraSessionModel(
        CameraSessionPhase.OBSERVING,
        activeRequest = CameraSessionRequest(CameraSessionRequestId("camera-fl013-ui"), CameraGeneration(1)),
    )

    private fun selectedBody() = HiHugoBodyPresentationModel(
        HiHugoBodyStage.SELECTED,
        "LOOI Reference Body",
        "Active body selected",
        true,
        emptyList(),
        null,
        null,
    )

    private fun face() = HiHugoPresentationModel(
        HiHugoFaceState.OBSERVING,
        identity = null,
        safeDisplayIdentity = null,
        bootstrapRunReference = HugoBootstrapRunReference.fromUuid(
            UUID.fromString("80000000-0000-4000-8000-000000000013"),
        ),
    )
}
