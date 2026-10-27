package io.redi.robot.feature.controlcenter

import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.negotiation.CapabilityNegotiationStatus
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.context.provider.ActiveProviderContextSnapshot
import io.redi.robot.context.provider.ProviderContextAvailability
import io.redi.robot.context.provider.ProviderContextConnection
import io.redi.robot.provider.diagnostic.DiagnosticFreshness
import io.redi.robot.provider.diagnostic.ProviderDiagnosticSnapshot
import io.redi.robot.service.api.ServiceAuthenticationSnapshot
import io.redi.robot.service.api.ServiceAuthenticationState
import io.redi.robot.service.auth.CredentialEpochMillis
import io.redi.robot.service.auth.ServiceCredentialKnowledgeState
import io.redi.robot.service.auth.ServiceCredentialSnapshot
import io.redi.robot.trust.domain.ApprovalRecord
import io.redi.robot.trust.domain.ApprovalState
import io.redi.robot.trust.domain.PermissionGrant
import io.redi.robot.trust.domain.RevocationRecord
import io.redi.robot.trust.domain.RevocationTarget
import java.security.MessageDigest

data class ControlCenterServiceSource(
    val providerDefinition: ProviderDefinitionId,
    val authentication: ServiceAuthenticationSnapshot,
)

/** Accepted canonical read-only evidence. No callback or mutable repository crosses this input. */
data class ControlCenterCanonicalSources(
    val activeProviders: ActiveProviderContextSnapshot? = null,
    val providerDiagnostics: List<ProviderDiagnosticSnapshot> = emptyList(),
    val serviceAuthentication: List<ControlCenterServiceSource> = emptyList(),
    val credentialSnapshot: ServiceCredentialSnapshot? = null,
    val grants: List<PermissionGrant> = emptyList(),
    val approvals: List<ApprovalRecord> = emptyList(),
    val revocations: List<RevocationRecord> = emptyList(),
    val now: MonotonicTimestampMs,
    val credentialNow: CredentialEpochMillis,
    val error: ControlCenterSourceError? = null,
)

object ControlCenterProjector {
    fun project(sources: ControlCenterCanonicalSources): ControlCenterModelResult<ControlCenterSnapshot> {
        if (sources.error != null) return ControlCenterModelResult.Success(ControlCenterSnapshot.error(sources.error))

        val serviceGroups = sources.serviceAuthentication.groupBy { it.providerDefinition.value }
        if (serviceGroups.values.any { it.size > 1 }) {
            return ControlCenterModelResult.Failure(ControlCenterModelError.CONFLICTING_SOURCE_EVIDENCE)
        }
        val serviceByDefinition = serviceGroups
            .mapValues { (_, values) -> values.singleOrNull()?.authentication }
        val credentialByDefinition = sources.credentialSnapshot?.records.orEmpty()
            .groupBy { it.providerDefinitionId.value }

        val providers = mutableListOf<ControlCenterProviderItem>()
        val capabilities = mutableListOf<ControlCenterCapabilityItem>()
        val diagnostics = mutableListOf<ControlCenterDiagnosticItem>()
        val definitionsWithDiagnostics = sources.providerDiagnostics.mapNotNull { it.summary.definitionId?.value }.toSet()

        sources.providerDiagnostics.forEach { diagnostic ->
            val definition = diagnostic.summary.definitionId?.value
            val reference = ref("provider", diagnostic.summary.diagnosticId.value)
            val family = diagnostic.summary.family?.displayName() ?: "Unknown provider"
            val label = diagnostic.summary.alias?.value ?: family
            val auth = definition?.let(serviceByDefinition::get).toAuthStatus()
            val credential = definition?.let(credentialByDefinition::get).toCredentialStatus(sources.credentialNow)
            val blocked = diagnostic.blockedState?.kind?.value
            providers += ControlCenterProviderItem(
                reference,
                label,
                definition,
                family,
                providerStatus(diagnostic, auth),
                auth,
                credential,
                blocked,
            )
            diagnostic.negotiation?.let { negotiation ->
                negotiation.capabilities.forEach { capability ->
                    capabilities += ControlCenterCapabilityItem(
                        ref("capability", "${diagnostic.summary.diagnosticId.value}|${capability.wireValue}"),
                        label,
                        capability.wireValue,
                        negotiation.status.toControlCenterStatus(),
                    )
                }
            }
            diagnostics += diagnosticItem(diagnostic)
        }

        sources.activeProviders?.providers.orEmpty().forEach { active ->
            if (active.identity.definitionId.value !in definitionsWithDiagnostics) {
                val reference = ref("active-provider", active.identity.instanceId.value)
                providers += ControlCenterProviderItem(
                    reference,
                    active.identity.family.displayName(),
                    active.identity.definitionId.value,
                    active.identity.family.displayName(),
                    when {
                        active.connection != ProviderContextConnection.CONNECTED ->
                            ControlCenterProviderStatus.UNAVAILABLE
                        active.availability == ProviderContextAvailability.AVAILABLE ->
                            ControlCenterProviderStatus.CONNECTED
                        active.availability == ProviderContextAvailability.DEGRADED ->
                            ControlCenterProviderStatus.DEGRADED
                        else -> ControlCenterProviderStatus.UNAVAILABLE
                    },
                    serviceByDefinition[active.identity.definitionId.value].toAuthStatus(),
                    credentialByDefinition[active.identity.definitionId.value].toCredentialStatus(sources.credentialNow),
                    null,
                )
            }
        }

        sources.serviceAuthentication.forEach { service ->
            if (providers.none { it.definition == service.providerDefinition.value }) {
                providers += ControlCenterProviderItem(
                    ref("service", service.providerDefinition.value),
                    "Service provider",
                    service.providerDefinition.value,
                    ProviderFamily.SERVICE_CONNECTOR.displayName(),
                    if (service.authentication.state == ServiceAuthenticationState.REVOKED) {
                        ControlCenterProviderStatus.REVOKED
                    } else {
                        ControlCenterProviderStatus.KNOWN
                    },
                    service.authentication.toAuthStatus(),
                    credentialByDefinition[service.providerDefinition.value].toCredentialStatus(sources.credentialNow),
                    null,
                )
            }
        }

        val grantItems = sources.grants.map { grant ->
            ControlCenterGrantItem(
                ref("grant", grant.authorizationReference.value),
                grant.scope.capability.wireValue,
                grant.scope.intent.name,
                grant.lifetime.name,
                grantStatus(grant, sources.revocations, sources.now),
            )
        }
        val approvalItems = sources.approvals.map { approval ->
            ControlCenterApprovalItem(
                ref("approval", approval.id.value),
                approval.capability.wireValue,
                approval.requirement.name,
                approvalStatus(approval, sources.revocations, sources.now),
            )
        }

        val hasItems = listOf(providers, capabilities, grantItems, approvalItems, diagnostics).any { it.isNotEmpty() }
        return ControlCenterSnapshot.create(
            loadState = if (hasItems) ControlCenterLoadState.READY else ControlCenterLoadState.EMPTY,
            providers = providers,
            capabilities = capabilities,
            grants = grantItems,
            approvals = approvalItems,
            diagnostics = diagnostics,
            credentialRecordCount = sources.credentialSnapshot?.records?.size ?: 0,
        )
    }

    private fun providerStatus(
        diagnostic: ProviderDiagnosticSnapshot,
        auth: ControlCenterAuthStatus,
    ): ControlCenterProviderStatus = when {
        auth == ControlCenterAuthStatus.REVOKED -> ControlCenterProviderStatus.REVOKED
        diagnostic.blockedState != null -> ControlCenterProviderStatus.BLOCKED
        diagnostic.connection?.freshness == DiagnosticFreshness.STALE -> ControlCenterProviderStatus.DEGRADED
        diagnostic.connection?.sessionReference != null -> ControlCenterProviderStatus.CONNECTED
        diagnostic.summary.identityKnown -> ControlCenterProviderStatus.KNOWN
        else -> ControlCenterProviderStatus.UNAVAILABLE
    }

    private fun diagnosticItem(value: ProviderDiagnosticSnapshot): ControlCenterDiagnosticItem {
        val blocked = value.blockedState
        return if (blocked != null) {
            ControlCenterDiagnosticItem(
                ref("diagnostic", value.summary.diagnosticId.value),
                "Provider blocked",
                "${blocked.kind.value} · ${blocked.resolutionAvailability.value}",
                ControlCenterDiagnosticSeverity.BLOCKED,
            )
        } else {
            ControlCenterDiagnosticItem(
                ref("diagnostic", value.summary.diagnosticId.value),
                "Provider evidence",
                "Validated redacted diagnostic snapshot",
                ControlCenterDiagnosticSeverity.INFO,
            )
        }
    }

    private fun ServiceAuthenticationSnapshot?.toAuthStatus(): ControlCenterAuthStatus = when (this?.state) {
        null -> ControlCenterAuthStatus.NOT_APPLICABLE
        ServiceAuthenticationState.UNAUTHENTICATED -> ControlCenterAuthStatus.UNAUTHENTICATED
        ServiceAuthenticationState.AUTHENTICATED -> ControlCenterAuthStatus.AUTHENTICATED
        ServiceAuthenticationState.EXPIRED -> ControlCenterAuthStatus.EXPIRED
        ServiceAuthenticationState.REVOKED -> ControlCenterAuthStatus.REVOKED
        ServiceAuthenticationState.FAILED -> ControlCenterAuthStatus.FAILED
    }

    private fun List<io.redi.robot.service.auth.ServiceCredentialMetadata>?.toCredentialStatus(
        now: CredentialEpochMillis,
    ): ControlCenterCredentialStatus {
        val states = this.orEmpty().map { it.stateAt(now) }.toSet()
        return when {
            ServiceCredentialKnowledgeState.ACTIVE in states -> ControlCenterCredentialStatus.ACTIVE
            ServiceCredentialKnowledgeState.EXPIRED in states -> ControlCenterCredentialStatus.EXPIRED
            ServiceCredentialKnowledgeState.REVOKED in states -> ControlCenterCredentialStatus.REVOKED
            else -> ControlCenterCredentialStatus.NONE
        }
    }

    private fun CapabilityNegotiationStatus.toControlCenterStatus(): ControlCenterCapabilityStatus = when (this) {
        CapabilityNegotiationStatus.NEGOTIATED -> ControlCenterCapabilityStatus.NEGOTIATED
        CapabilityNegotiationStatus.PARTIALLY_NEGOTIATED -> ControlCenterCapabilityStatus.PARTIAL
        CapabilityNegotiationStatus.RUNTIME_UNAVAILABLE,
        CapabilityNegotiationStatus.PROVIDER_NOT_READY,
        CapabilityNegotiationStatus.STALE_SESSION,
        CapabilityNegotiationStatus.UNSUPPORTED,
        CapabilityNegotiationStatus.VERSION_MISMATCH,
        CapabilityNegotiationStatus.MISSING_PREREQUISITE,
        CapabilityNegotiationStatus.CONFLICTING_DECLARATION,
        CapabilityNegotiationStatus.FAILED,
        CapabilityNegotiationStatus.CANCELLED,
        -> ControlCenterCapabilityStatus.BLOCKED
    }

    private fun grantStatus(
        grant: PermissionGrant,
        revocations: List<RevocationRecord>,
        now: MonotonicTimestampMs,
    ): ControlCenterAuthorityStatus = when {
        revocations.any { (it.target as? RevocationTarget.Grant)?.authorizationReference == grant.authorizationReference } ->
            ControlCenterAuthorityStatus.REVOKED
        !grant.validity.contains(now) -> ControlCenterAuthorityStatus.EXPIRED
        else -> ControlCenterAuthorityStatus.ACTIVE
    }

    private fun approvalStatus(
        approval: ApprovalRecord,
        revocations: List<RevocationRecord>,
        now: MonotonicTimestampMs,
    ): ControlCenterAuthorityStatus = when {
        revocations.any { (it.target as? RevocationTarget.Approval)?.approvalId == approval.id } ->
            ControlCenterAuthorityStatus.REVOKED
        !approval.validity.contains(now) -> ControlCenterAuthorityStatus.EXPIRED
        approval.state == ApprovalState.APPROVED -> ControlCenterAuthorityStatus.ACTIVE
        approval.state == ApprovalState.DENIED -> ControlCenterAuthorityStatus.DENIED
        approval.state == ApprovalState.CONSUMED -> ControlCenterAuthorityStatus.CONSUMED
        else -> ControlCenterAuthorityStatus.REQUESTED
    }

    private fun ProviderFamily.displayName(): String = when (this) {
        ProviderFamily.BODY_ADAPTER -> "Body provider"
        ProviderFamily.DEVICE_ADAPTER -> "Device provider"
        ProviderFamily.SERVICE_CONNECTOR -> "Service provider"
    }

    private fun ref(scope: String, source: String): ControlCenterItemReference {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$scope\u0000$source".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return (ControlCenterItemReference.parse("cc_${digest.take(16)}") as ControlCenterModelResult.Success).value
    }
}
