package io.redi.robot.firstlife.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionCoordinatorTest {
    @Test
    fun `coordinator invokes one capture and releases after ephemeral transcript delivery`() {
        val port = FakePort()
        var permissionRequest: VoiceSessionRequestId? = null
        val delivered = mutableListOf<String>()
        val models = mutableListOf<VoiceSessionModel>()
        val coordinator = VoiceSessionCoordinator(
            port = port,
            nowMs = { 100L },
            requestIdFactory = { VoiceSessionRequestId("current") },
            onPermissionRequested = { permissionRequest = it },
            onTranscript = { delivered += it.value },
            onModelChanged = { models += it },
        )

        coordinator.startExplicitSession()
        coordinator.onPermissionResult(permissionRequest!!, granted = true, canAskAgain = false)
        assertEquals(listOf(VoiceSessionRequestId("current")), port.supportChecks)
        port.supportCallback!!.invoke(VoiceSessionRequestId("current"), VoiceOnDeviceSupport.SUPPORTED)
        assertEquals(1, port.starts.size)

        port.listener!!.onResults(VoiceSessionRequestId("current"), VoiceTranscriptCandidates.from(listOf("Redi")))
        assertEquals(listOf("Redi"), delivered)
        assertEquals(listOf(VoiceSessionRequestId("current")), port.releases)
        assertEquals(VoiceSessionPhase.COMPLETED, coordinator.model.phase)
        assertFalse(models.toString().contains("Redi"))
    }

    @Test
    fun `stale adapter callbacks cannot advance a newer session`() {
        val port = FakePort()
        var nextId = 0
        val coordinator = VoiceSessionCoordinator(
            port,
            nowMs = { 10L },
            requestIdFactory = { VoiceSessionRequestId("request-${++nextId}") },
            onPermissionRequested = {},
            onTranscript = {},
            onModelChanged = {},
        )
        coordinator.startExplicitSession()
        val currentId = coordinator.model.activeRequest!!.id
        coordinator.onResults(VoiceSessionRequestId("stale"), VoiceTranscriptCandidates.from(listOf("Private")))

        assertEquals(currentId, coordinator.model.activeRequest!!.id)
        assertEquals(VoiceSessionPhase.AWAITING_PERMISSION, coordinator.model.phase)
        assertTrue(port.releases.isEmpty())
    }

    private class FakePort : VoiceCapturePort {
        val supportChecks = mutableListOf<VoiceSessionRequestId>()
        var supportCallback: ((VoiceSessionRequestId, VoiceOnDeviceSupport) -> Unit)? = null
        val starts = mutableListOf<VoiceSessionRequest>()
        val releases = mutableListOf<VoiceSessionRequestId>()
        var listener: VoiceCaptureListener? = null

        override fun checkOnDeviceSupport(
            requestId: VoiceSessionRequestId,
            callback: (VoiceSessionRequestId, VoiceOnDeviceSupport) -> Unit,
        ) {
            supportChecks += requestId
            supportCallback = callback
        }

        override fun startOnDeviceCapture(request: VoiceSessionRequest, listener: VoiceCaptureListener) {
            starts += request
            this.listener = listener
        }

        override fun startSystemCapture(request: VoiceSessionRequest, listener: VoiceCaptureListener) {
            starts += request
            this.listener = listener
        }

        override fun release(requestId: VoiceSessionRequestId) {
            releases += requestId
        }
    }
}
