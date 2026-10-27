package io.redi.robot.memory.semantic

import io.redi.robot.hugo.identity.HugoIdentityId
import io.redi.robot.memory.domain.MemoryAllowedUse
import io.redi.robot.memory.domain.MemoryCreator
import io.redi.robot.memory.domain.MemoryDisposition
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
import io.redi.robot.memory.storage.MemoryChangeSetResult
import io.redi.robot.memory.storage.MemoryEnvelopeStore
import io.redi.robot.memory.storage.MemoryLoadResult
import io.redi.robot.memory.storage.MemoryTransitionResult

/**
 * H061 semantic memory: validated facts and concepts.
 *
 * Validation authority is explicit user confirmation only. A record whose provenance is not a
 * user-explicit statement can be created only as a CANDIDATE; it becomes VALIDATED exclusively
 * through [SemanticConfirmationService.confirm] — an explicit user confirmation act — and its
 * original provenance is preserved unchanged through that transition. Model, tool, observed, and
 * inferred records can never self-validate.
 */
class SemanticFactFactory private constructor(
    private val ownerIdentity: HugoIdentityId,
    private val recordIds: () -> MemoryRecordId,
) {
    /**
     * Creates one semantic fact envelope. Requesting VALIDATED requires user-explicit,
     * non-model provenance; every other provenance must start as a CANDIDATE.
     */
    @Suppress("LongParameterList")
    fun create(
        factType: MemoryTypeToken,
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
    ): SemanticCreationResult {
        if (requestedValidationState == MemoryValidationState.DISPUTED) {
            return SemanticCreationResult.Failed(SemanticMemoryError.DISPUTED_NOT_CREATABLE)
        }
        if (requestedValidationState == MemoryValidationState.VALIDATED &&
            (provenance.origin != MemoryProvenanceOrigin.USER_EXPLICIT || provenance.modelGenerated)
        ) {
            return SemanticCreationResult.Failed(SemanticMemoryError.SELF_VALIDATION_FORBIDDEN)
        }
        if (retention.retentionClass == MemoryRetentionClass.SESSION_SCOPED) {
            return SemanticCreationResult.Failed(SemanticMemoryError.SESSION_RETENTION_FORBIDDEN)
        }
        return when (
            val created = MemoryEnvelope.create(
                recordId = recordIds(),
                ownerIdentity = ownerIdentity,
                layer = MemoryLayer.SEMANTIC,
                type = factType,
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
            is MemoryDomainResult.Success -> SemanticCreationResult.Created(created.value)
            is MemoryDomainResult.Failure -> SemanticCreationResult.Failed(
                SemanticMemoryError.ENVELOPE_REJECTED,
                created.error,
            )
        }
    }

    companion object {
        fun open(ownerIdentity: HugoIdentityId, recordIds: () -> MemoryRecordId): SemanticFactFactory =
            SemanticFactFactory(ownerIdentity, recordIds)
    }

    /**
     * Creates the correction successor for [target]: a SEMANTIC-layer record bound to the
     * target's owner that preserves the target's type, scope, subjects, privacy, allowed use,
     * and retention — a correction replaces content, never governance. Provenance is forced to
     * user-explicit and non-model (a model may never correct), the record carries the
     * `supersedes` link plus lineage, and it is VALIDATED (an explicit user statement is
     * self-validating). The successor is not persisted here; [SemanticCorrectionService] commits
     * it atomically together with the SUPERSEDED target through the H059 change-set.
     */
    fun createCorrection(
        target: MemoryEnvelope,
        content: ContextValue,
        confidence: ContextConfidence,
        createdAtEpochSecond: Long,
    ): SemanticCreationResult {
        if (target.layer != MemoryLayer.SEMANTIC || target.scope != MemoryScope.GlobalPersonal) {
            return SemanticCreationResult.Failed(SemanticMemoryError.CORRECTION_TARGET_MISMATCH)
        }
        if (target.ownerIdentity != ownerIdentity) {
            return SemanticCreationResult.Failed(SemanticMemoryError.OWNER_MISMATCH)
        }
        if (target.disposition != MemoryDisposition.ACTIVE) {
            return SemanticCreationResult.Failed(SemanticMemoryError.TARGET_NOT_ACTIVE)
        }
        val retention = target.retention
            ?: return SemanticCreationResult.Failed(SemanticMemoryError.ENVELOPE_REJECTED)
        return when (
            val created = MemoryEnvelope.create(
                recordId = recordIds(),
                ownerIdentity = target.ownerIdentity,
                layer = MemoryLayer.SEMANTIC,
                type = target.type,
                scope = target.scope,
                subjects = target.subjects,
                content = content,
                provenance = userCorrectionProvenance(),
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
            is MemoryDomainResult.Success -> SemanticCreationResult.Created(created.value)
            is MemoryDomainResult.Failure -> SemanticCreationResult.Failed(
                SemanticMemoryError.ENVELOPE_REJECTED,
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
}

/**
 * Explicit user confirmation lifecycle (H061). A CANDIDATE becomes VALIDATED only through this
 * explicit user-confirmed act; provenance and confidence are preserved unchanged, and the flag
 * [confirmRequest.userConfirmed] must be true — the caller (a future governed UI flow) may only
 * set it for a genuine user confirmation gesture. Disputed records are not confirmable; a
 * disputed record must first be resolved by the user. Fail-closed safety: an expired retention
 * and an unresolved conflict are not confirmable, and the transition commits CAS-style — the
 * stored record must still be exactly the candidate the decision was made on, so a concurrently
 * committed correction or change can never be overwritten (resurrected).
 */
class SemanticConfirmationService(private val store: MemoryEnvelopeStore) {
    fun confirm(request: SemanticConfirmationRequest): SemanticConfirmationResult {
        if (!request.userConfirmed) {
            return SemanticConfirmationResult.Failed(SemanticMemoryError.USER_CONFIRMATION_REQUIRED)
        }
        return when (val loaded = store.load(request.recordId)) {
            is MemoryLoadResult.Found -> {
                val envelope = loaded.envelope
                if (envelope.layer != MemoryLayer.SEMANTIC) {
                    SemanticConfirmationResult.Failed(SemanticMemoryError.WRONG_LAYER)
                } else if (envelope.disposition != MemoryDisposition.ACTIVE) {
                    SemanticConfirmationResult.Failed(SemanticMemoryError.NOT_ACTIVE)
                } else if (envelope.validationState != MemoryValidationState.CANDIDATE) {
                    SemanticConfirmationResult.Failed(SemanticMemoryError.NOT_CANDIDATE)
                } else if (envelope.content == null || envelope.confidence == null || envelope.retention == null) {
                    SemanticConfirmationResult.Failed(SemanticMemoryError.ENVELOPE_REJECTED)
                } else if (
                    envelope.retention.expiresAtEpochSecond != null &&
                    request.atEpochSecond >= envelope.retention.expiresAtEpochSecond
                ) {
                    SemanticConfirmationResult.Failed(SemanticMemoryError.RETENTION_EXPIRED)
                } else if (envelope.conflictsWith.isNotEmpty()) {
                    SemanticConfirmationResult.Failed(SemanticMemoryError.UNRESOLVED_CONFLICTS)
                } else {
                    // The lifecycle transition uses the codec path (restore): a user-confirmed
                    // model-inferred candidate becomes VALIDATED while retaining its original
                    // provenance — creation policy does not apply to lifecycle transitions.
                    val rebuilt = when (
                        val restored = MemoryEnvelope.restore(
                            recordId = envelope.recordId,
                            ownerIdentity = envelope.ownerIdentity,
                            layer = envelope.layer,
                            type = envelope.type,
                            scope = envelope.scope,
                            subjects = envelope.subjects,
                            content = envelope.content,
                            provenance = envelope.provenance,
                            confidence = envelope.confidence,
                            validationState = MemoryValidationState.VALIDATED,
                            disposition = envelope.disposition,
                            privacy = envelope.privacy,
                            allowedUse = envelope.allowedUse,
                            retention = envelope.retention,
                            supersedes = envelope.supersedes,
                            conflictsWith = envelope.conflictsWith,
                            lineage = envelope.lineage,
                            tombstone = null,
                            createdAtEpochSecond = envelope.createdAtEpochSecond,
                            updatedAtEpochSecond = maxOf(request.atEpochSecond, envelope.createdAtEpochSecond),
                        )
                    ) {
                        is MemoryDomainResult.Success -> restored.value
                        is MemoryDomainResult.Failure ->
                            return SemanticConfirmationResult.Failed(SemanticMemoryError.ENVELOPE_REJECTED)
                    }
                    // CAS-style commit: the stored record must still be exactly the candidate
                    // this decision was made on; a concurrently committed correction or change
                    // is never overwritten (resurrected).
                    when (val persisted = store.persistTransition(rebuilt, envelope)) {
                        is MemoryTransitionResult.Applied -> SemanticConfirmationResult.Confirmed(rebuilt)
                        is MemoryTransitionResult.Failed -> SemanticConfirmationResult.Failed(
                            when (persisted.error) {
                                MemoryChangeSetError.STALE_WRITE,
                                MemoryChangeSetError.INVALID_TARGET_STATE,
                                MemoryChangeSetError.TARGET_MISSING,
                                MemoryChangeSetError.LINK_MISMATCH,
                                -> SemanticMemoryError.CONCURRENT_MODIFICATION
                                else -> SemanticMemoryError.STORAGE_FAILED
                            },
                        )
                    }
                }
            }
            is MemoryLoadResult.Missing -> SemanticConfirmationResult.Failed(SemanticMemoryError.RECORD_NOT_FOUND)
            is MemoryLoadResult.Failed -> SemanticConfirmationResult.Failed(SemanticMemoryError.STORAGE_FAILED)
        }
    }
}

data class SemanticConfirmationRequest(
    val recordId: MemoryRecordId,
    val userConfirmed: Boolean,
    val actor: io.redi.robot.memory.domain.MemoryCreator,
    val atEpochSecond: Long,
)

/**
 * Commits explicit user corrections of SEMANTIC records atomically through the H059 change-set:
 * the correction successor and the SUPERSEDED target are applied together, with stale-write
 * protection, correction-pair integrity, and crash-safe recovery. Only user corrections may
 * supersede — an inference never outranks a governed record, so an explicit user correction is
 * the one authority that can overturn an inferred semantic fact.
 */
class SemanticCorrectionService(private val store: MemoryEnvelopeStore) {
    fun correct(
        expectedTarget: MemoryEnvelope,
        correction: MemoryEnvelope,
        updatedAtEpochSecond: Long,
    ): SemanticCorrectionResult {
        if (correction.layer != MemoryLayer.SEMANTIC) {
            return SemanticCorrectionResult.Failed(SemanticMemoryError.WRONG_LAYER)
        }
        if (correction.provenance.origin != MemoryProvenanceOrigin.USER_EXPLICIT ||
            correction.provenance.modelGenerated
        ) {
            return SemanticCorrectionResult.Failed(SemanticMemoryError.CORRECTION_PROVENANCE_FORBIDDEN)
        }
        return when (
            val applied = store.persistSupersessionChangeSet(correction, expectedTarget, updatedAtEpochSecond)
        ) {
            is MemoryChangeSetResult.Applied -> SemanticCorrectionResult.Applied(correction)
            is MemoryChangeSetResult.AlreadyApplied -> SemanticCorrectionResult.AlreadyApplied(correction)
            is MemoryChangeSetResult.Failed -> SemanticCorrectionResult.Failed(applied.error.toSemanticError())
        }
    }

    fun recover(): SemanticCorrectionResult = when (val recovered = store.recoverChangeSet()) {
        is MemoryChangeSetRecoveryResult.Recovered -> SemanticCorrectionResult.Recovered(recovered.appliedCount)
        is MemoryChangeSetRecoveryResult.NothingToRecover -> SemanticCorrectionResult.NothingToRecover
        is MemoryChangeSetRecoveryResult.Failed ->
            SemanticCorrectionResult.Failed(recovered.error.toSemanticError())
    }

    private fun MemoryChangeSetError.toSemanticError(): SemanticMemoryError = when (this) {
        MemoryChangeSetError.STALE_WRITE -> SemanticMemoryError.STALE_WRITE
        MemoryChangeSetError.TARGET_MISSING -> SemanticMemoryError.TARGET_MISSING
        MemoryChangeSetError.INVALID_TARGET_STATE -> SemanticMemoryError.TARGET_NOT_ACTIVE
        MemoryChangeSetError.DUPLICATE_SUCCESSOR -> SemanticMemoryError.DUPLICATE_CORRECTION
        MemoryChangeSetError.OPERATION_ID_REUSED -> SemanticMemoryError.IDEMPOTENCY_CONFLICT
        MemoryChangeSetError.CORRECTION_AUTHORITY_INVALID ->
            SemanticMemoryError.CORRECTION_PROVENANCE_FORBIDDEN
        MemoryChangeSetError.LINK_MISMATCH -> SemanticMemoryError.LINK_MISMATCH
        MemoryChangeSetError.CROSS_LAYER_SUPERSESSION -> SemanticMemoryError.WRONG_LAYER
        MemoryChangeSetError.CORRECTION_PAIR_MISMATCH -> SemanticMemoryError.CORRECTION_PAIR_MISMATCH
        MemoryChangeSetError.INVALID_UPDATED_AT -> SemanticMemoryError.INVALID_UPDATED_AT
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
        -> SemanticMemoryError.STORAGE_FAILED
    }
}

/** Forced provenance for corrections: user-explicit and non-model — a model may never correct. */
private fun userCorrectionProvenance(): MemoryProvenance =
    (MemoryProvenance.create(
        MemoryProvenanceOrigin.USER_EXPLICIT,
        modelGenerated = false,
        (io.redi.robot.context.api.ContextSourceType.parse("memory.correction")
            as io.redi.robot.context.api.ContextObservationResult.Success).value,
        (io.redi.robot.context.api.ContextSourceReference.parse("memory_correction")
            as io.redi.robot.context.api.ContextObservationResult.Success).value,
        (MemoryCreator.parse("user_correction") as MemoryDomainResult.Success).value,
        null,
    ) as MemoryDomainResult.Success).value

sealed interface SemanticCreationResult {
    data class Created(val envelope: MemoryEnvelope) : SemanticCreationResult

    data class Failed(
        val error: SemanticMemoryError,
        val envelopeError: io.redi.robot.memory.domain.MemoryDomainError? = null,
    ) : SemanticCreationResult
}

sealed interface SemanticConfirmationResult {
    data class Confirmed(val envelope: MemoryEnvelope) : SemanticConfirmationResult

    data class Failed(val error: SemanticMemoryError) : SemanticConfirmationResult
}

sealed interface SemanticCorrectionResult {
    data class Applied(val correction: MemoryEnvelope) : SemanticCorrectionResult

    /** Exact retry of an operation that is already durably committed. */
    data class AlreadyApplied(val correction: MemoryEnvelope) : SemanticCorrectionResult

    data class Recovered(val appliedCount: Int) : SemanticCorrectionResult

    data object NothingToRecover : SemanticCorrectionResult

    data class Failed(val error: SemanticMemoryError) : SemanticCorrectionResult
}

enum class SemanticMemoryError {
    SELF_VALIDATION_FORBIDDEN,
    DISPUTED_NOT_CREATABLE,
    SESSION_RETENTION_FORBIDDEN,
    USER_CONFIRMATION_REQUIRED,
    RECORD_NOT_FOUND,
    WRONG_LAYER,
    NOT_ACTIVE,
    NOT_CANDIDATE,
    RETENTION_EXPIRED,
    UNRESOLVED_CONFLICTS,
    CONCURRENT_MODIFICATION,
    CORRECTION_PROVENANCE_FORBIDDEN,
    CORRECTION_TARGET_MISMATCH,
    OWNER_MISMATCH,
    TARGET_NOT_ACTIVE,
    DUPLICATE_CORRECTION,
    IDEMPOTENCY_CONFLICT,
    LINK_MISMATCH,
    STALE_WRITE,
    TARGET_MISSING,
    INVALID_UPDATED_AT,
    CORRECTION_PAIR_MISMATCH,
    ENVELOPE_REJECTED,
    STORAGE_FAILED,
    ;
}

/** Re-exported allowed-use taxonomy; the definition remains the H058 envelope's. */
typealias SemanticAllowedUseAlias = MemoryAllowedUse
