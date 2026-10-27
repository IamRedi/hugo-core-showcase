package io.redi.robot.platform.voice.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.redi.robot.voice.capture.AudioCaptureEvidence
import io.redi.robot.voice.capture.AudioCaptureEvent
import io.redi.robot.voice.capture.AudioCaptureFailure
import io.redi.robot.voice.capture.AudioCaptureListener
import io.redi.robot.voice.capture.AudioCapturePortSnapshot
import io.redi.robot.voice.capture.AudioCaptureStartDisposition
import io.redi.robot.voice.capture.AudioCaptureStartResult
import io.redi.robot.voice.capture.AudioCaptureStopDisposition
import io.redi.robot.voice.capture.AudioCaptureStopReason
import io.redi.robot.voice.capture.EphemeralPcm16Frame
import io.redi.robot.voice.capture.EphemeralVadProcessor
import io.redi.robot.voice.capture.ExplicitAudioCapturePort
import io.redi.robot.voice.capture.ExplicitAudioCaptureRequest
import io.redi.robot.voice.capture.LocalVadTransition
import io.redi.robot.voice.capture.LocalVoiceActivityDetector
import io.redi.robot.voice.capture.ThresholdLocalVoiceActivityDetector
import io.redi.robot.voice.session.AudioMonotonicTimeMs
import io.redi.robot.voice.session.AudioSessionId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Android-only AudioRecord wrapper. It owns no file, network, transcript, or long-lived PCM store. */
class AndroidAudioRecordCapturePort(
    context: Context,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val permissionChecker: () -> Boolean = {
        ContextCompat.checkSelfPermission(context.applicationContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    },
    private val detectorFactory: () -> LocalVoiceActivityDetector = { ThresholdLocalVoiceActivityDetector() },
) : ExplicitAudioCapturePort {
    private val lock = Any()
    private var active: ActiveCapture? = null

    override fun start(
        request: ExplicitAudioCaptureRequest,
        listener: AudioCaptureListener,
    ): AudioCaptureStartResult {
        if (!permissionChecker()) return result(AudioCaptureStartDisposition.PERMISSION_UNAVAILABLE)
        val now = clock()
        if (now < request.requestedAt.value || now >= request.deadlineAt.value) {
            return result(AudioCaptureStartDisposition.INVALID_REQUEST)
        }

        val capture = synchronized(lock) {
            if (active != null) return result(AudioCaptureStartDisposition.BUSY)
            val audioRecord = createAudioRecord(request) ?: return result(AudioCaptureStartDisposition.AUDIO_UNAVAILABLE)
            ActiveCapture(request, listener, audioRecord, EphemeralVadProcessor(detectorFactory())).also { active = it }
        }
        return runCatching {
            capture.worker = thread(
                start = true,
                isDaemon = true,
                name = "hugo-explicit-audio-capture",
            ) { runCapture(capture) }
            AudioCaptureStartResult(AudioCaptureStartDisposition.ACCEPTED, microphoneOwned = true)
        }.getOrElse {
            synchronized(lock) { if (active === capture) active = null }
            capture.releaseOnce()
            result(AudioCaptureStartDisposition.AUDIO_UNAVAILABLE)
        }
    }

    override fun stop(
        sessionId: AudioSessionId,
        reason: AudioCaptureStopReason,
    ): AudioCaptureStopDisposition {
        val capture = synchronized(lock) { active } ?: return AudioCaptureStopDisposition.NOT_ACTIVE
        if (capture.request.sessionId != sessionId) return AudioCaptureStopDisposition.WRONG_SESSION
        if (!capture.stopReason.compareAndSet(null, reason)) return AudioCaptureStopDisposition.ALREADY_STOPPING
        runCatching {
            if (capture.audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) capture.audioRecord.stop()
        }
        return AudioCaptureStopDisposition.REQUESTED
    }

    override fun snapshot(): AudioCapturePortSnapshot = synchronized(lock) {
        val capture = active
        AudioCapturePortSnapshot(
            active = capture != null,
            stopping = capture?.stopReason?.get() != null,
            microphoneOwned = capture != null && !capture.released.get(),
        )
    }

    private fun createAudioRecord(request: ExplicitAudioCaptureRequest): AudioRecord? = runCatching {
        val config = request.configuration
        val minimum = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) return null
        val bufferBytes = maxOf(minimum, config.samplesPerFrame * PCM16_BYTES_PER_SAMPLE * BUFFERED_FRAMES)
        val audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(config.sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (audioRecord.state == AudioRecord.STATE_INITIALIZED) {
            audioRecord
        } else {
            audioRecord.release()
            null
        }
    }.getOrNull()

    private fun runCapture(capture: ActiveCapture) {
        var frames = 0L
        var samples = 0L
        var releases = 0L
        var transitions = 0
        var firstFrameLatency: Long? = null
        var maximumVadLatency: Long? = null
        var failure: AudioCaptureFailure? = null
        val buffer = ShortArray(capture.request.configuration.samplesPerFrame)
        try {
            capture.audioRecord.startRecording()
            if (capture.audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                failure = AudioCaptureFailure.START_FAILED
                return
            }
            val startedAt = now()
            if (!capture.emit(
                    AudioCaptureEvent.Started(
                        capture.request.sessionId,
                        startedAt,
                        (startedAt.value - capture.request.requestedAt.value).coerceAtLeast(0L),
                    ),
                )
            ) {
                failure = AudioCaptureFailure.CALLBACK_FAILED
                return
            }

            while (capture.stopReason.get() == null) {
                if (!permissionChecker()) {
                    failure = AudioCaptureFailure.PERMISSION_REVOKED
                    break
                }
                if (clock() >= capture.request.deadlineAt.value) {
                    capture.stopReason.compareAndSet(null, AudioCaptureStopReason.DEADLINE_REACHED)
                    break
                }
                val count = capture.audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) {
                    if (capture.stopReason.get() == null) failure = AudioCaptureFailure.READ_FAILED
                    break
                }
                val capturedAt = now()
                val frame = EphemeralPcm16Frame.copyOf(++frames, capturedAt, buffer, count)
                samples += count
                try {
                    val observation = capture.processor.process(frame, now())
                    firstFrameLatency = firstFrameLatency ?: (capturedAt.value - capture.request.requestedAt.value).coerceAtLeast(0L)
                    maximumVadLatency = maxOf(maximumVadLatency ?: 0L, observation.decisionLatencyMs)
                    if (observation.transition != LocalVadTransition.NONE) {
                        transitions++
                        if (!capture.emit(AudioCaptureEvent.VoiceActivity(capture.request.sessionId, now(), observation))) {
                            failure = AudioCaptureFailure.CALLBACK_FAILED
                            break
                        }
                    }
                } finally {
                    frame.close()
                    releases++
                    buffer.fill(0)
                }
            }
        } catch (_: SecurityException) {
            failure = AudioCaptureFailure.PERMISSION_REVOKED
        } catch (_: IllegalStateException) {
            if (capture.stopReason.get() == null) failure = AudioCaptureFailure.START_FAILED
        } catch (_: Throwable) {
            failure = AudioCaptureFailure.UNKNOWN_FAILED
        } finally {
            buffer.fill(0)
            capture.processor.reset()
            capture.releaseOnce()
            synchronized(lock) { if (active === capture) active = null }
            val evidence = AudioCaptureEvidence(
                frames,
                samples,
                releases,
                firstFrameLatency,
                maximumVadLatency,
                transitions,
            )
            val terminalAt = now()
            if (failure == null) {
                capture.emit(
                    AudioCaptureEvent.Stopped(
                        capture.request.sessionId,
                        terminalAt,
                        capture.stopReason.get() ?: AudioCaptureStopReason.CANCELLED,
                        evidence,
                    ),
                )
            } else {
                capture.emit(AudioCaptureEvent.Failed(capture.request.sessionId, terminalAt, failure!!, evidence))
            }
        }
    }

    private fun now(): AudioMonotonicTimeMs = AudioMonotonicTimeMs(clock().coerceAtLeast(0L))

    private fun result(disposition: AudioCaptureStartDisposition) =
        AudioCaptureStartResult(disposition, microphoneOwned = false)

    private class ActiveCapture(
        val request: ExplicitAudioCaptureRequest,
        val listener: AudioCaptureListener,
        val audioRecord: AudioRecord,
        val processor: EphemeralVadProcessor,
    ) {
        val stopReason = AtomicReference<AudioCaptureStopReason?>(null)
        val released = AtomicBoolean(false)
        var worker: Thread? = null

        fun emit(event: AudioCaptureEvent): Boolean = runCatching { listener.onEvent(event) }.isSuccess

        fun releaseOnce() {
            if (!released.compareAndSet(false, true)) return
            runCatching {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop()
            }
            runCatching { audioRecord.release() }
        }
    }

    companion object {
        private const val PCM16_BYTES_PER_SAMPLE = 2
        private const val BUFFERED_FRAMES = 4
    }
}
