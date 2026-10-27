package io.redi.robot.body.looi.firstlife

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.redi.robot.MainActivity
import io.redi.robot.body.looi.ble.scan.BleScanConfig
import io.redi.robot.body.looi.ble.scan.BleScanEvent
import io.redi.robot.body.looi.ble.scan.BleScanFailure
import io.redi.robot.body.looi.ble.scan.BleScanListener
import io.redi.robot.body.looi.ble.scan.BleScanResult
import io.redi.robot.body.looi.connection.ConnectionPhase
import io.redi.robot.body.looi.connection.ConnectionTarget
import io.redi.robot.body.looi.protocol.handshake.HandshakeCandidateId
import io.redi.robot.body.looi.protocol.profile.LooiProtocolProfile
import io.redi.robot.body.looi.protocol.telemetry.LooiTelemetryParser
import io.redi.robot.body.looi.protocol.telemetry.LowerContactSafetyAdapter
import io.redi.robot.body.looi.protocol.telemetry.RawLooiTelemetryEnvelope
import io.redi.robot.core.ActionRequest
import io.redi.robot.core.ActionSource
import io.redi.robot.core.RobotAction
import io.redi.robot.capability.api.*
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.firstlife.runtime.AuthorizedDispatchResult
import io.redi.robot.trust.runtime.AuthorizationDecisionStatus
import io.redi.robot.platform.service.ConnectedDeviceService
import io.redi.robot.platform.service.LooiFirstLifeConnectionStage
import io.redi.robot.platform.transport.ble.scan.AndroidBleScanner
import io.redi.robot.feature.manualcontrol.ManualDriveCommand
import io.redi.robot.feature.manualcontrol.ManualDriveCommandSink
import io.redi.robot.feature.manualcontrol.ManualDriveController
import io.redi.robot.feature.manualcontrol.ManualDriveDirection
import io.redi.robot.feature.manualcontrol.ManualStopReason
import io.redi.robot.safety.DefaultSafetyController
import io.redi.robot.safety.SafetyContext
import io.redi.robot.safety.SafetyDecision
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit Samsung + real-LOOI acceptance. Enable only with -e fl006Hardware true. */
@RunWith(AndroidJUnit4::class)
class LooiFirstLifeProviderWiringInstrumentedTest {
    @Test
    fun explicitFL008BodyPresentationUsesUserSelectionAndClearsOnDisconnect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("fl008Hardware") == "true")
        val context = instrumentation.targetContext
        val candidate = scanLooi(context)
        val bound = bind(context)
        try {
            val available = connectAndNegotiate(bound.binder, candidate)
            val runtime = checkNotNull(bound.binder.firstLifeRuntimeSnapshot())
            assertTrue(runtime.bodyReady)
            assertFalse(runtime.activeBodySelected)
            assertEquals(LooiFirstLifeDefinition.capabilityReferences.map { it.wireValue }, available.negotiatedCapabilities)
            assertFalse(available.trusted)
            assertFalse(available.authorized)
            assertFalse(available.capabilityExecuted)

            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(1_500)
            captureFL008Screenshot(instrumentation, context, "fl008-available.png")
            Log.i(FL008_TAG, "available-window=open exact-capabilities=6 authority=false no-motion=true")
            await("FL008 explicit user active-body selection", 60_000) {
                bound.binder.firstLifeSnapshot().activeBodySelected
            }
            val selected = checkNotNull(bound.binder.firstLifeRuntimeSnapshot())
            assertTrue(selected.activeBodySelected)
            val selectionDeadline = checkNotNull(selected.activeBodySelectionExpiresAt)
            assertFalse(selected.authorized)
            assertFalse(selected.actionReady)
            SystemClock.sleep(750)
            captureFL008Screenshot(instrumentation, context, "fl008-selected.png")
            Log.i(FL008_TAG, "selected-window=open expiring=true authority=false no-motion=true")
            SystemClock.sleep(5_000)

            await("FL008 explicit user deactivation", 60_000) {
                !bound.binder.firstLifeSnapshot().activeBodySelected
            }
            assertTrue(
                "Selection expired instead of being explicitly deactivated",
                SystemClock.elapsedRealtime() < selectionDeadline.value,
            )
            Log.i(FL008_TAG, "deactivated=verified identity-unchanged=true")

            val reselected = checkNotNull(bound.binder.selectFirstLifeActiveBody())
            assertEquals(LooiActiveBodySelectionStatus.SELECTED, reselected.status)
            bound.binder.disconnectFirstLife()
            await("FL008 disconnect clears selection") {
                val snapshot = bound.binder.firstLifeSnapshot()
                !snapshot.activeBodySelected && snapshot.negotiatedCapabilities.isEmpty()
            }
            SystemClock.sleep(750)
            captureFL008Screenshot(instrumentation, context, "fl008-disconnected.png")
            Log.i(FL008_TAG, "disconnected-window=open selection=cleared capabilities=cleared")
            SystemClock.sleep(1_000)
        } finally {
            runCatching { bound.binder.deactivateFirstLifeActiveBody() }
            runCatching { bound.binder.disconnectFirstLife() }
            context.unbindService(bound.connection)
        }
    }

    @Test
    fun explicitFed9SensorTransitionKeepsTransportConnectedWithoutMotion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("fl006SensorIsolation") == "true")
        val context = instrumentation.targetContext
        val candidate = scanLooi(context)
        val bound = bind(context)
        try {
            bound.binder.connectFirstLife(ConnectionTarget(candidate.id, candidate.displayName))
            await("transport ready") {
                bound.binder.firstLifeSnapshot().stage == LooiFirstLifeConnectionStage.TRANSPORT_READY
            }
            assertNotNull(bound.binder.startFed9CaptureWithVerifiedHandshakeA())
            await("active FED9 subscription", 20_000) {
                val state = bound.binder.t032State()
                state.prerequisiteReady && state.fed9Active
            }

            val generation = bound.binder.currentState().generation
            val session = checkNotNull(bound.binder.firstLifeSnapshot().connectionSessionReference)
            Log.i(TAG, "sensor-isolation-window=open generation=$generation session=$session no-motion=true")
            await("controlled T031 lower-contact hazard", 60_000) {
                bound.binder.fed9CaptureState().records.any { record ->
                    record.generation == generation && record.payload.size == 5 &&
                        record.payload.firstOrNull() == 0x01 && record.payload.drop(1).any { it == 0 }
                }
            }
            val hazard = checkNotNull(
                bound.binder.fed9CaptureState().records.lastOrNull { record ->
                    record.generation == generation && record.payload.size == 5 &&
                        record.payload.firstOrNull() == 0x01 && record.payload.drop(1).any { it == 0 }
                },
            )
            Log.i(TAG, "sensor-isolation-hazard raw=${hazard.payload.joinToString("-")} no-motion=true hold=10s")
            assertTransportRemainsReady(bound.binder, generation, session, "hazard", 10_000)

            val safeReturnSequenceBarrier = bound.binder.fed9CaptureState().records
                .maxOfOrNull { it.sequence } ?: hazard.sequence
            Log.i(TAG, "sensor-isolation-safe-return-window=open no-motion=true")
            await("controlled T031 return to safe", 60_000) {
                bound.binder.fed9CaptureState().records.any { record ->
                    record.generation == generation && record.sequence > safeReturnSequenceBarrier &&
                        record.payload == listOf(0x01, 0x01, 0x01, 0x01, 0x01)
                }
            }
            val safe = checkNotNull(
                bound.binder.fed9CaptureState().records.lastOrNull { record ->
                    record.generation == generation && record.sequence > safeReturnSequenceBarrier &&
                        record.payload == listOf(0x01, 0x01, 0x01, 0x01, 0x01)
                },
            )
            assertTrue(bound.binder.t032State().sensorStateKnown)
            assertTrue(bound.binder.t032State().streamFresh)
            Log.i(TAG, "sensor-isolation-safe raw=${safe.payload.joinToString("-")} no-motion=true hold=10s")
            assertTransportRemainsReady(bound.binder, generation, session, "safe", 10_000)

            bound.binder.disconnectFirstLife()
            await("explicit sensor-isolation disconnect") {
                bound.binder.currentState().phase == ConnectionPhase.DISCONNECTED
            }
            Log.i(TAG, "sensor-isolation-disconnect=explicit result=verified no-motion=true")
        } finally {
            runCatching { bound.binder.disconnectFirstLife() }
            context.unbindService(bound.connection)
        }
    }

    @Test
    fun explicitT032ProductionPathRegressionUsesExistingSafetyStops() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("fl006Hardware") == "true")
        val context = instrumentation.targetContext
        val candidate = scanLooi(context)
        val bound = bind(context)
        try {
            bound.binder.connectFirstLife(ConnectionTarget(candidate.id, candidate.displayName))
            await("transport ready") {
                bound.binder.firstLifeSnapshot().stage == LooiFirstLifeConnectionStage.TRANSPORT_READY
            }
            assertNotNull(bound.binder.startFed9CaptureWithVerifiedHandshakeA())
            await("active FED9 subscription", 20_000) {
                val state = bound.binder.t032State()
                state.prerequisiteReady && state.fed9Active
            }

            var sequence = 1L
            fun now() = SystemClock.elapsedRealtime()
            Log.i(TAG, "t031-transition-window=open no-motion=true")
            await("controlled T031 lower-contact transition", 60_000) {
                bound.binder.fed9CaptureState().records.any { record ->
                    record.payload.size == 5 && record.payload.firstOrNull() == 0x01 &&
                        record.payload.drop(1).any { it == 0 }
                }
            }
            val hazardRecord = checkNotNull(
                bound.binder.fed9CaptureState().records.lastOrNull { record ->
                    record.payload.size == 5 && record.payload.firstOrNull() == 0x01 &&
                        record.payload.drop(1).any { it == 0 }
                },
            )
            Log.i(TAG, "t031-transition raw=${hazardRecord.payload.joinToString(separator = "-")} no-motion=true")
            await("controlled T031 return to safe", 60_000) {
                bound.binder.fed9CaptureState().records.any { record ->
                    record.sequence > hazardRecord.sequence && record.payload == listOf(0x01, 0x01, 0x01, 0x01, 0x01)
                }
            }
            val safeRecord = checkNotNull(
                bound.binder.fed9CaptureState().records.lastOrNull { record ->
                    record.sequence > hazardRecord.sequence && record.payload == listOf(0x01, 0x01, 0x01, 0x01, 0x01)
                },
            )
            val preflightAt = now()
            val generation = bound.binder.t032State().generation
            val rawLowerContact = safeRecord.payload
            assertEquals(generation, hazardRecord.generation)
            assertEquals(generation, safeRecord.generation)
            assertEquals(generation, bound.binder.currentState().generation)
            assertNotNull(bound.binder.firstLifeSnapshot().connectionSessionReference)
            assertTrue(bound.binder.t032State().sensorStateKnown)
            assertTrue(bound.binder.t032State().streamFresh)
            assertTrue(
                "Latest FED9 evidence is not a T031 lower-contact frame: $rawLowerContact",
                rawLowerContact.size == 5 && rawLowerContact.firstOrNull() == 0x01,
            )
            val semanticState = LowerContactSafetyAdapter(generation).accept(
                LooiTelemetryParser.parse(
                    RawLooiTelemetryEnvelope(
                        generation = generation,
                        timestampMs = preflightAt,
                        profile = LooiProtocolProfile.PROFILE_A,
                        characteristicUuid = "FED9",
                        payload = rawLowerContact,
                    ),
                ),
            )
            assertTrue("T031 lower-contact mapping is not valid", semanticState.readings.isNotEmpty())
            assertFalse(
                "T031 lower-contact mapping reports unsafe support: $rawLowerContact",
                semanticState.readings.values.any { it.triggered },
            )
            val preflightAction = RobotAction.Drive(
                ManualDriveDirection.LEFT.linear * 2f,
                ManualDriveDirection.LEFT.angular * 2f,
                preflightAt,
            )
            val preflightDecision = DefaultSafetyController().evaluate(
                ActionRequest(sequence++, ActionSource.MANUAL, preflightAction),
                SafetyContext(
                    nowElapsedMs = preflightAt,
                    connected = true,
                    connectionGeneration = generation,
                    emergencyStopLatched = false,
                    cliffContactSensors = semanticState,
                ),
            )
            assertTrue("Deterministic safety preflight rejected: $preflightDecision", preflightDecision is SafetyDecision.Allowed)
            Log.i(
                TAG,
                "t032-preflight raw=${rawLowerContact.joinToString(separator = "-")} " +
                    "semantic=safe fresh=true policy=allowed",
            )
            Log.i(TAG, "t032-pre-motion-safe-hold=open duration=10s no-motion=true")
            SystemClock.sleep(10_000)
            assertEquals(ConnectionPhase.TRANSPORT_READY, bound.binder.currentState().phase)
            assertEquals(generation, bound.binder.currentState().generation)
            assertTrue(bound.binder.t032State().sensorStateKnown)
            Log.i(TAG, "t032-pre-motion-safe-hold=verified transport=stable")
            fun armFloor() {
                val armed = bound.binder.armT032Floor()
                assertTrue("T032 floor mode did not arm: ${armed.lastOutcome}", armed.driveEnabled)
            }
            fun startMinimalTurn() {
                val previousWriteStatus = bound.binder.t032State().driveWriteStatus
                val started = bound.binder.dispatchT032Drive(
                    ManualDriveCommand.Refresh(sequence++, ManualDriveDirection.LEFT, now()),
                )
                assertTrue("Minimal turn was not accepted: ${started.lastOutcome}", started.lastOutcome.contains("queued"))
                assertNotEquals("Drive status did not advance to the current operation", previousWriteStatus, started.driveWriteStatus)
                assertTrue("Current drive operation was not observable", started.driveWriteStatus != "none")
            }
            fun awaitStopDelivery(label: String) {
                SystemClock.sleep(750)
                val connection = bound.binder.currentState()
                assertEquals("$label changed the connection phase", ConnectionPhase.TRANSPORT_READY, connection.phase)
                assertEquals("$label changed the connection generation", generation, connection.generation)
            }
            fun holdMinimalTurnForObservation() {
                startMinimalTurn()
                repeat(5) {
                    SystemClock.sleep(40)
                    startMinimalTurn()
                }
                Log.i(TAG, "t032-minimal-turn=refreshed duration=200ms magnitude=unchanged")
            }

            armFloor()
            holdMinimalTurnForObservation()
            val releaseStopCount = bound.binder.t032State().stopRequestCount
            bound.binder.dispatchT032Drive(
                ManualDriveCommand.Stop(sequence++, ManualStopReason.RELEASE, now()),
            )
            await("manual release stop") { bound.binder.t032State().stopRequestCount > releaseStopCount }
            assertFalse(bound.binder.t032State().emergencyStopLatched)
            awaitStopDelivery("manual release stop")
            Log.i(TAG, "t032-release-stop=verified transport=stable")

            startMinimalTurn()
            val deadManStopCount = bound.binder.t032State().stopRequestCount
            await("dead-man e-stop", 2_000) {
                val state = bound.binder.t032State()
                state.emergencyStopLatched && state.stopRequestCount >= deadManStopCount + 2
            }
            awaitStopDelivery("dead-man e-stop")
            Log.i(TAG, "t032-dead-man=verified e-stop=latched")

            assertFalse(bound.binder.resetT032EmergencyStopReportedStationary().emergencyStopLatched)
            armFloor()
            startMinimalTurn()
            val eStopCount = bound.binder.t032State().stopRequestCount
            val eStopped = bound.binder.activateT032EmergencyStop()
            assertTrue(eStopped.emergencyStopLatched)
            assertTrue(eStopped.stopRequestCount >= eStopCount + 2)
            awaitStopDelivery("local e-stop")
            Log.i(TAG, "t032-local-e-stop=verified")

            assertFalse(bound.binder.resetT032EmergencyStopReportedStationary().emergencyStopLatched)
            armFloor()
            startMinimalTurn()
            bound.binder.disconnectFirstLife()
            await("disconnect safe state") {
                bound.binder.currentState().phase == ConnectionPhase.DISCONNECTED &&
                    !bound.binder.firstLifeSnapshot().protocolReady &&
                    bound.binder.firstLifeSnapshot().negotiatedCapabilities.isEmpty()
            }
            Log.i(TAG, "t032-disconnect-safe=verified readiness=cleared")
        } finally {
            runCatching { bound.binder.activateT032EmergencyStop() }
            runCatching { bound.binder.disconnectFirstLife() }
            context.unbindService(bound.connection)
        }
    }

    @Test
    fun explicitFL007AuthorizedSemanticDispatchUsesOnlyTheVerifiedT032Path() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("fl007Hardware") == "true")
        val context = instrumentation.targetContext
        val candidate = scanLooi(context)
        val bound = bind(context)
        try {
            bound.binder.connectFirstLife(ConnectionTarget(candidate.id, candidate.displayName))
            await("FL007 transport ready") {
                bound.binder.firstLifeSnapshot().stage == LooiFirstLifeConnectionStage.TRANSPORT_READY
            }
            assertNotNull(bound.binder.startFed9CaptureWithVerifiedHandshakeA())
            await("FL007 protocol negotiation and T032") {
                bound.binder.firstLifeSnapshot().stage == LooiFirstLifeConnectionStage.PROTOCOL_READY &&
                    bound.binder.firstLifeNegotiationReport() != null &&
                    bound.binder.t032State().fed9Active
            }
            val report = checkNotNull(bound.binder.firstLifeNegotiationReport())
            val authorization = FirstLifePhysicalAuthorization()
            var sequence = 1L
            fun now() = semanticTime(SystemClock.elapsedRealtime())
            fun authorized(capability: String, input: CapabilityData? = null): AuthorizedDispatchResult.Invoked {
                val value = authorization.create(semanticReference(capability), input, report, sequence++, now())
                assertEquals(AuthorizationDecisionStatus.AUTHORIZED, value.decision.status)
                return bound.binder.dispatchFirstLifeCapability(value.request, value.decision)
                    as AuthorizedDispatchResult.Invoked
            }
            fun stop(): AuthorizedDispatchResult.Invoked {
                val request = semanticRequest(
                    "mobility.stop@1.0", null, report.providerInstanceId,
                    report.providerGeneration.value, sequence++, now(),
                )
                return bound.binder.dispatchFirstLifeCapability(request, null) as AuthorizedDispatchResult.Invoked
            }

            Log.i(FL007_TAG, "lower-contact-transition-window=open no-motion=true")
            val barrier = bound.binder.fed9CaptureState().records.maxOfOrNull { it.sequence } ?: 0
            await("FL007 controlled lower-contact hazard", 60_000) {
                bound.binder.fed9CaptureState().records.any {
                    it.sequence > barrier && it.payload.size == 5 && it.payload.firstOrNull() == 1 &&
                        it.payload.drop(1).any { value -> value == 0 }
                }
            }
            val hazardSequence = checkNotNull(bound.binder.fed9CaptureState().records.lastOrNull {
                it.sequence > barrier && it.payload.size == 5 && it.payload.firstOrNull() == 1 &&
                    it.payload.drop(1).any { value -> value == 0 }
            }).sequence
            await("FL007 controlled safe return", 60_000) {
                bound.binder.fed9CaptureState().records.any {
                    it.sequence > hazardSequence && it.payload == listOf(1, 1, 1, 1, 1)
                }
            }
            assertEquals(true, bound.binder.t032State().lowerContactSafe)
            assertTrue(bound.binder.t032State().streamFresh)
            Log.i(FL007_TAG, "lower-contact=known-fresh-safe generation=current")

            val contact = authorized("safety.lower_contact.status@1.0").result
            val connection = authorized("provider.connection.status@1.0").result
            assertEquals(CapabilityResultState.COMPLETED, contact.state)
            assertEquals(CapabilityResultState.COMPLETED, connection.state)
            assertEquals(true, semanticFlag(checkNotNull(contact.output), "safe"))
            assertEquals(true, semanticFlag(checkNotNull(connection.output), "connected"))
            assertEquals(CapabilityResultState.ACCEPTED, stop().result.state)
            Log.i(FL007_TAG, "semantic-status=completed prioritized-stop=accepted authority-does-not-imply-safety")

            assertTrue(bound.binder.armT032RaisedWheels().driveEnabled)
            assertTrue(bound.binder.armT032HeadCalibration().headEnabled)
            assertEquals(
                CapabilityResultState.ACCEPTED,
                authorized(
                    "body.head.position.control@1.0",
                    semanticData("hugo.contract.body_head_position_input@1.0", "position", semanticText("center")),
                ).result.state,
            )
            assertEquals(
                CapabilityResultState.ACCEPTED,
                authorized(
                    "body.light.control@1.0",
                    semanticData("hugo.contract.body_light_input@1.0", "enabled", CapabilityArgumentValue.Flag(true)),
                ).result.state,
            )
            SystemClock.sleep(750)
            Log.i(FL007_TAG, "head-center=accepted light-minimum-on=accepted existing-safety-path=true")

            bound.binder.confirmT032RaisedWheelChecksPassed()
            assertTrue(bound.binder.armT032Floor().driveEnabled)
            val moveInput = semanticData(
                "hugo.contract.mobility_move_input@1.0", "direction", semanticText("left"),
            )
            repeat(3) { index ->
                assertEquals(CapabilityResultState.ACCEPTED, authorized("mobility.move@1.0", moveInput).result.state)
                if (index < 2) SystemClock.sleep(40)
            }
            SystemClock.sleep(20)
            assertEquals(CapabilityResultState.ACCEPTED, stop().result.state)
            SystemClock.sleep(750)
            assertFalse(bound.binder.t032State().emergencyStopLatched)
            Log.i(FL007_TAG, "minimal-left-pulse=accepted duration<=100ms release-stop=verified")

            assertEquals(CapabilityResultState.ACCEPTED, authorized("mobility.move@1.0", moveInput).result.state)
            assertTrue("FL007 move accepted without arming dead-man", bound.binder.t032State().deadManArmed)
            await("FL007 dead-man", 2_000) { bound.binder.t032State().emergencyStopLatched }
            Log.i(FL007_TAG, "dead-man=verified")
            assertFalse(bound.binder.resetT032EmergencyStopReportedStationary().emergencyStopLatched)
            assertTrue(bound.binder.armT032Floor().driveEnabled)
            assertEquals(CapabilityResultState.ACCEPTED, authorized("mobility.move@1.0", moveInput).result.state)
            assertTrue(bound.binder.activateT032EmergencyStop().emergencyStopLatched)
            Log.i(FL007_TAG, "local-e-stop=verified")

            assertFalse(bound.binder.resetT032EmergencyStopReportedStationary().emergencyStopLatched)
            assertTrue(bound.binder.armT032Floor().driveEnabled)
            assertEquals(CapabilityResultState.ACCEPTED, authorized("mobility.move@1.0", moveInput).result.state)
            val motionHazardBarrier = bound.binder.fed9CaptureState().records.maxOfOrNull { it.sequence } ?: 0
            Log.i(FL007_TAG, "motion-contact-gate-window=open")
            await("FL007 in-motion lower-contact stop", 60_000) {
                bound.binder.fed9CaptureState().records.any {
                    it.sequence > motionHazardBarrier && it.payload.size == 5 && it.payload.firstOrNull() == 1 &&
                        it.payload.drop(1).any { value -> value == 0 }
                } && bound.binder.t032State().emergencyStopLatched
            }
            Log.i(FL007_TAG, "lower-contact-motion-stop=latched no-disconnect=true")

            bound.binder.disconnectFirstLife()
            await("FL007 disconnect safe") {
                bound.binder.currentState().phase == ConnectionPhase.DISCONNECTED &&
                    !bound.binder.firstLifeSnapshot().protocolReady
            }
            Log.i(FL007_TAG, "disconnect-safe=verified readiness=cleared")
        } finally {
            runCatching { bound.binder.activateT032EmergencyStop() }
            runCatching { bound.binder.disconnectFirstLife() }
            context.unbindService(bound.connection)
        }
    }

    @Test
    fun explicitFL007ExistingManualPathStopsOnBackground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("fl007ManualHardware") == "true")
        val context = instrumentation.targetContext
        val candidate = scanLooi(context)
        val bound = bind(context)
        try {
            bound.binder.connectFirstLife(ConnectionTarget(candidate.id, candidate.displayName))
            await("FL007 manual transport ready") {
                bound.binder.firstLifeSnapshot().stage == LooiFirstLifeConnectionStage.TRANSPORT_READY
            }
            assertNotNull(bound.binder.startFed9CaptureWithVerifiedHandshakeA())
            await("FL007 manual T032 ready") {
                bound.binder.firstLifeSnapshot().stage == LooiFirstLifeConnectionStage.PROTOCOL_READY &&
                    bound.binder.t032State().fed9Active
            }

            Log.i(FL007_TAG, "manual-background-lower-contact-window=open no-motion=true")
            val barrier = bound.binder.fed9CaptureState().records.maxOfOrNull { it.sequence } ?: 0
            await("FL007 manual controlled lower-contact hazard", 60_000) {
                bound.binder.fed9CaptureState().records.any {
                    it.sequence > barrier && it.payload.size == 5 && it.payload.firstOrNull() == 1 &&
                        it.payload.drop(1).any { value -> value == 0 }
                }
            }
            val hazardSequence = checkNotNull(bound.binder.fed9CaptureState().records.lastOrNull {
                it.sequence > barrier && it.payload.size == 5 && it.payload.firstOrNull() == 1 &&
                    it.payload.drop(1).any { value -> value == 0 }
            }).sequence
            await("FL007 manual controlled safe return", 60_000) {
                bound.binder.fed9CaptureState().records.any {
                    it.sequence > hazardSequence && it.payload == listOf(1, 1, 1, 1, 1)
                }
            }
            assertEquals(true, bound.binder.t032State().lowerContactSafe)
            assertTrue(bound.binder.t032State().streamFresh)

            bound.binder.confirmT032RaisedWheelChecksPassed()
            assertTrue(bound.binder.armT032Floor().driveEnabled)
            val controller = ManualDriveController(
                ManualDriveCommandSink { command -> bound.binder.dispatchT032Drive(command) },
            )
            val token = controller.press(ManualDriveDirection.LEFT, SystemClock.elapsedRealtime())
            assertNotNull(token)
            SystemClock.sleep(40)
            controller.cancelActive(ManualStopReason.BACKGROUND, SystemClock.elapsedRealtime())
            SystemClock.sleep(750)
            val stopped = bound.binder.t032State()
            assertFalse(stopped.deadManArmed)
            assertFalse(stopped.emergencyStopLatched)
            Log.i(
                FL007_TAG,
                "existing-manual-path=minimal-left-pulse background-stop=verified dead-man=cleared",
            )

            bound.binder.disconnectFirstLife()
            await("FL007 manual disconnect safe") {
                bound.binder.currentState().phase == ConnectionPhase.DISCONNECTED
            }
        } finally {
            runCatching { bound.binder.activateT032EmergencyStop() }
            runCatching { bound.binder.disconnectFirstLife() }
            context.unbindService(bound.connection)
        }
    }

    @Test
    fun explicitGenericCompositionPreservesIdentityAndCreatesFreshSessionWithoutMovement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("fl006Hardware") == "true")
        val context = instrumentation.targetContext
        val candidate = scanLooi(context)
        val bound = bind(context)
        try {
            val first = connectAndNegotiate(bound.binder, candidate)
            val firstIdentity = checkNotNull(first.safeIdentityEvidence)
            val firstSession = checkNotNull(first.connectionSessionReference)
            Log.i(TAG, "provider=$firstIdentity generation=${first.providerGeneration} session=$firstSession " +
                "manifest=${first.manifestResolved} adapter=${first.adapterKnownCompatible} " +
                "capabilities=${first.negotiatedCapabilities.joinToString()}")

            bound.binder.disconnectFirstLife()
            await("first disconnect") { bound.binder.currentState().phase == ConnectionPhase.DISCONNECTED }
            val second = connectAndNegotiate(bound.binder, candidate)
            Log.i(TAG, "provider=${second.safeIdentityEvidence} generation=${second.providerGeneration} " +
                "session=${second.connectionSessionReference} reconnect=verified")

            assertEquals(firstIdentity, second.safeIdentityEvidence)
            assertNotEquals(first.providerGeneration, second.providerGeneration)
            assertNotEquals(firstSession, second.connectionSessionReference)
            assertFalse(second.trusted)
            assertFalse(second.authorized)
            assertFalse(second.activeBodySelected)
            assertFalse(second.capabilityExecuted)
        } finally {
            runCatching { bound.binder.disconnectFirstLife() }
            context.unbindService(bound.connection)
        }
    }

    @Test
    fun bluetoothOffFailsClosedAndClearsReadiness() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("fl006Hardware") == "true")
        val context = instrumentation.targetContext
        val candidate = scanLooi(context)
        val bound = bind(context)
        try {
            connectAndNegotiate(bound.binder, candidate)
            instrumentation.uiAutomation.executeShellCommand("svc bluetooth disable").close()
            await("Bluetooth-off cleanup", 15_000) {
                bound.binder.firstLifeSnapshot().stage in setOf(
                    LooiFirstLifeConnectionStage.DISCONNECTED,
                    LooiFirstLifeConnectionStage.FAILED,
                )
            }
            val snapshot = bound.binder.firstLifeSnapshot()
            assertFalse(snapshot.protocolReady)
            assertTrue(snapshot.negotiatedCapabilities.isEmpty())
            Log.i(TAG, "bluetooth-off=${snapshot.stage} capabilities=cleared authority=false")
        } finally {
            runCatching { bound.binder.disconnectFirstLife() }
            instrumentation.uiAutomation.executeShellCommand("svc bluetooth enable").close()
            context.unbindService(bound.connection)
        }
    }

    private fun connectAndNegotiate(
        binder: ConnectedDeviceService.ConnectionBinder,
        candidate: BleScanResult,
    ): io.redi.robot.platform.service.LooiFirstLifeServiceSnapshot {
        val initial = binder.connectFirstLife(ConnectionTarget(candidate.id, candidate.displayName))
        assertTrue(initial.manifestResolved)
        assertTrue(initial.adapterKnownCompatible)
        await("transport ready") { binder.firstLifeSnapshot().stage == LooiFirstLifeConnectionStage.TRANSPORT_READY }
        assertNotNull(binder.startHandshake(HandshakeCandidateId.A))
        await("protocol ready and negotiated") {
            binder.firstLifeSnapshot().stage == LooiFirstLifeConnectionStage.PROTOCOL_READY &&
                binder.firstLifeSnapshot().negotiatedCapabilities.isNotEmpty()
        }
        val snapshot = binder.firstLifeSnapshot()
        assertEquals(
            LooiFirstLifeDefinition.capabilityReferences.map { it.wireValue },
            snapshot.negotiatedCapabilities,
        )
        assertTrue(snapshot.firstLifeProviderObserved)
        assertTrue(snapshot.firstLifeBodyAvailable)
        assertFalse(snapshot.trusted)
        assertFalse(snapshot.authorized)
        assertFalse(snapshot.activeBodySelected)
        assertFalse(snapshot.capabilityExecuted)
        return snapshot
    }

    private fun scanLooi(context: Context): BleScanResult {
        val latch = CountDownLatch(1)
        var found: BleScanResult? = null
        var failure: BleScanFailure? = null
        val session = AndroidBleScanner(context).startScan(BleScanConfig(timeoutMillis = 15_000), BleScanListener { event ->
            val candidate = (event as? BleScanEvent.DeviceFound)?.result
            if (candidate != null) {
                val category = when {
                    candidate.displayName?.contains("LOOI", ignoreCase = true) == true -> "looi-pattern"
                    candidate.displayName?.startsWith("MELK-", ignoreCase = true) == true -> "melk-pattern"
                    candidate.displayName.isNullOrBlank() -> "unnamed"
                    else -> "other"
                }
                Log.i(TAG, "scan-candidate-category=$category")
            }
            if (candidate?.displayName?.contains("LOOI", ignoreCase = true) == true && found == null) {
                found = candidate
                latch.countDown()
            }
            if (event is BleScanEvent.Failure && failure == null) {
                failure = event.cause
                latch.countDown()
            }
        })
        try {
            assertTrue("No named LOOI candidate found", latch.await(16, TimeUnit.SECONDS))
            assertTrue("BLE scan failed: $failure", failure == null)
            return checkNotNull(found)
        } finally {
            session.stop()
        }
    }

    private fun bind(context: Context): BoundService {
        val latch = CountDownLatch(1)
        lateinit var binder: ConnectedDeviceService.ConnectionBinder
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service as ConnectedDeviceService.ConnectionBinder
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        assertTrue(context.bindService(Intent(context, ConnectedDeviceService::class.java), connection, Context.BIND_AUTO_CREATE))
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return BoundService(binder, connection)
    }

    private fun await(label: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !condition()) Thread.sleep(100)
        assertTrue("Timed out waiting for $label", condition())
    }

    private fun captureFL008Screenshot(
        instrumentation: android.app.Instrumentation,
        context: Context,
        fileName: String,
    ) {
        val directory = checkNotNull(context.getExternalFilesDir(null))
        val target = File(directory, fileName)
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        try {
            FileOutputStream(target).use { output ->
                assertTrue("Could not encode $fileName", screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            screenshot.recycle()
        }
        Log.i(FL008_TAG, "screenshot=$fileName redacted=true")
    }

    private fun assertTransportRemainsReady(
        binder: ConnectedDeviceService.ConnectionBinder,
        generation: Long,
        session: String,
        stage: String,
        durationMs: Long,
    ) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val connection = binder.currentState()
            val snapshot = binder.firstLifeSnapshot()
            assertEquals(ConnectionPhase.TRANSPORT_READY, connection.phase)
            assertEquals(generation, connection.generation)
            assertEquals(session, snapshot.connectionSessionReference)
            assertTrue(
                snapshot.stage == LooiFirstLifeConnectionStage.TRANSPORT_READY ||
                    snapshot.stage == LooiFirstLifeConnectionStage.PROTOCOL_READY,
            )
            Thread.sleep(250)
        }
        Log.i(TAG, "sensor-isolation-$stage transport=stable generation=$generation session=$session")
    }

    private fun semanticRequest(
        capability: String,
        input: CapabilityData?,
        provider: ProviderInstanceId,
        generation: Long,
        sequence: Long,
        now: MonotonicTimestampMs,
    ): CapabilityRequest = semanticEnvelope(CapabilityRequest.create(
        CapabilityRequestId.fromUuid(java.util.UUID.randomUUID()), semanticReference(capability),
        CapabilityProviderTarget.Exact(provider), CapabilityActorKind.HUGO_RUNTIME,
        semanticEnvelope(CapabilityExecutionContext.create(generation, sequence)),
        now, semanticTime(now.value + 5_000), null, CapabilityAuditId.fromUuid(java.util.UUID.randomUUID()), input,
    ))

    private fun semanticData(
        contract: String,
        key: String,
        value: CapabilityArgumentValue,
    ): CapabilityData = semanticEnvelope(CapabilityData.create(
        semanticDescriptor(CapabilityContractReference.parse(contract)),
        mapOf(semanticEnvelope(CapabilityArgumentName.parse(key)) to value),
    ))

    private fun semanticText(value: String) = semanticEnvelope(CapabilityArgumentValue.Text.create(value))
    private fun semanticFlag(data: CapabilityData, key: String) =
        (data.values[semanticEnvelope(CapabilityArgumentName.parse(key))] as CapabilityArgumentValue.Flag).value
    private fun semanticReference(value: String) = semanticIdentity(CapabilityReference.parse(value))
    private fun semanticTime(value: Long) = semanticEnvelope(MonotonicTimestampMs.create(value))
    private fun <T> semanticEnvelope(result: CapabilityEnvelopeResult<T>) =
        (result as CapabilityEnvelopeResult.Success).value
    private fun <T> semanticDescriptor(result: CapabilityDescriptorResult<T>) =
        (result as CapabilityDescriptorResult.Success).value
    private fun <T> semanticIdentity(result: CapabilityIdentityResult<T>) =
        (result as CapabilityIdentityResult.Success).value

    private data class BoundService(
        val binder: ConnectedDeviceService.ConnectionBinder,
        val connection: ServiceConnection,
    )

    private companion object {
        const val TAG = "FL006_EVIDENCE"
        const val FL007_TAG = "FL007_EVIDENCE"
        const val FL008_TAG = "FL008_EVIDENCE"
    }
}
