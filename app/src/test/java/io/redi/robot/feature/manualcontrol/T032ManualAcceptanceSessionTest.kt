package io.redi.robot.feature.manualcontrol

import io.redi.robot.body.looi.ble.queue.ActiveGattOperationTransport
import io.redi.robot.body.looi.ble.queue.GattOperationSuccess
import io.redi.robot.body.looi.ble.queue.GattQueuedOperationRequest
import io.redi.robot.body.looi.connection.GattCharacteristicInventory
import io.redi.robot.body.looi.connection.GattNotificationEnvelope
import io.redi.robot.body.looi.connection.GattServiceInventory
import io.redi.robot.safety.deadman.DeadManMonotonicClock
import io.redi.robot.safety.deadman.DeadManScheduledTask
import io.redi.robot.safety.deadman.DeadManScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class T032ManualAcceptanceSessionTest {
    @Test
    fun `motion requires active fresh FED9 and explicit raised-wheel arm`() {
        val harness = Harness()

        harness.session.dispatch(ManualDriveCommand.Refresh(0, ManualDriveDirection.FORWARD, 0))
        assertTrue(harness.transport.requests.isEmpty())

        harness.makeSafe()
        assertTrue(harness.session.armRaisedWheels().driveEnabled)
        harness.session.dispatch(ManualDriveCommand.Refresh(1, ManualDriveDirection.FORWARD, 0))

        assertEquals("fed0", shortId(harness.transport.requests.single().characteristicUuid))
        assertEquals(listOf(16, 0), harness.transport.requests.single().payload)
    }

    @Test
    fun `raised-wheel pulse cap latches e-stop and requests repeated neutral`() {
        val harness = Harness()
        harness.makeSafe()
        harness.session.armRaisedWheels()
        harness.session.dispatch(ManualDriveCommand.Refresh(0, ManualDriveDirection.FORWARD, 0))

        harness.now = 250
        val state = harness.session.dispatch(ManualDriveCommand.Refresh(1, ManualDriveDirection.FORWARD, 250))

        assertTrue(state.emergencyStopLatched)
        assertEquals(T032VerificationMode.DISARMED, state.mode)
        assertEquals(2, state.stopRequestCount)
        assertTrue(harness.transport.requests.takeLast(2).all { it.payload == listOf(0, 0) })
    }

    @Test
    fun `release and disconnect request neutral and leave session disarmed`() {
        val harness = Harness()
        harness.makeSafe()
        harness.session.armRaisedWheels()
        harness.session.dispatch(ManualDriveCommand.Refresh(0, ManualDriveDirection.RIGHT, 0))
        harness.session.dispatch(ManualDriveCommand.Stop(1, ManualStopReason.RELEASE, 20))
        assertEquals(listOf(0, 0), harness.transport.requests.last().payload)

        val disconnected = harness.session.onDisconnect()
        assertFalse(disconnected.driveEnabled)
        assertTrue(disconnected.emergencyStopLatched)
        assertEquals(T032VerificationMode.DISARMED, disconnected.mode)
        assertTrue(harness.transport.requests.takeLast(2).all { it.payload == listOf(0, 0) })
    }

    @Test
    fun `release cancels dead man without latching e-stop or disarming floor mode`() {
        val harness = Harness()
        harness.makeSafe()
        harness.session.armFloor()
        harness.session.dispatch(ManualDriveCommand.Refresh(0, ManualDriveDirection.FORWARD, 0))

        val released = harness.session.dispatch(ManualDriveCommand.Stop(1, ManualStopReason.RELEASE, 20))
        harness.now = 200
        harness.runDueTasks()
        val afterFormerDeadline = harness.session.state()

        assertFalse(released.emergencyStopLatched)
        assertEquals(T032VerificationMode.FLOOR, released.mode)
        assertFalse(afterFormerDeadline.emergencyStopLatched)
        assertEquals(T032VerificationMode.FLOOR, afterFormerDeadline.mode)
        assertEquals(listOf(0, 0), harness.transport.requests.last().payload)
    }

    @Test
    fun `recreated UI sequence cannot invalidate generation-owned dead man ordering`() {
        val harness = Harness()
        harness.makeSafe()
        harness.session.armFloor()
        harness.session.dispatch(ManualDriveCommand.Refresh(0, ManualDriveDirection.FORWARD, 0))
        harness.session.dispatch(ManualDriveCommand.Stop(1, ManualStopReason.RELEASE, 20))

        harness.now = 30
        val afterUiRestart = harness.session.dispatch(ManualDriveCommand.Refresh(0, ManualDriveDirection.FORWARD, 30))

        assertFalse(afterUiRestart.emergencyStopLatched)
        assertEquals(T032VerificationMode.FLOOR, afterUiRestart.mode)
        assertEquals(listOf(16, 0), harness.transport.requests.last().payload)
    }

    @Test
    fun `contact loss during motion latches stop and cannot resume pending command`() {
        val harness = Harness()
        harness.makeSafe()
        harness.session.armFloor()
        harness.session.dispatch(ManualDriveCommand.Refresh(0, ManualDriveDirection.FORWARD, 0))

        harness.now = 20
        val stopped = harness.session.onNotification(
            GattNotificationEnvelope(7, "000000ff-0000-1000-8000-00805f9b34fb", "fed9", listOf(1, 0, 1, 1, 1)),
            harness.now,
        )
        assertTrue(stopped.emergencyStopLatched)
        assertEquals(T032VerificationMode.DISARMED, stopped.mode)
        assertTrue(harness.transport.requests.takeLast(2).all { it.payload == listOf(0, 0) })

        harness.now = 30
        harness.session.onNotification(
            GattNotificationEnvelope(7, "000000ff-0000-1000-8000-00805f9b34fb", "fed9", listOf(1, 1, 1, 1, 1)),
            harness.now,
        )
        val requestCountBeforeRetry = harness.transport.requests.size
        val retry = harness.session.dispatch(ManualDriveCommand.Refresh(1, ManualDriveDirection.FORWARD, 30))

        assertTrue(retry.emergencyStopLatched)
        assertEquals(requestCountBeforeRetry, harness.transport.requests.size)
    }

    @Test
    fun `hazard latches without disconnect and safe return requires local stationary reset plus new press`() {
        val transport = DelayedTransport()
        val harness = Harness(transport)
        harness.makeSafe()
        harness.session.armFloor()
        harness.session.dispatch(ManualDriveCommand.Refresh(0, ManualDriveDirection.FORWARD, 0))

        harness.now = 20
        val hazard = harness.session.onNotification(
            GattNotificationEnvelope(7, "000000ff-0000-1000-8000-00805f9b34fb", "fed9", listOf(1, 0, 1, 1, 1)),
            harness.now,
        )
        assertTrue(hazard.prerequisiteReady)
        assertTrue(hazard.fed9Active)
        assertTrue(hazard.emergencyStopLatched)
        assertEquals(T032VerificationMode.DISARMED, hazard.mode)

        harness.now = 30
        val safeReturn = harness.session.onNotification(
            GattNotificationEnvelope(7, "000000ff-0000-1000-8000-00805f9b34fb", "fed9", listOf(1, 1, 1, 1, 1)),
            harness.now,
        )
        assertTrue(safeReturn.prerequisiteReady)
        assertTrue(safeReturn.fed9Active)
        assertTrue(safeReturn.emergencyStopLatched)
        assertFalse(safeReturn.driveEnabled)

        transport.completeAll()
        val reset = harness.session.resetEmergencyStopReportedStationary()
        assertFalse(reset.emergencyStopLatched)
        assertEquals(T032VerificationMode.DISARMED, reset.mode)
        assertFalse(reset.driveEnabled)

        val requestsBeforeNewPress = transport.requests.size
        assertTrue(harness.session.armFloor().driveEnabled)
        harness.session.dispatch(ManualDriveCommand.Refresh(1, ManualDriveDirection.FORWARD, 30))
        assertEquals(requestsBeforeNewPress + 1, transport.requests.size)
    }

    @Test
    fun `head command requires explicit limited calibration arm and uses FED1`() {
        val harness = Harness()
        harness.makeSafe()

        harness.session.requestHead(-0.5f)
        assertTrue(harness.transport.requests.isEmpty())

        harness.session.armRaisedWheels()
        harness.session.armHeadCalibration()
        harness.session.requestHead(-0.5f)

        assertEquals("fed1", shortId(harness.transport.requests.single().characteristicUuid))
        assertEquals(listOf(0x3d), harness.transport.requests.single().payload)
    }

    @Test
    fun `parsed FED9 stream keeps event-based safe contact state fresh`() {
        val harness = Harness()
        harness.makeSafe()
        harness.now = 1_000
        harness.session.onNotification(
            GattNotificationEnvelope(7, "000000ff-0000-1000-8000-00805f9b34fb", "fed9", listOf(0x0e, 0x22, 0x01)),
            harness.now,
        )

        assertTrue(harness.session.armRaisedWheels().driveEnabled)
    }

    @Test
    fun `generation-scoped event contact state permits continuous manual refresh`() {
        val harness = Harness()
        harness.makeSafe()
        harness.now = 1_000
        assertTrue(harness.session.armRaisedWheels().driveEnabled)

        harness.session.dispatch(ManualDriveCommand.Refresh(0, ManualDriveDirection.FORWARD, 1_000))
        assertEquals(listOf(16, 0), harness.transport.requests.last().payload)

        harness.now = 1_151
        val continued = harness.session.dispatch(ManualDriveCommand.Refresh(1, ManualDriveDirection.FORWARD, 1_151))
        assertFalse(continued.emergencyStopLatched)
        assertEquals(T032VerificationMode.RAISED_WHEELS, continued.mode)
        assertEquals(2, harness.transport.requests.count { shortId(it.characteristicUuid) == "fed0" })
    }

    @Test
    fun `fresh parsed FED9 during motion permits next refresh`() {
        val harness = Harness()
        harness.makeSafe()
        harness.now = 1_000
        harness.session.armRaisedWheels()
        harness.session.dispatch(ManualDriveCommand.Refresh(0, ManualDriveDirection.FORWARD, 1_000))

        harness.now = 1_040
        harness.session.onNotification(
            GattNotificationEnvelope(7, "000000ff-0000-1000-8000-00805f9b34fb", "fed9", listOf(0x0e, 0x20, 0x00)),
            harness.now,
        )
        harness.now = 1_050
        val state = harness.session.dispatch(ManualDriveCommand.Refresh(1, ManualDriveDirection.FORWARD, 1_050))

        assertFalse(state.emergencyStopLatched)
        assertEquals(2, harness.transport.requests.count { shortId(it.characteristicUuid) == "fed0" })
    }

    @Test
    fun `event contact state remains usable without a stationary FED9 heartbeat`() {
        val harness = Harness()
        harness.makeSafe()
        harness.now = 1_000
        harness.session.armRaisedWheels()
        harness.session.dispatch(ManualDriveCommand.Refresh(0, ManualDriveDirection.FORWARD, 1_000))

        harness.now = 1_100
        val state = harness.session.dispatch(ManualDriveCommand.Refresh(1, ManualDriveDirection.FORWARD, 1_100))

        assertFalse(state.emergencyStopLatched)
        assertEquals(2, harness.transport.requests.count { shortId(it.characteristicUuid) == "fed0" })
    }

    @Test
    fun `hardware-calibrated angular polarity maps right negative and left positive`() {
        val right = Harness().apply {
            makeSafe()
            session.armFloor()
            session.dispatch(ManualDriveCommand.Refresh(0, ManualDriveDirection.RIGHT, 0))
        }
        val left = Harness().apply {
            makeSafe()
            session.armFloor()
            session.dispatch(ManualDriveCommand.Refresh(0, ManualDriveDirection.LEFT, 0))
        }

        assertEquals(listOf(0, -16), right.transport.requests.single().payload)
        assertEquals(listOf(0, 16), left.transport.requests.single().payload)
    }

    @Test
    fun `verified semantic entry point preserves the exact manual safety and queue path`() {
        val harness = Harness()
        harness.makeSafe()
        assertEquals(true, harness.session.state().lowerContactSafe)
        harness.session.armFloor()

        val move = harness.session.dispatchVerified(
            ManualDriveCommand.Refresh(1, ManualDriveDirection.LEFT, 0),
        )
        val stop = harness.session.dispatchVerified(
            ManualDriveCommand.Stop(2, ManualStopReason.RELEASE, 20),
        )

        assertEquals(T032VerifiedDisposition.ACCEPTED, move.disposition)
        assertTrue(move.state.deadManArmed)
        assertEquals(T032VerifiedDisposition.ACCEPTED, stop.disposition)
        assertFalse(stop.state.deadManArmed)
        assertEquals(listOf(0, 16), harness.transport.requests.first().payload)
        assertEquals(listOf(0, 0), harness.transport.requests.last().payload)
        assertFalse(stop.state.emergencyStopLatched)
    }

    private class Harness(val transport: RecordingTransport = FakeTransport()) {
        var now = 0L
        private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
        val session = T032ManualAcceptanceSession(
            generation = 7,
            inventory = listOf(
                GattServiceInventory(
                    "000000ff-0000-1000-8000-00805f9b34fb",
                    listOf("fed0", "fed1", "fed2", "fed9").map(::GattCharacteristicInventory),
                ),
            ),
            transport = transport,
            clock = DeadManMonotonicClock { now },
            scheduler = DeadManScheduler { deadline, action ->
                scheduled += deadline to action
                DeadManScheduledTask { scheduled.removeAll { it.second === action } }
            },
        )

        fun makeSafe() {
            session.setFed9Active(true)
            session.onNotification(
                GattNotificationEnvelope(7, "000000ff-0000-1000-8000-00805f9b34fb", "fed9", listOf(1, 1, 1, 1, 1)),
                now,
            )
        }

        fun runDueTasks() {
            scheduled.toList().filter { it.first <= now }.forEach { it.second() }
        }
    }

    private interface RecordingTransport : ActiveGattOperationTransport {
        val requests: MutableList<GattQueuedOperationRequest>
    }

    private class FakeTransport : RecordingTransport {
        override val generation = 7L
        override val requests = mutableListOf<GattQueuedOperationRequest>()
        override fun isAvailable() = true
        override fun dispatch(operation: GattQueuedOperationRequest, onResult: (io.redi.robot.body.looi.ble.queue.GattOperationResult) -> Unit) {
            requests += operation
            onResult(GattOperationSuccess())
        }
    }

    private class DelayedTransport : RecordingTransport {
        override val generation = 7L
        override val requests = mutableListOf<GattQueuedOperationRequest>()
        private val callbacks = mutableListOf<(io.redi.robot.body.looi.ble.queue.GattOperationResult) -> Unit>()
        override fun isAvailable() = true
        override fun dispatch(operation: GattQueuedOperationRequest, onResult: (io.redi.robot.body.looi.ble.queue.GattOperationResult) -> Unit) {
            requests += operation
            callbacks += onResult
        }

        fun completeAll() {
            var completed = 0
            while (completed < callbacks.size) {
                callbacks[completed++](GattOperationSuccess())
            }
        }
    }

    private fun shortId(uuid: String): String = uuid.lowercase().removePrefix("0000").substringBefore('-')
}
