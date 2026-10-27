package io.redi.robot.discovery.identity

import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.RedactedProviderIdentity
import io.redi.robot.capability.provider.redacted
import io.redi.robot.capability.registry.ProviderRegistrySnapshot
import io.redi.robot.discovery.api.DiscoveryDisplayHint
import io.redi.robot.discovery.api.DiscoveryHostReference
import io.redi.robot.discovery.api.DiscoveryObservation
import io.redi.robot.discovery.api.DiscoveryObservationId
import java.util.Collections

enum class IdentityResolutionStatus {
    MATCHED_KNOWN_PROVIDER,
    NEW_PROVIDER_CANDIDATE,
    AMBIGUOUS,
    CONFLICTING_EVIDENCE,
    INSUFFICIENT_EVIDENCE,
    STALE_EVIDENCE,
    REJECTED,
}

enum class IdentityResolutionReason {
    STRONG_VERIFIED_INSTANCE_EVIDENCE,
    DEFINITION_CANDIDATE_ONLY,
    MULTIPLE_PLAUSIBLE_CANDIDATES,
    STRONG_EVIDENCE_DISAGREES,
    WEAK_OR_UNTARGETED_EVIDENCE_ONLY,
    NO_FRESH_EVIDENCE,
    EMPTY_OBSERVATIONS,
    DUPLICATE_OBSERVATION,
    DUPLICATE_EVIDENCE,
    MISSING_EVALUATION_TIME,
    UNKNOWN_OBSERVATION_REFERENCE,
    PROVENANCE_MISMATCH,
    EVIDENCE_PREDATES_OBSERVATION,
    UNKNOWN_CONTRADICTION_REFERENCE,
    UNKNOWN_REGISTERED_INSTANCE,
    REGISTERED_IDENTITY_MISMATCH,
}

sealed interface IdentityCandidate {
    val supportingEvidenceIds: Set<IdentityEvidenceId>

    fun redacted(): RedactedIdentityCandidate
}

class KnownProviderIdentityCandidate internal constructor(
    val identity: ProviderIdentity,
    evidenceIds: Set<IdentityEvidenceId>,
) : IdentityCandidate {
    override val supportingEvidenceIds: Set<IdentityEvidenceId> = evidenceIds.toSortedEvidenceSet()

    override fun redacted(): RedactedIdentityCandidate = RedactedIdentityCandidate(
        definitionId = identity.definitionId,
        family = identity.family,
        displayHint = DiscoveryDisplayHint.create(identity.displayAlias.value).successOrNull(),
        knownProvider = identity.redacted(),
        supportingEvidenceIds = supportingEvidenceIds,
    )

    override fun toString(): String = redacted().toString()
}

class NewProviderIdentityCandidate internal constructor(
    val definitionId: ProviderDefinitionId,
    val family: ProviderFamily?,
    val displayHint: DiscoveryDisplayHint?,
    evidenceIds: Set<IdentityEvidenceId>,
) : IdentityCandidate {
    override val supportingEvidenceIds: Set<IdentityEvidenceId> = evidenceIds.toSortedEvidenceSet()

    override fun redacted(): RedactedIdentityCandidate = RedactedIdentityCandidate(
        definitionId = definitionId,
        family = family,
        displayHint = displayHint,
        knownProvider = null,
        supportingEvidenceIds = supportingEvidenceIds,
    )

    override fun toString(): String = redacted().toString()
}

data class RedactedIdentityCandidate(
    val definitionId: ProviderDefinitionId,
    val family: ProviderFamily?,
    val displayHint: DiscoveryDisplayHint?,
    val knownProvider: RedactedProviderIdentity?,
    val supportingEvidenceIds: Set<IdentityEvidenceId>,
)

data class RedactedIdentityResolution(
    val status: IdentityResolutionStatus,
    val reason: IdentityResolutionReason,
    val observationIds: Set<DiscoveryObservationId>,
    val candidates: List<RedactedIdentityCandidate>,
    val consideredEvidenceIds: Set<IdentityEvidenceId>,
)

class IdentityResolution internal constructor(
    val status: IdentityResolutionStatus,
    val reason: IdentityResolutionReason,
    observationIds: Set<DiscoveryObservationId>,
    candidates: List<IdentityCandidate>,
    consideredEvidenceIds: Set<IdentityEvidenceId>,
) {
    val observationIds: Set<DiscoveryObservationId> = Collections.unmodifiableSet(
        observationIds.sortedBy { it.value }.toSet(),
    )
    val candidates: List<IdentityCandidate> = Collections.unmodifiableList(candidates.toList())
    val consideredEvidenceIds: Set<IdentityEvidenceId> = consideredEvidenceIds.toSortedEvidenceSet()

    fun redacted(): RedactedIdentityResolution = RedactedIdentityResolution(
        status = status,
        reason = reason,
        observationIds = observationIds,
        candidates = candidates.map(IdentityCandidate::redacted),
        consideredEvidenceIds = consideredEvidenceIds,
    )

    override fun toString(): String = redacted().toString()
}

/** Evaluation times are host-local because H021 timestamps are monotonic, not wall-clock values. */
class IdentityResolutionRequest private constructor(
    observations: List<DiscoveryObservation>,
    evidence: List<IdentityEvidence>,
    evaluationTimes: Map<DiscoveryHostReference, MonotonicTimestampMs>,
) {
    val observations: List<DiscoveryObservation> = Collections.unmodifiableList(observations.toList())
    val evidence: List<IdentityEvidence> = Collections.unmodifiableList(evidence.toList())
    val evaluationTimes: Map<DiscoveryHostReference, MonotonicTimestampMs> = Collections.unmodifiableMap(
        evaluationTimes.toMap(),
    )

    companion object {
        fun create(
            observations: List<DiscoveryObservation>,
            evidence: List<IdentityEvidence>,
            evaluationTimes: Map<DiscoveryHostReference, MonotonicTimestampMs>,
        ): IdentityResolutionRequest = IdentityResolutionRequest(observations, evidence, evaluationTimes)
    }
}

/** Minimal immutable identity-only projection of H015; runtime/manifest state is not identity proof. */
class KnownProviderIdentitySnapshot private constructor(identities: List<ProviderIdentity>) {
    val identities: List<ProviderIdentity> = Collections.unmodifiableList(
        identities.sortedBy { it.instanceId.value },
    )

    companion object {
        fun fromRegistry(registry: ProviderRegistrySnapshot): KnownProviderIdentitySnapshot =
            KnownProviderIdentitySnapshot(registry.entries.map { it.record.identity })

        internal fun fromIdentities(identities: List<ProviderIdentity>): KnownProviderIdentitySnapshot =
            KnownProviderIdentitySnapshot(identities)
    }
}

/**
 * Pure H022 resolver. It reads an immutable H015 snapshot and never registers, merges, trusts,
 * authorizes, connects, selects, or mutates a provider. No confidence values are added or scored.
 */
object IdentityResolver {
    fun resolve(
        request: IdentityResolutionRequest,
        registry: ProviderRegistrySnapshot,
    ): IdentityResolution = resolve(request, KnownProviderIdentitySnapshot.fromRegistry(registry))

    internal fun resolve(
        request: IdentityResolutionRequest,
        knownProviders: KnownProviderIdentitySnapshot,
    ): IdentityResolution {
        val observations = request.observations.sortedBy { it.observationId.value }
        val evidence = request.evidence.sortedBy { it.evidenceId.value }
        val observationIds = observations.map { it.observationId }.toSet()

        if (observations.isEmpty()) return rejected(observationIds, IdentityResolutionReason.EMPTY_OBSERVATIONS)
        if (observationIds.size != observations.size) {
            return rejected(observationIds, IdentityResolutionReason.DUPLICATE_OBSERVATION)
        }
        if (evidence.map { it.evidenceId }.distinct().size != evidence.size) {
            return rejected(observationIds, IdentityResolutionReason.DUPLICATE_EVIDENCE)
        }
        val missingTime = observations.any { it.observingHost !in request.evaluationTimes } ||
            evidence.any { it.provenance.observingHost !in request.evaluationTimes }
        if (missingTime) return rejected(observationIds, IdentityResolutionReason.MISSING_EVALUATION_TIME)

        val observationsById = observations.associateBy { it.observationId }
        val evidenceIds = evidence.map { it.evidenceId }.toSet()
        val knownByInstance = knownProviders.identities.associateBy { it.instanceId }
        for (item in evidence) {
            val referenced = item.observationIds.map { observationsById[it] }
            if (referenced.any { it == null }) {
                return rejected(observationIds, IdentityResolutionReason.UNKNOWN_OBSERVATION_REFERENCE)
            }
            val concrete = referenced.filterNotNull()
            if (
                concrete.any { it.observingHost != item.provenance.observingHost } ||
                concrete.map { it.sourceType }.toSet() != item.provenance.sourceTypes
            ) {
                return rejected(observationIds, IdentityResolutionReason.PROVENANCE_MISMATCH)
            }
            if (concrete.any { item.observedAt.value < it.observedAt.value }) {
                return rejected(observationIds, IdentityResolutionReason.EVIDENCE_PREDATES_OBSERVATION)
            }
            if (item.contradictions.any { it !in evidenceIds }) {
                return rejected(observationIds, IdentityResolutionReason.UNKNOWN_CONTRADICTION_REFERENCE)
            }
            val instanceId = item.target.instanceId
            if (instanceId != null) {
                val registered = knownByInstance[instanceId]
                    ?: return rejected(observationIds, IdentityResolutionReason.UNKNOWN_REGISTERED_INSTANCE)
                if (
                    item.target.definitionId?.let { it != registered.definitionId } == true ||
                    item.target.family?.let { it != registered.family } == true
                ) {
                    return rejected(observationIds, IdentityResolutionReason.REGISTERED_IDENTITY_MISMATCH)
                }
            }
        }

        val freshObservationIds = observations.filter { observation ->
            observation.isFreshAt(request.evaluationTimes.getValue(observation.observingHost))
        }.map { it.observationId }.toSet()
        val freshEvidence = evidence.filter { item ->
            item.isFreshAt(request.evaluationTimes.getValue(item.provenance.observingHost)) &&
                item.observationIds.all { it in freshObservationIds }
        }
        if (freshObservationIds.isEmpty() || (evidence.isNotEmpty() && freshEvidence.isEmpty())) {
            return resolution(
                status = IdentityResolutionStatus.STALE_EVIDENCE,
                reason = IdentityResolutionReason.NO_FRESH_EVIDENCE,
                observationIds = observationIds,
                evidence = evidence,
            )
        }

        val freshById = freshEvidence.associateBy { it.evidenceId }
        val explicitStrongContradiction = freshEvidence.any { item ->
            item.contradictions.any { otherId ->
                val other = freshById[otherId]
                other != null && (item.strength == IdentityEvidenceStrength.STRONG ||
                    other.strength == IdentityEvidenceStrength.STRONG)
            }
        }
        val strongSupports = freshEvidence.filter {
            it.strength == IdentityEvidenceStrength.STRONG &&
                it.disposition == IdentityEvidenceDisposition.SUPPORTS
        }
        val strongExcludes = freshEvidence.filter {
            it.strength == IdentityEvidenceStrength.STRONG &&
                it.disposition == IdentityEvidenceDisposition.EXCLUDES
        }
        val strongSupportInstances = strongSupports.mapNotNull { it.target.instanceId }.toSet()
        val supportExcludeConflict = strongSupports.any { supported ->
            strongExcludes.any { excluded -> targetsOverlap(supported.target, excluded.target, knownByInstance) }
        }
        val strongDefinitionSupports = strongSupports
            .mapNotNull { it.target.definitionId }
            .toSet()
        val strongFamilySupports = strongSupports.mapNotNull { it.target.family }.toSet()
        val soleStrongInstance = strongSupportInstances.singleOrNull()?.let(knownByInstance::getValue)
        val instanceDefinitionConflict = soleStrongInstance != null && strongDefinitionSupports.any {
            it != soleStrongInstance.definitionId
        }
        val instanceFamilyConflict = soleStrongInstance != null && strongFamilySupports.any {
            it != soleStrongInstance.family
        }
        if (
            explicitStrongContradiction || supportExcludeConflict ||
            strongSupportInstances.size > 1 || strongDefinitionSupports.size > 1 ||
            strongFamilySupports.size > 1 || instanceDefinitionConflict || instanceFamilyConflict
        ) {
            return resolution(
                status = IdentityResolutionStatus.CONFLICTING_EVIDENCE,
                reason = IdentityResolutionReason.STRONG_EVIDENCE_DISAGREES,
                observationIds = observationIds,
                evidence = freshEvidence,
                candidates = candidateList(freshEvidence, observations, knownByInstance),
            )
        }

        val supportedInstances = freshEvidence.filter {
            it.disposition == IdentityEvidenceDisposition.SUPPORTS && it.target.instanceId != null
        }.groupBy { checkNotNull(it.target.instanceId) }
        if (supportedInstances.size > 1) {
            return resolution(
                status = IdentityResolutionStatus.AMBIGUOUS,
                reason = IdentityResolutionReason.MULTIPLE_PLAUSIBLE_CANDIDATES,
                observationIds = observationIds,
                evidence = freshEvidence,
                candidates = knownCandidates(supportedInstances, knownByInstance),
            )
        }
        if (supportedInstances.size == 1) {
            val (instanceId, supporting) = supportedInstances.entries.single()
            val hasStrongSupport = supporting.any { it.strength == IdentityEvidenceStrength.STRONG }
            if (hasStrongSupport) {
                return resolution(
                    status = IdentityResolutionStatus.MATCHED_KNOWN_PROVIDER,
                    reason = IdentityResolutionReason.STRONG_VERIFIED_INSTANCE_EVIDENCE,
                    observationIds = observationIds,
                    evidence = freshEvidence,
                    candidates = listOf(
                        KnownProviderIdentityCandidate(
                            identity = knownByInstance.getValue(instanceId),
                            evidenceIds = supporting.map { it.evidenceId }.toSet(),
                        ),
                    ),
                )
            }
        }

        val supportedDefinitions = freshEvidence.filter {
            it.disposition == IdentityEvidenceDisposition.SUPPORTS &&
                it.target.instanceId == null && it.target.definitionId != null
        }.groupBy { checkNotNull(it.target.definitionId) }
        if (supportedDefinitions.size > 1) {
            return resolution(
                status = IdentityResolutionStatus.AMBIGUOUS,
                reason = IdentityResolutionReason.MULTIPLE_PLAUSIBLE_CANDIDATES,
                observationIds = observationIds,
                evidence = freshEvidence,
                candidates = newCandidates(supportedDefinitions, observations),
            )
        }
        if (supportedDefinitions.size == 1) {
            val supporting = supportedDefinitions.values.single()
            if (supporting.any { it.strength != IdentityEvidenceStrength.WEAK }) {
                return resolution(
                    status = IdentityResolutionStatus.NEW_PROVIDER_CANDIDATE,
                    reason = IdentityResolutionReason.DEFINITION_CANDIDATE_ONLY,
                    observationIds = observationIds,
                    evidence = freshEvidence,
                    candidates = newCandidates(supportedDefinitions, observations),
                )
            }
        }

        return resolution(
            status = IdentityResolutionStatus.INSUFFICIENT_EVIDENCE,
            reason = IdentityResolutionReason.WEAK_OR_UNTARGETED_EVIDENCE_ONLY,
            observationIds = observationIds,
            evidence = freshEvidence,
            candidates = candidateList(freshEvidence, observations, knownByInstance),
        )
    }

    private fun candidateList(
        evidence: List<IdentityEvidence>,
        observations: List<DiscoveryObservation>,
        known: Map<ProviderInstanceId, ProviderIdentity>,
    ): List<IdentityCandidate> {
        val instances = evidence.filter {
            it.disposition == IdentityEvidenceDisposition.SUPPORTS && it.target.instanceId != null
        }.groupBy { checkNotNull(it.target.instanceId) }
        val definitions = evidence.filter {
            it.disposition == IdentityEvidenceDisposition.SUPPORTS &&
                it.target.instanceId == null && it.target.definitionId != null
        }.groupBy { checkNotNull(it.target.definitionId) }
        return knownCandidates(instances, known) + newCandidates(definitions, observations)
    }

    private fun knownCandidates(
        grouped: Map<ProviderInstanceId, List<IdentityEvidence>>,
        known: Map<ProviderInstanceId, ProviderIdentity>,
    ): List<IdentityCandidate> = grouped.entries.sortedBy { it.key.value }.map { (instanceId, evidence) ->
        KnownProviderIdentityCandidate(
            identity = known.getValue(instanceId),
            evidenceIds = evidence.map { it.evidenceId }.toSet(),
        )
    }

    private fun newCandidates(
        grouped: Map<ProviderDefinitionId, List<IdentityEvidence>>,
        observations: List<DiscoveryObservation>,
    ): List<IdentityCandidate> = grouped.entries.sortedBy { it.key.value }.map { (definitionId, evidence) ->
        NewProviderIdentityCandidate(
            definitionId = definitionId,
            family = evidence.mapNotNull { it.target.family }.distinct().singleOrNull(),
            displayHint = observations.mapNotNull { it.displayHint }.distinctBy { it.value }.singleOrNull(),
            evidenceIds = evidence.map { it.evidenceId }.toSet(),
        )
    }

    private fun targetsOverlap(
        first: IdentityEvidenceTarget,
        second: IdentityEvidenceTarget,
        known: Map<ProviderInstanceId, ProviderIdentity>,
    ): Boolean {
        if (first.instanceId != null && second.instanceId != null) return first.instanceId == second.instanceId
        val firstIdentity = first.instanceId?.let(known::getValue)
        val secondIdentity = second.instanceId?.let(known::getValue)
        val firstDefinition = first.definitionId ?: firstIdentity?.definitionId
        val secondDefinition = second.definitionId ?: secondIdentity?.definitionId
        val firstFamily = first.family ?: firstIdentity?.family
        val secondFamily = second.family ?: secondIdentity?.family
        return when {
            firstDefinition != null && secondDefinition != null -> firstDefinition == secondDefinition
            firstFamily != null && secondFamily != null -> firstFamily == secondFamily
            else -> false
        }
    }

    private fun rejected(
        observationIds: Set<DiscoveryObservationId>,
        reason: IdentityResolutionReason,
    ): IdentityResolution = resolution(
        status = IdentityResolutionStatus.REJECTED,
        reason = reason,
        observationIds = observationIds,
    )

    private fun resolution(
        status: IdentityResolutionStatus,
        reason: IdentityResolutionReason,
        observationIds: Set<DiscoveryObservationId>,
        evidence: List<IdentityEvidence> = emptyList(),
        candidates: List<IdentityCandidate> = emptyList(),
    ): IdentityResolution = IdentityResolution(
        status = status,
        reason = reason,
        observationIds = observationIds,
        candidates = candidates,
        consideredEvidenceIds = evidence.map { it.evidenceId }.toSet(),
    )
}

private fun Set<IdentityEvidenceId>.toSortedEvidenceSet(): Set<IdentityEvidenceId> =
    Collections.unmodifiableSet(sortedBy { it.value }.toSet())

private fun <T> io.redi.robot.discovery.api.DiscoveryObservationResult<T>.successOrNull(): T? = when (this) {
    is io.redi.robot.discovery.api.DiscoveryObservationResult.Success -> value
    is io.redi.robot.discovery.api.DiscoveryObservationResult.Failure -> null
}
