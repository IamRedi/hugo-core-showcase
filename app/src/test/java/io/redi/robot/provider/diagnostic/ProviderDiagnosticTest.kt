package io.redi.robot.provider.diagnostic

import io.redi.robot.adapter.api.AdapterId
import io.redi.robot.adapter.api.AdapterResult
import io.redi.robot.adapter.api.AdapterVersion
import io.redi.robot.adapter.compatibility.CompatibilityCheckStatus
import io.redi.robot.adapter.compatibility.CompatibilityDimension
import io.redi.robot.adapter.compatibility.CompatibilityFindingCode
import io.redi.robot.adapter.compatibility.CompatibilityReportStatus
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.negotiation.CapabilityNegotiationReason
import io.redi.robot.capability.negotiation.CapabilityNegotiationStatus
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.connection.api.ConnectionAvailability
import io.redi.robot.connection.api.ConnectionMethodId
import io.redi.robot.connection.api.ConnectionMethodReference
import io.redi.robot.connection.api.ConnectionMethodVersion
import io.redi.robot.connection.api.ConnectionReadiness
import io.redi.robot.connection.api.ConnectionResult
import io.redi.robot.discovery.identity.IdentityResolutionReason
import io.redi.robot.discovery.identity.IdentityResolutionStatus
import io.redi.robot.feature.providers.ProviderBlockedEvidence
import io.redi.robot.feature.providers.ProviderBlockedKind
import io.redi.robot.feature.providers.ProviderBlockedMappingResult
import io.redi.robot.feature.providers.ProviderBlockedStateMapper
import io.redi.robot.feature.providers.ProviderResolutionAvailability
import io.redi.robot.feature.providers.toDiagnosticSection
import io.redi.robot.manifest.resolution.ManifestResolutionReason
import io.redi.robot.manifest.resolution.ManifestResolutionStatus
import io.redi.robot.provider.calibration.CalibrationDomainResult
import io.redi.robot.provider.calibration.CalibrationItemId
import io.redi.robot.provider.calibration.CalibrationNegotiationReadinessStatus
import io.redi.robot.provider.calibration.CalibrationStatus
import io.redi.robot.provider.runtime.ProviderOrchestrationPhase
import io.redi.robot.provider.runtime.ProviderOrchestrationStopReason
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderDiagnosticTest {
    @Test
    fun `portable complete fixture strictly round trips`() {
        val fixture = fixture().trim()
        val decoded = success(ProviderDiagnosticCodec.decode(fixture))
        assertEquals(ProviderFamily.BODY_ADAPTER, decoded.summary.family)
        assertEquals(IdentityResolutionStatus.MATCHED_KNOWN_PROVIDER, decoded.identity?.status)
        assertEquals(ManifestResolutionStatus.RESOLVED, decoded.manifest?.status)
        assertEquals(CompatibilityFindingCode.MISSING_ADAPTER, decoded.compatibility?.checks?.single()?.finding)
        assertEquals(3, decoded.negotiation?.capabilities?.size)
        assertEquals(ProviderBlockedKind.ADAPTER_MISSING.name, decoded.blockedState?.kind?.value)
        assertEquals(CalibrationNegotiationReadinessStatus.CALIBRATION_REQUIRED, decoded.calibration?.readiness)
        assertEquals(fixture, success(ProviderDiagnosticCodec.encode(decoded)))
    }

    @Test
    fun `partially known and ambiguous provider remain explicit`() {
        val snapshot = snapshot(
            summary = ProviderDiagnosticProjection.summary("partial", null, definition("hugo.test/body"), ProviderFamily.BODY_ADAPTER),
            identity = IdentityDiagnostic(
                IdentityResolutionStatus.AMBIGUOUS,
                IdentityResolutionReason.MULTIPLE_PLAUSIBLE_CANDIDATES,
                2,
                listOf(diag(2), diag(1)),
                DiagnosticFreshness.FRESH,
            ),
            manifest = null,
        )
        val restored = success(ProviderDiagnosticCodec.decode(success(ProviderDiagnosticCodec.encode(snapshot))))
        assertFalse(restored.summary.identityKnown)
        assertEquals(IdentityResolutionStatus.AMBIGUOUS, restored.identity?.status)
        assertNull(restored.manifest)
        assertEquals(listOf(diag(1), diag(2)), restored.identity?.evidenceReferences)
    }

    @Test
    fun `missing adapter and incompatible provider preserve typed compatibility findings`() {
        val missing = snapshot(
            compatibility = CompatibilityDiagnostic(
                CompatibilityReportStatus.MISSING_ADAPTER,
                listOf(check(CompatibilityFindingCode.MISSING_ADAPTER)),
            ),
            adapter = AdapterDiagnostic(false, null, null, null, null),
        )
        assertEquals(CompatibilityFindingCode.MISSING_ADAPTER, missing.compatibility?.checks?.single()?.finding)

        val incompatible = snapshot(
            compatibility = CompatibilityDiagnostic(
                CompatibilityReportStatus.INCOMPATIBLE,
                listOf(check(CompatibilityFindingCode.PROVIDER_FAMILY_MISMATCH)),
            ),
        )
        assertEquals(
            CompatibilityFindingCode.PROVIDER_FAMILY_MISMATCH,
            incompatible.compatibility?.checks?.single()?.finding,
        )
    }

    @Test
    fun `negotiated capabilities are semantic canonical and authority free`() {
        val snapshot = snapshot(
            negotiation = NegotiatedCapabilitiesDiagnostic(
                CapabilityNegotiationStatus.NEGOTIATED,
                CapabilityNegotiationReason.ALL_DECLARED_CAPABILITIES_NEGOTIATED,
                listOf(capability("mobility.stop@1.0"), capability("mobility.move@1.0")),
                diag(5),
                DiagnosticFreshness.FRESH,
            ),
        )
        assertEquals(
            listOf("mobility.move@1.0", "mobility.stop@1.0"),
            snapshot.negotiation?.capabilities?.map { it.wireValue },
        )
        assertFalse(snapshot.grantsTrust)
        assertFalse(snapshot.grantsPermission)
        assertFalse(snapshot.grantsSafetyApproval)
        assertFalse(snapshot.provesPhysicalFeasibility)
        assertFalse(snapshot.executesCapability)
        assertFalse(snapshot.selectsActiveBody)
    }

    @Test
    fun `authorization blocked diagnostic reuses H033 semantics exactly`() {
        val ui = (
            ProviderBlockedStateMapper.map(
                ProviderBlockedEvidence.Authorization(
                    io.redi.robot.trust.runtime.AuthorizationDecisionStatus.PERMISSION_REQUIRED,
                ),
            ) as ProviderBlockedMappingResult.Blocked
            ).state
        val blocked = ui.toDiagnosticSection()
        assertEquals(ProviderBlockedKind.PERMISSION_REQUIRED.name, blocked.kind.value)
        assertEquals(
            ProviderResolutionAvailability.ADMIN_OR_POLICY_REQUIRED.name,
            blocked.resolutionAvailability.value,
        )
    }

    @Test
    fun `calibration diagnostic exports readiness and references not payloads`() {
        val calibration = CalibrationReadinessDiagnostic(
            CalibrationNegotiationReadinessStatus.CALIBRATION_REQUIRED,
            CalibrationStatus.EXPIRED,
            diag(6),
            listOf(item("body.wheel_radius")),
        )
        val text = success(ProviderDiagnosticCodec.encode(snapshot(calibration = calibration)))
        assertTrue(text.contains("CALIBRATION_REQUIRED"))
        assertTrue(text.contains("body.wheel_radius"))
        assertFalse(text.contains("measurement", ignoreCase = true))
        assertFalse(text.contains("payload", ignoreCase = true))
    }

    @Test
    fun `stale evidence and freshness timestamps survive round trip`() {
        val evidence = ProviderDiagnosticEvidence(
            diag(7),
            DiagnosticEvidenceSource.DISCOVERY,
            safe("bluetooth.le"),
            100,
            200,
            DiagnosticFreshness.STALE,
        )
        val restored = success(
            ProviderDiagnosticCodec.decode(success(ProviderDiagnosticCodec.encode(snapshot(evidence = listOf(evidence))))),
        )
        assertEquals(evidence, restored.evidence.single())
    }

    @Test
    fun `equivalent inputs produce deterministic ordering`() {
        val first = snapshot(
            compatibility = CompatibilityDiagnostic(
                CompatibilityReportStatus.INCOMPATIBLE,
                listOf(
                    check(CompatibilityFindingCode.UNSUPPORTED_VERSION, "version.check"),
                    check(CompatibilityFindingCode.PROVIDER_FAMILY_MISMATCH, "family.check"),
                ),
            ),
            evidence = listOf(evidence(9, DiagnosticEvidenceSource.MANIFEST), evidence(8, DiagnosticEvidenceSource.DISCOVERY)),
        )
        val second = snapshot(
            compatibility = CompatibilityDiagnostic(
                CompatibilityReportStatus.INCOMPATIBLE,
                listOf(
                    check(CompatibilityFindingCode.PROVIDER_FAMILY_MISMATCH, "family.check"),
                    check(CompatibilityFindingCode.UNSUPPORTED_VERSION, "version.check"),
                ),
            ),
            evidence = listOf(evidence(8, DiagnosticEvidenceSource.DISCOVERY), evidence(9, DiagnosticEvidenceSource.MANIFEST)),
        )
        assertEquals(success(ProviderDiagnosticCodec.encode(first)), success(ProviderDiagnosticCodec.encode(second)))
    }

    @Test
    fun `malformed strict and unsupported schemas fail closed`() {
        assertFailure(ProviderDiagnosticError.MALFORMED_DOCUMENT, ProviderDiagnosticCodec.decode("{"))
        val fixture = fixture().trim()
        assertFailure(
            ProviderDiagnosticError.UNSUPPORTED_SCHEMA_MAJOR(9),
            ProviderDiagnosticCodec.decode(fixture.replaceFirst("\"1.0\"", "\"9.0\"")),
        )
        val unexpected = fixture.replaceFirst("\"generatedAt\":1000", "\"unexpected\":1,\"generatedAt\":1000")
        val result = ProviderDiagnosticCodec.decode(unexpected) as ProviderDiagnosticResult.Failure
        assertTrue(result.error is ProviderDiagnosticError.UNEXPECTED_FIELDS)
    }

    @Test
    fun `stable identifiers aliases and secret shaped values are rejected or omitted`() {
        val privateId = "10000000-0000-4000-8000-000000000001"
        val unsafeSummary = ProviderSummaryDiagnostic(diag(1), definition("hugo.test/body"), ProviderFamily.BODY_ADAPTER, alias(privateId), true)
        assertFailure(ProviderDiagnosticError.UNSAFE_ALIAS, ProviderDiagnosticSnapshot.create(generatedAt = 1, summary = unsafeSummary))
        assertTrue(DiagnosticSafeValue.parse("192.0.2.1") is ProviderDiagnosticResult.Failure)
        assertTrue(DiagnosticSafeValue.parse("AA:BB:CC:DD:EE:FF") is ProviderDiagnosticResult.Failure)
        assertTrue(DiagnosticSafeValue.parse("oauth_token") is ProviderDiagnosticResult.Failure)
        assertTrue(DiagnosticSafeValue.parse("serial_12345") is ProviderDiagnosticResult.Failure)
        assertTrue(DiagnosticSafeValue.parse("private.endpoint") is ProviderDiagnosticResult.Failure)

        val projected = ProviderDiagnosticProjection.summary("safe-export", null, alias = alias(privateId))
        assertNull(projected.alias)
    }

    @Test
    fun `private provider IDs and transport protocol values cannot appear in model or fixture`() {
        val privateId = "10000000-0000-4000-8000-000000000001"
        val text = success(ProviderDiagnosticCodec.encode(snapshot()))
        assertFalse(text.contains(privateId))
        assertFalse(text.contains("ProviderInstanceId"))
        assertFalse(ProviderDiagnosticSnapshot::class.java.declaredFields.any {
            it.type.name.contains("ProviderInstanceId")
        })
        val forbidden = listOf("FED0", "FED8", "FED9", "BluetoothGatt", "rawPacket", "api_key", "Bearer")
        forbidden.forEach { assertFalse(text.contains(it, ignoreCase = true)) }
        forbidden.forEach { assertFalse(fixture().contains(it, ignoreCase = true)) }
    }

    @Test
    fun `one way diagnostic references are scoped and do not reveal source`() {
        val source = "10000000-0000-4000-8000-000000000001"
        val first = ProviderDiagnosticId.derive("provider-export", source)
        val second = ProviderDiagnosticId.derive("identity-evidence", source)
        assertNotEquals(first, second)
        assertFalse(first.value.contains(source))
        assertEquals(first, ProviderDiagnosticId.derive("provider-export", source))
    }

    @Test
    fun `bounded output rejects excessive capability lists`() {
        val capabilities = (0..128).map { capability("test.capability_$it@1.0") }
        assertFailure(
            ProviderDiagnosticError.BOUNDS_EXCEEDED,
            ProviderDiagnosticSnapshot.create(
                generatedAt = 1,
                summary = summary(),
                negotiation = NegotiatedCapabilitiesDiagnostic(
                    CapabilityNegotiationStatus.NEGOTIATED,
                    CapabilityNegotiationReason.ALL_DECLARED_CAPABILITIES_NEGOTIATED,
                    capabilities,
                    diag(5),
                    DiagnosticFreshness.FRESH,
                ),
            ),
        )
        val oversized = " ".repeat(65 * 1024)
        assertFailure(ProviderDiagnosticError.OUTPUT_TOO_LARGE, ProviderDiagnosticCodec.decode(oversized))
    }

    private fun snapshot(
        summary: ProviderSummaryDiagnostic = summary(),
        lifecycle: LifecycleDiagnostic? = LifecycleDiagnostic(
            ProviderOrchestrationPhase.BLOCKED,
            ProviderOrchestrationStopReason.MISSING_ADAPTER,
            DiagnosticLifecycleState.IDENTIFIED,
            3,
        ),
        identity: IdentityDiagnostic? = IdentityDiagnostic(
            IdentityResolutionStatus.MATCHED_KNOWN_PROVIDER,
            IdentityResolutionReason.STRONG_VERIFIED_INSTANCE_EVIDENCE,
            1,
            listOf(diag(2)),
            DiagnosticFreshness.FRESH,
        ),
        manifest: ManifestDiagnostic? = ManifestDiagnostic(
            ManifestResolutionStatus.RESOLVED,
            ManifestResolutionReason.EXACT_VALIDATED_DEFINITION,
            listOf(diag(3)),
        ),
        compatibility: CompatibilityDiagnostic? = null,
        adapter: AdapterDiagnostic? = null,
        connection: ConnectionDiagnostic? = ConnectionDiagnostic(
            ConnectionAvailability.UNAVAILABLE,
            ConnectionReadiness.NOT_CONNECTED,
            method(),
            null,
            DiagnosticFreshness.FRESH,
        ),
        negotiation: NegotiatedCapabilitiesDiagnostic? = null,
        blocked: BlockedStateDiagnostic? = null,
        calibration: CalibrationReadinessDiagnostic? = null,
        evidence: List<ProviderDiagnosticEvidence> = emptyList(),
    ): ProviderDiagnosticSnapshot = success(
        ProviderDiagnosticSnapshot.create(
            generatedAt = 1000,
            summary = summary,
            lifecycle = lifecycle,
            identity = identity,
            manifest = manifest,
            compatibility = compatibility,
            adapter = adapter,
            connection = connection,
            negotiation = negotiation,
            blockedState = blocked,
            calibration = calibration,
            evidence = evidence,
        ),
    )

    private fun summary() = ProviderSummaryDiagnostic(
        diag(1), definition("hugo.test/body"), ProviderFamily.BODY_ADAPTER, alias("Reference body"), true,
    )
    private fun check(finding: CompatibilityFindingCode, id: String = "adapter.check") =
        CompatibilityCheckDiagnostic(
            CompatibilityDimension.ADAPTER_IDENTITY,
            safe(id),
            if (finding == CompatibilityFindingCode.MATCHED) CompatibilityCheckStatus.MATCHED else CompatibilityCheckStatus.FAILED,
            finding,
            listOf(safe("required.value")),
            listOf(safe("observed.value")),
            listOf(diag(4)),
        )
    private fun evidence(id: Int, source: DiagnosticEvidenceSource) = ProviderDiagnosticEvidence(
        diag(id), source, safe("hugo.test"), 100, 200, DiagnosticFreshness.STALE,
    )
    private fun diag(id: Int) = success(ProviderDiagnosticId.parse("diag_${id.toString(16).padStart(24, '0')}"))
    private fun safe(raw: String) = success(DiagnosticSafeValue.parse(raw))
    private fun definition(raw: String) = providerSuccess(ProviderDefinitionId.parse(raw))
    private fun alias(raw: String) = providerSuccess(RedactedProviderAlias.parse(raw))
    private fun capability(raw: String) = capabilitySuccess(CapabilityReference.parse(raw))
    private fun item(raw: String) = calibrationSuccess(CalibrationItemId.parse(raw))
    private fun method() = ConnectionMethodReference(
        connectionSuccess(ConnectionMethodId.parse("hugo.connection/local")),
        connectionSuccess(ConnectionMethodVersion.parse("1.0")),
    )
    private fun fixture(): String = File(projectRoot(), "app/src/test/resources/fixtures/h034-provider-diagnostic-v1.json").readText()
    private fun projectRoot(): File {
        var current = File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(6) {
            if (File(current, "settings.gradle.kts").isFile) return current
            current = current.parentFile ?: return@repeat
        }
        error("Project root not found")
    }
    private fun <T> success(result: ProviderDiagnosticResult<T>): T = (result as ProviderDiagnosticResult.Success).value
    private fun <T> providerSuccess(result: ProviderIdentityResult<T>): T = (result as ProviderIdentityResult.Success).value
    private fun <T> adapterSuccess(result: AdapterResult<T>): T = (result as AdapterResult.Success).value
    private fun <T> capabilitySuccess(result: CapabilityIdentityResult<T>): T = (result as CapabilityIdentityResult.Success).value
    private fun <T> calibrationSuccess(result: CalibrationDomainResult<T>): T = (result as CalibrationDomainResult.Success).value
    private fun <T> connectionSuccess(result: ConnectionResult<T>): T = (result as ConnectionResult.Success).value
    private fun assertFailure(expected: ProviderDiagnosticError, result: ProviderDiagnosticResult<*>) {
        assertEquals(ProviderDiagnosticResult.Failure(expected), result)
    }
}
