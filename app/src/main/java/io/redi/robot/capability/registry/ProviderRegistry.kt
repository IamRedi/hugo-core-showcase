package io.redi.robot.capability.registry

import io.redi.robot.adapter.api.AdapterIdentity
import io.redi.robot.adapter.api.AdapterPackageDigest
import io.redi.robot.capability.api.CapabilityDescriptor
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.ProviderRuntimeSnapshot
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleState
import io.redi.robot.manifest.domain.CommonManifestBinding
import io.redi.robot.manifest.domain.SpecializedManifestKind
import java.util.Collections

/**
 * A normalized, immutable provider record. Presence here is neither trust, authorization,
 * connection readiness, safety approval, physical feasibility, nor provider selection.
 */
class ProviderRegistryRecord private constructor(
    val identity: ProviderIdentity,
    val manifest: ProviderManifestReference,
    val adapter: ProviderAdapterReference,
    capabilities: List<CapabilityDescriptor>,
    val runtime: ProviderRegistryRuntimeSnapshot,
    val hostAssociation: ProviderHostAssociationReference?,
) {
    val capabilities: List<CapabilityDescriptor> = Collections.unmodifiableList(capabilities.toList())

    companion object {
        fun create(
            identity: ProviderIdentity,
            manifest: ProviderManifestReference,
            adapter: ProviderAdapterReference,
            capabilities: List<CapabilityDescriptor>,
            runtime: ProviderRegistryRuntimeSnapshot,
            hostAssociation: ProviderHostAssociationReference? = null,
        ): ProviderRegistryResult<ProviderRegistryRecord> = when {
            manifest.common.providerDefinitionId != identity.definitionId -> registryFailure(
                ProviderRegistryError.ManifestIdentityMismatch,
            )
            manifest.specialization?.providerFamily != null &&
                manifest.specialization.providerFamily != identity.family -> registryFailure(
                ProviderRegistryError.ManifestFamilyMismatch,
            )
            capabilities.map { it.reference.id }.distinct().size != capabilities.size -> registryFailure(
                ProviderRegistryError.DuplicateCapabilityDeclaration,
            )
            else -> ProviderRegistryResult.Success(
                ProviderRegistryRecord(
                    identity = identity,
                    manifest = manifest,
                    adapter = adapter,
                    capabilities = capabilities.sortedBy { it.reference.wireValue },
                    runtime = runtime,
                    hostAssociation = hostAssociation,
                ),
            )
        }
    }
}

/** H010/H011 manifest binding only; it is not a manifest validator or trust assertion. */
data class ProviderManifestReference(
    val common: CommonManifestBinding,
    val specialization: SpecializedManifestKind? = null,
)

/** H012 adapter identity/integrity reference only; matching does not grant execution authority. */
data class ProviderAdapterReference(
    val identity: AdapterIdentity,
    val packageDigest: AdapterPackageDigest,
)

/**
 * An opaque local host association for future multi-host routing. It is intentionally not part of
 * provider identity and carries no host authority, address, credential, or availability claim.
 */
@JvmInline
value class ProviderHostAssociationReference private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._/-][a-z0-9]+){0,7}$")

        fun parse(raw: String): ProviderRegistryResult<ProviderHostAssociationReference> = when {
            raw.length !in 3..96 || !pattern.matches(raw) -> registryFailure(
                ProviderRegistryError.InvalidHostAssociationReference,
            )
            else -> ProviderRegistryResult.Success(ProviderHostAssociationReference(raw))
        }
    }
}

/** H008/H009 runtime observations, preserved without creating a second lifecycle model. */
class ProviderRegistryRuntimeSnapshot private constructor(
    val generation: ProviderGeneration,
    val lifecycle: ProviderLifecycleState,
    val availability: ProviderRuntimeSnapshot,
) {
    companion object {
        fun create(
            generation: ProviderGeneration,
            lifecycle: ProviderLifecycleState,
            availability: ProviderRuntimeSnapshot,
        ): ProviderRegistryResult<ProviderRegistryRuntimeSnapshot> = when {
            lifecycle.generation != generation -> registryFailure(
                ProviderRegistryError.LifecycleGenerationMismatch,
            )
            availability.generation != generation -> registryFailure(
                ProviderRegistryError.AvailabilityGenerationMismatch,
            )
            else -> ProviderRegistryResult.Success(
                ProviderRegistryRuntimeSnapshot(generation, lifecycle, availability),
            )
        }
    }
}

private val ProviderLifecycleState.generation: ProviderGeneration?
    get() = when (this) {
        ProviderLifecycleState.Inactive -> null
        is ProviderLifecycleState.Discovered -> generation
        is ProviderLifecycleState.Identified -> generation
        is ProviderLifecycleState.Connecting -> generation
        is ProviderLifecycleState.Connected -> generation
        is ProviderLifecycleState.Negotiating -> generation
        is ProviderLifecycleState.Registered -> generation
        is ProviderLifecycleState.Cancelling -> generation
        is ProviderLifecycleState.Disconnecting -> generation
        is ProviderLifecycleState.Cancelled -> generation
        is ProviderLifecycleState.Disconnected -> generation
        is ProviderLifecycleState.Failed -> generation
    }

@JvmInline
value class ProviderRegistryRevision private constructor(val value: Long) {
    companion object {
        val initial: ProviderRegistryRevision = ProviderRegistryRevision(0L)

        fun create(value: Long): ProviderRegistryResult<ProviderRegistryRevision> = if (value < 0L) {
            registryFailure(ProviderRegistryError.InvalidRevision)
        } else {
            ProviderRegistryResult.Success(ProviderRegistryRevision(value))
        }
    }

    internal fun next(): ProviderRegistryRevision = ProviderRegistryRevision(value + 1L)
}

data class RegisteredProviderSnapshot(
    val record: ProviderRegistryRecord,
    val revision: ProviderRegistryRevision,
)

class ProviderRegistrySnapshot private constructor(entries: List<RegisteredProviderSnapshot>) {
    val entries: List<RegisteredProviderSnapshot> = Collections.unmodifiableList(entries.toList())

    fun find(instanceId: ProviderInstanceId): RegisteredProviderSnapshot? = entries.firstOrNull {
        it.record.identity.instanceId == instanceId
    }

    fun byFamily(family: ProviderFamily): List<RegisteredProviderSnapshot> = entries.filter {
        it.record.identity.family == family
    }

    /** Exact reference match only; compatibility and execution selection belong to later layers. */
    fun offering(capability: CapabilityReference): List<RegisteredProviderSnapshot> = entries.filter {
        it.record.capabilities.any { descriptor -> descriptor.reference == capability }
    }

    companion object {
        fun empty(): ProviderRegistrySnapshot = ProviderRegistrySnapshot(emptyList())

        internal fun of(entries: Collection<RegisteredProviderSnapshot>): ProviderRegistrySnapshot =
            ProviderRegistrySnapshot(
                entries.sortedBy { snapshot -> snapshot.record.identity.instanceId.value },
            )
    }
}

data class ProviderRuntimeRegistrationUpdate(
    val instanceId: ProviderInstanceId,
    val expectedRevision: ProviderRegistryRevision,
    val runtime: ProviderRegistryRuntimeSnapshot,
    val hostAssociation: ProviderHostAssociationReference? = null,
)

data class ProviderUnregistrationRequest(
    val instanceId: ProviderInstanceId,
    val expectedRevision: ProviderRegistryRevision,
    val expectedGeneration: ProviderGeneration,
)

sealed interface ProviderRegistryResult<out T> {
    data class Success<T>(val value: T) : ProviderRegistryResult<T>

    data class Failure(val error: ProviderRegistryError) : ProviderRegistryResult<Nothing>
}

sealed interface ProviderRegistryError {
    data object InvalidHostAssociationReference : ProviderRegistryError
    data object InvalidRevision : ProviderRegistryError
    data object ManifestIdentityMismatch : ProviderRegistryError
    data object ManifestFamilyMismatch : ProviderRegistryError
    data object DuplicateCapabilityDeclaration : ProviderRegistryError
    data object LifecycleGenerationMismatch : ProviderRegistryError
    data object AvailabilityGenerationMismatch : ProviderRegistryError
    data object DuplicateProviderInstance : ProviderRegistryError
    data object ProviderIdentityConflict : ProviderRegistryError
    data object UnknownProviderInstance : ProviderRegistryError
    data object StaleRevision : ProviderRegistryError
    data object StaleGeneration : ProviderRegistryError
}

/**
 * Provider-neutral registry port. Implementations may persist the immutable snapshots later, but
 * this contract deliberately does not discover, connect, trust, authorize, or execute providers.
 */
interface ProviderRegistry {
    fun snapshot(): ProviderRegistrySnapshot

    fun register(record: ProviderRegistryRecord): ProviderRegistryResult<RegisteredProviderSnapshot>

    fun updateRuntime(
        update: ProviderRuntimeRegistrationUpdate,
    ): ProviderRegistryResult<RegisteredProviderSnapshot>

    fun unregister(request: ProviderUnregistrationRequest): ProviderRegistryResult<Unit>
}

/** Deterministic in-memory reference implementation for tests; it is not durable storage. */
class InMemoryProviderRegistry : ProviderRegistry {
    private var current: ProviderRegistrySnapshot = ProviderRegistrySnapshot.empty()

    @Synchronized
    override fun snapshot(): ProviderRegistrySnapshot = current

    @Synchronized
    override fun register(record: ProviderRegistryRecord): ProviderRegistryResult<RegisteredProviderSnapshot> {
        val existing = current.find(record.identity.instanceId)
        if (existing != null) {
            return registryFailure(
                if (
                    existing.record.identity.definitionId != record.identity.definitionId ||
                    existing.record.identity.family != record.identity.family
                ) {
                    ProviderRegistryError.ProviderIdentityConflict
                } else {
                    ProviderRegistryError.DuplicateProviderInstance
                },
            )
        }
        val registered = RegisteredProviderSnapshot(record, ProviderRegistryRevision.initial)
        current = ProviderRegistrySnapshot.of(current.entries + registered)
        return ProviderRegistryResult.Success(registered)
    }

    @Synchronized
    override fun updateRuntime(
        update: ProviderRuntimeRegistrationUpdate,
    ): ProviderRegistryResult<RegisteredProviderSnapshot> {
        val existing = current.find(update.instanceId) ?: return registryFailure(
            ProviderRegistryError.UnknownProviderInstance,
        )
        if (existing.revision != update.expectedRevision) {
            return registryFailure(ProviderRegistryError.StaleRevision)
        }
        if (update.runtime.generation.value < existing.record.runtime.generation.value) {
            return registryFailure(ProviderRegistryError.StaleGeneration)
        }
        val updated = RegisteredProviderSnapshot(
            record = ProviderRegistryRecord.create(
                identity = existing.record.identity,
                manifest = existing.record.manifest,
                adapter = existing.record.adapter,
                capabilities = existing.record.capabilities,
                runtime = update.runtime,
                hostAssociation = update.hostAssociation,
            ).requireSuccess(),
            revision = existing.revision.next(),
        )
        current = ProviderRegistrySnapshot.of(current.entries - existing + updated)
        return ProviderRegistryResult.Success(updated)
    }

    @Synchronized
    override fun unregister(request: ProviderUnregistrationRequest): ProviderRegistryResult<Unit> {
        val existing = current.find(request.instanceId) ?: return registryFailure(
            ProviderRegistryError.UnknownProviderInstance,
        )
        if (existing.revision != request.expectedRevision) {
            return registryFailure(ProviderRegistryError.StaleRevision)
        }
        if (existing.record.runtime.generation != request.expectedGeneration) {
            return registryFailure(ProviderRegistryError.StaleGeneration)
        }
        current = ProviderRegistrySnapshot.of(current.entries - existing)
        return ProviderRegistryResult.Success(Unit)
    }
}

private fun <T> registryFailure(error: ProviderRegistryError): ProviderRegistryResult<T> =
    ProviderRegistryResult.Failure(error)

private fun <T> ProviderRegistryResult<T>.requireSuccess(): T = when (this) {
    is ProviderRegistryResult.Success -> value
    is ProviderRegistryResult.Failure -> error("Unexpected provider registry failure: $error")
}
