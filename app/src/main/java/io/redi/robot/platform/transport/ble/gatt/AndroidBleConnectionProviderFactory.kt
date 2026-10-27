package io.redi.robot.platform.transport.ble.gatt

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.connection.api.ConnectionProvider
import io.redi.robot.platform.connection.ble.BleConnectionLifecycleObserver
import io.redi.robot.platform.connection.ble.BleConnectionProvider
import io.redi.robot.platform.connection.ble.BleEndpointResolver
import io.redi.robot.platform.connection.ble.BleMonotonicClock
import io.redi.robot.platform.connection.ble.BleTransportAvailability
import io.redi.robot.platform.connection.ble.BleTransportAvailabilityProbe
import io.redi.robot.platform.connection.ble.TimerBleConnectionDeadlineScheduler

/**
 * Android composition boundary for H028. It returns H013 and shares the exact permission and
 * Bluetooth-enabled checks with the existing [AndroidGattTransportAdapter]. Nothing is installed,
 * trusted, authorized, registered, or connected by constructing it.
 */
class AndroidBleConnectionProviderFactory(
    context: Context,
) {
    private val applicationContext = context.applicationContext

    fun create(
        endpoints: BleEndpointResolver,
        lifecycleObserver: BleConnectionLifecycleObserver = BleConnectionLifecycleObserver {},
    ): ConnectionProvider {
        val managerProvider = {
            applicationContext.getSystemService(BluetoothManager::class.java)
        }
        val permissionCheck = {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                applicationContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        }
        val bluetoothEnabledCheck = {
            runCatching { managerProvider()?.adapter?.isEnabled == true }.getOrDefault(false)
        }
        val availability = BleTransportAvailabilityProbe {
            when {
                !permissionCheck() -> BleTransportAvailability.PERMISSION_UNAVAILABLE
                managerProvider()?.adapter == null -> BleTransportAvailability.TRANSPORT_UNAVAILABLE
                !bluetoothEnabledCheck() -> BleTransportAvailability.BLUETOOTH_DISABLED
                else -> BleTransportAvailability.AVAILABLE
            }
        }
        val clock = BleMonotonicClock {
            when (val result = MonotonicTimestampMs.create(SystemClock.elapsedRealtime())) {
                is CapabilityEnvelopeResult.Success -> result.value
                is CapabilityEnvelopeResult.Failure -> error("Android monotonic clock rejected: ${result.error}")
            }
        }
        val existingTransport = AndroidGattTransportAdapter(
            context = applicationContext,
            connectPermissionCheck = permissionCheck,
            bluetoothEnabledCheck = bluetoothEnabledCheck,
            bluetoothManagerProvider = managerProvider,
        )
        return BleConnectionProvider(
            transport = ExistingGattConnectionBridge(existingTransport),
            endpoints = endpoints,
            availability = availability,
            clock = clock,
            deadlines = TimerBleConnectionDeadlineScheduler(clock),
            lifecycleObserver = lifecycleObserver,
        )
    }
}
