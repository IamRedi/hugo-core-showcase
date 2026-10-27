package io.redi.robot.feature.diagnostic

import io.redi.robot.body.looi.discovery.LooiCandidateStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyBleDiagnosticModelsTest {
    @Test fun `diagnostic device projection has no field for a stable Bluetooth address`() {
        val device = DiagnosticBleDevice(
            key = "device-1",
            displayName = "LOOI",
            rssi = -55,
            candidateStatus = LooiCandidateStatus.UNKNOWN,
            confidence = 0.4f,
            evidence = emptyList(),
        )

        assertFalse(device.toString().contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(DiagnosticBleDevice::class.java.declaredFields.none { it.name.contains("address", true) })
    }
}
