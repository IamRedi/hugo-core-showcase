package io.redi.robot.body.looi.connection

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionControllerTest {
    @Test fun `connect lifecycle emits expected transport-ready and disconnected progression`() {
        val transport = ControlledFakeTransport()
        val controller = ConnectionController(transport)
        val target = ConnectionTarget("target-a", "Looi-One")

        val afterRequest = controller.requestConnect(target)
        assertEquals(ConnectionPhase.CONNECTING, afterRequest.phase)
        assertEquals(1L, afterRequest.generation)
        assertEquals(1, transport.connectCalls)
        assertEquals(target, transport.lastConnectTarget)
        assertEquals(1L, transport.lastConnectGeneration)

        controller.onTransportEvent(GattTransportEvent.Connecting(1L))
        assertEquals(ConnectionPhase.CONNECTING, controller.state.phase)

        controller.onTransportEvent(GattTransportEvent.Connected(1L))
        assertEquals(ConnectionPhase.CONNECTED, controller.state.phase)

        controller.onTransportEvent(GattTransportEvent.Ready(1L))
        assertEquals(ConnectionPhase.TRANSPORT_READY, controller.state.phase)

        val afterStop = controller.requestDisconnect()
        assertEquals(ConnectionPhase.DISCONNECTING, afterStop.phase)
        assertEquals(ConnectionFailure.CancelledByRequest, controller.state.failure)
        assertEquals(1, transport.disconnectCalls)

        controller.requestDisconnect()
        assertEquals(ConnectionPhase.DISCONNECTING, controller.state.phase)
        assertEquals(1, transport.disconnectCalls)

        controller.onTransportEvent(GattTransportEvent.Disconnected(1L))
        assertEquals(ConnectionPhase.DISCONNECTED, controller.state.phase)
        assertEquals(null, controller.state.failure)
    }

    @Test fun `disconnect during connect ignores late connected callback and remains deterministic`() {
        val transport = ControlledFakeTransport()
        val controller = ConnectionController(transport)
        val target = ConnectionTarget("target-b")

        controller.requestConnect(target)
        assertEquals(ConnectionPhase.CONNECTING, controller.state.phase)

        val stopped = controller.requestDisconnect()
        assertEquals(ConnectionPhase.DISCONNECTING, stopped.phase)

        controller.onTransportEvent(GattTransportEvent.Connected(1L))
        assertEquals(ConnectionPhase.DISCONNECTING, controller.state.phase)

        controller.onTransportEvent(GattTransportEvent.Disconnected(1L))
        assertEquals(ConnectionPhase.DISCONNECTED, controller.state.phase)
        assertEquals(1, transport.disconnectCalls)
    }

    @Test fun `stale generation callback cannot reach ready`() {
        val transport = ControlledFakeTransport()
        val controller = ConnectionController(transport)
        val first = ConnectionTarget("target-c")
        val second = ConnectionTarget("target-d")

        controller.requestConnect(first)
        controller.onTransportEvent(GattTransportEvent.Connected(1L))
        assertEquals(ConnectionPhase.CONNECTED, controller.state.phase)
        controller.requestDisconnect()
        controller.onTransportEvent(GattTransportEvent.Disconnected(1L))
        assertEquals(ConnectionPhase.DISCONNECTED, controller.state.phase)

        val secondConnect = controller.requestConnect(second)
        assertEquals(ConnectionPhase.CONNECTING, secondConnect.phase)
        assertEquals(2L, secondConnect.generation)
        assertEquals(2, transport.connectCalls)

        controller.onTransportEvent(GattTransportEvent.Ready(1L))
        assertEquals("old generation must not advance transport readiness", ConnectionPhase.CONNECTING, controller.state.phase)

        controller.onTransportEvent(GattTransportEvent.Connected(2L))
        controller.onTransportEvent(GattTransportEvent.Ready(2L))
        assertEquals(ConnectionPhase.TRANSPORT_READY, controller.state.phase)
    }

    @Test fun `transport failure maps status into typed failure and enters failed state`() {
        val transport = ControlledFakeTransport()
        val controller = ConnectionController(transport)
        val target = ConnectionTarget("target-e")

        controller.requestConnect(target)
        val failure = mapGattStatusToFailure(0x08, GattOperation.CONNECT)
        controller.onTransportEvent(GattTransportEvent.Failure(1L, failure))

        assertEquals(ConnectionPhase.FAILED, controller.state.phase)
        assertEquals(failure, controller.state.failure)
    }

    @Test fun `invalid transition requests are safely ignored`() {
        val state = ConnectionStateMachine.initialState()
        val ignoredDisconnect = ConnectionStateMachine.reduce(state, ConnectionEvent.DisconnectRequested)
        assertEquals(ConnectionPhase.IDLE, ignoredDisconnect.phase)

        val connectedState = ConnectionStateMachine.reduce(
            ConnectionState(phase = ConnectionPhase.DISCONNECTED, generation = 7),
            ConnectionEvent.TransportConnected(7L),
        )
        assertEquals(ConnectionPhase.DISCONNECTED, connectedState.phase)
    }

    @Test fun `repeated transport callbacks are idempotent`() {
        val transport = ControlledFakeTransport()
        val controller = ConnectionController(transport)

        controller.requestConnect(ConnectionTarget("target-f"))
        controller.onTransportEvent(GattTransportEvent.Connected(1L))
        controller.onTransportEvent(GattTransportEvent.Connected(1L))
        assertEquals(ConnectionPhase.CONNECTED, controller.state.phase)

        controller.onTransportEvent(GattTransportEvent.Disconnected(1L))
        controller.onTransportEvent(GattTransportEvent.Disconnected(1L))
        assertEquals(ConnectionPhase.DISCONNECTED, controller.state.phase)
    }

    @Test fun `failure mapping remains typed for unsupported gatt status`() {
        val failure = mapGattStatusToFailure(0x99, GattOperation.READY_WAIT, "custom failure")
        assertEquals(ConnectionFailure.GattFailure::class, failure::class)
    }
}

private class ControlledFakeTransport : GattTransportAdapter {
    var connectCalls = 0
        private set
    var disconnectCalls = 0
        private set
    var lastConnectGeneration: Long = 0L
        private set
    var lastConnectTarget: ConnectionTarget? = null
        private set

    override fun connect(target: ConnectionTarget, generation: Long, callback: GattTransportObserver) {
        connectCalls++
        lastConnectGeneration = generation
        lastConnectTarget = target
    }

    override fun disconnect(generation: Long) {
        disconnectCalls++
    }
}
