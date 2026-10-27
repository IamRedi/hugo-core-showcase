package io.redi.robot.body.looi.connection

/** Pure reducer for connection lifecycle transitions. */
object ConnectionStateMachine {
    private val activeAttemptPhases = setOf(
        ConnectionPhase.CONNECTING,
        ConnectionPhase.CONNECTED,
        ConnectionPhase.TRANSPORT_READY,
        ConnectionPhase.DISCONNECTING,
    )

    fun initialState(): ConnectionState = ConnectionState()

    fun reduce(state: ConnectionState, event: ConnectionEvent): ConnectionState {
        return when (event) {
            is ConnectionEvent.ConnectRequested -> connectRequested(state, event.target)
            is ConnectionEvent.DisconnectRequested -> disconnectRequested(state)
            is ConnectionEvent.TransportConnecting -> transportConnecting(state, event.generation)
            is ConnectionEvent.TransportConnected -> transportConnected(state, event.generation)
            is ConnectionEvent.TransportReady -> transportReady(state, event.generation)
            is ConnectionEvent.TransportDisconnected -> transportDisconnected(state, event.generation)
            is ConnectionEvent.TransportFailure -> transportFailure(state, event.generation, event.failure)
        }
    }

    private fun connectRequested(state: ConnectionState, target: ConnectionTarget): ConnectionState = when (state.phase) {
        ConnectionPhase.IDLE,
        ConnectionPhase.DISCONNECTED,
        ConnectionPhase.FAILED,
        -> state.copy(
            phase = ConnectionPhase.CONNECTING,
            generation = state.generation + 1,
            target = target,
            failure = null,
        )

        ConnectionPhase.CONNECTING,
        ConnectionPhase.CONNECTED,
        ConnectionPhase.TRANSPORT_READY,
        ConnectionPhase.DISCONNECTING,
        -> state
    }

    private fun disconnectRequested(state: ConnectionState): ConnectionState = when (state.phase) {
        ConnectionPhase.CONNECTING,
        ConnectionPhase.CONNECTED,
        ConnectionPhase.TRANSPORT_READY,
        -> state.copy(phase = ConnectionPhase.DISCONNECTING, failure = ConnectionFailure.CancelledByRequest)

        ConnectionPhase.DISCONNECTING,
        ConnectionPhase.DISCONNECTED,
        ConnectionPhase.IDLE,
        ConnectionPhase.FAILED,
        -> state
    }

    private fun transportConnecting(state: ConnectionState, generation: Long): ConnectionState = when {
        !isCurrentGenerationStateEvent(state, generation) -> state
        state.phase == ConnectionPhase.CONNECTING -> state
        else -> state.copy(phase = ConnectionPhase.CONNECTING)
    }

    private fun transportConnected(state: ConnectionState, generation: Long): ConnectionState = when {
        !isCurrentGenerationStateEvent(state, generation) -> state
        state.phase == ConnectionPhase.CONNECTING -> state.copy(phase = ConnectionPhase.CONNECTED, failure = null)
        state.phase == ConnectionPhase.DISCONNECTING -> state
        else -> state
    }

    private fun transportReady(state: ConnectionState, generation: Long): ConnectionState = when {
        !isCurrentGenerationStateEvent(state, generation) -> state
        state.phase == ConnectionPhase.CONNECTED -> state.copy(phase = ConnectionPhase.TRANSPORT_READY, failure = null)
        state.phase == ConnectionPhase.TRANSPORT_READY -> state
        else -> state
    }

    private fun transportDisconnected(state: ConnectionState, generation: Long): ConnectionState = when {
        !isCurrentGenerationStateEvent(state, generation) -> state
        state.phase == ConnectionPhase.DISCONNECTED -> state
        else -> state.copy(
            phase = ConnectionPhase.DISCONNECTED,
            failure = null,
            target = null,
        )
    }

    private fun transportFailure(
        state: ConnectionState,
        generation: Long,
        failure: ConnectionFailure,
    ): ConnectionState = when {
        !isCurrentGenerationStateEvent(state, generation) -> state
        state.phase in setOf(
            ConnectionPhase.CONNECTING,
            ConnectionPhase.CONNECTED,
            ConnectionPhase.TRANSPORT_READY,
            ConnectionPhase.DISCONNECTING,
        ) -> state.copy(phase = ConnectionPhase.FAILED, failure = failure)

        else -> state
    }

    private fun isCurrentGenerationStateEvent(state: ConnectionState, generation: Long): Boolean {
        return state.generation == generation && state.phase in activeAttemptPhases
    }
}
