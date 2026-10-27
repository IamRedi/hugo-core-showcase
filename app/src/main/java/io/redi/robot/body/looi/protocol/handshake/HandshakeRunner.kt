package io.redi.robot.body.looi.protocol.handshake

/**
 * Pure, deterministic handshake experiment runner for policy A/B.
 *
 * It executes named candidates step-by-step, records timestamps/results, and never
 * maps completion to protocol-ready.
 */
class HandshakeRunner(
    private val transport: HandshakeTransport,
    private val subscriptionState: HandshakeNotificationSubscriptionState,
    private val featureEnabled: () -> Boolean = { true },
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    private var activeGeneration: Long = 0L
    private var activeCandidateId: HandshakeCandidateId? = null
    private var activeCandidate: HandshakeCandidate? = null
    private var activeStepIndex: Int = -1
    private var running: Boolean = false
    private var cancelled: Boolean = false
    private var completed: Boolean = false
    private var activeFeatureEnabled: Boolean = false
    private var activeStepTimeoutMs: Long = 0L
    private var activeStepStartedAtMs: Long = 0L
    private var activeStepHandle: Long = NO_ACTIVE_OPERATION
    private var activeStepToken: Long = 0L
    private var activeStepInFlight: Boolean = false
    private val stepResults = mutableListOf<HandshakeStepResult>()
    private var failureReason: String? = null

    fun start(generation: Long, candidateId: HandshakeCandidateId): HandshakeAttemptSummary {
        if (running) {
            return snapshot()
        }

        resetAttempt(generation, candidateId)
        activeFeatureEnabled = featureEnabled()

        if (!activeFeatureEnabled) {
            completed = true
            running = false
            return snapshot()
        }

        activeCandidate = HANDSHAKE_CANDIDATE_BY_ID[candidateId]
        if (activeCandidate == null) {
            running = false
            completed = true
            failureReason = "Unknown handshake candidate: $candidateId"
            return snapshot()
        }

        startNextStep()
        return snapshot()
    }

    fun summary(): HandshakeAttemptSummary = snapshot()

    fun onTransportResult(result: HandshakeTransportResult) {
        if (!running || activeCandidate == null || !activeStepInFlight || activeStepHandle == NO_ACTIVE_OPERATION) {
            return
        }

        when (result) {
            is HandshakeTransportSuccess -> onStepSuccess()
            is HandshakeTransportFailure -> onStepFailure(result)
        }
    }

    fun checkTimeout(nowMs: Long = nowProvider()) {
        if (!running || !activeStepInFlight) return
        if (nowMs < activeStepTimeoutMs) return

        if (activeStepHandle != NO_ACTIVE_OPERATION) {
            transport.cancel(activeStepHandle)
        }
        onStepFailure(
            HandshakeTransportFailure(
                reason = HandshakeTransportStatusReason.TIMEOUT,
                detail = "step timed out",
            ),
        )
    }

    fun cancel(generation: Long): HandshakeAttemptSummary {
        if (!running || activeGeneration != generation) return snapshot()

        activeStepToken++
        cancelled = true
        running = false
        completed = true
        activeStepInFlight = false
        activeStepTimeoutMs = 0L
        activeStepStartedAtMs = 0L

        if (activeStepHandle != NO_ACTIVE_OPERATION) {
            transport.cancel(activeStepHandle)
        }

        if (activeStepIndex >= 0) {
            val stepId = activeCandidate?.steps?.get(activeStepIndex)?.id ?: "step-$activeStepIndex"
            if (stepResults.size == activeStepIndex + 1) {
                stepResults[activeStepIndex] = stepResults[activeStepIndex].copy(
                    completedAtMs = nowProvider(),
                    failureReason = "Cancelled by operator",
                )
            } else if (stepResults.size <= activeStepIndex) {
                stepResults.add(
                    HandshakeStepResult(
                        stepIndex = activeStepIndex,
                        stepId = stepId,
                        success = false,
                        failureReason = "Cancelled by operator",
                        startedAtMs = activeStepStartedAtMs,
                        completedAtMs = nowProvider(),
                    ),
                )
            }
        }

        if (failureReason == null) {
            failureReason = "Cancelled by operator"
        }
        return snapshot()
    }

    private fun resetAttempt(generation: Long, candidateId: HandshakeCandidateId) {
        activeStepToken++
        activeGeneration = generation
        activeCandidateId = candidateId
        activeCandidate = null
        activeStepIndex = -1
        running = true
        cancelled = false
        completed = false
        activeFeatureEnabled = false
        activeStepTimeoutMs = 0L
        activeStepStartedAtMs = 0L
        activeStepHandle = NO_ACTIVE_OPERATION
        activeStepInFlight = false
        stepResults.clear()
        failureReason = null
    }

    private fun startNextStep() {
        val candidate = activeCandidate ?: return
        val nextIndex = activeStepIndex + 1
        if (nextIndex >= candidate.steps.size) {
            running = false
            completed = true
            return
        }

        activeStepIndex = nextIndex
        val step = candidate.steps[activeStepIndex]
        activeStepStartedAtMs = nowProvider()
        activeStepTimeoutMs = activeStepStartedAtMs + step.timeoutMs
        activeStepInFlight = false
        activeStepHandle = NO_ACTIVE_OPERATION

        val missingSubscriptions = step.requiredActiveSubscriptions.filterNot {
            subscriptionState.isSubscribed(activeGeneration, it)
        }
        if (missingSubscriptions.isNotEmpty()) {
            recordFailure(
                step,
                activeStepIndex,
                HandshakeTransportStatusReason.MISSING_NOTIFICATION_SUBSCRIPTION,
                "missing active notification(s): ${missingSubscriptions.joinToString()}",
            )
            return
        }

        val operation = step.operation
        if (operation == null) {
            recordSuccess(step, activeStepIndex)
            startNextStep()
            return
        }

        activeStepInFlight = true
        activeStepToken++
        val callbackToken = activeStepToken
        activeStepHandle = transport.execute(operation) { result ->
            if (activeStepToken != callbackToken || !activeStepInFlight || !running) {
                return@execute
            }
            onTransportResult(result)
        }
        activeStepTimeoutMs = activeStepStartedAtMs + step.timeoutMs
    }

    private fun onStepSuccess() {
        val candidate = activeCandidate ?: return
        val step = candidate.steps[activeStepIndex]
        recordSuccess(step, activeStepIndex)
        invalidateInFlightStep()
        startNextStep()
    }

    private fun onStepFailure(failure: HandshakeTransportFailure) {
        if (!activeStepInFlight) return
        val candidate = activeCandidate ?: return
        val step = candidate.steps[activeStepIndex]
        running = false
        completed = true
        invalidateInFlightStep()
        recordFailure(
            step,
            activeStepIndex,
            failure.reason,
            failure.detail,
        )
    }

    private fun recordSuccess(step: HandshakeStep, stepIndex: Int, nowMs: Long = nowProvider()) {
        stepResults.add(
            HandshakeStepResult(
                stepIndex = stepIndex,
                stepId = step.id,
                success = true,
                failureReason = null,
                startedAtMs = activeStepStartedAtMs,
                completedAtMs = nowMs,
            ),
        )
        failureReason = null
    }

    private fun recordFailure(
        step: HandshakeStep,
        stepIndex: Int,
        reason: HandshakeTransportStatusReason,
        detail: String,
        nowMs: Long = nowProvider(),
    ) {
        stepResults.add(
            HandshakeStepResult(
                stepIndex = stepIndex,
                stepId = step.id,
                success = false,
                failureReason = "code=${reason.name}; detail=$detail",
                startedAtMs = activeStepStartedAtMs,
                completedAtMs = nowMs,
            ),
        )
        running = false
        completed = true
        cancelled = reason == HandshakeTransportStatusReason.CANCELLED
        failureReason = detail
        invalidateInFlightStep()
    }

    private fun invalidateInFlightStep() {
        activeStepHandle = NO_ACTIVE_OPERATION
        activeStepInFlight = false
        activeStepTimeoutMs = 0L
        activeStepStartedAtMs = 0L
    }

    private fun snapshot(): HandshakeAttemptSummary = HandshakeAttemptSummary(
        generation = activeGeneration,
        candidate = activeCandidateId ?: HandshakeCandidateId.A,
        running = running,
        completed = completed,
        cancelled = cancelled,
        featureEnabled = activeFeatureEnabled,
        stepResults = stepResults.toList(),
        protocolReady = false,
        failureReason = failureReason,
    )

    private companion object {
        const val NO_ACTIVE_OPERATION = Long.MIN_VALUE
    }
}
