package io.redi.robot.platform.storage

import android.content.Context
import io.redi.robot.body.looi.firstlife.LooiFirstLifeDefinition
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.discovery.identity.IdentityEvidenceResult
import io.redi.robot.discovery.identity.ProtectedIdentityEvidenceReference
import io.redi.robot.manifest.domain.CommonManifestResult
import io.redi.robot.manifest.domain.ManifestSchemaVersion
import io.redi.robot.manifest.domain.ManifestVersion
import io.redi.robot.manifest.domain.SpecializedManifestKind
import io.redi.robot.provider.storage.ProviderConfigurationReference
import io.redi.robot.provider.storage.ProviderInstanceConfigurationSource
import io.redi.robot.provider.storage.ProviderInstancePersistResult
import io.redi.robot.provider.storage.ProviderInstanceProvenance
import io.redi.robot.provider.storage.ProviderInstanceRecord
import io.redi.robot.provider.storage.ProviderInstanceRestoreResult
import io.redi.robot.provider.storage.ProviderInstanceStore
import io.redi.robot.provider.storage.ProviderInstanceStorePersistence
import io.redi.robot.provider.storage.ProviderInstanceStoreResult
import io.redi.robot.provider.storage.ProviderStoreEntryReference
import io.redi.robot.provider.storage.ProviderStoreKeyVersion
import io.redi.robot.provider.storage.ProviderStoreTimestamp
import io.redi.robot.provider.storage.StoredAdapterReference
import io.redi.robot.provider.storage.StoredManifestReference
import java.security.MessageDigest
import java.util.UUID

sealed interface LooiProviderIdentityResult {
    data class Available(
        val identity: ProviderIdentity,
        val protectedBinding: ProtectedIdentityEvidenceReference,
        val restored: Boolean,
    ) : LooiProviderIdentityResult
    data class Failed(val reason: LooiProviderIdentityFailure) : LooiProviderIdentityResult
}

enum class LooiProviderIdentityFailure { STORAGE_UNAVAILABLE, CORRUPT_OR_UNVERIFIABLE, CONFLICTING_BINDING }

/** H032 continuity over authenticated app-private storage. Raw BLE identifiers never leave this class call. */
class LooiProviderIdentityRepository(context: Context) {
    private val persistence = ProviderInstanceStorePersistence(
        AtomicFileProviderInstanceStorage(context.noBackupFilesDir.resolve("hugo/provider-instances")),
        AndroidKeystoreProviderInstanceProtection(keyVersion()),
    )

    @Synchronized
    fun resolve(localBleHandle: String, nowEpochMs: Long): LooiProviderIdentityResult {
        val binding = protectedBinding(localBleHandle)
        val restored = when (val result = persistence.restore()) {
            is ProviderInstanceRestoreResult.Restored -> result.store
            is ProviderInstanceRestoreResult.RecoveredFromBackup -> {
                if (persistence.commitRecovery(result) !is ProviderInstancePersistResult.Persisted) {
                    return LooiProviderIdentityResult.Failed(LooiProviderIdentityFailure.STORAGE_UNAVAILABLE)
                }
                result.store
            }
            ProviderInstanceRestoreResult.Absent -> ProviderInstanceStore.empty()
            is ProviderInstanceRestoreResult.Failed -> return LooiProviderIdentityResult.Failed(
                LooiProviderIdentityFailure.CORRUPT_OR_UNVERIFIABLE,
            )
        }
        val matches = restored.snapshot().records.filter { binding in it.protectedIdentityBindings }
        if (matches.size > 1) return LooiProviderIdentityResult.Failed(LooiProviderIdentityFailure.CONFLICTING_BINDING)
        matches.singleOrNull()?.let { return LooiProviderIdentityResult.Available(identity(it.instanceId), binding, true) }

        val record = record(binding, nowEpochMs)
        if (restored.create(record, restored.snapshot().revision) !is ProviderInstanceStoreResult.Success) {
            return LooiProviderIdentityResult.Failed(LooiProviderIdentityFailure.CONFLICTING_BINDING)
        }
        if (persistence.persist(restored) !is ProviderInstancePersistResult.Persisted) {
            return LooiProviderIdentityResult.Failed(LooiProviderIdentityFailure.STORAGE_UNAVAILABLE)
        }
        return LooiProviderIdentityResult.Available(identity(record.instanceId), binding, false)
    }

    private fun record(binding: ProtectedIdentityEvidenceReference, now: Long): ProviderInstanceRecord = store(
        ProviderInstanceRecord.create(
            entryReference = ProviderStoreEntryReference.fromUuid(UUID.randomUUID()),
            instanceId = ProviderInstanceId.fromUuid(UUID.randomUUID()),
            definitionId = LooiFirstLifeDefinition.definitionId,
            family = ProviderFamily.BODY_ADAPTER,
            alias = alias(),
            manifest = StoredManifestReference(
                LooiFirstLifeDefinition.definitionId,
                manifest(ManifestSchemaVersion.parse("1.0")),
                manifest(ManifestVersion.parse("1.0.0")),
                LooiFirstLifeDefinition.commonDocument.digest,
                SpecializedManifestKind.BODY,
            ),
            adapter = StoredAdapterReference(
                LooiFirstLifeDefinition.adapterId,
                LooiFirstLifeDefinition.adapterVersion,
            ),
            trustRecordReferences = emptySet(),
            calibrationProfileReferences = emptySet(),
            protectedIdentityBindings = setOf(binding),
            firstSeenAt = store(ProviderStoreTimestamp.create(now)),
            lastSeenAt = store(ProviderStoreTimestamp.create(now)),
            lastSuccessfulUseAt = null,
            provenance = ProviderInstanceProvenance(
                ProviderInstanceConfigurationSource.BUILT_IN,
                store(ProviderConfigurationReference.parse("hugo.builtin.looi-reference")),
            ),
        ),
    )

    private fun identity(instanceId: ProviderInstanceId) = ProviderIdentity(
        LooiFirstLifeDefinition.definitionId,
        instanceId,
        ProviderFamily.BODY_ADAPTER,
        alias(),
    )

    private fun protectedBinding(raw: String): ProtectedIdentityEvidenceReference {
        val digest = MessageDigest.getInstance("SHA-256").digest("hugo.looi.binding:$raw".toByteArray())
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        var buffer = 0
        var bits = 0
        val token = buildString {
            digest.forEach { byte ->
                buffer = (buffer shl 8) or (byte.toInt() and 0xff)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    append(alphabet[(buffer shr bits) and 31])
                }
            }
            if (bits > 0) append(alphabet[(buffer shl (5 - bits)) and 31])
        }.take(52)
        return identityEvidence(ProtectedIdentityEvidenceReference.parse("iref_$token"))
    }

    private fun alias() = provider(RedactedProviderAlias.parse("LOOI Reference Body"))
    private fun keyVersion() = when (val result = ProviderStoreKeyVersion.create(1)) {
        is io.redi.robot.provider.storage.ProviderProtectionResult.Success -> result.value
        is io.redi.robot.provider.storage.ProviderProtectionResult.Failure -> error(result.error)
    }
    private fun <T> store(result: ProviderInstanceStoreResult<T>): T =
        (result as ProviderInstanceStoreResult.Success).value
    private fun <T> provider(result: ProviderIdentityResult<T>): T =
        (result as ProviderIdentityResult.Success).value
    private fun <T> manifest(result: CommonManifestResult<T>): T =
        (result as CommonManifestResult.Success).value
    private fun <T> identityEvidence(result: IdentityEvidenceResult<T>): T =
        (result as IdentityEvidenceResult.Success).value
}
