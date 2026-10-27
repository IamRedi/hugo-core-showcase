package io.redi.robot.conversation.context

import io.redi.robot.context.api.ContextKey
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.runtime.ContextSnapshotResolution
import io.redi.robot.gateway.contract.GatewayOperation
import io.redi.robot.gateway.contract.GatewayRequestId
import io.redi.robot.gateway.contract.GatewayContractResult
import io.redi.robot.gateway.contract.GatewayText
import io.redi.robot.skills.api.SkillReference
import java.util.Collections
import java.util.UUID

@JvmInline
value class ConversationContextProvenanceId private constructor(val value: String) {
    override fun toString(): String = "context-provenance:[redacted]"

    companion object {
        fun fromUuid(uuid: UUID): ConversationContextProvenanceId = ConversationContextProvenanceId(uuid.toString())

        fun parse(raw: String): ConversationContextResult<ConversationContextProvenanceId> = if (
            runCatching { UUID.fromString(raw).toString() }.getOrNull() == raw
        ) {
            ConversationContextResult.Success(ConversationContextProvenanceId(raw))
        } else {
            contextFailure(ConversationContextError.INVALID_PROVENANCE_ID)
        }
    }
}

class ConversationContextPrivacyPolicy private constructor(
    allowed: Set<ContextPrivacyClassification>,
) {
    val allowed: Set<ContextPrivacyClassification> = Collections.unmodifiableSet(allowed.toSet())

    fun permits(value: ContextPrivacyClassification): Boolean = value in allowed

    companion object {
        val FORBIDDEN = setOf(
            ContextPrivacyClassification.SENSITIVE,
            ContextPrivacyClassification.RESTRICTED,
        )

        fun create(
            allowed: Set<ContextPrivacyClassification>,
        ): ConversationContextResult<ConversationContextPrivacyPolicy> = when {
            allowed.isEmpty() -> contextFailure(ConversationContextError.EMPTY_PRIVACY_POLICY)
            allowed.any { it in FORBIDDEN } -> contextFailure(ConversationContextError.FORBIDDEN_PRIVACY_CLASS)
            else -> ConversationContextResult.Success(ConversationContextPrivacyPolicy(allowed))
        }
    }
}

class ConversationContextBudget private constructor(
    val maximumItems: Int,
    val maximumInputUnits: Int,
    val maximumUtf8Bytes: Int,
) {
    companion object {
        const val HARD_MAXIMUM_ITEMS = 64
        const val HARD_MAXIMUM_INPUT_UNITS = GatewayText.MAX_CODE_POINTS
        const val HARD_MAXIMUM_UTF8_BYTES = GatewayText.MAX_CODE_POINTS * 4

        fun create(
            maximumItems: Int,
            maximumInputUnits: Int,
            maximumUtf8Bytes: Int,
        ): ConversationContextResult<ConversationContextBudget> = when {
            maximumItems !in 1..HARD_MAXIMUM_ITEMS -> contextFailure(ConversationContextError.INVALID_ITEM_BUDGET)
            maximumInputUnits !in 256..HARD_MAXIMUM_INPUT_UNITS ->
                contextFailure(ConversationContextError.INVALID_INPUT_UNIT_BUDGET)
            maximumUtf8Bytes !in 512..HARD_MAXIMUM_UTF8_BYTES ->
                contextFailure(ConversationContextError.INVALID_UTF8_BUDGET)
            else -> ConversationContextResult.Success(
                ConversationContextBudget(maximumItems, maximumInputUnits, maximumUtf8Bytes),
            )
        }
    }
}

data class ConversationContextText(
    val provenanceId: ConversationContextProvenanceId,
    val text: GatewayText,
    val privacyClassification: ContextPrivacyClassification,
) {
    override fun toString(): String = "ConversationContextText(provenance=redacted, text=redacted, " +
        "privacy=$privacyClassification)"
}

data class ConversationMemoryContextItem(
    val provenanceId: ConversationContextProvenanceId,
    val text: GatewayText,
    val privacyClassification: ContextPrivacyClassification,
) {
    override fun toString(): String = "ConversationMemoryContextItem(provenance=redacted, content=redacted, " +
        "privacy=$privacyClassification)"
}

data class ConversationMemorySelectionRequest(
    val maximumCandidates: Int,
    val allowedPrivacyClassifications: Set<ContextPrivacyClassification>,
)

sealed interface ConversationMemorySelectionResult {
    class Available(items: List<ConversationMemoryContextItem>) : ConversationMemorySelectionResult {
        val items: List<ConversationMemoryContextItem> = Collections.unmodifiableList(items.toList())
    }

    data object Unavailable : ConversationMemorySelectionResult
}

fun interface ConversationMemoryContextPort {
    fun select(request: ConversationMemorySelectionRequest): ConversationMemorySelectionResult
}

object EmptyConversationMemoryContextPort : ConversationMemoryContextPort {
    override fun select(request: ConversationMemorySelectionRequest): ConversationMemorySelectionResult =
        ConversationMemorySelectionResult.Available(emptyList())
}

class ConversationSnapshotContextItem internal constructor(
    val key: ContextKey,
    val resolution: ContextSnapshotResolution,
    val privacyClassification: ContextPrivacyClassification,
    provenanceIds: List<String>,
) {
    val provenanceIds: List<String> = Collections.unmodifiableList(provenanceIds.toList())
}

class ConversationToolContextSchema internal constructor(
    val reference: SkillReference,
    val capability: String,
    val actor: String,
    val skillClass: String,
    val inputContract: String?,
    fixedInput: Map<String, String>,
    val outputContract: String?,
    val maximumDurationMs: Long,
    val cancellationRequired: Boolean,
) {
    val fixedInput: Map<String, String> = Collections.unmodifiableMap(fixedInput.toSortedMap())
}

class ConversationContextPacket internal constructor(
    val requestId: GatewayRequestId,
    val operation: GatewayOperation,
    val budget: ConversationContextBudget,
    val currentTurn: ConversationContextText,
    val summary: ConversationContextText?,
    snapshotItems: List<ConversationSnapshotContextItem>,
    memories: List<ConversationMemoryContextItem>,
    tools: List<ConversationToolContextSchema>,
) {
    val snapshotItems: List<ConversationSnapshotContextItem> = Collections.unmodifiableList(snapshotItems.toList())
    val memories: List<ConversationMemoryContextItem> = Collections.unmodifiableList(memories.toList())
    val tools: List<ConversationToolContextSchema> = Collections.unmodifiableList(tools.toList())
    val itemCount: Int = 1 + (if (summary == null) 0 else 1) + snapshotItems.size + memories.size + tools.size

    val grantsTrust = false
    val grantsAuthorization = false
    val approvesSafety = false
    val executesSkill = false
    val executesCapability = false

    fun canonicalDocument(): String = ConversationContextCodec.encode(this)
    fun canonicalInputUnits(): Int = canonicalDocument().let { it.codePointCount(0, it.length) }
    fun canonicalUtf8Bytes(): Int = canonicalDocument().toByteArray(Charsets.UTF_8).size
    fun asGatewayText(): GatewayContractResult<GatewayText> = GatewayText.create(canonicalDocument())

    override fun toString(): String = "ConversationContextPacket(request=redacted, operation=$operation, " +
        "items=$itemCount, snapshotItems=${snapshotItems.size}, memories=${memories.size}, " +
        "tools=${tools.size}, content=[redacted])"

    companion object {
        const val SCHEMA_VERSION = "1.0"
    }
}

enum class ConversationContextOmissionReason {
    SUMMARY_PRIVACY,
    SNAPSHOT_PRIVACY,
    MEMORY_PRIVACY,
    MEMORY_UNAVAILABLE,
    ITEM_BUDGET,
    INPUT_UNIT_BUDGET,
    UTF8_BUDGET,
}

class ConversationContextBuildEvidence internal constructor(counts: Map<ConversationContextOmissionReason, Int>) {
    val omissionCounts: Map<ConversationContextOmissionReason, Int> = Collections.unmodifiableMap(
        ConversationContextOmissionReason.entries.associateWith { counts[it] ?: 0 },
    )

    override fun toString(): String = "ConversationContextBuildEvidence(omissions=$omissionCounts, content=[redacted])"
}

sealed interface ConversationContextBuildResult {
    data class Built(
        val packet: ConversationContextPacket,
        val evidence: ConversationContextBuildEvidence,
    ) : ConversationContextBuildResult

    data class Rejected(val error: ConversationContextError) : ConversationContextBuildResult
}

enum class ConversationContextError {
    INVALID_PROVENANCE_ID,
    EMPTY_PRIVACY_POLICY,
    FORBIDDEN_PRIVACY_CLASS,
    INVALID_ITEM_BUDGET,
    INVALID_INPUT_UNIT_BUDGET,
    INVALID_UTF8_BUDGET,
    REQUEST_HAS_NO_CURRENT_TURN,
    CURRENT_TURN_PRIVACY_FORBIDDEN,
    DUPLICATE_TOOL_DEFINITION,
    TOOL_DEFINITION_MISMATCH,
    TOO_MANY_MEMORY_CANDIDATES,
    DUPLICATE_MEMORY_PROVENANCE,
    DUPLICATE_CONTEXT_PROVENANCE,
    MANDATORY_CONTENT_EXCEEDS_BUDGET,
}

sealed interface ConversationContextResult<out T> {
    data class Success<T>(val value: T) : ConversationContextResult<T>
    data class Failure(val error: ConversationContextError) : ConversationContextResult<Nothing>
}

internal fun <T> contextFailure(error: ConversationContextError): ConversationContextResult<T> =
    ConversationContextResult.Failure(error)
