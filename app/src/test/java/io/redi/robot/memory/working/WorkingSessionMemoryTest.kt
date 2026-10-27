package io.redi.robot.memory.working

import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.hugo.identity.HugoIdentityId
import io.redi.robot.memory.domain.MemoryAllowedUse
import io.redi.robot.memory.domain.MemoryCreator
import io.redi.robot.memory.domain.MemoryDisposition
import io.redi.robot.memory.domain.MemoryDomainResult
import io.redi.robot.memory.domain.MemoryEnvelope
import io.redi.robot.memory.domain.MemoryEnvelopeCodec
import io.redi.robot.memory.domain.MemoryLayer
import io.redi.robot.memory.domain.MemoryProvenance
import io.redi.robot.memory.domain.MemoryProvenanceOrigin
import io.redi.robot.memory.domain.MemoryRecordId
import io.redi.robot.memory.domain.MemoryRetention
import io.redi.robot.memory.domain.MemoryRetentionClass
import io.redi.robot.memory.domain.MemoryScope
import io.redi.robot.memory.domain.MemorySubjectToken
import io.redi.robot.memory.domain.MemoryTypeToken
import io.redi.robot.memory.domain.MemoryValidationState
import io.redi.robot.context.api.ContextText
import io.redi.robot.context.api.ContextValue
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkingSessionMemoryTest {
    @Test
    fun `policy bounds are validated`() {
        assertTrue(WorkingMemoryPolicy.create(0) is WorkingPolicyResult.Failed)
        assertTrue(WorkingMemoryPolicy.create(65) is WorkingPolicyResult.Failed)
        assertTrue(WorkingMemoryPolicy.create(1) is WorkingPolicyResult.Success)
        assertTrue(WorkingMemoryPolicy.create(64) is WorkingPolicyResult.Success)
    }

    @Test
    fun `remember and recall round trip within bounded capacity`() {
        val memory = openSession(capacity = 4)
        assertTrue(memory.remember(workingEnvelope(1)) is WorkingRememberResult.Remembered)
        assertTrue(memory.remember(workingEnvelope(2)) is WorkingRememberResult.Remembered)

        assertEquals(2, memory.size())
        assertEquals(
            listOf(recordId(1), recordId(2)),
            memory.recallAll().map { it.recordId },
        )
        assertEquals("session content 1", memory.recall(recordId(1))?.content?.canonicalValue)
        assertEquals(owner(), memory.owner())
    }

    @Test
    fun `only working layer active session scoped envelopes are accepted`() {
        val memory = openSession(capacity = 4)

        val episodic = MemoryEnvelope.create(
            recordId = recordId(1),
            ownerIdentity = owner(),
            layer = MemoryLayer.EPISODIC,
            type = token("event"),
            scope = MemoryScope.GlobalPersonal,
            subjects = listOf(subject("session")),
            content = textValue("episodic"),
            provenance = provenance(MemoryProvenanceOrigin.USER_EXPLICIT, modelGenerated = false),
            confidence = confidence(700),
            validationState = MemoryValidationState.CANDIDATE,
            disposition = MemoryDisposition.ACTIVE,
            privacy = ContextPrivacyClassification.PRIVATE,
            allowedUse = MemoryAllowedUse.LOCAL_ONLY,
            retention = retention(MemoryRetentionClass.UNTIL_CORRECTED, null),
            supersedes = null,
            conflictsWith = emptyList(),
            lineage = null,
            createdAtEpochSecond = 1_000L,
            updatedAtEpochSecond = null,
        ).success()
        assertEquals(
            WorkingMemoryError.WRONG_LAYER,
            (memory.remember(episodic) as WorkingRememberResult.Failed).error,
        )

        val superseded = workingEnvelope(1, disposition = MemoryDisposition.SUPERSEDED)
        assertEquals(
            WorkingMemoryError.NOT_ACTIVE,
            (memory.remember(superseded) as WorkingRememberResult.Failed).error,
        )

        val durableRetention = workingEnvelope(1).let { envelope ->
            // Same working record shape but with durable retention is policy-rejected.
            MemoryEnvelope.create(
                recordId = envelope.recordId,
                ownerIdentity = envelope.ownerIdentity,
                layer = MemoryLayer.WORKING,
                type = envelope.type,
                scope = envelope.scope,
                subjects = envelope.subjects,
                content = envelope.content!!,
                provenance = envelope.provenance,
                confidence = envelope.confidence!!,
                validationState = envelope.validationState,
                disposition = envelope.disposition,
                privacy = envelope.privacy,
                allowedUse = envelope.allowedUse,
                retention = retention(MemoryRetentionClass.UNTIL_CORRECTED, null),
                supersedes = null,
                conflictsWith = emptyList(),
                lineage = null,
                createdAtEpochSecond = envelope.createdAtEpochSecond,
                updatedAtEpochSecond = null,
            ).success()
        }
        assertEquals(
            WorkingMemoryError.WRONG_RETENTION_CLASS,
            (memory.remember(durableRetention) as WorkingRememberResult.Failed).error,
        )
    }

    @Test
    fun `foreign owner envelopes never enter the session`() {
        val memory = openSession(capacity = 4)
        val foreign = MemoryEnvelope.create(
            recordId = recordId(9),
            ownerIdentity = otherOwner(),
            layer = MemoryLayer.WORKING,
            type = token("fact"),
            scope = MemoryScope.GlobalPersonal,
            subjects = listOf(subject("session")),
            content = textValue("foreign"),
            provenance = provenance(MemoryProvenanceOrigin.USER_EXPLICIT, modelGenerated = false),
            confidence = confidence(500),
            validationState = MemoryValidationState.CANDIDATE,
            disposition = MemoryDisposition.ACTIVE,
            privacy = ContextPrivacyClassification.PRIVATE,
            allowedUse = MemoryAllowedUse.LOCAL_ONLY,
            retention = retention(MemoryRetentionClass.SESSION_SCOPED, null),
            supersedes = null,
            conflictsWith = emptyList(),
            lineage = null,
            createdAtEpochSecond = 1_000L,
            updatedAtEpochSecond = null,
        ).success()

        assertEquals(
            WorkingMemoryError.OWNER_MISMATCH,
            (memory.remember(foreign) as WorkingRememberResult.Failed).error,
        )
        assertEquals(0, memory.size())
        assertNull(memory.recall(recordId(9)))
    }

    @Test
    fun `capacity overflow deterministically evicts the oldest entry`() {
        val memory = openSession(capacity = 2)
        memory.remember(workingEnvelope(1))
        memory.remember(workingEnvelope(2))

        val third = memory.remember(workingEnvelope(3)) as WorkingRememberResult.Remembered
        assertEquals(recordId(1), third.evictedRecordId)
        assertEquals(2, memory.size())
        assertNull(memory.recall(recordId(1)))
        assertTrue(memory.recall(recordId(2)) != null)
        assertTrue(memory.recall(recordId(3)) != null)
    }

    @Test
    fun `forget and endSession provide deterministic session cleanup`() {
        val memory = openSession(capacity = 4)
        memory.remember(workingEnvelope(1))
        memory.remember(workingEnvelope(2))

        assertTrue(memory.forget(recordId(1)))
        assertFalse(memory.forget(recordId(1)))
        assertEquals(1, memory.size())

        memory.remember(workingEnvelope(3))
        assertEquals(2, memory.endSession())
        assertEquals(0, memory.size())
        assertNull(memory.recall(recordId(2)))
        assertNull(memory.recall(recordId(3)))
    }

    @Test
    fun `promotion is the explicit durability decision producing an episodic envelope`() {
        val memory = openSession(capacity = 4)
        memory.remember(workingEnvelope(1))

        val promoted = memory.promote(
            recordId(1),
            retention(MemoryRetentionClass.UNTIL_CORRECTED, null),
            updatedAtEpochSecond = 2_000L,
        ) as WorkingPromotionResult.Promoted

        val envelope = promoted.envelope
        assertEquals(MemoryLayer.EPISODIC, envelope.layer)
        assertEquals(recordId(1), envelope.recordId)
        assertEquals(MemoryValidationState.CANDIDATE, envelope.validationState)
        assertEquals(MemoryProvenanceOrigin.USER_EXPLICIT, envelope.provenance.origin)
        assertEquals(MemoryScope.GlobalPersonal, envelope.scope)
        assertEquals(ContextPrivacyClassification.PRIVATE, envelope.privacy)
        assertEquals(MemoryAllowedUse.LOCAL_ONLY, envelope.allowedUse)
        assertEquals(1_000L, envelope.createdAtEpochSecond)
        assertEquals(2_000L, envelope.updatedAtEpochSecond)

        // The entry leaves the session once promoted: it is no longer session-temporary.
        assertNull(memory.recall(recordId(1)))
        assertEquals(0, memory.size())
        assertTrue(memory.trace.last().startsWith("PROMOTED:"))
    }

    @Test
    fun `model generated working entries stay candidates through promotion`() {
        val memory = openSession(capacity = 4)
        val modelCandidate = MemoryEnvelope.create(
            recordId = recordId(5),
            ownerIdentity = owner(),
            layer = MemoryLayer.WORKING,
            type = token("observation"),
            scope = MemoryScope.GlobalPersonal,
            subjects = listOf(subject("session")),
            content = textValue("model guess"),
            provenance = provenance(MemoryProvenanceOrigin.MODEL_INFERRED, modelGenerated = true),
            confidence = confidence(300),
            validationState = MemoryValidationState.CANDIDATE,
            disposition = MemoryDisposition.ACTIVE,
            privacy = ContextPrivacyClassification.PRIVATE,
            allowedUse = MemoryAllowedUse.LOCAL_ONLY,
            retention = retention(MemoryRetentionClass.SESSION_SCOPED, null),
            supersedes = null,
            conflictsWith = emptyList(),
            lineage = null,
            createdAtEpochSecond = 1_000L,
            updatedAtEpochSecond = null,
        ).success()
        memory.remember(modelCandidate)

        val promoted = memory.promote(
            recordId(5),
            retention(MemoryRetentionClass.POLICY_CONTROLLED, null),
            updatedAtEpochSecond = 2_000L,
        ) as WorkingPromotionResult.Promoted

        assertEquals(MemoryValidationState.CANDIDATE, promoted.envelope.validationState)
        assertTrue(promoted.envelope.provenance.modelGenerated)
        assertFalse(promoted.envelope.isFact)
    }

    @Test
    fun `promotion rejects session scoped retention unknown ids and envelope violations`() {
        val memory = openSession(capacity = 4)
        memory.remember(workingEnvelope(1))

        val sessionRetention = memory.promote(
            recordId(1),
            retention(MemoryRetentionClass.SESSION_SCOPED, null),
            updatedAtEpochSecond = 2_000L,
        ) as WorkingPromotionResult.Failed
        assertEquals(
            WorkingMemoryError.INVALID_PROMOTION_RETENTION,
            (sessionRetention.reason as WorkingPromotionReason.Policy).error,
        )
        // The rejected promotion must leave the working entry untouched in the session.
        assertTrue(memory.recall(recordId(1)) != null)

        val unknown = memory.promote(
            recordId(99),
            retention(MemoryRetentionClass.UNTIL_CORRECTED, null),
            updatedAtEpochSecond = 2_000L,
        ) as WorkingPromotionResult.Failed
        assertEquals(
            WorkingMemoryError.RECORD_NOT_FOUND,
            (unknown.reason as WorkingPromotionReason.Policy).error,
        )
    }

    @Test
    fun `trace is bounded and content free`() {
        val memory = openSession(capacity = 2)
        memory.remember(workingEnvelope(1))
        memory.remember(workingEnvelope(2))
        memory.remember(workingEnvelope(3))
        memory.endSession()

        val trace = memory.trace.joinToString(separator = "\n")
        assertTrue(trace.contains("EVICTED:"))
        assertTrue(trace.contains("SESSION_ENDED:cleared=2"))
        assertFalse(trace.contains("session content"))
        assertFalse(trace.contains(owner().value))
        assertTrue(memory.trace.size <= WorkingMemoryPolicy.MAXIMUM_TRACE_LINES)
    }

    // ------------------------------------------------------------------ helpers

    private fun openSession(capacity: Int): WorkingSessionMemory {
        val policy = (WorkingMemoryPolicy.create(capacity) as WorkingPolicyResult.Success).policy
        return WorkingSessionMemory.open(policy, owner())
    }

    private fun workingEnvelope(
        index: Int,
        disposition: MemoryDisposition = MemoryDisposition.ACTIVE,
    ): MemoryEnvelope = MemoryEnvelope.create(
        recordId = recordId(index),
        ownerIdentity = owner(),
        layer = MemoryLayer.WORKING,
        type = token("scratch"),
        scope = MemoryScope.GlobalPersonal,
        subjects = listOf(subject("session")),
        content = textValue("session content $index"),
        provenance = provenance(MemoryProvenanceOrigin.USER_EXPLICIT, modelGenerated = false),
        confidence = confidence(600),
        validationState = MemoryValidationState.CANDIDATE,
        disposition = disposition,
        privacy = ContextPrivacyClassification.PRIVATE,
        allowedUse = MemoryAllowedUse.LOCAL_ONLY,
        retention = retention(MemoryRetentionClass.SESSION_SCOPED, null),
        supersedes = null,
        conflictsWith = emptyList(),
        lineage = null,
        createdAtEpochSecond = 1_000L,
        updatedAtEpochSecond = null,
    ).success()

    private fun <T> MemoryDomainResult<T>.success(): T = when (this) {
        is MemoryDomainResult.Success -> value
        is MemoryDomainResult.Failure -> error("expected success, got $error")
    }

    private fun recordId(index: Int): MemoryRecordId = MemoryRecordId.fromUuid(
        UUID.fromString("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"),
    )

    private fun owner(): HugoIdentityId = HugoIdentityId.fromUuid(
        UUID.fromString("ffffffff-0000-4000-8000-000000000001"),
    )

    private fun otherOwner(): HugoIdentityId = HugoIdentityId.fromUuid(
        UUID.fromString("ffffffff-0000-4000-8000-000000000002"),
    )

    private fun token(raw: String): MemoryTypeToken =
        (MemoryTypeToken.parse(raw) as MemoryDomainResult.Success).value
    private fun subject(raw: String): MemorySubjectToken =
        (MemorySubjectToken.parse(raw) as MemoryDomainResult.Success).value
    private fun creator(raw: String): MemoryCreator =
        (MemoryCreator.parse(raw) as MemoryDomainResult.Success).value

    private fun provenance(origin: MemoryProvenanceOrigin, modelGenerated: Boolean): MemoryProvenance =
        (MemoryProvenance.create(
            origin,
            modelGenerated,
            (io.redi.robot.context.api.ContextSourceType.parse("memory.test")
                as io.redi.robot.context.api.ContextObservationResult.Success).value,
            (io.redi.robot.context.api.ContextSourceReference.parse("memory_test")
                as io.redi.robot.context.api.ContextObservationResult.Success).value,
            creator("hugo_runtime"),
            null,
        ) as MemoryDomainResult.Success).value

    private fun confidence(permille: Int): io.redi.robot.context.api.ContextConfidence =
        (io.redi.robot.context.api.ContextConfidence.create(permille)
            as io.redi.robot.context.api.ContextObservationResult.Success).value

    private fun textValue(raw: String): ContextValue = ContextValue.TextValue(
        (ContextText.create(raw) as io.redi.robot.context.api.ContextObservationResult.Success).value,
    )

    private fun retention(retentionClass: MemoryRetentionClass, expiry: Long?): MemoryRetention =
        (MemoryRetention.create(retentionClass, expiry) as MemoryDomainResult.Success).value
}
