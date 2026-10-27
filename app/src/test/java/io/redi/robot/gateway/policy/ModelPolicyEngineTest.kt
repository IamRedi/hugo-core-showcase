package io.redi.robot.gateway.policy

import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.gateway.contract.GatewayBudget
import io.redi.robot.gateway.contract.GatewayContractResult
import io.redi.robot.gateway.contract.GatewayCost
import io.redi.robot.gateway.contract.GatewayUsage
import io.redi.robot.gateway.contract.GatewayUsageEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPolicyEngineTest {
    @Test
    fun `configuration rejects empty forbidden privacy and unbounded values`() {
        assertTrue(
            ModelPolicyConfiguration.create(emptyMap(), circuit(2, 1_000))
                is ModelPolicyResult.Failure,
        )
        assertTrue(
            ModelTaskPolicy.create(
                budget(512, 30_000),
                100,
                setOf(ContextPrivacyClassification.PRIVATE),
                10, 5_000, 100, 50_000,
            ) is ModelPolicyResult.Failure,
        )
        assertTrue(
            ModelTaskPolicy.create(
                budget(512, 30_000),
                512,
                setOf(ContextPrivacyClassification.RESTRICTED),
                10, 5_000, 100, 50_000,
            ) is ModelPolicyResult.Failure,
        )
        assertTrue(
            ModelTaskPolicy.create(
                budget(512, 30_000),
                512,
                setOf(ContextPrivacyClassification.PUBLIC),
                0, 5_000, 100, 50_000,
            ) is ModelPolicyResult.Failure,
        )
        assertTrue(
            ModelCircuitPolicy.create(0, 1_000) is ModelPolicyResult.Failure,
        )
        assertTrue(
            ModelCircuitPolicy.create(2, 700_000L) is ModelPolicyResult.Failure,
        )
    }

    @Test
    fun `eligible task and privacy are allowed with the configured envelope`() {
        val engine = engine()
        val decision = engine.evaluate(ModelTaskClass.CONVERSATION_TEXT, PRIVATE, 100, DAY_1)

        val allowed = decision as ModelPolicyDecision.Allowed
        assertEquals(512, allowed.allowance.requestBudget.maximumOutputUnits)
        assertEquals(30_000L, allowed.allowance.requestBudget.timeoutMs)
        assertEquals(4_096, allowed.allowance.maximumContextInputUnits)
        assertFalse(allowed.grantsAuthority)
        assertFalse(allowed.selectsModel)
    }

    @Test
    fun `forbidden privacy is blocked before any accounting and empty configuration fails closed`() {
        val engine = engine()
        val blocked = engine.evaluate(ModelTaskClass.CONVERSATION_TEXT, SENSITIVE, 100, DAY_1)
        assertEquals(ModelPolicyBlockReason.PRIVACY_NOT_PERMITTED, (blocked as ModelPolicyDecision.Blocked).reason)
        assertEquals(ModelPolicyBlockReason.PRIVACY_NOT_PERMITTED, engine.snapshot(110L).lastBlockReason)

        // A configuration with no tasks is rejected at construction: policy never silently allows.
        assertTrue(
            ModelPolicyConfiguration.create(emptyMap(), circuit(2, 1_000)) is ModelPolicyResult.Failure,
        )
    }

    @Test
    fun `session output budget exhaustion blocks with an explicit typed result`() {
        val engine = engine(sessionMaximumOutputUnits = 500)
        assertTrue(engine.evaluate(TASK, PRIVATE, 100, DAY_1) is ModelPolicyDecision.Allowed)
        engine.onTerminal(TASK, COMPLETED, usage(input = 100, output = 500, MEASURED), 110, DAY_1)

        val blocked = engine.evaluate(TASK, PRIVATE, 120, DAY_1) as ModelPolicyDecision.Blocked
        assertEquals(ModelPolicyBlockReason.SESSION_BUDGET_EXHAUSTED, blocked.reason)
    }

    @Test
    fun `session request cap blocks even when usage evidence is absent`() {
        val engine = engine(sessionMaximumRequests = 1)
        assertTrue(engine.evaluate(TASK, PRIVATE, 100, DAY_1) is ModelPolicyDecision.Allowed)
        engine.onTerminal(TASK, COMPLETED, usage = null, monotonicNowMs = 110, epochDay = DAY_1)

        val blocked = engine.evaluate(TASK, PRIVATE, 120, DAY_1) as ModelPolicyDecision.Blocked
        assertEquals(ModelPolicyBlockReason.SESSION_REQUEST_EXHAUSTED, blocked.reason)
    }

    @Test
    fun `unknown usage evidence accrues the request honestly without inventing units`() {
        val engine = engine(sessionMaximumRequests = 3)
        assertTrue(engine.evaluate(TASK, PRIVATE, 100, DAY_1) is ModelPolicyDecision.Allowed)
        engine.onTerminal(TASK, COMPLETED, usageUnknown(), 110, DAY_1)

        val snapshot = engine.snapshot(120)
        assertEquals(1, snapshot.sessionRequests)
        assertEquals(1, snapshot.sessionUnknownUsageRequests)
        assertEquals(0L, snapshot.sessionOutputUnits)
        assertEquals(0, snapshot.sessionCostEvidencedRequests)
        assertTrue(engine.evaluate(TASK, PRIVATE, 130, DAY_1) is ModelPolicyDecision.Allowed)
    }

    @Test
    fun `daily request cap blocks on the same day and resets on the next day`() {
        val engine = engine(dailyMaximumRequests = 1)
        assertTrue(engine.evaluate(TASK, PRIVATE, 100, DAY_1) is ModelPolicyDecision.Allowed)
        engine.onTerminal(TASK, COMPLETED, usage(input = 10, output = 20, MEASURED), 110, DAY_1)
        assertEquals(
            ModelPolicyBlockReason.DAILY_REQUEST_EXHAUSTED,
            (engine.evaluate(TASK, PRIVATE, 120, DAY_1) as ModelPolicyDecision.Blocked).reason,
        )
        assertTrue(engine.evaluate(TASK, PRIVATE, 86_400_000L + 100, DAY_2) is ModelPolicyDecision.Allowed)
    }

    @Test
    fun `daily output budget blocks and a later day recovers`() {
        val engine = engine(dailyMaximumOutputUnits = 300)
        assertTrue(engine.evaluate(TASK, PRIVATE, 100, DAY_1) is ModelPolicyDecision.Allowed)
        engine.onTerminal(TASK, COMPLETED, usage(input = 10, output = 300, MEASURED), 110, DAY_1)
        assertEquals(
            ModelPolicyBlockReason.DAILY_BUDGET_EXHAUSTED,
            (engine.evaluate(TASK, PRIVATE, 120, DAY_1) as ModelPolicyDecision.Blocked).reason,
        )
        assertTrue(engine.evaluate(TASK, PRIVATE, 86_400_000L + 100, DAY_2) is ModelPolicyDecision.Allowed)
    }

    @Test
    fun `bounded consecutive provider health failures open the circuit`() {
        val engine = engine(failureThreshold = 2, cooldownMs = 1_000)
        runTerminal(engine, TRANSPORT_FAILURE, 100)
        // One failure below the threshold stays closed.
        assertEquals(ModelCircuitState.CLOSED, engine.snapshot(120).circuitState)
        assertTrue(engine.evaluate(TASK, PRIVATE, 130, DAY_1) is ModelPolicyDecision.Allowed)
        runTerminal(engine, TRANSPORT_FAILURE, 140)

        val blocked = engine.evaluate(TASK, PRIVATE, 150, DAY_1) as ModelPolicyDecision.Blocked
        assertEquals(ModelPolicyBlockReason.CIRCUIT_OPEN, blocked.reason)
        assertEquals(ModelCircuitState.OPEN, blocked.circuitState)
    }

    @Test
    fun `open circuit fails fast then half opens with a single probe that recovers`() {
        val engine = engine(failureThreshold = 1, cooldownMs = 1_000)
        runTerminal(engine, TRANSPORT_FAILURE, 100)

        assertTrue(engine.evaluate(TASK, PRIVATE, 200, DAY_1) is ModelPolicyDecision.Blocked)
        // After the cooldown the next eligible request is the single probe.
        val probe = engine.evaluate(TASK, PRIVATE, 1_100 + 1, DAY_1)
        assertTrue(probe is ModelPolicyDecision.Allowed)
        // A second evaluation while the probe is in flight fails fast.
        assertEquals(
            ModelPolicyBlockReason.CIRCUIT_OPEN,
            (engine.evaluate(TASK, PRIVATE, 1_100 + 2, DAY_1) as ModelPolicyDecision.Blocked).reason,
        )
        engine.onTerminal(TASK, COMPLETED, usage(input = 5, output = 5, MEASURED), 1_100 + 3, DAY_1)

        assertEquals(ModelCircuitState.CLOSED, engine.snapshot(1_100 + 4).circuitState)
        assertTrue(engine.evaluate(TASK, PRIVATE, 1_100 + 5, DAY_1) is ModelPolicyDecision.Allowed)
    }

    @Test
    fun `failed half open probe reopens the circuit without counting beyond the threshold`() {
        val engine = engine(failureThreshold = 3, cooldownMs = 500)
        runTerminal(engine, TRANSPORT_FAILURE, 100)
        runTerminal(engine, FAILED, 110)
        runTerminal(engine, PROTOCOL_ERROR, 120)
        assertTrue(engine.evaluate(TASK, PRIVATE, 130, DAY_1) is ModelPolicyDecision.Blocked)

        val probe = engine.evaluate(TASK, PRIVATE, 1_000, DAY_1)
        assertTrue(probe is ModelPolicyDecision.Allowed)
        engine.onTerminal(TASK, GATEWAY_TIMED_OUT, null, 1_100, DAY_1)

        val blocked = engine.evaluate(TASK, PRIVATE, 1_200, DAY_1) as ModelPolicyDecision.Blocked
        assertEquals(ModelPolicyBlockReason.CIRCUIT_OPEN, blocked.reason)
    }

    @Test
    fun `refusal is a healthy terminal that never counts as provider health failure`() {
        val engine = engine(failureThreshold = 1, cooldownMs = 1_000)
        engine.onTerminal(TASK, REFUSED, null, 100, DAY_1)
        assertEquals(ModelCircuitState.CLOSED, engine.snapshot(110).circuitState)
        assertTrue(engine.evaluate(TASK, PRIVATE, 120, DAY_1) is ModelPolicyDecision.Allowed)
    }

    @Test
    fun `local user and gateway cancellations never count as provider health failures`() {
        val engine = engine(failureThreshold = 1, cooldownMs = 1_000)
        engine.onTerminal(TASK, CLIENT_CANCELLED, null, 100, DAY_1)
        engine.onTerminal(TASK, GATEWAY_CANCELLED, null, 110, DAY_1)
        assertEquals(ModelCircuitState.CLOSED, engine.snapshot(120).circuitState)
        assertEquals(2, engine.snapshot(120).sessionRequests)
        assertTrue(engine.evaluate(TASK, PRIVATE, 130, DAY_1) is ModelPolicyDecision.Allowed)
    }

    @Test
    fun `client timeout counts as provider health failure and opens the circuit`() {
        val engine = engine(failureThreshold = 1, cooldownMs = 60_000)
        runTerminal(engine, CLIENT_TIMED_OUT, 100)
        assertEquals(ModelCircuitState.OPEN, engine.snapshot(110).circuitState)
    }

    @Test
    fun `start rejection releases a half open probe without a health verdict`() {
        val engine = engine(failureThreshold = 1, cooldownMs = 500)
        runTerminal(engine, TRANSPORT_FAILURE, 100)
        val probe = engine.evaluate(TASK, PRIVATE, 700, DAY_1)
        assertTrue(probe is ModelPolicyDecision.Allowed)
        engine.onStartRejected(TASK)

        assertEquals(ModelCircuitState.HALF_OPEN, engine.snapshot(710).circuitState)
        val nextProbe = engine.evaluate(TASK, PRIVATE, 720, DAY_1)
        assertTrue(nextProbe is ModelPolicyDecision.Allowed)
    }

    @Test
    fun `context narrowing can only decrease every configured dimension`() {
        val configured = contextBudget(items = 32, inputUnits = 8_192, bytes = 32_768)
        val narrowed = ModelPolicyContextNarrower.narrow(configured, 4_096)
        assertEquals(32, narrowed.maximumItems)
        assertEquals(4_096, narrowed.maximumInputUnits)
        assertEquals(32_768, narrowed.maximumUtf8Bytes)

        val unchanged = ModelPolicyContextNarrower.narrow(configured, 16_384)
        assertEquals(8_192, unchanged.maximumInputUnits)

        val minimum = ModelPolicyContextNarrower.narrow(configured, 1)
        assertEquals(256, minimum.maximumInputUnits)
    }

    @Test
    fun `usage snapshot stays content free and grants nothing`() {
        val engine = engine()
        engine.onTerminal(TASK, COMPLETED, usage(input = 7, output = 9, MEASURED, costMicros = 3), 100, DAY_1)
        val snapshot = engine.snapshot(110)
        assertFalse(snapshot.grantsAuthority)
        assertFalse(snapshot.executesCapability)
        assertFalse(snapshot.writesMemory)
        assertFalse(snapshot.toString().contains("USD"))
        assertEquals(1, snapshot.sessionCostEvidencedRequests)
        assertEquals(9L, snapshot.sessionOutputUnits)
        assertEquals(null, snapshot.lastBlockReason)
    }

    private fun runTerminal(
        engine: ModelPolicyEngine,
        status: GatewayClientTerminalStatusAlias,
        at: Long,
    ) {
        engine.onTerminal(TASK, status, null, at, DAY_1)
    }

    private fun engine(
        sessionMaximumRequests: Int = 10,
        sessionMaximumOutputUnits: Int = 100_000,
        dailyMaximumRequests: Int = 100,
        dailyMaximumOutputUnits: Int = 1_000_000,
        failureThreshold: Int = 2,
        cooldownMs: Long = 1_000,
    ): ModelPolicyEngine {
        val taskPolicy = (
            ModelTaskPolicy.create(
                budget(512, 30_000),
                4_096,
                setOf(ContextPrivacyClassification.PUBLIC, ContextPrivacyClassification.PRIVATE),
                sessionMaximumRequests,
                sessionMaximumOutputUnits,
                dailyMaximumRequests,
                dailyMaximumOutputUnits,
            ) as ModelPolicyResult.Success
            ).value
        val configuration = (
            ModelPolicyConfiguration.create(mapOf(ModelTaskClass.CONVERSATION_TEXT to taskPolicy), circuit(failureThreshold, cooldownMs))
            as ModelPolicyResult.Success
            ).value
        return ModelPolicyEngine(configuration)
    }

    private fun budget(outputUnits: Int, timeoutMs: Long): GatewayBudget =
        (GatewayBudget.create(outputUnits, timeoutMs) as GatewayContractResult.Success).value

    private fun circuit(threshold: Int, cooldownMs: Long): ModelCircuitPolicy =
        (ModelCircuitPolicy.create(threshold, cooldownMs) as ModelPolicyResult.Success).value

    private fun contextBudget(items: Int, inputUnits: Int, bytes: Int) =
        io.redi.robot.conversation.context.ConversationContextBudget.create(items, inputUnits, bytes)
            .let { result -> (result as io.redi.robot.conversation.context.ConversationContextResult.Success).value }

    private fun usage(input: Long, output: Long, evidence: GatewayUsageEvidence, costMicros: Long? = null): GatewayUsage =
        (GatewayUsage.create(
            input,
            output,
            evidence,
            costMicros?.let { (GatewayCost.create("USD", it) as GatewayContractResult.Success).value },
        ) as GatewayContractResult.Success).value

    private fun usageUnknown(): GatewayUsage =
        (GatewayUsage.create(null, null, GatewayUsageEvidence.UNKNOWN, null) as GatewayContractResult.Success).value

    private companion object {
        private val TASK = ModelTaskClass.CONVERSATION_TEXT
        private val PRIVATE = ContextPrivacyClassification.PRIVATE
        private val SENSITIVE = ContextPrivacyClassification.SENSITIVE
        private val MEASURED = GatewayUsageEvidence.MEASURED
        private const val DAY_1 = 20_000L
        private const val DAY_2 = 20_001L

        private val COMPLETED = io.redi.robot.ai.gateway.GatewayClientTerminalStatus.COMPLETED
        private val CLIENT_CANCELLED = io.redi.robot.ai.gateway.GatewayClientTerminalStatus.CLIENT_CANCELLED
        private val GATEWAY_CANCELLED = io.redi.robot.ai.gateway.GatewayClientTerminalStatus.GATEWAY_CANCELLED
        private val REFUSED = io.redi.robot.ai.gateway.GatewayClientTerminalStatus.REFUSED
        private val FAILED = io.redi.robot.ai.gateway.GatewayClientTerminalStatus.FAILED
        private val TRANSPORT_FAILURE = io.redi.robot.ai.gateway.GatewayClientTerminalStatus.TRANSPORT_FAILURE
        private val CLIENT_TIMED_OUT = io.redi.robot.ai.gateway.GatewayClientTerminalStatus.CLIENT_TIMED_OUT
        private val GATEWAY_TIMED_OUT = io.redi.robot.ai.gateway.GatewayClientTerminalStatus.GATEWAY_TIMED_OUT
        private val PROTOCOL_ERROR = io.redi.robot.ai.gateway.GatewayClientTerminalStatus.PROTOCOL_ERROR
    }
}

private typealias GatewayClientTerminalStatusAlias = io.redi.robot.ai.gateway.GatewayClientTerminalStatus
