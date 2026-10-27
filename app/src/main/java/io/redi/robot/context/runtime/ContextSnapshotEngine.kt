package io.redi.robot.context.runtime

import io.redi.robot.context.api.ContextFreshness
import io.redi.robot.context.api.ContextObservation
import io.redi.robot.context.api.ContextObservationClaim
import io.redi.robot.context.api.ContextObservationCodec
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextUnknownReason
import io.redi.robot.context.api.ContextValue

object ContextSnapshotEngine {
    const val MAXIMUM_INPUT_OBSERVATIONS: Int = 1024

    fun build(
        observations: Collection<ContextObservation>,
        request: ContextSnapshotRequest,
    ): ContextSnapshotResult<ContextSnapshot> {
        if (observations.size > MAXIMUM_INPUT_OBSERVATIONS) {
            return snapshotFailure(ContextSnapshotError.TooManyInputObservations(MAXIMUM_INPUT_OBSERVATIONS))
        }

        val omissionCounts = mutableMapOf<ContextSnapshotOmissionReason, Int>()
        val uniqueById = linkedMapOf<String, ContextObservation>()
        observations.forEach { observation ->
            val previous = uniqueById[observation.observationId.value]
            when {
                previous == null -> uniqueById[observation.observationId.value] = observation
                previous.signature() == observation.signature() -> omissionCounts.increment(
                    ContextSnapshotOmissionReason.DUPLICATE_OBSERVATION,
                )
                else -> return snapshotFailure(
                    ContextSnapshotError.ConflictingObservationId(observation.observationId),
                )
            }
        }

        val requested = (request.keySelection as? ContextKeySelection.Exact)?.keys
            ?.mapIndexed { index, key -> key.value to index }
            ?.toMap()

        val eligible = uniqueById.values.filter { observation ->
            when {
                observation.freshnessAt(request.now) == ContextFreshness.NOT_YET_VALID -> {
                    omissionCounts.increment(ContextSnapshotOmissionReason.NOT_YET_VALID)
                    false
                }
                observation.freshnessAt(request.now) == ContextFreshness.STALE -> {
                    omissionCounts.increment(ContextSnapshotOmissionReason.STALE)
                    false
                }
                requested != null && observation.key.value !in requested -> {
                    omissionCounts.increment(ContextSnapshotOmissionReason.KEY_FILTERED)
                    false
                }
                observation.privacyClassification !in request.allowedPrivacyClassifications -> {
                    omissionCounts.increment(ContextSnapshotOmissionReason.PRIVACY_FILTERED)
                    false
                }
                else -> true
            }
        }

        val normalized = eligible.groupBy { observation ->
            SourceSlot(observation.key.value, observation.sourceType.value, observation.sourceReference.value)
        }.values.flatMap { sameSource ->
            val latest = sameSource.maxOf { it.observedAt.value }
            sameSource.filter { observation ->
                val retain = observation.observedAt.value == latest
                if (!retain) omissionCounts.increment(ContextSnapshotOmissionReason.SUPERSEDED)
                retain
            }
        }

        val candidateItems = normalized.groupBy { it.key.value }.mapNotNull { (_, sameKey) ->
            if (sameKey.size > request.budget.maximumEvidencePerItem) {
                omissionCounts.increment(ContextSnapshotOmissionReason.EVIDENCE_PER_ITEM_BUDGET)
                null
            } else {
                sameKey.toSnapshotItem()
            }
        }.sortedWith(
            compareBy<ContextSnapshotItem> { requested?.get(it.key.value) ?: Int.MAX_VALUE }
                .thenBy { it.key.value },
        )

        val selected = mutableListOf<ContextSnapshotItem>()
        var evidenceCount = 0
        candidateItems.forEach { item ->
            when {
                selected.size >= request.budget.maximumItems ->
                    omissionCounts.increment(ContextSnapshotOmissionReason.ITEM_BUDGET)
                evidenceCount + item.evidence.size > request.budget.maximumTotalEvidence ->
                    omissionCounts.increment(ContextSnapshotOmissionReason.TOTAL_EVIDENCE_BUDGET)
                else -> {
                    selected += item
                    evidenceCount += item.evidence.size
                }
            }
        }

        var snapshot = snapshot(observations.size, selected, omissionCounts, request)
        while (snapshot.canonicalUtf8Size() > request.budget.maximumUtf8Bytes && selected.isNotEmpty()) {
            selected.removeAt(selected.lastIndex)
            omissionCounts.increment(ContextSnapshotOmissionReason.SIZE_BUDGET)
            snapshot = snapshot(observations.size, selected, omissionCounts, request)
        }
        val finalSize = snapshot.canonicalUtf8Size()
        return if (finalSize > request.budget.maximumUtf8Bytes) {
            snapshotFailure(ContextSnapshotError.SnapshotBudgetTooSmall(finalSize))
        } else {
            ContextSnapshotResult.Success(snapshot)
        }
    }

    private fun snapshot(
        inputCount: Int,
        items: List<ContextSnapshotItem>,
        omissions: Map<ContextSnapshotOmissionReason, Int>,
        request: ContextSnapshotRequest,
    ): ContextSnapshot = ContextSnapshot(
        asOf = request.now,
        keySelection = request.keySelection,
        allowedPrivacyClassifications = request.allowedPrivacyClassifications,
        appliedBudget = request.budget,
        inputObservationCount = inputCount,
        items = items,
        omissions = omissions.entries
            .filter { it.value > 0 }
            .sortedBy { it.key.wireValue }
            .map { ContextSnapshotOmission(it.key, it.value) },
    )

    private fun List<ContextObservation>.toSnapshotItem(): ContextSnapshotItem {
        val sorted = sortedWith(
            compareBy<ContextObservation> { it.sourceType.value }
                .thenBy { it.sourceReference.value }
                .thenByDescending { it.observedAt.value }
                .thenBy { it.observationId.value },
        )
        val values = sorted.flatMap { observation ->
            when (val claim = observation.claim) {
                is ContextObservationClaim.Known -> listOf(claim.value)
                is ContextObservationClaim.Conflict -> claim.candidates
                is ContextObservationClaim.Unknown -> emptyList()
            }
        }.distinctBy { it.canonicalKey() }.sortedBy { it.canonicalKey() }
        val resolution = when {
            values.size == 1 -> ContextSnapshotResolution.Known(values.single())
            values.size > 1 -> ContextSnapshotResolution.Conflict(values)
            else -> ContextSnapshotResolution.Unknown(
                sorted.mapNotNull { (it.claim as? ContextObservationClaim.Unknown)?.reason }
                    .distinct()
                    .sortedBy(ContextUnknownReason::wireValue),
            )
        }
        return ContextSnapshotItem(
            key = sorted.first().key,
            resolution = resolution,
            evidence = sorted.map { observation ->
                ContextSnapshotEvidence(
                    observationId = observation.observationId,
                    sourceType = observation.sourceType,
                    sourceReference = observation.sourceReference,
                    claim = observation.claim,
                    confidence = observation.confidence,
                    privacyClassification = observation.privacyClassification,
                    observedAt = observation.observedAt,
                    expiresAt = observation.expiresAt,
                )
            },
            effectivePrivacyClassification = sorted.maxBy { it.privacyClassification.ordinal }
                .privacyClassification,
        )
    }

    private fun ContextObservation.signature(): String = ContextObservationCodec.encode(this)

    private fun ContextValue.canonicalKey(): String = "$wireType:$canonicalValue"

    private fun MutableMap<ContextSnapshotOmissionReason, Int>.increment(
        reason: ContextSnapshotOmissionReason,
    ) {
        this[reason] = getOrDefault(reason, 0) + 1
    }

    private data class SourceSlot(
        val key: String,
        val sourceType: String,
        val sourceReference: String,
    )
}
