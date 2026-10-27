package io.redi.robot.body.looi.protocol.profile

import io.redi.robot.body.looi.protocol.inventory.GattCapabilityCharacteristic
import io.redi.robot.body.looi.protocol.inventory.GattCapabilityInventory
import io.redi.robot.body.looi.protocol.inventory.GattCapabilityService
import io.redi.robot.protocol.fixtures.GattCapabilityFixtureMapper
import io.redi.robot.protocol.fixtures.ProtocolFixtureParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

class LooiProtocolProfileSelectorTest {

    @Test
    fun `selects exact profile A from required characteristics only`() {
        val result = selectLooiProtocolProfile(
            capabilityInventory(setOf("FED0", "FED1", "FED2", "FED8", "FED9", "FEDA")),
        )

        assertEquals(LooiProfileSelectionMode.COMPATIBLE, result.mode)
        assertEquals(LooiProtocolProfile.PROFILE_A, result.selectedProfile)
        assertEquals(setOf(LooiProtocolProfile.PROFILE_A), result.compatibleProfiles)
        assertTrue(result.canControl)
        assertFalse(result.canControl && result.partialProfiles.isNotEmpty())
    }

    @Test
    fun `selects exact profile B from required characteristics only`() {
        val result = selectLooiProtocolProfile(
            capabilityInventory(setOf("FED4", "FED5", "FED6", "FED7", "FED8")),
        )

        assertEquals(LooiProfileSelectionMode.COMPATIBLE, result.mode)
        assertEquals(LooiProtocolProfile.PROFILE_B, result.selectedProfile)
        assertEquals(setOf(LooiProtocolProfile.PROFILE_B), result.compatibleProfiles)
        assertTrue(result.canControl)
    }

    @Test
    fun `returns partial when one or more required characteristics are missing`() {
        val result = selectLooiProtocolProfile(
            capabilityInventory(setOf("FED4", "FED5", "FED7", "FED8")),
        )

        assertEquals(LooiProfileSelectionMode.PARTIAL, result.mode)
        assertEquals(null, result.selectedProfile)
        assertEquals(setOf("FED6"), result.partialProfiles[LooiProtocolProfile.PROFILE_B])
        assertFalse(result.canControl)
        assertTrue(result.rejectionReason.contains("partial"))
    }

    @Test
    fun `superset of required characteristics remains compatible`() {
        val result = selectLooiProtocolProfile(
            capabilityInventory(
                setOf(
                    "FED0",
                    "FED1",
                    "FED2",
                    "FED8",
                    "FED9",
                    "FEDA",
                    "FF00",
                    "FEDF",
                ),
            ),
        )

        assertEquals(LooiProfileSelectionMode.COMPATIBLE, result.mode)
        assertEquals(LooiProtocolProfile.PROFILE_A, result.selectedProfile)
        assertTrue(result.canControl)
    }

    @Test
    fun `returns ambiguous when profile A and profile B are both exact matches`() {
        val result = selectLooiProtocolProfile(
            capabilityInventory(
                setOf("FED0", "FED1", "FED2", "FED8", "FED9", "FEDA", "FED4", "FED5", "FED6", "FED7"),
            ),
        )

        assertEquals(LooiProfileSelectionMode.AMBIGUOUS, result.mode)
        assertEquals(setOf(LooiProtocolProfile.PROFILE_A, LooiProtocolProfile.PROFILE_B), result.compatibleProfiles)
        assertEquals(null, result.selectedProfile)
        assertFalse(result.canControl)
    }

    @Test
    fun `real hardware ambiguous fixture is treated as ambiguous research-only selection`() {
        val fixture = ProtocolFixtureParser.parse(loadFixture("fixtures/t011-hardware-2026-08-24.json"))
        val inventory = GattCapabilityFixtureMapper.fromFixture(fixture.gattInventory)
        val result = selectLooiProtocolProfile(inventory)

        assertEquals(LooiProfileSelectionMode.AMBIGUOUS, result.mode)
        assertEquals(setOf(LooiProtocolProfile.PROFILE_A, LooiProtocolProfile.PROFILE_B), result.compatibleProfiles)
        assertTrue(result.rejectionReason.contains("ambiguous"))
        assertFalse(result.canControl)
    }

    private fun capabilityInventory(shortCharacteristics: Set<String>): GattCapabilityInventory =
        GattCapabilityInventory(
            services = listOf(
                GattCapabilityService(
                    uuid = "000000FF-0000-1000-8000-00805F9B34FB",
                    isPrimary = true,
                    characteristics = shortCharacteristics.map(::shortCharacteristic),
                ),
            ),
        )

    private fun shortCharacteristic(uuid: String) = GattCapabilityCharacteristic(
        uuid = "0000$uuid-0000-1000-8000-00805F9B34FB",
        properties = emptyList(),
    )

    private fun loadFixture(path: String): String = javaClass.classLoader
        ?.let { loader -> loader.getResourceAsStream(path)?.bufferedReader()?.readText() }
        ?: throw FileNotFoundException("Fixture not found: $path")
}
