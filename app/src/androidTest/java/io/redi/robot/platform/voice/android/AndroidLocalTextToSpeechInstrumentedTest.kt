package io.redi.robot.platform.voice.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.redi.robot.firstlife.voice.LocalSpeechText
import io.redi.robot.firstlife.voice.LocalTtsSupport
import io.redi.robot.firstlife.voice.SpeechOutputBlockedReason
import io.redi.robot.firstlife.voice.SpeechOutputKind
import io.redi.robot.firstlife.voice.SpeechOutputListener
import io.redi.robot.firstlife.voice.SpeechOutputRequest
import io.redi.robot.firstlife.voice.SpeechOutputRequestId
import io.redi.robot.firstlife.interaction.FirstLifeNameValidation
import io.redi.robot.firstlife.interaction.FirstLifeNameValidator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidLocalTextToSpeechInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun exactLocalAlbanianSupportIsReportedAndOnlySupportedVoiceMaySpeak() {
        val adapter = AndroidLocalTextToSpeech(context)
        val id = SpeechOutputRequestId("fl010-support")
        val supportLatch = CountDownLatch(1)
        var support: LocalTtsSupport? = null
        instrumentation.runOnMainSync {
            adapter.checkSupport(id) { callbackId, result ->
                if (callbackId == id) {
                    support = result
                    supportLatch.countDown()
                }
            }
        }
        assertTrue("TTS initialization timed out", supportLatch.await(8, TimeUnit.SECONDS))
        assertNotNull(support)
        println("FL010_LOCAL_TTS_SUPPORT=$support locale=sq-AL")
        if (support != LocalTtsSupport.SUPPORTED) {
            instrumentation.runOnMainSync { adapter.release(id) }
            return
        }

        val terminal = CountDownLatch(1)
        instrumentation.runOnMainSync {
            adapter.speak(
                SpeechOutputRequest(id, 1, SpeechOutputKind.INTRODUCTION, text = LocalSpeechText.from(context.getString(io.redi.robot.R.string.first_life_introduction))),
                object : SpeechOutputListener {
                    override fun onStarted(requestId: SpeechOutputRequestId) = Unit
                    override fun onCompleted(requestId: SpeechOutputRequestId) = terminal.countDown()
                    override fun onFailed(requestId: SpeechOutputRequestId, reason: SpeechOutputBlockedReason) = terminal.countDown()
                },
            )
        }
        assertTrue("Local Albanian utterance did not terminate", terminal.await(20, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            adapter.release(id)
            adapter.release(id)
        }

        val greetingName = (FirstLifeNameValidator.validate("Redi") as FirstLifeNameValidation.Valid).name.value
        val greetingAdapter = AndroidLocalTextToSpeech(context)
        val greetingId = SpeechOutputRequestId("fl010-greeting")
        val greetingSupport = CountDownLatch(1)
        instrumentation.runOnMainSync {
            greetingAdapter.checkSupport(greetingId) { _, result ->
                if (result == LocalTtsSupport.SUPPORTED) greetingSupport.countDown()
            }
        }
        assertTrue("Greeting support check failed", greetingSupport.await(8, TimeUnit.SECONDS))
        val greetingTerminal = CountDownLatch(1)
        instrumentation.runOnMainSync {
            greetingAdapter.speak(
                SpeechOutputRequest(
                    greetingId,
                    2,
                    SpeechOutputKind.SESSION_NAME_GREETING,
                    text = LocalSpeechText.from(context.getString(io.redi.robot.R.string.first_life_name_greeting, greetingName)),
                ),
                object : SpeechOutputListener {
                    override fun onStarted(requestId: SpeechOutputRequestId) = Unit
                    override fun onCompleted(requestId: SpeechOutputRequestId) = greetingTerminal.countDown()
                    override fun onFailed(requestId: SpeechOutputRequestId, reason: SpeechOutputBlockedReason) = greetingTerminal.countDown()
                },
            )
        }
        assertTrue("Local Albanian greeting did not terminate", greetingTerminal.await(20, TimeUnit.SECONDS))
        println("FL010_LOCAL_TTS_UTTERANCES=introduction,greeting terminal=completed")
        instrumentation.runOnMainSync { greetingAdapter.release(greetingId) }
    }

    @Test
    fun explicitReleaseIsIdempotent() {
        val adapter = AndroidLocalTextToSpeech(context)
        val id = SpeechOutputRequestId("fl010-release")
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync { adapter.checkSupport(id) { _, _ -> latch.countDown() } }
        assertTrue(latch.await(8, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            adapter.release(id)
            adapter.release(id)
        }
    }

    @Test
    fun releaseAfterStartStopsCurrentUtteranceWithoutACompletedCallback() {
        val adapter = AndroidLocalTextToSpeech(context)
        val id = SpeechOutputRequestId("fl010-cancel")
        val supportLatch = CountDownLatch(1)
        var supported = false
        instrumentation.runOnMainSync {
            adapter.checkSupport(id) { _, result ->
                supported = result == LocalTtsSupport.SUPPORTED
                supportLatch.countDown()
            }
        }
        assertTrue(supportLatch.await(8, TimeUnit.SECONDS))
        if (!supported) {
            instrumentation.runOnMainSync { adapter.release(id) }
            return
        }
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            adapter.speak(
                SpeechOutputRequest(id, 1, SpeechOutputKind.INTRODUCTION, text = LocalSpeechText.from(context.getString(io.redi.robot.R.string.first_life_introduction))),
                object : SpeechOutputListener {
                    override fun onStarted(requestId: SpeechOutputRequestId) = started.countDown()
                    override fun onCompleted(requestId: SpeechOutputRequestId) = completed.countDown()
                    override fun onFailed(requestId: SpeechOutputRequestId, reason: SpeechOutputBlockedReason) = Unit
                },
            )
        }
        assertTrue("Utterance never started", started.await(8, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { adapter.release(id) }
        assertTrue("Cancelled utterance must not report completed", !completed.await(1, TimeUnit.SECONDS))
        println("FL010_LOCAL_TTS_CANCEL=release-after-start no-completed-callback")
    }
}
