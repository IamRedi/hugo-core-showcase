package io.redi.robot.body.looi.capability

import io.redi.robot.body.looi.manifest.ExistingLooiImplementationBoundary
import io.redi.robot.body.looi.manifest.LooiCapabilityCatalog
import io.redi.robot.capability.api.CapabilityActorKind
import io.redi.robot.capability.api.CapabilityArgumentName
import io.redi.robot.capability.api.CapabilityArgumentValue
import io.redi.robot.capability.api.CapabilityAuditId
import io.redi.robot.capability.api.CapabilityContractReference
import io.redi.robot.capability.api.CapabilityData
import io.redi.robot.capability.api.CapabilityDescriptorResult
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.CapabilityExecutionContext
import io.redi.robot.capability.api.CapabilityFailureCode
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityProviderTarget
import io.redi.robot.capability.api.CapabilityReasonCode
import io.redi.robot.capability.api.CapabilityRequest
import io.redi.robot.capability.api.CapabilityRequestId
import io.redi.robot.capability.api.CapabilityResultCorrelation
import io.redi.robot.capability.api.CapabilityResultState
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.capability.provider.ProviderRuntimeSnapshot
import io.redi.robot.capability.provider.RedactedProviderAlias
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LooiCompatibilityFacadeTest {
    @Test
    fun supportedCapabilityRoutesToTheMappedExistingBoundary() {
        val runtime = FakeRuntime()
        val facade = facade(runtime)
        val request = request("mobility.move@1.0", input = semanticInput())

        val result = facade.execute(request)

        assertEquals(CapabilityResultState.ACCEPTED, result.state)
        assertEquals(ExistingLooiImplementationBoundary.EXISTING_DRIVE_SAFETY_PATH, runtime.invocations.single().boundary)
        assertEquals(request.requestId, runtime.invocations.single().correlation.requestId)
    }

    @Test
    fun unsupportedCapabilityFailsClosedWithoutRuntimeInvocation() {
        val runtime = FakeRuntime()
        val result = facade(runtime).execute(request("power.battery.status@1.0"))

        assertEquals(CapabilityResultState.REJECTED, result.state)
        assertEquals(CapabilityFailureCode.UNSUPPORTED_CAPABILITY, result.failure?.code)
        assertTrue(runtime.invocations.isEmpty())
    }

    @Test
    fun semanticMotionCanOnlyReceiveTheSafetyOwnedRuntimeOutcome() {
        val runtime = FakeRuntime().apply {
            nextOutcome = { invocation ->
                LooiVerifiedRuntimeOutcome.SafetyRejected(invocation.correlation, time(20), reason("safety.rejected"))
            }
        }
        val result = facade(runtime).execute(request("mobility.move@1.0", input = semanticInput()))

        assertEquals(CapabilityResultState.REJECTED, result.state)
        assertEquals(CapabilityFailureCode.SAFETY_REJECTED, result.failure?.code)
        assertEquals(ExistingLooiImplementationBoundary.EXISTING_DRIVE_SAFETY_PATH, runtime.invocations.single().boundary)
    }

    @Test
    fun stopUsesThePriorityStopPath() {
        val runtime = FakeRuntime()
        val result = facade(runtime).execute(request("mobility.stop@1.0"))

        assertEquals(CapabilityResultState.ACCEPTED, result.state)
        assertEquals(LooiRuntimePriority.STOP, runtime.invocations.single().priority)
        assertEquals(ExistingLooiImplementationBoundary.EXISTING_DRIVE_SAFETY_PATH, runtime.invocations.single().boundary)
    }

    @Test
    fun staleProviderGenerationIsRejectedBeforeRuntime() {
        val runtime = FakeRuntime()
        val stale = request("mobility.stop@1.0", epoch = 3L)
        val result = facade(runtime).execute(stale)

        assertEquals(CapabilityResultState.REJECTED, result.state)
        assertEquals(CapabilityFailureCode.INVALID_REQUEST, result.failure?.code)
        assertTrue(runtime.invocations.isEmpty())
    }

    @Test
    fun requestCorrelationIsPreservedAndMismatchedRuntimeOutcomeFails() {
        val runtime = FakeRuntime()
        val request = request("mobility.stop@1.0")
        val accepted = facade(runtime).execute(request)
        assertEquals(CapabilityResultCorrelation.MATCHED, accepted.correlationWith(request))
        assertEquals(4L, runtime.invocations.single().correlation.providerGeneration.value)
        assertEquals(request.executionContext, runtime.invocations.single().correlation.executionContext)
        assertEquals(request.auditId, runtime.invocations.single().correlation.auditId)

        runtime.nextOutcome = { invocation ->
            LooiVerifiedRuntimeOutcome.Accepted(
                invocation.correlation.copy(requestId = requestId("00000000-0000-0000-0000-000000000077")),
                time(20),
            )
        }
        val mismatched = facade(runtime).execute(request("mobility.stop@1.0"))
        assertEquals(CapabilityResultState.FAILED, mismatched.state)
        assertEquals(CapabilityFailureCode.MALFORMED_RESULT, mismatched.failure?.code)
    }

    @Test
    fun lowerContactAndConnectionAreReadOnlyAndReturnNormalizedOutput() {
        val runtime = FakeRuntime().apply {
            nextOutcome = { invocation -> LooiVerifiedRuntimeOutcome.Completed(invocation.correlation, time(20), semanticOutput()) }
        }
        val facade = facade(runtime)
        val contact = facade.execute(request("safety.lower_contact.status@1.0"))
        val connection = facade.execute(request("provider.connection.status@1.0"))
        val invalidInput = facade.execute(request("safety.lower_contact.status@1.0", input = semanticInput()))

        assertEquals(CapabilityResultState.COMPLETED, contact.state)
        assertEquals(CapabilityResultState.COMPLETED, connection.state)
        assertTrue(contact.output != null)
        assertEquals(CapabilityResultState.REJECTED, invalidInput.state)
        assertEquals(CapabilityFailureCode.INVALID_REQUEST, invalidInput.failure?.code)
    }

    @Test
    fun facadeExposesOnlyTheSixMappedCapabilitiesAndNoProtocolOrLegacyExecutorDetails() {
        assertEquals(6, LooiCapabilityCatalog.supported.size)
        assertFalse(LooiCapabilityCatalog.supported.any { it.descriptor.reference.wireValue == "power.battery.status@1.0" })

        val workingDirectory = Path.of(System.getProperty("user.dir"))
        val sourcePath = listOf(
            workingDirectory.resolve("app/src/main/java/io/redi/robot/body/looi/capability/LooiCompatibilityFacade.kt"),
            workingDirectory.resolve("src/main/java/io/redi/robot/body/looi/capability/LooiCompatibilityFacade.kt"),
        ).first(Files::exists)
        val source = String(Files.readAllBytes(sourcePath), Charsets.UTF_8)
        listOf("FED0", "FED8", "FED9", "BluetoothGatt", "BluetoothDevice", "ProfileADriveCommandExecutor").forEach {
            assertFalse("$it leaked into facade", source.contains(it))
        }
    }

    private fun facade(runtime: FakeRuntime): LooiCompatibilityFacade = LooiCompatibilityFacade(provider(), runtime)

    private fun request(
        capability: String,
        epoch: Long = 4L,
        input: CapabilityData? = null,
    ): CapabilityRequest = success(
        CapabilityRequest.create(
            requestId(), reference(capability), CapabilityProviderTarget.Exact(provider().instanceId),
            CapabilityActorKind.HUGO_RUNTIME, success(CapabilityExecutionContext.create(epoch, 9L)),
            time(10), time(100), null, auditId(), input,
        ),
    )

    private fun semanticInput(): CapabilityData = success(
        CapabilityData.create(
            contract(),
            mapOf(argument("linear") to success(CapabilityArgumentValue.Decimal.create(0.2))),
        ),
    )

    private fun semanticOutput(): CapabilityData = success(
        CapabilityData.create(contract(), mapOf(argument("available") to CapabilityArgumentValue.Flag(true))),
    )

    private fun provider(): ProviderIdentity = ProviderIdentity(
        success(ProviderDefinitionId.parse("redi/looi")),
        success(ProviderInstanceId.parse("00000000-0000-0000-0000-000000000001")),
        io.redi.robot.capability.provider.ProviderFamily.BODY_ADAPTER,
        success(RedactedProviderAlias.parse("Reference body")),
    )

    private fun contract(): CapabilityContractReference = success(CapabilityContractReference.parse("semantic.status@1.0"))
    private fun argument(value: String): CapabilityArgumentName = success(CapabilityArgumentName.parse(value))
    private fun reference(value: String): CapabilityReference = success(CapabilityReference.parse(value))
    private fun requestId(value: String = UUID.randomUUID().toString()): CapabilityRequestId = success(CapabilityRequestId.parse(value))
    private fun auditId(): CapabilityAuditId = success(CapabilityAuditId.parse(UUID.randomUUID().toString()))
    private fun generation(): ProviderGeneration = success(ProviderGeneration.create(4L))
    private fun time(value: Long): MonotonicTimestampMs = success(MonotonicTimestampMs.create(value))
    private fun reason(value: String): CapabilityReasonCode = success(CapabilityReasonCode.parse(value))

    private inner class FakeRuntime : LooiVerifiedRuntimePort {
        val invocations = mutableListOf<LooiRuntimeInvocation>()
        var nextOutcome: (LooiRuntimeInvocation) -> LooiVerifiedRuntimeOutcome = { invocation ->
            LooiVerifiedRuntimeOutcome.Accepted(invocation.correlation, time(20))
        }

        override fun runtimeSnapshot(): ProviderRuntimeSnapshot = success(
            ProviderRuntimeSnapshot.create(generation(), ProviderRuntimeAvailability.AVAILABLE, time(10), null),
        )

        override fun execute(invocation: LooiRuntimeInvocation): LooiVerifiedRuntimeOutcome {
            invocations += invocation
            return nextOutcome(invocation)
        }

        override fun cancel(invocation: LooiRuntimeInvocation): LooiVerifiedRuntimeOutcome = nextOutcome(invocation)
    }

    private fun <T> success(result: Any): T = when (result) {
        is CapabilityEnvelopeResult.Success<*> -> result.value as T
        is CapabilityIdentityResult.Success<*> -> result.value as T
        is CapabilityDescriptorResult.Success<*> -> result.value as T
        is ProviderIdentityResult.Success<*> -> result.value as T
        is io.redi.robot.capability.provider.CapabilityProviderPortResult.Success<*> -> result.value as T
        else -> error("Expected success, got $result")
    }
}
