package io.redi.robot.feature.hihugo

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.redi.robot.feature.hihugo.presentation.HiHugoFaceState
import io.redi.robot.feature.hihugo.presentation.HiHugoPresentationModel
import io.redi.robot.feature.hihugo.ui.HiHugoScreen
import io.redi.robot.firstlife.identity.HugoBootstrapRunReference
import io.redi.robot.firstlife.voice.LocalSpeechText
import io.redi.robot.firstlife.voice.SpeechOutputKind
import io.redi.robot.firstlife.voice.SpeechOutputModel
import io.redi.robot.firstlife.voice.SpeechOutputPhase
import io.redi.robot.firstlife.voice.SpeechOutputRequest
import io.redi.robot.firstlife.voice.SpeechOutputRequestId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HiHugoSpeechPresentationInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun fixedIntroductionIsExplicitAndGreetingRequiresSessionName() {
        var introductions = 0
        composeRule.setContent {
            HiHugoScreen(
                model = face(),
                onSpeakIntroduction = { introductions++ },
                onDeveloperStateRequested = {},
                reducedMotion = true,
            )
        }
        composeRule.onNodeWithTag("hihugo-speak-introduction").performScrollTo().performClick()
        composeRule.onNodeWithTag("hihugo-speak-greeting").assertIsNotEnabled()
        composeRule.onNodeWithTag("hihugo-start-voice").assertIsNotEnabled()
        composeRule.onNodeWithTag("hihugo-speech-boundary").assertExists()
        assertEquals(1, introductions)
    }

    @Test
    fun activeSpeechOffersCancelAndDisablesMicrophoneStart() {
        var cancellations = 0
        val request = SpeechOutputRequest(
            SpeechOutputRequestId("presentation"),
            1,
            SpeechOutputKind.INTRODUCTION,
            text = LocalSpeechText.from("Përshëndetje."),
        )
        composeRule.setContent {
            HiHugoScreen(
                model = face().copy(faceState = HiHugoFaceState.SPEAKING),
                speechOutput = SpeechOutputModel(SpeechOutputPhase.SPEAKING, activeRequest = request),
                onCancelSpeech = { cancellations++ },
                onDeveloperStateRequested = {},
                reducedMotion = true,
            )
        }
        composeRule.onNodeWithTag("hihugo-start-voice").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("hihugo-cancel-speech").performScrollTo().performClick()
        assertEquals(1, cancellations)
    }

    private fun face() = HiHugoPresentationModel(
        HiHugoFaceState.IDLE,
        identity = null,
        safeDisplayIdentity = null,
        bootstrapRunReference = HugoBootstrapRunReference.fromUuid(UUID.fromString("90000000-0000-4000-8000-000000000010")),
    )
}
