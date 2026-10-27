package io.redi.robot.feature.telemetry

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.redi.robot.core.state.TelemetryFreshness
import org.junit.Rule
import org.junit.Test

class TelemetryScreenInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()
    @Test fun staleTelemetryIsVisiblyMarked() {
        composeRule.setContent { TelemetryScreen(TelemetryUiState(profile = "PROFILE_A/v1", freshness = TelemetryFreshness.STALE)) }
        composeRule.onNodeWithText("Freshness: STALE").assertExists()
    }
}
