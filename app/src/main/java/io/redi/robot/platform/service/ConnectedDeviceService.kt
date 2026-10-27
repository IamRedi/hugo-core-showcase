package io.redi.robot.platform.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.redi.robot.R
import io.redi.robot.body.looi.connection.ConnectionState
import io.redi.robot.body.looi.connection.ConnectionTarget
import io.redi.robot.body.looi.connection.ConnectionPhase
import io.redi.robot.body.looi.protocol.handshake.HandshakeAttemptSummary
import io.redi.robot.body.looi.protocol.handshake.HandshakeCandidateId
import io.redi.robot.body.looi.protocol.handshake.HandshakeExperimentCoordinator
import io.redi.robot.body.looi.protocol.telemetrycapture.Fed9CaptureAvailability
import io.redi.robot.body.looi.protocol.telemetrycapture.Fed9CaptureState
import io.redi.robot.body.looi.protocol.telemetrycapture.Fed9FixtureExportResult
import io.redi.robot.body.looi.protocol.telemetrycapture.Fed9TelemetryCapture
import io.redi.robot.body.looi.protocol.telemetrycapture.VerifiedFed9Prerequisite
import io.redi.robot.body.looi.protocol.telemetrycapture.fed9CaptureAvailability
import io.redi.robot.body.looi.firstlife.LooiT032VerifiedRuntimeDelegate
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.CapabilityRequest
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.CapabilityProviderPortResult
import io.redi.robot.capability.provider.ProviderAvailabilityReason
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.capability.provider.ProviderRuntimeSnapshot
import io.redi.robot.firstlife.runtime.FirstLifeAuthorizedCapabilityDispatcher
import io.redi.robot.firstlife.runtime.AuthorizedDispatchContext
import io.redi.robot.firstlife.runtime.AuthorizedDispatchRejection
import io.redi.robot.firstlife.runtime.AuthorizedDispatchResult
import io.redi.robot.firstlife.runtime.FirstLifeOrientationDispatchPort
import io.redi.robot.firstlife.runtime.FirstLifeOrientationDispatchReceipt
import io.redi.robot.firstlife.runtime.FirstLifeOrientationEffectBinding
import io.redi.robot.firstlife.runtime.FirstLifeOrientationEffectResult
import io.redi.robot.firstlife.runtime.FirstLifeOrientationServiceEvidence
import io.redi.robot.firstlife.runtime.OrientationAuthorityBeginResult
import io.redi.robot.firstlife.runtime.OrientationAuthorityBeginStatus
import io.redi.robot.firstlife.orientation.OrientationEffect
import io.redi.robot.firstlife.orientation.OrientationEffectOutcome
import io.redi.robot.firstlife.orientation.FirstLifeOrientationModel
import io.redi.robot.trust.runtime.AuthorizationDecision
import io.redi.robot.body.looi.connection.GattNotificationObserver
import io.redi.robot.body.looi.connection.GattServiceInventory
import io.redi.robot.body.looi.connection.GattTransportDiagnosticEvent
import io.redi.robot.body.looi.connection.GattTransportDiagnosticsObserver
import io.redi.robot.platform.transport.ble.gatt.AndroidGattTransportAdapter
import io.redi.robot.feature.manualcontrol.ManualDriveCommand
import io.redi.robot.feature.manualcontrol.T032AcceptanceState
import io.redi.robot.feature.manualcontrol.T032ManualAcceptanceSession
import io.redi.robot.safety.deadman.DeadManMonotonicClock
import io.redi.robot.safety.deadman.DeadManScheduledTask
import io.redi.robot.safety.deadman.DeadManScheduler
import io.redi.robot.safety.estop.EmergencyStopReason
import java.io.File

/**
 * User-started, visible owner for an external BLE device connection.
 * It intentionally does not auto-start after boot or process recreation, and it never owns motion authority.
 */
class ConnectedDeviceService : Service() {
    private lateinit var connectionOwner: ConnectedDeviceConnectionOwner
    private lateinit var gattAdapter: AndroidGattTransportAdapter
    private lateinit var firstLifeBridge: ServiceOwnedLooiBleBridge
    private lateinit var firstLifeComposition: LooiFirstLifeServiceComposition
    private var handshakeExperiment: HandshakeExperimentCoordinator? = null
    private var fed9Capture: Fed9TelemetryCapture? = null
    private var t032AcceptanceSession: T032ManualAcceptanceSession? = null
    private var latestGattInventory: List<GattServiceInventory> = emptyList()
    private val handshakeHandler = Handler(Looper.getMainLooper())
    private val listeners = linkedSetOf<(ConnectionState) -> Unit>()
    private val fed9CaptureListeners = linkedSetOf<(Fed9CaptureState) -> Unit>()
    private val t032Listeners = linkedSetOf<(T032AcceptanceState) -> Unit>()
    private val firstLifeListeners = linkedSetOf<(LooiFirstLifeServiceSnapshot) -> Unit>()
    private var isForeground = false
    private var firstLifeDispatcherKey: String? = null
    private var firstLifeDispatcher: FirstLifeAuthorizedCapabilityDispatcher? = null
    private var firstLifeOrientationEvidence = FirstLifeOrientationServiceEvidence()
    private val firstLifeOrientationBinding = FirstLifeOrientationEffectBinding(
        FirstLifeOrientationDispatchPort { request, authorization ->
            val before = t032AcceptanceSession?.state()
            val dispatch = firstLifeDispatchBinding()?.let { binding ->
                binding.first.dispatch(request, authorization, binding.second)
            } ?: AuthorizedDispatchResult.Rejected(AuthorizedDispatchRejection.NEGOTIATION_STALE)
            val after = t032AcceptanceSession?.state()
            val stopObserved = request.capability.wireValue == "mobility.stop@1.0" &&
                before != null && after != null &&
                after.stopRequestCount > before.stopRequestCount && !after.deadManArmed
            FirstLifeOrientationDispatchReceipt(dispatch, stopObserved)
        },
    )

    inner class ConnectionBinder : Binder() {
        fun connect(target: ConnectionTarget): ConnectionState {
            promoteToForeground()
            return connectionOwner.requestConnect(target)
        }

        /** FL006 explicit generic path. It composes H028 over this service's existing sole owner. */
        fun connectFirstLife(target: ConnectionTarget): LooiFirstLifeServiceSnapshot {
            promoteToForeground()
            return firstLifeComposition.connect(target)
        }

        fun disconnectFirstLife(): LooiFirstLifeServiceSnapshot {
            firstLifeOrientationBinding.endHold()
            return firstLifeComposition.disconnect()
        }
        fun firstLifeSnapshot(): LooiFirstLifeServiceSnapshot = firstLifeComposition.snapshot()
        fun firstLifeRuntimeSnapshot() = firstLifeComposition.runtimeSnapshot()
        fun firstLifeBlockedPresentation() = firstLifeComposition.blockedPresentation()
        fun selectFirstLifeActiveBody() = firstLifeComposition.selectActiveBody()
        fun deactivateFirstLifeActiveBody() = firstLifeComposition.deactivateActiveBody().also {
            firstLifeOrientationBinding.endHold()
        }
        fun expireFirstLifeActiveBody() = firstLifeComposition.expireActiveBody().also { result ->
            if (result?.status == io.redi.robot.body.looi.firstlife.LooiActiveBodySelectionStatus.EXPIRED) {
                firstLifeOrientationBinding.endHold()
            }
        }
        fun confirmFirstLifeOrientationMounting(): Boolean = firstLifeComposition.confirmOrientationMounting()
        fun beginFirstLifeOrientationHold(): OrientationAuthorityBeginResult {
            val context = firstLifeComposition.orientationAuthorityContextForExplicitHold()
                ?: return OrientationAuthorityBeginResult(OrientationAuthorityBeginStatus.CONTEXT_NOT_CURRENT)
            return firstLifeOrientationBinding.beginHold(
                context,
                timestamp(android.os.SystemClock.elapsedRealtime()),
            ).also { result ->
                firstLifeOrientationEvidence = FirstLifeOrientationServiceEvidence(holdStarted = result.started)
            }
        }
        fun firstLifeOrientationAuthorityCurrent(): Boolean =
            firstLifeOrientationBinding.authorityCurrent(timestamp(android.os.SystemClock.elapsedRealtime()))
        fun dispatchFirstLifeOrientationEffect(effect: OrientationEffect): FirstLifeOrientationEffectResult {
            val context = firstLifeComposition.orientationAuthorityContext()
                ?: return FirstLifeOrientationEffectResult(
                    OrientationEffectOutcome.STALE_CORRELATION,
                    null,
                ).also { result -> recordOrientationEvidence(effect, result) }
            return firstLifeOrientationBinding.execute(
                effect,
                context,
                timestamp(android.os.SystemClock.elapsedRealtime()),
            ).also { result -> recordOrientationEvidence(effect, result) }
        }
        fun endFirstLifeOrientationHold() = firstLifeOrientationBinding.endHold()
        fun firstLifeOrientationEvidence(): FirstLifeOrientationServiceEvidence = firstLifeOrientationEvidence
        fun recordFirstLifeOrientationModel(model: FirstLifeOrientationModel) {
            firstLifeOrientationEvidence = firstLifeOrientationEvidence.copy(
                controllerPhase = model.phase,
                pulseCount = model.pulseCount,
                cumulativeEnergizedMs = model.cumulativeEnergizedMs,
                terminalReason = model.terminalReason,
            )
        }
        /** Project-owned H029 evidence for the H030/FL007 composition; never render or diagnose raw IDs. */
        fun firstLifeNegotiationReport() = firstLifeComposition.currentNegotiation()
        fun addFirstLifeListener(listener: (LooiFirstLifeServiceSnapshot) -> Unit) {
            firstLifeListeners += listener
            listener(firstLifeComposition.snapshot())
        }
        fun removeFirstLifeListener(listener: (LooiFirstLifeServiceSnapshot) -> Unit) {
            firstLifeListeners -= listener
        }

        fun dispatchFirstLifeCapability(
            request: CapabilityRequest,
            authorization: AuthorizationDecision?,
        ): AuthorizedDispatchResult {
            val binding = firstLifeDispatchBinding()
                ?: return AuthorizedDispatchResult.Rejected(AuthorizedDispatchRejection.NEGOTIATION_STALE)
            return binding.first.dispatch(request, authorization, binding.second)
        }

        fun cancelFirstLifeCapability(request: CapabilityRequest): AuthorizedDispatchResult {
            val binding = firstLifeDispatchBinding()
                ?: return AuthorizedDispatchResult.Rejected(AuthorizedDispatchRejection.NEGOTIATION_STALE)
            return binding.first.cancel(request, binding.second)
        }

        fun disconnect(): ConnectionState = disconnectSafely()
        fun endUserSession(): ConnectionState = shutdownUserSession()
        fun currentState(): ConnectionState = connectionOwner.state

        fun startHandshake(candidate: HandshakeCandidateId): HandshakeAttemptSummary? {
            val state = connectionOwner.state
            if (state.phase != ConnectionPhase.TRANSPORT_READY) return null
            val operationTransport = gattAdapter.operationTransportForGeneration(state.generation) ?: return null
            val experiment = HandshakeExperimentCoordinator(state.generation, operationTransport)
            handshakeExperiment = experiment
            val summary = experiment.start(candidate)
            scheduleHandshakeTimeoutCheck(experiment)
            return summary
        }

        fun cancelHandshake(): HandshakeAttemptSummary? = handshakeExperiment?.cancel()
        fun handshakeSummary(): HandshakeAttemptSummary? = handshakeExperiment?.summary()
        fun handshakeActive(): Boolean = handshakeExperiment?.isActive() == true

        fun startFed9Capture(): Fed9CaptureState = startFed9CaptureInternal()
        fun startFed9CaptureWithVerifiedHandshakeA(): HandshakeAttemptSummary? {
            val state = connectionOwner.state
            if (state.phase != ConnectionPhase.TRANSPORT_READY) return null
            val operationTransport = gattAdapter.operationTransportForGeneration(state.generation) ?: return null
            fed9Capture?.stop("new verified prerequisite requested")
            val experiment = HandshakeExperimentCoordinator(state.generation, operationTransport)
            handshakeExperiment = experiment
            val summary = experiment.start(HandshakeCandidateId.A)
            scheduleHandshakeTimeoutCheck(experiment, startFed9AfterSuccessForGeneration = state.generation)
            return summary
        }
        fun stopFed9Capture(): Fed9CaptureState = fed9Capture?.stop() ?: Fed9CaptureState()
        fun fed9CaptureState(): Fed9CaptureState = fed9Capture?.state() ?: Fed9CaptureState()
        fun exportFed9Capture(): Fed9FixtureExportResult = exportFed9CaptureInternal()
        fun addFed9CaptureListener(listener: (Fed9CaptureState) -> Unit) {
            fed9CaptureListeners += listener
            listener(fed9Capture?.state() ?: Fed9CaptureState())
        }
        fun removeFed9CaptureListener(listener: (Fed9CaptureState) -> Unit) { fed9CaptureListeners -= listener }

        fun t032State(): T032AcceptanceState = t032AcceptanceSession?.state() ?: T032AcceptanceState()
        fun armT032RaisedWheels(): T032AcceptanceState {
            synchronizeT032Fed9State()
            return t032AcceptanceSession?.armRaisedWheels() ?: T032AcceptanceState()
        }
        fun confirmT032RaisedWheelChecksPassed(): T032AcceptanceState =
            t032AcceptanceSession?.confirmRaisedWheelChecksPassed() ?: T032AcceptanceState()
        fun armT032Floor(): T032AcceptanceState {
            synchronizeT032Fed9State()
            return t032AcceptanceSession?.armFloor() ?: T032AcceptanceState()
        }
        fun armT032HeadCalibration(): T032AcceptanceState = t032AcceptanceSession?.armHeadCalibration() ?: T032AcceptanceState()
        fun dispatchT032Drive(command: ManualDriveCommand): T032AcceptanceState =
            t032AcceptanceSession?.dispatch(command) ?: T032AcceptanceState(lastOutcome = "No T032 acceptance session")
        fun requestT032Head(normalizedPitch: Float): T032AcceptanceState =
            t032AcceptanceSession?.requestHead(normalizedPitch) ?: T032AcceptanceState(lastOutcome = "No T032 acceptance session")
        fun requestT032Light(normalizedBrightness: Float): T032AcceptanceState =
            t032AcceptanceSession?.requestLight(normalizedBrightness) ?: T032AcceptanceState(lastOutcome = "No T032 acceptance session")
        fun activateT032EmergencyStop(): T032AcceptanceState {
            firstLifeOrientationBinding.endHold()
            return t032AcceptanceSession?.emergencyStop() ?: T032AcceptanceState(emergencyStopLatched = true)
        }
        fun resetT032EmergencyStopReportedStationary(): T032AcceptanceState =
            t032AcceptanceSession?.resetEmergencyStopReportedStationary() ?: T032AcceptanceState()
        fun addT032StateListener(listener: (T032AcceptanceState) -> Unit) {
            t032Listeners += listener
            listener(t032State())
        }
        fun removeT032StateListener(listener: (T032AcceptanceState) -> Unit) { t032Listeners -= listener }

        fun addStateListener(listener: (ConnectionState) -> Unit) {
            listeners += listener
            listener(connectionOwner.state)
        }

        fun removeStateListener(listener: (ConnectionState) -> Unit) {
            listeners -= listener
        }
    }

    private val binder = ConnectionBinder()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        gattAdapter = AndroidGattTransportAdapter(
            applicationContext,
            diagnosticsObserver = GattTransportDiagnosticsObserver { event ->
                if (event is GattTransportDiagnosticEvent.ServicesDiscovered) latestGattInventory = event.services
            },
            notificationObserver = GattNotificationObserver { envelope ->
                fed9Capture?.onNotification(envelope)
                t032AcceptanceSession?.onNotification(envelope, android.os.SystemClock.elapsedRealtime())
            },
        )
        connectionOwner = ConnectedDeviceConnectionOwner(
            delegate = gattAdapter,
            onStateChanged = ::onConnectionStateChanged,
        )
        firstLifeBridge = ServiceOwnedLooiBleBridge(
            connectExisting = connectionOwner::requestConnect,
            disconnectExisting = connectionOwner::requestDisconnect,
        )
        firstLifeComposition = LooiFirstLifeServiceComposition(
            applicationContext,
            firstLifeBridge,
        ) { snapshot -> firstLifeListeners.toList().forEach { it(snapshot) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT, ACTION_STOP -> {
                shutdownUserSession()
            }
            ACTION_START -> promoteToForeground()
        }
        // Never restart a hidden connection after process death; user must explicitly start a new session.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        shutdownUserSession()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        firstLifeOrientationBinding.endHold()
        handshakeHandler.removeCallbacksAndMessages(null)
        handshakeExperiment?.cancel()
        fed9Capture?.stop("service destroyed")
        t032AcceptanceSession?.onDisconnect(EmergencyStopReason.SERVICE_SHUTDOWN)
        connectionOwner.shutdown()
        listeners.clear()
        firstLifeListeners.clear()
        super.onDestroy()
    }

    private fun onConnectionStateChanged(state: ConnectionState) {
        if (::firstLifeComposition.isInitialized) firstLifeComposition.onExistingStateChanged(state)
        if (state.phase != ConnectionPhase.TRANSPORT_READY) {
            firstLifeOrientationBinding.endHold()
            handshakeExperiment?.cancel()
            fed9Capture?.onDisconnect(state.generation)
            t032AcceptanceSession?.onDisconnect(EmergencyStopReason.TRANSPORT_LOST)
            t032AcceptanceSession = null
            publishT032State(T032AcceptanceState(generation = state.generation, lastOutcome = "Transport is not ready"))
        }
        listeners.toList().forEach { it(state) }
        if (isForeground) notifyStateChanged(state)
    }

    private fun scheduleHandshakeTimeoutCheck(
        experiment: HandshakeExperimentCoordinator,
        startFed9AfterSuccessForGeneration: Long? = null,
    ) {
        handshakeHandler.post(object : Runnable {
            override fun run() {
                if (handshakeExperiment !== experiment) return
                experiment.checkTimeout()
                firstLifeComposition.onHandshakeChanged(experiment.summary())
                if (experiment.isActive()) {
                    handshakeHandler.postDelayed(this, 100L)
                    return
                }
                val generation = startFed9AfterSuccessForGeneration ?: return
                val summary = experiment.summary()
                if (VerifiedFed9Prerequisite.isSatisfied(generation, summary) &&
                    connectionOwner.state.generation == generation &&
                    connectionOwner.state.phase == ConnectionPhase.TRANSPORT_READY
                ) {
                    prepareT032AcceptanceSession(generation)
                    startFed9CaptureInternal()
                }
            }
        })
    }

    private fun canPostNotifications(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    @android.annotation.SuppressLint("NotificationPermission")
    private fun notifyStateChanged(state: ConnectionState) {
        if (!canPostNotifications()) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notificationFor(state))
    }

    private fun promoteToForeground() {
        if (isForeground) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notificationFor(connectionOwner.state), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notificationFor(connectionOwner.state))
            }
            isForeground = true
        } catch (_: SecurityException) {
            // Missing permission/runtime prerequisite must fail closed; do not keep a hidden service alive.
            stopSelf()
        }
    }

    private fun removeForegroundAndStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        isForeground = false
        stopSelf()
    }

    /**
     * Visible user-session stop path. It is idempotent so notification, UI, task-removal, and
     * destruction callbacks can converge safely without keeping a GATT session alive.
     */
    private fun shutdownUserSession(): ConnectionState {
        firstLifeOrientationBinding.endHold()
        handshakeHandler.removeCallbacksAndMessages(null)
        handshakeExperiment?.cancel()
        fed9Capture?.stop("user session ended")
        t032AcceptanceSession?.onDisconnect(EmergencyStopReason.SERVICE_SHUTDOWN)
        connectionOwner.shutdown()
        removeForegroundAndStop()
        return connectionOwner.state
    }

    private fun startFed9CaptureInternal(): Fed9CaptureState {
        val connection = connectionOwner.state
        if (connection.phase != ConnectionPhase.TRANSPORT_READY) return Fed9CaptureState(error = "Transport must be ready before FED9 capture")
        val availability = fed9CaptureAvailability(latestGattInventory)
        if (availability !is Fed9CaptureAvailability.Available) {
            return Fed9CaptureState(error = (availability as Fed9CaptureAvailability.Unavailable).reason)
        }
        fed9Capture?.stop("replaced by new capture run")
        val transport = gattAdapter.operationTransportForGeneration(connection.generation)
            ?: return Fed9CaptureState(error = "No active GATT operation transport")
        fed9Capture = Fed9TelemetryCapture(
            generation = connection.generation,
            profileEvidence = availability.profileEvidence,
            endpoint = availability.endpoint,
            transport = transport,
            inventory = latestGattInventory,
            monotonicNowMs = { android.os.SystemClock.elapsedRealtime() },
            wallClockNowMs = { System.currentTimeMillis() },
            onStateChanged = { captureState ->
                fed9CaptureListeners.toList().forEach { it(captureState) }
                t032AcceptanceSession?.setFed9Active(captureState.captureActive)
            },
        )
        return fed9Capture!!.start()
    }

    private fun prepareT032AcceptanceSession(generation: Long) {
        val transport = gattAdapter.operationTransportForGeneration(generation) ?: return
        val scheduler = DeadManScheduler { deadlineElapsedMs, action ->
            val runnable = Runnable(action)
            val delayMs = (deadlineElapsedMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            handshakeHandler.postDelayed(runnable, delayMs)
            DeadManScheduledTask { handshakeHandler.removeCallbacks(runnable) }
        }
        t032AcceptanceSession = runCatching {
            T032ManualAcceptanceSession(
                generation = generation,
                inventory = latestGattInventory,
                transport = transport,
                clock = DeadManMonotonicClock { android.os.SystemClock.elapsedRealtime() },
                scheduler = scheduler,
                onStateChanged = ::publishT032State,
            )
        }.getOrElse {
            publishT032State(T032AcceptanceState(generation = generation, lastOutcome = "T032 unavailable: ${it.message}"))
            null
        }
    }

    private fun synchronizeT032Fed9State() {
        t032AcceptanceSession?.setFed9Active(fed9Capture?.state()?.captureActive == true)
    }

    private fun firstLifeDispatchBinding(): Pair<FirstLifeAuthorizedCapabilityDispatcher, AuthorizedDispatchContext>? {
        val report = firstLifeComposition.currentNegotiation() ?: return null
        val session = t032AcceptanceSession ?: return null
        val now = android.os.SystemClock.elapsedRealtime()
        val runtime = provider(ProviderRuntimeSnapshot.create(
            report.providerGeneration,
            if (session.state().prerequisiteReady) ProviderRuntimeAvailability.AVAILABLE
            else ProviderRuntimeAvailability.UNAVAILABLE,
            timestamp(now),
            if (session.state().prerequisiteReady) null else ProviderAvailabilityReason.NOT_READY,
        ))
        val port = ServiceOwnedLooiT032SemanticPort(
            runtime = { runtime },
            state = session::state,
            drive = session::dispatchVerified,
            head = session::requestHeadVerified,
            light = session::requestLightVerified,
            nowMs = { android.os.SystemClock.elapsedRealtime() },
        )
        val delegate = LooiT032VerifiedRuntimeDelegate(port) { timestamp(android.os.SystemClock.elapsedRealtime()) }
        val capabilityProvider = firstLifeComposition.capabilityProvider(delegate) ?: return null
        val key = "${capabilityProvider.identity.instanceId.value}/${report.providerGeneration.value}/${report.negotiationId.value}"
        if (firstLifeDispatcherKey != key) {
            firstLifeDispatcherKey = key
            firstLifeDispatcher = FirstLifeAuthorizedCapabilityDispatcher(capabilityProvider) {
                firstLifeComposition.currentNegotiation()
            }
        }
        val context = AuthorizedDispatchContext(
            capabilityProvider.identity.instanceId,
            report.providerGeneration,
            report.negotiationId,
            firstLifeComposition.currentConnectionSessionIds(),
            timestamp(now),
        )
        return checkNotNull(firstLifeDispatcher) to context
    }

    private fun disconnectSafely(): ConnectionState {
        firstLifeOrientationBinding.endHold()
        t032AcceptanceSession?.onDisconnect(EmergencyStopReason.TRANSPORT_LOST)
        return connectionOwner.requestDisconnect()
    }

    private fun recordOrientationEvidence(effect: OrientationEffect, result: FirstLifeOrientationEffectResult) {
        val trace = result.trace
        val invoked = trace?.receipt?.dispatch is AuthorizedDispatchResult.Invoked
        val move = effect is OrientationEffect.ProposeTurn
        firstLifeOrientationEvidence = firstLifeOrientationEvidence.copy(
            moveInvocationCount = firstLifeOrientationEvidence.moveInvocationCount +
                if (move && result.outcome == OrientationEffectOutcome.MOVE_ACCEPTED) 1 else 0,
            stopConfirmedCount = firstLifeOrientationEvidence.stopConfirmedCount +
                if (!move && result.outcome == OrientationEffectOutcome.STOP_CONFIRMED) 1 else 0,
            lastOutcome = result.outcome,
            lastRoute = trace?.route ?: firstLifeOrientationEvidence.lastRoute,
            h030AuthorizationCorrelated = if (move) {
                trace?.decision?.status == io.redi.robot.trust.runtime.AuthorizationDecisionStatus.AUTHORIZED
            } else firstLifeOrientationEvidence.h030AuthorizationCorrelated,
            h018Invoked = if (move) invoked else firstLifeOrientationEvidence.h018Invoked,
            t032StopObserved = if (!move) trace?.receipt?.stopObserved == true
            else firstLifeOrientationEvidence.t032StopObserved,
        )
    }

    private fun publishT032State(state: T032AcceptanceState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            t032Listeners.toList().forEach { it(state) }
        } else {
            handshakeHandler.post { t032Listeners.toList().forEach { it(state) } }
        }
    }

    private fun timestamp(value: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(value)) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> error(result.error)
    }

    private fun <T> provider(result: CapabilityProviderPortResult<T>): T = when (result) {
        is CapabilityProviderPortResult.Success -> result.value
        is CapabilityProviderPortResult.Failure -> error(result.error)
    }

    private fun exportFed9CaptureInternal(): Fed9FixtureExportResult {
        val result = fed9Capture?.export() ?: return Fed9FixtureExportResult.Failure("No FED9 capture run exists")
        if (result !is Fed9FixtureExportResult.Success) return result
        return runCatching {
            val directory = File(filesDir, "diagnostics").apply { mkdirs() }
            val file = File(directory, "fed9-capture-${System.currentTimeMillis()}.json")
            file.writeText(result.json)
            result.copy(filePath = file.absolutePath)
        }.getOrElse { Fed9FixtureExportResult.Failure("Unable to write fixture: ${it.message}") }
    }

    private fun notificationFor(state: ConnectionState): Notification {
        val disconnectIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ConnectedDeviceService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("REDI robot connection")
            .setContentText("BLE connection: ${state.phase.name.lowercase()}")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(0, "Disconnect", disconnectIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Connected robot", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "io.redi.robot.action.START_CONNECTED_DEVICE_SERVICE"
        const val ACTION_DISCONNECT = "io.redi.robot.action.DISCONNECT_CONNECTED_DEVICE"
        const val ACTION_STOP = "io.redi.robot.action.STOP_CONNECTED_DEVICE_SERVICE"
        private const val CHANNEL_ID = "connected-device"
        private const val NOTIFICATION_ID = 2_001

        /** Must be called directly from a visible, explicit user action. */
        fun startUserControlled(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConnectedDeviceService::class.java).setAction(ACTION_START))
        }
    }
}
