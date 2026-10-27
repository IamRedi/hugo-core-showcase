package io.redi.robot.provider.runtime

import io.redi.robot.adapter.compatibility.CompatibilityReportStatus
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleEvent
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleFailure
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleReducer
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleSnapshot
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleState
import io.redi.robot.discovery.identity.IdentityResolution
import io.redi.robot.discovery.identity.IdentityResolutionStatus
import io.redi.robot.manifest.resolution.ManifestResolution
import io.redi.robot.manifest.resolution.ManifestResolutionStatus

/**
 * H027 preparation coordinator. It invokes canonical stage ports but never trusts, authorizes,
 * connects, registers, selects an active body, or executes a capability.
 */
class ProviderLifecycleOrchestrator(
    private val identityStage: ProviderIdentityResolutionStage = CanonicalProviderIdentityResolutionStage,
    private val manifestStage: ProviderManifestResolutionStage = CanonicalProviderManifestResolutionStage,
    private val compatibilityStage: ProviderCompatibilityStage = CanonicalProviderCompatibilityStage,
) {
    private var current: ProviderOrchestrationSnapshot = ProviderOrchestrationSnapshot.initial()
    private var activeRequest: ProviderOrchestrationRequest? = null

    @Synchronized
    fun snapshot(): ProviderOrchestrationSnapshot = current

    @Synchronized
    fun start(request: ProviderOrchestrationRequest): ProviderOrchestrationUpdate {
        val previousCorrelation = current.correlation
        if (current.phase != ProviderOrchestrationPhase.INACTIVE && !current.phase.isTerminal()) {
            return ignored(ProviderOrchestrationIgnoredReason.ACTIVE_RUN)
        }
        if (previousCorrelation != null) {
            when {
                request.correlation.generation.value < previousCorrelation.generation.value ->
                    return ignored(ProviderOrchestrationIgnoredReason.STALE_GENERATION)
                request.correlation.generation == previousCorrelation.generation ->
                    return ignored(ProviderOrchestrationIgnoredReason.REUSED_GENERATION)
                request.correlation.runId == previousCorrelation.runId ->
                    return ignored(ProviderOrchestrationIgnoredReason.REUSED_RUN_ID)
            }
        }
        val lifecycle = ProviderLifecycleReducer.reduce(
            ProviderLifecycleSnapshot.initial(),
            ProviderLifecycleEvent.Discovered(request.correlation.generation),
        ).snapshot
        val from = current.phase
        activeRequest = request
        current = ProviderOrchestrationSnapshot(
            correlation = request.correlation,
            phase = ProviderOrchestrationPhase.OBSERVED,
            stopReason = null,
            observationIds = request.observationIds,
            providerLifecycle = lifecycle,
            identityResolution = null,
            manifestResolution = null,
            manifestCorrelation = null,
            compatibility = null,
            preparation = null,
            audit = listOf(
                ProviderOrchestrationAuditEntry(
                    sequence = 1,
                    event = ProviderOrchestrationAuditEvent.OBSERVATION_ACCEPTED,
                    phase = ProviderOrchestrationPhase.OBSERVED,
                    stage = null,
                    outcome = "observation_accepted",
                ),
            ),
        )
        return transitioned(from)
    }

    @Synchronized
    fun beginIdentity(correlation: ProviderOrchestrationCorrelation): ProviderOrchestrationUpdate {
        correlationFailure(correlation)?.let { return it }
        return when (current.phase) {
            ProviderOrchestrationPhase.OBSERVED -> transition(
                ProviderOrchestrationPhase.RESOLVING_IDENTITY,
                audit = listOf(audit(ProviderOrchestrationAuditEvent.IDENTITY_REQUESTED, ProviderOrchestrationStage.IDENTITY)),
            )
            ProviderOrchestrationPhase.RESOLVING_IDENTITY -> ignored(ProviderOrchestrationIgnoredReason.DUPLICATE_EVENT)
            else -> ignoredForPhase(ProviderOrchestrationStage.IDENTITY)
        }
    }

    fun runIdentity(correlation: ProviderOrchestrationCorrelation): ProviderOrchestrationUpdate {
        val request = synchronized(this) {
            correlationFailure(correlation)?.let { return it }
            if (current.phase != ProviderOrchestrationPhase.RESOLVING_IDENTITY) {
                return ignoredForPhase(ProviderOrchestrationStage.IDENTITY)
            }
            checkNotNull(activeRequest)
        }
        val result = try {
            identityStage.resolve(request.identityRequest, request.providerRegistrySnapshot)
        } catch (_: Exception) {
            return dependencyFailed(correlation, ProviderOrchestrationStage.IDENTITY)
        }
        return acceptIdentity(correlation, result)
    }

    @Synchronized
    fun acceptIdentity(
        correlation: ProviderOrchestrationCorrelation,
        result: IdentityResolution,
    ): ProviderOrchestrationUpdate {
        correlationFailure(correlation)?.let { return it }
        if (current.phase != ProviderOrchestrationPhase.RESOLVING_IDENTITY) {
            return ignoredForPhase(ProviderOrchestrationStage.IDENTITY)
        }
        val resultAudit = audit(
            ProviderOrchestrationAuditEvent.IDENTITY_RESULT_ACCEPTED,
            ProviderOrchestrationStage.IDENTITY,
            result.status.name,
        )
        return when (result.status) {
            IdentityResolutionStatus.MATCHED_KNOWN_PROVIDER,
            IdentityResolutionStatus.NEW_PROVIDER_CANDIDATE,
            -> {
                val lifecycle = ProviderLifecycleReducer.reduce(
                    current.providerLifecycle,
                    ProviderLifecycleEvent.IdentityResolved(correlation.generation),
                ).snapshot
                transition(
                    phase = ProviderOrchestrationPhase.IDENTITY_RESOLVED,
                    lifecycle = lifecycle,
                    identity = result,
                    audit = listOf(resultAudit),
                )
            }
            IdentityResolutionStatus.AMBIGUOUS -> stop(
                ProviderOrchestrationPhase.AMBIGUOUS,
                ProviderOrchestrationStopReason.AMBIGUOUS_IDENTITY,
                identity = result,
                audit = resultAudit,
            )
            IdentityResolutionStatus.CONFLICTING_EVIDENCE -> stop(
                ProviderOrchestrationPhase.BLOCKED,
                ProviderOrchestrationStopReason.CONFLICTING_IDENTITY_EVIDENCE,
                identity = result,
                audit = resultAudit,
            )
            IdentityResolutionStatus.STALE_EVIDENCE -> stop(
                ProviderOrchestrationPhase.BLOCKED,
                ProviderOrchestrationStopReason.STALE_OBSERVATION,
                identity = result,
                audit = resultAudit,
            )
            IdentityResolutionStatus.INSUFFICIENT_EVIDENCE -> stop(
                ProviderOrchestrationPhase.BLOCKED,
                ProviderOrchestrationStopReason.INSUFFICIENT_IDENTITY_EVIDENCE,
                identity = result,
                audit = resultAudit,
            )
            IdentityResolutionStatus.REJECTED -> {
                val lifecycle = ProviderLifecycleReducer.reduce(
                    current.providerLifecycle,
                    ProviderLifecycleEvent.Failed(correlation.generation, ProviderLifecycleFailure.IDENTITY_FAILED),
                ).snapshot
                stop(
                    ProviderOrchestrationPhase.FAILED,
                    ProviderOrchestrationStopReason.IDENTITY_REJECTED,
                    identity = result,
                    lifecycle = lifecycle,
                    audit = resultAudit,
                )
            }
        }
    }

    @Synchronized
    fun beginManifest(correlation: ProviderOrchestrationCorrelation): ProviderOrchestrationUpdate {
        correlationFailure(correlation)?.let { return it }
        return when (current.phase) {
            ProviderOrchestrationPhase.IDENTITY_RESOLVED -> transition(
                ProviderOrchestrationPhase.RESOLVING_MANIFEST,
                audit = listOf(audit(ProviderOrchestrationAuditEvent.MANIFEST_REQUESTED, ProviderOrchestrationStage.MANIFEST)),
            )
            ProviderOrchestrationPhase.RESOLVING_MANIFEST -> ignored(ProviderOrchestrationIgnoredReason.DUPLICATE_EVENT)
            else -> ignoredForPhase(ProviderOrchestrationStage.MANIFEST)
        }
    }

    fun runManifest(correlation: ProviderOrchestrationCorrelation): ProviderOrchestrationUpdate {
        val captured = synchronized(this) {
            correlationFailure(correlation)?.let { return it }
            if (current.phase != ProviderOrchestrationPhase.RESOLVING_MANIFEST) {
                return ignoredForPhase(ProviderOrchestrationStage.MANIFEST)
            }
            checkNotNull(activeRequest) to checkNotNull(current.identityResolution)
        }
        val result = try {
            manifestStage.resolve(captured.first, captured.second)
        } catch (_: Exception) {
            return dependencyFailed(correlation, ProviderOrchestrationStage.MANIFEST)
        }
        return acceptManifest(correlation, result)
    }

    @Synchronized
    fun acceptManifest(
        correlation: ProviderOrchestrationCorrelation,
        result: ManifestResolution,
    ): ProviderOrchestrationUpdate {
        correlationFailure(correlation)?.let { return it }
        if (current.phase != ProviderOrchestrationPhase.RESOLVING_MANIFEST) {
            return ignoredForPhase(ProviderOrchestrationStage.MANIFEST)
        }
        val resultAudit = audit(
            ProviderOrchestrationAuditEvent.MANIFEST_RESULT_ACCEPTED,
            ProviderOrchestrationStage.MANIFEST,
            result.status.name,
        )
        return when (result.status) {
            ManifestResolutionStatus.RESOLVED -> {
                val resolved = checkNotNull(result.resolved)
                transition(
                    phase = ProviderOrchestrationPhase.MANIFEST_RESOLVED,
                    manifest = result,
                    manifestCorrelation = ResolvedManifestCorrelation(
                        resolved.candidateId,
                        resolved.candidateProvenance,
                        resolved.common.digest,
                        resolved.specialized.digest,
                    ),
                    audit = listOf(resultAudit),
                )
            }
            ManifestResolutionStatus.AMBIGUOUS -> stop(
                ProviderOrchestrationPhase.AMBIGUOUS,
                ProviderOrchestrationStopReason.AMBIGUOUS_MANIFEST,
                manifest = result,
                audit = resultAudit,
            )
            ManifestResolutionStatus.CONFLICTING_MANIFESTS -> stop(
                ProviderOrchestrationPhase.BLOCKED,
                ProviderOrchestrationStopReason.CONFLICTING_MANIFESTS,
                manifest = result,
                audit = resultAudit,
            )
            ManifestResolutionStatus.NOT_FOUND -> stop(
                ProviderOrchestrationPhase.BLOCKED,
                ProviderOrchestrationStopReason.MANIFEST_NOT_FOUND,
                manifest = result,
                audit = resultAudit,
            )
            ManifestResolutionStatus.IDENTITY_MISMATCH,
            ManifestResolutionStatus.INCOMPATIBLE_PROVIDER_FAMILY,
            -> stop(
                ProviderOrchestrationPhase.BLOCKED,
                ProviderOrchestrationStopReason.MANIFEST_IDENTITY_OR_FAMILY_MISMATCH,
                manifest = result,
                audit = resultAudit,
            )
            ManifestResolutionStatus.DIGEST_MISMATCH,
            ManifestResolutionStatus.UNSUPPORTED_SCHEMA,
            -> stop(
                ProviderOrchestrationPhase.BLOCKED,
                ProviderOrchestrationStopReason.MANIFEST_INTEGRITY_OR_SCHEMA_INVALID,
                manifest = result,
                audit = resultAudit,
            )
            ManifestResolutionStatus.INCOMPATIBLE_ADAPTER -> stop(
                ProviderOrchestrationPhase.BLOCKED,
                ProviderOrchestrationStopReason.INCOMPATIBLE_ADAPTER_OR_RUNTIME,
                manifest = result,
                audit = resultAudit,
            )
            ManifestResolutionStatus.REJECTED -> stop(
                ProviderOrchestrationPhase.FAILED,
                ProviderOrchestrationStopReason.MANIFEST_REJECTED,
                manifest = result,
                audit = resultAudit,
            )
        }
    }

    @Synchronized
    fun beginCompatibility(correlation: ProviderOrchestrationCorrelation): ProviderOrchestrationUpdate {
        correlationFailure(correlation)?.let { return it }
        return when (current.phase) {
            ProviderOrchestrationPhase.MANIFEST_RESOLVED -> transition(
                ProviderOrchestrationPhase.CHECKING_COMPATIBILITY,
                audit = listOf(
                    audit(
                        ProviderOrchestrationAuditEvent.COMPATIBILITY_REQUESTED,
                        ProviderOrchestrationStage.COMPATIBILITY,
                    ),
                ),
            )
            ProviderOrchestrationPhase.CHECKING_COMPATIBILITY ->
                ignored(ProviderOrchestrationIgnoredReason.DUPLICATE_EVENT)
            else -> ignoredForPhase(ProviderOrchestrationStage.COMPATIBILITY)
        }
    }

    fun runCompatibility(correlation: ProviderOrchestrationCorrelation): ProviderOrchestrationUpdate {
        val captured = synchronized(this) {
            correlationFailure(correlation)?.let { return it }
            if (current.phase != ProviderOrchestrationPhase.CHECKING_COMPATIBILITY) {
                return ignoredForPhase(ProviderOrchestrationStage.COMPATIBILITY)
            }
            checkNotNull(activeRequest) to checkNotNull(current.manifestResolution?.resolved)
        }
        val result = try {
            compatibilityStage.evaluate(captured.first, captured.second)
        } catch (_: Exception) {
            return dependencyFailed(correlation, ProviderOrchestrationStage.COMPATIBILITY)
        }
        return acceptCompatibility(correlation, result)
    }

    @Synchronized
    fun acceptCompatibility(
        correlation: ProviderOrchestrationCorrelation,
        result: ProviderCompatibilityEvaluation,
    ): ProviderOrchestrationUpdate {
        correlationFailure(correlation)?.let { return it }
        if (current.phase != ProviderOrchestrationPhase.CHECKING_COMPATIBILITY) {
            return ignoredForPhase(ProviderOrchestrationStage.COMPATIBILITY)
        }
        val requiredAdapter = checkNotNull(current.manifestResolution?.resolved)
            .common.manifest.adapter.adapterId.value
        val relevant = result.candidates.candidates.filter {
            it.record.key.adapterId.wireValue == requiredAdapter
        }
        val satisfying = relevant.filter {
            it.report.status == CompatibilityReportStatus.COMPATIBLE ||
                it.report.status == CompatibilityReportStatus.PARTIALLY_COMPATIBLE
        }
        val resultAudit = audit(
            ProviderOrchestrationAuditEvent.COMPATIBILITY_RESULT_ACCEPTED,
            ProviderOrchestrationStage.COMPATIBILITY,
            when {
                relevant.isEmpty() -> "MISSING_ADAPTER"
                satisfying.size > 1 -> "MULTIPLE_COMPATIBLE_ADAPTERS"
                satisfying.size == 1 -> satisfying.single().report.status.name
                relevant.any { it.report.status == CompatibilityReportStatus.UNKNOWN } -> "UNKNOWN"
                else -> "INCOMPATIBLE"
            },
        )
        return when {
            relevant.isEmpty() -> stop(
                ProviderOrchestrationPhase.NEEDS_ADAPTER,
                ProviderOrchestrationStopReason.MISSING_ADAPTER,
                compatibility = result,
                audit = resultAudit,
            )
            satisfying.size > 1 -> stop(
                ProviderOrchestrationPhase.AMBIGUOUS,
                ProviderOrchestrationStopReason.MULTIPLE_COMPATIBLE_ADAPTERS,
                compatibility = result,
                audit = resultAudit,
            )
            satisfying.size == 1 -> {
                val candidate = satisfying.single()
                transition(
                    phase = ProviderOrchestrationPhase.PREPARED,
                    compatibility = result,
                    preparation = ProviderPreparationEvidence(candidate, candidate.report),
                    audit = listOf(
                        resultAudit,
                        audit(
                            ProviderOrchestrationAuditEvent.PREPARATION_COMPLETED,
                            ProviderOrchestrationStage.COMPATIBILITY,
                            candidate.report.status.name,
                        ),
                    ),
                )
            }
            relevant.any { it.report.status == CompatibilityReportStatus.UNKNOWN } -> stop(
                ProviderOrchestrationPhase.BLOCKED,
                ProviderOrchestrationStopReason.COMPATIBILITY_UNKNOWN,
                compatibility = result,
                audit = resultAudit,
            )
            else -> stop(
                ProviderOrchestrationPhase.BLOCKED,
                ProviderOrchestrationStopReason.INCOMPATIBLE_ADAPTER_OR_RUNTIME,
                compatibility = result,
                audit = resultAudit,
            )
        }
    }

    @Synchronized
    fun cancel(correlation: ProviderOrchestrationCorrelation): ProviderOrchestrationUpdate {
        correlationFailure(correlation)?.let { return it }
        if (current.phase == ProviderOrchestrationPhase.CANCELLED) {
            return ignored(ProviderOrchestrationIgnoredReason.DUPLICATE_EVENT)
        }
        if (current.phase.isTerminal()) {
            return ignored(ProviderOrchestrationIgnoredReason.TERMINAL_STATE)
        }
        var lifecycle = ProviderLifecycleReducer.reduce(
            current.providerLifecycle,
            ProviderLifecycleEvent.CancellationRequested(correlation.generation),
        ).snapshot
        if (lifecycle.state is ProviderLifecycleState.Cancelling) {
            lifecycle = ProviderLifecycleReducer.reduce(
                lifecycle,
                ProviderLifecycleEvent.CancellationCompleted(correlation.generation),
            ).snapshot
        }
        return transition(
            phase = ProviderOrchestrationPhase.CANCELLED,
            stopReason = ProviderOrchestrationStopReason.CANCELLED,
            lifecycle = lifecycle,
            audit = listOf(
                audit(ProviderOrchestrationAuditEvent.CANCELLED, null, "cancelled"),
            ),
        )
    }

    @Synchronized
    private fun dependencyFailed(
        correlation: ProviderOrchestrationCorrelation,
        stage: ProviderOrchestrationStage,
    ): ProviderOrchestrationUpdate {
        correlationFailure(correlation)?.let { return it }
        if (current.phase.isTerminal()) return ignored(ProviderOrchestrationIgnoredReason.TERMINAL_STATE)
        val lifecycle = if (stage == ProviderOrchestrationStage.IDENTITY) {
            ProviderLifecycleReducer.reduce(
                current.providerLifecycle,
                ProviderLifecycleEvent.Failed(correlation.generation, ProviderLifecycleFailure.IDENTITY_FAILED),
            ).snapshot
        } else {
            current.providerLifecycle
        }
        return transition(
            phase = ProviderOrchestrationPhase.FAILED,
            stopReason = ProviderOrchestrationStopReason.DEPENDENCY_FAILURE,
            lifecycle = lifecycle,
            audit = listOf(
                audit(ProviderOrchestrationAuditEvent.DEPENDENCY_FAILED, stage, "dependency_failure"),
            ),
        )
    }

    private fun stop(
        phase: ProviderOrchestrationPhase,
        reason: ProviderOrchestrationStopReason,
        identity: IdentityResolution? = current.identityResolution,
        manifest: ManifestResolution? = current.manifestResolution,
        compatibility: ProviderCompatibilityEvaluation? = current.compatibility,
        lifecycle: ProviderLifecycleSnapshot = current.providerLifecycle,
        audit: ProviderOrchestrationAuditEntry,
    ): ProviderOrchestrationUpdate = transition(
        phase = phase,
        stopReason = reason,
        lifecycle = lifecycle,
        identity = identity,
        manifest = manifest,
        compatibility = compatibility,
        audit = listOf(
            audit,
            audit(ProviderOrchestrationAuditEvent.STOPPED, audit.stage, reason.name),
        ),
    )

    private fun transition(
        phase: ProviderOrchestrationPhase,
        stopReason: ProviderOrchestrationStopReason? = current.stopReason,
        lifecycle: ProviderLifecycleSnapshot = current.providerLifecycle,
        identity: IdentityResolution? = current.identityResolution,
        manifest: ManifestResolution? = current.manifestResolution,
        manifestCorrelation: ResolvedManifestCorrelation? = current.manifestCorrelation,
        compatibility: ProviderCompatibilityEvaluation? = current.compatibility,
        preparation: ProviderPreparationEvidence? = current.preparation,
        audit: List<ProviderOrchestrationAuditEntry>,
    ): ProviderOrchestrationUpdate {
        val from = current.phase
        val existingAudit = current.audit
        val normalizedAudit = audit.mapIndexed { index, entry ->
            entry.copy(sequence = existingAudit.size + index + 1, phase = phase)
        }
        current = ProviderOrchestrationSnapshot(
            correlation = current.correlation,
            phase = phase,
            stopReason = stopReason,
            observationIds = current.observationIds,
            providerLifecycle = lifecycle,
            identityResolution = identity,
            manifestResolution = manifest,
            manifestCorrelation = manifestCorrelation,
            compatibility = compatibility,
            preparation = preparation,
            audit = existingAudit + normalizedAudit,
        )
        if (phase.isTerminal()) activeRequest = null
        return transitioned(from)
    }

    private fun audit(
        event: ProviderOrchestrationAuditEvent,
        stage: ProviderOrchestrationStage?,
        outcome: String = "requested",
    ): ProviderOrchestrationAuditEntry = ProviderOrchestrationAuditEntry(
        sequence = 0,
        event = event,
        phase = current.phase,
        stage = stage,
        outcome = outcome,
    )

    private fun correlationFailure(correlation: ProviderOrchestrationCorrelation): ProviderOrchestrationUpdate? {
        val active = current.correlation ?: return ignored(ProviderOrchestrationIgnoredReason.NO_ACTIVE_RUN)
        return when {
            correlation.generation.value < active.generation.value ->
                ignored(ProviderOrchestrationIgnoredReason.STALE_GENERATION)
            correlation.generation.value > active.generation.value ->
                ignored(ProviderOrchestrationIgnoredReason.FUTURE_GENERATION)
            correlation.runId != active.runId -> ignored(ProviderOrchestrationIgnoredReason.RUN_ID_MISMATCH)
            else -> null
        }
    }

    private fun ignoredForPhase(stage: ProviderOrchestrationStage): ProviderOrchestrationUpdate = when {
        current.phase.isTerminal() -> ignored(ProviderOrchestrationIgnoredReason.TERMINAL_STATE)
        stage == ProviderOrchestrationStage.IDENTITY && current.identityResolution != null ->
            ignored(ProviderOrchestrationIgnoredReason.DUPLICATE_EVENT)
        stage == ProviderOrchestrationStage.MANIFEST && current.manifestResolution != null ->
            ignored(ProviderOrchestrationIgnoredReason.DUPLICATE_EVENT)
        stage == ProviderOrchestrationStage.COMPATIBILITY && current.compatibility != null ->
            ignored(ProviderOrchestrationIgnoredReason.DUPLICATE_EVENT)
        else -> ignored(ProviderOrchestrationIgnoredReason.INVALID_PHASE)
    }

    private fun transitioned(from: ProviderOrchestrationPhase): ProviderOrchestrationUpdate =
        ProviderOrchestrationUpdate(
            current,
            ProviderOrchestrationDisposition.Transitioned(from, current.phase),
        )

    private fun ignored(reason: ProviderOrchestrationIgnoredReason): ProviderOrchestrationUpdate =
        ProviderOrchestrationUpdate(current, ProviderOrchestrationDisposition.Ignored(reason))
}
