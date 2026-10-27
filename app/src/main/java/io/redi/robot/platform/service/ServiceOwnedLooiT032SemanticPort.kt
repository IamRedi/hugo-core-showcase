package io.redi.robot.platform.service

import io.redi.robot.body.looi.firstlife.LooiSemanticDirection
import io.redi.robot.body.looi.firstlife.LooiSemanticHeadPosition
import io.redi.robot.body.looi.firstlife.LooiT032SemanticCommand
import io.redi.robot.body.looi.firstlife.LooiT032SemanticCommandResult
import io.redi.robot.body.looi.firstlife.LooiT032SemanticPort
import io.redi.robot.body.looi.firstlife.LooiT032SemanticSnapshot
import io.redi.robot.capability.provider.ProviderRuntimeSnapshot
import io.redi.robot.feature.manualcontrol.ManualDriveCommand
import io.redi.robot.feature.manualcontrol.ManualDriveDirection
import io.redi.robot.feature.manualcontrol.ManualStopReason
import io.redi.robot.feature.manualcontrol.T032AcceptanceState
import io.redi.robot.feature.manualcontrol.T032VerifiedCommandResult
import io.redi.robot.feature.manualcontrol.T032VerifiedDisposition

/** Semantic adapter over the existing service-owned T032 session; no transport is created here. */
class ServiceOwnedLooiT032SemanticPort(
    private val runtime: () -> ProviderRuntimeSnapshot,
    private val state: () -> T032AcceptanceState,
    private val drive: (ManualDriveCommand) -> T032VerifiedCommandResult,
    private val head: (Float) -> T032VerifiedCommandResult,
    private val light: (Float) -> T032VerifiedCommandResult,
    private val nowMs: () -> Long,
) : LooiT032SemanticPort {
    private var sequence = 0L

    override fun snapshot(): LooiT032SemanticSnapshot = state().let {
        LooiT032SemanticSnapshot(
            runtime = runtime(),
            connectionEstablished = it.prerequisiteReady,
            lowerContactKnown = it.sensorStateKnown,
            lowerContactFresh = it.streamFresh,
            lowerContactSafe = it.lowerContactSafe,
        )
    }

    @Synchronized
    override fun command(command: LooiT032SemanticCommand): LooiT032SemanticCommandResult = map(
        when (command) {
            LooiT032SemanticCommand.Stop -> drive(
                ManualDriveCommand.Stop(++sequence, ManualStopReason.RELEASE, nowMs()),
            )
            is LooiT032SemanticCommand.Move -> drive(
                ManualDriveCommand.Refresh(++sequence, command.direction.manual(), nowMs()),
            )
            is LooiT032SemanticCommand.Head -> head(command.position.normalized)
            is LooiT032SemanticCommand.Light -> light(if (command.enabled) 1f else 0f)
        },
    )

    private fun map(result: T032VerifiedCommandResult): LooiT032SemanticCommandResult = when (result.disposition) {
        T032VerifiedDisposition.ACCEPTED -> LooiT032SemanticCommandResult.Accepted
        T032VerifiedDisposition.SAFETY_REJECTED ->
            LooiT032SemanticCommandResult.SafetyRejected("safety.rejected")
        T032VerifiedDisposition.TRANSPORT_REJECTED ->
            LooiT032SemanticCommandResult.TransportFailure("transport.queue.rejected")
        T032VerifiedDisposition.UNAVAILABLE ->
            LooiT032SemanticCommandResult.Unavailable("provider.unavailable")
    }
}

private fun LooiSemanticDirection.manual(): ManualDriveDirection = when (this) {
    LooiSemanticDirection.FORWARD -> ManualDriveDirection.FORWARD
    LooiSemanticDirection.BACKWARD -> ManualDriveDirection.BACKWARD
    LooiSemanticDirection.LEFT -> ManualDriveDirection.LEFT
    LooiSemanticDirection.RIGHT -> ManualDriveDirection.RIGHT
}

private val LooiSemanticHeadPosition.normalized: Float
    get() = when (this) {
        LooiSemanticHeadPosition.UP -> -1f
        LooiSemanticHeadPosition.CENTER -> 0f
        LooiSemanticHeadPosition.DOWN -> 1f
    }
