package io.redi.robot.protocol.fixtures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.FileNotFoundException

class ProtocolFixtureParserTest {
    private val parser = ProtocolFixtureParser

    @Test fun `valid fixture parses and round trips`() {
        val fixtureText = loadFixture("fixtures/t005-valid.json")
        val fixture = parser.parse(fixtureText)
        val roundTripped = parser.parse(parser.serialize(fixture))

        assertEquals(3, fixture.events.size)
        assertEquals(1, fixture.gattInventory.services.size)
        assertEquals(fixture, roundTripped)
    }

    @Test fun `redacted T016 physical candidate A fixture parses`() {
        val fixture = parser.parse(loadFixture("fixtures/t016-hardware-candidate-a-2026-08-24.json"))

        assertEquals(2, fixture.events.size)
        assertEquals(listOf(0x01), (fixture.events[0] as WriteEvent).payload)
        assertEquals(listOf(0x03), (fixture.events[1] as WriteEvent).payload)
    }

    @Test fun `truncated fixture fails with actionable JSON error`() {
        val malformed = loadFixture("fixtures/t005-malformed.json")
        val error = assertThrows(ProtocolFixtureParseException::class.java) {
            parser.parse(malformed)
        }
        assertEquals("Invalid JSON", error.message)
    }

    @Test fun `raw stable address is rejected by redaction rules`() {
        val redactionFail = loadFixture("fixtures/t005-redaction-fail.json")
        val error = assertThrows(ProtocolFixtureParseException::class.java) {
            parser.parse(redactionFail)
        }
        assertEquals("Fixture contains forbidden stable identifier field 'macAddress'", error.message)
        assertEquals("root.macAddress", error.path)
    }

    private fun loadFixture(path: String): String = javaClass.classLoader
        ?.let { loader ->
            loader.getResourceAsStream(path)
                ?.bufferedReader()
                ?.readText()
        } ?: throw FileNotFoundException("Fixture not found: $path")
}
