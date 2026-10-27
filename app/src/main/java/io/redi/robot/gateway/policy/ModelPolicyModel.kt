package io.redi.robot.gateway.policy

import io.redi.robot.ai.gateway.GatewayClientTerminalStatus
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.gateway.contract.GatewayBudget
import io.redi.robot.gateway.contract.GatewayContractResult
import io.redi.robot.gateway.contract.GatewayText
import io.redi.robot.gateway.contract.GatewayUsage
import java.util.Collections

/**
 * Client-side H057 task classification. It names a local purpose for a gateway operation; it never
 * names a model, provider, or endpoint. Actual model selection remains gateway-side (ADR-005).
 */
enum class ModelTaskClass {
    CONVERSATION_TEXT,
    ;
}

/** Typed reasons an H057 policy evaluation refuses a gateway request before any transport opens. */
enum class ModelPolicyBlockReason {
    TASK_NOT_PERMITTED,
    PRIVACY_NOT_PERMITTED,
    CIRCUIT_OPEN,
    SESSION_REQUEST_EXHAUSTED,
    SESSION_BUDGET_EXHAUSTED,
    DAILY_REQUEST_EXHAUSTED,
    DAILY_BUDGET_EXHAUSTED,
    ;
}

/** Provider-health circuit state. OPEN fails AI requests fast; local routing never consults it. */
enum class ModelCircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN,
    ;
}

/** Bounded per-task policy. Every value is validated; nothing here is unbounded or provider-specific. */
class ModelTaskPolicy private constructor(
    val requestBudget: GatewayBudget,
    val maximumContextInputUnits: Int,
    allowedPrivacy: Set<ContextPrivacyClassification>,
    val sessionMaximumRequests: Int,
    val sessionMaximumOutputUnits: Int,
    val dailyMaximumRequests: Int,
    val dailyMaximumOutputUnits: Int,
) {
    val allowedPrivacy: Set<ContextPrivacyClassification> =
        Collections.unmodifiableSet(allowedPrivacy.toSortedSet())

    override fun toString(): String = "ModelTaskPolicy(outputUnits=${requestBudget.maximumOutputUnits}, " +
        "timeoutMs=${requestBudget.timeoutMs}, contextUnits=$maximumContextInputUnits, " +
        "privacy=[redacted], sessionRequests=$sessionMaximumRequests, " +
        "sessionUnits=$sessionMaximumOutputUnits, dailyRequests=$dailyMaximumRequests, " +
        "dailyUnits=$dailyMaximumOutputUnits)"

    companion object {
        const val MINIMUM_CONTEXT_INPUT_UNITS = 256
        const val MAXIMUM_SESSION_REQUESTS = 10_000
        const val MAXIMUM_SESSION_OUTPUT_UNITS = 100_000
        const val MAXIMUM_DAILY_REQUESTS = 100_000
        const val MAXIMUM_DAILY_OUTPUT_UNITS = 1_000_000

        fun create(
            requestBudget: GatewayBudget,
            maximumContextInputUnits: Int,
            allowedPrivacy: Set<ContextPrivacyClassification>,
            sessionMaximumRequests: Int,
            sessionMaximumOutputUnits: Int,
            dailyMaximumRequests: Int,
            dailyMaximumOutputUnits: Int,
        ): ModelPolicyResult<ModelTaskPolicy> = when {
            maximumContextInputUnits !in MINIMUM_CONTEXT_INPUT_UNITS..GatewayText.MAX_CODE_POINTS ->
                ModelPolicyResult.Failure(ModelPolicyModelError.INVALID_CONTEXT_ALLOWANCE)
            allowedPrivacy.isEmpty() ||
                allowedPrivacy.any { it in ConversationContextForbiddenPrivacy.FORBIDDEN } ->
                ModelPolicyResult.Failure(ModelPolicyModelError.FORBIDDEN_PRIVACY_CLASS)
            sessionMaximumRequests !in 1..MAXIMUM_SESSION_REQUESTS ||
                dailyMaximumRequests !in 1..MAXIMUM_DAILY_REQUESTS ->
                ModelPolicyResult.Failure(ModelPolicyModelError.INVALID_REQUEST_CAP)
            sessionMaximumOutputUnits !in 1..MAXIMUM_SESSION_OUTPUT_UNITS ||
                dailyMaximumOutputUnits !in 1..MAXIMUM_DAILY_OUTPUT_UNITS ->
                ModelPolicyResult.Failure(ModelPolicyModelError.INVALID_UNIT_CAP)
            else -> ModelPolicyResult.Success(
                ModelTaskPolicy(
                    requestBudget,
                    maximumContextInputUnits,
                    allowedPrivacy,
                    sessionMaximumRequests,
                    sessionMaximumOutputUnits,
                    dailyMaximumRequests,
                    dailyMaximumOutputUnits,
                ),
            )
        }
    }
}

/** Circuit thresholds. Thresholds are counts, cooldowns are monotonic milliseconds. */
data class ModelCircuitPolicy private constructor(
    val consecutiveFailureThreshold: Int,
    val openCooldownMs: Long,
) {
    override fun toString(): String = "ModelCircuitPolicy(threshold=$consecutiveFailureThreshold, " +
        "cooldownMs=$openCooldownMs)"

    companion object {
        const val MAXIMUM_FAILURE_THRESHOLD = 100
        const val MAXIMUM_COOLDOWN_MS = 600_000L

        fun create(consecutiveFailureThreshold: Int, openCooldownMs: Long): ModelPolicyResult<ModelCircuitPolicy> =
            if (consecutiveFailureThreshold !in 1..MAXIMUM_FAILURE_THRESHOLD ||
                openCooldownMs !in 1..MAXIMUM_COOLDOWN_MS
            ) {
                ModelPolicyResult.Failure(ModelPolicyModelError.INVALID_CIRCUIT_POLICY)
            } else {
                ModelPolicyResult.Success(ModelCircuitPolicy(consecutiveFailureThreshold, openCooldownMs))
            }
    }
}

/**
 * Whole-engine configuration. Construction fails closed on any unbounded, forbidden-privacy, or
 * invalid value; there are no defaults that relax a limit.
 */
class ModelPolicyConfiguration private constructor(
    private val tasks: Map<ModelTaskClass, ModelTaskPolicy>,
    val circuit: ModelCircuitPolicy,
) {
    fun taskPolicy(task: ModelTaskClass): ModelTaskPolicy? = tasks[task]

    val configuredTasks: Set<ModelTaskClass> = Collections.unmodifiableSet(tasks.keys.toSet())

    override fun toString(): String = "ModelPolicyConfiguration(tasks=${tasks.keys}, circuit=$circuit, " +
        "models=[none], providers=[none])"

    companion object {
        fun create(
            tasks: Map<ModelTaskClass, ModelTaskPolicy>,
            circuit: ModelCircuitPolicy,
        ): ModelPolicyResult<ModelPolicyConfiguration> = if (tasks.isEmpty()) {
            ModelPolicyResult.Failure(ModelPolicyModelError.EMPTY_TASK_CONFIGURATION)
        } else {
            ModelPolicyResult.Success(ModelPolicyConfiguration(tasks.toSortedMap(), circuit))
        }
    }
}

/** Allowed decision: the exact H051 envelope for this request plus the narrowed context allowance. */
data class ModelPolicyAllowance(
    val task: ModelTaskClass,
    val requestBudget: GatewayBudget,
    val maximumContextInputUnits: Int,
) {
    val grantsAuthority: Boolean get() = false
    val selectsModel: Boolean get() = false
    val selectsProvider: Boolean get() = false

    override fun toString(): String = "ModelPolicyAllowance(task=$task, " +
        "outputUnits=${requestBudget.maximumOutputUnits}, timeoutMs=${requestBudget.timeoutMs}, " +
        "contextUnits=$maximumContextInputUnits, model=[gateway-side])"
}

sealed interface ModelPolicyDecision {
    val grantsAuthority: Boolean get() = false
    val selectsModel: Boolean get() = false

    data class Allowed(val allowance: ModelPolicyAllowance) : ModelPolicyDecision

    data class Blocked(
        val reason: ModelPolicyBlockReason,
        val circuitState: ModelCircuitState,
    ) : ModelPolicyDecision
}

/** Content-free, bounded usage accounting. Units come only from H051 usage evidence. */
class ModelPolicyUsageSnapshot internal constructor(
    val sessionRequests: Int,
    val sessionInputUnits: Long,
    val sessionOutputUnits: Long,
    val sessionUnknownUsageRequests: Int,
    val sessionCostEvidencedRequests: Int,
    val dailyRequests: Int,
    val dailyOutputUnits: Long,
    val consecutiveHealthFailures: Int,
    val circuitState: ModelCircuitState,
    val lastBlockReason: ModelPolicyBlockReason?,
) {
    val grantsAuthority: Boolean get() = false
    val executesCapability: Boolean get() = false
    val writesMemory: Boolean get() = false

    override fun toString(): String = "ModelPolicyUsageSnapshot(session=$sessionRequests req/" +
        "$sessionInputUnits in/$sessionOutputUnits out/$sessionUnknownUsageRequests unknown/" +
        "$sessionCostEvidencedRequests cost, daily=$dailyRequests req/$dailyOutputUnits out, " +
        "failures=$consecutiveHealthFailures, circuit=$circuitState, lastBlock=$lastBlockReason, " +
        "content=[none])"
}

enum class ModelPolicyModelError {
    INVALID_CONTEXT_ALLOWANCE,
    FORBIDDEN_PRIVACY_CLASS,
    INVALID_REQUEST_CAP,
    INVALID_UNIT_CAP,
    INVALID_CIRCUIT_POLICY,
    EMPTY_TASK_CONFIGURATION,
}

sealed interface ModelPolicyResult<out T> {
    data class Success<T>(val value: T) : ModelPolicyResult<T>
    data class Failure(val error: ModelPolicyModelError) : ModelPolicyResult<Nothing>
}

/**
 * H057 client policy boundary. The engine implements it; the H056 turn coordinator consumes it.
 * Evaluation always happens before any transport open and can only narrow, never enlarge, the
 * H051 request envelope and H054 context bounds.
 */
interface ModelPolicyPort {
    fun evaluate(
        task: ModelTaskClass,
        privacy: ContextPrivacyClassification,
        monotonicNowMs: Long,
        epochDay: Long,
    ): ModelPolicyDecision

    /**
     * Reports one finished gateway stream. Health classification follows H057: transport, timeout,
     * failed, and protocol terminals count; COMPLETED recovers; REFUSED and both cancellation
     * statuses never count.
     */
    fun onTerminal(
        task: ModelTaskClass,
        status: GatewayClientTerminalStatus,
        usage: GatewayUsage?,
        monotonicNowMs: Long,
        epochDay: Long,
    )

    /**
     * Reports that an allowed request never reached a stream terminal (local route rejection,
     * context rejection, or start rejection). It releases an in-flight circuit probe without
     * counting a provider-health failure.
     */
    fun onStartRejected(task: ModelTaskClass)

    fun snapshot(monotonicNowMs: Long): ModelPolicyUsageSnapshot
}

/** Narrows an H054 context budget to the policy allowance. Every dimension can only decrease. */
object ModelPolicyContextNarrower {
    fun narrow(configured: io.redi.robot.conversation.context.ConversationContextBudget, allowanceUnits: Int):
        io.redi.robot.conversation.context.ConversationContextBudget = when (
            val narrowed = io.redi.robot.conversation.context.ConversationContextBudget.create(
                configured.maximumItems,
                minOf(configured.maximumInputUnits, allowanceUnits)
                    .coerceAtLeast(ModelTaskPolicy.MINIMUM_CONTEXT_INPUT_UNITS),
                configured.maximumUtf8Bytes,
            )
        ) {
        is io.redi.robot.conversation.context.ConversationContextResult.Success -> narrowed.value
        is io.redi.robot.conversation.context.ConversationContextResult.Failure ->
            error("Policy narrowing produced an invalid H054 budget")
    }
}

/** Forbidden context privacy classes, mirrored from H054 as a policy precondition. */
object ConversationContextForbiddenPrivacy {
    val FORBIDDEN: Set<ContextPrivacyClassification> = setOf(
        ContextPrivacyClassification.SENSITIVE,
        ContextPrivacyClassification.RESTRICTED,
    )
}
