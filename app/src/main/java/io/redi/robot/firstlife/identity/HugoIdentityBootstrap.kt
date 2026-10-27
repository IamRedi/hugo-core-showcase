package io.redi.robot.firstlife.identity

import io.redi.robot.hugo.identity.HugoIdentity
import io.redi.robot.hugo.identity.HugoIdentityCodec
import io.redi.robot.hugo.identity.HugoIdentityError
import io.redi.robot.hugo.identity.HugoIdentityId
import io.redi.robot.hugo.identity.HugoIdentityResult
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Android-free persistence boundary for the unchanged H003 identity document. */
interface HugoIdentityBootstrapStorage {
    fun read(): HugoIdentityStorageReadResult

    /** Atomically creates storage or returns the concurrently established document. */
    fun createIfAbsent(encodedIdentity: String): HugoIdentityStorageCreateResult

    /** Destructive operation reachable only through an explicit recovery workflow. */
    fun reset(): HugoIdentityStorageResetResult
}

sealed interface HugoIdentityStorageReadResult {
    data object Missing : HugoIdentityStorageReadResult
    data class Content(val encodedIdentity: String) : HugoIdentityStorageReadResult
    data class UnsupportedVersion(val found: String) : HugoIdentityStorageReadResult
    data object Corrupt : HugoIdentityStorageReadResult
    data object Unavailable : HugoIdentityStorageReadResult
}

sealed interface HugoIdentityStorageCreateResult {
    data object Created : HugoIdentityStorageCreateResult
    data class Existing(val readResult: HugoIdentityStorageReadResult) : HugoIdentityStorageCreateResult
    data object Unavailable : HugoIdentityStorageCreateResult
}

enum class HugoIdentityStorageResetResult { RESET, FAILED }

fun interface HugoIdentityEntropySource {
    fun create(): HugoIdentity

    companion object {
        val SECURE_RANDOM = HugoIdentityEntropySource {
            HugoIdentity(HugoIdentityId.fromUuid(UUID.randomUUID()))
        }
    }
}

@JvmInline
value class HugoBootstrapRunReference private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun newReference(): HugoBootstrapRunReference = HugoBootstrapRunReference(UUID.randomUUID().toString())

        fun fromUuid(uuid: UUID): HugoBootstrapRunReference = HugoBootstrapRunReference(uuid.toString())
    }
}

@JvmInline
value class HugoSafeDisplayIdentity(val value: String) {
    override fun toString(): String = value

    companion object {
        internal fun from(identity: HugoIdentity): HugoSafeDisplayIdentity {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(identity.id.value.toByteArray(StandardCharsets.UTF_8))
                .take(4)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                .uppercase()
            return HugoSafeDisplayIdentity("HUGO-$digest")
        }
    }
}

enum class HugoIdentityBootstrapStatus {
    CREATED,
    RESTORED,
    STORAGE_UNAVAILABLE,
    CORRUPT_IDENTITY,
    UNSUPPORTED_SCHEMA,
}

enum class HugoIdentityRecoveryState {
    NONE,
    RETRY_STORAGE,
    EXPLICIT_RESET_REQUIRED,
}

/** Slice-owned startup view. It deliberately contains no provider, body, user, or session data. */
data class HugoIdentityBootstrapSnapshot(
    val identity: HugoIdentity?,
    val status: HugoIdentityBootstrapStatus,
    val runReference: HugoBootstrapRunReference,
    val safeDisplayIdentity: HugoSafeDisplayIdentity?,
    val recoveryState: HugoIdentityRecoveryState,
) {
    init {
        val ready = status == HugoIdentityBootstrapStatus.CREATED || status == HugoIdentityBootstrapStatus.RESTORED
        require(ready == (identity != null)) { "identity exists only for a ready bootstrap" }
        require((identity != null) == (safeDisplayIdentity != null)) { "display evidence follows identity readiness" }
        require((recoveryState == HugoIdentityRecoveryState.NONE) == ready) {
            "only ready bootstrap has no recovery action"
        }
    }
}

enum class HugoIdentityResetReason {
    CORRUPT_STORAGE,
    UNSUPPORTED_SCHEMA,
    OPERATOR_RECOVERY,
}

/** Construction is intentionally named as an explicit confirmation; FL002 exposes no UI action. */
class ExplicitHugoIdentityReset private constructor(val reason: HugoIdentityResetReason) {
    companion object {
        fun afterUserConfirmation(reason: HugoIdentityResetReason): ExplicitHugoIdentityReset =
            ExplicitHugoIdentityReset(reason)
    }
}

sealed interface HugoIdentityResetOutcome {
    data class Completed(val runReference: HugoBootstrapRunReference) : HugoIdentityResetOutcome
    data class Failed(val runReference: HugoBootstrapRunReference) : HugoIdentityResetOutcome
}

/**
 * Create-once bootstrap over H003. Stored-but-unreadable state always fails closed and never asks
 * the entropy source for a replacement identity.
 */
class HugoIdentityBootstrapper(
    private val storage: HugoIdentityBootstrapStorage,
    private val entropy: HugoIdentityEntropySource = HugoIdentityEntropySource.SECURE_RANDOM,
) {
    @Synchronized
    fun bootstrap(runReference: HugoBootstrapRunReference): HugoIdentityBootstrapSnapshot =
        when (val stored = storage.read()) {
            HugoIdentityStorageReadResult.Missing -> createOnce(runReference)
            else -> snapshotFromRead(stored, runReference, HugoIdentityBootstrapStatus.RESTORED)
        }

    @Synchronized
    fun reset(
        confirmation: ExplicitHugoIdentityReset,
        runReference: HugoBootstrapRunReference,
    ): HugoIdentityResetOutcome {
        @Suppress("UNUSED_VARIABLE")
        val auditedReason = confirmation.reason
        return when (storage.reset()) {
            HugoIdentityStorageResetResult.RESET -> HugoIdentityResetOutcome.Completed(runReference)
            HugoIdentityStorageResetResult.FAILED -> HugoIdentityResetOutcome.Failed(runReference)
        }
    }

    private fun createOnce(runReference: HugoBootstrapRunReference): HugoIdentityBootstrapSnapshot {
        val candidate = entropy.create()
        val encoded = HugoIdentityCodec.encode(candidate)
        return when (val created = storage.createIfAbsent(encoded)) {
            HugoIdentityStorageCreateResult.Created -> ready(candidate, HugoIdentityBootstrapStatus.CREATED, runReference)
            is HugoIdentityStorageCreateResult.Existing ->
                snapshotFromRead(created.readResult, runReference, HugoIdentityBootstrapStatus.RESTORED)
            HugoIdentityStorageCreateResult.Unavailable -> unavailable(runReference)
        }
    }

    private fun snapshotFromRead(
        stored: HugoIdentityStorageReadResult,
        runReference: HugoBootstrapRunReference,
        successStatus: HugoIdentityBootstrapStatus,
    ): HugoIdentityBootstrapSnapshot = when (stored) {
        is HugoIdentityStorageReadResult.Content -> when (val decoded = HugoIdentityCodec.decode(stored.encodedIdentity)) {
            is HugoIdentityResult.Success -> ready(decoded.value, successStatus, runReference)
            is HugoIdentityResult.Failure -> when (decoded.error) {
                is HugoIdentityError.UnsupportedContractVersion -> blocked(
                    HugoIdentityBootstrapStatus.UNSUPPORTED_SCHEMA,
                    HugoIdentityRecoveryState.EXPLICIT_RESET_REQUIRED,
                    runReference,
                )
                else -> corrupt(runReference)
            }
        }
        is HugoIdentityStorageReadResult.UnsupportedVersion -> blocked(
            HugoIdentityBootstrapStatus.UNSUPPORTED_SCHEMA,
            HugoIdentityRecoveryState.EXPLICIT_RESET_REQUIRED,
            runReference,
        )
        HugoIdentityStorageReadResult.Corrupt -> corrupt(runReference)
        HugoIdentityStorageReadResult.Unavailable -> unavailable(runReference)
        HugoIdentityStorageReadResult.Missing -> unavailable(runReference)
    }

    private fun ready(
        identity: HugoIdentity,
        status: HugoIdentityBootstrapStatus,
        runReference: HugoBootstrapRunReference,
    ) = HugoIdentityBootstrapSnapshot(
        identity = identity,
        status = status,
        runReference = runReference,
        safeDisplayIdentity = HugoSafeDisplayIdentity.from(identity),
        recoveryState = HugoIdentityRecoveryState.NONE,
    )

    private fun corrupt(runReference: HugoBootstrapRunReference) = blocked(
        HugoIdentityBootstrapStatus.CORRUPT_IDENTITY,
        HugoIdentityRecoveryState.EXPLICIT_RESET_REQUIRED,
        runReference,
    )

    private fun unavailable(runReference: HugoBootstrapRunReference) = blocked(
        HugoIdentityBootstrapStatus.STORAGE_UNAVAILABLE,
        HugoIdentityRecoveryState.RETRY_STORAGE,
        runReference,
    )

    private fun blocked(
        status: HugoIdentityBootstrapStatus,
        recoveryState: HugoIdentityRecoveryState,
        runReference: HugoBootstrapRunReference,
    ) = HugoIdentityBootstrapSnapshot(null, status, runReference, null, recoveryState)
}
