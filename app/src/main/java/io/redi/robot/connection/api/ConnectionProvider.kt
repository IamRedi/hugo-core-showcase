package io.redi.robot.connection.api

import io.redi.robot.capability.api.MonotonicTimestampMs

/** A pure channel boundary. Channel success never proves trust, authority, or capability readiness. */
interface ConnectionProvider {
    val method: ConnectionMethodReference

    fun availabilitySnapshot(): ConnectionAvailabilitySnapshot

    fun connect(request: ConnectionConnectRequest): ConnectionCommandResult

    fun cancel(request: ConnectionCancellationRequest): ConnectionCommandResult

    fun disconnect(request: ConnectionDisconnectRequest): ConnectionCommandResult
}

data class ConnectionMethodId private constructor(val value: String) {
    companion object {
        private val pattern = Regex(
            "^[a-z][a-z0-9]*(?:[.][a-z][a-z0-9]*){1,7}/[a-z][a-z0-9]*(?:[-.][a-z0-9]+)*$",
        )

        fun parse(raw: String): ConnectionResult<ConnectionMethodId> = if (
            raw.length in 3..160 && pattern.matches(raw)
        ) ConnectionResult.Success(ConnectionMethodId(raw))
        else failure(ConnectionContractError.MalformedMethodId)
    }
}

data class ConnectionMethodVersion private constructor(val major: Int, val minor: Int) {
    val value: String = "$major.$minor"

    companion object {
        fun parse(raw: String): ConnectionResult<ConnectionMethodVersion> {
            val parts = raw.split('.')
            val major = parts.getOrNull(0)?.toIntOrNull()
            val minor = parts.getOrNull(1)?.toIntOrNull()
            return if (parts.size != 2 || parts.any { it.isEmpty() || it.length > 1 && it.startsWith('0') } ||
                major == null || minor == null || major <= 0 || minor < 0
            ) failure(ConnectionContractError.MalformedMethodVersion)
            else ConnectionResult.Success(ConnectionMethodVersion(major, minor))
        }
    }
}

data class ConnectionMethodReference(val id: ConnectionMethodId, val version: ConnectionMethodVersion)

data class ConnectionEndpointReference private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._/-][a-z0-9]+)*$")

        /** Opaque local reference; never a raw address, URL, credential, or vendor payload. */
        fun parse(raw: String): ConnectionResult<ConnectionEndpointReference> = if (
            raw.length in 3..160 && pattern.matches(raw)
        ) ConnectionResult.Success(ConnectionEndpointReference(raw))
        else failure(ConnectionContractError.MalformedEndpointReference)
    }
}

data class ConnectionPeerAlias private constructor(val value: String) {
    companion object {
        fun parse(raw: String): ConnectionResult<ConnectionPeerAlias> = if (
            raw.trim().length in 1..80 && raw.none { it.isISOControl() }
        ) ConnectionResult.Success(ConnectionPeerAlias(raw.trim()))
        else failure(ConnectionContractError.MalformedPeerAlias)
    }
}

@JvmInline
value class ConnectionGeneration private constructor(val value: Long) {
    companion object {
        fun create(value: Long): ConnectionResult<ConnectionGeneration> = if (value < 0L) {
            failure(ConnectionContractError.InvalidGeneration(value))
        } else ConnectionResult.Success(ConnectionGeneration(value))
    }
}

@JvmInline
value class ConnectionRequestId private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        fun parse(raw: String): ConnectionResult<ConnectionRequestId> = if (pattern.matches(raw)) {
            ConnectionResult.Success(ConnectionRequestId(raw))
        } else failure(ConnectionContractError.MalformedRequestId)
    }
}

@JvmInline
value class ConnectionSessionId private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        fun parse(raw: String): ConnectionResult<ConnectionSessionId> = if (pattern.matches(raw)) {
            ConnectionResult.Success(ConnectionSessionId(raw))
        } else failure(ConnectionContractError.MalformedSessionId)
    }
}

class ConnectionConnectRequest private constructor(
    val requestId: ConnectionRequestId,
    val generation: ConnectionGeneration,
    val method: ConnectionMethodReference,
    val endpoint: ConnectionEndpointReference,
    val peerAlias: ConnectionPeerAlias?,
    val requestedAt: MonotonicTimestampMs,
    val deadline: MonotonicTimestampMs,
) {
    companion object {
        fun create(
            requestId: ConnectionRequestId,
            generation: ConnectionGeneration,
            method: ConnectionMethodReference,
            endpoint: ConnectionEndpointReference,
            peerAlias: ConnectionPeerAlias?,
            requestedAt: MonotonicTimestampMs,
            deadline: MonotonicTimestampMs,
        ): ConnectionResult<ConnectionConnectRequest> = if (deadline.value <= requestedAt.value) {
            failure(ConnectionContractError.InvalidDeadline)
        } else ConnectionResult.Success(
            ConnectionConnectRequest(requestId, generation, method, endpoint, peerAlias, requestedAt, deadline),
        )
    }
}

data class ConnectionCancellationRequest(
    val requestId: ConnectionRequestId,
    val generation: ConnectionGeneration,
    val requestedAt: MonotonicTimestampMs,
)

data class ConnectionDisconnectRequest(
    val requestId: ConnectionRequestId,
    val generation: ConnectionGeneration,
    val sessionId: ConnectionSessionId,
    val requestedAt: MonotonicTimestampMs,
)

enum class ConnectionAvailability { AVAILABLE, UNAVAILABLE }

enum class ConnectionAvailabilityReason {
    METHOD_UNAVAILABLE,
    PERMISSION_UNAVAILABLE,
    TEMPORARILY_UNAVAILABLE,
    POLICY_UNAVAILABLE,
    UNKNOWN,
}

class ConnectionAvailabilitySnapshot private constructor(
    val method: ConnectionMethodReference,
    val availability: ConnectionAvailability,
    val observedAt: MonotonicTimestampMs,
    val reason: ConnectionAvailabilityReason?,
) {
    companion object {
        fun create(
            method: ConnectionMethodReference,
            availability: ConnectionAvailability,
            observedAt: MonotonicTimestampMs,
            reason: ConnectionAvailabilityReason?,
        ): ConnectionResult<ConnectionAvailabilitySnapshot> = if (
            (availability == ConnectionAvailability.AVAILABLE) != (reason == null)
        ) failure(ConnectionContractError.AvailabilityReasonConflict(availability, reason))
        else ConnectionResult.Success(ConnectionAvailabilitySnapshot(method, availability, observedAt, reason))
    }
}

enum class ConnectionReadiness { NOT_CONNECTED, CHANNEL_ESTABLISHED }

data class ConnectionMetadata(
    val method: ConnectionMethodReference,
    val endpoint: ConnectionEndpointReference,
    val peerAlias: ConnectionPeerAlias?,
    val observedAt: MonotonicTimestampMs,
)

data class ConnectionSession(
    val id: ConnectionSessionId,
    val generation: ConnectionGeneration,
    val connectRequestId: ConnectionRequestId,
    val metadata: ConnectionMetadata,
) {
    val readiness: ConnectionReadiness = ConnectionReadiness.CHANNEL_ESTABLISHED
}

enum class ConnectionFailureCode {
    UNSUPPORTED_METHOD,
    UNAVAILABLE,
    PERMISSION_UNAVAILABLE,
    CONNECTION_REJECTED,
    TIMEOUT,
    CANCELLED,
    TRANSPORT_FAILURE,
    SESSION_LOST,
    UNKNOWN,
}

data class ConnectionFailure(val code: ConnectionFailureCode)

enum class ConnectionCommandState { ACCEPTED, REJECTED }

class ConnectionCommandResult private constructor(
    val requestId: ConnectionRequestId,
    val generation: ConnectionGeneration,
    val state: ConnectionCommandState,
    val failure: ConnectionFailure?,
) {
    companion object {
        fun create(
            requestId: ConnectionRequestId,
            generation: ConnectionGeneration,
            state: ConnectionCommandState,
            failure: ConnectionFailure?,
        ): ConnectionResult<ConnectionCommandResult> = if (
            (state == ConnectionCommandState.ACCEPTED) != (failure == null)
        ) failure(ConnectionContractError.CommandFailureConflict(state, failure))
        else ConnectionResult.Success(ConnectionCommandResult(requestId, generation, state, failure))
    }
}

sealed interface ConnectionLifecycleState {
    data object Idle : ConnectionLifecycleState
    data class Connecting(val request: ConnectionConnectRequest) : ConnectionLifecycleState
    data class Connected(val session: ConnectionSession) : ConnectionLifecycleState
    data class Disconnecting(val session: ConnectionSession, val cancellation: Boolean) : ConnectionLifecycleState
    data class Disconnected(val generation: ConnectionGeneration) : ConnectionLifecycleState
    data class Cancelled(val generation: ConnectionGeneration) : ConnectionLifecycleState
    data class Failed(val generation: ConnectionGeneration, val failure: ConnectionFailure) : ConnectionLifecycleState
}

data class ConnectionLifecycleSnapshot(
    val state: ConnectionLifecycleState,
    val latestGeneration: ConnectionGeneration?,
) {
    companion object {
        fun initial() = ConnectionLifecycleSnapshot(ConnectionLifecycleState.Idle, null)
    }
}

sealed interface ConnectionLifecycleEvent {
    data class ConnectRequested(val request: ConnectionConnectRequest) : ConnectionLifecycleEvent
    data class ChannelEstablished(
        val generation: ConnectionGeneration,
        val requestId: ConnectionRequestId,
        val sessionId: ConnectionSessionId,
        val metadata: ConnectionMetadata,
    ) : ConnectionLifecycleEvent
    data class CancellationRequested(val generation: ConnectionGeneration, val requestId: ConnectionRequestId) : ConnectionLifecycleEvent
    data class DisconnectRequested(val generation: ConnectionGeneration, val sessionId: ConnectionSessionId) : ConnectionLifecycleEvent
    data class Disconnected(val generation: ConnectionGeneration, val sessionId: ConnectionSessionId?) : ConnectionLifecycleEvent
    data class TimedOut(val generation: ConnectionGeneration, val requestId: ConnectionRequestId) : ConnectionLifecycleEvent
    data class Failed(
        val generation: ConnectionGeneration,
        val requestId: ConnectionRequestId,
        val failure: ConnectionFailure,
    ) : ConnectionLifecycleEvent
}

sealed interface ConnectionLifecycleDisposition {
    data object Transitioned : ConnectionLifecycleDisposition
    data class Ignored(val reason: ConnectionLifecycleIgnoredReason) : ConnectionLifecycleDisposition
}

enum class ConnectionLifecycleIgnoredReason {
    NO_ACTIVE_GENERATION,
    STALE_GENERATION,
    FUTURE_GENERATION,
    REUSED_GENERATION,
    INVALID_TRANSITION,
    CORRELATION_MISMATCH,
    DUPLICATE_EVENT,
}

data class ConnectionLifecycleReduction(
    val snapshot: ConnectionLifecycleSnapshot,
    val disposition: ConnectionLifecycleDisposition,
)

/** Pure lifecycle reducer. Effects, scheduling, and callback threading remain outside this contract. */
object ConnectionLifecycleReducer {
    fun reduce(snapshot: ConnectionLifecycleSnapshot, event: ConnectionLifecycleEvent): ConnectionLifecycleReduction {
        if (event is ConnectionLifecycleEvent.ConnectRequested) return connectRequested(snapshot, event.request)
        val generation = event.generation()
        val latest = snapshot.latestGeneration ?: return ignored(snapshot, ConnectionLifecycleIgnoredReason.NO_ACTIVE_GENERATION)
        if (generation.value < latest.value) return ignored(snapshot, ConnectionLifecycleIgnoredReason.STALE_GENERATION)
        if (generation.value > latest.value) return ignored(snapshot, ConnectionLifecycleIgnoredReason.FUTURE_GENERATION)
        return when (event) {
            is ConnectionLifecycleEvent.ChannelEstablished -> established(snapshot, event)
            is ConnectionLifecycleEvent.CancellationRequested -> cancel(snapshot, event)
            is ConnectionLifecycleEvent.DisconnectRequested -> disconnectRequested(snapshot, event)
            is ConnectionLifecycleEvent.Disconnected -> disconnected(snapshot, event)
            is ConnectionLifecycleEvent.TimedOut -> timeout(snapshot, event)
            is ConnectionLifecycleEvent.Failed -> failed(snapshot, event)
            is ConnectionLifecycleEvent.ConnectRequested -> error("Handled above")
        }
    }

    private fun connectRequested(
        snapshot: ConnectionLifecycleSnapshot,
        request: ConnectionConnectRequest,
    ): ConnectionLifecycleReduction {
        snapshot.latestGeneration?.let {
            if (request.generation.value < it.value) return ignored(snapshot, ConnectionLifecycleIgnoredReason.STALE_GENERATION)
            if (request.generation.value == it.value) return ignored(snapshot, ConnectionLifecycleIgnoredReason.REUSED_GENERATION)
        }
        return when (snapshot.state) {
            ConnectionLifecycleState.Idle,
            is ConnectionLifecycleState.Disconnected,
            is ConnectionLifecycleState.Cancelled,
            is ConnectionLifecycleState.Failed,
            -> transition(snapshot, ConnectionLifecycleState.Connecting(request), request.generation)
            else -> ignored(snapshot, ConnectionLifecycleIgnoredReason.INVALID_TRANSITION)
        }
    }

    private fun established(
        snapshot: ConnectionLifecycleSnapshot,
        event: ConnectionLifecycleEvent.ChannelEstablished,
    ): ConnectionLifecycleReduction {
        val state = snapshot.state as? ConnectionLifecycleState.Connecting ?: return terminalOrInvalid(snapshot)
        if (state.request.requestId != event.requestId || state.request.method != event.metadata.method ||
            state.request.endpoint != event.metadata.endpoint
        ) return ignored(snapshot, ConnectionLifecycleIgnoredReason.CORRELATION_MISMATCH)
        return transition(
            snapshot,
            ConnectionLifecycleState.Connected(
                ConnectionSession(event.sessionId, event.generation, event.requestId, event.metadata),
            ),
            event.generation,
        )
    }

    private fun cancel(
        snapshot: ConnectionLifecycleSnapshot,
        event: ConnectionLifecycleEvent.CancellationRequested,
    ): ConnectionLifecycleReduction = when (val state = snapshot.state) {
        is ConnectionLifecycleState.Connecting -> if (state.request.requestId == event.requestId) {
            transition(snapshot, ConnectionLifecycleState.Cancelled(event.generation), event.generation)
        } else ignored(snapshot, ConnectionLifecycleIgnoredReason.CORRELATION_MISMATCH)
        is ConnectionLifecycleState.Connected -> if (state.session.connectRequestId == event.requestId) {
            transition(snapshot, ConnectionLifecycleState.Disconnecting(state.session, true), event.generation)
        } else ignored(snapshot, ConnectionLifecycleIgnoredReason.CORRELATION_MISMATCH)
        is ConnectionLifecycleState.Disconnecting -> ignored(snapshot, ConnectionLifecycleIgnoredReason.DUPLICATE_EVENT)
        else -> terminalOrInvalid(snapshot)
    }

    private fun disconnectRequested(
        snapshot: ConnectionLifecycleSnapshot,
        event: ConnectionLifecycleEvent.DisconnectRequested,
    ): ConnectionLifecycleReduction = when (val state = snapshot.state) {
        is ConnectionLifecycleState.Connected -> if (state.session.id == event.sessionId) {
            transition(snapshot, ConnectionLifecycleState.Disconnecting(state.session, false), event.generation)
        } else ignored(snapshot, ConnectionLifecycleIgnoredReason.CORRELATION_MISMATCH)
        is ConnectionLifecycleState.Disconnecting,
        is ConnectionLifecycleState.Disconnected,
        -> ignored(snapshot, ConnectionLifecycleIgnoredReason.DUPLICATE_EVENT)
        else -> terminalOrInvalid(snapshot)
    }

    private fun disconnected(
        snapshot: ConnectionLifecycleSnapshot,
        event: ConnectionLifecycleEvent.Disconnected,
    ): ConnectionLifecycleReduction = when (val state = snapshot.state) {
        is ConnectionLifecycleState.Connecting -> transition(snapshot, ConnectionLifecycleState.Disconnected(event.generation), event.generation)
        is ConnectionLifecycleState.Connected -> if (event.sessionId == null || event.sessionId == state.session.id) {
            transition(snapshot, ConnectionLifecycleState.Disconnected(event.generation), event.generation)
        } else ignored(snapshot, ConnectionLifecycleIgnoredReason.CORRELATION_MISMATCH)
        is ConnectionLifecycleState.Disconnecting -> if (event.sessionId == null || event.sessionId == state.session.id) {
            val next = if (state.cancellation) ConnectionLifecycleState.Cancelled(event.generation)
            else ConnectionLifecycleState.Disconnected(event.generation)
            transition(snapshot, next, event.generation)
        } else ignored(snapshot, ConnectionLifecycleIgnoredReason.CORRELATION_MISMATCH)
        is ConnectionLifecycleState.Disconnected -> ignored(snapshot, ConnectionLifecycleIgnoredReason.DUPLICATE_EVENT)
        else -> terminalOrInvalid(snapshot)
    }

    private fun timeout(snapshot: ConnectionLifecycleSnapshot, event: ConnectionLifecycleEvent.TimedOut): ConnectionLifecycleReduction {
        val state = snapshot.state as? ConnectionLifecycleState.Connecting ?: return terminalOrInvalid(snapshot)
        return if (state.request.requestId == event.requestId) transition(
            snapshot,
            ConnectionLifecycleState.Failed(event.generation, ConnectionFailure(ConnectionFailureCode.TIMEOUT)),
            event.generation,
        ) else ignored(snapshot, ConnectionLifecycleIgnoredReason.CORRELATION_MISMATCH)
    }

    private fun failed(snapshot: ConnectionLifecycleSnapshot, event: ConnectionLifecycleEvent.Failed): ConnectionLifecycleReduction = when (val state = snapshot.state) {
        is ConnectionLifecycleState.Connecting -> if (state.request.requestId == event.requestId) {
            transition(snapshot, ConnectionLifecycleState.Failed(event.generation, event.failure), event.generation)
        } else ignored(snapshot, ConnectionLifecycleIgnoredReason.CORRELATION_MISMATCH)
        is ConnectionLifecycleState.Connected -> if (state.session.connectRequestId == event.requestId) {
            transition(snapshot, ConnectionLifecycleState.Failed(event.generation, event.failure), event.generation)
        } else ignored(snapshot, ConnectionLifecycleIgnoredReason.CORRELATION_MISMATCH)
        else -> terminalOrInvalid(snapshot)
    }

    private fun ConnectionLifecycleEvent.generation(): ConnectionGeneration = when (this) {
        is ConnectionLifecycleEvent.ConnectRequested -> request.generation
        is ConnectionLifecycleEvent.ChannelEstablished -> generation
        is ConnectionLifecycleEvent.CancellationRequested -> generation
        is ConnectionLifecycleEvent.DisconnectRequested -> generation
        is ConnectionLifecycleEvent.Disconnected -> generation
        is ConnectionLifecycleEvent.TimedOut -> generation
        is ConnectionLifecycleEvent.Failed -> generation
    }

    private fun terminalOrInvalid(snapshot: ConnectionLifecycleSnapshot): ConnectionLifecycleReduction = when (snapshot.state) {
        is ConnectionLifecycleState.Cancelled,
        is ConnectionLifecycleState.Disconnected,
        is ConnectionLifecycleState.Failed,
        -> ignored(snapshot, ConnectionLifecycleIgnoredReason.DUPLICATE_EVENT)
        else -> ignored(snapshot, ConnectionLifecycleIgnoredReason.INVALID_TRANSITION)
    }

    private fun transition(
        snapshot: ConnectionLifecycleSnapshot,
        state: ConnectionLifecycleState,
        generation: ConnectionGeneration,
    ) = ConnectionLifecycleReduction(
        snapshot.copy(state = state, latestGeneration = generation),
        ConnectionLifecycleDisposition.Transitioned,
    )

    private fun ignored(snapshot: ConnectionLifecycleSnapshot, reason: ConnectionLifecycleIgnoredReason) =
        ConnectionLifecycleReduction(snapshot, ConnectionLifecycleDisposition.Ignored(reason))
}

sealed interface ConnectionResult<out T> {
    data class Success<T>(val value: T) : ConnectionResult<T>
    data class Failure(val error: ConnectionContractError) : ConnectionResult<Nothing>
}

sealed interface ConnectionContractError {
    data object MalformedMethodId : ConnectionContractError
    data object MalformedMethodVersion : ConnectionContractError
    data object MalformedEndpointReference : ConnectionContractError
    data object MalformedPeerAlias : ConnectionContractError
    data class InvalidGeneration(val value: Long) : ConnectionContractError
    data object MalformedRequestId : ConnectionContractError
    data object MalformedSessionId : ConnectionContractError
    data object InvalidDeadline : ConnectionContractError
    data class AvailabilityReasonConflict(val availability: ConnectionAvailability, val reason: ConnectionAvailabilityReason?) : ConnectionContractError
    data class CommandFailureConflict(val state: ConnectionCommandState, val failure: ConnectionFailure?) : ConnectionContractError
}

private fun <T> failure(error: ConnectionContractError): ConnectionResult<T> = ConnectionResult.Failure(error)
