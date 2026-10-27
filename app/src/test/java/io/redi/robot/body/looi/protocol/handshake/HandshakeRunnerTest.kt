package io.redi.robot.body.looi.protocol.handshake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeRunnerTest {
    @Test
    fun `candidate A sequence succeeds and never sets protocol ready`() {
        val transport = FakeHandshakeTransport()
        val runner = HandshakeRunner(
            transport = transport,
            subscriptionState = FakeSubscriptionState(
                mapOf(1L to setOf("FED5", "FED9")),
            ),
        )

        val started = runner.start(1L, HandshakeCandidateId.A)
        assertTrue(started.running)

        transport.completeSuccess(0)
        transport.completeSuccess(1)
        transport.completeSuccess(2)

        val summary = runner.summary()
        assertFalse(summary.running)
        assertTrue(summary.completed)
        assertEquals(HandshakeCandidateId.A, summary.candidate)
        assertFalse(summary.protocolReady)
        assertEquals(0, summary.stepResults.count { !it.success })
        assertEquals(4, summary.stepResults.size)
        assertEquals(
            listOf(
                "000000FF-0000-1000-8000-00805F9B34FB",
                "000000FF-0000-1000-8000-00805F9B34FB",
                "000000FF-0000-1000-8000-00805F9B34FB",
            ),
            transport.operations.map { it.serviceUuid },
        )
        assertEquals(
            listOf("FEDA", "FEDA", "FED8"),
            transport.operations.map { it.characteristicUuid.substring(4, 8) },
        )
    }

    @Test
    fun `candidate B sequence succeeds and never sets protocol ready`() {
        val transport = FakeHandshakeTransport()
        val runner = HandshakeRunner(
            transport = transport,
            subscriptionState = FakeSubscriptionState(
                mapOf(3L to setOf("FED9", "FEF0")),
            ),
        )

        runner.start(3L, HandshakeCandidateId.B)

        transport.completeSuccess(0)
        transport.completeSuccess(1)
        transport.completeSuccess(2)

        val summary = runner.summary()
        assertFalse(summary.running)
        assertTrue(summary.completed)
        assertEquals(HandshakeCandidateId.B, summary.candidate)
        assertFalse(summary.protocolReady)
        assertEquals(4, summary.stepResults.size)
        assertEquals(0, summary.stepResults.count { !it.success })
        assertEquals(
            listOf("FEDA", "FEF0", "FEDA"),
            transport.operations.map { it.characteristicUuid.substring(4, 8) },
        )
        assertEquals(
            "20260824010101010101".toByteArray().toList().map { it.toInt() },
            transport.operations[1].payload,
        )
    }

    @Test
    fun `step timeout records failure and keeps protocol unready`() {
        var nowMs = 2_000L
        val transport = FakeHandshakeTransport()
        val runner = HandshakeRunner(
            transport = transport,
            subscriptionState = FakeSubscriptionState(
                mapOf(7L to setOf("FED9", "FEF0")),
            ),
            nowProvider = { nowMs },
        )

        runner.start(7L, HandshakeCandidateId.B)
        assertEquals(1, transport.operations.size)

        nowMs += HANDSHAKE_DEFAULT_STEP_TIMEOUT_MS + 1L
        runner.checkTimeout(nowMs)

        val summary = runner.summary()
        assertFalse(summary.running)
        assertTrue(summary.completed)
        assertEquals(2, summary.stepResults.size)
        assertFalse(summary.protocolReady)
        assertEquals(
            "code=TIMEOUT; detail=step timed out",
            summary.stepResults.last().failureReason,
        )
    }

    @Test
    fun `missing characteristic failure stays failed and never ready`() {
        val transport = FakeHandshakeTransport()
        val runner = HandshakeRunner(
            transport = transport,
            subscriptionState = FakeSubscriptionState(
                mapOf(8L to setOf("FED5", "FED9")),
            ),
        )

        runner.start(8L, HandshakeCandidateId.A)
        transport.completeFailure(
            0,
            HandshakeTransportFailure(
                reason = HandshakeTransportStatusReason.MISSING_CHARACTERISTIC,
                detail = "characteristic not found",
            ),
        )

        val summary = runner.summary()
        assertFalse(summary.running)
        assertTrue(summary.completed)
        assertEquals(1, summary.stepResults.size)
        assertFalse(summary.protocolReady)
        assertFalse(summary.stepResults.single().success)
        assertEquals(
            "code=MISSING_CHARACTERISTIC; detail=characteristic not found",
            summary.stepResults.single().failureReason,
        )
    }

    @Test
    fun `cancel is idempotent and blocks stale callback completion`() {
        val transport = FakeHandshakeTransport()
        val runner = HandshakeRunner(
            transport = transport,
            subscriptionState = FakeSubscriptionState(
                mapOf(9L to setOf("FED9", "FEF0")),
            ),
        )

        val started = runner.start(9L, HandshakeCandidateId.B)
        assertTrue(started.running)
        assertEquals(1, transport.operations.size)

        val firstCancel = runner.cancel(9L)
        assertTrue(firstCancel.cancelled)
        assertFalse(firstCancel.running)
        assertTrue(firstCancel.completed)
        assertTrue(transport.canceledHandles.contains(0L))

        transport.completeSuccess(0)

        val afterCallback = runner.summary()
        assertTrue(afterCallback.cancelled)
        assertEquals(
            "Cancelled by operator",
            afterCallback.stepResults.last().failureReason,
        )
        val secondCancel = runner.cancel(9L)
        assertTrue(secondCancel.cancelled)
        assertEquals(1, transport.canceledHandles.size)
    }

    @Test
    fun `disabled feature runs no transport and marks not ready`() {
        val transport = FakeHandshakeTransport()
        val runner = HandshakeRunner(
            transport = transport,
            subscriptionState = FakeSubscriptionState(emptyMap()),
            featureEnabled = { false },
        )

        val summary = runner.start(10L, HandshakeCandidateId.A)
        assertFalse(summary.running)
        assertTrue(summary.completed)
        assertEquals(0, transport.operations.size)
        assertFalse(summary.featureEnabled)
    }

    private class FakeHandshakeTransport : HandshakeTransport {
        private val callbacks = mutableMapOf<Long, (HandshakeTransportResult) -> Unit>()
        private val executed = mutableListOf<HandshakeOperation>()
        val canceledHandles = mutableSetOf<Long>()

        val operations: List<HandshakeOperation>
            get() = executed.toList()

        override fun execute(
            operation: HandshakeOperation,
            completion: (HandshakeTransportResult) -> Unit,
        ): Long {
            val handle = callbacks.size.toLong()
            executed.add(operation)
            callbacks[handle] = completion
            return handle
        }

        override fun cancel(operationHandle: Long) {
            callbacks[operationHandle]?.let {
                canceledHandles.add(operationHandle)
            }
        }

        fun completeSuccess(handle: Int) {
            callbacks[handle.toLong()]?.invoke(HandshakeTransportSuccess(emptyList()))
        }

        fun completeFailure(handle: Int, failure: HandshakeTransportFailure) {
            callbacks[handle.toLong()]?.invoke(failure)
        }
    }

    private class FakeSubscriptionState(
        private val activeByGeneration: Map<Long, Set<String>>,
    ) : HandshakeNotificationSubscriptionState {
        override fun isSubscribed(generation: Long, characteristicShortUuid: String): Boolean {
            return activeByGeneration[generation]?.contains(characteristicShortUuid) == true
        }
    }
}
