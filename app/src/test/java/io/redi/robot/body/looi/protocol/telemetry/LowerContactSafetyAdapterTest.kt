package io.redi.robot.body.looi.protocol.telemetry

import io.redi.robot.body.looi.protocol.profile.LooiProtocolProfile
import io.redi.robot.protocol.fixtures.ProtocolFixtureParser
import io.redi.robot.safety.sensors.CliffContactCorner
import io.redi.robot.safety.sensors.CliffContactObservationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowerContactSafetyAdapterTest {
    @Test fun `real hardware transition fixtures verify all four active-low positions and safe return`() {
        val cases = listOf(
            "t031-prerequisite-fed9-front-left-transition-repeat-2026-08-24.json" to CliffContactCorner.FRONT_LEFT,
            "t031-prerequisite-fed9-front-right-transition-2026-08-24.json" to CliffContactCorner.FRONT_RIGHT,
            "t031-prerequisite-fed9-rear-left-transition-2026-08-24.json" to CliffContactCorner.REAR_LEFT,
            "t031-prerequisite-fed9-rear-right-transition-2026-08-24.json" to CliffContactCorner.REAR_RIGHT,
        )
        cases.forEach { (fixtureName, expectedCorner) ->
            val adapter = LowerContactSafetyAdapter(7)
            val states = loadFixture(fixtureName).events.map { event ->
                adapter.accept(
                    LooiTelemetryParser.parse(
                        RawLooiTelemetryEnvelope(7, event.timestampMs, LooiProtocolProfile.PROFILE_A, event.characteristicUuid, event.payload),
                    ),
                )
            }
            assertTrue(states.any { state ->
                state.status == CliffContactObservationStatus.VALID &&
                    state.readings.getValue(expectedCorner).triggered &&
                    CliffContactCorner.entries.filterNot { it == expectedCorner }.none { state.readings.getValue(it).triggered }
            })
            val final = states.last { it.status == CliffContactObservationStatus.VALID }
            assertTrue(final.readings.values.none { it.triggered })
        }
    }

    @Test fun `malformed cliff frame fails closed while unrelated telemetry is ignored`() {
        val adapter = LowerContactSafetyAdapter(3)
        val attitude = LooiTelemetryParser.parse(envelope(3, 10, listOf(0x0e, 1, 2)))
        adapter.accept(attitude)
        assertEquals(CliffContactObservationStatus.UNKNOWN, adapter.state.status)

        adapter.accept(LooiTelemetryParser.parse(envelope(3, 11, listOf(0x01, 0, 1))))
        assertEquals(CliffContactObservationStatus.MALFORMED, adapter.state.status)
    }

    @Test fun `stale generation cannot replace current safety state`() {
        val adapter = LowerContactSafetyAdapter(4)
        adapter.accept(LooiTelemetryParser.parse(envelope(4, 10, listOf(0x01, 1, 1, 1, 1))))
        val current = adapter.state
        adapter.accept(LooiTelemetryParser.parse(envelope(3, 20, listOf(0x01, 0, 0, 0, 0))))
        assertEquals(current, adapter.state)
        assertFalse(adapter.state.readings.values.any { it.triggered })
    }

    private fun envelope(generation: Long, timestamp: Long, payload: List<Int>) = RawLooiTelemetryEnvelope(
        generation, timestamp, LooiProtocolProfile.PROFILE_A, "FED9", payload,
    )
    private fun loadFixture(name: String) = ProtocolFixtureParser.parse(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")).bufferedReader().use { it.readText() },
    )
}
