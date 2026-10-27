package io.redi.robot.body.looi.protocol.telemetrycapture

import io.redi.robot.body.looi.protocol.handshake.HANDSHAKE_CANDIDATE_A
import io.redi.robot.body.looi.protocol.handshake.HandshakeAttemptSummary
import io.redi.robot.body.looi.protocol.handshake.HandshakeCandidateId

/**
 * Fail-closed gate between the physically verified non-motion Candidate A experiment and FED9 capture.
 * It does not promote the connection to protocol-ready or authorize any control capability.
 */
object VerifiedFed9Prerequisite {
    fun isSatisfied(expectedGeneration: Long, summary: HandshakeAttemptSummary): Boolean =
        summary.generation == expectedGeneration &&
            summary.candidate == HandshakeCandidateId.A &&
            summary.featureEnabled &&
            summary.completed &&
            !summary.running &&
            !summary.cancelled &&
            summary.failureReason == null &&
            summary.stepResults.size == HANDSHAKE_CANDIDATE_A.steps.size &&
            summary.stepResults.all { it.success }
}
