package io.redi.robot.platform.voice.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.redi.robot.firstlife.voice.VoiceCaptureListener
import io.redi.robot.firstlife.voice.VoiceOnDeviceSupport
import io.redi.robot.firstlife.voice.VoiceRecognizerFailure
import io.redi.robot.firstlife.voice.VoiceSessionRequest
import io.redi.robot.firstlife.voice.VoiceSessionRequestId
import io.redi.robot.firstlife.voice.VoiceTranscriptCandidates
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidOnDeviceSpeechRecognizerInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun exactAlbanianOnDeviceSupportDispositionIsReportedWithoutInventingSupport() {
        assertEquals(
            "Grant microphone permission explicitly before the FL009 device test",
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO),
        )
        val adapter = AndroidOnDeviceSpeechRecognizer(context)
        val requestId = VoiceSessionRequestId("instrumented-local-support")
        val supportLatch = CountDownLatch(1)
        var support: VoiceOnDeviceSupport? = null
        instrumentation.runOnMainSync {
            adapter.checkOnDeviceSupport(requestId) { callbackId, result ->
                assertEquals(requestId, callbackId)
                support = result
                supportLatch.countDown()
            }
        }
        assertTrue("on-device support callback timed out", supportLatch.await(5, TimeUnit.SECONDS))
        assertTrue(
            "device must return a typed exact-language disposition",
            support == VoiceOnDeviceSupport.SUPPORTED ||
                support == VoiceOnDeviceSupport.ALBANIAN_MODEL_DOWNLOAD_REQUIRED ||
                support == VoiceOnDeviceSupport.ALBANIAN_MODEL_UNAVAILABLE,
        )

        if (support != VoiceOnDeviceSupport.SUPPORTED) return

        val startedOrTerminal = CountDownLatch(1)
        val request = VoiceSessionRequest(requestId, 1, 8_001)
        instrumentation.runOnMainSync {
            adapter.startOnDeviceCapture(
                request,
                object : VoiceCaptureListener {
                    override fun onCaptureStarted(requestId: VoiceSessionRequestId) = startedOrTerminal.countDown()
                    override fun onSpeechEnded(requestId: VoiceSessionRequestId) = Unit
                    override fun onResults(requestId: VoiceSessionRequestId, candidates: VoiceTranscriptCandidates) = startedOrTerminal.countDown()
                    override fun onFailure(requestId: VoiceSessionRequestId, failure: VoiceRecognizerFailure) = startedOrTerminal.countDown()
                },
            )
        }
        assertTrue("recognizer did not start or terminate", startedOrTerminal.await(5, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            adapter.release(requestId)
            adapter.release(requestId)
        }
    }

    @Test
    fun disclosedEnglishSystemRecognizerCanStartAndReleaseWithoutRetainingAudio() {
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO),
        )
        val adapter = AndroidOnDeviceSpeechRecognizer(context)
        val requestId = VoiceSessionRequestId("instrumented-system-release")
        val startedOrTerminal = CountDownLatch(1)
        val request = VoiceSessionRequest(
            requestId,
            1,
            8_001,
            io.redi.robot.firstlife.voice.VoiceRecognitionTier.SYSTEM_NETWORK_ASSISTED,
            io.redi.robot.firstlife.voice.VoiceCapturePolicy.SYSTEM_FALLBACK_LOCALE_TAG,
        )
        instrumentation.runOnMainSync {
            adapter.startSystemCapture(
                request,
                object : VoiceCaptureListener {
                    override fun onCaptureStarted(requestId: VoiceSessionRequestId) = startedOrTerminal.countDown()
                    override fun onSpeechEnded(requestId: VoiceSessionRequestId) = Unit
                    override fun onResults(requestId: VoiceSessionRequestId, candidates: VoiceTranscriptCandidates) = startedOrTerminal.countDown()
                    override fun onFailure(requestId: VoiceSessionRequestId, failure: VoiceRecognizerFailure) = startedOrTerminal.countDown()
                },
            )
        }
        assertTrue(startedOrTerminal.await(5, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            adapter.release(requestId)
            adapter.release(requestId)
        }
    }
}
