package io.redi.robot.body.looi.connection

/** Project-owned target model for a BLE candidate. */
data class ConnectionTarget(
    val deviceId: String,
    val displayName: String? = null,
)

/** Transport-independent lifecycle phases. */
enum class ConnectionPhase {
    IDLE,
    CONNECTING,
    CONNECTED,
    TRANSPORT_READY,
    DISCONNECTING,
    DISCONNECTED,
    FAILED,
}

/** Typed transport failure model for connection errors. */
sealed interface ConnectionFailure {
    data class GattFailure(
        val operation: GattOperation,
        val kind: GattFailureKind,
        val status: Int,
        val detail: String,
    ) : ConnectionFailure

    data object CancelledByRequest : ConnectionFailure
    data class Unknown(val message: String, val cause: Throwable? = null) : ConnectionFailure
}

enum class GattOperation {
    CONNECT,
    READY_WAIT,
    DISCONNECT,
}

enum class GattFailureKind {
    TIMEOUT,
    AUTH_REQUIRED,
    LINK_LOST,
    UNSUPPORTED,
    UNKNOWN,
}

/** Maps known BLE/GATT status codes into typed project-owned failures. */
fun mapGattStatusToFailure(status: Int, operation: GattOperation, detail: String? = null): ConnectionFailure {
    return when (status) {
        0x08 -> ConnectionFailure.GattFailure(operation, GattFailureKind.TIMEOUT, status, detail ?: "GATT request timed out")
        0x05 -> ConnectionFailure.GattFailure(
            operation,
            GattFailureKind.AUTH_REQUIRED,
            status,
            detail ?: "BLE auth/permission required",
        )
        0x85 -> ConnectionFailure.GattFailure(
            operation,
            GattFailureKind.LINK_LOST,
            status,
            detail ?: "BLE link appears lost",
        )
        0x80 -> ConnectionFailure.GattFailure(
            operation,
            GattFailureKind.UNSUPPORTED,
            status,
            detail ?: "Operation unsupported by the platform transport",
        )
        else -> ConnectionFailure.GattFailure(
            operation,
            GattFailureKind.UNKNOWN,
            status,
            detail ?: "Unhandled GATT status $status",
        )
    }
}

data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val generation: Long = 0L,
    val target: ConnectionTarget? = null,
    val failure: ConnectionFailure? = null,
)

sealed interface ConnectionEvent {
    data class ConnectRequested(val target: ConnectionTarget) : ConnectionEvent
    data class TransportConnecting(val generation: Long) : ConnectionEvent
    data class TransportConnected(val generation: Long) : ConnectionEvent
    data class TransportReady(val generation: Long) : ConnectionEvent
    data class TransportDisconnected(val generation: Long) : ConnectionEvent
    data class TransportFailure(val generation: Long, val failure: ConnectionFailure) : ConnectionEvent
    data object DisconnectRequested : ConnectionEvent
}
