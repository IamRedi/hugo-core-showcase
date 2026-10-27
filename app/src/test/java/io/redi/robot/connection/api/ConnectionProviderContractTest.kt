package io.redi.robot.connection.api

import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionProviderContractTest {
    @Test
    fun validContractHasOpaqueMethodEndpointGenerationAndSessionIdentity() {
        val request = request(1)
        val session = session(request)

        assertEquals(1, request.generation.value)
        assertEquals(ConnectionReadiness.CHANNEL_ESTABLISHED, session.readiness)
        assertFalse(request.endpoint.value.contains(":"))
    }

    @Test
    fun generationAndSessionLifecycleAreDeterministic() {
        val request = request(2)
        val session = session(request)
        val final = reduceAll(
            ConnectionLifecycleSnapshot.initial(),
            listOf(
                ConnectionLifecycleEvent.ConnectRequested(request),
                ConnectionLifecycleEvent.ChannelEstablished(
                    request.generation, request.requestId, session.id, session.metadata,
                ),
                ConnectionLifecycleEvent.DisconnectRequested(request.generation, session.id),
                ConnectionLifecycleEvent.Disconnected(request.generation, session.id),
            ),
        )

        assertEquals(ConnectionLifecycleState.Disconnected(request.generation), final.state)
        assertEquals(request.generation, final.latestGeneration)
    }

    @Test
    fun cancellationAndTimeoutPreventLateConnectedResults() {
        val cancelledRequest = request(3)
        val cancelled = reduceAll(
            ConnectionLifecycleSnapshot.initial(),
            listOf(
                ConnectionLifecycleEvent.ConnectRequested(cancelledRequest),
                ConnectionLifecycleEvent.CancellationRequested(
                    cancelledRequest.generation,
                    cancelledRequest.requestId,
                ),
            ),
        )
        val late = ConnectionLifecycleReducer.reduce(
            cancelled,
            ConnectionLifecycleEvent.ChannelEstablished(
                cancelledRequest.generation,
                cancelledRequest.requestId,
                sessionId("22222222-2222-4222-8222-222222222222"),
                metadata(cancelledRequest),
            ),
        )
        val timeoutRequest = request(4)
        val timedOut = reduceAll(
            ConnectionLifecycleSnapshot.initial(),
            listOf(
                ConnectionLifecycleEvent.ConnectRequested(timeoutRequest),
                ConnectionLifecycleEvent.TimedOut(timeoutRequest.generation, timeoutRequest.requestId),
            ),
        )

        assertEquals(ConnectionLifecycleState.Cancelled(cancelledRequest.generation), cancelled.state)
        assertEquals(ConnectionLifecycleIgnoredReason.DUPLICATE_EVENT, ignoredReason(late))
        assertEquals(
            ConnectionLifecycleState.Failed(timeoutRequest.generation, ConnectionFailure(ConnectionFailureCode.TIMEOUT)),
            timedOut.state,
        )
    }

    @Test
    fun typedFailuresStaleCallbacksAndDisconnectAreSafe() {
        val first = request(5)
        val second = request(6)
        val secondSession = session(second)
        val disconnected = reduceAll(
            ConnectionLifecycleSnapshot.initial(),
            listOf(
                ConnectionLifecycleEvent.ConnectRequested(first),
                ConnectionLifecycleEvent.Failed(
                    first.generation,
                    first.requestId,
                    ConnectionFailure(ConnectionFailureCode.TRANSPORT_FAILURE),
                ),
                ConnectionLifecycleEvent.ConnectRequested(second),
                ConnectionLifecycleEvent.ChannelEstablished(
                    second.generation, second.requestId, secondSession.id, secondSession.metadata,
                ),
                ConnectionLifecycleEvent.DisconnectRequested(second.generation, secondSession.id),
                ConnectionLifecycleEvent.Disconnected(second.generation, secondSession.id),
            ),
        )
        val stale = ConnectionLifecycleReducer.reduce(
            disconnected,
            ConnectionLifecycleEvent.Failed(
                first.generation,
                first.requestId,
                ConnectionFailure(ConnectionFailureCode.TRANSPORT_FAILURE),
            ),
        )
        val duplicate = ConnectionLifecycleReducer.reduce(
            disconnected,
            ConnectionLifecycleEvent.Disconnected(second.generation, secondSession.id),
        )

        assertEquals(ConnectionLifecycleIgnoredReason.STALE_GENERATION, ignoredReason(stale))
        assertEquals(ConnectionLifecycleIgnoredReason.DUPLICATE_EVENT, ignoredReason(duplicate))
        val unsupported = success(ConnectionCommandResult.create(
            second.requestId,
            second.generation,
            ConnectionCommandState.REJECTED,
            ConnectionFailure(ConnectionFailureCode.UNSUPPORTED_METHOD),
        ))
        assertEquals(ConnectionFailureCode.UNSUPPORTED_METHOD, unsupported.failure?.code)
    }

    @Test
    fun validationRejectsMalformedValuesAndNoPlatformOrVendorTypesLeak() {
        assertEquals(ConnectionResult.Failure(ConnectionContractError.MalformedMethodId), ConnectionMethodId.parse("ble"))
        assertEquals(ConnectionResult.Failure(ConnectionContractError.MalformedMethodVersion), ConnectionMethodVersion.parse("1.02"))
        assertEquals(ConnectionResult.Failure(ConnectionContractError.InvalidDeadline), ConnectionConnectRequest.create(
            requestId("11111111-1111-4111-8111-111111111111"), generation(7), method(), endpoint(), null,
            timestamp(20), timestamp(20),
        ))
        val types = ConnectionProvider::class.java.methods.flatMap { method ->
            method.parameterTypes.asList() + method.returnType
        } + ConnectionProvider::class.java
        assertFalse(types.any { it.name.contains("android.") || it.name.contains("bluetooth", true) })
        assertFalse(types.any { it.name.contains("looi", true) || it.name.contains("vendor", true) })
        val methodNames = ConnectionProvider::class.java.methods.map { it.name }
        assertTrue(listOf("availabilitySnapshot", "connect", "cancel", "disconnect").all { expected ->
            methodNames.any { it == expected || it.startsWith("$expected-") }
        })
    }

    private fun request(value: Long): ConnectionConnectRequest = success(ConnectionConnectRequest.create(
        requestId("11111111-1111-4111-8111-111111111111"), generation(value), method(), endpoint(),
        success(ConnectionPeerAlias.parse("Reference peer")), timestamp(10), timestamp(100),
    ))

    private fun session(request: ConnectionConnectRequest) = ConnectionSession(
        sessionId("22222222-2222-4222-8222-222222222222"), request.generation, request.requestId,
        metadata(request),
    )

    private fun metadata(request: ConnectionConnectRequest) = ConnectionMetadata(
        request.method, request.endpoint, request.peerAlias, timestamp(11),
    )

    private fun method() = ConnectionMethodReference(
        success(ConnectionMethodId.parse("io.hugo.connection/reference")),
        success(ConnectionMethodVersion.parse("1.0")),
    )

    private fun endpoint() = success(ConnectionEndpointReference.parse("endpoint.reference.primary"))
    private fun generation(value: Long) = success(ConnectionGeneration.create(value))
    private fun requestId(value: String) = success(ConnectionRequestId.parse(value))
    private fun sessionId(value: String) = success(ConnectionSessionId.parse(value))

    private fun timestamp(value: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(value)) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> throw AssertionError(result.error)
    }

    private fun reduceAll(
        initial: ConnectionLifecycleSnapshot,
        events: List<ConnectionLifecycleEvent>,
    ): ConnectionLifecycleSnapshot = events.fold(initial) { snapshot, event ->
        ConnectionLifecycleReducer.reduce(snapshot, event).snapshot
    }

    private fun ignoredReason(reduction: ConnectionLifecycleReduction): ConnectionLifecycleIgnoredReason {
        assertTrue(reduction.disposition is ConnectionLifecycleDisposition.Ignored)
        return (reduction.disposition as ConnectionLifecycleDisposition.Ignored).reason
    }

    private fun <T> success(result: ConnectionResult<T>): T = when (result) {
        is ConnectionResult.Success -> result.value
        is ConnectionResult.Failure -> throw AssertionError(result.error)
    }
}
