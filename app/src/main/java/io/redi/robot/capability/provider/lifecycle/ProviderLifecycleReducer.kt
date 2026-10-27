package io.redi.robot.capability.provider.lifecycle

import io.redi.robot.capability.provider.ProviderGeneration

/**
 * Pure provider lifecycle reducer. It models session sequencing only; it does not establish
 * trust, authorization, safety approval, capability identity, physical feasibility, or reconnect
 * policy. Callers must serialize reductions for a provider instance.
 */
object ProviderLifecycleReducer {
    fun reduce(
        snapshot: ProviderLifecycleSnapshot,
        event: ProviderLifecycleEvent,
    ): ProviderLifecycleReduction {
        if (event is ProviderLifecycleEvent.Discovered) {
            return reduceDiscovery(snapshot, event)
        }

        val latestGeneration = snapshot.latestGeneration
            ?: return ignored(snapshot, ProviderLifecycleIgnoredReason.NO_ACTIVE_GENERATION)
        when {
            event.generation.value < latestGeneration.value -> return ignored(
                snapshot,
                ProviderLifecycleIgnoredReason.STALE_GENERATION,
            )
            event.generation.value > latestGeneration.value -> return ignored(
                snapshot,
                ProviderLifecycleIgnoredReason.FUTURE_GENERATION,
            )
        }

        return when (event) {
            is ProviderLifecycleEvent.IdentityResolved -> whenState(
                snapshot,
                ProviderLifecycleState.Discovered::class,
            ) { ProviderLifecycleState.Identified(event.generation) }
            is ProviderLifecycleEvent.ConnectRequested -> whenState(
                snapshot,
                ProviderLifecycleState.Identified::class,
            ) { ProviderLifecycleState.Connecting(event.generation) }
            is ProviderLifecycleEvent.ConnectionEstablished -> whenState(
                snapshot,
                ProviderLifecycleState.Connecting::class,
            ) { ProviderLifecycleState.Connected(event.generation) }
            is ProviderLifecycleEvent.NegotiationRequested -> whenState(
                snapshot,
                ProviderLifecycleState.Connected::class,
            ) { ProviderLifecycleState.Negotiating(event.generation) }
            is ProviderLifecycleEvent.CapabilitiesRegistered -> whenState(
                snapshot,
                ProviderLifecycleState.Negotiating::class,
            ) { ProviderLifecycleState.Registered(event.generation) }
            is ProviderLifecycleEvent.DisconnectRequested -> reduceDisconnectRequested(snapshot)
            is ProviderLifecycleEvent.Disconnected -> reduceDisconnected(snapshot)
            is ProviderLifecycleEvent.CancellationRequested -> reduceCancellationRequested(snapshot)
            is ProviderLifecycleEvent.CancellationCompleted -> reduceCancellationCompleted(snapshot)
            is ProviderLifecycleEvent.Failed -> reduceFailure(snapshot, event.failure)
            is ProviderLifecycleEvent.Discovered -> error("Handled before generation comparison")
        }
    }

    private fun reduceDiscovery(
        snapshot: ProviderLifecycleSnapshot,
        event: ProviderLifecycleEvent.Discovered,
    ): ProviderLifecycleReduction {
        val latest = snapshot.latestGeneration
        if (latest != null && event.generation.value <= latest.value) {
            return ignored(
                snapshot,
                if (event.generation.value < latest.value) {
                    ProviderLifecycleIgnoredReason.STALE_GENERATION
                } else {
                    ProviderLifecycleIgnoredReason.REUSED_GENERATION
                },
            )
        }
        return when (snapshot.state) {
            ProviderLifecycleState.Inactive,
            is ProviderLifecycleState.Cancelled,
            is ProviderLifecycleState.Disconnected,
            is ProviderLifecycleState.Failed,
            -> transitioned(snapshot, ProviderLifecycleState.Discovered(event.generation))
            else -> ignored(snapshot, ProviderLifecycleIgnoredReason.INVALID_TRANSITION)
        }
    }

    private fun reduceDisconnectRequested(snapshot: ProviderLifecycleSnapshot): ProviderLifecycleReduction =
        when (val state = snapshot.state) {
            is ProviderLifecycleState.Connecting,
            is ProviderLifecycleState.Connected,
            is ProviderLifecycleState.Negotiating,
            is ProviderLifecycleState.Registered,
            -> transitioned(
                snapshot,
                ProviderLifecycleState.Disconnecting(
                    generation = currentGeneration(snapshot),
                    terminalState = ProviderLifecycleTerminalState.DISCONNECTED,
                ),
            )
            is ProviderLifecycleState.Cancelling -> if (state.needsDisconnect) {
                transitioned(
                    snapshot,
                    ProviderLifecycleState.Disconnecting(
                        generation = state.generation,
                        terminalState = ProviderLifecycleTerminalState.CANCELLED,
                    ),
                )
            } else {
                ignored(snapshot, ProviderLifecycleIgnoredReason.INVALID_TRANSITION)
            }
            else -> ignored(snapshot, ProviderLifecycleIgnoredReason.INVALID_TRANSITION)
        }

    private fun reduceDisconnected(snapshot: ProviderLifecycleSnapshot): ProviderLifecycleReduction =
        when (val state = snapshot.state) {
            is ProviderLifecycleState.Disconnecting -> transitioned(
                snapshot,
                state.terminalState.toState(state.generation),
            )
            is ProviderLifecycleState.Cancelling -> if (state.needsDisconnect) {
                transitioned(snapshot, ProviderLifecycleState.Cancelled(state.generation))
            } else {
                ignored(snapshot, ProviderLifecycleIgnoredReason.INVALID_TRANSITION)
            }
            is ProviderLifecycleState.Connecting,
            is ProviderLifecycleState.Connected,
            is ProviderLifecycleState.Negotiating,
            is ProviderLifecycleState.Registered,
            -> transitioned(
                snapshot,
                ProviderLifecycleState.Disconnected(currentGeneration(snapshot)),
            )
            is ProviderLifecycleState.Disconnected -> ignored(
                snapshot,
                ProviderLifecycleIgnoredReason.DUPLICATE_EVENT,
            )
            else -> ignored(snapshot, ProviderLifecycleIgnoredReason.INVALID_TRANSITION)
        }

    private fun reduceCancellationRequested(snapshot: ProviderLifecycleSnapshot): ProviderLifecycleReduction =
        when (val state = snapshot.state) {
            is ProviderLifecycleState.Discovered,
            is ProviderLifecycleState.Identified,
            -> transitioned(
                snapshot,
                ProviderLifecycleState.Cancelling(
                    currentGeneration(snapshot),
                    needsDisconnect = false,
                ),
            )
            is ProviderLifecycleState.Connecting,
            is ProviderLifecycleState.Connected,
            is ProviderLifecycleState.Negotiating,
            is ProviderLifecycleState.Registered,
            -> transitioned(
                snapshot,
                ProviderLifecycleState.Cancelling(
                    currentGeneration(snapshot),
                    needsDisconnect = true,
                ),
            )
            is ProviderLifecycleState.Cancelling -> ignored(
                snapshot,
                ProviderLifecycleIgnoredReason.DUPLICATE_EVENT,
            )
            else -> ignored(snapshot, ProviderLifecycleIgnoredReason.INVALID_TRANSITION)
        }

    private fun reduceCancellationCompleted(snapshot: ProviderLifecycleSnapshot): ProviderLifecycleReduction =
        when (val state = snapshot.state) {
            is ProviderLifecycleState.Cancelling -> if (!state.needsDisconnect) {
                transitioned(snapshot, ProviderLifecycleState.Cancelled(state.generation))
            } else {
                ignored(snapshot, ProviderLifecycleIgnoredReason.INVALID_TRANSITION)
            }
            is ProviderLifecycleState.Cancelled -> ignored(
                snapshot,
                ProviderLifecycleIgnoredReason.DUPLICATE_EVENT,
            )
            else -> ignored(snapshot, ProviderLifecycleIgnoredReason.INVALID_TRANSITION)
        }

    private fun reduceFailure(
        snapshot: ProviderLifecycleSnapshot,
        failure: ProviderLifecycleFailure,
    ): ProviderLifecycleReduction = when (val state = snapshot.state) {
        is ProviderLifecycleState.Discovered,
        is ProviderLifecycleState.Identified,
        is ProviderLifecycleState.Connecting,
        is ProviderLifecycleState.Connected,
        is ProviderLifecycleState.Negotiating,
        is ProviderLifecycleState.Registered,
        is ProviderLifecycleState.Cancelling,
        is ProviderLifecycleState.Disconnecting,
        -> transitioned(
            snapshot,
            ProviderLifecycleState.Failed(currentGeneration(snapshot), failure),
        )
        else -> ignored(snapshot, ProviderLifecycleIgnoredReason.INVALID_TRANSITION)
    }

    private fun currentGeneration(snapshot: ProviderLifecycleSnapshot): ProviderGeneration =
        checkNotNull(snapshot.latestGeneration)

    private fun <T : ProviderLifecycleState> whenState(
        snapshot: ProviderLifecycleSnapshot,
        expected: kotlin.reflect.KClass<T>,
        next: () -> ProviderLifecycleState,
    ): ProviderLifecycleReduction = if (expected.isInstance(snapshot.state)) {
        transitioned(snapshot, next())
    } else {
        ignored(snapshot, ProviderLifecycleIgnoredReason.INVALID_TRANSITION)
    }

    private fun transitioned(
        snapshot: ProviderLifecycleSnapshot,
        next: ProviderLifecycleState,
    ): ProviderLifecycleReduction = ProviderLifecycleReduction(
        snapshot = ProviderLifecycleSnapshot.transitioned(next),
        disposition = ProviderLifecycleDisposition.Transitioned(snapshot.state, next),
    )

    private fun ignored(
        snapshot: ProviderLifecycleSnapshot,
        reason: ProviderLifecycleIgnoredReason,
    ): ProviderLifecycleReduction = ProviderLifecycleReduction(
        snapshot = snapshot,
        disposition = ProviderLifecycleDisposition.Ignored(reason),
    )
}

class ProviderLifecycleSnapshot private constructor(
    val state: ProviderLifecycleState,
    val latestGeneration: ProviderGeneration?,
) {
    companion object {
        fun initial(): ProviderLifecycleSnapshot = ProviderLifecycleSnapshot(
            state = ProviderLifecycleState.Inactive,
            latestGeneration = null,
        )

        internal fun transitioned(state: ProviderLifecycleState): ProviderLifecycleSnapshot =
            ProviderLifecycleSnapshot(state, state.generation)
    }
}

sealed interface ProviderLifecycleState {
    data object Inactive : ProviderLifecycleState

    data class Discovered(val generation: ProviderGeneration) : ProviderLifecycleState

    data class Identified(val generation: ProviderGeneration) : ProviderLifecycleState

    data class Connecting(val generation: ProviderGeneration) : ProviderLifecycleState

    /** A transport/session lifecycle fact only; it does not grant trust or authorization. */
    data class Connected(val generation: ProviderGeneration) : ProviderLifecycleState

    data class Negotiating(val generation: ProviderGeneration) : ProviderLifecycleState

    data class Registered(val generation: ProviderGeneration) : ProviderLifecycleState

    data class Cancelling(
        val generation: ProviderGeneration,
        val needsDisconnect: Boolean,
    ) : ProviderLifecycleState

    data class Disconnecting(
        val generation: ProviderGeneration,
        val terminalState: ProviderLifecycleTerminalState,
    ) : ProviderLifecycleState

    data class Cancelled(val generation: ProviderGeneration) : ProviderLifecycleState

    data class Disconnected(val generation: ProviderGeneration) : ProviderLifecycleState

    data class Failed(
        val generation: ProviderGeneration,
        val failure: ProviderLifecycleFailure,
    ) : ProviderLifecycleState
}

enum class ProviderLifecycleTerminalState {
    CANCELLED,
    DISCONNECTED,
    ;

    fun toState(generation: ProviderGeneration): ProviderLifecycleState = when (this) {
        CANCELLED -> ProviderLifecycleState.Cancelled(generation)
        DISCONNECTED -> ProviderLifecycleState.Disconnected(generation)
    }
}

sealed interface ProviderLifecycleEvent {
    val generation: ProviderGeneration

    data class Discovered(override val generation: ProviderGeneration) : ProviderLifecycleEvent

    data class IdentityResolved(override val generation: ProviderGeneration) : ProviderLifecycleEvent

    data class ConnectRequested(override val generation: ProviderGeneration) : ProviderLifecycleEvent

    data class ConnectionEstablished(override val generation: ProviderGeneration) : ProviderLifecycleEvent

    data class NegotiationRequested(override val generation: ProviderGeneration) : ProviderLifecycleEvent

    data class CapabilitiesRegistered(override val generation: ProviderGeneration) : ProviderLifecycleEvent

    data class CancellationRequested(override val generation: ProviderGeneration) : ProviderLifecycleEvent

    data class CancellationCompleted(override val generation: ProviderGeneration) : ProviderLifecycleEvent

    data class DisconnectRequested(override val generation: ProviderGeneration) : ProviderLifecycleEvent

    data class Disconnected(override val generation: ProviderGeneration) : ProviderLifecycleEvent

    data class Failed(
        override val generation: ProviderGeneration,
        val failure: ProviderLifecycleFailure,
    ) : ProviderLifecycleEvent
}

enum class ProviderLifecycleFailure {
    DISCOVERY_FAILED,
    IDENTITY_FAILED,
    CONNECTION_FAILED,
    NEGOTIATION_FAILED,
    REGISTRATION_FAILED,
    CANCELLATION_FAILED,
    DISCONNECTION_FAILED,
    UNKNOWN,
}

data class ProviderLifecycleReduction(
    val snapshot: ProviderLifecycleSnapshot,
    val disposition: ProviderLifecycleDisposition,
)

sealed interface ProviderLifecycleDisposition {
    data class Transitioned(
        val from: ProviderLifecycleState,
        val to: ProviderLifecycleState,
    ) : ProviderLifecycleDisposition

    data class Ignored(val reason: ProviderLifecycleIgnoredReason) : ProviderLifecycleDisposition
}

enum class ProviderLifecycleIgnoredReason {
    NO_ACTIVE_GENERATION,
    STALE_GENERATION,
    FUTURE_GENERATION,
    REUSED_GENERATION,
    DUPLICATE_EVENT,
    INVALID_TRANSITION,
}

private val ProviderLifecycleState.generation: ProviderGeneration?
    get() = when (this) {
        ProviderLifecycleState.Inactive -> null
        is ProviderLifecycleState.Discovered -> generation
        is ProviderLifecycleState.Identified -> generation
        is ProviderLifecycleState.Connecting -> generation
        is ProviderLifecycleState.Connected -> generation
        is ProviderLifecycleState.Negotiating -> generation
        is ProviderLifecycleState.Registered -> generation
        is ProviderLifecycleState.Cancelling -> generation
        is ProviderLifecycleState.Disconnecting -> generation
        is ProviderLifecycleState.Cancelled -> generation
        is ProviderLifecycleState.Disconnected -> generation
        is ProviderLifecycleState.Failed -> generation
    }
