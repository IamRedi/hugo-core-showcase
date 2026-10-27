package io.redi.robot.feature.manualcontrol

import io.redi.robot.body.looi.ble.queue.ActiveGattOperationTransport
import io.redi.robot.body.looi.ble.queue.GattOperationKind
import io.redi.robot.body.looi.ble.queue.GattOperationEnqueued
import io.redi.robot.body.looi.ble.queue.GattOperationLane
import io.redi.robot.body.looi.ble.queue.GattOperationRejected
import io.redi.robot.body.looi.ble.queue.GattOperationResult
import io.redi.robot.body.looi.ble.queue.GattOperationSuccess
import io.redi.robot.body.looi.ble.queue.GattQueuedOperationRequest
import io.redi.robot.body.looi.ble.queue.SerializedGattOperationQueue
import io.redi.robot.body.looi.connection.GattNotificationEnvelope
import io.redi.robot.body.looi.connection.GattServiceInventory
import io.redi.robot.body.looi.protocol.profile.LooiProtocolProfile
import io.redi.robot.body.looi.protocol.profileA.ProfileAAxisPolarity
import io.redi.robot.body.looi.protocol.profileA.ProfileADriveCommandExecutor
import io.redi.robot.body.looi.protocol.profileA.ProfileADriveEndpoint
import io.redi.robot.body.looi.protocol.profileA.ProfileADriveExecutionResult
import io.redi.robot.body.looi.protocol.profileA.ProfileADriveExperimentConfiguration
import io.redi.robot.body.looi.protocol.profileA.ProfileADriveEncoder
import io.redi.robot.body.looi.protocol.profileA.ProfileADriveQueuePolicy
import io.redi.robot.body.looi.protocol.profileA.ProfileAHeadEncoder
import io.redi.robot.body.looi.protocol.profileA.ProfileAHeadEncodingResult
import io.redi.robot.body.looi.protocol.profileA.ProfileAHeadExperimentConfiguration
import io.redi.robot.body.looi.protocol.profileA.ProfileAHeadRateLimiter
import io.redi.robot.body.looi.protocol.profileA.ProfileALightEncoder
import io.redi.robot.body.looi.protocol.profileA.ProfileALightEncodingResult
import io.redi.robot.body.looi.protocol.profileA.ProfileALightExperimentConfiguration
import io.redi.robot.body.looi.protocol.telemetry.LooiTelemetryParser
import io.redi.robot.body.looi.protocol.telemetry.LowerContactSafetyAdapter
import io.redi.robot.body.looi.protocol.telemetry.RawLooiTelemetryEnvelope
import io.redi.robot.core.ActionRequest
import io.redi.robot.core.ActionSource
import io.redi.robot.core.RobotAction
import io.redi.robot.core.authority.MotionAuthorityState
import io.redi.robot.core.authority.MotionAuthorityStore
import io.redi.robot.core.authority.MotionLeasePolicy
import io.redi.robot.safety.DefaultSafetyController
import io.redi.robot.safety.SafetyContext
import io.redi.robot.safety.SafetyDecision
import io.redi.robot.safety.deadman.DeadManController
import io.redi.robot.safety.deadman.DeadManMonotonicClock
import io.redi.robot.safety.deadman.DeadManPolicy
import io.redi.robot.safety.deadman.DeadManScheduler
import io.redi.robot.safety.estop.EmergencyStopController
import io.redi.robot.safety.estop.EmergencyStopDelivery
import io.redi.robot.safety.estop.EmergencyStopEvent
import io.redi.robot.safety.estop.EmergencyStopPolicy
import io.redi.robot.safety.estop.EmergencyStopQueueCancellation
import io.redi.robot.safety.estop.EmergencyStopReason
import io.redi.robot.safety.estop.EmergencyStopResetInitiator
import io.redi.robot.safety.sensors.CliffContactObservationStatus

private const val PROFILE_A_SERVICE_UUID = "000000ff-0000-1000-8000-00805f9b34fb"
private const val FED0_UUID = "0000fed0-0000-1000-8000-00805f9b34fb"
private const val FED1_UUID = "0000fed1-0000-1000-8000-00805f9b34fb"
private const val FED2_UUID = "0000fed2-0000-1000-8000-00805f9b34fb"
private const val RAISED_WHEEL_MAX_PULSE_MS = 250L

enum class T032VerificationMode { DISARMED, RAISED_WHEELS, FLOOR }

data class T032AcceptanceState(
    val generation: Long = 0,
    val prerequisiteReady: Boolean = false,
    val fed9Active: Boolean = false,
    val sensorStateKnown: Boolean = false,
    val streamFresh: Boolean = false,
    val lowerContactSafe: Boolean? = null,
    val mode: T032VerificationMode = T032VerificationMode.DISARMED,
    val raisedWheelVerified: Boolean = false,
    val headCalibrationArmed: Boolean = false,
    val emergencyStopLatched: Boolean = false,
    val deadManArmed: Boolean = false,
    val stopRequestCount: Int = 0,
    val driveWriteStatus: String = "none",
    val lastOutcome: String = "T032 acceptance mode is disarmed",
) {
    val driveEnabled: Boolean
        get() = prerequisiteReady && fed9Active && sensorStateKnown && mode != T032VerificationMode.DISARMED && !emergencyStopLatched

    val headEnabled: Boolean
        get() = driveEnabled && mode == T032VerificationMode.RAISED_WHEELS && headCalibrationArmed

    val lightEnabled: Boolean
        get() = driveEnabled
}

enum class T032VerifiedDisposition { ACCEPTED, SAFETY_REJECTED, TRANSPORT_REJECTED, UNAVAILABLE }

data class T032VerifiedCommandResult(
    val state: T032AcceptanceState,
    val disposition: T032VerifiedDisposition,
)

private data class ProfileACommandEndpoints(
    val serviceUuid: String,
    val fed0Uuid: String,
    val fed1Uuid: String?,
    val fed2Uuid: String?,
)

/**
 * Explicitly armed acceptance-only composition for T032. It reuses the production safety,
 * dead-man, encoder, and serialized queue paths and never exposes raw writes to the UI.
 */
class T032ManualAcceptanceSession(
    private val generation: Long,
    inventory: List<GattServiceInventory>,
    transport: ActiveGattOperationTransport,
    private val clock: DeadManMonotonicClock,
    scheduler: DeadManScheduler,
    private val onStateChanged: (T032AcceptanceState) -> Unit = {},
) {
    init {
        require(generation >= 0)
        require(transport.generation == generation)
        require(transport.isAvailable())
    }

    private val endpoints = endpointsFrom(inventory)
    private val queue = SerializedGattOperationQueue(transport, nowProvider = clock::nowElapsedMs)
    private val sensorAdapter = LowerContactSafetyAdapter(generation)
    private val safety = DefaultSafetyController()
    private val authority = MotionAuthorityStore(
        MotionLeasePolicy(maxDurationMs = 250L),
        MotionAuthorityState(connectionGeneration = generation),
    )
    private var connected = true
    private var fed9Active = false
    private var mode = T032VerificationMode.DISARMED
    // T032 Stage C was physically accepted on the target LOOI before continuous manual mode.
    private var raisedWheelVerified = true
    private var headCalibrationArmed = false
    private var raisedPulseStartedAtMs: Long? = null
    private var motionActive = false
    private var stopRequestCount = 0
    private var lastOutcome = "Waiting for active FED9 safety telemetry"
    private var internalSequence = 1_000_000L
    private var lastDriveOperationId: io.redi.robot.body.looi.protocol.profileA.ProfileADriveOperationId? = null

    private lateinit var driveExecutor: ProfileADriveCommandExecutor
    private val emergencyStop = EmergencyStopController(
        authority = authority,
        queueCancellation = EmergencyStopQueueCancellation { queue.cancelGeneration(generation) },
        stopDelivery = EmergencyStopDelivery { deliverNeutralStop(clock.nowElapsedMs()) },
        policy = EmergencyStopPolicy(repeatedStopRequestCount = 2),
    )
    private val deadMan = DeadManController(
        policy = DeadManPolicy(commandLeaseTimeoutMs = 100L),
        clock = clock,
        scheduler = scheduler,
        emergencyStop = emergencyStop,
    )
    private val headEncoder = ProfileAHeadEncoder(
        ProfileAHeadExperimentConfiguration(true, safeUpByte = 0x20, candidateCenterByte = 0x5a, safeDownByte = 0xc0, minimumIntervalMs = 100L),
    )
    private val headRateLimiter = ProfileAHeadRateLimiter(100L)
    private val lightEncoder = ProfileALightEncoder(
        ProfileALightExperimentConfiguration(true, minimumOnByte = 0x03, supportsBrightness = false),
    )

    init {
        driveExecutor = ProfileADriveCommandExecutor(
            safety = safety,
            deadMan = deadMan,
            emergencyStop = emergencyStop,
            encoder = ProfileADriveEncoder(
                ProfileADriveExperimentConfiguration.conservativeCandidate().copy(
                    experimentalEnabled = true,
                    linearPolarity = ProfileAAxisPolarity.POSITIVE,
                    angularPolarity = ProfileAAxisPolarity.INVERTED,
                ),
            ),
            queue = queue,
            endpoint = ProfileADriveEndpoint(endpoints.serviceUuid, endpoints.fed0Uuid),
            queuePolicy = ProfileADriveQueuePolicy(writeWithResponse = false, timeoutMs = 500L),
        )
        deadMan.beginConnectionEpoch(generation)
        publish()
    }

    @Synchronized
    fun setFed9Active(active: Boolean): T032AcceptanceState {
        fed9Active = active
        if (!active) {
            mode = T032VerificationMode.DISARMED
            raisedPulseStartedAtMs = null
            motionActive = false
            lastOutcome = "FED9 subscription is not active; acceptance mode disarmed"
        } else if (lastOutcome == "FED9 subscription is not active; acceptance mode disarmed") {
            lastOutcome = "FED9 active; waiting for a verified contact-state observation"
        }
        return publish()
    }

    @Synchronized
    fun onNotification(notification: GattNotificationEnvelope, observedAtElapsedMs: Long): T032AcceptanceState {
        if (notification.generation != generation || !isShortUuid(notification.characteristicUuid, "fed9")) return snapshot(observedAtElapsedMs)
        val parsed = LooiTelemetryParser.parse(
            RawLooiTelemetryEnvelope(
                generation = generation,
                timestampMs = observedAtElapsedMs,
                profile = LooiProtocolProfile.PROFILE_A,
                characteristicUuid = notification.characteristicUuid,
                payload = notification.payload.toList(),
            ),
        )
        val sensorState = sensorAdapter.accept(parsed)
        if (
            motionActive &&
            sensorState.status == CliffContactObservationStatus.VALID &&
            sensorState.readings.values.any { it.triggered }
        ) {
            activateEmergencyStop(
                EmergencyStopReason.SAFETY_POLICY,
                observedAtElapsedMs,
                "Lower-contact safety stop latched; explicit stationary reset required",
            )
        }
        return publish(observedAtElapsedMs)
    }

    @Synchronized
    fun armRaisedWheels(): T032AcceptanceState {
        val state = snapshot(clock.nowElapsedMs())
        if (!state.prerequisiteReady || !state.fed9Active || !state.sensorStateKnown || state.emergencyStopLatched) {
            lastOutcome = "Raised-wheel arm rejected: handshake, active FED9, known contact state, and reset e-stop are required"
        } else {
            mode = T032VerificationMode.RAISED_WHEELS
            raisedPulseStartedAtMs = null
            lastOutcome = "Raised-wheel mode armed; each motion pulse is capped at 250 ms"
        }
        return publish()
    }

    @Synchronized
    fun confirmRaisedWheelChecksPassed(): T032AcceptanceState {
        if (mode != T032VerificationMode.RAISED_WHEELS || emergencyStop.state.latched) {
            lastOutcome = "Raised-wheel confirmation rejected"
        } else {
            raisedWheelVerified = true
            mode = T032VerificationMode.DISARMED
            headCalibrationArmed = false
            lastOutcome = "Raised-wheel checks recorded locally; floor mode remains disarmed"
        }
        return publish()
    }

    @Synchronized
    fun armFloor(): T032AcceptanceState {
        val state = snapshot(clock.nowElapsedMs())
        if (!raisedWheelVerified || !state.sensorStateKnown || !state.fed9Active || state.emergencyStopLatched) {
            lastOutcome = "Floor arm rejected: raised-wheel pass, active FED9, known contact state, and reset e-stop are required"
        } else {
            mode = T032VerificationMode.FLOOR
            raisedPulseStartedAtMs = null
            lastOutcome = "Continuous manual control armed"
        }
        return publish()
    }

    @Synchronized
    fun armHeadCalibration(): T032AcceptanceState {
        if (mode == T032VerificationMode.RAISED_WHEELS && !emergencyStop.state.latched) {
            headCalibrationArmed = true
            lastOutcome = "Candidate head calibration armed for raised-wheel setup only"
        } else {
            lastOutcome = "Head calibration arm rejected"
        }
        return publish()
    }

    @Synchronized
    fun dispatch(command: ManualDriveCommand): T032AcceptanceState = dispatchVerified(command).state

    @Synchronized
    fun dispatchVerified(command: ManualDriveCommand): T032VerifiedCommandResult {
        val now = clock.nowElapsedMs()
        val disposition = when (command) {
            is ManualDriveCommand.Refresh -> {
                val current = snapshot(now)
                if (!current.driveEnabled) {
                    lastOutcome = "Drive rejected: acceptance mode is not ready and armed"
                    return T032VerifiedCommandResult(publish(now), T032VerifiedDisposition.SAFETY_REJECTED)
                }
                if (mode == T032VerificationMode.RAISED_WHEELS) {
                    val startedAt = raisedPulseStartedAtMs ?: command.issuedAtElapsedMs.also { raisedPulseStartedAtMs = it }
                    if (command.issuedAtElapsedMs - startedAt >= RAISED_WHEEL_MAX_PULSE_MS) {
                        activateEmergencyStop(EmergencyStopReason.SAFETY_POLICY, now, "Raised-wheel 250 ms pulse cap reached")
                        return T032VerifiedCommandResult(publish(now), T032VerifiedDisposition.SAFETY_REJECTED)
                    }
                }
                val result = driveExecutor.execute(
                    ActionRequest(
                        sequence = internalSequence++,
                        source = ActionSource.MANUAL,
                        action = RobotAction.Drive(
                            command.direction.linear * 2f,
                            command.direction.angular * 2f,
                            command.issuedAtElapsedMs,
                        ),
                    ),
                    safetyContext(now, allowGenerationScopedStart = true),
                    generation,
                )
                lastOutcome = "Drive ${command.direction.name}: ${describe(result)}"
                when (result) {
                    is ProfileADriveExecutionResult.Queued -> {
                        motionActive = true
                        lastDriveOperationId = result.operationId
                    }
                    else -> Unit
                }
                driveDisposition(result)
            }
            is ManualDriveCommand.Stop -> {
                raisedPulseStartedAtMs = null
                motionActive = false
                val result = deliverNeutralStop(command.issuedAtElapsedMs)
                lastOutcome = "Neutral stop requested: ${command.reason.name}"
                result?.let(::driveDisposition) ?: T032VerifiedDisposition.UNAVAILABLE
            }
        }
        return T032VerifiedCommandResult(publish(now), disposition)
    }

    @Synchronized
    fun requestHead(normalizedPitch: Float): T032AcceptanceState = requestHeadVerified(normalizedPitch).state

    @Synchronized
    fun requestHeadVerified(normalizedPitch: Float): T032VerifiedCommandResult {
        val now = clock.nowElapsedMs()
        if (!snapshot(now).headEnabled || !headRateLimiter.accept(now)) {
            lastOutcome = "Head request rejected: raised-wheel calibration arm and rate limit are required"
            return T032VerifiedCommandResult(publish(now), T032VerifiedDisposition.SAFETY_REJECTED)
        }
        val request = ActionRequest(internalSequence++, ActionSource.MANUAL, RobotAction.SetHead(normalizedPitch, now))
        val approval = safety.evaluate(request, safetyContext(now))
        val encoded = headEncoder.encode(approval)
        val disposition = when (encoded) {
            is ProfileAHeadEncodingResult.Encoded -> enqueueVerified(endpoints.fed1Uuid, encoded.payload, "Head")
            is ProfileAHeadEncodingResult.Rejected -> {
                lastOutcome = "Head rejected: ${encoded.reason}"
                T032VerifiedDisposition.SAFETY_REJECTED
            }
        }
        return T032VerifiedCommandResult(publish(now), disposition)
    }

    @Synchronized
    fun requestLight(normalizedBrightness: Float): T032AcceptanceState = requestLightVerified(normalizedBrightness).state

    @Synchronized
    fun requestLightVerified(normalizedBrightness: Float): T032VerifiedCommandResult {
        val now = clock.nowElapsedMs()
        if (!snapshot(now).lightEnabled) {
            lastOutcome = "Light request rejected: raised-wheel mode is required"
            return T032VerifiedCommandResult(publish(now), T032VerifiedDisposition.SAFETY_REJECTED)
        }
        val request = ActionRequest(internalSequence++, ActionSource.MANUAL, RobotAction.SetLight(normalizedBrightness, now))
        val approval = safety.evaluate(request, safetyContext(now))
        val encoded = lightEncoder.encode(approval)
        val disposition = when (encoded) {
            is ProfileALightEncodingResult.Encoded -> enqueueVerified(endpoints.fed2Uuid, encoded.payload, "Light")
            is ProfileALightEncodingResult.Rejected -> {
                lastOutcome = "Light rejected: ${encoded.reason}"
                T032VerifiedDisposition.SAFETY_REJECTED
            }
        }
        return T032VerifiedCommandResult(publish(now), disposition)
    }

    @Synchronized
    fun emergencyStop(): T032AcceptanceState {
        activateEmergencyStop(EmergencyStopReason.LOCAL_USER, clock.nowElapsedMs(), "Local emergency stop activated")
        return publish()
    }

    @Synchronized
    fun resetEmergencyStopReportedStationary(): T032AcceptanceState {
        val transition = emergencyStop.dispatch(
            EmergencyStopEvent.ResetRequested(EmergencyStopResetInitiator.LOCAL_UI, reportedStationary = true, clock.nowElapsedMs()),
        )
        mode = T032VerificationMode.DISARMED
        raisedPulseStartedAtMs = null
        lastOutcome = "E-stop reset result: ${transition.emergencyStop.outcome}; acceptance mode remains disarmed"
        return publish()
    }

    @Synchronized
    fun onDisconnect(reason: EmergencyStopReason = EmergencyStopReason.TRANSPORT_LOST): T032AcceptanceState {
        if (connected) activateEmergencyStop(reason, clock.nowElapsedMs(), "Transport/session loss stop requested")
        connected = false
        fed9Active = false
        mode = T032VerificationMode.DISARMED
        headCalibrationArmed = false
        raisedPulseStartedAtMs = null
        motionActive = false
        deadMan.cancel()
        return publish()
    }

    @Synchronized
    fun state(): T032AcceptanceState = snapshot(clock.nowElapsedMs())

    private fun activateEmergencyStop(reason: EmergencyStopReason, now: Long, outcome: String) {
        emergencyStop.dispatch(EmergencyStopEvent.Activate(reason, now))
        mode = T032VerificationMode.DISARMED
        headCalibrationArmed = false
        raisedPulseStartedAtMs = null
        motionActive = false
        lastOutcome = outcome
    }

    private fun deliverNeutralStop(now: Long): ProfileADriveExecutionResult? {
        motionActive = false
        deadMan.cancel()
        stopRequestCount++
        if (!::driveExecutor.isInitialized) return null
        return driveExecutor.execute(
            ActionRequest(internalSequence++, ActionSource.SYSTEM, RobotAction.Stop(now)),
            safetyContext(now),
            generation,
        )
    }

    private fun enqueueVerified(
        characteristicUuid: String?,
        payload: List<Int>,
        label: String,
    ): T032VerifiedDisposition {
        if (characteristicUuid == null) {
            lastOutcome = "$label unavailable in discovered profile-A inventory"
            return T032VerifiedDisposition.UNAVAILABLE
        }
        var resultDescription = "$label queued"
        val submission = queue.submit(
            GattQueuedOperationRequest(
                generation = generation,
                kind = GattOperationKind.WRITE,
                serviceUuid = endpoints.serviceUuid,
                characteristicUuid = characteristicUuid,
                payload = payload,
                withResponse = false,
                lane = GattOperationLane.NORMAL,
                timeoutMs = 500L,
            ),
        ) { result: GattOperationResult ->
            resultDescription = if (result is GattOperationSuccess) "$label write succeeded" else "$label write failed: $result"
            lastOutcome = resultDescription
            publish()
        }
        lastOutcome = resultDescription
        return when (submission) {
            is GattOperationEnqueued -> T032VerifiedDisposition.ACCEPTED
            is GattOperationRejected -> T032VerifiedDisposition.TRANSPORT_REJECTED
        }
    }

    private fun safetyContext(now: Long, allowGenerationScopedStart: Boolean = false): SafetyContext {
        val sensors = sensorAdapter.state
        val effectiveSensors = if (
            allowGenerationScopedStart &&
            sensors.generation == generation &&
            sensors.status == CliffContactObservationStatus.VALID
        ) {
            sensors.copy(observedAtElapsedMs = now)
        } else {
            sensors
        }
        return SafetyContext(
        nowElapsedMs = now,
        connected = connected,
        connectionGeneration = generation,
        emergencyStopLatched = emergencyStop.state.latched,
        cliffContactSensors = effectiveSensors,
    )
    }

    private fun snapshot(now: Long): T032AcceptanceState {
        val sensors = sensorAdapter.state
        val known = sensors.status == CliffContactObservationStatus.VALID && sensors.generation == generation
        val fresh = sensors.status == CliffContactObservationStatus.VALID &&
            sensors.generation == generation &&
            sensors.observedAtElapsedMs?.let { now - it in 0..250L } == true
        val safe = if (known) sensors.readings.values.none { it.triggered } else null
        return T032AcceptanceState(
            generation = generation,
            prerequisiteReady = connected,
            fed9Active = fed9Active,
            sensorStateKnown = known,
            streamFresh = fresh,
            lowerContactSafe = safe,
            mode = mode,
            raisedWheelVerified = raisedWheelVerified,
            headCalibrationArmed = headCalibrationArmed,
            emergencyStopLatched = emergencyStop.state.latched,
            deadManArmed = deadMan.state.activeDeadlineElapsedMs != null,
            stopRequestCount = stopRequestCount,
            driveWriteStatus = lastDriveOperationId?.let { operationId ->
                driveExecutor.observedResult(operationId)?.let { observed ->
                    "${observed.operationId.value}:${observed.status}${observed.failure?.let { ":$it" } ?: ""}"
                }
            } ?: "none",
            lastOutcome = lastOutcome,
        )
    }

    private fun publish(now: Long = clock.nowElapsedMs()): T032AcceptanceState = snapshot(now).also(onStateChanged)

    private fun describe(result: ProfileADriveExecutionResult): String = when (result) {
        is ProfileADriveExecutionResult.Queued -> "queued ${result.operationId.value}"
        else -> "rejected $result"
    }

    private fun driveDisposition(result: ProfileADriveExecutionResult): T032VerifiedDisposition = when (result) {
        is ProfileADriveExecutionResult.Queued -> T032VerifiedDisposition.ACCEPTED
        is ProfileADriveExecutionResult.RejectedByQueue -> T032VerifiedDisposition.TRANSPORT_REJECTED
        is ProfileADriveExecutionResult.RejectedBySafety,
        is ProfileADriveExecutionResult.RejectedByDeadMan,
        is ProfileADriveExecutionResult.RejectedByEncoder,
        -> T032VerifiedDisposition.SAFETY_REJECTED
    }

    private companion object {
        fun endpointsFrom(inventory: List<GattServiceInventory>): ProfileACommandEndpoints {
            val service = inventory.firstOrNull { candidate ->
                candidate.characteristics.any { isShortUuid(it.uuid, "fed0") }
            } ?: error("Discovered inventory does not contain profile-A FED0")
            val serviceUuid = service.uuid.ifBlank { PROFILE_A_SERVICE_UUID }
            return ProfileACommandEndpoints(
                serviceUuid = serviceUuid,
                fed0Uuid = service.characteristics.firstOrNull { isShortUuid(it.uuid, "fed0") }?.uuid ?: FED0_UUID,
                fed1Uuid = service.characteristics.firstOrNull { isShortUuid(it.uuid, "fed1") }?.uuid,
                fed2Uuid = service.characteristics.firstOrNull { isShortUuid(it.uuid, "fed2") }?.uuid,
            )
        }

        fun isShortUuid(uuid: String, shortId: String): Boolean {
            val normalized = uuid.trim().lowercase()
            return normalized == shortId.lowercase() || normalized == "0000${shortId.lowercase()}-0000-1000-8000-00805f9b34fb"
        }
    }
}
