package io.redi.robot.body.looi.protocol.telemetry

import io.redi.robot.body.looi.protocol.profile.LooiProtocolProfile
import io.redi.robot.protocol.fixtures.ProtocolFixtureParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import kotlin.random.Random

class LooiTelemetryParserTest {
    @Test
    fun `source-reported corpus parses known profile A candidates as diagnostic observations`() {
        val fixture = ProtocolFixtureParser.parse(loadFixture("fixtures/t017-telemetry-candidates.json"))
        val observations = fixture.events.map { event ->
            LooiTelemetryParser.parse(envelope(LooiProtocolProfile.PROFILE_A, event.characteristicUuid, event.payload))
        }

        assertTrue(observations.all { it is LooiTelemetryParseResult.Parsed })
        assertEquals(InitializationCompleteCandidate(), (observations[0] as LooiTelemetryParseResult.Parsed).observation)
        assertEquals(
            LowerContactCandidate(true, false, true, false),
            (observations[1] as LooiTelemetryParseResult.Parsed).observation,
        )
        assertEquals(DockCandidate(DockCandidateState.DOCKED), (observations[2] as LooiTelemetryParseResult.Parsed).observation)
        assertEquals(ProfileABatteryCandidate(75, true), (observations[3] as LooiTelemetryParseResult.Parsed).observation)
        observations.filterIsInstance<LooiTelemetryParseResult.Parsed>().forEach { result ->
            assertFalse(result.observation.safetyUsable)
        }
    }

    @Test
    fun `profile A FED9 accepts documented packet shapes and keeps signed axes`() {
        val cases = listOf(
            listOf(0x02, 0x80, 0x7f) to ImuLikeAxesCandidate(-128, 127),
            listOf(0x06) to DockCandidate(DockCandidateState.REMOVED),
            listOf(0x09, 0x44) to SideTouchCandidate(TouchCandidateState.PRESSED, 0x44),
            listOf(0x0a, 0x00) to SideTouchCandidate(TouchCandidateState.RELEASED, 0x00),
            listOf(0x0b, 0x01) to ExternalPowerCandidate(0x01),
            listOf(0x0e, 0x12, 0x34) to AttitudeLikeCandidate(0x12, 0x34),
            listOf(0x12, 0x00, 0x00) to FrontTouchPulseCandidate(),
        )
        cases.forEach { (payload, expected) ->
            val result = LooiTelemetryParser.parse(envelope(LooiProtocolProfile.PROFILE_A, "FED9", payload))
            assertEquals(expected, (result as LooiTelemetryParseResult.Parsed).observation)
        }
    }

    @Test
    fun `malformed known packets and invalid battery values remain unknown`() {
        val invalid = listOf(
            envelope(LooiProtocolProfile.PROFILE_A, "FED9", listOf(0x01, 0x00)),
            envelope(LooiProtocolProfile.PROFILE_A, "FED9", listOf(0x01, 0x00, 0x01, 0x02, 0x01)),
            envelope(LooiProtocolProfile.PROFILE_A, "FED9", listOf(0x12, 0x01, 0x00)),
            envelope(LooiProtocolProfile.PROFILE_A, "FED8", listOf(101, 1)),
            envelope(LooiProtocolProfile.PROFILE_A, "FED8", listOf(50, 2)),
            envelope(LooiProtocolProfile.PROFILE_A, "FED8", listOf(50)),
        )

        invalid.forEach { resultInput ->
            val result = LooiTelemetryParser.parse(resultInput)
            assertTrue(result is LooiTelemetryParseResult.Unknown)
        }
    }

    @Test
    fun `profile B FED8 remains opaque ack and is never battery`() {
        val result = LooiTelemetryParser.parse(envelope(LooiProtocolProfile.PROFILE_B, "0000FED8-0000-1000-8000-00805f9b34fb", listOf(75, 1)))

        assertTrue(result is LooiTelemetryParseResult.Parsed)
        assertEquals(ProfileBFed8AckCandidate(listOf(75, 1)), (result as LooiTelemetryParseResult.Parsed).observation)
        assertFalse(result.observation is ProfileABatteryCandidate)
    }

    @Test
    fun `arbitrary byte corpus cannot crash or produce safety-usable telemetry`() {
        val random = Random(17)
        repeat(2_000) { index ->
            val payload = List(random.nextInt(0, 32)) { random.nextInt(0, 256) }
            val profile = if (index % 2 == 0) LooiProtocolProfile.PROFILE_A else LooiProtocolProfile.PROFILE_B
            val characteristic = if (index % 3 == 0) "FED9" else "FED8"
            val result = LooiTelemetryParser.parse(envelope(profile, characteristic, payload))
            if (result is LooiTelemetryParseResult.Parsed) assertFalse(result.observation.safetyUsable)
        }
    }

    private fun envelope(profile: LooiProtocolProfile, characteristic: String, payload: List<Int>) =
        RawLooiTelemetryEnvelope(1, 100, profile, characteristic, payload)

    private fun loadFixture(path: String): String = javaClass.classLoader
        ?.getResourceAsStream(path)
        ?.bufferedReader()
        ?.readText()
        ?: throw FileNotFoundException("Fixture not found: $path")
}
