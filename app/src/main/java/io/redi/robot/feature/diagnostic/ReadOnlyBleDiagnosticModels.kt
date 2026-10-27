package io.redi.robot.feature.diagnostic

import io.redi.robot.body.looi.connection.ConnectionState
import io.redi.robot.body.looi.connection.GattServiceInventory
import io.redi.robot.body.looi.discovery.LooiCandidateStatus
import io.redi.robot.body.looi.discovery.LooiEvidence
import io.redi.robot.body.looi.protocol.handshake.HandshakeAttemptSummary
import io.redi.robot.body.looi.protocol.telemetrycapture.Fed9CaptureState

data class BleDiagnosticUiState(
    val permissionsReady: Boolean = false,
    val scanActive: Boolean = false,
    val scanStatus: String = "Not started",
    val devices: List<DiagnosticBleDevice> = emptyList(),
    val connection: ConnectionState = ConnectionState(),
    val gattStatusCodes: List<Int> = emptyList(),
    val inventory: List<GattServiceInventory> = emptyList(),
    val handshake: HandshakeAttemptSummary? = null,
    val fed9Capture: Fed9CaptureState = Fed9CaptureState(),
    val fed9ExportStatus: String? = null,
    val log: List<DiagnosticLogEntry> = emptyList(),
)

/** A UI-safe BLE device projection. The platform address is intentionally not retained here. */
data class DiagnosticBleDevice(
    val key: String,
    val displayName: String,
    val rssi: Int?,
    val candidateStatus: LooiCandidateStatus,
    val confidence: Float,
    val evidence: List<LooiEvidence>,
)

data class DiagnosticLogEntry(
    val timestampUnixMillis: Long,
    val message: String,
)
