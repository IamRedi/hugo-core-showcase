package io.redi.robot.platform.device.phone.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.device.phone.sensors.PhoneSensorAccuracy
import io.redi.robot.device.phone.sensors.PhoneSensorBackend
import io.redi.robot.device.phone.sensors.PhoneSensorBackendFailure
import io.redi.robot.device.phone.sensors.PhoneSensorBackendResult
import io.redi.robot.device.phone.sensors.PhoneSensorBackendSession
import io.redi.robot.device.phone.sensors.PhoneSensorClock
import io.redi.robot.device.phone.sensors.PhoneSensorDeviceAdapter
import io.redi.robot.device.phone.sensors.PhoneSensorGeneration
import io.redi.robot.device.phone.sensors.PhoneSensorKind
import io.redi.robot.device.phone.sensors.PhoneSensorRegistration
import io.redi.robot.device.phone.sensors.PhoneSensorResult
import io.redi.robot.device.phone.sensors.PhoneSensorSample
import java.util.concurrent.atomic.AtomicBoolean

/** Android ownership boundary for H039. No Android sensor type crosses into the DeviceAdapter. */
class AndroidPhoneSensorBackend(
    private val sensorManager: SensorManager,
    private val samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_NORMAL,
) : PhoneSensorBackend {
    override fun open(
        generation: PhoneSensorGeneration,
        requestedKinds: Set<PhoneSensorKind>,
        callback: (PhoneSensorGeneration, PhoneSensorSample) -> Unit,
    ): PhoneSensorBackendResult {
        val sensorsByKind = requestedKinds.associateWith(::defaultSensor)
        val unavailable = sensorsByKind.filterValues { it == null }.keys.toMutableSet()
        val active = linkedMapOf<PhoneSensorKind, Sensor>()
        val sequences = mutableMapOf<PhoneSensorKind, Long>()
        val closed = AtomicBoolean(false)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (closed.get()) return
                val kind = kindFor(event.sensor.type) ?: return
                if (synchronized(active) { kind !in active }) return
                val copiedValues = valuesFor(kind, event.values) ?: return
                val sequence = synchronized(sequences) {
                    val next = (sequences[kind] ?: 0L) + 1L
                    sequences[kind] = next
                    next
                }
                val observedAt = timestamp(event.timestamp / NANOS_PER_MILLISECOND) ?: return
                when (
                    val sample = PhoneSensorSample.create(
                        kind = kind,
                        sequence = sequence,
                        observedAt = observedAt,
                        values = copiedValues,
                        accuracy = accuracy(event.accuracy),
                    )
                ) {
                    is PhoneSensorResult.Success -> callback(generation, sample.value)
                    is PhoneSensorResult.Failure -> Unit
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        try {
            sensorsByKind.forEach { (kind, sensor) ->
                if (sensor != null) {
                    if (sensorManager.registerListener(listener, sensor, samplingPeriodUs)) {
                        synchronized(active) { active[kind] = sensor }
                    } else {
                        unavailable += kind
                    }
                }
            }
        } catch (_: SecurityException) {
            closed.set(true)
            sensorManager.unregisterListener(listener)
            return PhoneSensorBackendResult.Failed(PhoneSensorBackendFailure.PLATFORM_UNAVAILABLE)
        } catch (_: RuntimeException) {
            closed.set(true)
            sensorManager.unregisterListener(listener)
            return PhoneSensorBackendResult.Failed(PhoneSensorBackendFailure.REGISTRATION_FAILED)
        }

        if (active.isEmpty()) {
            closed.set(true)
            sensorManager.unregisterListener(listener)
            return PhoneSensorBackendResult.Failed(PhoneSensorBackendFailure.NO_REQUESTED_SENSOR_AVAILABLE)
        }

        val registration = PhoneSensorRegistration {
            if (closed.compareAndSet(false, true)) sensorManager.unregisterListener(listener)
        }
        return PhoneSensorBackendResult.Opened(
            PhoneSensorBackendSession(
                activeKinds = active.keys.toSet(),
                unavailableKinds = unavailable.toSet(),
                registration = registration,
            ),
        )
    }

    private fun defaultSensor(kind: PhoneSensorKind): Sensor? = sensorManager.getDefaultSensor(
        when (kind) {
            PhoneSensorKind.ACCELEROMETER -> Sensor.TYPE_ACCELEROMETER
            PhoneSensorKind.AMBIENT_LIGHT -> Sensor.TYPE_LIGHT
            PhoneSensorKind.PROXIMITY -> Sensor.TYPE_PROXIMITY
        },
    )

    private fun kindFor(sensorType: Int): PhoneSensorKind? = when (sensorType) {
        Sensor.TYPE_ACCELEROMETER -> PhoneSensorKind.ACCELEROMETER
        Sensor.TYPE_LIGHT -> PhoneSensorKind.AMBIENT_LIGHT
        Sensor.TYPE_PROXIMITY -> PhoneSensorKind.PROXIMITY
        else -> null
    }

    private fun valuesFor(kind: PhoneSensorKind, raw: FloatArray): List<Double>? = when (kind) {
        PhoneSensorKind.ACCELEROMETER -> if (raw.size >= 3) {
            listOf(raw[0].toDouble(), raw[1].toDouble(), raw[2].toDouble())
        } else {
            null
        }
        PhoneSensorKind.AMBIENT_LIGHT,
        PhoneSensorKind.PROXIMITY,
        -> raw.firstOrNull()?.toDouble()?.let(::listOf)
    }

    private fun accuracy(value: Int): PhoneSensorAccuracy = when (value) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> PhoneSensorAccuracy.HIGH
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> PhoneSensorAccuracy.MEDIUM
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> PhoneSensorAccuracy.LOW
        else -> PhoneSensorAccuracy.UNRELIABLE
    }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        fun from(context: Context): AndroidPhoneSensorBackend? =
            (context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
                ?.let(::AndroidPhoneSensorBackend)

        fun adapter(context: Context): PhoneSensorDeviceAdapter? = from(context)?.let { backend ->
            PhoneSensorDeviceAdapter(
                backend = backend,
                clock = PhoneSensorClock {
                    checkNotNull(timestamp(SystemClock.elapsedRealtime()))
                },
            )
        }

        private fun timestamp(value: Long): MonotonicTimestampMs? = when (
            val result = MonotonicTimestampMs.create(value)
        ) {
            is CapabilityEnvelopeResult.Success -> result.value
            is CapabilityEnvelopeResult.Failure -> null
        }
    }
}
