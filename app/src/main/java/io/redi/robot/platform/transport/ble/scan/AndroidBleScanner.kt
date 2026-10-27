package io.redi.robot.platform.transport.ble.scan

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.redi.robot.body.looi.ble.scan.BleScanCompletedReason
import io.redi.robot.body.looi.ble.scan.BleScanConfig
import io.redi.robot.body.looi.ble.scan.BleScanEvent
import io.redi.robot.body.looi.ble.scan.BleScanFailure
import io.redi.robot.body.looi.ble.scan.BleScanListener
import io.redi.robot.body.looi.ble.scan.BleScanResult
import io.redi.robot.body.looi.ble.scan.BleScanSession
import io.redi.robot.body.looi.ble.scan.BleScanner
import io.redi.robot.body.looi.ble.scan.DuplicatePolicyState
import io.redi.robot.body.looi.ble.scan.MutableBleScanState
import java.util.Timer
import java.util.TimerTask

class AndroidBleScanner(
    private val context: Context,
    @Suppress("InlinedApi")
    private val scanRequestPermissionCheck: () -> Boolean = {
        bleScanPermissionGranted(
            sdkInt = Build.VERSION.SDK_INT,
            bluetoothScanGranted = context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED,
            legacyFineLocationGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )
    },
    private val bluetoothEnabledCheck: () -> Boolean = {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        adapter?.isEnabled == true
    },
    private val bleScannerProvider: () -> BluetoothLeScanner? = {
        val manager = context.getSystemService(BluetoothManager::class.java)
        manager?.adapter?.bluetoothLeScanner
    },
) : BleScanner {

    private val scanState = MutableBleScanState()

    override fun startScan(config: BleScanConfig, listener: BleScanListener): BleScanSession {
        val duplicates = DuplicatePolicyState(config.allowDuplicateObservations)

        if (!scanState.markActive()) {
            listener.onEvent(BleScanEvent.Failure(BleScanFailure.ScanInProgress))
            listener.onEvent(BleScanEvent.Completed(BleScanCompletedReason.FAILED))
            return StopOnlyScanSession()
        }

        if (!scanRequestPermissionCheck()) {
            listener.onEvent(BleScanEvent.Failure(BleScanFailure.PermissionUnavailable))
            listener.onEvent(BleScanEvent.Completed(BleScanCompletedReason.FAILED))
            scanState.markStopped()
            return StopOnlyScanSession()
        }

        if (!bluetoothEnabledCheck()) {
            listener.onEvent(BleScanEvent.Failure(BleScanFailure.BluetoothDisabled))
            listener.onEvent(BleScanEvent.Completed(BleScanCompletedReason.FAILED))
            scanState.markStopped()
            return StopOnlyScanSession()
        }

        val scanner = bleScannerProvider() ?: run {
            listener.onEvent(BleScanEvent.Failure(BleScanFailure.ScannerUnavailable))
            listener.onEvent(BleScanEvent.Completed(BleScanCompletedReason.FAILED))
            scanState.markStopped()
            return StopOnlyScanSession()
        }

        lateinit var callback: ScanCallback
        callback = createCallback(
            duplicates,
            listener,
            scanState,
            scanner,
            onFailure = { failure ->
                if (scanState.markStopped()) {
                    stopScanSafely(scanner, callback)
                    listener.onEvent(BleScanEvent.Failure(failure))
                    listener.onEvent(BleScanEvent.Completed(BleScanCompletedReason.FAILED))
                }
            },
        )

        return runCatching {
            startScanSafely(scanner, callback)
            startTimeoutTimer(config.timeoutMillis, listener, scanState, scanner, callback)
        }.getOrElse { error ->
            val failure = when (error) {
                is SecurityException -> BleScanFailure.PermissionUnavailable
                else -> BleScanFailure.Unknown("Unable to start scan", error)
            }
            listener.onEvent(BleScanEvent.Failure(failure))
            listener.onEvent(BleScanEvent.Completed(BleScanCompletedReason.FAILED))
            scanState.markStopped()
            stopScanSafely(scanner, callback)
            return StopOnlyScanSession()
        }.let {
            AndroidScanSession(scanState, listener, stopScan = { stopScanSafely(scanner, callback) })
        }
    }

    private fun createCallback(
        duplicates: DuplicatePolicyState,
        listener: BleScanListener,
        state: MutableBleScanState,
        scanner: BluetoothLeScanner,
        onFailure: (BleScanFailure) -> Unit,
    ): ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!state.isActive()) return
            val mapped = BleScanResult(
                id = scanResultId(result),
                displayName = safeDeviceName(result),
                rssi = result.rssi,
            )
            if (duplicates.shouldEmitFor(mapped)) {
                listener.onEvent(BleScanEvent.DeviceFound(mapped))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            if (!state.isActive()) return
            val failure = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> BleScanFailure.ScanInProgress
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                    BleScanFailure.Unknown("Application registration failed", null)
                SCAN_FAILED_INTERNAL_ERROR ->
                    BleScanFailure.Unknown("Scanner internal error", null)
                SCAN_FAILED_FEATURE_UNSUPPORTED ->
                    BleScanFailure.Unknown("Feature unsupported", null)
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES ->
                    BleScanFailure.Unknown("Out of hardware resources", null)
                SCAN_FAILED_SCANNING_TOO_FREQUENTLY ->
                    BleScanFailure.Unknown("Scanning too frequently", null)
                else ->
                    BleScanFailure.Unknown("Scan failed ($errorCode)", null)
            }
            onFailure(failure)
            stopScanSafely(scanner, this)
        }
    }

    private fun scanResultId(scanResult: ScanResult): String {
        val address = scanResult.device?.address
        if (!address.isNullOrBlank()) return address
        val fallback = safeDeviceName(scanResult)
        if (!fallback.isNullOrBlank()) return "no-address:${fallback}"
        return "no-address:${scanResult.hashCode()}"
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(scanResult: ScanResult): String? = try {
        scanResult.device?.name
    } catch (_: SecurityException) {
        null
    }

    private fun startTimeoutTimer(
        timeoutMillis: Long,
        listener: BleScanListener,
        state: MutableBleScanState,
        scanner: BluetoothLeScanner,
        callback: ScanCallback,
    ): AndroidScanSession {
        val timer = Timer("ble-scan-timeout", true)
        timer.schedule(
            object : TimerTask() {
                override fun run() {
                    if (state.markStopped()) {
                        stopScanSafely(scanner, callback)
                        listener.onEvent(BleScanEvent.Failure(BleScanFailure.Timeout(timeoutMillis)))
                        listener.onEvent(BleScanEvent.Completed(BleScanCompletedReason.TIMEOUT))
                    }
                }
            },
            timeoutMillis,
        )

        return AndroidScanSession(
            state = state,
            listener = listener,
            stopScan = { stopScanSafely(scanner, callback) },
            timer = timer,
        )
    }

    @SuppressLint("MissingPermission")
    private fun startScanSafely(scanner: BluetoothLeScanner, callback: ScanCallback) {
        if (!scanRequestPermissionCheck()) {
            throw SecurityException("Missing scan permission")
        }
        scanner.startScan(callback)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanSafely(scanner: BluetoothLeScanner, callback: ScanCallback) {
        if (!scanRequestPermissionCheck()) return
        try {
            scanner.stopScan(callback)
        } catch (_: SecurityException) {
            // Permission can be revoked while callbacks are running; cleanup attempts should not crash.
        }
    }
}

internal fun bleScanPermissionGranted(
    sdkInt: Int,
    bluetoothScanGranted: Boolean,
    legacyFineLocationGranted: Boolean,
): Boolean = if (sdkInt >= Build.VERSION_CODES.S) bluetoothScanGranted else legacyFineLocationGranted

private class AndroidScanSession(
    private val state: MutableBleScanState,
    private val listener: BleScanListener,
    private val stopScan: () -> Unit,
    private val timer: Timer? = null,
) : BleScanSession {

    private var closed = false

    override fun stop() {
        if (closed) return
        closed = true
        if (state.markStopped()) {
            listener.onEvent(BleScanEvent.Completed(BleScanCompletedReason.REQUESTED_STOP))
            timer?.cancel()
            stopScan()
        }
    }

    override val isActive: Boolean
        get() = state.isActive()
}

private class StopOnlyScanSession : BleScanSession {
    override fun stop() = Unit

    override val isActive: Boolean = false
}
