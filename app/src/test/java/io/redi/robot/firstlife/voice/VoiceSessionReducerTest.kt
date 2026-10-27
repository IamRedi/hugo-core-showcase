package io.redi.robot.firstlife.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionReducerTest {
    private val request = VoiceSessionRequest(VoiceSessionRequestId("request-1"), 1_000, 9_000)

    @Test
    fun `explicit start requests permission without claiming microphone ownership`() {
        val result = reduce(VoiceSessionModel.IDLE, VoiceSessionEvent.ExplicitStart(request))

        assertEquals(VoiceSessionPhase.AWAITING_PERMISSION, result.model.phase)
        assertFalse(result.model.recognizerOwned)
        assertEquals(listOf(VoiceSessionEffect.RequestMicrophonePermission(request.id)), result.effects)
    }

    @Test
    fun `permission and exact local support precede capture`() {
        var model = started()
        val permission = reduce(model, VoiceSessionEvent.PermissionResult(request.id, true, false))
        model = permission.model
        assertEquals(VoiceSessionPhase.CHECKING_SUPPORT, model.phase)
        assertEquals(listOf(VoiceSessionEffect.CheckOnDeviceSupport(request.id)), permission.effects)

        val support = reduce(model, VoiceSessionEvent.SupportResult(request.id, VoiceOnDeviceSupport.SUPPORTED))
        assertEquals(VoiceSessionPhase.LISTENING, support.model.phase)
        assertTrue(support.model.recognizerOwned)
        assertEquals(listOf(VoiceSessionEffect.StartOnDeviceCapture(request)), support.effects)
    }

    @Test
    fun `permission denial and unavailable Albanian model fail closed`() {
        val denied = reduce(started(), VoiceSessionEvent.PermissionResult(request.id, false, true))
        assertEquals(VoiceSessionBlockedReason.PERMISSION_DENIED, denied.model.blockedReason)
        assertTrue(denied.effects.isEmpty())

        val permanent = reduce(started(), VoiceSessionEvent.PermissionResult(request.id, false, false))
        assertEquals(VoiceSessionBlockedReason.PERMISSION_PERMANENTLY_DENIED, permanent.model.blockedReason)

        val checking = reduce(started(), VoiceSessionEvent.PermissionResult(request.id, true, false)).model
        val unsupported = reduce(checking, VoiceSessionEvent.SupportResult(request.id, VoiceOnDeviceSupport.ALBANIAN_MODEL_UNAVAILABLE))
        assertEquals(VoiceSessionBlockedReason.ALBANIAN_MODEL_UNAVAILABLE, unsupported.model.blockedReason)
        assertFalse(unsupported.model.recognizerOwned)

        val downloadable = reduce(checking, VoiceSessionEvent.SupportResult(request.id, VoiceOnDeviceSupport.ALBANIAN_MODEL_DOWNLOAD_REQUIRED))
        assertEquals(VoiceSessionBlockedReason.ALBANIAN_MODEL_DOWNLOAD_REQUIRED, downloadable.model.blockedReason)
    }

    @Test
    fun `results are bounded and only redacted evidence reaches terminal model`() {
        val alternatives = VoiceTranscriptCandidates.from(
            listOf("A".repeat(100), "Besa", "Arta", "ignored fourth"),
        )
        val result = reduce(listening(), VoiceSessionEvent.Results(request.id, alternatives))

        assertEquals(VoiceSessionPhase.COMPLETED, result.model.phase)
        assertFalse(result.model.recognizerOwned)
        assertEquals(3, result.model.evidence!!.alternativeCount)
        assertEquals(80, result.model.evidence!!.acceptedCodePointCount)
        assertEquals(VoiceCapturePolicy.LOCALE_TAG, result.model.evidence!!.localeTag)
        assertEquals(1, result.effects.filterIsInstance<VoiceSessionEffect.ReleaseRecognizer>().size)
        val delivered = result.effects.filterIsInstance<VoiceSessionEffect.DeliverTranscript>().single()
        assertFalse(delivered.toString().contains("A".repeat(10)))
        assertFalse(result.model.javaClass.declaredFields.any { it.name.contains("transcript", ignoreCase = true) })
    }

    @Test
    fun `blank results become no match and release exactly once`() {
        val result = reduce(
            listening(),
            VoiceSessionEvent.Results(request.id, VoiceTranscriptCandidates.from(listOf(" ", ""))),
        )
        assertEquals(VoiceSessionBlockedReason.NO_MATCH, result.model.blockedReason)
        assertEquals(1, result.effects.filterIsInstance<VoiceSessionEffect.ReleaseRecognizer>().size)
        val repeated = reduce(result.model, VoiceSessionEvent.Cancel(request.id))
        assertEquals(VoiceReductionStatus.IDEMPOTENT, repeated.status)
        assertTrue(repeated.effects.isEmpty())
    }

    @Test
    fun `cancel background and timeout release an owned recognizer once`() {
        listOf<VoiceSessionEvent>(
            VoiceSessionEvent.Cancel(request.id),
            VoiceSessionEvent.Backgrounded(request.id),
            VoiceSessionEvent.DeadlineElapsed(request.id, 9_000),
        ).forEach { event ->
            val result = reduce(listening(), event)
            assertEquals(1, result.effects.filterIsInstance<VoiceSessionEffect.ReleaseRecognizer>().size)
            assertFalse(result.model.recognizerOwned)
        }
    }

    @Test
    fun `early deadline and stale request cannot stop current capture`() {
        val listening = listening()
        val early = reduce(listening, VoiceSessionEvent.DeadlineElapsed(request.id, 8_999))
        assertEquals(VoiceReductionStatus.IGNORED_EARLY, early.status)
        assertEquals(listening, early.model)

        val stale = reduce(listening, VoiceSessionEvent.Results(VoiceSessionRequestId("old"), VoiceTranscriptCandidates.from(listOf("Redi"))))
        assertEquals(VoiceReductionStatus.IGNORED_WRONG_REQUEST, stale.status)
        assertEquals(listening, stale.model)
        assertTrue(stale.effects.isEmpty())
    }

    @Test
    fun `network recognizer error is rejected rather than treated as offline success`() {
        val result = reduce(listening(), VoiceSessionEvent.Failure(request.id, VoiceRecognizerFailure.NETWORK))
        assertEquals(VoiceSessionBlockedReason.NETWORK_ATTEMPT_REJECTED, result.model.blockedReason)
        assertNull(result.model.evidence)
    }

    @Test
    fun `system fallback requires separate review consent and permission before capture`() {
        val unavailable = reduce(
            reduce(started(), VoiceSessionEvent.PermissionResult(request.id, true, false)).model,
            VoiceSessionEvent.SupportResult(request.id, VoiceOnDeviceSupport.ALBANIAN_MODEL_UNAVAILABLE),
        ).model
        val fallback = VoiceSessionRequest(
            VoiceSessionRequestId("system-fallback"),
            10_000,
            18_000,
            VoiceRecognitionTier.SYSTEM_NETWORK_ASSISTED,
            VoiceCapturePolicy.SYSTEM_FALLBACK_LOCALE_TAG,
        )
        var result = reduce(unavailable, VoiceSessionEvent.ExplicitSystemFallbackReview(fallback))
        assertEquals(VoiceSessionPhase.AWAITING_SYSTEM_CONSENT, result.model.phase)
        assertTrue(result.effects.isEmpty())

        result = reduce(result.model, VoiceSessionEvent.SystemFallbackConsent(fallback.id, 12_000))
        assertEquals(VoiceSessionPhase.AWAITING_PERMISSION, result.model.phase)
        assertEquals(listOf(VoiceSessionEffect.RequestMicrophonePermission(fallback.id)), result.effects)
        assertEquals(12_000, result.model.activeRequest!!.startedAtMs)
        assertEquals(20_000, result.model.activeRequest!!.deadlineAtMs)

        result = reduce(result.model, VoiceSessionEvent.PermissionResult(fallback.id, true, false))
        assertEquals(VoiceSessionPhase.LISTENING, result.model.phase)
        assertEquals(listOf(VoiceSessionEffect.StartSystemCapture(result.model.activeRequest!!)), result.effects)
        assertTrue(result.model.recognizerOwned)
    }

    @Test
    fun `locale matching requires the exact Albanian locale`() {
        assertTrue("sq-AL".isAlbanianLocaleTag())
        assertTrue("sq_AL".isAlbanianLocaleTag())
        assertFalse("sq".isAlbanianLocaleTag())
        assertFalse("en-US".isAlbanianLocaleTag())
    }

    private fun started() = reduce(VoiceSessionModel.IDLE, VoiceSessionEvent.ExplicitStart(request)).model

    private fun listening(): VoiceSessionModel {
        var model = started()
        model = reduce(model, VoiceSessionEvent.PermissionResult(request.id, true, false)).model
        return reduce(model, VoiceSessionEvent.SupportResult(request.id, VoiceOnDeviceSupport.SUPPORTED)).model
    }

    private fun reduce(model: VoiceSessionModel, event: VoiceSessionEvent) = VoiceSessionReducer.reduce(model, event)
}
