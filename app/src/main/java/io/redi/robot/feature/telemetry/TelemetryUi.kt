package io.redi.robot.feature.telemetry

import io.redi.robot.core.state.TelemetryFreshness
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

data class TelemetryUiState(
    val profile: String? = null,
    val batteryPercent: Int? = null,
    val externalPower: Boolean? = null,
    val dock: String? = null,
    val freshness: TelemetryFreshness = TelemetryFreshness.UNKNOWN,
    val error: String? = null,
)

fun TelemetryUiState.redactedDiagnosticExport(): String = buildString {
    append("profile=").append(profile ?: "unknown").append('\n')
    append("battery=").append(batteryPercent ?: "unknown").append('\n')
    append("externalPower=").append(externalPower ?: "unknown").append('\n')
    append("dock=").append(dock ?: "unknown").append('\n')
    append("freshness=").append(freshness.name)
    error?.let { append('\n').append("error=").append(it.replace(Regex("[0-9A-Fa-f]{12,}"), "<redacted>")) }
}

@Composable
fun TelemetryScreen(state: TelemetryUiState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("telemetry-screen")) {
        Text("Telemetry")
        Text("Profile: ${state.profile ?: "unknown"}")
        Text("Freshness: ${state.freshness.name}", Modifier.testTag("telemetry-freshness"))
        Text("Battery: ${state.batteryPercent?.let { "$it%" } ?: "unknown"}")
        Text("External power: ${state.externalPower?.toString() ?: "unknown"}")
        Text("Dock: ${state.dock ?: "unknown"}")
        state.error?.let { Text("Error: $it") }
    }
}
