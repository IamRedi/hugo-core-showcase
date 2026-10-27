package io.redi.robot.service.api

import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.CapabilityRegistrationResult
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.capability.provider.ProviderRuntimeSnapshot
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.connection.api.ConnectionAvailability
import io.redi.robot.connection.api.ConnectionAvailabilitySnapshot
import java.util.Collections

/** Opaque local correlation only; it is never an access token, account ID, cookie, or credential. */
@JvmInline
value class ServiceAuthSessionReference private constructor(val value: String) {
    companion object {
        private val pattern = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
        )

        fun parse(raw: String): ServiceConnectorResult<ServiceAuthSessionReference> = if (
            pattern.matches(raw)
        ) {
            ServiceConnectorResult.Success(ServiceAuthSessionReference(raw))
        } else {
            serviceFailure(ServiceConnectorError.MalformedAuthSessionReference)
        }
    }
}

enum class ServiceAuthenticationState {
    UNAUTHENTICATED,
    AUTHENTICATED,
    EXPIRED,
    REVOKED,
    FAILED,
}

enum class ServiceAuthenticationFailureCode {
    CONSENT_REQUIRED,
    SESSION_REJECTED,
    TEMPORARY_FAILURE,
    UNKNOWN,
}

class ServiceAuthenticationSnapshot private constructor(
    val generation: ProviderGeneration,
    val state: ServiceAuthenticationState,
    val sessionReference: ServiceAuthSessionReference?,
    grantedScopes: Set<ServiceAuthorizationScopeReference>,
    val observedAt: MonotonicTimestampMs,
    val expiresAt: MonotonicTimestampMs?,
    val failure: ServiceAuthenticationFailureCode?,
) {
    val grantedScopes: Set<ServiceAuthorizationScopeReference> = Collections.unmodifiableSet(
        grantedScopes.toSortedSet(),
    )

    companion object {
        const val MAXIMUM_GRANTED_SCOPES: Int = 64

        @Suppress("LongParameterList")
        fun create(
            generation: ProviderGeneration,
            state: ServiceAuthenticationState,
            sessionReference: ServiceAuthSessionReference?,
            grantedScopes: Set<ServiceAuthorizationScopeReference>,
            observedAt: MonotonicTimestampMs,
            expiresAt: MonotonicTimestampMs?,
            failure: ServiceAuthenticationFailureCode?,
        ): ServiceConnectorResult<ServiceAuthenticationSnapshot> {
            val valid = grantedScopes.size <= MAXIMUM_GRANTED_SCOPES && when (state) {
                ServiceAuthenticationState.UNAUTHENTICATED ->
                    sessionReference == null && grantedScopes.isEmpty() && expiresAt == null && failure == null
                ServiceAuthenticationState.AUTHENTICATED ->
                    sessionReference != null && failure == null &&
                        (expiresAt == null || expiresAt.value > observedAt.value)
                ServiceAuthenticationState.EXPIRED ->
                    sessionReference != null && grantedScopes.isEmpty() && failure == null &&
                        expiresAt != null && expiresAt.value <= observedAt.value
                ServiceAuthenticationState.REVOKED ->
                    sessionReference != null && grantedScopes.isEmpty() && expiresAt == null && failure == null
                ServiceAuthenticationState.FAILED ->
                    sessionReference == null && grantedScopes.isEmpty() && expiresAt == null && failure != null
            }
            return if (valid) {
                ServiceConnectorResult.Success(
                    ServiceAuthenticationSnapshot(
                        generation,
                        state,
                        sessionReference,
                        grantedScopes,
                        observedAt,
                        expiresAt,
                        failure,
                    ),
                )
            } else {
                serviceFailure(ServiceConnectorError.AuthSnapshotConflict)
            }
        }
    }
}

class ServiceConnectionEvidence private constructor(
    val snapshot: ConnectionAvailabilitySnapshot,
    val expiresAt: MonotonicTimestampMs,
) {
    companion object {
        const val MAXIMUM_FRESHNESS_MS: Long = 3_600_000L

        fun create(
            snapshot: ConnectionAvailabilitySnapshot,
            expiresAt: MonotonicTimestampMs,
        ): ServiceConnectorResult<ServiceConnectionEvidence> = if (
            expiresAt.value <= snapshot.observedAt.value ||
                expiresAt.value - snapshot.observedAt.value > MAXIMUM_FRESHNESS_MS
        ) {
            serviceFailure(ServiceConnectorError.InvalidConnectionFreshness)
        } else {
            ServiceConnectorResult.Success(ServiceConnectionEvidence(snapshot, expiresAt))
        }
    }
}

enum class ServiceRateEvidenceState {
    KNOWN,
    UNKNOWN,
}

enum class ServiceOperationFailureCode {
    AUTHENTICATION_REQUIRED,
    AUTHENTICATION_EXPIRED,
    AUTHENTICATION_REVOKED,
    MISSING_SCOPE,
    UNSUPPORTED_OPERATION,
    RATE_LIMITED,
    CONNECTION_UNAVAILABLE,
    TIMEOUT,
    CANCELLED,
    TRANSIENT_FAILURE,
    PERMANENT_FAILURE,
    MALFORMED_RESPONSE,
    UNKNOWN,
}

/** Provider-neutral failure only. Raw response bodies, vendor codes, and exception text stay inside adapters. */
class ServiceOperationFailure private constructor(
    val code: ServiceOperationFailureCode,
    val observedAt: MonotonicTimestampMs,
    val retryAt: MonotonicTimestampMs?,
) {
    companion object {
        fun create(
            code: ServiceOperationFailureCode,
            observedAt: MonotonicTimestampMs,
            retryAt: MonotonicTimestampMs?,
        ): ServiceConnectorResult<ServiceOperationFailure> {
            val retryable = code in setOf(
                ServiceOperationFailureCode.RATE_LIMITED,
                ServiceOperationFailureCode.CONNECTION_UNAVAILABLE,
                ServiceOperationFailureCode.TRANSIENT_FAILURE,
            )
            val valid = when {
                code == ServiceOperationFailureCode.RATE_LIMITED ->
                    retryAt != null && retryAt.value > observedAt.value
                retryAt != null -> retryable && retryAt.value > observedAt.value
                else -> true
            }
            return if (valid) {
                ServiceConnectorResult.Success(ServiceOperationFailure(code, observedAt, retryAt))
            } else {
                serviceFailure(ServiceConnectorError.InvalidOperationFailure)
            }
        }
    }
}

class ServiceRateEvidence private constructor(
    val generation: ProviderGeneration,
    val policyId: ServiceRatePolicyId,
    val state: ServiceRateEvidenceState,
    val remainingUnits: Int?,
    val observedAt: MonotonicTimestampMs,
    val resetsAt: MonotonicTimestampMs?,
) {
    companion object {
        fun create(
            generation: ProviderGeneration,
            policyId: ServiceRatePolicyId,
            state: ServiceRateEvidenceState,
            remainingUnits: Int?,
            observedAt: MonotonicTimestampMs,
            resetsAt: MonotonicTimestampMs?,
        ): ServiceConnectorResult<ServiceRateEvidence> {
            val valid = when (state) {
                ServiceRateEvidenceState.KNOWN ->
                    remainingUnits != null && remainingUnits >= 0 &&
                        resetsAt != null && resetsAt.value > observedAt.value
                ServiceRateEvidenceState.UNKNOWN -> remainingUnits == null && resetsAt == null
            }
            return if (valid) {
                ServiceConnectorResult.Success(
                    ServiceRateEvidence(generation, policyId, state, remainingUnits, observedAt, resetsAt),
                )
            } else {
                serviceFailure(ServiceConnectorError.InvalidRateSnapshot)
            }
        }
    }
}

class ServiceConnectorSnapshot private constructor(
    val generation: ProviderGeneration,
    val authentication: ServiceAuthenticationSnapshot,
    connectionEvidence: List<ServiceConnectionEvidence>,
    rateEvidence: List<ServiceRateEvidence>,
    val observedAt: MonotonicTimestampMs,
) {
    val connectionEvidence: List<ServiceConnectionEvidence> = Collections.unmodifiableList(
        connectionEvidence.sortedBy { it.snapshot.method.id.value + "@" + it.snapshot.method.version.value },
    )
    val rateEvidence: List<ServiceRateEvidence> = Collections.unmodifiableList(
        rateEvidence.sortedBy { it.policyId.value },
    )

    companion object {
        const val MAXIMUM_CONNECTION_EVIDENCE: Int = 32
        const val MAXIMUM_RATE_EVIDENCE: Int = 32

        fun create(
            generation: ProviderGeneration,
            authentication: ServiceAuthenticationSnapshot,
            connectionEvidence: List<ServiceConnectionEvidence>,
            rateEvidence: List<ServiceRateEvidence>,
            observedAt: MonotonicTimestampMs,
        ): ServiceConnectorResult<ServiceConnectorSnapshot> {
            if (authentication.generation != generation || rateEvidence.any { it.generation != generation }) {
                return serviceFailure(ServiceConnectorError.SnapshotGenerationMismatch)
            }
            if (
                authentication.observedAt.value > observedAt.value ||
                connectionEvidence.any { it.snapshot.observedAt.value > observedAt.value } ||
                rateEvidence.any { it.observedAt.value > observedAt.value }
            ) {
                return serviceFailure(ServiceConnectorError.SnapshotEvidenceFromFuture)
            }
            if (connectionEvidence.size > MAXIMUM_CONNECTION_EVIDENCE) {
                return serviceFailure(
                    ServiceConnectorError.TooManyConnectionEvidence(MAXIMUM_CONNECTION_EVIDENCE),
                )
            }
            if (rateEvidence.size > MAXIMUM_RATE_EVIDENCE) {
                return serviceFailure(ServiceConnectorError.TooManyRateEvidence(MAXIMUM_RATE_EVIDENCE))
            }
            if (connectionEvidence.map { it.snapshot.method }.distinct().size != connectionEvidence.size) {
                return serviceFailure(ServiceConnectorError.DuplicateConnectionEvidence)
            }
            if (rateEvidence.map { it.policyId }.distinct().size != rateEvidence.size) {
                return serviceFailure(ServiceConnectorError.DuplicateRateEvidence)
            }
            return ServiceConnectorResult.Success(
                ServiceConnectorSnapshot(
                    generation,
                    authentication,
                    connectionEvidence,
                    rateEvidence,
                    observedAt,
                ),
            )
        }
    }
}

enum class ServiceOperationAssessmentStatus {
    PRECONDITIONS_SATISFIED,
    BLOCKED,
    UNKNOWN,
}

enum class ServiceOperationCheckDimension {
    PROVIDER_FAMILY,
    PROVIDER_DEFINITION,
    PROVIDER_RUNTIME,
    CAPABILITY_REGISTRATION,
    AUTHENTICATION_SESSION,
    SERVICE_SCOPES,
    CONNECTION_METHOD,
    RATE_POLICY,
}

enum class ServiceOperationFinding(val definitive: Boolean) {
    WRONG_PROVIDER_FAMILY(true),
    PROVIDER_DEFINITION_MISMATCH(true),
    GENERATION_MISMATCH(true),
    PROVIDER_INSTANCE_MISMATCH(true),
    CAPABILITY_REGISTRATION_MISMATCH(true),
    UNSUPPORTED_OPERATION(true),
    PROVIDER_UNAVAILABLE(true),
    PROVIDER_DEGRADED(false),
    FUTURE_EVIDENCE(true),
    AUTHENTICATION_REQUIRED(true),
    AUTHENTICATION_EXPIRED(true),
    AUTHENTICATION_REVOKED(true),
    AUTHENTICATION_FAILED(true),
    AUTHENTICATION_SCOPE_MISMATCH(true),
    MISSING_SCOPE(true),
    CONNECTION_CONTRACT_MISMATCH(true),
    CONNECTION_EVIDENCE_MISSING(false),
    CONNECTION_EVIDENCE_STALE(false),
    CONNECTION_UNAVAILABLE(true),
    RATE_POLICY_MISMATCH(true),
    RATE_STATE_UNKNOWN(false),
    RATE_EVIDENCE_STALE(false),
    RATE_LIMITED(true),
}

class ServiceOperationAssessment internal constructor(
    val capability: CapabilityReference,
    val status: ServiceOperationAssessmentStatus,
    checkedDimensions: Set<ServiceOperationCheckDimension>,
    findings: Set<ServiceOperationFinding>,
    matchedScopes: Set<ServiceAuthorizationScopeReference>,
    missingScopes: Set<ServiceAuthorizationScopeReference>,
    val remainingRateUnits: Int?,
    val retryAt: MonotonicTimestampMs?,
) {
    val checkedDimensions: Set<ServiceOperationCheckDimension> = Collections.unmodifiableSet(
        checkedDimensions.toSortedSet(),
    )
    val findings: Set<ServiceOperationFinding> = Collections.unmodifiableSet(findings.toSortedSet())
    val matchedScopes: Set<ServiceAuthorizationScopeReference> = Collections.unmodifiableSet(
        matchedScopes.toSortedSet(),
    )
    val missingScopes: Set<ServiceAuthorizationScopeReference> = Collections.unmodifiableSet(
        missingScopes.toSortedSet(),
    )

    val grantsTrust: Boolean get() = false
    val grantsAuthorization: Boolean get() = false
    val establishesConnection: Boolean get() = false
    val executesOperation: Boolean get() = false
}

data class ServiceOperationAssessmentInput(
    val identity: ProviderIdentity,
    val contract: ServiceConnectorContract,
    val providerRuntime: ProviderRuntimeSnapshot,
    val registrations: CapabilityRegistrationResult,
    val serviceSnapshot: ServiceConnectorSnapshot,
    val capability: CapabilityReference,
    val now: MonotonicTimestampMs,
)

/** Pure technical-evidence check. A satisfied report is still not H014 authorization or execution. */
object ServiceOperationAssessor {
    fun assess(input: ServiceOperationAssessmentInput): ServiceOperationAssessment {
        val checks = linkedSetOf<ServiceOperationCheckDimension>()
        val findings = linkedSetOf<ServiceOperationFinding>()

        checks += ServiceOperationCheckDimension.PROVIDER_FAMILY
        if (input.identity.family != ProviderFamily.SERVICE_CONNECTOR) {
            findings += ServiceOperationFinding.WRONG_PROVIDER_FAMILY
        }

        checks += ServiceOperationCheckDimension.PROVIDER_DEFINITION
        if (input.identity.definitionId != input.contract.providerDefinitionId) {
            findings += ServiceOperationFinding.PROVIDER_DEFINITION_MISMATCH
        }

        checks += ServiceOperationCheckDimension.PROVIDER_RUNTIME
        if (
            input.providerRuntime.generation != input.serviceSnapshot.generation ||
            input.registrations.generation != input.serviceSnapshot.generation
        ) {
            findings += ServiceOperationFinding.GENERATION_MISMATCH
        }
        if (input.registrations.providerInstanceId != input.identity.instanceId) {
            findings += ServiceOperationFinding.PROVIDER_INSTANCE_MISMATCH
        }
        when (input.providerRuntime.availability) {
            ProviderRuntimeAvailability.AVAILABLE -> Unit
            ProviderRuntimeAvailability.DEGRADED -> findings += ServiceOperationFinding.PROVIDER_DEGRADED
            ProviderRuntimeAvailability.UNAVAILABLE -> findings += ServiceOperationFinding.PROVIDER_UNAVAILABLE
        }
        if (input.serviceSnapshot.observedAt.value > input.now.value) {
            findings += ServiceOperationFinding.FUTURE_EVIDENCE
        }

        checks += ServiceOperationCheckDimension.CAPABILITY_REGISTRATION
        val contractCapabilities = input.contract.operations.map { it.capability }.toSet()
        val registeredCapabilities = input.registrations.registrations.map { it.descriptor.reference }.toSet()
        if (contractCapabilities != registeredCapabilities) {
            findings += ServiceOperationFinding.CAPABILITY_REGISTRATION_MISMATCH
        }
        val operation = input.contract.operation(input.capability)
        if (operation == null) findings += ServiceOperationFinding.UNSUPPORTED_OPERATION

        checks += ServiceOperationCheckDimension.AUTHENTICATION_SESSION
        val authentication = input.serviceSnapshot.authentication
        if (!input.contract.supportedScopes.containsAll(authentication.grantedScopes)) {
            findings += ServiceOperationFinding.AUTHENTICATION_SCOPE_MISMATCH
        }
        when (authentication.state) {
            ServiceAuthenticationState.UNAUTHENTICATED ->
                findings += ServiceOperationFinding.AUTHENTICATION_REQUIRED
            ServiceAuthenticationState.AUTHENTICATED -> if (
                authentication.expiresAt != null && input.now.value >= authentication.expiresAt.value
            ) {
                findings += ServiceOperationFinding.AUTHENTICATION_EXPIRED
            }
            ServiceAuthenticationState.EXPIRED -> findings += ServiceOperationFinding.AUTHENTICATION_EXPIRED
            ServiceAuthenticationState.REVOKED -> findings += ServiceOperationFinding.AUTHENTICATION_REVOKED
            ServiceAuthenticationState.FAILED -> findings += ServiceOperationFinding.AUTHENTICATION_FAILED
        }

        checks += ServiceOperationCheckDimension.SERVICE_SCOPES
        val requiredScopes = operation?.requiredScopes.orEmpty()
        val matchedScopes = requiredScopes.intersect(authentication.grantedScopes)
        val missingScopes = requiredScopes - authentication.grantedScopes
        if (missingScopes.isNotEmpty()) findings += ServiceOperationFinding.MISSING_SCOPE

        checks += ServiceOperationCheckDimension.CONNECTION_METHOD
        if (input.serviceSnapshot.connectionEvidence.any {
                it.snapshot.method !in input.contract.connectionMethods
            }
        ) {
            findings += ServiceOperationFinding.CONNECTION_CONTRACT_MISMATCH
        }
        operation?.let { declaredOperation ->
            val connection = input.serviceSnapshot.connectionEvidence.firstOrNull {
                it.snapshot.method == declaredOperation.connectionMethod
            }
            when {
                connection == null -> findings += ServiceOperationFinding.CONNECTION_EVIDENCE_MISSING
                input.now.value >= connection.expiresAt.value ->
                    findings += ServiceOperationFinding.CONNECTION_EVIDENCE_STALE
                connection.snapshot.availability == ConnectionAvailability.UNAVAILABLE ->
                    findings += ServiceOperationFinding.CONNECTION_UNAVAILABLE
            }
        }

        var remainingRateUnits: Int? = null
        var retryAt: MonotonicTimestampMs? = null
        checks += ServiceOperationCheckDimension.RATE_POLICY
        val declaredRatePolicies = input.contract.ratePolicies.map { it.id }.toSet()
        if (input.serviceSnapshot.rateEvidence.any { it.policyId !in declaredRatePolicies }) {
            findings += ServiceOperationFinding.RATE_POLICY_MISMATCH
        }
        operation?.ratePolicyId?.let { policyId ->
            val policy = input.contract.ratePolicy(policyId)
            val rate = input.serviceSnapshot.rateEvidence.firstOrNull { it.policyId == policyId }
            when {
                policy == null -> findings += ServiceOperationFinding.RATE_POLICY_MISMATCH
                rate == null || rate.state == ServiceRateEvidenceState.UNKNOWN ->
                    findings += ServiceOperationFinding.RATE_STATE_UNKNOWN
                rate.remainingUnits == null || rate.resetsAt == null ->
                    findings += ServiceOperationFinding.RATE_STATE_UNKNOWN
                rate.remainingUnits > policy.maximumUnits ->
                    findings += ServiceOperationFinding.RATE_POLICY_MISMATCH
                rate.resetsAt.value - rate.observedAt.value > policy.windowMs ->
                    findings += ServiceOperationFinding.RATE_POLICY_MISMATCH
                input.now.value >= rate.resetsAt.value ->
                    findings += ServiceOperationFinding.RATE_EVIDENCE_STALE
                else -> {
                    remainingRateUnits = rate.remainingUnits
                    if (rate.remainingUnits < operation.rateCostUnits) {
                        findings += ServiceOperationFinding.RATE_LIMITED
                        retryAt = rate.resetsAt
                    }
                }
            }
        }

        val orderedFindings = findings.toSortedSet()
        val status = when {
            orderedFindings.isEmpty() -> ServiceOperationAssessmentStatus.PRECONDITIONS_SATISFIED
            orderedFindings.any { it.definitive } -> ServiceOperationAssessmentStatus.BLOCKED
            else -> ServiceOperationAssessmentStatus.UNKNOWN
        }
        return ServiceOperationAssessment(
            capability = input.capability,
            status = status,
            checkedDimensions = checks,
            findings = orderedFindings,
            matchedScopes = matchedScopes,
            missingScopes = missingScopes,
            remainingRateUnits = remainingRateUnits,
            retryAt = retryAt,
        )
    }
}
