package io.redi.robot.feature.manualcontrol

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualDriveControllerTest {
    @Test
    fun `press refresh and release emit semantic dead man trace`() {
        val commands = mutableListOf<ManualDriveCommand>()
        val controller = ManualDriveController(commands::add)

        val token = controller.press(ManualDriveDirection.FORWARD, 0)!!
        controller.refresh(token, 50)
        controller.release(token, 60)

        assertEquals(
            listOf(
                ManualDriveCommand.Refresh(0, ManualDriveDirection.FORWARD, 0),
                ManualDriveCommand.Refresh(1, ManualDriveDirection.FORWARD, 50),
                ManualDriveCommand.Stop(2, ManualStopReason.RELEASE, 60),
            ),
            commands,
        )
    }

    @Test
    fun `multi touch focus navigation and background each stop active control once`() {
        val commands = mutableListOf<ManualDriveCommand>()
        val controller = ManualDriveController(commands::add)
        controller.press(ManualDriveDirection.LEFT, 0)
        assertEquals(null, controller.press(ManualDriveDirection.RIGHT, 1))
        val background = controller.press(ManualDriveDirection.BACKWARD, 2)!!
        controller.cancel(background, ManualStopReason.BACKGROUND, 3)
        assertEquals(false, controller.refresh(background, 3))
        val focus = controller.press(ManualDriveDirection.FORWARD, 4)!!
        controller.cancel(focus, ManualStopReason.FOCUS_LOST, 5)
        val navigation = controller.press(ManualDriveDirection.RIGHT, 6)!!
        controller.cancel(navigation, ManualStopReason.NAVIGATION, 7)

        assertEquals(
            listOf(
                ManualDriveCommand.Refresh(0, ManualDriveDirection.LEFT, 0),
                ManualDriveCommand.Stop(1, ManualStopReason.MULTI_TOUCH, 1),
                ManualDriveCommand.Refresh(2, ManualDriveDirection.BACKWARD, 2),
                ManualDriveCommand.Stop(3, ManualStopReason.BACKGROUND, 3),
                ManualDriveCommand.Refresh(4, ManualDriveDirection.FORWARD, 4),
                ManualDriveCommand.Stop(5, ManualStopReason.FOCUS_LOST, 5),
                ManualDriveCommand.Refresh(6, ManualDriveDirection.RIGHT, 6),
                ManualDriveCommand.Stop(7, ManualStopReason.NAVIGATION, 7),
            ),
            commands,
        )
    }

    @Test
    fun `emergency stop terminates an active press without a second release stop`() {
        val commands = mutableListOf<ManualDriveCommand>()
        val controller = ManualDriveController(commands::add)
        val token = controller.press(ManualDriveDirection.FORWARD, 0)!!
        controller.emergencyStop(1)
        controller.release(token, 2)
        assertEquals(false, controller.refresh(token, 3))

        assertEquals(
            listOf(
                ManualDriveCommand.Refresh(0, ManualDriveDirection.FORWARD, 0),
                ManualDriveCommand.Stop(1, ManualStopReason.EMERGENCY_STOP, 1),
            ),
            commands,
        )
    }

    @Test
    fun `stale background press is cancelled and first new press is accepted`() {
        val commands = mutableListOf<ManualDriveCommand>()
        val controller = ManualDriveController(commands::add)
        controller.press(ManualDriveDirection.FORWARD, 0)

        val recovered = controller.press(ManualDriveDirection.RIGHT, 200)

        assertEquals(2L, recovered)
        assertEquals(
            listOf(
                ManualDriveCommand.Refresh(0, ManualDriveDirection.FORWARD, 0),
                ManualDriveCommand.Stop(1, ManualStopReason.POINTER_CANCELLED, 200),
                ManualDriveCommand.Refresh(2, ManualDriveDirection.RIGHT, 200),
            ),
            commands,
        )
    }
}
