package io.redi.robot.platform.transport.ble.gatt

import io.redi.robot.body.looi.connection.ConnectionFailure
import io.redi.robot.body.looi.connection.ConnectionTarget
import io.redi.robot.body.looi.connection.GattFailureKind
import io.redi.robot.body.looi.connection.GattOperation
import io.redi.robot.body.looi.connection.GattTransportAdapter
import io.redi.robot.body.looi.connection.GattTransportEvent
import io.redi.robot.body.looi.connection.GattTransportObserver
import io.redi.robot.platform.connection.ble.BleTransportConnection
import io.redi.robot.platform.connection.ble.BleTransportEvent
import io.redi.robot.platform.connection.ble.BleTransportFailure
import io.redi.robot.platform.connection.ble.BleTransportFailureKind
import io.redi.robot.platform.connection.ble.BleTransportObserver
import io.redi.robot.platform.connection.ble.BleTransportOperation
import io.redi.robot.platform.connection.ble.BleTransportTarget

/**
 * Vocabulary bridge only. The delegate remains the sole owner of Android scanning, GATT,
 * generations, service discovery, callback filtering, disconnect, and close behavior.
 */
class ExistingGattConnectionBridge(
    private val delegate: GattTransportAdapter,
) : BleTransportConnection {
    override fun connect(target: BleTransportTarget, generation: Long, observer: BleTransportObserver) {
        delegate.connect(
            ConnectionTarget(target.localTransportHandle, target.displayName),
            generation,
            object : GattTransportObserver {
                override fun onTransportEvent(event: GattTransportEvent) {
                    observer.onEvent(event.toBleEvent())
                }
            },
        )
    }

    override fun disconnect(generation: Long) = delegate.disconnect(generation)
}

private fun GattTransportEvent.toBleEvent(): BleTransportEvent = when (this) {
    is GattTransportEvent.Connecting -> BleTransportEvent.Connecting(generation)
    is GattTransportEvent.Connected -> BleTransportEvent.Connected(generation)
    is GattTransportEvent.Ready -> BleTransportEvent.Ready(generation)
    is GattTransportEvent.Disconnected -> BleTransportEvent.Disconnected(generation)
    is GattTransportEvent.Failure -> BleTransportEvent.Failure(generation, failure.toBleFailure())
}

private fun ConnectionFailure.toBleFailure(): BleTransportFailure = when (this) {
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
    is ConnectionFailure.Unknown -> BleTransportFailure(
        kind = message.toKnownFailureKind(),
        operation = BleTransportOperation.UNKNOWN,
    )
}

private fun GattOperation.toBleOperation(): BleTransportOperation = when (this) {
    GattOperation.CONNECT -> BleTransportOperation.CONNECT
    GattOperation.READY_WAIT -> BleTransportOperation.READY_WAIT
    GattOperation.DISCONNECT -> BleTransportOperation.DISCONNECT
}

/** Existing failures predate H013; classification retains type/status while discarding private free text. */
private fun String.toKnownFailureKind(): BleTransportFailureKind {
    val normalized = lowercase()
    return when {
        "permission" in normalized || "securityexception" in normalized ->
            BleTransportFailureKind.PERMISSION_UNAVAILABLE
        "bluetooth is disabled" in normalized -> BleTransportFailureKind.BLUETOOTH_DISABLED
        "invalid ble address" in normalized || "resolve target device" in normalized ->
            BleTransportFailureKind.ENDPOINT_UNAVAILABLE
        "manager unavailable" in normalized || "adapter unavailable" in normalized ->
            BleTransportFailureKind.TRANSPORT_FAILURE
        else -> BleTransportFailureKind.TRANSPORT_FAILURE
    }
}
