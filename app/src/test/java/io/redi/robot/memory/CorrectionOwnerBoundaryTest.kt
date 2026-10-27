package io.redi.robot.memory

import io.redi.robot.context.api.ContextConfidence
import io.redi.robot.context.api.ContextObservationResult
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextSourceReference
import io.redi.robot.context.api.ContextSourceType
import io.redi.robot.context.api.ContextText
import io.redi.robot.context.api.ContextValue
import io.redi.robot.hugo.identity.HugoIdentityId
import io.redi.robot.memory.domain.MemoryAllowedUse
import io.redi.robot.memory.domain.MemoryCreator
import io.redi.robot.memory.domain.MemoryDisposition
import io.redi.robot.memory.domain.MemoryDomainResult
import io.redi.robot.memory.domain.MemoryEnvelope
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
import io.redi.robot.memory.personal.PersonalCreationResult
import io.redi.robot.memory.personal.PersonalMemoryError
import io.redi.robot.memory.personal.PersonalRecordFactory
import io.redi.robot.memory.semantic.SemanticCreationResult
import io.redi.robot.memory.semantic.SemanticFactFactory
import io.redi.robot.memory.semantic.SemanticMemoryError
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class CorrectionOwnerBoundaryTest {
    @Test
    fun `personal factory opened for owner A rejects owner B correction target`() {
        val factory = PersonalRecordFactory.open(ownerA()) { recordId(10) }
        val foreign = target(MemoryLayer.PERSONAL, ownerB())

        val result = factory.createCorrection(
            foreign,
            text("new personal value"),
            confidence(1000),
            3_000L,
        )

        assertEquals(
            PersonalMemoryError.OWNER_MISMATCH,
            (result as PersonalCreationResult.Failed).error,
        )
    }

    @Test
    fun `semantic factory opened for owner A rejects owner B correction target`() {
        val factory = SemanticFactFactory.open(ownerA()) { recordId(11) }
        val foreign = target(MemoryLayer.SEMANTIC, ownerB())

        val result = factory.createCorrection(
            foreign,
            text("new semantic value"),
            confidence(1000),
            3_000L,
        )

        assertEquals(
            SemanticMemoryError.OWNER_MISMATCH,
            (result as SemanticCreationResult.Failed).error,
        )
    }
}

private fun target(layer: MemoryLayer, owner: HugoIdentityId): MemoryEnvelope =
    MemoryEnvelope.create(
        recordId = recordId(1),
        ownerIdentity = owner,
        layer = layer,
        type = type("preference"),
        scope = MemoryScope.GlobalPersonal,
        subjects = listOf(subject("answers")),
        content = text("original"),
        provenance = provenance(),
        confidence = confidence(900),
        validationState = MemoryValidationState.VALIDATED,
        disposition = MemoryDisposition.ACTIVE,
        privacy = ContextPrivacyClassification.PRIVATE,
        allowedUse = MemoryAllowedUse.GATEWAY_ALLOWED,
        retention = retention(),
        supersedes = null,
        conflictsWith = emptyList(),
        lineage = null,
        createdAtEpochSecond = 1_000L,
        updatedAtEpochSecond = null,
    ).success()

private fun provenance(): MemoryProvenance =
    (MemoryProvenance.create(
        MemoryProvenanceOrigin.USER_EXPLICIT,
        modelGenerated = false,
        (ContextSourceType.parse("memory.test") as ContextObservationResult.Success).value,
        (ContextSourceReference.parse("memory_test") as ContextObservationResult.Success).value,
        (MemoryCreator.parse("user") as MemoryDomainResult.Success).value,
        null,
    ) as MemoryDomainResult.Success).value

private fun ownerA(): HugoIdentityId =
    HugoIdentityId.fromUuid(UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001"))

private fun ownerB(): HugoIdentityId =
    HugoIdentityId.fromUuid(UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002"))

private fun recordId(index: Int): MemoryRecordId =
    MemoryRecordId.fromUuid(
        UUID.fromString("20000000-0000-4000-8000-${index.toString().padStart(12, '0')}"),
    )

private fun type(raw: String): MemoryTypeToken =
    (MemoryTypeToken.parse(raw) as MemoryDomainResult.Success).value

private fun subject(raw: String): MemorySubjectToken =
    (MemorySubjectToken.parse(raw) as MemoryDomainResult.Success).value

private fun text(raw: String): ContextValue =
    ContextValue.TextValue((ContextText.create(raw) as ContextObservationResult.Success).value)

private fun confidence(value: Int): ContextConfidence =
    (ContextConfidence.create(value) as ContextObservationResult.Success).value

private fun retention(): MemoryRetention =
    (MemoryRetention.create(MemoryRetentionClass.UNTIL_CORRECTED, null)
        as MemoryDomainResult.Success).value

private fun <T> MemoryDomainResult<T>.success(): T = when (this) {
    is MemoryDomainResult.Success -> value
    is MemoryDomainResult.Failure -> error("expected success: $error")
}
