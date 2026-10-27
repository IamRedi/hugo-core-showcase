package io.redi.robot.showcase

import io.redi.robot.showcase.domain.DemoScenario
import io.redi.robot.showcase.domain.ShowcaseSession
import io.redi.robot.showcase.domain.ShowcaseMemoryLab
import io.redi.robot.hugo.identity.HugoIdentityId
import io.redi.robot.memory.domain.MemoryRecordId
import io.redi.robot.memory.domain.MemoryValidationState
import io.redi.robot.memory.domain.MemoryAllowedUse
import io.redi.robot.context.api.ContextPrivacyClassification
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ShowcaseSessionTest {
    @Test fun `proposal has no execution effect and approval is one shot`() = runBlocking {
        val session = ShowcaseSession { 2_000 }
        session.propose(DemoScenario.LIGHT)
        assertEquals(0, session.snapshot().executionCount)
        assertEquals(0f, session.snapshot().brightness)
        session.approve()
        assertEquals("VIRTUAL_ACTION_APPLIED", session.snapshot().result)
        assertEquals(.65f, session.snapshot().brightness)
        session.approve()
        assertEquals(1, session.snapshot().executionCount)
    }
    @Test fun `unchanged safety controller rejects overspeed and stale commands`() = runBlocking {
        val session = ShowcaseSession { 2_000 }
        session.propose(DemoScenario.OVERSPEED); session.approve()
        assertEquals("SPEED_LIMIT_EXCEEDED", session.snapshot().result)
        session.propose(DemoScenario.STALE); session.approve()
        assertEquals("STALE_COMMAND", session.snapshot().result)
        assertEquals(0, session.snapshot().executionCount)
    }
    @Test fun `reconnect invalidates old proposal and new generation can execute only after new approval`() = runBlocking {
        val session = ShowcaseSession { 2_000 }
        session.propose(DemoScenario.LIGHT); session.reconnect(); session.approve()
        assertEquals("GENERATION_CHANGED", session.snapshot().result)
        assertEquals(0, session.snapshot().executionCount)
        session.propose(DemoScenario.LIGHT); session.approve()
        assertEquals(1, session.snapshot().executionCount)
    }
    @Test fun `expired proposal cannot become a newly timestamped physical request`() = runBlocking {
        var time = 2_000L
        val session = ShowcaseSession { time }
        session.propose(DemoScenario.LIGHT); time += 60_001; session.approve()
        assertEquals("PROPOSAL_EXPIRED", session.snapshot().result)
        assertEquals(0, session.snapshot().executionCount)
    }
    @Test fun `stop has no model dependency and AI cannot reset it`() = runBlocking {
        val session = ShowcaseSession { 2_000 }
        session.propose(DemoScenario.LIGHT); session.emergencyStop()
        assertNull(session.snapshot().pending)
        session.resetStop(fromAi = true)
        assertTrue(session.snapshot().emergencyStopLatched)
        session.propose(DemoScenario.LIGHT); session.approve()
        assertEquals("EMERGENCY_STOP_LATCHED", session.snapshot().result)
        assertEquals(0, session.snapshot().executionCount)
        session.resetStop()
        assertFalse(session.snapshot().emergencyStopLatched)
    }
    @Test fun `memory self-validation cannot replace a candidate and a correction keeps governance`() {
        var counter = 1
        val lab = ShowcaseMemoryLab(HugoIdentityId.fromUuid(UUID.fromString("ffffffff-0000-4000-8000-000000000001")),
            { MemoryRecordId.fromUuid(UUID.fromString("00000000-0000-4000-8000-${(counter++).toString().padStart(12,'0')}")) }, { 1_000 })
        lab.createCandidate()
        val candidate = lab.envelope!!
        assertEquals(MemoryValidationState.CANDIDATE, candidate.validationState)
        lab.attemptModelSelfValidation()
        assertEquals("SELF_VALIDATION_FORBIDDEN", lab.result)
        assertSame(candidate, lab.envelope)
        lab.userCorrectionDraft()
        val corrected = lab.envelope!!
        assertEquals(candidate.recordId, corrected.supersedes)
        assertEquals(candidate.ownerIdentity, corrected.ownerIdentity)
        assertEquals(MemoryValidationState.VALIDATED, corrected.validationState)
        assertEquals(ContextPrivacyClassification.PRIVATE, corrected.privacy)
        assertEquals(MemoryAllowedUse.LOCAL_ONLY, corrected.allowedUse)
        assertFalse(corrected.grantsAuthority)
    }
}
