package io.redi.robot.firstlife.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionCoordinatorTest {
    @Test
    fun `explicit permission and exact callbacks open one front observation session`() {
        val port = FakePort()
        var permission: CameraSessionRequestId? = null
        val coordinator = CameraSessionCoordinator(port, { CameraSessionRequestId("camera") }, { permission = it }, {})
        assertTrue(coordinator.startExplicitSession())
        val request = coordinator.model.activeRequest!!
        coordinator.onPermissionResult(permission!!, granted = true, canAskAgain = false)
        assertEquals(listOf(request), port.opened)
        port.listener!!.onOpened(request)
        assertEquals(CameraSessionPhase.OBSERVING, coordinator.model.phase)
        assertTrue(coordinator.model.evidence!!.visiblePreview)
        assertEquals(CameraLensFacing.FRONT, coordinator.model.evidence!!.lensFacing)
        assertTrue(coordinator.model.evidence!!.previewMirrored)

        port.listener!!.onFrame(frame(request, 1))
        assertEquals(1, coordinator.model.lastFrameMetadata!!.sequence)
        assertFalse(coordinator.model.lastFrameMetadata!!.mirrored)
        assertFalse(coordinator.model.toString().contains("camera"))
    }

    @Test
    fun `denial fails closed and stale generation cannot advance current session`() {
        val port = FakePort()
        var next = 0
        val coordinator = CameraSessionCoordinator(port, { CameraSessionRequestId("camera-${++next}") }, {}, {})
        coordinator.startExplicitSession()
        val first = coordinator.model.activeRequest!!
        coordinator.onPermissionResult(first.id, false, canAskAgain = true)
        assertEquals(CameraBlockedReason.PERMISSION_DENIED, coordinator.model.blockedReason)
        assertNull(coordinator.model.activeRequest)

        coordinator.startExplicitSession()
        val second = coordinator.model.activeRequest!!
        coordinator.onPermissionResult(second.id, true, false)
        port.listener!!.onOpened(first)
        assertEquals(CameraSessionPhase.OPENING, coordinator.model.phase)
        port.listener!!.onOpened(second)
        port.listener!!.onFrame(frame(first, 99))
        assertNull(coordinator.model.lastFrameMetadata)
    }

    @Test
    fun `cancel and background close exact request once`() {
        val port = FakePort()
        val coordinator = CameraSessionCoordinator(port, { CameraSessionRequestId("camera") }, {}, {})
        coordinator.startExplicitSession()
        val request = coordinator.model.activeRequest!!
        coordinator.onPermissionResult(request.id, true, false)
        port.listener!!.onOpened(request)
        coordinator.close(background = true)
        coordinator.close(background = true)
        assertEquals(listOf(request.id), port.closed)
        assertEquals(CameraBlockedReason.BACKGROUNDED, coordinator.model.blockedReason)
    }

    @Test
    fun `person evidence is correlated bounded and cleared with camera lifecycle`() {
        val port = FakePort()
        var now = 1_100_000_000L
        val coordinator = CameraSessionCoordinator(
            port,
            { CameraSessionRequestId("camera") },
            {},
            {},
            nowElapsedNanos = { now },
        )
        coordinator.startExplicitSession()
        val request = coordinator.model.activeRequest!!
        coordinator.onPermissionResult(request.id, true, false)
        port.listener!!.onOpened(request)
        repeat(3) { index ->
            val sequence = index + 1L
            val observed = 1_000_000_000L + index * 50_000_000L
            now = observed + 10_000_000L
            port.listener!!.onPersonObservation(
                PersonDetectionObservation(
                    request.id,
                    request.generation,
                    sequence,
                    observed,
                    observed + 10_000_000L,
                    10,
                    listOf(PersonDetection(0.9f, NormalizedBoundingBox(0.3f, 0.1f, 0.7f, 0.9f))),
                ),
            )
        }
        assertEquals(PersonDirection.CENTER, coordinator.model.personEvidence!!.direction)
        assertEquals(10, coordinator.model.detectorTelemetry!!.inferenceLatencyMs)
        now = coordinator.model.personEvidence!!.expiresAtElapsedNanos + 1
        coordinator.expirePersonEvidence()
        assertEquals(PersonDirection.UNKNOWN, coordinator.model.personEvidence!!.direction)
        coordinator.close()
        assertNull(coordinator.model.personEvidence)
        assertNull(coordinator.model.detectorTelemetry)
    }

    private fun frame(request: CameraSessionRequest, sequence: Long) = CameraFrameMetadata(
        request.id, request.generation, sequence, sequence * 1_000, 640, 480, 90,
    )

    private class FakePort : CameraSessionPort {
        val opened = mutableListOf<CameraSessionRequest>()
        val closed = mutableListOf<CameraSessionRequestId>()
        var listener: CameraSessionListener? = null
        override fun open(request: CameraSessionRequest, listener: CameraSessionListener) {
            opened += request
            this.listener = listener
        }
        override fun close(requestId: CameraSessionRequestId) {
            closed += requestId
        }
    }
}
