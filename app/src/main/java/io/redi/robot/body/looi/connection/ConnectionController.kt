package io.redi.robot.body.looi.connection

interface GattTransportAdapter {
    fun connect(target: ConnectionTarget, generation: Long, callback: GattTransportObserver)
    fun disconnect(generation: Long)
}

interface GattTransportObserver {
    fun onTransportEvent(event: GattTransportEvent)
}

sealed interface GattTransportEvent {
    data class Connecting(val generation: Long) : GattTransportEvent
    data class Connected(val generation: Long) : GattTransportEvent
    data class Ready(val generation: Long) : GattTransportEvent
    data class Disconnected(val generation: Long) : GattTransportEvent
    data class Failure(val generation: Long, val failure: ConnectionFailure) : GattTransportEvent
}

/**
 * Connection coordinator used by transport tasks and tests.
 * Android types remain outside of this package.
 */
class ConnectionController(
    private val transport: GattTransportAdapter,
) {
    var state: ConnectionState = ConnectionStateMachine.initialState()
        private set

    private val observer = object : GattTransportObserver {
        override fun onTransportEvent(event: GattTransportEvent) {
            state = ConnectionStateMachine.reduce(state, event.toConnectionEvent())
        }
    }

    fun requestConnect(target: ConnectionTarget): ConnectionState {
        val requested = ConnectionStateMachine.reduce(state, ConnectionEvent.ConnectRequested(target))
        val shouldStartTransport = state.phase != ConnectionPhase.CONNECTING && requested.phase == ConnectionPhase.CONNECTING
        state = requested
        if (shouldStartTransport) {
            transport.connect(target, state.generation, observer)
        }
        return state
    }

    fun requestDisconnect(): ConnectionState {
        val requested = ConnectionStateMachine.reduce(state, ConnectionEvent.DisconnectRequested)
        val shouldStopTransport = state.phase != ConnectionPhase.DISCONNECTING && requested.phase == ConnectionPhase.DISCONNECTING
        state = requested
        if (shouldStopTransport) {
            transport.disconnect(state.generation)
        }
        return state
    }

    fun onTransportEvent(event: GattTransportEvent) {
        state = ConnectionStateMachine.reduce(state, event.toConnectionEvent())
    }
}

private fun GattTransportEvent.toConnectionEvent(): ConnectionEvent = when (this) {
    is GattTransportEvent.Connecting -> ConnectionEvent.TransportConnecting(this.generation)
    is GattTransportEvent.Connected -> ConnectionEvent.TransportConnected(this.generation)
    is GattTransportEvent.Ready -> ConnectionEvent.TransportReady(this.generation)
    is GattTransportEvent.Disconnected -> ConnectionEvent.TransportDisconnected(this.generation)
    is GattTransportEvent.Failure -> ConnectionEvent.TransportFailure(this.generation, this.failure)
}
