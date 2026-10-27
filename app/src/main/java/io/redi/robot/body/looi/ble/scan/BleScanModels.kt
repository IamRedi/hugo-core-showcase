package io.redi.robot.body.looi.ble.scan

import java.util.concurrent.atomic.AtomicBoolean

const val DEFAULT_SCAN_TIMEOUT_MILLIS = 10_000L

data class BleScanResult(
    val id: String,
    val displayName: String?,
    val rssi: Int?,
)

data class BleScanConfig(
    val timeoutMillis: Long = DEFAULT_SCAN_TIMEOUT_MILLIS,
    val allowDuplicateObservations: Boolean = false,
)

sealed interface BleScanFailure {
    data class Timeout(val timeoutMillis: Long) : BleScanFailure
    data object PermissionUnavailable : BleScanFailure
    data object BluetoothDisabled : BleScanFailure
    data object ScannerUnavailable : BleScanFailure
    data object ScanInProgress : BleScanFailure
    data class Unknown(val message: String, val cause: Throwable?) : BleScanFailure
}

enum class BleScanCompletedReason {
    REQUESTED_STOP,
    TIMEOUT,
    FAILED,
}

sealed interface BleScanEvent {
    data class DeviceFound(val result: BleScanResult) : BleScanEvent

    data class Failure(val cause: BleScanFailure) : BleScanEvent

    data class Completed(val reason: BleScanCompletedReason) : BleScanEvent
}

fun interface BleScanListener {
    fun onEvent(event: BleScanEvent)
}

interface BleScanSession {
    fun stop()
    val isActive: Boolean
}

interface BleScanner {
    fun startScan(config: BleScanConfig, listener: BleScanListener): BleScanSession
}

data class DuplicatePolicyState(
    val allowDuplicates: Boolean,
) {
    private val observedIds: MutableSet<String> = HashSet()

    fun shouldEmitFor(result: BleScanResult): Boolean {
        if (allowDuplicates) return true
        return observedIds.add(result.id)
    }

    fun clear() {
        observedIds.clear()
    }
}

class MutableBleScanState {
    private val active = AtomicBoolean(false)

    fun markActive() = active.compareAndSet(false, true)
    fun markStopped() = active.compareAndSet(true, false)
    fun isActive(): Boolean = active.get()
}
