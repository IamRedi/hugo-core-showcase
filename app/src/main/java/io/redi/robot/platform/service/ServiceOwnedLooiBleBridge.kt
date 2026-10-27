package io.redi.robot.platform.service

import io.redi.robot.body.looi.connection.ConnectionFailure
import io.redi.robot.body.looi.connection.ConnectionPhase
import io.redi.robot.body.looi.connection.ConnectionState
import io.redi.robot.body.looi.connection.ConnectionTarget
import io.redi.robot.body.looi.connection.GattFailureKind
import io.redi.robot.body.looi.connection.GattOperation
import io.redi.robot.platform.connection.ble.BleTransportConnection
import io.redi.robot.platform.connection.ble.BleTransportEvent
import io.redi.robot.platform.connection.ble.BleTransportFailure
import io.redi.robot.platform.connection.ble.BleTransportFailureKind
import io.redi.robot.platform.connection.ble.BleTransportObserver
import io.redi.robot.platform.connection.ble.BleTransportOperation
import io.redi.robot.platform.connection.ble.BleTransportTarget

/**
 * H028 bridge into the service-owned lifecycle. It owns no scanner, GATT object, protocol state, or
 * execution authority. Generic and legacy generations are correlated explicitly and stale service
 * updates are ignored.
 */
class ServiceOwnedLooiBleBridge(
    private val connectExisting: (ConnectionTarget) -> ConnectionState,
    private val disconnectExisting: () -> ConnectionState,
) : BleTransportConnection {
    private val lock = Any()
    private var active: Correlation? = null

    override fun connect(target: BleTransportTarget, generation: Long, observer: BleTransportObserver) {
        val requested = connectExisting(ConnectionTarget(target.localTransportHandle, target.displayName))
        val correlation = Correlation(generation, requested.generation, observer)
        synchronized(lock) { active = correlation }
        publish(correlation, requested)
    }

    override fun disconnect(generation: Long) {
        val correlation = synchronized(lock) { active?.takeIf { it.genericGeneration == generation } } ?: return
        publish(correlation, disconnectExisting())
    }

    fun onExistingStateChanged(state: ConnectionState) {
        val correlation = synchronized(lock) { active?.takeIf { it.existingGeneration == state.generation } } ?: return
        publish(correlation, state)
    }

    private fun publish(correlation: Correlation, state: ConnectionState) {
        val event = synchronized(lock) {
            if (active !== correlation || state.generation != correlation.existingGeneration ||
                state.phase == correlation.lastPhase
            ) return
            correlation.lastPhase = state.phase
            when (state.phase) {
                ConnectionPhase.CONNECTING -> BleTransportEvent.Connecting(correlation.genericGeneration)
                ConnectionPhase.CONNECTED -> BleTransportEvent.Connected(correlation.genericGeneration)
                ConnectionPhase.TRANSPORT_READY -> BleTransportEvent.Ready(correlation.genericGeneration)
                ConnectionPhase.DISCONNECTED -> BleTransportEvent.Disconnected(correlation.genericGeneration)
                ConnectionPhase.FAILED -> BleTransportEvent.Failure(
                    correlation.genericGeneration,
                    state.failure.toBleFailure(),
                )
                ConnectionPhase.IDLE, ConnectionPhase.DISCONNECTING -> null
            }
        }
        if (event != null) correlation.observer.onEvent(event)
        if (event is BleTransportEvent.Disconnected || event is BleTransportEvent.Failure) {
            synchronized(lock) { if (active === correlation) active = null }
        }
    }

    private data class Correlation(
        val genericGeneration: Long,
        val existingGeneration: Long,
        val observer: BleTransportObserver,
        var lastPhase: ConnectionPhase? = null,
    )
}

private fun ConnectionFailure?.toBleFailure(): BleTransportFailure = when (this) {
    ConnectionFailure.CancelledByRequest -> BleTransportFailure(BleTransportFailureKind.CANCELLED)
    is ConnectionFailure.GattFailure -> BleTransportFailure(
        kind = when (kind) {
            GattFailureKind.TIMEOUT -> BleTransportFailureKind.TIMEOUT
            GattFailureKind.AUTH_REQUIRED -> BleTransportFailureKind.PERMISSION_UNAVAILABLE
            GattFailureKind.LINK_LOST -> BleTransportFailureKind.LINK_LOST
            GattFailureKind.UNSUPPORTED -> BleTransportFailureKind.UNSUPPORTED
            GattFailureKind.UNKNOWN -> BleTransportFailureKind.TRANSPORT_FAILURE
        },
        operation = operation.toBleOperation(),
        platformStatus = status,
    )
    is ConnectionFailure.Unknown, null -> BleTransportFailure(BleTransportFailureKind.TRANSPORT_FAILURE)
}

private fun GattOperation.toBleOperation(): BleTransportOperation = when (this) {
    GattOperation.CONNECT -> BleTransportOperation.CONNECT
    GattOperation.READY_WAIT -> BleTransportOperation.READY_WAIT
    GattOperation.DISCONNECT -> BleTransportOperation.DISCONNECT
}
