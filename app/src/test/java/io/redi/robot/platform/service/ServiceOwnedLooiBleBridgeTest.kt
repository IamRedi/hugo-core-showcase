package io.redi.robot.platform.service

import io.redi.robot.body.looi.connection.ConnectionFailure
import io.redi.robot.body.looi.connection.ConnectionPhase
import io.redi.robot.body.looi.connection.ConnectionState
import io.redi.robot.body.looi.connection.ConnectionTarget
import io.redi.robot.platform.connection.ble.BleTransportEvent
import io.redi.robot.platform.connection.ble.BleTransportObserver
import io.redi.robot.platform.connection.ble.BleTransportTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceOwnedLooiBleBridgeTest {
    @Test
    fun `generic connect invokes existing owner and projects channel and readiness`() {
        val events = mutableListOf<BleTransportEvent>()
        var target: ConnectionTarget? = null
        val bridge = ServiceOwnedLooiBleBridge(
            connectExisting = {
                target = it
                ConnectionState(ConnectionPhase.CONNECTING, generation = 7, target = it)
            },
            disconnectExisting = { ConnectionState(ConnectionPhase.DISCONNECTING, generation = 7) },
        )
        bridge.connect(BleTransportTarget("private-handle", "LOOI"), 3, BleTransportObserver(events::add))
        bridge.onExistingStateChanged(ConnectionState(ConnectionPhase.CONNECTED, 7))
        bridge.onExistingStateChanged(ConnectionState(ConnectionPhase.TRANSPORT_READY, 7))

        assertEquals("private-handle", target?.deviceId)
        assertEquals(listOf(3L, 3L, 3L), events.map { it.generation })
        assertTrue(events[0] is BleTransportEvent.Connecting)
        assertTrue(events[1] is BleTransportEvent.Connected)
        assertTrue(events[2] is BleTransportEvent.Ready)
    }

    @Test
    fun `stale generation is ignored and disconnect cleanup is correlated once`() {
        val events = mutableListOf<BleTransportEvent>()
        var disconnects = 0
        val bridge = ServiceOwnedLooiBleBridge(
            connectExisting = { ConnectionState(ConnectionPhase.CONNECTING, 2, it) },
            disconnectExisting = {
                disconnects++
                ConnectionState(ConnectionPhase.DISCONNECTING, 2)
            },
        )
        bridge.connect(BleTransportTarget("private-handle"), 4, BleTransportObserver(events::add))
        bridge.onExistingStateChanged(ConnectionState(ConnectionPhase.CONNECTED, 1))
        bridge.disconnect(3)
        bridge.disconnect(4)
        bridge.onExistingStateChanged(ConnectionState(ConnectionPhase.DISCONNECTED, 2))
        bridge.onExistingStateChanged(ConnectionState(ConnectionPhase.DISCONNECTED, 2))

        assertEquals(1, disconnects)
        assertEquals(2, events.size)
        assertTrue(events.last() is BleTransportEvent.Disconnected)
    }

    @Test
    fun `existing typed failure maps without private detail`() {
        val events = mutableListOf<BleTransportEvent>()
        val bridge = ServiceOwnedLooiBleBridge(
            connectExisting = { ConnectionState(ConnectionPhase.CONNECTING, 9, it) },
            disconnectExisting = { ConnectionState(ConnectionPhase.DISCONNECTED, 9) },
        )
        bridge.connect(BleTransportTarget("private-handle"), 5, BleTransportObserver(events::add))
        bridge.onExistingStateChanged(ConnectionState(
            ConnectionPhase.FAILED,
            9,
            failure = ConnectionFailure.Unknown("private platform detail"),
        ))
        val failure = events.last() as BleTransportEvent.Failure
        assertEquals(5, failure.generation)
        assertEquals(null, failure.failure.platformStatus)
    }
}
