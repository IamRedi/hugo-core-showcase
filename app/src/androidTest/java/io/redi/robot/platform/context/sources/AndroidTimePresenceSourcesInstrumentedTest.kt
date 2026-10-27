package io.redi.robot.platform.context.sources

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.context.api.ContextObservationClaim
import io.redi.robot.context.api.ContextObservationResult
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextSourceReference
import io.redi.robot.context.api.ContextUnknownReason
import io.redi.robot.context.api.ContextValue
import io.redi.robot.context.sources.CoarsePresenceAcquisition
import io.redi.robot.context.sources.CoarsePresenceContextSource
import io.redi.robot.context.sources.CoarsePresenceRegion
import io.redi.robot.context.sources.CoarsePresenceRequest
import io.redi.robot.context.sources.TimePresenceSourceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

@RunWith(AndroidJUnit4::class)
class AndroidTimePresenceSourcesInstrumentedTest {
    @Test
    fun samsungClockAndAuthorizedCoarsePresenceProduceFreshRedactedContextOnly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(
            "H044 device run requires an explicit temporary coarse-location grant",
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION),
        )

        ActivityScenario.launch(H044ContextSourceVerificationActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            verifyForegroundSources(context)
        }
    }

    private fun verifyForegroundSources(context: Context) {
        val clockBatch = sourceSuccess(AndroidLocalTimeContextSource.observe(source("h044_clock_device_run")))
        assertEquals(
            listOf(
                "time.local.epoch_day",
                "time.local.minute_of_day",
                "time.local.day_of_week",
                "time.local.utc_offset_minutes",
            ),
            clockBatch.observations.map { it.key.value },
        )
        val clockNow = timestamp(SystemClock.elapsedRealtime())
        assertTrue(clockBatch.observations.all { it.isFreshAt(clockNow) })
        assertTrue(clockBatch.observations.all { it.privacyClassification == ContextPrivacyClassification.PRIVATE })

        val backend = requireNotNull(AndroidCoarsePresenceBackend.from(context, timeoutMs = 20_000))
        val result = AtomicReference<CoarsePresenceAcquisition>()
        val latch = CountDownLatch(1)
        val cancellation = backend.acquire { acquisition ->
            result.set(acquisition)
            latch.countDown()
        }
        assertTrue("Expected one bounded Android coarse-location result", latch.await(25, TimeUnit.SECONDS))
        cancellation.close()

        val acquisition = requireNotNull(result.get())
        val region = if (acquisition is CoarsePresenceAcquisition.Observed) {
            val radius = min(
                CoarsePresenceRegion.MAXIMUM_RADIUS_METERS,
                max(CoarsePresenceRegion.MINIMUM_RADIUS_METERS, acquisition.sample.accuracyMeters * 2.0 + 1_000.0),
            )
            sourceSuccess(CoarsePresenceRegion.create(acquisition.sample.latitude, acquisition.sample.longitude, radius))
        } else {
            sourceSuccess(CoarsePresenceRegion.create(0.0, 0.0, CoarsePresenceRegion.MINIMUM_RADIUS_METERS))
        }
        val request = sourceSuccess(
            CoarsePresenceRequest.create(
                sourceReference = source("h044_presence_device_run"),
                region = region,
                maximumSampleAgeMs = 120_000,
                freshnessWindowMs = 60_000,
            ),
        )
        val now = timestamp(SystemClock.elapsedRealtime())
        val presenceBatch = sourceSuccess(
            CoarsePresenceContextSource.observe(request, acquisition, now),
        )
        val presence = presenceBatch.observations.single()

        assertEquals("presence.authorized_region", presence.key.value)
        assertEquals(ContextPrivacyClassification.RESTRICTED, presence.privacyClassification)
        val claimStatus = when (acquisition) {
            is CoarsePresenceAcquisition.Observed -> when (val claim = presence.claim) {
                is ContextObservationClaim.Known -> {
                    assertEquals(true, (claim.value as ContextValue.BooleanValue).value)
                    "known_inside"
                }
                is ContextObservationClaim.Unknown -> {
                    assertEquals(ContextUnknownReason.NOT_OBSERVED, claim.reason)
                    "unknown_stale_or_uncertain"
                }
                is ContextObservationClaim.Conflict -> error("One coarse source cannot emit conflict")
            }
            CoarsePresenceAcquisition.PermissionDenied -> {
                assertEquals(
                    ContextUnknownReason.PERMISSION_DENIED,
                    (presence.claim as ContextObservationClaim.Unknown).reason,
                )
                "unknown_permission_denied"
            }
            CoarsePresenceAcquisition.LocationDisabled,
            CoarsePresenceAcquisition.Unavailable,
            CoarsePresenceAcquisition.TimedOut,
            CoarsePresenceAcquisition.Cancelled,
            -> {
                assertEquals(
                    ContextUnknownReason.SOURCE_UNAVAILABLE,
                    (presence.claim as ContextObservationClaim.Unknown).reason,
                )
                "unknown_source_unavailable"
            }
        }
        assertTrue(presence.isFreshAt(now))
        assertFalse(presenceBatch.persistsHistory)
        assertFalse(presenceBatch.grantsAuthorization)
        assertFalse(presenceBatch.grantsExecutionAuthority)
        if (acquisition is CoarsePresenceAcquisition.Observed) {
            assertTrue(acquisition.sample.toString().contains("redacted"))
            assertFalse(acquisition.sample.toString().contains(acquisition.sample.accuracyMeters.toString()))
        }

        val requestedPermissions = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toSet()
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in requestedPermissions)
        assertFalse(Manifest.permission.ACCESS_BACKGROUND_LOCATION in requestedPermissions)

        println(
            "H044_DEVICE_TRACE " +
                "model=${Build.MODEL} timeKeys=${clockBatch.observations.size} " +
                "permission=coarse_foreground result=$claimStatus freshness=bounded " +
                "location=redacted history=none authority=none",
        )
    }

    private fun source(value: String): ContextSourceReference = when (
        val result = ContextSourceReference.parse(value)
    ) {
        is ContextObservationResult.Success -> result.value
        is ContextObservationResult.Failure -> error("invalid H044 source reference")
    }

    private fun timestamp(value: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(value)) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> error("invalid H044 monotonic timestamp")
    }

    private fun <T> sourceSuccess(result: TimePresenceSourceResult<T>): T = when (result) {
        is TimePresenceSourceResult.Success -> result.value
        is TimePresenceSourceResult.Failure -> error("H044 source failed: ${result.error}")
    }
}
