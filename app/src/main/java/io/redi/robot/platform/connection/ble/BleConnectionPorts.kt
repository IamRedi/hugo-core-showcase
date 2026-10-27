package io.redi.robot.platform.connection.ble

import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.connection.api.ConnectionEndpointReference
import io.redi.robot.connection.api.ConnectionGeneration
import io.redi.robot.connection.api.ConnectionRequestId
import io.redi.robot.connection.api.ConnectionSessionId
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

/** Platform-local target. Its handle may contain private transport data and must never enter H013 diagnostics. */
data class BleTransportTarget(
    val localTransportHandle: String,
    val displayName: String? = null,
)

sealed interface BleEndpointResolution {
    data class Resolved(val target: BleTransportTarget) : BleEndpointResolution
    data object Unavailable : BleEndpointResolution
    data object Rejected : BleEndpointResolution
}

/** Resolves an H013 opaque reference inside the protected BLE platform boundary. */
fun interface BleEndpointResolver {
    fun resolve(endpoint: ConnectionEndpointReference): BleEndpointResolution
}

enum class BleTransportAvailability {
    AVAILABLE,
    BLUETOOTH_DISABLED,
    PERMISSION_UNAVAILABLE,
    TRANSPORT_UNAVAILABLE,
}

fun interface BleTransportAvailabilityProbe {
    fun current(): BleTransportAvailability
}

enum class BleTransportOperation {
    CONNECT,
    READY_WAIT,
    DISCONNECT,
    UNKNOWN,
}

enum class BleTransportFailureKind {
    TIMEOUT,
    PERMISSION_UNAVAILABLE,
    BLUETOOTH_DISABLED,
    ENDPOINT_UNAVAILABLE,
    LINK_LOST,
    UNSUPPORTED,
    CANCELLED,
    TRANSPORT_FAILURE,
    UNKNOWN,
}

/** Redacted platform evidence. Numeric status is retained; raw addresses, messages, and causes are not. */
data class BleTransportFailure(
    val kind: BleTransportFailureKind,
    val operation: BleTransportOperation = BleTransportOperation.UNKNOWN,
    val platformStatus: Int? = null,
)

sealed interface BleTransportEvent {
    val generation: Long

    data class Connecting(override val generation: Long) : BleTransportEvent
    data class Connected(override val generation: Long) : BleTransportEvent
    data class Ready(override val generation: Long) : BleTransportEvent
    data class Disconnected(override val generation: Long) : BleTransportEvent
    data class Failure(
        override val generation: Long,
        val failure: BleTransportFailure,
    ) : BleTransportEvent
}

fun interface BleTransportObserver {
    fun onEvent(event: BleTransportEvent)
}

/** Narrow bridge to the already-verified BLE/GATT transport; it does not define another BLE stack. */
interface BleTransportConnection {
    fun connect(target: BleTransportTarget, generation: Long, observer: BleTransportObserver)

    fun disconnect(generation: Long)
}

fun interface BleMonotonicClock {
    fun now(): MonotonicTimestampMs
}

fun interface BleConnectionDeadline {
    fun cancel()
}

fun interface BleConnectionDeadlineScheduler {
    fun schedule(
        requestId: ConnectionRequestId,
        generation: ConnectionGeneration,
        deadline: MonotonicTimestampMs,
        onElapsed: () -> Unit,
    ): BleConnectionDeadline
}

/** Deadline scheduler over the same injected monotonic clock used by H013 metadata. */
class TimerBleConnectionDeadlineScheduler(
    private val clock: BleMonotonicClock,
) : BleConnectionDeadlineScheduler {
    override fun schedule(
        requestId: ConnectionRequestId,
        generation: ConnectionGeneration,
        deadline: MonotonicTimestampMs,
        onElapsed: () -> Unit,
    ): BleConnectionDeadline {
        val timer = Timer("hugo-ble-deadline-${generation.value}", true)
        val cancelled = AtomicBoolean(false)
        val task = object : TimerTask() {
            override fun run() {
                if (cancelled.compareAndSet(false, true)) onElapsed()
                timer.cancel()
            }
        }
        timer.schedule(task, (deadline.value - clock.now().value).coerceAtLeast(0L))
        return BleConnectionDeadline {
            if (cancelled.compareAndSet(false, true)) {
                task.cancel()
                timer.cancel()
            }
        }
    }
}

fun interface BleConnectionSessionIdFactory {
    fun create(requestId: ConnectionRequestId, generation: ConnectionGeneration): ConnectionSessionId
}
