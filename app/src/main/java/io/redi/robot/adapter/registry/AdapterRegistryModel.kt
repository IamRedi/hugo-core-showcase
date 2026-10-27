package io.redi.robot.adapter.registry

import io.redi.robot.adapter.api.AdapterDescriptor
import io.redi.robot.adapter.api.AdapterDescriptorCodec
import io.redi.robot.adapter.api.AdapterId
import io.redi.robot.adapter.api.AdapterPackageDigest
import io.redi.robot.adapter.api.AdapterResult
import io.redi.robot.adapter.api.AdapterVersion
import io.redi.robot.adapter.compatibility.CompatibilityEvidenceReference
import io.redi.robot.adapter.compatibility.CompatibilityReport
import io.redi.robot.adapter.compatibility.CompatibilityReportStatus
import io.redi.robot.adapter.compatibility.ExtensionSchemaSupport
import io.redi.robot.adapter.compatibility.HugoRuntimeEvidence
import io.redi.robot.adapter.compatibility.ProviderVersionEvidence
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.connection.api.ConnectionMethodReference
import io.redi.robot.manifest.domain.ManifestExtensionId
import io.redi.robot.manifest.domain.ManifestSchemaVersion
import io.redi.robot.manifest.resolution.ResolvedManifestDefinition
import java.util.Collections

@JvmInline
value class AdapterRegistryRevision private constructor(val value: Long) {
    companion object {
        val INITIAL: AdapterRegistryRevision = AdapterRegistryRevision(0L)

        fun create(value: Long): AdapterRegistryModelResult<AdapterRegistryRevision> = if (value < 0L) {
            AdapterRegistryModelResult.Failure(AdapterRegistryModelError.INVALID_REVISION)
        } else {
            AdapterRegistryModelResult.Success(AdapterRegistryRevision(value))
        }

        internal fun next(current: AdapterRegistryRevision): AdapterRegistryRevision {
            if (current.value == Long.MAX_VALUE) throw ArithmeticException("adapter registry revision exhausted")
            return AdapterRegistryRevision(current.value + 1L)
        }
    }
}

@JvmInline
value class AdapterArtifactReference private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._/-][a-z0-9]+){1,7}$")

        fun parse(raw: String): AdapterRegistryModelResult<AdapterArtifactReference> = if (
            raw.length in 3..160 && pattern.matches(raw)
        ) {
            AdapterRegistryModelResult.Success(AdapterArtifactReference(raw))
        } else {
            AdapterRegistryModelResult.Failure(AdapterRegistryModelError.INVALID_ARTIFACT_REFERENCE)
        }
    }
}

data class AdapterRegistryKey(
    val adapterId: AdapterId,
    val version: AdapterVersion,
)

/** Describes what knowledge was indexed, never whether adapter code is installed or usable. */
enum class AdapterKnowledgeState {
    DEFINITION,
    PACKAGE_REFERENCE,
    DEVELOPMENT_CANDIDATE,
}

data class AdapterExtensionSchemaClaim(
    val id: ManifestExtensionId,
    val schemaVersion: ManifestSchemaVersion,
)

class AdapterRegistryRecord private constructor(
    internal val indexedDescriptor: AdapterDescriptor,
    val descriptorReference: CompatibilityEvidenceReference,
    val artifactReference: AdapterArtifactReference?,
    val knowledgeState: AdapterKnowledgeState,
    connectionMethods: List<ConnectionMethodReference>,
    extensionSchemas: List<AdapterExtensionSchemaClaim>,
) {
    /** Returns an isolated H012 value so callers cannot mutate an indexed snapshot through list casts. */
    val descriptor: AdapterDescriptor
        get() = copyDescriptor(indexedDescriptor)

    val key: AdapterRegistryKey = AdapterRegistryKey(indexedDescriptor.identity.id, indexedDescriptor.identity.version)
    val packageDigest: AdapterPackageDigest = indexedDescriptor.packageDigest
    val connectionMethods: List<ConnectionMethodReference> = Collections.unmodifiableList(
        connectionMethods.sortedBy(::connectionWireValue),
    )
    val extensionSchemas: List<AdapterExtensionSchemaClaim> = Collections.unmodifiableList(
        extensionSchemas.sortedBy { "${it.id.value}@${it.schemaVersion.wireValue}" },
    )

    internal val canonicalValue: String = buildString {
        append(AdapterDescriptorCodec.encode(indexedDescriptor))
        append('|').append(descriptorReference.value)
        append('|').append(artifactReference?.value ?: "-")
        append('|').append(knowledgeState.name)
        append('|').append(this@AdapterRegistryRecord.connectionMethods.joinToString(",", transform = ::connectionWireValue))
        append('|').append(
            this@AdapterRegistryRecord.extensionSchemas.joinToString(",") {
                "${it.id.value}@${it.schemaVersion.wireValue}"
            },
        )
    }

    companion object {
        fun create(
            descriptor: AdapterDescriptor,
            descriptorReference: CompatibilityEvidenceReference,
            artifactReference: AdapterArtifactReference?,
            knowledgeState: AdapterKnowledgeState,
            connectionMethods: List<ConnectionMethodReference>,
            extensionSchemas: List<AdapterExtensionSchemaClaim>,
        ): AdapterRegistryModelResult<AdapterRegistryRecord> {
            val connectionKeys = connectionMethods.map(::connectionWireValue)
            if (connectionKeys.distinct().size != connectionKeys.size) {
                return AdapterRegistryModelResult.Failure(AdapterRegistryModelError.DUPLICATE_CONNECTION_METHOD)
            }
            val extensionKeys = extensionSchemas.map { it.id.value }
            if (extensionKeys.distinct().size != extensionKeys.size) {
                return AdapterRegistryModelResult.Failure(AdapterRegistryModelError.DUPLICATE_EXTENSION_SCHEMA)
            }
            return AdapterRegistryModelResult.Success(
                AdapterRegistryRecord(
                    copyDescriptor(descriptor),
                    descriptorReference,
                    artifactReference,
                    knowledgeState,
                    connectionMethods.toList(),
                    extensionSchemas.toList(),
                ),
            )
        }
    }
}

class AdapterRegistrySnapshot internal constructor(
    val revision: AdapterRegistryRevision,
    records: List<AdapterRegistryRecord>,
) {
    val records: List<AdapterRegistryRecord> = Collections.unmodifiableList(
        records.sortedWith(adapterRecordComparator),
    )

    fun exact(key: AdapterRegistryKey): AdapterRegistryRecord? = records.firstOrNull { it.key == key }

    fun versions(adapterId: AdapterId): List<AdapterRegistryRecord> = immutableRecords(
        records.filter { it.key.adapterId == adapterId },
    )

    fun byProviderFamily(family: ProviderFamily): List<AdapterRegistryRecord> = immutableRecords(
        records.filter { record -> record.indexedDescriptor.manifestSupport.any { it.providerFamily == family } },
    )

    fun byCapability(capability: CapabilityReference): List<AdapterRegistryRecord> = immutableRecords(
        records.filter { capability in it.indexedDescriptor.supportedCapabilities },
    )
}

data class AdapterManifestRegistryQuery(
    val resolvedManifest: ResolvedManifestDefinition,
    val hugoRuntime: HugoRuntimeEvidence,
    val providerVersions: List<ProviderVersionEvidence>,
)

data class AdapterCompatibilityCandidate(
    val record: AdapterRegistryRecord,
    val report: CompatibilityReport,
)

class AdapterCompatibilityCandidateSet internal constructor(candidates: List<AdapterCompatibilityCandidate>) {
    val candidates: List<AdapterCompatibilityCandidate> = Collections.unmodifiableList(
        candidates.sortedWith(compareBy({ it.record.key.adapterId.wireValue }, { it.record.key.version })),
    )
    val satisfyingCandidates: List<AdapterCompatibilityCandidate> = Collections.unmodifiableList(
        this.candidates.filter {
            it.report.status == CompatibilityReportStatus.COMPATIBLE ||
                it.report.status == CompatibilityReportStatus.PARTIALLY_COMPATIBLE
        },
    )
    val unknownCandidates: List<AdapterCompatibilityCandidate> = Collections.unmodifiableList(
        this.candidates.filter { it.report.status == CompatibilityReportStatus.UNKNOWN },
    )
}

sealed interface AdapterRegistryMutationResult {
    val snapshot: AdapterRegistrySnapshot

    data class Registered(
        val record: AdapterRegistryRecord,
        override val snapshot: AdapterRegistrySnapshot,
    ) : AdapterRegistryMutationResult

    data class Replaced(
        val previous: AdapterRegistryRecord,
        val replacement: AdapterRegistryRecord,
        override val snapshot: AdapterRegistrySnapshot,
    ) : AdapterRegistryMutationResult

    data class Removed(
        val record: AdapterRegistryRecord,
        override val snapshot: AdapterRegistrySnapshot,
    ) : AdapterRegistryMutationResult

    data class Duplicate(
        val record: AdapterRegistryRecord,
        override val snapshot: AdapterRegistrySnapshot,
    ) : AdapterRegistryMutationResult

    data class Conflict(
        val key: AdapterRegistryKey,
        val reason: AdapterRegistryConflict,
        override val snapshot: AdapterRegistrySnapshot,
    ) : AdapterRegistryMutationResult

    data class StaleRevision(
        val expected: AdapterRegistryRevision,
        val actual: AdapterRegistryRevision,
        override val snapshot: AdapterRegistrySnapshot,
    ) : AdapterRegistryMutationResult

    data class NotFound(
        val key: AdapterRegistryKey,
        override val snapshot: AdapterRegistrySnapshot,
    ) : AdapterRegistryMutationResult

    data class Rejected(
        val reason: AdapterRegistryRejection,
        override val snapshot: AdapterRegistrySnapshot,
    ) : AdapterRegistryMutationResult
}

enum class AdapterRegistryConflict {
    PACKAGE_DIGEST,
    DESCRIPTOR,
    INDEX_METADATA,
    EXPECTED_DIGEST,
}

enum class AdapterRegistryRejection {
    REPLACEMENT_IDENTITY_MISMATCH,
    REVISION_EXHAUSTED,
}

sealed interface AdapterRegistryModelResult<out T> {
    data class Success<T>(val value: T) : AdapterRegistryModelResult<T>
    data class Failure(val error: AdapterRegistryModelError) : AdapterRegistryModelResult<Nothing>
}

enum class AdapterRegistryModelError {
    INVALID_REVISION,
    INVALID_ARTIFACT_REFERENCE,
    DUPLICATE_CONNECTION_METHOD,
    DUPLICATE_EXTENSION_SCHEMA,
}

internal val adapterRecordComparator = compareBy<AdapterRegistryRecord>(
    { it.key.adapterId.wireValue },
    { it.key.version },
)

internal fun immutableRecords(records: List<AdapterRegistryRecord>): List<AdapterRegistryRecord> =
    Collections.unmodifiableList(records.sortedWith(adapterRecordComparator))

internal fun connectionWireValue(reference: ConnectionMethodReference): String =
    "${reference.id.value}@${reference.version.value}"

internal fun AdapterRegistryRecord.extensionSupport(): List<ExtensionSchemaSupport> = extensionSchemas.map {
    ExtensionSchemaSupport(it.id, it.schemaVersion, descriptorReference)
}

private fun copyDescriptor(descriptor: AdapterDescriptor): AdapterDescriptor = when (
    val result = AdapterDescriptorCodec.decode(AdapterDescriptorCodec.encode(descriptor))
) {
    is AdapterResult.Success -> result.value
    is AdapterResult.Failure -> error("Validated H012 descriptor failed canonical copy: ${result.error}")
}
