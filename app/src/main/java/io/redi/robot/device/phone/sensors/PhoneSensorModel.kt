package io.redi.robot.device.phone.sensors

import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.context.api.ContextObservation
import io.redi.robot.context.api.ContextSourceReference
import java.util.Collections

enum class PhoneSensorKind {
    ACCELEROMETER,
    AMBIENT_LIGHT,
    PROXIMITY,
}

enum class PhoneSensorAccuracy {
    UNRELIABLE,
    LOW,
    MEDIUM,
    HIGH,
}

@JvmInline
value class PhoneSensorGeneration private constructor(val value: Long) {
    companion object {
        fun create(value: Long): PhoneSensorResult<PhoneSensorGeneration> = if (value < 0L) {
            phoneSensorFailure(PhoneSensorError.InvalidGeneration)
        } else {
            PhoneSensorResult.Success(PhoneSensorGeneration(value))
        }
    }
}

class PhoneSensorSessionRequest private constructor(
    val generation: PhoneSensorGeneration,
    requestedKinds: Set<PhoneSensorKind>,
    val sourceReference: ContextSourceReference,
    val freshnessWindowMs: Long,
) {
    val requestedKinds: Set<PhoneSensorKind> = Collections.unmodifiableSet(requestedKinds.toSet())

    companion object {
        const val MINIMUM_FRESHNESS_MS: Long = 50L
        const val MAXIMUM_FRESHNESS_MS: Long = 60_000L

        fun create(
            generation: PhoneSensorGeneration,
            requestedKinds: Set<PhoneSensorKind>,
            sourceReference: ContextSourceReference,
            freshnessWindowMs: Long,
        ): PhoneSensorResult<PhoneSensorSessionRequest> = when {
            requestedKinds.isEmpty() -> phoneSensorFailure(PhoneSensorError.EmptySensorRequest)
            freshnessWindowMs !in MINIMUM_FRESHNESS_MS..MAXIMUM_FRESHNESS_MS ->
                phoneSensorFailure(PhoneSensorError.InvalidFreshnessWindow)
            else -> PhoneSensorResult.Success(
                PhoneSensorSessionRequest(
                    generation,
                    requestedKinds,
                    sourceReference,
                    freshnessWindowMs,
                ),
            )
        }
    }
}

class PhoneSensorSample private constructor(
    val kind: PhoneSensorKind,
    val sequence: Long,
    val observedAt: MonotonicTimestampMs,
    values: List<Double>,
    val accuracy: PhoneSensorAccuracy,
) {
    val values: List<Double> = Collections.unmodifiableList(values.toList())

    companion object {
        fun create(
            kind: PhoneSensorKind,
            sequence: Long,
            observedAt: MonotonicTimestampMs,
            values: List<Double>,
            accuracy: PhoneSensorAccuracy,
        ): PhoneSensorResult<PhoneSensorSample> {
            if (sequence <= 0L) return phoneSensorFailure(PhoneSensorError.InvalidSequence)
            if (values.any { !it.isFinite() }) return phoneSensorFailure(PhoneSensorError.InvalidSensorValue)
            val valid = when (kind) {
                PhoneSensorKind.ACCELEROMETER -> values.size == 3 && values.all { it in -500.0..500.0 }
                PhoneSensorKind.AMBIENT_LIGHT -> values.size == 1 && values.single() in 0.0..1_000_000.0
                PhoneSensorKind.PROXIMITY -> values.size == 1 && values.single() in 0.0..1_000.0
            }
            return if (!valid) {
                phoneSensorFailure(PhoneSensorError.InvalidSensorValue)
            } else {
                PhoneSensorResult.Success(PhoneSensorSample(kind, sequence, observedAt, values, accuracy))
            }
        }
    }
}

fun interface PhoneSensorObservationSink {
    /** Receives one complete sample projection. Implementations must not retain raw platform data. */
    fun onObservations(observations: List<ContextObservation>)
}

fun interface PhoneSensorClock {
    fun now(): MonotonicTimestampMs
}

fun interface PhoneSensorRegistration : AutoCloseable {
    override fun close()
}

class PhoneSensorBackendSession(
    activeKinds: Set<PhoneSensorKind>,
    unavailableKinds: Set<PhoneSensorKind>,
    val registration: PhoneSensorRegistration,
) {
    val activeKinds: Set<PhoneSensorKind> = Collections.unmodifiableSet(activeKinds.toSet())
    val unavailableKinds: Set<PhoneSensorKind> = Collections.unmodifiableSet(unavailableKinds.toSet())

    init {
        require(activeKinds.isNotEmpty())
        require(activeKinds.intersect(unavailableKinds).isEmpty())
    }
}

sealed interface PhoneSensorBackendResult {
    data class Opened(val session: PhoneSensorBackendSession) : PhoneSensorBackendResult
    data class Failed(val reason: PhoneSensorBackendFailure) : PhoneSensorBackendResult
}

enum class PhoneSensorBackendFailure {
    NO_REQUESTED_SENSOR_AVAILABLE,
    REGISTRATION_FAILED,
    PLATFORM_UNAVAILABLE,
}

interface PhoneSensorBackend {
    fun open(
        generation: PhoneSensorGeneration,
        requestedKinds: Set<PhoneSensorKind>,
        callback: (PhoneSensorGeneration, PhoneSensorSample) -> Unit,
    ): PhoneSensorBackendResult
}

enum class PhoneSensorAdapterLifecycle {
    STOPPED,
    STARTING,
    ACTIVE,
    FAILED,
}

class PhoneSensorAdapterSnapshot internal constructor(
    val generation: PhoneSensorGeneration?,
    val lifecycle: PhoneSensorAdapterLifecycle,
    activeKinds: Set<PhoneSensorKind>,
    unavailableKinds: Set<PhoneSensorKind>,
    lastObservedAt: Map<PhoneSensorKind, MonotonicTimestampMs>,
    val emittedObservationCount: Long,
    val failure: PhoneSensorError?,
) {
    val activeKinds: Set<PhoneSensorKind> = Collections.unmodifiableSet(activeKinds.toSet())
    val unavailableKinds: Set<PhoneSensorKind> = Collections.unmodifiableSet(unavailableKinds.toSet())
    val lastObservedAt: Map<PhoneSensorKind, MonotonicTimestampMs> = Collections.unmodifiableMap(
        lastObservedAt.toMap(),
    )

    val grantsNavigationAuthority: Boolean get() = false
    val grantsAuthorization: Boolean get() = false
    val grantsExecutionAuthority: Boolean get() = false
}

data class PhoneSensorStartReport(
    val snapshot: PhoneSensorAdapterSnapshot,
)

sealed interface PhoneSensorSampleDisposition {
    data class Emitted(val observationCount: Int) : PhoneSensorSampleDisposition
    data class Ignored(val reason: PhoneSensorIgnoredReason) : PhoneSensorSampleDisposition
}

enum class PhoneSensorIgnoredReason {
    NO_ACTIVE_SESSION,
    STALE_GENERATION,
    SENSOR_NOT_ACTIVE,
    OUT_OF_ORDER,
    FUTURE_SAMPLE,
    STALE_SAMPLE,
}

sealed interface PhoneSensorResult<out T> {
    data class Success<T>(val value: T) : PhoneSensorResult<T>
    data class Failure(val error: PhoneSensorError) : PhoneSensorResult<Nothing>
}

sealed interface PhoneSensorError {
    data object InvalidGeneration : PhoneSensorError
    data object EmptySensorRequest : PhoneSensorError
    data object InvalidFreshnessWindow : PhoneSensorError
    data object InvalidSequence : PhoneSensorError
    data object InvalidSensorValue : PhoneSensorError
    data object StaleGeneration : PhoneSensorError
    data object FutureGeneration : PhoneSensorError
    data class BackendFailure(val reason: PhoneSensorBackendFailure) : PhoneSensorError
    data object ObservationSinkFailure : PhoneSensorError
    data object TimestampOverflow : PhoneSensorError
    data object ResourceReleaseFailure : PhoneSensorError
}

internal fun <T> phoneSensorFailure(error: PhoneSensorError): PhoneSensorResult<T> =
    PhoneSensorResult.Failure(error)
