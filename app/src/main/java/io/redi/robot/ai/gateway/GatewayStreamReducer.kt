package io.redi.robot.ai.gateway

import io.redi.robot.gateway.contract.GatewayContractResult
import io.redi.robot.gateway.contract.GatewayEvent
import io.redi.robot.gateway.contract.GatewayEventPayload
import io.redi.robot.gateway.contract.GatewayRequest
import io.redi.robot.gateway.contract.GatewayStreamValidator
import io.redi.robot.gateway.contract.GatewayText
import io.redi.robot.gateway.contract.GatewayToolProposal
import io.redi.robot.gateway.contract.GatewayUsage
import java.util.Collections

class GatewayStreamState internal constructor(
    val request: GatewayRequest,
    events: List<GatewayEvent>,
    partialText: List<GatewayText>,
    toolProposals: List<GatewayToolProposal>,
    embeddings: Map<Int, List<Double>>,
    val usage: GatewayUsage?,
    val terminalPayload: GatewayEventPayload?,
    internal val partialTextBytes: Int,
    internal val proposalBytes: Int,
    internal val embeddingDimensions: Int,
) {
    val events: List<GatewayEvent> = Collections.unmodifiableList(events.toList())
    val partialText: List<GatewayText> = Collections.unmodifiableList(partialText.toList())
    val toolProposals: List<GatewayToolProposal> = Collections.unmodifiableList(toolProposals.toList())
    val embeddings: Map<Int, List<Double>> = Collections.unmodifiableMap(
        embeddings.toSortedMap().mapValues { (_, vector) -> Collections.unmodifiableList(vector.toList()) },
    )
    val isTerminal: Boolean = terminalPayload != null

    val grantsTrust: Boolean = false
    val grantsAuthorization: Boolean = false
    val approvesSafety: Boolean = false
    val executesSkill: Boolean = false
    val executesCapability: Boolean = false

    fun redactedTrace(): List<String> = events.map { event ->
        when (event.payload) {
            GatewayEventPayload.Started -> "0:STARTED"
            is GatewayEventPayload.PartialText -> "${event.sequence}:PARTIAL_TEXT:bytes=redacted"
            is GatewayEventPayload.ToolProposal -> "${event.sequence}:TOOL_PROPOSAL:authority=false"
            is GatewayEventPayload.Refusal -> "${event.sequence}:REFUSAL:${event.payload.reason.name}"
            is GatewayEventPayload.Embedding -> "${event.sequence}:EMBEDDING:dimensions=${event.payload.vector.size}"
            is GatewayEventPayload.Usage -> "${event.sequence}:USAGE:${event.payload.usage.evidence.name}"
            is GatewayEventPayload.Completed -> "${event.sequence}:COMPLETED:${event.payload.reason.name}"
            is GatewayEventPayload.Failed ->
                "${event.sequence}:FAILED:${event.payload.code.name}:retryable=${event.payload.retryable}"
            is GatewayEventPayload.Cancelled -> "${event.sequence}:CANCELLED:${event.payload.reason.name}"
            GatewayEventPayload.TimedOut -> "${event.sequence}:TIMED_OUT"
        }
    }

    override fun toString(): String = "GatewayStreamState(request=${request.requestId}, operation=${request.operation}, " +
        "eventCount=${events.size}, textBytes=$partialTextBytes, proposalCount=${toolProposals.size}, " +
        "embeddingDimensions=$embeddingDimensions, terminal=$isTerminal, content=[redacted])"

    companion object {
        fun initial(request: GatewayRequest): GatewayStreamState = GatewayStreamState(
            request,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyMap(),
            null,
            null,
            0,
            0,
            0,
        )
    }
}

enum class GatewayStreamReductionError {
    EVENT_AFTER_TERMINAL,
    EVENT_LIMIT_EXCEEDED,
    CORRELATION_MISMATCH,
    DUPLICATE_EVENT_ID,
    OUT_OF_ORDER_SEQUENCE,
    START_EVENT_REQUIRED,
    DUPLICATE_START,
    DUPLICATE_USAGE,
    TEXT_BUFFER_EXCEEDED,
    TOOL_PROPOSAL_LIMIT_EXCEEDED,
    TOOL_ARGUMENT_BUFFER_EXCEEDED,
    UNDECLARED_SKILL_PROPOSAL,
    INVALID_EMBEDDING_INDEX,
    DUPLICATE_EMBEDDING_INDEX,
    EMBEDDING_BUFFER_EXCEEDED,
    INVALID_TERMINAL_STREAM,
}

sealed interface GatewayStreamReductionResult {
    data class Accepted(val state: GatewayStreamState) : GatewayStreamReductionResult
    data class Rejected(val error: GatewayStreamReductionError) : GatewayStreamReductionResult
}

object GatewayStreamReducer {
    const val MAXIMUM_TEXT_BYTES = 64 * 1024
    const val MAXIMUM_PARTIAL_TEXT_EVENTS = 128
    const val MAXIMUM_TOOL_PROPOSALS = 32
    const val MAXIMUM_TOOL_ARGUMENT_BYTES = 32 * 1024
    const val MAXIMUM_EMBEDDING_DIMENSIONS = 16 * 1024

    fun reduce(state: GatewayStreamState, event: GatewayEvent): GatewayStreamReductionResult {
        if (state.isTerminal) return rejected(GatewayStreamReductionError.EVENT_AFTER_TERMINAL)
        if (state.events.size >= GatewayStreamValidator.MAX_EVENTS) {
            return rejected(GatewayStreamReductionError.EVENT_LIMIT_EXCEEDED)
        }
        if (event.requestId != state.request.requestId || event.operation != state.request.operation) {
            return rejected(GatewayStreamReductionError.CORRELATION_MISMATCH)
        }
        if (state.events.any { it.eventId == event.eventId }) {
            return rejected(GatewayStreamReductionError.DUPLICATE_EVENT_ID)
        }
        if (event.sequence != state.events.size) {
            return rejected(GatewayStreamReductionError.OUT_OF_ORDER_SEQUENCE)
        }
        if (state.events.isEmpty() && event.payload !is GatewayEventPayload.Started) {
            return rejected(GatewayStreamReductionError.START_EVENT_REQUIRED)
        }
        if (state.events.isNotEmpty() && event.payload is GatewayEventPayload.Started) {
            return rejected(GatewayStreamReductionError.DUPLICATE_START)
        }

        val nextText = state.partialText.toMutableList()
        val nextProposals = state.toolProposals.toMutableList()
        val nextEmbeddings = state.embeddings.mapValues { (_, vector) -> vector.toList() }.toMutableMap()
        var nextUsage = state.usage
        var nextTextBytes = state.partialTextBytes
        var nextProposalBytes = state.proposalBytes
        var nextEmbeddingDimensions = state.embeddingDimensions

        when (val payload = event.payload) {
            is GatewayEventPayload.PartialText -> {
                if (nextText.size >= MAXIMUM_PARTIAL_TEXT_EVENTS) {
                    return rejected(GatewayStreamReductionError.TEXT_BUFFER_EXCEEDED)
                }
                nextTextBytes += payload.text.value.toByteArray(Charsets.UTF_8).size
                if (nextTextBytes > MAXIMUM_TEXT_BYTES) {
                    return rejected(GatewayStreamReductionError.TEXT_BUFFER_EXCEEDED)
                }
                nextText += payload.text
            }
            is GatewayEventPayload.ToolProposal -> {
                if (payload.proposal.skill !in state.request.declaredSkills) {
                    return rejected(GatewayStreamReductionError.UNDECLARED_SKILL_PROPOSAL)
                }
                if (nextProposals.size >= MAXIMUM_TOOL_PROPOSALS) {
                    return rejected(GatewayStreamReductionError.TOOL_PROPOSAL_LIMIT_EXCEEDED)
                }
                nextProposalBytes += proposalSize(payload.proposal)
                if (nextProposalBytes > MAXIMUM_TOOL_ARGUMENT_BYTES) {
                    return rejected(GatewayStreamReductionError.TOOL_ARGUMENT_BUFFER_EXCEEDED)
                }
                nextProposals += payload.proposal
            }
            is GatewayEventPayload.Embedding -> {
                if (payload.inputIndex !in state.request.embeddingInputs.indices) {
                    return rejected(GatewayStreamReductionError.INVALID_EMBEDDING_INDEX)
                }
                if (payload.inputIndex in nextEmbeddings) {
                    return rejected(GatewayStreamReductionError.DUPLICATE_EMBEDDING_INDEX)
                }
                nextEmbeddingDimensions += payload.vector.size
                if (nextEmbeddingDimensions > MAXIMUM_EMBEDDING_DIMENSIONS) {
                    return rejected(GatewayStreamReductionError.EMBEDDING_BUFFER_EXCEEDED)
                }
                nextEmbeddings[payload.inputIndex] = payload.vector.toList()
            }
            is GatewayEventPayload.Usage -> {
                if (nextUsage != null) return rejected(GatewayStreamReductionError.DUPLICATE_USAGE)
                nextUsage = payload.usage
            }
            else -> Unit
        }

        val nextEvents = state.events + event
        if (event.isTerminal && GatewayStreamValidator.validate(state.request, nextEvents) !is GatewayContractResult.Success) {
            return rejected(GatewayStreamReductionError.INVALID_TERMINAL_STREAM)
        }
        return GatewayStreamReductionResult.Accepted(
            GatewayStreamState(
                state.request,
                nextEvents,
                nextText,
                nextProposals,
                nextEmbeddings,
                nextUsage,
                event.payload.takeIf { event.isTerminal },
                nextTextBytes,
                nextProposalBytes,
                nextEmbeddingDimensions,
            ),
        )
    }

    private fun proposalSize(proposal: GatewayToolProposal): Int =
        proposal.skill.wireValue.toByteArray(Charsets.UTF_8).size + proposal.arguments.entries.sumOf { (name, value) ->
            name.toByteArray(Charsets.UTF_8).size + value.canonicalValue.toByteArray(Charsets.UTF_8).size
        }

    private fun rejected(error: GatewayStreamReductionError) = GatewayStreamReductionResult.Rejected(error)
}
