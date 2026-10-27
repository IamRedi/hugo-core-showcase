package io.redi.robot.feature.hihugo

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.redi.robot.feature.hihugo.presentation.HiHugoFaceState
import io.redi.robot.feature.hihugo.presentation.HiHugoPresentationModel
import io.redi.robot.feature.hihugo.ui.HiHugoScreen
import io.redi.robot.firstlife.experience.FirstLifeExperienceSnapshot
import io.redi.robot.firstlife.experience.FirstLifeExperienceStage
import io.redi.robot.firstlife.identity.HugoBootstrapRunReference
import io.redi.robot.firstlife.interaction.FirstLifeBodyStatus
import io.redi.robot.firstlife.interaction.FirstLifeInteractionState
import io.redi.robot.firstlife.interaction.FirstLifePresentationIntent
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HiHugoFirstLifePresentationInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun readyJourneyExposesExplicitStartAndEphemeralResetWithoutClaimingSuccess() {
        var starts = 0
        var resets = 0
        composeRule.setContent {
            HiHugoScreen(
                model = face(),
                experience = snapshot(FirstLifeExperienceStage.READY),
                onStartExperience = { starts++ },
                onResetExperience = { resets++ },
                onDeveloperStateRequested = {},
                reducedMotion = true,
            )
        }

        composeRule.onNodeWithTag("hihugo-first-life-panel").performScrollTo().assertExists()
        composeRule.onNodeWithTag("hihugo-first-life-status")
            .assertTextEquals("Ready for one explicit First Life session")
        composeRule.onNodeWithTag("hihugo-first-life-start").performScrollTo().performClick()
        composeRule.onNodeWithTag("hihugo-first-life-reset").performScrollTo().performClick()
        composeRule.onNodeWithTag("hihugo-first-life-boundary").assertExists()
        assertEquals(1, starts)
        assertEquals(1, resets)
    }

    @Test
    fun observationContinuationRemainsAnExplicitVisibleCameraAction() {
        var cameraStarts = 0
        composeRule.setContent {
            HiHugoScreen(
                model = face(),
                experience = snapshot(
                    FirstLifeExperienceStage.OBSERVATION_READY,
                    interaction = FirstLifeInteractionState.READY_FOR_NEXT_PHASE,
                    observationReady = true,
                    nameAvailable = true,
                ),
                onStartCameraSession = { cameraStarts++ },
                onDeveloperStateRequested = {},
                reducedMotion = true,
            )
        }

        composeRule.onNodeWithTag("hihugo-first-life-observe").performScrollTo().performClick()
        composeRule.onNodeWithTag("hihugo-first-life-status")
            .assertTextEquals("Greeting complete · visible camera still requires your tap")
        assertEquals(1, cameraStarts)
    }

    @Test
    fun completionLabelRequiresTheCenteredEvidenceSnapshot() {
        composeRule.setContent {
            HiHugoScreen(
                model = face(),
                experience = snapshot(
                    FirstLifeExperienceStage.PERSON_CENTERED,
                    interaction = FirstLifeInteractionState.READY_FOR_NEXT_PHASE,
                    observationReady = true,
                    nameAvailable = true,
                    centered = true,
                ),
                onDeveloperStateRequested = {},
                reducedMotion = true,
            )
        }

        composeRule.onNodeWithTag("hihugo-first-life-status").performScrollTo()
            .assertTextEquals("First Life path complete · fresh centered evidence")
        composeRule.onNodeWithTag("hihugo-first-life-cancel").assertExists()
    }

    private fun snapshot(
        stage: FirstLifeExperienceStage,
        interaction: FirstLifeInteractionState = FirstLifeInteractionState.READY,
        observationReady: Boolean = false,
        nameAvailable: Boolean = false,
        centered: Boolean = false,
    ) = FirstLifeExperienceSnapshot(
        stage,
        interaction,
        FirstLifePresentationIntent.IDLE,
        FirstLifeBodyStatus.UNAVAILABLE,
        nameAvailable,
        observationReady,
        cameraActive = stage == FirstLifeExperienceStage.OBSERVING || centered,
        orientationActive = false,
        centeredPersonConfirmed = centered,
        blockedSource = null,
        lastCleanupReason = null,
    )

    private fun face() = HiHugoPresentationModel(
        HiHugoFaceState.IDLE,
        identity = null,
        safeDisplayIdentity = null,
        bootstrapRunReference = HugoBootstrapRunReference.fromUuid(
            UUID.fromString("80000000-0000-4000-8000-000000000014"),
        ),
    )
}
