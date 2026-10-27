package io.redi.robot.platform.service

import io.redi.robot.feature.manualcontrol.T032AcceptanceState
import io.redi.robot.feature.manualcontrol.T032VerificationMode

/**
 * Projects the already-verified T032 event-state contract into the FL013 prerequisite model.
 *
 * `streamFresh` remains the raw 250 ms diagnostic-age fact. Profile-A FED9 lower-contact frames are
 * transition events, so after T032 has explicitly armed FLOOR mode the current generation-owned
 * known state remains usable until a later event, E-stop, subscription loss, disconnect, or
 * generation replacement invalidates it. This projection grants no safety or execution authority;
 * every move still passes through the unchanged T032 safety controller.
 */
internal fun T032AcceptanceState.hasCurrentLowerContactEvidenceForOrientation(): Boolean =
    streamFresh || (
        generation > 0L &&
            prerequisiteReady &&
            fed9Active &&
            sensorStateKnown &&
            mode == T032VerificationMode.FLOOR &&
            !emergencyStopLatched
        )
