package io.redi.robot.conversation.context

import io.redi.robot.capability.api.CapabilityArgumentValue
import io.redi.robot.context.runtime.ContextSnapshot
import io.redi.robot.gateway.contract.GatewayRequest
import io.redi.robot.skills.api.SkillDefinition

class ConversationContextBuilder(
    private val memories: ConversationMemoryContextPort = EmptyConversationMemoryContextPort,
) {
    companion object {
        const val MAXIMUM_MEMORY_CANDIDATES = 64
    }

    @Suppress("LongParameterList")
    fun build(
        request: GatewayRequest,
        currentTurnProvenanceId: ConversationContextProvenanceId,
        currentTurnPrivacy: io.redi.robot.context.api.ContextPrivacyClassification,
        summary: ConversationContextText?,
        snapshot: ContextSnapshot,
        privacyPolicy: ConversationContextPrivacyPolicy,
        budget: ConversationContextBudget,
        toolDefinitions: List<SkillDefinition>,
    ): ConversationContextBuildResult {
        val prompt = request.prompt
            ?: return ConversationContextBuildResult.Rejected(ConversationContextError.REQUEST_HAS_NO_CURRENT_TURN)
        if (!privacyPolicy.permits(currentTurnPrivacy)) {
            return ConversationContextBuildResult.Rejected(ConversationContextError.CURRENT_TURN_PRIVACY_FORBIDDEN)
        }
        if (toolDefinitions.map { it.reference }.distinct().size != toolDefinitions.size) {
            return ConversationContextBuildResult.Rejected(ConversationContextError.DUPLICATE_TOOL_DEFINITION)
        }
        if (toolDefinitions.map { it.reference }.toSet() != request.declaredSkills.toSet()) {
            return ConversationContextBuildResult.Rejected(ConversationContextError.TOOL_DEFINITION_MISMATCH)
        }

        val memoryResult = runCatching {
            memories.select(
                ConversationMemorySelectionRequest(MAXIMUM_MEMORY_CANDIDATES, privacyPolicy.allowed),
            )
        }.getOrDefault(ConversationMemorySelectionResult.Unavailable)
        val memoryCandidates = when (memoryResult) {
            is ConversationMemorySelectionResult.Available -> memoryResult.items
            ConversationMemorySelectionResult.Unavailable -> emptyList()
        }
        if (memoryCandidates.size > MAXIMUM_MEMORY_CANDIDATES) {
            return ConversationContextBuildResult.Rejected(ConversationContextError.TOO_MANY_MEMORY_CANDIDATES)
        }
        if (memoryCandidates.map { it.provenanceId }.distinct().size != memoryCandidates.size) {
            return ConversationContextBuildResult.Rejected(ConversationContextError.DUPLICATE_MEMORY_PROVENANCE)
        }
        val contextProvenance = listOf(currentTurnProvenanceId) +
            listOfNotNull(summary?.provenanceId) + memoryCandidates.map { it.provenanceId }
        if (contextProvenance.distinct().size != contextProvenance.size) {
            return ConversationContextBuildResult.Rejected(ConversationContextError.DUPLICATE_CONTEXT_PROVENANCE)
        }

        val counts = mutableMapOf<ConversationContextOmissionReason, Int>()
        fun omit(reason: ConversationContextOmissionReason) {
            counts[reason] = (counts[reason] ?: 0) + 1
        }
        if (memoryResult is ConversationMemorySelectionResult.Unavailable) {
            omit(ConversationContextOmissionReason.MEMORY_UNAVAILABLE)
        }

        val current = ConversationContextText(currentTurnProvenanceId, prompt, currentTurnPrivacy)
        val tools = toolDefinitions.sortedBy { it.reference }.map(::toolSchema)
        var packet = ConversationContextPacket(
            request.requestId,
            request.operation,
            budget,
            current,
            null,
            emptyList(),
            emptyList(),
            tools,
        )
        if (!fits(packet, budget)) {
            return ConversationContextBuildResult.Rejected(ConversationContextError.MANDATORY_CONTENT_EXCEEDS_BUDGET)
        }

        if (summary != null) {
            if (!privacyPolicy.permits(summary.privacyClassification)) {
                omit(ConversationContextOmissionReason.SUMMARY_PRIVACY)
            } else {
                packet = attempt(packet.copyWith(summary = summary), packet, budget, ::omit)
            }
        }

        snapshot.items.forEach { item ->
            if (!privacyPolicy.permits(item.effectivePrivacyClassification)) {
                omit(ConversationContextOmissionReason.SNAPSHOT_PRIVACY)
            } else {
                val projected = ConversationSnapshotContextItem(
                    item.key,
                    item.resolution,
                    item.effectivePrivacyClassification,
                    item.evidence.map { it.observationId.value }.sorted(),
                )
                packet = attempt(
                    packet.copyWith(snapshotItems = packet.snapshotItems + projected),
                    packet,
                    budget,
                    ::omit,
                )
            }
        }

        memoryCandidates.sortedBy { it.provenanceId.value }.forEach { item ->
            if (!privacyPolicy.permits(item.privacyClassification)) {
                omit(ConversationContextOmissionReason.MEMORY_PRIVACY)
            } else {
                packet = attempt(
                    packet.copyWith(memories = packet.memories + item),
                    packet,
                    budget,
                    ::omit,
                )
            }
        }
        return ConversationContextBuildResult.Built(packet, ConversationContextBuildEvidence(counts))
    }

    private fun attempt(
        candidate: ConversationContextPacket,
        previous: ConversationContextPacket,
        budget: ConversationContextBudget,
        omit: (ConversationContextOmissionReason) -> Unit,
    ): ConversationContextPacket {
        val reason = when {
            candidate.itemCount > budget.maximumItems -> ConversationContextOmissionReason.ITEM_BUDGET
            candidate.canonicalInputUnits() > budget.maximumInputUnits ->
                ConversationContextOmissionReason.INPUT_UNIT_BUDGET
            candidate.canonicalUtf8Bytes() > budget.maximumUtf8Bytes -> ConversationContextOmissionReason.UTF8_BUDGET
            else -> return candidate
        }
        omit(reason)
        return previous
    }

    private fun fits(packet: ConversationContextPacket, budget: ConversationContextBudget): Boolean =
        packet.itemCount <= budget.maximumItems &&
            packet.canonicalInputUnits() <= budget.maximumInputUnits &&
            packet.canonicalUtf8Bytes() <= budget.maximumUtf8Bytes

    private fun toolSchema(definition: SkillDefinition): ConversationToolContextSchema =
        ConversationToolContextSchema(
            definition.reference,
            definition.capability.wireValue,
            definition.actor.name.lowercase(),
            definition.skillClass.name.lowercase(),
            definition.inputContract?.wireValue,
            definition.fixedInput?.values.orEmpty().entries.associate { (name, value) ->
                name.value to when (value) {
                    is CapabilityArgumentValue.Text -> "text:${value.value}"
                    is CapabilityArgumentValue.Integer -> "integer:${value.value}"
                    is CapabilityArgumentValue.Decimal -> "decimal:${java.lang.Double.toHexString(value.value)}"
                    is CapabilityArgumentValue.Flag -> "flag:${value.value}"
                }
            },
            definition.outputContract?.wireValue,
            definition.maximumDurationMs,
            definition.cancellationRequired,
        )

    private fun ConversationContextPacket.copyWith(
        summary: ConversationContextText? = this.summary,
        snapshotItems: List<ConversationSnapshotContextItem> = this.snapshotItems,
        memories: List<ConversationMemoryContextItem> = this.memories,
    ) = ConversationContextPacket(
        requestId,
        operation,
        budget,
        currentTurn,
        summary,
        snapshotItems,
        memories,
        tools,
    )
}
