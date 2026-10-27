package io.redi.robot.feature.hihugo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.redi.robot.feature.hihugo.presentation.HiHugoBootstrapProjection
import io.redi.robot.feature.hihugo.presentation.HiHugoFaceState
import io.redi.robot.feature.hihugo.presentation.HiHugoBodyPresentationModel
import io.redi.robot.feature.hihugo.presentation.HiHugoBodyStage
import io.redi.robot.feature.hihugo.presentation.HiHugoPresentationEvent
import io.redi.robot.feature.hihugo.presentation.HiHugoPresentationReducer
import io.redi.robot.firstlife.identity.HugoIdentityBootstrapSnapshot
import io.redi.robot.firstlife.experience.FirstLifeExperienceSnapshot
import io.redi.robot.firstlife.experience.FirstLifeExperienceStage
import io.redi.robot.firstlife.voice.VoiceSessionModel
import io.redi.robot.firstlife.voice.VoiceSessionPhase
import io.redi.robot.firstlife.voice.SpeechOutputModel
import io.redi.robot.firstlife.voice.SpeechOutputPhase
import io.redi.robot.firstlife.vision.CameraSessionModel
import io.redi.robot.firstlife.vision.CameraSessionPhase
import io.redi.robot.firstlife.orientation.FirstLifeOrientationModel
import io.redi.robot.firstlife.interaction.FirstLifePresentationIntent
import kotlinx.coroutines.delay

@Composable
fun HiHugoEntry(
    bootstrapSnapshot: HugoIdentityBootstrapSnapshot,
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
    onExpireActiveBody: () -> Unit = {},
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
    developerControlsEnabled: Boolean,
    reducedMotion: Boolean,
    nowElapsedMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    val bootstrapModel = remember(bootstrapSnapshot) { HiHugoBootstrapProjection.project(bootstrapSnapshot) }
    var selectedStateName by rememberSaveable { mutableStateOf(bootstrapModel.faceState.name) }
    var sequence by rememberSaveable { mutableLongStateOf(0L) }
    val selectedState = HiHugoFaceState.valueOf(selectedStateName)
    val runtimeFaceState = if (bodyPresentation.stage == HiHugoBodyStage.NO_BODY) {
        bootstrapModel.faceState
    } else {
        bodyPresentation.faceState
    }
    val voiceFaceState = when (voiceSession.phase) {
        VoiceSessionPhase.LISTENING -> HiHugoFaceState.LISTENING
        VoiceSessionPhase.PROCESSING,
        VoiceSessionPhase.CHECKING_SUPPORT,
        -> HiHugoFaceState.THINKING
        VoiceSessionPhase.BLOCKED -> HiHugoFaceState.BLOCKED
        VoiceSessionPhase.AWAITING_SYSTEM_CONSENT -> HiHugoFaceState.BLOCKED
        VoiceSessionPhase.IDLE,
        VoiceSessionPhase.AWAITING_PERMISSION,
        VoiceSessionPhase.COMPLETED,
        -> null
    }
    val speechFaceState = when (speechOutput.phase) {
        SpeechOutputPhase.SPEAKING -> HiHugoFaceState.SPEAKING
        SpeechOutputPhase.CHECKING_SUPPORT,
        SpeechOutputPhase.QUEUED,
        -> HiHugoFaceState.THINKING
        SpeechOutputPhase.BLOCKED -> HiHugoFaceState.BLOCKED
        SpeechOutputPhase.IDLE,
        SpeechOutputPhase.COMPLETED,
        -> null
    }
    val cameraFaceState = when (cameraSession.phase) {
        CameraSessionPhase.OPENING -> HiHugoFaceState.THINKING
        CameraSessionPhase.OBSERVING -> HiHugoFaceState.OBSERVING
        CameraSessionPhase.BLOCKED -> HiHugoFaceState.BLOCKED
        CameraSessionPhase.IDLE,
        CameraSessionPhase.AWAITING_PERMISSION,
        -> null
    }
    val experienceFaceState = when (experience?.stage) {
        FirstLifeExperienceStage.FAILED -> HiHugoFaceState.ERROR
        FirstLifeExperienceStage.BLOCKED -> HiHugoFaceState.BLOCKED
        else -> when (experience?.presentationIntent) {
            FirstLifePresentationIntent.LISTENING -> HiHugoFaceState.LISTENING
            FirstLifePresentationIntent.THINKING -> HiHugoFaceState.THINKING
            FirstLifePresentationIntent.SPEAKING -> HiHugoFaceState.SPEAKING
            FirstLifePresentationIntent.BODY_READY -> HiHugoFaceState.BODY_READY
            FirstLifePresentationIntent.BLOCKED -> HiHugoFaceState.BLOCKED
            FirstLifePresentationIntent.ERROR -> HiHugoFaceState.ERROR
            FirstLifePresentationIntent.IDLE,
            null,
            -> null
        }
    }
    val activityFaceState = listOfNotNull(
        experienceFaceState,
        voiceFaceState,
        speechFaceState,
        cameraFaceState,
    ).maxByOrNull {
        when (it) {
            HiHugoFaceState.ERROR -> 7
            HiHugoFaceState.BLOCKED -> 6
            HiHugoFaceState.SPEAKING -> 5
            HiHugoFaceState.LISTENING -> 4
            HiHugoFaceState.THINKING -> 3
            HiHugoFaceState.OBSERVING -> 2
            else -> 1
        }
    }
    val model = remember(bootstrapModel, selectedState, sequence, runtimeFaceState, activityFaceState) {
        if (activityFaceState != null) {
            bootstrapModel.copy(faceState = activityFaceState)
        } else if (sequence == 0L) bootstrapModel.copy(faceState = runtimeFaceState) else HiHugoPresentationReducer.reduce(
            bootstrapModel,
            HiHugoPresentationEvent(sequence, selectedState),
        ).model
    }

    androidx.compose.runtime.LaunchedEffect(bodyPresentation.stage, bodyPresentation.selectionExpiresAt) {
        val expiry = bodyPresentation.selectionExpiresAt ?: return@LaunchedEffect
        if (bodyPresentation.stage != HiHugoBodyStage.SELECTED) return@LaunchedEffect
        delay((expiry.value - nowElapsedMs()).coerceAtLeast(0L))
        onExpireActiveBody()
    }

    HiHugoScreen(
        model = model,
        experience = experience,
        onStartExperience = onStartExperience,
        onCancelExperience = onCancelExperience,
        onResetExperience = onResetExperience,
        bodyPresentation = bodyPresentation,
        providerSetup = providerSetup,
        onDiscoverProvider = onDiscoverProvider,
        onConnectProvider = onConnectProvider,
        onDisconnectProvider = onDisconnectProvider,
        onSelectActiveBody = onSelectActiveBody,
        onDeactivateActiveBody = onDeactivateActiveBody,
        voiceSession = voiceSession,
        speechOutput = speechOutput,
        sessionDisplayNameAvailable = sessionDisplayNameAvailable,
        voiceInputAllowed = voiceInputAllowed,
        onStartVoiceSession = onStartVoiceSession,
        onReviewSystemVoiceFallback = onReviewSystemVoiceFallback,
        onConsentSystemVoiceFallback = onConsentSystemVoiceFallback,
        onCancelVoiceSession = onCancelVoiceSession,
        onSpeakIntroduction = onSpeakIntroduction,
        onSpeakGreeting = onSpeakGreeting,
        onCancelSpeech = onCancelSpeech,
        onInstallVoiceData = onInstallVoiceData,
        cameraSession = cameraSession,
        onStartCameraSession = onStartCameraSession,
        onCloseCameraSession = onCloseCameraSession,
        cameraPreview = cameraPreview,
        orientation = orientation,
        orientationMountingConfirmed = orientationMountingConfirmed,
        orientationSafetyReady = orientationSafetyReady,
        onPrepareOrientation = onPrepareOrientation,
        onBeginOrientationHold = onBeginOrientationHold,
        onReleaseOrientationHold = onReleaseOrientationHold,
        onCancelOrientation = onCancelOrientation,
        onOrientationEmergencyStop = onOrientationEmergencyStop,
        onDeveloperStateRequested = { state ->
            sequence += 1
            selectedStateName = state.name
        },
        developerControlsEnabled = developerControlsEnabled && bodyPresentation.stage == HiHugoBodyStage.NO_BODY,
        reducedMotion = reducedMotion,
    )
}
