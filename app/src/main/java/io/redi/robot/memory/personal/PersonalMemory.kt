package io.redi.robot.memory.personal

import io.redi.robot.hugo.identity.HugoIdentityId
import io.redi.robot.memory.domain.MemoryAllowedUse
import io.redi.robot.memory.domain.MemoryCreator
import io.redi.robot.memory.domain.MemoryDisposition
import io.redi.robot.memory.domain.MemoryDomainError
import io.redi.robot.memory.domain.MemoryDomainResult
import io.redi.robot.memory.domain.MemoryEnvelope
import io.redi.robot.memory.domain.MemoryLayer
import io.redi.robot.memory.domain.MemoryLineage
import io.redi.robot.memory.domain.MemoryProvenance
import io.redi.robot.memory.domain.MemoryProvenanceOrigin
import io.redi.robot.memory.domain.MemoryRecordId
import io.redi.robot.memory.domain.MemoryRetention
import io.redi.robot.memory.domain.MemoryRetentionClass
import io.redi.robot.memory.domain.MemoryScope
import io.redi.robot.memory.domain.MemorySubjectToken
import io.redi.robot.memory.domain.MemoryTypeToken
import io.redi.robot.memory.domain.MemoryValidationState
import io.redi.robot.context.api.ContextConfidence
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextValue
import io.redi.robot.memory.storage.MemoryChangeSetError
import io.redi.robot.memory.storage.MemoryChangeSetRecoveryResult
import io.redi.robot.memory.storage.MemoryEnvelopeStore

/**
 * H061 personal memory: user-governed preferences, routines, goals, and corrections.
 *
 * Two authorities are enforced here:
 *
 * 1. **Corrections are user acts.** A correction record (one whose `supersedes` link targets
 *    another record) must carry user-explicit, non-model provenance; a model may never correct.
 *    Because supersession happens only through the user-correction change-set, an explicit user
 *    correction always outranks inferred or model-generated memory, and an inference can never
 *    supersede anything.
 * 2. **Atomic supersession.** A correction and its SUPERSEDED target commit as one all-or-nothing,
 *    crash-safe, recoverable, idempotent H059 change-set; stale writes are rejected.
 */
class PersonalRecordFactory private constructor(
    private val ownerIdentity: HugoIdentityId,
    private val recordIds: () -> MemoryRecordId,
) {
    /**
     * Creates one personal envelope (preference, routine, goal). Requesting VALIDATED requires
     * user-explicit, non-model provenance; every other provenance starts as a CANDIDATE.
     */
    @Suppress("LongParameterList")
    fun create(
        personalType: MemoryTypeToken,
        subjects: List<MemorySubjectToken>,
        content: ContextValue,
        provenance: MemoryProvenance,
        confidence: ContextConfidence,
        requestedValidationState: MemoryValidationState,
        privacy: ContextPrivacyClassification,
        allowedUse: MemoryAllowedUse,
        retention: MemoryRetention,
        lineage: MemoryLineage?,
        conflictsWith: List<MemoryRecordId>,
        createdAtEpochSecond: Long,
    ): PersonalCreationResult {
        if (requestedValidationState == MemoryValidationState.DISPUTED) {
            return PersonalCreationResult.Failed(PersonalMemoryError.DISPUTED_NOT_CREATABLE)
        }
        if (requestedValidationState == MemoryValidationState.VALIDATED &&
            (provenance.origin != MemoryProvenanceOrigin.USER_EXPLICIT || provenance.modelGenerated)
        ) {
            return PersonalCreationResult.Failed(PersonalMemoryError.SELF_VALIDATION_FORBIDDEN)
        }
        if (retention.retentionClass == MemoryRetentionClass.SESSION_SCOPED) {
            return PersonalCreationResult.Failed(PersonalMemoryError.SESSION_RETENTION_FORBIDDEN)
        }
        return when (
            val created = MemoryEnvelope.create(
                recordId = recordIds(),
                ownerIdentity = ownerIdentity,
                layer = MemoryLayer.PERSONAL,
                type = personalType,
                scope = MemoryScope.GlobalPersonal,
                subjects = subjects,
                content = content,
                provenance = provenance,
                confidence = confidence,
                validationState = requestedValidationState,
                disposition = MemoryDisposition.ACTIVE,
                privacy = privacy,
                allowedUse = allowedUse,
                retention = retention,
                supersedes = null,
                conflictsWith = conflictsWith,
                lineage = lineage,
                createdAtEpochSecond = createdAtEpochSecond,
                updatedAtEpochSecond = null,
            )
        ) {
            is MemoryDomainResult.Success -> PersonalCreationResult.Created(created.value)
            is MemoryDomainResult.Failure -> PersonalCreationResult.Failed(
                PersonalMemoryError.ENVELOPE_REJECTED,
                created.error,
            )
        }
    }

    /**
     * Creates the correction successor for [target]: a PERSONAL-layer record bound to the
     * target's owner that preserves the target's layer, type, scope, subjects, privacy, allowed
     * use, and retention — a correction replaces content, never governance, and privacy or
     * allowed use cannot be relaxed through a correction. Provenance is forced to user-explicit
     * and non-model (a model may never correct), the record carries the `supersedes` link plus
     * lineage, and it is VALIDATED (an explicit user statement is self-validating). The
     * successor is not persisted here; the correction service commits it atomically together
     * with the SUPERSEDED target.
     */
    fun createCorrection(
        target: MemoryEnvelope,
        content: ContextValue,
        confidence: ContextConfidence,
        createdAtEpochSecond: Long,
    ): PersonalCreationResult {
        if (target.layer != MemoryLayer.PERSONAL || target.scope != MemoryScope.GlobalPersonal) {
            return PersonalCreationResult.Failed(PersonalMemoryError.CORRECTION_TARGET_MISMATCH)
        }
        if (target.ownerIdentity != ownerIdentity) {
            return PersonalCreationResult.Failed(PersonalMemoryError.OWNER_MISMATCH)
        }
        if (target.disposition != MemoryDisposition.ACTIVE) {
            return PersonalCreationResult.Failed(PersonalMemoryError.TARGET_NOT_ACTIVE)
        }
        val retention = target.retention
            ?: return PersonalCreationResult.Failed(PersonalMemoryError.ENVELOPE_REJECTED)
        val provenance = when (
            val created = MemoryProvenance.create(
                MemoryProvenanceOrigin.USER_EXPLICIT,
                modelGenerated = false,
                (io.redi.robot.context.api.ContextSourceType.parse("memory.correction")
                    as io.redi.robot.context.api.ContextObservationResult.Success).value,
                (io.redi.robot.context.api.ContextSourceReference.parse("memory_correction")
                    as io.redi.robot.context.api.ContextObservationResult.Success).value,
                (MemoryCreator.parse("user_correction") as MemoryDomainResult.Success).value,
                null,
            )
        ) {
            is MemoryDomainResult.Success -> created.value
            is MemoryDomainResult.Failure ->
                return PersonalCreationResult.Failed(PersonalMemoryError.ENVELOPE_REJECTED)
        }
        return when (
            val created = MemoryEnvelope.create(
                recordId = recordIds(),
                ownerIdentity = target.ownerIdentity,
                layer = MemoryLayer.PERSONAL,
                type = target.type,
                scope = target.scope,
                subjects = target.subjects,
                content = content,
                provenance = provenance,
                confidence = confidence,
                validationState = MemoryValidationState.VALIDATED,
                disposition = MemoryDisposition.ACTIVE,
                privacy = target.privacy,
                allowedUse = target.allowedUse,
                retention = retention,
                supersedes = target.recordId,
                conflictsWith = emptyList(),
                lineage = lineageOf(listOf(target.recordId)),
                createdAtEpochSecond = createdAtEpochSecond,
                updatedAtEpochSecond = null,
            )
        ) {
            is MemoryDomainResult.Success -> PersonalCreationResult.Created(created.value)
            is MemoryDomainResult.Failure -> PersonalCreationResult.Failed(
                PersonalMemoryError.ENVELOPE_REJECTED,
                created.error,
            )
        }
    }

    private fun lineageOf(ids: List<MemoryRecordId>): MemoryLineage? = when (
        val created = MemoryLineage.create(ids)
    ) {
        is MemoryDomainResult.Success -> created.value
        is MemoryDomainResult.Failure -> null
    }

    companion object {
        fun open(ownerIdentity: HugoIdentityId, recordIds: () -> MemoryRecordId): PersonalRecordFactory =
            PersonalRecordFactory(ownerIdentity, recordIds)
    }
}

/**
 * Commits user corrections atomically through the H059 change-set: the correction successor and
 * the SUPERSEDED target are applied together, with stale-write protection and crash-safe
 * recovery. Only user corrections may supersede — an inference never outranks a governed record.
 */
class PersonalCorrectionService(private val store: MemoryEnvelopeStore) {
    fun correct(
        expectedTarget: MemoryEnvelope,
        correction: MemoryEnvelope,
        updatedAtEpochSecond: Long,
    ): PersonalCorrectionResult {
        if (correction.layer != MemoryLayer.PERSONAL) {
            return PersonalCorrectionResult.Failed(PersonalMemoryError.WRONG_LAYER)
        }
        if (correction.provenance.origin != MemoryProvenanceOrigin.USER_EXPLICIT ||
            correction.provenance.modelGenerated
        ) {
            return PersonalCorrectionResult.Failed(PersonalMemoryError.CORRECTION_PROVENANCE_FORBIDDEN)
        }
        return when (
            val applied = store.persistSupersessionChangeSet(
                correction,
                expectedTarget,
                updatedAtEpochSecond,
            )
        ) {
            is io.redi.robot.memory.storage.MemoryChangeSetResult.Applied ->
                PersonalCorrectionResult.Applied(correction)
            is io.redi.robot.memory.storage.MemoryChangeSetResult.AlreadyApplied ->
                PersonalCorrectionResult.AlreadyApplied(correction)
            is io.redi.robot.memory.storage.MemoryChangeSetResult.Failed ->
                PersonalCorrectionResult.Failed(applied.error.toPersonalError())
        }
    }

    fun recover(): PersonalRecoveryResult = when (val recovered = store.recoverChangeSet()) {
        is MemoryChangeSetRecoveryResult.Recovered ->
            PersonalRecoveryResult.Recovered(recovered.appliedCount)
        is MemoryChangeSetRecoveryResult.NothingToRecover -> PersonalRecoveryResult.NothingToRecover
        is MemoryChangeSetRecoveryResult.Failed ->
            PersonalRecoveryResult.Failed(recovered.error.toPersonalError())
    }

    private fun MemoryChangeSetError.toPersonalError(): PersonalMemoryError {
        return when (this) {
        MemoryChangeSetError.STALE_WRITE -> PersonalMemoryError.STALE_WRITE
        MemoryChangeSetError.TARGET_MISSING -> PersonalMemoryError.TARGET_MISSING
        MemoryChangeSetError.INVALID_TARGET_STATE -> PersonalMemoryError.TARGET_NOT_ACTIVE
        MemoryChangeSetError.DUPLICATE_SUCCESSOR -> PersonalMemoryError.DUPLICATE_CORRECTION
        MemoryChangeSetError.OPERATION_ID_REUSED -> PersonalMemoryError.IDEMPOTENCY_CONFLICT
        MemoryChangeSetError.CORRECTION_AUTHORITY_INVALID ->
            PersonalMemoryError.CORRECTION_PROVENANCE_FORBIDDEN
        MemoryChangeSetError.LINK_MISMATCH -> PersonalMemoryError.LINK_MISMATCH
        MemoryChangeSetError.CROSS_LAYER_SUPERSESSION -> PersonalMemoryError.WRONG_LAYER
        MemoryChangeSetError.CORRECTION_PAIR_MISMATCH -> PersonalMemoryError.CORRECTION_PAIR_MISMATCH
        MemoryChangeSetError.INVALID_UPDATED_AT -> PersonalMemoryError.INVALID_UPDATED_AT
        MemoryChangeSetError.CHANGE_SET_SUPPORT_UNAVAILABLE,
        MemoryChangeSetError.JOURNAL_WRITE_FAILED,
        MemoryChangeSetError.JOURNAL_READ_FAILED,
        MemoryChangeSetError.JOURNAL_MALFORMED,
        MemoryChangeSetError.CORRUPT_RECORD,
        MemoryChangeSetError.ENCRYPTION_FAILED,
        MemoryChangeSetError.DECRYPTION_FAILED,
        MemoryChangeSetError.AUTHENTICATION_FAILED,
        MemoryChangeSetError.KEY_UNAVAILABLE,
        MemoryChangeSetError.KEY_LOST,
        MemoryChangeSetError.STORAGE_IO_FAILED,
        MemoryChangeSetError.OVERSIZED_RECORD,
        MemoryChangeSetError.INVALID_LIMIT,
        MemoryChangeSetError.JOURNAL_CLEAR_FAILED,
        MemoryChangeSetError.PENDING_JOURNAL_UNRESOLVED,
        -> PersonalMemoryError.STORAGE_FAILED
        }
    }
}

sealed interface PersonalCreationResult {
    data class Created(val envelope: MemoryEnvelope) : PersonalCreationResult

    data class Failed(
        val error: PersonalMemoryError,
        val envelopeError: MemoryDomainError? = null,
    ) : PersonalCreationResult
}

sealed interface PersonalCorrectionResult {
    data class Applied(val correction: MemoryEnvelope) : PersonalCorrectionResult

    /** Exact retry of an operation that is already durably committed. */
    data class AlreadyApplied(val correction: MemoryEnvelope) : PersonalCorrectionResult

    data class Failed(val error: PersonalMemoryError) : PersonalCorrectionResult
}

sealed interface PersonalRecoveryResult {
    data class Recovered(val appliedCount: Int) : PersonalRecoveryResult

    data object NothingToRecover : PersonalRecoveryResult

    data class Failed(val error: PersonalMemoryError) : PersonalRecoveryResult
}

enum class PersonalMemoryError {
    SELF_VALIDATION_FORBIDDEN,
    DISPUTED_NOT_CREATABLE,
    SESSION_RETENTION_FORBIDDEN,
    CORRECTION_PROVENANCE_FORBIDDEN,
    CORRECTION_TARGET_MISMATCH,
    OWNER_MISMATCH,
    TARGET_NOT_ACTIVE,
    DUPLICATE_CORRECTION,
    IDEMPOTENCY_CONFLICT,
    LINK_MISMATCH,
    CORRECTION_PAIR_MISMATCH,
    WRONG_LAYER,
    STALE_WRITE,
    TARGET_MISSING,
    INVALID_UPDATED_AT,
    ENVELOPE_REJECTED,
    STORAGE_FAILED,
    ;
}
