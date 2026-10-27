package io.redi.robot

import android.content.Intent
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.redi.robot.platform.permissions.PermissionDecisionState
import io.redi.robot.platform.permissions.allPermissionsGranted
import io.redi.robot.platform.permissions.canRequestPermissionAgain
import io.redi.robot.platform.permissions.evaluatePermissionState
import io.redi.robot.platform.permissions.hasPermanentlyDenied
import io.redi.robot.platform.permissions.permissionRequirementForRuntime
import io.redi.robot.feature.diagnostic.BleDiagnosticUiState
import io.redi.robot.feature.diagnostic.DiagnosticBleDevice
import io.redi.robot.platform.diagnostic.ReadOnlyBleDiagnosticController
import io.redi.robot.platform.diagnostic.BoundDiagnosticConnectionClient
import io.redi.robot.platform.diagnostic.DiagnosticConnectionClient
import io.redi.robot.platform.service.ConnectedDeviceService
import io.redi.robot.platform.service.hasCurrentLowerContactEvidenceForOrientation
import io.redi.robot.body.looi.protocol.handshake.HandshakeCandidateId
import io.redi.robot.feature.manualcontrol.ManualDriveCommandSink
import io.redi.robot.feature.manualcontrol.ManualDriveController
import io.redi.robot.feature.manualcontrol.ManualDriveControls
import io.redi.robot.feature.manualcontrol.ManualDriveUiState
import io.redi.robot.feature.manualcontrol.ManualHeadCommandSink
import io.redi.robot.feature.manualcontrol.ManualHeadControls
import io.redi.robot.feature.manualcontrol.ManualHeadUiState
import io.redi.robot.feature.manualcontrol.ManualLightCommandSink
import io.redi.robot.feature.manualcontrol.ManualLightControls
import io.redi.robot.feature.manualcontrol.ManualLightUiState
import io.redi.robot.feature.manualcontrol.T032AcceptanceState
import io.redi.robot.feature.manualcontrol.T032VerificationMode
import io.redi.robot.feature.hihugo.ui.HiHugoEntry
import io.redi.robot.feature.hihugo.ui.HiHugoProviderSetupPresentation
import io.redi.robot.feature.hihugo.ui.toHiHugoBlockedPresentation
import io.redi.robot.feature.hihugo.presentation.HiHugoBodyPresentationProjection
import io.redi.robot.firstlife.identity.HugoIdentityBootstrapSnapshot
import io.redi.robot.firstlife.experience.FirstLifeExperienceCleanupReason
import io.redi.robot.firstlife.experience.FirstLifeExperienceCoordinator
import io.redi.robot.firstlife.experience.FirstLifeExperienceEffectPort
import io.redi.robot.firstlife.experience.FirstLifeExperienceSnapshot
import io.redi.robot.firstlife.experience.FirstLifeExperienceStage
import io.redi.robot.firstlife.runtime.FirstLifeRuntimeSnapshot
import io.redi.robot.firstlife.runtime.FirstLifeOrientationSessionCoordinator
import io.redi.robot.firstlife.orientation.FirstLifeOrientationModel
import io.redi.robot.firstlife.orientation.OrientationAttemptId
import io.redi.robot.firstlife.orientation.OrientationPrerequisites
import io.redi.robot.firstlife.voice.VoiceSessionCoordinator
import io.redi.robot.firstlife.voice.VoiceSessionModel
import io.redi.robot.firstlife.voice.VoiceSessionPhase
import io.redi.robot.firstlife.voice.VoiceSessionRequestId
import io.redi.robot.firstlife.voice.LocalSpeechText
import io.redi.robot.firstlife.voice.SpeechOutputCoordinator
import io.redi.robot.firstlife.voice.SpeechOutputKind
import io.redi.robot.firstlife.voice.SpeechOutputModel
import io.redi.robot.firstlife.voice.SpeechOutputRequestId
import io.redi.robot.firstlife.interaction.FirstLifeBodyStatus
import io.redi.robot.firstlife.interaction.FirstLifeSessionId
import io.redi.robot.firstlife.interaction.FirstLifeSpeechText
import io.redi.robot.feature.providers.ProviderBlockedUiState
import io.redi.robot.platform.voice.android.AndroidOnDeviceSpeechRecognizer
import io.redi.robot.platform.voice.android.AndroidLocalTextToSpeech
import io.redi.robot.firstlife.vision.CameraSessionCoordinator
import io.redi.robot.firstlife.vision.CameraSessionModel
import io.redi.robot.firstlife.vision.CameraSessionRequestId
import io.redi.robot.platform.vision.android.AndroidCameraXSession
import io.redi.robot.platform.vision.android.AndroidFrontCameraPreview
import io.redi.robot.platform.service.FirstLifeProviderSetupController
import io.redi.robot.platform.service.FirstLifeProviderSetupFailure
import io.redi.robot.platform.service.FirstLifeProviderSetupStage
import io.redi.robot.platform.service.FirstLifeProviderSetupState
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.delay
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bootstrapSnapshot = (application as HugoFirstLifeApplication).hugoIdentityBootstrapSnapshot
        val reducedMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        setContent {
            HiHugoFirstLifeScreen(
                this,
                bootstrapSnapshot,
                applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                reducedMotion,
            )
        }
    }
}

@Composable
private fun HiHugoFirstLifeScreen(
    activity: ComponentActivity,
    bootstrapSnapshot: HugoIdentityBootstrapSnapshot,
    developerControlsEnabled: Boolean,
    reducedMotion: Boolean,
) {
    var binder by remember { mutableStateOf<ConnectedDeviceService.ConnectionBinder?>(null) }
    var runtime by remember { mutableStateOf<FirstLifeRuntimeSnapshot?>(null) }
    var blocked by remember { mutableStateOf<ProviderBlockedUiState?>(null) }
    var voiceModel by remember { mutableStateOf(VoiceSessionModel.IDLE) }
    var speechModel by remember { mutableStateOf(SpeechOutputModel.IDLE) }
    var cameraModel by remember { mutableStateOf(CameraSessionModel.IDLE) }
    var t032State by remember { mutableStateOf(T032AcceptanceState()) }
    var orientationModel by remember { mutableStateOf(FirstLifeOrientationModel.IDLE) }
    var experienceSnapshot by remember { mutableStateOf<FirstLifeExperienceSnapshot?>(null) }
    var providerSetupState by remember { mutableStateOf(FirstLifeProviderSetupState()) }
    var providerSetupController by remember { mutableStateOf<FirstLifeProviderSetupController?>(null) }
    var orientationMountingConfirmed by remember { mutableStateOf(false) }
    var orientationMountingExpiresAtMs by remember { mutableStateOf<Long?>(null) }
    var pendingCameraPermission by remember { mutableStateOf<CameraSessionRequestId?>(null) }
    var cameraDenialCount by rememberSaveable { mutableIntStateOf(0) }
    var pendingPermissionRequest by remember { mutableStateOf<VoiceSessionRequestId?>(null) }
    var microphoneDenialCount by rememberSaveable { mutableIntStateOf(0) }
    val currentBinder = rememberUpdatedState(binder)
    val voiceCoordinatorSlot = remember { arrayOfNulls<VoiceSessionCoordinator>(1) }
    val speechCoordinatorSlot = remember { arrayOfNulls<SpeechOutputCoordinator>(1) }
    val cameraCoordinatorSlot = remember { arrayOfNulls<CameraSessionCoordinator>(1) }
    val orientationCoordinatorSlot = remember { arrayOfNulls<FirstLifeOrientationSessionCoordinator>(1) }
    val experienceCoordinatorSlot = remember { arrayOfNulls<FirstLifeExperienceCoordinator>(1) }
    val providerSetupControllerSlot = remember { arrayOfNulls<FirstLifeProviderSetupController>(1) }

    val experienceEffects = remember(activity) {
        object : FirstLifeExperienceEffectPort {
            override fun speak(kind: SpeechOutputKind, speech: FirstLifeSpeechText): Boolean =
                speechCoordinatorSlot[0]?.speak(kind, LocalSpeechText.from(speech.text)) == true

            override fun startNameInput(): Boolean {
                voiceCoordinatorSlot[0]?.startExplicitSession() ?: return false
                return true
            }

            override fun cancelNameInput(background: Boolean) {
                voiceCoordinatorSlot[0]?.let { if (background) it.background() else it.cancel() }
            }

            override fun cancelSpeech(background: Boolean) {
                speechCoordinatorSlot[0]?.cancel(background)
            }

            override fun openVisibleCamera(): Boolean =
                cameraCoordinatorSlot[0]?.startExplicitSession() == true

            override fun closeVisibleCamera(background: Boolean) {
                cameraCoordinatorSlot[0]?.close(background)
            }

            override fun stopOrientation(reason: FirstLifeExperienceCleanupReason) {
                orientationCoordinatorSlot[0]?.let {
                    if (reason == FirstLifeExperienceCleanupReason.BACKGROUND ||
                        reason == FirstLifeExperienceCleanupReason.DISPOSE
                    ) {
                        it.background()
                    } else {
                        it.cancel()
                    }
                }
            }

            override fun deactivateActiveBody() {
                currentBinder.value?.deactivateFirstLifeActiveBody()
            }
        }
    }
    val experienceCoordinator = remember(bootstrapSnapshot.identity, experienceEffects) {
        FirstLifeExperienceCoordinator(
            hugoIdentity = bootstrapSnapshot.identity,
            sessionIdFactory = { FirstLifeSessionId.fromUuid(UUID.randomUUID()) },
            nowMs = { android.os.SystemClock.elapsedRealtime() },
            nowNanos = { android.os.SystemClock.elapsedRealtimeNanos() },
            effects = experienceEffects,
            onSnapshotChanged = { next -> activity.runOnUiThread { experienceSnapshot = next } },
        )
    }
    experienceCoordinatorSlot[0] = experienceCoordinator
    LaunchedEffect(experienceCoordinator) { experienceSnapshot = experienceCoordinator.snapshot }

    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val requestId = pendingPermissionRequest
        pendingPermissionRequest = null
        if (requestId != null) {
            val canAskAgain = !granted && (
                activity.shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO) ||
                    microphoneDenialCount == 0
                )
            if (granted) microphoneDenialCount = 0 else microphoneDenialCount++
            voiceCoordinatorSlot[0]?.onPermissionResult(
                requestId = requestId,
                granted = granted,
                canAskAgain = canAskAgain,
            )
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val requestId = pendingCameraPermission
        pendingCameraPermission = null
        if (requestId != null) {
            val canAskAgain = !granted && (
                activity.shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA) ||
                    cameraDenialCount == 0
                )
            if (granted) cameraDenialCount = 0 else cameraDenialCount++
            cameraCoordinatorSlot[0]?.onPermissionResult(requestId, granted, canAskAgain)
        }
    }
    val voicePort = remember(activity) { AndroidOnDeviceSpeechRecognizer(activity.applicationContext) }
    val voiceCoordinator = remember(activity, voicePort) {
        VoiceSessionCoordinator(
            port = voicePort,
            nowMs = { android.os.SystemClock.elapsedRealtime() },
            requestIdFactory = { VoiceSessionRequestId(UUID.randomUUID().toString()) },
            onPermissionRequested = { requestId ->
                if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    voiceCoordinatorSlot[0]?.onPermissionResult(requestId, granted = true, canAskAgain = false)
                } else {
                    pendingPermissionRequest = requestId
                    microphonePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            },
            onTranscript = { transcript -> experienceCoordinatorSlot[0]?.onTranscript(transcript.value) },
            onModelChanged = { next -> activity.runOnUiThread {
                voiceModel = next
                experienceCoordinatorSlot[0]?.onVoiceSessionChanged(next)
            } },
        )
    }
    voiceCoordinatorSlot[0] = voiceCoordinator

    val speechPort = remember(activity) { AndroidLocalTextToSpeech(activity.applicationContext) }
    val speechCoordinator = remember(activity, speechPort) {
        SpeechOutputCoordinator(
            port = speechPort,
            requestIdFactory = { SpeechOutputRequestId(UUID.randomUUID().toString()) },
            onModelChanged = { next -> activity.runOnUiThread {
                speechModel = next
                experienceCoordinatorSlot[0]?.onSpeechOutputChanged(next)
            } },
        )
    }
    speechCoordinatorSlot[0] = speechCoordinator
    val cameraPort = remember(activity) { AndroidCameraXSession(activity.applicationContext, activity) }
    val cameraCoordinator = remember(activity, cameraPort) {
        CameraSessionCoordinator(
            port = cameraPort,
            requestIdFactory = { CameraSessionRequestId(UUID.randomUUID().toString()) },
            onPermissionRequested = { requestId ->
                if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    cameraCoordinatorSlot[0]?.onPermissionResult(requestId, granted = true, canAskAgain = false)
                } else {
                    pendingCameraPermission = requestId
                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                }
            },
            onModelChanged = { next -> activity.runOnUiThread {
                cameraModel = next
                experienceCoordinatorSlot[0]?.onCameraSessionChanged(next)
            } },
        )
    }
    cameraCoordinatorSlot[0] = cameraCoordinator

    val orientationCoordinator = remember(activity) {
        FirstLifeOrientationSessionCoordinator(
            execute = { effect ->
                currentBinder.value?.dispatchFirstLifeOrientationEffect(effect)?.outcome
                    ?: io.redi.robot.firstlife.orientation.OrientationEffectOutcome.STALE_CORRELATION
            },
            nowElapsedMs = { android.os.SystemClock.elapsedRealtime() },
            nowElapsedNanos = { android.os.SystemClock.elapsedRealtimeNanos() },
            attemptId = { OrientationAttemptId(UUID.randomUUID().toString()) },
            onModelChanged = { next -> activity.runOnUiThread {
                orientationModel = next
                currentBinder.value?.recordFirstLifeOrientationModel(next)
                experienceCoordinatorSlot[0]?.onOrientationChanged(next)
            } },
            onAttemptEnded = { currentBinder.value?.endFirstLifeOrientationHold() },
        )
    }
    orientationCoordinatorSlot[0] = orientationCoordinator

    fun currentOrientationPrerequisites(authorityCurrent: Boolean): OrientationPrerequisites {
        val currentSafety = binder?.t032State()?.also { t032State = it } ?: t032State
        return OrientationPrerequisites(
            bodyReady = runtime?.bodyReady == true,
            activeBodySelected = runtime?.activeBodySelected == true,
            mountingAligned = orientationMountingConfirmed &&
                (orientationMountingExpiresAtMs ?: 0L) > android.os.SystemClock.elapsedRealtime(),
            connectionCurrent = runtime?.connected == true,
            negotiationCurrent = runtime?.negotiated == true,
            authorityGrantCurrent = authorityCurrent,
            cameraSessionActive = cameraModel.phase == io.redi.robot.firstlife.vision.CameraSessionPhase.OBSERVING,
            floorModeArmed = currentSafety.mode == T032VerificationMode.FLOOR,
            lowerContactKnown = currentSafety.sensorStateKnown,
            // FED9 contact is a verified generation-scoped transition event, not a heartbeat.
            // The T032 safety/runtime path remains the final authority for every move.
            lowerContactFresh = currentSafety.hasCurrentLowerContactEvidenceForOrientation(),
            lowerContactSafe = currentSafety.lowerContactSafe == true,
            emergencyStopClear = !currentSafety.emergencyStopLatched,
            personEvidence = cameraModel.personEvidence,
        )
    }

    LaunchedEffect(voiceModel.activeRequest?.id, voiceModel.activeRequest?.deadlineAtMs, voiceModel.phase) {
        val request = voiceModel.activeRequest ?: return@LaunchedEffect
        if (voiceModel.phase != VoiceSessionPhase.AWAITING_PERMISSION &&
            voiceModel.phase != VoiceSessionPhase.CHECKING_SUPPORT &&
            voiceModel.phase != VoiceSessionPhase.LISTENING &&
            voiceModel.phase != VoiceSessionPhase.PROCESSING
        ) return@LaunchedEffect
        delay((request.deadlineAtMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        voiceCoordinator.checkDeadline()
    }

    LaunchedEffect(cameraModel.activeRequest?.id, cameraModel.phase) {
        while (cameraModel.phase == io.redi.robot.firstlife.vision.CameraSessionPhase.OBSERVING) {
            delay(100L)
            cameraCoordinator.expirePersonEvidence()
        }
    }

    LaunchedEffect(
        runtime?.providerGeneration,
        runtime?.bodyReady,
        runtime?.activeBodySelected,
        cameraModel.phase,
        cameraModel.personEvidence,
        t032State,
        orientationMountingConfirmed,
    ) {
        if (!orientationModel.active) return@LaunchedEffect
        orientationCoordinator.prerequisitesChanged(
            currentOrientationPrerequisites(binder?.firstLifeOrientationAuthorityCurrent() == true),
        )
    }

    LaunchedEffect(orientationModel.nextDeadlineElapsedMs, orientationModel.phase) {
        val deadline = orientationModel.nextDeadlineElapsedMs ?: return@LaunchedEffect
        delay((deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        orientationCoordinator.tick(
            currentOrientationPrerequisites(binder?.firstLifeOrientationAuthorityCurrent() == true),
        )
    }

    LaunchedEffect(runtime?.providerGeneration, runtime?.activeBodySelected) {
        if (runtime?.bodyReady != true || runtime?.activeBodySelected != true) {
            orientationMountingConfirmed = false
            orientationMountingExpiresAtMs = null
        }
    }

    LaunchedEffect(
        runtime?.providerGeneration,
        runtime?.providerPrepared,
        runtime?.bodyReady,
        runtime?.activeBodySelected,
        blocked,
    ) {
        val status = when {
            blocked != null -> FirstLifeBodyStatus.PROVIDER_BLOCKED
            runtime?.activeBodySelected == true -> FirstLifeBodyStatus.SELECTED
            runtime?.bodyReady == true -> FirstLifeBodyStatus.AVAILABLE
            runtime?.providerPrepared == true -> FirstLifeBodyStatus.LOST
            else -> FirstLifeBodyStatus.UNAVAILABLE
        }
        experienceCoordinator.bodyStatusChanged(status)
    }

    LaunchedEffect(orientationMountingExpiresAtMs) {
        val expiry = orientationMountingExpiresAtMs ?: return@LaunchedEffect
        delay((expiry - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        orientationMountingConfirmed = false
        orientationMountingExpiresAtMs = null
    }

    DisposableEffect(activity, voiceCoordinator) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> experienceCoordinator.foreground()
                Lifecycle.Event.ON_STOP -> {
                    providerSetupControllerSlot[0]?.background()
                    experienceCoordinator.background()
                }
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            experienceCoordinator.dispose()
            cameraPort.destroy()
            cameraCoordinatorSlot[0] = null
            voiceCoordinatorSlot[0] = null
            speechCoordinatorSlot[0] = null
            orientationCoordinatorSlot[0] = null
            experienceCoordinatorSlot[0] = null
            providerSetupControllerSlot[0]?.close()
            providerSetupControllerSlot[0] = null
        }
    }

    DisposableEffect(activity) {
        var listener: ((io.redi.robot.platform.service.LooiFirstLifeServiceSnapshot) -> Unit)? = null
        var safetyListener: ((T032AcceptanceState) -> Unit)? = null
        var setupController: FirstLifeProviderSetupController? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val connected = service as? ConnectedDeviceService.ConnectionBinder ?: return
                binder = connected
                connected.expireFirstLifeActiveBody()
                val update: (io.redi.robot.platform.service.LooiFirstLifeServiceSnapshot) -> Unit = {
                    activity.runOnUiThread {
                        runtime = connected.firstLifeRuntimeSnapshot()
                        blocked = connected.firstLifeBlockedPresentation()
                    }
                }
                listener = update
                connected.addFirstLifeListener(update)
                val updateSafety: (T032AcceptanceState) -> Unit = { next ->
                    activity.runOnUiThread { t032State = next }
                }
                safetyListener = updateSafety
                connected.addT032StateListener(updateSafety)
                setupController = FirstLifeProviderSetupController(
                    context = activity.applicationContext,
                    binder = connected,
                    onStateChanged = { next -> activity.runOnUiThread { providerSetupState = next } },
                ).also {
                    providerSetupController = it
                    providerSetupControllerSlot[0] = it
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
                setupController?.close()
                setupController = null
                providerSetupController = null
                providerSetupControllerSlot[0] = null
                providerSetupState = FirstLifeProviderSetupState(FirstLifeProviderSetupStage.DISCONNECTED)
                runtime = null
                blocked = null
                orientationMountingConfirmed = false
                orientationMountingExpiresAtMs = null
                experienceCoordinator.bodyStatusChanged(FirstLifeBodyStatus.LOST)
            }
        }
        val isBound = activity.bindService(
            Intent(activity, ConnectedDeviceService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        onDispose {
            setupController?.close()
            providerSetupController = null
            providerSetupControllerSlot[0] = null
            listener?.let { callback -> binder?.removeFirstLifeListener(callback) }
            safetyListener?.let { callback -> binder?.removeT032StateListener(callback) }
            if (isBound) activity.unbindService(connection)
            binder = null
        }
    }

    val bodyPresentation = remember(runtime, blocked) {
        HiHugoBodyPresentationProjection.project(runtime, blocked?.toHiHugoBlockedPresentation())
    }
    HiHugoEntry(
        bootstrapSnapshot = bootstrapSnapshot,
        experience = experienceSnapshot,
        onStartExperience = { experienceCoordinator.startIntroduction() },
        onCancelExperience = { experienceCoordinator.cancel() },
        onResetExperience = { experienceCoordinator.reset() },
        bodyPresentation = bodyPresentation,
        providerSetup = providerSetupState.toHiHugoPresentation(),
        onDiscoverProvider = { providerSetupController?.discover() },
        onConnectProvider = { providerSetupController?.connectCandidate() },
        onDisconnectProvider = { providerSetupController?.disconnect() },
        onSelectActiveBody = { binder?.selectFirstLifeActiveBody() },
        onDeactivateActiveBody = { binder?.deactivateFirstLifeActiveBody() },
        onExpireActiveBody = { binder?.expireFirstLifeActiveBody() },
        voiceSession = voiceModel,
        speechOutput = speechModel,
        sessionDisplayNameAvailable = experienceSnapshot?.sessionNameAvailable == true,
        voiceInputAllowed = experienceSnapshot?.stage == FirstLifeExperienceStage.AWAITING_EXPLICIT_LISTEN &&
            speechModel.activeRequest == null,
        onStartVoiceSession = { experienceCoordinator.requestNameInput() },
        onReviewSystemVoiceFallback = { voiceCoordinator.reviewSystemFallback() },
        onConsentSystemVoiceFallback = { voiceCoordinator.consentToSystemFallback() },
        onCancelVoiceSession = { voiceCoordinator.cancel() },
        onSpeakIntroduction = { experienceCoordinator.startIntroduction() },
        onSpeakGreeting = { experienceCoordinator.repeatGreeting() },
        onCancelSpeech = { speechCoordinator.cancel() },
        onInstallVoiceData = {
            activity.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        },
        cameraSession = cameraModel,
        onStartCameraSession = { experienceCoordinator.openVisibleCamera() },
        onCloseCameraSession = { cameraCoordinator.close() },
        cameraPreview = { AndroidFrontCameraPreview(cameraPort, Modifier.fillMaxWidth().height(240.dp)) },
        orientation = orientationModel,
        orientationMountingConfirmed = orientationMountingConfirmed,
        orientationSafetyReady = t032State.mode == T032VerificationMode.FLOOR &&
            t032State.hasCurrentLowerContactEvidenceForOrientation() &&
            t032State.lowerContactSafe == true &&
            !t032State.emergencyStopLatched,
        onPrepareOrientation = {
            val connected = binder ?: return@HiHugoEntry
            orientationMountingConfirmed = connected.confirmFirstLifeOrientationMounting()
            orientationMountingExpiresAtMs = if (orientationMountingConfirmed) {
                android.os.SystemClock.elapsedRealtime() + 60_000L
            } else null
            t032State = connected.armT032Floor()
        },
        onBeginOrientationHold = {
            if (!experienceCoordinator.beforeOrientationHold()) return@HiHugoEntry
            val connected = binder ?: return@HiHugoEntry
            val authority = connected.beginFirstLifeOrientationHold()
            orientationCoordinator.begin(currentOrientationPrerequisites(authority.started))
        },
        onReleaseOrientationHold = { orientationCoordinator.release() },
        onCancelOrientation = { orientationCoordinator.cancel() },
        onOrientationEmergencyStop = {
            t032State = binder?.activateT032EmergencyStop() ?: t032State
            orientationCoordinator.emergencyStop()
        },
        developerControlsEnabled = developerControlsEnabled,
        reducedMotion = reducedMotion,
    )
}

private fun FirstLifeProviderSetupState.toHiHugoPresentation(): HiHugoProviderSetupPresentation =
    HiHugoProviderSetupPresentation(
        statusLabel = when (stage) {
            FirstLifeProviderSetupStage.IDLE -> "Ready to search for a compatible nearby body"
            FirstLifeProviderSetupStage.DISCOVERING -> "Searching for a compatible nearby body"
            FirstLifeProviderSetupStage.CANDIDATE_FOUND -> "Compatible body candidate found"
            FirstLifeProviderSetupStage.CONNECTING -> "Establishing the provider channel"
            FirstLifeProviderSetupStage.PREPARING -> "Verifying the provider and negotiating capabilities"
            FirstLifeProviderSetupStage.READY -> "Provider prepared; explicit active-body selection is still required"
            FirstLifeProviderSetupStage.PERMISSION_REQUIRED -> "Nearby-device permission is required before discovery"
            FirstLifeProviderSetupStage.BLUETOOTH_DISABLED -> "Bluetooth is off; enable it before discovery"
            FirstLifeProviderSetupStage.NOT_FOUND -> "No compatible advertising body was found"
            FirstLifeProviderSetupStage.FAILED -> when (failure) {
                FirstLifeProviderSetupFailure.TIMEOUT -> "Provider connection timed out; return the body to advertising mode and retry"
                FirstLifeProviderSetupFailure.AUTHENTICATION_REQUIRED -> "Provider authentication is required before connection"
                FirstLifeProviderSetupFailure.LINK_LOST -> "Provider link was lost before preparation completed"
                FirstLifeProviderSetupFailure.UNSUPPORTED -> "The platform reported this connection method as unsupported"
                FirstLifeProviderSetupFailure.CANCELLED -> "Provider setup was cancelled"
                FirstLifeProviderSetupFailure.UNKNOWN,
                null,
                -> "Provider setup failed closed; retry discovery"
            }
            FirstLifeProviderSetupStage.DISCONNECTED -> "Provider disconnected; HUGO identity is unchanged"
        },
        canDiscover = stage in setOf(
            FirstLifeProviderSetupStage.IDLE,
            FirstLifeProviderSetupStage.PERMISSION_REQUIRED,
            FirstLifeProviderSetupStage.BLUETOOTH_DISABLED,
            FirstLifeProviderSetupStage.NOT_FOUND,
            FirstLifeProviderSetupStage.FAILED,
            FirstLifeProviderSetupStage.DISCONNECTED,
        ),
        canConnect = candidateAvailable && !connectionActive && stage == FirstLifeProviderSetupStage.CANDIDATE_FOUND,
        canDisconnect = connectionActive,
    )

@Composable
private fun PermissionAwareScreen(activity: ComponentActivity) {
    val requiredPermissions =
        remember {
            permissionRequirementForRuntime().toSet()
        }

    var hasRequestedPermissions by remember { mutableStateOf(false) }
    var permissionCheckEpoch by remember { mutableIntStateOf(0) }
    var diagnosticState by remember { mutableStateOf(BleDiagnosticUiState()) }
    var serviceBinder by remember { mutableStateOf<ConnectedDeviceService.ConnectionBinder?>(null) }
    var t032State by remember { mutableStateOf(T032AcceptanceState()) }
    val managedConnection: DiagnosticConnectionClient? = remember(serviceBinder) {
        serviceBinder?.let { BoundDiagnosticConnectionClient(activity.applicationContext, it) }
    }
    val diagnosticController = remember(managedConnection) {
        ReadOnlyBleDiagnosticController(
            context = activity.applicationContext,
            onStateChanged = { diagnosticState = it },
            managedConnection = managedConnection,
        )
    }
    val manualController = remember(serviceBinder) {
        ManualDriveController(ManualDriveCommandSink { command -> serviceBinder?.dispatchT032Drive(command) })
    }

    DisposableEffect(serviceBinder) {
        val binder = serviceBinder
        val listener: (T032AcceptanceState) -> Unit = { t032State = it }
        binder?.addT032StateListener(listener)
        onDispose { binder?.removeT032StateListener(listener) }
    }

    LaunchedEffect(t032State.emergencyStopLatched, manualController) {
        if (t032State.emergencyStopLatched) {
            manualController.emergencyStop(android.os.SystemClock.elapsedRealtime())
        }
    }

    DisposableEffect(activity, manualController) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                manualController.cancelActive(io.redi.robot.feature.manualcontrol.ManualStopReason.BACKGROUND, android.os.SystemClock.elapsedRealtime())
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            manualController.cancelActive(io.redi.robot.feature.manualcontrol.ManualStopReason.NAVIGATION, android.os.SystemClock.elapsedRealtime())
        }
    }

    DisposableEffect(activity) {
        val isBound = AtomicBoolean(false)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (isBound.get()) serviceBinder = service as? ConnectedDeviceService.ConnectionBinder
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (isBound.get()) serviceBinder = null
            }
        }
        val unbind = {
            if (isBound.compareAndSet(true, false)) {
                activity.unbindService(connection)
                serviceBinder = null
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (isBound.compareAndSet(false, true) &&
                        !activity.bindService(Intent(activity, ConnectedDeviceService::class.java), connection, Context.BIND_AUTO_CREATE)
                    ) {
                        isBound.set(false)
                    }
                }
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY,
                -> unbind()
                else -> Unit
            }
        }
        activity.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            activity.lifecycle.removeObserver(lifecycleObserver)
            unbind()
        }
    }

    DisposableEffect(diagnosticController) {
        onDispose { diagnosticController.close() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionCheckEpoch++
    }

    val permissionState: PermissionDecisionState = remember(
        requiredPermissions,
        hasRequestedPermissions,
        permissionCheckEpoch,
    ) {
        evaluatePermissionState(
            requiredPermissions = requiredPermissions,
            isGranted = { permission ->
                ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
            },
            shouldShowRationale = { permission ->
                activity.shouldShowRequestPermissionRationale(permission)
            },
            requestedAtLeastOnce = hasRequestedPermissions,
        )
    }

    LaunchedEffect(diagnosticController, permissionState.allPermissionsGranted) {
        diagnosticController.updatePermissionsReady(permissionState.allPermissionsGranted)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.padding(24.dp),
            ) {
                item { Text("REDI BLE Diagnostic", style = MaterialTheme.typography.headlineMedium) }
                item { Spacer(Modifier.height(8.dp)) }

                item {
                    if (permissionState.allPermissionsGranted) {
                        Text("Bluetooth permissions are ready. This screen performs scan, connection, and service discovery only.")
                    } else if (permissionState.hasPermanentlyDenied) {
                        Text("Bluetooth access is blocked. Open app settings, enable permissions, and return.")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { openAppSettings(activity) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Open settings")
                        }
                    } else {
                        Text("Bluetooth permissions are required to enable scanning and connection.")
                        Spacer(Modifier.height(12.dp))
                        if (permissionState.canRequestPermissionAgain) {
                            Button(
                                onClick = {
                                    hasRequestedPermissions = true
                                    permissionLauncher.launch(requiredPermissions.toTypedArray())
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Grant permissions") }
                        } else {
                            Text("Waiting for user decision.")
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
                item { PermissionStatusSection(permissionState = permissionState) }
                item { HorizontalDivider(Modifier.padding(vertical = 16.dp)) }
                item {
                    Row {
                        Button(
                            onClick = { diagnosticController.startScan() },
                            enabled = diagnosticState.permissionsReady && !diagnosticState.scanActive,
                        ) { Text("Scan") }
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Button(onClick = { diagnosticController.stopScan() }, enabled = diagnosticState.scanActive) {
                            Text("Stop scan")
                        }
                    }
                    Button(
                        onClick = { diagnosticController.disconnect() },
                        enabled = diagnosticState.connection.phase.name in setOf("CONNECTING", "CONNECTED", "TRANSPORT_READY"),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Disconnect BLE") }
                    Button(
                        onClick = { serviceBinder?.endUserSession() },
                        enabled = serviceBinder != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("End connection session") }
                    Text("Scan: ${diagnosticState.scanStatus}")
                    Text("Connection: ${diagnosticState.connection.phase} (transport-ready is not protocol-ready)")
                    Text(if (managedConnection != null) "Connection owner: connected-device service" else "Connection service is binding...")
                    diagnosticState.connection.failure?.let { Text("Failure: $it") }
                    if (diagnosticState.connection.phase.name == "TRANSPORT_READY" && managedConnection != null) {
                        Spacer(Modifier.height(8.dp))
                        Column {
                            Button(onClick = { diagnosticController.startHandshake(HandshakeCandidateId.A) }, modifier = Modifier.fillMaxWidth()) { Text("Handshake A") }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { diagnosticController.startHandshake(HandshakeCandidateId.B) }, modifier = Modifier.fillMaxWidth()) { Text("Handshake B") }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { diagnosticController.cancelHandshake() }, modifier = Modifier.fillMaxWidth()) { Text("Cancel handshake") }
                        }
                        Text("Research only: no protocol-ready or movement is enabled.")
                        diagnosticState.handshake?.let { handshake ->
                            Text("Handshake ${handshake.candidate}: running=${handshake.running}, completed=${handshake.completed}")
                            handshake.stepResults.forEach { step ->
                                Text("${step.stepId}: ${if (step.success) "success" else "failed"}${step.failureReason?.let { "; $it" } ?: ""}")
                            }
                            Text("Protocol ready: ${handshake.protocolReady}")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("FED9 telemetry capture (read-only diagnostic)", style = MaterialTheme.typography.titleMedium)
                    Text("Subscription: ${diagnosticState.fed9Capture.subscriptionState}; notifications: ${diagnosticState.fed9Capture.notificationCount}")
                    Text("Latest raw payload: ${diagnosticState.fed9Capture.latestPayloadHex}", style = MaterialTheme.typography.bodySmall)
                    diagnosticState.fed9Capture.error?.let { Text("FED9 error: $it") }
                    Column {
                        Button(
                            onClick = { diagnosticController.startFed9CaptureWithVerifiedHandshakeA() },
                            enabled = managedConnection != null && diagnosticState.connection.phase.name == "TRANSPORT_READY" && !diagnosticState.fed9Capture.captureActive,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Handshake A + Start FED9") }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { diagnosticController.stopFed9Capture() },
                            enabled = diagnosticState.fed9Capture.captureActive,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Stop capture") }
                    }
                    Button(onClick = { diagnosticController.exportFed9Capture() }, enabled = diagnosticState.fed9Capture.records.isNotEmpty()) { Text("Export redacted fixture") }
                    diagnosticState.fed9ExportStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("Uses only the verified non-motion Handshake A prerequisite. No FED0, heartbeat, actuation, or sensor interpretation.", style = MaterialTheme.typography.bodySmall)
                }
                item { HorizontalDivider(Modifier.padding(vertical = 16.dp)) }
                item {
                    Text("T032 manual acceptance", style = MaterialTheme.typography.titleMedium)
                    Text("Mode: ${t032State.mode}; FED9 active: ${t032State.fed9Active}; contact known: ${t032State.sensorStateKnown}; stream fresh: ${t032State.streamFresh}; e-stop: ${t032State.emergencyStopLatched}")
                    Text("Stops requested: ${t032State.stopRequestCount}; drive write: ${t032State.driveWriteStatus}; ${t032State.lastOutcome}", style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = { serviceBinder?.armT032Floor() },
                        enabled = t032State.raisedWheelVerified && t032State.sensorStateKnown && !t032State.emergencyStopLatched,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Enable continuous manual control") }
                    Button(
                        onClick = { serviceBinder?.resetT032EmergencyStopReportedStationary() },
                        enabled = t032State.emergencyStopLatched,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Reset e-stop: I confirm robot is stationary") }
                    ManualDriveControls(
                        state = ManualDriveUiState(
                            ready = t032State.prerequisiteReady && t032State.mode != T032VerificationMode.DISARMED,
                            safe = t032State.fed9Active && t032State.sensorStateKnown,
                            emergencyStopLatched = t032State.emergencyStopLatched,
                        ),
                        controller = manualController,
                        nowElapsedMs = { android.os.SystemClock.elapsedRealtime() },
                        onEmergencyStop = { serviceBinder?.activateT032EmergencyStop() },
                    )
                    Button(
                        onClick = {
                            serviceBinder?.armT032RaisedWheels()
                            serviceBinder?.armT032HeadCalibration()
                        },
                        enabled = t032State.prerequisiteReady &&
                            t032State.fed9Active &&
                            t032State.sensorStateKnown &&
                            !t032State.emergencyStopLatched,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Arm limited head calibration (robot stationary)") }
                    ManualHeadControls(
                        state = ManualHeadUiState(ready = t032State.prerequisiteReady, safe = t032State.sensorStateKnown, calibrated = t032State.headEnabled),
                        sink = ManualHeadCommandSink { serviceBinder?.requestT032Head(it) },
                    )
                    ManualLightControls(
                        state = ManualLightUiState(ready = t032State.lightEnabled, safe = t032State.sensorStateKnown, minimumOnAvailable = t032State.lightEnabled),
                        sink = ManualLightCommandSink { serviceBinder?.requestT032Light(it) },
                    )
                }
                item { HorizontalDivider(Modifier.padding(vertical = 16.dp)) }
                item { Text("Discovered devices", style = MaterialTheme.typography.titleMedium) }
                items(diagnosticState.devices, key = { it.key }) { device ->
                    DiagnosticDeviceCard(device = device, onConnect = { diagnosticController.connect(device.key) })
                }
                item { HorizontalDivider(Modifier.padding(vertical = 16.dp)) }
                item {
                    Text("GATT status codes: ${diagnosticState.gattStatusCodes.joinToString().ifBlank { "none" }}")
                    Text("Discovered GATT inventory", style = MaterialTheme.typography.titleMedium)
                    if (diagnosticState.inventory.isEmpty()) Text("No service inventory observed.")
                    diagnosticState.inventory.forEach { service ->
                        Text("Service: ${service.uuid}")
                        service.characteristics.forEach { characteristic ->
                            Text("  Characteristic: ${characteristic.uuid}")
                        }
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 16.dp)) }
                item { Text("Timestamped diagnostic log", style = MaterialTheme.typography.titleMedium) }
                items(diagnosticState.log.mapIndexed { index, entry -> index to entry }, key = { it.first }) { indexedEntry ->
                    val entry = indexedEntry.second
                    Text("${entry.timestampUnixMillis}: ${entry.message}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticDeviceCard(device: DiagnosticBleDevice, onConnect: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("${device.key}: ${device.displayName}", style = MaterialTheme.typography.titleSmall)
        Text("RSSI: ${device.rssi ?: "unknown"}; candidate: ${device.candidateStatus}; confidence: ${device.confidence}")
        if (device.evidence.isEmpty()) {
            Text("No LOOI evidence observed. Manual connection remains read-only research only.")
        } else {
            device.evidence.forEach { evidence -> Text("Evidence: ${evidence.detail}") }
        }
        Button(onClick = onConnect) { Text("Connect (read-only)") }
    }
}

@Composable
private fun PermissionStatusSection(permissionState: PermissionDecisionState) {
    if (permissionState.requiredPermissions.isEmpty()) return

    Text("Requested permissions:")
    permissionState.requiredPermissions.forEach { permission ->
        val status = when {
            permissionState.grantedPermissions.contains(permission) -> "granted"
            permissionState.permanentlyDeniedPermissions.contains(permission) -> "permanently denied"
            else -> "denied"
        }
        Text("- ${permission.substringAfterLast('.')} - $status")
    }
}

private fun openAppSettings(activity: ComponentActivity) {
    val intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null),
        )
    activity.startActivity(intent)
}
