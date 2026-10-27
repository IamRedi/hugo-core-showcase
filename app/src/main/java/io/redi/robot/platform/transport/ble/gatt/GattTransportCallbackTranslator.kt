package io.redi.robot.platform.transport.ble.gatt

import io.redi.robot.body.looi.connection.ConnectionFailure
import io.redi.robot.body.looi.connection.ConnectionEvent
import io.redi.robot.body.looi.connection.ConnectionState
import io.redi.robot.body.looi.connection.ConnectionTarget
import io.redi.robot.body.looi.connection.GattFailureKind
import io.redi.robot.body.looi.connection.GattOperation
import io.redi.robot.body.looi.connection.GattTransportEvent
import io.redi.robot.body.looi.connection.GattTransportObserver
import io.redi.robot.body.looi.connection.mapGattStatusToFailure

private const val GATT_SUCCESS = 0x00
private const val PROFILE_DISCONNECTED = 0
private const val PROFILE_CONNECTING = 1
private const val PROFILE_CONNECTED = 2
private const val PROFILE_DISCONNECTING = 3

internal class GattTransportCallbackTranslator(
    private val generation: Long,
) {
    private var isClosed = false
    private var connectedDispatched = false
    private var connectingDispatched = false
    private var disconnectedDispatched = false
    private var readyDispatched = false

    fun onConnectionStateChange(status: Int, newState: Int): GattTransportEvent? {
        if (isClosed) return null
        if (status != GATT_SUCCESS) {
            isClosed = true
            return GattTransportEvent.Failure(
                generation = generation,
                failure = mapGattStatusToFailure(
                    status,
                    GattOperation.CONNECT,
                    "Connection callback failed",
                ),
            )
        }

        return when (newState) {
            PROFILE_CONNECTING -> {
                if (connectingDispatched) null else {
                    connectingDispatched = true
                    GattTransportEvent.Connecting(generation)
                }
            }
            PROFILE_CONNECTED -> {
                if (connectedDispatched) null else {
                    connectedDispatched = true
                    GattTransportEvent.Connected(generation)
                }
            }
            PROFILE_DISCONNECTED -> {
                if (disconnectedDispatched) null else {
                    isClosed = true
                    disconnectedDispatched = true
                    GattTransportEvent.Disconnected(generation)
                }
            }
            PROFILE_DISCONNECTING -> null
            else -> null
        }
    }

    fun onServicesDiscovered(status: Int): GattTransportEvent? {
        if (isClosed) return null
        if (status != GATT_SUCCESS) {
            isClosed = true
            return GattTransportEvent.Failure(
                generation = generation,
                failure = mapGattStatusToFailure(
                    status,
                    GattOperation.READY_WAIT,
                    "Service discovery failed",
                ),
            )
        }

        if (readyDispatched) return null
        readyDispatched = true
        return GattTransportEvent.Ready(generation)
    }

    fun close() {
        isClosed = true
    }
}
