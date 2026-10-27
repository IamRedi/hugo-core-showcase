package io.redi.robot.adapter.registry

import io.redi.robot.adapter.api.AdapterId
import io.redi.robot.adapter.api.AdapterDescriptorCodec
import io.redi.robot.adapter.api.AdapterPackageDigest
import io.redi.robot.adapter.compatibility.AdapterDescriptorEvidence
import io.redi.robot.adapter.compatibility.CompatibilityMatcher
import io.redi.robot.adapter.compatibility.CompatibilityRequest
import io.redi.robot.adapter.compatibility.ConnectionMethodSupport
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.provider.ProviderFamily

/** In-memory H025 knowledge index. It has no installer, loader, authority, or runtime side effects. */
class AdapterRegistry private constructor(initialSnapshot: AdapterRegistrySnapshot?) {
    private var revision: AdapterRegistryRevision = initialSnapshot?.revision ?: AdapterRegistryRevision.INITIAL
    private val records = initialSnapshot?.records
        ?.associateByTo(mutableMapOf(), AdapterRegistryRecord::key)
        ?: mutableMapOf()

    constructor() : this(null)

    companion object {
        /** H026 restore hook; restored records retain the persisted revision without replaying mutations. */
        internal fun restore(snapshot: AdapterRegistrySnapshot): AdapterRegistry = AdapterRegistry(snapshot)
    }

    @Synchronized
    fun snapshot(): AdapterRegistrySnapshot = currentSnapshot()

    @Synchronized
    fun register(
        record: AdapterRegistryRecord,
        expectedRevision: AdapterRegistryRevision,
    ): AdapterRegistryMutationResult {
        stale(expectedRevision)?.let { return it }
        val existing = records[record.key]
        if (existing != null) {
            return if (existing.canonicalValue == record.canonicalValue) {
                AdapterRegistryMutationResult.Duplicate(existing, currentSnapshot())
            } else {
                AdapterRegistryMutationResult.Conflict(
                    record.key,
                    conflict(existing, record),
                    currentSnapshot(),
                )
            }
        }
        if (!advanceRevision()) {
            return AdapterRegistryMutationResult.Rejected(
                AdapterRegistryRejection.REVISION_EXHAUSTED,
                currentSnapshot(),
            )
        }
        records[record.key] = record
        return AdapterRegistryMutationResult.Registered(record, currentSnapshot())
    }

    @Synchronized
    fun replace(
        key: AdapterRegistryKey,
        expectedDigest: AdapterPackageDigest,
        replacement: AdapterRegistryRecord,
        expectedRevision: AdapterRegistryRevision,
    ): AdapterRegistryMutationResult {
        stale(expectedRevision)?.let { return it }
        if (replacement.key != key) {
            return AdapterRegistryMutationResult.Rejected(
                AdapterRegistryRejection.REPLACEMENT_IDENTITY_MISMATCH,
                currentSnapshot(),
            )
        }
        val existing = records[key] ?: return AdapterRegistryMutationResult.NotFound(key, currentSnapshot())
        if (existing.packageDigest != expectedDigest) {
            return AdapterRegistryMutationResult.Conflict(
                key,
                AdapterRegistryConflict.EXPECTED_DIGEST,
                currentSnapshot(),
            )
        }
        if (existing.canonicalValue == replacement.canonicalValue) {
            return AdapterRegistryMutationResult.Duplicate(existing, currentSnapshot())
        }
        if (!advanceRevision()) {
            return AdapterRegistryMutationResult.Rejected(
                AdapterRegistryRejection.REVISION_EXHAUSTED,
                currentSnapshot(),
            )
        }
        records[key] = replacement
        return AdapterRegistryMutationResult.Replaced(existing, replacement, currentSnapshot())
    }

    @Synchronized
    fun remove(
        key: AdapterRegistryKey,
        expectedDigest: AdapterPackageDigest,
        expectedRevision: AdapterRegistryRevision,
    ): AdapterRegistryMutationResult {
        stale(expectedRevision)?.let { return it }
        val existing = records[key] ?: return AdapterRegistryMutationResult.NotFound(key, currentSnapshot())
        if (existing.packageDigest != expectedDigest) {
            return AdapterRegistryMutationResult.Conflict(
                key,
                AdapterRegistryConflict.EXPECTED_DIGEST,
                currentSnapshot(),
            )
        }
        if (!advanceRevision()) {
            return AdapterRegistryMutationResult.Rejected(
                AdapterRegistryRejection.REVISION_EXHAUSTED,
                currentSnapshot(),
            )
        }
        records.remove(key)
        return AdapterRegistryMutationResult.Removed(existing, currentSnapshot())
    }

    fun exact(key: AdapterRegistryKey): AdapterRegistryRecord? = snapshot().exact(key)

    fun versions(adapterId: AdapterId): List<AdapterRegistryRecord> = snapshot().versions(adapterId)

    fun byProviderFamily(family: ProviderFamily): List<AdapterRegistryRecord> = snapshot().byProviderFamily(family)

    fun byCapability(capability: CapabilityReference): List<AdapterRegistryRecord> = snapshot().byCapability(capability)

    fun queryManifestCompatibility(query: AdapterManifestRegistryQuery): AdapterCompatibilityCandidateSet {
        return snapshot().queryManifestCompatibility(query)
    }

    private fun stale(expected: AdapterRegistryRevision): AdapterRegistryMutationResult.StaleRevision? =
        if (expected != revision) {
            AdapterRegistryMutationResult.StaleRevision(expected, revision, currentSnapshot())
        } else {
            null
        }

    private fun advanceRevision(): Boolean = try {
        revision = AdapterRegistryRevision.next(revision)
        true
    } catch (_: ArithmeticException) {
        false
    }

    private fun currentSnapshot(): AdapterRegistrySnapshot = AdapterRegistrySnapshot(revision, records.values.toList())

    private fun conflict(
        existing: AdapterRegistryRecord,
        candidate: AdapterRegistryRecord,
    ): AdapterRegistryConflict = when {
        existing.packageDigest != candidate.packageDigest -> AdapterRegistryConflict.PACKAGE_DIGEST
        AdapterDescriptorCodec.encode(existing.indexedDescriptor) !=
            AdapterDescriptorCodec.encode(candidate.indexedDescriptor) ->
            AdapterRegistryConflict.DESCRIPTOR
        else -> AdapterRegistryConflict.INDEX_METADATA
    }
}

/** H025 query over one immutable revision; it cannot observe or mutate later registry changes. */
fun AdapterRegistrySnapshot.queryManifestCompatibility(
    query: AdapterManifestRegistryQuery,
): AdapterCompatibilityCandidateSet {
    val candidates = records.map { record ->
            val request = CompatibilityRequest.create(
                resolvedManifest = query.resolvedManifest,
                adapter = AdapterDescriptorEvidence(record.indexedDescriptor, record.descriptorReference),
                hugoRuntime = query.hugoRuntime,
                connectionMethods = record.connectionMethods.map {
                    ConnectionMethodSupport(it, record.descriptorReference)
                },
                extensionSchemas = record.extensionSupport(),
                providerVersions = query.providerVersions,
            )
            AdapterCompatibilityCandidate(record, CompatibilityMatcher.match(request))
        }
    return AdapterCompatibilityCandidateSet(candidates)
}
