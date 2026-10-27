package io.redi.robot.service.api

import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.provider.CapabilityProvider
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.connection.api.ConnectionMethodReference
import io.redi.robot.manifest.domain.CommonManifestResult
import io.redi.robot.manifest.domain.ManifestContractReference
import java.util.Collections

/**
 * Provider-neutral service role. Semantic execution remains exclusively on [CapabilityProvider];
 * this extension adds service auth/scope/rate evidence and never accepts credentials or payloads.
 */
interface ServiceConnector : CapabilityProvider {
    val serviceContract: ServiceConnectorContract

    fun serviceSnapshot(): ServiceConnectorSnapshot
}

@JvmInline
value class ServiceConnectorSchemaVersion private constructor(val wireValue: String) {
    companion object {
        const val CURRENT: String = "1.0"

        fun parse(raw: String): ServiceConnectorResult<ServiceConnectorSchemaVersion> = when {
            !raw.matches(Regex("^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$")) ->
                serviceFailure(ServiceConnectorError.MalformedSchemaVersion)
            raw.substringBefore('.').toIntOrNull() != 1 ->
                serviceFailure(ServiceConnectorError.UnsupportedSchemaVersion(raw))
            raw != CURRENT -> serviceFailure(ServiceConnectorError.UnsupportedSchemaVersion(raw))
            else -> ServiceConnectorResult.Success(ServiceConnectorSchemaVersion(raw))
        }
    }
}

@JvmInline
value class ServiceAuthenticationSchemeReference private constructor(
    val contract: ManifestContractReference,
) {
    val wireValue: String get() = contract.wireValue

    companion object {
        fun parse(raw: String): ServiceConnectorResult<ServiceAuthenticationSchemeReference> =
            when (val parsed = ManifestContractReference.parse(raw)) {
                is CommonManifestResult.Failure -> serviceFailure(ServiceConnectorError.MalformedAuthenticationScheme)
                is CommonManifestResult.Success -> if (
                    parsed.value.id.value.startsWith("auth.")
                ) {
                    ServiceConnectorResult.Success(ServiceAuthenticationSchemeReference(parsed.value))
                } else {
                    serviceFailure(ServiceConnectorError.MalformedAuthenticationScheme)
                }
            }
    }
}

@JvmInline
value class ServiceAuthorizationScopeReference private constructor(
    val contract: ManifestContractReference,
) : Comparable<ServiceAuthorizationScopeReference> {
    val wireValue: String get() = contract.wireValue

    override fun compareTo(other: ServiceAuthorizationScopeReference): Int =
        wireValue.compareTo(other.wireValue)

    companion object {
        fun parse(raw: String): ServiceConnectorResult<ServiceAuthorizationScopeReference> =
            when (val parsed = ManifestContractReference.parse(raw)) {
                is CommonManifestResult.Failure -> serviceFailure(ServiceConnectorError.MalformedAuthorizationScope)
                is CommonManifestResult.Success -> if (
                    parsed.value.id.value.startsWith("scope.")
                ) {
                    ServiceConnectorResult.Success(ServiceAuthorizationScopeReference(parsed.value))
                } else {
                    serviceFailure(ServiceConnectorError.MalformedAuthorizationScope)
                }
            }
    }
}

@JvmInline
value class ServiceRatePolicyId private constructor(val value: String) : Comparable<ServiceRatePolicyId> {
    override fun compareTo(other: ServiceRatePolicyId): Int = value.compareTo(other.value)

    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$")

        fun parse(raw: String): ServiceConnectorResult<ServiceRatePolicyId> = if (
            raw.length in 3..96 && pattern.matches(raw)
        ) {
            ServiceConnectorResult.Success(ServiceRatePolicyId(raw))
        } else {
            serviceFailure(ServiceConnectorError.MalformedRatePolicyId)
        }
    }
}

class ServiceRatePolicy private constructor(
    val id: ServiceRatePolicyId,
    val maximumUnits: Int,
    val windowMs: Long,
) {
    companion object {
        const val MAXIMUM_UNITS: Int = 1_000_000
        const val MINIMUM_WINDOW_MS: Long = 100L
        const val MAXIMUM_WINDOW_MS: Long = 86_400_000L

        fun create(
            id: ServiceRatePolicyId,
            maximumUnits: Int,
            windowMs: Long,
        ): ServiceConnectorResult<ServiceRatePolicy> = when {
            maximumUnits !in 1..MAXIMUM_UNITS -> serviceFailure(ServiceConnectorError.InvalidRateLimit)
            windowMs !in MINIMUM_WINDOW_MS..MAXIMUM_WINDOW_MS ->
                serviceFailure(ServiceConnectorError.InvalidRateWindow)
            else -> ServiceConnectorResult.Success(ServiceRatePolicy(id, maximumUnits, windowMs))
        }
    }
}

class ServiceOperationDefinition private constructor(
    val capability: CapabilityReference,
    requiredScopes: Set<ServiceAuthorizationScopeReference>,
    val connectionMethod: ConnectionMethodReference,
    val ratePolicyId: ServiceRatePolicyId?,
    val rateCostUnits: Int,
) {
    val requiredScopes: Set<ServiceAuthorizationScopeReference> = Collections.unmodifiableSet(
        requiredScopes.toSortedSet(),
    )

    companion object {
        const val MAXIMUM_REQUIRED_SCOPES: Int = 32
        const val MAXIMUM_RATE_COST_UNITS: Int = 10_000

        fun create(
            capability: CapabilityReference,
            requiredScopes: Set<ServiceAuthorizationScopeReference>,
            connectionMethod: ConnectionMethodReference,
            ratePolicyId: ServiceRatePolicyId?,
            rateCostUnits: Int,
        ): ServiceConnectorResult<ServiceOperationDefinition> = when {
            requiredScopes.size > MAXIMUM_REQUIRED_SCOPES ->
                serviceFailure(ServiceConnectorError.TooManyOperationScopes(MAXIMUM_REQUIRED_SCOPES))
            ratePolicyId == null && rateCostUnits != 0 ->
                serviceFailure(ServiceConnectorError.RatePolicyBindingConflict)
            ratePolicyId != null && rateCostUnits !in 1..MAXIMUM_RATE_COST_UNITS ->
                serviceFailure(ServiceConnectorError.RatePolicyBindingConflict)
            else -> ServiceConnectorResult.Success(
                ServiceOperationDefinition(
                    capability,
                    requiredScopes,
                    connectionMethod,
                    ratePolicyId,
                    rateCostUnits,
                ),
            )
        }
    }
}

class ServiceConnectorContract private constructor(
    val schemaVersion: ServiceConnectorSchemaVersion,
    val providerDefinitionId: ProviderDefinitionId,
    val authenticationScheme: ServiceAuthenticationSchemeReference,
    supportedScopes: Set<ServiceAuthorizationScopeReference>,
    operations: List<ServiceOperationDefinition>,
    ratePolicies: List<ServiceRatePolicy>,
) {
    val supportedScopes: Set<ServiceAuthorizationScopeReference> = Collections.unmodifiableSet(
        supportedScopes.toSortedSet(),
    )
    val operations: List<ServiceOperationDefinition> = Collections.unmodifiableList(
        operations.sortedBy { it.capability.wireValue },
    )
    val ratePolicies: List<ServiceRatePolicy> = Collections.unmodifiableList(
        ratePolicies.sortedBy { it.id.value },
    )
    val connectionMethods: Set<ConnectionMethodReference> = Collections.unmodifiableSet(
        operations.map { it.connectionMethod }.toSet(),
    )

    fun operation(capability: CapabilityReference): ServiceOperationDefinition? =
        operations.firstOrNull { it.capability == capability }

    fun ratePolicy(id: ServiceRatePolicyId): ServiceRatePolicy? = ratePolicies.firstOrNull { it.id == id }

    companion object {
        const val MAXIMUM_SCOPES: Int = 64
        const val MAXIMUM_OPERATIONS: Int = 64
        const val MAXIMUM_RATE_POLICIES: Int = 32

        @Suppress("LongParameterList")
        fun create(
            schemaVersion: ServiceConnectorSchemaVersion,
            providerDefinitionId: ProviderDefinitionId,
            authenticationScheme: ServiceAuthenticationSchemeReference,
            supportedScopes: Set<ServiceAuthorizationScopeReference>,
            operations: List<ServiceOperationDefinition>,
            ratePolicies: List<ServiceRatePolicy>,
        ): ServiceConnectorResult<ServiceConnectorContract> {
            if (supportedScopes.size > MAXIMUM_SCOPES) {
                return serviceFailure(ServiceConnectorError.TooManySupportedScopes(MAXIMUM_SCOPES))
            }
            if (operations.isEmpty() || operations.size > MAXIMUM_OPERATIONS) {
                return serviceFailure(ServiceConnectorError.InvalidOperationCount(MAXIMUM_OPERATIONS))
            }
            if (operations.map { it.capability }.distinct().size != operations.size) {
                return serviceFailure(ServiceConnectorError.DuplicateOperation)
            }
            if (ratePolicies.size > MAXIMUM_RATE_POLICIES) {
                return serviceFailure(ServiceConnectorError.TooManyRatePolicies(MAXIMUM_RATE_POLICIES))
            }
            if (ratePolicies.map { it.id }.distinct().size != ratePolicies.size) {
                return serviceFailure(ServiceConnectorError.DuplicateRatePolicy)
            }
            if (operations.any { !supportedScopes.containsAll(it.requiredScopes) }) {
                return serviceFailure(ServiceConnectorError.UndeclaredOperationScope)
            }
            val policyIds = ratePolicies.map { it.id }.toSet()
            if (operations.any { it.ratePolicyId != null && it.ratePolicyId !in policyIds }) {
                return serviceFailure(ServiceConnectorError.UndeclaredRatePolicy)
            }
            return ServiceConnectorResult.Success(
                ServiceConnectorContract(
                    schemaVersion,
                    providerDefinitionId,
                    authenticationScheme,
                    supportedScopes,
                    operations,
                    ratePolicies,
                ),
            )
        }
    }
}

sealed interface ServiceConnectorResult<out T> {
    data class Success<T>(val value: T) : ServiceConnectorResult<T>
    data class Failure(val error: ServiceConnectorError) : ServiceConnectorResult<Nothing>
}

sealed interface ServiceConnectorError {
    data object MalformedSchemaVersion : ServiceConnectorError
    data class UnsupportedSchemaVersion(val found: String) : ServiceConnectorError
    data object MalformedAuthenticationScheme : ServiceConnectorError
    data object MalformedAuthorizationScope : ServiceConnectorError
    data object MalformedRatePolicyId : ServiceConnectorError
    data object InvalidRateLimit : ServiceConnectorError
    data object InvalidRateWindow : ServiceConnectorError
    data class TooManyOperationScopes(val maximum: Int) : ServiceConnectorError
    data object RatePolicyBindingConflict : ServiceConnectorError
    data class TooManySupportedScopes(val maximum: Int) : ServiceConnectorError
    data class InvalidOperationCount(val maximum: Int) : ServiceConnectorError
    data object DuplicateOperation : ServiceConnectorError
    data class TooManyRatePolicies(val maximum: Int) : ServiceConnectorError
    data object DuplicateRatePolicy : ServiceConnectorError
    data object UndeclaredOperationScope : ServiceConnectorError
    data object UndeclaredRatePolicy : ServiceConnectorError
    data object MalformedAuthSessionReference : ServiceConnectorError
    data object AuthSnapshotConflict : ServiceConnectorError
    data object InvalidConnectionFreshness : ServiceConnectorError
    data object InvalidRateSnapshot : ServiceConnectorError
    data object SnapshotGenerationMismatch : ServiceConnectorError
    data object SnapshotEvidenceFromFuture : ServiceConnectorError
    data object DuplicateConnectionEvidence : ServiceConnectorError
    data object DuplicateRateEvidence : ServiceConnectorError
    data object InvalidOperationFailure : ServiceConnectorError
    data class TooManyConnectionEvidence(val maximum: Int) : ServiceConnectorError
    data class TooManyRateEvidence(val maximum: Int) : ServiceConnectorError
}

internal fun <T> serviceFailure(error: ServiceConnectorError): ServiceConnectorResult<T> =
    ServiceConnectorResult.Failure(error)
