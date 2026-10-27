package io.redi.robot.platform.connection.ble

import io.redi.robot.body.looi.connection.ConnectionFailure as LegacyConnectionFailure
import io.redi.robot.body.looi.connection.ConnectionTarget
import io.redi.robot.body.looi.connection.GattFailureKind
import io.redi.robot.body.looi.connection.GattOperation
import io.redi.robot.body.looi.connection.GattTransportAdapter
import io.redi.robot.body.looi.connection.GattTransportEvent
import io.redi.robot.body.looi.connection.GattTransportObserver
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.connection.api.ConnectionAvailability
import io.redi.robot.connection.api.ConnectionAvailabilityReason
import io.redi.robot.connection.api.ConnectionCancellationRequest
import io.redi.robot.connection.api.ConnectionCommandState
import io.redi.robot.connection.api.ConnectionConnectRequest
import io.redi.robot.connection.api.ConnectionDisconnectRequest
import io.redi.robot.connection.api.ConnectionEndpointReference
import io.redi.robot.connection.api.ConnectionFailureCode
import io.redi.robot.connection.api.ConnectionGeneration
import io.redi.robot.connection.api.ConnectionLifecycleSnapshot
import io.redi.robot.connection.api.ConnectionLifecycleState
import io.redi.robot.connection.api.ConnectionMethodId
import io.redi.robot.connection.api.ConnectionMethodReference
import io.redi.robot.connection.api.ConnectionMethodVersion
import io.redi.robot.connection.api.ConnectionPeerAlias
import io.redi.robot.connection.api.ConnectionReadiness
import io.redi.robot.connection.api.ConnectionRequestId
import io.redi.robot.connection.api.ConnectionResult
import io.redi.robot.connection.api.ConnectionSessionId
import io.redi.robot.platform.transport.ble.gatt.ExistingGattConnectionBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BleConnectionProviderTest {
    @Test
    fun `generic connect invokes existing GATT adapter and connected establishes H013 channel`() {
        val harness = Harness()
        val request = request(1)

        val command = harness.provider.connect(request)
        harness.transport.emit(0, GattTransportEvent.Connecting(1))
        harness.transport.emit(0, GattTransportEvent.Connected(1))

        assertEquals(ConnectionCommandState.ACCEPTED, command.state)
        assertEquals(1, harness.transport.connectCalls)
        assertEquals("AA:BB:CC:DD:EE:FF", harness.transport.targets.single().deviceId)
        assertEquals(1L, harness.transport.generations.single())
        val connected = harness.provider.lifecycleSnapshot().state as ConnectionLifecycleState.Connected
        assertEquals(ConnectionReadiness.CHANNEL_ESTABLISHED, connected.session.readiness)
        assertEquals(request.endpoint, connected.session.metadata.endpoint)
        assertFalse(connected.session.metadata.endpoint.value.contains(':'))
        assertNotEquals(request.requestId.value, connected.session.id.value)
    }

    @Test
    fun `ready callback is evidence only and does not imply capability readiness or another state`() {
        val harness = Harness()
        harness.provider.connect(request(2))
        harness.transport.emit(0, GattTransportEvent.Connected(2))
        val connected = harness.provider.lifecycleSnapshot()

        harness.transport.emit(0, GattTransportEvent.Ready(2))

        assertEquals(connected, harness.provider.lifecycleSnapshot())
        assertEquals(BleConnectionDiagnosticEvent.TRANSPORT_READY, harness.provider.diagnosticsSnapshot().last().event)
    }

    @Test
    fun `typed GATT timeout maps to H013 failure and retains redacted platform status`() {
        val harness = Harness()
        harness.provider.connect(request(3))

        harness.transport.emit(
            0,
            GattTransportEvent.Failure(
                3,
                LegacyConnectionFailure.GattFailure(
                    GattOperation.CONNECT,
                    GattFailureKind.TIMEOUT,
                    0x08,
                    "private transport detail",
                ),
            ),
        )

        val failed = harness.provider.lifecycleSnapshot().state as ConnectionLifecycleState.Failed
        assertEquals(ConnectionFailureCode.TIMEOUT, failed.failure.code)
        assertEquals(1, harness.transport.disconnectCalls)
        val diagnostic = harness.provider.diagnosticsSnapshot().last()
        assertEquals(BleTransportFailureKind.TIMEOUT, diagnostic.failureKind)
        assertEquals(BleTransportOperation.CONNECT, diagnostic.operation)
        assertEquals(0x08, diagnostic.platformStatus)
        assertFalse(diagnostic.toString().contains("private transport detail"))
    }

    @Test
    fun `legacy permission failure maps to H013 permission unavailable`() {
        val harness = Harness()
        harness.provider.connect(request(4))

        harness.transport.emit(
            0,
            GattTransportEvent.Failure(
                4,
                LegacyConnectionFailure.Unknown("Missing BLUETOOTH_CONNECT permission"),
            ),
        )

        val failed = harness.provider.lifecycleSnapshot().state as ConnectionLifecycleState.Failed
        assertEquals(ConnectionFailureCode.PERMISSION_UNAVAILABLE, failed.failure.code)
    }

    @Test
    fun `deadline timeout fails and cleans the existing transport exactly once`() {
        val harness = Harness()
        val request = request(5)
        harness.provider.connect(request)

        harness.deadlines.fire(0)
        harness.deadlines.fire(0)

        val failed = harness.provider.lifecycleSnapshot().state as ConnectionLifecycleState.Failed
        assertEquals(ConnectionFailureCode.TIMEOUT, failed.failure.code)
        assertEquals(1, harness.transport.disconnectCalls)
        assertEquals(listOf(5L), harness.transport.disconnectGenerations)
    }

    @Test
    fun `connect cancellation is deterministic idempotent and rejects late callback`() {
        val harness = Harness()
        val request = request(6)
        harness.provider.connect(request)

        val first = harness.provider.cancel(ConnectionCancellationRequest(request.requestId, request.generation, timestamp(20)))
        val repeated = harness.provider.cancel(ConnectionCancellationRequest(request.requestId, request.generation, timestamp(21)))
        harness.transport.emit(0, GattTransportEvent.Connected(6))

        assertEquals(ConnectionCommandState.ACCEPTED, first.state)
        assertEquals(ConnectionCommandState.ACCEPTED, repeated.state)
        assertEquals(ConnectionLifecycleState.Cancelled(request.generation), harness.provider.lifecycleSnapshot().state)
        assertEquals(1, harness.transport.disconnectCalls)
        assertEquals(BleConnectionDiagnosticEvent.CALLBACK_IGNORED, harness.provider.diagnosticsSnapshot().last().event)
    }

    @Test
    fun `disconnect and repeated disconnect close once and preserve session correlation`() {
        val harness = Harness()
        val request = request(7)
        harness.provider.connect(request)
        harness.transport.emit(0, GattTransportEvent.Connected(7))
        val session = (harness.provider.lifecycleSnapshot().state as ConnectionLifecycleState.Connected).session
        val disconnect = ConnectionDisconnectRequest(request.requestId, request.generation, session.id, timestamp(30))

        assertEquals(ConnectionCommandState.ACCEPTED, harness.provider.disconnect(disconnect).state)
        assertEquals(ConnectionCommandState.ACCEPTED, harness.provider.disconnect(disconnect).state)
        harness.transport.emit(0, GattTransportEvent.Disconnected(7))
        assertEquals(ConnectionCommandState.ACCEPTED, harness.provider.disconnect(disconnect).state)

        assertEquals(ConnectionLifecycleState.Disconnected(request.generation), harness.provider.lifecycleSnapshot().state)
        assertEquals(1, harness.transport.disconnectCalls)
    }

    @Test
    fun `cancellation after channel establishment waits for disconnect confirmation`() {
        val harness = Harness()
        val request = request(8)
        harness.provider.connect(request)
        harness.transport.emit(0, GattTransportEvent.Connected(8))

        harness.provider.cancel(ConnectionCancellationRequest(request.requestId, request.generation, timestamp(30)))
        assertTrue(harness.provider.lifecycleSnapshot().state is ConnectionLifecycleState.Disconnecting)
        harness.transport.emit(0, GattTransportEvent.Disconnected(8))

        assertEquals(ConnectionLifecycleState.Cancelled(request.generation), harness.provider.lifecycleSnapshot().state)
        assertEquals(1, harness.transport.disconnectCalls)
    }

    @Test
    fun `late callback from old transport observer cannot advance newer generation`() {
        val harness = Harness()
        val first = request(9)
        harness.provider.connect(first)
        harness.provider.cancel(ConnectionCancellationRequest(first.requestId, first.generation, timestamp(20)))
        val second = request(10, requestId = "22222222-2222-4222-8222-222222222222")
        harness.provider.connect(second)

        harness.transport.emit(0, GattTransportEvent.Connected(9))

        assertTrue(harness.provider.lifecycleSnapshot().state is ConnectionLifecycleState.Connecting)
        assertEquals(second.generation, harness.provider.lifecycleSnapshot().latestGeneration)
        harness.transport.emit(1, GattTransportEvent.Connected(10))
        assertTrue(harness.provider.lifecycleSnapshot().state is ConnectionLifecycleState.Connected)
    }

    @Test
    fun `wrong event generation request or session correlation cannot mutate lifecycle`() {
        val harness = Harness()
        val request = request(11)
        harness.provider.connect(request)
        val before = harness.provider.lifecycleSnapshot()

        harness.transport.emit(0, GattTransportEvent.Connected(12))
        val wrongCancel = harness.provider.cancel(
            ConnectionCancellationRequest(requestId("33333333-3333-4333-8333-333333333333"), request.generation, timestamp(20)),
        )
        val wrongDisconnect = harness.provider.disconnect(
            ConnectionDisconnectRequest(
                request.requestId,
                request.generation,
                sessionId("44444444-4444-4444-8444-444444444444"),
                timestamp(20),
            ),
        )

        assertEquals(before, harness.provider.lifecycleSnapshot())
        assertEquals(ConnectionCommandState.REJECTED, wrongCancel.state)
        assertEquals(ConnectionCommandState.REJECTED, wrongDisconnect.state)
        assertEquals(0, harness.transport.disconnectCalls)
    }

    @Test
    fun `Bluetooth disabled permission and transport availability remain distinct`() {
        val disabled = Harness(available = BleTransportAvailability.BLUETOOTH_DISABLED)
        val permission = Harness(available = BleTransportAvailability.PERMISSION_UNAVAILABLE)
        val unavailable = Harness(available = BleTransportAvailability.TRANSPORT_UNAVAILABLE)

        assertEquals(ConnectionAvailability.UNAVAILABLE, disabled.provider.availabilitySnapshot().availability)
        assertEquals(
            ConnectionAvailabilityReason.TEMPORARILY_UNAVAILABLE,
            disabled.provider.availabilitySnapshot().reason,
        )
        assertEquals(
            ConnectionFailureCode.PERMISSION_UNAVAILABLE,
            permission.provider.connect(request(12)).failure?.code,
        )
        assertEquals(ConnectionFailureCode.UNAVAILABLE, unavailable.provider.connect(request(13)).failure?.code)
        assertEquals(0, disabled.transport.connectCalls + permission.transport.connectCalls + unavailable.transport.connectCalls)
    }

    @Test
    fun `unavailable endpoint directory does not expose or invoke a device path`() {
        val harness = Harness(endpointResolution = BleEndpointResolution.Unavailable)
        val result = harness.provider.connect(request(14))

        assertEquals(ConnectionCommandState.REJECTED, result.state)
        assertEquals(ConnectionFailureCode.UNAVAILABLE, result.failure?.code)
        assertEquals(0, harness.transport.connectCalls)
        assertTrue(harness.provider.lifecycleSnapshot().state is ConnectionLifecycleState.Idle)
    }

    @Test
    fun `unsupported connection method is rejected before transport resolution`() {
        val harness = Harness()
        val request = request(15, method = method("io.hugo.connection/local"))

        val result = harness.provider.connect(request)

        assertEquals(ConnectionFailureCode.UNSUPPORTED_METHOD, result.failure?.code)
        assertEquals(0, harness.resolverCalls)
        assertEquals(0, harness.transport.connectCalls)
    }

    @Test
    fun `equivalent requests create equivalent sessions and ordered evidence`() {
        val first = Harness()
        val second = Harness()
        val request = request(16)
        first.provider.connect(request)
        second.provider.connect(request)
        first.transport.emit(0, GattTransportEvent.Connected(16))
        second.transport.emit(0, GattTransportEvent.Connected(16))

        val firstSession = (first.provider.lifecycleSnapshot().state as ConnectionLifecycleState.Connected).session
        val secondSession = (second.provider.lifecycleSnapshot().state as ConnectionLifecycleState.Connected).session
        assertEquals(firstSession, secondSession)
        assertEquals(first.provider.diagnosticsSnapshot(), second.provider.diagnosticsSnapshot())
    }

    @Test
    fun `wrapper surface and source leak no Android GATT LOOI protocol trust or authority types`() {
        val exposedTypes = BleConnectionProvider::class.java.declaredMethods.flatMap {
            it.parameterTypes.asList() + it.returnType
        } + BleConnectionProvider::class.java.declaredConstructors.flatMap { it.parameterTypes.asList() }
        val forbiddenTypeTerms = listOf("android.", "BluetoothGatt", "BluetoothDevice", "body.looi", "protocol")
        assertFalse(exposedTypes.any { type -> forbiddenTypeTerms.any { type.name.contains(it, ignoreCase = true) } })

        val source = sourceFile("platform/connection/ble/BleConnectionProvider.kt").readText()
        val forbiddenSourceTerms = listOf(
            "android.bluetooth",
            "BluetoothGatt",
            "BluetoothDevice",
            "body.looi",
            "FED0",
            "FED8",
            "FED9",
            "trust",
            "permission grant",
            "active body",
        )
        assertTrue(forbiddenSourceTerms.filter(source::contains).isEmpty())

        val stateProperties = ConnectionLifecycleSnapshot::class.java.declaredFields.map { it.name }
        assertFalse(stateProperties.any { it.contains("trust", true) || it.contains("author", true) })
    }

    private inner class Harness(
        available: BleTransportAvailability = BleTransportAvailability.AVAILABLE,
        endpointResolution: BleEndpointResolution = BleEndpointResolution.Resolved(
            BleTransportTarget("AA:BB:CC:DD:EE:FF", "Reference BLE peer"),
        ),
    ) {
        val transport = ControlledLegacyGattTransport()
        val deadlines = ControlledDeadlines()
        var resolverCalls = 0
        val provider = BleConnectionProvider(
            transport = ExistingGattConnectionBridge(transport),
            endpoints = BleEndpointResolver {
                resolverCalls++
                endpointResolution
            },
            availability = BleTransportAvailabilityProbe { available },
            clock = BleMonotonicClock { timestamp(50) },
            deadlines = deadlines,
        )
    }

    private class ControlledLegacyGattTransport : GattTransportAdapter {
        var connectCalls = 0
        var disconnectCalls = 0
        val targets = mutableListOf<ConnectionTarget>()
        val generations = mutableListOf<Long>()
        val disconnectGenerations = mutableListOf<Long>()
        private val observers = mutableListOf<GattTransportObserver>()

        override fun connect(target: ConnectionTarget, generation: Long, callback: GattTransportObserver) {
            connectCalls++
            targets += target
            generations += generation
            observers += callback
        }

        override fun disconnect(generation: Long) {
            disconnectCalls++
            disconnectGenerations += generation
        }

        fun emit(index: Int, event: GattTransportEvent) = observers[index].onTransportEvent(event)
    }

    private class ControlledDeadlines : BleConnectionDeadlineScheduler {
        private data class Entry(val callback: () -> Unit, var cancelled: Boolean = false)
        private val entries = mutableListOf<Entry>()

        override fun schedule(
            requestId: ConnectionRequestId,
            generation: ConnectionGeneration,
            deadline: MonotonicTimestampMs,
            onElapsed: () -> Unit,
        ): BleConnectionDeadline {
            entries += Entry(onElapsed)
            val entry = entries.last()
            return BleConnectionDeadline { entry.cancelled = true }
        }

        fun fire(index: Int) {
            entries[index].callback()
        }
    }

    private fun request(
        generation: Long,
        requestId: String = "11111111-1111-4111-8111-111111111111",
        method: ConnectionMethodReference = BLE_CONNECTION_METHOD,
    ): ConnectionConnectRequest = connectionValue(
        ConnectionConnectRequest.create(
            requestId = requestId(requestId),
            generation = generation(generation),
            method = method,
            endpoint = endpoint(),
            peerAlias = connectionValue(ConnectionPeerAlias.parse("Reference peer")),
            requestedAt = timestamp(10),
            deadline = timestamp(100),
        ),
    )

    private fun endpoint(): ConnectionEndpointReference =
        connectionValue(ConnectionEndpointReference.parse("ble_endpoint.session_one"))

    private fun method(id: String): ConnectionMethodReference = ConnectionMethodReference(
        connectionValue(ConnectionMethodId.parse(id)),
        connectionValue(ConnectionMethodVersion.parse("1.0")),
    )

    private fun generation(value: Long): ConnectionGeneration = connectionValue(ConnectionGeneration.create(value))
    private fun requestId(value: String): ConnectionRequestId = connectionValue(ConnectionRequestId.parse(value))
    private fun sessionId(value: String): ConnectionSessionId = connectionValue(ConnectionSessionId.parse(value))

    private fun timestamp(value: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(value)) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> throw AssertionError(result.error)
    }

    private fun sourceFile(relativePath: String): File {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"), File("../app/src/main/java"))
        return roots.map { File(it, "io/redi/robot/$relativePath") }.first { it.isFile }
    }

    private fun <T> connectionValue(result: ConnectionResult<T>): T = when (result) {
        is ConnectionResult.Success -> result.value
        is ConnectionResult.Failure -> throw AssertionError(result.error)
    }
}
