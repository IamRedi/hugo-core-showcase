package io.redi.robot.context.api

import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextObservationCodecTest {
    @Test
    fun `known unknown and conflict claims round trip strictly`() {
        val claims = listOf(
            ContextObservationClaim.Known(ContextValue.TextValue(text("occupied"))),
            ContextObservationClaim.Unknown(ContextUnknownReason.SOURCE_UNAVAILABLE),
            successful(
                ContextObservationClaim.Conflict.create(
                    listOf(
                        ContextValue.SymbolValue(symbol("presence.home")),
                        ContextValue.SymbolValue(symbol("presence.away")),
                    ),
                ),
            ),
        )

        claims.forEachIndexed { index, claim ->
            val encoded = ContextObservationCodec.encode(observation(index, claim))
            val decoded = successful(ContextObservationCodec.decode(encoded))
            assertEquals(encoded, ContextObservationCodec.encode(decoded))
            assertEquals(claim, decoded.claim)
        }
    }

    @Test
    fun `equivalent conflict input produces deterministic encoding`() {
        val home = ContextValue.SymbolValue(symbol("presence.home"))
        val away = ContextValue.SymbolValue(symbol("presence.away"))
        val first = successful(ContextObservationClaim.Conflict.create(listOf(home, away)))
        val second = successful(ContextObservationClaim.Conflict.create(listOf(away, home)))

        assertEquals(
            ContextObservationCodec.encode(observation(0, first)),
            ContextObservationCodec.encode(observation(0, second)),
        )
    }

    @Test
    fun `codec rejects unsupported missing unexpected and malformed fields`() {
        val encoded = ContextObservationCodec.encode(
            observation(0, ContextObservationClaim.Known(ContextValue.IntegerValue(12))),
        )

        assertEquals(
            ContextObservationResult.Failure(ContextObservationError.UnsupportedSchemaVersion("2.0")),
            ContextObservationCodec.decode(encoded.replace("\"1.0\"", "\"2.0\"")),
        )
        assertEquals(
            ContextObservationResult.Failure(
                ContextObservationError.UnexpectedFields(sortedSetOf("isFact")),
            ),
            ContextObservationCodec.decode(encoded.dropLast(1) + ",\"isFact\":true}"),
        )
        assertEquals(
            ContextObservationResult.Failure(ContextObservationError.MissingField("sourceReference")),
            ContextObservationCodec.decode(encoded.replace(Regex(",\"sourceReference\":\"[^\"]+\""), "")),
        )
        assertTrue(
            ContextObservationCodec.decode(encoded.replace("\"confidencePermille\":900", "\"confidencePermille\":1001"))
                is ContextObservationResult.Failure,
        )
    }

    @Test
    fun `claim shape cannot smuggle value into unknown or authority into root`() {
        val unknown = ContextObservationCodec.encode(
            observation(0, ContextObservationClaim.Unknown(ContextUnknownReason.NOT_OBSERVED)),
        )
        val withValue = unknown.replace(
            "\"reason\":\"not_observed\"",
            "\"reason\":\"not_observed\",\"value\":{\"type\":\"boolean\",\"value\":true}",
        )

        assertEquals(
            ContextObservationResult.Failure(ContextObservationError.InvalidDocument("claim")),
            ContextObservationCodec.decode(withValue),
        )
        assertTrue(ContextObservationCodec.decode("{}") is ContextObservationResult.Failure)
    }

    private fun observation(index: Int, claim: ContextObservationClaim): ContextObservation = successful(
        ContextObservation.create(
            observationId = successful(
                ContextObservationId.parse("${index + 1}23e4567-e89b-12d3-a456-426614174000"),
            ),
            sourceType = successful(ContextSourceType.parse("host.sensor")),
            sourceReference = successful(ContextSourceReference.parse("host_sensor")),
            key = successful(ContextKey.parse("presence.user_nearby")),
            claim = claim,
            confidence = successful(ContextConfidence.create(900)),
            privacyClassification = ContextPrivacyClassification.SENSITIVE,
            observedAt = time(100),
            expiresAt = time(150),
        ),
    )

    private fun text(value: String): ContextText = successful(ContextText.create(value))

    private fun symbol(value: String): ContextSymbol = successful(ContextSymbol.parse(value))

    private fun time(value: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(value)) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> error("invalid test time")
    }

    private fun <T> successful(result: ContextObservationResult<T>): T {
        assertTrue(result is ContextObservationResult.Success)
        return (result as ContextObservationResult.Success).value
    }
}
