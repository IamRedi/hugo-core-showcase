package io.redi.robot.context.api

import io.redi.robot.capability.api.MonotonicTimestampMs
import java.math.BigDecimal
import java.util.Collections
import java.util.UUID

/** Per-event correlation only. It is not a HUGO, user, provider, host, or source identity. */
@JvmInline
value class ContextObservationId private constructor(val value: String) {
    companion object {
        fun fromUuid(uuid: UUID): ContextObservationId = ContextObservationId(uuid.toString())

        fun parse(raw: String): ContextObservationResult<ContextObservationId> =
            canonicalUuid(raw)?.let { ContextObservationResult.Success(ContextObservationId(it)) }
                ?: contextFailure(ContextObservationError.InvalidObservationId)
    }
}

/** Extensible semantic source class, such as `host.clock` or `provider.availability`. */
@JvmInline
value class ContextSourceType private constructor(val value: String) {
    companion object {
        fun parse(raw: String): ContextObservationResult<ContextSourceType> = semanticToken(raw, 3, 80)
            ?.let { ContextObservationResult.Success(ContextSourceType(it)) }
            ?: contextFailure(ContextObservationError.InvalidSourceType)
    }
}

/** Opaque run-local source alias. It must not contain an endpoint, account, serial, or address. */
@JvmInline
value class ContextSourceReference private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]{2,63}(?:_[a-z0-9]{2,63}){0,7}$")

        fun parse(raw: String): ContextObservationResult<ContextSourceReference> = if (
            raw.length in 3..128 && pattern.matches(raw)
        ) {
            ContextObservationResult.Success(ContextSourceReference(raw))
        } else {
            contextFailure(ContextObservationError.InvalidSourceReference)
        }
    }
}

/** Provider-neutral semantic key. H036 defines no source-specific key catalogue. */
@JvmInline
value class ContextKey private constructor(val value: String) {
    companion object {
        fun parse(raw: String): ContextObservationResult<ContextKey> = semanticToken(raw, 3, 96)
            ?.let { ContextObservationResult.Success(ContextKey(it)) }
            ?: contextFailure(ContextObservationError.InvalidContextKey)
    }
}

/** Confidence in the source observation only. It is never truth, trust, or action authority. */
@JvmInline
value class ContextConfidence private constructor(val permille: Int) {
    companion object {
        fun create(permille: Int): ContextObservationResult<ContextConfidence> = if (permille in 0..1000) {
            ContextObservationResult.Success(ContextConfidence(permille))
        } else {
            contextFailure(ContextObservationError.InvalidConfidence)
        }
    }
}

enum class ContextPrivacyClassification(val wireValue: String) {
    PUBLIC("public"),
    PRIVATE("private"),
    SENSITIVE("sensitive"),
    RESTRICTED("restricted"),
    ;

    companion object {
        fun fromWireValue(value: String): ContextPrivacyClassification? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

enum class ContextUnknownReason(val wireValue: String) {
    NOT_OBSERVED("not_observed"),
    SOURCE_UNAVAILABLE("source_unavailable"),
    PERMISSION_DENIED("permission_denied"),
    UNSUPPORTED("unsupported"),
    REDACTED("redacted"),
    ;

    companion object {
        fun fromWireValue(value: String): ContextUnknownReason? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

enum class ContextFreshness {
    NOT_YET_VALID,
    FRESH,
    STALE,
}

@JvmInline
value class ContextText private constructor(val value: String) {
    companion object {
        fun create(raw: String): ContextObservationResult<ContextText> = boundedText(raw, 160)
            ?.let { ContextObservationResult.Success(ContextText(it)) }
            ?: contextFailure(ContextObservationError.InvalidTextValue)
    }
}

@JvmInline
value class ContextSymbol private constructor(val value: String) {
    companion object {
        fun parse(raw: String): ContextObservationResult<ContextSymbol> = semanticToken(raw, 1, 96)
            ?.let { ContextObservationResult.Success(ContextSymbol(it)) }
            ?: contextFailure(ContextObservationError.InvalidSymbolValue)
    }
}

sealed interface ContextValue {
    val wireType: String
    val canonicalValue: String

    data class BooleanValue(val value: Boolean) : ContextValue {
        override val wireType: String = "boolean"
        override val canonicalValue: String = value.toString()
    }

    data class IntegerValue(val value: Long) : ContextValue {
        override val wireType: String = "integer"
        override val canonicalValue: String = value.toString()
    }

    class DecimalValue private constructor(override val canonicalValue: String) : ContextValue {
        override val wireType: String = "decimal"

        override fun equals(other: Any?): Boolean = other is DecimalValue && canonicalValue == other.canonicalValue

        override fun hashCode(): Int = canonicalValue.hashCode()

        override fun toString(): String = "DecimalValue(canonicalValue=$canonicalValue)"

        companion object {
            fun parse(raw: String): ContextObservationResult<DecimalValue> {
                val parsed = runCatching { BigDecimal(raw) }.getOrNull()
                val normalized = parsed?.stripTrailingZeros()?.toPlainString()
                return if (
                    parsed == null ||
                    normalized == null ||
                    normalized == "-0" ||
                    parsed.precision() > 18 ||
                    parsed.scale().coerceAtLeast(0) > 6 ||
                    normalized != raw
                ) {
                    contextFailure(ContextObservationError.InvalidDecimalValue)
                } else {
                    ContextObservationResult.Success(DecimalValue(normalized))
                }
            }
        }
    }

    data class TextValue(val value: ContextText) : ContextValue {
        override val wireType: String = "text"
        override val canonicalValue: String = value.value
    }

    data class SymbolValue(val value: ContextSymbol) : ContextValue {
        override val wireType: String = "symbol"
        override val canonicalValue: String = value.value
    }
}

sealed interface ContextObservationClaim {
    val wireStatus: String

    data class Known(val value: ContextValue) : ContextObservationClaim {
        override val wireStatus: String = "known"
    }

    data class Unknown(val reason: ContextUnknownReason) : ContextObservationClaim {
        override val wireStatus: String = "unknown"
    }

    class Conflict private constructor(candidates: List<ContextValue>) : ContextObservationClaim {
        override val wireStatus: String = "conflict"
        val candidates: List<ContextValue> = Collections.unmodifiableList(candidates.toList())

        override fun equals(other: Any?): Boolean = other is Conflict && candidates == other.candidates

        override fun hashCode(): Int = candidates.hashCode()

        override fun toString(): String = "Conflict(candidates=$candidates)"

        companion object {
            private const val maximumCandidates = 8

            fun create(candidates: Collection<ContextValue>): ContextObservationResult<Conflict> {
                val values = candidates.toList()
                val keys = values.map(ContextValue::sortKey)
                return when {
                    values.size < 2 -> contextFailure(ContextObservationError.TooFewConflictCandidates)
                    values.size > maximumCandidates -> contextFailure(
                        ContextObservationError.TooManyConflictCandidates(maximumCandidates),
                    )
                    keys.distinct().size != keys.size -> contextFailure(
                        ContextObservationError.DuplicateConflictCandidate,
                    )
                    else -> ContextObservationResult.Success(
                        Conflict(values.sortedBy(ContextValue::sortKey)),
                    )
                }
            }
        }
    }
}

/**
 * One bounded source observation. It is explicitly not a durable memory, reconciled fact,
 * permission, trust decision, safety approval, provider selection, or execution instruction.
 */
class ContextObservation private constructor(
    val observationId: ContextObservationId,
    val sourceType: ContextSourceType,
    val sourceReference: ContextSourceReference,
    val key: ContextKey,
    val claim: ContextObservationClaim,
    val confidence: ContextConfidence,
    val privacyClassification: ContextPrivacyClassification,
    val observedAt: MonotonicTimestampMs,
    val expiresAt: MonotonicTimestampMs,
) {
    fun freshnessAt(now: MonotonicTimestampMs): ContextFreshness = when {
        now.value < observedAt.value -> ContextFreshness.NOT_YET_VALID
        now.value < expiresAt.value -> ContextFreshness.FRESH
        else -> ContextFreshness.STALE
    }

    fun isFreshAt(now: MonotonicTimestampMs): Boolean = freshnessAt(now) == ContextFreshness.FRESH

    companion object {
        const val SCHEMA_VERSION: String = "1.0"

        @Suppress("LongParameterList")
        fun create(
            observationId: ContextObservationId,
            sourceType: ContextSourceType,
            sourceReference: ContextSourceReference,
            key: ContextKey,
            claim: ContextObservationClaim,
            confidence: ContextConfidence,
            privacyClassification: ContextPrivacyClassification,
            observedAt: MonotonicTimestampMs,
            expiresAt: MonotonicTimestampMs,
        ): ContextObservationResult<ContextObservation> = if (expiresAt.value <= observedAt.value) {
            contextFailure(ContextObservationError.InvalidFreshnessWindow)
        } else {
            ContextObservationResult.Success(
                ContextObservation(
                    observationId,
                    sourceType,
                    sourceReference,
                    key,
                    claim,
                    confidence,
                    privacyClassification,
                    observedAt,
                    expiresAt,
                ),
            )
        }
    }
}

sealed interface ContextObservationResult<out T> {
    data class Success<T>(val value: T) : ContextObservationResult<T>

    data class Failure(val error: ContextObservationError) : ContextObservationResult<Nothing>
}

sealed interface ContextObservationError {
    data object InvalidObservationId : ContextObservationError
    data object InvalidSourceType : ContextObservationError
    data object InvalidSourceReference : ContextObservationError
    data object InvalidContextKey : ContextObservationError
    data object InvalidConfidence : ContextObservationError
    data object InvalidTextValue : ContextObservationError
    data object InvalidSymbolValue : ContextObservationError
    data object InvalidDecimalValue : ContextObservationError
    data object InvalidFreshnessWindow : ContextObservationError
    data object TooFewConflictCandidates : ContextObservationError
    data class TooManyConflictCandidates(val maximum: Int) : ContextObservationError
    data object DuplicateConflictCandidate : ContextObservationError
    data class UnsupportedSchemaVersion(val found: String) : ContextObservationError
    data class MissingField(val field: String) : ContextObservationError
    data class UnexpectedFields(val fields: Set<String>) : ContextObservationError
    data class InvalidDocument(val field: String) : ContextObservationError
}

private val semanticPattern = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$")

private fun semanticToken(raw: String, minimumLength: Int, maximumLength: Int): String? = raw.takeIf {
    it.length in minimumLength..maximumLength && semanticPattern.matches(it)
}

private fun boundedText(raw: String, maximumLength: Int): String? {
    val normalized = raw.trim()
    return normalized.takeIf {
        it.isNotEmpty() && it.length <= maximumLength && it.none(Char::isISOControl)
    }
}

private fun ContextValue.sortKey(): String = "$wireType:$canonicalValue"

private fun canonicalUuid(raw: String): String? = runCatching {
    UUID.fromString(raw).toString().takeIf { it == raw }
}.getOrNull()

private fun <T> contextFailure(error: ContextObservationError): ContextObservationResult<T> =
    ContextObservationResult.Failure(error)
