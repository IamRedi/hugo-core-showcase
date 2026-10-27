package io.redi.robot.feature.manualcontrol

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LightControlsInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()
    @Test fun minimumOnIsOnlyExposedWhenConfigured() {
        val commands = mutableListOf<Float>()
        composeRule.setContent { ManualLightControls(ManualLightUiState(true, true, true), ManualLightCommandSink(commands::add)) }
        composeRule.onNodeWithTag("light-off").performClick(); composeRule.onNodeWithTag("light-minimum-on").performClick()
        assertEquals(listOf(0f, 1f), commands)
    }
    @Test fun unsafeMinimumOnIsDisabled() {
        composeRule.setContent { ManualLightControls(ManualLightUiState(true, false, true), ManualLightCommandSink {}) }
        composeRule.onNodeWithTag("light-minimum-on").assertIsNotEnabled()
    }
}
