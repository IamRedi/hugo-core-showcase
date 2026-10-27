package io.redi.robot.body.looi.ble.notification

import io.redi.robot.body.looi.ble.queue.GattOperationFailure
import io.redi.robot.body.looi.ble.queue.GattOperationFailureResult
import io.redi.robot.body.looi.ble.queue.GattOperationResult
import io.redi.robot.body.looi.ble.queue.GattOperationSuccess
import io.redi.robot.body.looi.ble.queue.GattOperationTransport
import io.redi.robot.body.looi.ble.queue.GattQueuedOperationRequest
import io.redi.robot.body.looi.ble.queue.GattQueueFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GattNotificationRouterTest {
    @Test
    fun `subscribe completion follows queue ordering`() {
        val transport = ControlledGattTransport()
        val events = mutableListOf<GattNotificationEvent>()
        val router = GattNotificationRouter(
            transport = transport,
            onEvent = { events.add(it) },
        )

        val keyA = GattNotificationKey("A", "service-a", "char-a")
        val keyB = GattNotificationKey("A", "service-b", "char-b")

        router.requestSubscribe(1L, keyA)
        router.requestSubscribe(1L, keyB)

        assertEquals(1, transport.dispatched)
        transport.complete(0, GattOperationSuccess())

        assertEquals(2, transport.dispatched)
        transport.complete(1, GattOperationSuccess())

        val subscribed = events.filterIsInstance<GattNotificationEvent.Subscribed>()
        assertEquals(2, subscribed.size)
        assertEquals(1L, subscribed[0].generation)
        assertEquals(1L, subscribed[1].generation)
        assertEquals(keyA, subscribed[0].key)
        assertEquals(keyB, subscribed[1].key)
    }

    @Test
    fun `duplicate subscribe request returns same handle and does not re-enqueue`() {
        val transport = ControlledGattTransport()
        val events = mutableListOf<GattNotificationEvent>()
        val router = GattNotificationRouter(
            transport = transport,
            onEvent = { events.add(it) },
        )
        val key = GattNotificationKey("A", "service-dup", "char-dup")

        val first = router.requestSubscribe(7L, key)
        val second = router.requestSubscribe(7L, key)

        assertEquals(1, transport.dispatched)
        val firstHandle = (first as GattNotificationSubscriptionResult.Accepted).handle
        val secondHandle = (second as GattNotificationSubscriptionResult.Accepted).handle
        assertEquals(firstHandle, secondHandle)

        transport.complete(0, GattOperationSuccess())
        assertEquals(1, events.filterIsInstance<GattNotificationEvent.Subscribed>().size)
    }

    @Test
    fun `notification stream preserves byte copies and order`() {
        val transport = ControlledGattTransport()
        val events = mutableListOf<GattNotificationEvent>()
        val timestamps = mutableListOf(111L, 222L, 333L, 444L)
        val router = GattNotificationRouter(
            transport = transport,
            onEvent = { events.add(it) },
            nowProvider = { timestamps.removeAt(0) },
        )
        val key = GattNotificationKey("B", "service-notif", "char-notif")
        router.requestSubscribe(4L, key)
        transport.complete(0, GattOperationSuccess())

        val firstPayload = byteArrayOf(1, 2, 3)
        val secondPayload = byteArrayOf(4, 5, 6)
        router.routeNotification(4L, key.channel, key.serviceUuid, key.characteristicUuid, firstPayload)
        firstPayload[0] = 9
        router.routeNotification(4L, key.channel, key.serviceUuid, key.characteristicUuid, secondPayload)

        val notifications = events.filterIsInstance<GattNotificationEvent.NotificationReceived>()
        assertEquals(2, notifications.size)
        assertTrue(notifications[0].payload.bytes.contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(notifications[1].payload.bytes.contentEquals(byteArrayOf(4, 5, 6)))
        assertEquals(111L, notifications[0].payload.timestampMs)
        assertEquals(222L, notifications[1].payload.timestampMs)
    }

    @Test
    fun `descriptor failure callback marks subscription failed`() {
        val transport = ControlledGattTransport()
        val events = mutableListOf<GattNotificationEvent>()
        val router = GattNotificationRouter(
            transport = transport,
            onEvent = { events.add(it) },
        )
        val key = GattNotificationKey("B", "service-fail", "char-fail")

        router.requestSubscribe(9L, key)
        transport.complete(
            0,
            GattOperationFailureResult(
                GattOperationFailure(
                    reason = GattQueueFailureReason.TRANSPORT_ERROR,
                    detail = "queue write failed",
                ),
            ),
        )

        val failed = events.filterIsInstance<GattNotificationEvent.SubscribeFailed>()
        assertEquals(1, failed.size)
        assertEquals("CCCD write failed", failed[0].reason)
        assertEquals("queue write failed", failed[0].failure?.detail)

        val retry = router.requestSubscribe(9L, key)
        assertTrue(retry is GattNotificationSubscriptionResult.Accepted)
        assertEquals(2, transport.dispatched)
    }

    @Test
    fun `queue full rejects subscribe request with failure event`() {
        val transport = ControlledGattTransport()
        val events = mutableListOf<GattNotificationEvent>()
        val router = GattNotificationRouter(
            transport = transport,
            onEvent = { events.add(it) },
            maxQueuedOperations = 1,
        )

        router.requestSubscribe(12L, GattNotificationKey("D", "service-1", "char-1"))
        router.requestSubscribe(12L, GattNotificationKey("D", "service-2", "char-2"))
        val rejected = router.requestSubscribe(12L, GattNotificationKey("D", "service-3", "char-3"))

        assertTrue(rejected is GattNotificationSubscriptionResult.Rejected)
        assertEquals("operation queue is full", (rejected as GattNotificationSubscriptionResult.Rejected).reason)
        assertEquals(1, events.filterIsInstance<GattNotificationEvent.SubscribeFailed>().size)
    }

    @Test
    fun `disconnect clears subscriptions and drops stale notifications`() {
        val transport = ControlledGattTransport()
        val events = mutableListOf<GattNotificationEvent>()
        val router = GattNotificationRouter(
            transport = transport,
            onEvent = { events.add(it) },
            maxQueuedOperations = 1,
        )
        val key = GattNotificationKey("C", "service-disco", "char-disco")
        val second = GattNotificationKey("C", "service-disco-2", "char-disco-2")

        router.requestSubscribe(11L, key)
        router.requestSubscribe(11L, second)

        transport.complete(0, GattOperationSuccess())
        router.onGenerationDisconnect(11L)
        router.routeNotification(11L, key.channel, key.serviceUuid, key.characteristicUuid, byteArrayOf(1))

        assertEquals(0, events.filterIsInstance<GattNotificationEvent.NotificationReceived>().size)
        assertEquals(2, events.filterIsInstance<GattNotificationEvent.Unsubscribed>().size)
        assertFalse(events.any { it is GattNotificationEvent.SubscribeFailed })
    }

    private class ControlledGattTransport : GattOperationTransport {
        private val callbacks = mutableListOf<(GattOperationResult) -> Unit>()
        val requests = mutableListOf<GattQueuedOperationRequest>()

        var dispatched: Int = 0
            private set

        fun complete(index: Int, result: GattOperationResult) {
            callbacks[index](result)
        }

        override fun dispatch(operation: GattQueuedOperationRequest, onResult: (GattOperationResult) -> Unit) {
            requests.add(operation)
            callbacks.add(onResult)
            dispatched++
        }
    }
}
