package io.redi.robot.feature.manualcontrol

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

data class ManualLightUiState(val ready: Boolean, val safe: Boolean, val minimumOnAvailable: Boolean, val lastBrightness: Float? = null) {
    val enabled: Boolean get() = ready && safe
    val status: String get() = if (enabled) "Light command state: ${lastBrightness ?: "none"}" else "Light control unavailable: safety approval required"
}
fun interface ManualLightCommandSink { fun request(normalizedBrightness: Float) }

@Composable
fun ManualLightControls(state: ManualLightUiState, sink: ManualLightCommandSink, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text("Front light")
        Text(state.status, Modifier.testTag("light-status"))
        Button(onClick = { sink.request(0f) }, enabled = state.ready, modifier = Modifier.testTag("light-off")) { Text("Light off") }
        if (state.minimumOnAvailable) Button(onClick = { if (state.enabled) sink.request(1f) }, enabled = state.enabled, modifier = Modifier.testTag("light-minimum-on")) { Text("Light on (candidate minimum)") }
    }
}
