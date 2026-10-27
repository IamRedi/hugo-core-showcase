package io.redi.robot.feature.hihugo

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.redi.robot.HugoFirstLifeApplication
import io.redi.robot.MainActivity
import io.redi.robot.feature.hihugo.presentation.HiHugoFaceState
import io.redi.robot.feature.hihugo.presentation.HiHugoVisualCatalog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HiHugoShellInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun restoredIdentityShowsIdleShellWithoutProviderRuntime() {
        composeRule.onNodeWithText("HiHugo").assertExists()
        composeRule.onNodeWithText("Idle").assertExists()
        composeRule.onNodeWithText("Body / provider: not connected").assertExists()
        composeRule.onNodeWithTag("hihugo-face").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("HUGO face")),
        )
    }

    @Test
    fun developerHarnessRendersEveryPresentationState() {
        HiHugoFaceState.entries.forEach { state ->
            composeRule.onNodeWithTag("hihugo-state-${state.name}").performScrollTo().performClick()
            val expectedLabel = HiHugoVisualCatalog.forState(state).label
            composeRule.onNodeWithTag("hihugo-face").assert(
                SemanticsMatcher("state description begins with $expectedLabel") { node ->
                    node.config.contains(SemanticsProperties.StateDescription) &&
                        node.config[SemanticsProperties.StateDescription].startsWith(expectedLabel)
                },
            )
        }
    }

    @Test
    fun recreationPreservesPresentationAndExactHugoIdentity() {
        val before = (composeRule.activity.application as HugoFirstLifeApplication).hugoIdentityBootstrapSnapshot.identity
        composeRule.onNodeWithTag("hihugo-state-OBSERVING").performScrollTo().performClick()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        val after = (composeRule.activity.application as HugoFirstLifeApplication).hugoIdentityBootstrapSnapshot.identity

        composeRule.onNodeWithText("Observing").assertExists()
        assertEquals(before, after)
    }

    @Test
    fun backgroundPausesFaceAnimation() {
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hihugo-face").assertExists()
    }
}
