package io.redi.robot.context.provider

import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.hugo.identity.HugoIdentity
import io.redi.robot.hugo.identity.HugoIdentityId
import io.redi.robot.hugo.identity.HugoIdentityResult
import io.redi.robot.trust.domain.HugoHostRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveBodyStateTest {
    private val reducer = ActiveBodyReducer(success(MountingEvidencePolicy.create(confidence(900))))
    private val host = EmbodimentHost(success(EmbodimentHostReference.parse("mobile-host.primary")), HugoHostRole.MOBILE_HOST)

    @Test
    fun noActiveBodyIsTheInitialState() {
        val state = ActiveBodyState.initial(host)

        assertNull(state.generation)
        assertNull(state.activeSelection)
        assertTrue(state.bodies.isEmpty())
    }

    @Test
    fun validRemoteActiveBodyAssignmentDoesNotRequireMounting() {
        val body = body("00000000-0000-0000-0000-000000000001")
        val observed = observe(body, remote = true)
        val state = reducer.reduce(ActiveBodyState.initial(host), observed).state
        val selected = reducer.reduce(state, select(body, ActiveBodyActivationMode.REMOTE)).state

        assertEquals(body, selected.activeSelection?.body)
        assertEquals(ActiveBodyActivationMode.REMOTE, selected.activeSelection?.mode)
        assertEquals(MountingState.UNKNOWN, selected.body(body)?.mounting)
    }

    @Test
    fun remoteControlWithoutEmbodimentRemainsUnselected() {
        val body = body("00000000-0000-0000-0000-000000000002")
        val state = reducer.reduce(ActiveBodyState.initial(host), observe(body, remote = true)).state

        assertTrue(state.body(body)!!.remotelyControlled)
        assertNull(state.activeSelection)
    }

    @Test
    fun mountedBodyIsNotAutomaticallyActive() {
        val body = body("00000000-0000-0000-0000-000000000003")
        val observed = reducer.reduce(ActiveBodyState.initial(host), observe(body, remote = false)).state
        val mounted = reducer.reduce(
            observed,
            ActiveBodyEvent.MountEvidence(generation(1), body, listOf(evidence("mount-verified", EmbodimentEvidenceKind.MOUNTING_VERIFIED, 0, 100)), time(10)),
        ).state

        assertEquals(MountingState.MOUNTED, mounted.body(body)?.mounting)
        assertNull(mounted.activeSelection)
    }

    @Test
    fun staleMountingEvidenceCannotEstablishAttachment() {
        val body = body("00000000-0000-0000-0000-000000000004")
        val observed = reducer.reduce(ActiveBodyState.initial(host), observe(body, remote = false)).state
        val result = reducer.reduce(
            observed,
            ActiveBodyEvent.MountEvidence(generation(1), body, listOf(evidence("old-mount", EmbodimentEvidenceKind.MOUNTING_VERIFIED, 0, 5)), time(5)),
        )

        assertEquals(MountingState.UNKNOWN, result.state.body(body)?.mounting)
        assertTrue(result.disposition is ActiveBodyDisposition.Applied)
    }

    @Test
    fun staleGenerationIsIgnored() {
        val body = body("00000000-0000-0000-0000-000000000005")
        val state = reducer.reduce(ActiveBodyState.initial(host), observe(body, remote = true, generation = generation(2))).state
        val stale = reducer.reduce(state, observe(body, remote = false, generation = generation(1)))

        assertEquals(ActiveBodyDisposition.Ignored(ActiveBodyIgnoredReason.STALE_GENERATION), stale.disposition)
        assertTrue(stale.state.body(body)!!.remotelyControlled)
    }

    @Test
    fun explicitBodySwitchKeepsHugoIdentityUnchanged() {
        val hugo = HugoIdentity(success(HugoIdentityId.parse("00000000-0000-0000-0000-000000000099")))
        val first = body("00000000-0000-0000-0000-000000000006")
        val second = body("00000000-0000-0000-0000-000000000007")
        var state = reducer.reduce(ActiveBodyState.initial(host), observe(first, remote = true)).state
        state = reducer.reduce(state, observe(second, remote = true)).state
        state = reducer.reduce(state, select(first, ActiveBodyActivationMode.REMOTE)).state
        state = reducer.reduce(state, select(second, ActiveBodyActivationMode.REMOTE)).state

        assertEquals(second, state.activeSelection?.body)
        assertEquals("00000000-0000-0000-0000-000000000099", hugo.id.value)
    }

    @Test
    fun detachAndProviderUnavailableClearActiveSelection() {
        val body = body("00000000-0000-0000-0000-000000000008")
        var state = reducer.reduce(ActiveBodyState.initial(host), observe(body, remote = true)).state
        state = reducer.reduce(state, select(body, ActiveBodyActivationMode.REMOTE)).state
        state = reducer.reduce(state, ActiveBodyEvent.Detach(generation(1), body, evidence("detach", EmbodimentEvidenceKind.DETACH_VERIFIED, 10, 20))).state

        assertEquals(MountingState.DETACHED, state.body(body)?.mounting)
        assertNull(state.activeSelection)

        state = reducer.reduce(state, select(body, ActiveBodyActivationMode.REMOTE)).state
        state = reducer.reduce(state, ActiveBodyEvent.Unavailable(generation(1), body, evidence("offline", EmbodimentEvidenceKind.PROVIDER_UNAVAILABLE, 20, 30))).state
        assertEquals(BodyAvailability.UNAVAILABLE, state.body(body)?.availability)
        assertNull(state.activeSelection)
    }

    @Test
    fun activeSelectionExpiresAndGrantsNoAuthority() {
        val body = body("00000000-0000-0000-0000-000000000009")
        var state = reducer.reduce(ActiveBodyState.initial(host), observe(body, remote = true)).state
        state = reducer.reduce(state, select(body, ActiveBodyActivationMode.REMOTE, expiresAt = 20)).state

        assertFalse(state.activeSelection!!.grantsExecutionAuthority)
        state = reducer.reduce(state, ActiveBodyEvent.Expire(generation(1), time(20))).state
        assertNull(state.activeSelection)
    }

    private fun observe(
        body: BodyProviderReference,
        remote: Boolean,
        generation: EmbodimentGeneration = generation(1),
    ): ActiveBodyEvent.Observe = ActiveBodyEvent.Observe(
        generation, body, BodyAvailability.AVAILABLE, nearby = true, connected = true,
        remotelyControlled = remote,
        evidence = listOf(evidence("observed-${body.identity.instanceId.value.takeLast(2)}", EmbodimentEvidenceKind.DISCOVERY_OBSERVED, 0, 100)),
        observedAt = time(10),
    )

    private fun select(
        body: BodyProviderReference,
        mode: ActiveBodyActivationMode,
        expiresAt: Long = 100,
    ): ActiveBodyEvent.Select = ActiveBodyEvent.Select(
        generation(1),
        success(ActiveBodySelection.create(body, mode, success(ActiveBodySelectionReference.parse("selection")), time(10), time(expiresAt))),
    )

    private fun evidence(
        id: String,
        kind: EmbodimentEvidenceKind,
        observedAt: Long,
        expiresAt: Long,
    ): EmbodimentEvidence = success(
        EmbodimentEvidence.create(
            success(EmbodimentEvidenceId.parse(id)), kind,
            EmbodimentEvidenceProvenance.HARDWARE_VERIFIED, confidence(950), time(observedAt), time(expiresAt),
        ),
    )

    private fun body(instance: String): BodyProviderReference = success(
        BodyProviderReference.create(
            ProviderIdentity(
                success(ProviderDefinitionId.parse("redi/body")), success(ProviderInstanceId.parse(instance)),
                ProviderFamily.BODY_ADAPTER, success(RedactedProviderAlias.parse("Test body")),
            ),
        ),
    )

    private fun generation(value: Long): EmbodimentGeneration = success(EmbodimentGeneration.create(value))
    private fun confidence(value: Int): EmbodimentConfidence = success(EmbodimentConfidence.create(value))
    private fun time(value: Long): MonotonicTimestampMs = success(MonotonicTimestampMs.create(value))

    private fun <T> success(result: Any): T = when (result) {
        is EmbodimentResult.Success<*> -> result.value as T
        is ProviderIdentityResult.Success<*> -> result.value as T
        is HugoIdentityResult.Success<*> -> result.value as T
        is CapabilityEnvelopeResult.Success<*> -> result.value as T
        else -> error("Expected success, got $result")
    }
}
