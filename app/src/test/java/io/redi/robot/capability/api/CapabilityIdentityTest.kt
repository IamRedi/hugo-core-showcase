package io.redi.robot.capability.api

import io.redi.robot.capability.provider.ProviderDefinitionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityIdentityTest {
    @Test
    fun `semantic IDs parse into namespace and name without provider coupling`() {
        val cases = mapOf(
            "body.move" to ("body" to "move"),
            "device.capture_image" to ("device" to "capture_image"),
            "service.calendar.read" to ("service.calendar" to "read"),
            "environment.light.control" to ("environment.light" to "control"),
        )

        cases.forEach { (wire, expected) ->
            val id = successful(CapabilityId.parse(wire))
            assertEquals(expected.first, id.namespace.value)
            assertEquals(expected.second, id.name.value)
            assertEquals(wire, id.wireValue)
        }
        assertNotEquals(CapabilityId::class, ProviderDefinitionId::class)
    }

    @Test
    fun `blank noncanonical and incomplete IDs fail closed`() {
        assertEquals(
            CapabilityIdentityResult.Failure(CapabilityIdentityError.BlankCapabilityId),
            CapabilityId.parse(" "),
        )
        assertEquals(
            CapabilityIdentityResult.Failure(CapabilityIdentityError.MalformedCapabilityId),
            CapabilityId.parse("move"),
        )
        assertEquals(
            CapabilityIdentityResult.Failure(
                CapabilityIdentityError.InvalidNamespace(CapabilityNameProblem.Malformed),
            ),
            CapabilityId.parse("Vendor.move"),
        )
        assertEquals(
            CapabilityIdentityResult.Failure(
                CapabilityIdentityError.InvalidName(CapabilityNameProblem.Malformed),
            ),
            CapabilityId.parse("body.move-fast"),
        )
    }

    @Test
    fun `capability reference wire representation round trips`() {
        val reference = successful(CapabilityReference.parse("service.calendar.read@2.7"))

        assertEquals("service.calendar.read@2.7", reference.wireValue)
        assertEquals(CapabilityIdentityResult.Success(reference), CapabilityReference.parse(reference.wireValue))
    }

    @Test
    fun `version boundaries have typed failures`() {
        assertEquals(
            CapabilityIdentityResult.Failure(
                CapabilityIdentityError.InvalidVersion(CapabilityVersionProblem.MajorMustBePositive),
            ),
            CapabilityVersion.create(0, 0),
        )
        assertEquals(
            CapabilityIdentityResult.Failure(
                CapabilityIdentityError.InvalidVersion(CapabilityVersionProblem.MinorMustNotBeNegative),
            ),
            CapabilityVersion.create(1, -1),
        )
        assertEquals(
            CapabilityIdentityResult.Failure(
                CapabilityIdentityError.InvalidVersion(CapabilityVersionProblem.Malformed),
            ),
            CapabilityVersion.parse("01.0"),
        )
        assertEquals(
            CapabilityIdentityResult.Failure(
                CapabilityIdentityError.InvalidVersion(CapabilityVersionProblem.OutsideIntegerRange),
            ),
            CapabilityVersion.parse("2147483648.0"),
        )
        assertEquals(Int.MAX_VALUE, successful(CapabilityVersion.parse("2147483647.0")).major)
    }

    @Test
    fun `same version is exact and newer offered minor is backward compatible`() {
        val id = successful(CapabilityId.parse("vision.observe"))
        for (major in 1..4) {
            for (requiredMinor in 0..4) {
                for (offeredMinor in 0..4) {
                    val required = reference(id, major, requiredMinor)
                    val offered = reference(id, major, offeredMinor)
                    val result = offered.compatibilityWith(required)

                    when {
                        offeredMinor == requiredMinor -> assertEquals(CapabilityCompatibility.Exact, result)
                        offeredMinor > requiredMinor -> assertEquals(
                            CapabilityCompatibility.BackwardCompatibleMinor(
                                offered = offered.version,
                                required = required.version,
                            ),
                            result,
                        )
                        else -> assertEquals(
                            CapabilityCompatibility.Incompatible(
                                CapabilityIncompatibilityReason.OfferedMinorTooOld,
                            ),
                            result,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `major mismatch and different capability are explicitly incompatible`() {
        val required = successful(CapabilityReference.parse("power.battery.status@1.4"))
        val newMajor = successful(CapabilityReference.parse("power.battery.status@2.4"))
        val differentId = successful(CapabilityReference.parse("power.external.status@1.4"))

        assertEquals(
            CapabilityCompatibility.Incompatible(
                CapabilityIncompatibilityReason.MajorVersionMismatch,
            ),
            newMajor.compatibilityWith(required),
        )
        assertEquals(
            CapabilityCompatibility.Incompatible(
                CapabilityIncompatibilityReason.DifferentCapability,
            ),
            differentId.compatibilityWith(required),
        )
    }

    @Test
    fun `malformed references cannot hide missing identity or version`() {
        listOf("body.move", "@1.0", "body.move@", "body.move@1.0@extra").forEach { malformed ->
            assertTrue(CapabilityReference.parse(malformed) is CapabilityIdentityResult.Failure)
        }
    }

    private fun reference(
        id: CapabilityId,
        major: Int,
        minor: Int,
    ): CapabilityReference = CapabilityReference(
        id = id,
        version = successful(CapabilityVersion.create(major, minor)),
    )

    private fun <T> successful(result: CapabilityIdentityResult<T>): T {
        assertTrue(result is CapabilityIdentityResult.Success)
        return (result as CapabilityIdentityResult.Success).value
    }
}
