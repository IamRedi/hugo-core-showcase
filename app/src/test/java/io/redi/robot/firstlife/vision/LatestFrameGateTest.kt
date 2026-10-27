package io.redi.robot.firstlife.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class LatestFrameGateTest {
    @Test
    fun `one in-flight plus latest pending releases every frame exactly once`() {
        val processed = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        val completions = mutableMapOf<Int, () -> Unit>()
        val gate = LatestFrameGate<Int>(
            process = { frame, done -> processed += frame; completions[frame] = done },
            release = { released += it },
        )
        gate.offer(1)
        gate.offer(2)
        gate.offer(3)
        assertEquals(listOf(1), processed)
        assertEquals(listOf(2), released)
        completions.getValue(1).invoke()
        assertEquals(listOf(1, 3), processed)
        completions.getValue(3).invoke()
        assertEquals(listOf(2, 1, 3), released)
    }

    @Test
    fun `close releases current and pending while late completion is idempotent`() {
        val released = mutableListOf<Int>()
        var completion: (() -> Unit)? = null
        val gate = LatestFrameGate<Int>({ _, done -> completion = done }, { released += it })
        gate.offer(1)
        gate.offer(2)
        gate.close()
        completion!!.invoke()
        gate.offer(3)
        assertEquals(listOf(1, 2, 3), released.sorted())
    }
}
