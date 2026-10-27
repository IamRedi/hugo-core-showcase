package io.redi.robot.core.authority

import io.redi.robot.core.ActionSource

@JvmInline
value class MotionLeaseId(val value: String) {
    init {
        require(value.isNotBlank()) { "motion lease id must not be blank" }
    }
}

/** The caller must choose an explicit bounded duration; there is no implicit wall-clock policy. */
data class MotionLeasePolicy(val maxDurationMs: Long) {
    init {
        require(maxDurationMs > 0) { "maximum lease duration must be positive" }
    }
}

enum class MotionSourcePriority {
    AI_AGENT,
    LOCAL_SKILL,
    MANUAL,
    SYSTEM,
}

fun ActionSource.motionPriority(): MotionSourcePriority = when (this) {
    ActionSource.AI_AGENT -> MotionSourcePriority.AI_AGENT
    ActionSource.LOCAL_SKILL -> MotionSourcePriority.LOCAL_SKILL
    ActionSource.MANUAL -> MotionSourcePriority.MANUAL
    ActionSource.SYSTEM -> MotionSourcePriority.SYSTEM
}

data class MotionLease(
    val id: MotionLeaseId,
    val source: ActionSource,
    val connectionGeneration: Long,
    val acquiredAtElapsedMs: Long,
    val expiresAtElapsedMs: Long,
) {
    init {
        require(connectionGeneration >= 0) { "connection generation must not be negative" }
        require(acquiredAtElapsedMs >= 0) { "lease acquisition timestamp must not be negative" }
        require(expiresAtElapsedMs > acquiredAtElapsedMs) { "lease expiry must be after acquisition" }
    }
}

data class MotionLeaseRequest(
    val id: MotionLeaseId,
    val source: ActionSource,
    val connectionGeneration: Long,
    val durationMs: Long,
) {
    init {
        require(connectionGeneration >= 0) { "connection generation must not be negative" }
        require(durationMs > 0) { "lease duration must be positive" }
    }
}

data class MotionAuthorityState(
    val connectionGeneration: Long = 0L,
    val activeLease: MotionLease? = null,
) {
    init {
        require(connectionGeneration >= 0) { "connection generation must not be negative" }
        require(activeLease == null || activeLease.connectionGeneration == connectionGeneration) {
            "active lease generation must match authority generation"
        }
    }
}

sealed interface MotionAuthorityEvent {
    val occurredAtElapsedMs: Long

    data class Acquire(
        val request: MotionLeaseRequest,
        override val occurredAtElapsedMs: Long,
    ) : MotionAuthorityEvent

    data class Renew(
        val id: MotionLeaseId,
        val source: ActionSource,
        val connectionGeneration: Long,
        val durationMs: Long,
        override val occurredAtElapsedMs: Long,
    ) : MotionAuthorityEvent

    data class Release(
        val id: MotionLeaseId,
        val source: ActionSource,
        val connectionGeneration: Long,
        override val occurredAtElapsedMs: Long,
    ) : MotionAuthorityEvent

    /** Emitted by an independent monotonic watchdog in T024; reducer never reads a clock itself. */
    data class LeaseExpired(
        val id: MotionLeaseId,
        val connectionGeneration: Long,
        override val occurredAtElapsedMs: Long,
    ) : MotionAuthorityEvent

    /** A transport generation change revokes authority before any later connection can act. */
    data class ReconnectStarted(
        val connectionGeneration: Long,
        override val occurredAtElapsedMs: Long,
    ) : MotionAuthorityEvent

    /** Safety revocation clears ownership without pretending that transport generation changed. */
    data class RevokeAll(
        override val occurredAtElapsedMs: Long,
    ) : MotionAuthorityEvent
}

sealed interface MotionAuthorityOutcome {
    data class Granted(val lease: MotionLease) : MotionAuthorityOutcome
    data class Renewed(val lease: MotionLease) : MotionAuthorityOutcome
    data class Released(val lease: MotionLease) : MotionAuthorityOutcome
    data class Preempted(val previous: MotionLease, val replacement: MotionLease) : MotionAuthorityOutcome
    data class Expired(val lease: MotionLease) : MotionAuthorityOutcome
    data class ReconnectInvalidated(val previous: MotionLease?) : MotionAuthorityOutcome
    data class Revoked(val previous: MotionLease?) : MotionAuthorityOutcome
    data class Rejected(val reason: RejectionReason) : MotionAuthorityOutcome
}

enum class RejectionReason {
    STALE_CONNECTION_GENERATION,
    DURATION_EXCEEDS_POLICY,
    LOWER_OR_EQUAL_PRIORITY,
    NOT_ACTIVE_OWNER,
    LEASE_ALREADY_EXPIRED,
    EXPIRY_NOT_DUE,
}

data class MotionAuthorityTransition(
    val state: MotionAuthorityState,
    val outcome: MotionAuthorityOutcome,
)

/** Pure reducer for exclusive, time-bounded motion ownership. */
class MotionAuthorityReducer(private val policy: MotionLeasePolicy) {
    fun reduce(state: MotionAuthorityState, event: MotionAuthorityEvent): MotionAuthorityTransition {
        require(event.occurredAtElapsedMs >= 0) { "event timestamp must not be negative" }
        return when (event) {
            is MotionAuthorityEvent.Acquire -> acquire(state, event)
            is MotionAuthorityEvent.Renew -> renew(state, event)
            is MotionAuthorityEvent.Release -> release(state, event)
            is MotionAuthorityEvent.LeaseExpired -> expire(state, event)
            is MotionAuthorityEvent.ReconnectStarted -> MotionAuthorityTransition(
                MotionAuthorityState(connectionGeneration = event.connectionGeneration),
                MotionAuthorityOutcome.ReconnectInvalidated(state.activeLease),
            )
            is MotionAuthorityEvent.RevokeAll -> MotionAuthorityTransition(
                state.copy(activeLease = null),
                MotionAuthorityOutcome.Revoked(state.activeLease),
            )
        }
    }

    private fun acquire(state: MotionAuthorityState, event: MotionAuthorityEvent.Acquire): MotionAuthorityTransition {
        val request = event.request
        if (request.connectionGeneration != state.connectionGeneration) return rejected(state, RejectionReason.STALE_CONNECTION_GENERATION)
        if (request.durationMs > policy.maxDurationMs) return rejected(state, RejectionReason.DURATION_EXCEEDS_POLICY)
        val lease = leaseFor(request, event.occurredAtElapsedMs)
        val active = state.activeLease ?: return MotionAuthorityTransition(state.copy(activeLease = lease), MotionAuthorityOutcome.Granted(lease))
        if (request.source.motionPriority() <= active.source.motionPriority()) {
            return rejected(state, RejectionReason.LOWER_OR_EQUAL_PRIORITY)
        }
        return MotionAuthorityTransition(state.copy(activeLease = lease), MotionAuthorityOutcome.Preempted(active, lease))
    }

    private fun renew(state: MotionAuthorityState, event: MotionAuthorityEvent.Renew): MotionAuthorityTransition {
        if (event.connectionGeneration != state.connectionGeneration) return rejected(state, RejectionReason.STALE_CONNECTION_GENERATION)
        if (event.durationMs > policy.maxDurationMs) return rejected(state, RejectionReason.DURATION_EXCEEDS_POLICY)
        val active = state.activeLease ?: return rejected(state, RejectionReason.NOT_ACTIVE_OWNER)
        if (active.id != event.id || active.source != event.source || active.connectionGeneration != event.connectionGeneration) {
            return rejected(state, RejectionReason.NOT_ACTIVE_OWNER)
        }
        if (event.occurredAtElapsedMs >= active.expiresAtElapsedMs) return rejected(state, RejectionReason.LEASE_ALREADY_EXPIRED)
        val renewed = active.copy(expiresAtElapsedMs = event.occurredAtElapsedMs + event.durationMs)
        return MotionAuthorityTransition(state.copy(activeLease = renewed), MotionAuthorityOutcome.Renewed(renewed))
    }

    private fun release(state: MotionAuthorityState, event: MotionAuthorityEvent.Release): MotionAuthorityTransition {
        if (event.connectionGeneration != state.connectionGeneration) return rejected(state, RejectionReason.STALE_CONNECTION_GENERATION)
        val active = state.activeLease ?: return rejected(state, RejectionReason.NOT_ACTIVE_OWNER)
        if (active.id != event.id || active.source != event.source || active.connectionGeneration != event.connectionGeneration) {
            return rejected(state, RejectionReason.NOT_ACTIVE_OWNER)
        }
        return MotionAuthorityTransition(state.copy(activeLease = null), MotionAuthorityOutcome.Released(active))
    }

    private fun expire(state: MotionAuthorityState, event: MotionAuthorityEvent.LeaseExpired): MotionAuthorityTransition {
        if (event.connectionGeneration != state.connectionGeneration) return rejected(state, RejectionReason.STALE_CONNECTION_GENERATION)
        val active = state.activeLease ?: return rejected(state, RejectionReason.NOT_ACTIVE_OWNER)
        if (active.id != event.id || active.connectionGeneration != event.connectionGeneration) {
            return rejected(state, RejectionReason.NOT_ACTIVE_OWNER)
        }
        if (event.occurredAtElapsedMs < active.expiresAtElapsedMs) return rejected(state, RejectionReason.EXPIRY_NOT_DUE)
        return MotionAuthorityTransition(state.copy(activeLease = null), MotionAuthorityOutcome.Expired(active))
    }

    private fun leaseFor(request: MotionLeaseRequest, nowElapsedMs: Long): MotionLease = MotionLease(
        id = request.id,
        source = request.source,
        connectionGeneration = request.connectionGeneration,
        acquiredAtElapsedMs = nowElapsedMs,
        expiresAtElapsedMs = nowElapsedMs + request.durationMs,
    )

    private fun rejected(state: MotionAuthorityState, reason: RejectionReason) =
        MotionAuthorityTransition(state, MotionAuthorityOutcome.Rejected(reason))
}

class MotionAuthorityStore(policy: MotionLeasePolicy, initialState: MotionAuthorityState = MotionAuthorityState()) {
    private val reducer = MotionAuthorityReducer(policy)
    var state: MotionAuthorityState = initialState
        private set

    fun dispatch(event: MotionAuthorityEvent): MotionAuthorityTransition = reducer.reduce(state, event).also { state = it.state }
}
