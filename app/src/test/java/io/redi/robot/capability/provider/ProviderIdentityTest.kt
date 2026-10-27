package io.redi.robot.capability.provider

import io.redi.robot.hugo.identity.HugoIdentityId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderIdentityTest {
    private val instanceUuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")

    @Test
    fun `definition and private instance IDs have distinct canonical scopes`() {
        val definition = successful(ProviderDefinitionId.parse("io.hugo.body/looi"))
        val instance = ProviderInstanceId.fromUuid(instanceUuid)
        val hugo = HugoIdentityId.fromUuid(instanceUuid)

        assertEquals("io.hugo.body/looi", definition.value)
        assertEquals(instanceUuid.toString(), instance.value)
        assertNotEquals(ProviderDefinitionId::class, ProviderInstanceId::class)
        assertNotEquals(ProviderInstanceId::class, HugoIdentityId::class)
        assertEquals(hugo.value, instance.value)
    }

    @Test
    fun `invalid definition and instance values fail with scoped errors`() {
        assertEquals(
            ProviderIdentityResult.Failure(
                ProviderIdentityError.InvalidDefinitionId(ProviderIdentifierProblem.Blank),
            ),
            ProviderDefinitionId.parse(" "),
        )
        assertEquals(
            ProviderIdentityResult.Failure(
                ProviderIdentityError.InvalidDefinitionId(ProviderIdentifierProblem.Malformed),
            ),
            ProviderDefinitionId.parse("LOOI-MAC"),
        )
        assertEquals(
            ProviderIdentityResult.Failure(
                ProviderIdentityError.InvalidInstanceId(ProviderIdentifierProblem.NonCanonical),
            ),
            ProviderInstanceId.parse(instanceUuid.toString().uppercase()),
        )
    }

    @Test
    fun `provider family has exact body device and service roles`() {
        assertEquals(
            setOf("body_adapter", "device_adapter", "service_connector"),
            ProviderFamily.entries.map { it.wireValue }.toSet(),
        )
    }

    @Test
    fun `redacted alias rejects stable transport address`() {
        assertEquals(
            ProviderIdentityResult.Failure(
                ProviderIdentityError.InvalidAlias(ProviderAliasProblem.StableAddress),
            ),
            RedactedProviderAlias.parse("LOOI AA:BB:CC:DD:EE:FF"),
        )
    }

    @Test
    fun `identity round trips for every provider family`() {
        ProviderFamily.entries.forEach { family ->
            val identity = identity(family)

            assertEquals(
                ProviderIdentityResult.Success(identity),
                ProviderIdentityCodec.decode(ProviderIdentityCodec.encode(identity)),
            )
        }
    }

    @Test
    fun `redacted projection cannot expose private instance ID`() {
        val identity = identity(ProviderFamily.BODY_ADAPTER)
        val redacted = identity.redacted()

        assertEquals(identity.definitionId, redacted.definitionId)
        assertEquals(identity.family, redacted.family)
        assertEquals(identity.displayAlias, redacted.displayAlias)
        assertFalse(redacted.toString().contains(identity.instanceId.value))
    }

    @Test
    fun `unknown schema and transport fields fail closed`() {
        val encoded = ProviderIdentityCodec.encode(identity(ProviderFamily.DEVICE_ADAPTER))
        assertEquals(
            ProviderIdentityResult.Failure(
                ProviderIdentityError.UnsupportedSchemaVersion("2.0"),
            ),
            ProviderIdentityCodec.decode(encoded.replace("\"1.0\"", "\"2.0\"")),
        )

        val withAddress = encoded.dropLast(1) + ",\"transportAddress\":\"private\"}"
        assertEquals(
            ProviderIdentityResult.Failure(
                ProviderIdentityError.UnexpectedFields(sortedSetOf("transportAddress")),
            ),
            ProviderIdentityCodec.decode(withAddress),
        )
    }

    private fun identity(family: ProviderFamily): ProviderIdentity = ProviderIdentity(
        definitionId = successful(ProviderDefinitionId.parse("io.hugo.reference/fixture")),
        instanceId = ProviderInstanceId.fromUuid(instanceUuid),
        family = family,
        displayAlias = successful(RedactedProviderAlias.parse("Reference provider")),
    )

    private fun <T> successful(result: ProviderIdentityResult<T>): T {
        assertTrue(result is ProviderIdentityResult.Success)
        return (result as ProviderIdentityResult.Success).value
    }
}
