package io.redi.robot.platform.transport.ble.gatt

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Ignore("Requires Android BLE transport + real LOOI target for callback-path verification.")
class AndroidGattTransportAdapterInstrumentedTest {

    @Test
    fun placeholderGattTransportFlow() {
        // Pending: callback ordering/state transitions, connect/disconnect cleanup, and generation isolation.
    }
}
