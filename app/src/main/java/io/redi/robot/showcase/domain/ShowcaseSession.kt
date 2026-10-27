package io.redi.robot.showcase.domain

import io.redi.robot.core.ActionRequest
import io.redi.robot.core.ActionSource
import io.redi.robot.core.RobotAction
import io.redi.robot.core.RobotBody
import io.redi.robot.core.authority.MotionAuthorityEvent
import io.redi.robot.core.authority.MotionAuthorityReducer
import io.redi.robot.core.authority.MotionAuthorityState
import io.redi.robot.core.authority.MotionLeasePolicy
import io.redi.robot.safety.DefaultSafetyController
import io.redi.robot.safety.SafetyContext
import io.redi.robot.safety.SafetyDecision
import io.redi.robot.safety.estop.EmergencyStopEvent
import io.redi.robot.safety.estop.EmergencyStopReason
import io.redi.robot.safety.estop.EmergencyStopReducer
import io.redi.robot.safety.estop.EmergencyStopResetInitiator
import io.redi.robot.safety.estop.EmergencyStopState
import io.redi.robot.safety.sensors.CliffContactSensorState

/** In-memory evaluation adapter. It has no transport, endpoint, provider key or hardware port. */
class SimulatedRobotBody : RobotBody {
    var brightness: Float = 0f
        private set
    var executionCount: Int = 0
        private set
    override suspend fun connect(): Result<Unit> = Result.success(Unit)
    override suspend fun disconnect() { brightness = 0f }
    override suspend fun execute(action: RobotAction): Result<Unit> {
        when (action) {
            is RobotAction.SetLight -> brightness = action.normalizedBrightness
            is RobotAction.Stop -> brightness = 0f
            else -> return Result.failure(IllegalArgumentException("Simulation exposes light and stop only"))
        }
        executionCount++
        return Result.success(Unit)
    }
    override suspend fun emergencyStop(): Result<Unit> { brightness = 0f; return Result.success(Unit) }
}

enum class DemoScenario { LIGHT, OVERSPEED, STALE }
data class DemoProposal(val scenario: DemoScenario, val generation: Long, val proposedAt: Long)
data class DemoTrace(val stage: String, val result: String)
data class ShowcaseSnapshot(
    val generation: Long,
    val pending: DemoProposal?,
    val result: String,
    val brightness: Float,
    val executionCount: Int,
    val emergencyStopLatched: Boolean,
    val trace: List<DemoTrace>,
)

/**
 * Presentation-only confirmation shell around the unchanged local safety and E-stop reducers.
 * This is not the production H030 authorization orchestrator or a language model.
 * Time is injected. A proposal has no execution effect; approval creates a fresh typed request.
 */
class ShowcaseSession(private val now: () -> Long) {
    private val body = SimulatedRobotBody()
    private val safety = DefaultSafetyController()
    private val authority = MotionAuthorityReducer(MotionLeasePolicy(1_000))
    private var authorityState = MotionAuthorityState(connectionGeneration = 1)
    private var stop = EmergencyStopState()
    private var pending: DemoProposal? = null
    private var sequence = 0L
    private var result = "READY"
    private var trace = listOf(DemoTrace("Body", "SIMULATED · no hardware"))

    fun propose(scenario: DemoScenario) {
        pending = DemoProposal(scenario, authorityState.connectionGeneration, now())
        result = "AWAITING_APPROVAL"
        trace = listOf(DemoTrace("1 · Scripted proposal", scenario.name), DemoTrace("2 · Local confirmation", "Waiting · nothing executed"))
    }

    suspend fun approve() {
        val proposal = pending ?: return
        pending = null
        if (proposal.generation != authorityState.connectionGeneration) {
            reject("GENERATION_CHANGED"); return
        }
        if (now() - proposal.proposedAt !in 0..60_000) {
            reject("PROPOSAL_EXPIRED"); return
        }
        val issuedAt = if (proposal.scenario == DemoScenario.STALE) now() - 1_000 else now()
        val action = when (proposal.scenario) {
            DemoScenario.LIGHT, DemoScenario.STALE -> RobotAction.SetLight(.65f, issuedAt)
            DemoScenario.OVERSPEED -> RobotAction.Drive(.90f, 0f, issuedAt)
        }
        val decision = safety.evaluate(
            ActionRequest(++sequence, ActionSource.AI_AGENT, action),
            SafetyContext(now(), true, authorityState.connectionGeneration, stop.latched, CliffContactSensorState.unknown(authorityState.connectionGeneration)),
        )
        trace = trace.take(1) + DemoTrace("2 · Local confirmation", "Explicit user tap")
        when (decision) {
            is SafetyDecision.Rejected -> reject(decision.reason.name)
            is SafetyDecision.Allowed -> {
                if (body.execute(decision.action).isSuccess) {
                    result = "VIRTUAL_ACTION_APPLIED"
                    trace = trace + DemoTrace("3 · SafetyController", "ALLOWED") + DemoTrace("4 · SimulatedRobotBody", "Light = 65% · no device write")
                } else reject("CAPABILITY_NOT_SIMULATED")
            }
        }
    }

    suspend fun reconnect() {
        body.disconnect()
        authorityState = authority.reduce(authorityState, MotionAuthorityEvent.ReconnectStarted(authorityState.connectionGeneration + 1, now())).state
        body.connect()
        result = "GENERATION_CHANGED"
        trace = trace + DemoTrace("Reconnect", "New generation · old proposal cannot execute")
    }

    suspend fun emergencyStop() {
        stop = EmergencyStopReducer.reduce(stop, EmergencyStopEvent.Activate(EmergencyStopReason.LOCAL_USER, now())).state
        pending = null
        body.emergencyStop()
        result = "EMERGENCY_STOP_LATCHED"
        trace = listOf(DemoTrace("Local stop", "Latched without AI or network"), DemoTrace("SimulatedRobotBody", "Virtual light off"))
    }

    fun resetStop(fromAi: Boolean = false) {
        val transition = EmergencyStopReducer.reduce(stop, EmergencyStopEvent.ResetRequested(if (fromAi) EmergencyStopResetInitiator.AI_AGENT else EmergencyStopResetInitiator.LOCAL_UI, true, now()))
        stop = transition.state
        result = transition.outcome.toString()
        trace = trace + DemoTrace("Reset request", if (stop.latched) "Rejected · local user required" else "Local user reset")
    }

    fun snapshot() = ShowcaseSnapshot(authorityState.connectionGeneration, pending, result, body.brightness, body.executionCount, stop.latched, trace.toList())
    private fun reject(reason: String) { result = reason; trace = trace + DemoTrace("3 · Deterministic rejection", reason) }
}
