package io.redi.robot.context.sources

import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.context.api.ContextObservation
import io.redi.robot.context.api.ContextObservationClaim
import io.redi.robot.context.api.ContextObservationResult
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextSourceReference
import io.redi.robot.context.api.ContextUnknownReason
import io.redi.robot.context.api.ContextValue
import io.redi.robot.context.runtime.ContextSnapshotBudget
import io.redi.robot.context.runtime.ContextSnapshotEngine
import io.redi.robot.context.runtime.ContextSnapshotRequest
import io.redi.robot.context.runtime.ContextSnapshotResolution
import io.redi.robot.context.runtime.ContextSnapshotResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

class TimePresenceContextSourcesTest {
    @Test
    fun `local time emits deterministic private minute day and offset evidence`() {
        val wall = Instant.parse("2026-09-01T10:08:43Z").toEpochMilli()
        val first = localTime(wall, "Europe/Berlin", observedAt = 1_000)
        val second = localTime(wall, "Europe/Berlin", observedAt = 1_000)

        assertEquals(
            listOf(
                "time.local.epoch_day",
                "time.local.minute_of_day",
                "time.local.day_of_week",
                "time.local.utc_offset_minutes",
            ),
            first.map { it.key.value },
        )
        assertEquals(
            listOf(
                LocalDate.of(2026, 9, 1).toEpochDay().toString(),
                (12 * 60 + 8).toString(),
                "tuesday",
                "120",
            ),
            first.map(::knownValue),
        )
        assertEquals(first.map { it.observationId }, second.map { it.observationId })
        first.forEach {
            assertEquals("host.clock", it.sourceType.value)
            assertEquals(ContextPrivacyClassification.PRIVATE, it.privacyClassification)
            assertEquals(1_000, it.confidence.permille)
            assertEquals(1_000, it.observedAt.value)
            assertEquals(18_000, it.expiresAt.value)
        }
    }

    @Test
    fun `local time expires exactly at the next wall minute and new minute changes identifiers`() {
        val firstWall = Instant.parse("2026-09-01T10:08:59.999Z").toEpochMilli()
        val secondWall = firstWall + 1
        val first = localTime(firstWall, "UTC", observedAt = 500)
        val second = localTime(secondWall, "UTC", observedAt = 501)

        assertTrue(first.all { it.isFreshAt(time(500)) })
        assertTrue(first.all { !it.isFreshAt(time(501)) })
        assertEquals("608", knownValue(first.single { it.key.value.endsWith("minute_of_day") }))
        assertEquals("609", knownValue(second.single { it.key.value.endsWith("minute_of_day") }))
        assertNotEquals(first.map { it.observationId }, second.map { it.observationId })
    }

    @Test
    fun `invalid clock zone region accuracy and freshness fail closed`() {
        assertEquals(
            TimePresenceSourceError.InvalidZone,
            failure(LocalTimeSample.create(1, "Not/AZone", time(1))),
        )
        assertEquals(
            TimePresenceSourceError.InvalidWallClock,
            failure(LocalTimeSample.create(-1, "UTC", time(1))),
        )
        assertEquals(
            TimePresenceSourceError.InvalidRegion,
            failure(CoarsePresenceRegion.create(91.0, 0.0, 3_000.0)),
        )
        assertEquals(
            TimePresenceSourceError.InvalidRegion,
            failure(CoarsePresenceRegion.create(0.0, 0.0, 2_999.0)),
        )
        assertEquals(
            TimePresenceSourceError.InvalidAccuracy,
            failure(CoarseLocationSample.create(0.0, 0.0, Double.NaN, time(1))),
        )
        assertEquals(
            TimePresenceSourceError.InvalidFreshnessWindow,
            failure(CoarsePresenceRequest.create(source(), region(), 121_000, 30_000)),
        )
    }

    @Test
    fun `permission denial is explicit restricted unknown and never absence or false`() {
        val observation = presence(CoarsePresenceAcquisition.PermissionDenied, now = 1_000)

        assertEquals("presence.authorized_region", observation.key.value)
        assertEquals("host.location.coarse", observation.sourceType.value)
        assertEquals(ContextPrivacyClassification.RESTRICTED, observation.privacyClassification)
        assertEquals(
            ContextUnknownReason.PERMISSION_DENIED,
            (observation.claim as ContextObservationClaim.Unknown).reason,
        )
        assertEquals(1_000, observation.observedAt.value)
        assertEquals(31_000, observation.expiresAt.value)
    }

    @Test
    fun `unavailable disabled timeout and cancellation remain source unavailable`() {
        listOf(
            CoarsePresenceAcquisition.Unavailable,
            CoarsePresenceAcquisition.LocationDisabled,
            CoarsePresenceAcquisition.TimedOut,
            CoarsePresenceAcquisition.Cancelled,
        ).forEach { acquisition ->
            assertEquals(
                ContextUnknownReason.SOURCE_UNAVAILABLE,
                (presence(acquisition).claim as ContextObservationClaim.Unknown).reason,
            )
        }
    }

    @Test
    fun `coarse uncertainty is respected while definite inside and outside are known`() {
        val inside = presence(observed(latitude = 0.0, longitude = 0.0, accuracy = 3_000.0))
        val outside = presence(observed(latitude = 0.25, longitude = 0.0, accuracy = 3_000.0))
        val boundary = presence(observed(latitude = 0.09, longitude = 0.0, accuracy = 3_000.0))

        assertEquals("true", knownValue(inside))
        assertEquals("false", knownValue(outside))
        assertEquals(
            ContextUnknownReason.NOT_OBSERVED,
            (boundary.claim as ContextObservationClaim.Unknown).reason,
        )
        assertTrue(inside.confidence.permille in 500..950)
        assertTrue(outside.confidence.permille in 500..950)
    }

    @Test
    fun `stale future and expired location samples cannot become presence facts`() {
        val stale = presence(observed(observedAt = 800), now = 1_000, maximumAge = 100)
        val future = presence(observed(observedAt = 1_001), now = 1_000)
        val expired = presence(observed(observedAt = 0), now = 1_000, maximumAge = 2_000, freshness = 1_000)

        listOf(stale, future, expired).forEach {
            assertEquals(ContextUnknownReason.NOT_OBSERVED, (it.claim as ContextObservationClaim.Unknown).reason)
            assertEquals(1_000, it.observedAt.value)
        }
    }

    @Test
    fun `H037 retains fresh restricted evidence and removes it at exact expiry`() {
        val observation = presence(observed(observedAt = 1_000), now = 1_010, freshness = 1_000)
        val fresh = snapshot(listOf(observation), 1_999)
        val stale = snapshot(listOf(observation), 2_000)

        assertEquals("true", (fresh.items.single().resolution as ContextSnapshotResolution.Known).value.canonicalValue)
        assertTrue(stale.items.isEmpty())
    }

    @Test
    fun `equivalent presence inputs produce equivalent evidence without raw location leakage`() {
        val acquisition = observed(latitude = 12.345678, longitude = -76.54321, accuracy = 4_000.0)
        val first = presence(acquisition)
        val second = presence(acquisition)
        val serialized = listOf(
            first.observationId.value,
            first.sourceType.value,
            first.sourceReference.value,
            first.key.value,
            first.claim.toString(),
            first.toString(),
            (acquisition as CoarsePresenceAcquisition.Observed).sample.toString(),
        ).joinToString("|")

        assertEquals(first.observationId, second.observationId)
        assertFalse(serialized.contains("12.345678"))
        assertFalse(serialized.contains("-76.54321"))
        assertTrue(acquisition.sample.toString().contains("redacted"))
    }

    @Test
    fun `different opaque source aliases cannot reuse observation identifiers`() {
        val acquisition = observed()
        val first = success(
            CoarsePresenceContextSource.observe(request(sourceValue = "presence_region_one"), acquisition, time(1_010)),
        ).observations.single()
        val second = success(
            CoarsePresenceContextSource.observe(request(sourceValue = "presence_region_two"), acquisition, time(1_010)),
        ).observations.single()

        assertNotEquals(first.observationId, second.observationId)
    }

    @Test
    fun `batches are immutable ephemeral evidence and imply no authority`() {
        val batch = success(
            CoarsePresenceContextSource.observe(
                request(),
                CoarsePresenceAcquisition.PermissionDenied,
                time(1_000),
            ),
        )
        assertFalse(batch.persistsHistory)
        assertFalse(batch.grantsTrust)
        assertFalse(batch.grantsPermission)
        assertFalse(batch.grantsAuthorization)
        assertFalse(batch.grantsSafetyApproval)
        assertFalse(batch.grantsExecutionAuthority)
        assertTrue(runCatching { (batch.observations as MutableList).clear() }.isFailure)
    }

    @Test
    fun `pure context sources contain no Android vendor service credential or execution coupling`() {
        val sourceText = sourceRoot("context/sources")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        listOf(
            "import android.",
            "com.google.",
            "Bluetooth",
            "LOOI",
            "ServiceCredential",
            "PermissionGrant",
            "SafetyController",
            "execute(",
        ).forEach { prohibited ->
            assertFalse("Unexpected H044 coupling: $prohibited", sourceText.contains(prohibited, ignoreCase = true))
        }
    }

    private fun localTime(wall: Long, zone: String, observedAt: Long): List<ContextObservation> = success(
        LocalTimeContextSource.observe(
            success(LocalTimeSample.create(wall, zone, time(observedAt))),
            source("local_clock_run"),
        ),
    ).observations

    private fun presence(
        acquisition: CoarsePresenceAcquisition,
        now: Long = 1_010,
        maximumAge: Long = 1_000,
        freshness: Long = 30_000,
    ): ContextObservation = success(
        CoarsePresenceContextSource.observe(
            request(maximumAge, freshness),
            acquisition,
            time(now),
        ),
    ).observations.single()

    private fun request(
        maximumAge: Long = 1_000,
        freshness: Long = 30_000,
        sourceValue: String = "coarse_presence_run",
    ): CoarsePresenceRequest = success(
        CoarsePresenceRequest.create(source(sourceValue), region(), maximumAge, freshness),
    )

    private fun region(): CoarsePresenceRegion = success(
        CoarsePresenceRegion.create(0.0, 0.0, 10_000.0),
    )

    private fun observed(
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        accuracy: Double = 3_000.0,
        observedAt: Long = 1_000,
    ): CoarsePresenceAcquisition = CoarsePresenceAcquisition.Observed(
        success(CoarseLocationSample.create(latitude, longitude, accuracy, time(observedAt))),
    )

    private fun snapshot(observations: List<ContextObservation>, now: Long) = snapshotSuccess(
        ContextSnapshotEngine.build(
            observations,
            snapshotSuccess(
                ContextSnapshotRequest.create(
                    now = time(now),
                    requestedKeys = null,
                    allowedPrivacyClassifications = setOf(
                        ContextPrivacyClassification.PRIVATE,
                        ContextPrivacyClassification.RESTRICTED,
                    ),
                    budget = snapshotSuccess(ContextSnapshotBudget.create(8, 4, 16, 8_192)),
                ),
            ),
        ),
    )

    private fun knownValue(observation: ContextObservation): String =
        (observation.claim as ContextObservationClaim.Known).value.canonicalValue

    private fun source(value: String = "coarse_presence_run"): ContextSourceReference = observationSuccess(
        ContextSourceReference.parse(value),
    )

    private fun time(value: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(value)) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> error("invalid test timestamp")
    }

    private fun <T> success(result: TimePresenceSourceResult<T>): T {
        assertTrue(result is TimePresenceSourceResult.Success)
        return (result as TimePresenceSourceResult.Success).value
    }

    private fun failure(result: TimePresenceSourceResult<*>): TimePresenceSourceError {
        assertTrue(result is TimePresenceSourceResult.Failure)
        return (result as TimePresenceSourceResult.Failure).error
    }

    private fun <T> observationSuccess(result: ContextObservationResult<T>): T =
        (result as ContextObservationResult.Success).value

    private fun <T> snapshotSuccess(result: ContextSnapshotResult<T>): T =
        (result as ContextSnapshotResult.Success).value

    private fun sourceRoot(relative: String): File = listOf(
        File("src/main/java/io/redi/robot/$relative"),
        File("app/src/main/java/io/redi/robot/$relative"),
        File("../app/src/main/java/io/redi/robot/$relative"),
    ).first { it.isDirectory }
}
