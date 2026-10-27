package io.redi.robot.feature.manualcontrol

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

data class ManualHeadUiState(val ready: Boolean, val safe: Boolean, val calibrated: Boolean, val lastCommand: Float? = null) {
    val enabled: Boolean get() = ready && safe && calibrated
    val status: String get() = if (enabled) "Head control ready; position is commanded, not observed" else "Head control unavailable: profile calibration and safety approval required"
}

fun interface ManualHeadCommandSink { fun request(normalizedPitch: Float) }

@Composable
fun ManualHeadControls(state: ManualHeadUiState, sink: ManualHeadCommandSink, modifier: Modifier = Modifier) {
    var sliderValue by remember { mutableFloatStateOf(state.lastCommand ?: 0f) }
    fun send(value: Float) { if (state.enabled) sink.request(value.coerceIn(-0.5f, 0.5f)) }
    Column(modifier.fillMaxWidth()) {
        Text("Head control")
        Text(state.status, Modifier.testTag("head-status"))
        Button(onClick = { send(-0.5f) }, enabled = state.enabled, modifier = Modifier.testTag("head-up")) { Text("Head up (limited)") }
        Button(onClick = { send(0f) }, enabled = state.enabled, modifier = Modifier.testTag("head-center")) { Text("Head center candidate") }
        Button(onClick = { send(0.5f) }, enabled = state.enabled, modifier = Modifier.testTag("head-down")) { Text("Head down (limited)") }
        Slider(value = sliderValue, onValueChange = { sliderValue = it; send(it) }, valueRange = -0.5f..0.5f, enabled = state.enabled, modifier = Modifier.testTag("head-slider"))
        Text("Commanded pitch: ${state.lastCommand ?: "none"}")
    }
}
