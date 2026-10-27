package io.redi.robot.body.looi.firstlife

import io.redi.robot.body.looi.capability.LooiRuntimeInvocation
import io.redi.robot.body.looi.capability.LooiRuntimePriority
import io.redi.robot.body.looi.capability.LooiVerifiedRuntimeOutcome
import io.redi.robot.body.looi.capability.LooiVerifiedRuntimePort
import io.redi.robot.body.looi.manifest.ExistingLooiImplementationBoundary
import io.redi.robot.capability.api.CapabilityArgumentName
import io.redi.robot.capability.api.CapabilityArgumentValue
import io.redi.robot.capability.api.CapabilityContractReference
import io.redi.robot.capability.api.CapabilityData
import io.redi.robot.capability.api.CapabilityDescriptorResult
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.CapabilityReasonCode
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderRuntimeSnapshot

enum class LooiSemanticDirection { FORWARD, BACKWARD, LEFT, RIGHT }
enum class LooiSemanticHeadPosition { UP, CENTER, DOWN }

sealed interface LooiT032SemanticCommand {
    data object Stop : LooiT032SemanticCommand
    data class Move(val direction: LooiSemanticDirection) : LooiT032SemanticCommand
    data class Head(val position: LooiSemanticHeadPosition) : LooiT032SemanticCommand
    data class Light(val enabled: Boolean) : LooiT032SemanticCommand
}

sealed interface LooiT032SemanticCommandResult {
    data object Accepted : LooiT032SemanticCommandResult
    data class SafetyRejected(val reason: String) : LooiT032SemanticCommandResult
    data class Unavailable(val reason: String) : LooiT032SemanticCommandResult
    data class TransportFailure(val reason: String) : LooiT032SemanticCommandResult
}

data class LooiT032SemanticSnapshot(
    val runtime: ProviderRuntimeSnapshot,
    val connectionEstablished: Boolean,
    val lowerContactKnown: Boolean,
    val lowerContactFresh: Boolean,
    val lowerContactSafe: Boolean?,
)

/** Project-owned semantic boundary; it contains no direction bytes, endpoint, UUID, or Android type. */
interface LooiT032SemanticPort {
    fun snapshot(): LooiT032SemanticSnapshot
    fun command(command: LooiT032SemanticCommand): LooiT032SemanticCommandResult
}

/** Adapter-side H018 delegate into the sole service-owned T032 command path. */
class LooiT032VerifiedRuntimeDelegate(
    private val t032: LooiT032SemanticPort,
    private val clock: () -> MonotonicTimestampMs,
) : LooiVerifiedRuntimePort {
    override fun runtimeSnapshot(): ProviderRuntimeSnapshot = t032.snapshot().runtime

    override fun execute(invocation: LooiRuntimeInvocation): LooiVerifiedRuntimeOutcome {
        if (invocation.correlation.providerGeneration != runtimeSnapshot().generation) {
            return unavailable(invocation, "provider.generation.stale")
        }
        return when (invocation.boundary) {
            ExistingLooiImplementationBoundary.EXISTING_DRIVE_SAFETY_PATH -> executeDrive(invocation)
            ExistingLooiImplementationBoundary.EXISTING_HEAD_SAFETY_PATH -> executeHead(invocation)
            ExistingLooiImplementationBoundary.EXISTING_LIGHT_SAFETY_PATH -> executeLight(invocation)
            ExistingLooiImplementationBoundary.EXISTING_LOWER_CONTACT_SAFETY_OBSERVATION -> lowerContact(invocation)
            ExistingLooiImplementationBoundary.EXISTING_CONNECTION_STATE -> connection(invocation)
        }
    }

    override fun cancel(invocation: LooiRuntimeInvocation): LooiVerifiedRuntimeOutcome =
        map(invocation, t032.command(LooiT032SemanticCommand.Stop), cancelled = true)

    private fun executeDrive(invocation: LooiRuntimeInvocation): LooiVerifiedRuntimeOutcome {
        if (invocation.priority == LooiRuntimePriority.STOP) {
            return map(invocation, t032.command(LooiT032SemanticCommand.Stop))
        }
        val direction = text(invocation, MOVE_CONTRACT, DIRECTION)?.let {
            runCatching { LooiSemanticDirection.valueOf(it.uppercase()) }.getOrNull()
        } ?: return providerFailure(invocation, "semantic.input.invalid")
        return map(invocation, t032.command(LooiT032SemanticCommand.Move(direction)))
    }

    private fun executeHead(invocation: LooiRuntimeInvocation): LooiVerifiedRuntimeOutcome {
        val position = text(invocation, HEAD_CONTRACT, POSITION)?.let {
            runCatching { LooiSemanticHeadPosition.valueOf(it.uppercase()) }.getOrNull()
        } ?: return providerFailure(invocation, "semantic.input.invalid")
        return map(invocation, t032.command(LooiT032SemanticCommand.Head(position)))
    }

    private fun executeLight(invocation: LooiRuntimeInvocation): LooiVerifiedRuntimeOutcome {
        val data = invocation.input?.takeIf { it.contract == contract(LIGHT_CONTRACT) }
            ?: return providerFailure(invocation, "semantic.input.invalid")
        val enabled = (data.values[argument(ENABLED)] as? CapabilityArgumentValue.Flag)?.value
            ?: return providerFailure(invocation, "semantic.input.invalid")
        if (data.values.size != 1) return providerFailure(invocation, "semantic.input.invalid")
        return map(invocation, t032.command(LooiT032SemanticCommand.Light(enabled)))
    }

    private fun lowerContact(invocation: LooiRuntimeInvocation): LooiVerifiedRuntimeOutcome {
        val value = t032.snapshot()
        return completed(invocation, data(
            LOWER_CONTACT_OUTPUT,
            mapOf(
                KNOWN to CapabilityArgumentValue.Flag(value.lowerContactKnown),
                FRESH to CapabilityArgumentValue.Flag(value.lowerContactFresh),
                SAFE to CapabilityArgumentValue.Flag(value.lowerContactSafe == true),
            ),
        ))
    }

    private fun connection(invocation: LooiRuntimeInvocation): LooiVerifiedRuntimeOutcome {
        val value = t032.snapshot()
        return completed(invocation, data(
            CONNECTION_OUTPUT,
            mapOf(CONNECTED to CapabilityArgumentValue.Flag(value.connectionEstablished)),
        ))
    }

    private fun text(invocation: LooiRuntimeInvocation, expectedContract: String, key: String): String? {
        val data = invocation.input?.takeIf { it.contract == contract(expectedContract) } ?: return null
        if (data.values.size != 1) return null
        return (data.values[argument(key)] as? CapabilityArgumentValue.Text)?.value
    }

    private fun map(
        invocation: LooiRuntimeInvocation,
        result: LooiT032SemanticCommandResult,
        cancelled: Boolean = false,
    ): LooiVerifiedRuntimeOutcome = when (result) {
        LooiT032SemanticCommandResult.Accepted -> if (cancelled) {
            LooiVerifiedRuntimeOutcome.Cancelled(invocation.correlation, clock(), reason("runtime.cancelled"))
        } else LooiVerifiedRuntimeOutcome.Accepted(invocation.correlation, clock())
        is LooiT032SemanticCommandResult.SafetyRejected ->
            LooiVerifiedRuntimeOutcome.SafetyRejected(invocation.correlation, clock(), reason(result.reason))
        is LooiT032SemanticCommandResult.Unavailable -> unavailable(invocation, result.reason)
        is LooiT032SemanticCommandResult.TransportFailure ->
            LooiVerifiedRuntimeOutcome.TransportFailure(invocation.correlation, clock(), reason(result.reason))
    }

    private fun completed(invocation: LooiRuntimeInvocation, output: CapabilityData) =
        LooiVerifiedRuntimeOutcome.Completed(invocation.correlation, clock(), output)

    private fun unavailable(invocation: LooiRuntimeInvocation, value: String) =
        LooiVerifiedRuntimeOutcome.Unavailable(invocation.correlation, clock(), reason(value))

    private fun providerFailure(invocation: LooiRuntimeInvocation, value: String) =
        LooiVerifiedRuntimeOutcome.ProviderFailure(invocation.correlation, clock(), reason(value))

    private fun data(contract: String, values: Map<String, CapabilityArgumentValue>): CapabilityData = envelope(
        CapabilityData.create(contract(contract), values.mapKeys { argument(it.key) }),
    )

    private fun contract(raw: String): CapabilityContractReference = descriptor(CapabilityContractReference.parse(raw))
    private fun argument(raw: String): CapabilityArgumentName = envelope(CapabilityArgumentName.parse(raw))
    private fun reason(raw: String): CapabilityReasonCode = envelope(CapabilityReasonCode.parse(raw))

    private fun <T> descriptor(result: CapabilityDescriptorResult<T>): T = when (result) {
        is CapabilityDescriptorResult.Success -> result.value
        is CapabilityDescriptorResult.Failure -> error(result)
    }

    private fun <T> envelope(result: CapabilityEnvelopeResult<T>): T = when (result) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> error(result)
    }

    companion object {
        const val MOVE_CONTRACT = "hugo.contract.mobility_move_input@1.0"
        const val HEAD_CONTRACT = "hugo.contract.body_head_position_input@1.0"
        const val LIGHT_CONTRACT = "hugo.contract.body_light_input@1.0"
        const val LOWER_CONTACT_OUTPUT = "hugo.contract.lower_contact_status_output@1.0"
        const val CONNECTION_OUTPUT = "hugo.contract.provider_connection_status_output@1.0"
        const val DIRECTION = "direction"
        const val POSITION = "position"
        const val ENABLED = "enabled"
        const val KNOWN = "known"
        const val FRESH = "fresh"
        const val SAFE = "safe"
        const val CONNECTED = "connected"
    }
}
