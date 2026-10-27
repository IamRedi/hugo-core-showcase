package io.redi.robot.body.looi.connection

/** Read-only transport observations emitted by a platform GATT adapter for diagnostics. */
fun interface GattTransportDiagnosticsObserver {
    fun onDiagnosticEvent(event: GattTransportDiagnosticEvent)
}

/** Immutable, project-owned notification copied at the Android callback boundary. */
data class GattNotificationEnvelope(
    val generation: Long,
    val serviceUuid: String,
    val characteristicUuid: String,
    val payload: List<Int>,
)

fun interface GattNotificationObserver {
    fun onNotification(notification: GattNotificationEnvelope)
}

sealed interface GattTransportDiagnosticEvent {
    val generation: Long

    data class ConnectionStateChanged(
        override val generation: Long,
        val status: Int,
        val androidState: Int,
    ) : GattTransportDiagnosticEvent

    data class ServiceDiscoveryStarted(
        override val generation: Long,
    ) : GattTransportDiagnosticEvent

    data class ServicesDiscovered(
        override val generation: Long,
        val status: Int,
        val services: List<GattServiceInventory>,
    ) : GattTransportDiagnosticEvent

    data class GattClosed(
        override val generation: Long,
        val reason: String,
    ) : GattTransportDiagnosticEvent
}

/** Project-owned, read-only GATT inventory. No Android Bluetooth type crosses this boundary. */
data class GattServiceInventory(
    val uuid: String,
    val characteristics: List<GattCharacteristicInventory>,
)

data class GattCharacteristicInventory(
    val uuid: String,
)
