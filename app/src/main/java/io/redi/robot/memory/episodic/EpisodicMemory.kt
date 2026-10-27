package io.redi.robot.memory.episodic

import io.redi.robot.hugo.identity.HugoIdentityId
import io.redi.robot.memory.domain.MemoryAllowedUse
import io.redi.robot.memory.domain.MemoryDisposition
import io.redi.robot.memory.domain.MemoryDomainError
import io.redi.robot.memory.domain.MemoryDomainResult
import io.redi.robot.memory.domain.MemoryEnvelope
import io.redi.robot.memory.domain.MemoryLayer
import io.redi.robot.memory.domain.MemoryLineage
import io.redi.robot.memory.domain.MemoryProvenance
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
import io.redi.robot.memory.storage.MemoryEnvelopeStore
import io.redi.robot.memory.storage.MemoryListResult
import io.redi.robot.memory.storage.MemoryLoadResult
import io.redi.robot.memory.storage.MemoryPersistResult
import java.util.UUID

/**
 * H060 episodic memory: typed creation and persistence of bounded event/experience records.
 *
 * Episodic records are H058 envelopes with the EPISODIC layer, globally-personal scope (per the
 * H058 layer/scope matrix), explicit provenance, deterministic retention, and optional lineage
 * when derived. Creation is the only place event envelopes are born; persistence delegates to
 * the H059 authoritative store unchanged — persistence never upgrades confidence, validation
 * state, privacy, allowed use, or authority, and a model-generated record stays a candidate.
 */
class EpisodicEventFactory private constructor(
    private val ownerIdentity: HugoIdentityId,
    private val recordIds: () -> MemoryRecordId,
) {
    /**
     * Creates one episodic event envelope. Retention must be a real durable policy
     * (session-scoped retention belongs to working memory and is rejected here). Provenance is
     * explicit and complete; derived events carry lineage. The returned envelope is an H058
     * value; nothing is persisted by this call.
     */
    @Suppress("LongParameterList")
    fun create(
        eventType: MemoryTypeToken,
        subjects: List<MemorySubjectToken>,
        content: ContextValue,
        provenance: MemoryProvenance,
        confidence: ContextConfidence,
        validationState: MemoryValidationState,
        privacy: ContextPrivacyClassification,
        allowedUse: MemoryAllowedUse,
        retention: MemoryRetention,
        lineage: MemoryLineage?,
        createdAtEpochSecond: Long,
    ): EpisodicCreationResult {
        if (retention.retentionClass == MemoryRetentionClass.SESSION_SCOPED) {
            return EpisodicCreationResult.Failed(EpisodicRecordError.SESSION_RETENTION_FORBIDDEN)
        }
        val recordId = recordIds()
        return when (
            val created = MemoryEnvelope.create(
                recordId = recordId,
                ownerIdentity = ownerIdentity,
                layer = MemoryLayer.EPISODIC,
                type = eventType,
                scope = MemoryScope.GlobalPersonal,
                subjects = subjects,
                content = content,
                provenance = provenance,
                confidence = confidence,
                validationState = validationState,
                disposition = MemoryDisposition.ACTIVE,
                privacy = privacy,
                allowedUse = allowedUse,
                retention = retention,
                supersedes = null,
                conflictsWith = emptyList(),
                lineage = lineage,
                createdAtEpochSecond = createdAtEpochSecond,
                updatedAtEpochSecond = null,
            )
        ) {
            is MemoryDomainResult.Success -> EpisodicCreationResult.Created(created.value)
            is MemoryDomainResult.Failure -> EpisodicCreationResult.Failed(
                EpisodicRecordError.ENVELOPE_REJECTED,
                created.error,
            )
        }
    }

    companion object {
        fun open(
            ownerIdentity: HugoIdentityId,
            recordIds: () -> MemoryRecordId = {
                (MemoryRecordId.parse(UUID.randomUUID().toString()) as MemoryDomainResult.Success).value
            },
        ): EpisodicEventFactory = EpisodicEventFactory(ownerIdentity, recordIds)
    }
}

/**
 * Typed episodic-record persistence over the H059 authoritative store. It adds only the
 * EPISODIC-layer discipline: records that are not episodic event envelopes are rejected before
 * touching the store, and loads of non-episodic ids are reported typed instead of returned.
 */
class EpisodicRecordRepository(private val store: MemoryEnvelopeStore) {
    /** Persists one episodic envelope (create, update, or tombstone persistence). */
    fun record(envelope: MemoryEnvelope): EpisodicPersistResult {
        if (envelope.layer != MemoryLayer.EPISODIC) {
            return EpisodicPersistResult.Failed(EpisodicRecordError.WRONG_LAYER)
        }
        return when (val persisted = store.persist(envelope)) {
            is MemoryPersistResult.Persisted -> EpisodicPersistResult.Persisted
            is MemoryPersistResult.Failed ->
                EpisodicPersistResult.Failed(persisted.error.toEpisodicError())
        }
    }

    /** Loads one episodic record; a stored non-episodic id is a typed failure, not a value. */
    fun event(recordId: MemoryRecordId): EpisodicLoadResult = when (val loaded = store.load(recordId)) {
        is MemoryLoadResult.Found ->
            if (loaded.envelope.layer == MemoryLayer.EPISODIC) {
                EpisodicLoadResult.Found(loaded.envelope)
            } else {
                EpisodicLoadResult.Failed(EpisodicRecordError.WRONG_LAYER)
            }
        is MemoryLoadResult.Missing -> EpisodicLoadResult.Missing
        is MemoryLoadResult.Failed ->
            EpisodicLoadResult.Failed(loaded.error.toEpisodicError())
    }

    /**
     * Bounded, deterministic listing of persisted episodic event records.
     *
     * The id limit must not be applied before the EPISODIC filter — non-episodic records would
     * otherwise consume the requested result size. The facade therefore pages through the store
     * with H059 keyset continuation ([MemoryEnvelopeStore.storedRecordIds] with `afterId`),
     * collecting until [limit] episodic records are found or the store is exhausted. Every page
     * respects the H059 per-page bound; non-episodic records can never shrink, empty, or bound
     * an episodic result, and episodic records remain reachable at any store depth.
     */
    fun events(limit: Int): EpisodicListResult {
        if (limit <= 0) {
            return EpisodicListResult.Failed(EpisodicRecordError.INVALID_LIMIT)
        }
        val events = mutableListOf<MemoryEnvelope>()
        var afterId: MemoryRecordId? = null
        while (true) {
            when (val page = store.storedRecordIds(MemoryEnvelopeStore.MAXIMUM_LIST_LIMIT, afterId)) {
                is MemoryListResult.Failed ->
                    return EpisodicListResult.Failed(page.error.toEpisodicError())
                is MemoryListResult.Ids -> {
                    if (page.recordIds.isEmpty()) return EpisodicListResult.Events(events)
                    page.recordIds.forEach { id ->
                        when (val loaded = store.load(id)) {
                            is MemoryLoadResult.Found ->
                                if (loaded.envelope.layer == MemoryLayer.EPISODIC) {
                                    events.add(loaded.envelope)
                                    if (events.size == limit) return EpisodicListResult.Events(events)
                                }
                            is MemoryLoadResult.Failed ->
                                return EpisodicListResult.Failed(loaded.error.toEpisodicError())
                            MemoryLoadResult.Missing -> Unit
                        }
                    }
                    afterId = page.recordIds.last()
                }
            }
        }
    }

    private fun io.redi.robot.memory.storage.MemoryPersistenceError.toEpisodicError(): EpisodicRecordError =
        when (this) {
            io.redi.robot.memory.storage.MemoryPersistenceError.INVALID_LIMIT ->
                EpisodicRecordError.INVALID_LIMIT
            else -> EpisodicRecordError.STORAGE_FAILED
        }
}

sealed interface EpisodicCreationResult {
    data class Created(val envelope: MemoryEnvelope) : EpisodicCreationResult

    data class Failed(
        val error: EpisodicRecordError,
        val envelopeError: MemoryDomainError? = null,
    ) : EpisodicCreationResult
}

sealed interface EpisodicPersistResult {
    data object Persisted : EpisodicPersistResult

    data class Failed(val error: EpisodicRecordError) : EpisodicPersistResult
}

sealed interface EpisodicLoadResult {
    data class Found(val envelope: MemoryEnvelope) : EpisodicLoadResult

    data object Missing : EpisodicLoadResult

    data class Failed(val error: EpisodicRecordError) : EpisodicLoadResult
}

sealed interface EpisodicListResult {
    data class Events(val envelopes: List<MemoryEnvelope>) : EpisodicListResult

    data class Failed(val error: EpisodicRecordError) : EpisodicListResult
}

enum class EpisodicRecordError {
    WRONG_LAYER,
    SESSION_RETENTION_FORBIDDEN,
    ENVELOPE_REJECTED,
    INVALID_LIMIT,
    STORAGE_FAILED,
    ;
}
