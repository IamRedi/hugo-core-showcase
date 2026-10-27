package io.redi.robot.platform.transport.ble.gatt

import android.bluetooth.BluetoothProfile
import io.redi.robot.body.looi.connection.ConnectionFailure
import io.redi.robot.body.looi.connection.GattFailureKind
import io.redi.robot.body.looi.connection.GattTransportEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GattTransportCallbackTranslatorTest {
    @Test
    fun `connection state maps connecting and connected then disconnect closes duplicates`() {
        val translator = GattTransportCallbackTranslator(generation = 7L)

        assertEquals(
            GattTransportEvent.Connecting(7L),
            translator.onConnectionStateChange(0, BluetoothProfile.STATE_CONNECTING),
        )
        assertEquals(
            GattTransportEvent.Connected(7L),
            translator.onConnectionStateChange(0, BluetoothProfile.STATE_CONNECTED),
        )
        assertEquals(
            GattTransportEvent.Disconnected(7L),
            translator.onConnectionStateChange(0, BluetoothProfile.STATE_DISCONNECTED),
        )

        assertNull(translator.onConnectionStateChange(0, BluetoothProfile.STATE_DISCONNECTED))
    }

    @Test
    fun `connection failure maps non-success status into typed failure`() {
        val translator = GattTransportCallbackTranslator(generation = 8L)
        val event = translator.onConnectionStateChange(0x08, BluetoothProfile.STATE_DISCONNECTED)

        val failureEvent = event as GattTransportEvent.Failure
        assertEquals(ConnectionFailure.GattFailure::class, failureEvent.failure::class)
        assertEquals(
            GattFailureKind.TIMEOUT,
            (failureEvent.failure as ConnectionFailure.GattFailure).kind,
        )
        assertNotNull((failureEvent.failure as ConnectionFailure.GattFailure).detail)
    }

    @Test
    fun `service failure closes translator and suppresses subsequent events`() {
        val translator = GattTransportCallbackTranslator(generation = 1L)
        val failed = translator.onServicesDiscovered(0x0D)
        assertNotNull(failed)
        assertNull(translator.onServicesDiscovered(0x00))
    }

    @Test
    fun `service success emits ready event once`() {
        val translator = GattTransportCallbackTranslator(generation = 2L)
        assertEquals(
            GattTransportEvent.Ready(2L),
            translator.onServicesDiscovered(0x00),
        )
        assertNull(translator.onServicesDiscovered(0x00))
    }

    @Test
    fun `failure is only mapped once after close`() {
        val translator = GattTransportCallbackTranslator(generation = 9L)
        translator.close()

        assertNull(translator.onConnectionStateChange(0, BluetoothProfile.STATE_DISCONNECTED))
        assertNull(translator.onServicesDiscovered(0x00))
    }
}
