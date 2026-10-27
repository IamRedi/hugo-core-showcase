package io.redi.robot.body.looi.firstlife

import io.redi.robot.body.looi.capability.*
import io.redi.robot.body.looi.manifest.ExistingLooiImplementationBoundary
import io.redi.robot.capability.api.*
import io.redi.robot.capability.provider.*
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LooiT032VerifiedRuntimeDelegateTest {
    @Test
    fun `semantic direction head light and stop map only to project owned T032 commands`() {
        val port = FakePort()
        val delegate = LooiT032VerifiedRuntimeDelegate(port) { time(20) }

        delegate.execute(invocation(
            ExistingLooiImplementationBoundary.EXISTING_DRIVE_SAFETY_PATH,
            data(LooiT032VerifiedRuntimeDelegate.MOVE_CONTRACT, "direction", text("left")),
        ))
        delegate.execute(invocation(
            ExistingLooiImplementationBoundary.EXISTING_HEAD_SAFETY_PATH,
            data(LooiT032VerifiedRuntimeDelegate.HEAD_CONTRACT, "position", text("center")),
        ))
        delegate.execute(invocation(
            ExistingLooiImplementationBoundary.EXISTING_LIGHT_SAFETY_PATH,
            data(LooiT032VerifiedRuntimeDelegate.LIGHT_CONTRACT, "enabled", CapabilityArgumentValue.Flag(true)),
        ))
        delegate.execute(invocation(
            ExistingLooiImplementationBoundary.EXISTING_DRIVE_SAFETY_PATH, null, LooiRuntimePriority.STOP,
        ))

        assertEquals(
            listOf(
                LooiT032SemanticCommand.Move(LooiSemanticDirection.LEFT),
                LooiT032SemanticCommand.Head(LooiSemanticHeadPosition.CENTER),
                LooiT032SemanticCommand.Light(true),
                LooiT032SemanticCommand.Stop,
            ),
            port.commands,
        )
    }

    @Test
    fun `lower contact and connection return normalized semantic evidence`() {
        val port = FakePort()
        val delegate = LooiT032VerifiedRuntimeDelegate(port) { time(20) }

        val contact = delegate.execute(invocation(
            ExistingLooiImplementationBoundary.EXISTING_LOWER_CONTACT_SAFETY_OBSERVATION, null,
        )) as LooiVerifiedRuntimeOutcome.Completed
        val connection = delegate.execute(invocation(
            ExistingLooiImplementationBoundary.EXISTING_CONNECTION_STATE, null,
        )) as LooiVerifiedRuntimeOutcome.Completed

        assertEquals("hugo.contract.lower_contact_status_output@1.0", contact.output.contract.wireValue)
        assertEquals(true, flag(contact.output, "known"))
        assertEquals(true, flag(contact.output, "fresh"))
        assertEquals(true, flag(contact.output, "safe"))
        assertEquals(true, flag(connection.output, "connected"))
        assertTrue(port.commands.isEmpty())
    }

    @Test
    fun `invalid semantic input and stale generation fail without command`() {
        val port = FakePort()
        val delegate = LooiT032VerifiedRuntimeDelegate(port) { time(20) }
        val invalid = delegate.execute(invocation(
            ExistingLooiImplementationBoundary.EXISTING_DRIVE_SAFETY_PATH,
            data(LooiT032VerifiedRuntimeDelegate.MOVE_CONTRACT, "direction", text("diagonal")),
        ))
        val stale = delegate.execute(invocation(
            ExistingLooiImplementationBoundary.EXISTING_DRIVE_SAFETY_PATH,
            data(LooiT032VerifiedRuntimeDelegate.MOVE_CONTRACT, "direction", text("left")),
            generation = 2,
        ))

        assertTrue(invalid is LooiVerifiedRuntimeOutcome.ProviderFailure)
        assertTrue(stale is LooiVerifiedRuntimeOutcome.Unavailable)
        assertTrue(port.commands.isEmpty())
    }

    @Test
    fun `safety rejection is preserved and cancellation uses neutral stop`() {
        val port = FakePort().apply {
            next = LooiT032SemanticCommandResult.SafetyRejected("safety.rejected")
        }
        val delegate = LooiT032VerifiedRuntimeDelegate(port) { time(20) }
        val rejected = delegate.execute(invocation(
            ExistingLooiImplementationBoundary.EXISTING_DRIVE_SAFETY_PATH,
            data(LooiT032VerifiedRuntimeDelegate.MOVE_CONTRACT, "direction", text("right")),
        ))
        port.next = LooiT032SemanticCommandResult.Accepted
        val cancelled = delegate.cancel(invocation(
            ExistingLooiImplementationBoundary.EXISTING_DRIVE_SAFETY_PATH,
            data(LooiT032VerifiedRuntimeDelegate.MOVE_CONTRACT, "direction", text("right")),
        ))

        assertTrue(rejected is LooiVerifiedRuntimeOutcome.SafetyRejected)
        assertTrue(cancelled is LooiVerifiedRuntimeOutcome.Cancelled)
        assertEquals(LooiT032SemanticCommand.Stop, port.commands.last())
        assertFalse((cancelled as LooiVerifiedRuntimeOutcome.Cancelled).reason.value.contains("gatt"))
    }

    private inner class FakePort : LooiT032SemanticPort {
        val commands = mutableListOf<LooiT032SemanticCommand>()
        var next: LooiT032SemanticCommandResult = LooiT032SemanticCommandResult.Accepted
        override fun snapshot() = LooiT032SemanticSnapshot(
            runtime = provider(ProviderRuntimeSnapshot.create(
                provider(ProviderGeneration.create(1)), ProviderRuntimeAvailability.AVAILABLE, time(10), null,
            )),
            connectionEstablished = true,
            lowerContactKnown = true,
            lowerContactFresh = true,
            lowerContactSafe = true,
        )
        override fun command(command: LooiT032SemanticCommand): LooiT032SemanticCommandResult {
            commands += command
            return next
        }
    }

    private fun invocation(
        boundary: ExistingLooiImplementationBoundary,
        input: CapabilityData?,
        priority: LooiRuntimePriority = LooiRuntimePriority.NORMAL,
        generation: Long = 1,
    ): LooiRuntimeInvocation = LooiRuntimeInvocation(
        FacadeCorrelation(
            CapabilityRequestId.fromUuid(UUID.randomUUID()), reference("mobility.move@1.0"),
            CapabilityProviderTarget.AnyCompatible, envelope(CapabilityExecutionContext.create(generation, 1)),
            CapabilityAuditId.fromUuid(UUID.randomUUID()), provider(ProviderGeneration.create(generation)),
        ),
        boundary,
        input,
        priority,
    )

    private fun data(contract: String, key: String, value: CapabilityArgumentValue): CapabilityData = envelope(
        CapabilityData.create(
            descriptor(CapabilityContractReference.parse(contract)),
            mapOf(envelope(CapabilityArgumentName.parse(key)) to value),
        ),
    )
    private fun text(value: String) = envelope(CapabilityArgumentValue.Text.create(value))
    private fun flag(data: CapabilityData, key: String) =
        (data.values[envelope(CapabilityArgumentName.parse(key))] as CapabilityArgumentValue.Flag).value
    private fun reference(raw: String) = identity(CapabilityReference.parse(raw))
    private fun time(value: Long) = envelope(MonotonicTimestampMs.create(value))
    private fun <T> envelope(result: CapabilityEnvelopeResult<T>) = (result as CapabilityEnvelopeResult.Success).value
    private fun <T> descriptor(result: CapabilityDescriptorResult<T>) = (result as CapabilityDescriptorResult.Success).value
    private fun <T> identity(result: CapabilityIdentityResult<T>) = (result as CapabilityIdentityResult.Success).value
    private fun <T> provider(result: CapabilityProviderPortResult<T>) = (result as CapabilityProviderPortResult.Success).value
}
