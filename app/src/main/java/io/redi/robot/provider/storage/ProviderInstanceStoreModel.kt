package io.redi.robot.provider.storage

import io.redi.robot.adapter.api.AdapterId
import io.redi.robot.adapter.api.AdapterVersion
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.discovery.identity.ProtectedIdentityEvidenceReference
import io.redi.robot.manifest.domain.ManifestDigest
import io.redi.robot.manifest.domain.ManifestSchemaVersion
import io.redi.robot.manifest.domain.ManifestVersion
import io.redi.robot.manifest.domain.SpecializedManifestKind
import io.redi.robot.provider.calibration.CalibrationProfileId
import io.redi.robot.trust.domain.TrustRecordId
import java.util.Collections
import java.util.UUID

data class ProviderInstanceStoreSchemaVersion private constructor(val major: Int, val minor: Int) {
    val wireValue: String = "$major.$minor"

    companion object {
        val CURRENT = ProviderInstanceStoreSchemaVersion(1, 0)
        private val pattern = Regex("^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$")

        fun parse(raw: String): ProviderInstanceStoreResult<ProviderInstanceStoreSchemaVersion> {
            if (!pattern.matches(raw)) return storeFailure(ProviderInstanceStoreError.INVALID_SCHEMA_VERSION)
            val parts = raw.split('.').map(String::toInt)
            return ProviderInstanceStoreResult.Success(ProviderInstanceStoreSchemaVersion(parts[0], parts[1]))
        }
    }
}

@JvmInline
value class ProviderInstanceStoreRevision private constructor(val value: Long) {
    companion object {
        val INITIAL = ProviderInstanceStoreRevision(0)
        fun create(value: Long): ProviderInstanceStoreResult<ProviderInstanceStoreRevision> =
            if (value >= 0) ProviderInstanceStoreResult.Success(ProviderInstanceStoreRevision(value))
            else storeFailure(ProviderInstanceStoreError.INVALID_REVISION)
    }

    internal fun next(): ProviderInstanceStoreRevision = ProviderInstanceStoreRevision(Math.addExact(value, 1))
}

@JvmInline
value class ProviderInstanceRecordRevision private constructor(val value: Long) {
    companion object {
        val INITIAL = ProviderInstanceRecordRevision(1)
        fun create(value: Long): ProviderInstanceStoreResult<ProviderInstanceRecordRevision> =
            if (value > 0) ProviderInstanceStoreResult.Success(ProviderInstanceRecordRevision(value))
            else storeFailure(ProviderInstanceStoreError.INVALID_RECORD_REVISION)
    }

    internal fun next(): ProviderInstanceRecordRevision = ProviderInstanceRecordRevision(Math.addExact(value, 1))
}

@JvmInline
value class ProviderStoreTimestamp private constructor(val epochMilliseconds: Long) {
    companion object {
        fun create(value: Long): ProviderInstanceStoreResult<ProviderStoreTimestamp> =
            if (value >= 0) ProviderInstanceStoreResult.Success(ProviderStoreTimestamp(value))
            else storeFailure(ProviderInstanceStoreError.INVALID_TIMESTAMP)
    }
}

/** Opaque local record handle. It is neither the private provider ID nor authority. */
@JvmInline
value class ProviderStoreEntryReference private constructor(val value: String) {
    override fun toString(): String = "[provider-entry]"

    companion object {
        fun fromUuid(uuid: UUID): ProviderStoreEntryReference = ProviderStoreEntryReference(uuid.toString())
        fun parse(raw: String): ProviderInstanceStoreResult<ProviderStoreEntryReference> =
            canonicalUuid(raw)?.let { ProviderInstanceStoreResult.Success(ProviderStoreEntryReference(it)) }
                ?: storeFailure(ProviderInstanceStoreError.INVALID_ENTRY_REFERENCE)
    }
}

@JvmInline
value class ProviderConfigurationReference private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._/-][a-z0-9]+)*$")
        fun parse(raw: String): ProviderInstanceStoreResult<ProviderConfigurationReference> =
            if (raw.length in 3..160 && pattern.matches(raw)) {
                ProviderInstanceStoreResult.Success(ProviderConfigurationReference(raw))
            } else storeFailure(ProviderInstanceStoreError.INVALID_PROVENANCE_REFERENCE)
    }
}

enum class ProviderInstanceKnowledgeStatus { KNOWN, DISABLED, RETIRED }

enum class ProviderInstanceConfigurationSource {
    BUILT_IN,
    USER_APPROVED,
    VENDOR_CONFIGURATION,
    HUGO_MAINTAINED,
    DEVELOPER_MODE_CANDIDATE,
    LEGACY_MIGRATION,
}

data class ProviderInstanceProvenance(
    val source: ProviderInstanceConfigurationSource,
    val reference: ProviderConfigurationReference,
)

data class StoredManifestReference(
    val providerDefinitionId: ProviderDefinitionId,
    val schemaVersion: ManifestSchemaVersion,
    val manifestVersion: ManifestVersion,
    val digest: ManifestDigest,
    val specialization: SpecializedManifestKind?,
) {
    fun isCompatibleWith(family: ProviderFamily): Boolean =
        specialization == null || specialization.providerFamily == family
}

data class StoredAdapterReference(val id: AdapterId, val version: AdapterVersion)

/**
 * Durable identity/configuration knowledge only. Private identity and binding references are
 * serialized solely inside the authenticated encrypted payload.
 */
class ProviderInstanceRecord private constructor(
    val entryReference: ProviderStoreEntryReference,
    val instanceId: ProviderInstanceId,
    val definitionId: ProviderDefinitionId,
    val family: ProviderFamily,
    val alias: RedactedProviderAlias,
    val manifest: StoredManifestReference?,
    val adapter: StoredAdapterReference?,
    trustRecordReferences: Set<TrustRecordId>,
    calibrationProfileReferences: Set<CalibrationProfileId>,
    protectedIdentityBindings: Set<ProtectedIdentityEvidenceReference>,
    val firstSeenAt: ProviderStoreTimestamp,
    val lastSeenAt: ProviderStoreTimestamp,
    val lastSuccessfulUseAt: ProviderStoreTimestamp?,
    val revision: ProviderInstanceRecordRevision,
    val provenance: ProviderInstanceProvenance,
    val status: ProviderInstanceKnowledgeStatus,
) {
    val trustRecordReferences: Set<TrustRecordId> = immutableSorted(trustRecordReferences) { it.value }
    val calibrationProfileReferences: Set<CalibrationProfileId> =
        immutableSorted(calibrationProfileReferences) { it.value }
    val protectedIdentityBindings: Set<ProtectedIdentityEvidenceReference> =
        immutableSorted(protectedIdentityBindings) { it.value }

    fun diagnostic(): RedactedProviderInstanceRecord = RedactedProviderInstanceRecord(
        entryReference,
        definitionId,
        family,
        alias,
        manifest?.manifestVersion,
        adapter?.version,
        status,
        revision,
        trustRecordReferences.size,
        calibrationProfileReferences.size,
    )

    override fun toString(): String = diagnostic().toString()

    companion object {
        @Suppress("LongParameterList")
        fun create(
            entryReference: ProviderStoreEntryReference,
            instanceId: ProviderInstanceId,
            definitionId: ProviderDefinitionId,
            family: ProviderFamily,
            alias: RedactedProviderAlias,
            manifest: StoredManifestReference?,
            adapter: StoredAdapterReference?,
            trustRecordReferences: Set<TrustRecordId>,
            calibrationProfileReferences: Set<CalibrationProfileId>,
            protectedIdentityBindings: Set<ProtectedIdentityEvidenceReference>,
            firstSeenAt: ProviderStoreTimestamp,
            lastSeenAt: ProviderStoreTimestamp,
            lastSuccessfulUseAt: ProviderStoreTimestamp?,
            revision: ProviderInstanceRecordRevision = ProviderInstanceRecordRevision.INITIAL,
            provenance: ProviderInstanceProvenance,
            status: ProviderInstanceKnowledgeStatus = ProviderInstanceKnowledgeStatus.KNOWN,
        ): ProviderInstanceStoreResult<ProviderInstanceRecord> = when {
            manifest?.providerDefinitionId != null && manifest.providerDefinitionId != definitionId ->
                storeFailure(ProviderInstanceStoreError.CONFLICTING_DEFINITION_BINDING)
            manifest != null && !manifest.isCompatibleWith(family) ->
                storeFailure(ProviderInstanceStoreError.INVALID_MANIFEST_REFERENCE)
            containsStableIdentifier(alias.value) -> storeFailure(ProviderInstanceStoreError.INVALID_ALIAS)
            protectedIdentityBindings.isEmpty() -> storeFailure(ProviderInstanceStoreError.MISSING_IDENTITY_BINDING)
            trustRecordReferences.size > MAX_REFERENCES ||
                calibrationProfileReferences.size > MAX_REFERENCES ||
                protectedIdentityBindings.size > MAX_REFERENCES ->
                storeFailure(ProviderInstanceStoreError.TOO_MANY_REFERENCES)
            lastSeenAt.epochMilliseconds < firstSeenAt.epochMilliseconds ->
                storeFailure(ProviderInstanceStoreError.INVALID_TIMESTAMP_ORDER)
            lastSuccessfulUseAt != null && lastSuccessfulUseAt.epochMilliseconds < firstSeenAt.epochMilliseconds ->
                storeFailure(ProviderInstanceStoreError.INVALID_TIMESTAMP_ORDER)
            else -> ProviderInstanceStoreResult.Success(
                ProviderInstanceRecord(
                    entryReference, instanceId, definitionId, family, alias, manifest, adapter,
                    trustRecordReferences, calibrationProfileReferences, protectedIdentityBindings,
                    firstSeenAt, lastSeenAt, lastSuccessfulUseAt, revision, provenance, status,
                ),
            )
        }

        private const val MAX_REFERENCES = 64
    }
}

data class RedactedProviderInstanceRecord(
    val entryReference: ProviderStoreEntryReference,
    val definitionId: ProviderDefinitionId,
    val family: ProviderFamily,
    val alias: RedactedProviderAlias,
    val manifestVersion: ManifestVersion?,
    val adapterVersion: AdapterVersion?,
    val status: ProviderInstanceKnowledgeStatus,
    val revision: ProviderInstanceRecordRevision,
    val trustReferenceCount: Int,
    val calibrationReferenceCount: Int,
)

/** H022-facing read-only index: no ProviderInstanceId is exposed. */
data class KnownProviderInstanceProjection(
    val entryReference: ProviderStoreEntryReference,
    val definitionId: ProviderDefinitionId,
    val family: ProviderFamily,
    val alias: RedactedProviderAlias,
    val protectedIdentityBindings: Set<ProtectedIdentityEvidenceReference>,
    val status: ProviderInstanceKnowledgeStatus,
)

class ProviderInstanceStoreSnapshot private constructor(
    val schemaVersion: ProviderInstanceStoreSchemaVersion,
    val revision: ProviderInstanceStoreRevision,
    records: List<ProviderInstanceRecord>,
) {
    val records: List<ProviderInstanceRecord> = Collections.unmodifiableList(
        records.sortedBy { it.entryReference.value }.toList(),
    )

    companion object {
        fun empty() = ProviderInstanceStoreSnapshot(
            ProviderInstanceStoreSchemaVersion.CURRENT,
            ProviderInstanceStoreRevision.INITIAL,
            emptyList(),
        )

        fun create(
            schemaVersion: ProviderInstanceStoreSchemaVersion,
            revision: ProviderInstanceStoreRevision,
            records: List<ProviderInstanceRecord>,
        ): ProviderInstanceStoreResult<ProviderInstanceStoreSnapshot> {
            val duplicateInstance = records.groupBy { it.instanceId }.values.any { it.size > 1 }
            val duplicateEntry = records.groupBy { it.entryReference }.values.any { it.size > 1 }
            val conflictingBinding = records.flatMap { record ->
                record.protectedIdentityBindings.map { it to record.instanceId }
            }.groupBy({ it.first }, { it.second }).values.any { it.distinct().size > 1 }
            return when {
                schemaVersion != ProviderInstanceStoreSchemaVersion.CURRENT ->
                    storeFailure(ProviderInstanceStoreError.UNSUPPORTED_SCHEMA)
                duplicateInstance -> storeFailure(ProviderInstanceStoreError.DUPLICATE_INSTANCE_ID)
                duplicateEntry -> storeFailure(ProviderInstanceStoreError.DUPLICATE_ENTRY_REFERENCE)
                conflictingBinding -> storeFailure(ProviderInstanceStoreError.CONFLICTING_IDENTITY_EVIDENCE)
                else -> ProviderInstanceStoreResult.Success(
                    ProviderInstanceStoreSnapshot(schemaVersion, revision, records),
                )
            }
        }
    }
}

sealed interface ProviderInstanceStoreResult<out T> {
    data class Success<T>(val value: T) : ProviderInstanceStoreResult<T>
    data class Failure(val error: ProviderInstanceStoreError) : ProviderInstanceStoreResult<Nothing>
}

enum class ProviderInstanceStoreError {
    INVALID_SCHEMA_VERSION,
    UNSUPPORTED_SCHEMA,
    INVALID_REVISION,
    INVALID_RECORD_REVISION,
    INVALID_TIMESTAMP,
    INVALID_TIMESTAMP_ORDER,
    INVALID_ENTRY_REFERENCE,
    INVALID_PROVENANCE_REFERENCE,
    INVALID_ALIAS,
    INVALID_MANIFEST_REFERENCE,
    INVALID_ADAPTER_REFERENCE,
    INVALID_RECORD_SHAPE,
    MISSING_IDENTITY_BINDING,
    TOO_MANY_REFERENCES,
    DUPLICATE_INSTANCE_ID,
    DUPLICATE_ENTRY_REFERENCE,
    CONFLICTING_DEFINITION_BINDING,
    CONFLICTING_IDENTITY_EVIDENCE,
    UNKNOWN_PROVIDER,
    STALE_STORE_REVISION,
    STALE_RECORD_REVISION,
    RETIRED_PROVIDER,
    REVISION_EXHAUSTED,
}

internal fun <T> storeFailure(error: ProviderInstanceStoreError): ProviderInstanceStoreResult<T> =
    ProviderInstanceStoreResult.Failure(error)

private fun canonicalUuid(raw: String): String? = runCatching {
    UUID.fromString(raw).toString().takeIf { it == raw }
}.getOrNull()

private fun <T> immutableSorted(values: Set<T>, selector: (T) -> String): Set<T> =
    Collections.unmodifiableSet(values.sortedBy(selector).toCollection(LinkedHashSet()))

private val ipv4Address = Regex("(?:^|[^0-9])(?:[0-9]{1,3}[.]){3}[0-9]{1,3}(?:$|[^0-9])")
private val uuidText = Regex("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
private val accountLikeText = Regex("[^@\\s]+@[^@\\s]+[.][^@\\s]+")

private fun containsStableIdentifier(value: String): Boolean =
    ipv4Address.containsMatchIn(value) || uuidText.containsMatchIn(value) || accountLikeText.containsMatchIn(value)
