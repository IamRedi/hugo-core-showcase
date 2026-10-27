package io.redi.robot.protocol.fixtures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

class GattCapabilityFixtureMapperTest {
    @Test fun `real hardware golden inventory round trips deterministically`() {
        val fixture = ProtocolFixtureParser.parse(loadFixture("fixtures/t011-hardware-2026-08-24.json"))
        val normalized = GattCapabilityFixtureMapper.fromFixture(fixture.gattInventory)
        val roundTripped = GattCapabilityFixtureMapper.fromFixture(
            GattCapabilityFixtureMapper.toFixture(normalized),
        )

        assertEquals(6, normalized.services.size)
        assertEquals(normalized, roundTripped)
        assertTrue(
            normalized.services.any {
                it.uuid == "6e400001-b5a3-f393-e0a9-e50e24dcff00"
            },
        )
    }

    @Test fun `normalized fixture serialization round trips through T005 parser`() {
        val fixture = ProtocolFixtureParser.parse(loadFixture("fixtures/t005-valid.json"))
        val normalizedFixture = GattCapabilityFixtureMapper.withNormalizedInventory(fixture)
        val reparsed = ProtocolFixtureParser.parse(ProtocolFixtureParser.serialize(normalizedFixture))

        assertEquals(normalizedFixture, reparsed)
        assertEquals(
            GattCapabilityFixtureMapper.fromFixture(normalizedFixture.gattInventory),
            GattCapabilityFixtureMapper.fromFixture(reparsed.gattInventory),
        )
    }

    private fun loadFixture(path: String): String = javaClass.classLoader
        ?.let { loader ->
            loader.getResourceAsStream(path)
                ?.bufferedReader()
                ?.readText()
        } ?: throw FileNotFoundException("Fixture not found: $path")
}
