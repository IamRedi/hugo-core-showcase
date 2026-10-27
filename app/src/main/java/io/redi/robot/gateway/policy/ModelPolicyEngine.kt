package io.redi.robot.gateway.policy

import io.redi.robot.ai.gateway.GatewayClientTerminalStatus
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.gateway.contract.GatewayUsage
import io.redi.robot.gateway.contract.GatewayUsageEvidence

/**
 * Pure H057 model policy engine. All time is injected: circuit timing uses monotonic milliseconds
 * (ADR-007) and daily accounting uses an injected epoch-day source, so the engine holds no hidden
 * clock. Evaluation and terminal reporting are synchronized; the engine owns no transport, token,
 * model, provider, or memory.
 *
 * Circuit semantics (H057): CLOSED with bounded consecutive provider-health failures opens the
 * circuit for a bounded cooldown; OPEN fails AI requests fast without touching local routing; after
 * the cooldown the circuit is HALF_OPEN and the next eligible request is the single probe; probe
 * success closes the circuit, probe provider-health failure reopens it. There is no background
 * probing, automatic retry, or provider failover.
 */
class ModelPolicyEngine(
    private val configuration: ModelPolicyConfiguration,
) : ModelPolicyPort {
    private val lock = Any()

    private var sessionRequests = 0
    private var sessionInputUnits = 0L
    private var sessionOutputUnits = 0L
    private var sessionUnknownUsageRequests = 0
    private var sessionCostEvidencedRequests = 0

    private var dailyDay: Long? = null
    private var dailyRequests = 0
    private var dailyOutputUnits = 0L

    private var consecutiveHealthFailures = 0
    private var openUntilMs: Long? = null
    private var probeInFlight = false
    private var lastBlockReason: ModelPolicyBlockReason? = null

    override fun evaluate(
        task: ModelTaskClass,
        privacy: ContextPrivacyClassification,
        monotonicNowMs: Long,
        epochDay: Long,
    ): ModelPolicyDecision = synchronized(lock) {
        val taskPolicy = configuration.taskPolicy(task)
            ?: return blocked(ModelPolicyBlockReason.TASK_NOT_PERMITTED, monotonicNowMs)
        if (privacy !in taskPolicy.allowedPrivacy) {
            return blocked(ModelPolicyBlockReason.PRIVACY_NOT_PERMITTED, monotonicNowMs)
        }
        rollDailyWindowIfNeeded(epochDay)
        when (currentState(monotonicNowMs)) {
            ModelCircuitState.OPEN -> return blocked(ModelPolicyBlockReason.CIRCUIT_OPEN, monotonicNowMs)
            ModelCircuitState.HALF_OPEN -> {
                if (probeInFlight) {
                    return blocked(ModelPolicyBlockReason.CIRCUIT_OPEN, monotonicNowMs)
                }
                // The next user-initiated eligible request is the single half-open probe.
                probeInFlight = true
                return allowed(taskPolicy, task)
            }
            ModelCircuitState.CLOSED -> Unit
        }
        if (sessionRequests >= taskPolicy.sessionMaximumRequests) {
            return blocked(ModelPolicyBlockReason.SESSION_REQUEST_EXHAUSTED, monotonicNowMs)
        }
        if (sessionOutputUnits >= taskPolicy.sessionMaximumOutputUnits) {
            return blocked(ModelPolicyBlockReason.SESSION_BUDGET_EXHAUSTED, monotonicNowMs)
        }
        if (dailyRequests >= taskPolicy.dailyMaximumRequests) {
            return blocked(ModelPolicyBlockReason.DAILY_REQUEST_EXHAUSTED, monotonicNowMs)
        }
        if (dailyOutputUnits >= taskPolicy.dailyMaximumOutputUnits) {
            return blocked(ModelPolicyBlockReason.DAILY_BUDGET_EXHAUSTED, monotonicNowMs)
        }
        allowed(taskPolicy, task)
    }

    override fun onTerminal(
        task: ModelTaskClass,
        status: GatewayClientTerminalStatus,
        usage: GatewayUsage?,
        monotonicNowMs: Long,
        epochDay: Long,
    ) = synchronized(lock) {
        // Only configured tasks accrue; an unknown task can never pass evaluate.
        configuration.taskPolicy(task) ?: return
        rollDailyWindowIfNeeded(epochDay)
        sessionRequests += 1
        dailyRequests += 1
        usage?.let { accrued ->
            accrued.inputUnits?.let { sessionInputUnits += it }
            accrued.outputUnits?.let {
                sessionOutputUnits += it
                dailyOutputUnits += it
            }
            when (accrued.evidence) {
                GatewayUsageEvidence.UNKNOWN -> sessionUnknownUsageRequests += 1
                else -> if (accrued.estimatedCost != null) sessionCostEvidencedRequests += 1
            }
        }
        val probe = probeInFlight
        when (status) {
            GatewayClientTerminalStatus.COMPLETED,
            GatewayClientTerminalStatus.REFUSED,
            -> {
                // A healthy terminal (content refusal is still a healthy gateway answer) recovers
                // the circuit and resets the consecutive-failure count.
                consecutiveHealthFailures = 0
                if (probe) {
                    probeInFlight = false
                    openUntilMs = null
                }
            }
            GatewayClientTerminalStatus.TRANSPORT_FAILURE,
            GatewayClientTerminalStatus.CLIENT_TIMED_OUT,
            GatewayClientTerminalStatus.GATEWAY_TIMED_OUT,
            GatewayClientTerminalStatus.FAILED,
            GatewayClientTerminalStatus.PROTOCOL_ERROR,
            -> {
                consecutiveHealthFailures += 1
                if (probe) probeInFlight = false
                if (probe || consecutiveHealthFailures >= configuration.circuit.consecutiveFailureThreshold) {
                    openUntilMs = monotonicNowMs + configuration.circuit.openCooldownMs
                }
            }
            GatewayClientTerminalStatus.GATEWAY_CANCELLED,
            GatewayClientTerminalStatus.CLIENT_CANCELLED,
            -> {
                // Local or user cancellation is neither health failure nor recovery: release an
                // in-flight probe so a later user-initiated request can probe again, count nothing.
                if (probe) probeInFlight = false
            }
        }
    }

    override fun onStartRejected(task: ModelTaskClass) = synchronized(lock) {
        // An allowed request that never reached a stream terminal releases the probe without a
        // provider-health verdict; authentication and local construction failures are H052/H054
        // domains, not gateway health.
        if (probeInFlight) probeInFlight = false
    }

    override fun snapshot(monotonicNowMs: Long): ModelPolicyUsageSnapshot = synchronized(lock) {
        ModelPolicyUsageSnapshot(
            sessionRequests = sessionRequests,
            sessionInputUnits = sessionInputUnits,
            sessionOutputUnits = sessionOutputUnits,
            sessionUnknownUsageRequests = sessionUnknownUsageRequests,
            sessionCostEvidencedRequests = sessionCostEvidencedRequests,
            dailyRequests = dailyRequests,
            dailyOutputUnits = dailyOutputUnits,
            consecutiveHealthFailures = consecutiveHealthFailures,
            circuitState = currentState(monotonicNowMs),
            lastBlockReason = lastBlockReason,
        )
    }

    private fun allowed(taskPolicy: ModelTaskPolicy, task: ModelTaskClass): ModelPolicyDecision =
        ModelPolicyDecision.Allowed(
            ModelPolicyAllowance(
                task = task,
                requestBudget = taskPolicy.requestBudget,
                maximumContextInputUnits = taskPolicy.maximumContextInputUnits,
            ),
        )

    private fun blocked(reason: ModelPolicyBlockReason, monotonicNowMs: Long): ModelPolicyDecision {
        lastBlockReason = reason
        return ModelPolicyDecision.Blocked(reason, currentState(monotonicNowMs))
    }

    private fun currentState(monotonicNowMs: Long): ModelCircuitState {
        val openUntil = openUntilMs ?: return ModelCircuitState.CLOSED
        return if (monotonicNowMs < openUntil) ModelCircuitState.OPEN else ModelCircuitState.HALF_OPEN
    }

    private fun rollDailyWindowIfNeeded(epochDay: Long) {
        if (dailyDay != epochDay) {
            dailyDay = epochDay
            dailyRequests = 0
            dailyOutputUnits = 0L
        }
    }
}
