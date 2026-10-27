package io.redi.robot.capability.registry

import io.redi.robot.adapter.api.AdapterId
import io.redi.robot.adapter.api.AdapterIdentity
import io.redi.robot.adapter.api.AdapterPackageDigest
import io.redi.robot.adapter.api.AdapterVersion
import io.redi.robot.capability.api.CapabilityApprovalRequirement
import io.redi.robot.capability.api.CapabilityAvailabilitySemantics
import io.redi.robot.capability.api.CapabilityCancellationSemantics
import io.redi.robot.capability.api.CapabilityDescription
import io.redi.robot.capability.api.CapabilityDescriptor
import io.redi.robot.capability.api.CapabilityEffect
import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityLabel
import io.redi.robot.capability.api.CapabilityPermissionIntent
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.CapabilityRiskClass
import io.redi.robot.capability.api.CapabilitySafetyClass
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.capability.provider.ProviderRuntimeSnapshot
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.capability.provider.lifecycle.ProviderLifecycleState
import io.redi.robot.manifest.domain.CommonManifestBinding
import io.redi.robot.manifest.domain.ManifestDigest
import io.redi.robot.manifest.domain.SpecializedManifestKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProviderRegistryTest {
    @Test
    fun registerAndQueryReturnNormalizedCandidatesWithoutAuthority() {
        val registry = InMemoryProviderRegistry()
        val media = descriptor("media.control@1.0")
        val mobility = descriptor("mobility.move@1.0", effect = CapabilityEffect.ACT)
        val body = record("00000000-0000-0000-0000-000000000001", ProviderFamily.BODY_ADAPTER, listOf(mobility))
        val device = record("00000000-0000-0000-0000-000000000002", ProviderFamily.DEVICE_ADAPTER, listOf(media))

        assertTrue(registry.register(body) is ProviderRegistryResult.Success)
        assertTrue(registry.register(device) is ProviderRegistryResult.Success)

        assertEquals(listOf(body.identity.instanceId), registry.snapshot().byFamily(ProviderFamily.BODY_ADAPTER).map {
            it.record.identity.instanceId
        })
        assertEquals(listOf(device.identity.instanceId), registry.snapshot().offering(media.reference).map {
            it.record.identity.instanceId
        })
        assertEquals(ProviderRuntimeAvailability.UNAVAILABLE, registry.snapshot().offering(media.reference).single().record.runtime.availability.availability)
    }

    @Test
    fun duplicateAndConflictingInstanceRegistrationAreRejected() {
        val registry = InMemoryProviderRegistry()
        val original = record("00000000-0000-0000-0000-000000000011", ProviderFamily.DEVICE_ADAPTER)
        assertTrue(registry.register(original) is ProviderRegistryResult.Success)

        assertFailure(ProviderRegistryError.DuplicateProviderInstance, registry.register(original))
        assertFailure(
            ProviderRegistryError.ProviderIdentityConflict,
            registry.register(record("00000000-0000-0000-0000-000000000011", ProviderFamily.SERVICE_CONNECTOR)),
        )
    }

    @Test
    fun snapshotsAndCapabilityListsAreImmutableCopies() {
        val registry = InMemoryProviderRegistry()
        val mutable = mutableListOf(descriptor("vision.observe@1.0"))
        val record = record("00000000-0000-0000-0000-000000000021", ProviderFamily.DEVICE_ADAPTER, mutable)
        mutable += descriptor("media.play@1.0", effect = CapabilityEffect.ACT)

        registry.register(record)
        val first = registry.snapshot()
        val second = registry.snapshot()

        assertEquals(first.entries, second.entries)
        assertEquals(1, first.entries.single().record.capabilities.size)
        try {
            @Suppress("UNCHECKED_CAST")
            (first.entries as MutableList<RegisteredProviderSnapshot>).clear()
            fail("Registry snapshot entries must be immutable")
        } catch (_: UnsupportedOperationException) {
            // Expected.
        }
        try {
            @Suppress("UNCHECKED_CAST")
            (first.entries.single().record.capabilities as MutableList<CapabilityDescriptor>).clear()
            fail("Registry capability snapshots must be immutable")
        } catch (_: UnsupportedOperationException) {
            // Expected.
        }
    }

    @Test
    fun updateRejectsStaleRevisionAndGenerationThenAllowsNewGeneration() {
        val registry = InMemoryProviderRegistry()
        val initial: RegisteredProviderSnapshot = success(
            registry.register(record("00000000-0000-0000-0000-000000000031", ProviderFamily.DEVICE_ADAPTER)),
        )
        val advanced: RegisteredProviderSnapshot = success(
            registry.updateRuntime(
                ProviderRuntimeRegistrationUpdate(
                    initial.record.identity.instanceId,
                    initial.revision,
                    runtime(1L, ProviderRuntimeAvailability.AVAILABLE),
                ),
            ),
        )

        assertFailure(
            ProviderRegistryError.StaleRevision,
            registry.updateRuntime(
                ProviderRuntimeRegistrationUpdate(
                    initial.record.identity.instanceId,
                    initial.revision,
                    runtime(2L, ProviderRuntimeAvailability.AVAILABLE),
                ),
            ),
        )
        assertFailure(
            ProviderRegistryError.StaleGeneration,
            registry.updateRuntime(
                ProviderRuntimeRegistrationUpdate(
                    initial.record.identity.instanceId,
                    advanced.revision,
                    runtime(0L, ProviderRuntimeAvailability.UNAVAILABLE),
                ),
            ),
        )
    }

    @Test
    fun staleSessionCannotUnregisterNewGenerationAtomically() {
        val registry = InMemoryProviderRegistry()
        val initial: RegisteredProviderSnapshot = success(
            registry.register(record("00000000-0000-0000-0000-000000000041", ProviderFamily.BODY_ADAPTER)),
        )
        val reconnected: RegisteredProviderSnapshot = success(
            registry.updateRuntime(
                ProviderRuntimeRegistrationUpdate(
                    initial.record.identity.instanceId,
                    initial.revision,
                    runtime(1L, ProviderRuntimeAvailability.AVAILABLE),
                ),
            ),
        )

        assertFailure(
            ProviderRegistryError.StaleRevision,
            registry.unregister(
                ProviderUnregistrationRequest(initial.record.identity.instanceId, initial.revision, initial.record.runtime.generation),
            ),
        )
        assertTrue(
            registry.unregister(
                ProviderUnregistrationRequest(reconnected.record.identity.instanceId, reconnected.revision, reconnected.record.runtime.generation),
            ) is ProviderRegistryResult.Success,
        )
        assertTrue(registry.snapshot().entries.isEmpty())
    }

    @Test
    fun recordValidationRejectsManifestFamilyAndLifecycleGenerationMismatches() {
        val identity = identity("00000000-0000-0000-0000-000000000051", ProviderFamily.DEVICE_ADAPTER)
        val wrongFamilyManifest = ProviderManifestReference(
            CommonManifestBinding(identity.definitionId, digest()),
            SpecializedManifestKind.BODY,
        )
        assertFailure(
            ProviderRegistryError.ManifestFamilyMismatch,
            ProviderRegistryRecord.create(identity, wrongFamilyManifest, adapter(), listOf(descriptor("media.play@1.0", CapabilityEffect.ACT)), runtime()),
        )
        assertFailure(
            ProviderRegistryError.LifecycleGenerationMismatch,
            ProviderRegistryRuntimeSnapshot.create(
                generation(1L),
                ProviderLifecycleState.Registered(generation(0L)),
                availability(1L, ProviderRuntimeAvailability.AVAILABLE),
            ),
        )
    }

    @Test
    fun unregisterOfUnknownProviderIsExplicitAndHostReferenceIsOpaque() {
        val unknown = identity("00000000-0000-0000-0000-000000000061", ProviderFamily.SERVICE_CONNECTOR)
        val registry = InMemoryProviderRegistry()
        assertFailure(
            ProviderRegistryError.UnknownProviderInstance,
            registry.unregister(ProviderUnregistrationRequest(unknown.instanceId, revision(0L), generation(0L))),
        )
        assertTrue(ProviderHostAssociationReference.parse("home-core.primary") is ProviderRegistryResult.Success)
        assertFailure(ProviderRegistryError.InvalidHostAssociationReference, ProviderHostAssociationReference.parse("AA:BB:CC:DD:EE:FF"))
    }

    private fun record(
        instance: String,
        family: ProviderFamily,
        capabilities: List<CapabilityDescriptor> = listOf(descriptor("environment.status@1.0")),
    ): ProviderRegistryRecord {
        val identity = identity(instance, family)
        return success(
            ProviderRegistryRecord.create(
                identity = identity,
                manifest = ProviderManifestReference(
                    CommonManifestBinding(identity.definitionId, digest()),
                    when (family) {
                        ProviderFamily.BODY_ADAPTER -> SpecializedManifestKind.BODY
                        ProviderFamily.DEVICE_ADAPTER -> SpecializedManifestKind.DEVICE
                        ProviderFamily.SERVICE_CONNECTOR -> SpecializedManifestKind.SERVICE
                    },
                ),
                adapter = adapter(),
                capabilities = capabilities,
                runtime = runtime(),
            ),
        )
    }

    private fun identity(instance: String, family: ProviderFamily): ProviderIdentity = ProviderIdentity(
        definitionId = success(ProviderDefinitionId.parse("redi/provider")),
        instanceId = success(ProviderInstanceId.parse(instance)),
        family = family,
        displayAlias = success(RedactedProviderAlias.parse("Test provider")),
    )

    private fun adapter(): ProviderAdapterReference = ProviderAdapterReference(
        AdapterIdentity(
            success(AdapterId.parse("redi.robot/provider-adapter")),
            success(AdapterVersion.parse("1.0.0")),
        ),
        success(AdapterPackageDigest.parse("sha256:" + "a".repeat(64))),
    )

    private fun runtime(
        generation: Long = 0L,
        availability: ProviderRuntimeAvailability = ProviderRuntimeAvailability.UNAVAILABLE,
    ): ProviderRegistryRuntimeSnapshot = success(
        ProviderRegistryRuntimeSnapshot.create(
            generation(generation),
            ProviderLifecycleState.Registered(generation(generation)),
            availability(generation, availability),
        ),
    )

    private fun availability(generation: Long, availability: ProviderRuntimeAvailability): ProviderRuntimeSnapshot =
        success(
            ProviderRuntimeSnapshot.create(
                generation(generation),
                availability,
                success(MonotonicTimestampMs.create(10L)),
                if (availability == ProviderRuntimeAvailability.AVAILABLE) null else io.redi.robot.capability.provider.ProviderAvailabilityReason.NOT_READY,
            ),
        )

    private fun descriptor(raw: String, effect: CapabilityEffect = CapabilityEffect.OBSERVE): CapabilityDescriptor = success(
        CapabilityDescriptor.create(
            reference = success(CapabilityReference.parse(raw)),
            label = success(CapabilityLabel.parse("Test capability")),
            description = success(CapabilityDescription.parse("Provider-neutral registry test capability.")),
            inputContract = null,
            outputContract = null,
            effect = effect,
            risk = CapabilityRiskClass.INFORMATIONAL,
            safety = if (effect == CapabilityEffect.OBSERVE) CapabilitySafetyClass.NON_PHYSICAL else CapabilitySafetyClass.PHYSICAL_ACTION,
            permissionIntent = when (effect) {
                CapabilityEffect.OBSERVE -> CapabilityPermissionIntent.OBSERVE
                CapabilityEffect.ACT -> CapabilityPermissionIntent.ACT
                CapabilityEffect.MODIFY -> CapabilityPermissionIntent.MODIFY
            },
            approvalRequirement = CapabilityApprovalRequirement.WITHIN_GRANT,
            cancellation = CapabilityCancellationSemantics.SUPPORTED,
            availability = CapabilityAvailabilitySemantics.WHILE_REGISTERED,
            evidence = CapabilityEvidenceClass.AUTOMATED_TESTED,
        ),
    )

    private fun digest(): ManifestDigest = success(ManifestDigest.parse("sha256:" + "b".repeat(64)))

    private fun generation(value: Long): ProviderGeneration = success(ProviderGeneration.create(value))

    private fun revision(value: Long): ProviderRegistryRevision = success(ProviderRegistryRevision.create(value))

    private fun <T> success(result: Any): T = when (result) {
        is ProviderRegistryResult.Success<*> -> result.value as T
        is io.redi.robot.capability.provider.ProviderIdentityResult.Success<*> -> result.value as T
        is io.redi.robot.capability.provider.CapabilityProviderPortResult.Success<*> -> result.value as T
        is io.redi.robot.capability.api.CapabilityDescriptorResult.Success<*> -> result.value as T
        is io.redi.robot.capability.api.CapabilityEnvelopeResult.Success<*> -> result.value as T
        is CapabilityIdentityResult.Success<*> -> result.value as T
        is io.redi.robot.adapter.api.AdapterResult.Success<*> -> result.value as T
        is io.redi.robot.manifest.domain.CommonManifestResult.Success<*> -> result.value as T
        else -> error("Expected success, got $result")
    }

    private fun assertFailure(error: ProviderRegistryError, result: ProviderRegistryResult<*>) {
        assertEquals(ProviderRegistryResult.Failure(error), result)
    }
}
