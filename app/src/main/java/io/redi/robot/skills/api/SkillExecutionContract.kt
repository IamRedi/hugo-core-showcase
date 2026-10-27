package io.redi.robot.skills.api

import io.redi.robot.capability.api.CapabilityFailureCode
import io.redi.robot.capability.api.CapabilityOutcomeEvidence
import io.redi.robot.capability.api.CapabilityProviderTarget
import io.redi.robot.capability.api.CapabilityRequest
import io.redi.robot.capability.api.CapabilityResult
import io.redi.robot.capability.api.CapabilityResultState
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.trust.runtime.AuthorizationDecision

class AuthorizedSkillInvocation private constructor(
    val executionId: SkillExecutionId,
    val request: CapabilityRequest,
    val authorization: AuthorizationDecision,
) {
    val grantsNewAuthority: Boolean get() = false
    val bypassesAuthorizationGate: Boolean get() = false

    override fun toString(): String = "AuthorizedSkillInvocation(" +
        "execution=$executionId, request=redacted, authorization=H030-verified)"

    companion object {
        fun create(
            executionId: SkillExecutionId,
            request: CapabilityRequest,
            authorization: AuthorizationDecision,
        ): SkillInvocationResult<AuthorizedSkillInvocation> {
            if (!authorization.isAuthorized) {
                return SkillInvocationResult.Failure(SkillInvocationError.NOT_AUTHORIZED)
            }
            val audit = authorization.audit
            if (
                audit.requestId != request.requestId ||
                audit.requestAuditId != request.auditId ||
                audit.capability != request.capability ||
                audit.actorKind != request.actor
            ) {
                return SkillInvocationResult.Failure(SkillInvocationError.AUTHORIZATION_CORRELATION_MISMATCH)
            }
            val target = request.providerTarget as? CapabilityProviderTarget.Exact
                ?: return SkillInvocationResult.Failure(SkillInvocationError.EXACT_PROVIDER_REQUIRED)
            if (target.providerInstanceId != audit.providerInstanceId) {
                return SkillInvocationResult.Failure(SkillInvocationError.AUTHORIZATION_CORRELATION_MISMATCH)
            }
            if (
                request.authorizationReference == null ||
                authorization.authorizationReference != request.authorizationReference
            ) {
                return SkillInvocationResult.Failure(SkillInvocationError.AUTHORIZATION_REFERENCE_MISMATCH)
            }
            return SkillInvocationResult.Success(AuthorizedSkillInvocation(executionId, request, authorization))
        }
    }
}

enum class SkillInvocationError {
    NOT_AUTHORIZED,
    AUTHORIZATION_CORRELATION_MISMATCH,
    AUTHORIZATION_REFERENCE_MISMATCH,
    EXACT_PROVIDER_REQUIRED,
}

sealed interface SkillInvocationResult<out T> {
    data class Success<T>(val value: T) : SkillInvocationResult<T>
    data class Failure(val error: SkillInvocationError) : SkillInvocationResult<Nothing>
}

enum class SkillDispatchStartDisposition {
    ACCEPTED,
    BUSY,
    UNAVAILABLE,
    REJECTED,
}

data class SkillDispatchStartResult(
    val disposition: SkillDispatchStartDisposition,
    val requestOwned: Boolean,
) {
    init {
        require(requestOwned == (disposition == SkillDispatchStartDisposition.ACCEPTED))
    }
}

enum class SkillDispatchCancelDisposition {
    REQUESTED,
    ALREADY_CANCELLING,
    NOT_ACTIVE,
    WRONG_EXECUTION,
}

fun interface AuthorizedSkillResultListener {
    fun onResult(result: CapabilityResult)
}

/** H007/H030-only dispatch boundary. Implementations may not expose a raw adapter or transport. */
interface AuthorizedSkillDispatchPort {
    fun start(
        invocation: AuthorizedSkillInvocation,
        listener: AuthorizedSkillResultListener,
    ): SkillDispatchStartResult

    /** Cancellation carries correlation only; it cannot reuse or mint authorization authority. */
    fun cancel(
        executionId: SkillExecutionId,
        request: CapabilityRequest,
    ): SkillDispatchCancelDisposition
}

enum class SkillExecutionLifecycle {
    RUNNING,
    CANCELLING,
    TIMING_OUT,
}

enum class SkillCancellationReason {
    USER_REQUEST,
    BACKGROUNDED,
    SESSION_CANCELLED,
    DEADLINE_EXCEEDED,
}

enum class SkillExecutorStartDisposition {
    ACCEPTED,
    BUSY,
    UNKNOWN_SKILL,
    INVALID_REQUEST,
    CAPABILITY_MISMATCH,
    ACTOR_MISMATCH,
    INPUT_MISMATCH,
    AUTHORIZATION_NOT_CURRENT,
    DISPATCH_BUSY,
    DISPATCH_UNAVAILABLE,
    DISPATCH_REJECTED,
    REPLAY_REJECTED,
    TRACKING_CAPACITY_EXCEEDED,
}

data class SkillExecutorStartResult(
    val disposition: SkillExecutorStartDisposition,
    val executionOwned: Boolean,
) {
    init {
        require(executionOwned == (disposition == SkillExecutorStartDisposition.ACCEPTED))
    }
}

enum class SkillExecutorCancelDisposition {
    REQUESTED,
    ALREADY_CANCELLING,
    NOT_ACTIVE,
    WRONG_EXECUTION,
    STALE_TIME,
}

enum class SkillTimeDisposition {
    TIMEOUT_REQUESTED,
    CANCELLATION_EXPIRED,
    EARLY,
    NOT_ACTIVE,
    WRONG_EXECUTION,
}

enum class SkillTerminalOutcome {
    COMPLETED,
    REJECTED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    UNKNOWN,
    MALFORMED_RESULT,
}

data class SkillExecutionSnapshot(
    val active: Boolean,
    val lifecycle: SkillExecutionLifecycle?,
    val skill: SkillReference?,
    val deadlineAt: MonotonicTimestampMs?,
    val authorizationRetained: Boolean,
) {
    init {
        require(active == (lifecycle != null))
        require(active == (skill != null))
        require(active == (deadlineAt != null))
        require(!authorizationRetained || active)
        require(lifecycle == SkillExecutionLifecycle.RUNNING || !authorizationRetained)
    }

    val grantsAuthority: Boolean get() = false
    val provesSafety: Boolean get() = false
    val provesPhysicalFeasibility: Boolean get() = false
}

sealed interface SkillExecutionEvent {
    val executionId: SkillExecutionId
    val skill: SkillReference
    val occurredAt: MonotonicTimestampMs

    val grantsAuthority: Boolean get() = false
    val provesSafety: Boolean get() = false
    val provesPhysicalFeasibility: Boolean get() = false

    data class Dispatched(
        override val executionId: SkillExecutionId,
        override val skill: SkillReference,
        override val occurredAt: MonotonicTimestampMs,
    ) : SkillExecutionEvent

    data class Progress(
        override val executionId: SkillExecutionId,
        override val skill: SkillReference,
        override val occurredAt: MonotonicTimestampMs,
        val state: CapabilityResultState,
        val evidence: CapabilityOutcomeEvidence,
    ) : SkillExecutionEvent {
        init {
            require(!state.terminal)
        }
    }

    data class CancellationRequested(
        override val executionId: SkillExecutionId,
        override val skill: SkillReference,
        override val occurredAt: MonotonicTimestampMs,
        val reason: SkillCancellationReason,
        val dispatch: SkillDispatchCancelDisposition,
        val authorizationReleased: Boolean,
    ) : SkillExecutionEvent {
        init {
            require(authorizationReleased)
        }
    }

    data class Terminal(
        override val executionId: SkillExecutionId,
        override val skill: SkillReference,
        override val occurredAt: MonotonicTimestampMs,
        val outcome: SkillTerminalOutcome,
        val result: CapabilityResult?,
        val failureCode: CapabilityFailureCode?,
        val authorizationReleased: Boolean,
    ) : SkillExecutionEvent {
        init {
            require(authorizationReleased)
            when (outcome) {
                SkillTerminalOutcome.MALFORMED_RESULT -> require(result == null)
                SkillTerminalOutcome.UNKNOWN -> Unit
                else -> require(result != null)
            }
        }

        override fun toString(): String = "SkillExecutionEvent.Terminal(" +
            "execution=$executionId, skill=$skill, at=${occurredAt.value}, outcome=$outcome, " +
            "failure=$failureCode, output=redacted, authorizationReleased=true)"
    }
}

fun interface SkillExecutionListener {
    fun onEvent(event: SkillExecutionEvent)
}
