package io.redi.robot.voice.capture

import io.redi.robot.voice.session.AudioMonotonicTimeMs
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Bounded PCM16 frame. Samples are copied on entry and zeroed permanently on [close]. */
class EphemeralPcm16Frame private constructor(
    val sequence: Long,
    val capturedAt: AudioMonotonicTimeMs,
    private val samples: ShortArray,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val sampleCount: Int get() = samples.size
    val isReleased: Boolean get() = closed.get()

    fun sampleAt(index: Int): Short {
        check(!closed.get()) { "audio frame has been released" }
        return samples[index]
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) samples.fill(0)
    }

    companion object {
        const val MAX_SAMPLES = 1_920

        fun copyOf(
            sequence: Long,
            capturedAt: AudioMonotonicTimeMs,
            source: ShortArray,
            validSamples: Int,
        ): EphemeralPcm16Frame {
            require(sequence > 0L)
            require(validSamples in 1..minOf(source.size, MAX_SAMPLES))
            return EphemeralPcm16Frame(sequence, capturedAt, source.copyOf(validSamples))
        }
    }
}

data class LocalVadConfiguration(
    val minimumAverageAbsoluteAmplitude: Int = 900,
    val voicedFramesToStart: Int = 2,
    val quietFramesToEnd: Int = 5,
) {
    init {
        require(minimumAverageAbsoluteAmplitude in 1..32_767)
        require(voicedFramesToStart in 1..20)
        require(quietFramesToEnd in 1..50)
    }
}

enum class LocalVadTransition {
    NONE,
    VOICE_STARTED,
    VOICE_ENDED,
}

data class LocalVadObservation(
    val frameSequence: Long,
    val frameCapturedAt: AudioMonotonicTimeMs,
    val evaluatedAt: AudioMonotonicTimeMs,
    val decisionLatencyMs: Long,
    val voiceActive: Boolean,
    val transition: LocalVadTransition,
) {
    init {
        require(frameSequence > 0L)
        require(evaluatedAt.value >= frameCapturedAt.value)
        require(decisionLatencyMs == evaluatedAt.value - frameCapturedAt.value)
        require((transition == LocalVadTransition.VOICE_STARTED) implies voiceActive)
        require((transition == LocalVadTransition.VOICE_ENDED) implies !voiceActive)
    }
}

interface LocalVoiceActivityDetector {
    fun evaluate(frame: EphemeralPcm16Frame, evaluatedAt: AudioMonotonicTimeMs): LocalVadObservation

    fun reset()
}

/** Deterministic local energy gate. It classifies boundaries only and retains no PCM samples. */
class ThresholdLocalVoiceActivityDetector(
    private val configuration: LocalVadConfiguration = LocalVadConfiguration(),
) : LocalVoiceActivityDetector {
    private var voiceActive = false
    private var consecutiveVoiced = 0
    private var consecutiveQuiet = 0
    private var lastSequence = 0L

    override fun evaluate(
        frame: EphemeralPcm16Frame,
        evaluatedAt: AudioMonotonicTimeMs,
    ): LocalVadObservation {
        check(!frame.isReleased) { "released frames cannot be evaluated" }
        require(frame.sequence > lastSequence) { "frame sequence must increase" }
        require(evaluatedAt.value >= frame.capturedAt.value)
        lastSequence = frame.sequence

        var sum = 0L
        repeat(frame.sampleCount) { sum += abs(frame.sampleAt(it).toInt()).toLong() }
        val voiced = sum / frame.sampleCount >= configuration.minimumAverageAbsoluteAmplitude
        var transition = LocalVadTransition.NONE
        if (voiced) {
            consecutiveVoiced++
            consecutiveQuiet = 0
            if (!voiceActive && consecutiveVoiced >= configuration.voicedFramesToStart) {
                voiceActive = true
                transition = LocalVadTransition.VOICE_STARTED
            }
        } else {
            consecutiveQuiet++
            consecutiveVoiced = 0
            if (voiceActive && consecutiveQuiet >= configuration.quietFramesToEnd) {
                voiceActive = false
                transition = LocalVadTransition.VOICE_ENDED
            }
        }
        return LocalVadObservation(
            frame.sequence,
            frame.capturedAt,
            evaluatedAt,
            evaluatedAt.value - frame.capturedAt.value,
            voiceActive,
            transition,
        )
    }

    override fun reset() {
        voiceActive = false
        consecutiveVoiced = 0
        consecutiveQuiet = 0
        lastSequence = 0L
    }
}

/** Always closes/zeroes the frame, including detector or listener failure paths. */
class EphemeralVadProcessor(
    private val detector: LocalVoiceActivityDetector,
) {
    fun process(
        frame: EphemeralPcm16Frame,
        evaluatedAt: AudioMonotonicTimeMs,
    ): LocalVadObservation = frame.use { detector.evaluate(it, evaluatedAt) }

    fun reset() = detector.reset()
}

private infix fun Boolean.implies(consequent: Boolean): Boolean = !this || consequent
