package io.redi.robot.discovery.api

import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderFamily
import java.util.UUID

/** Per-observation UUID. It is not a provider, device, account, or transport identity. */
@JvmInline
value class DiscoveryObservationId private constructor(val value: String) {
    companion object {
        fun fromUuid(uuid: UUID): DiscoveryObservationId = DiscoveryObservationId(uuid.toString())

        fun parse(raw: String): DiscoveryObservationResult<DiscoveryObservationId> =
            canonicalUuid(raw)?.let { DiscoveryObservationResult.Success(DiscoveryObservationId(it)) }
                ?: DiscoveryObservationResult.Failure(DiscoveryObservationError.InvalidObservationId)
    }
}

/** Extensible, source-neutral classification such as `bluetooth.le` or `network.mdns`. */
@JvmInline
value class DiscoverySourceType private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$")

        fun parse(raw: String): DiscoveryObservationResult<DiscoverySourceType> = if (
            raw.length in 3..80 && pattern.matches(raw)
        ) {
            DiscoveryObservationResult.Success(DiscoverySourceType(raw))
        } else {
            DiscoveryObservationResult.Failure(DiscoveryObservationError.InvalidSourceType)
        }
    }
}

/** Opaque local reference to the host that produced the observation, not a Hugo or provider ID. */
@JvmInline
value class DiscoveryHostReference private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]{2,63}(?:_[a-z0-9]{2,63})*$")

        fun parse(raw: String): DiscoveryObservationResult<DiscoveryHostReference> = if (
            pattern.matches(raw)
        ) {
            DiscoveryObservationResult.Success(DiscoveryHostReference(raw))
        } else {
            DiscoveryObservationResult.Failure(DiscoveryObservationError.InvalidHostReference)
        }
    }
}

/** Bounded human hint. It deliberately rejects common stable transport/account identifier forms. */
@JvmInline
value class DiscoveryDisplayHint private constructor(val value: String) {
    companion object {
        fun create(raw: String): DiscoveryObservationResult<DiscoveryDisplayHint> = safeText(raw, 80)
            ?.let { DiscoveryObservationResult.Success(DiscoveryDisplayHint(it)) }
            ?: DiscoveryObservationResult.Failure(DiscoveryObservationError.InvalidDisplayHint)
    }
}

/** Semantic protocol or service hint, never a raw address, UUID, packet, or endpoint. */
@JvmInline
value class DiscoveryProtocolServiceHint private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$")

        fun parse(raw: String): DiscoveryObservationResult<DiscoveryProtocolServiceHint> = if (
            raw.length in 3..80 && pattern.matches(raw)
        ) {
            DiscoveryObservationResult.Success(DiscoveryProtocolServiceHint(raw))
        } else {
            DiscoveryObservationResult.Failure(DiscoveryObservationError.InvalidProtocolServiceHint)
        }
    }
}

@JvmInline
value class DiscoveryEvidenceKind private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$")

        fun parse(raw: String): DiscoveryObservationResult<DiscoveryEvidenceKind> = if (
            raw.length in 3..80 && pattern.matches(raw)
        ) {
            DiscoveryObservationResult.Success(DiscoveryEvidenceKind(raw))
        } else {
            DiscoveryObservationResult.Failure(DiscoveryObservationError.InvalidEvidenceKind)
        }
    }
}

@JvmInline
value class DiscoveryEvidenceSummary private constructor(val value: String) {
    companion object {
        fun create(raw: String): DiscoveryObservationResult<DiscoveryEvidenceSummary> = safeText(raw, 160)
            ?.let { DiscoveryObservationResult.Success(DiscoveryEvidenceSummary(it)) }
            ?: DiscoveryObservationResult.Failure(DiscoveryObservationError.InvalidEvidenceSummary)
    }
}

/** Confidence expresses source evidence quality only; it does not resolve provider identity. */
@JvmInline
value class DiscoveryConfidence private constructor(val value: Double) {
    companion object {
        fun create(raw: Double): DiscoveryObservationResult<DiscoveryConfidence> = if (
            raw.isFinite() && raw in 0.0..1.0
        ) {
            DiscoveryObservationResult.Success(DiscoveryConfidence(raw))
        } else {
            DiscoveryObservationResult.Failure(DiscoveryObservationError.InvalidConfidence)
        }
    }
}

enum class DiscoveryPrivacyClassification(val wireValue: String) {
    PUBLIC("public"),
    PRIVATE("private"),
    SENSITIVE("sensitive"),
    ;

    companion object {
        fun fromWireValue(value: String): DiscoveryPrivacyClassification? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

/**
 * Source-local correlation handle after a source has protected/redacted its raw identifier.
 * It cannot contain an address, endpoint, account ID, or serial-shaped value.
 */
@JvmInline
value class OpaqueDiscoveryReference private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]{2,63}(?:_[a-z0-9]{2,63})*$")

        fun parse(raw: String): DiscoveryObservationResult<OpaqueDiscoveryReference> = if (
            pattern.matches(raw)
        ) {
            DiscoveryObservationResult.Success(OpaqueDiscoveryReference(raw))
        } else {
            DiscoveryObservationResult.Failure(DiscoveryObservationError.InvalidOpaqueSourceReference)
        }
    }
}

@JvmInline
value class DiscoveryMetadataKey private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$")
        private val privateTerms = setOf(
            "account",
            "address",
            "credential",
            "endpoint",
            "ip",
            "mac",
            "password",
            "secret",
            "serial",
            "token",
        )

        fun parse(raw: String): DiscoveryObservationResult<DiscoveryMetadataKey> = when {
            raw.length !in 3..80 || !pattern.matches(raw) -> DiscoveryObservationResult.Failure(
                DiscoveryObservationError.InvalidMetadataKey,
            )
            raw.split('.', '_', '-').any { it in privateTerms } -> DiscoveryObservationResult.Failure(
                DiscoveryObservationError.PrivateMetadataKey,
            )
            else -> DiscoveryObservationResult.Success(DiscoveryMetadataKey(raw))
        }
    }
}

@JvmInline
value class DiscoveryMetadataValue private constructor(val value: String) {
    companion object {
        fun create(raw: String): DiscoveryObservationResult<DiscoveryMetadataValue> = safeText(raw, 128)
            ?.let { DiscoveryObservationResult.Success(DiscoveryMetadataValue(it)) }
            ?: DiscoveryObservationResult.Failure(DiscoveryObservationError.InvalidMetadataValue)
    }
}

data class DiscoveryEvidence(
    val kind: DiscoveryEvidenceKind,
    val observedAt: MonotonicTimestampMs,
    val summary: DiscoveryEvidenceSummary?,
)

/** Safe diagnostics projection: it omits [DiscoveryObservation.sourceLocalReference]. */
data class RedactedDiscoveryObservation(
    val observationId: DiscoveryObservationId,
    val sourceType: DiscoverySourceType,
    val observingHost: DiscoveryHostReference,
    val observedAt: MonotonicTimestampMs,
    val expiresAt: MonotonicTimestampMs,
    val displayHint: DiscoveryDisplayHint?,
    val providerFamilyHint: ProviderFamily?,
    val protocolServiceHints: Set<DiscoveryProtocolServiceHint>,
    val evidence: List<DiscoveryEvidence>,
    val confidence: DiscoveryConfidence,
    val privacyClassification: DiscoveryPrivacyClassification,
    val metadata: Map<DiscoveryMetadataKey, DiscoveryMetadataValue>,
)

/**
 * Passive evidence that a possible provider exists near an observing host.
 *
 * This value intentionally has no ProviderIdentity, trust, permission, connection, registry,
 * capability-availability, or active-body field. H022 and later layers may correlate observations,
 * but H021 never promotes one into an identified or usable provider.
 */
class DiscoveryObservation private constructor(
    val observationId: DiscoveryObservationId,
    val sourceType: DiscoverySourceType,
    val observingHost: DiscoveryHostReference,
    val observedAt: MonotonicTimestampMs,
    val expiresAt: MonotonicTimestampMs,
    val displayHint: DiscoveryDisplayHint?,
    val providerFamilyHint: ProviderFamily?,
    protocolServiceHints: List<DiscoveryProtocolServiceHint>,
    evidence: List<DiscoveryEvidence>,
    val confidence: DiscoveryConfidence,
    val privacyClassification: DiscoveryPrivacyClassification,
    val sourceLocalReference: OpaqueDiscoveryReference?,
    metadata: Map<DiscoveryMetadataKey, DiscoveryMetadataValue>,
) {
    val protocolServiceHints: Set<DiscoveryProtocolServiceHint> = protocolServiceHints.toSet()
    val evidence: List<DiscoveryEvidence> = evidence.toList()
    val metadata: Map<DiscoveryMetadataKey, DiscoveryMetadataValue> = metadata.toMap()

    fun isFreshAt(now: MonotonicTimestampMs): Boolean = now.value < expiresAt.value

    fun redacted(): RedactedDiscoveryObservation = RedactedDiscoveryObservation(
        observationId = observationId,
        sourceType = sourceType,
        observingHost = observingHost,
        observedAt = observedAt,
        expiresAt = expiresAt,
        displayHint = displayHint,
        providerFamilyHint = providerFamilyHint,
        protocolServiceHints = protocolServiceHints,
        evidence = evidence,
        confidence = confidence,
        privacyClassification = privacyClassification,
        metadata = metadata,
    )

    companion object {
        private const val maxProtocolHints = 16
        private const val maxEvidence = 16
        private const val maxMetadata = 24

        @Suppress("LongParameterList")
        fun create(
            observationId: DiscoveryObservationId,
            sourceType: DiscoverySourceType,
            observingHost: DiscoveryHostReference,
            observedAt: MonotonicTimestampMs,
            expiresAt: MonotonicTimestampMs,
            displayHint: DiscoveryDisplayHint?,
            providerFamilyHint: ProviderFamily?,
            protocolServiceHints: List<DiscoveryProtocolServiceHint>,
            evidence: List<DiscoveryEvidence>,
            confidence: DiscoveryConfidence,
            privacyClassification: DiscoveryPrivacyClassification,
            sourceLocalReference: OpaqueDiscoveryReference?,
            metadata: Map<DiscoveryMetadataKey, DiscoveryMetadataValue>,
        ): DiscoveryObservationResult<DiscoveryObservation> = when {
            expiresAt.value <= observedAt.value -> DiscoveryObservationResult.Failure(
                DiscoveryObservationError.InvalidExpiry,
            )
            protocolServiceHints.size > maxProtocolHints -> DiscoveryObservationResult.Failure(
                DiscoveryObservationError.TooManyProtocolServiceHints(maxProtocolHints),
            )
            protocolServiceHints.distinct().size != protocolServiceHints.size -> DiscoveryObservationResult.Failure(
                DiscoveryObservationError.DuplicateProtocolServiceHint,
            )
            evidence.isEmpty() -> DiscoveryObservationResult.Failure(DiscoveryObservationError.MissingEvidence)
            evidence.size > maxEvidence -> DiscoveryObservationResult.Failure(
                DiscoveryObservationError.TooManyEvidenceItems(maxEvidence),
            )
            evidence.distinct().size != evidence.size -> DiscoveryObservationResult.Failure(
                DiscoveryObservationError.DuplicateEvidence,
            )
            metadata.size > maxMetadata -> DiscoveryObservationResult.Failure(
                DiscoveryObservationError.TooManyMetadataEntries(maxMetadata),
            )
            privacyClassification == DiscoveryPrivacyClassification.PUBLIC && sourceLocalReference != null ->
                DiscoveryObservationResult.Failure(DiscoveryObservationError.PublicPrivacyWithLocalReference)
            else -> DiscoveryObservationResult.Success(
                DiscoveryObservation(
                    observationId = observationId,
                    sourceType = sourceType,
                    observingHost = observingHost,
                    observedAt = observedAt,
                    expiresAt = expiresAt,
                    displayHint = displayHint,
                    providerFamilyHint = providerFamilyHint,
                    protocolServiceHints = protocolServiceHints,
                    evidence = evidence,
                    confidence = confidence,
                    privacyClassification = privacyClassification,
                    sourceLocalReference = sourceLocalReference,
                    metadata = metadata,
                ),
            )
        }
    }
}

sealed interface DiscoveryObservationResult<out T> {
    data class Success<T>(val value: T) : DiscoveryObservationResult<T>

    data class Failure(val error: DiscoveryObservationError) : DiscoveryObservationResult<Nothing>
}

sealed interface DiscoveryObservationError {
    data object InvalidObservationId : DiscoveryObservationError
    data object InvalidSourceType : DiscoveryObservationError
    data object InvalidHostReference : DiscoveryObservationError
    data object InvalidDisplayHint : DiscoveryObservationError
    data object InvalidProtocolServiceHint : DiscoveryObservationError
    data object InvalidEvidenceKind : DiscoveryObservationError
    data object InvalidEvidenceSummary : DiscoveryObservationError
    data object InvalidConfidence : DiscoveryObservationError
    data object InvalidOpaqueSourceReference : DiscoveryObservationError
    data object InvalidMetadataKey : DiscoveryObservationError
    data object PrivateMetadataKey : DiscoveryObservationError
    data object InvalidMetadataValue : DiscoveryObservationError
    data object InvalidExpiry : DiscoveryObservationError
    data object MissingEvidence : DiscoveryObservationError
    data class TooManyProtocolServiceHints(val maximum: Int) : DiscoveryObservationError
    data object DuplicateProtocolServiceHint : DiscoveryObservationError
    data class TooManyEvidenceItems(val maximum: Int) : DiscoveryObservationError
    data object DuplicateEvidence : DiscoveryObservationError
    data class TooManyMetadataEntries(val maximum: Int) : DiscoveryObservationError
    data object PublicPrivacyWithLocalReference : DiscoveryObservationError
    data class UnsupportedSchemaVersion(val found: String) : DiscoveryObservationError
    data class MissingField(val field: String) : DiscoveryObservationError
    data class UnexpectedFields(val fields: Set<String>) : DiscoveryObservationError
    data class InvalidDocument(val field: String) : DiscoveryObservationError
}

private val macAddressPattern = Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
private val ipv4AddressPattern = Regex("\\b(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})(?:\\.(?:25[0-5]|2[0-4][0-9]|1?[0-9]{1,2})){3}\\b")
private val endpointPattern = Regex("(?i)(https?://|wss?://|\\b[a-z][a-z0-9+.-]*://|@)")

private fun safeText(raw: String, maximumLength: Int): String? {
    val normalized = raw.trim()
    return normalized.takeIf {
        it.isNotEmpty() &&
            it.length <= maximumLength &&
            it.none(Char::isISOControl) &&
            !macAddressPattern.containsMatchIn(it) &&
            !ipv4AddressPattern.containsMatchIn(it) &&
            !endpointPattern.containsMatchIn(it)
    }
}

private fun canonicalUuid(raw: String): String? = runCatching {
    UUID.fromString(raw).toString().takeIf { it == raw }
}.getOrNull()
