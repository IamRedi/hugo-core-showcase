package io.redi.robot.discovery.api

import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryObservationTest {
    @Test
    fun `valid observation is passive evidence with bounded freshness`() {
        val observation = observation()

        assertEquals("bluetooth.le", observation.sourceType.value)
        assertEquals(ProviderFamily.BODY_ADAPTER, observation.providerFamilyHint)
        assertTrue(observation.isFreshAt(time(149)))
        assertFalse(observation.isFreshAt(time(150)))
    }

    @Test
    fun `unknown provider observation permits missing optional hints and metadata`() {
        val observation = successful(
            DiscoveryObservation.create(
                observationId = observationId("123e4567-e89b-12d3-a456-426614174000"),
                sourceType = source("network.mdns"),
                observingHost = host("mobile_host"),
                observedAt = time(20),
                expiresAt = time(30),
                displayHint = null,
                providerFamilyHint = null,
                protocolServiceHints = emptyList(),
                evidence = listOf(evidence("passive.result", 20, null)),
                confidence = confidence(0.0),
                privacyClassification = DiscoveryPrivacyClassification.PUBLIC,
                sourceLocalReference = null,
                metadata = emptyMap(),
            ),
        )

        assertNull(observation.displayHint)
        assertNull(observation.providerFamilyHint)
        assertTrue(observation.protocolServiceHints.isEmpty())
        assertTrue(observation.metadata.isEmpty())
    }

    @Test
    fun `observation defensively copies optional collections`() {
        val hints = mutableListOf(hint("service.catalog"))
        val evidence = mutableListOf(evidence("passive.result", 100, "Possible mobile body"))
        val metadata = mutableMapOf(key("device.class") to metadataValue("mobile_body"))
        val observation = successful(
            DiscoveryObservation.create(
                observationId = observationId("123e4567-e89b-12d3-a456-426614174000"),
                sourceType = source("bluetooth.le"),
                observingHost = host("mobile_host"),
                observedAt = time(100),
                expiresAt = time(150),
                displayHint = hintDisplay("Possible mobile body"),
                providerFamilyHint = ProviderFamily.BODY_ADAPTER,
                protocolServiceHints = hints,
                evidence = evidence,
                confidence = confidence(0.6),
                privacyClassification = DiscoveryPrivacyClassification.PRIVATE,
                sourceLocalReference = opaque("local_7f3"),
                metadata = metadata,
            ),
        )
        hints.clear()
        evidence.clear()
        metadata.clear()

        assertEquals(1, observation.protocolServiceHints.size)
        assertEquals(1, observation.evidence.size)
        assertEquals(1, observation.metadata.size)
    }

    @Test
    fun `confidence expiry and duplicate evidence validation fail closed`() {
        assertTrue(DiscoveryConfidence.create(-0.01) is DiscoveryObservationResult.Failure)
        assertTrue(DiscoveryConfidence.create(1.01) is DiscoveryObservationResult.Failure)
        assertTrue(DiscoveryConfidence.create(Double.NaN) is DiscoveryObservationResult.Failure)

        assertEquals(
            DiscoveryObservationResult.Failure(DiscoveryObservationError.InvalidExpiry),
            createObservation(expiresAt = time(100)),
        )
        val duplicate = evidence("passive.result", 100, null)
        assertEquals(
            DiscoveryObservationResult.Failure(DiscoveryObservationError.DuplicateEvidence),
            createObservation(evidence = listOf(duplicate, duplicate)),
        )
    }

    @Test
    fun `duplicate observations remain independent evidence until identity resolution`() {
        val first = observation("123e4567-e89b-12d3-a456-426614174000")
        val second = observation("223e4567-e89b-12d3-a456-426614174000")

        assertNotEquals(first.observationId, second.observationId)
        assertEquals(first.sourceType, second.sourceType)
        assertEquals(first.sourceLocalReference, second.sourceLocalReference)
    }

    @Test
    fun `stable identifiers cannot enter display normalized metadata or opaque references`() {
        assertEquals(
            DiscoveryObservationResult.Failure(DiscoveryObservationError.InvalidDisplayHint),
            DiscoveryDisplayHint.create("Device AA:BB:CC:DD:EE:FF"),
        )
        assertEquals(
            DiscoveryObservationResult.Failure(DiscoveryObservationError.InvalidEvidenceSummary),
            DiscoveryEvidenceSummary.create("peer 192.0.2.1"),
        )
        assertEquals(
            DiscoveryObservationResult.Failure(DiscoveryObservationError.PrivateMetadataKey),
            DiscoveryMetadataKey.parse("transport_address"),
        )
        assertEquals(
            DiscoveryObservationResult.Failure(DiscoveryObservationError.InvalidOpaqueSourceReference),
            OpaqueDiscoveryReference.parse("AA:BB:CC:DD:EE:FF"),
        )
    }

    @Test
    fun `malformed identifiers and public local references fail closed`() {
        assertTrue(DiscoveryObservationId.parse("not-a-uuid") is DiscoveryObservationResult.Failure)
        assertTrue(DiscoverySourceType.parse("Bluetooth LE") is DiscoveryObservationResult.Failure)
        assertTrue(DiscoveryHostReference.parse("phone.local") is DiscoveryObservationResult.Failure)
        assertEquals(
            DiscoveryObservationResult.Failure(DiscoveryObservationError.PublicPrivacyWithLocalReference),
            createObservation(
                privacyClassification = DiscoveryPrivacyClassification.PUBLIC,
                sourceLocalReference = opaque("local_7f3"),
            ),
        )
    }

    @Test
    fun `strict codec round trip rejects unknown fields and unsupported versions`() {
        val observation = observation()
        val encoded = DiscoveryObservationCodec.encode(observation)

        assertEquals(encoded, DiscoveryObservationCodec.encode(successful(DiscoveryObservationCodec.decode(encoded))))
        assertEquals(
            DiscoveryObservationResult.Failure(
                DiscoveryObservationError.UnsupportedSchemaVersion("2.0"),
            ),
            DiscoveryObservationCodec.decode(encoded.replace("\"1.0\"", "\"2.0\"")),
        )
        assertEquals(
            DiscoveryObservationResult.Failure(
                DiscoveryObservationError.UnexpectedFields(sortedSetOf("connectionState")),
            ),
            DiscoveryObservationCodec.decode(encoded.dropLast(1) + ",\"connectionState\":\"connected\"}"),
        )
    }

    @Test
    fun `redacted projection cannot imply identity trust permission or connection`() {
        val observation = observation()
        val redacted = observation.redacted()

        assertFalse(redacted.toString().contains(observation.sourceLocalReference!!.value))
        assertEquals(observation.observationId, redacted.observationId)
        assertEquals(observation.providerFamilyHint, redacted.providerFamilyHint)
        assertTrue(redacted.evidence.isNotEmpty())
    }

    private fun observation(
        id: String = "123e4567-e89b-12d3-a456-426614174000",
    ): DiscoveryObservation = successful(createObservation(observationId = observationId(id)))

    private fun createObservation(
        observationId: DiscoveryObservationId = observationId("123e4567-e89b-12d3-a456-426614174000"),
        expiresAt: MonotonicTimestampMs = time(150),
        evidence: List<DiscoveryEvidence> = listOf(evidence("passive.result", 100, "Possible mobile body")),
        privacyClassification: DiscoveryPrivacyClassification = DiscoveryPrivacyClassification.PRIVATE,
        sourceLocalReference: OpaqueDiscoveryReference? = opaque("local_7f3"),
    ): DiscoveryObservationResult<DiscoveryObservation> = DiscoveryObservation.create(
        observationId = observationId,
        sourceType = source("bluetooth.le"),
        observingHost = host("mobile_host"),
        observedAt = time(100),
        expiresAt = expiresAt,
        displayHint = hintDisplay("Possible mobile body"),
        providerFamilyHint = ProviderFamily.BODY_ADAPTER,
        protocolServiceHints = listOf(hint("service.catalog")),
        evidence = evidence,
        confidence = confidence(0.6),
        privacyClassification = privacyClassification,
        sourceLocalReference = sourceLocalReference,
        metadata = mapOf(key("device.class") to metadataValue("mobile_body")),
    )

    private fun observationId(value: String): DiscoveryObservationId = successful(
        DiscoveryObservationId.parse(value),
    )

    private fun source(value: String): DiscoverySourceType = successful(DiscoverySourceType.parse(value))

    private fun host(value: String): DiscoveryHostReference = successful(DiscoveryHostReference.parse(value))

    private fun hintDisplay(value: String): DiscoveryDisplayHint = successful(DiscoveryDisplayHint.create(value))

    private fun hint(value: String): DiscoveryProtocolServiceHint = successful(
        DiscoveryProtocolServiceHint.parse(value),
    )

    private fun evidence(kind: String, observedAt: Long, summary: String?): DiscoveryEvidence = DiscoveryEvidence(
        kind = successful(DiscoveryEvidenceKind.parse(kind)),
        observedAt = time(observedAt),
        summary = summary?.let { successful(DiscoveryEvidenceSummary.create(it)) },
    )

    private fun confidence(value: Double): DiscoveryConfidence = successful(DiscoveryConfidence.create(value))

    private fun opaque(value: String): OpaqueDiscoveryReference = successful(OpaqueDiscoveryReference.parse(value))

    private fun key(value: String): DiscoveryMetadataKey = successful(DiscoveryMetadataKey.parse(value))

    private fun metadataValue(value: String): DiscoveryMetadataValue = successful(DiscoveryMetadataValue.create(value))

    private fun time(value: Long): MonotonicTimestampMs = successful(MonotonicTimestampMs.create(value))

    private fun <T> successful(result: DiscoveryObservationResult<T>): T {
        assertTrue(result is DiscoveryObservationResult.Success)
        return (result as DiscoveryObservationResult.Success).value
    }

    private fun <T> successful(result: io.redi.robot.capability.api.CapabilityEnvelopeResult<T>): T {
        assertTrue(result is io.redi.robot.capability.api.CapabilityEnvelopeResult.Success)
        return (result as io.redi.robot.capability.api.CapabilityEnvelopeResult.Success).value
    }
}
