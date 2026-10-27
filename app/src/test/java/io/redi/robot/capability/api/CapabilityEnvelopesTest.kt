package io.redi.robot.capability.api

import io.redi.robot.capability.provider.ProviderInstanceId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityEnvelopesTest {
    private val requestUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    private val providerUuid = UUID.fromString("223e4567-e89b-12d3-a456-426614174000")
    private val auditUuid = UUID.fromString("323e4567-e89b-12d3-a456-426614174000")
    private val authorizationUuid = UUID.fromString("423e4567-e89b-12d3-a456-426614174000")

    @Test
    fun `valid request and completed result serialize deterministically and correlate`() {
        val request = request()
        val result = result(request = request)

        val decodedRequest = successful(CapabilityEnvelopeCodec.decodeRequest(
            CapabilityEnvelopeCodec.encodeRequest(request),
        ))
        val decodedResult = successful(CapabilityEnvelopeCodec.decodeResult(
            CapabilityEnvelopeCodec.encodeResult(result),
        ))

        assertEquals(
            CapabilityEnvelopeCodec.encodeRequest(request),
            CapabilityEnvelopeCodec.encodeRequest(decodedRequest),
        )
        assertEquals(
            CapabilityEnvelopeCodec.encodeResult(result),
            CapabilityEnvelopeCodec.encodeResult(decodedResult),
        )
        assertEquals(CapabilityResultCorrelation.MATCHED, decodedResult.correlationWith(decodedRequest))
        assertTrue(decodedResult.state.terminal)
    }

    @Test
    fun `strict codecs reject unknown fields and malformed IDs`() {
        val requestJson = CapabilityEnvelopeCodec.encodeRequest(request())
        val withUnknown = requestJson.dropLast(1) + ",\"uiState\":\"pressed\"}"
        assertTrue(CapabilityEnvelopeCodec.decodeRequest(withUnknown) is CapabilityEnvelopeResult.Failure)

        val malformedId = requestJson.replace(requestUuid.toString(), "not-a-request-id")
        assertEquals(
            CapabilityEnvelopeResult.Failure(
                CapabilityEnvelopeError.InvalidIdentifier("requestId"),
            ),
            CapabilityEnvelopeCodec.decodeRequest(malformedId),
        )
    }

    @Test
    fun `deadline is strictly after monotonic creation time`() {
        val result = CapabilityRequest.create(
            requestId = CapabilityRequestId.fromUuid(requestUuid),
            capability = capability("body.move@1.0"),
            providerTarget = CapabilityProviderTarget.AnyCompatible,
            actor = CapabilityActorKind.LOCAL_USER,
            executionContext = context(1, 1),
            createdAt = time(100),
            deadline = time(100),
            authorizationReference = null,
            auditId = CapabilityAuditId.fromUuid(auditUuid),
            input = null,
        )

        assertEquals(
            CapabilityEnvelopeResult.Failure(CapabilityEnvelopeError.InvalidDeadline(100, 100)),
            result,
        )
    }

    @Test
    fun `correlation identifies stale context incompatible capability provider and audit`() {
        val request = request()
        assertEquals(
            CapabilityResultCorrelation.EXECUTION_CONTEXT_MISMATCH,
            result(request, executionContext = context(2, 1)).correlationWith(request),
        )
        assertEquals(
            CapabilityResultCorrelation.CAPABILITY_MISMATCH,
            result(request, capability = capability("body.stop@1.0")).correlationWith(request),
        )
        assertEquals(
            CapabilityResultCorrelation.PROVIDER_TARGET_MISMATCH,
            result(
                request,
                provider = ProviderInstanceId.fromUuid(
                    UUID.fromString("523e4567-e89b-12d3-a456-426614174000"),
                ),
            ).correlationWith(request),
        )
        assertEquals(
            CapabilityResultCorrelation.AUDIT_ID_MISMATCH,
            result(
                request,
                auditId = CapabilityAuditId.fromUuid(
                    UUID.fromString("623e4567-e89b-12d3-a456-426614174000"),
                ),
            ).correlationWith(request),
        )
    }

    @Test
    fun `typed failure codes cover canonical rejection and failure classes`() {
        assertEquals(
            setOf(
                CapabilityFailureCode.INVALID_REQUEST,
                CapabilityFailureCode.UNSUPPORTED_CAPABILITY,
                CapabilityFailureCode.INCOMPATIBLE_VERSION,
                CapabilityFailureCode.PROVIDER_UNAVAILABLE,
                CapabilityFailureCode.PERMISSION_DENIED,
                CapabilityFailureCode.APPROVAL_REQUIRED,
                CapabilityFailureCode.SAFETY_REJECTED,
                CapabilityFailureCode.TIMEOUT,
                CapabilityFailureCode.CANCELLED,
                CapabilityFailureCode.TRANSPORT_FAILURE,
                CapabilityFailureCode.PROVIDER_FAILURE,
                CapabilityFailureCode.MALFORMED_RESULT,
                CapabilityFailureCode.UNKNOWN,
            ),
            CapabilityFailureCode.entries.toSet(),
        )
    }

    @Test
    fun `cancel timeout and unknown outcomes are terminal and strictly paired`() {
        val request = request()
        val cases = listOf(
            Triple(CapabilityResultState.CANCELLED, CapabilityFailureCode.CANCELLED, CapabilityOutcomeEvidence.LOCALLY_OBSERVED),
            Triple(CapabilityResultState.TIMED_OUT, CapabilityFailureCode.TIMEOUT, CapabilityOutcomeEvidence.LOCALLY_OBSERVED),
            Triple(CapabilityResultState.UNKNOWN, CapabilityFailureCode.UNKNOWN, CapabilityOutcomeEvidence.OUTCOME_UNKNOWN),
        )
        cases.forEach { (state, code, evidence) ->
            val value = successful(
                createResult(
                    request = request,
                    state = state,
                    failure = CapabilityFailure(code),
                    evidence = evidence,
                    output = null,
                ),
            )
            assertTrue(value.state.terminal)
        }

        assertTrue(
            createResult(
                request,
                state = CapabilityResultState.TIMED_OUT,
                failure = CapabilityFailure(CapabilityFailureCode.CANCELLED),
                evidence = CapabilityOutcomeEvidence.LOCALLY_OBSERVED,
                output = null,
            ) is CapabilityEnvelopeResult.Failure,
        )
    }

    @Test
    fun `nonterminal and completed states enforce honest failure output and evidence`() {
        val request = request()
        val accepted = successful(
            createResult(
                request,
                state = CapabilityResultState.ACCEPTED,
                failure = null,
                evidence = CapabilityOutcomeEvidence.REQUEST_ACCEPTED,
                output = null,
            ),
        )
        assertFalse(accepted.state.terminal)

        assertTrue(
            createResult(
                request,
                state = CapabilityResultState.COMPLETED,
                failure = null,
                evidence = CapabilityOutcomeEvidence.REQUEST_ACCEPTED,
                output = data(),
            ) is CapabilityEnvelopeResult.Failure,
        )
        assertTrue(
            createResult(
                request,
                state = CapabilityResultState.COMPLETED,
                failure = null,
                evidence = CapabilityOutcomeEvidence.PROVIDER_REPORTED,
                output = null,
            ) is CapabilityEnvelopeResult.Failure,
        )
    }

    @Test
    fun `semantic argument values are bounded finite and copied`() {
        assertTrue(
            CapabilityArgumentValue.Decimal.create(Double.NaN) is CapabilityEnvelopeResult.Failure,
        )
        val values = mutableMapOf(
            argumentName("linear") to successful(CapabilityArgumentValue.Decimal.create(0.5)),
        )
        val data = successful(CapabilityData.create(contract(), values))
        values.clear()
        assertEquals(1, data.values.size)
    }

    private fun request(): CapabilityRequest = successful(
        CapabilityRequest.create(
            requestId = CapabilityRequestId.fromUuid(requestUuid),
            capability = capability("body.move@1.0"),
            providerTarget = CapabilityProviderTarget.Exact(ProviderInstanceId.fromUuid(providerUuid)),
            actor = CapabilityActorKind.LOCAL_USER,
            executionContext = context(7, 11),
            createdAt = time(1_000),
            deadline = time(1_250),
            authorizationReference = CapabilityAuthorizationReference.fromUuid(authorizationUuid),
            auditId = CapabilityAuditId.fromUuid(auditUuid),
            input = data(),
        ),
    )

    private fun result(
        request: CapabilityRequest,
        capability: CapabilityReference = request.capability,
        provider: ProviderInstanceId = ProviderInstanceId.fromUuid(providerUuid),
        executionContext: CapabilityExecutionContext = request.executionContext,
        auditId: CapabilityAuditId = request.auditId,
    ): CapabilityResult = successful(
        CapabilityResult.create(
            requestId = request.requestId,
            capability = capability,
            providerInstanceId = provider,
            executionContext = executionContext,
            occurredAt = time(1_100),
            state = CapabilityResultState.COMPLETED,
            failure = null,
            evidence = CapabilityOutcomeEvidence.PROVIDER_REPORTED,
            output = data(),
            auditId = auditId,
        ),
    )

    private fun createResult(
        request: CapabilityRequest,
        state: CapabilityResultState,
        failure: CapabilityFailure?,
        evidence: CapabilityOutcomeEvidence,
        output: CapabilityData?,
    ): CapabilityEnvelopeResult<CapabilityResult> = CapabilityResult.create(
        requestId = request.requestId,
        capability = request.capability,
        providerInstanceId = ProviderInstanceId.fromUuid(providerUuid),
        executionContext = request.executionContext,
        occurredAt = time(1_100),
        state = state,
        failure = failure,
        evidence = evidence,
        output = output,
        auditId = request.auditId,
    )

    private fun data(): CapabilityData = successful(
        CapabilityData.create(
            contract = contract(),
            values = mapOf(
                argumentName("linear") to successful(CapabilityArgumentValue.Decimal.create(0.5)),
                argumentName("enabled") to CapabilityArgumentValue.Flag(true),
            ),
        ),
    )

    private fun contract(): CapabilityContractReference = when (
        val result = CapabilityContractReference.parse("hugo.contract.mobility_move_input@1.0")
    ) {
        is CapabilityDescriptorResult.Success -> result.value
        is CapabilityDescriptorResult.Failure -> error(result.error.toString())
    }

    private fun capability(value: String): CapabilityReference = when (
        val result = CapabilityReference.parse(value)
    ) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> error(result.error.toString())
    }

    private fun context(epoch: Long, sequence: Long): CapabilityExecutionContext = successful(
        CapabilityExecutionContext.create(epoch, sequence),
    )

    private fun time(value: Long): MonotonicTimestampMs = successful(
        MonotonicTimestampMs.create(value),
    )

    private fun argumentName(value: String): CapabilityArgumentName = successful(
        CapabilityArgumentName.parse(value),
    )

    private fun <T> successful(result: CapabilityEnvelopeResult<T>): T {
        assertTrue(result is CapabilityEnvelopeResult.Success)
        return (result as CapabilityEnvelopeResult.Success).value
    }
}
