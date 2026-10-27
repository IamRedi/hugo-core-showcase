package io.redi.robot.context.provider

import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.capability.provider.RedactedProviderIdentity
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleState
import io.redi.robot.capability.provider.redacted
import io.redi.robot.capability.registry.ProviderHostAssociationReference
import io.redi.robot.capability.registry.ProviderRegistrySnapshot
import io.redi.robot.context.api.ContextConfidence
import io.redi.robot.context.api.ContextKey
import io.redi.robot.context.api.ContextObservation
import io.redi.robot.context.api.ContextObservationClaim
import io.redi.robot.context.api.ContextObservationId
import io.redi.robot.context.api.ContextObservationResult
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextSourceReference
import io.redi.robot.context.api.ContextSourceType
import io.redi.robot.context.api.ContextSymbol
import io.redi.robot.context.api.ContextValue
import io.redi.robot.context.runtime.ContextSnapshot
import io.redi.robot.context.runtime.ContextSnapshotBudget
import io.redi.robot.context.runtime.ContextSnapshotEngine
import io.redi.robot.context.runtime.ContextSnapshotError
import io.redi.robot.context.runtime.ContextSnapshotRequest
import io.redi.robot.context.runtime.ContextSnapshotResult
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID

@JvmInline
value class ActiveProviderContextRevision private constructor(val value: Long) {
    companion object {
        fun create(value: Long): ActiveProviderContextResult<ActiveProviderContextRevision> = if (value < 0L) {
            activeProviderFailure(ActiveProviderContextError.InvalidRevision)
        } else {
            ActiveProviderContextResult.Success(ActiveProviderContextRevision(value))
        }
    }
}

enum class ProviderContextConnection {
    CONNECTED,
    NOT_CONNECTED,
}

enum class ProviderContextAvailability {
    AVAILABLE,
    DEGRADED,
    UNAVAILABLE,
}

/** H015/H009 runtime projection only; registration and connection ownership remain upstream. */
data class ActiveProviderContextEntry internal constructor(
    val identity: ProviderIdentity,
    val generation: ProviderGeneration,
    val connection: ProviderContextConnection,
    val availability: ProviderContextAvailability,
    val hostAssociation: ProviderHostAssociationReference?,
    val observedAt: MonotonicTimestampMs,
)

/**
 * Current provider/host/embodiment evidence. Selection and availability are deliberately separate,
 * and neither grants trust, permission, safety approval, feasibility, or execution authority.
 */
class ActiveProviderContextSnapshot internal constructor(
    val revision: ActiveProviderContextRevision,
    val host: EmbodimentHost,
    val observedAt: MonotonicTimestampMs,
    val expiresAt: MonotonicTimestampMs,
    providers: List<ActiveProviderContextEntry>,
    val selectedBody: BodyProviderReference?,
    val activeBody: BodyProviderReference?,
    val contextSnapshot: ContextSnapshot,
) {
    val providers: List<ActiveProviderContextEntry> = Collections.unmodifiableList(providers.toList())

    val connectedProviders: List<ActiveProviderContextEntry> = Collections.unmodifiableList(
        providers.filter { it.connection == ProviderContextConnection.CONNECTED },
    )
    val connectedBodies: List<ActiveProviderContextEntry> = Collections.unmodifiableList(
        connectedProviders.filter { it.identity.family == ProviderFamily.BODY_ADAPTER },
    )
    val connectedDevices: List<ActiveProviderContextEntry> = Collections.unmodifiableList(
        connectedProviders.filter { it.identity.family == ProviderFamily.DEVICE_ADAPTER },
    )
    val connectedServices: List<ActiveProviderContextEntry> = Collections.unmodifiableList(
        connectedProviders.filter { it.identity.family == ProviderFamily.SERVICE_CONNECTOR },
    )
    val availableServices: List<ActiveProviderContextEntry> = Collections.unmodifiableList(
        connectedServices.filter { it.availability == ProviderContextAvailability.AVAILABLE },
    )

    val grantsAuthorization: Boolean get() = false
    val grantsExecutionAuthority: Boolean get() = false

    fun isFreshAt(now: MonotonicTimestampMs): Boolean =
        now.value >= observedAt.value && now.value < expiresAt.value
}

class ActiveProviderContextState private constructor(
    val revision: ActiveProviderContextRevision?,
    val snapshot: ActiveProviderContextSnapshot?,
) {
    companion object {
        fun initial(): ActiveProviderContextState = ActiveProviderContextState(null, null)

        internal fun updated(
            revision: ActiveProviderContextRevision,
            snapshot: ActiveProviderContextSnapshot?,
        ): ActiveProviderContextState = ActiveProviderContextState(revision, snapshot)
    }
}

sealed interface ActiveProviderContextEvent {
    val revision: ActiveProviderContextRevision

    data class Refresh(
        override val revision: ActiveProviderContextRevision,
        val registry: ProviderRegistrySnapshot,
        val embodiment: ActiveBodyState,
        val observedAt: MonotonicTimestampMs,
        val expiresAt: MonotonicTimestampMs,
    ) : ActiveProviderContextEvent

    data class Expire(
        override val revision: ActiveProviderContextRevision,
        val now: MonotonicTimestampMs,
    ) : ActiveProviderContextEvent
}

sealed interface ActiveProviderContextDisposition {
    data object Applied : ActiveProviderContextDisposition
    data class Ignored(val reason: ActiveProviderContextIgnoredReason) : ActiveProviderContextDisposition
}

enum class ActiveProviderContextIgnoredReason {
    STALE_REVISION,
    REUSED_REVISION,
    NO_SNAPSHOT,
    NOT_EXPIRED,
}

data class ActiveProviderContextReduction(
    val state: ActiveProviderContextState,
    val disposition: ActiveProviderContextDisposition,
)

sealed interface ActiveProviderContextResult<out T> {
    data class Success<T>(val value: T) : ActiveProviderContextResult<T>
    data class Failure(val error: ActiveProviderContextError) : ActiveProviderContextResult<Nothing>
}

sealed interface ActiveProviderContextError {
    data object InvalidRevision : ActiveProviderContextError
    data object InvalidFreshnessWindow : ActiveProviderContextError
    data class FutureProviderEvidence(val provider: RedactedProviderIdentity) : ActiveProviderContextError
    data class ContextProjectionFailure(val error: ContextSnapshotError) : ActiveProviderContextError
}

object ActiveProviderContextReducer {
    fun reduce(
        state: ActiveProviderContextState,
        event: ActiveProviderContextEvent,
    ): ActiveProviderContextResult<ActiveProviderContextReduction> {
        val currentRevision = state.revision
        if (currentRevision != null && event.revision.value <= currentRevision.value) {
            val reason = if (event.revision.value < currentRevision.value) {
                ActiveProviderContextIgnoredReason.STALE_REVISION
            } else {
                ActiveProviderContextIgnoredReason.REUSED_REVISION
            }
            return ActiveProviderContextResult.Success(
                ActiveProviderContextReduction(state, ActiveProviderContextDisposition.Ignored(reason)),
            )
        }
        return when (event) {
            is ActiveProviderContextEvent.Refresh -> refresh(event)
            is ActiveProviderContextEvent.Expire -> expire(state, event)
        }
    }

    private fun refresh(
        event: ActiveProviderContextEvent.Refresh,
    ): ActiveProviderContextResult<ActiveProviderContextReduction> {
        if (event.expiresAt.value <= event.observedAt.value) {
            return activeProviderFailure(ActiveProviderContextError.InvalidFreshnessWindow)
        }
        val future = event.registry.entries.firstOrNull {
            it.record.runtime.availability.observedAt.value > event.observedAt.value
        }
        if (future != null) {
            return activeProviderFailure(
                ActiveProviderContextError.FutureProviderEvidence(future.record.identity.redacted()),
            )
        }

        val providers = event.registry.entries.map { registered ->
            val record = registered.record
            val connected = record.runtime.lifecycle.isConnectedContext()
            ActiveProviderContextEntry(
                identity = record.identity,
                generation = record.runtime.generation,
                connection = if (connected) {
                    ProviderContextConnection.CONNECTED
                } else {
                    ProviderContextConnection.NOT_CONNECTED
                },
                availability = if (!connected) {
                    ProviderContextAvailability.UNAVAILABLE
                } else {
                    record.runtime.availability.availability.toContextAvailability()
                },
                hostAssociation = record.hostAssociation,
                observedAt = record.runtime.availability.observedAt,
            )
        }.sortedBy { it.identity.instanceId.value }

        val selectedBody = event.embodiment.activeSelection
            ?.takeIf {
                it.selectedAt.value <= event.observedAt.value && it.isActiveAt(event.observedAt)
            }
            ?.body
        val activeBody = selectedBody?.takeIf { selected ->
            val provider = providers.firstOrNull { it.identity.instanceId == selected.identity.instanceId }
            val body = event.embodiment.body(selected)
            provider != null &&
                provider.identity.family == ProviderFamily.BODY_ADAPTER &&
                provider.connection == ProviderContextConnection.CONNECTED &&
                provider.availability != ProviderContextAvailability.UNAVAILABLE &&
                body?.availability == BodyAvailability.AVAILABLE &&
                body.connected
        }

        val contextProjection = buildContextProjection(event, providers, selectedBody, activeBody)
        val contextSnapshot = when (contextProjection) {
            is ContextSnapshotResult.Success -> contextProjection.value
            is ContextSnapshotResult.Failure -> return activeProviderFailure(
                ActiveProviderContextError.ContextProjectionFailure(contextProjection.error),
            )
        }
        val snapshot = ActiveProviderContextSnapshot(
            revision = event.revision,
            host = event.embodiment.host,
            observedAt = event.observedAt,
            expiresAt = event.expiresAt,
            providers = providers,
            selectedBody = selectedBody,
            activeBody = activeBody,
            contextSnapshot = contextSnapshot,
        )
        return ActiveProviderContextResult.Success(
            ActiveProviderContextReduction(
                ActiveProviderContextState.updated(event.revision, snapshot),
                ActiveProviderContextDisposition.Applied,
            ),
        )
    }

    private fun expire(
        state: ActiveProviderContextState,
        event: ActiveProviderContextEvent.Expire,
    ): ActiveProviderContextResult<ActiveProviderContextReduction> {
        val snapshot = state.snapshot ?: return ActiveProviderContextResult.Success(
            ActiveProviderContextReduction(
                state,
                ActiveProviderContextDisposition.Ignored(ActiveProviderContextIgnoredReason.NO_SNAPSHOT),
            ),
        )
        if (event.now.value < snapshot.expiresAt.value) {
            return ActiveProviderContextResult.Success(
                ActiveProviderContextReduction(
                    state,
                    ActiveProviderContextDisposition.Ignored(ActiveProviderContextIgnoredReason.NOT_EXPIRED),
                ),
            )
        }
        return ActiveProviderContextResult.Success(
            ActiveProviderContextReduction(
                ActiveProviderContextState.updated(event.revision, null),
                ActiveProviderContextDisposition.Applied,
            ),
        )
    }

    private fun buildContextProjection(
        event: ActiveProviderContextEvent.Refresh,
        providers: List<ActiveProviderContextEntry>,
        selectedBody: BodyProviderReference?,
        activeBody: BodyProviderReference?,
    ): ContextSnapshotResult<ContextSnapshot> {
        val connected = providers.filter { it.connection == ProviderContextConnection.CONNECTED }
        val values = listOf(
            HOST_ROLE to ContextValue.SymbolValue(symbol(event.embodiment.host.role.name.lowercase())),
            CONNECTED_PROVIDER_COUNT to ContextValue.IntegerValue(connected.size.toLong()),
            CONNECTED_BODY_COUNT to ContextValue.IntegerValue(
                connected.count { it.identity.family == ProviderFamily.BODY_ADAPTER }.toLong(),
            ),
            CONNECTED_DEVICE_COUNT to ContextValue.IntegerValue(
                connected.count { it.identity.family == ProviderFamily.DEVICE_ADAPTER }.toLong(),
            ),
            CONNECTED_SERVICE_COUNT to ContextValue.IntegerValue(
                connected.count { it.identity.family == ProviderFamily.SERVICE_CONNECTOR }.toLong(),
            ),
            AVAILABLE_SERVICE_COUNT to ContextValue.IntegerValue(
                connected.count {
                    it.identity.family == ProviderFamily.SERVICE_CONNECTOR &&
                        it.availability == ProviderContextAvailability.AVAILABLE
                }.toLong(),
            ),
            BODY_SELECTED to ContextValue.BooleanValue(selectedBody != null),
            BODY_ACTIVE to ContextValue.BooleanValue(activeBody != null),
        )
        val observations = values.map { (key, value) ->
            observation(event, key, value)
        }
        val budget = snapshotValue(ContextSnapshotBudget.create(8, 1, 8, 8_192))
        val request = snapshotValue(
            ContextSnapshotRequest.create(
                now = event.observedAt,
                requestedKeys = values.map { it.first },
                allowedPrivacyClassifications = setOf(ContextPrivacyClassification.PRIVATE),
                budget = budget,
            ),
        )
        return ContextSnapshotEngine.build(observations, request)
    }

    private fun observation(
        event: ActiveProviderContextEvent.Refresh,
        key: ContextKey,
        value: ContextValue,
    ): ContextObservation = observationValue(
        ContextObservation.create(
            observationId = ContextObservationId.fromUuid(
                UUID.nameUUIDFromBytes(
                    "h038|${event.revision.value}|${key.value}".toByteArray(StandardCharsets.UTF_8),
                ),
            ),
            sourceType = SOURCE_TYPE,
            sourceReference = SOURCE_REFERENCE,
            key = key,
            claim = ContextObservationClaim.Known(value),
            confidence = CONFIDENCE,
            privacyClassification = ContextPrivacyClassification.PRIVATE,
            observedAt = event.observedAt,
            expiresAt = event.expiresAt,
        ),
    )

    private fun ProviderLifecycleState.isConnectedContext(): Boolean = when (this) {
        is ProviderLifecycleState.Connected,
        is ProviderLifecycleState.Negotiating,
        is ProviderLifecycleState.Registered,
        -> true
        else -> false
    }

    private fun ProviderRuntimeAvailability.toContextAvailability(): ProviderContextAvailability = when (this) {
        ProviderRuntimeAvailability.AVAILABLE -> ProviderContextAvailability.AVAILABLE
        ProviderRuntimeAvailability.DEGRADED -> ProviderContextAvailability.DEGRADED
        ProviderRuntimeAvailability.UNAVAILABLE -> ProviderContextAvailability.UNAVAILABLE
    }

    private fun symbol(raw: String): ContextSymbol = observationValue(ContextSymbol.parse(raw))

    private fun <T> observationValue(result: ContextObservationResult<T>): T = when (result) {
        is ContextObservationResult.Success -> result.value
        is ContextObservationResult.Failure -> error("Invalid internal H038 observation: ${result.error}")
    }

    private fun <T> snapshotValue(result: ContextSnapshotResult<T>): T = when (result) {
        is ContextSnapshotResult.Success -> result.value
        is ContextSnapshotResult.Failure -> error("Invalid internal H038 snapshot policy: ${result.error}")
    }

    private val SOURCE_TYPE = observationValue(ContextSourceType.parse("hugo.provider_context"))
    private val SOURCE_REFERENCE = observationValue(ContextSourceReference.parse("provider_context"))
    private val CONFIDENCE = observationValue(ContextConfidence.create(1000))
    private val HOST_ROLE = observationValue(ContextKey.parse("provider.host.role"))
    private val CONNECTED_PROVIDER_COUNT = observationValue(ContextKey.parse("provider.connected.count"))
    private val CONNECTED_BODY_COUNT = observationValue(ContextKey.parse("provider.body.connected_count"))
    private val CONNECTED_DEVICE_COUNT = observationValue(ContextKey.parse("provider.device.connected_count"))
    private val CONNECTED_SERVICE_COUNT = observationValue(ContextKey.parse("provider.service.connected_count"))
    private val AVAILABLE_SERVICE_COUNT = observationValue(ContextKey.parse("provider.service.available_count"))
    private val BODY_SELECTED = observationValue(ContextKey.parse("provider.body.selected"))
    private val BODY_ACTIVE = observationValue(ContextKey.parse("provider.body.active"))
}

private fun <T> activeProviderFailure(error: ActiveProviderContextError): ActiveProviderContextResult<T> =
    ActiveProviderContextResult.Failure(error)
