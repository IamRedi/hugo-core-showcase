package io.redi.robot.body.looi.ble.queue

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializedGattOperationQueueTest {
    @Test
    fun `normal operations serialize and dispatch in order`() {
        val transport = ControlledGattTransport()
        val queue = SerializedGattOperationQueue(
            transport = transport,
            maxQueuedOperations = 4,
        )
        val firstResults = mutableListOf<GattOperationResult>()
        val secondResults = mutableListOf<GattOperationResult>()

        val first = queue.submit(sampleOperation(generation = 1L), firstResults::add)
        val second = queue.submit(sampleOperation(generation = 1L), secondResults::add)

        assertEquals(1, transport.dispatchedCount)
        assertEquals(GattOperationQueueState.ACTIVE, queue.progress((first as GattOperationEnqueued).handle)?.state)
        assertEquals(GattOperationQueueState.QUEUED, queue.progress((second as GattOperationEnqueued).handle)?.state)

        transport.complete(0, GattOperationSuccess())
        assertEquals(2, transport.dispatchedCount)
        assertEquals(listOf(GattOperationSuccess()), firstResults)

        transport.complete(1, GattOperationSuccess())
        assertEquals(listOf(GattOperationSuccess()), firstResults)
        assertEquals(listOf(GattOperationSuccess()), secondResults)
        assertEquals(false, queue.hasPendingOperations())
    }

    @Test
    fun `stop lane preempts queued normal operations`() {
        val transport = ControlledGattTransport()
        val queue = SerializedGattOperationQueue(transport = transport)
        val normal = mutableListOf<GattOperationResult>()
        val stop = mutableListOf<GattOperationResult>()

        val activeHandle = (queue.submit(sampleOperation(generation = 2L), normal::add) as GattOperationEnqueued).handle
        val queuedHandle = (queue.submit(sampleOperation(generation = 2L), normal::add) as GattOperationEnqueued).handle

        val stopHandle = (queue.submit(
            sampleOperation(generation = 2L, lane = GattOperationLane.STOP),
            stop::add,
        ) as GattOperationEnqueued).handle
        assertEquals(1, transport.dispatchedCount)
        assertEquals(GattOperationQueueState.ACTIVE, queue.progress(activeHandle)?.state)
        assertEquals(null, queue.progress(queuedHandle))

        transport.complete(0, GattOperationSuccess())
        assertEquals(2, normal.size)
        val preempted = normal.firstOrNull { result ->
            result is GattOperationFailureResult && result.failure.reason == GattQueueFailureReason.STOP_PREEMPTED
        }
        assertTrue(preempted is GattOperationFailureResult)
        assertEquals(GattQueueFailureReason.STOP_PREEMPTED, (preempted as GattOperationFailureResult).failure.reason)
        assertEquals(2, transport.dispatchedCount)

        assertEquals(GattOperationQueueState.ACTIVE, queue.progress(stopHandle)?.state)
        transport.complete(1, GattOperationSuccess())
        assertEquals(listOf(GattOperationSuccess()), stop)
    }

    @Test
    fun `timeout does not let a stale callback complete a later operation`() {
        var nowMs = 10_000L
        val transport = ControlledGattTransport()
        val queue = SerializedGattOperationQueue(
            transport = transport,
            nowProvider = { nowMs },
        )
        val firstResults = mutableListOf<GattOperationResult>()
        val secondResults = mutableListOf<GattOperationResult>()

        val firstHandle = (queue.submit(sampleOperation(generation = 3L, timeoutMs = 500L), firstResults::add) as GattOperationEnqueued).handle
        val secondHandle = (queue.submit(sampleOperation(generation = 3L), secondResults::add) as GattOperationEnqueued).handle
        assertEquals(1, transport.dispatchedCount)

        nowMs += 600
        queue.process(nowMs)

        val firstFailure = firstResults.single() as GattOperationFailureResult
        assertEquals(GattQueueFailureReason.TIMEOUT, firstFailure.failure.reason)
        assertEquals(GattOperationQueueState.ACTIVE, queue.progress(secondHandle)?.state)
        assertEquals(2, transport.dispatchedCount)

        transport.complete(0, GattOperationSuccess())
        transport.complete(1, GattOperationSuccess())
        assertEquals(listOf(GattOperationFailureResult(
            GattOperationFailure(GattQueueFailureReason.TIMEOUT, "operation timed out"),
        )), firstResults)
        assertEquals(listOf(GattOperationSuccess()), secondResults)
    }

    @Test
    fun `queue full rejects additional normal operations`() {
        val transport = ControlledGattTransport()
        val queue = SerializedGattOperationQueue(transport = transport, maxQueuedOperations = 1)

        queue.submit(sampleOperation(generation = 4L), {})
        queue.submit(sampleOperation(generation = 4L), {})

        val rejected = queue.submit(sampleOperation(generation = 4L), {})
        assertTrue(rejected is GattOperationRejected)
        assertEquals(
            GattQueueFailureReason.QUEUE_FULL,
            (rejected as GattOperationRejected).reason.reason,
        )
    }

    @Test
    fun `cancelled operation ignores later stale callbacks`() {
        val transport = ControlledGattTransport()
        val queue = SerializedGattOperationQueue(transport = transport)
        val activeResults = mutableListOf<GattOperationResult>()
        val queuedResults = mutableListOf<GattOperationResult>()

        val activeHandle = (queue.submit(sampleOperation(generation = 5L), activeResults::add) as GattOperationEnqueued).handle
        queue.submit(sampleOperation(generation = 5L), queuedResults::add)
        assertEquals(true, queue.cancel(activeHandle))
        assertEquals(false, queue.cancel(activeHandle))

        val activeFailure = activeResults.single() as GattOperationFailureResult
        assertEquals(GattQueueFailureReason.CANCELLED, activeFailure.failure.reason)

        transport.complete(0, GattOperationSuccess())
        assertEquals(1, activeResults.size)
        assertEquals(0, queuedResults.size)
    }

    @Test
    fun `concurrent cancellation and Android-style callback leave one terminal result`() {
        repeat(1_000) { iteration ->
            val transport = ControlledGattTransport()
            val queue = SerializedGattOperationQueue(transport = transport)
            val results = mutableListOf<GattOperationResult>()
            val handle = (queue.submit(sampleOperation(generation = iteration.toLong()), results::add) as GattOperationEnqueued).handle
            val start = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
            val cancelThread = Thread {
                start.await()
                runCatching { queue.cancel(handle) }.exceptionOrNull()?.let { failure.compareAndSet(null, it) }
            }
            val callbackThread = Thread {
                start.await()
                runCatching { transport.complete(0, GattOperationSuccess()) }.exceptionOrNull()?.let { failure.compareAndSet(null, it) }
            }

            cancelThread.start()
            callbackThread.start()
            start.countDown()
            cancelThread.join()
            callbackThread.join()

            assertNull("iteration $iteration threw across queue threads", failure.get())
            assertEquals("iteration $iteration emitted more than one terminal result", 1, results.size)
            assertEquals(false, queue.hasPendingOperations())
        }
    }

    private fun sampleOperation(
        generation: Long,
        lane: GattOperationLane = GattOperationLane.NORMAL,
        timeoutMs: Long = 5000L,
    ): GattQueuedOperationRequest = GattQueuedOperationRequest(
        generation = generation,
        kind = GattOperationKind.READ,
        serviceUuid = "0000180A-0000-1000-8000-00805F9B34FB",
        characteristicUuid = "00002A29-0000-1000-8000-00805F9B34FB",
        lane = lane,
        timeoutMs = timeoutMs,
    )

    private class ControlledGattTransport : GattOperationTransport {
        private val callbacks = mutableListOf<(GattOperationResult) -> Unit>()

        val dispatchedCount: Int
            get() = callbacks.size

        fun complete(index: Int, result: GattOperationResult) {
            callbacks[index](result)
        }

        override fun dispatch(operation: GattQueuedOperationRequest, onResult: (GattOperationResult) -> Unit) {
            callbacks.add(onResult)
        }
    }

}
