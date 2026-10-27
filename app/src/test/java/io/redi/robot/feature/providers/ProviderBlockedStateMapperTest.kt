package io.redi.robot.feature.providers

import io.redi.robot.adapter.compatibility.CompatibilityFindingCode
import io.redi.robot.adapter.compatibility.CompatibilityReportStatus
import io.redi.robot.capability.negotiation.CapabilityNegotiationReason
import io.redi.robot.capability.negotiation.CapabilityNegotiationStatus
import io.redi.robot.capability.provider.ProviderAvailabilityReason
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.connection.api.ConnectionAvailability
import io.redi.robot.connection.api.ConnectionAvailabilityReason
import io.redi.robot.discovery.identity.IdentityResolutionStatus
import io.redi.robot.manifest.resolution.ManifestResolutionStatus
import io.redi.robot.provider.calibration.CalibrationDomainResult
import io.redi.robot.provider.calibration.CalibrationItemId
import io.redi.robot.provider.calibration.CalibrationNegotiationReadinessStatus
import io.redi.robot.provider.calibration.CalibrationStatus
import io.redi.robot.provider.storage.ProviderInstanceKnowledgeStatus
import io.redi.robot.trust.runtime.AuthorizationDecisionStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderBlockedStateMapperTest {
    @Test
    fun `unknown ambiguous and conflicting identity remain distinct blocked states`() {
        assertKind(
            ProviderBlockedKind.IDENTITY_UNKNOWN,
            ProviderBlockedEvidence.Identity(IdentityResolutionStatus.INSUFFICIENT_EVIDENCE),
        )
        assertKind(
            ProviderBlockedKind.IDENTITY_AMBIGUOUS,
            ProviderBlockedEvidence.Identity(IdentityResolutionStatus.AMBIGUOUS),
        )
        assertKind(
            ProviderBlockedKind.IDENTITY_CONFLICT,
            ProviderBlockedEvidence.Identity(IdentityResolutionStatus.CONFLICTING_EVIDENCE),
        )
    }

    @Test
    fun `manifest missing and conflict are honest`() {
        val missing = blocked(ProviderBlockedEvidence.Manifest(ManifestResolutionStatus.NOT_FOUND))
        assertEquals(ProviderBlockedKind.MANIFEST_MISSING, missing.kind)
        assertTrue(missing.whatIsUnknown.contains("validated manifest"))

        assertKind(
            ProviderBlockedKind.MANIFEST_CONFLICT,
            ProviderBlockedEvidence.Manifest(ManifestResolutionStatus.CONFLICTING_MANIFESTS),
        )
    }

    @Test
    fun `missing and incompatible adapters use H024 findings`() {
        assertKind(
            ProviderBlockedKind.ADAPTER_MISSING,
            ProviderBlockedEvidence.Compatibility(
                CompatibilityReportStatus.MISSING_ADAPTER,
                setOf(CompatibilityFindingCode.MISSING_ADAPTER),
            ),
        )
        assertKind(
            ProviderBlockedKind.ADAPTER_INCOMPATIBLE,
            ProviderBlockedEvidence.Compatibility(
                CompatibilityReportStatus.INCOMPATIBLE,
                setOf(CompatibilityFindingCode.UNSUPPORTED_VERSION),
            ),
        )
    }

    @Test
    fun `provider incompatibility connection unavailable and unsupported are distinct`() {
        assertKind(
            ProviderBlockedKind.PROVIDER_INCOMPATIBLE,
            ProviderBlockedEvidence.Compatibility(
                CompatibilityReportStatus.INCOMPATIBLE,
                setOf(CompatibilityFindingCode.PROVIDER_FAMILY_MISMATCH),
            ),
        )
        assertKind(
            ProviderBlockedKind.CONNECTION_UNAVAILABLE,
            ProviderBlockedEvidence.Connection(
                ConnectionAvailability.UNAVAILABLE,
                ConnectionAvailabilityReason.METHOD_UNAVAILABLE,
            ),
        )
        assertKind(
            ProviderBlockedKind.UNSUPPORTED_PROVIDER,
            ProviderBlockedEvidence.Manifest(ManifestResolutionStatus.UNSUPPORTED_SCHEMA),
        )
    }

    @Test
    fun `capability negotiation failures do not imply exploratory execution`() {
        val state = blocked(
            ProviderBlockedEvidence.Negotiation(
                CapabilityNegotiationStatus.UNSUPPORTED,
                CapabilityNegotiationReason.NO_DECLARED_CAPABILITY_SUPPORTED,
            ),
        )
        assertEquals(ProviderBlockedKind.CAPABILITY_NOT_NEGOTIATED, state.kind)
        assertFalse(state.exposesControlAction)
        assertFalse(state.executesCapability)
    }

    @Test
    fun `trust permission and approval requirements remain authority states`() {
        assertKind(
            ProviderBlockedKind.TRUST_REQUIRED,
            ProviderBlockedEvidence.Authorization(AuthorizationDecisionStatus.TRUST_REQUIRED),
        )
        assertKind(
            ProviderBlockedKind.PERMISSION_REQUIRED,
            ProviderBlockedEvidence.Authorization(AuthorizationDecisionStatus.PERMISSION_REQUIRED),
        )
        assertKind(
            ProviderBlockedKind.APPROVAL_REQUIRED,
            ProviderBlockedEvidence.Authorization(AuthorizationDecisionStatus.APPROVAL_REQUIRED),
        )
    }

    @Test
    fun `calibration required lists canonical items without guessing`() {
        val state = blocked(
            ProviderBlockedEvidence.CalibrationReadiness(
                CalibrationNegotiationReadinessStatus.CALIBRATION_REQUIRED,
                listOf(item("body.wheel_radius"), item("body.floor_clearance"), item("body.wheel_radius")),
            ),
        )
        assertEquals(ProviderBlockedKind.CALIBRATION_REQUIRED, state.kind)
        assertEquals(
            listOf(
                "Required calibration: body.floor_clearance",
                "Required calibration: body.wheel_radius",
            ),
            state.details,
        )
        assertTrue(state.whyBlocked.contains("does not guess"))
    }

    @Test
    fun `expired and invalid calibration remain blocked`() {
        assertKind(
            ProviderBlockedKind.CALIBRATION_INVALID,
            ProviderBlockedEvidence.Calibration(CalibrationStatus.EXPIRED, listOf(item("body.geometry"))),
        )
        assertKind(
            ProviderBlockedKind.CALIBRATION_INVALID,
            ProviderBlockedEvidence.Calibration(CalibrationStatus.INVALIDATED),
        )
    }

    @Test
    fun `stale unavailable and prohibited states remain distinct`() {
        assertKind(
            ProviderBlockedKind.STALE_EVIDENCE,
            ProviderBlockedEvidence.Identity(IdentityResolutionStatus.STALE_EVIDENCE),
        )
        assertKind(
            ProviderBlockedKind.PROVIDER_UNAVAILABLE,
            ProviderBlockedEvidence.RuntimeAvailability(
                ProviderRuntimeAvailability.UNAVAILABLE,
                ProviderAvailabilityReason.CONNECTION_UNAVAILABLE,
            ),
        )
        assertKind(
            ProviderBlockedKind.PROHIBITED,
            ProviderBlockedEvidence.Authorization(AuthorizationDecisionStatus.PROHIBITED),
        )
        assertKind(
            ProviderBlockedKind.PROHIBITED,
            ProviderBlockedEvidence.StoredKnowledge(ProviderInstanceKnowledgeStatus.DISABLED),
        )
    }

    @Test
    fun `successful domain states do not manufacture a blocked card`() {
        val results = listOf(
            ProviderBlockedStateMapper.map(
                ProviderBlockedEvidence.Identity(IdentityResolutionStatus.MATCHED_KNOWN_PROVIDER),
            ),
            ProviderBlockedStateMapper.map(
                ProviderBlockedEvidence.Manifest(ManifestResolutionStatus.RESOLVED),
            ),
            ProviderBlockedStateMapper.map(
                ProviderBlockedEvidence.Compatibility(CompatibilityReportStatus.COMPATIBLE),
            ),
            ProviderBlockedStateMapper.map(
                ProviderBlockedEvidence.Connection(ConnectionAvailability.AVAILABLE, null),
            ),
            ProviderBlockedStateMapper.map(
                ProviderBlockedEvidence.Authorization(AuthorizationDecisionStatus.AUTHORIZED),
            ),
            ProviderBlockedStateMapper.map(
                ProviderBlockedEvidence.Calibration(CalibrationStatus.VERIFIED),
            ),
        )
        assertTrue(results.all { it == ProviderBlockedMappingResult.NotBlocked })
    }

    @Test
    fun `mapper output is deterministic and contains no control authority`() {
        val evidence = ProviderBlockedEvidence.Compatibility(
            CompatibilityReportStatus.INCOMPATIBLE,
            linkedSetOf(CompatibilityFindingCode.UNSUPPORTED_VERSION, CompatibilityFindingCode.ADAPTER_IDENTITY_MISMATCH),
            alias("Desk device"),
        )
        val first = blocked(evidence)
        val second = blocked(evidence)
        assertEquals(first.textContent(), second.textContent())
        assertEquals(first.kind, second.kind)
        assertFalse(first.exposesControlAction)
        assertFalse(first.permitsTryAnyway)
        assertFalse(first.grantsTrust)
        assertFalse(first.grantsPermission)
        assertFalse(first.startsConnection)
        assertFalse(first.executesCapability)
    }

    @Test
    fun `safe aliases render while private identifiers are suppressed`() {
        val safe = blocked(
            ProviderBlockedEvidence.Identity(
                IdentityResolutionStatus.INSUFFICIENT_EVIDENCE,
                alias("Kitchen body"),
            ),
        )
        assertEquals("Kitchen body", safe.textContent().providerLabel)

        val privateId = "10000000-0000-4000-8000-000000000001"
        val suppressed = blocked(
            ProviderBlockedEvidence.Identity(
                IdentityResolutionStatus.INSUFFICIENT_EVIDENCE,
                alias(privateId),
            ),
        )
        assertNull(suppressed.providerAlias)
        assertFalse(suppressed.textContent().toString().contains(privateId))
        assertFalse(ProviderBlockedUiState::class.java.declaredFields.any { it.type.name.contains("ProviderInstanceId") })
    }

    @Test
    fun `unmapped failure falls back to honest generic blocked state`() {
        val state = blocked(ProviderBlockedEvidence.Unmapped(alias("Unknown provider")))
        assertEquals(ProviderBlockedKind.GENERIC_BLOCKED, state.kind)
        assertTrue(state.whatIsUnknown.contains("more specific"))
        assertTrue(state.whyBlocked.contains("remain blocked"))
    }

    @Test
    fun `presentation projection renders all explanatory sections and no action label`() {
        val content = blocked(
            ProviderBlockedEvidence.Authorization(AuthorizationDecisionStatus.PERMISSION_REQUIRED),
        ).textContent()
        assertEquals(
            listOf(
                "What HUGO knows",
                "What HUGO does not know",
                "Why HUGO cannot proceed",
                "Safe next step",
            ),
            content.sections.map { it.first },
        )
        assertFalse(content.toString().contains("Try anyway", ignoreCase = true))
        assertFalse(content.toString().contains("Connect now", ignoreCase = true))
    }

    @Test
    fun `Compose presentation depends only on UI state and exposes no clickable control`() {
        val root = projectRoot()
        val compose = File(root, "app/src/main/java/io/redi/robot/feature/providers/ProviderBlockedCard.kt").readText()
        assertTrue(compose.contains("state: ProviderBlockedUiState"))
        assertFalse(compose.contains("ProviderInstanceId"))
        assertFalse(compose.contains("IdentityResolutionStatus"))
        assertFalse(compose.contains("AuthorizationDecisionStatus"))
        assertFalse(Regex("\\bButton\\s*[(]").containsMatchIn(compose))
        assertFalse(compose.contains(".clickable"))
        assertFalse(compose.contains("onClick"))
    }

    private fun assertKind(expected: ProviderBlockedKind, evidence: ProviderBlockedEvidence) {
        assertEquals(expected, blocked(evidence).kind)
    }

    private fun blocked(evidence: ProviderBlockedEvidence): ProviderBlockedUiState =
        (ProviderBlockedStateMapper.map(evidence) as ProviderBlockedMappingResult.Blocked).state

    private fun alias(raw: String): RedactedProviderAlias =
        (RedactedProviderAlias.parse(raw) as ProviderIdentityResult.Success).value

    private fun item(raw: String): CalibrationItemId =
        (CalibrationItemId.parse(raw) as CalibrationDomainResult.Success).value

    private fun projectRoot(): File {
        var current = File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(6) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        error("Project root not found")
    }
}
