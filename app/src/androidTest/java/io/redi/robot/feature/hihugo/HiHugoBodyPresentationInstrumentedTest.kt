package io.redi.robot.feature.hihugo

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.redi.robot.feature.hihugo.presentation.HiHugoBodyPresentationModel
import io.redi.robot.feature.hihugo.presentation.HiHugoBodyStage
import io.redi.robot.feature.hihugo.presentation.HiHugoFaceState
import io.redi.robot.feature.hihugo.presentation.HiHugoPresentationModel
import io.redi.robot.feature.hihugo.ui.HiHugoScreen
import io.redi.robot.firstlife.identity.HugoBootstrapRunReference
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HiHugoBodyPresentationInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun availableBodyShowsExactCapabilitiesAndOnlyExplicitSelectionControl() {
        var selections = 0
        composeRule.setContent {
            HiHugoScreen(
                model = face(),
                bodyPresentation = body(HiHugoBodyStage.AVAILABLE),
                onSelectActiveBody = { selections++ },
                onDeveloperStateRequested = {},
                developerControlsEnabled = false,
                reducedMotion = true,
            )
        }

        composeRule.onNodeWithText("Body available — selection required").assertExists()
        exactCapabilities.forEach { composeRule.onNodeWithText("• $it").assertExists() }
        composeRule.onNodeWithTag("hihugo-select-active-body").performScrollTo().performClick()
        composeRule.onNodeWithTag("hihugo-deactivate-active-body").assertDoesNotExist()
        composeRule.onNodeWithText("Execute").assertDoesNotExist()
        assertEquals(1, selections)
    }

    @Test
    fun selectedBodyShowsDeactivateAndHonestAuthorityBoundary() {
        var deactivations = 0
        composeRule.setContent {
            HiHugoScreen(
                model = face(),
                bodyPresentation = body(HiHugoBodyStage.SELECTED),
                onDeactivateActiveBody = { deactivations++ },
                onDeveloperStateRequested = {},
                developerControlsEnabled = false,
                reducedMotion = true,
            )
        }

        composeRule.onNodeWithText("Active body selected").assertExists()
        composeRule.onNodeWithTag("hihugo-selection-boundary").assertExists()
        composeRule.onNodeWithTag("hihugo-deactivate-active-body").performScrollTo().performClick()
        composeRule.onNodeWithTag("hihugo-select-active-body").assertDoesNotExist()
        assertEquals(1, deactivations)
    }

    private fun face() = HiHugoPresentationModel(
        HiHugoFaceState.BODY_READY,
        identity = null,
        safeDisplayIdentity = null,
        bootstrapRunReference = HugoBootstrapRunReference.fromUuid(
            UUID.fromString("80000000-0000-4000-8000-000000000001"),
        ),
    )

    private fun body(stage: HiHugoBodyStage) = HiHugoBodyPresentationModel(
        stage = stage,
        providerLabel = "LOOI Reference Body",
        statusLabel = if (stage == HiHugoBodyStage.SELECTED) "Active body selected"
        else "Body available — selection required",
        negotiationFresh = true,
        capabilities = exactCapabilities.reversed(),
        selectionExpiresAt = null,
        blocked = null,
    )

    private companion object {
        val exactCapabilities = listOf(
            "body.head.position.control@1.0",
            "body.light.control@1.0",
            "mobility.move@1.0",
            "mobility.stop@1.0",
            "provider.connection.status@1.0",
            "safety.lower_contact.status@1.0",
        )
    }
}
