package io.redi.robot.platform.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.redi.robot.body.looi.ble.scan.BleScanCompletedReason
import io.redi.robot.body.looi.ble.scan.BleScanConfig
import io.redi.robot.body.looi.ble.scan.BleScanEvent
import io.redi.robot.body.looi.ble.scan.BleScanFailure
import io.redi.robot.body.looi.ble.scan.BleScanResult
import io.redi.robot.body.looi.ble.scan.BleScanSession
import io.redi.robot.body.looi.connection.ConnectionTarget
import io.redi.robot.body.looi.connection.ConnectionFailure
import io.redi.robot.body.looi.connection.ConnectionPhase
import io.redi.robot.body.looi.connection.ConnectionState
import io.redi.robot.body.looi.connection.GattFailureKind
import io.redi.robot.platform.transport.ble.scan.AndroidBleScanner

enum class FirstLifeProviderSetupStage {
    IDLE,
    DISCOVERING,
    CANDIDATE_FOUND,
    CONNECTING,
    PREPARING,
    READY,
    PERMISSION_REQUIRED,
    BLUETOOTH_DISABLED,
    NOT_FOUND,
    FAILED,
    DISCONNECTED,
}

enum class FirstLifeProviderSetupFailure {
    TIMEOUT,
    AUTHENTICATION_REQUIRED,
    LINK_LOST,
    UNSUPPORTED,
    CANCELLED,
    UNKNOWN,
}

/** Redacted app-shell state. The platform-owned target is deliberately absent. */
data class FirstLifeProviderSetupState(
    val stage: FirstLifeProviderSetupStage = FirstLifeProviderSetupStage.IDLE,
    val candidateAvailable: Boolean = false,
    val connectionActive: Boolean = false,
    val failure: FirstLifeProviderSetupFailure? = null,
)

/**
 * Narrow FL014 bridge from the existing Android scanner to the existing FL006 service composition.
 * It owns neither GATT nor protocol execution and never publishes the private scan identifier.
 */
class FirstLifeProviderSetupController(
    context: Context,
    private val binder: ConnectedDeviceService.ConnectionBinder,
    private val onStateChanged: (FirstLifeProviderSetupState) -> Unit,
    private val scanner: io.redi.robot.body.looi.ble.scan.BleScanner =
        AndroidBleScanner(context.applicationContext),
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanSession: BleScanSession? = null
    private var candidate: BleScanResult? = null
    private var handshakeGeneration: Long? = null
    private var closed = false
    private var state = FirstLifeProviderSetupState()

    private val providerListener: (LooiFirstLifeServiceSnapshot) -> Unit = { snapshot ->
        mainHandler.post { handleProviderSnapshot(snapshot) }
    }
    private val connectionListener: (ConnectionState) -> Unit = { connection ->
        mainHandler.post { handleConnectionState(connection) }
    }

    init {
        binder.addFirstLifeListener(providerListener)
        binder.addStateListener(connectionListener)
        publish()
    }

    fun discover() {
        if (closed || state.connectionActive || state.stage == FirstLifeProviderSetupStage.DISCOVERING) return
        scanSession?.stop()
        scanSession = null
        candidate = null
        state = FirstLifeProviderSetupState(FirstLifeProviderSetupStage.DISCOVERING)
        publish()
        scanSession = scanner.startScan(BleScanConfig(timeoutMillis = DISCOVERY_TIMEOUT_MS)) { event ->
            mainHandler.post { handleScanEvent(event) }
        }
    }

    fun connectCandidate() {
        val selected = candidate ?: return
        if (closed || state.connectionActive) return
        scanSession?.stop()
        scanSession = null
        state = FirstLifeProviderSetupState(
            stage = FirstLifeProviderSetupStage.CONNECTING,
            candidateAvailable = true,
            connectionActive = true,
        )
        publish()
        binder.connectFirstLife(ConnectionTarget(selected.id, selected.displayName))
    }

    fun disconnect() {
        if (closed) return
        scanSession?.stop()
        scanSession = null
        candidate = null
        handshakeGeneration = null
        binder.disconnectFirstLife()
        state = FirstLifeProviderSetupState(FirstLifeProviderSetupStage.DISCONNECTED)
        publish()
    }

    fun background() {
        if (closed) return
        scanSession?.stop()
        scanSession = null
        if (state.stage == FirstLifeProviderSetupStage.DISCOVERING) {
            state = FirstLifeProviderSetupState(FirstLifeProviderSetupStage.IDLE)
            publish()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        scanSession?.stop()
        scanSession = null
        binder.removeFirstLifeListener(providerListener)
        binder.removeStateListener(connectionListener)
    }

    private fun handleScanEvent(event: BleScanEvent) {
        if (closed) return
        when (event) {
            is BleScanEvent.DeviceFound -> {
                if (candidate == null && event.result.displayName?.contains(LOOI_NAME_MARKER, ignoreCase = true) == true) {
                    candidate = event.result
                    state = FirstLifeProviderSetupState(
                        stage = FirstLifeProviderSetupStage.CANDIDATE_FOUND,
                        candidateAvailable = true,
                    )
                    publish()
                    scanSession?.stop()
                    scanSession = null
                }
            }

            is BleScanEvent.Failure -> {
                if (candidate != null) return
                state = FirstLifeProviderSetupState(
                    stage = when (event.cause) {
                        BleScanFailure.PermissionUnavailable -> FirstLifeProviderSetupStage.PERMISSION_REQUIRED
                        BleScanFailure.BluetoothDisabled -> FirstLifeProviderSetupStage.BLUETOOTH_DISABLED
                        is BleScanFailure.Timeout -> FirstLifeProviderSetupStage.NOT_FOUND
                        BleScanFailure.ScannerUnavailable,
                        BleScanFailure.ScanInProgress,
                        is BleScanFailure.Unknown,
                        -> FirstLifeProviderSetupStage.FAILED
                    },
                )
                publish()
            }

            is BleScanEvent.Completed -> {
                scanSession = null
                if (candidate == null &&
                    state.stage == FirstLifeProviderSetupStage.DISCOVERING &&
                    event.reason != BleScanCompletedReason.REQUESTED_STOP
                ) {
                    state = FirstLifeProviderSetupState(FirstLifeProviderSetupStage.NOT_FOUND)
                    publish()
                }
            }
        }
    }

    private fun handleProviderSnapshot(snapshot: LooiFirstLifeServiceSnapshot) {
        if (closed) return
        when (snapshot.stage) {
            LooiFirstLifeConnectionStage.TRANSPORT_READY -> {
                state = FirstLifeProviderSetupState(
                    stage = FirstLifeProviderSetupStage.PREPARING,
                    candidateAvailable = candidate != null,
                    connectionActive = true,
                )
                publish()
                val generation = snapshot.providerGeneration
                if (generation != null && handshakeGeneration != generation) {
                    handshakeGeneration = generation
                    if (binder.startFed9CaptureWithVerifiedHandshakeA() == null) {
                        state = FirstLifeProviderSetupState(
                            stage = FirstLifeProviderSetupStage.FAILED,
                            candidateAvailable = candidate != null,
                            connectionActive = true,
                        )
                        publish()
                    }
                }
            }

            LooiFirstLifeConnectionStage.PROTOCOL_READY -> {
                state = FirstLifeProviderSetupState(
                    stage = FirstLifeProviderSetupStage.READY,
                    candidateAvailable = candidate != null,
                    connectionActive = true,
                )
                publish()
            }

            LooiFirstLifeConnectionStage.CONNECTING,
            LooiFirstLifeConnectionStage.CHANNEL_ESTABLISHED,
            -> {
                state = FirstLifeProviderSetupState(
                    stage = FirstLifeProviderSetupStage.CONNECTING,
                    candidateAvailable = candidate != null,
                    connectionActive = true,
                )
                publish()
            }

            LooiFirstLifeConnectionStage.IDENTITY_BLOCKED,
            LooiFirstLifeConnectionStage.FAILED,
            -> {
                state = state.copy(
                    stage = FirstLifeProviderSetupStage.FAILED,
                    candidateAvailable = candidate != null,
                    connectionActive = false,
                )
                publish()
            }

            LooiFirstLifeConnectionStage.DISCONNECTED -> {
                handshakeGeneration = null
                state = FirstLifeProviderSetupState(FirstLifeProviderSetupStage.DISCONNECTED)
                publish()
            }

            LooiFirstLifeConnectionStage.IDLE -> Unit
        }
    }

    private fun handleConnectionState(connection: ConnectionState) {
        if (closed || connection.phase != ConnectionPhase.FAILED) return
        state = FirstLifeProviderSetupState(
            stage = FirstLifeProviderSetupStage.FAILED,
            candidateAvailable = candidate != null,
            connectionActive = false,
            failure = connection.failure.toSetupFailure(),
        )
        publish()
    }

    private fun ConnectionFailure?.toSetupFailure(): FirstLifeProviderSetupFailure = when (this) {
        ConnectionFailure.CancelledByRequest -> FirstLifeProviderSetupFailure.CANCELLED
        is ConnectionFailure.GattFailure -> when (kind) {
            GattFailureKind.TIMEOUT -> FirstLifeProviderSetupFailure.TIMEOUT
            GattFailureKind.AUTH_REQUIRED -> FirstLifeProviderSetupFailure.AUTHENTICATION_REQUIRED
            GattFailureKind.LINK_LOST -> FirstLifeProviderSetupFailure.LINK_LOST
            GattFailureKind.UNSUPPORTED -> FirstLifeProviderSetupFailure.UNSUPPORTED
            GattFailureKind.UNKNOWN -> FirstLifeProviderSetupFailure.UNKNOWN
        }
        is ConnectionFailure.Unknown,
        null,
        -> FirstLifeProviderSetupFailure.UNKNOWN
    }

    private fun publish() {
        val snapshot = state.copy()
        mainHandler.post {
            if (!closed) onStateChanged(snapshot)
        }
    }

    private companion object {
        const val DISCOVERY_TIMEOUT_MS = 16_000L
        const val LOOI_NAME_MARKER = "LOOI"
    }
}
