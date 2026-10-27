package io.redi.robot.provider.storage

import io.redi.robot.adapter.api.AdapterVersion
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.manifest.domain.ManifestVersion
import java.util.Collections

/** Durable knowledge index only. It does not generate identities or expose runtime authority. */
class ProviderInstanceStore private constructor(snapshot: ProviderInstanceStoreSnapshot) {
    private var revision = snapshot.revision
    private val records = snapshot.records.associateByTo(linkedMapOf()) { it.instanceId }

    fun snapshot(): ProviderInstanceStoreSnapshot = storeSuccess(
        ProviderInstanceStoreSnapshot.create(
            ProviderInstanceStoreSchemaVersion.CURRENT,
            revision,
            records.values.toList(),
        ),
    )

    fun lookup(instanceId: ProviderInstanceId): ProviderInstanceRecord? = records[instanceId]

    fun diagnostics(): List<RedactedProviderInstanceRecord> = Collections.unmodifiableList(
        records.values.map(ProviderInstanceRecord::diagnostic).sortedBy { it.entryReference.value },
    )

    fun identityProjection(): List<KnownProviderInstanceProjection> = Collections.unmodifiableList(
        records.values.map {
            KnownProviderInstanceProjection(
                it.entryReference,
                it.definitionId,
                it.family,
                it.alias,
                it.protectedIdentityBindings,
                it.status,
            )
        }.sortedBy { it.entryReference.value },
    )

    fun create(record: ProviderInstanceRecord, expectedRevision: ProviderInstanceStoreRevision) = mutate(expectedRevision) {
        when {
            records.containsKey(record.instanceId) -> ProviderInstanceStoreError.DUPLICATE_INSTANCE_ID
            records.values.any { it.entryReference == record.entryReference } ->
                ProviderInstanceStoreError.DUPLICATE_ENTRY_REFERENCE
            records.values.any { existing ->
                existing.protectedIdentityBindings.any(record.protectedIdentityBindings::contains)
            } -> ProviderInstanceStoreError.CONFLICTING_IDENTITY_EVIDENCE
            else -> null
        }?.let { return@mutate storeFailure(it) }
        records[record.instanceId] = record
        ProviderInstanceStoreResult.Success(record)
    }

    fun updateAlias(
        instanceId: ProviderInstanceId,
        alias: RedactedProviderAlias,
        expectedStoreRevision: ProviderInstanceStoreRevision,
        expectedRecordRevision: ProviderInstanceRecordRevision,
    ) = update(instanceId, expectedStoreRevision, expectedRecordRevision) { current ->
        copy(current, alias = alias)
    }

    fun updateAssociations(
        instanceId: ProviderInstanceId,
        manifest: StoredManifestReference?,
        adapter: StoredAdapterReference?,
        lastSeenAt: ProviderStoreTimestamp,
        lastSuccessfulUseAt: ProviderStoreTimestamp?,
        expectedStoreRevision: ProviderInstanceStoreRevision,
        expectedRecordRevision: ProviderInstanceRecordRevision,
    ) = update(instanceId, expectedStoreRevision, expectedRecordRevision) { current ->
        copy(
            current,
            manifest = manifest,
            adapter = adapter,
            lastSeenAt = lastSeenAt,
            lastSuccessfulUseAt = lastSuccessfulUseAt,
        )
    }

    fun replaceReferences(
        instanceId: ProviderInstanceId,
        trustRecordReferences: Set<io.redi.robot.trust.domain.TrustRecordId>,
        calibrationProfileReferences: Set<io.redi.robot.provider.calibration.CalibrationProfileId>,
        expectedStoreRevision: ProviderInstanceStoreRevision,
        expectedRecordRevision: ProviderInstanceRecordRevision,
    ) = update(instanceId, expectedStoreRevision, expectedRecordRevision) { current ->
        copy(
            current,
            trustRecordReferences = trustRecordReferences,
            calibrationProfileReferences = calibrationProfileReferences,
        )
    }

    fun setStatus(
        instanceId: ProviderInstanceId,
        status: ProviderInstanceKnowledgeStatus,
        expectedStoreRevision: ProviderInstanceStoreRevision,
        expectedRecordRevision: ProviderInstanceRecordRevision,
    ) = update(instanceId, expectedStoreRevision, expectedRecordRevision) { current ->
        copy(current, status = status)
    }

    /** Deletion removes all local identity knowledge; rediscovery is unknown until explicit create. */
    fun delete(instanceId: ProviderInstanceId, expectedRevision: ProviderInstanceStoreRevision) =
        mutate(expectedRevision) {
            val removed = records.remove(instanceId) ?: return@mutate storeFailure(
                ProviderInstanceStoreError.UNKNOWN_PROVIDER,
            )
            ProviderInstanceStoreResult.Success(removed.diagnostic())
        }

    private fun update(
        instanceId: ProviderInstanceId,
        expectedStoreRevision: ProviderInstanceStoreRevision,
        expectedRecordRevision: ProviderInstanceRecordRevision,
        transform: (ProviderInstanceRecord) -> ProviderInstanceStoreResult<ProviderInstanceRecord>,
    ) = mutate(expectedStoreRevision) {
        val current = records[instanceId] ?: return@mutate storeFailure(ProviderInstanceStoreError.UNKNOWN_PROVIDER)
        if (current.revision != expectedRecordRevision) {
            return@mutate storeFailure(ProviderInstanceStoreError.STALE_RECORD_REVISION)
        }
        val transformed = try {
            transform(current)
        } catch (_: ArithmeticException) {
            return@mutate storeFailure(ProviderInstanceStoreError.REVISION_EXHAUSTED)
        }
        val updated = when (val result = transformed) {
            is ProviderInstanceStoreResult.Success -> result.value
            is ProviderInstanceStoreResult.Failure -> return@mutate result
        }
        records[instanceId] = updated
        ProviderInstanceStoreResult.Success(updated)
    }

    private fun <T> mutate(
        expectedRevision: ProviderInstanceStoreRevision,
        operation: () -> ProviderInstanceStoreResult<T>,
    ): ProviderInstanceStoreResult<T> {
        if (revision != expectedRevision) return storeFailure(ProviderInstanceStoreError.STALE_STORE_REVISION)
        val next = runCatching { revision.next() }.getOrElse {
            return storeFailure(ProviderInstanceStoreError.REVISION_EXHAUSTED)
        }
        val result = operation()
        if (result is ProviderInstanceStoreResult.Success) revision = next
        return result
    }

    @Suppress("LongParameterList")
    private fun copy(
        current: ProviderInstanceRecord,
        alias: RedactedProviderAlias = current.alias,
        manifest: StoredManifestReference? = current.manifest,
        adapter: StoredAdapterReference? = current.adapter,
        trustRecordReferences: Set<io.redi.robot.trust.domain.TrustRecordId> = current.trustRecordReferences,
        calibrationProfileReferences: Set<io.redi.robot.provider.calibration.CalibrationProfileId> =
            current.calibrationProfileReferences,
        lastSeenAt: ProviderStoreTimestamp = current.lastSeenAt,
        lastSuccessfulUseAt: ProviderStoreTimestamp? = current.lastSuccessfulUseAt,
        status: ProviderInstanceKnowledgeStatus = current.status,
    ): ProviderInstanceStoreResult<ProviderInstanceRecord> = ProviderInstanceRecord.create(
        current.entryReference,
        current.instanceId,
        current.definitionId,
        current.family,
        alias,
        manifest,
        adapter,
        trustRecordReferences,
        calibrationProfileReferences,
        current.protectedIdentityBindings,
        current.firstSeenAt,
        lastSeenAt,
        lastSuccessfulUseAt,
        current.revision.next(),
        current.provenance,
        status,
    )

    companion object {
        fun empty(): ProviderInstanceStore = ProviderInstanceStore(ProviderInstanceStoreSnapshot.empty())
        fun restore(snapshot: ProviderInstanceStoreSnapshot): ProviderInstanceStore = ProviderInstanceStore(snapshot)
    }
}

private fun <T> storeSuccess(result: ProviderInstanceStoreResult<T>): T = when (result) {
    is ProviderInstanceStoreResult.Success -> result.value
    is ProviderInstanceStoreResult.Failure -> error("Validated provider store state became invalid: ${result.error}")
}
