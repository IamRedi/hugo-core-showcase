package io.redi.robot.body.looi.firstlife

import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.discovery.api.DiscoveryConfidence
import io.redi.robot.discovery.api.DiscoveryEvidence
import io.redi.robot.discovery.api.DiscoveryEvidenceKind
import io.redi.robot.discovery.api.DiscoveryHostReference
import io.redi.robot.discovery.api.DiscoveryObservation
import io.redi.robot.discovery.api.DiscoveryObservationId
import io.redi.robot.discovery.api.DiscoveryObservationResult
import io.redi.robot.discovery.api.DiscoveryPrivacyClassification
import io.redi.robot.discovery.api.DiscoverySourceType
import io.redi.robot.discovery.identity.IdentityEvidence
import io.redi.robot.discovery.identity.IdentityEvidenceDisposition
import io.redi.robot.discovery.identity.IdentityEvidenceId
import io.redi.robot.discovery.identity.IdentityEvidenceProducer
import io.redi.robot.discovery.identity.IdentityEvidenceProvenance
import io.redi.robot.discovery.identity.IdentityEvidenceResult
import io.redi.robot.discovery.identity.IdentityEvidenceTarget
import io.redi.robot.discovery.identity.IdentityEvidenceType
import io.redi.robot.discovery.identity.IdentityVerificationLevel
import io.redi.robot.discovery.identity.ProtectedIdentityEvidenceReference
import java.util.UUID

data class LooiFirstLifeIdentityMapping(
    val observation: DiscoveryObservation,
    val evidence: IdentityEvidence,
)

/** H021 observation plus H022 protected known-instance mapping; neither value grants trust or use. */
object LooiFirstLifeIdentityMappingFactory {
    fun create(
        identity: ProviderIdentity,
        protectedBinding: ProtectedIdentityEvidenceReference,
        observedAt: MonotonicTimestampMs,
        verified: Boolean,
    ): LooiFirstLifeIdentityMapping {
        val expiresAt = time(observedAt.value + 60_000)
        val observation = discovery(DiscoveryObservation.create(
            observationId = DiscoveryObservationId.fromUuid(UUID.randomUUID()),
            sourceType = discovery(DiscoverySourceType.parse("bluetooth.le")),
            observingHost = discovery(DiscoveryHostReference.parse("android_local")),
            observedAt = observedAt,
            expiresAt = expiresAt,
            displayHint = null,
            providerFamilyHint = ProviderFamily.BODY_ADAPTER,
            protocolServiceHints = emptyList(),
            evidence = listOf(DiscoveryEvidence(discovery(DiscoveryEvidenceKind.parse("provider_hint")), observedAt, null)),
            confidence = discovery(DiscoveryConfidence.create(1.0)),
            privacyClassification = DiscoveryPrivacyClassification.PRIVATE,
            sourceLocalReference = null,
            metadata = emptyMap(),
        ))
        val evidence = identityEvidence(IdentityEvidence.create(
            evidenceId = IdentityEvidenceId.fromUuid(UUID.randomUUID()),
            observationIds = setOf(observation.observationId),
            provenance = identityEvidence(IdentityEvidenceProvenance.create(
                observation.observingHost,
                setOf(observation.sourceType),
                identityEvidence(IdentityEvidenceProducer.parse("hugo.looi.first-life.mapping")),
            )),
            evidenceType = if (verified) {
                IdentityEvidenceType.VERIFIED_PROVIDER_BINDING
            } else {
                IdentityEvidenceType.PROVIDER_DEFINITION_BINDING
            },
            confidence = observation.confidence,
            verificationLevel = if (verified) {
                IdentityVerificationLevel.PREVIOUSLY_VERIFIED
            } else {
                IdentityVerificationLevel.SOURCE_ASSERTED
            },
            protectedValueReference = protectedBinding.takeIf { verified },
            disposition = IdentityEvidenceDisposition.SUPPORTS,
            target = identityEvidence(IdentityEvidenceTarget.create(
                identity.definitionId,
                identity.instanceId.takeIf { verified },
                identity.family,
            )),
            contradictions = emptySet(),
            observedAt = observedAt,
            expiresAt = expiresAt,
        ))
        return LooiFirstLifeIdentityMapping(observation, evidence)
    }

    private fun time(value: Long) = when (val result = MonotonicTimestampMs.create(value)) {
        is io.redi.robot.capability.api.CapabilityEnvelopeResult.Success -> result.value
        is io.redi.robot.capability.api.CapabilityEnvelopeResult.Failure -> error(result.error)
    }
    private fun <T> discovery(result: DiscoveryObservationResult<T>) =
        (result as DiscoveryObservationResult.Success).value
    private fun <T> identityEvidence(result: IdentityEvidenceResult<T>) =
        (result as IdentityEvidenceResult.Success).value
}
