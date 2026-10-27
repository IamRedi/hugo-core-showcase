package io.redi.robot.body.looi.ble.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class FakeBleScannerTest {
    @Test
    fun `fake callbacks are emitted in order and default duplicate policy drops duplicates`() {
        val events = mutableListOf<BleScanEvent>()
        val scanner = ControlledFakeBleScanner()

        val session = scanner.startScan(BleScanConfig(allowDuplicateObservations = false), events::add)
        val first = BleScanResult(id = "a1", displayName = "Alpha", rssi = -40)
        val second = BleScanResult(id = "b2", displayName = "Beta", rssi = -50)
        val duplicate = BleScanResult(id = "a1", displayName = "Alpha-2", rssi = -41)

        scanner.emitFound(first)
        scanner.emitFound(second)
        scanner.emitFound(duplicate)
        scanner.emitFound(first)

        assertEquals(
            listOf(
                BleScanEvent.DeviceFound(first),
                BleScanEvent.DeviceFound(second),
            ),
            events,
        )
        assertEquals(true, session.isActive)
    }

    @Test
    fun `fake scanner can allow duplicates when configured`() {
        val events = mutableListOf<BleScanEvent>()
        val scanner = ControlledFakeBleScanner()

        scanner.startScan(BleScanConfig(allowDuplicateObservations = true), events::add)

        val duplicate = BleScanResult(id = "a1", displayName = "Alpha", rssi = -40)
        scanner.emitFound(duplicate)
        scanner.emitFound(duplicate)

        assertEquals(
            listOf(
                BleScanEvent.DeviceFound(duplicate),
                BleScanEvent.DeviceFound(duplicate),
            ),
            events,
        )
    }

    @Test
    fun `fake scanner emits timeout failure as typed state`() {
        val events = mutableListOf<BleScanEvent>()
        val scanner = ControlledFakeBleScanner()

        scanner.startScan(BleScanConfig(timeoutMillis = 50L), events::add)
        scanner.emitTimeout()

        assertEquals(
            listOf(
                BleScanEvent.Failure(BleScanFailure.Timeout(50L)),
                BleScanEvent.Completed(BleScanCompletedReason.TIMEOUT),
            ),
            events,
        )
    }

    @Test
    fun `fake scanner drops events after stop and stop is idempotent`() {
        val events = mutableListOf<BleScanEvent>()
        val scanner = ControlledFakeBleScanner()
        val session = scanner.startScan(BleScanConfig(), events::add)

        scanner.emitFound(BleScanResult(id = "a1", displayName = "Alpha", rssi = -35))
        session.stop()
        session.stop()
        scanner.emitFound(BleScanResult(id = "b2", displayName = "Beta", rssi = -30))

        assertEquals(
            listOf(BleScanEvent.DeviceFound(BleScanResult(id = "a1", displayName = "Alpha", rssi = -35))),
            events,
        )
        assertEquals(false, session.isActive)
    }

    private class ControlledFakeBleScanner : BleScanner {
        private var duplicatePolicy: DuplicatePolicyState = DuplicatePolicyState(false)
        private var active = false
        private var currentListener: BleScanListener? = null
        private var config: BleScanConfig = BleScanConfig()

        override fun startScan(config: BleScanConfig, listener: BleScanListener): BleScanSession {
            active = true
            currentListener = listener
            this.config = config
            duplicatePolicy = DuplicatePolicyState(config.allowDuplicateObservations)
            duplicatePolicy.clear()
            return FakeScanSession { active = false }
        }

        fun emitFound(result: BleScanResult) {
            if (!active) return
            if (duplicatePolicy.shouldEmitFor(result)) {
                currentListener?.onEvent(BleScanEvent.DeviceFound(result))
            }
        }

        fun emitTimeout() {
            if (!active) return
            currentListener?.onEvent(BleScanEvent.Failure(BleScanFailure.Timeout(config.timeoutMillis)))
            currentListener?.onEvent(BleScanEvent.Completed(BleScanCompletedReason.TIMEOUT))
            active = false
        }

        private inner class FakeScanSession(
            private val onStop: () -> Unit,
        ) : BleScanSession {
            private var stopped = false

            override fun stop() {
                if (stopped) return
                stopped = true
                onStop()
            }

            override val isActive: Boolean
                get() = active
        }
    }
}
