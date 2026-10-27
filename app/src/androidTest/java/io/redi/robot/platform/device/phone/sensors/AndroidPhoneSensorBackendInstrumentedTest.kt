package io.redi.robot.platform.device.phone.sensors

import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.context.api.ContextObservation
import io.redi.robot.context.api.ContextObservationResult
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextSourceReference
import io.redi.robot.device.phone.sensors.PhoneSensorAdapterLifecycle
import io.redi.robot.device.phone.sensors.PhoneSensorDeviceAdapter
import io.redi.robot.device.phone.sensors.PhoneSensorGeneration
import io.redi.robot.device.phone.sensors.PhoneSensorKind
import io.redi.robot.device.phone.sensors.PhoneSensorObservationSink
import io.redi.robot.device.phone.sensors.PhoneSensorResult
import io.redi.robot.device.phone.sensors.PhoneSensorSessionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AndroidPhoneSensorBackendInstrumentedTest {
    @Test
    fun samsungPhoneSensorsEmitFreshRedactedEvidenceAndReleaseOnStop() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sensorManager = context.getSystemService(android.content.Context.SENSOR_SERVICE) as SensorManager
        val adapter = requireNotNull(AndroidPhoneSensorBackend.adapter(context))
        val observations = Collections.synchronizedList(mutableListOf<ContextObservation>())
        val batches = AtomicInteger(0)
        val accelerometerBatch = CountDownLatch(1)
        val generation = generation(39)
        val request = success(
            PhoneSensorSessionRequest.create(
                generation = generation,
                requestedKinds = PhoneSensorKind.entries.toSet(),
                sourceReference = observationSuccess(ContextSourceReference.parse("phone_sensor_device_test")),
                freshnessWindowMs = 2_000,
            ),
        )

        val start = success(
            adapter.start(
                request,
                PhoneSensorObservationSink { batch ->
                    observations.addAll(batch)
                    batches.incrementAndGet()
                    if (batch.any { it.key.value.startsWith("phone.sensor.acceleration_") }) {
                        accelerometerBatch.countDown()
                    }
                },
            ),
        )

        assertEquals(PhoneSensorAdapterLifecycle.ACTIVE, start.snapshot.lifecycle)
        assertTrue(PhoneSensorKind.ACCELEROMETER in start.snapshot.activeKinds)
        assertTrue(sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) != null)
        assertTrue("Expected a real accelerometer observation", accelerometerBatch.await(10, TimeUnit.SECONDS))

        val captured = synchronized(observations) { observations.toList() }
        val now = timestamp(SystemClock.elapsedRealtime())
        assertTrue(captured.any { it.key.value.startsWith("phone.sensor.acceleration_") })
        captured.forEach { observation ->
            assertEquals("phone.sensor", observation.sourceType.value)
            assertEquals("phone_sensor_device_test", observation.sourceReference.value)
            assertEquals(ContextPrivacyClassification.SENSITIVE, observation.privacyClassification)
            assertTrue(observation.isFreshAt(now))
            assertFalse(observation.claim.toString().contains("android", ignoreCase = true))
        }

        val stopped = success(adapter.stop(generation))
        assertEquals(PhoneSensorAdapterLifecycle.STOPPED, stopped.lifecycle)
        assertTrue(stopped.activeKinds.isEmpty())
        val countAfterStop = observations.size
        Thread.sleep(500)
        assertEquals("No callback may emit after resource release", countAfterStop, observations.size)
        adapter.close()

        println(
            "H039_DEVICE_TRACE " +
                "model=${Build.MODEL} " +
                "activeKinds=${start.snapshot.activeKinds.sortedBy { it.name }.joinToString(",") { it.name }} " +
                "unavailableKinds=${start.snapshot.unavailableKinds.sortedBy { it.name }.joinToString(",") { it.name }} " +
                "batches=${batches.get()} observations=$countAfterStop source=redacted values=redacted release=confirmed",
        )
    }

    private fun generation(value: Long): PhoneSensorGeneration = success(PhoneSensorGeneration.create(value))

    private fun timestamp(value: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(value)) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> error("invalid device monotonic time")
    }

    private fun <T> success(result: PhoneSensorResult<T>): T = when (result) {
        is PhoneSensorResult.Success -> result.value
        is PhoneSensorResult.Failure -> error("H039 device setup failed: ${result.error}")
    }

    private fun <T> observationSuccess(result: ContextObservationResult<T>): T = when (result) {
        is ContextObservationResult.Success -> result.value
        is ContextObservationResult.Failure -> error("H039 source reference failed: ${result.error}")
    }
}
