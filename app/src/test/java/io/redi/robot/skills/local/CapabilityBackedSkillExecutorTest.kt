package io.redi.robot.skills.local

import io.redi.robot.capability.api.CapabilityActorKind
import io.redi.robot.capability.api.CapabilityArgumentName
import io.redi.robot.capability.api.CapabilityArgumentValue
import io.redi.robot.capability.api.CapabilityAuditId
import io.redi.robot.capability.api.CapabilityAuthorizationReference
import io.redi.robot.capability.api.CapabilityContractReference
import io.redi.robot.capability.api.CapabilityData
import io.redi.robot.capability.api.CapabilityDescriptorResult
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.CapabilityExecutionContext
import io.redi.robot.capability.api.CapabilityFailure
import io.redi.robot.capability.api.CapabilityFailureCode
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityOutcomeEvidence
import io.redi.robot.capability.api.CapabilityProviderTarget
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.CapabilityRequest
import io.redi.robot.capability.api.CapabilityRequestId
import io.redi.robot.capability.api.CapabilityResult
import io.redi.robot.capability.api.CapabilityResultState
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.negotiation.CapabilityNegotiationId
import io.redi.robot.capability.provider.CapabilityProviderPortResult
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.interaction.localintent.LocalIntentRouteStatus
import io.redi.robot.interaction.localintent.LocalIntentRouter
import io.redi.robot.skills.api.AuthorizedSkillDispatchPort
import io.redi.robot.skills.api.AuthorizedSkillInvocation
import io.redi.robot.skills.api.AuthorizedSkillResultListener
import io.redi.robot.skills.api.LocalSkillClass
import io.redi.robot.skills.api.SkillCancellationReason
import io.redi.robot.skills.api.SkillDefinition
import io.redi.robot.skills.api.SkillDispatchCancelDisposition
import io.redi.robot.skills.api.SkillDispatchStartDisposition
import io.redi.robot.skills.api.SkillDispatchStartResult
import io.redi.robot.skills.api.SkillExecutionEvent
import io.redi.robot.skills.api.SkillExecutionId
import io.redi.robot.skills.api.SkillExecutionLifecycle
import io.redi.robot.skills.api.SkillInvocationError
import io.redi.robot.skills.api.SkillInvocationResult
import io.redi.robot.skills.api.SkillModelError
import io.redi.robot.skills.api.SkillModelResult
import io.redi.robot.skills.api.SkillReference
import io.redi.robot.skills.api.SkillRegistry
import io.redi.robot.skills.api.SkillTerminalOutcome
import io.redi.robot.skills.api.SkillTimeDisposition
import io.redi.robot.skills.api.SkillExecutorCancelDisposition
import io.redi.robot.skills.api.SkillExecutorStartDisposition
import io.redi.robot.trust.runtime.AuthorizationAuditRecord
import io.redi.robot.trust.runtime.AuthorizationDecision
import io.redi.robot.trust.runtime.AuthorizationDecisionId
import io.redi.robot.trust.runtime.AuthorizationDecisionReason
import io.redi.robot.trust.runtime.AuthorizationDecisionStatus
import io.redi.robot.trust.runtime.AuthorizationGateModelResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class CapabilityBackedSkillExecutorTest {
    @Test
    fun `built in registry is deterministic typed and contains no motion skill`() {
        val snapshot = HugoLocalSkillCatalog.registry.snapshot
        assertEquals("1.0", snapshot.schemaVersion)
        assertEquals(
            listOf(
                "hugo.local.connection_status@1.0",
                "hugo.local.light_off@1.0",
                "hugo.local.lower_contact_status@1.0",
            ),
            snapshot.definitions.map { it.reference.wireValue },
        )
        assertTrue(snapshot.definitions.all { it.cancellationRequired })
        assertTrue(snapshot.definitions.none { it.capability.wireValue.startsWith("mobility.") })
        assertTrue(snapshot.definitions.none { it.capability.wireValue.contains("head") })
        assertFalse(snapshot.installed)
        assertFalse(snapshot.enabled)
        assertFalse(snapshot.authorized)
        assertFalse(snapshot.executing)

        val light = definition("hugo.local.light_off@1.0")
        assertEquals(LocalSkillClass.NON_MOTION_CONTROL, light.skillClass)
        assertTrue(light.acceptsInput(light.fixedInput))
        val enabled = light.fixedInput!!.values.values.single() as CapabilityArgumentValue.Flag
        assertFalse(enabled.value)
    }

    @Test
    fun `H046 status and light proposals resolve to exact reviewed skills without executing`() {
        val router = LocalIntentRouter()
        val connection = router.route("en-US", listOf("connection status"))
        val light = router.route("en-US", listOf("light off"))
        assertEquals(LocalIntentRouteStatus.PROPOSED, connection.status)
        assertEquals(LocalIntentRouteStatus.PROPOSED, light.status)

        val connectionSkills = HugoLocalSkillCatalog.registry.findByCapability(connection.proposal!!.capability)
        val lightSkills = HugoLocalSkillCatalog.registry.findByCapability(light.proposal!!.capability)
        assertEquals(listOf("hugo.local.connection_status@1.0"), connectionSkills.map { it.reference.wireValue })
        assertEquals(listOf("hugo.local.light_off@1.0"), lightSkills.map { it.reference.wireValue })
        assertTrue(lightSkills.single().acceptsInput(light.proposal!!.input))
        assertFalse(light.proposal!!.executesCapability)
    }

    @Test
    fun `skill schema and registry reject malformed conflicting or unbounded definitions`() {
        assertEquals(
            SkillModelError.INVALID_SKILL_REFERENCE,
            (SkillReference.parse("bad-skill") as SkillModelResult.Failure).error,
        )
        assertEquals(
            SkillModelError.INVALID_SKILL_REFERENCE,
            (SkillReference.parse("hugo.local.bad@01.0") as SkillModelResult.Failure).error,
        )
        val valid = definition("hugo.local.connection_status@1.0")
        assertEquals(
            SkillModelError.DUPLICATE_SKILL,
            (SkillRegistry.create(listOf(valid, valid)) as SkillModelResult.Failure).error,
        )
        assertEquals(
            SkillModelError.EMPTY_REGISTRY,
            (SkillRegistry.create(emptyList()) as SkillModelResult.Failure).error,
        )
        val invalidDuration = SkillDefinition.create(
            valid.reference,
            valid.capability,
            valid.actor,
            valid.skillClass,
            valid.inputContract,
            valid.fixedInput,
            valid.outputContract,
            maximumDurationMs = 31_000,
            cancellationRequired = true,
        )
        assertEquals(SkillModelError.INVALID_DURATION, (invalidDuration as SkillModelResult.Failure).error)
        val noCancellation = SkillDefinition.create(
            valid.reference,
            valid.capability,
            valid.actor,
            valid.skillClass,
            valid.inputContract,
            valid.fixedInput,
            valid.outputContract,
            maximumDurationMs = 1_000,
            cancellationRequired = false,
        )
        assertEquals(SkillModelError.CANCELLATION_REQUIRED, (noCancellation as SkillModelResult.Failure).error)
    }

    @Test
    fun `authorized invocation requires exact correlated H030 decision and provider`() {
        val definition = definition("hugo.local.connection_status@1.0")
        val request = request(definition)
        assertTrue(AuthorizedSkillInvocation.create(EXECUTION, request, decision(request)) is SkillInvocationResult.Success)

        val denied = decision(
            request,
            status = AuthorizationDecisionStatus.PERMISSION_REQUIRED,
            reason = AuthorizationDecisionReason.GRANT_MISSING,
        )
        assertEquals(
            SkillInvocationError.NOT_AUTHORIZED,
            (AuthorizedSkillInvocation.create(EXECUTION, request, denied) as SkillInvocationResult.Failure).error,
        )
        val wrongRequest = request(definition, requestId = requestId("00000000-0000-0000-0000-000000000150"))
        assertEquals(
            SkillInvocationError.AUTHORIZATION_CORRELATION_MISMATCH,
            (AuthorizedSkillInvocation.create(EXECUTION, wrongRequest, decision(request)) as SkillInvocationResult.Failure).error,
        )
        val anyTarget = request(definition, target = CapabilityProviderTarget.AnyCompatible)
        assertEquals(
            SkillInvocationError.EXACT_PROVIDER_REQUIRED,
            (AuthorizedSkillInvocation.create(EXECUTION, anyTarget, decision(anyTarget)) as SkillInvocationResult.Failure).error,
        )
    }

    @Test
    fun `authorized status skill preserves H007 progress and observed typed outcome`() {
        val harness = Harness()
        val definition = definition("hugo.local.connection_status@1.0")
        val invocation = invocation(definition)
        val start = harness.executor.start(definition.reference, invocation, time(20), harness.events::add)
        assertEquals(SkillExecutorStartDisposition.ACCEPTED, start.disposition)
        assertTrue(start.executionOwned)
        assertTrue(harness.executor.snapshot().authorizationRetained)

        harness.dispatch.emit(0, result(invocation.request, CapabilityResultState.ACCEPTED, 21))
        harness.dispatch.emit(0, result(invocation.request, CapabilityResultState.STARTED, 22))
        harness.dispatch.emit(
            0,
            result(
                invocation.request,
                CapabilityResultState.COMPLETED,
                23,
                output = connectionOutput(true),
                evidence = CapabilityOutcomeEvidence.LOCALLY_OBSERVED,
            ),
        )

        assertEquals(4, harness.events.size)
        val terminal = harness.events.last() as SkillExecutionEvent.Terminal
        assertEquals(SkillTerminalOutcome.COMPLETED, terminal.outcome)
        assertEquals(CapabilityOutcomeEvidence.LOCALLY_OBSERVED, terminal.result!!.evidence)
        assertEquals("hugo.contract.provider_connection_status_output@1.0", terminal.result!!.output!!.contract.wireValue)
        assertTrue(terminal.authorizationReleased)
        assertFalse(harness.executor.snapshot().active)
        assertFalse(terminal.toString().contains(PROVIDER.value))
    }

    @Test
    fun `unknown skill invalid capability actor input and duration never dispatch`() {
        val harness = Harness()
        val definition = definition("hugo.local.connection_status@1.0")
        val invocation = invocation(definition)
        assertEquals(
            SkillExecutorStartDisposition.UNKNOWN_SKILL,
            harness.executor.start(skill("hugo.local.unknown@1.0"), invocation, time(20), harness.events::add).disposition,
        )

        val light = definition("hugo.local.light_off@1.0")
        assertEquals(
            SkillExecutorStartDisposition.CAPABILITY_MISMATCH,
            harness.executor.start(light.reference, invocation, time(20), harness.events::add).disposition,
        )
        val wrongActor = invocation(definition, request = request(definition, actor = CapabilityActorKind.HUGO_RUNTIME))
        assertEquals(
            SkillExecutorStartDisposition.ACTOR_MISMATCH,
            harness.executor.start(definition.reference, wrongActor, time(20), harness.events::add).disposition,
        )
        val lightWrongInputRequest = request(light, input = capabilityData("hugo.contract.body_light_input@1.0", "enabled", true))
        val lightWrongInput = invocation(light, request = lightWrongInputRequest)
        assertEquals(
            SkillExecutorStartDisposition.INPUT_MISMATCH,
            harness.executor.start(light.reference, lightWrongInput, time(20), harness.events::add).disposition,
        )
        val tooLongRequest = request(definition, deadline = 2_011)
        val tooLong = invocation(definition, request = tooLongRequest)
        assertEquals(
            SkillExecutorStartDisposition.INVALID_REQUEST,
            harness.executor.start(definition.reference, tooLong, time(20), harness.events::add).disposition,
        )
        assertTrue(harness.dispatch.starts.isEmpty())
    }

    @Test
    fun `dispatch rejection clears execution and retained authorization`() {
        val harness = Harness()
        harness.dispatch.startDisposition = SkillDispatchStartDisposition.UNAVAILABLE
        val definition = definition("hugo.local.connection_status@1.0")
        val result = harness.executor.start(definition.reference, invocation(definition), time(20), harness.events::add)
        assertEquals(SkillExecutorStartDisposition.DISPATCH_UNAVAILABLE, result.disposition)
        assertFalse(harness.executor.snapshot().active)
        assertTrue(harness.events.isEmpty())
    }

    @Test
    fun `user cancellation releases authorization immediately and terminal cancellation is observed`() {
        val harness = Harness()
        val definition = definition("hugo.local.connection_status@1.0")
        val invocation = invocation(definition)
        harness.executor.start(definition.reference, invocation, time(20), harness.events::add)
        assertEquals(
            SkillExecutorCancelDisposition.REQUESTED,
            harness.executor.cancel(EXECUTION, time(30), SkillCancellationReason.USER_REQUEST),
        )
        val snapshot = harness.executor.snapshot()
        assertEquals(SkillExecutionLifecycle.CANCELLING, snapshot.lifecycle)
        assertFalse(snapshot.authorizationRetained)
        assertEquals(listOf(EXECUTION), harness.dispatch.cancellations)
        val cancellation = harness.events.last() as SkillExecutionEvent.CancellationRequested
        assertTrue(cancellation.authorizationReleased)

        harness.dispatch.emit(
            0,
            result(
                invocation.request,
                CapabilityResultState.CANCELLED,
                31,
                failure = CapabilityFailure(CapabilityFailureCode.CANCELLED),
            ),
        )
        val terminal = harness.events.last() as SkillExecutionEvent.Terminal
        assertEquals(SkillTerminalOutcome.CANCELLED, terminal.outcome)
        assertFalse(harness.executor.snapshot().active)
        assertEquals(SkillExecutorCancelDisposition.NOT_ACTIVE, harness.executor.cancel(EXECUTION, time(32), SkillCancellationReason.USER_REQUEST))
    }

    @Test
    fun `deadline requests cancellation and bounded grace ends with explicit unknown`() {
        val harness = Harness(policy = SkillExecutionPolicy(cancellationGraceMs = 100))
        val definition = definition("hugo.local.connection_status@1.0")
        val invocation = invocation(definition, request = request(definition, deadline = 100))
        harness.executor.start(definition.reference, invocation, time(20), harness.events::add)
        assertEquals(SkillTimeDisposition.EARLY, harness.executor.onTime(EXECUTION, time(99)))
        assertEquals(SkillTimeDisposition.TIMEOUT_REQUESTED, harness.executor.onTime(EXECUTION, time(100)))
        assertEquals(SkillExecutionLifecycle.TIMING_OUT, harness.executor.snapshot().lifecycle)
        assertFalse(harness.executor.snapshot().authorizationRetained)
        assertEquals(SkillTimeDisposition.EARLY, harness.executor.onTime(EXECUTION, time(199)))
        assertEquals(SkillTimeDisposition.CANCELLATION_EXPIRED, harness.executor.onTime(EXECUTION, time(200)))

        val terminal = harness.events.last() as SkillExecutionEvent.Terminal
        assertEquals(SkillTerminalOutcome.UNKNOWN, terminal.outcome)
        assertEquals(CapabilityFailureCode.UNKNOWN, terminal.failureCode)
        assertNull(terminal.result)
        assertFalse(harness.executor.snapshot().active)
    }

    @Test
    fun `provider timed out result during grace remains exact H007 evidence`() {
        val harness = Harness(policy = SkillExecutionPolicy(cancellationGraceMs = 100))
        val definition = definition("hugo.local.connection_status@1.0")
        val invocation = invocation(definition, request = request(definition, deadline = 100))
        harness.executor.start(definition.reference, invocation, time(20), harness.events::add)
        harness.executor.onTime(EXECUTION, time(100))
        harness.dispatch.emit(
            0,
            result(
                invocation.request,
                CapabilityResultState.TIMED_OUT,
                101,
                failure = CapabilityFailure(CapabilityFailureCode.TIMEOUT),
            ),
        )
        val terminal = harness.events.last() as SkillExecutionEvent.Terminal
        assertEquals(SkillTerminalOutcome.TIMED_OUT, terminal.outcome)
        assertEquals(CapabilityResultState.TIMED_OUT, terminal.result!!.state)
        assertFalse(harness.executor.snapshot().active)
    }

    @Test
    fun `wrong correlation and wrong output contract fail as malformed and cancel dispatch`() {
        val wrongCorrelation = Harness()
        val definition = definition("hugo.local.connection_status@1.0")
        val invocation = invocation(definition)
        wrongCorrelation.executor.start(definition.reference, invocation, time(20), wrongCorrelation.events::add)
        val wrongRequest = request(definition, requestId = requestId("00000000-0000-0000-0000-000000000250"))
        wrongCorrelation.dispatch.emit(
            0,
            result(wrongRequest, CapabilityResultState.COMPLETED, 30, output = connectionOutput(true)),
        )
        assertEquals(
            SkillTerminalOutcome.MALFORMED_RESULT,
            (wrongCorrelation.events.last() as SkillExecutionEvent.Terminal).outcome,
        )
        assertEquals(listOf(EXECUTION), wrongCorrelation.dispatch.cancellations)

        val wrongOutput = Harness()
        wrongOutput.executor.start(definition.reference, invocation, time(20), wrongOutput.events::add)
        wrongOutput.dispatch.emit(
            0,
            result(
                invocation.request,
                CapabilityResultState.COMPLETED,
                30,
                output = capabilityData("hugo.contract.lower_contact_status_output@1.0", "safe", true),
            ),
        )
        assertEquals(
            SkillTerminalOutcome.MALFORMED_RESULT,
            (wrongOutput.events.last() as SkillExecutionEvent.Terminal).outcome,
        )
    }

    @Test
    fun `late completion cannot become success and stale prior attempt cannot advance newer execution`() {
        val harness = Harness(policy = SkillExecutionPolicy(cancellationGraceMs = 100))
        val definition = definition("hugo.local.connection_status@1.0")
        val first = invocation(definition, request = request(definition, deadline = 100))
        harness.executor.start(definition.reference, first, time(20), harness.events::add)
        harness.dispatch.emit(
            0,
            result(first.request, CapabilityResultState.COMPLETED, 100, output = connectionOutput(true)),
        )
        assertEquals(SkillExecutionLifecycle.TIMING_OUT, harness.executor.snapshot().lifecycle)
        harness.executor.onTime(EXECUTION, time(200))

        val secondRequest = request(
            definition,
            requestId = requestId("00000000-0000-0000-0000-000000000450"),
            created = 210,
            deadline = 500,
        )
        val second = invocation(definition, request = secondRequest, decidedAt = 220, executionId = EXECUTION_2)
        harness.executor.start(definition.reference, second, time(220), harness.events::add)
        harness.dispatch.emit(
            0,
            result(first.request, CapabilityResultState.COMPLETED, 230, output = connectionOutput(false)),
        )
        assertTrue(harness.executor.snapshot().active)
        harness.dispatch.emit(
            1,
            result(second.request, CapabilityResultState.COMPLETED, 240, output = connectionOutput(true)),
        )
        assertFalse(harness.executor.snapshot().active)
        val completed = harness.events.filterIsInstance<SkillExecutionEvent.Terminal>()
            .count { it.outcome == SkillTerminalOutcome.COMPLETED }
        assertEquals(1, completed)
    }

    @Test
    fun `accepted request and execution identifiers cannot replay and tracking exhaustion fails closed`() {
        val replay = Harness()
        val definition = definition("hugo.local.connection_status@1.0")
        val first = invocation(definition)
        replay.executor.start(definition.reference, first, time(20), replay.events::add)
        replay.dispatch.emit(
            0,
            result(first.request, CapabilityResultState.COMPLETED, 25, output = connectionOutput(true)),
        )
        assertEquals(
            SkillExecutorStartDisposition.REPLAY_REJECTED,
            replay.executor.start(definition.reference, first, time(30), replay.events::add).disposition,
        )
        assertEquals(1, replay.dispatch.starts.size)

        val bounded = Harness(policy = SkillExecutionPolicy(maximumTrackedExecutions = 1))
        bounded.executor.start(definition.reference, first, time(20), bounded.events::add)
        bounded.dispatch.emit(
            0,
            result(first.request, CapabilityResultState.COMPLETED, 25, output = connectionOutput(true)),
        )
        val secondRequest = request(
            definition,
            requestId = requestId("00000000-0000-0000-0000-000000000350"),
            created = 30,
            deadline = 500,
        )
        val second = invocation(definition, secondRequest, decidedAt = 40, executionId = EXECUTION_2)
        assertEquals(
            SkillExecutorStartDisposition.TRACKING_CAPACITY_EXCEEDED,
            bounded.executor.start(definition.reference, second, time(40), bounded.events::add).disposition,
        )
    }

    @Test
    fun `skill execution trace is deterministic redacted and non authoritative`() {
        val harness = Harness()
        val definition = definition("hugo.local.connection_status@1.0")
        val invocation = invocation(definition)
        harness.executor.start(definition.reference, invocation, time(20), harness.events::add)
        harness.dispatch.emit(
            0,
            result(
                invocation.request,
                CapabilityResultState.COMPLETED,
                25,
                output = connectionOutput(true),
                evidence = CapabilityOutcomeEvidence.LOCALLY_OBSERVED,
            ),
        )
        val trace = harness.events.map {
            when (it) {
                is SkillExecutionEvent.Dispatched -> "DISPATCHED:${it.skill}"
                is SkillExecutionEvent.Progress -> "PROGRESS:${it.state}:${it.evidence}"
                is SkillExecutionEvent.CancellationRequested -> "CANCEL:${it.reason}:${it.dispatch}:authority=false"
                is SkillExecutionEvent.Terminal -> "TERMINAL:${it.outcome}:${it.result?.evidence}:authority=false"
            }
        }
        assertEquals(
            listOf(
                "DISPATCHED:hugo.local.connection_status@1.0",
                "TERMINAL:COMPLETED:LOCALLY_OBSERVED:authority=false",
            ),
            trace,
        )
        assertFalse(trace.joinToString().contains(PROVIDER.value))
        assertFalse(harness.executor.snapshot().grantsAuthority)
        assertFalse(harness.executor.snapshot().provesSafety)
        assertFalse(harness.executor.snapshot().provesPhysicalFeasibility)
    }

    @Test
    fun `skills boundaries contain no raw adapter Android vendor transport AI or marketplace coupling`() {
        val api = locate("app/src/main/java/io/redi/robot/skills/api")
            .walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val local = locate("app/src/main/java/io/redi/robot/skills/local")
            .walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val source = "$api\n$local"
        listOf(
            "import android.",
            "import androidx.",
            "Bluetooth",
            "Gatt",
            "FED9",
            "LOOI",
            "body.looi",
            "capability.provider.CapabilityProvider",
            "platform.",
            "OpenAI",
            "apiKey",
            "Marketplace",
            "http://",
            "https://",
        ).forEach { forbidden -> assertFalse("skill boundary leak: $forbidden", source.contains(forbidden)) }
    }

    private class Harness(
        policy: SkillExecutionPolicy = SkillExecutionPolicy(),
    ) {
        val dispatch = FakeDispatchPort()
        val executor = CapabilityBackedSkillExecutor(HugoLocalSkillCatalog.registry, dispatch, policy)
        val events = mutableListOf<SkillExecutionEvent>()
    }

    private class FakeDispatchPort : AuthorizedSkillDispatchPort {
        var startDisposition = SkillDispatchStartDisposition.ACCEPTED
        val starts = mutableListOf<AuthorizedSkillInvocation>()
        val cancellations = mutableListOf<SkillExecutionId>()
        private val registrations = mutableListOf<Pair<AuthorizedSkillInvocation, AuthorizedSkillResultListener>>()
        private var activeRegistration: Int? = null

        override fun start(
            invocation: AuthorizedSkillInvocation,
            listener: AuthorizedSkillResultListener,
        ): SkillDispatchStartResult {
            starts += invocation
            if (startDisposition == SkillDispatchStartDisposition.ACCEPTED) {
                registrations += invocation to listener
                activeRegistration = registrations.lastIndex
            }
            return SkillDispatchStartResult(
                startDisposition,
                requestOwned = startDisposition == SkillDispatchStartDisposition.ACCEPTED,
            )
        }

        override fun cancel(
            executionId: SkillExecutionId,
            request: CapabilityRequest,
        ): SkillDispatchCancelDisposition {
            val index = activeRegistration ?: return SkillDispatchCancelDisposition.NOT_ACTIVE
            if (registrations[index].first.executionId != executionId) return SkillDispatchCancelDisposition.WRONG_EXECUTION
            cancellations += executionId
            return SkillDispatchCancelDisposition.REQUESTED
        }

        fun emit(index: Int, result: CapabilityResult) {
            registrations[index].second.onResult(result)
            if (result.state.terminal && activeRegistration == index) activeRegistration = null
        }
    }

    private fun definition(reference: String): SkillDefinition =
        requireNotNull(HugoLocalSkillCatalog.registry.findExact(skill(reference)))

    private fun skill(raw: String): SkillReference = model(SkillReference.parse(raw))

    private fun invocation(
        definition: SkillDefinition,
        request: CapabilityRequest = request(definition),
        decidedAt: Long = 20,
        executionId: SkillExecutionId = EXECUTION,
    ): AuthorizedSkillInvocation = invocation(
        request,
        decision(request, decidedAt = decidedAt),
        executionId,
    )

    private fun invocation(
        request: CapabilityRequest,
        decision: AuthorizationDecision,
        executionId: SkillExecutionId = EXECUTION,
    ): AuthorizedSkillInvocation =
        when (val result = AuthorizedSkillInvocation.create(executionId, request, decision)) {
            is SkillInvocationResult.Success -> result.value
            is SkillInvocationResult.Failure -> error("invalid test invocation: ${result.error}")
        }

    @Suppress("LongParameterList")
    private fun request(
        definition: SkillDefinition,
        requestId: CapabilityRequestId = requestId("00000000-0000-0000-0000-000000000050"),
        actor: CapabilityActorKind = definition.actor,
        input: CapabilityData? = definition.fixedInput,
        created: Long = 10,
        deadline: Long = 1_000,
        target: CapabilityProviderTarget = CapabilityProviderTarget.Exact(PROVIDER),
    ): CapabilityRequest = envelope(
        CapabilityRequest.create(
            requestId,
            definition.capability,
            target,
            actor,
            envelope(CapabilityExecutionContext.create(5, 50)),
            time(created),
            time(deadline),
            AUTHORIZATION,
            AUDIT,
            input,
        ),
    )

    private fun decision(
        request: CapabilityRequest,
        status: AuthorizationDecisionStatus = AuthorizationDecisionStatus.AUTHORIZED,
        reason: AuthorizationDecisionReason = AuthorizationDecisionReason.AUTHORITY_VALID,
        decidedAt: Long = 20,
    ): AuthorizationDecision {
        val target = (request.providerTarget as? CapabilityProviderTarget.Exact)?.providerInstanceId ?: PROVIDER
        val audit = AuthorizationAuditRecord(
            decisionId = gateModel(AuthorizationDecisionId.fromUuid(UUID.fromString("00000000-0000-4000-8000-000000000050"))),
            requestId = request.requestId,
            requestAuditId = request.auditId,
            actorKind = request.actor,
            providerInstanceId = target,
            capability = request.capability,
            providerGeneration = providerResult(ProviderGeneration.create(50)),
            negotiationId = CapabilityNegotiationId.fromUuid(UUID.fromString("00000000-0000-0000-0000-000000000550")),
            decidedAt = time(decidedAt),
            authorizationReferenceConsidered = true,
            policyReferences = emptyList(),
            evidence = emptyList(),
            checks = emptyList(),
            approvalConsumption = null,
        )
        return AuthorizationDecision(status, reason, if (status == AuthorizationDecisionStatus.AUTHORIZED) AUTHORIZATION else null, audit)
    }

    private fun result(
        request: CapabilityRequest,
        state: CapabilityResultState,
        occurredAt: Long,
        failure: CapabilityFailure? = when (state) {
            CapabilityResultState.REJECTED -> CapabilityFailure(CapabilityFailureCode.PROVIDER_UNAVAILABLE)
            CapabilityResultState.FAILED -> CapabilityFailure(CapabilityFailureCode.PROVIDER_FAILURE)
            CapabilityResultState.CANCELLED -> CapabilityFailure(CapabilityFailureCode.CANCELLED)
            CapabilityResultState.TIMED_OUT -> CapabilityFailure(CapabilityFailureCode.TIMEOUT)
            CapabilityResultState.UNKNOWN -> CapabilityFailure(CapabilityFailureCode.UNKNOWN)
            else -> null
        },
        output: CapabilityData? = null,
        evidence: CapabilityOutcomeEvidence = when (state) {
            CapabilityResultState.ACCEPTED -> CapabilityOutcomeEvidence.REQUEST_ACCEPTED
            CapabilityResultState.UNKNOWN -> CapabilityOutcomeEvidence.OUTCOME_UNKNOWN
            else -> CapabilityOutcomeEvidence.PROVIDER_REPORTED
        },
    ): CapabilityResult = envelope(
        CapabilityResult.create(
            request.requestId,
            request.capability,
            PROVIDER,
            request.executionContext,
            time(occurredAt),
            state,
            failure,
            evidence,
            output,
            request.auditId,
        ),
    )

    private fun connectionOutput(connected: Boolean): CapabilityData = capabilityData(
        "hugo.contract.provider_connection_status_output@1.0",
        "connected",
        connected,
    )

    private fun capabilityData(contract: String, argument: String, value: Boolean): CapabilityData = envelope(
        CapabilityData.create(
            descriptor(CapabilityContractReference.parse(contract)),
            mapOf(envelope(CapabilityArgumentName.parse(argument)) to CapabilityArgumentValue.Flag(value)),
        ),
    )

    private fun requestId(raw: String) = CapabilityRequestId.fromUuid(UUID.fromString(raw))
    private fun time(value: Long): MonotonicTimestampMs = envelope(MonotonicTimestampMs.create(value))

    private fun <T> model(result: SkillModelResult<T>): T = when (result) {
        is SkillModelResult.Success -> result.value
        is SkillModelResult.Failure -> error("invalid test skill model: ${result.error}")
    }

    private fun <T> envelope(result: CapabilityEnvelopeResult<T>): T = when (result) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> error("invalid test envelope: ${result.error}")
    }

    private fun <T> descriptor(result: CapabilityDescriptorResult<T>): T = when (result) {
        is CapabilityDescriptorResult.Success -> result.value
        is CapabilityDescriptorResult.Failure -> error("invalid test descriptor: ${result.error}")
    }

    private fun <T> gateModel(result: AuthorizationGateModelResult<T>): T = when (result) {
        is AuthorizationGateModelResult.Success -> result.value
        is AuthorizationGateModelResult.Failure -> error("invalid test authorization model: ${result.error}")
    }

    private fun <T> providerResult(result: CapabilityProviderPortResult<T>): T = when (result) {
        is CapabilityProviderPortResult.Success -> result.value
        is CapabilityProviderPortResult.Failure -> error("invalid test provider model: ${result.error}")
    }

    private fun locate(relative: String): File = sequenceOf(File(relative), File("../$relative"), File("../../$relative"))
        .firstOrNull(File::exists) ?: error("Unable to locate $relative")

    companion object {
        private val EXECUTION = SkillExecutionId.fromUuid(UUID.fromString("00000000-0000-0000-0000-000000000050"))
        private val EXECUTION_2 = SkillExecutionId.fromUuid(UUID.fromString("00000000-0000-0000-0000-000000000150"))
        private val PROVIDER = ProviderInstanceId.fromUuid(UUID.fromString("50000000-0000-0000-0000-000000000050"))
        private val AUTHORIZATION = CapabilityAuthorizationReference.fromUuid(
            UUID.fromString("60000000-0000-0000-0000-000000000050"),
        )
        private val AUDIT = CapabilityAuditId.fromUuid(UUID.fromString("70000000-0000-0000-0000-000000000050"))
    }
}
