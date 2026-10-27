package io.redi.robot.device.phone.sensors

import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.context.api.ContextConfidence
import io.redi.robot.context.api.ContextKey
import io.redi.robot.context.api.ContextObservation
import io.redi.robot.context.api.ContextObservationClaim
import io.redi.robot.context.api.ContextObservationId
import io.redi.robot.context.api.ContextObservationResult
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextSourceType
import io.redi.robot.context.api.ContextValue
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Lifecycle owner for ephemeral raw phone-sensor observations. It performs no fusion, navigation,
 * position, velocity, heading, activity recognition, safety decision, or capability execution.
 */
class PhoneSensorDeviceAdapter(
    private val backend: PhoneSensorBackend,
    private val clock: PhoneSensorClock,
) : AutoCloseable {
    private var generation: PhoneSensorGeneration? = null
    private var lifecycle: PhoneSensorAdapterLifecycle = PhoneSensorAdapterLifecycle.STOPPED
    private var request: PhoneSensorSessionRequest? = null
    private var sink: PhoneSensorObservationSink? = null
    private var registration: PhoneSensorRegistration? = null
    private var activeKinds: Set<PhoneSensorKind> = emptySet()
    private var unavailableKinds: Set<PhoneSensorKind> = emptySet()
    private val lastSequence = mutableMapOf<PhoneSensorKind, Long>()
    private val lastObservedAt = mutableMapOf<PhoneSensorKind, MonotonicTimestampMs>()
    private var emittedObservationCount: Long = 0L
    private var failure: PhoneSensorError? = null

    @Synchronized
    fun start(
        request: PhoneSensorSessionRequest,
        sink: PhoneSensorObservationSink,
    ): PhoneSensorResult<PhoneSensorStartReport> {
        val current = generation
        if (current != null && request.generation.value <= current.value) {
            return phoneSensorFailure(PhoneSensorError.StaleGeneration)
        }
        if (failure == PhoneSensorError.ResourceReleaseFailure) {
            return phoneSensorFailure(PhoneSensorError.ResourceReleaseFailure)
        }
        if (!releaseRegistration()) {
            lifecycle = PhoneSensorAdapterLifecycle.FAILED
            this.request = null
            this.sink = null
            activeKinds = emptySet()
            unavailableKinds = emptySet()
            lastSequence.clear()
            lastObservedAt.clear()
            emittedObservationCount = 0L
            failure = PhoneSensorError.ResourceReleaseFailure
            return phoneSensorFailure(PhoneSensorError.ResourceReleaseFailure)
        }
        generation = request.generation
        lifecycle = PhoneSensorAdapterLifecycle.STARTING
        this.request = request
        this.sink = sink
        activeKinds = emptySet()
        unavailableKinds = emptySet()
        lastSequence.clear()
        lastObservedAt.clear()
        emittedObservationCount = 0L
        failure = null

        val opened = runCatching {
            backend.open(request.generation, request.requestedKinds, ::onSample)
        }.getOrElse {
            return failStart(PhoneSensorError.BackendFailure(PhoneSensorBackendFailure.PLATFORM_UNAVAILABLE))
        }
        return when (opened) {
            is PhoneSensorBackendResult.Failed -> failStart(PhoneSensorError.BackendFailure(opened.reason))
            is PhoneSensorBackendResult.Opened -> {
                if (!request.requestedKinds.containsAll(opened.session.activeKinds) ||
                    !request.requestedKinds.containsAll(opened.session.unavailableKinds) ||
                    opened.session.activeKinds + opened.session.unavailableKinds != request.requestedKinds
                ) {
                    registration = opened.session.registration
                    failStart(PhoneSensorError.BackendFailure(PhoneSensorBackendFailure.REGISTRATION_FAILED))
                } else {
                    registration = opened.session.registration
                    activeKinds = opened.session.activeKinds.toSet()
                    unavailableKinds = opened.session.unavailableKinds.toSet()
                    lifecycle = PhoneSensorAdapterLifecycle.ACTIVE
                    PhoneSensorResult.Success(PhoneSensorStartReport(snapshot()))
                }
            }
        }
    }

    @Synchronized
    fun onSample(
        sampleGeneration: PhoneSensorGeneration,
        sample: PhoneSensorSample,
    ): PhoneSensorSampleDisposition {
        val activeRequest = request ?: return PhoneSensorSampleDisposition.Ignored(
            PhoneSensorIgnoredReason.NO_ACTIVE_SESSION,
        )
        if (lifecycle != PhoneSensorAdapterLifecycle.ACTIVE) {
            return PhoneSensorSampleDisposition.Ignored(PhoneSensorIgnoredReason.NO_ACTIVE_SESSION)
        }
        if (sampleGeneration != generation) {
            return PhoneSensorSampleDisposition.Ignored(PhoneSensorIgnoredReason.STALE_GENERATION)
        }
        if (sample.kind !in activeKinds) {
            return PhoneSensorSampleDisposition.Ignored(PhoneSensorIgnoredReason.SENSOR_NOT_ACTIVE)
        }
        val previousSequence = lastSequence[sample.kind]
        val previousTime = lastObservedAt[sample.kind]
        if ((previousSequence != null && sample.sequence <= previousSequence) ||
            (previousTime != null && sample.observedAt.value < previousTime.value)
        ) {
            return PhoneSensorSampleDisposition.Ignored(PhoneSensorIgnoredReason.OUT_OF_ORDER)
        }
        val now = clock.now()
        if (sample.observedAt.value > now.value) {
            return PhoneSensorSampleDisposition.Ignored(PhoneSensorIgnoredReason.FUTURE_SAMPLE)
        }
        val expiresAtValue = runCatching {
            Math.addExact(sample.observedAt.value, activeRequest.freshnessWindowMs)
        }.getOrElse {
            failActive(PhoneSensorError.TimestampOverflow)
            return PhoneSensorSampleDisposition.Ignored(PhoneSensorIgnoredReason.STALE_SAMPLE)
        }
        if (now.value >= expiresAtValue) {
            return PhoneSensorSampleDisposition.Ignored(PhoneSensorIgnoredReason.STALE_SAMPLE)
        }
        val expiresAt = monotonic(expiresAtValue)
        val observations = project(activeRequest, sample, expiresAt)
        val activeSink = sink ?: return PhoneSensorSampleDisposition.Ignored(
            PhoneSensorIgnoredReason.NO_ACTIVE_SESSION,
        )
        try {
            activeSink.onObservations(observations)
        } catch (_: RuntimeException) {
            failActive(PhoneSensorError.ObservationSinkFailure)
            return PhoneSensorSampleDisposition.Ignored(PhoneSensorIgnoredReason.NO_ACTIVE_SESSION)
        }
        if (lifecycle != PhoneSensorAdapterLifecycle.ACTIVE || sampleGeneration != generation) {
            return PhoneSensorSampleDisposition.Ignored(PhoneSensorIgnoredReason.NO_ACTIVE_SESSION)
        }
        lastSequence[sample.kind] = sample.sequence
        lastObservedAt[sample.kind] = sample.observedAt
        emittedObservationCount += observations.size
        return PhoneSensorSampleDisposition.Emitted(observations.size)
    }

    @Synchronized
    fun stop(stopGeneration: PhoneSensorGeneration): PhoneSensorResult<PhoneSensorAdapterSnapshot> {
        val current = generation ?: return PhoneSensorResult.Success(snapshot())
        if (stopGeneration.value < current.value) return phoneSensorFailure(PhoneSensorError.StaleGeneration)
        if (stopGeneration.value > current.value) return phoneSensorFailure(PhoneSensorError.FutureGeneration)
        if (failure == PhoneSensorError.ResourceReleaseFailure) {
            return phoneSensorFailure(PhoneSensorError.ResourceReleaseFailure)
        }
        val released = releaseRegistration()
        lifecycle = PhoneSensorAdapterLifecycle.STOPPED
        request = null
        sink = null
        activeKinds = emptySet()
        unavailableKinds = emptySet()
        lastSequence.clear()
        lastObservedAt.clear()
        failure = if (released) null else PhoneSensorError.ResourceReleaseFailure
        return if (released) {
            PhoneSensorResult.Success(snapshot())
        } else {
            lifecycle = PhoneSensorAdapterLifecycle.FAILED
            phoneSensorFailure(PhoneSensorError.ResourceReleaseFailure)
        }
    }

    @Synchronized
    fun snapshot(): PhoneSensorAdapterSnapshot = PhoneSensorAdapterSnapshot(
        generation = generation,
        lifecycle = lifecycle,
        activeKinds = activeKinds,
        unavailableKinds = unavailableKinds,
        lastObservedAt = lastObservedAt,
        emittedObservationCount = emittedObservationCount,
        failure = failure,
    )

    @Synchronized
    override fun close() {
        if (failure == PhoneSensorError.ResourceReleaseFailure && registration == null) return
        val released = releaseRegistration()
        lifecycle = if (released) PhoneSensorAdapterLifecycle.STOPPED else PhoneSensorAdapterLifecycle.FAILED
        request = null
        sink = null
        activeKinds = emptySet()
        unavailableKinds = emptySet()
        lastSequence.clear()
        lastObservedAt.clear()
        failure = if (released) null else PhoneSensorError.ResourceReleaseFailure
    }

    private fun project(
        request: PhoneSensorSessionRequest,
        sample: PhoneSensorSample,
        expiresAt: MonotonicTimestampMs,
    ): List<ContextObservation> {
        val fields = when (sample.kind) {
            PhoneSensorKind.ACCELEROMETER -> listOf(
                ACCELERATION_X to sample.values[0],
                ACCELERATION_Y to sample.values[1],
                ACCELERATION_Z to sample.values[2],
            )
            PhoneSensorKind.AMBIENT_LIGHT -> listOf(AMBIENT_LIGHT to sample.values.single())
            PhoneSensorKind.PROXIMITY -> listOf(PROXIMITY to sample.values.single())
        }
        return fields.map { (key, rawValue) ->
            observationValue(
                ContextObservation.create(
                    observationId = ContextObservationId.fromUuid(
                        UUID.nameUUIDFromBytes(
                            "h039|${request.generation.value}|${sample.kind.name}|${sample.sequence}|${key.value}"
                                .toByteArray(StandardCharsets.UTF_8),
                        ),
                    ),
                    sourceType = SOURCE_TYPE,
                    sourceReference = request.sourceReference,
                    key = key,
                    claim = ContextObservationClaim.Known(decimal(rawValue)),
                    confidence = confidence(sample.accuracy),
                    privacyClassification = ContextPrivacyClassification.SENSITIVE,
                    observedAt = sample.observedAt,
                    expiresAt = expiresAt,
                ),
            )
        }
    }

    private fun failStart(error: PhoneSensorError): PhoneSensorResult<PhoneSensorStartReport> {
        val released = releaseRegistration()
        lifecycle = PhoneSensorAdapterLifecycle.FAILED
        activeKinds = emptySet()
        unavailableKinds = request?.requestedKinds.orEmpty()
        failure = if (released) error else PhoneSensorError.ResourceReleaseFailure
        sink = null
        return phoneSensorFailure(failure ?: error)
    }

    private fun failActive(error: PhoneSensorError) {
        val released = releaseRegistration()
        lifecycle = PhoneSensorAdapterLifecycle.FAILED
        activeKinds = emptySet()
        lastSequence.clear()
        lastObservedAt.clear()
        failure = if (released) error else PhoneSensorError.ResourceReleaseFailure
        sink = null
    }

    private fun releaseRegistration(): Boolean {
        val current = registration
        registration = null
        return current == null || runCatching { current.close() }.isSuccess
    }

    private fun decimal(value: Double): ContextValue.DecimalValue {
        val rounded = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros()
        val canonical = rounded.toPlainString().let { if (it == "-0") "0" else it }
        return observationValue(ContextValue.DecimalValue.parse(canonical))
    }

    private fun confidence(accuracy: PhoneSensorAccuracy): ContextConfidence = observationValue(
        ContextConfidence.create(
            when (accuracy) {
                PhoneSensorAccuracy.UNRELIABLE -> 0
                PhoneSensorAccuracy.LOW -> 500
                PhoneSensorAccuracy.MEDIUM -> 800
                PhoneSensorAccuracy.HIGH -> 1000
            },
        ),
    )

    private fun monotonic(value: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(value)) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> error("Invalid internal H039 monotonic timestamp")
    }

    private fun <T> observationValue(result: ContextObservationResult<T>): T = when (result) {
        is ContextObservationResult.Success -> result.value
        is ContextObservationResult.Failure -> error("Invalid internal H039 observation: ${result.error}")
    }

    companion object {
        private val SOURCE_TYPE = value(ContextSourceType.parse("phone.sensor"))
        private val ACCELERATION_X = value(ContextKey.parse("phone.sensor.acceleration_x_mps2"))
        private val ACCELERATION_Y = value(ContextKey.parse("phone.sensor.acceleration_y_mps2"))
        private val ACCELERATION_Z = value(ContextKey.parse("phone.sensor.acceleration_z_mps2"))
        private val AMBIENT_LIGHT = value(ContextKey.parse("phone.sensor.ambient_light_lux"))
        private val PROXIMITY = value(ContextKey.parse("phone.sensor.proximity_cm"))

        private fun <T> value(result: ContextObservationResult<T>): T = when (result) {
            is ContextObservationResult.Success -> result.value
            is ContextObservationResult.Failure -> error("Invalid H039 constant: ${result.error}")
        }
    }
}
