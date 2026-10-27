package io.redi.robot.feature.manualcontrol

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HeadControlsInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()
    @Test fun enabledCenterEmitsSemanticCommand() {
        val commands = mutableListOf<Float>()
        composeRule.setContent { ManualHeadControls(ManualHeadUiState(true, true, true), ManualHeadCommandSink(commands::add)) }
        composeRule.onNodeWithTag("head-center").performClick()
        assertEquals(listOf(0f), commands)
    }
    @Test fun uncalibratedControlsAreDisabled() {
        composeRule.setContent { ManualHeadControls(ManualHeadUiState(true, true, false), ManualHeadCommandSink {}) }
        composeRule.onNodeWithTag("head-up").assertIsNotEnabled()
        composeRule.onNodeWithTag("head-slider").assertIsNotEnabled()
    }
}
