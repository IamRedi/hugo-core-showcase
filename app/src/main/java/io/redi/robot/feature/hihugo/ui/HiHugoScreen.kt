package io.redi.robot.feature.hihugo.ui

import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.redi.robot.feature.hihugo.presentation.HiHugoAccent
import io.redi.robot.feature.hihugo.presentation.HiHugoAnimationPolicy
import io.redi.robot.feature.hihugo.presentation.HiHugoBlockedPresentation
import io.redi.robot.feature.hihugo.presentation.HiHugoBodyPresentationModel
import io.redi.robot.feature.hihugo.presentation.HiHugoBodyStage
import io.redi.robot.feature.hihugo.presentation.HiHugoFaceState
import io.redi.robot.feature.hihugo.presentation.HiHugoPresentationModel
import io.redi.robot.feature.providers.ProviderBlockedUiState
import io.redi.robot.feature.providers.textContent
import io.redi.robot.firstlife.voice.VoiceSessionBlockedReason
import io.redi.robot.firstlife.voice.VoiceSessionModel
import io.redi.robot.firstlife.voice.VoiceSessionPhase
import io.redi.robot.firstlife.voice.VoiceRecognitionTier
import io.redi.robot.firstlife.voice.SpeechOutputBlockedReason
import io.redi.robot.firstlife.voice.SpeechOutputModel
import io.redi.robot.firstlife.voice.SpeechOutputPhase
import io.redi.robot.firstlife.vision.CameraBlockedReason
import io.redi.robot.firstlife.vision.CameraSessionModel
import io.redi.robot.firstlife.vision.CameraSessionPhase
import io.redi.robot.firstlife.experience.FirstLifeExperienceSnapshot
import io.redi.robot.firstlife.experience.FirstLifeExperienceStage
import io.redi.robot.firstlife.orientation.FirstLifeOrientationModel
import io.redi.robot.firstlife.orientation.OrientationPhase
import kotlinx.coroutines.delay

data class HiHugoProviderSetupPresentation(
    val statusLabel: String = "No body discovery started",
    val canDiscover: Boolean = true,
    val canConnect: Boolean = false,
    val canDisconnect: Boolean = false,
)

@Composable
fun HiHugoScreen(
    model: HiHugoPresentationModel,
    experience: FirstLifeExperienceSnapshot? = null,
    onStartExperience: () -> Unit = {},
    onCancelExperience: () -> Unit = {},
    onResetExperience: () -> Unit = {},
    bodyPresentation: HiHugoBodyPresentationModel = HiHugoBodyPresentationModel.EMPTY,
    providerSetup: HiHugoProviderSetupPresentation = HiHugoProviderSetupPresentation(),
    onDiscoverProvider: () -> Unit = {},
    onConnectProvider: () -> Unit = {},
    onDisconnectProvider: () -> Unit = {},
    onSelectActiveBody: () -> Unit = {},
    onDeactivateActiveBody: () -> Unit = {},
    voiceSession: VoiceSessionModel = VoiceSessionModel.IDLE,
    speechOutput: SpeechOutputModel = SpeechOutputModel.IDLE,
    sessionDisplayNameAvailable: Boolean = false,
    voiceInputAllowed: Boolean = false,
    onStartVoiceSession: () -> Unit = {},
    onReviewSystemVoiceFallback: () -> Unit = {},
    onConsentSystemVoiceFallback: () -> Unit = {},
    onCancelVoiceSession: () -> Unit = {},
    onSpeakIntroduction: () -> Unit = {},
    onSpeakGreeting: () -> Unit = {},
    onCancelSpeech: () -> Unit = {},
    onInstallVoiceData: () -> Unit = {},
    cameraSession: CameraSessionModel = CameraSessionModel.IDLE,
    onStartCameraSession: () -> Unit = {},
    onCloseCameraSession: () -> Unit = {},
    cameraPreview: @Composable () -> Unit = {},
    orientation: FirstLifeOrientationModel = FirstLifeOrientationModel.IDLE,
    orientationMountingConfirmed: Boolean = false,
    orientationSafetyReady: Boolean = false,
    onPrepareOrientation: () -> Unit = {},
    onBeginOrientationHold: () -> Unit = {},
    onReleaseOrientationHold: () -> Unit = {},
    onCancelOrientation: () -> Unit = {},
    onOrientationEmergencyStop: () -> Unit = {},
    onDeveloperStateRequested: (HiHugoFaceState) -> Unit,
    modifier: Modifier = Modifier,
    developerControlsEnabled: Boolean = false,
    reducedMotion: Boolean = false,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleActive by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            lifecycleActive = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val animationActive = HiHugoAnimationPolicy.isActive(model, lifecycleActive, reducedMotion)

    MaterialTheme {
        Surface(modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                HiHugoCodeBackdrop(model, bodyPresentation.stage, animationActive)
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("HiHugo", color = Color(0xFFEAF8FF), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    if (developerControlsEnabled) Spacer(Modifier.height(24.dp)) else Spacer(Modifier.weight(.35f))
                    HiHugoFace(model, bodyPresentation.stage, animationActive)
                    Spacer(Modifier.height(28.dp))
                    Text(model.visualSpec.label, color = accentColor(model.visualSpec.accent), fontSize = 22.sp, fontWeight = FontWeight.Medium)
                    Text(model.visualSpec.status, color = Color(0xFFAAC0D1), fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
                    if (developerControlsEnabled) Spacer(Modifier.height(28.dp)) else Spacer(Modifier.weight(.65f))
                    if (experience != null) {
                        HiHugoFirstLifePanel(
                            experience,
                            onStartExperience,
                            onCancelExperience,
                            onResetExperience,
                            onStartCameraSession,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    HiHugoVoicePanel(
                        voiceSession,
                        voiceInputAllowed && speechOutput.activeRequest == null,
                        onStartVoiceSession,
                        onReviewSystemVoiceFallback,
                        onConsentSystemVoiceFallback,
                        onCancelVoiceSession,
                    )
                    Spacer(Modifier.height(12.dp))
                    HiHugoSpeechPanel(
                        speechOutput,
                        sessionDisplayNameAvailable,
                        onSpeakIntroduction,
                        onSpeakGreeting,
                        onCancelSpeech,
                        onInstallVoiceData,
                    )
                    Spacer(Modifier.height(12.dp))
                    HiHugoCameraPanel(
                        cameraSession,
                        onStartCameraSession,
                        onCloseCameraSession,
                        cameraPreview,
                    )
                    Spacer(Modifier.height(12.dp))
                    HiHugoProviderSetupPanel(
                        providerSetup,
                        onDiscoverProvider,
                        onConnectProvider,
                        onDisconnectProvider,
                    )
                    Spacer(Modifier.height(12.dp))
                    HiHugoBodyPanel(bodyPresentation, onSelectActiveBody, onDeactivateActiveBody)
                    Spacer(Modifier.height(12.dp))
                    HiHugoOrientationPanel(
                        orientation,
                        bodyPresentation.stage == HiHugoBodyStage.SELECTED,
                        cameraSession.phase == CameraSessionPhase.OBSERVING,
                        orientationMountingConfirmed,
                        orientationSafetyReady,
                        onPrepareOrientation,
                        onBeginOrientationHold,
                        onReleaseOrientationHold,
                        onCancelOrientation,
                        onOrientationEmergencyStop,
                    )
                    if (developerControlsEnabled) {
                        Text("Developer presentation preview", color = Color(0xFF71869A), fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HiHugoFaceState.entries.chunked(3).forEach { states ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    states.forEach { state ->
                                        Button(
                                            onClick = { onDeveloperStateRequested(state) },
                                            modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).testTag("hihugo-state-${state.name}"),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xE817263A)),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                        ) { Text(state.name.lowercase().replace('_', ' '), fontSize = 10.sp, maxLines = 1) }
                                    }
                                    repeat(3 - states.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HiHugoProviderSetupPanel(
    setup: HiHugoProviderSetupPresentation,
    onDiscover: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xFF0D1422), RoundedCornerShape(18.dp))
            .padding(16.dp)
            .semantics { stateDescription = setup.statusLabel }
            .testTag("hihugo-provider-setup"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Provider connection", color = Color(0xFFDCEAF4), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            setup.statusLabel,
            color = Color(0xFF9BAABB),
            fontSize = 13.sp,
            modifier = Modifier.testTag("hihugo-provider-setup-status"),
        )
        if (setup.canDiscover) {
            Button(
                onClick = onDiscover,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-discover-provider"),
            ) { Text("Find compatible body") }
        }
        if (setup.canConnect) {
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-connect-provider"),
            ) { Text("Connect body") }
        }
        if (setup.canDisconnect) {
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-disconnect-provider"),
            ) { Text("Disconnect provider") }
        }
        Text(
            "Discovery and connection do not grant trust, authorization, safety approval, or execution authority.",
            color = Color(0xFF71869A),
            fontSize = 10.sp,
            modifier = Modifier.testTag("hihugo-provider-setup-boundary"),
        )
    }
}

@Composable
private fun HiHugoFirstLifePanel(
    experience: FirstLifeExperienceSnapshot,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onContinueWithCamera: () -> Unit,
) {
    val status = when (experience.stage) {
        FirstLifeExperienceStage.READY -> "Ready for one explicit First Life session"
        FirstLifeExperienceStage.INTRODUCTION -> "Speaking the fixed local introduction"
        FirstLifeExperienceStage.AWAITING_EXPLICIT_LISTEN -> "Introduction complete · microphone still requires your tap"
        FirstLifeExperienceStage.LISTENING -> "Listening in the current ephemeral session"
        FirstLifeExperienceStage.GREETING -> "Speaking the validated session-name greeting"
        FirstLifeExperienceStage.OBSERVATION_READY -> "Greeting complete · visible camera still requires your tap"
        FirstLifeExperienceStage.OBSERVING -> "Observing locally · waiting for fresh centered evidence"
        FirstLifeExperienceStage.PERSON_CENTERED -> "First Life path complete · fresh centered evidence"
        FirstLifeExperienceStage.CANCELLING -> "Stopping active resources"
        FirstLifeExperienceStage.BLOCKED -> "Blocked honestly · review the detailed panel below"
        FirstLifeExperienceStage.FAILED -> "Session ended after an unexpected failure"
    }
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xE80A111C), RoundedCornerShape(18.dp))
            .padding(16.dp)
            .semantics { stateDescription = status }
            .testTag("hihugo-first-life-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("First Life", color = Color(0xFFDCEAF4), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            status,
            color = if (experience.stage in setOf(FirstLifeExperienceStage.BLOCKED, FirstLifeExperienceStage.FAILED)) {
                Color(0xFFFFC66D)
            } else {
                Color(0xFF9BAABB)
            },
            fontSize = 12.sp,
            modifier = Modifier.testTag("hihugo-first-life-status"),
        )
        when (experience.stage) {
            FirstLifeExperienceStage.READY -> Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-first-life-start"),
            ) { Text("Start First Life") }
            FirstLifeExperienceStage.OBSERVATION_READY -> Button(
                onClick = onContinueWithCamera,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-first-life-observe"),
            ) { Text("Continue with visible camera") }
            else -> Unit
        }
        if (experience.stage != FirstLifeExperienceStage.READY) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-first-life-cancel"),
            ) { Text("Cancel First Life session") }
        }
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-first-life-reset"),
        ) { Text("Reset ephemeral session") }
        Text(
            "Reset clears only this run's name, camera, microphone, orientation and active-body selection. " +
                "It never replaces HUGO identity and never invents provider, permission, safety or success evidence.",
            color = Color(0xFF71869A),
            fontSize = 10.sp,
            modifier = Modifier.testTag("hihugo-first-life-boundary"),
        )
    }
}

@Composable
private fun HiHugoOrientationPanel(
    orientation: FirstLifeOrientationModel,
    bodySelected: Boolean,
    cameraActive: Boolean,
    mountingConfirmed: Boolean,
    safetyReady: Boolean,
    onPrepare: () -> Unit,
    onBeginHold: () -> Unit,
    onReleaseHold: () -> Unit,
    onCancel: () -> Unit,
    onEmergencyStop: () -> Unit,
) {
    val ready = bodySelected && cameraActive && mountingConfirmed && safetyReady
    val status = buildString {
        append(orientation.phase.name.lowercase().replace('_', ' '))
        orientation.terminalReason?.let { append(" · ").append(it.name.lowercase().replace('_', ' ')) }
    }
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xE80A111C), RoundedCornerShape(18.dp))
            .padding(16.dp)
            .semantics { stateDescription = status }
            .testTag("hihugo-orientation-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Bounded person orientation", color = Color(0xFFDCEAF4), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Hold only · left/right · magnitude 16 · ≤100 ms/pulse · ≤3 pulses · ≤2 s",
            color = Color(0xFF8FA8BC),
            fontSize = 10.sp,
            modifier = Modifier.testTag("hihugo-orientation-envelope"),
        )
        Text(
            "State: $status · pulses ${orientation.pulseCount}/3 · energized ${orientation.cumulativeEnergizedMs}/300 ms",
            color = if (orientation.phase == OrientationPhase.BLOCKED) Color(0xFFFFC66D) else Color(0xFF9BAABB),
            fontSize = 11.sp,
            modifier = Modifier.testTag("hihugo-orientation-status"),
        )
        Button(
            onClick = onPrepare,
            enabled = bodySelected && !orientation.active,
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-orientation-prepare"),
        ) { Text("Confirm secured mount + arm floor safety") }
        Text(
            "Mount: ${if (mountingConfirmed) "confirmed" else "not confirmed"} · " +
                "fresh lower-contact safety: ${if (safetyReady) "SAFE" else "not ready"} · " +
                "camera: ${if (cameraActive) "active" else "closed"}",
            color = if (ready) Color(0xFF75E6A6) else Color(0xFFFFC66D),
            fontSize = 10.sp,
            modifier = Modifier.testTag("hihugo-orientation-prerequisites"),
        )
        Surface(
            color = if (ready) Color(0xFF12645B) else Color(0xFF263342),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 58.dp)
                .pointerInput(ready) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        if (ready) {
                            onBeginHold()
                            try {
                                waitForUpOrCancellation()
                            } finally {
                                onReleaseHold()
                            }
                        } else {
                            waitForUpOrCancellation()
                        }
                    }
                }
                .semantics {
                    role = Role.Button
                    contentDescription = "Hold to orient HUGO toward the current person evidence"
                    stateDescription = if (ready) "ready; movement only while held" else "blocked by prerequisites"
                }
                .testTag("hihugo-orientation-hold"),
        ) {
            Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
                Text("PRESS AND HOLD TO ORIENT", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).testTag("hihugo-orientation-cancel"),
            ) { Text("Cancel") }
            Button(
                onClick = onEmergencyStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).testTag("hihugo-orientation-estop"),
            ) { Text("E-STOP") }
        }
        Text(
            "Body readiness alone grants no trust, authorization, safety approval, or execution authority.",
            color = Color(0xFF71869A),
            fontSize = 10.sp,
            modifier = Modifier.testTag("hihugo-orientation-boundary"),
        )
    }
}

@Composable
private fun HiHugoVoicePanel(
    voice: VoiceSessionModel,
    speechTerminal: Boolean,
    onStart: () -> Unit,
    onReviewSystemFallback: () -> Unit,
    onConsentSystemFallback: () -> Unit,
    onCancel: () -> Unit,
) {
    val active = voice.phase == VoiceSessionPhase.AWAITING_SYSTEM_CONSENT ||
        voice.phase == VoiceSessionPhase.AWAITING_PERMISSION ||
        voice.phase == VoiceSessionPhase.CHECKING_SUPPORT ||
        voice.phase == VoiceSessionPhase.LISTENING ||
        voice.phase == VoiceSessionPhase.PROCESSING
    val status = when (voice.phase) {
        VoiceSessionPhase.IDLE -> "Ready for one explicit Albanian voice session"
        VoiceSessionPhase.AWAITING_SYSTEM_CONSENT -> "English Android system recognition may send audio over the network"
        VoiceSessionPhase.AWAITING_PERMISSION -> "Waiting for microphone permission"
        VoiceSessionPhase.CHECKING_SUPPORT -> "Checking on-device Albanian recognition"
        VoiceSessionPhase.LISTENING -> "Listening locally — speak your name"
        VoiceSessionPhase.PROCESSING -> "Processing the bounded response locally"
        VoiceSessionPhase.COMPLETED -> if (voice.evidence?.tier == VoiceRecognitionTier.ON_DEVICE) {
            "Local speech recognized; transcript is not retained"
        } else {
            "System speech recognized for this session; transcript is not retained"
        }
        VoiceSessionPhase.BLOCKED -> voice.blockedReason.voiceBlockedLabel()
    }
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xE80A111C), RoundedCornerShape(18.dp))
            .padding(16.dp)
            .semantics { stateDescription = status }
            .testTag("hihugo-voice-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Voice / shqip", color = Color(0xFFDCEAF4), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(status, color = if (voice.phase == VoiceSessionPhase.BLOCKED) Color(0xFFFFC66D) else Color(0xFF9BAABB), fontSize = 12.sp, modifier = Modifier.testTag("hihugo-voice-status"))
        if (voice.phase == VoiceSessionPhase.AWAITING_SYSTEM_CONSENT) {
            Text(
                "Fallback disclosure: Albanian is unavailable on this phone. Android's English system recognizer may process microphone audio online. No audio or transcript is stored by HUGO. Consent applies only to this session.",
                color = Color(0xFFFFC66D),
                fontSize = 11.sp,
                modifier = Modifier.testTag("hihugo-system-voice-disclosure"),
            )
            Button(
                onClick = onConsentSystemFallback,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-consent-system-voice"),
            ) { Text("I consent for this session") }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-cancel-voice"),
            ) { Text("Do not use system recognition") }
        } else if (active) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-cancel-voice"),
            ) { Text("Cancel microphone") }
        } else {
            Button(
                onClick = onStart,
                enabled = speechTerminal,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-start-voice"),
            ) { Text(if (voice.phase == VoiceSessionPhase.IDLE) "Listen to my name" else "Start a new local session") }
            if (voice.phase == VoiceSessionPhase.BLOCKED && voice.blockedReason in setOf(
                    VoiceSessionBlockedReason.ON_DEVICE_RECOGNIZER_UNAVAILABLE,
                    VoiceSessionBlockedReason.ALBANIAN_MODEL_DOWNLOAD_REQUIRED,
                    VoiceSessionBlockedReason.ALBANIAN_MODEL_UNAVAILABLE,
                )
            ) {
                OutlinedButton(
                    onClick = onReviewSystemFallback,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-review-system-voice"),
                ) { Text("Review Android system fallback") }
            }
        }
        Text(
            "One visible session, max 8 seconds. No audio file, transcript history, or automatic cloud fallback.",
            color = Color(0xFF71869A),
            fontSize = 10.sp,
            modifier = Modifier.testTag("hihugo-voice-privacy"),
        )
    }
}

@Composable
private fun HiHugoSpeechPanel(
    speech: SpeechOutputModel,
    sessionNameAvailable: Boolean,
    onIntroduction: () -> Unit,
    onGreeting: () -> Unit,
    onCancel: () -> Unit,
    onInstallVoiceData: () -> Unit,
) {
    val active = speech.phase in SpeechOutputModel.ACTIVE_PHASES
    val status = when (speech.phase) {
        SpeechOutputPhase.IDLE -> "Local Albanian speech is ready to be checked"
        SpeechOutputPhase.CHECKING_SUPPORT -> "Checking exact installed sq-AL voice and data"
        SpeechOutputPhase.QUEUED -> "Albanian speech is queued after audio-focus acceptance"
        SpeechOutputPhase.SPEAKING -> "Speaking with the confirmed local Albanian voice"
        SpeechOutputPhase.COMPLETED -> "Local Albanian utterance completed"
        SpeechOutputPhase.BLOCKED -> speech.blockedReason.speechBlockedLabel()
    }
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xE80A111C), RoundedCornerShape(18.dp))
            .padding(16.dp)
            .semantics { stateDescription = status }
            .testTag("hihugo-speech-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("HUGO voice / shqip", color = Color(0xFFDCEAF4), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            status,
            color = if (speech.phase == SpeechOutputPhase.BLOCKED) Color(0xFFFFC66D) else Color(0xFF9BAABB),
            fontSize = 12.sp,
            modifier = Modifier.testTag("hihugo-speech-status"),
        )
        if (active) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-cancel-speech"),
            ) { Text("Stop speaking") }
        } else {
            Button(
                onClick = onIntroduction,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-speak-introduction"),
            ) { Text("Speak the Albanian introduction") }
            Button(
                onClick = onGreeting,
                enabled = sessionNameAvailable,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-speak-greeting"),
            ) { Text("Greet the current session name") }
        }
        if (speech.blockedReason == SpeechOutputBlockedReason.VOICE_DATA_MISSING ||
            speech.blockedReason == SpeechOutputBlockedReason.ALBANIAN_LANGUAGE_UNAVAILABLE
        ) {
            OutlinedButton(
                onClick = onInstallVoiceData,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-install-tts-data"),
            ) { Text("Open Android voice-data setup") }
        }
        Text(
            "Fixed local text only. No cloning, cloud synthesis, transcript history, or arbitrary speech.",
            color = Color(0xFF71869A),
            fontSize = 10.sp,
            modifier = Modifier.testTag("hihugo-speech-boundary"),
        )
    }
}

private fun SpeechOutputBlockedReason?.speechBlockedLabel(): String = when (this) {
    SpeechOutputBlockedReason.ENGINE_UNAVAILABLE -> "No Android text-to-speech engine is available"
    SpeechOutputBlockedReason.ALBANIAN_LANGUAGE_UNAVAILABLE -> "An exact Albanian sq-AL voice is unavailable"
    SpeechOutputBlockedReason.VOICE_DATA_MISSING -> "Local Albanian voice data must be installed"
    SpeechOutputBlockedReason.AUDIO_FOCUS_DENIED -> "Audio focus was denied"
    SpeechOutputBlockedReason.SYNTHESIS_FAILED -> "The local speech engine reported an error"
    SpeechOutputBlockedReason.CANCELLED -> "Speech was cancelled"
    SpeechOutputBlockedReason.BACKGROUNDED -> "Speech stopped when HUGO left the foreground"
    SpeechOutputBlockedReason.INTERRUPTED -> "Speech was interrupted before completion"
    null -> "Local speech is blocked"
}

@Composable
private fun HiHugoCameraPanel(
    camera: CameraSessionModel,
    onStart: () -> Unit,
    onClose: () -> Unit,
    preview: @Composable () -> Unit,
) {
    val active = camera.phase in CameraSessionModel.ACTIVE_PHASES
    val status = when (camera.phase) {
        CameraSessionPhase.IDLE -> "Front camera is closed"
        CameraSessionPhase.AWAITING_PERMISSION -> "Waiting for explicit camera permission"
        CameraSessionPhase.OPENING -> "Opening the visible front-camera preview"
        CameraSessionPhase.OBSERVING -> "Visible front-camera observation is active"
        CameraSessionPhase.BLOCKED -> camera.blockedReason.cameraBlockedLabel()
    }
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xE80A111C), RoundedCornerShape(18.dp))
            .padding(16.dp)
            .semantics { stateDescription = status }
            .testTag("hihugo-camera-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Vision / front camera", color = Color(0xFFDCEAF4), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            status,
            color = if (camera.phase == CameraSessionPhase.BLOCKED) Color(0xFFFFC66D) else Color(0xFF9BAABB),
            fontSize = 12.sp,
            modifier = Modifier.testTag("hihugo-camera-status"),
        )
        if (camera.phase == CameraSessionPhase.OPENING || camera.phase == CameraSessionPhase.OBSERVING) {
            Box(Modifier.fillMaxWidth().height(240.dp).testTag("hihugo-camera-preview")) { preview() }
        }
        if (camera.phase == CameraSessionPhase.OBSERVING) {
            Text(
                "CAMERA ACTIVE · local ephemeral frames",
                color = Color(0xFF75E6A6),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("hihugo-camera-active-indicator"),
            )
            camera.lastFrameMetadata?.let { frame ->
                Text(
                    "Frame stream current · front · ${frame.rotationDegrees}° · BODY x verified · preview mirrored",
                    color = Color(0xFF8FA8BC),
                    fontSize = 10.sp,
                    modifier = Modifier.testTag("hihugo-camera-frame-current"),
                )
            }
            camera.personEvidence?.let { person ->
                Text(
                    "Person evidence · ${person.direction} · ${person.reason} · " +
                        (person.confidence?.let { "%.0f%%".format(it * 100f) } ?: "no confidence"),
                    color = if (person.direction == io.redi.robot.firstlife.vision.PersonDirection.UNKNOWN) {
                        Color(0xFFFFC66D)
                    } else {
                        Color(0xFF75E6A6)
                    },
                    fontSize = 11.sp,
                    modifier = Modifier.testTag("hihugo-person-evidence"),
                )
            }
            camera.detectorTelemetry?.let { telemetry ->
                Text(
                    "On-device detector · ${telemetry.inferenceLatencyMs} ms · no identity",
                    color = Color(0xFF8FA8BC),
                    fontSize = 10.sp,
                    modifier = Modifier.testTag("hihugo-person-detector-latency"),
                )
            }
            camera.detectorPerformance?.let { performance ->
                Text(
                    "Detector budget · n=${performance.sampleCount} · median=${performance.medianLatencyMs} ms · " +
                        "p95=${performance.p95LatencyMs} ms",
                    color = if (performance.medianLatencyMs <= 100 && performance.p95LatencyMs <= 150) {
                        Color(0xFF75E6A6)
                    } else {
                        Color(0xFFFFC66D)
                    },
                    fontSize = 10.sp,
                    modifier = Modifier.testTag("hihugo-person-detector-performance"),
                )
            }
        }
        if (active) {
            OutlinedButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-close-camera"),
            ) { Text("Close camera") }
        } else {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-open-camera"),
            ) { Text("Open visible front camera") }
        }
        Text(
            "No photo, video, face identity, cloud upload, frame log, or hidden capture.",
            color = Color(0xFF71869A),
            fontSize = 10.sp,
            modifier = Modifier.testTag("hihugo-camera-privacy"),
        )
    }
}

private fun CameraBlockedReason?.cameraBlockedLabel(): String = when (this) {
    CameraBlockedReason.PERMISSION_DENIED -> "Camera permission was denied"
    CameraBlockedReason.PERMISSION_PERMANENTLY_DENIED -> "Camera permission is blocked in Android settings"
    CameraBlockedReason.FRONT_CAMERA_UNAVAILABLE -> "The front camera is unavailable"
    CameraBlockedReason.BIND_FAILED -> "The visible camera session could not be opened"
    CameraBlockedReason.DETECTOR_UNAVAILABLE -> "The local person detector is unavailable"
    CameraBlockedReason.CANCELLED -> "The camera session was closed"
    CameraBlockedReason.BACKGROUNDED -> "The camera was released when HUGO left the foreground"
    null -> "Camera session blocked"
}

private fun VoiceSessionBlockedReason?.voiceBlockedLabel(): String = when (this) {
    VoiceSessionBlockedReason.PERMISSION_DENIED -> "Microphone permission was denied"
    VoiceSessionBlockedReason.PERMISSION_PERMANENTLY_DENIED -> "Microphone permission is blocked in Android settings"
    VoiceSessionBlockedReason.ON_DEVICE_RECOGNIZER_UNAVAILABLE -> "On-device speech recognition is unavailable"
    VoiceSessionBlockedReason.ALBANIAN_MODEL_DOWNLOAD_REQUIRED -> "The local Albanian speech model must be installed explicitly"
    VoiceSessionBlockedReason.ALBANIAN_MODEL_UNAVAILABLE -> "The local Albanian speech model is unavailable"
    VoiceSessionBlockedReason.NO_MATCH -> "No bounded speech match was produced"
    VoiceSessionBlockedReason.TIMEOUT -> "The 8-second listening session timed out"
    VoiceSessionBlockedReason.CANCELLED -> "The voice session was cancelled"
    VoiceSessionBlockedReason.BACKGROUNDED -> "The microphone was released when HUGO left the foreground"
    VoiceSessionBlockedReason.RECOGNIZER_BUSY -> "The Android speech recognizer is busy"
    VoiceSessionBlockedReason.AUDIO_UNAVAILABLE -> "Microphone audio is unavailable"
    VoiceSessionBlockedReason.NETWORK_ATTEMPT_REJECTED -> "The local recognizer attempted an unavailable network path"
    VoiceSessionBlockedReason.RECOGNIZER_FAILURE -> "The local recognizer ended with an error"
    null -> "Voice session blocked"
}

@Composable
private fun HiHugoCodeBackdrop(
    model: HiHugoPresentationModel,
    bodyStage: HiHugoBodyStage,
    animationActive: Boolean,
) {
    val state = model.faceState
    val moving = animationActive && (
        state == HiHugoFaceState.BOOTING ||
            state == HiHugoFaceState.LISTENING ||
            state == HiHugoFaceState.THINKING ||
            state == HiHugoFaceState.SPEAKING ||
            state == HiHugoFaceState.OBSERVING ||
            bodyStage == HiHugoBodyStage.CONNECTING
        )
    var frame by remember(state, bodyStage) { mutableStateOf(0) }
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        while (true) {
            delay(72)
            frame = (frame + 1) % 10_000
        }
    }
    Canvas(Modifier.fillMaxSize().testTag("hihugo-code-backdrop")) {
        val binaryLines = listOf(
            "110001010010110100011001010111001010001101001011010001100101011100101101000110010101",
            "001101101000101011010010011101001100101000110101101001001110100110010010110100011010",
            "101000111010010110001101001010011100101101000110100101001110010110101100010100101101",
            "010110001101001011100100101101000110010101110010100011010010110100010101110010100011",
        )
        val rows = 58
        val rowHeight = size.height / 54f
        val error = state == HiHugoFaceState.ERROR
        val booting = state == HiHugoFaceState.BOOTING
        val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = size.width * .0175f
        }
        repeat(rows) { row ->
            val speed = if (booting) 13f else 2.2f + (row % 4) * .55f
            val travel = if (moving) frame * speed else 0f
            val y = if (booting) {
                ((row * rowHeight + travel) % (size.height + rowHeight)) - rowHeight
            } else {
                row * rowHeight + rowHeight * .72f
            }
            val x = if (moving && !booting) {
                -((frame * (2 + row % 3) + row * 23) % 170).toFloat()
            } else {
                -18f
            }
            val lineIndex = (row + if (moving) frame / 8 else 0) % binaryLines.size
            paint.color = if (error) 0xFFFF1738.toInt() else 0xFF00D85A.toInt()
            paint.alpha = when {
                error -> 66 + (row * 17 % 42)
                moving -> 31 + (row * 13 % 36)
                else -> 16 + (row * 11 % 17)
            }
            val line = binaryLines[lineIndex]
            val lineWidth = paint.measureText(line)
            var drawX = x
            while (drawX < size.width) {
                drawContext.canvas.nativeCanvas.drawText(line, drawX, y, paint)
                drawX += lineWidth
            }
        }
    }
}

@Composable
private fun HiHugoBodyPanel(
    body: HiHugoBodyPresentationModel,
    onSelectActiveBody: () -> Unit,
    onDeactivateActiveBody: () -> Unit,
) {
    body.blocked?.let {
        HiHugoBlockedCard(it, Modifier.testTag("hihugo-provider-blocked"))
        return
    }
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xFF0D1422), RoundedCornerShape(18.dp))
            .padding(16.dp)
            .semantics { stateDescription = body.statusLabel }
            .testTag("hihugo-body-panel"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val title = if (body.stage == HiHugoBodyStage.NO_BODY) {
            "Body / provider: not connected"
        } else {
            "Body / provider: ${body.providerLabel ?: "redacted provider"}"
        }
        Text(title, color = Color(0xFFDCEAF4), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(body.statusLabel, color = Color(0xFF9BAABB), fontSize = 13.sp, modifier = Modifier.testTag("hihugo-body-status"))
        if (body.stage != HiHugoBodyStage.NO_BODY) {
            Text(
                if (body.negotiationFresh) "Negotiation: current" else "Negotiation: not current",
                color = if (body.negotiationFresh) Color(0xFF75E6A6) else Color(0xFFFFC66D),
                fontSize = 12.sp,
                modifier = Modifier.testTag("hihugo-negotiation-status"),
            )
        }
        if (body.capabilities.isNotEmpty()) {
            Column(Modifier.testTag("hihugo-capability-list"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Negotiated capabilities", color = Color(0xFFAAC0D1), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                body.capabilities.forEach { capability ->
                    Text("• $capability", color = Color(0xFF8FA8BC), fontSize = 11.sp)
                }
            }
        }
        when {
            body.canSelect -> Button(
                onClick = onSelectActiveBody,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-select-active-body"),
            ) { Text("Select as active body") }
            body.canDeactivate -> OutlinedButton(
                onClick = onDeactivateActiveBody,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("hihugo-deactivate-active-body"),
            ) { Text("Deactivate body") }
        }
        if (body.stage == HiHugoBodyStage.AVAILABLE || body.stage == HiHugoBodyStage.SELECTED) {
            Text(
                "Selection grants no trust, authorization, safety approval, or execution authority.",
                color = Color(0xFF71869A),
                fontSize = 10.sp,
                modifier = Modifier.testTag("hihugo-selection-boundary"),
            )
        }
    }
}

/** Adapter from the accepted H033 model into FL008's provider-neutral presentation boundary. */
fun ProviderBlockedUiState.toHiHugoBlockedPresentation(): HiHugoBlockedPresentation {
    val content = textContent()
    return HiHugoBlockedPresentation(
        heading = content.heading,
        providerLabel = content.providerLabel,
        sections = content.sections,
        details = content.details,
        resolutionLabel = content.resolutionLabel,
    )
}

@Composable
private fun HiHugoBlockedCard(state: HiHugoBlockedPresentation, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.heading, style = MaterialTheme.typography.headlineSmall)
            state.providerLabel?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            state.sections.forEach { (label, value) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.details.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Text(state.resolutionLabel, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun accentColor(accent: HiHugoAccent): Color = when (accent) {
    HiHugoAccent.CYAN -> Color(0xFF5DE7FF)
    HiHugoAccent.VIOLET -> Color(0xFFB99BFF)
    HiHugoAccent.BLUE -> Color(0xFF7BB7FF)
    HiHugoAccent.GREEN -> Color(0xFF75E6A6)
    HiHugoAccent.AMBER -> Color(0xFFFFC66D)
    HiHugoAccent.RED -> Color(0xFFFF7F8D)
}
