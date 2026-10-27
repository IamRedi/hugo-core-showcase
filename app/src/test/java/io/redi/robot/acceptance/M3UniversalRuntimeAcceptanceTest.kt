package io.redi.robot.acceptance

import io.redi.robot.adapter.compatibility.CompatibilityReportStatus
import io.redi.robot.adapter.storage.RegistryPersistenceError
import io.redi.robot.body.looi.manifest.LooiCapabilityCatalog
import io.redi.robot.capability.api.CapabilityApprovalRequirement
import io.redi.robot.capability.api.CapabilityAvailabilitySemantics
import io.redi.robot.capability.api.CapabilityCancellationSemantics
import io.redi.robot.capability.api.CapabilityDescription
import io.redi.robot.capability.api.CapabilityDescriptor
import io.redi.robot.capability.api.CapabilityDescriptorResult
import io.redi.robot.capability.api.CapabilityEffect
import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityLabel
import io.redi.robot.capability.api.CapabilityPermissionIntent
import io.redi.robot.capability.api.CapabilityRequest
import io.redi.robot.capability.api.CapabilityResult
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.CapabilityRiskClass
import io.redi.robot.capability.api.CapabilitySafetyClass
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.negotiation.CapabilityNegotiationStatus
import io.redi.robot.capability.provider.CapabilityCancellationRequest
import io.redi.robot.capability.provider.CapabilityNegotiationEntry
import io.redi.robot.capability.provider.CapabilityNegotiationRequest
import io.redi.robot.capability.provider.CapabilityNegotiationResult
import io.redi.robot.capability.provider.CapabilityNegotiationState
import io.redi.robot.capability.provider.CapabilityProvider
import io.redi.robot.capability.provider.CapabilityProviderPortResult
import io.redi.robot.capability.provider.CapabilityRegistration
import io.redi.robot.capability.provider.CapabilityRegistrationRequest
import io.redi.robot.capability.provider.CapabilityRegistrationResult
import io.redi.robot.capability.provider.ProviderAvailabilityReason
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.capability.provider.ProviderRuntimeSnapshot
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.connection.api.ConnectionFailureCode
import io.redi.robot.discovery.identity.IdentityResolutionStatus
import io.redi.robot.feature.providers.ProviderBlockedKind
import io.redi.robot.manifest.resolution.ManifestResolutionStatus
import io.redi.robot.provider.calibration.CalibrationNegotiationReadinessStatus
import io.redi.robot.provider.diagnostic.ProviderDiagnosticCodec
import io.redi.robot.provider.diagnostic.ProviderDiagnosticResult
import io.redi.robot.provider.runtime.ProviderOrchestrationIgnoredReason
import io.redi.robot.provider.storage.ProviderInstancePersistenceError
import io.redi.robot.trust.runtime.AuthorizationDecisionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** H035 acceptance-only coverage. It does not compose or route a production provider. */
class M3UniversalRuntimeAcceptanceTest {
    @Test
    fun `body device and service scenarios use one provider-neutral port`() {
        val scenarios = listOf(
            Scenario(ProviderFamily.BODY_ADAPTER, "hugo.test/body", "mobility.status@1.0", "Body status", 1),
            Scenario(ProviderFamily.DEVICE_ADAPTER, "hugo.test/device", "device.light.status@1.0", "Light status", 2),
            Scenario(ProviderFamily.SERVICE_CONNECTOR, "hugo.test/service", "service.records.read@1.0", "Read records", 3),
        )

        scenarios.forEach { scenario ->
            val descriptor = descriptor(scenario.capability, scenario.label)
            val provider: CapabilityProvider = AcceptanceProvider(identity(scenario), descriptor)
            val runtime = provider.runtimeSnapshot()
            val request = portSuccess(
                CapabilityNegotiationRequest.create(runtime.generation, listOf(descriptor.reference)),
            )
            val negotiation = provider.negotiate(request)
            val registration = provider.registerCapabilities(
                CapabilityRegistrationRequest.create(runtime.generation),
            )

            assertEquals(scenario.family, provider.identity.family)
            assertEquals(ProviderRuntimeAvailability.AVAILABLE, runtime.availability)
            assertEquals(CapabilityNegotiationState.SUPPORTED, negotiation.entries.single().state)
            assertEquals(descriptor.reference, negotiation.entries.single().offered)
            assertEquals(listOf(descriptor.reference), registration.registrations.map { it.descriptor.reference })
            assertEquals(AcceptanceProvider::class.java, provider.javaClass)
        }

        assertEquals(3, scenarios.map { it.family }.distinct().size)
        assertEquals(3, scenarios.map { reference(it.capability).id.namespace }.distinct().size)
    }

    @Test
    fun `LOOI remains exactly the evidence-backed semantic reference adapter`() {
        val expected = setOf(
            "mobility.stop@1.0",
            "mobility.move@1.0",
            "body.head.position.control@1.0",
            "body.light.control@1.0",
            "safety.lower_contact.status@1.0",
            "provider.connection.status@1.0",
        )
        val mappings = LooiCapabilityCatalog.supported

        assertEquals(expected, mappings.map { it.descriptor.reference.wireValue }.toSet())
        assertTrue(mappings.all { it.evidence.isNotEmpty() && it.limits.isNotEmpty() })
        assertTrue(mappings.filter { it.descriptor.effect == CapabilityEffect.ACT }.all { it.preservesExistingSafetyPath })

        val publicText = mappings.joinToString(" ") {
            "${it.descriptor.reference.wireValue} ${it.descriptor.label.value} ${it.descriptor.description.value}"
        }
        listOf("FED0", "FED8", "FED9", "BluetoothGatt", "BluetoothDevice", "ScanResult").forEach {
            assertFalse(publicText.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `required fault surface remains typed and fail closed`() {
        assertTrue(IdentityResolutionStatus.entries.map { it.name }.containsAll(
            listOf("AMBIGUOUS", "CONFLICTING_EVIDENCE", "INSUFFICIENT_EVIDENCE"),
        ))
        assertTrue(ManifestResolutionStatus.entries.map { it.name }.containsAll(
            listOf("NOT_FOUND", "CONFLICTING_MANIFESTS", "INCOMPATIBLE_ADAPTER"),
        ))
        assertTrue(CompatibilityReportStatus.entries.map { it.name }.containsAll(
            listOf("MISSING_ADAPTER", "INCOMPATIBLE", "UNKNOWN"),
        ))
        assertTrue(ConnectionFailureCode.entries.map { it.name }.containsAll(
            listOf("UNAVAILABLE", "CANCELLED", "SESSION_LOST"),
        ))
        assertTrue(CapabilityNegotiationStatus.entries.map { it.name }.containsAll(
            listOf("STALE_SESSION", "MISSING_PREREQUISITE", "RUNTIME_UNAVAILABLE", "CANCELLED"),
        ))
        assertTrue(AuthorizationDecisionStatus.entries.map { it.name }.containsAll(
            listOf("TRUST_REQUIRED", "PERMISSION_REQUIRED", "APPROVAL_REQUIRED", "PROHIBITED"),
        ))
        assertTrue(CalibrationNegotiationReadinessStatus.entries.map { it.name }.containsAll(
            listOf("CALIBRATION_REQUIRED", "STALE_NEGOTIATION", "INVALID"),
        ))
        assertTrue(ProviderBlockedKind.entries.toSet().containsAll(
            setOf(
                ProviderBlockedKind.IDENTITY_UNKNOWN,
                ProviderBlockedKind.IDENTITY_AMBIGUOUS,
                ProviderBlockedKind.MANIFEST_MISSING,
                ProviderBlockedKind.MANIFEST_CONFLICT,
                ProviderBlockedKind.ADAPTER_MISSING,
                ProviderBlockedKind.ADAPTER_INCOMPATIBLE,
                ProviderBlockedKind.CONNECTION_UNAVAILABLE,
                ProviderBlockedKind.CAPABILITY_NOT_NEGOTIATED,
                ProviderBlockedKind.TRUST_REQUIRED,
                ProviderBlockedKind.PERMISSION_REQUIRED,
                ProviderBlockedKind.APPROVAL_REQUIRED,
                ProviderBlockedKind.CALIBRATION_REQUIRED,
                ProviderBlockedKind.PROVIDER_UNAVAILABLE,
            ),
        ))
        assertEquals(ProviderOrchestrationIgnoredReason.STALE_GENERATION.name, "STALE_GENERATION")
        assertEquals(RegistryPersistenceError.METADATA_DIGEST_MISMATCH.toString(), "METADATA_DIGEST_MISMATCH")
        assertEquals(ProviderInstancePersistenceError.MALFORMED_PAYLOAD.toString(), "MALFORMED_PAYLOAD")
    }

    @Test
    fun `portable diagnostics are redacted evidence and never authority`() {
        val fixture = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("fixtures/h034-provider-diagnostic-v1.json"),
        ).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val snapshot = when (val result = ProviderDiagnosticCodec.decode(fixture)) {
            is ProviderDiagnosticResult.Success -> result.value
            is ProviderDiagnosticResult.Failure -> throw AssertionError(result.error)
        }

        assertFalse(snapshot.grantsTrust)
        assertFalse(snapshot.grantsPermission)
        assertFalse(snapshot.grantsSafetyApproval)
        assertFalse(snapshot.provesPhysicalFeasibility)
        assertFalse(snapshot.executesCapability)
        assertFalse(snapshot.selectsActiveBody)
        listOf("ProviderInstanceId", "BluetoothGatt", "FED0", "FED8", "FED9", "Bearer", "api_key").forEach {
            assertFalse(fixture.contains(it, ignoreCase = true))
        }
    }

    private inner class AcceptanceProvider(
        override val identity: ProviderIdentity,
        private val descriptor: CapabilityDescriptor,
    ) : CapabilityProvider {
        override fun runtimeSnapshot(): ProviderRuntimeSnapshot = portSuccess(
            ProviderRuntimeSnapshot.create(
                generation = generation(1),
                availability = ProviderRuntimeAvailability.AVAILABLE,
                observedAt = timestamp(1),
                reason = null,
            ),
        )

        override fun negotiate(request: CapabilityNegotiationRequest): CapabilityNegotiationResult = portSuccess(
            CapabilityNegotiationResult.create(
                identity.instanceId,
                request,
                request.requestedCapabilities.map {
                    CapabilityNegotiationEntry(it, CapabilityNegotiationState.SUPPORTED, descriptor.reference)
                },
            ),
        )

        override fun registerCapabilities(request: CapabilityRegistrationRequest): CapabilityRegistrationResult =
            portSuccess(
                CapabilityRegistrationResult.create(
                    identity.instanceId,
                    request,
                    listOf(CapabilityRegistration(descriptor)),
                ),
            )

        override fun execute(request: CapabilityRequest): CapabilityResult =
            error("H035 acceptance fakes have no execution authority")

        override fun cancel(request: CapabilityCancellationRequest): CapabilityResult =
            error("H035 acceptance fakes never start executions")
    }

    private data class Scenario(
        val family: ProviderFamily,
        val definition: String,
        val capability: String,
        val label: String,
        val suffix: Int,
    )

    private fun identity(scenario: Scenario): ProviderIdentity = ProviderIdentity(
        definitionId = providerSuccess(ProviderDefinitionId.parse(scenario.definition)),
        instanceId = providerSuccess(
            ProviderInstanceId.parse("10000000-0000-4000-8000-${scenario.suffix.toString().padStart(12, '0')}"),
        ),
        family = scenario.family,
        displayAlias = providerSuccess(RedactedProviderAlias.parse(scenario.label)),
    )

    private fun descriptor(raw: String, label: String): CapabilityDescriptor = descriptorSuccess(
        CapabilityDescriptor.create(
            reference = reference(raw),
            label = descriptorSuccess(CapabilityLabel.parse(label)),
            description = descriptorSuccess(CapabilityDescription.parse("Provider-neutral acceptance observation.")),
            inputContract = null,
            outputContract = null,
            effect = CapabilityEffect.OBSERVE,
            risk = CapabilityRiskClass.INFORMATIONAL,
            safety = CapabilitySafetyClass.NON_PHYSICAL,
            permissionIntent = CapabilityPermissionIntent.OBSERVE,
            approvalRequirement = CapabilityApprovalRequirement.WITHIN_GRANT,
            cancellation = CapabilityCancellationSemantics.NOT_APPLICABLE,
            availability = CapabilityAvailabilitySemantics.WHILE_REGISTERED,
            evidence = CapabilityEvidenceClass.AUTOMATED_TESTED,
        ),
    )

    private fun reference(raw: String): CapabilityReference = identitySuccess(CapabilityReference.parse(raw))

    private fun generation(value: Long): ProviderGeneration = portSuccess(ProviderGeneration.create(value))

    private fun timestamp(value: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(value)) {
        is io.redi.robot.capability.api.CapabilityEnvelopeResult.Success -> result.value
        is io.redi.robot.capability.api.CapabilityEnvelopeResult.Failure -> throw AssertionError(result.error)
    }

    private fun <T> portSuccess(result: CapabilityProviderPortResult<T>): T = when (result) {
        is CapabilityProviderPortResult.Success -> result.value
        is CapabilityProviderPortResult.Failure -> throw AssertionError(result.error)
    }

    private fun <T> providerSuccess(result: ProviderIdentityResult<T>): T = when (result) {
        is ProviderIdentityResult.Success -> result.value
        is ProviderIdentityResult.Failure -> throw AssertionError(result.error)
    }

    private fun <T> identitySuccess(result: CapabilityIdentityResult<T>): T = when (result) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> throw AssertionError(result.error)
    }

    private fun <T> descriptorSuccess(result: CapabilityDescriptorResult<T>): T = when (result) {
        is CapabilityDescriptorResult.Success -> result.value
        is CapabilityDescriptorResult.Failure -> throw AssertionError(result.error)
    }
}
