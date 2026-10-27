package io.redi.robot.trust.domain

import io.redi.robot.capability.api.CapabilityApprovalRequirement
import io.redi.robot.capability.api.CapabilityAuditId
import io.redi.robot.capability.api.CapabilityAuthorizationReference
import io.redi.robot.capability.api.CapabilityActorKind
import io.redi.robot.capability.api.CapabilityPermissionIntent
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.CapabilityRequestId
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderInstanceId

data class TrustSchemaVersion private constructor(val major: Int, val minor: Int) {
    val wireValue: String = "$major.$minor"

    companion object {
        const val supportedMajor: Int = 1

        fun parse(raw: String): TrustDomainResult<TrustSchemaVersion> {
            val parts = raw.split('.')
            val major = parts.getOrNull(0)?.toIntOrNull()
            val minor = parts.getOrNull(1)?.toIntOrNull()
            return if (
                parts.size != 2 || parts.any { it.isEmpty() || it.length > 1 && it.startsWith('0') } ||
                major == null || minor == null || major <= 0 || minor < 0
            ) {
                trustFailure(TrustDomainError.MalformedSchemaVersion)
            } else if (major != supportedMajor) {
                trustFailure(TrustDomainError.UnsupportedSchemaVersion(raw))
            } else {
                TrustDomainResult.Success(TrustSchemaVersion(major, minor))
            }
        }
    }
}

data class AuthorityActorId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): TrustDomainResult<AuthorityActorId> = parseUuid(raw) {
            AuthorityActorId(it)
        }
    }
}

data class AuthoritySessionId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): TrustDomainResult<AuthoritySessionId> = parseUuid(raw) {
            AuthoritySessionId(it)
        }
    }
}

data class TrustRecordId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): TrustDomainResult<TrustRecordId> = parseUuid(raw) {
            TrustRecordId(it)
        }
    }
}

data class ApprovalId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): TrustDomainResult<ApprovalId> = parseUuid(raw) {
            ApprovalId(it)
        }
    }
}

data class RevocationId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): TrustDomainResult<RevocationId> = parseUuid(raw) {
            RevocationId(it)
        }
    }
}

data class AuthorityProvenanceReference private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._/-][a-z0-9]+)*$")

        fun parse(raw: String): TrustDomainResult<AuthorityProvenanceReference> = if (
            raw.length in 3..160 && pattern.matches(raw)
        ) {
            TrustDomainResult.Success(AuthorityProvenanceReference(raw))
        } else {
            trustFailure(TrustDomainError.MalformedProvenanceReference)
        }
    }
}

data class PermissionScopeKey private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._][a-z0-9]+)*$")

        fun parse(raw: String): TrustDomainResult<PermissionScopeKey> = if (
            raw.length in 1..80 && pattern.matches(raw)
        ) TrustDomainResult.Success(PermissionScopeKey(raw))
        else trustFailure(TrustDomainError.MalformedScopeKey)
    }
}

data class PermissionScopeValue private constructor(val value: String) {
    companion object {
        fun parse(raw: String): TrustDomainResult<PermissionScopeValue> = if (
            raw.length in 1..160 && raw.none(Char::isISOControl) && raw.isNotBlank()
        ) TrustDomainResult.Success(PermissionScopeValue(raw))
        else trustFailure(TrustDomainError.MalformedScopeValue)
    }
}

data class AuthorityActor(
    val id: AuthorityActorId,
    val kind: CapabilityActorKind,
)

data class PermissionValidityWindow private constructor(
    val validFromInclusive: MonotonicTimestampMs,
    val validUntilExclusive: MonotonicTimestampMs?,
) {
    fun contains(timestamp: MonotonicTimestampMs): Boolean =
        timestamp.value >= validFromInclusive.value &&
            (validUntilExclusive == null || timestamp.value < validUntilExclusive.value)

    companion object {
        fun create(
            validFromInclusive: MonotonicTimestampMs,
            validUntilExclusive: MonotonicTimestampMs?,
        ): TrustDomainResult<PermissionValidityWindow> = if (
            validUntilExclusive != null && validUntilExclusive.value <= validFromInclusive.value
        ) {
            trustFailure(TrustDomainError.InvalidValidityWindow)
        } else {
            TrustDomainResult.Success(
                PermissionValidityWindow(validFromInclusive, validUntilExclusive),
            )
        }
    }
}

enum class ProviderTrustState {
    TRUSTED,
    UNTRUSTED,
    IDENTITY_CONFLICT,
}

class ProviderTrustRecord private constructor(
    val schemaVersion: TrustSchemaVersion,
    val id: TrustRecordId,
    val providerInstanceId: ProviderInstanceId,
    val state: ProviderTrustState,
    val validity: PermissionValidityWindow,
    val provenance: AuthorityProvenanceReference,
    val auditId: CapabilityAuditId,
) {
    companion object {
        fun create(
            schemaVersion: TrustSchemaVersion,
            id: TrustRecordId,
            providerInstanceId: ProviderInstanceId,
            state: ProviderTrustState,
            validity: PermissionValidityWindow,
            provenance: AuthorityProvenanceReference,
            auditId: CapabilityAuditId,
        ): TrustDomainResult<ProviderTrustRecord> = TrustDomainResult.Success(
            ProviderTrustRecord(
                schemaVersion,
                id,
                providerInstanceId,
                state,
                validity,
                provenance,
                auditId,
            ),
        )
    }
}

class PermissionScope private constructor(
    val actor: AuthorityActor,
    val providerInstanceId: ProviderInstanceId,
    val capability: CapabilityReference,
    val intent: CapabilityPermissionIntent,
    val constraints: Map<PermissionScopeKey, Set<PermissionScopeValue>>,
) {
    companion object {
        private const val maximumConstraints = 32
        private const val maximumValuesPerConstraint = 32

        fun create(
            actor: AuthorityActor,
            providerInstanceId: ProviderInstanceId,
            capability: CapabilityReference,
            intent: CapabilityPermissionIntent,
            constraints: Map<PermissionScopeKey, Set<PermissionScopeValue>> = emptyMap(),
        ): TrustDomainResult<PermissionScope> = when {
            constraints.size > maximumConstraints -> trustFailure(TrustDomainError.TooManyScopeConstraints)
            constraints.values.any { it.isEmpty() || it.size > maximumValuesPerConstraint } ->
                trustFailure(TrustDomainError.InvalidScopeConstraint)
            else -> TrustDomainResult.Success(
                PermissionScope(
                    actor,
                    providerInstanceId,
                    capability,
                    intent,
                    constraints.mapValues { (_, values) -> values.toSet() },
                ),
            )
        }
    }
}

enum class PermissionGrantLifetime {
    ONE_TIME,
    SESSION,
    PERSISTENT,
}

enum class HugoHostRole {
    HOME_CORE,
    MOBILE_HOST,
    OTHER_HOST,
    CLOUD_RELAY,
}

enum class ExecutionOrigin {
    LOCAL,
    REMOTE,
}

enum class ExecutionLocality {
    LOCAL_ONLY,
    LOCAL_OR_REMOTE,
}

class PermissionRestrictions private constructor(
    val allowedHostRoles: Set<HugoHostRole>,
    val locality: ExecutionLocality,
    val requiresPhysicalPresence: Boolean,
    val requiresMountedHost: Boolean,
    val requiresNearbyProvider: Boolean,
    val requiresActiveBody: Boolean,
) {
    companion object {
        fun create(
            allowedHostRoles: Set<HugoHostRole>,
            locality: ExecutionLocality,
            requiresPhysicalPresence: Boolean = false,
            requiresMountedHost: Boolean = false,
            requiresNearbyProvider: Boolean = false,
            requiresActiveBody: Boolean = false,
        ): TrustDomainResult<PermissionRestrictions> = if (allowedHostRoles.isEmpty()) {
            trustFailure(TrustDomainError.EmptyHostRestriction)
        } else {
            TrustDomainResult.Success(
                PermissionRestrictions(
                    allowedHostRoles.toSet(),
                    locality,
                    requiresPhysicalPresence,
                    requiresMountedHost,
                    requiresNearbyProvider,
                    requiresActiveBody,
                ),
            )
        }
    }
}

class PermissionGrant private constructor(
    val schemaVersion: TrustSchemaVersion,
    val authorizationReference: CapabilityAuthorizationReference,
    val scope: PermissionScope,
    val lifetime: PermissionGrantLifetime,
    val sessionId: AuthoritySessionId?,
    val oneTimeRequestId: CapabilityRequestId?,
    val approvalRequirement: CapabilityApprovalRequirement,
    val validity: PermissionValidityWindow,
    val restrictions: PermissionRestrictions,
    val provenance: AuthorityProvenanceReference,
    val auditId: CapabilityAuditId,
) {
    companion object {
        @Suppress("LongParameterList")
        fun create(
            schemaVersion: TrustSchemaVersion,
            authorizationReference: CapabilityAuthorizationReference,
            scope: PermissionScope,
            lifetime: PermissionGrantLifetime,
            sessionId: AuthoritySessionId?,
            oneTimeRequestId: CapabilityRequestId?,
            approvalRequirement: CapabilityApprovalRequirement,
            validity: PermissionValidityWindow,
            restrictions: PermissionRestrictions,
            provenance: AuthorityProvenanceReference,
            auditId: CapabilityAuditId,
        ): TrustDomainResult<PermissionGrant> {
            val lifetimeValid = when (lifetime) {
                PermissionGrantLifetime.ONE_TIME -> sessionId == null && oneTimeRequestId != null
                PermissionGrantLifetime.SESSION -> sessionId != null && oneTimeRequestId == null
                PermissionGrantLifetime.PERSISTENT -> sessionId == null && oneTimeRequestId == null
            }
            return if (!lifetimeValid) {
                trustFailure(TrustDomainError.GrantLifetimeBindingConflict)
            } else {
                TrustDomainResult.Success(
                    PermissionGrant(
                        schemaVersion,
                        authorizationReference,
                        scope,
                        lifetime,
                        sessionId,
                        oneTimeRequestId,
                        approvalRequirement,
                        validity,
                        restrictions,
                        provenance,
                        auditId,
                    ),
                )
            }
        }
    }
}

enum class ApprovalState {
    APPROVED,
    DENIED,
    CONSUMED,
}

class ApprovalRecord private constructor(
    val schemaVersion: TrustSchemaVersion,
    val id: ApprovalId,
    val authorizationReference: CapabilityAuthorizationReference,
    val actor: AuthorityActor,
    val providerInstanceId: ProviderInstanceId,
    val capability: CapabilityReference,
    val requirement: CapabilityApprovalRequirement,
    val requestId: CapabilityRequestId?,
    val sessionId: AuthoritySessionId?,
    val state: ApprovalState,
    val validity: PermissionValidityWindow,
    val provenance: AuthorityProvenanceReference,
    val auditId: CapabilityAuditId,
) {
    companion object {
        @Suppress("LongParameterList")
        fun create(
            schemaVersion: TrustSchemaVersion,
            id: ApprovalId,
            authorizationReference: CapabilityAuthorizationReference,
            actor: AuthorityActor,
            providerInstanceId: ProviderInstanceId,
            capability: CapabilityReference,
            requirement: CapabilityApprovalRequirement,
            requestId: CapabilityRequestId?,
            sessionId: AuthoritySessionId?,
            state: ApprovalState,
            validity: PermissionValidityWindow,
            provenance: AuthorityProvenanceReference,
            auditId: CapabilityAuditId,
        ): TrustDomainResult<ApprovalRecord> {
            val bindingValid = when (requirement) {
                CapabilityApprovalRequirement.SESSION_CONFIRMATION -> sessionId != null && requestId == null
                CapabilityApprovalRequirement.ONE_TIME_CONFIRMATION,
                CapabilityApprovalRequirement.EVERY_REQUEST,
                -> sessionId == null && requestId != null
                CapabilityApprovalRequirement.WITHIN_GRANT,
                CapabilityApprovalRequirement.PROHIBITED,
                -> false
            }
            return if (!bindingValid) {
                trustFailure(TrustDomainError.ApprovalBindingConflict)
            } else {
                TrustDomainResult.Success(
                    ApprovalRecord(
                        schemaVersion,
                        id,
                        authorizationReference,
                        actor,
                        providerInstanceId,
                        capability,
                        requirement,
                        requestId,
                        sessionId,
                        state,
                        validity,
                        provenance,
                        auditId,
                    ),
                )
            }
        }
    }
}

sealed interface RevocationTarget {
    data class Trust(val recordId: TrustRecordId) : RevocationTarget
    data class Grant(val authorizationReference: CapabilityAuthorizationReference) : RevocationTarget
    data class Approval(val approvalId: ApprovalId) : RevocationTarget
}

data class RevocationRecord(
    val schemaVersion: TrustSchemaVersion,
    val id: RevocationId,
    val target: RevocationTarget,
    val revokedAt: MonotonicTimestampMs,
    val provenance: AuthorityProvenanceReference,
    val auditId: CapabilityAuditId,
)

data class AuthorityPolicyInputs(
    val hostRole: HugoHostRole,
    val executionOrigin: ExecutionOrigin,
    val sessionId: AuthoritySessionId?,
    val userPhysicallyPresent: Boolean,
    val hostMountedOnBody: Boolean,
    val providerNearby: Boolean,
    val requestedProviderIsActiveBody: Boolean,
)

/** Non-authority facts retained only to prove they cannot substitute for a grant. */
data class NonAuthorityEvidence(
    val providerDiscovered: Boolean,
    val manifestCompatible: Boolean,
    val connectionEstablished: Boolean,
)

sealed interface TrustDomainResult<out T> {
    data class Success<T>(val value: T) : TrustDomainResult<T>
    data class Failure(val error: TrustDomainError) : TrustDomainResult<Nothing>
}

sealed interface TrustDomainError {
    data object MalformedSchemaVersion : TrustDomainError
    data class UnsupportedSchemaVersion(val found: String) : TrustDomainError
    data object MalformedIdentifier : TrustDomainError
    data object MalformedProvenanceReference : TrustDomainError
    data object MalformedScopeKey : TrustDomainError
    data object MalformedScopeValue : TrustDomainError
    data object InvalidValidityWindow : TrustDomainError
    data object TooManyScopeConstraints : TrustDomainError
    data object InvalidScopeConstraint : TrustDomainError
    data object EmptyHostRestriction : TrustDomainError
    data object GrantLifetimeBindingConflict : TrustDomainError
    data object ApprovalBindingConflict : TrustDomainError
}

private fun <T> trustFailure(error: TrustDomainError): TrustDomainResult<T> = TrustDomainResult.Failure(error)

private fun <T> parseUuid(raw: String, construct: (String) -> T): TrustDomainResult<T> {
    val pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    return if (pattern.matches(raw)) TrustDomainResult.Success(construct(raw))
    else trustFailure(TrustDomainError.MalformedIdentifier)
}
