package io.redi.robot.body.looi.protocol.inventory

import java.util.Locale
import java.util.UUID

/** Stable, project-owned description of observed GATT capabilities. */
data class GattCapabilityInventory(
    val services: List<GattCapabilityService>,
) {
    /** Deterministic structural identity only; this does not select a protocol profile. */
    fun fingerprint(): String = services.joinToString(separator = "|") { service ->
        val serviceType = if (service.isPrimary) "primary" else "secondary"
        val characteristics = service.characteristics.joinToString(separator = ",") { characteristic ->
            val properties = characteristic.properties.joinToString(separator = "+")
            val descriptors = characteristic.descriptors.joinToString(separator = "+")
            "${characteristic.uuid}($properties)[$descriptors]"
        }
        "$serviceType:${service.uuid}{$characteristics}"
    }
}

data class GattCapabilityService(
    val uuid: String,
    val isPrimary: Boolean,
    val characteristics: List<GattCapabilityCharacteristic>,
)

data class GattCapabilityCharacteristic(
    val uuid: String,
    val properties: List<String>,
    val descriptors: List<String> = emptyList(),
)

class InvalidGattUuidException(value: String) : IllegalArgumentException(
    "Invalid GATT UUID: '$value'",
)

/** Canonicalizes and merges captured inventory without assigning protocol meaning. */
object GattCapabilityInventoryNormalizer {
    private const val bluetoothBaseSuffix = "-0000-1000-8000-00805f9b34fb"
    private val uuid16Pattern = Regex("(?i)^[0-9a-f]{4}$")
    private val uuid32Pattern = Regex("(?i)^[0-9a-f]{8}$")

    fun normalize(services: Iterable<GattCapabilityService>): GattCapabilityInventory {
        val normalizedServices = services
            .groupBy { service ->
                ServiceKey(
                    uuid = normalizeUuid(service.uuid),
                    isPrimary = service.isPrimary,
                )
            }
            .map { (key, duplicates) ->
                GattCapabilityService(
                    uuid = key.uuid,
                    isPrimary = key.isPrimary,
                    characteristics = normalizeCharacteristics(
                        duplicates.flatMap { it.characteristics },
                    ),
                )
            }
            .sortedWith(
                compareBy<GattCapabilityService> { it.uuid }
                    .thenBy { if (it.isPrimary) 0 else 1 },
            )

        return GattCapabilityInventory(normalizedServices)
    }

    fun normalizeUuid(value: String): String {
        val candidate = value.trim()
        return when {
            uuid16Pattern.matches(candidate) -> "0000${candidate.lowercase(Locale.ROOT)}$bluetoothBaseSuffix"
            uuid32Pattern.matches(candidate) -> "${candidate.lowercase(Locale.ROOT)}$bluetoothBaseSuffix"
            else -> runCatching { UUID.fromString(candidate).toString() }
                .getOrElse { throw InvalidGattUuidException(value) }
        }
    }

    private fun normalizeCharacteristics(
        characteristics: Iterable<GattCapabilityCharacteristic>,
    ): List<GattCapabilityCharacteristic> = characteristics
        .groupBy { normalizeUuid(it.uuid) }
        .map { (uuid, duplicates) ->
            GattCapabilityCharacteristic(
                uuid = uuid,
                properties = duplicates
                    .flatMap { it.properties }
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .map { it.uppercase(Locale.ROOT) }
                    .distinct()
                    .sorted(),
                descriptors = duplicates
                    .flatMap { it.descriptors }
                    .map(::normalizeUuid)
                    .distinct()
                    .sorted(),
            )
        }
        .sortedBy { it.uuid }

    private data class ServiceKey(
        val uuid: String,
        val isPrimary: Boolean,
    )
}
