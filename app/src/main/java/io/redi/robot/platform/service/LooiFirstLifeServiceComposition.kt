package io.redi.robot.platform.service

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import io.redi.robot.body.looi.connection.ConnectionState
import io.redi.robot.body.looi.connection.ConnectionTarget
import io.redi.robot.body.looi.firstlife.LooiFirstLifeDefinition
import io.redi.robot.body.looi.firstlife.LooiFirstLifeProviderSession
import io.redi.robot.body.looi.firstlife.isCurrentForFirstLife
import io.redi.robot.body.looi.firstlife.LooiFirstLifeIdentityMappingFactory
import io.redi.robot.body.looi.firstlife.LooiFirstLifeRuntimeBinding
import io.redi.robot.body.looi.capability.LooiVerifiedRuntimePort
import io.redi.robot.body.looi.protocol.handshake.HandshakeAttemptSummary
import io.redi.robot.body.looi.protocol.telemetrycapture.VerifiedFed9Prerequisite
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.negotiation.CapabilityNegotiationStatus
import io.redi.robot.capability.negotiation.CapabilityNegotiationReport
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.CapabilityProvider
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.RedactedProviderIdentity
import io.redi.robot.capability.provider.redacted
import io.redi.robot.connection.api.ConnectionCommandState
import io.redi.robot.connection.api.ConnectionConnectRequest
import io.redi.robot.connection.api.ConnectionDisconnectRequest
import io.redi.robot.connection.api.ConnectionEndpointReference
import io.redi.robot.connection.api.ConnectionGeneration
import io.redi.robot.connection.api.ConnectionLifecycleSnapshot
import io.redi.robot.connection.api.ConnectionLifecycleState
import io.redi.robot.connection.api.ConnectionPeerAlias
import io.redi.robot.connection.api.ConnectionRequestId
import io.redi.robot.connection.api.ConnectionResult
import io.redi.robot.platform.connection.ble.BLE_CONNECTION_METHOD
import io.redi.robot.platform.connection.ble.BleConnectionLifecycleObserver
import io.redi.robot.platform.connection.ble.BleConnectionProvider
import io.redi.robot.platform.connection.ble.BleEndpointResolution
import io.redi.robot.platform.connection.ble.BleEndpointResolver
import io.redi.robot.platform.connection.ble.BleMonotonicClock
import io.redi.robot.platform.connection.ble.BleTransportAvailability
import io.redi.robot.platform.connection.ble.BleTransportAvailabilityProbe
import io.redi.robot.platform.connection.ble.BleTransportTarget
import io.redi.robot.platform.connection.ble.TimerBleConnectionDeadlineScheduler
import io.redi.robot.platform.storage.LooiProviderIdentityRepository
import io.redi.robot.platform.storage.LooiProviderIdentityResult
import io.redi.robot.discovery.identity.ProtectedIdentityEvidenceReference
import io.redi.robot.HugoFirstLifeApplication
import io.redi.robot.feature.providers.ProviderBlockedEvidence
import io.redi.robot.feature.providers.ProviderBlockedMappingResult
import io.redi.robot.feature.providers.ProviderBlockedStateMapper
import io.redi.robot.feature.providers.ProviderBlockedUiState
import io.redi.robot.feature.providers.toDiagnosticSection
import io.redi.robot.firstlife.runtime.FirstLifeOrientationAuthorityContext
import io.redi.robot.provider.diagnostic.BlockedStateDiagnostic
import io.redi.robot.body.looi.firstlife.diagnostics
import java.security.MessageDigest
import java.util.UUID

enum class LooiFirstLifeConnectionStage {
    IDLE, IDENTITY_BLOCKED, CONNECTING, CHANNEL_ESTABLISHED, TRANSPORT_READY,
    PROTOCOL_READY, DISCONNECTED, FAILED,
}

/** Redacted service evidence. Every boolean remains independent and grants no authority. */
data class LooiFirstLifeServiceSnapshot(
    val provider: RedactedProviderIdentity?,
    val safeIdentityEvidence: String?,
    val providerGeneration: Long?,
    val connectionSessionReference: String?,
    val stage: LooiFirstLifeConnectionStage,
    val manifestResolved: Boolean,
    val adapterKnownCompatible: Boolean,
    val protocolReady: Boolean,
    val negotiationStatus: CapabilityNegotiationStatus?,
    val negotiatedCapabilities: List<String>,
    val trusted: Boolean = false,
    val authorized: Boolean = false,
    val activeBodySelected: Boolean = false,
    val capabilityExecuted: Boolean = false,
    val firstLifeProviderObserved: Boolean = false,
    val firstLifeBodyAvailable: Boolean = false,
    val blockedState: BlockedStateDiagnostic? = null,
    val diagnosticsReady: Boolean = false,
)

/** Android composition root. ConnectedDeviceService remains the only owner of the actual BLE/GATT session. */
class LooiFirstLifeServiceComposition(
    context: Context,
    private val bridge: ServiceOwnedLooiBleBridge,
    private val stateObserver: (LooiFirstLifeServiceSnapshot) -> Unit = {},
) {
    private val identityRepository = LooiProviderIdentityRepository(context.applicationContext)
    private var endpoint: Pair<ConnectionEndpointReference, BleTransportTarget>? = null
    private var lifecycle = ConnectionLifecycleSnapshot.initial()
    private var providerSession: LooiFirstLifeProviderSession? = null
    private var protocolReady = false
    private var transportReady = false
    private var negotiatedStatus: CapabilityNegotiationStatus? = null
    private var negotiationReport: CapabilityNegotiationReport? = null
    private var negotiatedCapabilities: List<String> = emptyList()
    private var identityEvidence: String? = null
    private var pendingIdentityBinding: ProtectedIdentityEvidenceReference? = null
    private var blockedState: BlockedStateDiagnostic? = null
    private var blockedPresentation: ProviderBlockedUiState? = null
    private var diagnosticsReady = false
    private var requestId: ConnectionRequestId? = null
    private var nextGeneration = 1L
    private val firstLifeRuntime = (context.applicationContext as? HugoFirstLifeApplication)
        ?.hugoIdentityBootstrapSnapshot?.identity?.let { identity ->
            LooiFirstLifeRuntimeBinding(identity, SystemClock.elapsedRealtime())
        }
    private val clock = BleMonotonicClock { timestamp(SystemClock.elapsedRealtime()) }
    private val connectionProvider = BleConnectionProvider(
        transport = bridge,
        endpoints = BleEndpointResolver { requested ->
            endpoint?.takeIf { it.first == requested }?.second?.let(BleEndpointResolution::Resolved)
                ?: BleEndpointResolution.Unavailable
        },
        availability = availability(context.applicationContext),
        clock = clock,
        deadlines = TimerBleConnectionDeadlineScheduler(clock),
        lifecycleObserver = BleConnectionLifecycleObserver { changed ->
            lifecycle = changed
            if (changed.state !is ConnectionLifecycleState.Connected) clearReadiness()
            publish()
        },
    )

    fun connect(target: ConnectionTarget): LooiFirstLifeServiceSnapshot {
        val restoredIdentity = when (val result = identityRepository.resolve(target.deviceId, System.currentTimeMillis())) {
            is LooiProviderIdentityResult.Available -> result
            is LooiProviderIdentityResult.Failed -> {
                providerSession = null
                setBlocked(ProviderBlockedEvidence.Unmapped())
                publish(LooiFirstLifeConnectionStage.IDENTITY_BLOCKED)
                return snapshot(LooiFirstLifeConnectionStage.IDENTITY_BLOCKED)
            }
        }
        val identity = restoredIdentity.identity
        blockedState = null
        blockedPresentation = null
        diagnosticsReady = false
        val genericGeneration = connection(ConnectionGeneration.create(nextGeneration++))
        pendingIdentityBinding = restoredIdentity.protectedBinding
        val mapping = LooiFirstLifeIdentityMappingFactory.create(
            identity,
            restoredIdentity.protectedBinding,
            clock.now(),
            verified = false,
        )
        providerSession = LooiFirstLifeProviderSession(
            identity,
            provider(ProviderGeneration.create(genericGeneration.value)),
            mapping,
        )
        identityEvidence = safeIdentity(identity.instanceId.value)
        val opaqueEndpoint = connection(ConnectionEndpointReference.parse("looi.endpoint.g${genericGeneration.value}"))
        endpoint = opaqueEndpoint to BleTransportTarget(target.deviceId, target.displayName)
        requestId = connection(ConnectionRequestId.parse(UUID.randomUUID().toString()))
        val now = clock.now()
        val result = connectionProvider.connect(connection(ConnectionConnectRequest.create(
            requestId = checkNotNull(requestId),
            generation = genericGeneration,
            method = BLE_CONNECTION_METHOD,
            endpoint = opaqueEndpoint,
            peerAlias = connection(ConnectionPeerAlias.parse("LOOI Reference Body")),
            requestedAt = now,
            deadline = timestamp(now.value + CONNECT_TIMEOUT_MS),
        )))
        if (result.state != ConnectionCommandState.ACCEPTED) clearReadiness()
        publish()
        return snapshot()
    }

    fun onExistingStateChanged(state: ConnectionState) {
        bridge.onExistingStateChanged(state)
        transportReady = state.phase == io.redi.robot.body.looi.connection.ConnectionPhase.TRANSPORT_READY
        if (!transportReady) clearReadiness()
        if (state.phase in setOf(
                io.redi.robot.body.looi.connection.ConnectionPhase.DISCONNECTED,
                io.redi.robot.body.looi.connection.ConnectionPhase.FAILED,
            )
        ) {
            if (state.phase == io.redi.robot.body.looi.connection.ConnectionPhase.FAILED) {
                setBlocked(ProviderBlockedEvidence.Connection(
                    io.redi.robot.connection.api.ConnectionAvailability.UNAVAILABLE,
                    io.redi.robot.connection.api.ConnectionAvailabilityReason.UNKNOWN,
                    providerSession?.identity?.displayAlias,
                ))
            }
            loseFirstLifeProvider()
        }
        publish()
    }

    fun onHandshakeChanged(summary: HandshakeAttemptSummary?) {
        val generation = lifecycle.latestGeneration?.value ?: return
        protocolReady = summary != null && VerifiedFed9Prerequisite.isSatisfied(generation, summary)
        if (protocolReady) {
            val current = providerSession
            if (current != null && current.preparedProvider.manifestResolution?.resolved?.identityBinding?.instanceId == null) {
                val verifiedMapping = LooiFirstLifeIdentityMappingFactory.create(
                    current.identity,
                    checkNotNull(pendingIdentityBinding),
                    clock.now(),
                    verified = true,
                )
                providerSession = LooiFirstLifeProviderSession(
                    current.identity,
                    provider(ProviderGeneration.create(generation)),
                    verifiedMapping,
                )
            }
            val report = providerSession?.negotiate(lifecycle, true, SystemClock.elapsedRealtime())
            negotiationReport = report
            negotiatedStatus = report?.status
            negotiatedCapabilities = report?.negotiatedCapabilities?.map { it.capability.wireValue }
                ?.sorted().orEmpty()
            if (report != null) feedFirstLifeRuntime(report)
            if (report != null) {
                providerSession?.diagnostics(lifecycle, connectionProvider.availabilitySnapshot(), report, SystemClock.elapsedRealtime())
                diagnosticsReady = true
                blockedState = null
                blockedPresentation = null
            }
        } else {
            negotiationReport = null
            negotiatedStatus = null
            negotiatedCapabilities = emptyList()
            firstLifeRuntime?.deactivateActiveBody(SystemClock.elapsedRealtime())
        }
        publish()
    }

    fun disconnect(): LooiFirstLifeServiceSnapshot {
        val connected = lifecycle.state as? ConnectionLifecycleState.Connected
        val id = requestId
        if (connected != null && id != null) {
            connectionProvider.disconnect(ConnectionDisconnectRequest(
                id,
                connected.session.generation,
                connected.session.id,
                clock.now(),
            ))
        }
        clearReadiness()
        publish()
        return snapshot()
    }

    fun snapshot(): LooiFirstLifeServiceSnapshot = snapshot(null)

    fun capabilityProvider(runtime: LooiVerifiedRuntimePort): CapabilityProvider? =
        providerSession?.takeIf { it.isValidated() }?.capabilityProvider(runtime)

    fun currentNegotiation(): CapabilityNegotiationReport? = negotiationReport

    fun runtimeSnapshot() = firstLifeRuntime?.snapshot()

    fun blockedPresentation(): ProviderBlockedUiState? = blockedPresentation

    fun selectActiveBody() = firstLifeRuntime?.selectActiveBody(SystemClock.elapsedRealtime()).also { publish() }

    fun deactivateActiveBody() = firstLifeRuntime?.deactivateActiveBody(SystemClock.elapsedRealtime()).also { publish() }

    fun expireActiveBody() = firstLifeRuntime?.expireActiveBody(SystemClock.elapsedRealtime()).also { result ->
        if (result?.status == io.redi.robot.body.looi.firstlife.LooiActiveBodySelectionStatus.EXPIRED) publish()
    }

    fun confirmOrientationMounting(): Boolean =
        firstLifeRuntime?.confirmOrientationMounting(SystemClock.elapsedRealtime()) == true

    fun orientationAuthorityContext(): FirstLifeOrientationAuthorityContext? {
        val report = negotiationReport ?: return null
        return firstLifeRuntime?.orientationAuthorityContext(report)
    }

    /**
     * Re-evaluates H029 evidence only when an explicit orientation hold finds the prior report
     * expired. This creates no authority and changes no TTL; H014/H030 still bind the resulting
     * two-second hold to the exact refreshed negotiation and connection generation/session.
     */
    fun orientationAuthorityContextForExplicitHold(): FirstLifeOrientationAuthorityContext? {
        val now = SystemClock.elapsedRealtime()
        val current = negotiationReport
        val report = if (current?.isCurrentForFirstLife(now) == true) {
            current
        } else {
            providerSession?.negotiate(lifecycle, protocolReady, now).also { refreshed ->
                negotiationReport = refreshed
                negotiatedStatus = refreshed?.status
                negotiatedCapabilities = refreshed?.negotiatedCapabilities
                    ?.map { it.capability.wireValue }
                    ?.sorted()
                    .orEmpty()
                diagnosticsReady = refreshed != null
                if (refreshed != null) {
                    providerSession?.diagnostics(
                        lifecycle,
                        connectionProvider.availabilitySnapshot(),
                        refreshed,
                        now,
                    )
                }
                publish()
            }
        } ?: return null
        return firstLifeRuntime?.orientationAuthorityContext(report)
    }

    fun currentConnectionSessionIds() = (lifecycle.state as? ConnectionLifecycleState.Connected)
        ?.let { listOf(it.session.id) }.orEmpty()

    private fun snapshot(stageOverride: LooiFirstLifeConnectionStage?): LooiFirstLifeServiceSnapshot {
        val connected = lifecycle.state as? ConnectionLifecycleState.Connected
        val session = providerSession
        val stage = stageOverride ?: when {
            protocolReady -> LooiFirstLifeConnectionStage.PROTOCOL_READY
            transportReady -> LooiFirstLifeConnectionStage.TRANSPORT_READY
            lifecycle.state is ConnectionLifecycleState.Connecting -> LooiFirstLifeConnectionStage.CONNECTING
            lifecycle.state is ConnectionLifecycleState.Connected -> LooiFirstLifeConnectionStage.CHANNEL_ESTABLISHED
            lifecycle.state is ConnectionLifecycleState.Disconnected || lifecycle.state is ConnectionLifecycleState.Cancelled ->
                LooiFirstLifeConnectionStage.DISCONNECTED
            lifecycle.state is ConnectionLifecycleState.Failed -> LooiFirstLifeConnectionStage.FAILED
            else -> LooiFirstLifeConnectionStage.IDLE
        }
        val runtime = firstLifeRuntime?.snapshot()
        return LooiFirstLifeServiceSnapshot(
            provider = session?.identity?.redacted(),
            safeIdentityEvidence = identityEvidence,
            providerGeneration = lifecycle.latestGeneration?.value,
            connectionSessionReference = connected?.session?.id?.value?.let(::safeSession),
            stage = stage,
            manifestResolved = session?.isValidated() == true,
            adapterKnownCompatible = session?.isValidated() == true,
            protocolReady = protocolReady,
            negotiationStatus = negotiatedStatus,
            negotiatedCapabilities = negotiatedCapabilities,
            activeBodySelected = runtime?.activeBodySelected == true,
            firstLifeProviderObserved = runtime?.providerPrepared == true,
            firstLifeBodyAvailable = runtime?.bodyReady == true,
            blockedState = blockedState,
            diagnosticsReady = diagnosticsReady,
        )
    }

    private fun clearReadiness() {
        transportReady = false
        protocolReady = false
        negotiatedStatus = null
        negotiationReport = null
        negotiatedCapabilities = emptyList()
        firstLifeRuntime?.deactivateActiveBody(SystemClock.elapsedRealtime())
    }

    private fun publish(stage: LooiFirstLifeConnectionStage? = null) = stateObserver(snapshot(stage))

    private fun setBlocked(evidence: ProviderBlockedEvidence) {
        blockedPresentation = (ProviderBlockedStateMapper.map(evidence) as? ProviderBlockedMappingResult.Blocked)?.state
        blockedState = blockedPresentation?.toDiagnosticSection()
    }

    private fun feedFirstLifeRuntime(report: CapabilityNegotiationReport) {
        val runtime = firstLifeRuntime ?: return
        val provider = providerSession ?: return
        runtime.providerReady(provider, lifecycle, report, SystemClock.elapsedRealtime())
    }

    private fun loseFirstLifeProvider() {
        val generation = firstLifeRuntime?.snapshot()?.providerGeneration ?: return
        firstLifeRuntime?.providerLost(generation, SystemClock.elapsedRealtime())
    }

    private fun safeIdentity(raw: String): String = "looi-" + sha256(raw).take(12)
    private fun safeSession(raw: String): String = "session-" + sha256(raw).take(10)
    private fun sha256(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}

private fun availability(context: Context): BleTransportAvailabilityProbe {
    val manager = { context.getSystemService(BluetoothManager::class.java) }
    return BleTransportAvailabilityProbe {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ->
                BleTransportAvailability.PERMISSION_UNAVAILABLE
            manager()?.adapter == null -> BleTransportAvailability.TRANSPORT_UNAVAILABLE
            runCatching { manager()?.adapter?.isEnabled != true }.getOrDefault(true) ->
                BleTransportAvailability.BLUETOOTH_DISABLED
            else -> BleTransportAvailability.AVAILABLE
        }
    }
}

private fun timestamp(value: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(value)) {
    is CapabilityEnvelopeResult.Success -> result.value
    is CapabilityEnvelopeResult.Failure -> error(result.error)
}
private fun <T> connection(result: ConnectionResult<T>): T = (result as ConnectionResult.Success).value
private fun <T> provider(result: io.redi.robot.capability.provider.CapabilityProviderPortResult<T>): T =
    (result as io.redi.robot.capability.provider.CapabilityProviderPortResult.Success).value
