package io.redi.robot.device.phone.sensors

import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.context.api.ContextObservation
import io.redi.robot.context.api.ContextObservationClaim
import io.redi.robot.context.api.ContextObservationResult
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextSourceReference
import io.redi.robot.context.api.ContextValue
import io.redi.robot.context.runtime.ContextSnapshotBudget
import io.redi.robot.context.runtime.ContextSnapshotEngine
import io.redi.robot.context.runtime.ContextSnapshotRequest
import io.redi.robot.context.runtime.ContextSnapshotResolution
import io.redi.robot.context.runtime.ContextSnapshotResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneSensorDeviceAdapterTest {
    @Test
    fun `start reports exact available partition without implying authority`() {
        val backend = FakeBackend(active = setOf(PhoneSensorKind.ACCELEROMETER, PhoneSensorKind.PROXIMITY))
        val adapter = adapter(backend)

        val report = success(adapter.start(request(1), PhoneSensorObservationSink { })).snapshot

        assertEquals(PhoneSensorAdapterLifecycle.ACTIVE, report.lifecycle)
        assertEquals(setOf(PhoneSensorKind.ACCELEROMETER, PhoneSensorKind.PROXIMITY), report.activeKinds)
        assertEquals(setOf(PhoneSensorKind.AMBIENT_LIGHT), report.unavailableKinds)
        assertFalse(report.grantsNavigationAuthority)
        assertFalse(report.grantsAuthorization)
        assertFalse(report.grantsExecutionAuthority)
        assertEquals(1, backend.openCount)
    }

    @Test
    fun `accelerometer projects one bounded fresh H036 observation per axis`() {
        val backend = FakeBackend(active = setOf(PhoneSensorKind.ACCELEROMETER))
        val captured = mutableListOf<List<ContextObservation>>()
        val adapter = adapter(backend, now = 1_050)
        success(
            adapter.start(
                request(1, setOf(PhoneSensorKind.ACCELEROMETER), freshnessMs = 100),
                PhoneSensorObservationSink(captured::add),
            ),
        )

        backend.emit(1, sample(PhoneSensorKind.ACCELEROMETER, 1, 1_000, listOf(1.25, -2.5, 9.80665)))

        val observations = captured.single()
        assertEquals(
            listOf(
                "phone.sensor.acceleration_x_mps2",
                "phone.sensor.acceleration_y_mps2",
                "phone.sensor.acceleration_z_mps2",
            ),
            observations.map { it.key.value },
        )
        assertEquals(listOf("1.25", "-2.5", "9.80665"), observations.map(::decimalValue))
        observations.forEach {
            assertEquals("phone.sensor", it.sourceType.value)
            assertEquals("phone_sensor_run", it.sourceReference.value)
            assertEquals(ContextPrivacyClassification.SENSITIVE, it.privacyClassification)
            assertEquals(1_000, it.observedAt.value)
            assertEquals(1_100, it.expiresAt.value)
            assertEquals(1_000, it.confidence.permille)
        }
        assertEquals(3, adapter.snapshot().emittedObservationCount)
    }

    @Test
    fun `light and proximity remain distinct source observations`() {
        val backend = FakeBackend(active = setOf(PhoneSensorKind.AMBIENT_LIGHT, PhoneSensorKind.PROXIMITY))
        val captured = mutableListOf<ContextObservation>()
        val adapter = adapter(backend, now = 1_010)
        success(
            adapter.start(
                request(1, setOf(PhoneSensorKind.AMBIENT_LIGHT, PhoneSensorKind.PROXIMITY)),
                PhoneSensorObservationSink(captured::addAll),
            ),
        )

        backend.emit(1, sample(PhoneSensorKind.AMBIENT_LIGHT, 1, 1_000, listOf(42.75)))
        backend.emit(1, sample(PhoneSensorKind.PROXIMITY, 1, 1_001, listOf(3.5)))

        assertEquals(
            listOf("phone.sensor.ambient_light_lux", "phone.sensor.proximity_cm"),
            captured.map { it.key.value },
        )
        assertEquals(listOf("42.75", "3.5"), captured.map(::decimalValue))
    }

    @Test
    fun `H037 accepts fresh observations and excludes them at exact expiry`() {
        val backend = FakeBackend(active = setOf(PhoneSensorKind.AMBIENT_LIGHT))
        val captured = mutableListOf<ContextObservation>()
        val adapter = adapter(backend, now = 1_050)
        success(
            adapter.start(
                request(1, setOf(PhoneSensorKind.AMBIENT_LIGHT), freshnessMs = 100),
                PhoneSensorObservationSink(captured::addAll),
            ),
        )
        backend.emit(1, sample(PhoneSensorKind.AMBIENT_LIGHT, 1, 1_000, listOf(20.0)))

        val fresh = snapshot(captured, now = 1_099)
        assertEquals(1, fresh.items.size)
        assertEquals("20", (fresh.items.single().resolution as ContextSnapshotResolution.Known).value.canonicalValue)
        assertTrue(snapshot(captured, now = 1_100).items.isEmpty())
    }

    @Test
    fun `new generation closes prior resources and rejects stale callback`() {
        val backend = FakeBackend(active = setOf(PhoneSensorKind.ACCELEROMETER))
        val captured = mutableListOf<ContextObservation>()
        val adapter = adapter(backend)
        success(adapter.start(request(1), PhoneSensorObservationSink(captured::addAll)))
        val oldCallback = backend.callbacks.single()
        success(adapter.start(request(2), PhoneSensorObservationSink(captured::addAll)))

        oldCallback(generation(1), sample(PhoneSensorKind.ACCELEROMETER, 1, 1_000))
        val disposition = adapter.onSample(
            generation(1),
            sample(PhoneSensorKind.ACCELEROMETER, 2, 1_001),
        )

        assertEquals(PhoneSensorSampleDisposition.Ignored(PhoneSensorIgnoredReason.STALE_GENERATION), disposition)
        assertEquals(1, backend.registrations.first().closeCount)
        assertTrue(captured.isEmpty())
        assertEquals(generation(2), adapter.snapshot().generation)
    }

    @Test
    fun `out of order future and stale samples fail closed`() {
        val backend = FakeBackend(active = setOf(PhoneSensorKind.ACCELEROMETER))
        val adapter = adapter(backend, now = 1_050)
        success(
            adapter.start(
                request(1, setOf(PhoneSensorKind.ACCELEROMETER), freshnessMs = 100),
                PhoneSensorObservationSink { },
            ),
        )
        assertTrue(
            adapter.onSample(
                generation(1),
                sample(PhoneSensorKind.ACCELEROMETER, 2, 1_000),
            ) is PhoneSensorSampleDisposition.Emitted,
        )
        assertEquals(
            PhoneSensorIgnoredReason.OUT_OF_ORDER,
            (
                adapter.onSample(
                    generation(1),
                    sample(PhoneSensorKind.ACCELEROMETER, 1, 1_001),
                ) as PhoneSensorSampleDisposition.Ignored
                ).reason,
        )
        assertEquals(
            PhoneSensorIgnoredReason.FUTURE_SAMPLE,
            (
                adapter.onSample(
                    generation(1),
                    sample(PhoneSensorKind.ACCELEROMETER, 3, 1_051),
                ) as PhoneSensorSampleDisposition.Ignored
                ).reason,
        )
        success(
            adapter.start(
                request(2, setOf(PhoneSensorKind.ACCELEROMETER), freshnessMs = 100),
                PhoneSensorObservationSink { },
            ),
        )
        assertEquals(
            PhoneSensorIgnoredReason.STALE_SAMPLE,
            (
                adapter.onSample(
                    generation(2),
                    sample(PhoneSensorKind.ACCELEROMETER, 1, 950),
                ) as PhoneSensorSampleDisposition.Ignored
                ).reason,
        )
    }

    @Test
    fun `stop and close release registration exactly once`() {
        val backend = FakeBackend(active = setOf(PhoneSensorKind.ACCELEROMETER))
        val adapter = adapter(backend)
        success(adapter.start(request(4), PhoneSensorObservationSink { }))
        val registration = backend.registrations.single()

        assertEquals(PhoneSensorAdapterLifecycle.STOPPED, success(adapter.stop(generation(4))).lifecycle)
        assertEquals(PhoneSensorAdapterLifecycle.STOPPED, success(adapter.stop(generation(4))).lifecycle)
        adapter.close()

        assertEquals(1, registration.closeCount)
        assertTrue(adapter.snapshot().activeKinds.isEmpty())
        assertEquals(PhoneSensorError.StaleGeneration, failure(adapter.stop(generation(3))))
        assertEquals(PhoneSensorError.FutureGeneration, failure(adapter.stop(generation(5))))
    }

    @Test
    fun `backend and sink failures clean resources and remain explicit`() {
        val unavailable = adapter(FakeBackend(active = emptySet(), failure = PhoneSensorBackendFailure.NO_REQUESTED_SENSOR_AVAILABLE))
        assertEquals(
            PhoneSensorError.BackendFailure(PhoneSensorBackendFailure.NO_REQUESTED_SENSOR_AVAILABLE),
            failure(unavailable.start(request(1), PhoneSensorObservationSink { })),
        )
        assertEquals(PhoneSensorAdapterLifecycle.FAILED, unavailable.snapshot().lifecycle)

        val backend = FakeBackend(active = setOf(PhoneSensorKind.ACCELEROMETER))
        val adapter = adapter(backend)
        success(adapter.start(request(1), PhoneSensorObservationSink { error("sink failed") }))
        backend.emit(1, sample(PhoneSensorKind.ACCELEROMETER, 1, 1_000))

        assertEquals(PhoneSensorAdapterLifecycle.FAILED, adapter.snapshot().lifecycle)
        assertEquals(PhoneSensorError.ObservationSinkFailure, adapter.snapshot().failure)
        assertEquals(1, backend.registrations.single().closeCount)
    }

    @Test
    fun `resource release failure blocks generation replacement and fails closed`() {
        val backend = FakeBackend(
            active = setOf(PhoneSensorKind.ACCELEROMETER),
            throwOnClose = true,
        )
        val adapter = adapter(backend)
        success(adapter.start(request(1), PhoneSensorObservationSink { }))

        assertEquals(
            PhoneSensorError.ResourceReleaseFailure,
            failure(adapter.start(request(2), PhoneSensorObservationSink { })),
        )
        assertEquals(PhoneSensorAdapterLifecycle.FAILED, adapter.snapshot().lifecycle)
        assertEquals(generation(1), adapter.snapshot().generation)
        assertTrue(adapter.snapshot().activeKinds.isEmpty())
        assertEquals(1, backend.openCount)
        assertEquals(1, backend.registrations.single().closeCount)
        assertEquals(
            PhoneSensorError.ResourceReleaseFailure,
            failure(adapter.stop(generation(1))),
        )
        adapter.close()
        assertEquals(PhoneSensorAdapterLifecycle.FAILED, adapter.snapshot().lifecycle)
        assertEquals(PhoneSensorError.ResourceReleaseFailure, adapter.snapshot().failure)
    }

    @Test
    fun `sample and request validation are bounded`() {
        assertEquals(PhoneSensorError.InvalidSequence, failure(PhoneSensorSample.create(
            PhoneSensorKind.PROXIMITY, 0, time(1), listOf(1.0), PhoneSensorAccuracy.HIGH,
        )))
        assertEquals(PhoneSensorError.InvalidSensorValue, failure(PhoneSensorSample.create(
            PhoneSensorKind.ACCELEROMETER, 1, time(1), listOf(Double.NaN, 0.0, 0.0), PhoneSensorAccuracy.HIGH,
        )))
        assertEquals(PhoneSensorError.InvalidSensorValue, failure(PhoneSensorSample.create(
            PhoneSensorKind.AMBIENT_LIGHT, 1, time(1), listOf(1_000_001.0), PhoneSensorAccuracy.HIGH,
        )))
        assertEquals(PhoneSensorError.EmptySensorRequest, failure(PhoneSensorSessionRequest.create(
            generation(1), emptySet(), source(), 100,
        )))
        assertEquals(PhoneSensorError.InvalidFreshnessWindow, failure(PhoneSensorSessionRequest.create(
            generation(1), setOf(PhoneSensorKind.PROXIMITY), source(), 49,
        )))
    }

    @Test
    fun `equivalent events produce deterministic identifiers and snapshots retain no raw values`() {
        fun run(): Pair<List<String>, PhoneSensorAdapterSnapshot> {
            val backend = FakeBackend(active = setOf(PhoneSensorKind.ACCELEROMETER))
            val captured = mutableListOf<ContextObservation>()
            val adapter = adapter(backend)
            success(adapter.start(request(1), PhoneSensorObservationSink(captured::addAll)))
            backend.emit(1, sample(PhoneSensorKind.ACCELEROMETER, 1, 1_000, listOf(11.0, 22.0, 33.0)))
            return captured.map { it.observationId.value } to adapter.snapshot()
        }

        val first = run()
        val second = run()
        assertEquals(first.first, second.first)
        assertEquals(3, first.first.distinct().size)
        assertEquals(setOf(PhoneSensorKind.ACCELEROMETER), first.second.lastObservedAt.keys)
        assertFalse(first.second.javaClass.declaredFields.any { it.name.contains("value", ignoreCase = true) })
    }

    @Test
    fun `pure phone sensor package contains no Android platform vendor or navigation coupling`() {
        val packageRoot = sourceRoot("device/phone/sensors")
        val source = packageRoot.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        listOf(
            "import android.",
            "Bluetooth",
            "LOOI",
            "FED9",
            "android.location",
            "android.app.usage",
        ).forEach { prohibited ->
            assertFalse("Unexpected H039 coupling: $prohibited", source.contains(prohibited, ignoreCase = true))
        }
    }

    private fun adapter(backend: FakeBackend, now: Long = 1_050): PhoneSensorDeviceAdapter =
        PhoneSensorDeviceAdapter(backend, PhoneSensorClock { time(now) })

    private fun request(
        generation: Long,
        kinds: Set<PhoneSensorKind> = PhoneSensorKind.entries.toSet(),
        freshnessMs: Long = 1_000,
    ): PhoneSensorSessionRequest = success(
        PhoneSensorSessionRequest.create(generation(generation), kinds, source(), freshnessMs),
    )

    private fun sample(
        kind: PhoneSensorKind,
        sequence: Long,
        observedAt: Long,
        values: List<Double> = listOf(0.0, 0.0, 9.8),
    ): PhoneSensorSample = success(
        PhoneSensorSample.create(kind, sequence, time(observedAt), values, PhoneSensorAccuracy.HIGH),
    )

    private fun snapshot(observations: List<ContextObservation>, now: Long) = successfulSnapshot(
        ContextSnapshotEngine.build(
            observations,
            successfulSnapshot(
                ContextSnapshotRequest.create(
                    now = time(now),
                    requestedKeys = null,
                    allowedPrivacyClassifications = setOf(ContextPrivacyClassification.SENSITIVE),
                    budget = successfulSnapshot(ContextSnapshotBudget.create(8, 4, 16, 8_192)),
                ),
            ),
        ),
    )

    private fun decimalValue(observation: ContextObservation): String =
        ((observation.claim as ContextObservationClaim.Known).value as ContextValue.DecimalValue).canonicalValue

    private fun generation(value: Long): PhoneSensorGeneration = success(PhoneSensorGeneration.create(value))

    private fun source(): ContextSourceReference = successfulObservation(
        ContextSourceReference.parse("phone_sensor_run"),
    )

    private fun time(value: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(value)) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> error("invalid test time")
    }

    private fun <T> success(result: PhoneSensorResult<T>): T {
        assertTrue(result is PhoneSensorResult.Success)
        return (result as PhoneSensorResult.Success).value
    }

    private fun failure(result: PhoneSensorResult<*>): PhoneSensorError {
        assertTrue(result is PhoneSensorResult.Failure)
        return (result as PhoneSensorResult.Failure).error
    }

    private fun <T> successfulObservation(result: ContextObservationResult<T>): T =
        (result as ContextObservationResult.Success).value

    private fun <T> successfulSnapshot(result: ContextSnapshotResult<T>): T =
        (result as ContextSnapshotResult.Success).value

    private fun sourceRoot(relative: String): File = listOf(
        File("src/main/java/io/redi/robot/$relative"),
        File("app/src/main/java/io/redi/robot/$relative"),
        File("../app/src/main/java/io/redi/robot/$relative"),
    ).first { it.isDirectory }

    private class FakeRegistration(private val throwOnClose: Boolean) : PhoneSensorRegistration {
        var closeCount: Int = 0
        override fun close() {
            closeCount += 1
            if (throwOnClose) error("release failed")
        }
    }

    private inner class FakeBackend(
        private val active: Set<PhoneSensorKind>,
        private val failure: PhoneSensorBackendFailure? = null,
        private val throwOnClose: Boolean = false,
    ) : PhoneSensorBackend {
        val callbacks = mutableListOf<(PhoneSensorGeneration, PhoneSensorSample) -> Unit>()
        val registrations = mutableListOf<FakeRegistration>()
        var openCount: Int = 0

        override fun open(
            generation: PhoneSensorGeneration,
            requestedKinds: Set<PhoneSensorKind>,
            callback: (PhoneSensorGeneration, PhoneSensorSample) -> Unit,
        ): PhoneSensorBackendResult {
            openCount += 1
            if (failure != null) return PhoneSensorBackendResult.Failed(failure)
            val registration = FakeRegistration(throwOnClose)
            registrations += registration
            callbacks += callback
            return PhoneSensorBackendResult.Opened(
                PhoneSensorBackendSession(
                    activeKinds = active.intersect(requestedKinds),
                    unavailableKinds = requestedKinds - active,
                    registration = registration,
                ),
            )
        }

        fun emit(
            generation: Long,
            sample: PhoneSensorSample,
        ) {
            callbacks.last()(generation(generation), sample)
        }
    }
}
