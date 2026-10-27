package io.redi.robot.firstlife.runtime

import io.redi.robot.body.looi.capability.FacadeCorrelation
import io.redi.robot.body.looi.capability.LooiRuntimeInvocation
import io.redi.robot.body.looi.capability.LooiVerifiedRuntimeOutcome
import io.redi.robot.body.looi.capability.LooiVerifiedRuntimePort
import io.redi.robot.body.looi.firstlife.LooiFirstLifeDefinition
import io.redi.robot.body.looi.firstlife.LooiFirstLifeProviderSession
import io.redi.robot.capability.api.*
import io.redi.robot.capability.negotiation.CapabilityNegotiationId
import io.redi.robot.capability.provider.*
import io.redi.robot.connection.api.*
import io.redi.robot.trust.runtime.*
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLifeAuthorizedCapabilityDispatcherTest {
    private val providerIdentity = ProviderIdentity(
        LooiFirstLifeDefinition.definitionId,
        providerIdentity(ProviderInstanceId.parse("10000000-0000-4000-8000-000000000001")),
        ProviderFamily.BODY_ADAPTER,
        providerIdentity(RedactedProviderAlias.parse("LOOI Reference Body")),
    )
    private val connection = connected()
    private val providerSession = LooiFirstLifeProviderSession(providerIdentity, generation(1))
    private val report = checkNotNull(providerSession.negotiate(connection, true, 100))
    private val runtime = RecordingRuntime()
    private val dispatcher = FirstLifeAuthorizedCapabilityDispatcher(providerSession.capabilityProvider(runtime)) { report }

    @Test
    fun `missing and denied authorization invoke nothing`() {
        val request = request("mobility.move@1.0", moveInput())

        assertEquals(
            AuthorizedDispatchRejection.AUTHORIZATION_MISSING,
            (dispatcher.dispatch(request, null, context()) as AuthorizedDispatchResult.Rejected).reason,
        )
        assertEquals(
            AuthorizedDispatchRejection.AUTHORIZATION_DENIED,
            (dispatcher.dispatch(request, decision(request, false), context()) as AuthorizedDispatchResult.Rejected).reason,
        )
        assertTrue(runtime.executed.isEmpty())
    }

    @Test
    fun `exact authorized request reaches H018 once and replay is rejected`() {
        val request = request("mobility.move@1.0", moveInput())
        val first = dispatcher.dispatch(request, decision(request), context())
        val replay = dispatcher.dispatch(request, decision(request), context())

        assertTrue(first is AuthorizedDispatchResult.Invoked)
        assertEquals(AuthorizedDispatchRejection.REQUEST_REPLAYED, (replay as AuthorizedDispatchResult.Rejected).reason)
        assertEquals(1, runtime.executed.size)
        assertEquals(request.requestId, runtime.executed.single().correlation.requestId)
        assertFalse((first as AuthorizedDispatchResult.Invoked).safetyAuthorityGranted)
    }

    @Test
    fun `wrong generation negotiation session and decision correlation fail closed`() {
        val request = request("mobility.move@1.0", moveInput())
        val staleGeneration = AuthorizedDispatchContext(
            providerIdentity.instanceId, generation(2), report.negotiationId, sessionIds(), time(120),
        )
        val staleSession = AuthorizedDispatchContext(
            providerIdentity.instanceId, generation(1), report.negotiationId,
            listOf(session("40000000-0000-4000-8000-000000000004")), time(120),
        )
        val wrongRequest = request("mobility.move@1.0", moveInput())

        assertEquals(AuthorizedDispatchRejection.GENERATION_STALE,
            (dispatcher.dispatch(request, decision(request), staleGeneration) as AuthorizedDispatchResult.Rejected).reason)
        assertEquals(AuthorizedDispatchRejection.CONNECTION_SESSION_STALE,
            (dispatcher.dispatch(request, decision(request), staleSession) as AuthorizedDispatchResult.Rejected).reason)
        assertEquals(AuthorizedDispatchRejection.AUTHORIZATION_CORRELATION_MISMATCH,
            (dispatcher.dispatch(request, decision(wrongRequest), context()) as AuthorizedDispatchResult.Rejected).reason)
        assertTrue(runtime.executed.isEmpty())
    }

    @Test
    fun `semantic stop and cancellation remain harm reducing without new authorization`() {
        val stop = request("mobility.stop@1.0", null)
        val move = request("mobility.move@1.0", moveInput())

        assertTrue(dispatcher.dispatch(stop, null, context()) is AuthorizedDispatchResult.Invoked)
        assertTrue(dispatcher.cancel(move, context()) is AuthorizedDispatchResult.Invoked)
        assertEquals(1, runtime.executed.size)
        assertEquals(1, runtime.cancelled.size)
    }

    private fun context() = AuthorizedDispatchContext(
        providerIdentity.instanceId, generation(1), report.negotiationId, sessionIds(), time(120),
    )

    private fun sessionIds() = report.negotiatedCapabilities.first().connectionSessionIds

    private fun request(capability: String, input: CapabilityData?): CapabilityRequest = envelope(
        CapabilityRequest.create(
            CapabilityRequestId.fromUuid(UUID.randomUUID()), reference(capability),
            CapabilityProviderTarget.Exact(providerIdentity.instanceId), CapabilityActorKind.HUGO_RUNTIME,
            envelope(CapabilityExecutionContext.create(1, 1)), time(110), time(500), null,
            CapabilityAuditId.fromUuid(UUID.randomUUID()), input,
        ),
    )

    private fun moveInput(): CapabilityData = envelope(CapabilityData.create(
        descriptor(CapabilityContractReference.parse("hugo.contract.mobility_move_input@1.0")),
        mapOf(
            envelope(CapabilityArgumentName.parse("direction")) to
                envelope(CapabilityArgumentValue.Text.create("left")),
        ),
    ))

    private fun decision(request: CapabilityRequest, authorized: Boolean = true): AuthorizationDecision {
        val id = authorization(AuthorizationDecisionId.fromUuid(UUID.randomUUID()))
        val audit = AuthorizationAuditRecord(
            id, request.requestId, request.auditId, request.actor, providerIdentity.instanceId,
            request.capability, generation(1), report.negotiationId, time(115), false,
            emptyList(), emptyList(), emptyList(), null,
        )
        return AuthorizationDecision(
            if (authorized) AuthorizationDecisionStatus.AUTHORIZED else AuthorizationDecisionStatus.PERMISSION_REQUIRED,
            if (authorized) AuthorizationDecisionReason.AUTHORITY_VALID else AuthorizationDecisionReason.GRANT_MISSING,
            null,
            audit,
        )
    }

    private inner class RecordingRuntime : LooiVerifiedRuntimePort {
        val executed = mutableListOf<LooiRuntimeInvocation>()
        val cancelled = mutableListOf<LooiRuntimeInvocation>()
        override fun runtimeSnapshot(): ProviderRuntimeSnapshot = provider(ProviderRuntimeSnapshot.create(
            generation(1), ProviderRuntimeAvailability.AVAILABLE, time(100), null,
        ))
        override fun execute(invocation: LooiRuntimeInvocation): LooiVerifiedRuntimeOutcome {
            executed += invocation
            return LooiVerifiedRuntimeOutcome.Accepted(invocation.correlation, time(121))
        }
        override fun cancel(invocation: LooiRuntimeInvocation): LooiVerifiedRuntimeOutcome {
            cancelled += invocation
            return LooiVerifiedRuntimeOutcome.Cancelled(
                invocation.correlation, time(121), envelope(CapabilityReasonCode.parse("runtime.cancelled")),
            )
        }
    }

    private fun connected(): ConnectionLifecycleSnapshot {
        val method = ConnectionMethodReference(
            connection(ConnectionMethodId.parse("io.hugo.connection/bluetooth-le")),
            connection(ConnectionMethodVersion.parse("1.0")),
        )
        val request = connection(ConnectionConnectRequest.create(
            connection(ConnectionRequestId.parse("20000000-0000-4000-8000-000000000001")),
            connection(ConnectionGeneration.create(1)), method,
            connection(ConnectionEndpointReference.parse("looi.endpoint.current")), null, time(10), time(1000),
        ))
        val connecting = ConnectionLifecycleReducer.reduce(
            ConnectionLifecycleSnapshot.initial(), ConnectionLifecycleEvent.ConnectRequested(request),
        ).snapshot
        return ConnectionLifecycleReducer.reduce(
            connecting,
            ConnectionLifecycleEvent.ChannelEstablished(
                request.generation, request.requestId,
                session("30000000-0000-4000-8000-000000000001"),
                ConnectionMetadata(method, request.endpoint, null, time(20)),
            ),
        ).snapshot
    }

    private fun reference(raw: String) = identity(CapabilityReference.parse(raw))
    private fun time(value: Long) = envelope(MonotonicTimestampMs.create(value))
    private fun generation(value: Long) = provider(ProviderGeneration.create(value))
    private fun session(raw: String) = connection(ConnectionSessionId.parse(raw))
    private fun <T> envelope(result: CapabilityEnvelopeResult<T>) = (result as CapabilityEnvelopeResult.Success).value
    private fun <T> descriptor(result: CapabilityDescriptorResult<T>) = (result as CapabilityDescriptorResult.Success).value
    private fun <T> identity(result: CapabilityIdentityResult<T>) = (result as CapabilityIdentityResult.Success).value
    private fun <T> provider(result: CapabilityProviderPortResult<T>) = (result as CapabilityProviderPortResult.Success).value
    private fun <T> providerIdentity(result: ProviderIdentityResult<T>) = (result as ProviderIdentityResult.Success).value
    private fun <T> connection(result: ConnectionResult<T>) = (result as ConnectionResult.Success).value
    private fun <T> authorization(result: AuthorizationGateModelResult<T>) = (result as AuthorizationGateModelResult.Success).value
}
