package io.redi.robot.platform.diagnostic

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.redi.robot.body.looi.ble.scan.BleScanCompletedReason
import io.redi.robot.body.looi.ble.scan.BleScanConfig
import io.redi.robot.body.looi.ble.scan.BleScanEvent
import io.redi.robot.body.looi.ble.scan.BleScanResult
import io.redi.robot.body.looi.ble.scan.BleScanSession
import io.redi.robot.body.looi.connection.ConnectionController
import io.redi.robot.body.looi.connection.ConnectionPhase
import io.redi.robot.body.looi.connection.ConnectionTarget
import io.redi.robot.body.looi.connection.GattTransportAdapter
import io.redi.robot.body.looi.connection.GattTransportDiagnosticEvent
import io.redi.robot.body.looi.connection.GattTransportDiagnosticsObserver
import io.redi.robot.body.looi.connection.GattTransportEvent
import io.redi.robot.body.looi.connection.GattTransportObserver
import io.redi.robot.body.looi.discovery.LooiScanCandidate
import io.redi.robot.body.looi.discovery.rankLooiScanCandidates
import io.redi.robot.body.looi.discovery.toLooiScanObservation
import io.redi.robot.feature.diagnostic.BleDiagnosticUiState
import io.redi.robot.feature.diagnostic.DiagnosticBleDevice
import io.redi.robot.feature.diagnostic.DiagnosticLogEntry
import io.redi.robot.platform.transport.ble.gatt.AndroidGattTransportAdapter
import io.redi.robot.platform.transport.ble.scan.AndroidBleScanner
import io.redi.robot.body.looi.protocol.handshake.HandshakeCandidateId
import io.redi.robot.body.looi.protocol.telemetrycapture.Fed9FixtureExportResult

/**
 * Platform-owned diagnostic composition of scan, candidate ranking, GATT discovery, and named T016 experiments.
 * It has no protocol profile selection or control/movement capability.
 */
class ReadOnlyBleDiagnosticController(
    context: Context,
    private val onStateChanged: (BleDiagnosticUiState) -> Unit,
    private val managedConnection: DiagnosticConnectionClient? = null,
    private val scanner: AndroidBleScanner = AndroidBleScanner(context),
    gattAdapterFactory: (GattTransportDiagnosticsObserver) -> GattTransportAdapter = {
        AndroidGattTransportAdapter(context, diagnosticsObserver = it)
    },
) : GattTransportDiagnosticsObserver {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rawDevices = linkedMapOf<String, BleScanResult>()
    private val uiKeys = linkedMapOf<String, String>()
    private var scanSession: BleScanSession? = null
    private var state = BleDiagnosticUiState()

    private val connectionController = ConnectionController(
        transport = object : GattTransportAdapter {
            private val delegate = gattAdapterFactory(this@ReadOnlyBleDiagnosticController)

            override fun connect(target: ConnectionTarget, generation: Long, callback: GattTransportObserver) {
                delegate.connect(
                    target,
                    generation,
                    object : GattTransportObserver {
                        override fun onTransportEvent(event: GattTransportEvent) {
                            callback.onTransportEvent(event)
                            post { recordTransportEvent(event) }
                        }
                    },
                )
            }

            override fun disconnect(generation: Long) = delegate.disconnect(generation)
        },
    )

    private val managedConnectionListener: (io.redi.robot.body.looi.connection.ConnectionState) -> Unit = { connection ->
        post {
            state = state.copy(connection = connection)
            update("Service-owned connection state: ${connection.phase}")
            publish()
        }
    }
    private val fed9CaptureListener: (io.redi.robot.body.looi.protocol.telemetrycapture.Fed9CaptureState) -> Unit = { capture ->
        post {
            state = state.copy(fed9Capture = capture)
            update("FED9 capture: ${capture.subscriptionState}; notifications=${capture.notificationCount}")
            publish()
        }
    }

    init {
        managedConnection?.addStateListener(managedConnectionListener)
        managedConnection?.addFed9CaptureListener(fed9CaptureListener)
        publish()
    }

    fun updatePermissionsReady(ready: Boolean) = post {
        state = state.copy(permissionsReady = ready)
        if (!ready && state.scanActive) stopScanInternal("Stopped: Bluetooth permissions unavailable")
        publish()
    }

    fun startScan() = post {
        if (!state.permissionsReady) {
            update("Scan not started: Bluetooth permissions unavailable")
            state = state.copy(scanStatus = "Permissions unavailable")
            publish()
            return@post
        }
        if (scanSession?.isActive == true) return@post
        rawDevices.clear()
        uiKeys.clear()
        state = state.copy(devices = emptyList(), inventory = emptyList(), scanStatus = "Scanning")
        update("Scan started (read-only)")
        scanSession = scanner.startScan(BleScanConfig()) { event -> post { handleScanEvent(event) } }
        state = state.copy(scanActive = scanSession?.isActive == true)
        publish()
    }

    fun stopScan() = post { stopScanInternal("Scan stopped by operator") }

    fun connect(deviceKey: String) = post {
        val deviceId = uiKeys.entries.firstOrNull { it.value == deviceKey }?.key ?: return@post
        val result = rawDevices[deviceId] ?: return@post
        if (!state.permissionsReady) {
            update("Connection not started: Bluetooth permissions unavailable")
            publish()
            return@post
        }
        val activeState = managedConnection?.let { state.connection } ?: connectionController.state
        if (activeState.phase !in setOf(ConnectionPhase.IDLE, ConnectionPhase.DISCONNECTED, ConnectionPhase.FAILED)) {
            update("Connection ignored: a transport attempt is already active")
            publish()
            return@post
        }
        state = state.copy(inventory = emptyList(), gattStatusCodes = emptyList())
        update("Manual read-only connection requested for $deviceKey")
        val target = ConnectionTarget(deviceId = result.id, displayName = result.displayName)
        val connection = managedConnection?.connect(target) ?: connectionController.requestConnect(target)
        state = state.copy(connection = connection)
        publish()
    }

    fun disconnect() = post {
        val connection = managedConnection?.disconnect() ?: connectionController.requestDisconnect()
        state = state.copy(connection = connection)
        update("Disconnect requested")
        publish()
    }

    fun startHandshake(candidate: HandshakeCandidateId) = post {
        val summary = managedConnection?.startHandshake(candidate)
        if (summary == null) {
            update("Handshake $candidate not started: transport must be ready and service-owned")
        } else {
            state = state.copy(handshake = summary)
            update("Handshake $candidate started: steps=${summary.stepResults.size}; protocolReady=${summary.protocolReady}")
            refreshHandshakeSummary()
        }
        publish()
    }

    fun cancelHandshake() = post {
        val summary = managedConnection?.cancelHandshake()
        if (summary != null) state = state.copy(handshake = summary)
        update(if (summary == null) "No handshake experiment to cancel" else "Handshake cancelled")
        publish()
    }

    fun startFed9Capture() = post {
        val capture = managedConnection?.startFed9Capture()
        if (capture == null) update("FED9 capture unavailable: service connection is binding") else state = state.copy(fed9Capture = capture)
        publish()
    }

    fun startFed9CaptureWithVerifiedHandshakeA() = post {
        val summary = managedConnection?.startFed9CaptureWithVerifiedHandshakeA()
        if (summary == null) {
            update("FED9 prerequisite not started: transport must be ready and service-owned")
        } else {
            state = state.copy(handshake = summary)
            update("Verified non-motion Handshake A started; FED9 will start only after all steps succeed")
            refreshHandshakeSummary()
        }
        publish()
    }

    fun stopFed9Capture() = post {
        val capture = managedConnection?.stopFed9Capture()
        if (capture == null) update("No FED9 capture to stop") else state = state.copy(fed9Capture = capture)
        publish()
    }

    fun exportFed9Capture() = post {
        state = state.copy(
            fed9ExportStatus = when (val result = managedConnection?.exportFed9Capture()) {
                is Fed9FixtureExportResult.Success -> "Redacted fixture exported: ${result.filePath ?: "storage unavailable"} (${result.json.length} bytes)"
                is Fed9FixtureExportResult.Failure -> "Fixture export failed: ${result.reason}"
                null -> "Fixture export unavailable: service connection is binding"
            },
        )
        publish()
    }

    fun close() = post {
        scanSession?.stop()
        scanSession = null
        managedConnection?.removeStateListener(managedConnectionListener)
        managedConnection?.removeFed9CaptureListener(fed9CaptureListener)
        val connection = if (managedConnection == null) connectionController.requestDisconnect() else state.connection
        state = state.copy(scanActive = false, connection = connection)
        update("Diagnostic session closed")
        publish()
    }

    override fun onDiagnosticEvent(event: GattTransportDiagnosticEvent) = post {
        when (event) {
            is GattTransportDiagnosticEvent.ConnectionStateChanged -> {
                state = state.copy(gattStatusCodes = state.gattStatusCodes + event.status)
                update("GATT connection callback: status=${event.status}, state=${event.androidState}, generation=${event.generation}")
            }
            is GattTransportDiagnosticEvent.ServiceDiscoveryStarted ->
                update("GATT service discovery started for generation=${event.generation}")
            is GattTransportDiagnosticEvent.ServicesDiscovered -> {
                state = state.copy(
                    gattStatusCodes = state.gattStatusCodes + event.status,
                    inventory = event.services,
                )
                update("GATT service discovery callback: status=${event.status}, services=${event.services.size}")
            }
            is GattTransportDiagnosticEvent.GattClosed ->
                update("GATT closed: ${event.reason}, generation=${event.generation}")
        }
        publish()
    }

    private fun handleScanEvent(event: BleScanEvent) {
        when (event) {
            is BleScanEvent.DeviceFound -> {
                rawDevices[event.result.id] = event.result
                uiKeys.getOrPut(event.result.id) { "device-${uiKeys.size + 1}" }
                state = state.copy(devices = projectDevices())
                update("Observed ${uiKeys.getValue(event.result.id)}")
            }
            is BleScanEvent.Failure -> {
                state = state.copy(scanStatus = "Scan failure: ${event.cause}")
                update("Scan failure: ${event.cause}")
            }
            is BleScanEvent.Completed -> {
                scanSession = null
                state = state.copy(scanActive = false, scanStatus = completedStatus(event.reason))
                update("Scan completed: ${event.reason}")
            }
        }
        publish()
    }

    private fun recordTransportEvent(event: GattTransportEvent) {
        state = state.copy(connection = connectionController.state)
        update("Transport event: ${event.javaClass.simpleName}; state=${connectionController.state.phase}")
        publish()
    }

    private fun projectDevices(): List<DiagnosticBleDevice> {
        val candidates = rankLooiScanCandidates(rawDevices.values.map { it.toLooiScanObservation() })
            .associateBy(LooiScanCandidate::deviceId)
        return rawDevices.values.mapNotNull { result ->
            val candidate = candidates[result.id] ?: return@mapNotNull null
            DiagnosticBleDevice(
                key = uiKeys.getValue(result.id),
                displayName = result.displayName ?: "Unnamed BLE device",
                rssi = result.rssi,
                candidateStatus = candidate.status,
                confidence = candidate.confidence,
                evidence = candidate.reasons,
            )
        }
    }

    private fun stopScanInternal(message: String) {
        scanSession?.stop()
        scanSession = null
        state = state.copy(scanActive = false, scanStatus = "Stopped")
        update(message)
        publish()
    }

    private fun completedStatus(reason: BleScanCompletedReason): String = when (reason) {
        BleScanCompletedReason.REQUESTED_STOP -> "Stopped"
        BleScanCompletedReason.TIMEOUT -> "Timed out"
        BleScanCompletedReason.FAILED -> "Failed"
    }

    private fun refreshHandshakeSummary() {
        val summary = managedConnection?.handshakeSummary() ?: return
        if (state.handshake != summary) {
            state = state.copy(handshake = summary)
            update(
                "Handshake ${summary.candidate}: running=${summary.running}; completed=${summary.completed}; " +
                    "steps=${summary.stepResults.size}; protocolReady=${summary.protocolReady}",
            )
            publish()
        }
        if (managedConnection?.handshakeActive() == true) {
            mainHandler.postDelayed({ refreshHandshakeSummary() }, HANDSHAKE_STATUS_POLL_MS)
        }
    }

    private fun update(message: String) {
        val entry = DiagnosticLogEntry(System.currentTimeMillis(), message)
        state = state.copy(log = (state.log + entry).takeLast(MAX_LOG_ENTRIES))
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun publish() = onStateChanged(state)

    private companion object {
        const val MAX_LOG_ENTRIES = 200
        const val HANDSHAKE_STATUS_POLL_MS = 100L
    }
}
