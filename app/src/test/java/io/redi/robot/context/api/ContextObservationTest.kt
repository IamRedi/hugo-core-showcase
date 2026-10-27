package io.redi.robot.context.api

import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextObservationTest {
    @Test
    fun `known observation has explicit source privacy confidence and bounded freshness`() {
        val observation = observation(ContextObservationClaim.Known(ContextValue.BooleanValue(true)))

        assertEquals("host.sensor", observation.sourceType.value)
        assertEquals("host_sensor", observation.sourceReference.value)
        assertEquals("presence.user_nearby", observation.key.value)
        assertEquals(ContextPrivacyClassification.SENSITIVE, observation.privacyClassification)
        assertEquals(875, observation.confidence.permille)
        assertEquals(ContextFreshness.NOT_YET_VALID, observation.freshnessAt(time(99)))
        assertEquals(ContextFreshness.FRESH, observation.freshnessAt(time(100)))
        assertEquals(ContextFreshness.FRESH, observation.freshnessAt(time(149)))
        assertEquals(ContextFreshness.STALE, observation.freshnessAt(time(150)))
        assertFalse(observation.isFreshAt(time(150)))
    }

    @Test
    fun `unknown is an explicit observation and never invented as a value`() {
        val observation = observation(
            ContextObservationClaim.Unknown(ContextUnknownReason.PERMISSION_DENIED),
            confidence = confidence(1000),
        )

        val claim = observation.claim as ContextObservationClaim.Unknown
        assertEquals(ContextUnknownReason.PERMISSION_DENIED, claim.reason)
        assertEquals("unknown", claim.wireStatus)
    }

    @Test
    fun `conflict requires bounded unique candidates and orders them deterministically`() {
        val first = ContextValue.SymbolValue(symbol("presence.away"))
        val second = ContextValue.SymbolValue(symbol("presence.home"))
        val mutableCandidates = mutableListOf(second, first)
        val conflict = successful(ContextObservationClaim.Conflict.create(mutableCandidates))
        mutableCandidates.clear()

        assertEquals(listOf(first, second), conflict.candidates)
        assertEquals(
            ContextObservationResult.Failure(ContextObservationError.TooFewConflictCandidates),
            ContextObservationClaim.Conflict.create(listOf(first)),
        )
        assertEquals(
            ContextObservationResult.Failure(ContextObservationError.DuplicateConflictCandidate),
            ContextObservationClaim.Conflict.create(listOf(first, first)),
        )
        assertEquals(
            ContextObservationResult.Failure(ContextObservationError.TooManyConflictCandidates(8)),
            ContextObservationClaim.Conflict.create((0..8).map { ContextValue.IntegerValue(it.toLong()) }),
        )
    }

    @Test
    fun `invalid identities confidence values and freshness fail closed`() {
        assertTrue(ContextObservationId.parse("not-a-uuid") is ContextObservationResult.Failure)
        assertTrue(ContextSourceType.parse("Android Sensor") is ContextObservationResult.Failure)
        assertTrue(ContextSourceReference.parse("AA:BB:CC:DD:EE:FF") is ContextObservationResult.Failure)
        assertTrue(ContextKey.parse("Presence User") is ContextObservationResult.Failure)
        assertTrue(ContextConfidence.create(-1) is ContextObservationResult.Failure)
        assertTrue(ContextConfidence.create(1001) is ContextObservationResult.Failure)
        assertEquals(
            ContextObservationResult.Failure(ContextObservationError.InvalidFreshnessWindow),
            createObservation(expiresAt = time(100)),
        )
    }

    @Test
    fun `scalar values are canonical and bounded`() {
        assertEquals("12.5", successful(ContextValue.DecimalValue.parse("12.5")).canonicalValue)
        assertTrue(ContextValue.DecimalValue.parse("12.50") is ContextObservationResult.Failure)
        assertTrue(ContextValue.DecimalValue.parse("1.1234567") is ContextObservationResult.Failure)
        assertTrue(ContextValue.DecimalValue.parse("NaN") is ContextObservationResult.Failure)
        assertTrue(ContextText.create(" ") is ContextObservationResult.Failure)
        assertTrue(ContextSymbol.parse("Not Canonical") is ContextObservationResult.Failure)
        assertEquals("hello", successful(ContextText.create(" hello ")).value)
    }

    @Test
    fun `observation contract has no memory truth authority or runtime fields`() {
        val prohibited = setOf(
            "memory",
            "fact",
            "truth",
            "trust",
            "permission",
            "authorization",
            "connection",
            "provider",
            "execution",
            "selected",
        )
        val fieldNames = ContextObservation::class.java.declaredFields.map { it.name.lowercase() }

        assertTrue(fieldNames.none { field -> prohibited.any(field::contains) })
        assertEquals(ContextPrivacyClassification.RESTRICTED.wireValue, "restricted")
    }

    private fun observation(
        claim: ContextObservationClaim,
        confidence: ContextConfidence = confidence(875),
    ): ContextObservation = successful(createObservation(claim = claim, confidence = confidence))

    private fun createObservation(
        claim: ContextObservationClaim = ContextObservationClaim.Known(ContextValue.BooleanValue(true)),
        confidence: ContextConfidence = confidence(875),
        expiresAt: MonotonicTimestampMs = time(150),
    ): ContextObservationResult<ContextObservation> = ContextObservation.create(
        observationId = successful(ContextObservationId.parse("123e4567-e89b-12d3-a456-426614174000")),
        sourceType = successful(ContextSourceType.parse("host.sensor")),
        sourceReference = successful(ContextSourceReference.parse("host_sensor")),
        key = successful(ContextKey.parse("presence.user_nearby")),
        claim = claim,
        confidence = confidence,
        privacyClassification = ContextPrivacyClassification.SENSITIVE,
        observedAt = time(100),
        expiresAt = expiresAt,
    )

    private fun symbol(value: String): ContextSymbol = successful(ContextSymbol.parse(value))

    private fun confidence(value: Int): ContextConfidence = successful(ContextConfidence.create(value))

    private fun time(value: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(value)) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> error("invalid test time")
    }

    private fun <T> successful(result: ContextObservationResult<T>): T {
        assertTrue(result is ContextObservationResult.Success)
        return (result as ContextObservationResult.Success).value
    }
}
