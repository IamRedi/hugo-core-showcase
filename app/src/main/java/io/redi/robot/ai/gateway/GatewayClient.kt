package io.redi.robot.ai.gateway

import io.redi.robot.gateway.auth.GatewayAuthEpochSeconds
import io.redi.robot.gateway.auth.GatewayAuthResult
import io.redi.robot.gateway.auth.GatewayAuthScope
import io.redi.robot.gateway.auth.GatewayEndpointOrigin
import io.redi.robot.gateway.auth.GatewayTokenRepository
import io.redi.robot.gateway.contract.GatewayCancellation
import io.redi.robot.gateway.contract.GatewayCancellationId
import io.redi.robot.gateway.contract.GatewayCancellationReason
import io.redi.robot.gateway.contract.GatewayContractCodec
import io.redi.robot.gateway.contract.GatewayContractResult
import io.redi.robot.gateway.contract.GatewayEventPayload
import io.redi.robot.gateway.contract.GatewayOperation
import io.redi.robot.gateway.contract.GatewayRequest
import io.redi.robot.gateway.contract.GatewaySchemaVersion
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID

@JvmInline
value class GatewayClientMonotonicMs private constructor(val value: Long) : Comparable<GatewayClientMonotonicMs> {
    override fun compareTo(other: GatewayClientMonotonicMs): Int = value.compareTo(other.value)

    companion object {
        fun create(value: Long): GatewayClientModelResult<GatewayClientMonotonicMs> = if (value >= 0) {
            GatewayClientModelResult.Success(GatewayClientMonotonicMs(value))
        } else {
            GatewayClientModelResult.Failure(GatewayClientModelError.INVALID_MONOTONIC_TIME)
        }
    }
}

enum class GatewayTransportOpenFailure {
    UNAUTHENTICATED,
    RATE_LIMITED,
    UNAVAILABLE,
    INVALID_REQUEST,
    PROTOCOL_UNSUPPORTED,
}

sealed interface GatewayTransportOpenResult {
    data class Accepted(val channel: GatewayHttpStreamChannel) : GatewayTransportOpenResult
    data class Rejected(val reason: GatewayTransportOpenFailure) : GatewayTransportOpenResult
}

enum class GatewayTransportReadFailure { UNAVAILABLE, CONNECTION_LOST, IO_FAILURE }

sealed interface GatewayStreamReadResult {
    class Frame private constructor(internal val bytes: ByteArray) : GatewayStreamReadResult {
        override fun toString(): String = "GatewayStreamFrame(bytes=[redacted])"

        companion object {
            const val MAXIMUM_FRAME_BYTES = 64 * 1024

            fun copyOf(bytes: ByteArray): GatewayClientModelResult<Frame> = if (
                bytes.isNotEmpty() && bytes.size <= MAXIMUM_FRAME_BYTES
            ) GatewayClientModelResult.Success(Frame(bytes.copyOf())) else
                GatewayClientModelResult.Failure(GatewayClientModelError.INVALID_FRAME_SIZE)
        }
    }

    data object WouldBlock : GatewayStreamReadResult
    data object EndOfStream : GatewayStreamReadResult
    data class Failure(val reason: GatewayTransportReadFailure) : GatewayStreamReadResult
}

enum class GatewayTransportCancellationDelivery { DELIVERED, ALREADY_CLOSED, FAILED }

/** One bounded stream. Frame byte ownership transfers to the caller of readNext. */
interface GatewayHttpStreamChannel {
    fun readNext(): GatewayStreamReadResult
    fun cancel(cancellationDocument: ByteArray): GatewayTransportCancellationDelivery
    fun close()
}

/**
 * Provider-neutral HTTP/stream seam. Implementations consume token/request bytes synchronously and
 * must not retain those arrays. H053 supplies no production network implementation or endpoint.
 */
interface GatewayHttpStreamPort {
    fun open(
        endpoint: GatewayEndpointOrigin,
        bearerToken: ByteArray,
        requestDocument: ByteArray,
    ): GatewayTransportOpenResult
}

enum class GatewayClientStartFailure {
    BUSY,
    INVALID_DEADLINE,
    TOKEN_ABSENT,
    TOKEN_EXPIRED,
    TOKEN_REVOKED,
    ENDPOINT_DENIED,
    AUDIENCE_MISMATCH,
    INSUFFICIENT_SCOPE,
    AUTH_STORAGE_FAILURE,
    AUTH_INVALID,
    TRANSPORT_UNAUTHENTICATED,
    TRANSPORT_RATE_LIMITED,
    TRANSPORT_UNAVAILABLE,
    TRANSPORT_INVALID_REQUEST,
    PROTOCOL_UNSUPPORTED,
}

sealed interface GatewayClientStartResult {
    data class Started(val snapshot: GatewayClientSnapshot) : GatewayClientStartResult
    data class Rejected(val reason: GatewayClientStartFailure) : GatewayClientStartResult
}

enum class GatewayClientTerminalStatus {
    COMPLETED,
    REFUSED,
    FAILED,
    GATEWAY_CANCELLED,
    GATEWAY_TIMED_OUT,
    CLIENT_CANCELLED,
    CLIENT_TIMED_OUT,
    TRANSPORT_FAILURE,
    PROTOCOL_ERROR,
}

data class GatewayClientTerminalReport(
    val status: GatewayClientTerminalStatus,
    val stream: GatewayStreamState,
    val gatewayTerminalConfirmed: Boolean,
    val cancellationDelivery: GatewayTransportCancellationDelivery?,
    val protocolError: GatewayStreamReductionError?,
    val transportFailure: GatewayTransportReadFailure?,
) {
    val grantsTrust: Boolean = false
    val grantsAuthorization: Boolean = false
    val approvesSafety: Boolean = false
    val executesSkill: Boolean = false
    val executesCapability: Boolean = false

    fun redactedTrace(): List<String> = stream.redactedTrace() +
        "CLIENT_TERMINAL:$status:gatewayConfirmed=$gatewayTerminalConfirmed"

    override fun toString(): String = "GatewayClientTerminalReport(status=$status, request=${stream.request.requestId}, " +
        "events=${stream.events.size}, gatewayConfirmed=$gatewayTerminalConfirmed, content=[redacted])"
}

data class GatewayClientSnapshot(
    val requestId: String,
    val operation: GatewayOperation,
    val eventCount: Int,
    val bufferedTextBytes: Int,
    val proposalCount: Int,
    val embeddingDimensions: Int,
    val terminalStatus: GatewayClientTerminalStatus?,
) {
    val grantsTrust: Boolean = false
    val grantsAuthorization: Boolean = false
    val approvesSafety: Boolean = false
    val executesSkill: Boolean = false
    val executesCapability: Boolean = false

    override fun toString(): String = "GatewayClientSnapshot(request=redacted, operation=$operation, " +
        "events=$eventCount, textBytes=$bufferedTextBytes, proposals=$proposalCount, " +
        "embeddingDimensions=$embeddingDimensions, terminal=$terminalStatus)"
}

sealed interface GatewayClientPollResult {
    data class Active(val snapshot: GatewayClientSnapshot) : GatewayClientPollResult
    data class Terminal(val report: GatewayClientTerminalReport) : GatewayClientPollResult
    data object Inactive : GatewayClientPollResult
}

sealed interface GatewayClientCancelResult {
    data class Cancelled(val report: GatewayClientTerminalReport) : GatewayClientCancelResult
    data class AlreadyTerminal(val report: GatewayClientTerminalReport) : GatewayClientCancelResult
    data object Inactive : GatewayClientCancelResult
}

enum class GatewayClientModelError { INVALID_MONOTONIC_TIME, INVALID_FRAME_SIZE }

sealed interface GatewayClientModelResult<out T> {
    data class Success<T>(val value: T) : GatewayClientModelResult<T>
    data class Failure(val error: GatewayClientModelError) : GatewayClientModelResult<Nothing>
}

class GatewayClient(
    private val tokens: GatewayTokenRepository,
    private val transport: GatewayHttpStreamPort,
    private val cancellationIdFactory: () -> GatewayCancellationId = ::newCancellationId,
) {
    private data class Active(
        val endpoint: GatewayEndpointOrigin,
        val channel: GatewayHttpStreamChannel,
        val deadline: GatewayClientMonotonicMs,
        var stream: GatewayStreamState,
        var closed: Boolean = false,
    )

    private var active: Active? = null
    private var terminal: GatewayClientTerminalReport? = null

    @Synchronized
    fun start(
        request: GatewayRequest,
        endpoint: GatewayEndpointOrigin,
        authNow: GatewayAuthEpochSeconds,
        monotonicNow: GatewayClientMonotonicMs,
    ): GatewayClientStartResult {
        if (active != null) return GatewayClientStartResult.Rejected(GatewayClientStartFailure.BUSY)
        if (monotonicNow.value > Long.MAX_VALUE - request.budget.timeoutMs) {
            return GatewayClientStartResult.Rejected(GatewayClientStartFailure.INVALID_DEADLINE)
        }
        val scope = request.operation.requiredScope()
        val authorizedOpen = tokens.withValidToken(endpoint, setOf(scope), authNow) { token ->
            val requestBytes = GatewayContractCodec.encodeRequest(request).toByteArray(StandardCharsets.UTF_8)
            try {
                runCatching { transport.open(endpoint, token, requestBytes) }
                    .getOrElse { GatewayTransportOpenResult.Rejected(GatewayTransportOpenFailure.UNAVAILABLE) }
            } finally {
                requestBytes.fill(0)
            }
        }
        val open = when (authorizedOpen) {
            is GatewayAuthResult.Success -> authorizedOpen.value
            else -> return GatewayClientStartResult.Rejected(authorizedOpen.toStartFailure())
        }
        val channel = when (open) {
            is GatewayTransportOpenResult.Accepted -> open.channel
            is GatewayTransportOpenResult.Rejected ->
                return GatewayClientStartResult.Rejected(open.reason.toStartFailure())
        }
        terminal = null
        active = Active(
            endpoint,
            channel,
            GatewayClientMonotonicMs.create(monotonicNow.value + request.budget.timeoutMs).success(),
            GatewayStreamState.initial(request),
        )
        return GatewayClientStartResult.Started(snapshot(active!!, null))
    }

    @Synchronized
    fun poll(now: GatewayClientMonotonicMs): GatewayClientPollResult {
        val current = active ?: return terminal?.let(GatewayClientPollResult::Terminal)
            ?: GatewayClientPollResult.Inactive
        if (now >= current.deadline) {
            return GatewayClientPollResult.Terminal(
                terminateLocally(current, GatewayClientTerminalStatus.CLIENT_TIMED_OUT, GatewayCancellationReason.DEADLINE),
            )
        }
        return when (val read = runCatching { current.channel.readNext() }.getOrElse {
            GatewayStreamReadResult.Failure(GatewayTransportReadFailure.IO_FAILURE)
        }) {
            GatewayStreamReadResult.WouldBlock -> GatewayClientPollResult.Active(snapshot(current, null))
            GatewayStreamReadResult.EndOfStream -> GatewayClientPollResult.Terminal(
                terminateWithoutGateway(current, GatewayClientTerminalStatus.PROTOCOL_ERROR),
            )
            is GatewayStreamReadResult.Failure -> GatewayClientPollResult.Terminal(
                terminateWithoutGateway(current, GatewayClientTerminalStatus.TRANSPORT_FAILURE, transport = read.reason),
            )
            is GatewayStreamReadResult.Frame -> consumeFrame(current, read)
        }
    }

    @Synchronized
    fun cancel(reason: GatewayCancellationReason): GatewayClientCancelResult {
        val current = active ?: return terminal?.let(GatewayClientCancelResult::AlreadyTerminal)
            ?: GatewayClientCancelResult.Inactive
        return GatewayClientCancelResult.Cancelled(
            terminateLocally(current, GatewayClientTerminalStatus.CLIENT_CANCELLED, reason),
        )
    }

    @Synchronized
    fun snapshot(): GatewayClientSnapshot? = active?.let { snapshot(it, null) }
        ?: terminal?.let { snapshot(null, it) }

    private fun consumeFrame(
        current: Active,
        frame: GatewayStreamReadResult.Frame,
    ): GatewayClientPollResult {
        val text = try {
            decodeUtf8(frame.bytes)
        } finally {
            frame.bytes.fill(0)
        } ?: return GatewayClientPollResult.Terminal(
            terminateWithoutGateway(current, GatewayClientTerminalStatus.PROTOCOL_ERROR),
        )
        val event = when (val decoded = GatewayContractCodec.decodeEvent(text)) {
            is GatewayContractResult.Success -> decoded.value
            is GatewayContractResult.Failure -> return GatewayClientPollResult.Terminal(
                terminateWithoutGateway(current, GatewayClientTerminalStatus.PROTOCOL_ERROR),
            )
        }
        return when (val reduced = GatewayStreamReducer.reduce(current.stream, event)) {
            is GatewayStreamReductionResult.Rejected -> GatewayClientPollResult.Terminal(
                terminateWithoutGateway(
                    current,
                    GatewayClientTerminalStatus.PROTOCOL_ERROR,
                    protocol = reduced.error,
                ),
            )
            is GatewayStreamReductionResult.Accepted -> {
                current.stream = reduced.state
                if (reduced.state.isTerminal) {
                    val report = GatewayClientTerminalReport(
                        status = reduced.state.terminalPayload.toTerminalStatus(),
                        stream = reduced.state,
                        gatewayTerminalConfirmed = true,
                        cancellationDelivery = null,
                        protocolError = null,
                        transportFailure = null,
                    )
                    closeAndPublish(current, report)
                    GatewayClientPollResult.Terminal(report)
                } else {
                    GatewayClientPollResult.Active(snapshot(current, null))
                }
            }
        }
    }

    private fun terminateLocally(
        current: Active,
        status: GatewayClientTerminalStatus,
        reason: GatewayCancellationReason,
    ): GatewayClientTerminalReport {
        val cancellation = GatewayCancellation(
            GatewaySchemaVersion.CURRENT,
            current.stream.request.requestId,
            cancellationIdFactory(),
            reason,
        )
        val bytes = GatewayContractCodec.encodeCancellation(cancellation).toByteArray(StandardCharsets.UTF_8)
        val delivery = try {
            runCatching { current.channel.cancel(bytes) }.getOrDefault(GatewayTransportCancellationDelivery.FAILED)
        } finally {
            bytes.fill(0)
        }
        val report = GatewayClientTerminalReport(
            status,
            current.stream,
            gatewayTerminalConfirmed = false,
            cancellationDelivery = delivery,
            protocolError = null,
            transportFailure = null,
        )
        closeAndPublish(current, report)
        return report
    }

    private fun terminateWithoutGateway(
        current: Active,
        status: GatewayClientTerminalStatus,
        protocol: GatewayStreamReductionError? = null,
        transport: GatewayTransportReadFailure? = null,
    ): GatewayClientTerminalReport {
        val report = GatewayClientTerminalReport(
            status,
            current.stream,
            gatewayTerminalConfirmed = false,
            cancellationDelivery = null,
            protocolError = protocol,
            transportFailure = transport,
        )
        closeAndPublish(current, report)
        return report
    }

    private fun closeAndPublish(current: Active, report: GatewayClientTerminalReport) {
        if (!current.closed) {
            current.closed = true
            runCatching { current.channel.close() }
        }
        if (active === current) active = null
        terminal = report
    }

    private fun snapshot(current: Active?, report: GatewayClientTerminalReport?): GatewayClientSnapshot {
        val stream = current?.stream ?: checkNotNull(report).stream
        return GatewayClientSnapshot(
            requestId = stream.request.requestId.value,
            operation = stream.request.operation,
            eventCount = stream.events.size,
            bufferedTextBytes = stream.partialTextBytes,
            proposalCount = stream.toolProposals.size,
            embeddingDimensions = stream.embeddingDimensions,
            terminalStatus = report?.status,
        )
    }

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun GatewayAuthResult<*>.toStartFailure(): GatewayClientStartFailure = when (this) {
        GatewayAuthResult.Absent -> GatewayClientStartFailure.TOKEN_ABSENT
        GatewayAuthResult.Expired -> GatewayClientStartFailure.TOKEN_EXPIRED
        GatewayAuthResult.Revoked -> GatewayClientStartFailure.TOKEN_REVOKED
        GatewayAuthResult.EndpointDenied -> GatewayClientStartFailure.ENDPOINT_DENIED
        GatewayAuthResult.AudienceMismatch -> GatewayClientStartFailure.AUDIENCE_MISMATCH
        GatewayAuthResult.InsufficientScope -> GatewayClientStartFailure.INSUFFICIENT_SCOPE
        GatewayAuthResult.Corrupt,
        is GatewayAuthResult.UnsupportedVersion,
        GatewayAuthResult.StorageUnavailable,
        -> GatewayClientStartFailure.AUTH_STORAGE_FAILURE
        else -> GatewayClientStartFailure.AUTH_INVALID
    }

    private fun GatewayTransportOpenFailure.toStartFailure(): GatewayClientStartFailure = when (this) {
        GatewayTransportOpenFailure.UNAUTHENTICATED -> GatewayClientStartFailure.TRANSPORT_UNAUTHENTICATED
        GatewayTransportOpenFailure.RATE_LIMITED -> GatewayClientStartFailure.TRANSPORT_RATE_LIMITED
        GatewayTransportOpenFailure.UNAVAILABLE -> GatewayClientStartFailure.TRANSPORT_UNAVAILABLE
        GatewayTransportOpenFailure.INVALID_REQUEST -> GatewayClientStartFailure.TRANSPORT_INVALID_REQUEST
        GatewayTransportOpenFailure.PROTOCOL_UNSUPPORTED -> GatewayClientStartFailure.PROTOCOL_UNSUPPORTED
    }

    private fun GatewayOperation.requiredScope(): GatewayAuthScope = when (this) {
        GatewayOperation.TEXT -> GatewayAuthScope.TEXT
        GatewayOperation.REALTIME -> GatewayAuthScope.REALTIME
        GatewayOperation.VISION -> GatewayAuthScope.VISION
        GatewayOperation.EMBEDDING -> GatewayAuthScope.EMBEDDING
    }

    private fun GatewayEventPayload?.toTerminalStatus(): GatewayClientTerminalStatus = when (this) {
        is GatewayEventPayload.Completed -> GatewayClientTerminalStatus.COMPLETED
        is GatewayEventPayload.Refusal -> GatewayClientTerminalStatus.REFUSED
        is GatewayEventPayload.Failed -> GatewayClientTerminalStatus.FAILED
        is GatewayEventPayload.Cancelled -> GatewayClientTerminalStatus.GATEWAY_CANCELLED
        GatewayEventPayload.TimedOut -> GatewayClientTerminalStatus.GATEWAY_TIMED_OUT
        else -> error("Non-terminal gateway payload")
    }

    private fun <T> GatewayClientModelResult<T>.success(): T = (this as GatewayClientModelResult.Success).value
}

private fun newCancellationId(): GatewayCancellationId = when (
    val parsed = GatewayCancellationId.parse(UUID.randomUUID().toString())
) {
    is GatewayContractResult.Success -> parsed.value
    is GatewayContractResult.Failure -> error("Generated cancellation ID was invalid")
}
