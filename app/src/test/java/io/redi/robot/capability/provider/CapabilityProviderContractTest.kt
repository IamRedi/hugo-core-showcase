package io.redi.robot.capability.provider

import io.redi.robot.capability.api.CapabilityActorKind
import io.redi.robot.capability.api.CapabilityApprovalRequirement
import io.redi.robot.capability.api.CapabilityAuditId
import io.redi.robot.capability.api.CapabilityAvailabilitySemantics
import io.redi.robot.capability.api.CapabilityCancellationSemantics
import io.redi.robot.capability.api.CapabilityCompatibility
import io.redi.robot.capability.api.CapabilityDescriptor
import io.redi.robot.capability.api.CapabilityDescriptorResult
import io.redi.robot.capability.api.CapabilityDescription
import io.redi.robot.capability.api.CapabilityEffect
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityExecutionContext
import io.redi.robot.capability.api.CapabilityFailure
import io.redi.robot.capability.api.CapabilityFailureCode
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityLabel
import io.redi.robot.capability.api.CapabilityOutcomeEvidence
import io.redi.robot.capability.api.CapabilityPermissionIntent
import io.redi.robot.capability.api.CapabilityProviderTarget
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.CapabilityRequest
import io.redi.robot.capability.api.CapabilityRequestId
import io.redi.robot.capability.api.CapabilityResult
import io.redi.robot.capability.api.CapabilityResultCorrelation
import io.redi.robot.capability.api.CapabilityResultState
import io.redi.robot.capability.api.CapabilityRiskClass
import io.redi.robot.capability.api.CapabilitySafetyClass
import io.redi.robot.capability.api.CapabilityVersion
import io.redi.robot.capability.api.MonotonicTimestampMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityProviderContractTest {
    @Test
    fun bodyDeviceAndServiceFakesImplementTheSameProviderPort() {
        val descriptor = observeDescriptor(reference("environment.status@1.1"))
        val providers = listOf(
            fakeProvider(ProviderFamily.BODY_ADAPTER, descriptor, ProviderRuntimeAvailability.AVAILABLE),
            fakeProvider(ProviderFamily.DEVICE_ADAPTER, descriptor, ProviderRuntimeAvailability.AVAILABLE),
            fakeProvider(ProviderFamily.SERVICE_CONNECTOR, descriptor, ProviderRuntimeAvailability.AVAILABLE),
        )

        providers.forEach { provider ->
            val registrations = provider.registerCapabilities(
                CapabilityRegistrationRequest.create(generation(4)),
            )

            assertEquals(provider.identity.instanceId, registrations.providerInstanceId)
            assertEquals(ProviderRuntimeAvailability.AVAILABLE, provider.runtimeSnapshot().availability)
            assertEquals(listOf(descriptor.reference), registrations.registrations.map { it.descriptor.reference })
        }
    }

    @Test
    fun availabilityIsRuntimeStateAndDoesNotChangeIdentityOrDeclarations() {
        val descriptor = observeDescriptor(reference("environment.status@1.1"))
        val provider = fakeProvider(
            ProviderFamily.DEVICE_ADAPTER,
            descriptor,
            ProviderRuntimeAvailability.UNAVAILABLE,
        )

        val snapshot = provider.runtimeSnapshot()
        val registrations = provider.registerCapabilities(
            CapabilityRegistrationRequest.create(snapshot.generation),
        )

        assertEquals(ProviderRuntimeAvailability.UNAVAILABLE, snapshot.availability)
        assertEquals(ProviderAvailabilityReason.CONNECTION_UNAVAILABLE, snapshot.reason)
        assertEquals("test/provider", provider.identity.definitionId.value)
        assertEquals(listOf(descriptor.reference), registrations.registrations.map { it.descriptor.reference })
    }

    @Test
    fun negotiationUsesCapabilityVersionCompatibilityWithoutTrustOrProviderCoupling() {
        val provider = fakeProvider(
            ProviderFamily.SERVICE_CONNECTOR,
            observeDescriptor(reference("environment.status@1.1")),
            ProviderRuntimeAvailability.AVAILABLE,
        )
        val request = portSuccess(
            CapabilityNegotiationRequest.create(generation(4), listOf(reference("environment.status@1.0"))),
        )

        val negotiation = provider.negotiate(request)

        assertEquals(provider.identity.instanceId, negotiation.providerInstanceId)
        assertEquals(CapabilityNegotiationState.SUPPORTED, negotiation.entries.single().state)
        assertEquals("environment.status@1.1", negotiation.entries.single().offered?.wireValue)
    }

    @Test
    fun executeAndCancelPreserveTheH007CorrelationTuple() {
        val provider = fakeProvider(
            ProviderFamily.BODY_ADAPTER,
            observeDescriptor(reference("environment.status@1.0")),
            ProviderRuntimeAvailability.AVAILABLE,
        )
        val request = requestFor(provider.identity.instanceId, reference("environment.status@1.0"))

        val execution = provider.execute(request)
        val cancellation = provider.cancel(
            CapabilityCancellationRequest.from(request, timestamp(11)),
        )

        assertEquals(CapabilityResultCorrelation.MATCHED, execution.correlationWith(request))
        assertEquals(CapabilityResultCorrelation.MATCHED, cancellation.correlationWith(request))
        assertEquals(CapabilityResultState.ACCEPTED, execution.state)
        assertEquals(CapabilityResultState.CANCELLED, cancellation.state)
        assertEquals(CapabilityFailureCode.CANCELLED, cancellation.failure?.code)
    }

    @Test
    fun portModelsRejectInvalidNegotiationAndDuplicateRegistrationReferences() {
        val reference = reference("environment.status@1.0")
        val empty = CapabilityNegotiationRequest.create(generation(1), emptyList())
        val duplicate = CapabilityNegotiationRequest.create(generation(1), listOf(reference, reference))
        val request = portSuccess(CapabilityNegotiationRequest.create(generation(1), listOf(reference)))
        val mismatched = CapabilityNegotiationResult.create(
            providerIdentity(ProviderFamily.BODY_ADAPTER).instanceId,
            request,
            emptyList(),
        )
        val descriptor = observeDescriptor(reference)
        val duplicateRegistrations = CapabilityRegistrationResult.create(
            providerIdentity(ProviderFamily.BODY_ADAPTER).instanceId,
            CapabilityRegistrationRequest.create(generation(1)),
            listOf(CapabilityRegistration(descriptor), CapabilityRegistration(descriptor)),
        )

        assertPortFailure(empty, CapabilityProviderPortError.EmptyNegotiation)
        assertPortFailure(duplicate, CapabilityProviderPortError.DuplicateCapabilityReferences)
        assertPortFailure(mismatched, CapabilityProviderPortError.NegotiationRequestMismatch)
        assertPortFailure(duplicateRegistrations, CapabilityProviderPortError.DuplicateCapabilityReferences)
    }

    @Test
    fun providerPortHasNoPlatformOrVendorImports() {
        val methods = CapabilityProvider::class.java.methods
        val exposedTypes = methods.flatMap { method ->
            method.parameterTypes.asList() + method.returnType
        } + CapabilityProvider::class.java

        assertFalse(exposedTypes.any { it.name.contains("android.") })
        assertFalse(exposedTypes.any { it.name.contains("bluetooth", ignoreCase = true) })
        assertTrue(methods.map { it.name }.containsAll(
            listOf("runtimeSnapshot", "negotiate", "registerCapabilities", "execute", "cancel"),
        ))
    }

    private inner class FakeProvider(
        override val identity: ProviderIdentity,
        private val descriptor: CapabilityDescriptor,
        private val availability: ProviderRuntimeAvailability,
    ) : CapabilityProvider {
        override fun runtimeSnapshot(): ProviderRuntimeSnapshot = portSuccess(
            ProviderRuntimeSnapshot.create(
                generation = generation(4),
                availability = availability,
                observedAt = timestamp(10),
                reason = if (availability == ProviderRuntimeAvailability.AVAILABLE) {
                    null
                } else {
                    ProviderAvailabilityReason.CONNECTION_UNAVAILABLE
                },
            ),
        )

        override fun negotiate(request: CapabilityNegotiationRequest): CapabilityNegotiationResult = portSuccess(
            CapabilityNegotiationResult.create(
                providerInstanceId = identity.instanceId,
                request = request,
                entries = request.requestedCapabilities.map { requested ->
                    val compatibility = descriptor.reference.compatibilityWith(requested)
                    when (compatibility) {
                        is CapabilityCompatibility.Exact,
                        is CapabilityCompatibility.BackwardCompatibleMinor,
                        -> CapabilityNegotiationEntry(
                            requested = requested,
                            state = CapabilityNegotiationState.SUPPORTED,
                            offered = descriptor.reference,
                        )
                        is CapabilityCompatibility.Incompatible -> CapabilityNegotiationEntry(
                            requested = requested,
                            state = CapabilityNegotiationState.INCOMPATIBLE,
                        )
                    }
                },
            ),
        )

        override fun registerCapabilities(
            request: CapabilityRegistrationRequest,
        ): CapabilityRegistrationResult = portSuccess(
            CapabilityRegistrationResult.create(
                providerInstanceId = identity.instanceId,
                request = request,
                registrations = listOf(CapabilityRegistration(descriptor)),
            ),
        )

        override fun execute(request: CapabilityRequest): CapabilityResult = envelopeSuccess(
            CapabilityResult.create(
                requestId = request.requestId,
                capability = request.capability,
                providerInstanceId = identity.instanceId,
                executionContext = request.executionContext,
                occurredAt = timestamp(10),
                state = CapabilityResultState.ACCEPTED,
                failure = null,
                evidence = CapabilityOutcomeEvidence.REQUEST_ACCEPTED,
                output = null,
                auditId = request.auditId,
            ),
        )

        override fun cancel(request: CapabilityCancellationRequest): CapabilityResult = envelopeSuccess(
            CapabilityResult.create(
                requestId = request.requestId,
                capability = request.capability,
                providerInstanceId = identity.instanceId,
                executionContext = request.executionContext,
                occurredAt = request.requestedAt,
                state = CapabilityResultState.CANCELLED,
                failure = CapabilityFailure(CapabilityFailureCode.CANCELLED),
                evidence = CapabilityOutcomeEvidence.LOCALLY_OBSERVED,
                output = null,
                auditId = request.auditId,
            ),
        )
    }

    private fun fakeProvider(
        family: ProviderFamily,
        descriptor: CapabilityDescriptor,
        availability: ProviderRuntimeAvailability,
    ): CapabilityProvider = FakeProvider(providerIdentity(family), descriptor, availability)

    private fun providerIdentity(family: ProviderFamily): ProviderIdentity = ProviderIdentity(
        definitionId = providerSuccess(ProviderDefinitionId.parse("test/provider")),
        instanceId = providerSuccess(
            ProviderInstanceId.parse("11111111-1111-4111-8111-111111111111"),
        ),
        family = family,
        displayAlias = providerSuccess(RedactedProviderAlias.parse("Test provider")),
    )

    private fun requestFor(
        providerInstanceId: ProviderInstanceId,
        capability: CapabilityReference,
    ): CapabilityRequest = envelopeSuccess(
        CapabilityRequest.create(
            requestId = envelopeSuccess(CapabilityRequestId.parse("22222222-2222-4222-8222-222222222222")),
            capability = capability,
            providerTarget = CapabilityProviderTarget.Exact(providerInstanceId),
            actor = CapabilityActorKind.LOCAL_USER,
            executionContext = envelopeSuccess(CapabilityExecutionContext.create(7, 3)),
            createdAt = timestamp(5),
            deadline = timestamp(20),
            authorizationReference = null,
            auditId = envelopeSuccess(CapabilityAuditId.parse("33333333-3333-4333-8333-333333333333")),
            input = null,
        ),
    )

    private fun observeDescriptor(reference: CapabilityReference): CapabilityDescriptor = descriptorSuccess(
        CapabilityDescriptor.create(
            reference = reference,
            label = descriptorSuccess(CapabilityLabel.parse("Status")),
            description = descriptorSuccess(CapabilityDescription.parse("Read-only status observation.")),
            inputContract = null,
            outputContract = null,
            effect = CapabilityEffect.OBSERVE,
            risk = CapabilityRiskClass.INFORMATIONAL,
            safety = CapabilitySafetyClass.NON_PHYSICAL,
            permissionIntent = CapabilityPermissionIntent.OBSERVE,
            approvalRequirement = CapabilityApprovalRequirement.WITHIN_GRANT,
            cancellation = CapabilityCancellationSemantics.NOT_APPLICABLE,
            availability = CapabilityAvailabilitySemantics.WHILE_REGISTERED,
            evidence = CapabilityEvidenceClass.DECLARED,
        ),
    )

    private fun reference(raw: String): CapabilityReference = identitySuccess(CapabilityReference.parse(raw))

    private fun generation(value: Long): ProviderGeneration = portSuccess(ProviderGeneration.create(value))

    private fun timestamp(value: Long): MonotonicTimestampMs = envelopeSuccess(
        MonotonicTimestampMs.create(value),
    )

    private fun <T> portSuccess(result: CapabilityProviderPortResult<T>): T = when (result) {
        is CapabilityProviderPortResult.Success -> result.value
        is CapabilityProviderPortResult.Failure -> throw AssertionError(result.error)
    }

    private fun assertPortFailure(
        result: CapabilityProviderPortResult<*>,
        expected: CapabilityProviderPortError,
    ) {
        assertTrue(result is CapabilityProviderPortResult.Failure)
        assertEquals(expected, (result as CapabilityProviderPortResult.Failure).error)
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

    private fun <T> envelopeSuccess(result: CapabilityEnvelopeResult<T>): T = when (result) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> throw AssertionError(result.error)
    }
}
