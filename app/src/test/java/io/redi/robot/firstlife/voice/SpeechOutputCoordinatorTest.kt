package io.redi.robot.firstlife.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechOutputCoordinatorTest {
    @Test
    fun `exact support queues one utterance and real callbacks project speaking then completed`() {
        val port = FakePort()
        val states = mutableListOf<SpeechOutputPhase>()
        val coordinator = coordinator(port, states)

        assertTrue(coordinator.speak(SpeechOutputKind.INTRODUCTION, LocalSpeechText.from("Përshëndetje.")))
        val request = coordinator.model.activeRequest!!
        port.supportCallback!!.invoke(request.id, LocalTtsSupport.SUPPORTED)
        assertEquals(listOf(request), port.spoken)
        assertEquals(SpeechOutputPhase.QUEUED, coordinator.model.phase)

        port.listener!!.onStarted(request.id)
        assertEquals(SpeechOutputPhase.SPEAKING, coordinator.model.phase)
        port.listener!!.onCompleted(request.id)

        assertEquals(SpeechOutputPhase.COMPLETED, coordinator.model.phase)
        assertEquals(SpeechOutputKind.INTRODUCTION, coordinator.model.evidence!!.kind)
        assertEquals(listOf(request.id), port.releases)
        assertTrue(states.contains(SpeechOutputPhase.SPEAKING))
        assertFalse(coordinator.model.toString().contains("Përshëndetje"))
    }

    @Test
    fun `missing Albanian data fails closed without speaking`() {
        val port = FakePort()
        val coordinator = coordinator(port)
        coordinator.speak(SpeechOutputKind.INTRODUCTION, LocalSpeechText.from("Përshëndetje."))
        val id = coordinator.model.activeRequest!!.id

        port.supportCallback!!.invoke(id, LocalTtsSupport.VOICE_DATA_MISSING)

        assertEquals(SpeechOutputPhase.BLOCKED, coordinator.model.phase)
        assertEquals(SpeechOutputBlockedReason.VOICE_DATA_MISSING, coordinator.model.blockedReason)
        assertTrue(port.spoken.isEmpty())
        assertEquals(listOf(id), port.releases)
    }

    @Test
    fun `cancel and background release exactly once and stale callbacks cannot advance`() {
        val port = FakePort()
        val coordinator = coordinator(port)
        coordinator.speak(SpeechOutputKind.INTRODUCTION, LocalSpeechText.from("Përshëndetje."))
        val old = coordinator.model.activeRequest!!.id
        coordinator.cancel(background = true)
        coordinator.cancel(background = true)
        coordinator.onStarted(old)

        assertEquals(SpeechOutputBlockedReason.BACKGROUNDED, coordinator.model.blockedReason)
        assertEquals(listOf(old), port.releases)
    }

    @Test
    fun `bounded text and request diagnostics redact session name`() {
        val greeting = LocalSpeechText.from("Gëzohem që të njoh, Redi.")
        val request = SpeechOutputRequest(SpeechOutputRequestId("private-id"), 1, SpeechOutputKind.SESSION_NAME_GREETING, text = greeting)
        assertFalse(greeting.toString().contains("Redi"))
        assertFalse(request.toString().contains("Redi"))
        assertFalse(request.id.toString().contains("private-id"))
    }

    private fun coordinator(port: FakePort, states: MutableList<SpeechOutputPhase> = mutableListOf()) =
        SpeechOutputCoordinator(
            port,
            requestIdFactory = { SpeechOutputRequestId("request") },
            onModelChanged = { states += it.phase },
        )

    private class FakePort : LocalSpeechOutputPort {
        var supportCallback: ((SpeechOutputRequestId, LocalTtsSupport) -> Unit)? = null
        val spoken = mutableListOf<SpeechOutputRequest>()
        val releases = mutableListOf<SpeechOutputRequestId>()
        var listener: SpeechOutputListener? = null

        override fun checkSupport(requestId: SpeechOutputRequestId, callback: (SpeechOutputRequestId, LocalTtsSupport) -> Unit) {
            supportCallback = callback
        }
        override fun speak(request: SpeechOutputRequest, listener: SpeechOutputListener) {
            spoken += request
            this.listener = listener
        }
        override fun release(requestId: SpeechOutputRequestId) {
            releases += requestId
        }
    }
}
