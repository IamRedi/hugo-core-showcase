package io.redi.robot.protocol.fixtures

import io.redi.robot.body.looi.protocol.inventory.GattCapabilityCharacteristic
import io.redi.robot.body.looi.protocol.inventory.GattCapabilityInventory
import io.redi.robot.body.looi.protocol.inventory.GattCapabilityInventoryNormalizer
import io.redi.robot.body.looi.protocol.inventory.GattCapabilityService

/** Fixture boundary for deterministic, redacted capability-inventory import/export. */
object GattCapabilityFixtureMapper {
    fun fromFixture(inventory: GattInventory): GattCapabilityInventory =
        GattCapabilityInventoryNormalizer.normalize(
            inventory.services.map { service ->
                GattCapabilityService(
                    uuid = service.uuid,
                    isPrimary = service.isPrimary,
                    characteristics = service.characteristics.map { characteristic ->
                        GattCapabilityCharacteristic(
                            uuid = characteristic.uuid,
                            properties = characteristic.properties,
                            descriptors = characteristic.descriptors,
                        )
                    },
                )
            },
        )

    fun toFixture(inventory: GattCapabilityInventory): GattInventory {
        val normalized = GattCapabilityInventoryNormalizer.normalize(inventory.services)
        return GattInventory(
            services = normalized.services.map { service ->
                GattService(
                    uuid = service.uuid,
                    isPrimary = service.isPrimary,
                    characteristics = service.characteristics.map { characteristic ->
                        GattCharacteristic(
                            uuid = characteristic.uuid,
                            properties = characteristic.properties,
                            descriptors = characteristic.descriptors,
                        )
                    },
                )
            },
        )
    }

    fun withNormalizedInventory(fixture: ProtocolFixture): ProtocolFixture = fixture.copy(
        gattInventory = toFixture(fromFixture(fixture.gattInventory)),
    )
}
