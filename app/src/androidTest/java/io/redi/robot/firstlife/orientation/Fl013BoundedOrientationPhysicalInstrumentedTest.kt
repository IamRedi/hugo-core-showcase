package io.redi.robot.firstlife.orientation

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import io.redi.robot.body.looi.firstlife.LooiFirstLifeDefinition
import io.redi.robot.feature.manualcontrol.T032VerificationMode
import io.redi.robot.platform.service.ConnectedDeviceService
import io.redi.robot.platform.service.LooiFirstLifeConnectionStage
import io.redi.robot.platform.transport.ble.scan.AndroidBleScanner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit operator-staged Samsung + real-LOOI acceptance. No stage auto-generates movement. */
@RunWith(AndroidJUnit4::class)
class Fl013BoundedOrientationPhysicalInstrumentedTest {
    @Test
    fun explicitFL013ProductionUiUsesBoundedControllerAndExistingSafetyPath() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("fl013Hardware") == "true")
        val remainingStopsOnly =
            InstrumentationRegistry.getArguments().getString("fl013RemainingStopsOnly") == "true"
        val backgroundDisconnectOnly =
            InstrumentationRegistry.getArguments().getString("fl013BackgroundDisconnectOnly") == "true"
        val context = instrumentation.targetContext
        assertFalse("Samsung must be unlocked before FL013", context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
        val candidate = scanLooi(context)
        val bound = bind(context)
        try {
            bound.binder.connectFirstLife(ConnectionTarget(candidate.id, candidate.displayName))
            await("FL013 transport ready") {
                bound.binder.firstLifeSnapshot().stage == LooiFirstLifeConnectionStage.TRANSPORT_READY
            }
            assertNotNull(bound.binder.startFed9CaptureWithVerifiedHandshakeA())
            await("FL013 protocol, exact negotiation, and FED9") {
                val snapshot = bound.binder.firstLifeSnapshot()
                snapshot.stage == LooiFirstLifeConnectionStage.PROTOCOL_READY &&
                    snapshot.negotiatedCapabilities == LooiFirstLifeDefinition.capabilityReferences.map { it.wireValue } &&
                    bound.binder.t032State().fed9Active
            }

            val barrier = bound.binder.fed9CaptureState().records.maxOfOrNull { it.sequence } ?: 0L
            Log.i(TAG, "stage=lower-contact-hazard action=controlled-transition no-motion=true")
            await("FL013 controlled lower-contact hazard", OPERATOR_TIMEOUT_MS) {
                bound.binder.fed9CaptureState().records.any { record ->
                    record.sequence > barrier && record.payload.size == 5 && record.payload.firstOrNull() == 1 &&
                        record.payload.drop(1).any { it == 0 }
                }
            }
            val hazardSequence = checkNotNull(bound.binder.fed9CaptureState().records.lastOrNull { record ->
                record.sequence > barrier && record.payload.size == 5 && record.payload.firstOrNull() == 1 &&
                    record.payload.drop(1).any { it == 0 }
            }).sequence
            Log.i(TAG, "stage=lower-contact-safe-return action=fully-supported no-motion=true")
            await("FL013 fresh all-supported return", OPERATOR_TIMEOUT_MS) {
                bound.binder.fed9CaptureState().records.any {
                    it.sequence > hazardSequence && it.payload == listOf(1, 1, 1, 1, 1)
                }
            }
            await("FL013 T032 accepted fresh all-supported return") {
                val state = bound.binder.t032State()
                state.lowerContactSafe == true && state.streamFresh
            }
            Log.i(TAG, "stage=lower-contact-safe-return result=known-fresh-safe generation=current")

            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "stage=ui-setup action=select-body-open-camera-confirm-mount-and-floor")
            await("FL013 explicit active BODY selection", OPERATOR_TIMEOUT_MS) {
                bound.binder.firstLifeSnapshot().activeBodySelected
            }

            if (remainingStopsOnly || backgroundDisconnectOnly) {
                Log.i(TAG, "run-mode=remaining-stops-only prior-stages=evidence-preserved")
            } else {
                Log.i(TAG, "stage=center action=aim-person-center-then-hold")
                await("FL013 centered terminal", OPERATOR_TIMEOUT_MS) {
                    bound.binder.firstLifeOrientationEvidence().terminalReason == OrientationTerminalReason.CENTERED
                }
                assertEquals(0, bound.binder.firstLifeOrientationEvidence().moveInvocationCount)
                assertEquals(0, bound.binder.firstLifeOrientationEvidence().pulseCount)
                Log.i(TAG, "stage=center result=no-motion")

            Log.i(TAG, "stage=release action=aim-left-or-right-hold-briefly-then-release")
            await("FL013 release stop", OPERATOR_TIMEOUT_MS) {
                val evidence = bound.binder.firstLifeOrientationEvidence()
                evidence.terminalReason == OrientationTerminalReason.USER_RELEASED &&
                    evidence.moveInvocationCount in 1..2 && evidence.stopConfirmedCount >= 1
            }
            assertTrace(bound.binder.firstLifeOrientationEvidence())
            Log.i(TAG, "stage=release result=confirmed-stop")

            Log.i(TAG, "stage=person-loss action=hold-toward-person-then-remove-person-without-releasing")
            await("FL013 person loss stop", OPERATOR_TIMEOUT_MS) {
                val evidence = bound.binder.firstLifeOrientationEvidence()
                evidence.terminalReason in setOf(
                    OrientationTerminalReason.PERSON_EVIDENCE_MISSING,
                    OrientationTerminalReason.PERSON_EVIDENCE_STALE,
                    OrientationTerminalReason.PERSON_EVIDENCE_AMBIGUOUS,
                ) && evidence.moveInvocationCount > 0 && evidence.stopConfirmedCount >= 1
            }
            Log.i(TAG, "stage=person-loss result=terminal-stop")

            Log.i(TAG, "stage=max-budget action=aim-left-or-right-and-hold-at-least-one-second")
            await("FL013 three-pulse hard budget", OPERATOR_TIMEOUT_MS) {
                val evidence = bound.binder.firstLifeOrientationEvidence()
                evidence.terminalReason == OrientationTerminalReason.PULSE_BUDGET_EXHAUSTED &&
                    evidence.pulseCount == 3 && evidence.cumulativeEnergizedMs == 300L
            }
            val budget = bound.binder.firstLifeOrientationEvidence()
            assertEquals(6, budget.moveInvocationCount)
            assertEquals(3, budget.stopConfirmedCount)
            assertTrace(budget)
            val frozenMoves = budget.moveInvocationCount
            SystemClock.sleep(500)
            assertEquals(frozenMoves, bound.binder.firstLifeOrientationEvidence().moveInvocationCount)
            Log.i(TAG, "stage=max-budget result=pulses-3 energized-ms-300 later-pulses=false")

            val hazardBarrier = bound.binder.fed9CaptureState().records.maxOfOrNull { it.sequence } ?: 0L
            Log.i(TAG, "stage=lower-contact-gate action=create-hazard-then-try-hold no-motion-required=true")
            await("FL013 unsafe lower contact", OPERATOR_TIMEOUT_MS) {
                bound.binder.fed9CaptureState().records.any { record ->
                    record.sequence > hazardBarrier && record.payload.size == 5 && record.payload.firstOrNull() == 1 &&
                        record.payload.drop(1).any { it == 0 }
                } && bound.binder.t032State().lowerContactSafe == false
            }
            val beforeUnsafeHold = bound.binder.firstLifeOrientationEvidence().moveInvocationCount
            SystemClock.sleep(5_000)
            assertEquals(beforeUnsafeHold, bound.binder.firstLifeOrientationEvidence().moveInvocationCount)
            val unsafeSequence = checkNotNull(bound.binder.fed9CaptureState().records.lastOrNull { record ->
                record.sequence > hazardBarrier && record.payload.size == 5 && record.payload.drop(1).any { it == 0 }
            }).sequence
            Log.i(TAG, "stage=lower-contact-gate action=return-fully-supported")
            await("FL013 safe return after blocked hold", OPERATOR_TIMEOUT_MS) {
                bound.binder.fed9CaptureState().records.any {
                    it.sequence > unsafeSequence && it.payload == listOf(1, 1, 1, 1, 1)
                }
            }
            await("FL013 T032 accepted safe return after blocked hold") {
                bound.binder.t032State().lowerContactSafe == true
            }
            if (bound.binder.t032State().emergencyStopLatched) {
                assertFalse(bound.binder.resetT032EmergencyStopReportedStationary().emergencyStopLatched)
            }
                Log.i(TAG, "stage=lower-contact-gate result=no-move-while-unsafe")
            }

            if (backgroundDisconnectOnly) {
                Log.i(TAG, "run-mode=background-disconnect-only prior-estop=evidence-preserved")
            } else {
                Log.i(TAG, "stage=estop action=begin-one-hold-then-tap-visible-e-stop")
                if (remainingStopsOnly) {
                    await("FL013 active pulse before staged local E-stop", OPERATOR_TIMEOUT_MS) {
                        val evidence = bound.binder.firstLifeOrientationEvidence()
                        evidence.controllerPhase in setOf(OrientationPhase.PULSING, OrientationPhase.COOLDOWN) &&
                            evidence.moveInvocationCount > 0
                    }
                    Log.i(TAG, "stage=estop continuation-action=same-local-e-stop-entrypoint")
                    bound.binder.activateT032EmergencyStop()
                }
                await("FL013 local E-stop", OPERATOR_TIMEOUT_MS) {
                    bound.binder.t032State().emergencyStopLatched &&
                        bound.binder.firstLifeOrientationEvidence().terminalReason == OrientationTerminalReason.EMERGENCY_STOP
                }
                assertFalse(bound.binder.firstLifeOrientationAuthorityCurrent())
                Log.i(TAG, "stage=estop result=latched-authority-cleared")

                assertFalse(bound.binder.resetT032EmergencyStopReportedStationary().emergencyStopLatched)
            }
            Log.i(TAG, "stage=background action=prepare-floor-then-hold; harness-will-background")
            await("FL013 active pulse before background", OPERATOR_TIMEOUT_MS) {
                bound.binder.firstLifeOrientationEvidence().controllerPhase in setOf(
                    OrientationPhase.PULSING,
                    OrientationPhase.COOLDOWN,
                ) && bound.binder.firstLifeOrientationEvidence().moveInvocationCount > 0
            }
            assertTrue(
                "FL013 HOME action was rejected",
                instrumentation.uiAutomation.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME,
                ),
            )
            await("FL013 background stop", 10_000) {
                val evidence = bound.binder.firstLifeOrientationEvidence()
                evidence.terminalReason in BACKGROUND_SAFE_TERMINALS &&
                    evidence.stopConfirmedCount >= 1 &&
                    !bound.binder.firstLifeOrientationAuthorityCurrent()
            }
            assertFalse(bound.binder.firstLifeOrientationAuthorityCurrent())
            Log.i(
                TAG,
                "stage=background result=confirmed-stop-authority-cleared " +
                    "terminal=${bound.binder.firstLifeOrientationEvidence().terminalReason}",
            )

            bound.binder.disconnectFirstLife()
            await("FL013 disconnect safe", 20_000) {
                bound.binder.currentState().phase == ConnectionPhase.DISCONNECTED &&
                    !bound.binder.firstLifeSnapshot().protocolReady &&
                    bound.binder.firstLifeSnapshot().negotiatedCapabilities.isEmpty()
            }
            assertFalse(bound.binder.firstLifeOrientationAuthorityCurrent())
            Log.i(TAG, "stage=disconnect result=safe-readiness-cleared")
        } finally {
            runCatching { bound.binder.activateT032EmergencyStop() }
            runCatching { bound.binder.disconnectFirstLife() }
            context.unbindService(bound.connection)
        }
    }

    private fun assertTrace(evidence: io.redi.robot.firstlife.runtime.FirstLifeOrientationServiceEvidence) {
        assertEquals(io.redi.robot.firstlife.runtime.FirstLifeOrientationRoute.H007_H029_H030_H018_T032, evidence.lastRoute)
        assertTrue(evidence.h030AuthorizationCorrelated)
        assertTrue(evidence.h018Invoked)
        assertTrue(evidence.t032StopObserved)
        assertFalse(evidence.grantsSafetyAuthority)
        assertTrue(evidence.toString().contains("redacted=true"))
    }

    private fun scanLooi(context: Context): BleScanResult {
        val latch = CountDownLatch(1)
        var found: BleScanResult? = null
        var failure: BleScanFailure? = null
        val session = AndroidBleScanner(context).startScan(
            BleScanConfig(timeoutMillis = 15_000),
            BleScanListener { event ->
                val candidate = (event as? BleScanEvent.DeviceFound)?.result
                if (candidate?.displayName?.contains("LOOI", ignoreCase = true) == true && found == null) {
                    found = candidate
                    latch.countDown()
                }
                if (event is BleScanEvent.Failure && failure == null) {
                    failure = event.cause
                    latch.countDown()
                }
            },
        )
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
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline && !condition()) Thread.sleep(50)
        assertTrue("Timed out waiting for $label", condition())
    }

    private data class BoundService(
        val binder: ConnectedDeviceService.ConnectionBinder,
        val connection: ServiceConnection,
    )

    private companion object {
        const val TAG = "FL013_EVIDENCE"
        val BACKGROUND_SAFE_TERMINALS = setOf(
            OrientationTerminalReason.BACKGROUNDED,
            OrientationTerminalReason.USER_RELEASED,
            OrientationTerminalReason.CAMERA_CLOSED,
            OrientationTerminalReason.PERSON_EVIDENCE_MISSING,
            OrientationTerminalReason.PERSON_EVIDENCE_STALE,
            OrientationTerminalReason.PERSON_EVIDENCE_AMBIGUOUS,
        )
        // Physical actions are coordinated with the operator through an external test console.
        // This bounds staging wait only; it does not alter any production freshness or motion timer.
        const val OPERATOR_TIMEOUT_MS = 600_000L
    }
}
