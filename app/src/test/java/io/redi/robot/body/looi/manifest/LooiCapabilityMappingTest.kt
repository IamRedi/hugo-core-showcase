package io.redi.robot.body.looi.manifest

import io.redi.robot.capability.api.CapabilityApprovalRequirement
import io.redi.robot.capability.api.CapabilityEffect
import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityPermissionIntent
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.CapabilityRiskClass
import io.redi.robot.capability.api.CapabilitySafetyClass
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LooiCapabilityMappingTest {
    @Test
    fun everyMappingIsHardwareEvidenceSupportedAndHasAnAdapterBoundary() {
        assertEquals(6, LooiCapabilityCatalog.supported.size)
        LooiCapabilityCatalog.supported.forEach { mapping ->
            assertTrue(mapping.evidence.isNotEmpty())
            assertTrue(mapping.limits.isNotEmpty())
            assertEquals(CapabilityEvidenceClass.HARDWARE_VERIFIED, mapping.descriptor.evidence)
            assertFalse(mapping.descriptor.reference.id.wireValue.contains("looi", ignoreCase = true))
        }
    }

    @Test
    fun actionRiskPermissionAndSafetyMetadataRemainStrict() {
        val move = mapping("mobility.move@1.0")
        val stop = mapping("mobility.stop@1.0")
        val head = mapping("body.head.position.control@1.0")
        val light = mapping("body.light.control@1.0")
        val contact = mapping("safety.lower_contact.status@1.0")

        assertEquals(CapabilityRiskClass.SAFETY_CRITICAL, move.descriptor.risk)
        assertEquals(CapabilityApprovalRequirement.EVERY_REQUEST, move.descriptor.approvalRequirement)
        assertEquals(CapabilityPermissionIntent.ACT, move.descriptor.permissionIntent)
        assertEquals(CapabilitySafetyClass.SAFETY_CRITICAL_ACTION, stop.descriptor.safety)
        assertEquals(CapabilityApprovalRequirement.EVERY_REQUEST, head.descriptor.approvalRequirement)
        assertEquals(CapabilityRiskClass.LOW, light.descriptor.risk)
        assertEquals(CapabilityEffect.OBSERVE, contact.descriptor.effect)
        assertEquals(CapabilityPermissionIntent.OBSERVE, contact.descriptor.permissionIntent)
    }

    @Test
    fun unsupportedSemanticCapabilityIsRejectedWithoutInventingTelemetryClaims() {
        val battery = reference("power.battery.status@1.0")

        assertEquals(LooiCapabilityLookupResult.Unsupported(battery), LooiCapabilityCatalog.lookup(battery))
    }

    @Test
    fun actuationMappingsRetainTheExistingSafetyBoundary() {
        LooiCapabilityCatalog.supported.filter { it.descriptor.effect == CapabilityEffect.ACT }.forEach {
            assertTrue(it.preservesExistingSafetyPath)
        }
        assertEquals(
            ExistingLooiImplementationBoundary.EXISTING_DRIVE_SAFETY_PATH,
            mapping("mobility.move@1.0").boundary,
        )
    }

    @Test
    fun mappingDoesNotExposeProtocolDetailsAndCoreApiContainNoProtocolConstants() {
        val forbidden = listOf("FED0", "FED8", "FED9", "BluetoothGatt", "BluetoothDevice")
        LooiCapabilityCatalog.supported.forEach { mapping ->
            val publicText = listOf(
                mapping.descriptor.reference.wireValue,
                mapping.descriptor.label.value,
                mapping.descriptor.description.value,
            ).joinToString(" ")
            forbidden.forEach { token -> assertFalse(publicText.contains(token, ignoreCase = true)) }
        }

        val root = Path.of(System.getProperty("user.dir"))
        val coreSources = listOf(
            root.resolve("app/src/main/java/io/redi/robot/core"),
            root.resolve("app/src/main/java/io/redi/robot/capability/api"),
        )
        coreSources.filter(Files::exists).flatMap { directory ->
            Files.walk(directory).use { files -> files.filter { it.toString().endsWith(".kt") }.toList() }
        }.forEach { source ->
            val text = String(Files.readAllBytes(source), Charsets.UTF_8)
            forbidden.forEach { token -> assertFalse("$token leaked into $source", text.contains(token)) }
        }
    }

    @Test
    fun mappingDoesNotChangeRuntimeAvailabilityPermissionOrActiveBodyTruth() {
        val connection = mapping("provider.connection.status@1.0")
        val move = mapping("mobility.move@1.0")

        assertEquals(CapabilityEffect.OBSERVE, connection.descriptor.effect)
        assertEquals(CapabilityApprovalRequirement.EVERY_REQUEST, move.descriptor.approvalRequirement)
        assertTrue(move.preservesExistingSafetyPath)
    }

    private fun mapping(raw: String): LooiCapabilityMapping = when (val result = LooiCapabilityCatalog.lookup(reference(raw))) {
        is LooiCapabilityLookupResult.Supported -> result.mapping
        is LooiCapabilityLookupResult.Unsupported -> error("Expected supported capability: $raw")
    }

    private fun reference(raw: String): CapabilityReference = when (val result = CapabilityReference.parse(raw)) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> error("Invalid test reference: $result")
    }
}
