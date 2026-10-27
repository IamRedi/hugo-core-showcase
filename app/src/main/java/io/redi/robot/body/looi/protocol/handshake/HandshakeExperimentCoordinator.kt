package io.redi.robot.body.looi.protocol.handshake

import io.redi.robot.body.looi.ble.notification.GattNotificationEvent
import io.redi.robot.body.looi.ble.notification.GattNotificationKey
import io.redi.robot.body.looi.ble.notification.GattNotificationRouter
import io.redi.robot.body.looi.ble.queue.GattOperationTransport
import io.redi.robot.body.looi.ble.queue.SerializedGattOperationQueue

/** Starts a named experiment only after its required CCCD subscriptions are active. */
class HandshakeExperimentCoordinator(
    private val generation: Long,
    operationTransport: GattOperationTransport,
    private val onEvent: (String) -> Unit = {},
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val queue = SerializedGattOperationQueue(operationTransport, nowProvider = nowProvider)
    private val subscribed = mutableSetOf<String>()
    private var pendingCandidate: HandshakeCandidateId? = null
    private val router = GattNotificationRouter(operationTransport, ::onNotificationEvent, nowProvider)
    private val runner = HandshakeRunner(
        transport = GenerationHandshakeTransport(generation, queue),
        subscriptionState = HandshakeNotificationSubscriptionState { requestedGeneration, short ->
            requestedGeneration == generation && short in subscribed
        },
        nowProvider = nowProvider,
    )

    fun start(candidateId: HandshakeCandidateId): HandshakeAttemptSummary {
        val candidate = HANDSHAKE_CANDIDATE_BY_ID.getValue(candidateId)
        pendingCandidate = candidateId
        candidate.steps.flatMap { it.requiredActiveSubscriptions }.toSet().forEach { shortUuid ->
            router.requestSubscribe(
                generation,
                GattNotificationKey("handshake-$candidateId", buildFullUuid("FF"), buildFullUuid(shortUuid)),
            )
        }
        startIfPrepared(candidate)
        return runner.summary()
    }

    fun cancel(): HandshakeAttemptSummary {
        pendingCandidate = null
        return runner.cancel(generation)
    }

    fun checkTimeout(): HandshakeAttemptSummary {
        queue.process(nowProvider())
        runner.checkTimeout(nowProvider())
        return runner.summary()
    }

    fun summary(): HandshakeAttemptSummary = runner.summary()

    /** True while CCCD setup or the bounded experiment is still in progress. */
    fun isActive(): Boolean = pendingCandidate != null || runner.summary().running

    private fun onNotificationEvent(event: GattNotificationEvent) {
        when (event) {
            is GattNotificationEvent.Subscribed -> {
                subscribed += event.key.characteristicUuid.take(8).takeLast(4).uppercase()
                onEvent("subscription active: ${event.key.characteristicUuid}")
                pendingCandidate?.let { startIfPrepared(HANDSHAKE_CANDIDATE_BY_ID.getValue(it)) }
            }
            is GattNotificationEvent.SubscribeFailed -> {
                onEvent("subscription failed: ${event.reason}")
                pendingCandidate = null
            }
            else -> Unit
        }
    }

    private fun startIfPrepared(candidate: HandshakeCandidate) {
        if (pendingCandidate != candidate.id) return
        val needed = candidate.steps.flatMap { it.requiredActiveSubscriptions }.toSet()
        if (needed.all { it in subscribed }) {
            pendingCandidate = null
            runner.start(generation, candidate.id)
        }
    }
}
