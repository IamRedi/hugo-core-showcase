package io.redi.robot.platform.transport.ble.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBleScanPermissionPolicyTest {
    @Test
    fun `Android 12 and newer require Bluetooth scan permission`() {
        assertTrue(bleScanPermissionGranted(31, bluetoothScanGranted = true, legacyFineLocationGranted = false))
        assertFalse(bleScanPermissionGranted(31, bluetoothScanGranted = false, legacyFineLocationGranted = true))
    }

    @Test
    fun `Android 11 and older require legacy fine location permission`() {
        assertTrue(bleScanPermissionGranted(30, bluetoothScanGranted = false, legacyFineLocationGranted = true))
        assertFalse(bleScanPermissionGranted(28, bluetoothScanGranted = true, legacyFineLocationGranted = false))
    }
}
