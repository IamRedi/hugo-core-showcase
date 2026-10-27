package io.redi.robot.gateway.contract

import io.redi.robot.skills.api.SkillReference
import java.util.UUID

data class GatewaySchemaVersion(val major: Int, val minor: Int) : Comparable<GatewaySchemaVersion> {
    val wireValue: String = "$major.$minor"

    init {
        require(major in 0..999 && minor in 0..999)
    }

    override fun compareTo(other: GatewaySchemaVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor })

    companion object {
        val CURRENT = GatewaySchemaVersion(1, 0)

        fun parse(raw: String): GatewayContractResult<GatewaySchemaVersion> {
            val pieces = raw.split('.')
            if (pieces.size != 2 || pieces.any { it.isEmpty() || it.length > 3 || it.any { c -> !c.isDigit() } }) {
                return GatewayContractResult.Failure(GatewayContractError.INVALID_SCHEMA_VERSION)
            }
            if (pieces.any { it.length > 1 && it.startsWith('0') }) {
                return GatewayContractResult.Failure(GatewayContractError.INVALID_SCHEMA_VERSION)
            }
            val major = pieces[0].toIntOrNull()
            val minor = pieces[1].toIntOrNull()
            return if (major != null && minor != null && major in 0..999 && minor in 0..999) {
                GatewayContractResult.Success(GatewaySchemaVersion(major, minor))
            } else {
                GatewayContractResult.Failure(GatewayContractError.INVALID_SCHEMA_VERSION)
            }
        }
    }
}

enum class GatewaySchemaCompatibility {
    EXACT,
    COMPATIBLE_ADDITIVE_MINOR,
    UNSUPPORTED_OLDER_MINOR,
    INCOMPATIBLE_MAJOR,
}

fun GatewaySchemaVersion.compatibilityWith(required: GatewaySchemaVersion): GatewaySchemaCompatibility = when {
    major != required.major -> GatewaySchemaCompatibility.INCOMPATIBLE_MAJOR
    minor < required.minor -> GatewaySchemaCompatibility.UNSUPPORTED_OLDER_MINOR
    minor == required.minor -> GatewaySchemaCompatibility.EXACT
    else -> GatewaySchemaCompatibility.COMPATIBLE_ADDITIVE_MINOR
}

@JvmInline
value class GatewayRequestId private constructor(val value: String) {
    override fun toString(): String = "gateway-request:[redacted]"

    companion object {
        fun parse(raw: String): GatewayContractResult<GatewayRequestId> = parseUuid(raw) {
            GatewayRequestId(it)
        }
    }
}

@JvmInline
value class GatewayEventId private constructor(val value: String) {
    override fun toString(): String = "gateway-event:[redacted]"

    companion object {
        fun parse(raw: String): GatewayContractResult<GatewayEventId> = parseUuid(raw) {
            GatewayEventId(it)
        }
    }
}

@JvmInline
value class GatewayCancellationId private constructor(val value: String) {
    override fun toString(): String = "gateway-cancellation:[redacted]"

    companion object {
        fun parse(raw: String): GatewayContractResult<GatewayCancellationId> = parseUuid(raw) {
            GatewayCancellationId(it)
        }
    }
}

@JvmInline
value class GatewayText private constructor(val value: String) {
    override fun toString(): String = "gateway-text:[redacted]"

    companion object {
        const val MAX_CODE_POINTS = 8_192

        fun create(raw: String): GatewayContractResult<GatewayText> = when {
            raw.isBlank() -> GatewayContractResult.Failure(GatewayContractError.INVALID_TEXT)
            raw.codePointCount(0, raw.length) > MAX_CODE_POINTS ->
                GatewayContractResult.Failure(GatewayContractError.LIMIT_EXCEEDED)
            raw.any { it == '\u0000' } -> GatewayContractResult.Failure(GatewayContractError.INVALID_TEXT)
            else -> GatewayContractResult.Success(GatewayText(raw))
        }
    }
}

@JvmInline
value class GatewayAttachmentReference private constructor(val value: String) {
    override fun toString(): String = "gateway-attachment:[redacted]"

    companion object {
        private val pattern = Regex("^[a-z][a-z0-9_]{0,31}:[A-Za-z0-9_-]{8,96}$")

        fun parse(raw: String): GatewayContractResult<GatewayAttachmentReference> = if (pattern.matches(raw)) {
            GatewayContractResult.Success(GatewayAttachmentReference(raw))
        } else {
            GatewayContractResult.Failure(GatewayContractError.INVALID_ATTACHMENT_REFERENCE)
        }
    }
}

enum class GatewayOperation(val wireValue: String) {
    TEXT("text"),
    REALTIME("realtime"),
    VISION("vision"),
    EMBEDDING("embedding");

    companion object {
        fun fromWireValue(raw: String): GatewayOperation? = entries.firstOrNull { it.wireValue == raw }
    }
}

enum class GatewayRealtimeModality(val wireValue: String) {
    TEXT("text"),
    AUDIO("audio");

    companion object {
        fun fromWireValue(raw: String): GatewayRealtimeModality? = entries.firstOrNull { it.wireValue == raw }
    }
}

data class GatewayBudget private constructor(
    val maximumOutputUnits: Int,
    val timeoutMs: Long,
) {
    companion object {
        fun create(maximumOutputUnits: Int, timeoutMs: Long): GatewayContractResult<GatewayBudget> =
            if (maximumOutputUnits !in 1..32_768 || timeoutMs !in 100L..120_000L) {
                GatewayContractResult.Failure(GatewayContractError.INVALID_BUDGET)
            } else {
                GatewayContractResult.Success(GatewayBudget(maximumOutputUnits, timeoutMs))
            }
    }
}

data class GatewayRealtimeConfiguration private constructor(
    val inputModalities: Set<GatewayRealtimeModality>,
    val outputModalities: Set<GatewayRealtimeModality>,
) {
    companion object {
        fun create(
            inputModalities: Set<GatewayRealtimeModality>,
            outputModalities: Set<GatewayRealtimeModality>,
        ): GatewayContractResult<GatewayRealtimeConfiguration> =
            if (inputModalities.isEmpty() || outputModalities.isEmpty()) {
                GatewayContractResult.Failure(GatewayContractError.INVALID_REALTIME_CONFIGURATION)
            } else {
                GatewayContractResult.Success(
                    GatewayRealtimeConfiguration(inputModalities.toSet(), outputModalities.toSet()),
                )
            }
    }
}

data class GatewayRequest private constructor(
    val schemaVersion: GatewaySchemaVersion,
    val requestId: GatewayRequestId,
    val operation: GatewayOperation,
    val prompt: GatewayText?,
    val attachmentReference: GatewayAttachmentReference?,
    val embeddingInputs: List<GatewayText>,
    val realtime: GatewayRealtimeConfiguration?,
    val declaredSkills: List<SkillReference>,
    val budget: GatewayBudget,
) {
    companion object {
        const val MAX_EMBEDDING_INPUTS = 64
        const val MAX_DECLARED_SKILLS = 32

        @Suppress("LongParameterList")
        fun create(
            schemaVersion: GatewaySchemaVersion,
            requestId: GatewayRequestId,
            operation: GatewayOperation,
            prompt: GatewayText?,
            attachmentReference: GatewayAttachmentReference?,
            embeddingInputs: List<GatewayText>,
            realtime: GatewayRealtimeConfiguration?,
            declaredSkills: List<SkillReference>,
            budget: GatewayBudget,
        ): GatewayContractResult<GatewayRequest> {
            if (schemaVersion.compatibilityWith(GatewaySchemaVersion.CURRENT) ==
                GatewaySchemaCompatibility.INCOMPATIBLE_MAJOR ||
                schemaVersion.compatibilityWith(GatewaySchemaVersion.CURRENT) ==
                GatewaySchemaCompatibility.UNSUPPORTED_OLDER_MINOR
            ) {
                return GatewayContractResult.Failure(GatewayContractError.UNSUPPORTED_SCHEMA_VERSION)
            }
            if (embeddingInputs.size > MAX_EMBEDDING_INPUTS || declaredSkills.size > MAX_DECLARED_SKILLS) {
                return GatewayContractResult.Failure(GatewayContractError.LIMIT_EXCEEDED)
            }
            if (declaredSkills.distinct().size != declaredSkills.size) {
                return GatewayContractResult.Failure(GatewayContractError.DUPLICATE_SKILL)
            }
            val validShape = when (operation) {
                GatewayOperation.TEXT -> prompt != null && attachmentReference == null &&
                    embeddingInputs.isEmpty() && realtime == null
                GatewayOperation.VISION -> prompt != null && attachmentReference != null &&
                    embeddingInputs.isEmpty() && realtime == null
                GatewayOperation.EMBEDDING -> prompt == null && attachmentReference == null &&
                    embeddingInputs.isNotEmpty() && realtime == null && declaredSkills.isEmpty()
                GatewayOperation.REALTIME -> prompt == null && attachmentReference == null &&
                    embeddingInputs.isEmpty() && realtime != null
            }
            if (!validShape) return GatewayContractResult.Failure(GatewayContractError.INVALID_REQUEST_SHAPE)
            return GatewayContractResult.Success(
                GatewayRequest(
                    schemaVersion,
                    requestId,
                    operation,
                    prompt,
                    attachmentReference,
                    embeddingInputs.toList(),
                    realtime,
                    declaredSkills.sorted(),
                    budget,
                ),
            )
        }
    }

    override fun toString(): String = "GatewayRequest(operation=${operation.wireValue}, requestId=$requestId, content=[redacted])"
}

enum class GatewayCancellationReason(val wireValue: String) {
    USER("user"),
    BARGE_IN("barge_in"),
    BACKGROUND("background"),
    DEADLINE("deadline"),
    CLIENT_SHUTDOWN("client_shutdown");

    companion object {
        fun fromWireValue(raw: String): GatewayCancellationReason? = entries.firstOrNull { it.wireValue == raw }
    }
}

data class GatewayCancellation(
    val schemaVersion: GatewaySchemaVersion,
    val requestId: GatewayRequestId,
    val cancellationId: GatewayCancellationId,
    val reason: GatewayCancellationReason,
)

sealed interface GatewayScalar {
    val canonicalValue: String

    data class Text private constructor(val value: String) : GatewayScalar {
        override val canonicalValue: String = "text:$value"
        override fun toString(): String = "gateway-scalar:text:[redacted]"

        companion object {
            fun create(raw: String): GatewayContractResult<Text> = if (
                raw.length <= 1_024 && raw.none { it == '\u0000' }
            ) GatewayContractResult.Success(Text(raw)) else GatewayContractResult.Failure(GatewayContractError.LIMIT_EXCEEDED)
        }
    }

    data class Integer(val value: Long) : GatewayScalar {
        override val canonicalValue: String = "integer:$value"
        override fun toString(): String = "gateway-scalar:integer:[redacted]"
    }

    data class Decimal private constructor(val value: Double) : GatewayScalar {
        override val canonicalValue: String = "decimal:${java.lang.Double.toHexString(value)}"
        override fun toString(): String = "gateway-scalar:decimal:[redacted]"

        companion object {
            fun create(raw: Double): GatewayContractResult<Decimal> = if (raw.isFinite()) {
                GatewayContractResult.Success(Decimal(raw))
            } else {
                GatewayContractResult.Failure(GatewayContractError.NON_FINITE_NUMBER)
            }
        }
    }

    data class Flag(val value: Boolean) : GatewayScalar {
        override val canonicalValue: String = "flag:$value"
        override fun toString(): String = "gateway-scalar:flag:[redacted]"
    }
}

data class GatewayToolProposal private constructor(
    val proposalId: GatewayEventId,
    val skill: SkillReference,
    val arguments: Map<String, GatewayScalar>,
) {
    companion object {
        private val argumentNamePattern = Regex("^[a-z][a-z0-9_]{0,63}$")

        fun create(
            proposalId: GatewayEventId,
            skill: SkillReference,
            arguments: Map<String, GatewayScalar>,
        ): GatewayContractResult<GatewayToolProposal> {
            if (arguments.size > 32 || arguments.keys.any { !argumentNamePattern.matches(it) }) {
                return GatewayContractResult.Failure(GatewayContractError.INVALID_TOOL_ARGUMENTS)
            }
            return GatewayContractResult.Success(GatewayToolProposal(proposalId, skill, arguments.toSortedMap()))
        }
    }

    override fun toString(): String = "GatewayToolProposal(proposalId=$proposalId, skill=$skill, arguments=[redacted])"
}

enum class GatewayRefusalReason(val wireValue: String) {
    POLICY("policy"),
    PRIVACY("privacy"),
    UNSUPPORTED_REQUEST("unsupported_request");

    companion object {
        fun fromWireValue(raw: String): GatewayRefusalReason? = entries.firstOrNull { it.wireValue == raw }
    }
}

enum class GatewayFailureCode(val wireValue: String) {
    INVALID_REQUEST("invalid_request"),
    UNAUTHENTICATED("unauthenticated"),
    RATE_LIMITED("rate_limited"),
    UNAVAILABLE("unavailable"),
    INTERNAL("internal"),
    PROTOCOL_ERROR("protocol_error");

    companion object {
        fun fromWireValue(raw: String): GatewayFailureCode? = entries.firstOrNull { it.wireValue == raw }
    }
}

enum class GatewayFinishReason(val wireValue: String) {
    COMPLETE("complete"),
    OUTPUT_LIMIT("output_limit");

    companion object {
        fun fromWireValue(raw: String): GatewayFinishReason? = entries.firstOrNull { it.wireValue == raw }
    }
}

enum class GatewayUsageEvidence(val wireValue: String) {
    MEASURED("measured"),
    ESTIMATED("estimated"),
    UNKNOWN("unknown");

    companion object {
        fun fromWireValue(raw: String): GatewayUsageEvidence? = entries.firstOrNull { it.wireValue == raw }
    }
}

data class GatewayCost private constructor(val currency: String, val micros: Long) {
    companion object {
        private val currencyPattern = Regex("^[A-Z]{3}$")

        fun create(currency: String, micros: Long): GatewayContractResult<GatewayCost> = if (
            currencyPattern.matches(currency) && micros >= 0
        ) GatewayContractResult.Success(GatewayCost(currency, micros)) else
            GatewayContractResult.Failure(GatewayContractError.INVALID_USAGE)
    }
}

data class GatewayUsage private constructor(
    val inputUnits: Long?,
    val outputUnits: Long?,
    val evidence: GatewayUsageEvidence,
    val estimatedCost: GatewayCost?,
) {
    companion object {
        fun create(
            inputUnits: Long?,
            outputUnits: Long?,
            evidence: GatewayUsageEvidence,
            estimatedCost: GatewayCost?,
        ): GatewayContractResult<GatewayUsage> {
            if (inputUnits?.let { it < 0 } == true || outputUnits?.let { it < 0 } == true) {
                return GatewayContractResult.Failure(GatewayContractError.INVALID_USAGE)
            }
            if (evidence == GatewayUsageEvidence.UNKNOWN &&
                (inputUnits != null || outputUnits != null || estimatedCost != null)
            ) return GatewayContractResult.Failure(GatewayContractError.INVALID_USAGE)
            if (evidence != GatewayUsageEvidence.UNKNOWN && inputUnits == null && outputUnits == null) {
                return GatewayContractResult.Failure(GatewayContractError.INVALID_USAGE)
            }
            return GatewayContractResult.Success(GatewayUsage(inputUnits, outputUnits, evidence, estimatedCost))
        }
    }
}

sealed interface GatewayEventPayload {
    data object Started : GatewayEventPayload
    data class PartialText(val text: GatewayText) : GatewayEventPayload
    data class ToolProposal(val proposal: GatewayToolProposal) : GatewayEventPayload
    data class Refusal(val reason: GatewayRefusalReason) : GatewayEventPayload
    data class Embedding(val inputIndex: Int, val vector: List<Double>) : GatewayEventPayload {
        override fun toString(): String = "Embedding(inputIndex=$inputIndex, vector=[redacted])"
    }
    data class Usage(val usage: GatewayUsage) : GatewayEventPayload
    data class Completed(val reason: GatewayFinishReason) : GatewayEventPayload
    data class Failed(val code: GatewayFailureCode, val retryable: Boolean) : GatewayEventPayload
    data class Cancelled(val reason: GatewayCancellationReason) : GatewayEventPayload
    data object TimedOut : GatewayEventPayload
}

data class GatewayEvent private constructor(
    val schemaVersion: GatewaySchemaVersion,
    val requestId: GatewayRequestId,
    val eventId: GatewayEventId,
    val sequence: Int,
    val operation: GatewayOperation,
    val payload: GatewayEventPayload,
) {
    val isTerminal: Boolean = payload is GatewayEventPayload.Refusal ||
        payload is GatewayEventPayload.Completed || payload is GatewayEventPayload.Failed ||
        payload is GatewayEventPayload.Cancelled || payload is GatewayEventPayload.TimedOut

    companion object {
        fun create(
            schemaVersion: GatewaySchemaVersion,
            requestId: GatewayRequestId,
            eventId: GatewayEventId,
            sequence: Int,
            operation: GatewayOperation,
            payload: GatewayEventPayload,
        ): GatewayContractResult<GatewayEvent> {
            if (schemaVersion.compatibilityWith(GatewaySchemaVersion.CURRENT) ==
                GatewaySchemaCompatibility.INCOMPATIBLE_MAJOR || sequence !in 0..1_000_000
            ) return GatewayContractResult.Failure(GatewayContractError.INVALID_EVENT)
            if (payload is GatewayEventPayload.Embedding) {
                if (operation != GatewayOperation.EMBEDDING || payload.inputIndex !in 0..63 ||
                    payload.vector.isEmpty() || payload.vector.size > 4_096 || payload.vector.any { !it.isFinite() }
                ) return GatewayContractResult.Failure(GatewayContractError.INVALID_EMBEDDING)
            }
            if ((payload is GatewayEventPayload.PartialText || payload is GatewayEventPayload.ToolProposal) &&
                operation == GatewayOperation.EMBEDDING
            ) return GatewayContractResult.Failure(GatewayContractError.INVALID_EVENT)
            return GatewayContractResult.Success(
                GatewayEvent(schemaVersion, requestId, eventId, sequence, operation, payload),
            )
        }
    }

    override fun toString(): String = "GatewayEvent(sequence=$sequence, operation=${operation.wireValue}, " +
        "payload=${payload::class.simpleName}, requestId=$requestId, content=[redacted])"
}

object GatewayStreamValidator {
    const val MAX_EVENTS = 1_024

    fun validate(events: List<GatewayEvent>): GatewayContractResult<List<GatewayEvent>> {
        if (events.isEmpty() || events.size > MAX_EVENTS) {
            return GatewayContractResult.Failure(GatewayContractError.INVALID_EVENT_ORDER)
        }
        val first = events.first()
        if (first.sequence != 0 || first.payload !is GatewayEventPayload.Started) {
            return GatewayContractResult.Failure(GatewayContractError.INVALID_EVENT_ORDER)
        }
        if (events.any { it.requestId != first.requestId || it.operation != first.operation } ||
            events.map { it.eventId }.distinct().size != events.size
        ) return GatewayContractResult.Failure(GatewayContractError.CORRELATION_MISMATCH)
        if (events.indices.any { events[it].sequence != it }) {
            return GatewayContractResult.Failure(GatewayContractError.INVALID_EVENT_ORDER)
        }
        if (events.drop(1).any { it.payload is GatewayEventPayload.Started }) {
            return GatewayContractResult.Failure(GatewayContractError.INVALID_EVENT_ORDER)
        }
        val terminals = events.withIndex().filter { it.value.isTerminal }
        if (terminals.size != 1 || terminals.single().index != events.lastIndex) {
            return GatewayContractResult.Failure(GatewayContractError.INVALID_EVENT_ORDER)
        }
        if (events.count { it.payload is GatewayEventPayload.Usage } > 1) {
            return GatewayContractResult.Failure(GatewayContractError.INVALID_EVENT_ORDER)
        }
        return GatewayContractResult.Success(events.toList())
    }

    fun validate(request: GatewayRequest, events: List<GatewayEvent>): GatewayContractResult<List<GatewayEvent>> {
        val ordered = when (val result = validate(events)) {
            is GatewayContractResult.Success -> result.value
            is GatewayContractResult.Failure -> return result
        }
        if (ordered.any { it.requestId != request.requestId || it.operation != request.operation }) {
            return GatewayContractResult.Failure(GatewayContractError.CORRELATION_MISMATCH)
        }
        val declaredSkills = request.declaredSkills.toSet()
        if (ordered.any {
                val payload = it.payload
                payload is GatewayEventPayload.ToolProposal && payload.proposal.skill !in declaredSkills
            }
        ) return GatewayContractResult.Failure(GatewayContractError.UNDECLARED_SKILL_PROPOSAL)
        val embeddingIndexes = ordered.mapNotNull { (it.payload as? GatewayEventPayload.Embedding)?.inputIndex }
        if (embeddingIndexes.distinct().size != embeddingIndexes.size ||
            embeddingIndexes.any { it !in request.embeddingInputs.indices }
        ) return GatewayContractResult.Failure(GatewayContractError.INVALID_EMBEDDING)
        return GatewayContractResult.Success(ordered)
    }
}

enum class GatewayContractError {
    INVALID_SCHEMA_VERSION,
    UNSUPPORTED_SCHEMA_VERSION,
    INVALID_IDENTIFIER,
    INVALID_TEXT,
    INVALID_ATTACHMENT_REFERENCE,
    INVALID_BUDGET,
    INVALID_REALTIME_CONFIGURATION,
    INVALID_REQUEST_SHAPE,
    DUPLICATE_SKILL,
    INVALID_TOOL_ARGUMENTS,
    NON_FINITE_NUMBER,
    INVALID_USAGE,
    INVALID_EVENT,
    INVALID_EMBEDDING,
    INVALID_EVENT_ORDER,
    CORRELATION_MISMATCH,
    UNDECLARED_SKILL_PROPOSAL,
    LIMIT_EXCEEDED,
    MALFORMED_DOCUMENT,
    MISSING_FIELD,
    UNEXPECTED_FIELD,
}

sealed interface GatewayContractResult<out T> {
    data class Success<T>(val value: T) : GatewayContractResult<T>
    data class Failure(val error: GatewayContractError) : GatewayContractResult<Nothing>
}

private fun <T> parseUuid(raw: String, create: (String) -> T): GatewayContractResult<T> {
    val canonical = runCatching { UUID.fromString(raw).toString() }.getOrNull()
    return if (canonical == raw) GatewayContractResult.Success(create(raw)) else
        GatewayContractResult.Failure(GatewayContractError.INVALID_IDENTIFIER)
}
