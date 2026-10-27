package io.redi.robot.discovery.identity

import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.capability.registry.InMemoryProviderRegistry
import io.redi.robot.discovery.api.DiscoveryConfidence
import io.redi.robot.discovery.api.DiscoveryDisplayHint
import io.redi.robot.discovery.api.DiscoveryEvidence
import io.redi.robot.discovery.api.DiscoveryEvidenceKind
import io.redi.robot.discovery.api.DiscoveryHostReference
import io.redi.robot.discovery.api.DiscoveryObservation
import io.redi.robot.discovery.api.DiscoveryObservationId
import io.redi.robot.discovery.api.DiscoveryObservationResult
import io.redi.robot.discovery.api.DiscoveryPrivacyClassification
import io.redi.robot.discovery.api.DiscoverySourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityResolverTest {
    private val firstKnown = identity(
        definition = "redi.looi/mobile-body",
        instance = "123e4567-e89b-12d3-a456-426614174111",
        alias = "Living room LOOI",
    )
    private val secondKnown = identity(
        definition = "redi.looi/mobile-body",
        instance = "223e4567-e89b-12d3-a456-426614174222",
        alias = "Living room LOOI",
    )
    private val knownProviders = KnownProviderIdentitySnapshot.fromIdentities(listOf(firstKnown, secondKnown))

    @Test
    fun `strong previously verified evidence matches one known provider`() {
        val observed = observation()
        val result = resolve(
            listOf(observed),
            listOf(
                evidence(
                    id = 1,
                    observations = listOf(observed),
                    type = IdentityEvidenceType.VERIFIED_PROVIDER_BINDING,
                    target = target(firstKnown),
                ),
            ),
        )

        assertEquals(IdentityResolutionStatus.MATCHED_KNOWN_PROVIDER, result.status)
        assertEquals(firstKnown.instanceId, (result.candidates.single() as KnownProviderIdentityCandidate).identity.instanceId)
    }

    @Test
    fun `BLE name alone is insufficient even at maximum confidence`() {
        val observed = observation(display = "Living room LOOI")
        val result = resolve(
            listOf(observed),
            listOf(
                evidence(
                    id = 1,
                    observations = listOf(observed),
                    type = IdentityEvidenceType.DISPLAY_NAME_HINT,
                    target = target(firstKnown),
                    confidence = 1.0,
                ),
            ),
        )

        assertEquals(IdentityResolutionStatus.INSUFFICIENT_EVIDENCE, result.status)
        assertNotEquals(IdentityResolutionStatus.MATCHED_KNOWN_PROVIDER, result.status)
    }

    @Test
    fun `multiple observations can support the same provider without being merged into identity`() {
        val first = observation(id = 1, source = "bluetooth.le")
        val second = observation(id = 2, source = "bluetooth.le")
        val result = resolve(
            listOf(first, second),
            listOf(
                evidence(
                    id = 1,
                    observations = listOf(first, second),
                    type = IdentityEvidenceType.VERIFIED_PROVIDER_BINDING,
                    target = target(firstKnown),
                ),
            ),
        )

        assertEquals(IdentityResolutionStatus.MATCHED_KNOWN_PROVIDER, result.status)
        assertEquals(2, result.observationIds.size)
        assertEquals(1, result.candidates.size)
    }

    @Test
    fun `identical display names for two providers remain ambiguous`() {
        val first = observation(id = 1, display = "Living room LOOI")
        val second = observation(id = 2, display = "Living room LOOI")
        val result = resolve(
            listOf(first, second),
            listOf(
                evidence(1, listOf(first), IdentityEvidenceType.DISPLAY_NAME_HINT, target(firstKnown)),
                evidence(2, listOf(second), IdentityEvidenceType.DISPLAY_NAME_HINT, target(secondKnown)),
            ),
        )

        assertEquals(IdentityResolutionStatus.AMBIGUOUS, result.status)
        assertEquals(2, result.candidates.size)
    }

    @Test
    fun `conflicting strong evidence prevents a provider match`() {
        val observed = observation()
        val supportId = evidenceId(1)
        val excludeId = evidenceId(2)
        val support = evidence(
            id = 1,
            observations = listOf(observed),
            type = IdentityEvidenceType.VERIFIED_PROVIDER_BINDING,
            target = target(firstKnown),
            contradictions = setOf(excludeId),
        )
        val exclusion = evidence(
            id = 2,
            observations = listOf(observed),
            type = IdentityEvidenceType.VERIFIED_PROVIDER_BINDING,
            target = target(firstKnown),
            disposition = IdentityEvidenceDisposition.EXCLUDES,
            contradictions = setOf(supportId),
        )

        val result = resolve(listOf(observed), listOf(support, exclusion))

        assertEquals(IdentityResolutionStatus.CONFLICTING_EVIDENCE, result.status)
    }

    @Test
    fun `expired observations and evidence cannot prove identity`() {
        val observed = observation(expiresAt = 150)
        val result = resolve(
            listOf(observed),
            listOf(evidence(1, listOf(observed), IdentityEvidenceType.VERIFIED_PROVIDER_BINDING, target(firstKnown))),
            now = 150,
        )

        assertEquals(IdentityResolutionStatus.STALE_EVIDENCE, result.status)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun `mixed discovery sources may support an authenticated known-provider binding`() {
        val ble = observation(id = 1, source = "bluetooth.le")
        val service = observation(id = 2, source = "service.integration")
        val result = resolve(
            listOf(service, ble),
            listOf(
                evidence(
                    id = 1,
                    observations = listOf(ble, service),
                    type = IdentityEvidenceType.AUTHENTICATED_INTEGRATION_BINDING,
                    target = target(firstKnown),
                    verification = IdentityVerificationLevel.AUTHENTICATED,
                ),
            ),
        )

        assertEquals(IdentityResolutionStatus.MATCHED_KNOWN_PROVIDER, result.status)
    }

    @Test
    fun `definition evidence yields a new candidate but never creates an instance identity`() {
        val observed = observation()
        val definition = definition("vendor.printer/model-x")
        val result = resolve(
            listOf(observed),
            listOf(
                evidence(
                    id = 1,
                    observations = listOf(observed),
                    type = IdentityEvidenceType.PROVIDER_DEFINITION_BINDING,
                    target = target(definitionId = definition, family = ProviderFamily.DEVICE_ADAPTER),
                    verification = IdentityVerificationLevel.SOURCE_ASSERTED,
                ),
            ),
            known = KnownProviderIdentitySnapshot.fromIdentities(emptyList()),
        )

        assertEquals(IdentityResolutionStatus.NEW_PROVIDER_CANDIDATE, result.status)
        val candidate = result.candidates.single() as NewProviderIdentityCandidate
        assertEquals(definition, candidate.definitionId)
        assertFalse(candidate.toString().contains("instanceId"))
    }

    @Test
    fun `protected identifier remains opaque and redacted from diagnostics`() {
        assertTrue(
            ProtectedIdentityEvidenceReference.parse("AA:BB:CC:DD:EE:FF") is IdentityEvidenceResult.Failure,
        )
        assertTrue(
            ProtectedIdentityEvidenceReference.parse("192.0.2.1") is IdentityEvidenceResult.Failure,
        )
        assertTrue(
            ProtectedIdentityEvidenceReference.parse("account@example.com") is IdentityEvidenceResult.Failure,
        )
        val observed = observation()
        val item = evidence(
            id = 1,
            observations = listOf(observed),
            type = IdentityEvidenceType.PROTECTED_IDENTIFIER_BINDING,
            target = target(firstKnown),
            verification = IdentityVerificationLevel.PREVIOUSLY_VERIFIED,
        )
        val token = item.protectedValueReference!!.value

        assertFalse(item.toString().contains(token))
        assertFalse(item.redacted().toString().contains(token))
        assertFalse(resolve(listOf(observed), listOf(item)).toString().contains(firstKnown.instanceId.value))
    }

    @Test
    fun `identity result has no trust permission registration or connection implication`() {
        val fieldNames = IdentityResolution::class.java.declaredFields.map { it.name.lowercase() }

        assertTrue(fieldNames.none { it.contains("trust") })
        assertTrue(fieldNames.none { it.contains("permission") || it.contains("authoriz") })
        assertTrue(fieldNames.none { it.contains("connection") || it.contains("registered") })
    }

    @Test
    fun `resolver reads registry snapshot without automatic mutation`() {
        val registry = InMemoryProviderRegistry()
        val observed = observation()
        val before = registry.snapshot().entries
        val result = IdentityResolver.resolve(
            request(
                listOf(observed),
                listOf(
                    evidence(
                        id = 1,
                        observations = listOf(observed),
                        type = IdentityEvidenceType.PROVIDER_DEFINITION_BINDING,
                        target = target(
                            definitionId = definition("vendor.printer/model-x"),
                            family = ProviderFamily.DEVICE_ADAPTER,
                        ),
                        verification = IdentityVerificationLevel.SOURCE_ASSERTED,
                    ),
                ),
            ),
            registry.snapshot(),
        )

        assertEquals(IdentityResolutionStatus.NEW_PROVIDER_CANDIDATE, result.status)
        assertEquals(before, registry.snapshot().entries)
    }

    @Test
    fun `resolution is deterministic independent of observation and evidence ordering`() {
        val first = observation(id = 1, source = "bluetooth.le")
        val second = observation(id = 2, source = "service.integration")
        val firstEvidence = evidence(
            1,
            listOf(first),
            IdentityEvidenceType.DISPLAY_NAME_HINT,
            target(firstKnown),
        )
        val strongEvidence = evidence(
            2,
            listOf(second),
            IdentityEvidenceType.AUTHENTICATED_INTEGRATION_BINDING,
            target(firstKnown),
            verification = IdentityVerificationLevel.AUTHENTICATED,
        )

        val ordered = resolve(listOf(first, second), listOf(firstEvidence, strongEvidence)).redacted()
        val reversed = resolve(listOf(second, first), listOf(strongEvidence, firstEvidence)).redacted()

        assertEquals(ordered, reversed)
    }

    @Test
    fun `strict evidence codec round trip rejects version unknown fields and false strength`() {
        val observed = observation()
        val item = evidence(1, listOf(observed), IdentityEvidenceType.VERIFIED_PROVIDER_BINDING, target(firstKnown))
        val encoded = IdentityEvidenceCodec.encode(item)

        assertEquals(encoded, IdentityEvidenceCodec.encode(successful(IdentityEvidenceCodec.decode(encoded))))
        assertEquals(
            IdentityEvidenceResult.Failure(IdentityEvidenceError.UnsupportedSchemaVersion("2.0")),
            IdentityEvidenceCodec.decode(encoded.replace("\"1.0\"", "\"2.0\"")),
        )
        assertTrue(
            IdentityEvidenceCodec.decode(encoded.dropLast(1) + ",\"trust\":true}") is IdentityEvidenceResult.Failure,
        )
        assertEquals(
            IdentityEvidenceResult.Failure(IdentityEvidenceError.InvalidDocument("strength")),
            IdentityEvidenceCodec.decode(encoded.replace("\"strong\"", "\"weak\"")),
        )
    }

    private fun resolve(
        observations: List<DiscoveryObservation>,
        evidence: List<IdentityEvidence>,
        now: Long = 150,
        known: KnownProviderIdentitySnapshot = knownProviders,
    ): IdentityResolution = IdentityResolver.resolve(request(observations, evidence, now), known)

    private fun request(
        observations: List<DiscoveryObservation>,
        evidence: List<IdentityEvidence>,
        now: Long = 150,
    ): IdentityResolutionRequest = IdentityResolutionRequest.create(
        observations = observations,
        evidence = evidence,
        evaluationTimes = observations.associate { it.observingHost to time(now) },
    )

    private fun observation(
        id: Int = 1,
        source: String = "bluetooth.le",
        display: String = "Possible provider",
        expiresAt: Long = 200,
    ): DiscoveryObservation = successful(
        DiscoveryObservation.create(
            observationId = observationId(id),
            sourceType = source(source),
            observingHost = host(),
            observedAt = time(100),
            expiresAt = time(expiresAt),
            displayHint = successful(DiscoveryDisplayHint.create(display)),
            providerFamilyHint = ProviderFamily.BODY_ADAPTER,
            protocolServiceHints = emptyList(),
            evidence = listOf(
                DiscoveryEvidence(
                    kind = successful(DiscoveryEvidenceKind.parse("passive.result")),
                    observedAt = time(100),
                    summary = null,
                ),
            ),
            confidence = confidence(0.5),
            privacyClassification = DiscoveryPrivacyClassification.PUBLIC,
            sourceLocalReference = null,
            metadata = emptyMap(),
        ),
    )

    @Suppress("LongParameterList")
    private fun evidence(
        id: Int,
        observations: List<DiscoveryObservation>,
        type: IdentityEvidenceType,
        target: IdentityEvidenceTarget,
        disposition: IdentityEvidenceDisposition = IdentityEvidenceDisposition.SUPPORTS,
        contradictions: Set<IdentityEvidenceId> = emptySet(),
        confidence: Double = 0.5,
        verification: IdentityVerificationLevel = defaultVerification(type),
        expiresAt: Long = 200,
    ): IdentityEvidence = successful(
        IdentityEvidence.create(
            evidenceId = evidenceId(id),
            observationIds = observations.map { it.observationId }.toSet(),
            provenance = successful(
                IdentityEvidenceProvenance.create(
                    observingHost = host(),
                    sourceTypes = observations.map { it.sourceType }.toSet(),
                    producer = successful(IdentityEvidenceProducer.parse("hugo.identity.resolver.fixture")),
                ),
            ),
            evidenceType = type,
            confidence = confidence(confidence),
            verificationLevel = verification,
            protectedValueReference = if (type.requiresProtectedReference) protectedReference(id) else null,
            disposition = disposition,
            target = target,
            contradictions = contradictions,
            observedAt = time(110),
            expiresAt = time(expiresAt),
        ),
    )

    private fun defaultVerification(type: IdentityEvidenceType): IdentityVerificationLevel = when (type) {
        IdentityEvidenceType.DISPLAY_NAME_HINT,
        IdentityEvidenceType.MANUFACTURER_HINT,
        -> IdentityVerificationLevel.UNVERIFIED
        IdentityEvidenceType.PROTOCOL_CLASSIFICATION,
        IdentityEvidenceType.PROVIDER_DEFINITION_BINDING,
        -> IdentityVerificationLevel.SOURCE_ASSERTED
        IdentityEvidenceType.AUTHENTICATED_INTEGRATION_BINDING -> IdentityVerificationLevel.AUTHENTICATED
        IdentityEvidenceType.PROTECTED_IDENTIFIER_BINDING,
        IdentityEvidenceType.VERIFIED_PROVIDER_BINDING,
        -> IdentityVerificationLevel.PREVIOUSLY_VERIFIED
        IdentityEvidenceType.MANUAL_CONFIRMATION -> IdentityVerificationLevel.MANUALLY_CONFIRMED
    }

    private fun identity(definition: String, instance: String, alias: String): ProviderIdentity = ProviderIdentity(
        definitionId = this.definition(definition),
        instanceId = successfulProvider(ProviderInstanceId.parse(instance)),
        family = ProviderFamily.BODY_ADAPTER,
        displayAlias = successfulProvider(RedactedProviderAlias.parse(alias)),
    )

    private fun target(identity: ProviderIdentity): IdentityEvidenceTarget = target(
        definitionId = identity.definitionId,
        instanceId = identity.instanceId,
        family = identity.family,
    )

    private fun target(
        definitionId: ProviderDefinitionId?,
        instanceId: ProviderInstanceId? = null,
        family: ProviderFamily?,
    ): IdentityEvidenceTarget = successful(IdentityEvidenceTarget.create(definitionId, instanceId, family))

    private fun definition(raw: String): ProviderDefinitionId = successfulProvider(ProviderDefinitionId.parse(raw))

    private fun observationId(id: Int): DiscoveryObservationId = successful(
        DiscoveryObservationId.parse("00000000-0000-0000-0000-${id.toString().padStart(12, '0')}"),
    )

    private fun evidenceId(id: Int): IdentityEvidenceId = successful(
        IdentityEvidenceId.parse("10000000-0000-0000-0000-${id.toString().padStart(12, '0')}"),
    )

    private fun protectedReference(id: Int): ProtectedIdentityEvidenceReference = successful(
        ProtectedIdentityEvidenceReference.parse("iref_${('a'.code + id).toChar()}aaaaaaaaaaaaaaa"),
    )

    private fun host(): DiscoveryHostReference = successful(DiscoveryHostReference.parse("mobile_host"))

    private fun source(raw: String): DiscoverySourceType = successful(DiscoverySourceType.parse(raw))

    private fun confidence(raw: Double): DiscoveryConfidence = successful(DiscoveryConfidence.create(raw))

    private fun time(raw: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(raw)) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> throw AssertionError(result.error)
    }

    private fun <T> successful(result: IdentityEvidenceResult<T>): T {
        assertTrue(result is IdentityEvidenceResult.Success)
        return (result as IdentityEvidenceResult.Success).value
    }

    private fun <T> successful(result: DiscoveryObservationResult<T>): T {
        assertTrue(result is DiscoveryObservationResult.Success)
        return (result as DiscoveryObservationResult.Success).value
    }

    private fun <T> successfulProvider(result: ProviderIdentityResult<T>): T {
        assertTrue(result is ProviderIdentityResult.Success)
        return (result as ProviderIdentityResult.Success).value
    }
}
