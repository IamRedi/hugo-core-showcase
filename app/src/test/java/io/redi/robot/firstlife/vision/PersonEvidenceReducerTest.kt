package io.redi.robot.firstlife.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonEvidenceReducerTest {
    private val request = CameraSessionRequest(CameraSessionRequestId("session"), CameraGeneration(7))

    @Test
    fun `three fresh same-direction observations become usable and remain non-identifying`() {
        val reducer = PersonEvidenceReducer().also { it.beginSession(request) }
        val outputs = (1L..3L).map { sequence ->
            val observed = 1_000_000_000L + sequence * 100_000_000L
            reducer.reduce(observation(sequence, observed, detection(0.90f, 0.05f, 0.35f)), observed + 50_000_000L)!!
        }
        assertEquals(listOf(PersonDirection.UNKNOWN, PersonDirection.UNKNOWN, PersonDirection.LEFT), outputs.map { it.direction })
        assertEquals(PersonEvidenceReason.STABLE_PERSON, outputs.last().reason)
        assertEquals(-0.60f, outputs.last().horizontalCenterOffset!!, 0.0001f)
        assertTrue(outputs.last().isFreshAt(outputs.last().expiresAtElapsedNanos))
        assertEquals("CameraSessionRequestId(redacted)", outputs.last().requestId.toString())
    }

    @Test
    fun `no person low confidence and multiple people are deterministic unknowns`() {
        val reducer = PersonEvidenceReducer().also { it.beginSession(request) }
        val empty = reducer.reduce(observation(1, 1_000_000_000L), 1_010_000_000L)!!
        val low = reducer.reduce(observation(2, 1_100_000_000L, detection(0.59f, 0.3f, 0.7f)), 1_110_000_000L)!!
        val multiple = reducer.reduce(
            observation(3, 1_200_000_000L, detection(0.80f, 0.05f, 0.40f), detection(0.82f, 0.60f, 0.95f)),
            1_210_000_000L,
        )!!
        assertEquals(PersonEvidenceReason.NO_PERSON, empty.reason)
        assertEquals(PersonEvidenceReason.LOW_CONFIDENCE, low.reason)
        assertEquals(PersonEvidenceReason.MULTIPLE_PEOPLE, multiple.reason)
        assertTrue(listOf(empty, low, multiple).all { it.direction == PersonDirection.UNKNOWN })
    }

    @Test
    fun `stale out-of-order wrong-session and session-close results never advance evidence`() {
        val reducer = PersonEvidenceReducer().also { it.beginSession(request) }
        val stale = reducer.reduce(observation(1, 1_000_000_000L, detection()), 1_250_000_001L)!!
        assertEquals(PersonEvidenceReason.STALE, stale.reason)
        val first = reducer.reduce(observation(2, 1_300_000_000L, detection()), 1_310_000_000L)!!
        val outOfOrder = reducer.reduce(observation(2, 1_300_000_000L, detection()), 1_320_000_000L)!!
        assertEquals(PersonEvidenceReason.WARMING_UP, first.reason)
        assertEquals(PersonEvidenceReason.OUT_OF_ORDER, outOfOrder.reason)
        assertNull(
            reducer.reduce(
                observation(3, 1_400_000_000L, detection()).copy(generation = CameraGeneration(8)),
                1_410_000_000L,
            ),
        )
        reducer.closeSession()
        assertNull(reducer.reduce(observation(4, 1_500_000_000L, detection()), 1_510_000_000L))
    }

    @Test
    fun `alternation hysteresis gaps and expiry all fail to unknown`() {
        val reducer = PersonEvidenceReducer().also { it.beginSession(request) }
        reducer.reduce(observation(1, 1_000_000_000L, detection(0.9f, 0.1f, 0.3f)), 1_010_000_000L)
        reducer.reduce(observation(2, 1_100_000_000L, detection(0.9f, 0.7f, 0.9f)), 1_110_000_000L)
        val alternating = reducer.reduce(observation(3, 1_200_000_000L, detection(0.9f, 0.1f, 0.3f)), 1_210_000_000L)!!
        val gap = reducer.reduce(observation(4, 1_300_000_000L, detection(0.9f, 0.29f, 0.49f)), 1_310_000_000L)!!
        assertEquals(PersonDirection.UNKNOWN, alternating.direction)
        assertEquals(PersonDirection.UNKNOWN, gap.direction)
        assertEquals(PersonDirection.UNKNOWN, reducer.expire(gap, gap.expiresAtElapsedNanos + 1)!!.direction)
        assertEquals(PersonEvidenceReason.STALE, reducer.expire(gap, gap.expiresAtElapsedNanos + 1)!!.reason)
    }

    @Test
    fun `direction geometry honors every reviewed boundary`() {
        assertEquals(PersonDirection.LEFT, PersonEvidenceReducer.directionFor(0.3799f))
        assertEquals(PersonDirection.UNKNOWN, PersonEvidenceReducer.directionFor(0.38f))
        assertEquals(PersonDirection.CENTER, PersonEvidenceReducer.directionFor(0.42f))
        assertEquals(PersonDirection.CENTER, PersonEvidenceReducer.directionFor(0.58f))
        assertEquals(PersonDirection.UNKNOWN, PersonEvidenceReducer.directionFor(0.62f))
        assertEquals(PersonDirection.RIGHT, PersonEvidenceReducer.directionFor(0.6201f))
        for (step in 0..1_000) {
            val x = step / 1_000f
            assertTrue(PersonEvidenceReducer.directionFor(x) in PersonDirection.entries)
        }
    }

    @Test
    fun `latency evidence uses a bounded deterministic nearest-rank window`() {
        val window = PersonDetectorLatencyWindow()
        var snapshot = window.add(100)
        (1L..150L).forEach { snapshot = window.add(it) }
        assertEquals(PersonDetectorLatencyWindow.MAX_SAMPLES, snapshot.sampleCount)
        assertEquals(90, snapshot.medianLatencyMs)
        assertEquals(144, snapshot.p95LatencyMs)
        assertEquals(150, snapshot.maxLatencyMs)
        window.clear()
        assertEquals(7, window.add(7).medianLatencyMs)
    }

    private fun observation(sequence: Long, observed: Long, vararg detections: PersonDetection) =
        PersonDetectionObservation(
            requestId = request.id,
            generation = request.generation,
            sequence = sequence,
            observedAtElapsedNanos = observed,
            completedAtElapsedNanos = observed + 50_000_000L,
            detectorLatencyMs = 50,
            detections = detections.toList(),
        )

    private fun detection(confidence: Float = 0.9f, left: Float = 0.3f, right: Float = 0.7f) =
        PersonDetection(confidence, NormalizedBoundingBox(left, 0.1f, right, 0.9f))
}
