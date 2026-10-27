package io.redi.robot.skills.local

import io.redi.robot.capability.api.CapabilityFailureCode
import io.redi.robot.capability.api.CapabilityResult
import io.redi.robot.capability.api.CapabilityResultCorrelation
import io.redi.robot.capability.api.CapabilityResultState
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.skills.api.AuthorizedSkillDispatchPort
import io.redi.robot.skills.api.AuthorizedSkillInvocation
import io.redi.robot.skills.api.SkillCancellationReason
import io.redi.robot.skills.api.SkillDefinition
import io.redi.robot.skills.api.SkillDispatchCancelDisposition
import io.redi.robot.skills.api.SkillDispatchStartDisposition
import io.redi.robot.skills.api.SkillExecutionEvent
import io.redi.robot.skills.api.SkillExecutionId
import io.redi.robot.skills.api.SkillExecutionLifecycle
import io.redi.robot.skills.api.SkillExecutionListener
import io.redi.robot.skills.api.SkillExecutionSnapshot
import io.redi.robot.skills.api.SkillExecutorCancelDisposition
import io.redi.robot.skills.api.SkillExecutorStartDisposition
import io.redi.robot.skills.api.SkillExecutorStartResult
import io.redi.robot.skills.api.SkillReference
import io.redi.robot.skills.api.SkillRegistry
import io.redi.robot.skills.api.SkillTerminalOutcome
import io.redi.robot.skills.api.SkillTimeDisposition
import java.util.LinkedHashSet

data class SkillExecutionPolicy(
    val cancellationGraceMs: Long = 2_000L,
    val maximumTrackedExecutions: Int = 256,
) {
    init {
        require(cancellationGraceMs in 100L..10_000L)
        require(maximumTrackedExecutions in 1..1_024)
    }
}

/**
 * H050 bounded H007 executor. It accepts only H030-correlated invocations and delegates through a
 * semantic dispatch port; it has no provider selection, raw adapter, transport, or physical path.
 */
class CapabilityBackedSkillExecutor(
    private val registry: SkillRegistry,
    private val dispatchPort: AuthorizedSkillDispatchPort,
    private val policy: SkillExecutionPolicy = SkillExecutionPolicy(),
) {
    private val lock = Any()
    private var nextOrdinal = 0L
    private var active: ActiveExecution? = null
    private val dispatchedExecutionIds = LinkedHashSet<SkillExecutionId>()
    private val dispatchedRequestIds = LinkedHashSet<io.redi.robot.capability.api.CapabilityRequestId>()

    fun start(
        skill: SkillReference,
        invocation: AuthorizedSkillInvocation,
        now: MonotonicTimestampMs,
        listener: SkillExecutionListener,
    ): SkillExecutorStartResult = synchronized(lock) {
        if (active != null) return startResult(SkillExecutorStartDisposition.BUSY)
        val definition = registry.findExact(skill)
            ?: return startResult(SkillExecutorStartDisposition.UNKNOWN_SKILL)
        val request = invocation.request
        if (invocation.executionId in dispatchedExecutionIds || request.requestId in dispatchedRequestIds) {
            return startResult(SkillExecutorStartDisposition.REPLAY_REJECTED)
        }
        if (
            dispatchedExecutionIds.size >= policy.maximumTrackedExecutions ||
            dispatchedRequestIds.size >= policy.maximumTrackedExecutions
        ) {
            return startResult(SkillExecutorStartDisposition.TRACKING_CAPACITY_EXCEEDED)
        }
        val invalid = validateStart(definition, invocation, now)
        if (invalid != null) return startResult(invalid)

        val execution = ActiveExecution(
            ordinal = ++nextOrdinal,
            executionId = invocation.executionId,
            definition = definition,
            request = request,
            listener = listener,
        )
        active = execution
        val dispatch = dispatchPort.start(invocation) { result -> onResult(execution.ordinal, result) }
        if (dispatch.disposition != SkillDispatchStartDisposition.ACCEPTED) {
            clearIfCurrent(execution)
            execution.authorizationRetained = false
            return mapDispatchStart(dispatch.disposition)
        }
        dispatchedExecutionIds += invocation.executionId
        dispatchedRequestIds += request.requestId
        if (active === execution) {
            listener.onEvent(
                SkillExecutionEvent.Dispatched(
                    execution.executionId,
                    definition.reference,
                    now,
                ),
            )
        }
        startResult(SkillExecutorStartDisposition.ACCEPTED)
    }

    fun cancel(
        executionId: SkillExecutionId,
        now: MonotonicTimestampMs,
        reason: SkillCancellationReason,
    ): SkillExecutorCancelDisposition = synchronized(lock) {
        val execution = active ?: return SkillExecutorCancelDisposition.NOT_ACTIVE
        if (execution.executionId != executionId) return SkillExecutorCancelDisposition.WRONG_EXECUTION
        if (now.value < execution.request.createdAt.value) return SkillExecutorCancelDisposition.STALE_TIME
        if (execution.lifecycle != SkillExecutionLifecycle.RUNNING) {
            return SkillExecutorCancelDisposition.ALREADY_CANCELLING
        }
        requestCancellation(execution, now, reason)
        SkillExecutorCancelDisposition.REQUESTED
    }

    fun onTime(executionId: SkillExecutionId, now: MonotonicTimestampMs): SkillTimeDisposition = synchronized(lock) {
        val execution = active ?: return SkillTimeDisposition.NOT_ACTIVE
        if (execution.executionId != executionId) return SkillTimeDisposition.WRONG_EXECUTION
        when (execution.lifecycle) {
            SkillExecutionLifecycle.RUNNING -> {
                if (now.value < execution.request.deadline.value) return SkillTimeDisposition.EARLY
                requestCancellation(execution, now, SkillCancellationReason.DEADLINE_EXCEEDED)
                SkillTimeDisposition.TIMEOUT_REQUESTED
            }
            SkillExecutionLifecycle.CANCELLING,
            SkillExecutionLifecycle.TIMING_OUT,
            -> {
                if (now.value < requireNotNull(execution.cancellationDeadlineAt).value) {
                    return SkillTimeDisposition.EARLY
                }
                clearIfCurrent(execution)
                execution.authorizationRetained = false
                execution.listener.onEvent(
                    SkillExecutionEvent.Terminal(
                        execution.executionId,
                        execution.definition.reference,
                        now,
                        SkillTerminalOutcome.UNKNOWN,
                        result = null,
                        failureCode = CapabilityFailureCode.UNKNOWN,
                        authorizationReleased = true,
                    ),
                )
                SkillTimeDisposition.CANCELLATION_EXPIRED
            }
        }
    }

    fun snapshot(): SkillExecutionSnapshot = synchronized(lock) {
        val execution = active
        SkillExecutionSnapshot(
            active = execution != null,
            lifecycle = execution?.lifecycle,
            skill = execution?.definition?.reference,
            deadlineAt = execution?.request?.deadline,
            authorizationRetained = execution?.authorizationRetained == true,
        )
    }

    private fun validateStart(
        definition: SkillDefinition,
        invocation: AuthorizedSkillInvocation,
        now: MonotonicTimestampMs,
    ): SkillExecutorStartDisposition? {
        val request = invocation.request
        if (request.capability != definition.capability) return SkillExecutorStartDisposition.CAPABILITY_MISMATCH
        if (request.actor != definition.actor) return SkillExecutorStartDisposition.ACTOR_MISMATCH
        if (!definition.acceptsInput(request.input)) return SkillExecutorStartDisposition.INPUT_MISMATCH
        if (
            now.value < request.createdAt.value ||
            now.value >= request.deadline.value ||
            request.deadline.value - request.createdAt.value > definition.maximumDurationMs
        ) {
            return SkillExecutorStartDisposition.INVALID_REQUEST
        }
        val decidedAt = invocation.authorization.audit.decidedAt.value
        if (decidedAt < request.createdAt.value || decidedAt > now.value || decidedAt >= request.deadline.value) {
            return SkillExecutorStartDisposition.AUTHORIZATION_NOT_CURRENT
        }
        return null
    }

    private fun onResult(ordinal: Long, result: CapabilityResult) = synchronized(lock) {
        val execution = active?.takeIf { it.ordinal == ordinal } ?: return
        if (result.correlationWith(execution.request) != CapabilityResultCorrelation.MATCHED) {
            malformed(execution, result.occurredAt)
            return
        }
        if (result.occurredAt.value < execution.request.createdAt.value) {
            malformed(execution, result.occurredAt)
            return
        }
        if (result.occurredAt.value >= execution.request.deadline.value && result.state != CapabilityResultState.TIMED_OUT) {
            if (execution.lifecycle == SkillExecutionLifecycle.RUNNING) {
                requestCancellation(execution, result.occurredAt, SkillCancellationReason.DEADLINE_EXCEEDED)
            }
            return
        }
        if (!result.state.terminal) {
            if (execution.lifecycle == SkillExecutionLifecycle.RUNNING) {
                execution.listener.onEvent(
                    SkillExecutionEvent.Progress(
                        execution.executionId,
                        execution.definition.reference,
                        result.occurredAt,
                        result.state,
                        result.evidence,
                    ),
                )
            }
            return
        }
        if (
            result.state == CapabilityResultState.COMPLETED &&
            execution.definition.outputContract != null &&
            result.output?.contract != execution.definition.outputContract
        ) {
            malformed(execution, result.occurredAt)
            return
        }
        finish(execution, result)
    }

    private fun requestCancellation(
        execution: ActiveExecution,
        now: MonotonicTimestampMs,
        reason: SkillCancellationReason,
    ) {
        execution.lifecycle = if (reason == SkillCancellationReason.DEADLINE_EXCEEDED) {
            SkillExecutionLifecycle.TIMING_OUT
        } else {
            SkillExecutionLifecycle.CANCELLING
        }
        execution.authorizationRetained = false
        execution.cancellationDeadlineAt = timestamp(safeAdd(now.value, policy.cancellationGraceMs))
        val dispatch = dispatchPort.cancel(execution.executionId, execution.request)
        execution.listener.onEvent(
            SkillExecutionEvent.CancellationRequested(
                execution.executionId,
                execution.definition.reference,
                now,
                reason,
                dispatch,
                authorizationReleased = true,
            ),
        )
    }

    private fun malformed(execution: ActiveExecution, at: MonotonicTimestampMs) {
        dispatchPort.cancel(execution.executionId, execution.request)
        clearIfCurrent(execution)
        execution.authorizationRetained = false
        execution.listener.onEvent(
            SkillExecutionEvent.Terminal(
                execution.executionId,
                execution.definition.reference,
                at,
                SkillTerminalOutcome.MALFORMED_RESULT,
                result = null,
                failureCode = CapabilityFailureCode.MALFORMED_RESULT,
                authorizationReleased = true,
            ),
        )
    }

    private fun finish(execution: ActiveExecution, result: CapabilityResult) {
        clearIfCurrent(execution)
        execution.authorizationRetained = false
        execution.listener.onEvent(
            SkillExecutionEvent.Terminal(
                execution.executionId,
                execution.definition.reference,
                result.occurredAt,
                result.state.toSkillOutcome(),
                result,
                result.failure?.code,
                authorizationReleased = true,
            ),
        )
    }

    private fun clearIfCurrent(execution: ActiveExecution) {
        if (active === execution) active = null
    }

    private fun mapDispatchStart(disposition: SkillDispatchStartDisposition): SkillExecutorStartResult = startResult(
        when (disposition) {
            SkillDispatchStartDisposition.ACCEPTED -> error("accepted dispatch handled before mapping")
            SkillDispatchStartDisposition.BUSY -> SkillExecutorStartDisposition.DISPATCH_BUSY
            SkillDispatchStartDisposition.UNAVAILABLE -> SkillExecutorStartDisposition.DISPATCH_UNAVAILABLE
            SkillDispatchStartDisposition.REJECTED -> SkillExecutorStartDisposition.DISPATCH_REJECTED
        },
    )

    private fun startResult(disposition: SkillExecutorStartDisposition) = SkillExecutorStartResult(
        disposition,
        executionOwned = disposition == SkillExecutorStartDisposition.ACCEPTED,
    )

    private fun timestamp(value: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(value)) {
        is io.redi.robot.capability.api.CapabilityEnvelopeResult.Success -> result.value
        is io.redi.robot.capability.api.CapabilityEnvelopeResult.Failure -> error("invalid internal monotonic time")
    }

    private fun safeAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    private data class ActiveExecution(
        val ordinal: Long,
        val executionId: SkillExecutionId,
        val definition: SkillDefinition,
        val request: io.redi.robot.capability.api.CapabilityRequest,
        val listener: SkillExecutionListener,
        var lifecycle: SkillExecutionLifecycle = SkillExecutionLifecycle.RUNNING,
        var authorizationRetained: Boolean = true,
        var cancellationDeadlineAt: MonotonicTimestampMs? = null,
    )
}

private fun CapabilityResultState.toSkillOutcome(): SkillTerminalOutcome = when (this) {
    CapabilityResultState.COMPLETED -> SkillTerminalOutcome.COMPLETED
    CapabilityResultState.REJECTED -> SkillTerminalOutcome.REJECTED
    CapabilityResultState.FAILED -> SkillTerminalOutcome.FAILED
    CapabilityResultState.CANCELLED -> SkillTerminalOutcome.CANCELLED
    CapabilityResultState.TIMED_OUT -> SkillTerminalOutcome.TIMED_OUT
    CapabilityResultState.UNKNOWN -> SkillTerminalOutcome.UNKNOWN
    CapabilityResultState.ACCEPTED,
    CapabilityResultState.STARTED,
    -> error("nonterminal capability result cannot be a terminal skill outcome")
}
