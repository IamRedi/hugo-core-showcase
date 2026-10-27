package io.redi.robot.feature.hihugo.presentation

import io.redi.robot.firstlife.identity.HugoBootstrapRunReference
import io.redi.robot.firstlife.identity.HugoIdentityBootstrapSnapshot
import io.redi.robot.firstlife.identity.HugoIdentityBootstrapStatus
import io.redi.robot.firstlife.identity.HugoIdentityRecoveryState
import io.redi.robot.firstlife.identity.HugoSafeDisplayIdentity
import io.redi.robot.hugo.identity.HugoIdentity
import io.redi.robot.hugo.identity.HugoIdentityId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HiHugoPresentationReducerTest {
    private val identity = HugoIdentity(HugoIdentityId.fromUuid(UUID.fromString("4bccf95e-0e96-43c7-810a-b57ca66c77b3")))
    private val run = HugoBootstrapRunReference.fromUuid(UUID.fromString("af82c4ed-fe4b-44be-afd7-c9dde80d3cec"))
    private val safeIdentity = HugoSafeDisplayIdentity("HUGO-REDACTED")

    @Test
    fun everyFirstLifeStateHasDistinctDeterministicVisuals() {
        val specs = HiHugoFaceState.entries.map(HiHugoVisualCatalog::forState)

        assertEquals(9, specs.size)
        assertEquals(9, specs.map { it.label }.toSet().size)
        HiHugoFaceState.entries.forEach { state ->
            assertEquals(HiHugoVisualCatalog.forState(state), HiHugoVisualCatalog.forState(state))
        }
    }

    @Test
    fun readyBootstrapMapsToIdleWithoutChangingIdentity() {
        val snapshot = readySnapshot(HugoIdentityBootstrapStatus.RESTORED)

        val model = HiHugoBootstrapProjection.project(snapshot)

        assertEquals(HiHugoFaceState.IDLE, model.faceState)
        assertSame(identity, model.identity)
        assertEquals(safeIdentity, model.safeDisplayIdentity)
        assertEquals(run, model.bootstrapRunReference)
    }

    @Test
    fun bootstrapFailuresMapFailClosedAndNeverCreateIdentity() {
        val unavailable = HiHugoBootstrapProjection.project(failedSnapshot(HugoIdentityBootstrapStatus.STORAGE_UNAVAILABLE))
        val corrupt = HiHugoBootstrapProjection.project(failedSnapshot(HugoIdentityBootstrapStatus.CORRUPT_IDENTITY))
        val unsupported = HiHugoBootstrapProjection.project(failedSnapshot(HugoIdentityBootstrapStatus.UNSUPPORTED_SCHEMA))

        assertEquals(HiHugoFaceState.BLOCKED, unavailable.faceState)
        assertEquals(HiHugoFaceState.ERROR, corrupt.faceState)
        assertEquals(HiHugoFaceState.ERROR, unsupported.faceState)
        assertEquals(listOf(null, null, null), listOf(unavailable.identity, corrupt.identity, unsupported.identity))
    }

    @Test
    fun explicitEventsChangePresentationOnlyAndRejectStaleInput() {
        val initial = HiHugoBootstrapProjection.project(readySnapshot(HugoIdentityBootstrapStatus.CREATED))
        val speaking = HiHugoPresentationReducer.reduce(initial, HiHugoPresentationEvent(4, HiHugoFaceState.SPEAKING))
        val stale = HiHugoPresentationReducer.reduce(speaking.model, HiHugoPresentationEvent(3, HiHugoFaceState.ERROR))

        assertEquals(HiHugoReductionStatus.APPLIED, speaking.status)
        assertEquals(HiHugoFaceState.SPEAKING, speaking.model.faceState)
        assertEquals(initial.identity, speaking.model.identity)
        assertEquals(initial.bootstrapRunReference, speaking.model.bootstrapRunReference)
        assertEquals(HiHugoReductionStatus.IGNORED_STALE, stale.status)
        assertEquals(speaking.model, stale.model)
    }

    @Test
    fun faceStatesGrantNoRuntimeOrProviderImplications() {
        val fields = HiHugoPresentationModel::class.java.declaredFields.map { it.name.lowercase() }
        val forbidden = listOf("trusted", "authorized", "connected", "permission", "providerid", "sessionauthority")

        forbidden.forEach { word -> assertFalse(fields.any { it.contains(word) }) }
        assertNotEquals(HiHugoVisualCatalog.forState(HiHugoFaceState.BODY_READY), HiHugoVisualCatalog.forState(HiHugoFaceState.IDLE))
    }

    @Test
    fun animationIsBoundedByLifecycleAndReducedMotion() {
        val idle = HiHugoBootstrapProjection.project(readySnapshot(HugoIdentityBootstrapStatus.RESTORED))
        val blocked = idle.copy(faceState = HiHugoFaceState.BLOCKED)

        assertTrue(HiHugoAnimationPolicy.isActive(idle, lifecycleActive = true, reducedMotion = false))
        assertFalse(HiHugoAnimationPolicy.isActive(idle, lifecycleActive = false, reducedMotion = false))
        assertFalse(HiHugoAnimationPolicy.isActive(idle, lifecycleActive = true, reducedMotion = true))
        assertFalse(HiHugoAnimationPolicy.isActive(blocked, lifecycleActive = true, reducedMotion = false))
    }

    private fun readySnapshot(status: HugoIdentityBootstrapStatus) = HugoIdentityBootstrapSnapshot(
        identity = identity,
        status = status,
        runReference = run,
        safeDisplayIdentity = safeIdentity,
        recoveryState = HugoIdentityRecoveryState.NONE,
    )

    private fun failedSnapshot(status: HugoIdentityBootstrapStatus) = HugoIdentityBootstrapSnapshot(
        identity = null,
        status = status,
        runReference = run,
        safeDisplayIdentity = null,
        recoveryState = if (status == HugoIdentityBootstrapStatus.STORAGE_UNAVAILABLE) {
            HugoIdentityRecoveryState.RETRY_STORAGE
        } else {
            HugoIdentityRecoveryState.EXPLICIT_RESET_REQUIRED
        },
    )
}
