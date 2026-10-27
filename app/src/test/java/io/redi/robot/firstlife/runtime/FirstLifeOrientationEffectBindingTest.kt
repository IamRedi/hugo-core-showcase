package io.redi.robot.firstlife.runtime

import io.redi.robot.body.looi.firstlife.LooiFirstLifeDefinition
import io.redi.robot.body.looi.firstlife.LooiFirstLifeProviderSession
import io.redi.robot.capability.api.CapabilityArgumentValue
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.CapabilityOutcomeEvidence
import io.redi.robot.capability.api.CapabilityResult
import io.redi.robot.capability.api.CapabilityResultState
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.connection.api.ConnectionConnectRequest
import io.redi.robot.connection.api.ConnectionEndpointReference
import io.redi.robot.connection.api.ConnectionGeneration
import io.redi.robot.connection.api.ConnectionLifecycleEvent
import io.redi.robot.connection.api.ConnectionLifecycleReducer
import io.redi.robot.connection.api.ConnectionLifecycleSnapshot
import io.redi.robot.connection.api.ConnectionMetadata
import io.redi.robot.connection.api.ConnectionMethodId
import io.redi.robot.connection.api.ConnectionMethodReference
import io.redi.robot.connection.api.ConnectionMethodVersion
import io.redi.robot.connection.api.ConnectionRequestId
import io.redi.robot.connection.api.ConnectionResult
import io.redi.robot.connection.api.ConnectionSessionId
import io.redi.robot.context.provider.ActiveBodyActivationMode
import io.redi.robot.context.provider.ActiveBodyEvent
import io.redi.robot.context.provider.ActiveBodyReducer
import io.redi.robot.context.provider.ActiveBodySelection
import io.redi.robot.context.provider.ActiveBodySelectionReference
import io.redi.robot.context.provider.ActiveBodyState
import io.redi.robot.context.provider.BodyAvailability
import io.redi.robot.context.provider.BodyProviderReference
import io.redi.robot.context.provider.EmbodimentConfidence
import io.redi.robot.context.provider.EmbodimentEvidence
import io.redi.robot.context.provider.EmbodimentEvidenceId
import io.redi.robot.context.provider.EmbodimentEvidenceKind
import io.redi.robot.context.provider.EmbodimentEvidenceProvenance
import io.redi.robot.context.provider.EmbodimentGeneration
import io.redi.robot.context.provider.EmbodimentHost
import io.redi.robot.context.provider.EmbodimentHostReference
import io.redi.robot.context.provider.EmbodimentResult
import io.redi.robot.context.provider.MountingEvidencePolicy
import io.redi.robot.firstlife.orientation.FirstLifeOrientationEnvelope
import io.redi.robot.firstlife.orientation.OrientationAttemptId
import io.redi.robot.firstlife.orientation.OrientationEffect
import io.redi.robot.firstlife.orientation.OrientationEffectId
import io.redi.robot.firstlife.orientation.OrientationEffectOutcome
import io.redi.robot.firstlife.orientation.OrientationTurnDirection
import io.redi.robot.trust.domain.HugoHostRole
import io.redi.robot.trust.runtime.AuthorizationDecisionStatus
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLifeOrientationEffectBindingTest {
    private val providerIdentity = ProviderIdentity(
        LooiFirstLifeDefinition.definitionId,
        providerIdentity(ProviderInstanceId.parse("10000000-0000-4000-8000-000000000013")),
        ProviderFamily.BODY_ADAPTER,
        providerIdentity(RedactedProviderAlias.parse("LOOI Reference Body")),
    )
    private val report = checkNotNull(
        LooiFirstLifeProviderSession(providerIdentity, generation(1)).negotiate(connected(), true, 100),
    )
    private val host = EmbodimentHost(
        embodiment(EmbodimentHostReference.parse("android.first-life")),
        HugoHostRole.MOBILE_HOST,
    )

    @Test
    fun `explicit current hold authorizes exact semantic left and right only through full route`() {
        val receipts = mutableListOf<Pair<io.redi.robot.capability.api.CapabilityRequest, io.redi.robot.trust.runtime.AuthorizationDecision?>>()
        val binding = FirstLifeOrientationEffectBinding(FirstLifeOrientationDispatchPort { request, decision ->
            receipts += request to decision
            accepted(request, stopObserved = request.capability.wireValue == "mobility.stop@1.0")
        })
        val context = context()

        assertTrue(binding.beginHold(context, time(120)).started)
        val left = binding.execute(turn(1, OrientationTurnDirection.LEFT), context, time(120))
        val right = binding.execute(turn(2, OrientationTurnDirection.RIGHT), context, time(170))

        assertEquals(OrientationEffectOutcome.MOVE_ACCEPTED, left.outcome)
        assertEquals(OrientationEffectOutcome.MOVE_ACCEPTED, right.outcome)
        assertEquals(FirstLifeOrientationRoute.H007_H029_H030_H018_T032, left.trace?.route)
        assertTrue(receipts.all { it.first.capability.wireValue == "mobility.move@1.0" })
        assertTrue(receipts.all { it.second?.status == AuthorizationDecisionStatus.AUTHORIZED })
        assertNotEquals(receipts[0].second?.audit?.decisionId, receipts[1].second?.audit?.decisionId)
        assertEquals(listOf("left", "right"), receipts.map { receipt ->
            (receipt.first.input?.values?.values?.single() as CapabilityArgumentValue.Text).value
        })
        assertTrue(receipts.all { it.first.input?.values?.size == 1 })
        assertTrue(left.trace.toString().contains("redacted=true"))
        assertFalse(left.trace.toString().contains(providerIdentity.instanceId.value))
    }

    @Test
    fun `readiness does not grant authority and stale embodiment fails closed`() {
        val binding = FirstLifeOrientationEffectBinding(FirstLifeOrientationDispatchPort { request, _ ->
            accepted(request)
        })
        val current = context()

        assertEquals(
            OrientationEffectOutcome.AUTHORIZATION_DENIED,
            binding.execute(turn(1), current, time(120)).outcome,
        )
        assertEquals(
            OrientationAuthorityBeginStatus.CONTEXT_NOT_CURRENT,
            binding.beginHold(context(selected = false), time(120)).status,
        )
        assertNull(binding.beginHold(context(selected = false), time(120)).session)
    }

    @Test
    fun `hold expiry and changed correlation deny later move while stop remains harm reducing`() {
        val requests = mutableListOf<io.redi.robot.capability.api.CapabilityRequest>()
        val binding = FirstLifeOrientationEffectBinding(FirstLifeOrientationDispatchPort { request, _ ->
            requests += request
            accepted(request, stopObserved = request.capability.wireValue == "mobility.stop@1.0")
        })
        val context = context()
        assertTrue(binding.beginHold(context, time(120)).started)

        assertEquals(
            OrientationEffectOutcome.AUTHORIZATION_DENIED,
            binding.execute(turn(1), context, time(2_120)).outcome,
        )
        binding.endHold()
        val stop = binding.execute(
            OrientationEffect.RequestStop(effectId(2), null), context, time(130),
        )
        assertEquals(OrientationEffectOutcome.STOP_CONFIRMED, stop.outcome)
        assertEquals("mobility.stop@1.0", requests.single().capability.wireValue)
        assertNull(stop.trace?.decision)
        assertFalse((stop.trace?.receipt?.dispatch as AuthorizedDispatchResult.Invoked).safetyAuthorityGranted)
    }

    @Test
    fun `unconfirmed stop and dispatcher stale correlation remain explicit`() {
        val context = context()
        val unconfirmed = FirstLifeOrientationEffectBinding(FirstLifeOrientationDispatchPort { request, _ ->
            accepted(request, stopObserved = false)
        }).execute(OrientationEffect.RequestStop(effectId(1), null), context, time(120))
        val stale = FirstLifeOrientationEffectBinding(FirstLifeOrientationDispatchPort { _, _ ->
            FirstLifeOrientationDispatchReceipt(
                AuthorizedDispatchResult.Rejected(AuthorizedDispatchRejection.CONNECTION_SESSION_STALE),
            )
        }).execute(OrientationEffect.RequestStop(effectId(2), null), context, time(120))

        assertEquals(OrientationEffectOutcome.STOP_UNCONFIRMED, unconfirmed.outcome)
        assertEquals(OrientationEffectOutcome.STALE_CORRELATION, stale.outcome)
    }

    private fun context(selected: Boolean = true): FirstLifeOrientationAuthorityContext =
        FirstLifeOrientationAuthorityContext(providerIdentity, report, embodimentState(selected), host)

    private fun embodimentState(selected: Boolean): ActiveBodyState {
        val reducer = ActiveBodyReducer(embodiment(MountingEvidencePolicy.create(confidence(1000))))
        val body = embodiment(BodyProviderReference.create(providerIdentity))
        val generation = embodiment(EmbodimentGeneration.create(1))
        var state = reducer.reduce(
            ActiveBodyState.initial(host),
            ActiveBodyEvent.Observe(
                generation, body, BodyAvailability.AVAILABLE,
                nearby = true, connected = true, remotelyControlled = true,
                evidence = listOf(evidence("fl013.runtime", EmbodimentEvidenceKind.REMOTE_CONTROL_OBSERVED)),
                observedAt = time(100),
            ),
        ).state
        state = reducer.reduce(
            state,
            ActiveBodyEvent.MountEvidence(
                generation, body,
                listOf(evidence("fl013.mount", EmbodimentEvidenceKind.MOUNTING_VERIFIED)),
                time(110),
            ),
        ).state
        if (selected) {
            state = reducer.reduce(
                state,
                ActiveBodyEvent.Select(
                    generation,
                    embodiment(ActiveBodySelection.create(
                        body, ActiveBodyActivationMode.REMOTE,
                        embodiment(ActiveBodySelectionReference.parse("fl013.selection")),
                        time(110), time(5_000),
                    )),
                ),
            ).state
        }
        return state
    }

    private fun evidence(id: String, kind: EmbodimentEvidenceKind): EmbodimentEvidence = embodiment(
        EmbodimentEvidence.create(
            embodiment(EmbodimentEvidenceId.parse(id)), kind,
            EmbodimentEvidenceProvenance.HARDWARE_VERIFIED,
            confidence(1000), time(100), time(5_000),
        ),
    )

    private fun accepted(
        request: io.redi.robot.capability.api.CapabilityRequest,
        stopObserved: Boolean = false,
    ): FirstLifeOrientationDispatchReceipt = FirstLifeOrientationDispatchReceipt(
        AuthorizedDispatchResult.Invoked(
            envelope(CapabilityResult.create(
                request.requestId, request.capability, providerIdentity.instanceId,
                request.executionContext, request.createdAt, CapabilityResultState.ACCEPTED,
                null, CapabilityOutcomeEvidence.REQUEST_ACCEPTED, null, request.auditId,
            )),
            null,
        ),
        stopObserved,
    )

    private fun turn(sequence: Long, direction: OrientationTurnDirection = OrientationTurnDirection.LEFT) =
        OrientationEffect.ProposeTurn(
            effectId(sequence), direction,
            FirstLifeOrientationEnvelope.SIGNED_MAGNITUDE,
            refreshOrdinal = 1,
        )

    private fun effectId(sequence: Long) =
        OrientationEffectId(OrientationAttemptId("fl013.binding-attempt"), sequence)

    private fun connected(): ConnectionLifecycleSnapshot {
        val method = ConnectionMethodReference(
            connection(ConnectionMethodId.parse("io.hugo.connection/bluetooth-le")),
            connection(ConnectionMethodVersion.parse("1.0")),
        )
        val request = connection(ConnectionConnectRequest.create(
            connection(ConnectionRequestId.parse("20000000-0000-4000-8000-000000000013")),
            connection(ConnectionGeneration.create(1)), method,
            connection(ConnectionEndpointReference.parse("looi.endpoint.fl013")), null,
            time(10), time(1_000),
        ))
        val connecting = ConnectionLifecycleReducer.reduce(
            ConnectionLifecycleSnapshot.initial(), ConnectionLifecycleEvent.ConnectRequested(request),
        ).snapshot
        return ConnectionLifecycleReducer.reduce(
            connecting,
            ConnectionLifecycleEvent.ChannelEstablished(
                request.generation, request.requestId,
                connection(ConnectionSessionId.parse("30000000-0000-4000-8000-000000000013")),
                ConnectionMetadata(method, request.endpoint, null, time(20)),
            ),
        ).snapshot
    }

    private fun time(value: Long) = envelope(MonotonicTimestampMs.create(value))
    private fun generation(value: Long) = provider(ProviderGeneration.create(value))
    private fun confidence(value: Int) = embodiment(EmbodimentConfidence.create(value))
    private fun <T> envelope(result: CapabilityEnvelopeResult<T>) = (result as CapabilityEnvelopeResult.Success).value
    private fun <T> embodiment(result: EmbodimentResult<T>) = (result as EmbodimentResult.Success).value
    private fun <T> provider(result: io.redi.robot.capability.provider.CapabilityProviderPortResult<T>) =
        (result as io.redi.robot.capability.provider.CapabilityProviderPortResult.Success).value
    private fun <T> providerIdentity(result: ProviderIdentityResult<T>) = (result as ProviderIdentityResult.Success).value
    private fun <T> connection(result: ConnectionResult<T>) = (result as ConnectionResult.Success).value
}
