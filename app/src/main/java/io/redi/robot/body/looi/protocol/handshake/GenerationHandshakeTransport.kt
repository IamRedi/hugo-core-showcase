package io.redi.robot.body.looi.protocol.handshake

import io.redi.robot.body.looi.ble.queue.GattOperationEnqueued
import io.redi.robot.body.looi.ble.queue.GattOperationFailureResult
import io.redi.robot.body.looi.ble.queue.GattOperationKind
import io.redi.robot.body.looi.ble.queue.GattOperationRejected
import io.redi.robot.body.looi.ble.queue.GattQueuedOperationRequest
import io.redi.robot.body.looi.ble.queue.SerializedGattOperationQueue

/** Generation-bound adapter from the T016 runner to the only permitted GATT operation queue. */
class GenerationHandshakeTransport(
    private val generation: Long,
    private val queue: SerializedGattOperationQueue,
) : HandshakeTransport {
    private var nextHandle = 1L
    private val queueHandles = mutableMapOf<Long, io.redi.robot.body.looi.ble.queue.GattOperationHandle>()

    override fun execute(operation: HandshakeOperation, completion: (HandshakeTransportResult) -> Unit): Long {
        val handle = nextHandle++
        var completed = false
        val result = queue.submit(
            GattQueuedOperationRequest(
                generation = generation,
                kind = if (operation.kind == HandshakeOperationKind.READ) GattOperationKind.READ else GattOperationKind.WRITE,
                serviceUuid = operation.serviceUuid,
                characteristicUuid = operation.characteristicUuid,
                payload = operation.payload,
                withResponse = operation.withResponse,
                timeoutMs = operation.timeoutMs,
            ),
        ) { operationResult ->
            completed = true
            queueHandles.remove(handle)
            completion(
                when (operationResult) {
                    is io.redi.robot.body.looi.ble.queue.GattOperationSuccess -> HandshakeTransportSuccess(operationResult.payload ?: emptyList())
                    is GattOperationFailureResult -> HandshakeTransportFailure(HandshakeTransportStatusReason.ERROR, operationResult.failure.detail)
                },
            )
        }
        when (result) {
            is GattOperationEnqueued -> if (!completed) queueHandles[handle] = result.handle
            is GattOperationRejected -> completion(HandshakeTransportFailure(HandshakeTransportStatusReason.ERROR, result.reason.detail))
        }
        return handle
    }

    override fun cancel(operationHandle: Long) {
        queueHandles.remove(operationHandle)?.let(queue::cancel)
    }
}
