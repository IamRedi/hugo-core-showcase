package io.redi.robot.body.looi.transport.timing

import io.redi.robot.body.looi.protocol.profile.LooiProtocolProfile

/** Monotonic elapsed time only; wall-clock time must never drive transport safety deadlines. */
fun interface MonotonicClock {
    fun nowMs(): Long
}

fun interface TimingTask {
    fun cancel()
}

/** Platform adapters provide scheduling; this pure coordinator owns all deadline policy. */
fun interface TimingScheduler {
    fun scheduleAt(monotonicDeadlineMs: Long, action: () -> Unit): TimingTask
}

enum class TransportRefreshKind {
    IDLE_NEUTRAL,
    MOVEMENT,
}

/**
 * A future profile encoder may consume this event. No payload or BLE operation is represented here.
 * Enabled candidates are opt-in because no refresh interval is hardware-verified yet.
 */
data class TransportRefreshCandidate(
    val intervalMs: Long,
    val enabled: Boolean = false,
) {
    init {
        require(intervalMs > 0) { "refresh interval must be positive" }
    }
}

data class TransportTimingConfiguration(
    val profile: LooiProtocolProfile,
    val localWatchdogTimeoutMs: Long,
    val idleNeutralCandidate: TransportRefreshCandidate? = null,
    val movementRefreshCandidate: TransportRefreshCandidate? = null,
) {
    init {
        require(localWatchdogTimeoutMs > 0) { "local watchdog timeout must be positive" }
    }
}

/** Conservative default: values must be supplied per profile, and refresh remains disabled. */
fun conservativeTransportTimingConfiguration(profile: LooiProtocolProfile) = TransportTimingConfiguration(
    profile = profile,
    localWatchdogTimeoutMs = 1_000L,
    idleNeutralCandidate = null,
    movementRefreshCandidate = null,
)

sealed interface TransportTimingEvent {
    val generation: Long
    val occurredAtMs: Long

    data class RefreshDue(
        override val generation: Long,
        override val occurredAtMs: Long,
        val kind: TransportRefreshKind,
        val scheduledDeadlineMs: Long,
    ) : TransportTimingEvent

    data class LocalWatchdogMissedDeadline(
        override val generation: Long,
        override val occurredAtMs: Long,
        val deadlineMs: Long,
    ) : TransportTimingEvent
}

data class TransportTimingSnapshot(
    val generation: Long? = null,
    val movementRefreshActive: Boolean = false,
    val watchdogMissed: Boolean = false,
)

/**
 * Separates optional protocol refresh timing from the mandatory local watchdog deadline.
 * A missed watchdog deadline is an event for the safety owner; this component never writes to BLE.
 */
class TransportTimingCoordinator(
    private val clock: MonotonicClock,
    private val scheduler: TimingScheduler,
    private val onEvent: (TransportTimingEvent) -> Unit,
) {
    private data class ActiveSession(
        val generation: Long,
        val configuration: TransportTimingConfiguration,
        var movementRefreshActive: Boolean = false,
        var watchdogMissed: Boolean = false,
        var watchdogTask: TimingTask? = null,
        var idleTask: TimingTask? = null,
        var movementTask: TimingTask? = null,
    )

    private var session: ActiveSession? = null

    fun start(generation: Long, configuration: TransportTimingConfiguration): TransportTimingSnapshot {
        require(generation >= 0) { "generation must not be negative" }
        if (session?.generation == generation) return snapshot()
        cancelSession()

        val active = ActiveSession(generation, configuration)
        session = active
        scheduleWatchdog(active, clock.nowMs() + configuration.localWatchdogTimeoutMs)
        configuration.idleNeutralCandidate.takeIf { it?.enabled == true }?.let { candidate ->
            scheduleRefresh(active, TransportRefreshKind.IDLE_NEUTRAL, candidate, clock.nowMs() + candidate.intervalMs)
        }
        return snapshot()
    }

    /** Call after an observed successful transport activity; this does not acknowledge physical motion. */
    fun recordTransportActivity(generation: Long): TransportTimingSnapshot {
        val active = active(generation) ?: return snapshot()
        active.watchdogMissed = false
        active.watchdogTask?.cancel()
        scheduleWatchdog(active, clock.nowMs() + active.configuration.localWatchdogTimeoutMs)
        return snapshot()
    }

    /** Hook only. It schedules no BLE write and stays inactive unless a verified candidate is explicitly enabled. */
    fun setMovementRefreshActive(generation: Long, active: Boolean): TransportTimingSnapshot {
        val session = active(generation) ?: return snapshot()
        if (session.movementRefreshActive == active) return snapshot()
        session.movementRefreshActive = active
        session.movementTask?.cancel()
        session.movementTask = null
        if (active) {
            session.configuration.movementRefreshCandidate
                ?.takeIf { it.enabled }
                ?.let { candidate ->
                    scheduleRefresh(session, TransportRefreshKind.MOVEMENT, candidate, clock.nowMs() + candidate.intervalMs)
                }
        }
        return snapshot()
    }

    fun cancelGeneration(generation: Long): TransportTimingSnapshot {
        if (session?.generation == generation) cancelSession()
        return snapshot()
    }

    fun snapshot(): TransportTimingSnapshot = session?.let {
        TransportTimingSnapshot(it.generation, it.movementRefreshActive, it.watchdogMissed)
    } ?: TransportTimingSnapshot()

    private fun scheduleWatchdog(active: ActiveSession, deadlineMs: Long) {
        active.watchdogTask = scheduler.scheduleAt(deadlineMs) {
            if (session !== active || clock.nowMs() < deadlineMs) return@scheduleAt
            active.watchdogMissed = true
            active.watchdogTask = null
            onEvent(TransportTimingEvent.LocalWatchdogMissedDeadline(active.generation, clock.nowMs(), deadlineMs))
        }
    }

    private fun scheduleRefresh(
        active: ActiveSession,
        kind: TransportRefreshKind,
        candidate: TransportRefreshCandidate,
        deadlineMs: Long,
    ) {
        val task = scheduler.scheduleAt(deadlineMs) {
            if (session !== active || !isRefreshCurrent(active, kind, candidate)) return@scheduleAt
            onEvent(TransportTimingEvent.RefreshDue(active.generation, clock.nowMs(), kind, deadlineMs))
            var nextDeadline = deadlineMs + candidate.intervalMs
            while (nextDeadline <= clock.nowMs()) nextDeadline += candidate.intervalMs
            scheduleRefresh(active, kind, candidate, nextDeadline)
        }
        when (kind) {
            TransportRefreshKind.IDLE_NEUTRAL -> active.idleTask = task
            TransportRefreshKind.MOVEMENT -> active.movementTask = task
        }
    }

    private fun isRefreshCurrent(
        active: ActiveSession,
        kind: TransportRefreshKind,
        candidate: TransportRefreshCandidate,
    ): Boolean = when (kind) {
        TransportRefreshKind.IDLE_NEUTRAL -> active.configuration.idleNeutralCandidate == candidate && candidate.enabled
        TransportRefreshKind.MOVEMENT -> active.movementRefreshActive && active.configuration.movementRefreshCandidate == candidate && candidate.enabled
    }

    private fun active(generation: Long): ActiveSession? = session?.takeIf { it.generation == generation }

    private fun cancelSession() {
        session?.watchdogTask?.cancel()
        session?.idleTask?.cancel()
        session?.movementTask?.cancel()
        session = null
    }
}
