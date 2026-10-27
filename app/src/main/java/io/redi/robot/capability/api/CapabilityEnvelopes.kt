package io.redi.robot.capability.api

import io.redi.robot.capability.provider.ProviderInstanceId
import java.util.UUID

@JvmInline
value class CapabilityRequestId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun fromUuid(uuid: UUID): CapabilityRequestId = CapabilityRequestId(uuid.toString())

        fun parse(raw: String): CapabilityEnvelopeResult<CapabilityRequestId> =
            parseEnvelopeUuid(raw, "requestId") { CapabilityRequestId(it) }
    }
}

@JvmInline
value class CapabilityAuditId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun fromUuid(uuid: UUID): CapabilityAuditId = CapabilityAuditId(uuid.toString())

        fun parse(raw: String): CapabilityEnvelopeResult<CapabilityAuditId> =
            parseEnvelopeUuid(raw, "auditId") { CapabilityAuditId(it) }
    }
}

/** Reference to an external authorization decision; possession of it is not authority. */
@JvmInline
value class CapabilityAuthorizationReference private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun fromUuid(uuid: UUID): CapabilityAuthorizationReference =
            CapabilityAuthorizationReference(uuid.toString())

        fun parse(raw: String): CapabilityEnvelopeResult<CapabilityAuthorizationReference> =
            parseEnvelopeUuid(raw, "authorizationReference") {
                CapabilityAuthorizationReference(it)
            }
    }
}

@JvmInline
value class MonotonicTimestampMs private constructor(val value: Long) {
    companion object {
        fun create(value: Long): CapabilityEnvelopeResult<MonotonicTimestampMs> = if (value < 0L) {
            CapabilityEnvelopeResult.Failure(
                CapabilityEnvelopeError.InvalidMonotonicTime("timestamp", value),
            )
        } else {
            CapabilityEnvelopeResult.Success(MonotonicTimestampMs(value))
        }
    }
}

data class CapabilityExecutionContext private constructor(
    val epoch: Long,
    val sequence: Long,
) {
    companion object {
        fun create(
            epoch: Long,
            sequence: Long,
        ): CapabilityEnvelopeResult<CapabilityExecutionContext> = if (epoch < 0L || sequence < 0L) {
            CapabilityEnvelopeResult.Failure(
                CapabilityEnvelopeError.InvalidExecutionContext(epoch, sequence),
            )
        } else {
            CapabilityEnvelopeResult.Success(CapabilityExecutionContext(epoch, sequence))
        }
    }
}

enum class CapabilityActorKind {
    LOCAL_USER,
    HUGO_RUNTIME,
    LOCAL_SYSTEM,
}

sealed interface CapabilityProviderTarget {
    data class Exact(val providerInstanceId: ProviderInstanceId) : CapabilityProviderTarget

    data object AnyCompatible : CapabilityProviderTarget
}

@JvmInline
value class CapabilityArgumentName private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val canonicalPattern = Regex("^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$")

        fun parse(raw: String): CapabilityEnvelopeResult<CapabilityArgumentName> = when {
            raw.isBlank() -> CapabilityEnvelopeResult.Failure(
                CapabilityEnvelopeError.InvalidArgumentName(raw),
            )
            !canonicalPattern.matches(raw) -> CapabilityEnvelopeResult.Failure(
                CapabilityEnvelopeError.InvalidArgumentName(raw),
            )
            else -> CapabilityEnvelopeResult.Success(CapabilityArgumentName(raw))
        }
    }
}

sealed interface CapabilityArgumentValue {
    data class Text private constructor(val value: String) : CapabilityArgumentValue {
        companion object {
            fun create(value: String): CapabilityEnvelopeResult<Text> = when {
                value.length > 512 || value.any(Char::isISOControl) -> CapabilityEnvelopeResult.Failure(
                    CapabilityEnvelopeError.InvalidArgumentValue,
                )
                else -> CapabilityEnvelopeResult.Success(Text(value))
            }
        }
    }

    data class Integer(val value: Long) : CapabilityArgumentValue

    data class Decimal private constructor(val value: Double) : CapabilityArgumentValue {
        companion object {
            fun create(value: Double): CapabilityEnvelopeResult<Decimal> = if (value.isFinite()) {
                CapabilityEnvelopeResult.Success(Decimal(value))
            } else {
                CapabilityEnvelopeResult.Failure(CapabilityEnvelopeError.InvalidArgumentValue)
            }
        }
    }

    data class Flag(val value: Boolean) : CapabilityArgumentValue
}

class CapabilityData private constructor(
    val contract: CapabilityContractReference,
    val values: Map<CapabilityArgumentName, CapabilityArgumentValue>,
) {
    companion object {
        private const val maxArguments = 64

        fun create(
            contract: CapabilityContractReference,
            values: Map<CapabilityArgumentName, CapabilityArgumentValue>,
        ): CapabilityEnvelopeResult<CapabilityData> = if (values.size > maxArguments) {
            CapabilityEnvelopeResult.Failure(CapabilityEnvelopeError.TooManyArguments(maxArguments))
        } else {
            CapabilityEnvelopeResult.Success(CapabilityData(contract, values.toMap()))
        }
    }
}

class CapabilityRequest private constructor(
    val requestId: CapabilityRequestId,
    val capability: CapabilityReference,
    val providerTarget: CapabilityProviderTarget,
    val actor: CapabilityActorKind,
    val executionContext: CapabilityExecutionContext,
    val createdAt: MonotonicTimestampMs,
    val deadline: MonotonicTimestampMs,
    val authorizationReference: CapabilityAuthorizationReference?,
    val auditId: CapabilityAuditId,
    val input: CapabilityData?,
) {
    companion object {
        @Suppress("LongParameterList")
        fun create(
            requestId: CapabilityRequestId,
            capability: CapabilityReference,
            providerTarget: CapabilityProviderTarget,
            actor: CapabilityActorKind,
            executionContext: CapabilityExecutionContext,
            createdAt: MonotonicTimestampMs,
            deadline: MonotonicTimestampMs,
            authorizationReference: CapabilityAuthorizationReference?,
            auditId: CapabilityAuditId,
            input: CapabilityData?,
        ): CapabilityEnvelopeResult<CapabilityRequest> {
            if (deadline.value <= createdAt.value) {
                return CapabilityEnvelopeResult.Failure(
                    CapabilityEnvelopeError.InvalidDeadline(createdAt.value, deadline.value),
                )
            }
            return CapabilityEnvelopeResult.Success(
                CapabilityRequest(
                    requestId = requestId,
                    capability = capability,
                    providerTarget = providerTarget,
                    actor = actor,
                    executionContext = executionContext,
                    createdAt = createdAt,
                    deadline = deadline,
                    authorizationReference = authorizationReference,
                    auditId = auditId,
                    input = input,
                ),
            )
        }
    }
}

enum class CapabilityFailureCode {
    INVALID_REQUEST,
    UNSUPPORTED_CAPABILITY,
    INCOMPATIBLE_VERSION,
    PROVIDER_UNAVAILABLE,
    PERMISSION_DENIED,
    APPROVAL_REQUIRED,
    SAFETY_REJECTED,
    TIMEOUT,
    CANCELLED,
    TRANSPORT_FAILURE,
    PROVIDER_FAILURE,
    MALFORMED_RESULT,
    UNKNOWN,
}

@JvmInline
value class CapabilityReasonCode private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val canonicalPattern = Regex("^[a-z][a-z0-9]*(?:[.][a-z][a-z0-9_]*)*$")

        fun parse(raw: String): CapabilityEnvelopeResult<CapabilityReasonCode> = if (
            canonicalPattern.matches(raw)
        ) {
            CapabilityEnvelopeResult.Success(CapabilityReasonCode(raw))
        } else {
            CapabilityEnvelopeResult.Failure(CapabilityEnvelopeError.InvalidReasonCode(raw))
        }
    }
}

data class CapabilityFailure(
    val code: CapabilityFailureCode,
    val reasonCode: CapabilityReasonCode? = null,
)

enum class CapabilityResultState(val terminal: Boolean) {
    ACCEPTED(false),
    STARTED(false),
    COMPLETED(true),
    REJECTED(true),
    FAILED(true),
    CANCELLED(true),
    TIMED_OUT(true),
    UNKNOWN(true),
}

enum class CapabilityOutcomeEvidence {
    REQUEST_ACCEPTED,
    PROVIDER_REPORTED,
    LOCALLY_OBSERVED,
    OUTCOME_UNKNOWN,
}

class CapabilityResult private constructor(
    val requestId: CapabilityRequestId,
    val capability: CapabilityReference,
    val providerInstanceId: ProviderInstanceId,
    val executionContext: CapabilityExecutionContext,
    val occurredAt: MonotonicTimestampMs,
    val state: CapabilityResultState,
    val failure: CapabilityFailure?,
    val evidence: CapabilityOutcomeEvidence,
    val output: CapabilityData?,
    val auditId: CapabilityAuditId,
) {
    companion object {
        private val rejectedFailures = setOf(
            CapabilityFailureCode.INVALID_REQUEST,
            CapabilityFailureCode.UNSUPPORTED_CAPABILITY,
            CapabilityFailureCode.INCOMPATIBLE_VERSION,
            CapabilityFailureCode.PROVIDER_UNAVAILABLE,
            CapabilityFailureCode.PERMISSION_DENIED,
            CapabilityFailureCode.APPROVAL_REQUIRED,
            CapabilityFailureCode.SAFETY_REJECTED,
        )
        private val failedFailures = setOf(
            CapabilityFailureCode.TRANSPORT_FAILURE,
            CapabilityFailureCode.PROVIDER_FAILURE,
            CapabilityFailureCode.MALFORMED_RESULT,
        )

        @Suppress("LongParameterList")
        fun create(
            requestId: CapabilityRequestId,
            capability: CapabilityReference,
            providerInstanceId: ProviderInstanceId,
            executionContext: CapabilityExecutionContext,
            occurredAt: MonotonicTimestampMs,
            state: CapabilityResultState,
            failure: CapabilityFailure?,
            evidence: CapabilityOutcomeEvidence,
            output: CapabilityData?,
            auditId: CapabilityAuditId,
        ): CapabilityEnvelopeResult<CapabilityResult> {
            val validFailure = when (state) {
                CapabilityResultState.ACCEPTED,
                CapabilityResultState.STARTED,
                CapabilityResultState.COMPLETED,
                -> failure == null
                CapabilityResultState.REJECTED -> failure?.code in rejectedFailures
                CapabilityResultState.FAILED -> failure?.code in failedFailures
                CapabilityResultState.CANCELLED -> failure?.code == CapabilityFailureCode.CANCELLED
                CapabilityResultState.TIMED_OUT -> failure?.code == CapabilityFailureCode.TIMEOUT
                CapabilityResultState.UNKNOWN -> failure?.code == CapabilityFailureCode.UNKNOWN
            }
            if (!validFailure) {
                return CapabilityEnvelopeResult.Failure(
                    CapabilityEnvelopeError.OutcomeFailureConflict(state, failure?.code),
                )
            }
            if ((state == CapabilityResultState.COMPLETED) != (output != null)) {
                return CapabilityEnvelopeResult.Failure(
                    CapabilityEnvelopeError.OutputStateConflict(state),
                )
            }
            val validEvidence = when {
                state == CapabilityResultState.UNKNOWN ->
                    evidence == CapabilityOutcomeEvidence.OUTCOME_UNKNOWN
                evidence == CapabilityOutcomeEvidence.OUTCOME_UNKNOWN -> false
                evidence == CapabilityOutcomeEvidence.REQUEST_ACCEPTED ->
                    state == CapabilityResultState.ACCEPTED
                else -> true
            }
            if (!validEvidence) {
                return CapabilityEnvelopeResult.Failure(
                    CapabilityEnvelopeError.EvidenceStateConflict(state, evidence),
                )
            }

            return CapabilityEnvelopeResult.Success(
                CapabilityResult(
                    requestId = requestId,
                    capability = capability,
                    providerInstanceId = providerInstanceId,
                    executionContext = executionContext,
                    occurredAt = occurredAt,
                    state = state,
                    failure = failure,
                    evidence = evidence,
                    output = output,
                    auditId = auditId,
                ),
            )
        }
    }

    fun correlationWith(request: CapabilityRequest): CapabilityResultCorrelation = when {
        requestId != request.requestId -> CapabilityResultCorrelation.REQUEST_ID_MISMATCH
        capability != request.capability -> CapabilityResultCorrelation.CAPABILITY_MISMATCH
        executionContext != request.executionContext ->
            CapabilityResultCorrelation.EXECUTION_CONTEXT_MISMATCH
        auditId != request.auditId -> CapabilityResultCorrelation.AUDIT_ID_MISMATCH
        request.providerTarget is CapabilityProviderTarget.Exact &&
            providerInstanceId != request.providerTarget.providerInstanceId ->
            CapabilityResultCorrelation.PROVIDER_TARGET_MISMATCH
        else -> CapabilityResultCorrelation.MATCHED
    }
}

enum class CapabilityResultCorrelation {
    MATCHED,
    REQUEST_ID_MISMATCH,
    CAPABILITY_MISMATCH,
    EXECUTION_CONTEXT_MISMATCH,
    AUDIT_ID_MISMATCH,
    PROVIDER_TARGET_MISMATCH,
}

sealed interface CapabilityEnvelopeResult<out T> {
    data class Success<T>(val value: T) : CapabilityEnvelopeResult<T>

    data class Failure(val error: CapabilityEnvelopeError) : CapabilityEnvelopeResult<Nothing>
}

sealed interface CapabilityEnvelopeError {
    data class InvalidIdentifier(val field: String) : CapabilityEnvelopeError

    data class InvalidMonotonicTime(val field: String, val value: Long) : CapabilityEnvelopeError

    data class InvalidExecutionContext(val epoch: Long, val sequence: Long) : CapabilityEnvelopeError

    data class InvalidDeadline(val createdAt: Long, val deadline: Long) : CapabilityEnvelopeError

    data class InvalidArgumentName(val value: String) : CapabilityEnvelopeError

    data object InvalidArgumentValue : CapabilityEnvelopeError

    data class TooManyArguments(val maximum: Int) : CapabilityEnvelopeError

    data class InvalidReasonCode(val value: String) : CapabilityEnvelopeError

    data class OutcomeFailureConflict(
        val state: CapabilityResultState,
        val failure: CapabilityFailureCode?,
    ) : CapabilityEnvelopeError

    data class OutputStateConflict(val state: CapabilityResultState) : CapabilityEnvelopeError

    data class EvidenceStateConflict(
        val state: CapabilityResultState,
        val evidence: CapabilityOutcomeEvidence,
    ) : CapabilityEnvelopeError

    data class UnsupportedSchemaVersion(val found: String) : CapabilityEnvelopeError

    data class InvalidDocument(val path: String) : CapabilityEnvelopeError
}

private val canonicalUuidPattern = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
)

private fun <T> parseEnvelopeUuid(
    raw: String,
    field: String,
    construct: (String) -> T,
): CapabilityEnvelopeResult<T> = if (canonicalUuidPattern.matches(raw)) {
    CapabilityEnvelopeResult.Success(construct(raw))
} else {
    CapabilityEnvelopeResult.Failure(CapabilityEnvelopeError.InvalidIdentifier(field))
}
