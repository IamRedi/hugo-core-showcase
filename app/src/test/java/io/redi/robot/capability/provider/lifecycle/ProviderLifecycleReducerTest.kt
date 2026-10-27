package io.redi.robot.capability.provider.lifecycle

import io.redi.robot.capability.provider.CapabilityProviderPortResult
import io.redi.robot.capability.provider.ProviderGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLifecycleReducerTest {
    @Test
    fun legalLifecycleProgressesFromDiscoveryThroughCleanDisconnect() {
        val generation = generation(4)
        val final = reduceAll(
            ProviderLifecycleSnapshot.initial(),
            listOf(
                ProviderLifecycleEvent.Discovered(generation),
                ProviderLifecycleEvent.IdentityResolved(generation),
                ProviderLifecycleEvent.ConnectRequested(generation),
                ProviderLifecycleEvent.ConnectionEstablished(generation),
                ProviderLifecycleEvent.NegotiationRequested(generation),
                ProviderLifecycleEvent.CapabilitiesRegistered(generation),
                ProviderLifecycleEvent.DisconnectRequested(generation),
                ProviderLifecycleEvent.Disconnected(generation),
            ),
        )

        assertEquals(ProviderLifecycleState.Disconnected(generation), final.state)
        assertEquals(generation, final.latestGeneration)
    }

    @Test
    fun invalidTransitionsAreIgnoredWithoutChangingState() {
        val initial = ProviderLifecycleSnapshot.initial()
        val ignored = ProviderLifecycleReducer.reduce(
            initial,
            ProviderLifecycleEvent.ConnectRequested(generation(1)),
        )

        assertEquals(initial, ignored.snapshot)
        assertEquals(
            ProviderLifecycleDisposition.Ignored(ProviderLifecycleIgnoredReason.NO_ACTIVE_GENERATION),
            ignored.disposition,
        )
    }

    @Test
    fun staleFutureAndReusedGenerationsAreRejectedDeterministically() {
        val old = generation(2)
        val current = generation(3)
        val disconnected = reduceAll(
            ProviderLifecycleSnapshot.initial(),
            listOf(
                ProviderLifecycleEvent.Discovered(old),
                ProviderLifecycleEvent.CancellationRequested(old),
                ProviderLifecycleEvent.CancellationCompleted(old),
                ProviderLifecycleEvent.Discovered(current),
            ),
        )

        val stale = ProviderLifecycleReducer.reduce(
            disconnected,
            ProviderLifecycleEvent.IdentityResolved(old),
        )
        val future = ProviderLifecycleReducer.reduce(
            disconnected,
            ProviderLifecycleEvent.IdentityResolved(generation(4)),
        )
        val reused = ProviderLifecycleReducer.reduce(
            disconnected,
            ProviderLifecycleEvent.Discovered(current),
        )

        assertEquals(ProviderLifecycleIgnoredReason.STALE_GENERATION, ignoredReason(stale))
        assertEquals(ProviderLifecycleIgnoredReason.FUTURE_GENERATION, ignoredReason(future))
        assertEquals(ProviderLifecycleIgnoredReason.REUSED_GENERATION, ignoredReason(reused))
        assertEquals(ProviderLifecycleState.Discovered(current), stale.snapshot.state)
    }

    @Test
    fun cancellationRequiresDisconnectForAConnectionBearingState() {
        val generation = generation(5)
        val connected = reduceAll(
            ProviderLifecycleSnapshot.initial(),
            listOf(
                ProviderLifecycleEvent.Discovered(generation),
                ProviderLifecycleEvent.IdentityResolved(generation),
                ProviderLifecycleEvent.ConnectRequested(generation),
                ProviderLifecycleEvent.ConnectionEstablished(generation),
            ),
        )

        val cancelling = ProviderLifecycleReducer.reduce(
            connected,
            ProviderLifecycleEvent.CancellationRequested(generation),
        )
        val prematureCompletion = ProviderLifecycleReducer.reduce(
            cancelling.snapshot,
            ProviderLifecycleEvent.CancellationCompleted(generation),
        )
        val cancelled = ProviderLifecycleReducer.reduce(
            cancelling.snapshot,
            ProviderLifecycleEvent.Disconnected(generation),
        )

        assertEquals(
            ProviderLifecycleState.Cancelling(generation, needsDisconnect = true),
            cancelling.snapshot.state,
        )
        assertEquals(ProviderLifecycleIgnoredReason.INVALID_TRANSITION, ignoredReason(prematureCompletion))
        assertEquals(ProviderLifecycleState.Cancelled(generation), cancelled.snapshot.state)
    }

    @Test
    fun earlyCancellationCompletesWithoutInventingAConnection() {
        val generation = generation(6)
        val discovered = ProviderLifecycleReducer.reduce(
            ProviderLifecycleSnapshot.initial(),
            ProviderLifecycleEvent.Discovered(generation),
        )
        val cancelling = ProviderLifecycleReducer.reduce(
            discovered.snapshot,
            ProviderLifecycleEvent.CancellationRequested(generation),
        )
        val cancelled = ProviderLifecycleReducer.reduce(
            cancelling.snapshot,
            ProviderLifecycleEvent.CancellationCompleted(generation),
        )

        assertEquals(
            ProviderLifecycleState.Cancelling(generation, needsDisconnect = false),
            cancelling.snapshot.state,
        )
        assertEquals(ProviderLifecycleState.Cancelled(generation), cancelled.snapshot.state)
    }

    @Test
    fun failureIsTerminalAndLateConnectionCallbackIsIgnored() {
        val generation = generation(7)
        val connecting = reduceAll(
            ProviderLifecycleSnapshot.initial(),
            listOf(
                ProviderLifecycleEvent.Discovered(generation),
                ProviderLifecycleEvent.IdentityResolved(generation),
                ProviderLifecycleEvent.ConnectRequested(generation),
            ),
        )
        val failed = ProviderLifecycleReducer.reduce(
            connecting,
            ProviderLifecycleEvent.Failed(generation, ProviderLifecycleFailure.CONNECTION_FAILED),
        )
        val lateConnection = ProviderLifecycleReducer.reduce(
            failed.snapshot,
            ProviderLifecycleEvent.ConnectionEstablished(generation),
        )

        assertEquals(
            ProviderLifecycleState.Failed(generation, ProviderLifecycleFailure.CONNECTION_FAILED),
            failed.snapshot.state,
        )
        assertEquals(ProviderLifecycleIgnoredReason.INVALID_TRANSITION, ignoredReason(lateConnection))
    }

    @Test
    fun disconnectIsIdempotentAfterItsFirstTerminalObservation() {
        val generation = generation(8)
        val disconnected = reduceAll(
            ProviderLifecycleSnapshot.initial(),
            listOf(
                ProviderLifecycleEvent.Discovered(generation),
                ProviderLifecycleEvent.IdentityResolved(generation),
                ProviderLifecycleEvent.ConnectRequested(generation),
                ProviderLifecycleEvent.ConnectionEstablished(generation),
                ProviderLifecycleEvent.DisconnectRequested(generation),
                ProviderLifecycleEvent.Disconnected(generation),
            ),
        )

        val duplicate = ProviderLifecycleReducer.reduce(
            disconnected,
            ProviderLifecycleEvent.Disconnected(generation),
        )

        assertEquals(disconnected, duplicate.snapshot)
        assertEquals(ProviderLifecycleIgnoredReason.DUPLICATE_EVENT, ignoredReason(duplicate))
    }

    @Test
    fun lifecycleTypesRemainPlatformAndVendorFree() {
        val types = ProviderLifecycleReducer::class.java.methods.flatMap { method ->
            method.parameterTypes.asList() + method.returnType
        } + ProviderLifecycleReducer::class.java

        assertFalse(types.any { it.name.contains("android.") })
        assertFalse(types.any { it.name.contains("bluetooth", ignoreCase = true) })
        assertTrue(types.none { it.name.contains("looi", ignoreCase = true) })
    }

    private fun reduceAll(
        initial: ProviderLifecycleSnapshot,
        events: List<ProviderLifecycleEvent>,
    ): ProviderLifecycleSnapshot = events.fold(initial) { snapshot, event ->
        ProviderLifecycleReducer.reduce(snapshot, event).snapshot
    }

    private fun ignoredReason(reduction: ProviderLifecycleReduction): ProviderLifecycleIgnoredReason {
        assertTrue(reduction.disposition is ProviderLifecycleDisposition.Ignored)
        return (reduction.disposition as ProviderLifecycleDisposition.Ignored).reason
    }

    private fun generation(value: Long): ProviderGeneration = when (
        val result = ProviderGeneration.create(value)
    ) {
        is CapabilityProviderPortResult.Success -> result.value
        is CapabilityProviderPortResult.Failure -> throw AssertionError(result.error)
    }
}
