package io.redi.robot.body.looi.ble.queue

/**
 * A project-owned operation port for one connected GATT generation.
 * The Android implementation is created only by the active platform session.
 */
interface ActiveGattOperationTransport : GattOperationTransport {
    val generation: Long
    fun isAvailable(): Boolean
}
