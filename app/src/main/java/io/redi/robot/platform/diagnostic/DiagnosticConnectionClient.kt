package io.redi.robot.platform.diagnostic

import android.content.Context
import io.redi.robot.body.looi.connection.ConnectionState
import io.redi.robot.body.looi.connection.ConnectionTarget
import io.redi.robot.platform.service.ConnectedDeviceService
import io.redi.robot.body.looi.protocol.handshake.HandshakeAttemptSummary
import io.redi.robot.body.looi.protocol.handshake.HandshakeCandidateId
import io.redi.robot.body.looi.protocol.telemetrycapture.Fed9CaptureState
import io.redi.robot.body.looi.protocol.telemetrycapture.Fed9FixtureExportResult

/** UI-facing service binding. The activity observes a service-owned session rather than owning GATT. */
interface DiagnosticConnectionClient {
    fun connect(target: ConnectionTarget): ConnectionState
    fun disconnect(): ConnectionState
    fun addStateListener(listener: (ConnectionState) -> Unit)
    fun removeStateListener(listener: (ConnectionState) -> Unit)
    fun startHandshake(candidate: HandshakeCandidateId): HandshakeAttemptSummary?
    fun cancelHandshake(): HandshakeAttemptSummary?
    fun handshakeSummary(): HandshakeAttemptSummary?
    fun handshakeActive(): Boolean
    fun startFed9CaptureWithVerifiedHandshakeA(): HandshakeAttemptSummary?
    fun startFed9Capture(): Fed9CaptureState
    fun stopFed9Capture(): Fed9CaptureState
    fun exportFed9Capture(): Fed9FixtureExportResult
    fun addFed9CaptureListener(listener: (Fed9CaptureState) -> Unit)
    fun removeFed9CaptureListener(listener: (Fed9CaptureState) -> Unit)
}

class BoundDiagnosticConnectionClient(
    private val context: Context,
    private val binder: ConnectedDeviceService.ConnectionBinder,
) : DiagnosticConnectionClient {
    override fun connect(target: ConnectionTarget): ConnectionState {
        ConnectedDeviceService.startUserControlled(context)
        return binder.connect(target)
    }

    override fun disconnect(): ConnectionState = binder.disconnect()
    override fun addStateListener(listener: (ConnectionState) -> Unit) = binder.addStateListener(listener)
    override fun removeStateListener(listener: (ConnectionState) -> Unit) = binder.removeStateListener(listener)
    override fun startHandshake(candidate: HandshakeCandidateId): HandshakeAttemptSummary? = binder.startHandshake(candidate)
    override fun cancelHandshake(): HandshakeAttemptSummary? = binder.cancelHandshake()
    override fun handshakeSummary(): HandshakeAttemptSummary? = binder.handshakeSummary()
    override fun handshakeActive(): Boolean = binder.handshakeActive()
    override fun startFed9CaptureWithVerifiedHandshakeA(): HandshakeAttemptSummary? =
        binder.startFed9CaptureWithVerifiedHandshakeA()
    override fun startFed9Capture(): Fed9CaptureState = binder.startFed9Capture()
    override fun stopFed9Capture(): Fed9CaptureState = binder.stopFed9Capture()
    override fun exportFed9Capture(): Fed9FixtureExportResult = binder.exportFed9Capture()
    override fun addFed9CaptureListener(listener: (Fed9CaptureState) -> Unit) = binder.addFed9CaptureListener(listener)
    override fun removeFed9CaptureListener(listener: (Fed9CaptureState) -> Unit) = binder.removeFed9CaptureListener(listener)
}
