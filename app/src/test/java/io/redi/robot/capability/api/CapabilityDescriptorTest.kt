package io.redi.robot.capability.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityDescriptorTest {
    @Test
    fun `descriptor corpus spans observe act and modify without creating a catalog`() {
        val corpus = listOf(
            descriptor("vision.observe@1.0", CapabilityEffect.OBSERVE),
            descriptor("environment.light.control@1.0", CapabilityEffect.ACT),
            descriptor("system.configuration.change@1.0", CapabilityEffect.MODIFY),
        )

        assertEquals(
            setOf(CapabilityEffect.OBSERVE, CapabilityEffect.ACT, CapabilityEffect.MODIFY),
            corpus.map { it.effect }.toSet(),
        )
        assertTrue(corpus.first().isReadOnly)
        assertFalse(corpus[1].isReadOnly)
    }

    @Test
    fun `descriptor exposes semantic metadata and not current provider state`() {
        val descriptor = descriptor("power.battery.status@2.1", CapabilityEffect.OBSERVE)

        assertEquals("power.battery.status@2.1", descriptor.reference.wireValue)
        assertEquals("Reference capability", descriptor.label.value)
        assertEquals(CapabilityAvailabilitySemantics.DYNAMIC_STATE, descriptor.availability)
        assertEquals(CapabilityEvidenceClass.DOCUMENTED, descriptor.evidence)
        assertEquals(CapabilityPermissionIntent.OBSERVE, descriptor.permissionIntent)
    }

    @Test
    fun `input and output contract references are versioned and round trip`() {
        listOf(
            "hugo.contract.mobility_move_input@1.0",
            "hugo.contract.operation_result@2.3",
        ).forEach { wire ->
            val reference = successful(CapabilityContractReference.parse(wire))
            assertEquals(wire, reference.wireValue)
        }
    }

    @Test
    fun `permission must match side effect`() {
        val result = createDescriptor(
            effect = CapabilityEffect.ACT,
            permission = CapabilityPermissionIntent.OBSERVE,
        )

        assertEquals(
            CapabilityDescriptorResult.Failure(
                CapabilityDescriptorError.PermissionEffectConflict(
                    CapabilityEffect.ACT,
                    CapabilityPermissionIntent.OBSERVE,
                ),
            ),
            result,
        )
    }

    @Test
    fun `physical action and safety critical classifications reject conflicts`() {
        assertEquals(
            CapabilityDescriptorResult.Failure(
                CapabilityDescriptorError.SafetyEffectConflict(
                    CapabilityEffect.OBSERVE,
                    CapabilitySafetyClass.PHYSICAL_ACTION,
                ),
            ),
            createDescriptor(
                effect = CapabilityEffect.OBSERVE,
                permission = CapabilityPermissionIntent.OBSERVE,
                safety = CapabilitySafetyClass.PHYSICAL_ACTION,
            ),
        )
        assertEquals(
            CapabilityDescriptorResult.Failure(
                CapabilityDescriptorError.SafetyRiskConflict(
                    CapabilitySafetyClass.SAFETY_CRITICAL_ACTION,
                    CapabilityRiskClass.HIGH,
                ),
            ),
            createDescriptor(
                effect = CapabilityEffect.ACT,
                permission = CapabilityPermissionIntent.ACT,
                safety = CapabilitySafetyClass.SAFETY_CRITICAL_ACTION,
                risk = CapabilityRiskClass.HIGH,
            ),
        )
    }

    @Test
    fun `numeric limits require finite ordered bounds`() {
        val key = successful(CapabilityLimitKey.parse("linear_speed"))

        assertLimitFailure(key, null, null, CapabilityLimitProblem.MissingBounds)
        assertLimitFailure(key, Double.NaN, 1.0, CapabilityLimitProblem.NonFinite)
        assertLimitFailure(key, 0.0, Double.POSITIVE_INFINITY, CapabilityLimitProblem.NonFinite)
        assertLimitFailure(key, 2.0, 1.0, CapabilityLimitProblem.ReversedBounds)

        val valid = successful(
            CapabilityNumericLimit.create(
                key = key,
                minimum = -1.0,
                maximum = 1.0,
                unit = successful(CapabilityUnit.parse("m/s")),
            ),
        )
        assertEquals(-1.0, valid.minimum)
        assertEquals(1.0, valid.maximum)
    }

    @Test
    fun `duplicate limits and unbounded metadata are rejected`() {
        val key = successful(CapabilityLimitKey.parse("speed"))
        val limit = successful(CapabilityNumericLimit.create(key, 0.0, 1.0))
        assertEquals(
            CapabilityDescriptorResult.Failure(CapabilityDescriptorError.DuplicateLimitKeys),
            createDescriptor(limits = listOf(limit, limit)),
        )

        val metadata = (1..17).associate { index ->
            successful(CapabilityMetadataKey.parse("hint_$index")) to
                successful(CapabilityMetadataValue.parse("value"))
        }
        assertEquals(
            CapabilityDescriptorResult.Failure(
                CapabilityDescriptorError.TooManyMetadataEntries(16),
            ),
            createDescriptor(metadata = metadata),
        )
    }

    @Test
    fun `raw protocol metadata keys are rejected`() {
        listOf("gatt", "transport.uuid", "bluetooth_characteristic", "packet").forEach { raw ->
            assertTrue(CapabilityMetadataKey.parse(raw) is CapabilityDescriptorResult.Failure)
        }
    }

    @Test
    fun `descriptor collections are copied at construction`() {
        val limits = mutableListOf(
            successful(
                CapabilityNumericLimit.create(
                    key = successful(CapabilityLimitKey.parse("rate")),
                    minimum = 0.0,
                    maximum = 10.0,
                ),
            ),
        )
        val descriptor = successful(createDescriptor(limits = limits))

        limits.clear()
        assertEquals(1, descriptor.limits.size)
    }

    private fun descriptor(wire: String, effect: CapabilityEffect): CapabilityDescriptor = successful(
        createDescriptor(
            reference = successful(CapabilityReference.parse(wire)),
            effect = effect,
            permission = when (effect) {
                CapabilityEffect.OBSERVE -> CapabilityPermissionIntent.OBSERVE
                CapabilityEffect.ACT -> CapabilityPermissionIntent.ACT
                CapabilityEffect.MODIFY -> CapabilityPermissionIntent.MODIFY
            },
        ),
    )

    @Suppress("LongParameterList")
    private fun createDescriptor(
        reference: CapabilityReference = successful(CapabilityReference.parse("body.move@1.0")),
        effect: CapabilityEffect = CapabilityEffect.ACT,
        permission: CapabilityPermissionIntent = CapabilityPermissionIntent.ACT,
        safety: CapabilitySafetyClass = CapabilitySafetyClass.NON_PHYSICAL,
        risk: CapabilityRiskClass = CapabilityRiskClass.LOW,
        limits: List<CapabilityNumericLimit> = emptyList(),
        metadata: Map<CapabilityMetadataKey, CapabilityMetadataValue> = emptyMap(),
    ): CapabilityDescriptorResult<CapabilityDescriptor> = CapabilityDescriptor.create(
        reference = reference,
        label = successful(CapabilityLabel.parse("Reference capability")),
        description = successful(CapabilityDescription.parse("Semantic contract test fixture.")),
        inputContract = successful(
            CapabilityContractReference.parse("hugo.contract.operation_input@1.0"),
        ),
        outputContract = successful(
            CapabilityContractReference.parse("hugo.contract.operation_result@1.0"),
        ),
        effect = effect,
        risk = risk,
        safety = safety,
        permissionIntent = permission,
        approvalRequirement = CapabilityApprovalRequirement.EVERY_REQUEST,
        cancellation = CapabilityCancellationSemantics.SUPPORTED,
        availability = CapabilityAvailabilitySemantics.DYNAMIC_STATE,
        evidence = CapabilityEvidenceClass.DOCUMENTED,
        limits = limits,
        metadata = metadata,
    )

    private fun assertLimitFailure(
        key: CapabilityLimitKey,
        minimum: Double?,
        maximum: Double?,
        expected: CapabilityLimitProblem,
    ) {
        assertEquals(
            CapabilityDescriptorResult.Failure(
                CapabilityDescriptorError.InvalidLimit(key, expected),
            ),
            CapabilityNumericLimit.create(key, minimum, maximum),
        )
    }

    private fun <T> successful(result: CapabilityDescriptorResult<T>): T {
        assertTrue(result is CapabilityDescriptorResult.Success)
        return (result as CapabilityDescriptorResult.Success).value
    }

    private fun <T> successful(result: CapabilityIdentityResult<T>): T {
        assertTrue(result is CapabilityIdentityResult.Success)
        return (result as CapabilityIdentityResult.Success).value
    }
}
