package io.redi.robot.feature.telemetry

import io.redi.robot.core.state.TelemetryFreshness
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryUiStateTest {
    @Test fun `export preserves unknown freshness and redacts identifier like strings`() {
        val export = TelemetryUiState(freshness = TelemetryFreshness.UNKNOWN, error = "device AABBCCDDEEFF failed").redactedDiagnosticExport()
        assertTrue(export.contains("freshness=UNKNOWN")); assertTrue(export.contains("<redacted>"))
    }
}
