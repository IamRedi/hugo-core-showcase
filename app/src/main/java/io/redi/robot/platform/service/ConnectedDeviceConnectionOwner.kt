package io.redi.robot.platform.service

import io.redi.robot.body.looi.connection.ConnectionController
import io.redi.robot.body.looi.connection.ConnectionPhase
import io.redi.robot.body.looi.connection.ConnectionState
import io.redi.robot.body.looi.connection.ConnectionTarget
import io.redi.robot.body.looi.connection.GattTransportAdapter
import io.redi.robot.body.looi.connection.GattTransportEvent
import io.redi.robot.body.looi.connection.GattTransportObserver

/** Service-owned connection lifecycle. It contains no scan, protocol, action, or lease state. */
class ConnectedDeviceConnectionOwner(
    private val delegate: GattTransportAdapter,
    private val onStateChanged: (ConnectionState) -> Unit = {},
) {
    private val transport = object : GattTransportAdapter {
        override fun connect(target: ConnectionTarget, generation: Long, callback: GattTransportObserver) {
            delegate.connect(target, generation, object : GattTransportObserver {
                override fun onTransportEvent(event: GattTransportEvent) {
                    callback.onTransportEvent(event)
                    publish()
                }
            })
        }

        override fun disconnect(generation: Long) = delegate.disconnect(generation)
    }
    private val controller: ConnectionController = ConnectionController(transport)

    val state: ConnectionState get() = controller.state

    fun requestConnect(target: ConnectionTarget): ConnectionState {
        val state = controller.requestConnect(target)
        publish()
        return state
    }

    fun requestDisconnect(): ConnectionState {
        val state = controller.requestDisconnect()
        publish()
        return state
    }

    /** Idempotent shutdown path: revoke the active transport session and never persist its target. */
    fun shutdown(): ConnectionState {
        if (controller.state.phase in setOf(ConnectionPhase.CONNECTING, ConnectionPhase.CONNECTED, ConnectionPhase.TRANSPORT_READY)) {
            controller.requestDisconnect()
        }
        publish()
        return controller.state
    }

    private fun publish() = onStateChanged(controller.state)
}
