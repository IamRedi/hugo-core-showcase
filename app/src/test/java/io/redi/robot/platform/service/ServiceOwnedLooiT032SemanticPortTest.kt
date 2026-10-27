package io.redi.robot.platform.service

import io.redi.robot.body.looi.firstlife.*
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.*
import io.redi.robot.feature.manualcontrol.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceOwnedLooiT032SemanticPortTest {
    @Test
    fun `semantic commands reuse manual commands without exposing transport`() {
        val drives = mutableListOf<ManualDriveCommand>()
        val heads = mutableListOf<Float>()
        val lights = mutableListOf<Float>()
        val port = port(drives, heads, lights)

        port.command(LooiT032SemanticCommand.Move(LooiSemanticDirection.RIGHT))
        port.command(LooiT032SemanticCommand.Stop)
        port.command(LooiT032SemanticCommand.Head(LooiSemanticHeadPosition.CENTER))
        port.command(LooiT032SemanticCommand.Light(true))

        assertEquals(ManualDriveDirection.RIGHT, (drives[0] as ManualDriveCommand.Refresh).direction)
        assertTrue(drives[1] is ManualDriveCommand.Stop)
        assertEquals(0f, heads.single())
        assertEquals(1f, lights.single())
        assertEquals(true, port.snapshot().lowerContactSafe)
    }

    @Test
    fun `typed T032 rejection remains safety rejection`() {
        val rejected = T032VerifiedCommandResult(
            T032AcceptanceState(), T032VerifiedDisposition.SAFETY_REJECTED,
        )
        val port = ServiceOwnedLooiT032SemanticPort(
            runtime = ::runtime,
            state = { T032AcceptanceState() },
            drive = { rejected }, head = { rejected }, light = { rejected }, nowMs = { 10 },
        )

        assertTrue(port.command(LooiT032SemanticCommand.Stop) is LooiT032SemanticCommandResult.SafetyRejected)
    }

    private fun port(
        drives: MutableList<ManualDriveCommand>,
        heads: MutableList<Float>,
        lights: MutableList<Float>,
    ) = ServiceOwnedLooiT032SemanticPort(
        runtime = ::runtime,
        state = { T032AcceptanceState(prerequisiteReady = true, sensorStateKnown = true, streamFresh = true, lowerContactSafe = true) },
        drive = { command ->
            drives += command
            T032VerifiedCommandResult(T032AcceptanceState(), T032VerifiedDisposition.ACCEPTED)
        },
        head = { value ->
            heads += value
            T032VerifiedCommandResult(T032AcceptanceState(), T032VerifiedDisposition.ACCEPTED)
        },
        light = { value ->
            lights += value
            T032VerifiedCommandResult(T032AcceptanceState(), T032VerifiedDisposition.ACCEPTED)
        },
        nowMs = { 10 },
    )

    private fun runtime(): ProviderRuntimeSnapshot = provider(ProviderRuntimeSnapshot.create(
        provider(ProviderGeneration.create(1)), ProviderRuntimeAvailability.AVAILABLE, time(10), null,
    ))
    private fun time(value: Long) = (MonotonicTimestampMs.create(value) as CapabilityEnvelopeResult.Success).value
    private fun <T> provider(result: CapabilityProviderPortResult<T>) = (result as CapabilityProviderPortResult.Success).value
}
