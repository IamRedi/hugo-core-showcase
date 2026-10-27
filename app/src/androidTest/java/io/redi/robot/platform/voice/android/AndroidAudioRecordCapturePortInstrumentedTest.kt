package io.redi.robot.platform.voice.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.redi.robot.MainActivity
import io.redi.robot.voice.capture.AudioCaptureConfiguration
import io.redi.robot.voice.capture.AudioCaptureEvent
import io.redi.robot.voice.capture.AudioCaptureStartDisposition
import io.redi.robot.voice.capture.AudioCaptureStopDisposition
import io.redi.robot.voice.capture.AudioCaptureStopReason
import io.redi.robot.voice.capture.ExplicitAudioCaptureRequest
import io.redi.robot.voice.session.AudioMonotonicTimeMs
import io.redi.robot.voice.session.AudioSessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidAudioRecordCapturePortInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun permissionUnavailableFailsBeforeAllocatingAudioRecord() {
        val port = AndroidAudioRecordCapturePort(context, permissionChecker = { false })
        val now = SystemClock.elapsedRealtime()
        val result = port.start(request(now, now + 500)) { error("denied capture must emit no event") }
        assertEquals(AudioCaptureStartDisposition.PERMISSION_UNAVAILABLE, result.disposition)
        assertFalse(result.microphoneOwned)
        assertFalse(port.snapshot().active)
    }

    @Test
    fun boundedExplicitCaptureTimesOutReleasesAndRetainsNoAudio() {
        assertEquals(
            "Grant microphone permission explicitly before the H048 device test",
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO),
        )
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            val port = AndroidAudioRecordCapturePort(context)
            val now = SystemClock.elapsedRealtime()
            val events = Collections.synchronizedList(mutableListOf<AudioCaptureEvent>())
            val terminal = CountDownLatch(1)
            val result = port.start(request(now, now + CAPTURE_WINDOW_MS)) { event ->
                events += event
                if (event is AudioCaptureEvent.Stopped || event is AudioCaptureEvent.Failed) terminal.countDown()
            }
            assertEquals(AudioCaptureStartDisposition.ACCEPTED, result.disposition)
            assertTrue(result.microphoneOwned)
            assertTrue("bounded capture did not terminate", terminal.await(5, TimeUnit.SECONDS))

            val snapshot = events.toList()
            assertTrue("AudioRecord never reported started: $snapshot", snapshot.any { it is AudioCaptureEvent.Started })
            val failure = snapshot.filterIsInstance<AudioCaptureEvent.Failed>().singleOrNull()
            assertEquals("AudioRecord capture failed closed: $failure", null, failure)
            val stopped = snapshot.filterIsInstance<AudioCaptureEvent.Stopped>().single()
            assertEquals(AudioCaptureStopReason.DEADLINE_REACHED, stopped.reason)
            assertTrue("no PCM frame was processed", stopped.evidence.framesProcessed > 0)
            assertEquals(stopped.evidence.framesProcessed, stopped.evidence.bufferReleaseCount)
            assertTrue(stopped.evidence.samplesProcessed > 0)
            assertNotNull(stopped.evidence.firstFrameLatencyMs)
            assertNotNull(stopped.evidence.maximumVadDecisionLatencyMs)
            assertTrue(stopped.evidence.maximumVadDecisionLatencyMs!! < 1_000L)
            assertFalse(stopped.evidence.rawAudioRetained)
            assertFalse(stopped.evidence.audioUploaded)
            assertFalse(port.snapshot().active)
            assertFalse(port.snapshot().microphoneOwned)
            assertEquals(AudioCaptureStopDisposition.NOT_ACTIVE, port.stop(SESSION, AudioCaptureStopReason.CANCELLED))
            println(
                "H048_DEVICE_CAPTURE=bounded-explicit frames=${stopped.evidence.framesProcessed} " +
                    "firstFrameMs=${stopped.evidence.firstFrameLatencyMs} " +
                    "maxVadMs=${stopped.evidence.maximumVadDecisionLatencyMs} " +
                    "transitions=${stopped.evidence.voiceActivityTransitions} rawRetained=false uploaded=false released=true",
            )
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun request(startedAt: Long, deadlineAt: Long) = ExplicitAudioCaptureRequest(
        SESSION,
        AudioMonotonicTimeMs(startedAt),
        AudioMonotonicTimeMs(deadlineAt),
        AudioCaptureConfiguration(maximumDurationMs = 1_500L),
    )

    companion object {
        private val SESSION = AudioSessionId.fromUuid(UUID.fromString("00000000-0000-0000-0000-000000000048"))
        private const val CAPTURE_WINDOW_MS = 800L
    }
}
