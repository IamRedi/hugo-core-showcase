package io.redi.robot.platform.connection.ble

import io.redi.robot.connection.api.ConnectionAvailability
import io.redi.robot.connection.api.ConnectionAvailabilityReason
import io.redi.robot.connection.api.ConnectionAvailabilitySnapshot
import io.redi.robot.connection.api.ConnectionCancellationRequest
import io.redi.robot.connection.api.ConnectionCommandResult
import io.redi.robot.connection.api.ConnectionCommandState
import io.redi.robot.connection.api.ConnectionConnectRequest
import io.redi.robot.connection.api.ConnectionDisconnectRequest
import io.redi.robot.connection.api.ConnectionFailure
import io.redi.robot.connection.api.ConnectionFailureCode
import io.redi.robot.connection.api.ConnectionGeneration
import io.redi.robot.connection.api.ConnectionLifecycleDisposition
import io.redi.robot.connection.api.ConnectionLifecycleEvent
import io.redi.robot.connection.api.ConnectionLifecycleReducer
import io.redi.robot.connection.api.ConnectionLifecycleSnapshot
import io.redi.robot.connection.api.ConnectionLifecycleState
import io.redi.robot.connection.api.ConnectionMetadata
import io.redi.robot.connection.api.ConnectionMethodId
import io.redi.robot.connection.api.ConnectionMethodReference
import io.redi.robot.connection.api.ConnectionMethodVersion
import io.redi.robot.connection.api.ConnectionProvider
import io.redi.robot.connection.api.ConnectionRequestId
import io.redi.robot.connection.api.ConnectionResult
import io.redi.robot.connection.api.ConnectionSessionId
import java.security.MessageDigest

val BLE_CONNECTION_METHOD: ConnectionMethodReference = ConnectionMethodReference(
    id = connectionValue(ConnectionMethodId.parse("io.hugo.connection/bluetooth-le")),
    version = connectionValue(ConnectionMethodVersion.parse("1.0")),
)

fun interface BleConnectionLifecycleObserver {
    fun onChanged(snapshot: ConnectionLifecycleSnapshot)
}

enum class BleConnectionDiagnosticEvent {
    CONNECT_ACCEPTED,
    TRANSPORT_CONNECTING,
    CHANNEL_ESTABLISHED,
    TRANSPORT_READY,
    TRANSPORT_FAILURE,
    TRANSPORT_DISCONNECTED,
    TIMEOUT,
    CANCEL_REQUESTED,
    DISCONNECT_REQUESTED,
    CALLBACK_IGNORED,
}

/** Redacted evidence: correlation and typed status only, never a transport handle or private endpoint. */
data class BleConnectionDiagnostic(
    val requestId: ConnectionRequestId,
    val generation: ConnectionGeneration,
    val event: BleConnectionDiagnosticEvent,
    val failureKind: BleTransportFailureKind? = null,
    val operation: BleTransportOperation? = null,
    val platformStatus: Int? = null,
)

class BleConnectionProvider(
    override val method: ConnectionMethodReference = BLE_CONNECTION_METHOD,
    private val transport: BleTransportConnection,
    private val endpoints: BleEndpointResolver,
    private val availability: BleTransportAvailabilityProbe,
    private val clock: BleMonotonicClock,
    private val deadlines: BleConnectionDeadlineScheduler,
    private val sessionIds: BleConnectionSessionIdFactory = DeterministicBleConnectionSessionIds,
    private val lifecycleObserver: BleConnectionLifecycleObserver = BleConnectionLifecycleObserver {},
) : ConnectionProvider {
    private val lock = Any()
    private var lifecycle = ConnectionLifecycleSnapshot.initial()
    private var active: ActiveAttempt? = null
    private var lastRequestId: ConnectionRequestId? = null
    private var lastSessionId: ConnectionSessionId? = null
    private val diagnostics = mutableListOf<BleConnectionDiagnostic>()

    override fun availabilitySnapshot(): ConnectionAvailabilitySnapshot {
        val state = availability.current()
        val (value, reason) = state.toH013Availability()
        return connectionValue(ConnectionAvailabilitySnapshot.create(method, value, clock.now(), reason))
    }

    override fun connect(request: ConnectionConnectRequest): ConnectionCommandResult {
        if (request.method != method) return rejected(request.requestId, request.generation, ConnectionFailureCode.UNSUPPORTED_METHOD)

        val availabilityFailure = availability.current().toConnectFailure()
        if (availabilityFailure != null) return rejected(request.requestId, request.generation, availabilityFailure)

        val target = when (val resolved = endpoints.resolve(request.endpoint)) {
            is BleEndpointResolution.Resolved -> resolved.target
            BleEndpointResolution.Unavailable -> return rejected(
                request.requestId,
                request.generation,
                ConnectionFailureCode.UNAVAILABLE,
            )
            BleEndpointResolution.Rejected -> return rejected(
                request.requestId,
                request.generation,
                ConnectionFailureCode.CONNECTION_REJECTED,
            )
        }

        val attempt: ActiveAttempt
        val next: ConnectionLifecycleSnapshot
        synchronized(lock) {
            val reduction = ConnectionLifecycleReducer.reduce(
                lifecycle,
                ConnectionLifecycleEvent.ConnectRequested(request),
            )
            if (reduction.disposition !is ConnectionLifecycleDisposition.Transitioned) {
                return rejected(request.requestId, request.generation, ConnectionFailureCode.CONNECTION_REJECTED)
            }
            lifecycle = reduction.snapshot
            attempt = ActiveAttempt(
                request = request,
                target = target,
                sessionId = sessionIds.create(request.requestId, request.generation),
            )
            active = attempt
            lastRequestId = request.requestId
            lastSessionId = null
            diagnostics += diagnostic(attempt, BleConnectionDiagnosticEvent.CONNECT_ACCEPTED)
            next = lifecycle
        }
        lifecycleObserver.onChanged(next)

        val deadline = deadlines.schedule(request.requestId, request.generation, request.deadline) {
            onDeadlineElapsed(attempt)
        }
        val shouldConnect = synchronized(lock) {
            if (active === attempt) {
                attempt.deadline = deadline
                true
            } else {
                false
            }
        }
        if (!shouldConnect) {
            deadline.cancel()
            return accepted(request.requestId, request.generation)
        }

        runCatching {
            transport.connect(target, request.generation.value, BleTransportObserver { event ->
                onTransportEvent(attempt, event)
            })
        }.onFailure {
            onTransportEvent(
                attempt,
                BleTransportEvent.Failure(
                    request.generation.value,
                    BleTransportFailure(BleTransportFailureKind.TRANSPORT_FAILURE, BleTransportOperation.CONNECT),
                ),
            )
        }
        return accepted(request.requestId, request.generation)
    }

    override fun cancel(request: ConnectionCancellationRequest): ConnectionCommandResult {
        val attempt: ActiveAttempt
        val next: ConnectionLifecycleSnapshot
        val terminal: Boolean
        synchronized(lock) {
            val candidate = active
            if (candidate == null) {
                return if (lastRequestId == request.requestId && lifecycle.latestGeneration == request.generation) {
                    accepted(request.requestId, request.generation)
                } else {
                    rejected(request.requestId, request.generation, ConnectionFailureCode.CONNECTION_REJECTED)
                }
            }
            if (!candidate.matches(request.requestId, request.generation)) {
                return rejected(request.requestId, request.generation, ConnectionFailureCode.CONNECTION_REJECTED)
            }
            val reduction = ConnectionLifecycleReducer.reduce(
                lifecycle,
                ConnectionLifecycleEvent.CancellationRequested(request.generation, request.requestId),
            )
            if (reduction.disposition !is ConnectionLifecycleDisposition.Transitioned) {
                return accepted(request.requestId, request.generation)
            }
            lifecycle = reduction.snapshot
            attempt = candidate
            terminal = lifecycle.state is ConnectionLifecycleState.Cancelled
            diagnostics += diagnostic(attempt, BleConnectionDiagnosticEvent.CANCEL_REQUESTED)
            next = lifecycle
        }
        lifecycleObserver.onChanged(next)
        cleanup(attempt, terminal)
        return accepted(request.requestId, request.generation)
    }

    override fun disconnect(request: ConnectionDisconnectRequest): ConnectionCommandResult {
        val attempt: ActiveAttempt
        val next: ConnectionLifecycleSnapshot
        synchronized(lock) {
            val candidate = active
            if (candidate == null) {
                return if (
                    lastRequestId == request.requestId &&
                    lastSessionId == request.sessionId &&
                    lifecycle.latestGeneration == request.generation
                ) {
                    accepted(request.requestId, request.generation)
                } else {
                    rejected(request.requestId, request.generation, ConnectionFailureCode.CONNECTION_REJECTED)
                }
            }
            if (!candidate.matches(request.requestId, request.generation) || candidate.sessionId != request.sessionId) {
                return rejected(request.requestId, request.generation, ConnectionFailureCode.CONNECTION_REJECTED)
            }
            val reduction = ConnectionLifecycleReducer.reduce(
                lifecycle,
                ConnectionLifecycleEvent.DisconnectRequested(request.generation, request.sessionId),
            )
            if (reduction.disposition !is ConnectionLifecycleDisposition.Transitioned) {
                return accepted(request.requestId, request.generation)
            }
            lifecycle = reduction.snapshot
            attempt = candidate
            diagnostics += diagnostic(attempt, BleConnectionDiagnosticEvent.DISCONNECT_REQUESTED)
            next = lifecycle
        }
        lifecycleObserver.onChanged(next)
        cleanup(attempt, terminal = false)
        return accepted(request.requestId, request.generation)
    }

    fun lifecycleSnapshot(): ConnectionLifecycleSnapshot = synchronized(lock) { lifecycle }

    fun diagnosticsSnapshot(): List<BleConnectionDiagnostic> = synchronized(lock) { diagnostics.toList() }

    private fun onDeadlineElapsed(attempt: ActiveAttempt) {
        val next: ConnectionLifecycleSnapshot
        synchronized(lock) {
            if (active !== attempt || lifecycle.state !is ConnectionLifecycleState.Connecting) return
            val reduction = ConnectionLifecycleReducer.reduce(
                lifecycle,
                ConnectionLifecycleEvent.TimedOut(attempt.request.generation, attempt.request.requestId),
            )
            if (reduction.disposition !is ConnectionLifecycleDisposition.Transitioned) return
            lifecycle = reduction.snapshot
            diagnostics += diagnostic(attempt, BleConnectionDiagnosticEvent.TIMEOUT)
            next = lifecycle
        }
        lifecycleObserver.onChanged(next)
        cleanup(attempt, terminal = true)
    }

    private fun onTransportEvent(attempt: ActiveAttempt, event: BleTransportEvent) {
        if (event.generation != attempt.request.generation.value) {
            recordIgnored(attempt)
            return
        }
        when (event) {
            is BleTransportEvent.Connecting -> record(attempt, BleConnectionDiagnosticEvent.TRANSPORT_CONNECTING)
            is BleTransportEvent.Connected -> channelEstablished(attempt)
            is BleTransportEvent.Ready -> transportReady(attempt)
            is BleTransportEvent.Disconnected -> transportDisconnected(attempt)
            is BleTransportEvent.Failure -> transportFailed(attempt, event.failure)
        }
    }

    private fun channelEstablished(attempt: ActiveAttempt) {
        val next: ConnectionLifecycleSnapshot
        val deadline: BleConnectionDeadline?
        synchronized(lock) {
            if (active !== attempt || lifecycle.state !is ConnectionLifecycleState.Connecting) {
                diagnostics += diagnostic(attempt, BleConnectionDiagnosticEvent.CALLBACK_IGNORED)
                return
            }
            val request = attempt.request
            val reduction = ConnectionLifecycleReducer.reduce(
                lifecycle,
                ConnectionLifecycleEvent.ChannelEstablished(
                    generation = request.generation,
                    requestId = request.requestId,
                    sessionId = attempt.sessionId,
                    metadata = ConnectionMetadata(request.method, request.endpoint, request.peerAlias, clock.now()),
                ),
            )
            if (reduction.disposition !is ConnectionLifecycleDisposition.Transitioned) return
            lifecycle = reduction.snapshot
            lastSessionId = attempt.sessionId
            diagnostics += diagnostic(attempt, BleConnectionDiagnosticEvent.CHANNEL_ESTABLISHED)
            deadline = attempt.deadline
            attempt.deadline = null
            next = lifecycle
        }
        deadline?.cancel()
        lifecycleObserver.onChanged(next)
    }

    private fun transportReady(attempt: ActiveAttempt) {
        synchronized(lock) {
            if (active !== attempt || lifecycle.state !is ConnectionLifecycleState.Connected) {
                diagnostics += diagnostic(attempt, BleConnectionDiagnosticEvent.CALLBACK_IGNORED)
                return
            }
            diagnostics += diagnostic(attempt, BleConnectionDiagnosticEvent.TRANSPORT_READY)
        }
    }

    private fun transportDisconnected(attempt: ActiveAttempt) {
        val next: ConnectionLifecycleSnapshot
        val deadline: BleConnectionDeadline?
        synchronized(lock) {
            if (active !== attempt) {
                diagnostics += diagnostic(attempt, BleConnectionDiagnosticEvent.CALLBACK_IGNORED)
                return
            }
            val sessionId = when (lifecycle.state) {
                is ConnectionLifecycleState.Connected,
                is ConnectionLifecycleState.Disconnecting,
                -> attempt.sessionId
                else -> null
            }
            val reduction = ConnectionLifecycleReducer.reduce(
                lifecycle,
                ConnectionLifecycleEvent.Disconnected(attempt.request.generation, sessionId),
            )
            if (reduction.disposition !is ConnectionLifecycleDisposition.Transitioned) {
                diagnostics += diagnostic(attempt, BleConnectionDiagnosticEvent.CALLBACK_IGNORED)
                return
            }
            lifecycle = reduction.snapshot
            diagnostics += diagnostic(attempt, BleConnectionDiagnosticEvent.TRANSPORT_DISCONNECTED)
            deadline = attempt.deadline
            attempt.deadline = null
            active = null
            next = lifecycle
        }
        deadline?.cancel()
        lifecycleObserver.onChanged(next)
    }

    private fun transportFailed(attempt: ActiveAttempt, transportFailure: BleTransportFailure) {
        val next: ConnectionLifecycleSnapshot
        synchronized(lock) {
            if (active !== attempt) {
                diagnostics += diagnostic(attempt, BleConnectionDiagnosticEvent.CALLBACK_IGNORED)
                return
            }
            val reduction = ConnectionLifecycleReducer.reduce(
                lifecycle,
                ConnectionLifecycleEvent.Failed(
                    attempt.request.generation,
                    attempt.request.requestId,
                    ConnectionFailure(transportFailure.toH013Failure()),
                ),
            )
            if (reduction.disposition !is ConnectionLifecycleDisposition.Transitioned) {
                diagnostics += diagnostic(attempt, BleConnectionDiagnosticEvent.CALLBACK_IGNORED)
                return
            }
            lifecycle = reduction.snapshot
            diagnostics += diagnostic(
                attempt,
                BleConnectionDiagnosticEvent.TRANSPORT_FAILURE,
                transportFailure,
            )
            next = lifecycle
        }
        lifecycleObserver.onChanged(next)
        cleanup(attempt, terminal = true)
    }

    private fun cleanup(attempt: ActiveAttempt, terminal: Boolean) {
        val deadline: BleConnectionDeadline?
        val shouldDisconnect: Boolean
        synchronized(lock) {
            if (attempt.cleanupStarted) return
            attempt.cleanupStarted = true
            deadline = attempt.deadline
            attempt.deadline = null
            shouldDisconnect = true
            if (terminal && active === attempt) active = null
        }
        deadline?.cancel()
        if (shouldDisconnect) transport.disconnect(attempt.request.generation.value)
    }

    private fun record(attempt: ActiveAttempt, event: BleConnectionDiagnosticEvent) {
        synchronized(lock) {
            if (active === attempt) diagnostics += diagnostic(attempt, event)
            else diagnostics += diagnostic(attempt, BleConnectionDiagnosticEvent.CALLBACK_IGNORED)
        }
    }

    private fun recordIgnored(attempt: ActiveAttempt) = synchronized(lock) {
        diagnostics += diagnostic(attempt, BleConnectionDiagnosticEvent.CALLBACK_IGNORED)
    }

    private fun diagnostic(
        attempt: ActiveAttempt,
        event: BleConnectionDiagnosticEvent,
        failure: BleTransportFailure? = null,
    ) = BleConnectionDiagnostic(
        requestId = attempt.request.requestId,
        generation = attempt.request.generation,
        event = event,
        failureKind = failure?.kind,
        operation = failure?.operation,
        platformStatus = failure?.platformStatus,
    )

    private data class ActiveAttempt(
        val request: ConnectionConnectRequest,
        val target: BleTransportTarget,
        val sessionId: ConnectionSessionId,
        var deadline: BleConnectionDeadline? = null,
        var cleanupStarted: Boolean = false,
    ) {
        fun matches(requestId: ConnectionRequestId, generation: ConnectionGeneration): Boolean =
            request.requestId == requestId && request.generation == generation
    }
}

object DeterministicBleConnectionSessionIds : BleConnectionSessionIdFactory {
    override fun create(requestId: ConnectionRequestId, generation: ConnectionGeneration): ConnectionSessionId {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("hugo-ble-session:${requestId.value}:${generation.value}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
            .toCharArray()
        digest[12] = '4'
        digest[16] = "89ab"[(digest[16].digitToInt(16) and 0x3)]
        val value = buildString(36) {
            digest.forEachIndexed { index, character ->
                if (index in setOf(8, 12, 16, 20)) append('-')
                append(character)
            }
        }
        return connectionValue(ConnectionSessionId.parse(value))
    }
}

private fun BleTransportAvailability.toH013Availability(): Pair<ConnectionAvailability, ConnectionAvailabilityReason?> =
    when (this) {
        BleTransportAvailability.AVAILABLE -> ConnectionAvailability.AVAILABLE to null
        BleTransportAvailability.PERMISSION_UNAVAILABLE ->
            ConnectionAvailability.UNAVAILABLE to ConnectionAvailabilityReason.PERMISSION_UNAVAILABLE
        BleTransportAvailability.BLUETOOTH_DISABLED,
        BleTransportAvailability.TRANSPORT_UNAVAILABLE,
        -> ConnectionAvailability.UNAVAILABLE to ConnectionAvailabilityReason.TEMPORARILY_UNAVAILABLE
    }

private fun BleTransportAvailability.toConnectFailure(): ConnectionFailureCode? = when (this) {
    BleTransportAvailability.AVAILABLE -> null
    BleTransportAvailability.PERMISSION_UNAVAILABLE -> ConnectionFailureCode.PERMISSION_UNAVAILABLE
    BleTransportAvailability.BLUETOOTH_DISABLED,
    BleTransportAvailability.TRANSPORT_UNAVAILABLE,
    -> ConnectionFailureCode.UNAVAILABLE
}

private fun BleTransportFailure.toH013Failure(): ConnectionFailureCode = when (kind) {
    BleTransportFailureKind.TIMEOUT -> ConnectionFailureCode.TIMEOUT
    BleTransportFailureKind.PERMISSION_UNAVAILABLE -> ConnectionFailureCode.PERMISSION_UNAVAILABLE
    BleTransportFailureKind.BLUETOOTH_DISABLED,
    BleTransportFailureKind.ENDPOINT_UNAVAILABLE,
    BleTransportFailureKind.UNSUPPORTED,
    -> ConnectionFailureCode.UNAVAILABLE
    BleTransportFailureKind.LINK_LOST -> ConnectionFailureCode.SESSION_LOST
    BleTransportFailureKind.CANCELLED -> ConnectionFailureCode.CANCELLED
    BleTransportFailureKind.TRANSPORT_FAILURE -> ConnectionFailureCode.TRANSPORT_FAILURE
    BleTransportFailureKind.UNKNOWN -> ConnectionFailureCode.UNKNOWN
}

private fun accepted(requestId: ConnectionRequestId, generation: ConnectionGeneration): ConnectionCommandResult =
    connectionValue(ConnectionCommandResult.create(requestId, generation, ConnectionCommandState.ACCEPTED, null))

private fun rejected(
    requestId: ConnectionRequestId,
    generation: ConnectionGeneration,
    code: ConnectionFailureCode,
): ConnectionCommandResult = connectionValue(
    ConnectionCommandResult.create(
        requestId,
        generation,
        ConnectionCommandState.REJECTED,
        ConnectionFailure(code),
    ),
)

private fun <T> connectionValue(result: ConnectionResult<T>): T = when (result) {
    is ConnectionResult.Success -> result.value
    is ConnectionResult.Failure -> error("Invalid internal H013 constant: ${result.error::class.simpleName}")
}
