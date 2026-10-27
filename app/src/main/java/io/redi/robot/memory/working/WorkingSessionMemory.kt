package io.redi.robot.memory.working

import io.redi.robot.hugo.identity.HugoIdentityId
import io.redi.robot.memory.domain.MemoryDisposition
import io.redi.robot.memory.domain.MemoryDomainError
import io.redi.robot.memory.domain.MemoryDomainResult
import io.redi.robot.memory.domain.MemoryEnvelope
import io.redi.robot.memory.domain.MemoryLayer
import io.redi.robot.memory.domain.MemoryRecordId
import io.redi.robot.memory.domain.MemoryRetention
import io.redi.robot.memory.domain.MemoryRetentionClass
import java.util.Collections

/**
 * H060 working memory: a bounded, session-scoped buffer of H058 WORKING-layer envelopes.
 *
 * Working memory is current-session context only. It is never persisted, never durable by
 * itself, and never promotes validation state. The only path out is an explicit, governed
 * durability decision ([promote]) that produces an EPISODIC envelope for the caller to persist
 * through the H059 authoritative store, or [forget]/[endSession] which discard.
 *
 * All bounds are enforced: bounded capacity with deterministic oldest-eviction, bounded session
 * lifetime ending at [endSession], and owner binding preventing cross-identity leakage. All time
 * values are caller-injected epoch seconds; no clock lives here. H058 domain types are consumed
 * unchanged; every H060-specific failure is typed here, never by extending H058.
 */
class WorkingSessionMemory private constructor(
    private val policy: WorkingMemoryPolicy,
    private val ownerIdentity: HugoIdentityId,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<MemoryRecordId, MemoryEnvelope>()
    private val traceLines = ArrayDeque<String>()

    /** Bounded, content-free operation trace (record traces are the H060 evidence form). */
    val trace: List<String>
        get() = Collections.unmodifiableList(traceLines.toList())

    /** The session owner; envelopes bound to another identity are rejected. */
    fun owner(): HugoIdentityId = ownerIdentity

    /**
     * Adds one WORKING-layer, active, session-scoped, owner-matching envelope. When the session
     * is at capacity the oldest entry is evicted deterministically and surfaced in the result.
     */
    fun remember(envelope: MemoryEnvelope): WorkingRememberResult = synchronized(lock) {
        if (envelope.layer != MemoryLayer.WORKING) {
            return failed(WorkingMemoryError.WRONG_LAYER)
        }
        if (envelope.disposition != MemoryDisposition.ACTIVE) {
            return failed(WorkingMemoryError.NOT_ACTIVE)
        }
        if (envelope.retention?.retentionClass != MemoryRetentionClass.SESSION_SCOPED) {
            return failed(WorkingMemoryError.WRONG_RETENTION_CLASS)
        }
        if (envelope.ownerIdentity != ownerIdentity) {
            return failed(WorkingMemoryError.OWNER_MISMATCH)
        }
        var evicted: MemoryRecordId? = null
        if (entries.size >= policy.capacity && !entries.containsKey(envelope.recordId)) {
            evicted = entries.keys.first()
            entries.remove(evicted)
            appendTrace("EVICTED:$evicted")
        }
        entries.remove(envelope.recordId)
        entries[envelope.recordId] = envelope
        appendTrace("REMEMBERED:${envelope.recordId}:size=${entries.size}")
        WorkingRememberResult.Remembered(envelope.recordId, evicted)
    }

    /** Recalls one entry by id, or null when absent from the current session. */
    fun recall(recordId: MemoryRecordId): MemoryEnvelope? = synchronized(lock) { entries[recordId] }

    /** Bounded snapshot of the current session in insertion order (oldest first). */
    fun recallAll(): List<MemoryEnvelope> = synchronized(lock) {
        Collections.unmodifiableList(entries.values.toList())
    }

    fun size(): Int = synchronized(lock) { entries.size }

    /** Explicitly discards one entry; returns whether it was present. */
    fun forget(recordId: MemoryRecordId): Boolean = synchronized(lock) {
        val removed = entries.remove(recordId) != null
        if (removed) appendTrace("FORGOT:$recordId")
        removed
    }

    /** Ends the session: every entry is discarded; the count is returned for evidence. */
    fun endSession(): Int = synchronized(lock) {
        val count = entries.size
        entries.clear()
        appendTrace("SESSION_ENDED:cleared=$count")
        count
    }

    /**
     * The governed durability decision: converts one WORKING entry into an EPISODIC envelope for
     * explicit persistence through the H059 store. Provenance, confidence, validation state,
     * disposition, privacy, allowed use, subjects, content, and links are preserved unchanged —
     * a model-generated candidate stays a candidate. The entry leaves the session on success.
     * Session-scoped retention is rejected: durability must be a real decision.
     */
    fun promote(
        recordId: MemoryRecordId,
        retention: MemoryRetention,
        updatedAtEpochSecond: Long,
    ): WorkingPromotionResult = synchronized(lock) {
        val working = entries[recordId]
            ?: return WorkingPromotionResult.Failed(WorkingPromotionReason.Policy(WorkingMemoryError.RECORD_NOT_FOUND))
        if (retention.retentionClass == MemoryRetentionClass.SESSION_SCOPED) {
            return WorkingPromotionResult.Failed(
                WorkingPromotionReason.Policy(WorkingMemoryError.INVALID_PROMOTION_RETENTION),
            )
        }
        val content = working.content
            ?: return WorkingPromotionResult.Failed(
                WorkingPromotionReason.Envelope(MemoryDomainError.ContentRequired),
            )
        val confidence = working.confidence
            ?: return WorkingPromotionResult.Failed(
                WorkingPromotionReason.Envelope(MemoryDomainError.ConfidenceRequired),
            )
        return when (val created = MemoryEnvelope.create(
            recordId = working.recordId,
            ownerIdentity = working.ownerIdentity,
            layer = MemoryLayer.EPISODIC,
            type = working.type,
            scope = working.scope,
            subjects = working.subjects,
            content = content,
            provenance = working.provenance,
            confidence = confidence,
            validationState = working.validationState,
            disposition = working.disposition,
            privacy = working.privacy,
            allowedUse = working.allowedUse,
            retention = retention,
            supersedes = working.supersedes,
            conflictsWith = working.conflictsWith,
            lineage = working.lineage,
            createdAtEpochSecond = working.createdAtEpochSecond,
            updatedAtEpochSecond = updatedAtEpochSecond,
        )) {
            is MemoryDomainResult.Success -> {
                entries.remove(recordId)
                appendTrace("PROMOTED:$recordId:EPISODIC")
                WorkingPromotionResult.Promoted(created.value)
            }
            is MemoryDomainResult.Failure ->
                WorkingPromotionResult.Failed(WorkingPromotionReason.Envelope(created.error))
        }
    }

    private fun failed(error: WorkingMemoryError): WorkingRememberResult {
        appendTrace("REJECTED:$error")
        return WorkingRememberResult.Failed(error)
    }

    private fun appendTrace(line: String) {
        traceLines.addLast(line)
        while (traceLines.size > WorkingMemoryPolicy.MAXIMUM_TRACE_LINES) {
            traceLines.removeFirst()
        }
    }

    companion object {
        /** Sessions are bound to exactly one Hugo identity at construction. */
        fun open(
            policy: WorkingMemoryPolicy,
            ownerIdentity: HugoIdentityId,
        ): WorkingSessionMemory = WorkingSessionMemory(policy, ownerIdentity)
    }
}

/** Bounded session policy. Capacity is validated; there is no unbounded mode. */
class WorkingMemoryPolicy private constructor(
    val capacity: Int,
) {
    override fun toString(): String = "WorkingMemoryPolicy(capacity=$capacity)"

    companion object {
        const val MAXIMUM_CAPACITY = 64
        const val MAXIMUM_TRACE_LINES = 64

        fun create(capacity: Int): WorkingPolicyResult = if (capacity in 1..MAXIMUM_CAPACITY) {
            WorkingPolicyResult.Success(WorkingMemoryPolicy(capacity))
        } else {
            WorkingPolicyResult.Failed(WorkingMemoryError.INVALID_SESSION_CAPACITY)
        }
    }
}

sealed interface WorkingPolicyResult {
    data class Success(val policy: WorkingMemoryPolicy) : WorkingPolicyResult
    data class Failed(val error: WorkingMemoryError) : WorkingPolicyResult
}

sealed interface WorkingRememberResult {
    data class Remembered(
        val recordId: MemoryRecordId,
        val evictedRecordId: MemoryRecordId?,
    ) : WorkingRememberResult

    data class Failed(val error: WorkingMemoryError) : WorkingRememberResult
}

sealed interface WorkingPromotionResult {
    data class Promoted(val envelope: MemoryEnvelope) : WorkingPromotionResult

    data class Failed(val reason: WorkingPromotionReason) : WorkingPromotionResult
}

sealed interface WorkingPromotionReason {
    data class Policy(val error: WorkingMemoryError) : WorkingPromotionReason

    /** The H058 envelope factory rejected the promoted record; H058 invariants stand. */
    data class Envelope(val error: MemoryDomainError) : WorkingPromotionReason
}

enum class WorkingMemoryError {
    WRONG_LAYER,
    NOT_ACTIVE,
    WRONG_RETENTION_CLASS,
    OWNER_MISMATCH,
    AT_CAPACITY,
    RECORD_NOT_FOUND,
    INVALID_PROMOTION_RETENTION,
    INVALID_SESSION_CAPACITY,
    ;
}
