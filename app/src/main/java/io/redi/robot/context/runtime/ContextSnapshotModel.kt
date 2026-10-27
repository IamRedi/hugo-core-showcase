package io.redi.robot.context.runtime

import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.context.api.ContextConfidence
import io.redi.robot.context.api.ContextKey
import io.redi.robot.context.api.ContextObservationClaim
import io.redi.robot.context.api.ContextObservationId
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextSourceReference
import io.redi.robot.context.api.ContextSourceType
import io.redi.robot.context.api.ContextUnknownReason
import io.redi.robot.context.api.ContextValue
import java.util.Collections

class ContextSnapshotBudget private constructor(
    val maximumItems: Int,
    val maximumEvidencePerItem: Int,
    val maximumTotalEvidence: Int,
    val maximumUtf8Bytes: Int,
) {
    companion object {
        const val HARD_MAXIMUM_ITEMS: Int = 128
        const val HARD_MAXIMUM_EVIDENCE_PER_ITEM: Int = 32
        const val HARD_MAXIMUM_TOTAL_EVIDENCE: Int = 512
        const val HARD_MAXIMUM_UTF8_BYTES: Int = 131_072

        fun create(
            maximumItems: Int,
            maximumEvidencePerItem: Int,
            maximumTotalEvidence: Int,
            maximumUtf8Bytes: Int,
        ): ContextSnapshotResult<ContextSnapshotBudget> = when {
            maximumItems !in 1..HARD_MAXIMUM_ITEMS -> snapshotFailure(ContextSnapshotError.InvalidItemBudget)
            maximumEvidencePerItem !in 1..HARD_MAXIMUM_EVIDENCE_PER_ITEM ->
                snapshotFailure(ContextSnapshotError.InvalidEvidencePerItemBudget)
            maximumTotalEvidence !in maximumEvidencePerItem..HARD_MAXIMUM_TOTAL_EVIDENCE ->
                snapshotFailure(ContextSnapshotError.InvalidTotalEvidenceBudget)
            maximumUtf8Bytes !in 512..HARD_MAXIMUM_UTF8_BYTES ->
                snapshotFailure(ContextSnapshotError.InvalidSizeBudget)
            else -> ContextSnapshotResult.Success(
                ContextSnapshotBudget(
                    maximumItems,
                    maximumEvidencePerItem,
                    maximumTotalEvidence,
                    maximumUtf8Bytes,
                ),
            )
        }
    }
}

sealed interface ContextKeySelection {
    data object All : ContextKeySelection

    class Exact internal constructor(keys: List<ContextKey>) : ContextKeySelection {
        val keys: List<ContextKey> = Collections.unmodifiableList(keys.toList())

        override fun equals(other: Any?): Boolean = other is Exact && keys == other.keys
        override fun hashCode(): Int = keys.hashCode()
    }
}

/**
 * Snapshot policy only. Passing observations asserts that an upstream boundary already authorized
 * observation. Privacy scope here cannot grant permission to obtain or disclose source data.
 */
class ContextSnapshotRequest private constructor(
    val now: MonotonicTimestampMs,
    val keySelection: ContextKeySelection,
    allowedPrivacyClassifications: Set<ContextPrivacyClassification>,
    val budget: ContextSnapshotBudget,
) {
    val allowedPrivacyClassifications: Set<ContextPrivacyClassification> = Collections.unmodifiableSet(
        allowedPrivacyClassifications.toSet(),
    )

    companion object {
        const val MAXIMUM_REQUESTED_KEYS: Int = 128

        fun create(
            now: MonotonicTimestampMs,
            requestedKeys: List<ContextKey>?,
            allowedPrivacyClassifications: Set<ContextPrivacyClassification>,
            budget: ContextSnapshotBudget,
        ): ContextSnapshotResult<ContextSnapshotRequest> {
            if (allowedPrivacyClassifications.isEmpty()) {
                return snapshotFailure(ContextSnapshotError.EmptyPrivacyScope)
            }
            if (requestedKeys != null) {
                if (requestedKeys.size > MAXIMUM_REQUESTED_KEYS) {
                    return snapshotFailure(
                        ContextSnapshotError.TooManyRequestedKeys(MAXIMUM_REQUESTED_KEYS),
                    )
                }
                if (requestedKeys.map { it.value }.distinct().size != requestedKeys.size) {
                    return snapshotFailure(ContextSnapshotError.DuplicateRequestedKey)
                }
            }
            val selection = requestedKeys?.let { ContextKeySelection.Exact(it) } ?: ContextKeySelection.All
            return ContextSnapshotResult.Success(
                ContextSnapshotRequest(now, selection, allowedPrivacyClassifications, budget),
            )
        }
    }
}

data class ContextSnapshotEvidence internal constructor(
    val observationId: ContextObservationId,
    val sourceType: ContextSourceType,
    val sourceReference: ContextSourceReference,
    val claim: ContextObservationClaim,
    val confidence: ContextConfidence,
    val privacyClassification: ContextPrivacyClassification,
    val observedAt: MonotonicTimestampMs,
    val expiresAt: MonotonicTimestampMs,
)

sealed interface ContextSnapshotResolution {
    data class Known(val value: ContextValue) : ContextSnapshotResolution

    class Unknown internal constructor(reasons: List<ContextUnknownReason>) : ContextSnapshotResolution {
        val reasons: List<ContextUnknownReason> = Collections.unmodifiableList(reasons.toList())

        override fun equals(other: Any?): Boolean = other is Unknown && reasons == other.reasons
        override fun hashCode(): Int = reasons.hashCode()
    }

    class Conflict internal constructor(candidates: List<ContextValue>) : ContextSnapshotResolution {
        val candidates: List<ContextValue> = Collections.unmodifiableList(candidates.toList())

        override fun equals(other: Any?): Boolean = other is Conflict && candidates == other.candidates
        override fun hashCode(): Int = candidates.hashCode()
    }
}

class ContextSnapshotItem internal constructor(
    val key: ContextKey,
    val resolution: ContextSnapshotResolution,
    evidence: List<ContextSnapshotEvidence>,
    val effectivePrivacyClassification: ContextPrivacyClassification,
) {
    val evidence: List<ContextSnapshotEvidence> = Collections.unmodifiableList(evidence.toList())
}

enum class ContextSnapshotOmissionReason(val wireValue: String) {
    DUPLICATE_OBSERVATION("duplicate_observation"),
    NOT_YET_VALID("not_yet_valid"),
    STALE("stale"),
    KEY_FILTERED("key_filtered"),
    PRIVACY_FILTERED("privacy_filtered"),
    SUPERSEDED("superseded"),
    EVIDENCE_PER_ITEM_BUDGET("evidence_per_item_budget"),
    ITEM_BUDGET("item_budget"),
    TOTAL_EVIDENCE_BUDGET("total_evidence_budget"),
    SIZE_BUDGET("size_budget"),
}

data class ContextSnapshotOmission internal constructor(
    val reason: ContextSnapshotOmissionReason,
    val count: Int,
) {
    init {
        require(count > 0)
    }
}

/** Ephemeral current-state evidence. It is neither memory nor an authority decision. */
class ContextSnapshot internal constructor(
    val asOf: MonotonicTimestampMs,
    val keySelection: ContextKeySelection,
    allowedPrivacyClassifications: Set<ContextPrivacyClassification>,
    val appliedBudget: ContextSnapshotBudget,
    val inputObservationCount: Int,
    items: List<ContextSnapshotItem>,
    omissions: List<ContextSnapshotOmission>,
) {
    val allowedPrivacyClassifications: Set<ContextPrivacyClassification> = Collections.unmodifiableSet(
        allowedPrivacyClassifications.toSet(),
    )
    val items: List<ContextSnapshotItem> = Collections.unmodifiableList(items.toList())
    val omissions: List<ContextSnapshotOmission> = Collections.unmodifiableList(omissions.toList())
    val retainedEvidenceCount: Int = items.sumOf { it.evidence.size }

    fun canonicalUtf8Size(): Int = ContextSnapshotCodec.encode(this).toByteArray(Charsets.UTF_8).size

    companion object {
        const val SCHEMA_VERSION: String = "1.0"
    }
}

sealed interface ContextSnapshotResult<out T> {
    data class Success<T>(val value: T) : ContextSnapshotResult<T>
    data class Failure(val error: ContextSnapshotError) : ContextSnapshotResult<Nothing>
}

sealed interface ContextSnapshotError {
    data object InvalidItemBudget : ContextSnapshotError
    data object InvalidEvidencePerItemBudget : ContextSnapshotError
    data object InvalidTotalEvidenceBudget : ContextSnapshotError
    data object InvalidSizeBudget : ContextSnapshotError
    data object EmptyPrivacyScope : ContextSnapshotError
    data object DuplicateRequestedKey : ContextSnapshotError
    data class TooManyRequestedKeys(val maximum: Int) : ContextSnapshotError
    data class TooManyInputObservations(val maximum: Int) : ContextSnapshotError
    data class ConflictingObservationId(val observationId: ContextObservationId) : ContextSnapshotError
    data class SnapshotBudgetTooSmall(val requiredUtf8Bytes: Int) : ContextSnapshotError
}

internal fun <T> snapshotFailure(error: ContextSnapshotError): ContextSnapshotResult<T> =
    ContextSnapshotResult.Failure(error)
