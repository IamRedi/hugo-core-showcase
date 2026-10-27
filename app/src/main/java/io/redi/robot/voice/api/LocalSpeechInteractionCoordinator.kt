package io.redi.robot.voice.api

import io.redi.robot.interaction.localintent.LocalIntentRouteReport
import io.redi.robot.interaction.localintent.LocalIntentRouter
import io.redi.robot.voice.session.AudioMonotonicTimeMs
import io.redi.robot.voice.session.AudioSessionId

enum class SpeechInteractionMode {
    RECOGNITION,
    SYNTHESIS,
}

enum class SpeechInteractionStartDisposition {
    ACCEPTED,
    BUSY,
    FOCUS_DENIED,
    FOCUS_UNAVAILABLE,
    FOCUS_BUSY,
    ADAPTER_BUSY,
    UNSUPPORTED_LOCALE,
    ADAPTER_UNAVAILABLE,
    INVALID_REQUEST,
    INTERRUPTED,
}

data class SpeechInteractionStartResult(
    val disposition: SpeechInteractionStartDisposition,
    val mode: SpeechInteractionMode,
    val operationOwned: Boolean,
) {
    init {
        require(operationOwned == (disposition == SpeechInteractionStartDisposition.ACCEPTED))
    }
}

enum class SpeechInterruptionReason {
    USER_REQUEST,
    BACKGROUNDED,
    NEW_SPEECH,
    AUDIO_FOCUS_LOST,
}

enum class SpeechInterruptionDisposition {
    REQUESTED,
    NOT_ACTIVE,
    WRONG_SESSION,
    STALE_TIME,
}

data class SpeechInteractionSnapshot(
    val active: Boolean,
    val mode: SpeechInteractionMode?,
    val focusOwned: Boolean,
    val transcriptRetained: Boolean = false,
    val audioRetained: Boolean = false,
) {
    init {
        require(active == (mode != null))
        require(!focusOwned || active)
        require(!transcriptRetained)
        require(!audioRetained)
    }

    val selectsProvider: Boolean get() = false
    val grantsAuthorization: Boolean get() = false
    val executesCapability: Boolean get() = false
}

sealed interface SpeechInteractionEvent {
    val operationId: SpeechOperationId
    val sessionId: AudioSessionId
    val occurredAt: AudioMonotonicTimeMs
    val mode: SpeechInteractionMode

    val selectsProvider: Boolean get() = false
    val grantsAuthorization: Boolean get() = false
    val executesCapability: Boolean get() = false

    data class AdapterStarted(
        override val operationId: SpeechOperationId,
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        override val mode: SpeechInteractionMode,
    ) : SpeechInteractionEvent

    data class LocalIntentRouted(
        override val operationId: SpeechOperationId,
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        val report: LocalIntentRouteReport,
    ) : SpeechInteractionEvent {
        override val mode: SpeechInteractionMode = SpeechInteractionMode.RECOGNITION

        override fun toString(): String = "SpeechInteractionEvent.LocalIntentRouted(" +
            "operation=$operationId, session=$sessionId, at=${occurredAt.value}, " +
            "status=${report.status}, transcript=discarded, execution=false)"
    }

    data class RecognitionNoMatch(
        override val operationId: SpeechOperationId,
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : SpeechInteractionEvent {
        override val mode: SpeechInteractionMode = SpeechInteractionMode.RECOGNITION
    }

    data class SynthesisCompleted(
        override val operationId: SpeechOperationId,
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
    ) : SpeechInteractionEvent {
        override val mode: SpeechInteractionMode = SpeechInteractionMode.SYNTHESIS
    }

    data class AdapterFailed(
        override val operationId: SpeechOperationId,
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        override val mode: SpeechInteractionMode,
        val failure: SpeechInteractionFailure,
    ) : SpeechInteractionEvent

    data class DeadlineExceeded(
        override val operationId: SpeechOperationId,
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        override val mode: SpeechInteractionMode,
    ) : SpeechInteractionEvent

    data class Interrupted(
        override val operationId: SpeechOperationId,
        override val sessionId: AudioSessionId,
        override val occurredAt: AudioMonotonicTimeMs,
        override val mode: SpeechInteractionMode,
        val reason: SpeechInterruptionReason,
        val cancellation: SpeechAdapterCancelDisposition,
    ) : SpeechInteractionEvent
}

fun interface SpeechInteractionListener {
    fun onEvent(event: SpeechInteractionEvent)
}

sealed interface SpeechInteractionFailure {
    data class Recognition(val reason: SpeechRecognitionFailure) : SpeechInteractionFailure
    data class Synthesis(val reason: SpeechSynthesisFailure) : SpeechInteractionFailure
}

/**
 * H049 contract-level composition. It routes final text through H046 before any later AI path and
 * owns no provider selection, authorization, capability dispatch, Android resource, or transcript store.
 */
class LocalSpeechInteractionCoordinator(
    private val recognitionPort: SpeechRecognitionPort,
    private val synthesisPort: SpeechSynthesisPort,
    private val focusPort: SpeechAudioFocusPort,
    private val intentRouter: LocalIntentRouter = LocalIntentRouter(),
    private val listener: SpeechInteractionListener,
) {
    private val lock = Any()
    private var nextOrdinal = 0L
    private var active: ActiveOperation? = null

    fun startRecognition(request: SpeechRecognitionRequest): SpeechInteractionStartResult = synchronized(lock) {
        if (active != null) return result(SpeechInteractionStartDisposition.BUSY, SpeechInteractionMode.RECOGNITION)
        val operation = ActiveOperation(
            ordinal = ++nextOrdinal,
            operationId = request.operationId,
            sessionId = request.sessionId,
            requestedAt = request.requestedAt,
            deadlineAt = request.deadlineAt,
            mode = SpeechInteractionMode.RECOGNITION,
            locale = request.locale,
        )
        active = operation
        val focus = focusPort.acquire(
            SpeechAudioFocusRequest(request.operationId, request.sessionId, SpeechAudioFocusUse.RECOGNITION),
        ) { event -> onFocusEvent(operation.ordinal, event) }
        if (focus.disposition != SpeechAudioFocusAcquireDisposition.GRANTED) {
            clearIfCurrent(operation)
            return mapFocusStart(focus.disposition, SpeechInteractionMode.RECOGNITION)
        }
        operation.focusOwned = true
        if (active !== operation) {
            releaseFocusOnce(operation)
            return result(SpeechInteractionStartDisposition.INTERRUPTED, SpeechInteractionMode.RECOGNITION)
        }
        val adapter = recognitionPort.start(request) { event -> onRecognitionEvent(operation.ordinal, event) }
        if (adapter.disposition != SpeechAdapterStartDisposition.ACCEPTED) {
            finish(operation)
            return mapAdapterStart(adapter.disposition, SpeechInteractionMode.RECOGNITION)
        }
        operation.adapterOwned = true
        result(SpeechInteractionStartDisposition.ACCEPTED, SpeechInteractionMode.RECOGNITION)
    }

    fun startSynthesis(request: SpeechSynthesisRequest): SpeechInteractionStartResult = synchronized(lock) {
        if (active != null) return result(SpeechInteractionStartDisposition.BUSY, SpeechInteractionMode.SYNTHESIS)
        val operation = ActiveOperation(
            ordinal = ++nextOrdinal,
            operationId = request.operationId,
            sessionId = request.sessionId,
            requestedAt = request.requestedAt,
            deadlineAt = request.deadlineAt,
            mode = SpeechInteractionMode.SYNTHESIS,
            locale = request.locale,
        )
        active = operation
        val focus = focusPort.acquire(
            SpeechAudioFocusRequest(request.operationId, request.sessionId, SpeechAudioFocusUse.SPEECH_OUTPUT),
        ) { event -> onFocusEvent(operation.ordinal, event) }
        if (focus.disposition != SpeechAudioFocusAcquireDisposition.GRANTED) {
            clearIfCurrent(operation)
            return mapFocusStart(focus.disposition, SpeechInteractionMode.SYNTHESIS)
        }
        operation.focusOwned = true
        if (active !== operation) {
            releaseFocusOnce(operation)
            return result(SpeechInteractionStartDisposition.INTERRUPTED, SpeechInteractionMode.SYNTHESIS)
        }
        val adapter = synthesisPort.start(request) { event -> onSynthesisEvent(operation.ordinal, event) }
        if (adapter.disposition != SpeechAdapterStartDisposition.ACCEPTED) {
            finish(operation)
            return mapAdapterStart(adapter.disposition, SpeechInteractionMode.SYNTHESIS)
        }
        operation.adapterOwned = true
        result(SpeechInteractionStartDisposition.ACCEPTED, SpeechInteractionMode.SYNTHESIS)
    }

    fun interrupt(
        sessionId: AudioSessionId,
        occurredAt: AudioMonotonicTimeMs,
        reason: SpeechInterruptionReason,
    ): SpeechInterruptionDisposition = synchronized(lock) {
        val operation = active ?: return SpeechInterruptionDisposition.NOT_ACTIVE
        if (operation.sessionId != sessionId) return SpeechInterruptionDisposition.WRONG_SESSION
        if (occurredAt.value < operation.requestedAt.value) return SpeechInterruptionDisposition.STALE_TIME
        interruptCurrent(operation, occurredAt, reason)
        SpeechInterruptionDisposition.REQUESTED
    }

    fun snapshot(): SpeechInteractionSnapshot = synchronized(lock) {
        val current = active
        SpeechInteractionSnapshot(
            active = current != null,
            mode = current?.mode,
            focusOwned = current?.focusOwned == true,
        )
    }

    private fun onRecognitionEvent(ordinal: Long, event: SpeechRecognitionEvent) = synchronized(lock) {
        val operation = current(ordinal, event.operationId, event.sessionId, SpeechInteractionMode.RECOGNITION)
        if (operation == null) {
            if (event is SpeechRecognitionEvent.FinalTranscript) event.transcript.discard()
            return
        }
        if (event.occurredAt.value < operation.requestedAt.value) {
            if (event is SpeechRecognitionEvent.FinalTranscript) event.transcript.discard()
            return
        }
        if (event.occurredAt.value >= operation.deadlineAt.value) {
            if (event is SpeechRecognitionEvent.FinalTranscript) event.transcript.discard()
            recognitionPort.cancel(operation.operationId)
            finish(operation)
            listener.onEvent(deadline(operation, event.occurredAt))
            return
        }
        when (event) {
            is SpeechRecognitionEvent.Started -> listener.onEvent(started(operation, event.occurredAt))
            is SpeechRecognitionEvent.FinalTranscript -> {
                val report = event.transcript.consume { alternatives ->
                    intentRouter.route(operation.locale.tag, alternatives)
                }
                finish(operation)
                listener.onEvent(
                    SpeechInteractionEvent.LocalIntentRouted(
                        operation.operationId,
                        operation.sessionId,
                        event.occurredAt,
                        report,
                    ),
                )
            }
            is SpeechRecognitionEvent.NoMatch -> {
                finish(operation)
                listener.onEvent(
                    SpeechInteractionEvent.RecognitionNoMatch(
                        operation.operationId,
                        operation.sessionId,
                        event.occurredAt,
                    ),
                )
            }
            is SpeechRecognitionEvent.Cancelled -> finish(operation)
            is SpeechRecognitionEvent.Failed -> {
                finish(operation)
                listener.onEvent(
                    failed(
                        operation,
                        event.occurredAt,
                        SpeechInteractionFailure.Recognition(event.reason),
                    ),
                )
            }
        }
    }

    private fun onSynthesisEvent(ordinal: Long, event: SpeechSynthesisEvent) = synchronized(lock) {
        val operation = current(ordinal, event.operationId, event.sessionId, SpeechInteractionMode.SYNTHESIS) ?: return
        if (event.occurredAt.value < operation.requestedAt.value) return
        if (event.occurredAt.value >= operation.deadlineAt.value) {
            synthesisPort.cancel(operation.operationId)
            finish(operation)
            listener.onEvent(deadline(operation, event.occurredAt))
            return
        }
        when (event) {
            is SpeechSynthesisEvent.Started -> listener.onEvent(started(operation, event.occurredAt))
            is SpeechSynthesisEvent.Completed -> {
                finish(operation)
                listener.onEvent(
                    SpeechInteractionEvent.SynthesisCompleted(
                        operation.operationId,
                        operation.sessionId,
                        event.occurredAt,
                    ),
                )
            }
            is SpeechSynthesisEvent.Cancelled -> finish(operation)
            is SpeechSynthesisEvent.Failed -> {
                finish(operation)
                listener.onEvent(
                    failed(
                        operation,
                        event.occurredAt,
                        SpeechInteractionFailure.Synthesis(event.reason),
                    ),
                )
            }
        }
    }

    private fun onFocusEvent(ordinal: Long, event: SpeechAudioFocusEvent) = synchronized(lock) {
        val operation = active?.takeIf {
            it.ordinal == ordinal && it.operationId == event.operationId && it.sessionId == event.sessionId
        } ?: return
        if (event.occurredAt.value < operation.requestedAt.value) return
        interruptCurrent(operation, event.occurredAt, SpeechInterruptionReason.AUDIO_FOCUS_LOST)
    }

    private fun interruptCurrent(
        operation: ActiveOperation,
        occurredAt: AudioMonotonicTimeMs,
        reason: SpeechInterruptionReason,
    ) {
        // Clear first so a synchronous adapter cancellation callback cannot revive or complete work.
        clearIfCurrent(operation)
        val cancellation = when (operation.mode) {
            SpeechInteractionMode.RECOGNITION -> recognitionPort.cancel(operation.operationId)
            SpeechInteractionMode.SYNTHESIS -> synthesisPort.cancel(operation.operationId)
        }
        releaseFocusOnce(operation)
        listener.onEvent(
            SpeechInteractionEvent.Interrupted(
                operation.operationId,
                operation.sessionId,
                occurredAt,
                operation.mode,
                reason,
                cancellation,
            ),
        )
    }

    private fun finish(operation: ActiveOperation) {
        clearIfCurrent(operation)
        releaseFocusOnce(operation)
    }

    private fun clearIfCurrent(operation: ActiveOperation) {
        if (active === operation) active = null
    }

    private fun releaseFocusOnce(operation: ActiveOperation) {
        if (!operation.focusOwned) return
        operation.focusOwned = false
        focusPort.release(operation.operationId)
    }

    private fun current(
        ordinal: Long,
        operationId: SpeechOperationId,
        sessionId: AudioSessionId,
        mode: SpeechInteractionMode,
    ): ActiveOperation? = active?.takeIf {
        it.ordinal == ordinal && it.operationId == operationId && it.sessionId == sessionId && it.mode == mode
    }

    private fun started(operation: ActiveOperation, at: AudioMonotonicTimeMs) =
        SpeechInteractionEvent.AdapterStarted(
            operation.operationId,
            operation.sessionId,
            at,
            operation.mode,
        )

    private fun failed(
        operation: ActiveOperation,
        at: AudioMonotonicTimeMs,
        failure: SpeechInteractionFailure,
    ) =
        SpeechInteractionEvent.AdapterFailed(
            operation.operationId,
            operation.sessionId,
            at,
            operation.mode,
            failure,
        )

    private fun deadline(operation: ActiveOperation, at: AudioMonotonicTimeMs) =
        SpeechInteractionEvent.DeadlineExceeded(
            operation.operationId,
            operation.sessionId,
            at,
            operation.mode,
        )

    private fun mapFocusStart(
        disposition: SpeechAudioFocusAcquireDisposition,
        mode: SpeechInteractionMode,
    ): SpeechInteractionStartResult = result(
        when (disposition) {
            SpeechAudioFocusAcquireDisposition.GRANTED -> error("granted focus is handled before mapping")
            SpeechAudioFocusAcquireDisposition.DENIED -> SpeechInteractionStartDisposition.FOCUS_DENIED
            SpeechAudioFocusAcquireDisposition.UNAVAILABLE -> SpeechInteractionStartDisposition.FOCUS_UNAVAILABLE
            SpeechAudioFocusAcquireDisposition.BUSY -> SpeechInteractionStartDisposition.FOCUS_BUSY
        },
        mode,
    )

    private fun mapAdapterStart(
        disposition: SpeechAdapterStartDisposition,
        mode: SpeechInteractionMode,
    ): SpeechInteractionStartResult = result(
        when (disposition) {
            SpeechAdapterStartDisposition.ACCEPTED -> error("accepted adapter is handled before mapping")
            SpeechAdapterStartDisposition.BUSY -> SpeechInteractionStartDisposition.ADAPTER_BUSY
            SpeechAdapterStartDisposition.UNSUPPORTED_LOCALE -> SpeechInteractionStartDisposition.UNSUPPORTED_LOCALE
            SpeechAdapterStartDisposition.UNAVAILABLE -> SpeechInteractionStartDisposition.ADAPTER_UNAVAILABLE
            SpeechAdapterStartDisposition.INVALID_REQUEST -> SpeechInteractionStartDisposition.INVALID_REQUEST
        },
        mode,
    )

    private fun result(
        disposition: SpeechInteractionStartDisposition,
        mode: SpeechInteractionMode,
    ) = SpeechInteractionStartResult(
        disposition,
        mode,
        operationOwned = disposition == SpeechInteractionStartDisposition.ACCEPTED,
    )

    private data class ActiveOperation(
        val ordinal: Long,
        val operationId: SpeechOperationId,
        val sessionId: AudioSessionId,
        val requestedAt: AudioMonotonicTimeMs,
        val deadlineAt: AudioMonotonicTimeMs,
        val mode: SpeechInteractionMode,
        val locale: SpeechLocale,
        var focusOwned: Boolean = false,
        var adapterOwned: Boolean = false,
    )
}
