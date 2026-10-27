package io.redi.robot.feature.hihugo.presentation

import io.redi.robot.firstlife.identity.HugoBootstrapRunReference
import io.redi.robot.firstlife.identity.HugoIdentityBootstrapSnapshot
import io.redi.robot.firstlife.identity.HugoIdentityBootstrapStatus
import io.redi.robot.firstlife.identity.HugoSafeDisplayIdentity
import io.redi.robot.hugo.identity.HugoIdentity

enum class HiHugoFaceState {
    BOOTING,
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    OBSERVING,
    BODY_READY,
    BLOCKED,
    ERROR,
}

enum class HiHugoMotion { NONE, BOOT, BLINK, ATTEND, THINK, SPEAK, OBSERVE, READY }

enum class HiHugoAccent { CYAN, VIOLET, BLUE, GREEN, AMBER, RED }

data class HiHugoVisualSpec(
    val label: String,
    val status: String,
    val eyeOpenness: Float,
    val gazeAmplitude: Float,
    val motion: HiHugoMotion,
    val accent: HiHugoAccent,
    val motionIntervalMillis: Long,
)

object HiHugoVisualCatalog {
    fun forState(state: HiHugoFaceState): HiHugoVisualSpec = when (state) {
        HiHugoFaceState.BOOTING -> HiHugoVisualSpec("Booting", "Starting HUGO", .35f, 0f, HiHugoMotion.BOOT, HiHugoAccent.BLUE, 1_200)
        HiHugoFaceState.IDLE -> HiHugoVisualSpec("Idle", "HUGO is ready", 1f, 0f, HiHugoMotion.BLINK, HiHugoAccent.CYAN, 3_200)
        HiHugoFaceState.LISTENING -> HiHugoVisualSpec("Listening", "Listening session", 1.12f, 0f, HiHugoMotion.ATTEND, HiHugoAccent.CYAN, 1_100)
        HiHugoFaceState.THINKING -> HiHugoVisualSpec("Thinking", "Processing a request", .82f, .22f, HiHugoMotion.THINK, HiHugoAccent.VIOLET, 900)
        HiHugoFaceState.SPEAKING -> HiHugoVisualSpec("Speaking", "Speaking session", .9f, 0f, HiHugoMotion.SPEAK, HiHugoAccent.VIOLET, 520)
        HiHugoFaceState.OBSERVING -> HiHugoVisualSpec("Observing", "Visible observation session", 1.05f, .3f, HiHugoMotion.OBSERVE, HiHugoAccent.BLUE, 850)
        HiHugoFaceState.BODY_READY -> HiHugoVisualSpec("Body ready", "A body can be presented", 1f, 0f, HiHugoMotion.READY, HiHugoAccent.GREEN, 1_700)
        HiHugoFaceState.BLOCKED -> HiHugoVisualSpec("Blocked", "HUGO needs attention", .58f, 0f, HiHugoMotion.NONE, HiHugoAccent.AMBER, 0)
        HiHugoFaceState.ERROR -> HiHugoVisualSpec("Error", "HUGO could not start", .42f, 0f, HiHugoMotion.NONE, HiHugoAccent.RED, 0)
    }
}

data class HiHugoPresentationModel(
    val faceState: HiHugoFaceState,
    val identity: HugoIdentity?,
    val safeDisplayIdentity: HugoSafeDisplayIdentity?,
    val bootstrapRunReference: HugoBootstrapRunReference,
    val lastSequence: Long = 0,
) {
    init {
        require((identity == null) == (safeDisplayIdentity == null))
    }

    val visualSpec: HiHugoVisualSpec get() = HiHugoVisualCatalog.forState(faceState)
}

object HiHugoBootstrapProjection {
    fun project(snapshot: HugoIdentityBootstrapSnapshot): HiHugoPresentationModel {
        val state = when (snapshot.status) {
            HugoIdentityBootstrapStatus.CREATED,
            HugoIdentityBootstrapStatus.RESTORED,
            -> HiHugoFaceState.IDLE
            HugoIdentityBootstrapStatus.STORAGE_UNAVAILABLE -> HiHugoFaceState.BLOCKED
            HugoIdentityBootstrapStatus.CORRUPT_IDENTITY,
            HugoIdentityBootstrapStatus.UNSUPPORTED_SCHEMA,
            -> HiHugoFaceState.ERROR
        }
        return HiHugoPresentationModel(
            faceState = state,
            identity = snapshot.identity,
            safeDisplayIdentity = snapshot.safeDisplayIdentity,
            bootstrapRunReference = snapshot.runReference,
        )
    }
}

data class HiHugoPresentationEvent(
    val sequence: Long,
    val faceState: HiHugoFaceState,
)

enum class HiHugoReductionStatus { APPLIED, IGNORED_STALE }

data class HiHugoReduction(
    val model: HiHugoPresentationModel,
    val status: HiHugoReductionStatus,
)

object HiHugoPresentationReducer {
    fun reduce(
        current: HiHugoPresentationModel,
        event: HiHugoPresentationEvent,
    ): HiHugoReduction {
        if (event.sequence <= current.lastSequence) {
            return HiHugoReduction(current, HiHugoReductionStatus.IGNORED_STALE)
        }
        return HiHugoReduction(
            current.copy(faceState = event.faceState, lastSequence = event.sequence),
            HiHugoReductionStatus.APPLIED,
        )
    }
}

object HiHugoAnimationPolicy {
    fun isActive(model: HiHugoPresentationModel, lifecycleActive: Boolean, reducedMotion: Boolean): Boolean =
        lifecycleActive && !reducedMotion && model.visualSpec.motion != HiHugoMotion.NONE
}
