package io.redi.robot.firstlife.runtime

import io.redi.robot.capability.api.CapabilityProviderTarget
import io.redi.robot.capability.api.CapabilityRequest
import io.redi.robot.capability.api.CapabilityRequestId
import io.redi.robot.capability.api.CapabilityResult
import io.redi.robot.capability.api.CapabilityResultCorrelation
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.negotiation.CapabilityNegotiationId
import io.redi.robot.capability.negotiation.CapabilityNegotiationReport
import io.redi.robot.capability.negotiation.CapabilityNegotiationStatus
import io.redi.robot.capability.provider.CapabilityCancellationRequest
import io.redi.robot.capability.provider.CapabilityProvider
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.connection.api.ConnectionSessionId
import io.redi.robot.trust.runtime.AuthorizationDecision
import io.redi.robot.trust.runtime.AuthorizationDecisionId
import io.redi.robot.trust.runtime.AuthorizationDecisionStatus
import java.util.Collections

/** Current evidence supplied by the application composition; it grants no authority by itself. */
class AuthorizedDispatchContext(
    val providerInstanceId: ProviderInstanceId,
    val providerGeneration: ProviderGeneration,
    val negotiationId: CapabilityNegotiationId,
    connectionSessionIds: List<ConnectionSessionId>,
    val now: MonotonicTimestampMs,
) {
    val connectionSessionIds: List<ConnectionSessionId> = Collections.unmodifiableList(
        connectionSessionIds.distinct().sortedBy { it.value },
    )
}

enum class AuthorizedDispatchRejection {
    AUTHORIZATION_MISSING,
    AUTHORIZATION_DENIED,
    AUTHORIZATION_CORRELATION_MISMATCH,
    PROVIDER_CORRELATION_MISMATCH,
    GENERATION_STALE,
    NEGOTIATION_STALE,
    CONNECTION_SESSION_STALE,
    REQUEST_EXPIRED,
    REQUEST_REPLAYED,
    RESULT_CORRELATION_MISMATCH,
}

sealed interface AuthorizedDispatchResult {
    data class Invoked(
        val result: CapabilityResult,
        val authorizationDecisionId: AuthorizationDecisionId?,
        val safetyAuthorityGranted: Boolean = false,
    ) : AuthorizedDispatchResult

    data class Rejected(val reason: AuthorizedDispatchRejection) : AuthorizedDispatchResult
}

/**
 * FL007 request-time gate. It owns correlation and replay protection only; the provider facade and
 * its verified runtime delegate continue to own execution outcomes and the T032 safety boundary.
 */
class FirstLifeAuthorizedCapabilityDispatcher(
    private val provider: CapabilityProvider,
    private val negotiation: () -> CapabilityNegotiationReport?,
) {
    private val consumedRequests = linkedSetOf<CapabilityRequestId>()

    @Synchronized
    fun dispatch(
        request: CapabilityRequest,
        authorization: AuthorizationDecision?,
        context: AuthorizedDispatchContext,
    ): AuthorizedDispatchResult {
        correlationFailure(request, context)?.let { return AuthorizedDispatchResult.Rejected(it) }

        val isStop = request.capability.wireValue == STOP_CAPABILITY
        if (!isStop) {
            authorizationFailure(request, authorization, context)?.let {
                return AuthorizedDispatchResult.Rejected(it)
            }
        }

        if (!consumedRequests.add(request.requestId)) {
            return AuthorizedDispatchResult.Rejected(AuthorizedDispatchRejection.REQUEST_REPLAYED)
        }
        val result = provider.execute(request)
        if (result.correlationWith(request) != CapabilityResultCorrelation.MATCHED) {
            return AuthorizedDispatchResult.Rejected(AuthorizedDispatchRejection.RESULT_CORRELATION_MISMATCH)
        }
        return AuthorizedDispatchResult.Invoked(result, if (isStop) null else authorization?.audit?.decisionId)
    }

    /** Harm-reducing cancellation needs no new grant, but must remain correlated to the live route. */
    @Synchronized
    fun cancel(
        original: CapabilityRequest,
        context: AuthorizedDispatchContext,
    ): AuthorizedDispatchResult {
        correlationFailure(original, context, enforceDeadline = false)?.let {
            return AuthorizedDispatchResult.Rejected(it)
        }
        val result = provider.cancel(CapabilityCancellationRequest.from(original, context.now))
        if (result.correlationWith(original) != CapabilityResultCorrelation.MATCHED) {
            return AuthorizedDispatchResult.Rejected(AuthorizedDispatchRejection.RESULT_CORRELATION_MISMATCH)
        }
        return AuthorizedDispatchResult.Invoked(result, null)
    }

    private fun correlationFailure(
        request: CapabilityRequest,
        context: AuthorizedDispatchContext,
        enforceDeadline: Boolean = true,
    ): AuthorizedDispatchRejection? {
        val exact = request.providerTarget as? CapabilityProviderTarget.Exact
            ?: return AuthorizedDispatchRejection.PROVIDER_CORRELATION_MISMATCH
        if (exact.providerInstanceId != provider.identity.instanceId ||
            context.providerInstanceId != provider.identity.instanceId
        ) return AuthorizedDispatchRejection.PROVIDER_CORRELATION_MISMATCH
        if (request.executionContext.epoch != context.providerGeneration.value) {
            return AuthorizedDispatchRejection.GENERATION_STALE
        }
        if (enforceDeadline && (context.now.value < request.createdAt.value || context.now.value >= request.deadline.value)) {
            return AuthorizedDispatchRejection.REQUEST_EXPIRED
        }

        val report = negotiation() ?: return AuthorizedDispatchRejection.NEGOTIATION_STALE
        if (report.negotiationId != context.negotiationId ||
            report.providerInstanceId != context.providerInstanceId ||
            report.providerGeneration != context.providerGeneration ||
            report.status !in successfulStatuses
        ) return AuthorizedDispatchRejection.NEGOTIATION_STALE
        val record = report.negotiatedCapabilities.singleOrNull { it.capability == request.capability }
            ?: return AuthorizedDispatchRejection.NEGOTIATION_STALE
        if (context.now.value < record.negotiatedAt.value || context.now.value >= record.validUntil.value) {
            return AuthorizedDispatchRejection.NEGOTIATION_STALE
        }
        if (record.connectionSessionIds != context.connectionSessionIds) {
            return AuthorizedDispatchRejection.CONNECTION_SESSION_STALE
        }
        return null
    }

    private fun authorizationFailure(
        request: CapabilityRequest,
        authorization: AuthorizationDecision?,
        context: AuthorizedDispatchContext,
    ): AuthorizedDispatchRejection? {
        authorization ?: return AuthorizedDispatchRejection.AUTHORIZATION_MISSING
        if (authorization.status != AuthorizationDecisionStatus.AUTHORIZED) {
            return AuthorizedDispatchRejection.AUTHORIZATION_DENIED
        }
        val audit = authorization.audit
        if (audit.requestId != request.requestId || audit.requestAuditId != request.auditId ||
            audit.capability != request.capability || audit.providerInstanceId != context.providerInstanceId ||
            audit.providerGeneration != context.providerGeneration || audit.negotiationId != context.negotiationId ||
            (request.authorizationReference != null && request.authorizationReference != authorization.authorizationReference)
        ) return AuthorizedDispatchRejection.AUTHORIZATION_CORRELATION_MISMATCH
        return null
    }

    private companion object {
        const val STOP_CAPABILITY = "mobility.stop@1.0"
        val successfulStatuses = setOf(
            CapabilityNegotiationStatus.NEGOTIATED,
            CapabilityNegotiationStatus.PARTIALLY_NEGOTIATED,
        )
    }
}
