package io.redi.robot.firstlife.experience

import io.redi.robot.firstlife.interaction.FirstLifeBodyStatus
import io.redi.robot.firstlife.interaction.FirstLifeDialogue
import io.redi.robot.firstlife.interaction.FirstLifeEventSequence
import io.redi.robot.firstlife.interaction.FirstLifeInteractionEffect
import io.redi.robot.firstlife.interaction.FirstLifeInteractionEvent
import io.redi.robot.firstlife.interaction.FirstLifeInteractionModel
import io.redi.robot.firstlife.interaction.FirstLifeInteractionReducer
import io.redi.robot.firstlife.interaction.FirstLifeInteractionState
import io.redi.robot.firstlife.interaction.FirstLifeMonotonicTimeMs
import io.redi.robot.firstlife.interaction.FirstLifeNameInputResult
import io.redi.robot.firstlife.interaction.FirstLifeNameInputSource
import io.redi.robot.firstlife.interaction.FirstLifePresentationIntent
import io.redi.robot.firstlife.interaction.FirstLifePresentationProjection
import io.redi.robot.firstlife.interaction.FirstLifeReductionStatus
import io.redi.robot.firstlife.interaction.FirstLifeRequestKind
import io.redi.robot.firstlife.interaction.FirstLifeRequestReference
import io.redi.robot.firstlife.interaction.FirstLifeSessionId
import io.redi.robot.firstlife.interaction.FirstLifeSpeechText
import io.redi.robot.firstlife.orientation.FirstLifeOrientationModel
import io.redi.robot.firstlife.vision.CameraSessionModel
import io.redi.robot.firstlife.vision.CameraSessionPhase
import io.redi.robot.firstlife.vision.PersonDirection
import io.redi.robot.firstlife.vision.PersonEvidenceReason
import io.redi.robot.firstlife.voice.SpeechOutputBlockedReason
import io.redi.robot.firstlife.voice.SpeechOutputKind
import io.redi.robot.firstlife.voice.SpeechOutputModel
import io.redi.robot.firstlife.voice.SpeechOutputPhase
import io.redi.robot.firstlife.voice.VoiceRecognitionTier
import io.redi.robot.firstlife.voice.VoiceSessionBlockedReason
import io.redi.robot.firstlife.voice.VoiceSessionModel
import io.redi.robot.firstlife.voice.VoiceSessionPhase
import io.redi.robot.hugo.identity.HugoIdentity

interface FirstLifeExperienceEffectPort {
    fun speak(kind: SpeechOutputKind, speech: FirstLifeSpeechText): Boolean
    fun startNameInput(): Boolean
    fun cancelNameInput(background: Boolean)
    fun cancelSpeech(background: Boolean)
    fun openVisibleCamera(): Boolean
    fun closeVisibleCamera(background: Boolean)
    fun stopOrientation(reason: FirstLifeExperienceCleanupReason)
    fun deactivateActiveBody()
}

/**
 * FL014 composition owner. It binds the accepted FL004 reducer to the already verified voice,
 * speech, camera, orientation, and body owners without acquiring any provider or execution port.
 */
class FirstLifeExperienceCoordinator(
    hugoIdentity: HugoIdentity?,
    private val sessionIdFactory: () -> FirstLifeSessionId,
    private val nowMs: () -> Long,
    private val nowNanos: () -> Long,
    private val effects: FirstLifeExperienceEffectPort,
    private val onSnapshotChanged: (FirstLifeExperienceSnapshot) -> Unit = {},
) {
    private var interaction = FirstLifeInteractionModel.start(
        hugoIdentity,
        sessionIdFactory(),
        FirstLifeMonotonicTimeMs(nowMs().coerceAtLeast(0L)),
    )
    private var presentation = FirstLifePresentationProjection.project(interaction)
    private var voice = VoiceSessionModel.IDLE
    private var speech = SpeechOutputModel.IDLE
    private var camera = CameraSessionModel.IDLE
    private var orientation = FirstLifeOrientationModel.IDLE
    private var observationReady = false
    private var centeredPersonConfirmed = false
    private var completingObservation = false
    private var blockedSource: FirstLifeExperienceBlockedSource? = null
    private var lastCleanupReason: FirstLifeExperienceCleanupReason? = null
    private var cleanupInProgress = false
    private var backgrounded = false
    private var pendingNameInputStart = false
    private var suppressNextVoiceStartEffect = false
    private var sequence = 0L

    val model: FirstLifeInteractionModel
        @Synchronized get() = interaction

    val snapshot: FirstLifeExperienceSnapshot
        @Synchronized get() = project()

    @Synchronized
    fun startIntroduction(): Boolean {
        blockedSource = null
        lastCleanupReason = null
        return dispatch(
            FirstLifeInteractionEvent.StartIntroduction(
                interaction.sessionId,
                nextSequence(),
                now(),
            ),
        )
    }

    @Synchronized
    fun requestNameInput(): Boolean {
        if (interaction.state != FirstLifeInteractionState.AWAITING_LISTEN_START ||
            camera.phase in CameraSessionModel.ACTIVE_PHASES || orientation.active || pendingNameInputStart
        ) {
            blockedSource = FirstLifeExperienceBlockedSource.RESOURCE_CONFLICT
            publish()
            return false
        }
        blockedSource = null
        pendingNameInputStart = true
        val started = effects.startNameInput()
        if (!started) {
            pendingNameInputStart = false
            blockedSource = FirstLifeExperienceBlockedSource.VOICE
        }
        publish()
        return started
    }

    @Synchronized
    fun openVisibleCamera(): Boolean {
        if (voice.phase in ACTIVE_VOICE_PHASES || speech.phase in SpeechOutputModel.ACTIVE_PHASES || orientation.active) {
            blockedSource = FirstLifeExperienceBlockedSource.RESOURCE_CONFLICT
            publish()
            return false
        }
        blockedSource = null
        val opened = effects.openVisibleCamera()
        if (!opened && camera.phase !in CameraSessionModel.ACTIVE_PHASES) {
            blockedSource = FirstLifeExperienceBlockedSource.CAMERA
        }
        publish()
        return opened
    }

    @Synchronized
    fun beforeOrientationHold(): Boolean {
        val allowed = camera.phase == CameraSessionPhase.OBSERVING &&
            voice.phase !in ACTIVE_VOICE_PHASES && speech.phase !in SpeechOutputModel.ACTIVE_PHASES
        if (!allowed) {
            blockedSource = FirstLifeExperienceBlockedSource.RESOURCE_CONFLICT
            publish()
        } else {
            blockedSource = null
        }
        return allowed
    }

    @Synchronized
    fun repeatGreeting(): Boolean {
        val name = interaction.sessionName ?: return false
        if (voice.phase in ACTIVE_VOICE_PHASES || speech.phase in SpeechOutputModel.ACTIVE_PHASES || orientation.active) {
            blockedSource = FirstLifeExperienceBlockedSource.RESOURCE_CONFLICT
            publish()
            return false
        }
        return effects.speak(SpeechOutputKind.SESSION_NAME_GREETING, FirstLifeDialogue.greeting(name))
    }

    @Synchronized
    fun bodyStatusChanged(status: FirstLifeBodyStatus) {
        if (status == interaction.bodyStatus) return
        dispatch(
            FirstLifeInteractionEvent.BodyStatusChanged(
                interaction.sessionId,
                nextSequence(),
                now(),
                status,
            ),
        )
    }

    @Synchronized
    fun onSpeechOutputChanged(next: SpeechOutputModel) {
        speech = next
        if (cleanupInProgress) return
        val request = interaction.activeRequest?.reference
        when {
            next.phase == SpeechOutputPhase.SPEAKING && request?.matches(next.activeRequest?.kind) == true ->
                dispatch(FirstLifeInteractionEvent.SpeechStarted(interaction.sessionId, nextSequence(), now(), request))
            next.phase == SpeechOutputPhase.COMPLETED && request?.matches(next.evidence?.kind) == true ->
                dispatch(FirstLifeInteractionEvent.SpeechCompleted(interaction.sessionId, nextSequence(), now(), request))
            next.phase == SpeechOutputPhase.BLOCKED && request?.kind in SPEECH_REQUEST_KINDS -> {
                val event = if (next.blockedReason in CANCELLED_SPEECH_REASONS) {
                    FirstLifeInteractionEvent.SpeechCancelled(interaction.sessionId, nextSequence(), now(), checkNotNull(request))
                } else {
                    FirstLifeInteractionEvent.SpeechUnavailable(interaction.sessionId, nextSequence(), now(), checkNotNull(request))
                }
                dispatch(event)
            }
            else -> publish()
        }
    }

    @Synchronized
    fun onVoiceSessionChanged(next: VoiceSessionModel) {
        voice = next
        if (cleanupInProgress) return
        if (next.phase == VoiceSessionPhase.LISTENING && pendingNameInputStart) {
            pendingNameInputStart = false
            blockedSource = null
            val occurredAt = now()
            suppressNextVoiceStartEffect = true
            val requested = dispatch(
                FirstLifeInteractionEvent.RequestNameInput(
                    interaction.sessionId,
                    nextSequence(),
                    occurredAt,
                    FirstLifeMonotonicTimeMs(occurredAt.value + NAME_INPUT_TIMEOUT_MS),
                ),
            )
            suppressNextVoiceStartEffect = false
            val request = interaction.activeRequest?.reference
            if (requested && request?.kind == FirstLifeRequestKind.NAME_INPUT) {
                dispatch(FirstLifeInteractionEvent.NameInputStarted(interaction.sessionId, nextSequence(), now(), request))
            } else {
                blockedSource = FirstLifeExperienceBlockedSource.VOICE
                publish()
            }
            return
        }
        if (next.phase == VoiceSessionPhase.BLOCKED && pendingNameInputStart) {
            if (next.blockedReason !in SYSTEM_FALLBACK_ELIGIBLE_REASONS) pendingNameInputStart = false
            blockedSource = FirstLifeExperienceBlockedSource.VOICE
            publish()
            return
        }
        val request = interaction.activeRequest?.reference
        when {
            next.phase == VoiceSessionPhase.LISTENING && request?.kind == FirstLifeRequestKind.NAME_INPUT -> {
                blockedSource = null
                dispatch(FirstLifeInteractionEvent.NameInputStarted(interaction.sessionId, nextSequence(), now(), request))
            }
            next.phase == VoiceSessionPhase.BLOCKED && request?.kind == FirstLifeRequestKind.NAME_INPUT -> {
                val result = when (next.blockedReason) {
                    VoiceSessionBlockedReason.NO_MATCH -> FirstLifeNameInputResult.NoSpeech
                    VoiceSessionBlockedReason.TIMEOUT -> FirstLifeNameInputResult.TimedOut
                    VoiceSessionBlockedReason.CANCELLED,
                    VoiceSessionBlockedReason.BACKGROUNDED,
                    -> FirstLifeNameInputResult.Cancelled
                    else -> null
                }
                if (result != null) {
                    dispatch(
                        FirstLifeInteractionEvent.NameInputCompleted(
                            interaction.sessionId,
                            nextSequence(),
                            now(),
                            request,
                            result,
                        ),
                    )
                } else {
                    blockedSource = FirstLifeExperienceBlockedSource.VOICE
                    publish()
                }
            }
            else -> publish()
        }
    }

    /** The bounded text exists only for this call and the accepted FL004 model. */
    @Synchronized
    fun onTranscript(rawText: String) {
        if (cleanupInProgress) return
        val request = interaction.activeRequest?.reference
            ?.takeIf { it.kind == FirstLifeRequestKind.NAME_INPUT }
            ?: return
        val source = if (voice.evidence?.tier == VoiceRecognitionTier.SYSTEM_NETWORK_ASSISTED) {
            FirstLifeNameInputSource.SYSTEM_RECOGNIZER_FALLBACK
        } else {
            FirstLifeNameInputSource.ON_DEVICE_STT
        }
        dispatch(
            FirstLifeInteractionEvent.NameInputCompleted(
                interaction.sessionId,
                nextSequence(),
                now(),
                request,
                FirstLifeNameInputResult.Success(rawText, source),
            ),
        )
    }

    @Synchronized
    fun onCameraSessionChanged(next: CameraSessionModel) {
        camera = next
        if (cleanupInProgress) return
        when (next.phase) {
            CameraSessionPhase.OBSERVING -> {
                if (observationReady) {
                    val centeredNow = next.personEvidence?.let { evidence ->
                        evidence.direction == PersonDirection.CENTER &&
                            evidence.reason == PersonEvidenceReason.STABLE_PERSON &&
                            evidence.isFreshAt(nowNanos())
                    } == true
                    if (centeredNow && !centeredPersonConfirmed) {
                        centeredPersonConfirmed = true
                        completingObservation = true
                        val stop = runCatching {
                            effects.stopOrientation(FirstLifeExperienceCleanupReason.OBSERVATION_COMPLETE)
                        }
                        val close = runCatching { effects.closeVisibleCamera(background = false) }
                        completingObservation = false
                        lastCleanupReason = FirstLifeExperienceCleanupReason.OBSERVATION_COMPLETE
                        blockedSource = if (stop.isFailure || close.isFailure) {
                            FirstLifeExperienceBlockedSource.CLEANUP
                        } else {
                            null
                        }
                    } else if (!centeredPersonConfirmed) {
                        blockedSource = null
                    }
                }
            }
            CameraSessionPhase.BLOCKED -> {
                if (!completingObservation && !centeredPersonConfirmed) {
                    if (observationReady) blockedSource = FirstLifeExperienceBlockedSource.CAMERA
                }
            }
            CameraSessionPhase.IDLE,
            CameraSessionPhase.AWAITING_PERMISSION,
            CameraSessionPhase.OPENING,
            -> Unit
        }
        publish()
    }

    @Synchronized
    fun onOrientationChanged(next: FirstLifeOrientationModel) {
        orientation = next
        publish()
    }

    @Synchronized
    fun cancel(): Boolean = cleanup(FirstLifeExperienceCleanupReason.USER_CANCEL, resetSession = false)

    @Synchronized
    fun reset(): Boolean = cleanup(FirstLifeExperienceCleanupReason.RESET, resetSession = true)

    @Synchronized
    fun background(): Boolean {
        if (backgrounded) return false
        backgrounded = true
        return cleanup(FirstLifeExperienceCleanupReason.BACKGROUND, resetSession = false)
    }

    @Synchronized
    fun foreground() {
        backgrounded = false
    }

    @Synchronized
    fun dispose(): Boolean {
        if (backgrounded) return false
        backgrounded = true
        return cleanup(FirstLifeExperienceCleanupReason.DISPOSE, resetSession = false)
    }

    private fun cleanup(reason: FirstLifeExperienceCleanupReason, resetSession: Boolean): Boolean {
        cleanupInProgress = true
        val background = reason == FirstLifeExperienceCleanupReason.BACKGROUND ||
            reason == FirstLifeExperienceCleanupReason.DISPOSE
        val outcomes = listOf(
            runCatching { effects.stopOrientation(reason) },
            runCatching { effects.closeVisibleCamera(background) },
            runCatching { effects.cancelNameInput(background) },
            runCatching { effects.cancelSpeech(background) },
            runCatching { effects.deactivateActiveBody() },
        )
        cleanupInProgress = false

        pendingNameInputStart = false
        suppressNextVoiceStartEffect = false
        blockedSource = if (outcomes.any { it.isFailure }) FirstLifeExperienceBlockedSource.CLEANUP else null
        observationReady = false
        centeredPersonConfirmed = false
        lastCleanupReason = reason
        if (resetSession) {
            reduceWithoutResourceEffects(
                FirstLifeInteractionEvent.ResetSession(
                    interaction.sessionId,
                    nextSequence(),
                    now(),
                    sessionIdFactory(),
                ),
            )
        } else if (interaction.state != FirstLifeInteractionState.READY) {
            reduceWithoutResourceEffects(
                FirstLifeInteractionEvent.CancelSession(interaction.sessionId, nextSequence(), now()),
            )
            if (interaction.state == FirstLifeInteractionState.CANCELLING) {
                reduceWithoutResourceEffects(
                    FirstLifeInteractionEvent.CancellationCompleted(interaction.sessionId, nextSequence(), now()),
                )
            }
        }
        publish()
        return true
    }

    private fun dispatch(event: FirstLifeInteractionEvent): Boolean {
        val reduction = FirstLifeInteractionReducer.reduce(interaction, event)
        interaction = reduction.model
        sequence = maxOf(sequence, interaction.lastSequence)
        if (reduction.status == FirstLifeReductionStatus.APPLIED) {
            reduction.effects.forEach(::execute)
        }
        publish()
        return reduction.status == FirstLifeReductionStatus.APPLIED
    }

    private fun reduceWithoutResourceEffects(event: FirstLifeInteractionEvent) {
        val reduction = FirstLifeInteractionReducer.reduce(interaction, event)
        interaction = reduction.model
        sequence = maxOf(sequence, interaction.lastSequence)
        reduction.effects.filterIsInstance<FirstLifeInteractionEffect.PresentState>().forEach(::execute)
    }

    private fun execute(effect: FirstLifeInteractionEffect) {
        when (effect) {
            is FirstLifeInteractionEffect.SpeakText -> {
                val kind = when (effect.request.kind) {
                    FirstLifeRequestKind.INTRODUCTION_SPEECH -> SpeechOutputKind.INTRODUCTION
                    FirstLifeRequestKind.GREETING_SPEECH -> SpeechOutputKind.SESSION_NAME_GREETING
                    FirstLifeRequestKind.NAME_INPUT -> return
                }
                if (!effects.speak(kind, effect.speech) && interaction.activeRequest?.reference == effect.request) {
                    dispatch(
                        FirstLifeInteractionEvent.SpeechUnavailable(
                            interaction.sessionId,
                            nextSequence(),
                            now(),
                            effect.request,
                        ),
                    )
                }
            }
            is FirstLifeInteractionEffect.RequestNameInput -> {
                if (suppressNextVoiceStartEffect) {
                    suppressNextVoiceStartEffect = false
                } else if (!effects.startNameInput()) {
                    blockedSource = FirstLifeExperienceBlockedSource.VOICE
                }
            }
            is FirstLifeInteractionEffect.CancelNameInput -> effects.cancelNameInput(background = false)
            is FirstLifeInteractionEffect.StopSpeaking -> effects.cancelSpeech(background = false)
            is FirstLifeInteractionEffect.PresentState -> presentation = effect.intent
            is FirstLifeInteractionEffect.NameRetryRequired ->
                blockedSource = FirstLifeExperienceBlockedSource.INTERACTION
            is FirstLifeInteractionEffect.ProceedToObservationPhase -> observationReady = true
        }
    }

    private fun project(): FirstLifeExperienceSnapshot {
        val stage = when {
            interaction.state == FirstLifeInteractionState.FAILED -> FirstLifeExperienceStage.FAILED
            blockedSource != null || interaction.state == FirstLifeInteractionState.BLOCKED -> FirstLifeExperienceStage.BLOCKED
            interaction.state == FirstLifeInteractionState.CANCELLING -> FirstLifeExperienceStage.CANCELLING
            centeredPersonConfirmed -> FirstLifeExperienceStage.PERSON_CENTERED
            observationReady && camera.phase == CameraSessionPhase.OBSERVING -> FirstLifeExperienceStage.OBSERVING
            observationReady -> FirstLifeExperienceStage.OBSERVATION_READY
            interaction.state == FirstLifeInteractionState.INTRODUCTION_READY ||
                interaction.state == FirstLifeInteractionState.INTRO_SPEAKING -> FirstLifeExperienceStage.INTRODUCTION
            interaction.state == FirstLifeInteractionState.AWAITING_LISTEN_START ||
                interaction.state == FirstLifeInteractionState.NAME_INPUT_REQUESTED ->
                FirstLifeExperienceStage.AWAITING_EXPLICIT_LISTEN
            interaction.state == FirstLifeInteractionState.LISTENING -> FirstLifeExperienceStage.LISTENING
            interaction.state == FirstLifeInteractionState.GREETING_READY ||
                interaction.state == FirstLifeInteractionState.GREETING_SPEAKING -> FirstLifeExperienceStage.GREETING
            else -> FirstLifeExperienceStage.READY
        }
        return FirstLifeExperienceSnapshot(
            stage = stage,
            interactionState = interaction.state,
            presentationIntent = presentation,
            bodyStatus = interaction.bodyStatus,
            sessionNameAvailable = interaction.sessionName != null,
            observationReady = observationReady,
            cameraActive = camera.phase == CameraSessionPhase.OBSERVING,
            orientationActive = orientation.active,
            centeredPersonConfirmed = centeredPersonConfirmed,
            blockedSource = blockedSource ?: if (interaction.state == FirstLifeInteractionState.BLOCKED) {
                FirstLifeExperienceBlockedSource.INTERACTION
            } else null,
            lastCleanupReason = lastCleanupReason,
        )
    }

    private fun publish() = onSnapshotChanged(project())

    private fun nextSequence(): FirstLifeEventSequence = FirstLifeEventSequence(++sequence)

    private fun now(): FirstLifeMonotonicTimeMs = FirstLifeMonotonicTimeMs(
        maxOf(nowMs().coerceAtLeast(0L), interaction.lastOccurredAt.value),
    )

    private fun FirstLifeRequestReference.matches(kind: SpeechOutputKind?): Boolean = when (this.kind) {
        FirstLifeRequestKind.INTRODUCTION_SPEECH -> kind == SpeechOutputKind.INTRODUCTION
        FirstLifeRequestKind.GREETING_SPEECH -> kind == SpeechOutputKind.SESSION_NAME_GREETING
        FirstLifeRequestKind.NAME_INPUT -> false
    }

    private companion object {
        const val NAME_INPUT_TIMEOUT_MS = 8_000L
        val ACTIVE_VOICE_PHASES = setOf(
            VoiceSessionPhase.AWAITING_SYSTEM_CONSENT,
            VoiceSessionPhase.AWAITING_PERMISSION,
            VoiceSessionPhase.CHECKING_SUPPORT,
            VoiceSessionPhase.LISTENING,
            VoiceSessionPhase.PROCESSING,
        )
        val SYSTEM_FALLBACK_ELIGIBLE_REASONS = setOf(
            VoiceSessionBlockedReason.ON_DEVICE_RECOGNIZER_UNAVAILABLE,
            VoiceSessionBlockedReason.ALBANIAN_MODEL_DOWNLOAD_REQUIRED,
            VoiceSessionBlockedReason.ALBANIAN_MODEL_UNAVAILABLE,
        )
        val SPEECH_REQUEST_KINDS = setOf(
            FirstLifeRequestKind.INTRODUCTION_SPEECH,
            FirstLifeRequestKind.GREETING_SPEECH,
        )
        val CANCELLED_SPEECH_REASONS = setOf(
            SpeechOutputBlockedReason.CANCELLED,
            SpeechOutputBlockedReason.BACKGROUNDED,
            SpeechOutputBlockedReason.INTERRUPTED,
        )
    }
}
