package io.redi.robot.body.looi.protocol.telemetrycapture

import io.redi.robot.body.looi.ble.notification.GattNotificationEvent
import io.redi.robot.body.looi.ble.notification.GattNotificationKey
import io.redi.robot.body.looi.ble.notification.GattNotificationRouter
import io.redi.robot.body.looi.ble.notification.GattNotificationSubscriptionHandle
import io.redi.robot.body.looi.ble.queue.GattOperationTransport
import io.redi.robot.body.looi.connection.GattNotificationEnvelope
import io.redi.robot.body.looi.connection.GattServiceInventory
import io.redi.robot.body.looi.protocol.inventory.GattCapabilityCharacteristic
import io.redi.robot.body.looi.protocol.inventory.GattCapabilityInventory
import io.redi.robot.body.looi.protocol.inventory.GattCapabilityService
import io.redi.robot.body.looi.protocol.profile.LooiProtocolProfile
import io.redi.robot.body.looi.protocol.profile.LooiProfileSelectionMode
import io.redi.robot.body.looi.protocol.profile.selectLooiProtocolProfile
import io.redi.robot.protocol.fixtures.FixtureRedaction
import io.redi.robot.protocol.fixtures.GattCharacteristic
import io.redi.robot.protocol.fixtures.GattInventory
import io.redi.robot.protocol.fixtures.GattService
import io.redi.robot.protocol.fixtures.NotificationEvent
import io.redi.robot.protocol.fixtures.ProtocolFixture
import io.redi.robot.protocol.fixtures.ProtocolFixtureParser
import io.redi.robot.protocol.fixtures.ProtocolRunMetadata

const val FED9_CHARACTERISTIC_SHORT_ID = "FED9"
private const val BLUETOOTH_16BIT_SUFFIX = "-0000-1000-8000-00805f9b34fb"

object Fed9TelemetryDiagnosticContract {
    val prohibitedActuationCharacteristicShortIds = setOf("FED0", "FE00", "FF02")

    fun isFed9(uuid: String): Boolean = uuid.equals(FED9_CHARACTERISTIC_SHORT_ID, true) ||
        uuid.lowercase().endsWith("0000fed9$BLUETOOTH_16BIT_SUFFIX")
}

data class Fed9CaptureEndpoint(val serviceUuid: String, val characteristicUuid: String)

/** Candidate evidence for this read-only endpoint; it never authorizes protocol readiness or control. */
data class Fed9CaptureProfileEvidence(
    val profileId: String,
    val selectionMode: LooiProfileSelectionMode,
    val verified: Boolean = false,
)

sealed interface Fed9CaptureAvailability {
    data class Available(
        val profileEvidence: Fed9CaptureProfileEvidence,
        val endpoint: Fed9CaptureEndpoint,
    ) : Fed9CaptureAvailability
    data class Unavailable(val reason: String) : Fed9CaptureAvailability
}

fun fed9CaptureAvailability(inventory: List<GattServiceInventory>): Fed9CaptureAvailability {
    val capabilities = GattCapabilityInventory(
        inventory.map { service ->
            GattCapabilityService(
                uuid = service.uuid,
                isPrimary = true,
                characteristics = service.characteristics.map { GattCapabilityCharacteristic(it.uuid, emptyList()) },
            )
        },
    )
    val selection = selectLooiProtocolProfile(capabilities)
    val observedIds = inventory
        .flatMap { it.characteristics }
        .mapNotNull { characteristicShortId(it.uuid) }
        .toSet()
    if (!observedIds.containsAll(LooiProtocolProfile.PROFILE_A.requiredCharacteristicShortIds)) {
        return Fed9CaptureAvailability.Unavailable("Observed inventory lacks the complete Profile A candidate capability set")
    }
    val endpoints = inventory.asSequence()
        .flatMap { service -> service.characteristics.asSequence().map { service to it } }
        .filter { (_, characteristic) -> Fed9TelemetryDiagnosticContract.isFed9(characteristic.uuid) }
        .map { (service, characteristic) -> Fed9CaptureEndpoint(service.uuid, characteristic.uuid) }
        .toList()
    if (endpoints.size != 1) {
        return Fed9CaptureAvailability.Unavailable("Expected exactly one FED9 endpoint; observed ${endpoints.size}")
    }
    return Fed9CaptureAvailability.Available(
        profileEvidence = Fed9CaptureProfileEvidence(
            profileId = LooiProtocolProfile.PROFILE_A.modelId,
            selectionMode = selection.mode,
            verified = false,
        ),
        endpoint = endpoints.single(),
    )
}

private fun characteristicShortId(uuid: String): String? {
    val normalized = uuid.lowercase()
    if (normalized.matches(Regex("^[0-9a-f]{4}$"))) return normalized.uppercase()
    if (!normalized.endsWith(BLUETOOTH_16BIT_SUFFIX)) return null
    val prefix = normalized.substringBefore(BLUETOOTH_16BIT_SUFFIX)
    return prefix.takeIf { it.length == 8 && it.startsWith("0000") }?.substring(4)?.uppercase()
}

enum class Fed9CaptureSubscriptionState { IDLE, SUBSCRIBING, ACTIVE, STOPPED, FAILED }

data class Fed9CaptureRecord(
    val sequence: Long,
    val relativeTimestampMs: Long,
    val generation: Long,
    val protocolProfile: String,
    val profileVerified: Boolean,
    val serviceUuid: String,
    val characteristicUuid: String,
    val payload: List<Int>,
)

data class Fed9CaptureState(
    val subscriptionState: Fed9CaptureSubscriptionState = Fed9CaptureSubscriptionState.IDLE,
    val notificationCount: Int = 0,
    val latestPayload: List<Int>? = null,
    val records: List<Fed9CaptureRecord> = emptyList(),
    val error: String? = null,
) {
    val captureActive: Boolean get() = subscriptionState == Fed9CaptureSubscriptionState.ACTIVE
    val latestPayloadHex: String get() = latestPayload?.joinToString(" ") { it.toString(16).padStart(2, '0') } ?: "none"
}

sealed interface Fed9FixtureExportResult {
    data class Success(val json: String, val filePath: String? = null) : Fed9FixtureExportResult
    data class Failure(val reason: String) : Fed9FixtureExportResult
}

/** Read-only, bounded capture. It stores raw bytes only and assigns no sensor meaning. */
class Fed9TelemetryCapture(
    private val generation: Long,
    private val profileEvidence: Fed9CaptureProfileEvidence,
    private val endpoint: Fed9CaptureEndpoint,
    transport: GattOperationTransport,
    private val inventory: List<GattServiceInventory>,
    private val maxRecords: Int = 128,
    private val monotonicNowMs: () -> Long,
    private val wallClockNowMs: () -> Long,
    private val onStateChanged: (Fed9CaptureState) -> Unit = {},
) {
    init {
        require(maxRecords > 0)
        require(profileEvidence.profileId == LooiProtocolProfile.PROFILE_A.modelId)
        require(!profileEvidence.verified)
        require(Fed9TelemetryDiagnosticContract.isFed9(endpoint.characteristicUuid))
    }

    private val startedAtMonotonicMs = monotonicNowMs()
    private val runId = "fed9-capture-$startedAtMonotonicMs"
    private var subscription: GattNotificationSubscriptionHandle? = null
    private var sequence = 0L
    private var state = Fed9CaptureState()
    private val router = GattNotificationRouter(transport, ::onRouterEvent, monotonicNowMs)

    fun state(): Fed9CaptureState = state

    fun start(): Fed9CaptureState {
        if (state.subscriptionState in setOf(Fed9CaptureSubscriptionState.SUBSCRIBING, Fed9CaptureSubscriptionState.ACTIVE)) return state
        state = state.copy(subscriptionState = Fed9CaptureSubscriptionState.SUBSCRIBING, error = null)
        publish()
        when (val result = router.requestSubscribe(generation, notificationKey())) {
            is io.redi.robot.body.looi.ble.notification.GattNotificationSubscriptionResult.Accepted -> subscription = result.handle
            is io.redi.robot.body.looi.ble.notification.GattNotificationSubscriptionResult.Rejected -> {
                state = state.copy(subscriptionState = Fed9CaptureSubscriptionState.FAILED, error = result.reason)
                publish()
            }
        }
        return state
    }

    fun onNotification(notification: GattNotificationEnvelope) {
        if (notification.generation != generation || state.subscriptionState != Fed9CaptureSubscriptionState.ACTIVE) return
        router.routeNotification(
            generation = notification.generation,
            channel = notificationKey().channel,
            serviceUuid = notification.serviceUuid,
            characteristicUuid = notification.characteristicUuid,
            bytes = notification.payload.map(Int::toByte).toByteArray(),
        )
    }

    fun stop(reason: String = "capture stopped by operator"): Fed9CaptureState {
        subscription?.let(router::requestUnsubscribe)
        subscription = null
        if (state.subscriptionState != Fed9CaptureSubscriptionState.FAILED) {
            state = state.copy(subscriptionState = Fed9CaptureSubscriptionState.STOPPED, error = null)
            publish()
        }
        return state
    }

    fun onDisconnect(disconnectedGeneration: Long): Fed9CaptureState {
        if (disconnectedGeneration != generation) return state
        router.onGenerationDisconnect(generation)
        return stop("connection generation disconnected")
    }

    fun export(): Fed9FixtureExportResult = runCatching {
        val fixture = ProtocolFixture(
            schemaVersion = "1.0",
            run = ProtocolRunMetadata(runId, wallClockNowMs(), startedAtMonotonicMs, operatorTag = "fed9-diagnostic"),
            redaction = FixtureRedaction(redactedDeviceId = "redacted-looi-device"),
            gattInventory = GattInventory(inventory.map { service ->
                GattService(service.uuid, true, service.characteristics.map { GattCharacteristic(it.uuid, emptyList()) })
            }),
            events = state.records.map { record ->
                NotificationEvent(record.relativeTimestampMs, "fed9-diagnostic", record.serviceUuid, record.characteristicUuid, record.payload)
            },
            notes = "Read-only FED9 diagnostic; profileCandidate=${profileEvidence.profileId}; " +
                "selectionMode=${profileEvidence.selectionMode}; profileVerified=${profileEvidence.verified}; " +
                "generation=$generation; records=${state.records.size}",
        )
        val json = ProtocolFixtureParser.serialize(fixture)
        ProtocolFixtureParser.parse(json)
        Fed9FixtureExportResult.Success(json)
    }.getOrElse { Fed9FixtureExportResult.Failure(it.message ?: "fixture export failed") }

    private fun onRouterEvent(event: GattNotificationEvent) {
        when (event) {
            is GattNotificationEvent.Subscribed -> if (event.generation == generation && event.key == notificationKey()) {
                state = state.copy(subscriptionState = Fed9CaptureSubscriptionState.ACTIVE)
                publish()
            }
            is GattNotificationEvent.SubscribeFailed -> if (event.generation == generation) {
                state = state.copy(subscriptionState = Fed9CaptureSubscriptionState.FAILED, error = event.reason)
                publish()
            }
            is GattNotificationEvent.NotificationReceived -> append(event.payload.timestampMs, event.payload.bytes.map { it.toInt() and 0xff })
            is GattNotificationEvent.MalformedNotification -> if (event.generation == generation && event.handle == subscription) {
                append(monotonicNowMs(), event.rawBytes.map { it.toInt() and 0xff })
            }
            is GattNotificationEvent.Unsubscribed -> Unit
        }
    }

    private fun append(timestampMs: Long, payload: List<Int>) {
        if (state.subscriptionState != Fed9CaptureSubscriptionState.ACTIVE) return
        val record = Fed9CaptureRecord(
            sequence = ++sequence,
            relativeTimestampMs = timestampMs - startedAtMonotonicMs,
            generation = generation,
            protocolProfile = profileEvidence.profileId,
            profileVerified = profileEvidence.verified,
            serviceUuid = endpoint.serviceUuid,
            characteristicUuid = endpoint.characteristicUuid,
            payload = payload.toList(),
        )
        state = state.copy(
            notificationCount = state.notificationCount + 1,
            latestPayload = record.payload,
            records = (state.records + record).takeLast(maxRecords),
        )
        publish()
    }

    private fun notificationKey() = GattNotificationKey("fed9-diagnostic", endpoint.serviceUuid, endpoint.characteristicUuid)
    private fun publish() = onStateChanged(state)
}
