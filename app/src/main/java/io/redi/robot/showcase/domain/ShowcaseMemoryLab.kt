package io.redi.robot.showcase.domain

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
import io.redi.robot.memory.domain.MemoryDomainResult
import io.redi.robot.memory.domain.MemoryEnvelope
import io.redi.robot.memory.domain.MemoryProvenance
import io.redi.robot.memory.domain.MemoryProvenanceOrigin
import io.redi.robot.memory.domain.MemoryRecordId
import io.redi.robot.memory.domain.MemoryRetention
import io.redi.robot.memory.domain.MemoryRetentionClass
import io.redi.robot.memory.domain.MemorySubjectToken
import io.redi.robot.memory.domain.MemoryTypeToken
import io.redi.robot.memory.domain.MemoryValidationState
import io.redi.robot.memory.semantic.SemanticCreationResult
import io.redi.robot.memory.semantic.SemanticFactFactory

/** Existing H061 factories, synthetic content, ephemeral envelopes only. No canonical store write. */
class ShowcaseMemoryLab(owner: HugoIdentityId, recordIds: () -> MemoryRecordId, private val epochSecond: () -> Long) {
    private val factory = SemanticFactFactory.open(owner, recordIds)
    var envelope: MemoryEnvelope? = null
        private set
    var result = "NO_RECORD"
        private set

    fun createCandidate() = accept(create(MemoryValidationState.CANDIDATE))
    fun attemptModelSelfValidation() {
        val attempt = create(MemoryValidationState.VALIDATED)
        result = when (attempt) {
            is SemanticCreationResult.Failed -> attempt.error.name
            is SemanticCreationResult.Created -> "UNEXPECTED_VALIDATION"
        }
    }
    fun userCorrectionDraft() {
        val target = envelope ?: return
        accept(factory.createCorrection(target, text("The synthetic user explicitly prefers amber lighting."), observation(ContextConfidence.create(1_000)), epochSecond()))
    }
    private fun accept(created: SemanticCreationResult) {
        when (created) {
            is SemanticCreationResult.Created -> { envelope = created.envelope; result = created.envelope.validationState.name }
            is SemanticCreationResult.Failed -> result = created.error.name
        }
    }
    private fun create(validation: MemoryValidationState): SemanticCreationResult = factory.create(
        domain(MemoryTypeToken.parse("preference")), listOf(domain(MemorySubjectToken.parse("demo_user"))),
        text("The synthetic user may prefer blue lighting."),
        domain(MemoryProvenance.create(MemoryProvenanceOrigin.MODEL_INFERRED, true,
            observation(ContextSourceType.parse("showcase.memory")), observation(ContextSourceReference.parse("showcase_demo")),
            domain(MemoryCreator.parse("showcase_runtime")), null)),
        observation(ContextConfidence.create(500)), validation, ContextPrivacyClassification.PRIVATE, MemoryAllowedUse.LOCAL_ONLY,
        domain(MemoryRetention.create(MemoryRetentionClass.UNTIL_CORRECTED, null)), null, emptyList(), epochSecond(),
    )
    private fun text(value: String) = ContextValue.TextValue(observation(ContextText.create(value)))
    private fun <T> domain(value: MemoryDomainResult<T>): T = (value as MemoryDomainResult.Success).value
    private fun <T> observation(value: ContextObservationResult<T>): T = (value as ContextObservationResult.Success).value
}
