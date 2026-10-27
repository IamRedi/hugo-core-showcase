package io.redi.robot.platform.service

import io.redi.robot.body.looi.connection.ConnectionPhase
import io.redi.robot.body.looi.connection.ConnectionTarget
import io.redi.robot.body.looi.connection.GattTransportAdapter
import io.redi.robot.body.looi.connection.GattTransportEvent
import io.redi.robot.body.looi.connection.GattTransportObserver
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectedDeviceConnectionOwnerTest {
    @Test
    fun `owner holds one lifecycle across client recreation and publishes callbacks`() {
        val transport = FakeTransport()
        val snapshots = mutableListOf<ConnectionPhase>()
        val owner = ConnectedDeviceConnectionOwner(transport) { snapshots += it.phase }

        owner.requestConnect(ConnectionTarget("candidate"))
        transport.emit(GattTransportEvent.Connected(1))

        assertEquals(ConnectionPhase.CONNECTED, owner.state.phase)
        assertEquals(listOf(ConnectionPhase.CONNECTING, ConnectionPhase.CONNECTED), snapshots)
    }

    @Test
    fun `shutdown disconnects once and never persists a reconnect target`() {
        val transport = FakeTransport()
        val owner = ConnectedDeviceConnectionOwner(transport)
        owner.requestConnect(ConnectionTarget("candidate"))

        owner.shutdown()
        owner.shutdown()

        assertEquals(1, transport.disconnectCalls)
        assertEquals(ConnectionPhase.DISCONNECTING, owner.state.phase)
    }

    private class FakeTransport : GattTransportAdapter {
        lateinit var observer: GattTransportObserver
        var disconnectCalls = 0
        override fun connect(target: ConnectionTarget, generation: Long, callback: GattTransportObserver) { observer = callback }
        override fun disconnect(generation: Long) { disconnectCalls++ }
        fun emit(event: GattTransportEvent) = observer.onTransportEvent(event)
    }
}
