package io.redi.robot.firstlife.runtime

import io.redi.robot.firstlife.orientation.FirstLifeOrientationController
import io.redi.robot.firstlife.orientation.FirstLifeOrientationModel
import io.redi.robot.firstlife.orientation.OrientationAttemptId
import io.redi.robot.firstlife.orientation.OrientationEffect
import io.redi.robot.firstlife.orientation.OrientationEffectOutcome
import io.redi.robot.firstlife.orientation.OrientationEvent
import io.redi.robot.firstlife.orientation.OrientationPrerequisites
import io.redi.robot.firstlife.orientation.OrientationTransition

/**
 * Small lifecycle owner for the pure FL013 reducer. Scheduling remains outside this class so tests
 * and Android use the same deterministic transition logic.
 */
class FirstLifeOrientationSessionCoordinator(
    private val execute: (OrientationEffect) -> OrientationEffectOutcome,
    private val nowElapsedMs: () -> Long,
    private val nowElapsedNanos: () -> Long,
    private val attemptId: () -> OrientationAttemptId,
    private val onModelChanged: (FirstLifeOrientationModel) -> Unit = {},
    private val onAttemptEnded: () -> Unit = {},
) {
    private val controller = FirstLifeOrientationController()
    private var latestPrerequisites: OrientationPrerequisites? = null
    var model: FirstLifeOrientationModel = FirstLifeOrientationModel.IDLE
        private set

    @Synchronized
    fun begin(prerequisites: OrientationPrerequisites): FirstLifeOrientationModel {
        latestPrerequisites = prerequisites
        if (model.active) return model
        apply(controller.reduce(model, OrientationEvent.BeginHold(
            attemptId(), nowElapsedMs(), nowElapsedNanos(), prerequisites,
        )))
        return model
    }

    @Synchronized
    fun tick(prerequisites: OrientationPrerequisites = checkNotNull(latestPrerequisites)): FirstLifeOrientationModel {
        latestPrerequisites = prerequisites
        apply(controller.reduce(model, OrientationEvent.Tick(nowElapsedMs(), nowElapsedNanos(), prerequisites)))
        return model
    }

    @Synchronized
    fun prerequisitesChanged(prerequisites: OrientationPrerequisites): FirstLifeOrientationModel {
        latestPrerequisites = prerequisites
        apply(controller.reduce(model, OrientationEvent.PrerequisitesChanged(
            nowElapsedMs(), nowElapsedNanos(), prerequisites,
        )))
        return model
    }

    @Synchronized fun release() = terminal(OrientationEvent.UserReleased(nowElapsedMs(), nowElapsedNanos()))
    @Synchronized fun cancel() = terminal(OrientationEvent.UserCancelled(nowElapsedMs(), nowElapsedNanos()))
    @Synchronized fun background() = terminal(OrientationEvent.Backgrounded(nowElapsedMs(), nowElapsedNanos()))
    @Synchronized fun emergencyStop() = terminal(OrientationEvent.EmergencyStop(nowElapsedMs(), nowElapsedNanos()))

    private fun terminal(event: OrientationEvent): FirstLifeOrientationModel {
        apply(controller.reduce(model, event))
        return model
    }

    private fun apply(initial: OrientationTransition) {
        var transition = initial
        var depth = 0
        while (true) {
            val wasActive = model.active
            val previousAttempt = model.attemptId
            model = transition.model
            onModelChanged(model)
            if (!model.active && model.attemptId != null && (wasActive || previousAttempt != model.attemptId)) {
                onAttemptEnded()
            }
            val effect = transition.effects.singleOrNull() ?: return
            check(++depth <= MAX_SYNCHRONOUS_EFFECTS)
            val outcome = execute(effect)
            transition = controller.reduce(model, OrientationEvent.EffectCompleted(
                effect.id, outcome, nowElapsedMs(), nowElapsedNanos(),
            ))
        }
    }

    private companion object { const val MAX_SYNCHRONOUS_EFFECTS = 4 }
}
