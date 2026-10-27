package io.redi.robot.platform.context.sources

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.context.api.ContextSourceReference
import io.redi.robot.context.sources.CoarseLocationSample
import io.redi.robot.context.sources.CoarsePresenceAcquisition
import io.redi.robot.context.sources.CoarsePresenceBackend
import io.redi.robot.context.sources.CoarsePresenceCancellation
import io.redi.robot.context.sources.LocalTimeContextSource
import io.redi.robot.context.sources.LocalTimeSample
import io.redi.robot.context.sources.TimePresenceObservationBatch
import io.redi.robot.context.sources.TimePresenceSourceResult
import java.time.ZoneId
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Android clock ownership boundary for H044. */
object AndroidLocalTimeContextSource {
    fun observe(
        sourceReference: ContextSourceReference,
    ): TimePresenceSourceResult<TimePresenceObservationBatch> {
        val observedAt = timestamp(SystemClock.elapsedRealtime())
            ?: return TimePresenceSourceResult.Failure(
                io.redi.robot.context.sources.TimePresenceSourceError.TimestampOverflow,
            )
        val sample = when (
            val created = LocalTimeSample.create(
                wallClockEpochMillis = System.currentTimeMillis(),
                zoneId = ZoneId.systemDefault().id,
                observedAt = observedAt,
            )
        ) {
            is TimePresenceSourceResult.Success -> created.value
            is TimePresenceSourceResult.Failure -> return created
        }
        return LocalTimeContextSource.observe(sample, sourceReference)
    }
}

/**
 * One-shot approximate foreground-location bridge. Android Location never crosses this boundary and
 * no listener, coordinate, provider name, or fix is retained after completion/cancellation.
 */
class AndroidCoarsePresenceBackend private constructor(
    private val context: Context,
    private val locationManager: LocationManager,
    private val callbackExecutor: Executor,
    private val handler: Handler,
    private val timeoutMs: Long,
) : CoarsePresenceBackend {
    override fun acquire(callback: (CoarsePresenceAcquisition) -> Unit): CoarsePresenceCancellation {
        val completed = AtomicBoolean(false)
        var cancellationSignal: CancellationSignal? = null
        var legacyListener: LocationListener? = null

        fun finish(result: CoarsePresenceAcquisition) {
            if (!completed.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(completed)
            cancellationSignal?.cancel()
            legacyListener?.let(locationManager::removeUpdates)
            callback(result)
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            finish(CoarsePresenceAcquisition.PermissionDenied)
            return CoarsePresenceCancellation { }
        }

        val providers = enabledProviders()
        if (providers.isEmpty()) {
            finish(
                if (hasLocationProvider()) {
                    CoarsePresenceAcquisition.LocationDisabled
                } else {
                    CoarsePresenceAcquisition.Unavailable
                },
            )
            return CoarsePresenceCancellation { }
        }

        val timeout = Runnable { finish(CoarsePresenceAcquisition.TimedOut) }
        handler.postAtTime(timeout, completed, SystemClock.uptimeMillis() + timeoutMs)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var providerIndex = 0
            lateinit var requestNextProvider: () -> Unit
            requestNextProvider = {
                if (!completed.get()) {
                    val provider = providers.getOrNull(providerIndex++)
                    if (provider == null) {
                        finish(CoarsePresenceAcquisition.Unavailable)
                    } else {
                        try {
                            cancellationSignal?.cancel()
                            cancellationSignal = CancellationSignal()
                            locationManager.getCurrentLocation(
                                provider,
                                cancellationSignal,
                                callbackExecutor,
                            ) { location ->
                                if (location == null) requestNextProvider() else finish(location.toAcquisition())
                            }
                        } catch (_: SecurityException) {
                            finish(CoarsePresenceAcquisition.PermissionDenied)
                        } catch (_: RuntimeException) {
                            requestNextProvider()
                        }
                    }
                }
            }
            requestNextProvider()
        } else {
            try {
                val provider = providers.first()
                @Suppress("DEPRECATION")
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) = finish(location.toAcquisition())
                    override fun onProviderDisabled(provider: String) = finish(CoarsePresenceAcquisition.LocationDisabled)
                    override fun onProviderEnabled(provider: String) = Unit
                    @Deprecated("Deprecated Android callback")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                legacyListener = listener
                @Suppress("DEPRECATION")
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (_: SecurityException) {
                finish(CoarsePresenceAcquisition.PermissionDenied)
            } catch (_: RuntimeException) {
                finish(CoarsePresenceAcquisition.Unavailable)
            }
        }

        return CoarsePresenceCancellation { finish(CoarsePresenceAcquisition.Cancelled) }
    }

    private fun enabledProviders(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
    }.distinct().filter { provider ->
        runCatching {
            locationManager.allProviders.contains(provider) && locationManager.isProviderEnabled(provider)
        }.getOrDefault(false)
    }

    private fun hasLocationProvider(): Boolean = runCatching {
        locationManager.allProviders.any { it in setOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER) }
    }.getOrDefault(false)

    private fun Location?.toAcquisition(): CoarsePresenceAcquisition {
        val location = this ?: return CoarsePresenceAcquisition.Unavailable
        val observedAt = timestamp(location.elapsedRealtimeNanos / NANOS_PER_MILLISECOND)
            ?: return CoarsePresenceAcquisition.Unavailable
        return when (
            val sample = CoarseLocationSample.create(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy.toDouble(),
                observedAt = observedAt,
            )
        ) {
            is TimePresenceSourceResult.Success -> CoarsePresenceAcquisition.Observed(sample.value)
            is TimePresenceSourceResult.Failure -> CoarsePresenceAcquisition.Unavailable
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 15_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        fun from(
            context: Context,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        ): AndroidCoarsePresenceBackend? {
            if (timeoutMs !in 1_000L..60_000L) return null
            val applicationContext = context.applicationContext
            val manager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return null
            return AndroidCoarsePresenceBackend(
                context = applicationContext,
                locationManager = manager,
                callbackExecutor = ContextCompat.getMainExecutor(applicationContext),
                handler = Handler(Looper.getMainLooper()),
                timeoutMs = timeoutMs,
            )
        }
    }
}

private fun timestamp(value: Long): MonotonicTimestampMs? = when (val result = MonotonicTimestampMs.create(value)) {
    is CapabilityEnvelopeResult.Success -> result.value
    is CapabilityEnvelopeResult.Failure -> null
}
