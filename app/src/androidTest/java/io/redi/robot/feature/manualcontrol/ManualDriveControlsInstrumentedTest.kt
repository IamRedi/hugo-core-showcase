package io.redi.robot.feature.manualcontrol

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ManualDriveControlsInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun pressAndReleaseEmitsRefreshThenStop() {
        val commands = mutableListOf<ManualDriveCommand>()
        composeRule.setContent {
            ManualDriveControls(
                state = ManualDriveUiState(ready = true, safe = true, emergencyStopLatched = false),
                controller = ManualDriveController(commands::add),
                nowElapsedMs = { 0L },
                onEmergencyStop = {},
                refreshIntervalMs = 10_000L,
            )
        }

        composeRule.onNodeWithTag("manual-forward").performTouchInput {
            down(center)
            up()
        }
        composeRule.waitUntil(5_000) { commands.size == 2 }

        assertEquals(ManualDriveCommand.Refresh(0, ManualDriveDirection.FORWARD, 0), commands.first())
        assertEquals(ManualDriveCommand.Stop(1, ManualStopReason.RELEASE, 0), commands.last())
    }

    @Test
    fun disabledControlDoesNotEmitMotionAndEmergencyStopRemainsAvailable() {
        val commands = mutableListOf<ManualDriveCommand>()
        var emergencyStopCalls = 0
        composeRule.setContent {
            ManualDriveControls(
                state = ManualDriveUiState(ready = false, safe = false, emergencyStopLatched = false),
                controller = ManualDriveController(commands::add),
                nowElapsedMs = { 0L },
                onEmergencyStop = { emergencyStopCalls++ },
            )
        }

        composeRule.onNodeWithTag("manual-forward").assertIsNotEnabled()
        composeRule.onNodeWithTag("manual-emergency-stop").performClick()

        assertEquals(emptyList<ManualDriveCommand>(), commands)
        assertEquals(1, emergencyStopCalls)
    }
}
