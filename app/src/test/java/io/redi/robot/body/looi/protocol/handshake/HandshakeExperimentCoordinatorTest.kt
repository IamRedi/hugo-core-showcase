package io.redi.robot.body.looi.protocol.handshake

import io.redi.robot.body.looi.ble.queue.GattOperationFailure
import io.redi.robot.body.looi.ble.queue.GattOperationFailureResult
import io.redi.robot.body.looi.ble.queue.GattOperationKind
import io.redi.robot.body.looi.ble.queue.GattOperationResult
import io.redi.robot.body.looi.ble.queue.GattOperationSuccess
import io.redi.robot.body.looi.ble.queue.GattOperationTransport
import io.redi.robot.body.looi.ble.queue.GattQueueFailureReason
import io.redi.robot.body.looi.ble.queue.GattQueuedOperationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeExperimentCoordinatorTest {
    @Test fun `candidate A waits for both active subscriptions before first handshake write`() {
        val transport = ControlledTransport()
        val coordinator = HandshakeExperimentCoordinator(4, transport)
        coordinator.start(HandshakeCandidateId.A)

        assertEquals(listOf(GattOperationKind.DESCRIPTOR_WRITE), transport.requests.map { it.kind })
        transport.completeSuccess()
        assertEquals(listOf(GattOperationKind.DESCRIPTOR_WRITE, GattOperationKind.DESCRIPTOR_WRITE), transport.requests.map { it.kind })
        transport.completeSuccess()
        assertEquals(GattOperationKind.WRITE, transport.requests.last().kind)
        assertEquals("0000FEDA-0000-1000-8000-00805F9B34FB", transport.requests.last().characteristicUuid)
    }

    @Test fun `subscription failure prevents candidate B handshake write`() {
        val transport = ControlledTransport()
        val coordinator = HandshakeExperimentCoordinator(8, transport)
        coordinator.start(HandshakeCandidateId.B)

        transport.completeFailure("CCCD rejected")
        assertTrue(transport.requests.all { it.kind == GattOperationKind.DESCRIPTOR_WRITE })
        assertTrue(coordinator.summary().stepResults.isEmpty())
    }

    private class ControlledTransport : GattOperationTransport {
        val requests = mutableListOf<GattQueuedOperationRequest>()
        private var completion: ((GattOperationResult) -> Unit)? = null
        override fun dispatch(operation: GattQueuedOperationRequest, onResult: (GattOperationResult) -> Unit) {
            requests += operation
            completion = onResult
        }
        fun completeSuccess() = completion?.invoke(GattOperationSuccess())
        fun completeFailure(detail: String) = completion?.invoke(
            GattOperationFailureResult(GattOperationFailure(GattQueueFailureReason.TRANSPORT_ERROR, detail)),
        )
    }
}
