package io.redi.robot.firstlife.orientation

import io.redi.robot.firstlife.vision.PersonDirection
import io.redi.robot.firstlife.vision.PersonEvidenceReason

/** Pure FL013 controller. It proposes semantic turns/stops and owns no executor or authority. */
class FirstLifeOrientationController {
    fun reduce(
        model: FirstLifeOrientationModel,
        event: OrientationEvent,
    ): OrientationTransition = when (event) {
        is OrientationEvent.BeginHold -> begin(model, event)
        is OrientationEvent.Tick -> tick(model, event)
        is OrientationEvent.PrerequisitesChanged -> prerequisitesChanged(model, event)
        is OrientationEvent.EffectCompleted -> effectCompleted(model, event)
        is OrientationEvent.UserReleased -> terminate(model, OrientationTerminalReason.USER_RELEASED, event.nowElapsedMs)
        is OrientationEvent.UserCancelled -> terminate(model, OrientationTerminalReason.USER_CANCELLED, event.nowElapsedMs)
        is OrientationEvent.Backgrounded -> terminate(model, OrientationTerminalReason.BACKGROUNDED, event.nowElapsedMs)
        is OrientationEvent.EmergencyStop -> terminate(model, OrientationTerminalReason.EMERGENCY_STOP, event.nowElapsedMs)
    }

    private fun begin(
        model: FirstLifeOrientationModel,
        event: OrientationEvent.BeginHold,
    ): OrientationTransition {
        if (model.active) return OrientationTransition(model, staleEventRejected = true)
        prerequisiteFailure(event.prerequisites, event.nowElapsedNanos)?.let {
            return blocked(event.attemptId, event.nowElapsedMs, it)
        }
        return when (event.prerequisites.personEvidence?.direction) {
            PersonDirection.CENTER -> completed(event.attemptId, event.nowElapsedMs, OrientationTerminalReason.CENTERED)
            PersonDirection.LEFT -> startPulse(base(event), OrientationTurnDirection.LEFT, event.nowElapsedMs)
            PersonDirection.RIGHT -> startPulse(base(event), OrientationTurnDirection.RIGHT, event.nowElapsedMs)
            PersonDirection.UNKNOWN,
            null,
            -> blocked(
                event.attemptId,
                event.nowElapsedMs,
                evidenceFailure(event.prerequisites, event.nowElapsedNanos)
                    ?: OrientationTerminalReason.PERSON_EVIDENCE_AMBIGUOUS,
            )
        }
    }

    private fun base(event: OrientationEvent.BeginHold) = FirstLifeOrientationModel(
        attemptId = event.attemptId,
        phase = OrientationPhase.COOLDOWN,
        startedAtElapsedMs = event.nowElapsedMs,
    )

    private fun tick(model: FirstLifeOrientationModel, event: OrientationEvent.Tick): OrientationTransition {
        if (!model.active) return OrientationTransition(model, staleEventRejected = true)
        wallClockFailure(model, event.nowElapsedMs)?.let { return terminate(model, it, event.nowElapsedMs) }
        prerequisiteFailure(event.prerequisites, event.nowElapsedNanos)?.let {
            return terminate(model, it, event.nowElapsedMs)
        }
        val observedDirection = event.prerequisites.personEvidence?.direction
        if (model.direction != null && observedDirection == PersonDirection.CENTER) {
            return terminate(model, OrientationTerminalReason.CENTERED, event.nowElapsedMs)
        }
        if (model.direction != null && observedDirection?.turnDirection() != model.direction) {
            return terminate(model, OrientationTerminalReason.DIRECTION_CHANGED, event.nowElapsedMs)
        }
        val deadline = model.nextDeadlineElapsedMs ?: return OrientationTransition(model, staleEventRejected = true)
        if (event.nowElapsedMs < deadline) return OrientationTransition(model, staleEventRejected = true)
        return when (model.phase) {
            OrientationPhase.PULSING -> advancePulse(model, event.nowElapsedMs)
            OrientationPhase.COOLDOWN -> when {
                model.pulseCount >= FirstLifeOrientationEnvelope.MAX_PULSES ->
                    completeAfterConfirmedStop(model, OrientationTerminalReason.PULSE_BUDGET_EXHAUSTED)
                else -> startPulse(model, checkNotNull(observedDirection?.turnDirection()), event.nowElapsedMs)
            }
            else -> OrientationTransition(model, staleEventRejected = true)
        }
    }

    private fun prerequisitesChanged(
        model: FirstLifeOrientationModel,
        event: OrientationEvent.PrerequisitesChanged,
    ): OrientationTransition {
        if (!model.active) return OrientationTransition(model, staleEventRejected = true)
        wallClockFailure(model, event.nowElapsedMs)?.let { return terminate(model, it, event.nowElapsedMs) }
        prerequisiteFailure(event.prerequisites, event.nowElapsedNanos)?.let {
            return terminate(model, it, event.nowElapsedMs)
        }
        val direction = event.prerequisites.personEvidence?.direction
        return when {
            direction == PersonDirection.CENTER -> terminate(model, OrientationTerminalReason.CENTERED, event.nowElapsedMs)
            model.direction != null && direction?.turnDirection() != model.direction ->
                terminate(model, OrientationTerminalReason.DIRECTION_CHANGED, event.nowElapsedMs)
            else -> OrientationTransition(model)
        }
    }

    private fun advancePulse(model: FirstLifeOrientationModel, nowMs: Long): OrientationTransition {
        val started = checkNotNull(model.pulseStartedAtElapsedMs)
        val hardStopAt = started + FirstLifeOrientationEnvelope.MAX_PULSE_MS
        if (nowMs >= hardStopAt || model.currentPulseRefreshes >= FirstLifeOrientationEnvelope.MAX_REFRESHES_PER_PULSE) {
            return requestStop(model, reason = null, nowMs = nowMs)
        }
        val id = nextEffect(model)
        val refresh = model.currentPulseRefreshes + 1
        return OrientationTransition(
            model.copy(
                phase = OrientationPhase.AWAITING_MOVE_RESULT,
                currentPulseRefreshes = refresh,
                pendingEffect = id,
                nextDeadlineElapsedMs = hardStopAt,
                nextEffectSequence = id.sequence + 1,
            ),
            listOf(OrientationEffect.ProposeTurn(id, checkNotNull(model.direction), refreshOrdinal = refresh)),
        )
    }

    private fun effectCompleted(
        model: FirstLifeOrientationModel,
        event: OrientationEvent.EffectCompleted,
    ): OrientationTransition {
        if (event.effectId != model.pendingEffect || !model.active) {
            return OrientationTransition(model, staleEventRejected = true)
        }
        return when (model.phase) {
            OrientationPhase.AWAITING_MOVE_RESULT -> when (event.outcome) {
                OrientationEffectOutcome.MOVE_ACCEPTED -> {
                    val started = checkNotNull(model.pulseStartedAtElapsedMs)
                    val next = if (model.currentPulseRefreshes < FirstLifeOrientationEnvelope.MAX_REFRESHES_PER_PULSE) {
                        started + FirstLifeOrientationEnvelope.REFRESH_INTERVAL_MS
                    } else {
                        started + FirstLifeOrientationEnvelope.MAX_PULSE_MS
                    }
                    OrientationTransition(model.copy(
                        phase = OrientationPhase.PULSING,
                        pendingEffect = null,
                        nextDeadlineElapsedMs = next,
                    ))
                }
                OrientationEffectOutcome.AUTHORIZATION_DENIED ->
                    requestStop(model, OrientationTerminalReason.AUTHORIZATION_DENIED, event.nowElapsedMs)
                OrientationEffectOutcome.SAFETY_REJECTED ->
                    requestStop(model, OrientationTerminalReason.SAFETY_REJECTED, event.nowElapsedMs)
                else -> requestStop(model, OrientationTerminalReason.DISPATCH_REJECTED, event.nowElapsedMs)
            }
            OrientationPhase.AWAITING_STOP_RESULT -> when (event.outcome) {
                OrientationEffectOutcome.STOP_CONFIRMED -> afterStopConfirmed(model, event.nowElapsedMs)
                else -> OrientationTransition(model.copy(
                    phase = OrientationPhase.BLOCKED,
                    pendingEffect = null,
                    nextDeadlineElapsedMs = null,
                    terminalReason = OrientationTerminalReason.STOP_NOT_CONFIRMED,
                ))
            }
            else -> OrientationTransition(model, staleEventRejected = true)
        }
    }

    private fun startPulse(
        model: FirstLifeOrientationModel,
        direction: OrientationTurnDirection,
        nowMs: Long,
    ): OrientationTransition {
        if (model.pulseCount >= FirstLifeOrientationEnvelope.MAX_PULSES ||
            model.cumulativeEnergizedMs >= FirstLifeOrientationEnvelope.MAX_CUMULATIVE_ENERGIZED_MS
        ) return completeAfterConfirmedStop(model, OrientationTerminalReason.PULSE_BUDGET_EXHAUSTED)
        val id = nextEffect(model)
        return OrientationTransition(
            model.copy(
                phase = OrientationPhase.AWAITING_MOVE_RESULT,
                pulseStartedAtElapsedMs = nowMs,
                pulseCount = model.pulseCount + 1,
                currentPulseRefreshes = 1,
                direction = direction,
                pendingEffect = id,
                nextDeadlineElapsedMs = nowMs + FirstLifeOrientationEnvelope.REFRESH_INTERVAL_MS,
                stopTerminalReason = null,
                terminalReason = null,
                nextEffectSequence = id.sequence + 1,
            ),
            listOf(OrientationEffect.ProposeTurn(id, direction, refreshOrdinal = 1)),
        )
    }

    private fun terminate(
        model: FirstLifeOrientationModel,
        reason: OrientationTerminalReason,
        nowMs: Long,
    ): OrientationTransition {
        if (!model.active) return OrientationTransition(model, staleEventRejected = true)
        return requestStop(model, reason, nowMs)
    }

    private fun requestStop(
        model: FirstLifeOrientationModel,
        reason: OrientationTerminalReason?,
        nowMs: Long,
    ): OrientationTransition {
        if (model.phase == OrientationPhase.AWAITING_STOP_RESULT) return OrientationTransition(model)
        val energized = model.pulseStartedAtElapsedMs?.let {
            (nowMs - it).coerceIn(0L, FirstLifeOrientationEnvelope.MAX_PULSE_MS)
        } ?: 0L
        val id = nextEffect(model)
        return OrientationTransition(
            model.copy(
                phase = OrientationPhase.AWAITING_STOP_RESULT,
                cumulativeEnergizedMs = (model.cumulativeEnergizedMs + energized)
                    .coerceAtMost(FirstLifeOrientationEnvelope.MAX_CUMULATIVE_ENERGIZED_MS),
                pendingEffect = id,
                nextDeadlineElapsedMs = null,
                stopTerminalReason = reason,
                nextEffectSequence = id.sequence + 1,
            ),
            listOf(OrientationEffect.RequestStop(id, reason)),
        )
    }

    private fun afterStopConfirmed(model: FirstLifeOrientationModel, nowMs: Long): OrientationTransition {
        model.stopTerminalReason?.let { return completeAfterConfirmedStop(model, it) }
        if (model.pulseCount >= FirstLifeOrientationEnvelope.MAX_PULSES ||
            model.cumulativeEnergizedMs >= FirstLifeOrientationEnvelope.MAX_CUMULATIVE_ENERGIZED_MS
        ) return completeAfterConfirmedStop(model, OrientationTerminalReason.PULSE_BUDGET_EXHAUSTED)
        return OrientationTransition(model.copy(
            phase = OrientationPhase.COOLDOWN,
            pulseStartedAtElapsedMs = null,
            currentPulseRefreshes = 0,
            pendingEffect = null,
            nextDeadlineElapsedMs = nowMs + FirstLifeOrientationEnvelope.COOLDOWN_MS,
            stopTerminalReason = null,
        ))
    }

    private fun completeAfterConfirmedStop(
        model: FirstLifeOrientationModel,
        reason: OrientationTerminalReason,
    ) = OrientationTransition(model.copy(
        phase = if (reason in successfulTerminalReasons) OrientationPhase.COMPLETED else OrientationPhase.BLOCKED,
        pulseStartedAtElapsedMs = null,
        currentPulseRefreshes = 0,
        pendingEffect = null,
        nextDeadlineElapsedMs = null,
        stopTerminalReason = null,
        terminalReason = reason,
    ))

    private fun completed(attemptId: OrientationAttemptId, nowMs: Long, reason: OrientationTerminalReason) =
        OrientationTransition(FirstLifeOrientationModel(
            attemptId = attemptId,
            phase = OrientationPhase.COMPLETED,
            startedAtElapsedMs = nowMs,
            terminalReason = reason,
        ))

    private fun blocked(attemptId: OrientationAttemptId, nowMs: Long, reason: OrientationTerminalReason) =
        OrientationTransition(FirstLifeOrientationModel(
            attemptId = attemptId,
            phase = OrientationPhase.BLOCKED,
            startedAtElapsedMs = nowMs,
            terminalReason = reason,
        ))

    private fun nextEffect(model: FirstLifeOrientationModel) = OrientationEffectId(
        checkNotNull(model.attemptId),
        model.nextEffectSequence,
    )

    private fun prerequisiteFailure(value: OrientationPrerequisites, nowNanos: Long): OrientationTerminalReason? = when {
        !value.emergencyStopClear -> OrientationTerminalReason.EMERGENCY_STOP
        !value.bodyReady -> OrientationTerminalReason.BODY_UNAVAILABLE
        !value.activeBodySelected -> OrientationTerminalReason.ACTIVE_BODY_LOST
        !value.mountingAligned -> OrientationTerminalReason.MOUNTING_UNCONFIRMED
        !value.connectionCurrent -> OrientationTerminalReason.CONNECTION_LOST
        !value.negotiationCurrent -> OrientationTerminalReason.NEGOTIATION_STALE
        !value.authorityGrantCurrent -> OrientationTerminalReason.AUTHORITY_LOST
        !value.cameraSessionActive -> OrientationTerminalReason.CAMERA_CLOSED
        !value.floorModeArmed -> OrientationTerminalReason.FLOOR_MODE_NOT_ARMED
        !value.lowerContactKnown -> OrientationTerminalReason.LOWER_CONTACT_UNKNOWN
        !value.lowerContactFresh -> OrientationTerminalReason.LOWER_CONTACT_STALE
        !value.lowerContactSafe -> OrientationTerminalReason.LOWER_CONTACT_UNSAFE
        else -> evidenceFailure(value, nowNanos)
    }

    private fun evidenceFailure(value: OrientationPrerequisites, nowNanos: Long): OrientationTerminalReason? {
        val evidence = value.personEvidence ?: return OrientationTerminalReason.PERSON_EVIDENCE_MISSING
        if (!evidence.isFreshAt(nowNanos)) return OrientationTerminalReason.PERSON_EVIDENCE_STALE
        if (evidence.reason != PersonEvidenceReason.STABLE_PERSON || evidence.direction == PersonDirection.UNKNOWN) {
            return OrientationTerminalReason.PERSON_EVIDENCE_AMBIGUOUS
        }
        return null
    }

    private fun wallClockFailure(model: FirstLifeOrientationModel, nowMs: Long): OrientationTerminalReason? =
        model.startedAtElapsedMs?.takeIf {
            nowMs - it >= FirstLifeOrientationEnvelope.MAX_ATTEMPT_WALL_CLOCK_MS
        }?.let { OrientationTerminalReason.WALL_CLOCK_BUDGET_EXHAUSTED }

    private fun PersonDirection.turnDirection(): OrientationTurnDirection? = when (this) {
        PersonDirection.LEFT -> OrientationTurnDirection.LEFT
        PersonDirection.RIGHT -> OrientationTurnDirection.RIGHT
        PersonDirection.CENTER,
        PersonDirection.UNKNOWN,
        -> null
    }

    private companion object {
        val successfulTerminalReasons = setOf(
            OrientationTerminalReason.CENTERED,
            OrientationTerminalReason.USER_RELEASED,
            OrientationTerminalReason.USER_CANCELLED,
            OrientationTerminalReason.PULSE_BUDGET_EXHAUSTED,
        )
    }
}
