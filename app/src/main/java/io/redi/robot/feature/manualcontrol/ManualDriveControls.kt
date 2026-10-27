package io.redi.robot.feature.manualcontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class ManualDriveDirection(val linear: Float, val angular: Float, val label: String) {
    FORWARD(0.10f, 0f, "Forward"),
    BACKWARD(-0.10f, 0f, "Back"),
    LEFT(0f, -0.10f, "Left"),
    RIGHT(0f, 0.10f, "Right"),
}

enum class ManualStopReason {
    RELEASE,
    POINTER_CANCELLED,
    MULTI_TOUCH,
    FOCUS_LOST,
    NAVIGATION,
    BACKGROUND,
    EMERGENCY_STOP,
}

sealed interface ManualDriveCommand {
    data class Refresh(
        val sequence: Long,
        val direction: ManualDriveDirection,
        val issuedAtElapsedMs: Long,
    ) : ManualDriveCommand

    data class Stop(
        val sequence: Long,
        val reason: ManualStopReason,
        val issuedAtElapsedMs: Long,
    ) : ManualDriveCommand
}

fun interface ManualDriveCommandSink {
    fun dispatch(command: ManualDriveCommand)
}

data class ManualDriveUiState(
    val ready: Boolean,
    val safe: Boolean,
    val emergencyStopLatched: Boolean,
) {
    val driveEnabled: Boolean get() = ready && safe && !emergencyStopLatched

    val status: String
        get() = when {
            emergencyStopLatched -> "E-stop latched: local stationary reset required"
            !ready -> "Manual drive unavailable: robot is not protocol ready"
            !safe -> "Manual drive unavailable: safety approval is required"
            else -> "Manual drive ready: press and hold a direction"
        }
}

/**
 * UI-owned press state. It emits only semantic refresh/stop commands and owns no encoder,
 * transport, or safety policy. A second pointer always cancels the active press.
 */
class ManualDriveController(private val sink: ManualDriveCommandSink) {
    private data class ActivePress(
        val token: Long,
        val direction: ManualDriveDirection,
        val lastRefreshAtElapsedMs: Long,
    )

    private var nextSequence = 0L
    private var active: ActivePress? = null

    fun press(direction: ManualDriveDirection, issuedAtElapsedMs: Long): Long? {
        require(issuedAtElapsedMs >= 0) { "manual command timestamp must not be negative" }
        active?.let { existing ->
            val stale = issuedAtElapsedMs - existing.lastRefreshAtElapsedMs > STALE_PRESS_TIMEOUT_MS
            stopActive(if (stale) ManualStopReason.POINTER_CANCELLED else ManualStopReason.MULTI_TOUCH, issuedAtElapsedMs)
            if (!stale) return null
        }
        val token = nextSequence
        active = ActivePress(token, direction, issuedAtElapsedMs)
        emitRefresh(active!!, issuedAtElapsedMs)
        return token
    }

    fun refresh(token: Long, issuedAtElapsedMs: Long): Boolean {
        require(issuedAtElapsedMs >= 0) { "manual command timestamp must not be negative" }
        val activePress = active?.takeIf { it.token == token } ?: return false
        val refreshed = activePress.copy(lastRefreshAtElapsedMs = issuedAtElapsedMs)
        active = refreshed
        emitRefresh(refreshed, issuedAtElapsedMs)
        return true
    }

    fun release(token: Long, issuedAtElapsedMs: Long) {
        if (active?.token == token) stopActive(ManualStopReason.RELEASE, issuedAtElapsedMs)
    }

    fun cancel(token: Long, reason: ManualStopReason, issuedAtElapsedMs: Long) {
        if (active?.token == token) stopActive(reason, issuedAtElapsedMs)
    }

    fun cancelActive(reason: ManualStopReason, issuedAtElapsedMs: Long) {
        stopActive(reason, issuedAtElapsedMs)
    }

    fun emergencyStop(issuedAtElapsedMs: Long) {
        stopActive(ManualStopReason.EMERGENCY_STOP, issuedAtElapsedMs)
    }

    private fun emitRefresh(activePress: ActivePress, issuedAtElapsedMs: Long) {
        sink.dispatch(ManualDriveCommand.Refresh(nextSequence++, activePress.direction, issuedAtElapsedMs))
    }

    private fun stopActive(reason: ManualStopReason, issuedAtElapsedMs: Long) {
        val activePress = active ?: return
        active = null
        sink.dispatch(ManualDriveCommand.Stop(nextSequence++, reason, issuedAtElapsedMs))
    }

    private companion object {
        const val STALE_PRESS_TIMEOUT_MS = 150L
    }
}

@Composable
fun ManualDriveControls(
    state: ManualDriveUiState,
    controller: ManualDriveController,
    nowElapsedMs: () -> Long,
    onEmergencyStop: () -> Unit,
    modifier: Modifier = Modifier,
    refreshIntervalMs: Long = 50L,
) {
    require(refreshIntervalMs > 0) { "manual refresh interval must be positive" }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focus -> if (!focus.isFocused) controller.cancelActive(ManualStopReason.FOCUS_LOST, nowElapsedMs()) },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Manual drive", style = MaterialTheme.typography.titleMedium)
        Text(state.status, modifier = Modifier.testTag("manual-drive-status"))
        DirectionButton(ManualDriveDirection.FORWARD, state.driveEnabled, controller, nowElapsedMs, refreshIntervalMs)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DirectionButton(ManualDriveDirection.LEFT, state.driveEnabled, controller, nowElapsedMs, refreshIntervalMs, Modifier.weight(1f))
            DirectionButton(ManualDriveDirection.RIGHT, state.driveEnabled, controller, nowElapsedMs, refreshIntervalMs, Modifier.weight(1f))
        }
        DirectionButton(ManualDriveDirection.BACKWARD, state.driveEnabled, controller, nowElapsedMs, refreshIntervalMs)
        Button(
            onClick = {
                controller.emergencyStop(nowElapsedMs())
                onEmergencyStop()
            },
            modifier = Modifier.fillMaxWidth().testTag("manual-emergency-stop"),
        ) { Text("EMERGENCY STOP") }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DirectionButton(
    direction: ManualDriveDirection,
    enabled: Boolean,
    controller: ManualDriveController,
    nowElapsedMs: () -> Long,
    refreshIntervalMs: Long,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    var activeToken by remember { mutableStateOf<Long?>(null) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    val refreshScope = rememberCoroutineScope()
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .defaultMinSize(minWidth = 96.dp, minHeight = 48.dp)
            .testTag("manual-${direction.name.lowercase()}")
            .semantics { if (!enabled) disabled() }
            .pointerInteropFilter { event ->
                if (!enabled) return@pointerInteropFilter false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val token = controller.press(direction, nowElapsedMs())
                        activeToken = token
                        refreshJob?.cancel()
                        refreshJob = token?.let {
                            refreshScope.launch {
                                while (true) {
                                    delay(refreshIntervalMs)
                                    if (!controller.refresh(it, nowElapsedMs())) {
                                        if (activeToken == it) activeToken = null
                                        break
                                    }
                                }
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val token = activeToken
                        refreshJob?.cancel()
                        refreshJob = null
                        activeToken = null
                        token?.let { controller.release(it, nowElapsedMs()) }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        val token = activeToken
                        refreshJob?.cancel()
                        refreshJob = null
                        activeToken = null
                        token?.let { controller.cancel(it, ManualStopReason.POINTER_CANCELLED, nowElapsedMs()) }
                        true
                    }
                    else -> true
                }
            },
    ) {
        Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp), contentAlignment = Alignment.Center) {
            Text(direction.label)
        }
    }
}
