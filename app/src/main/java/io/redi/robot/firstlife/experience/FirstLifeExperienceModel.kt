package io.redi.robot.firstlife.experience

import io.redi.robot.firstlife.interaction.FirstLifeBodyStatus
import io.redi.robot.firstlife.interaction.FirstLifeInteractionState
import io.redi.robot.firstlife.interaction.FirstLifePresentationIntent

enum class FirstLifeExperienceStage {
    READY,
    INTRODUCTION,
    AWAITING_EXPLICIT_LISTEN,
    LISTENING,
    GREETING,
    OBSERVATION_READY,
    OBSERVING,
    PERSON_CENTERED,
    CANCELLING,
    BLOCKED,
    FAILED,
}

enum class FirstLifeExperienceBlockedSource {
    INTERACTION,
    VOICE,
    CAMERA,
    RESOURCE_CONFLICT,
    CLEANUP,
}

enum class FirstLifeExperienceCleanupReason {
    USER_CANCEL,
    RESET,
    OBSERVATION_COMPLETE,
    BACKGROUND,
    DISPOSE,
}

/**
 * Presentation-safe composition evidence. It deliberately contains no identity value, session ID,
 * transcript, name, provider ID, camera geometry, transport detail, or execution authority.
 */
data class FirstLifeExperienceSnapshot(
    val stage: FirstLifeExperienceStage,
    val interactionState: FirstLifeInteractionState,
    val presentationIntent: FirstLifePresentationIntent,
    val bodyStatus: FirstLifeBodyStatus,
    val sessionNameAvailable: Boolean,
    val observationReady: Boolean,
    val cameraActive: Boolean,
    val orientationActive: Boolean,
    val centeredPersonConfirmed: Boolean,
    val blockedSource: FirstLifeExperienceBlockedSource?,
    val lastCleanupReason: FirstLifeExperienceCleanupReason?,
) {
    override fun toString(): String =
        "FirstLifeExperienceSnapshot(stage=$stage, interaction=$interactionState, " +
            "presentation=$presentationIntent, body=$bodyStatus, nameAvailable=$sessionNameAvailable, " +
            "observationReady=$observationReady, cameraActive=$cameraActive, " +
            "orientationActive=$orientationActive, centeredConfirmed=$centeredPersonConfirmed, " +
            "blockedSource=$blockedSource, cleanup=$lastCleanupReason)"
}
