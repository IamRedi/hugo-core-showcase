package io.redi.robot.provider.diagnostic

import io.redi.robot.adapter.api.AdapterId
import io.redi.robot.adapter.api.AdapterVersion
import io.redi.robot.adapter.compatibility.CompatibilityCheckStatus
import io.redi.robot.adapter.compatibility.CompatibilityDimension
import io.redi.robot.adapter.compatibility.CompatibilityFindingCode
import io.redi.robot.adapter.compatibility.CompatibilityReportStatus
import io.redi.robot.adapter.registry.AdapterKnowledgeState
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.negotiation.CapabilityNegotiationReason
import io.redi.robot.capability.negotiation.CapabilityNegotiationStatus
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.connection.api.ConnectionAvailability
import io.redi.robot.connection.api.ConnectionMethodReference
import io.redi.robot.connection.api.ConnectionReadiness
import io.redi.robot.discovery.identity.IdentityResolutionReason
import io.redi.robot.discovery.identity.IdentityResolutionStatus
import io.redi.robot.manifest.resolution.ManifestResolutionReason
import io.redi.robot.manifest.resolution.ManifestResolutionStatus
import io.redi.robot.provider.calibration.CalibrationItemId
import io.redi.robot.provider.calibration.CalibrationNegotiationReadinessStatus
import io.redi.robot.provider.calibration.CalibrationStatus
import io.redi.robot.provider.runtime.ProviderOrchestrationPhase
import io.redi.robot.provider.runtime.ProviderOrchestrationStopReason
import java.security.MessageDigest
import java.util.Collections

data class ProviderDiagnosticSchemaVersion private constructor(val major: Int, val minor: Int) {
    val wireValue: String = "$major.$minor"

    companion object {
        val CURRENT = ProviderDiagnosticSchemaVersion(1, 0)
        private val pattern = Regex("^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$")

        fun parse(raw: String): ProviderDiagnosticResult<ProviderDiagnosticSchemaVersion> {
            if (!pattern.matches(raw)) return diagnosticFailure(ProviderDiagnosticError.INVALID_SCHEMA_VERSION)
            val parts = raw.split('.').map(String::toInt)
            if (parts[0] != CURRENT.major) {
                return diagnosticFailure(ProviderDiagnosticError.UNSUPPORTED_SCHEMA_MAJOR(parts[0]))
            }
            if (parts[1] != CURRENT.minor) {
                return diagnosticFailure(ProviderDiagnosticError.UNSUPPORTED_SCHEMA_MINOR(parts[1]))
            }
            return ProviderDiagnosticResult.Success(ProviderDiagnosticSchemaVersion(parts[0], parts[1]))
        }
    }
}

/** Export-local project-owned correlation label derived one-way from source references. */
@JvmInline
value class ProviderDiagnosticId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val pattern = Regex("^diag_[0-9a-f]{24}$")
        fun parse(raw: String): ProviderDiagnosticResult<ProviderDiagnosticId> =
            if (pattern.matches(raw)) ProviderDiagnosticResult.Success(ProviderDiagnosticId(raw))
            else diagnosticFailure(ProviderDiagnosticError.INVALID_DIAGNOSTIC_ID)

        fun derive(scope: String, sourceReference: String): ProviderDiagnosticId {
            require(scope.matches(Regex("^[a-z][a-z0-9._-]{2,63}$")))
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$scope\u0000$sourceReference".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return ProviderDiagnosticId("diag_${digest.take(24)}")
        }
    }
}

/** Bounded semantic/provenance text. Stable addresses, UUIDs, accounts, URLs, and secret labels fail. */
@JvmInline
value class DiagnosticSafeValue private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): ProviderDiagnosticResult<DiagnosticSafeValue> = if (isSafeDiagnosticValue(raw)) {
            ProviderDiagnosticResult.Success(DiagnosticSafeValue(raw))
        } else diagnosticFailure(ProviderDiagnosticError.UNSAFE_VALUE)
    }
}

enum class DiagnosticFreshness { FRESH, STALE, UNKNOWN }

enum class DiagnosticLifecycleState {
    INACTIVE,
    DISCOVERED,
    IDENTIFIED,
    CONNECTING,
    CONNECTED,
    NEGOTIATING,
    REGISTERED,
    CANCELLING,
    DISCONNECTING,
    CANCELLED,
    DISCONNECTED,
    FAILED,
}

enum class DiagnosticEvidenceSource {
    DISCOVERY,
    IDENTITY,
    MANIFEST,
    COMPATIBILITY,
    ADAPTER_REGISTRY,
    CONNECTION,
    PROVIDER_RUNTIME,
    NEGOTIATION,
    AUTHORIZATION,
    CALIBRATION,
}

data class ProviderSummaryDiagnostic(
    val diagnosticId: ProviderDiagnosticId,
    val definitionId: ProviderDefinitionId?,
    val family: ProviderFamily?,
    val alias: RedactedProviderAlias?,
    val identityKnown: Boolean,
)

data class LifecycleDiagnostic(
    val orchestrationPhase: ProviderOrchestrationPhase,
    val stopReason: ProviderOrchestrationStopReason?,
    val lifecycleState: DiagnosticLifecycleState,
    val generation: Long?,
)

data class IdentityDiagnostic(
    val status: IdentityResolutionStatus,
    val reason: IdentityResolutionReason,
    val candidateCount: Int,
    val evidenceReferences: List<ProviderDiagnosticId>,
    val freshness: DiagnosticFreshness,
)

data class ManifestDiagnostic(
    val status: ManifestResolutionStatus,
    val reason: ManifestResolutionReason,
    val candidateReferences: List<ProviderDiagnosticId>,
)

data class CompatibilityCheckDiagnostic(
    val dimension: CompatibilityDimension,
    val checkId: DiagnosticSafeValue,
    val status: CompatibilityCheckStatus,
    val finding: CompatibilityFindingCode,
    val requiredValues: List<DiagnosticSafeValue>,
    val observedValues: List<DiagnosticSafeValue>,
    val evidenceReferences: List<ProviderDiagnosticId>,
)

data class CompatibilityDiagnostic(
    val status: CompatibilityReportStatus,
    val checks: List<CompatibilityCheckDiagnostic>,
)

data class AdapterDiagnostic(
    val known: Boolean,
    val knowledgeState: AdapterKnowledgeState?,
    val adapterId: AdapterId?,
    val adapterVersion: AdapterVersion?,
    val descriptorReference: ProviderDiagnosticId?,
)

data class ConnectionDiagnostic(
    val availability: ConnectionAvailability?,
    val readiness: ConnectionReadiness,
    val method: ConnectionMethodReference?,
    val sessionReference: ProviderDiagnosticId?,
    val freshness: DiagnosticFreshness,
)

data class NegotiatedCapabilitiesDiagnostic(
    val status: CapabilityNegotiationStatus,
    val reason: CapabilityNegotiationReason,
    val capabilities: List<CapabilityReference>,
    val negotiationReference: ProviderDiagnosticId,
    val freshness: DiagnosticFreshness,
)

/** Uses H033 codes exactly; it defines no parallel blocked-state taxonomy. */
data class BlockedStateDiagnostic(
    val kind: DiagnosticSafeValue,
    val resolutionAvailability: DiagnosticSafeValue,
)

data class CalibrationReadinessDiagnostic(
    val readiness: CalibrationNegotiationReadinessStatus,
    val status: CalibrationStatus?,
    val profileReference: ProviderDiagnosticId?,
    val unmetItems: List<CalibrationItemId>,
)

data class ProviderDiagnosticEvidence(
    val reference: ProviderDiagnosticId,
    val source: DiagnosticEvidenceSource,
    val provenance: DiagnosticSafeValue,
    val observedAt: Long?,
    val validUntil: Long?,
    val freshness: DiagnosticFreshness,
)

class ProviderDiagnosticSnapshot private constructor(
    val schemaVersion: ProviderDiagnosticSchemaVersion,
    val generatedAt: Long,
    val summary: ProviderSummaryDiagnostic,
    val lifecycle: LifecycleDiagnostic?,
    val identity: IdentityDiagnostic?,
    val manifest: ManifestDiagnostic?,
    val compatibility: CompatibilityDiagnostic?,
    val adapter: AdapterDiagnostic?,
    val connection: ConnectionDiagnostic?,
    val negotiation: NegotiatedCapabilitiesDiagnostic?,
    val blockedState: BlockedStateDiagnostic?,
    val calibration: CalibrationReadinessDiagnostic?,
    evidence: List<ProviderDiagnosticEvidence>,
) {
    val evidence: List<ProviderDiagnosticEvidence> = Collections.unmodifiableList(
        evidence.sortedWith(compareBy({ it.source.ordinal }, { it.reference.value })),
    )
    val grantsTrust: Boolean get() = false
    val grantsPermission: Boolean get() = false
    val grantsSafetyApproval: Boolean get() = false
    val provesPhysicalFeasibility: Boolean get() = false
    val executesCapability: Boolean get() = false
    val selectsActiveBody: Boolean get() = false

    companion object {
        @Suppress("LongParameterList", "LongMethod")
        fun create(
            schemaVersion: ProviderDiagnosticSchemaVersion = ProviderDiagnosticSchemaVersion.CURRENT,
            generatedAt: Long,
            summary: ProviderSummaryDiagnostic,
            lifecycle: LifecycleDiagnostic? = null,
            identity: IdentityDiagnostic? = null,
            manifest: ManifestDiagnostic? = null,
            compatibility: CompatibilityDiagnostic? = null,
            adapter: AdapterDiagnostic? = null,
            connection: ConnectionDiagnostic? = null,
            negotiation: NegotiatedCapabilitiesDiagnostic? = null,
            blockedState: BlockedStateDiagnostic? = null,
            calibration: CalibrationReadinessDiagnostic? = null,
            evidence: List<ProviderDiagnosticEvidence> = emptyList(),
        ): ProviderDiagnosticResult<ProviderDiagnosticSnapshot> {
            val checks = compatibility?.checks.orEmpty()
            val capabilities = negotiation?.capabilities.orEmpty()
            val unmetItems = calibration?.unmetItems.orEmpty()
            val allReferences = evidence.map { it.reference }
            return when {
                schemaVersion != ProviderDiagnosticSchemaVersion.CURRENT ->
                    diagnosticFailure(ProviderDiagnosticError.UNSUPPORTED_SCHEMA_MINOR(schemaVersion.minor))
                generatedAt < 0 -> diagnosticFailure(ProviderDiagnosticError.INVALID_TIMESTAMP)
                summary.alias?.let { !isSafeAlias(it.value) } == true ->
                    diagnosticFailure(ProviderDiagnosticError.UNSAFE_ALIAS)
                summary.identityKnown && (summary.definitionId == null || summary.family == null) ->
                    diagnosticFailure(ProviderDiagnosticError.CONTRADICTORY_SECTION)
                lifecycle?.generation?.let { it < 0 } == true ->
                    diagnosticFailure(ProviderDiagnosticError.INVALID_TIMESTAMP)
                identity?.candidateCount?.let { it < 0 || it > MAX_CANDIDATES } == true ->
                    diagnosticFailure(ProviderDiagnosticError.BOUNDS_EXCEEDED)
                checks.size > MAX_CHECKS || capabilities.size > MAX_CAPABILITIES ||
                    unmetItems.size > MAX_CALIBRATION_ITEMS || evidence.size > MAX_EVIDENCE ->
                    diagnosticFailure(ProviderDiagnosticError.BOUNDS_EXCEEDED)
                checks.map { "${it.dimension}:${it.checkId.value}" }.distinct().size != checks.size ->
                    diagnosticFailure(ProviderDiagnosticError.DUPLICATE_ENTRY)
                capabilities.distinct().size != capabilities.size || unmetItems.distinct().size != unmetItems.size ||
                    allReferences.distinct().size != allReferences.size ->
                    diagnosticFailure(ProviderDiagnosticError.DUPLICATE_ENTRY)
                evidence.any { invalidEvidenceTime(it) } -> diagnosticFailure(ProviderDiagnosticError.INVALID_TIMESTAMP)
                adapter?.known == false && listOf(
                    adapter.knowledgeState,
                    adapter.adapterId,
                    adapter.adapterVersion,
                    adapter.descriptorReference,
                ).any { it != null } -> diagnosticFailure(ProviderDiagnosticError.CONTRADICTORY_SECTION)
                adapter?.known == true && (
                    adapter.knowledgeState == null || adapter.adapterId == null || adapter.adapterVersion == null
                    ) ->
                    diagnosticFailure(ProviderDiagnosticError.CONTRADICTORY_SECTION)
                connection?.readiness == ConnectionReadiness.CHANNEL_ESTABLISHED &&
                    (connection.sessionReference == null || connection.method == null) ->
                    diagnosticFailure(ProviderDiagnosticError.CONTRADICTORY_SECTION)
                else -> ProviderDiagnosticResult.Success(
                    ProviderDiagnosticSnapshot(
                        schemaVersion,
                        generatedAt,
                        summary,
                        lifecycle,
                        identity?.copy(evidenceReferences = immutableIds(identity.evidenceReferences)),
                        manifest?.copy(candidateReferences = immutableIds(manifest.candidateReferences)),
                        compatibility?.copy(checks = immutableChecks(checks)),
                        adapter,
                        connection,
                        negotiation?.copy(capabilities = immutableCapabilities(capabilities)),
                        blockedState,
                        calibration?.copy(unmetItems = immutableItems(unmetItems)),
                        evidence,
                    ),
                )
            }
        }

        private const val MAX_CANDIDATES = 64
        private const val MAX_CHECKS = 128
        private const val MAX_CAPABILITIES = 128
        private const val MAX_CALIBRATION_ITEMS = 64
        private const val MAX_EVIDENCE = 128
    }
}

sealed interface ProviderDiagnosticResult<out T> {
    data class Success<T>(val value: T) : ProviderDiagnosticResult<T>
    data class Failure(val error: ProviderDiagnosticError) : ProviderDiagnosticResult<Nothing>
}

sealed interface ProviderDiagnosticError {
    data object INVALID_SCHEMA_VERSION : ProviderDiagnosticError
    data class UNSUPPORTED_SCHEMA_MAJOR(val found: Int) : ProviderDiagnosticError
    data class UNSUPPORTED_SCHEMA_MINOR(val found: Int) : ProviderDiagnosticError
    data object INVALID_DIAGNOSTIC_ID : ProviderDiagnosticError
    data object UNSAFE_VALUE : ProviderDiagnosticError
    data object UNSAFE_ALIAS : ProviderDiagnosticError
    data object INVALID_TIMESTAMP : ProviderDiagnosticError
    data object BOUNDS_EXCEEDED : ProviderDiagnosticError
    data object DUPLICATE_ENTRY : ProviderDiagnosticError
    data object CONTRADICTORY_SECTION : ProviderDiagnosticError
    data object MALFORMED_DOCUMENT : ProviderDiagnosticError
    data class MISSING_FIELD(val field: String) : ProviderDiagnosticError
    data class UNEXPECTED_FIELDS(val fields: Set<String>) : ProviderDiagnosticError
    data class INVALID_FIELD(val field: String) : ProviderDiagnosticError
    data object OUTPUT_TOO_LARGE : ProviderDiagnosticError
}

internal fun <T> diagnosticFailure(error: ProviderDiagnosticError): ProviderDiagnosticResult<T> =
    ProviderDiagnosticResult.Failure(error)

private fun invalidEvidenceTime(value: ProviderDiagnosticEvidence): Boolean =
    value.observedAt?.let { it < 0 } == true || value.validUntil?.let { it < 0 } == true ||
        (value.observedAt != null && value.validUntil != null && value.validUntil <= value.observedAt)

private fun isSafeAlias(value: String): Boolean = value.length in 1..64 && value.none(Char::isISOControl) &&
    !stableIdentifierPattern.containsMatchIn(value) && !secretPattern.containsMatchIn(value)

private fun isSafeDiagnosticValue(value: String): Boolean = value.length in 1..160 &&
    value.none(Char::isISOControl) && !value.any(Char::isWhitespace) &&
    value.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._/@:+-]{0,159}$")) &&
    !stableIdentifierPattern.containsMatchIn(value) && !secretPattern.containsMatchIn(value) &&
    !value.contains("://")

private val stableIdentifierPattern = Regex(
    "(?i)(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|" +
        "(?:[0-9a-f]{2}:){5}[0-9a-f]{2}|(?:^|[^0-9])(?:[0-9]{1,3}[.]){3}[0-9]{1,3}(?:$|[^0-9])|" +
        "[^@\\s]+@[^@\\s]+[.][^@\\s]+)",
)
private val secretPattern = Regex(
    "(?i)(password|passwd|oauth|bearer|token|api[_-]?key|access[_-]?code|secret|serial|imei|endpoint)",
)

private fun immutableIds(values: List<ProviderDiagnosticId>): List<ProviderDiagnosticId> =
    Collections.unmodifiableList(values.distinct().sortedBy { it.value })
private fun immutableCapabilities(values: List<CapabilityReference>): List<CapabilityReference> =
    Collections.unmodifiableList(values.sortedBy { it.wireValue })
private fun immutableItems(values: List<CalibrationItemId>): List<CalibrationItemId> =
    Collections.unmodifiableList(values.sortedBy { it.value })
private fun immutableChecks(values: List<CompatibilityCheckDiagnostic>): List<CompatibilityCheckDiagnostic> =
    Collections.unmodifiableList(values.map { check ->
        check.copy(
            requiredValues = Collections.unmodifiableList(check.requiredValues.distinct().sortedBy { it.value }),
            observedValues = Collections.unmodifiableList(check.observedValues.distinct().sortedBy { it.value }),
            evidenceReferences = immutableIds(check.evidenceReferences),
        )
    }.sortedWith(compareBy({ it.dimension.ordinal }, { it.checkId.value })))
