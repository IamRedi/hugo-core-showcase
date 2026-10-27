package io.redi.robot.feature.hihugo.presentation

import io.redi.robot.firstlife.runtime.FirstLifeRuntimeSnapshot
import io.redi.robot.firstlife.runtime.FirstLifeRuntimeTimeMs

enum class HiHugoBodyStage {
    NO_BODY,
    CONNECTING,
    AVAILABLE,
    SELECTED,
    BLOCKED,
}

/** Safe H033 text projection; it contains no provider identity, command, or recovery callback. */
data class HiHugoBlockedPresentation(
    val heading: String,
    val providerLabel: String?,
    val sections: List<Pair<String, String>>,
    val details: List<String>,
    val resolutionLabel: String,
)

/** Redacted FL008 UI state. It has no provider ID, endpoint, session, grant, or execution callback. */
data class HiHugoBodyPresentationModel(
    val stage: HiHugoBodyStage,
    val providerLabel: String?,
    val statusLabel: String,
    val negotiationFresh: Boolean,
    val capabilities: List<String>,
    val selectionExpiresAt: FirstLifeRuntimeTimeMs?,
    val blocked: HiHugoBlockedPresentation?,
    val selectionGrantsAuthority: Boolean = false,
    val capabilitiesExecutableFromPresentation: Boolean = false,
) {
    val faceState: HiHugoFaceState get() = when (stage) {
        HiHugoBodyStage.AVAILABLE,
        HiHugoBodyStage.SELECTED,
        -> HiHugoFaceState.BODY_READY
        HiHugoBodyStage.BLOCKED -> HiHugoFaceState.BLOCKED
        HiHugoBodyStage.NO_BODY,
        HiHugoBodyStage.CONNECTING,
        -> HiHugoFaceState.IDLE
    }

    val canSelect: Boolean get() = stage == HiHugoBodyStage.AVAILABLE
    val canDeactivate: Boolean get() = stage == HiHugoBodyStage.SELECTED

    companion object {
        val EMPTY = HiHugoBodyPresentationModel(
            HiHugoBodyStage.NO_BODY,
            providerLabel = null,
            statusLabel = "No body available",
            negotiationFresh = false,
            capabilities = emptyList(),
            selectionExpiresAt = null,
            blocked = null,
        )
    }
}

object HiHugoBodyPresentationProjection {
    fun project(
        snapshot: FirstLifeRuntimeSnapshot?,
        blocked: HiHugoBlockedPresentation?,
    ): HiHugoBodyPresentationModel {
        if (blocked != null) return HiHugoBodyPresentationModel(
            HiHugoBodyStage.BLOCKED,
            providerLabel = snapshot?.providerAlias?.value,
            statusLabel = blocked.heading,
            negotiationFresh = false,
            capabilities = emptyList(),
            selectionExpiresAt = null,
            blocked = blocked,
        )
        snapshot ?: return HiHugoBodyPresentationModel.EMPTY
        val stage = when {
            snapshot.activeBodySelected && snapshot.bodyReady -> HiHugoBodyStage.SELECTED
            snapshot.bodyReady -> HiHugoBodyStage.AVAILABLE
            snapshot.providerPrepared || snapshot.connected -> HiHugoBodyStage.CONNECTING
            else -> HiHugoBodyStage.NO_BODY
        }
        val capabilities = if (snapshot.negotiated) {
            snapshot.negotiatedCapabilities.map { it.wireValue }.distinct().sorted()
        } else {
            emptyList()
        }
        return HiHugoBodyPresentationModel(
            stage = stage,
            providerLabel = snapshot.providerAlias?.value,
            statusLabel = when (stage) {
                HiHugoBodyStage.NO_BODY -> "No body available"
                HiHugoBodyStage.CONNECTING -> "Preparing body evidence"
                HiHugoBodyStage.AVAILABLE -> "Body available — selection required"
                HiHugoBodyStage.SELECTED -> "Active body selected"
                HiHugoBodyStage.BLOCKED -> error("Handled above")
            },
            negotiationFresh = snapshot.negotiated && snapshot.bodyReady,
            capabilities = capabilities,
            selectionExpiresAt = snapshot.activeBodySelectionExpiresAt,
            blocked = null,
        )
    }
}
