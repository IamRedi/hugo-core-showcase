package io.redi.robot.body.looi.protocol.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GattCapabilityInventoryNormalizerTest {
    @Test fun `normalizes order casing properties descriptors and duplicates`() {
        val inventory = GattCapabilityInventoryNormalizer.normalize(
            listOf(
                service(
                    uuid = "180F",
                    characteristic = characteristic(
                        uuid = "2A19",
                        properties = listOf("notify", " READ ", "notify"),
                        descriptors = listOf("2902"),
                    ),
                ),
                service(
                    uuid = "0000180f",
                    characteristic = characteristic(
                        uuid = "00002a19-0000-1000-8000-00805F9B34FB",
                        properties = listOf("vendor_observed"),
                        descriptors = listOf("00002902"),
                    ),
                ),
            ),
        )

        assertEquals(1, inventory.services.size)
        val characteristic = inventory.services.single().characteristics.single()
        assertEquals("0000180f-0000-1000-8000-00805f9b34fb", inventory.services.single().uuid)
        assertEquals("00002a19-0000-1000-8000-00805f9b34fb", characteristic.uuid)
        assertEquals(listOf("NOTIFY", "READ", "VENDOR_OBSERVED"), characteristic.properties)
        assertEquals(listOf("00002902-0000-1000-8000-00805f9b34fb"), characteristic.descriptors)
    }

    @Test fun `retains unknown valid service and characteristic UUIDs`() {
        val inventory = GattCapabilityInventoryNormalizer.normalize(
            listOf(
                service(
                    uuid = "12345678-9abc-def0-1234-56789abcdef0",
                    characteristic = characteristic("abcdef01-2345-6789-abcd-ef0123456789"),
                ),
            ),
        )

        assertEquals("12345678-9abc-def0-1234-56789abcdef0", inventory.services.single().uuid)
        assertEquals(
            "abcdef01-2345-6789-abcd-ef0123456789",
            inventory.services.single().characteristics.single().uuid,
        )
    }

    @Test fun `invalid UUID fails instead of being assigned protocol meaning`() {
        assertThrows(InvalidGattUuidException::class.java) {
            GattCapabilityInventoryNormalizer.normalize(
                listOf(service("not-a-uuid", characteristic("2A19"))),
            )
        }
    }

    @Test fun `candidate A and B structures have different deterministic fingerprints`() {
        val candidateA = GattCapabilityInventoryNormalizer.normalize(
            listOf(service("00FF", characteristic("FEDE"))),
        )
        val candidateB = GattCapabilityInventoryNormalizer.normalize(
            listOf(
                service(
                    "6E400001-B5A3-F393-E0A9-E50E24DCFF00",
                    characteristic("FF01"),
                ),
            ),
        )

        assertNotEquals(candidateA.fingerprint(), candidateB.fingerprint())
        assertEquals(candidateA.fingerprint(), candidateA.fingerprint())
    }

    private fun service(
        uuid: String,
        characteristic: GattCapabilityCharacteristic,
    ) = GattCapabilityService(
        uuid = uuid,
        isPrimary = true,
        characteristics = listOf(characteristic),
    )

    private fun characteristic(
        uuid: String,
        properties: List<String> = emptyList(),
        descriptors: List<String> = emptyList(),
    ) = GattCapabilityCharacteristic(
        uuid = uuid,
        properties = properties,
        descriptors = descriptors,
    )
}
