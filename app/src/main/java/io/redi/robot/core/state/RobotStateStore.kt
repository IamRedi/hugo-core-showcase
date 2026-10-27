package io.redi.robot.core.state

import io.redi.robot.core.authority.MotionAuthorityState

/** Distinguishes an absent observation from a capability that is known not to be available. */
enum class StateAvailability {
    UNKNOWN,
    AVAILABLE,
    UNAVAILABLE,
}

/** Immutable observation with a monotonic timestamp supplied by the caller. */
data class TimedState<T>(
    val availability: StateAvailability,
    val value: T? = null,
    val observedAtElapsedMs: Long? = null,
    val unavailableReason: String? = null,
) {
    init {
        require(observedAtElapsedMs == null || observedAtElapsedMs >= 0) { "observation timestamp must not be negative" }
        when (availability) {
            StateAvailability.AVAILABLE -> require(value != null && observedAtElapsedMs != null) {
                "available state requires a value and timestamp"
            }
            StateAvailability.UNAVAILABLE -> require(value == null && observedAtElapsedMs != null) {
                "unavailable state must not carry a value and requires a timestamp"
            }
            StateAvailability.UNKNOWN -> require(value == null && observedAtElapsedMs == null && unavailableReason == null) {
                "unknown state must not carry a value, timestamp, or reason"
            }
        }
    }

    companion object {
        fun <T> unknown(): TimedState<T> = TimedState(StateAvailability.UNKNOWN)
        fun <T> available(value: T, observedAtElapsedMs: Long): TimedState<T> =
            TimedState(StateAvailability.AVAILABLE, value, observedAtElapsedMs)

        fun <T> unavailable(reason: String, observedAtElapsedMs: Long): TimedState<T> =
            TimedState(StateAvailability.UNAVAILABLE, observedAtElapsedMs = observedAtElapsedMs, unavailableReason = reason)
    }
}

/** Semantic transport state only. Raw GATT statuses and Android types remain outside core. */
enum class RobotConnectionPhase {
    IDLE,
    CONNECTING,
    CONNECTED,
    TRANSPORT_READY,
    DISCONNECTING,
    DISCONNECTED,
    FAILED,
}

data class RobotConnectionState(
    val phase: RobotConnectionPhase,
    val generation: Long,
    val failureSummary: String? = null,
) {
    init {
        require(generation >= 0) { "connection generation must not be negative" }
    }
}

/** Selection result without GATT inventory, UUID, or packet details. */
enum class RobotProfileStatus {
    SELECTED,
    AMBIGUOUS,
    PARTIAL,
    UNSUPPORTED,
}

data class RobotProfileState(
    val status: RobotProfileStatus,
    val profileId: String? = null,
) {
    init {
        require((status == RobotProfileStatus.SELECTED) == (profileId != null)) {
            "only a selected profile may carry a profile id"
        }
    }
}

enum class TelemetryKind {
    INITIALIZATION,
    CONTACT,
    DOCK,
    TOUCH,
    EXTERNAL_POWER,
    BATTERY,
    OPAQUE_STATUS,
}

enum class TelemetryFreshness {
    UNKNOWN,
    UNAVAILABLE,
    FRESH,
    STALE,
}

data class RobotTelemetryState(
    val latest: TimedState<TelemetryKind> = TimedState.unknown(),
) {
    fun freshnessAt(nowElapsedMs: Long, maxAgeMs: Long): TelemetryFreshness {
        require(nowElapsedMs >= 0) { "current timestamp must not be negative" }
        require(maxAgeMs >= 0) { "maximum telemetry age must not be negative" }
        return when (latest.availability) {
            StateAvailability.UNKNOWN -> TelemetryFreshness.UNKNOWN
            StateAvailability.UNAVAILABLE -> TelemetryFreshness.UNAVAILABLE
            StateAvailability.AVAILABLE -> {
                val ageMs = nowElapsedMs - checkNotNull(latest.observedAtElapsedMs)
                if (ageMs in 0..maxAgeMs) TelemetryFreshness.FRESH else TelemetryFreshness.STALE
            }
        }
    }
}

/**
 * Aggregate consumed by future UI and orchestration. Safety remains availability-only until T023.
 */
data class RobotStateSnapshot(
    val connection: TimedState<RobotConnectionState> = TimedState.unknown(),
    val profile: TimedState<RobotProfileState> = TimedState.unknown(),
    val telemetry: RobotTelemetryState = RobotTelemetryState(),
    val authority: TimedState<MotionAuthorityState> = TimedState.unknown(),
    val safety: TimedState<Unit> = TimedState.unknown(),
)

sealed interface RobotStateEvent {
    val occurredAtElapsedMs: Long

    data class ConnectionObserved(
        val state: RobotConnectionState,
        override val occurredAtElapsedMs: Long,
    ) : RobotStateEvent

    data class ProfileObserved(
        val state: RobotProfileState,
        override val occurredAtElapsedMs: Long,
    ) : RobotStateEvent

    data class ProfileUnavailable(
        val reason: String,
        override val occurredAtElapsedMs: Long,
    ) : RobotStateEvent

    data class TelemetryObserved(
        val kind: TelemetryKind,
        override val occurredAtElapsedMs: Long,
    ) : RobotStateEvent

    data class TelemetryUnavailable(
        val reason: String,
        override val occurredAtElapsedMs: Long,
    ) : RobotStateEvent

    data class AuthorityObserved(
        val state: MotionAuthorityState,
        override val occurredAtElapsedMs: Long,
    ) : RobotStateEvent

    data class SafetyAvailabilityObserved(
        val availability: StateAvailability,
        val unavailableReason: String? = null,
        override val occurredAtElapsedMs: Long,
    ) : RobotStateEvent

    /** A new generation invalidates every profile/telemetry/authority/safety observation. */
    data class ReconnectStarted(
        val generation: Long,
        override val occurredAtElapsedMs: Long,
    ) : RobotStateEvent
}

object RobotStateReducer {
    fun reduce(snapshot: RobotStateSnapshot, event: RobotStateEvent): RobotStateSnapshot {
        require(event.occurredAtElapsedMs >= 0) { "event timestamp must not be negative" }
        return when (event) {
            is RobotStateEvent.ConnectionObserved -> snapshot.copy(
                connection = TimedState.available(event.state, event.occurredAtElapsedMs),
            )
            is RobotStateEvent.ProfileObserved -> snapshot.copy(
                profile = TimedState.available(event.state, event.occurredAtElapsedMs),
            )
            is RobotStateEvent.ProfileUnavailable -> snapshot.copy(
                profile = TimedState.unavailable(event.reason, event.occurredAtElapsedMs),
            )
            is RobotStateEvent.TelemetryObserved -> snapshot.copy(
                telemetry = RobotTelemetryState(TimedState.available(event.kind, event.occurredAtElapsedMs)),
            )
            is RobotStateEvent.TelemetryUnavailable -> snapshot.copy(
                telemetry = RobotTelemetryState(TimedState.unavailable(event.reason, event.occurredAtElapsedMs)),
            )
            is RobotStateEvent.AuthorityObserved -> snapshot.copy(
                authority = TimedState.available(event.state, event.occurredAtElapsedMs),
            )
            is RobotStateEvent.SafetyAvailabilityObserved -> snapshot.copy(
                safety = availabilityState(event.availability, event.unavailableReason, event.occurredAtElapsedMs),
            )
            is RobotStateEvent.ReconnectStarted -> RobotStateSnapshot(
                connection = TimedState.available(
                    RobotConnectionState(RobotConnectionPhase.CONNECTING, event.generation),
                    event.occurredAtElapsedMs,
                ),
            )
        }
    }

    private fun availabilityState(
        availability: StateAvailability,
        unavailableReason: String?,
        occurredAtElapsedMs: Long,
    ): TimedState<Unit> = when (availability) {
        StateAvailability.AVAILABLE -> TimedState.available(Unit, occurredAtElapsedMs)
        StateAvailability.UNAVAILABLE -> TimedState.unavailable(
            requireNotNull(unavailableReason) { "unavailable state requires a reason" },
            occurredAtElapsedMs,
        )
        StateAvailability.UNKNOWN -> TimedState.unknown()
    }
}

/** Mutable holder with a pure reducer; publishing and threading are owned by callers. */
class RobotStateStore(initialSnapshot: RobotStateSnapshot = RobotStateSnapshot()) {
    var snapshot: RobotStateSnapshot = initialSnapshot
        private set

    fun dispatch(event: RobotStateEvent): RobotStateSnapshot = RobotStateReducer.reduce(snapshot, event).also { snapshot = it }
}
