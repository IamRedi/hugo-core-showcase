package io.redi.robot.context.sources

import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.context.api.ContextConfidence
import io.redi.robot.context.api.ContextKey
import io.redi.robot.context.api.ContextObservation
import io.redi.robot.context.api.ContextObservationClaim
import io.redi.robot.context.api.ContextObservationId
import io.redi.robot.context.api.ContextObservationResult
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextSourceReference
import io.redi.robot.context.api.ContextSourceType
import io.redi.robot.context.api.ContextSymbol
import io.redi.robot.context.api.ContextUnknownReason
import io.redi.robot.context.api.ContextValue
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections
import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class TimePresenceObservationBatch internal constructor(observations: List<ContextObservation>) {
    val observations: List<ContextObservation> = Collections.unmodifiableList(observations.toList())

    val persistsHistory: Boolean get() = false
    val grantsTrust: Boolean get() = false
    val grantsPermission: Boolean get() = false
    val grantsAuthorization: Boolean get() = false
    val grantsSafetyApproval: Boolean get() = false
    val grantsExecutionAuthority: Boolean get() = false
}

sealed interface TimePresenceSourceResult<out T> {
    data class Success<T>(val value: T) : TimePresenceSourceResult<T>
    data class Failure(val error: TimePresenceSourceError) : TimePresenceSourceResult<Nothing>
}

sealed interface TimePresenceSourceError {
    data object InvalidWallClock : TimePresenceSourceError
    data object InvalidZone : TimePresenceSourceError
    data object InvalidRegion : TimePresenceSourceError
    data object InvalidAccuracy : TimePresenceSourceError
    data object InvalidFreshnessWindow : TimePresenceSourceError
    data object TimestampOverflow : TimePresenceSourceError
    data object InvalidObservation : TimePresenceSourceError
}

class LocalTimeSample private constructor(
    val wallClockEpochMillis: Long,
    val zoneId: String,
    val observedAt: MonotonicTimestampMs,
) {
    companion object {
        fun create(
            wallClockEpochMillis: Long,
            zoneId: String,
            observedAt: MonotonicTimestampMs,
        ): TimePresenceSourceResult<LocalTimeSample> {
            if (wallClockEpochMillis < 0L) return failure(TimePresenceSourceError.InvalidWallClock)
            val canonicalZone = runCatching { ZoneId.of(zoneId).id }.getOrNull()
                ?: return failure(TimePresenceSourceError.InvalidZone)
            if (canonicalZone != zoneId) return failure(TimePresenceSourceError.InvalidZone)
            return TimePresenceSourceResult.Success(
                LocalTimeSample(wallClockEpochMillis, canonicalZone, observedAt),
            )
        }
    }
}

/** Pure H044 local-time projection. It has no scheduler, persistence, locale, or Android dependency. */
object LocalTimeContextSource {
    fun observe(
        sample: LocalTimeSample,
        sourceReference: ContextSourceReference,
    ): TimePresenceSourceResult<TimePresenceObservationBatch> {
        val zoned = runCatching {
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(sample.wallClockEpochMillis), ZoneId.of(sample.zoneId))
        }.getOrElse { return failure(TimePresenceSourceError.InvalidWallClock) }
        val remainingInMinute = MILLIS_PER_MINUTE - Math.floorMod(sample.wallClockEpochMillis, MILLIS_PER_MINUTE)
        val expiresAtValue = runCatching { Math.addExact(sample.observedAt.value, remainingInMinute) }
            .getOrElse { return failure(TimePresenceSourceError.TimestampOverflow) }
        val expiresAt = timestamp(expiresAtValue)
            ?: return failure(TimePresenceSourceError.TimestampOverflow)
        val fields = listOf(
            LOCAL_EPOCH_DAY to ContextValue.IntegerValue(zoned.toLocalDate().toEpochDay()),
            LOCAL_MINUTE_OF_DAY to ContextValue.IntegerValue((zoned.hour * 60L) + zoned.minute),
            LOCAL_DAY_OF_WEEK to ContextValue.SymbolValue(
                observationValue(ContextSymbol.parse(zoned.dayOfWeek.name.lowercase())),
            ),
            UTC_OFFSET_MINUTES to ContextValue.IntegerValue(zoned.offset.totalSeconds / 60L),
        )
        val observations = fields.map { (key, value) ->
            observation(
                idSeed = "time|${sourceReference.value}|${sample.wallClockEpochMillis / MILLIS_PER_MINUTE}|" +
                    "${sample.zoneId}|${key.value}",
                sourceType = HOST_CLOCK,
                sourceReference = sourceReference,
                key = key,
                claim = ContextObservationClaim.Known(value),
                confidence = FULL_CONFIDENCE,
                privacy = ContextPrivacyClassification.PRIVATE,
                observedAt = sample.observedAt,
                expiresAt = expiresAt,
            ) ?: return failure(TimePresenceSourceError.InvalidObservation)
        }
        return TimePresenceSourceResult.Success(TimePresenceObservationBatch(observations))
    }
}

class CoarsePresenceRegion private constructor(
    internal val centerLatitude: Double,
    internal val centerLongitude: Double,
    val radiusMeters: Double,
) {
    companion object {
        const val MINIMUM_RADIUS_METERS: Double = 3_000.0
        const val MAXIMUM_RADIUS_METERS: Double = 500_000.0

        fun create(
            centerLatitude: Double,
            centerLongitude: Double,
            radiusMeters: Double,
        ): TimePresenceSourceResult<CoarsePresenceRegion> = if (
            !centerLatitude.isFinite() || centerLatitude !in -90.0..90.0 ||
            !centerLongitude.isFinite() || centerLongitude !in -180.0..180.0 ||
            !radiusMeters.isFinite() || radiusMeters !in MINIMUM_RADIUS_METERS..MAXIMUM_RADIUS_METERS
        ) {
            failure(TimePresenceSourceError.InvalidRegion)
        } else {
            TimePresenceSourceResult.Success(
                CoarsePresenceRegion(centerLatitude, centerLongitude, radiusMeters),
            )
        }
    }
}

class CoarsePresenceRequest private constructor(
    val sourceReference: ContextSourceReference,
    internal val region: CoarsePresenceRegion,
    val maximumSampleAgeMs: Long,
    val freshnessWindowMs: Long,
) {
    companion object {
        const val MINIMUM_FRESHNESS_MS: Long = 1_000L
        const val MAXIMUM_FRESHNESS_MS: Long = 60_000L
        const val MAXIMUM_SAMPLE_AGE_MS: Long = 120_000L

        fun create(
            sourceReference: ContextSourceReference,
            region: CoarsePresenceRegion,
            maximumSampleAgeMs: Long,
            freshnessWindowMs: Long,
        ): TimePresenceSourceResult<CoarsePresenceRequest> = if (
            maximumSampleAgeMs !in 1L..MAXIMUM_SAMPLE_AGE_MS ||
            freshnessWindowMs !in MINIMUM_FRESHNESS_MS..MAXIMUM_FRESHNESS_MS
        ) {
            failure(TimePresenceSourceError.InvalidFreshnessWindow)
        } else {
            TimePresenceSourceResult.Success(
                CoarsePresenceRequest(sourceReference, region, maximumSampleAgeMs, freshnessWindowMs),
            )
        }
    }
}

/** Ephemeral platform sample. Coordinates must be discarded immediately after [observe]. */
class CoarseLocationSample private constructor(
    internal val latitude: Double,
    internal val longitude: Double,
    val accuracyMeters: Double,
    val observedAt: MonotonicTimestampMs,
) {
    override fun toString(): String = "CoarseLocationSample(location=redacted)"

    companion object {
        fun create(
            latitude: Double,
            longitude: Double,
            accuracyMeters: Double,
            observedAt: MonotonicTimestampMs,
        ): TimePresenceSourceResult<CoarseLocationSample> = when {
            !latitude.isFinite() || latitude !in -90.0..90.0 ||
                !longitude.isFinite() || longitude !in -180.0..180.0 ->
                failure(TimePresenceSourceError.InvalidRegion)
            !accuracyMeters.isFinite() || accuracyMeters !in 0.0..1_000_000.0 ->
                failure(TimePresenceSourceError.InvalidAccuracy)
            else -> TimePresenceSourceResult.Success(
                CoarseLocationSample(latitude, longitude, accuracyMeters, observedAt),
            )
        }
    }
}

sealed interface CoarsePresenceAcquisition {
    data class Observed(val sample: CoarseLocationSample) : CoarsePresenceAcquisition
    data object PermissionDenied : CoarsePresenceAcquisition
    data object LocationDisabled : CoarsePresenceAcquisition
    data object Unavailable : CoarsePresenceAcquisition
    data object TimedOut : CoarsePresenceAcquisition
    data object Cancelled : CoarsePresenceAcquisition
}

fun interface CoarsePresenceCancellation : AutoCloseable {
    override fun close()
}

fun interface CoarsePresenceBackend {
    fun acquire(callback: (CoarsePresenceAcquisition) -> Unit): CoarsePresenceCancellation
}

/**
 * Converts one permission-gated coarse sample into one short-lived H036 boolean/unknown claim.
 * Neither coordinates nor an Android permission become part of the observation.
 */
object CoarsePresenceContextSource {
    fun observe(
        request: CoarsePresenceRequest,
        acquisition: CoarsePresenceAcquisition,
        now: MonotonicTimestampMs,
    ): TimePresenceSourceResult<TimePresenceObservationBatch> {
        val projected = when (acquisition) {
            is CoarsePresenceAcquisition.Observed -> projectObserved(request, acquisition.sample, now)
            CoarsePresenceAcquisition.PermissionDenied -> unknown(
                request,
                now,
                ContextUnknownReason.PERMISSION_DENIED,
                "permission_denied",
            )
            CoarsePresenceAcquisition.LocationDisabled -> unknown(
                request,
                now,
                ContextUnknownReason.SOURCE_UNAVAILABLE,
                "location_disabled",
            )
            CoarsePresenceAcquisition.Unavailable -> unknown(
                request,
                now,
                ContextUnknownReason.SOURCE_UNAVAILABLE,
                "unavailable",
            )
            CoarsePresenceAcquisition.TimedOut -> unknown(
                request,
                now,
                ContextUnknownReason.SOURCE_UNAVAILABLE,
                "timed_out",
            )
            CoarsePresenceAcquisition.Cancelled -> unknown(
                request,
                now,
                ContextUnknownReason.SOURCE_UNAVAILABLE,
                "cancelled",
            )
        } ?: return failure(TimePresenceSourceError.TimestampOverflow)
        return TimePresenceSourceResult.Success(TimePresenceObservationBatch(listOf(projected)))
    }

    private fun projectObserved(
        request: CoarsePresenceRequest,
        sample: CoarseLocationSample,
        now: MonotonicTimestampMs,
    ): ContextObservation? {
        val age = now.value - sample.observedAt.value
        if (age < 0L || age > request.maximumSampleAgeMs) {
            return unknown(request, now, ContextUnknownReason.NOT_OBSERVED, "stale_or_future")
        }
        val distance = distanceMeters(
            sample.latitude,
            sample.longitude,
            request.region.centerLatitude,
            request.region.centerLongitude,
        )
        val definitelyInside = distance + sample.accuracyMeters <= request.region.radiusMeters
        val definitelyOutside = max(0.0, distance - sample.accuracyMeters) > request.region.radiusMeters
        val claim = when {
            definitelyInside -> ContextObservationClaim.Known(ContextValue.BooleanValue(true))
            definitelyOutside -> ContextObservationClaim.Known(ContextValue.BooleanValue(false))
            else -> ContextObservationClaim.Unknown(ContextUnknownReason.NOT_OBSERVED)
        }
        val expiresAtValue = runCatching {
            Math.addExact(sample.observedAt.value, request.freshnessWindowMs)
        }.getOrNull() ?: return null
        if (now.value >= expiresAtValue) {
            return unknown(request, now, ContextUnknownReason.NOT_OBSERVED, "expired_sample")
        }
        val expiresAt = timestamp(expiresAtValue) ?: return null
        val confidence = when (claim) {
            is ContextObservationClaim.Known -> {
                val ratio = min(1.0, sample.accuracyMeters / request.region.radiusMeters)
                observationValue(ContextConfidence.create((950.0 - ratio * 450.0).toInt().coerceIn(500, 950)))
            }
            else -> FULL_CONFIDENCE
        }
        return observation(
            idSeed = "presence|${request.sourceReference.value}|${sample.observedAt.value}|" +
                "${claim.wireStatus}|${claim.canonicalSeed()}",
            sourceType = HOST_COARSE_LOCATION,
            sourceReference = request.sourceReference,
            key = AUTHORIZED_REGION_PRESENCE,
            claim = claim,
            confidence = confidence,
            privacy = ContextPrivacyClassification.RESTRICTED,
            observedAt = sample.observedAt,
            expiresAt = expiresAt,
        )
    }

    private fun unknown(
        request: CoarsePresenceRequest,
        now: MonotonicTimestampMs,
        reason: ContextUnknownReason,
        seed: String,
    ): ContextObservation? {
        val expiresAtValue = runCatching { Math.addExact(now.value, request.freshnessWindowMs) }.getOrNull()
            ?: return null
        val expiresAt = timestamp(expiresAtValue) ?: return null
        return observation(
            idSeed = "presence|${request.sourceReference.value}|${now.value}|unknown|${reason.wireValue}|$seed",
            sourceType = HOST_COARSE_LOCATION,
            sourceReference = request.sourceReference,
            key = AUTHORIZED_REGION_PRESENCE,
            claim = ContextObservationClaim.Unknown(reason),
            confidence = FULL_CONFIDENCE,
            privacy = ContextPrivacyClassification.RESTRICTED,
            observedAt = now,
            expiresAt = expiresAt,
        )
    }
}

private val HOST_CLOCK = observationValue(ContextSourceType.parse("host.clock"))
private val HOST_COARSE_LOCATION = observationValue(ContextSourceType.parse("host.location.coarse"))
private val LOCAL_EPOCH_DAY = observationValue(ContextKey.parse("time.local.epoch_day"))
private val LOCAL_MINUTE_OF_DAY = observationValue(ContextKey.parse("time.local.minute_of_day"))
private val LOCAL_DAY_OF_WEEK = observationValue(ContextKey.parse("time.local.day_of_week"))
private val UTC_OFFSET_MINUTES = observationValue(ContextKey.parse("time.local.utc_offset_minutes"))
private val AUTHORIZED_REGION_PRESENCE = observationValue(ContextKey.parse("presence.authorized_region"))
private val FULL_CONFIDENCE = observationValue(ContextConfidence.create(1_000))

private const val MILLIS_PER_MINUTE = 60_000L
private const val EARTH_RADIUS_METERS = 6_371_008.8

@Suppress("LongParameterList")
private fun observation(
    idSeed: String,
    sourceType: ContextSourceType,
    sourceReference: ContextSourceReference,
    key: ContextKey,
    claim: ContextObservationClaim,
    confidence: ContextConfidence,
    privacy: ContextPrivacyClassification,
    observedAt: MonotonicTimestampMs,
    expiresAt: MonotonicTimestampMs,
): ContextObservation? = when (
    val result = ContextObservation.create(
        observationId = ContextObservationId.fromUuid(
            UUID.nameUUIDFromBytes(idSeed.toByteArray(StandardCharsets.UTF_8)),
        ),
        sourceType = sourceType,
        sourceReference = sourceReference,
        key = key,
        claim = claim,
        confidence = confidence,
        privacyClassification = privacy,
        observedAt = observedAt,
        expiresAt = expiresAt,
    )
) {
    is ContextObservationResult.Success -> result.value
    is ContextObservationResult.Failure -> null
}

private fun ContextObservationClaim.canonicalSeed(): String = when (this) {
    is ContextObservationClaim.Known -> "${value.wireType}:${value.canonicalValue}"
    is ContextObservationClaim.Unknown -> reason.wireValue
    is ContextObservationClaim.Conflict -> candidates.joinToString(",") { "${it.wireType}:${it.canonicalValue}" }
}

private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val first = Math.toRadians(lat1)
    val second = Math.toRadians(lat2)
    val haversine = sin(dLat / 2.0) * sin(dLat / 2.0) +
        cos(first) * cos(second) * sin(dLon / 2.0) * sin(dLon / 2.0)
    return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
}

private fun timestamp(value: Long): MonotonicTimestampMs? = when (val result = MonotonicTimestampMs.create(value)) {
    is CapabilityEnvelopeResult.Success -> result.value
    is CapabilityEnvelopeResult.Failure -> null
}

private fun <T> observationValue(result: ContextObservationResult<T>): T = when (result) {
    is ContextObservationResult.Success -> result.value
    is ContextObservationResult.Failure -> error("Invalid H044 internal context constant: ${result.error}")
}

private fun <T> failure(error: TimePresenceSourceError): TimePresenceSourceResult<T> =
    TimePresenceSourceResult.Failure(error)
