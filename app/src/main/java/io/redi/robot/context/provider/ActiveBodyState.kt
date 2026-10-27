package io.redi.robot.context.provider

import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.trust.domain.HugoHostRole
import java.util.Collections

/**
 * Pure, expiring embodiment context. It does not contain HugoIdentity, trust, grants, safety
 * approval, connection details, or execution authority.
 */
class ActiveBodyState private constructor(
    val generation: EmbodimentGeneration?,
    val host: EmbodimentHost,
    bodies: List<BodyEmbodimentSnapshot>,
    val activeSelection: ActiveBodySelection?,
) {
    val bodies: List<BodyEmbodimentSnapshot> = Collections.unmodifiableList(bodies.toList())

    fun body(reference: BodyProviderReference): BodyEmbodimentSnapshot? = bodies.firstOrNull {
        it.body == reference
    }

    companion object {
        fun initial(host: EmbodimentHost): ActiveBodyState = ActiveBodyState(
            generation = null,
            host = host,
            bodies = emptyList(),
            activeSelection = null,
        )

        internal fun create(
            generation: EmbodimentGeneration,
            host: EmbodimentHost,
            bodies: Collection<BodyEmbodimentSnapshot>,
            activeSelection: ActiveBodySelection?,
        ): ActiveBodyState = ActiveBodyState(
            generation = generation,
            host = host,
            bodies = bodies.sortedBy { it.body.identity.instanceId.value },
            activeSelection = activeSelection,
        )
    }
}

@JvmInline
value class EmbodimentGeneration private constructor(val value: Long) {
    companion object {
        fun create(value: Long): EmbodimentResult<EmbodimentGeneration> = if (value < 0L) {
            embodimentFailure(EmbodimentError.InvalidGeneration)
        } else {
            EmbodimentResult.Success(EmbodimentGeneration(value))
        }
    }
}

/** The H004 body provider reference; registry ownership remains with H015. */
@JvmInline
value class BodyProviderReference private constructor(val identity: ProviderIdentity) {
    companion object {
        fun create(identity: ProviderIdentity): EmbodimentResult<BodyProviderReference> = if (
            identity.family != ProviderFamily.BODY_ADAPTER
        ) {
            embodimentFailure(EmbodimentError.NotBodyProvider)
        } else {
            EmbodimentResult.Success(BodyProviderReference(identity))
        }
    }
}

/** Opaque host association, distinct from HUGO identity and from authorization authority. */
data class EmbodimentHost(
    val reference: EmbodimentHostReference,
    val role: HugoHostRole,
)

@JvmInline
value class EmbodimentHostReference private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._/-][a-z0-9]+){0,7}$")

        fun parse(raw: String): EmbodimentResult<EmbodimentHostReference> = if (
            raw.length !in 3..96 || !pattern.matches(raw)
        ) {
            embodimentFailure(EmbodimentError.InvalidHostReference)
        } else {
            EmbodimentResult.Success(EmbodimentHostReference(raw))
        }
    }
}

enum class BodyAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

enum class MountingState {
    UNKNOWN,
    MOUNTED,
    DETACHED,
}

/** Facts are intentionally separate: transport/proximity/power alone never imply [MOUNTED]. */
class BodyEmbodimentSnapshot private constructor(
    val body: BodyProviderReference,
    val availability: BodyAvailability,
    val discovered: Boolean,
    val nearby: Boolean,
    val connected: Boolean,
    val remotelyControlled: Boolean,
    val mounting: MountingState,
    evidence: List<EmbodimentEvidence>,
) {
    val evidence: List<EmbodimentEvidence> = Collections.unmodifiableList(evidence.toList())

    companion object {
        internal fun create(
            body: BodyProviderReference,
            availability: BodyAvailability,
            discovered: Boolean,
            nearby: Boolean,
            connected: Boolean,
            remotelyControlled: Boolean,
            mounting: MountingState,
            evidence: List<EmbodimentEvidence>,
        ): BodyEmbodimentSnapshot = BodyEmbodimentSnapshot(
            body,
            availability,
            discovered,
            nearby,
            connected,
            remotelyControlled,
            mounting,
            evidence.sortedBy { it.id.value },
        )
    }
}

enum class EmbodimentEvidenceKind {
    DISCOVERY_OBSERVED,
    PROXIMITY_OBSERVED,
    TRANSPORT_CONNECTED,
    REMOTE_CONTROL_OBSERVED,
    POWER_RELATION_OBSERVED,
    MOUNTING_VERIFIED,
    DETACH_VERIFIED,
    PROVIDER_UNAVAILABLE,
}

enum class EmbodimentEvidenceProvenance {
    DECLARED,
    RUNTIME_OBSERVED,
    AUTOMATED_TESTED,
    HARDWARE_VERIFIED,
}

@JvmInline
value class EmbodimentEvidenceId private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+){0,7}$")

        fun parse(raw: String): EmbodimentResult<EmbodimentEvidenceId> = if (!pattern.matches(raw)) {
            embodimentFailure(EmbodimentError.InvalidEvidenceId)
        } else {
            EmbodimentResult.Success(EmbodimentEvidenceId(raw))
        }
    }
}

@JvmInline
value class EmbodimentConfidence private constructor(val permille: Int) {
    companion object {
        fun create(permille: Int): EmbodimentResult<EmbodimentConfidence> = if (permille !in 0..1000) {
            embodimentFailure(EmbodimentError.InvalidConfidence)
        } else {
            EmbodimentResult.Success(EmbodimentConfidence(permille))
        }
    }
}

class EmbodimentEvidence private constructor(
    val id: EmbodimentEvidenceId,
    val kind: EmbodimentEvidenceKind,
    val provenance: EmbodimentEvidenceProvenance,
    val confidence: EmbodimentConfidence,
    val observedAt: MonotonicTimestampMs,
    val expiresAt: MonotonicTimestampMs,
) {
    fun isFreshAt(now: MonotonicTimestampMs): Boolean = now.value < expiresAt.value

    companion object {
        fun create(
            id: EmbodimentEvidenceId,
            kind: EmbodimentEvidenceKind,
            provenance: EmbodimentEvidenceProvenance,
            confidence: EmbodimentConfidence,
            observedAt: MonotonicTimestampMs,
            expiresAt: MonotonicTimestampMs,
        ): EmbodimentResult<EmbodimentEvidence> = if (expiresAt.value <= observedAt.value) {
            embodimentFailure(EmbodimentError.InvalidEvidenceFreshness)
        } else {
            EmbodimentResult.Success(
                EmbodimentEvidence(id, kind, provenance, confidence, observedAt, expiresAt),
            )
        }
    }
}

/** H016 policy only; real adapters must supply verified evidence without inventing sensor meaning. */
class MountingEvidencePolicy private constructor(val minimumConfidence: EmbodimentConfidence) {
    fun establishesMounted(evidence: Collection<EmbodimentEvidence>, now: MonotonicTimestampMs): Boolean =
        evidence.any {
            it.kind == EmbodimentEvidenceKind.MOUNTING_VERIFIED &&
                it.provenance == EmbodimentEvidenceProvenance.HARDWARE_VERIFIED &&
                it.confidence.permille >= minimumConfidence.permille &&
                it.isFreshAt(now)
        }

    companion object {
        fun create(minimumConfidence: EmbodimentConfidence): EmbodimentResult<MountingEvidencePolicy> =
            EmbodimentResult.Success(MountingEvidencePolicy(minimumConfidence))
    }
}

enum class ActiveBodyActivationMode {
    REMOTE,
    MOUNTED,
}

@JvmInline
value class ActiveBodySelectionReference private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+){0,7}$")

        fun parse(raw: String): EmbodimentResult<ActiveBodySelectionReference> = if (!pattern.matches(raw)) {
            embodimentFailure(EmbodimentError.InvalidSelectionReference)
        } else {
            EmbodimentResult.Success(ActiveBodySelectionReference(raw))
        }
    }
}

/** Explicit, expiring context selection. Its reference is correlation only, never authority. */
class ActiveBodySelection private constructor(
    val body: BodyProviderReference,
    val mode: ActiveBodyActivationMode,
    val reference: ActiveBodySelectionReference?,
    val selectedAt: MonotonicTimestampMs,
    val expiresAt: MonotonicTimestampMs,
) {
    val grantsExecutionAuthority: Boolean get() = false

    fun isActiveAt(now: MonotonicTimestampMs): Boolean = now.value < expiresAt.value

    companion object {
        fun create(
            body: BodyProviderReference,
            mode: ActiveBodyActivationMode,
            reference: ActiveBodySelectionReference?,
            selectedAt: MonotonicTimestampMs,
            expiresAt: MonotonicTimestampMs,
        ): EmbodimentResult<ActiveBodySelection> = if (expiresAt.value <= selectedAt.value) {
            embodimentFailure(EmbodimentError.InvalidSelectionFreshness)
        } else {
            EmbodimentResult.Success(ActiveBodySelection(body, mode, reference, selectedAt, expiresAt))
        }
    }
}

sealed interface ActiveBodyEvent {
    val generation: EmbodimentGeneration

    data class Observe(
        override val generation: EmbodimentGeneration,
        val body: BodyProviderReference,
        val availability: BodyAvailability,
        val nearby: Boolean,
        val connected: Boolean,
        val remotelyControlled: Boolean,
        val evidence: List<EmbodimentEvidence>,
        val observedAt: MonotonicTimestampMs,
    ) : ActiveBodyEvent

    data class MountEvidence(
        override val generation: EmbodimentGeneration,
        val body: BodyProviderReference,
        val evidence: List<EmbodimentEvidence>,
        val observedAt: MonotonicTimestampMs,
    ) : ActiveBodyEvent

    data class Select(
        override val generation: EmbodimentGeneration,
        val selection: ActiveBodySelection,
    ) : ActiveBodyEvent

    data class Detach(
        override val generation: EmbodimentGeneration,
        val body: BodyProviderReference,
        val evidence: EmbodimentEvidence,
    ) : ActiveBodyEvent

    data class Unavailable(
        override val generation: EmbodimentGeneration,
        val body: BodyProviderReference,
        val evidence: EmbodimentEvidence,
    ) : ActiveBodyEvent

    data class Deactivate(override val generation: EmbodimentGeneration) : ActiveBodyEvent

    data class Expire(
        override val generation: EmbodimentGeneration,
        val now: MonotonicTimestampMs,
    ) : ActiveBodyEvent
}

sealed interface ActiveBodyDisposition {
    data object Applied : ActiveBodyDisposition

    data class Ignored(val reason: ActiveBodyIgnoredReason) : ActiveBodyDisposition
}

enum class ActiveBodyIgnoredReason {
    STALE_GENERATION,
    UNKNOWN_BODY,
    BODY_UNAVAILABLE,
    REMOTE_CONTROL_NOT_OBSERVED,
    MOUNTING_NOT_ESTABLISHED,
    INVALID_EVIDENCE,
}

data class ActiveBodyReduction(
    val state: ActiveBodyState,
    val disposition: ActiveBodyDisposition,
)

/** Pure reducer; it does not discover, connect, select automatically, authorize, or command a body. */
class ActiveBodyReducer(private val mountingPolicy: MountingEvidencePolicy) {
    fun reduce(state: ActiveBodyState, event: ActiveBodyEvent): ActiveBodyReduction {
        if (state.generation != null && event.generation.value < state.generation.value) {
            return ActiveBodyReduction(state, ActiveBodyDisposition.Ignored(ActiveBodyIgnoredReason.STALE_GENERATION))
        }
        return when (event) {
            is ActiveBodyEvent.Observe -> observe(state, event)
            is ActiveBodyEvent.MountEvidence -> mountEvidence(state, event)
            is ActiveBodyEvent.Select -> select(state, event)
            is ActiveBodyEvent.Detach -> detach(state, event)
            is ActiveBodyEvent.Unavailable -> unavailable(state, event)
            is ActiveBodyEvent.Deactivate -> applied(state, event.generation, state.bodies, null)
            is ActiveBodyEvent.Expire -> {
                val selection = state.activeSelection?.takeIf { it.isActiveAt(event.now) }
                applied(state, event.generation, state.bodies, selection)
            }
        }
    }

    private fun observe(state: ActiveBodyState, event: ActiveBodyEvent.Observe): ActiveBodyReduction {
        val prior = state.body(event.body)
        val snapshot = BodyEmbodimentSnapshot.create(
            body = event.body,
            availability = event.availability,
            discovered = true,
            nearby = event.nearby,
            connected = event.connected,
            remotelyControlled = event.remotelyControlled,
            mounting = prior?.mounting ?: MountingState.UNKNOWN,
            evidence = (prior?.evidence.orEmpty() + event.evidence),
        )
        val selection = state.activeSelection?.takeIf { selected ->
            selected.body != event.body || event.availability == BodyAvailability.AVAILABLE
        }
        return applied(state, event.generation, replace(state.bodies, snapshot), selection)
    }

    private fun mountEvidence(state: ActiveBodyState, event: ActiveBodyEvent.MountEvidence): ActiveBodyReduction {
        val prior = state.body(event.body) ?: return ignored(state, ActiveBodyIgnoredReason.UNKNOWN_BODY)
        val evidence = prior.evidence + event.evidence
        val mounting = if (mountingPolicy.establishesMounted(evidence, event.observedAt)) {
            MountingState.MOUNTED
        } else {
            prior.mounting
        }
        return applied(
            state,
            event.generation,
            replace(state.bodies, BodyEmbodimentSnapshot.create(
                event.body, prior.availability, prior.discovered, prior.nearby, prior.connected,
                prior.remotelyControlled, mounting, evidence,
            )),
            state.activeSelection,
        )
    }

    private fun select(state: ActiveBodyState, event: ActiveBodyEvent.Select): ActiveBodyReduction {
        val body = state.body(event.selection.body) ?: return ignored(state, ActiveBodyIgnoredReason.UNKNOWN_BODY)
        if (body.availability == BodyAvailability.UNAVAILABLE) {
            return ignored(state, ActiveBodyIgnoredReason.BODY_UNAVAILABLE)
        }
        val reason = when (event.selection.mode) {
            ActiveBodyActivationMode.REMOTE -> if (!body.remotelyControlled) {
                ActiveBodyIgnoredReason.REMOTE_CONTROL_NOT_OBSERVED
            } else {
                null
            }
            ActiveBodyActivationMode.MOUNTED -> if (body.mounting != MountingState.MOUNTED) {
                ActiveBodyIgnoredReason.MOUNTING_NOT_ESTABLISHED
            } else {
                null
            }
        }
        return if (reason == null) {
            applied(state, event.generation, state.bodies, event.selection)
        } else {
            ignored(state, reason)
        }
    }

    private fun detach(state: ActiveBodyState, event: ActiveBodyEvent.Detach): ActiveBodyReduction {
        val prior = state.body(event.body) ?: return ignored(state, ActiveBodyIgnoredReason.UNKNOWN_BODY)
        if (event.evidence.kind != EmbodimentEvidenceKind.DETACH_VERIFIED) {
            return ignored(state, ActiveBodyIgnoredReason.INVALID_EVIDENCE)
        }
        return applied(
            state,
            event.generation,
            replace(state.bodies, BodyEmbodimentSnapshot.create(
                event.body, prior.availability, prior.discovered, prior.nearby, prior.connected,
                prior.remotelyControlled, MountingState.DETACHED, prior.evidence + event.evidence,
            )),
            state.activeSelection?.takeIf { it.body != event.body },
        )
    }

    private fun unavailable(state: ActiveBodyState, event: ActiveBodyEvent.Unavailable): ActiveBodyReduction {
        val prior = state.body(event.body) ?: return ignored(state, ActiveBodyIgnoredReason.UNKNOWN_BODY)
        if (event.evidence.kind != EmbodimentEvidenceKind.PROVIDER_UNAVAILABLE) {
            return ignored(state, ActiveBodyIgnoredReason.INVALID_EVIDENCE)
        }
        return applied(
            state,
            event.generation,
            replace(state.bodies, BodyEmbodimentSnapshot.create(
                event.body, BodyAvailability.UNAVAILABLE, prior.discovered, prior.nearby,
                prior.connected, prior.remotelyControlled, prior.mounting, prior.evidence + event.evidence,
            )),
            state.activeSelection?.takeIf { it.body != event.body },
        )
    }

    private fun replace(
        bodies: List<BodyEmbodimentSnapshot>,
        replacement: BodyEmbodimentSnapshot,
    ): List<BodyEmbodimentSnapshot> = bodies.filter { it.body != replacement.body } + replacement

    private fun applied(
        prior: ActiveBodyState,
        generation: EmbodimentGeneration,
        bodies: List<BodyEmbodimentSnapshot>,
        selection: ActiveBodySelection?,
    ): ActiveBodyReduction = ActiveBodyReduction(
        ActiveBodyState.create(generation, prior.host, bodies, selection),
        ActiveBodyDisposition.Applied,
    )

    private fun ignored(state: ActiveBodyState, reason: ActiveBodyIgnoredReason): ActiveBodyReduction =
        ActiveBodyReduction(state, ActiveBodyDisposition.Ignored(reason))
}

sealed interface EmbodimentResult<out T> {
    data class Success<T>(val value: T) : EmbodimentResult<T>

    data class Failure(val error: EmbodimentError) : EmbodimentResult<Nothing>
}

sealed interface EmbodimentError {
    data object InvalidGeneration : EmbodimentError
    data object NotBodyProvider : EmbodimentError
    data object InvalidHostReference : EmbodimentError
    data object InvalidEvidenceId : EmbodimentError
    data object InvalidConfidence : EmbodimentError
    data object InvalidEvidenceFreshness : EmbodimentError
    data object InvalidSelectionReference : EmbodimentError
    data object InvalidSelectionFreshness : EmbodimentError
}

private fun <T> embodimentFailure(error: EmbodimentError): EmbodimentResult<T> =
    EmbodimentResult.Failure(error)
