package io.redi.robot.feature.hihugo

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.redi.robot.feature.hihugo.presentation.HiHugoFaceState
import io.redi.robot.feature.hihugo.presentation.HiHugoPresentationModel
import io.redi.robot.feature.hihugo.ui.HiHugoScreen
import io.redi.robot.firstlife.identity.HugoBootstrapRunReference
import io.redi.robot.firstlife.voice.VoiceCapturePolicy
import io.redi.robot.firstlife.voice.VoiceRecognitionEvidence
import io.redi.robot.firstlife.voice.VoiceRecognitionTier
import io.redi.robot.firstlife.voice.VoiceSessionModel
import io.redi.robot.firstlife.voice.VoiceSessionPhase
import io.redi.robot.firstlife.voice.VoiceSessionRequest
import io.redi.robot.firstlife.voice.VoiceSessionRequestId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HiHugoVoicePresentationInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun idleVoicePanelRequiresOneExplicitStartAndStatesPrivacyBoundary() {
        var starts = 0
        composeRule.setContent {
            HiHugoScreen(
                model = face(),
                voiceSession = VoiceSessionModel.IDLE,
                voiceInputAllowed = true,
                onStartVoiceSession = { starts++ },
                onDeveloperStateRequested = {},
                reducedMotion = true,
            )
        }

        composeRule.onNodeWithTag("hihugo-start-voice").performScrollTo().performClick()
        composeRule.onNodeWithTag("hihugo-cancel-voice").assertDoesNotExist()
        composeRule.onNodeWithTag("hihugo-voice-privacy").assertExists()
        assertEquals(1, starts)
    }

    @Test
    fun activeAndCompletedVoiceStatesExposeNoTranscriptOrAutomaticAuthority() {
        var cancels = 0
        val request = VoiceSessionRequest(VoiceSessionRequestId("ui-request"), 1, 8_001)
        var voice by mutableStateOf(VoiceSessionModel(VoiceSessionPhase.LISTENING, request, true, null, null))
        composeRule.setContent {
            HiHugoScreen(
                model = face().copy(faceState = HiHugoFaceState.LISTENING),
                voiceSession = voice,
                onCancelVoiceSession = { cancels++ },
                onDeveloperStateRequested = {},
                reducedMotion = true,
            )
        }
        composeRule.onNodeWithText("Listening locally — speak your name").assertExists()
        composeRule.onNodeWithTag("hihugo-cancel-voice").performScrollTo().performClick()
        assertEquals(1, cancels)

        composeRule.runOnIdle {
            voice = VoiceSessionModel(
                VoiceSessionPhase.COMPLETED,
                request,
                false,
                VoiceRecognitionEvidence(VoiceRecognitionTier.ON_DEVICE, VoiceCapturePolicy.LOCALE_TAG, 1, 4),
                null,
            )
        }
        composeRule.onNodeWithText("Local speech recognized; transcript is not retained").assertExists()
        composeRule.onNodeWithText("Redi").assertDoesNotExist()
        composeRule.onNodeWithText("Authorized").assertDoesNotExist()
    }

    private fun face() = HiHugoPresentationModel(
        HiHugoFaceState.IDLE,
        identity = null,
        safeDisplayIdentity = null,
        bootstrapRunReference = HugoBootstrapRunReference.fromUuid(
            UUID.fromString("90000000-0000-4000-8000-000000000001"),
        ),
    )
}
