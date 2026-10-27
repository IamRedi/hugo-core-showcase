package io.redi.robot.body.looi.protocol.telemetrycapture

import io.redi.robot.body.looi.ble.queue.GattOperationResult
import io.redi.robot.body.looi.ble.queue.GattOperationSuccess
import io.redi.robot.body.looi.ble.queue.GattOperationTransport
import io.redi.robot.body.looi.ble.queue.GattQueuedOperationRequest
import io.redi.robot.body.looi.connection.GattCharacteristicInventory
import io.redi.robot.body.looi.connection.GattNotificationEnvelope
import io.redi.robot.body.looi.connection.GattServiceInventory
import io.redi.robot.body.looi.protocol.profile.LooiProtocolProfile
import io.redi.robot.body.looi.protocol.profile.LooiProfileSelectionMode
import io.redi.robot.body.looi.protocol.handshake.HANDSHAKE_CANDIDATE_A
import io.redi.robot.body.looi.protocol.handshake.HandshakeAttemptSummary
import io.redi.robot.body.looi.protocol.handshake.HandshakeCandidateId
import io.redi.robot.protocol.fixtures.ProtocolFixtureParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Fed9TelemetryCaptureTest {
    @Test fun `capture becomes active only after subscription succeeds`() {
        val transport = ControlledTransport()
        val capture = capture(transport)
        capture.start()
        capture.onNotification(notification(1, byteArrayOf(1)))
        assertEquals(Fed9CaptureSubscriptionState.SUBSCRIBING, capture.state().subscriptionState)
        assertEquals(0, capture.state().notificationCount)
        transport.complete(0)
        assertEquals(Fed9CaptureSubscriptionState.ACTIVE, capture.state().subscriptionState)
    }

    @Test fun `notification bytes are immutable and arbitrary payloads stay raw`() {
        val transport = ControlledTransport(); val capture = capture(transport); capture.start(); transport.complete(0)
        val bytes = byteArrayOf(0, -1, 42)
        capture.onNotification(notification(1, bytes)); bytes[1] = 1
        assertEquals(listOf(0, 255, 42), capture.state().latestPayload)
        assertEquals(listOf(0, 255, 42), capture.state().records.single().payload)
    }

    @Test fun `stale generation notification is ignored`() {
        val transport = ControlledTransport(); val capture = capture(transport); capture.start(); transport.complete(0)
        capture.onNotification(notification(2, byteArrayOf(1)))
        assertEquals(0, capture.state().notificationCount)
    }

    @Test fun `cancellation and disconnect stop capture`() {
        val transport = ControlledTransport(); val capture = capture(transport); capture.start(); transport.complete(0)
        capture.stop(); assertEquals(Fed9CaptureSubscriptionState.STOPPED, capture.state().subscriptionState)
        capture.onDisconnect(1); assertEquals(Fed9CaptureSubscriptionState.STOPPED, capture.state().subscriptionState)
    }

    @Test fun `bounded capture retains newest records`() {
        val transport = ControlledTransport(); val capture = capture(transport, maxRecords = 2); capture.start(); transport.complete(0)
        capture.onNotification(notification(1, byteArrayOf(1))); capture.onNotification(notification(1, byteArrayOf(2))); capture.onNotification(notification(1, byteArrayOf(3)))
        assertEquals(3, capture.state().notificationCount)
        assertEquals(listOf(listOf(2), listOf(3)), capture.state().records.map { it.payload })
    }

    @Test fun `export round trips through T005 parser without identifiers`() {
        val transport = ControlledTransport(); val capture = capture(transport); capture.start(); transport.complete(0)
        capture.onNotification(notification(1, byteArrayOf(0x01, 0x02)))
        val export = capture.export() as Fed9FixtureExportResult.Success
        val parsed = ProtocolFixtureParser.parse(export.json)
        assertEquals(1, parsed.events.size)
        assertFalse(export.json.contains("AA:BB:CC"))
    }

    @Test fun `real hardware no-notification evidence is a valid redacted T005 fixture`() {
        val json = checkNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "fixtures/t030a-hardware-fed9-active-no-notification-2026-08-24.json",
            ),
        ).bufferedReader().use { it.readText() }
        val parsed = ProtocolFixtureParser.parse(json)

        assertTrue(parsed.events.isEmpty())
        assertEquals("redacted-looi-device", parsed.redaction.redactedDeviceId)
    }

    @Test fun `real hardware FED9 notification capture is a valid redacted T005 fixture`() {
        val json = checkNotNull(
            javaClass.classLoader?.getResourceAsStream(
                "fixtures/t030a-hardware-fed9-notifications-2026-08-24.json",
            ),
        ).bufferedReader().use { it.readText() }
        val parsed = ProtocolFixtureParser.parse(json)

        assertEquals(14, parsed.events.size)
        assertEquals("redacted-looi-device", parsed.redaction.redactedDeviceId)
        assertFalse(json.contains("RFCX10YN5TF"))
        assertTrue(json.contains("0000fed9-0000-1000-8000-00805f9b34fb", ignoreCase = true))
    }

    @Test fun `diagnostic contract references only FED9 and no prohibited actuation endpoint`() {
        assertTrue(Fed9TelemetryDiagnosticContract.isFed9("0000fed9-0000-1000-8000-00805f9b34fb"))
        assertTrue(Fed9TelemetryDiagnosticContract.prohibitedActuationCharacteristicShortIds.none { it == FED9_CHARACTERISTIC_SHORT_ID })
    }

    @Test fun `ambiguous full inventory permits only unverified endpoint scoped FED9 capture`() {
        val availability = fed9CaptureAvailability(ambiguousInventory())
        assertTrue(availability is Fed9CaptureAvailability.Available)
        availability as Fed9CaptureAvailability.Available
        assertEquals(LooiProtocolProfile.PROFILE_A.modelId, availability.profileEvidence.profileId)
        assertEquals(LooiProfileSelectionMode.AMBIGUOUS, availability.profileEvidence.selectionMode)
        assertFalse(availability.profileEvidence.verified)
        assertTrue(Fed9TelemetryDiagnosticContract.isFed9(availability.endpoint.characteristicUuid))
    }

    @Test fun `incomplete profile A capability evidence cannot start FED9 diagnostic`() {
        assertTrue(fed9CaptureAvailability(inventory()) is Fed9CaptureAvailability.Unavailable)
    }

    @Test fun `FED9 prerequisite accepts only a complete successful candidate A for the current generation`() {
        val successfulSteps = HANDSHAKE_CANDIDATE_A.steps.mapIndexed { index, step ->
            io.redi.robot.body.looi.protocol.handshake.HandshakeStepResult(
                stepIndex = index,
                stepId = step.id,
                success = true,
                failureReason = null,
                startedAtMs = index.toLong(),
                completedAtMs = index.toLong() + 1,
            )
        }
        val success = HandshakeAttemptSummary(
            generation = 7,
            candidate = HandshakeCandidateId.A,
            running = false,
            completed = true,
            cancelled = false,
            featureEnabled = true,
            stepResults = successfulSteps,
            protocolReady = false,
            failureReason = null,
        )

        assertTrue(VerifiedFed9Prerequisite.isSatisfied(7, success))
        assertFalse(VerifiedFed9Prerequisite.isSatisfied(8, success))
        assertFalse(VerifiedFed9Prerequisite.isSatisfied(7, success.copy(candidate = HandshakeCandidateId.B)))
        assertFalse(VerifiedFed9Prerequisite.isSatisfied(7, success.copy(stepResults = successfulSteps.dropLast(1))))
        assertFalse(VerifiedFed9Prerequisite.isSatisfied(7, success.copy(cancelled = true)))
        assertFalse(VerifiedFed9Prerequisite.isSatisfied(7, success.copy(failureReason = "failed")))
    }

    @Test fun `verified FED9 prerequisite contains no prohibited actuation or heartbeat operation`() {
        val operationCharacteristics = HANDSHAKE_CANDIDATE_A.steps.mapNotNull { it.operation?.characteristicUuid }
        assertTrue(operationCharacteristics.none { uuid ->
            Fed9TelemetryDiagnosticContract.prohibitedActuationCharacteristicShortIds.any { prohibited ->
                uuid.contains(prohibited, ignoreCase = true)
            }
        })
        assertTrue(operationCharacteristics.none { it.contains("FF02", ignoreCase = true) })
    }

    private fun capture(transport: ControlledTransport, maxRecords: Int = 128) = Fed9TelemetryCapture(
        generation = 1,
        profileEvidence = Fed9CaptureProfileEvidence(
            profileId = LooiProtocolProfile.PROFILE_A.modelId,
            selectionMode = LooiProfileSelectionMode.AMBIGUOUS,
        ),
        endpoint = Fed9CaptureEndpoint("0000feea-0000-1000-8000-00805f9b34fb", "0000fed9-0000-1000-8000-00805f9b34fb"),
        transport = transport, inventory = inventory(), maxRecords = maxRecords,
        monotonicNowMs = { 100L }, wallClockNowMs = { 200L },
    )
    private fun notification(generation: Long, bytes: ByteArray) = GattNotificationEnvelope(generation, "0000feea-0000-1000-8000-00805f9b34fb", "0000fed9-0000-1000-8000-00805f9b34fb", bytes.map { it.toInt() and 0xff })
    private fun inventory() = listOf(GattServiceInventory("0000feea-0000-1000-8000-00805f9b34fb", listOf(GattCharacteristicInventory("0000fed9-0000-1000-8000-00805f9b34fb"))))
    private fun ambiguousInventory() = listOf(
        GattServiceInventory(
            "000000ff-0000-1000-8000-00805f9b34fb",
            listOf("FED0", "FED1", "FED2", "FED4", "FED5", "FED6", "FED7", "FED8", "FED9", "FEDA")
                .map { GattCharacteristicInventory("0000${it.lowercase()}-0000-1000-8000-00805f9b34fb") },
        ),
    )
    private class ControlledTransport : GattOperationTransport {
        private val callbacks = mutableListOf<(GattOperationResult) -> Unit>()
        override fun dispatch(operation: GattQueuedOperationRequest, onResult: (GattOperationResult) -> Unit) { callbacks += onResult }
        fun complete(index: Int) = callbacks[index](GattOperationSuccess())
    }
}
