package io.redi.robot.feature.controlcenter

import java.util.Collections

enum class ControlCenterSection { PROVIDERS, CAPABILITIES, GRANTS, APPROVALS, DIAGNOSTICS }

enum class ControlCenterLoadState { READY, EMPTY, ERROR }

enum class ControlCenterSourceError { UNAVAILABLE, STALE, CORRUPT, UNSUPPORTED }

enum class ControlCenterProviderStatus {
    KNOWN,
    AVAILABLE,
    CONNECTED,
    DEGRADED,
    BLOCKED,
    REVOKED,
    UNAVAILABLE,
}

enum class ControlCenterAuthStatus { NOT_APPLICABLE, UNAUTHENTICATED, AUTHENTICATED, EXPIRED, REVOKED, FAILED }

enum class ControlCenterCredentialStatus { NONE, ACTIVE, EXPIRED, REVOKED }

enum class ControlCenterCapabilityStatus { NEGOTIATED, PARTIAL, BLOCKED, UNKNOWN }

enum class ControlCenterAuthorityStatus { ACTIVE, EXPIRED, REVOKED, DENIED, CONSUMED, REQUESTED }

enum class ControlCenterDiagnosticSeverity { INFO, WARNING, BLOCKED }

/** One-way redacted reference. It is neither a source identifier nor authority. */
@JvmInline
value class ControlCenterItemReference private constructor(val value: String) : Comparable<ControlCenterItemReference> {
    override fun compareTo(other: ControlCenterItemReference): Int = value.compareTo(other.value)

    companion object {
        private val pattern = Regex("^cc_[0-9a-f]{16}$")

        fun parse(raw: String): ControlCenterModelResult<ControlCenterItemReference> = if (pattern.matches(raw)) {
            ControlCenterModelResult.Success(ControlCenterItemReference(raw))
        } else {
            ControlCenterModelResult.Failure(ControlCenterModelError.INVALID_REFERENCE)
        }
    }
}

data class ControlCenterProviderItem(
    val reference: ControlCenterItemReference,
    val label: String,
    val definition: String?,
    val family: String,
    val status: ControlCenterProviderStatus,
    val authentication: ControlCenterAuthStatus,
    val credential: ControlCenterCredentialStatus,
    val blockedReason: String?,
)

data class ControlCenterCapabilityItem(
    val reference: ControlCenterItemReference,
    val providerLabel: String,
    val capability: String,
    val status: ControlCenterCapabilityStatus,
)

data class ControlCenterGrantItem(
    val reference: ControlCenterItemReference,
    val capability: String,
    val permissionIntent: String,
    val lifetime: String,
    val status: ControlCenterAuthorityStatus,
)

data class ControlCenterApprovalItem(
    val reference: ControlCenterItemReference,
    val capability: String,
    val requirement: String,
    val status: ControlCenterAuthorityStatus,
)

data class ControlCenterDiagnosticItem(
    val reference: ControlCenterItemReference,
    val title: String,
    val detail: String,
    val severity: ControlCenterDiagnosticSeverity,
)

/** Immutable, redacted, presentation-only snapshot. */
class ControlCenterSnapshot private constructor(
    val loadState: ControlCenterLoadState,
    val sourceError: ControlCenterSourceError?,
    providers: List<ControlCenterProviderItem>,
    capabilities: List<ControlCenterCapabilityItem>,
    grants: List<ControlCenterGrantItem>,
    approvals: List<ControlCenterApprovalItem>,
    diagnostics: List<ControlCenterDiagnosticItem>,
    val credentialRecordCount: Int,
) {
    val providers: List<ControlCenterProviderItem> = immutable(providers.sortedBy { it.reference })
    val capabilities: List<ControlCenterCapabilityItem> = immutable(
        capabilities.sortedWith(compareBy({ it.capability }, { it.reference.value })),
    )
    val grants: List<ControlCenterGrantItem> = immutable(grants.sortedBy { it.reference })
    val approvals: List<ControlCenterApprovalItem> = immutable(approvals.sortedBy { it.reference })
    val diagnostics: List<ControlCenterDiagnosticItem> = immutable(
        diagnostics.sortedWith(compareBy({ it.severity.ordinal }, { it.reference.value })),
    )

    val permitsSourceEditing: Boolean = false
    val grantsTrust: Boolean = false
    val grantsAuthorization: Boolean = false
    val grantsApproval: Boolean = false
    val establishesConnection: Boolean = false
    val executesCapability: Boolean = false

    fun count(section: ControlCenterSection): Int = when (section) {
        ControlCenterSection.PROVIDERS -> providers.size
        ControlCenterSection.CAPABILITIES -> capabilities.size
        ControlCenterSection.GRANTS -> grants.size
        ControlCenterSection.APPROVALS -> approvals.size
        ControlCenterSection.DIAGNOSTICS -> diagnostics.size
    }

    companion object {
        const val MAXIMUM_ITEMS_PER_SECTION: Int = 128

        @Suppress("LongParameterList")
        fun create(
            loadState: ControlCenterLoadState,
            sourceError: ControlCenterSourceError? = null,
            providers: List<ControlCenterProviderItem> = emptyList(),
            capabilities: List<ControlCenterCapabilityItem> = emptyList(),
            grants: List<ControlCenterGrantItem> = emptyList(),
            approvals: List<ControlCenterApprovalItem> = emptyList(),
            diagnostics: List<ControlCenterDiagnosticItem> = emptyList(),
            credentialRecordCount: Int = 0,
        ): ControlCenterModelResult<ControlCenterSnapshot> {
            val allCounts = listOf(
                providers.size,
                capabilities.size,
                grants.size,
                approvals.size,
                diagnostics.size,
            )
            val hasItems = allCounts.any { it > 0 }
            val references = providers.map { it.reference } + capabilities.map { it.reference } +
                grants.map { it.reference } + approvals.map { it.reference } + diagnostics.map { it.reference }
            return when {
                sourceError != null && loadState != ControlCenterLoadState.ERROR ->
                    ControlCenterModelResult.Failure(ControlCenterModelError.CONTRADICTORY_LOAD_STATE)
                sourceError == null && loadState == ControlCenterLoadState.ERROR ->
                    ControlCenterModelResult.Failure(ControlCenterModelError.CONTRADICTORY_LOAD_STATE)
                loadState == ControlCenterLoadState.ERROR && hasItems ->
                    ControlCenterModelResult.Failure(ControlCenterModelError.CONTRADICTORY_LOAD_STATE)
                loadState == ControlCenterLoadState.EMPTY && hasItems ->
                    ControlCenterModelResult.Failure(ControlCenterModelError.CONTRADICTORY_LOAD_STATE)
                loadState == ControlCenterLoadState.READY && !hasItems ->
                    ControlCenterModelResult.Failure(ControlCenterModelError.CONTRADICTORY_LOAD_STATE)
                allCounts.any { it > MAXIMUM_ITEMS_PER_SECTION } || credentialRecordCount !in 0..MAXIMUM_ITEMS_PER_SECTION ->
                    ControlCenterModelResult.Failure(ControlCenterModelError.BOUNDS_EXCEEDED)
                references.distinct().size != references.size ->
                    ControlCenterModelResult.Failure(ControlCenterModelError.DUPLICATE_REFERENCE)
                providers.any { !safeText(it.label) || !safeOptionalText(it.definition) || !safeText(it.family) ||
                    !safeOptionalText(it.blockedReason) } ||
                    capabilities.any { !safeText(it.providerLabel) || !safeText(it.capability) } ||
                    grants.any { !safeText(it.capability) || !safeText(it.permissionIntent) || !safeText(it.lifetime) } ||
                    approvals.any { !safeText(it.capability) || !safeText(it.requirement) } ||
                    diagnostics.any { !safeText(it.title) || !safeText(it.detail) } ->
                    ControlCenterModelResult.Failure(ControlCenterModelError.UNSAFE_TEXT)
                else -> ControlCenterModelResult.Success(
                    ControlCenterSnapshot(
                        loadState,
                        sourceError,
                        providers,
                        capabilities,
                        grants,
                        approvals,
                        diagnostics,
                        credentialRecordCount,
                    ),
                )
            }
        }

        fun empty(): ControlCenterSnapshot = (create(ControlCenterLoadState.EMPTY) as
            ControlCenterModelResult.Success).value

        fun error(error: ControlCenterSourceError): ControlCenterSnapshot = (
            create(ControlCenterLoadState.ERROR, sourceError = error) as ControlCenterModelResult.Success
            ).value
    }
}

data class ControlCenterState(
    val selectedSection: ControlCenterSection,
    val snapshot: ControlCenterSnapshot,
)

sealed interface ControlCenterEvent {
    data class SelectSection(val section: ControlCenterSection) : ControlCenterEvent
    data class ReplaceSnapshot(val snapshot: ControlCenterSnapshot) : ControlCenterEvent
}

object ControlCenterReducer {
    fun reduce(state: ControlCenterState, event: ControlCenterEvent): ControlCenterState = when (event) {
        is ControlCenterEvent.SelectSection -> state.copy(selectedSection = event.section)
        is ControlCenterEvent.ReplaceSnapshot -> state.copy(snapshot = event.snapshot)
    }
}

sealed interface ControlCenterModelResult<out T> {
    data class Success<T>(val value: T) : ControlCenterModelResult<T>
    data class Failure(val error: ControlCenterModelError) : ControlCenterModelResult<Nothing>
}

enum class ControlCenterModelError {
    INVALID_REFERENCE,
    CONTRADICTORY_LOAD_STATE,
    BOUNDS_EXCEEDED,
    DUPLICATE_REFERENCE,
    UNSAFE_TEXT,
    CONFLICTING_SOURCE_EVIDENCE,
}

private fun safeOptionalText(value: String?): Boolean = value == null || safeText(value)

private fun safeText(value: String): Boolean = value.isNotBlank() && value.length <= 160 &&
    value.none(Char::isISOControl) && !sensitivePattern.containsMatchIn(value)

private val sensitivePattern = Regex(
    "(?i)(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|" +
        "(?:[0-9a-f]{2}:){5}[0-9a-f]{2}|password|passwd|bearer|api[_-]?key|access[_-]?token|secret)",
)

private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
