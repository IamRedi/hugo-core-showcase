package io.redi.robot.discovery.identity

import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.discovery.api.DiscoveryConfidence
import io.redi.robot.discovery.api.DiscoveryHostReference
import io.redi.robot.discovery.api.DiscoveryObservationId
import io.redi.robot.discovery.api.DiscoverySourceType
import java.util.Collections
import java.util.UUID

/** Project-owned evidence identity. It is not a provider, device, account, or transport identity. */
@JvmInline
value class IdentityEvidenceId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun fromUuid(uuid: UUID): IdentityEvidenceId = IdentityEvidenceId(uuid.toString())

        fun parse(raw: String): IdentityEvidenceResult<IdentityEvidenceId> = canonicalUuid(raw)
            ?.let { IdentityEvidenceResult.Success(IdentityEvidenceId(it)) }
            ?: identityEvidenceFailure(IdentityEvidenceError.InvalidEvidenceId)
    }
}

/** Safe semantic producer name, not a network endpoint, package digest, credential, or authority. */
@JvmInline
value class IdentityEvidenceProducer private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$")

        fun parse(raw: String): IdentityEvidenceResult<IdentityEvidenceProducer> = if (
            raw.length in 3..80 && pattern.matches(raw)
        ) {
            IdentityEvidenceResult.Success(IdentityEvidenceProducer(raw))
        } else {
            identityEvidenceFailure(IdentityEvidenceError.InvalidProducer)
        }
    }
}

/**
 * Lookup reference for protected local matching material. Only a project-owned base32 token may
 * cross this boundary; a MAC, IP, serial, endpoint, account ID, or raw external identifier cannot.
 */
@JvmInline
value class ProtectedIdentityEvidenceReference private constructor(val value: String) {
    override fun toString(): String = "[protected]"

    companion object {
        private val pattern = Regex("^iref_[a-z2-7]{16,52}$")

        fun parse(raw: String): IdentityEvidenceResult<ProtectedIdentityEvidenceReference> = if (
            pattern.matches(raw)
        ) {
            IdentityEvidenceResult.Success(ProtectedIdentityEvidenceReference(raw))
        } else {
            identityEvidenceFailure(IdentityEvidenceError.InvalidProtectedReference)
        }
    }
}

enum class IdentityEvidenceStrength(val wireValue: String) {
    WEAK("weak"),
    MODERATE("moderate"),
    STRONG("strong"),
}

enum class IdentityVerificationLevel(val wireValue: String) {
    UNVERIFIED("unverified"),
    SOURCE_ASSERTED("source_asserted"),
    AUTHENTICATED("authenticated"),
    PREVIOUSLY_VERIFIED("previously_verified"),
    MANUALLY_CONFIRMED("manually_confirmed"),
    ;

    companion object {
        fun fromWireValue(raw: String): IdentityVerificationLevel? = entries.firstOrNull {
            it.wireValue == raw
        }
    }
}

/** Evidence classes own strength and allowed verification; confidence cannot upgrade either. */
enum class IdentityEvidenceType(
    val wireValue: String,
    val strength: IdentityEvidenceStrength,
    internal val allowedVerification: Set<IdentityVerificationLevel>,
    internal val requiresProtectedReference: Boolean,
) {
    DISPLAY_NAME_HINT(
        "display_name_hint",
        IdentityEvidenceStrength.WEAK,
        setOf(IdentityVerificationLevel.UNVERIFIED, IdentityVerificationLevel.SOURCE_ASSERTED),
        false,
    ),
    MANUFACTURER_HINT(
        "manufacturer_hint",
        IdentityEvidenceStrength.WEAK,
        setOf(IdentityVerificationLevel.UNVERIFIED, IdentityVerificationLevel.SOURCE_ASSERTED),
        false,
    ),
    PROTOCOL_CLASSIFICATION(
        "protocol_classification",
        IdentityEvidenceStrength.MODERATE,
        setOf(IdentityVerificationLevel.SOURCE_ASSERTED, IdentityVerificationLevel.AUTHENTICATED),
        false,
    ),
    PROVIDER_DEFINITION_BINDING(
        "provider_definition_binding",
        IdentityEvidenceStrength.MODERATE,
        setOf(
            IdentityVerificationLevel.SOURCE_ASSERTED,
            IdentityVerificationLevel.AUTHENTICATED,
            IdentityVerificationLevel.PREVIOUSLY_VERIFIED,
            IdentityVerificationLevel.MANUALLY_CONFIRMED,
        ),
        false,
    ),
    PROTECTED_IDENTIFIER_BINDING(
        "protected_identifier_binding",
        IdentityEvidenceStrength.STRONG,
        setOf(IdentityVerificationLevel.AUTHENTICATED, IdentityVerificationLevel.PREVIOUSLY_VERIFIED),
        true,
    ),
    AUTHENTICATED_INTEGRATION_BINDING(
        "authenticated_integration_binding",
        IdentityEvidenceStrength.STRONG,
        setOf(IdentityVerificationLevel.AUTHENTICATED),
        true,
    ),
    VERIFIED_PROVIDER_BINDING(
        "verified_provider_binding",
        IdentityEvidenceStrength.STRONG,
        setOf(IdentityVerificationLevel.PREVIOUSLY_VERIFIED),
        true,
    ),
    MANUAL_CONFIRMATION(
        "manual_confirmation",
        IdentityEvidenceStrength.STRONG,
        setOf(IdentityVerificationLevel.MANUALLY_CONFIRMED),
        false,
    ),
    ;

    companion object {
        fun fromWireValue(raw: String): IdentityEvidenceType? = entries.firstOrNull {
            it.wireValue == raw
        }
    }
}

enum class IdentityEvidenceDisposition(val wireValue: String) {
    SUPPORTS("supports"),
    EXCLUDES("excludes"),
    ;

    companion object {
        fun fromWireValue(raw: String): IdentityEvidenceDisposition? = entries.firstOrNull {
            it.wireValue == raw
        }
    }
}

/** Candidate named by evidence. An instance target is meaningful only against an H015 snapshot. */
class IdentityEvidenceTarget private constructor(
    val definitionId: ProviderDefinitionId?,
    val instanceId: ProviderInstanceId?,
    val family: ProviderFamily?,
) {
    fun redacted(): RedactedIdentityEvidenceTarget = RedactedIdentityEvidenceTarget(
        definitionId = definitionId,
        family = family,
        referencesKnownInstance = instanceId != null,
    )

    override fun toString(): String = redacted().toString()

    companion object {
        fun create(
            definitionId: ProviderDefinitionId?,
            instanceId: ProviderInstanceId?,
            family: ProviderFamily?,
        ): IdentityEvidenceResult<IdentityEvidenceTarget> = if (
            definitionId == null && instanceId == null && family == null
        ) {
            identityEvidenceFailure(IdentityEvidenceError.EmptyTarget)
        } else {
            IdentityEvidenceResult.Success(IdentityEvidenceTarget(definitionId, instanceId, family))
        }
    }
}

data class RedactedIdentityEvidenceTarget(
    val definitionId: ProviderDefinitionId?,
    val family: ProviderFamily?,
    val referencesKnownInstance: Boolean,
)

/** Provenance is checked against every referenced H021 observation by the resolver. */
class IdentityEvidenceProvenance private constructor(
    val observingHost: DiscoveryHostReference,
    sourceTypes: Set<DiscoverySourceType>,
    val producer: IdentityEvidenceProducer,
) {
    val sourceTypes: Set<DiscoverySourceType> = Collections.unmodifiableSet(
        sourceTypes.sortedBy { it.value }.toSet(),
    )

    companion object {
        fun create(
            observingHost: DiscoveryHostReference,
            sourceTypes: Set<DiscoverySourceType>,
            producer: IdentityEvidenceProducer,
        ): IdentityEvidenceResult<IdentityEvidenceProvenance> = if (sourceTypes.isEmpty()) {
            identityEvidenceFailure(IdentityEvidenceError.MissingProvenanceSource)
        } else {
            IdentityEvidenceResult.Success(IdentityEvidenceProvenance(observingHost, sourceTypes, producer))
        }
    }
}

data class RedactedIdentityEvidence(
    val evidenceId: IdentityEvidenceId,
    val observationIds: Set<DiscoveryObservationId>,
    val provenance: IdentityEvidenceProvenance,
    val evidenceType: IdentityEvidenceType,
    val strength: IdentityEvidenceStrength,
    val confidence: DiscoveryConfidence,
    val verificationLevel: IdentityVerificationLevel,
    val disposition: IdentityEvidenceDisposition,
    val target: RedactedIdentityEvidenceTarget,
    val contradictions: Set<IdentityEvidenceId>,
    val observedAt: MonotonicTimestampMs,
    val expiresAt: MonotonicTimestampMs,
    val hasProtectedValueReference: Boolean,
)

/**
 * Immutable protected identity evidence. It is a claim for deterministic evaluation, not identity
 * truth, trust, authorization, registration, connection, or an instruction to merge providers.
 */
class IdentityEvidence private constructor(
    val evidenceId: IdentityEvidenceId,
    observationIds: Set<DiscoveryObservationId>,
    val provenance: IdentityEvidenceProvenance,
    val evidenceType: IdentityEvidenceType,
    val confidence: DiscoveryConfidence,
    val verificationLevel: IdentityVerificationLevel,
    val protectedValueReference: ProtectedIdentityEvidenceReference?,
    val disposition: IdentityEvidenceDisposition,
    val target: IdentityEvidenceTarget,
    contradictions: Set<IdentityEvidenceId>,
    val observedAt: MonotonicTimestampMs,
    val expiresAt: MonotonicTimestampMs,
) {
    val observationIds: Set<DiscoveryObservationId> = Collections.unmodifiableSet(
        observationIds.sortedBy { it.value }.toSet(),
    )
    val contradictions: Set<IdentityEvidenceId> = Collections.unmodifiableSet(
        contradictions.sortedBy { it.value }.toSet(),
    )
    val strength: IdentityEvidenceStrength get() = evidenceType.strength

    fun isFreshAt(now: MonotonicTimestampMs): Boolean = now.value < expiresAt.value

    fun redacted(): RedactedIdentityEvidence = RedactedIdentityEvidence(
        evidenceId = evidenceId,
        observationIds = observationIds,
        provenance = provenance,
        evidenceType = evidenceType,
        strength = strength,
        confidence = confidence,
        verificationLevel = verificationLevel,
        disposition = disposition,
        target = target.redacted(),
        contradictions = contradictions,
        observedAt = observedAt,
        expiresAt = expiresAt,
        hasProtectedValueReference = protectedValueReference != null,
    )

    override fun toString(): String = redacted().toString()

    companion object {
        private const val maximumObservationReferences = 32
        private const val maximumContradictions = 32

        @Suppress("LongParameterList")
        fun create(
            evidenceId: IdentityEvidenceId,
            observationIds: Set<DiscoveryObservationId>,
            provenance: IdentityEvidenceProvenance,
            evidenceType: IdentityEvidenceType,
            confidence: DiscoveryConfidence,
            verificationLevel: IdentityVerificationLevel,
            protectedValueReference: ProtectedIdentityEvidenceReference?,
            disposition: IdentityEvidenceDisposition,
            target: IdentityEvidenceTarget,
            contradictions: Set<IdentityEvidenceId>,
            observedAt: MonotonicTimestampMs,
            expiresAt: MonotonicTimestampMs,
        ): IdentityEvidenceResult<IdentityEvidence> = when {
            observationIds.isEmpty() -> identityEvidenceFailure(IdentityEvidenceError.MissingObservationReference)
            observationIds.size > maximumObservationReferences -> identityEvidenceFailure(
                IdentityEvidenceError.TooManyObservationReferences(maximumObservationReferences),
            )
            contradictions.size > maximumContradictions -> identityEvidenceFailure(
                IdentityEvidenceError.TooManyContradictions(maximumContradictions),
            )
            evidenceId in contradictions -> identityEvidenceFailure(IdentityEvidenceError.SelfContradiction)
            expiresAt.value <= observedAt.value -> identityEvidenceFailure(IdentityEvidenceError.InvalidExpiry)
            verificationLevel !in evidenceType.allowedVerification -> identityEvidenceFailure(
                IdentityEvidenceError.InvalidVerificationForEvidenceType,
            )
            evidenceType.requiresProtectedReference && protectedValueReference == null -> identityEvidenceFailure(
                IdentityEvidenceError.MissingProtectedReference,
            )
            else -> IdentityEvidenceResult.Success(
                IdentityEvidence(
                    evidenceId = evidenceId,
                    observationIds = observationIds,
                    provenance = provenance,
                    evidenceType = evidenceType,
                    confidence = confidence,
                    verificationLevel = verificationLevel,
                    protectedValueReference = protectedValueReference,
                    disposition = disposition,
                    target = target,
                    contradictions = contradictions,
                    observedAt = observedAt,
                    expiresAt = expiresAt,
                ),
            )
        }
    }
}

sealed interface IdentityEvidenceResult<out T> {
    data class Success<T>(val value: T) : IdentityEvidenceResult<T>

    data class Failure(val error: IdentityEvidenceError) : IdentityEvidenceResult<Nothing>
}

sealed interface IdentityEvidenceError {
    data object InvalidEvidenceId : IdentityEvidenceError
    data object InvalidProducer : IdentityEvidenceError
    data object InvalidProtectedReference : IdentityEvidenceError
    data object EmptyTarget : IdentityEvidenceError
    data object MissingProvenanceSource : IdentityEvidenceError
    data object MissingObservationReference : IdentityEvidenceError
    data class TooManyObservationReferences(val maximum: Int) : IdentityEvidenceError
    data class TooManyContradictions(val maximum: Int) : IdentityEvidenceError
    data object SelfContradiction : IdentityEvidenceError
    data object InvalidExpiry : IdentityEvidenceError
    data object InvalidVerificationForEvidenceType : IdentityEvidenceError
    data object MissingProtectedReference : IdentityEvidenceError
    data class UnsupportedSchemaVersion(val found: String) : IdentityEvidenceError
    data class MissingField(val field: String) : IdentityEvidenceError
    data class UnexpectedFields(val fields: Set<String>) : IdentityEvidenceError
    data class InvalidDocument(val field: String) : IdentityEvidenceError
}

private fun <T> identityEvidenceFailure(error: IdentityEvidenceError): IdentityEvidenceResult<T> =
    IdentityEvidenceResult.Failure(error)

private fun canonicalUuid(raw: String): String? = runCatching {
    UUID.fromString(raw).toString().takeIf { it == raw }
}.getOrNull()
