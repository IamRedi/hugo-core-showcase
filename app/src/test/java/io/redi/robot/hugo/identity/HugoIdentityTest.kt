package io.redi.robot.hugo.identity

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HugoIdentityTest {
    private val canonicalId = "123e4567-e89b-12d3-a456-426614174000"

    @Test
    fun `identity ID is an opaque canonical value from caller-owned entropy`() {
        val id = HugoIdentityId.fromUuid(UUID.fromString(canonicalId))

        assertEquals(canonicalId, id.value)
        assertEquals(id, successfulId(HugoIdentityId.parse(canonicalId)))
    }

    @Test
    fun `blank identity ID has a typed error`() {
        val result = HugoIdentityId.parse("   ")

        assertTrue(result is HugoIdentityResult.Failure)
        assertSame(
            HugoIdentityError.BlankIdentityId,
            (result as HugoIdentityResult.Failure).error,
        )
    }

    @Test
    fun `malformed and noncanonical identity IDs are distinct typed errors`() {
        val malformed = HugoIdentityId.parse("phone-installation-42") as HugoIdentityResult.Failure
        val nonCanonical = HugoIdentityId.parse(canonicalId.uppercase()) as HugoIdentityResult.Failure

        assertEquals(
            HugoIdentityError.InvalidIdentityId(IdentityIdProblem.Malformed),
            malformed.error,
        )
        assertEquals(
            HugoIdentityError.InvalidIdentityId(IdentityIdProblem.NonCanonical),
            nonCanonical.error,
        )
    }

    @Test
    fun `identity serialization is deterministic and round trips`() {
        val identity = HugoIdentity(HugoIdentityId.fromUuid(UUID.fromString(canonicalId)))

        val encoded = HugoIdentityCodec.encode(identity)
        val decoded = HugoIdentityCodec.decode(encoded)

        assertEquals(
            "{\"schemaVersion\":\"1.0\",\"identityId\":\"$canonicalId\"}",
            encoded,
        )
        assertEquals(HugoIdentityResult.Success(identity), decoded)
    }

    @Test
    fun `unsupported contract version has a typed error`() {
        val encoded = "{\"schemaVersion\":\"2.0\",\"identityId\":\"$canonicalId\"}"

        assertEquals(
            HugoIdentityResult.Failure(HugoIdentityError.UnsupportedContractVersion("2.0")),
            HugoIdentityCodec.decode(encoded),
        )
    }

    @Test
    fun `blank malformed and non-object documents have typed errors`() {
        assertEquals(
            HugoIdentityResult.Failure(
                HugoIdentityError.InvalidDocument(IdentityDocumentProblem.Blank),
            ),
            HugoIdentityCodec.decode(" "),
        )
        assertEquals(
            HugoIdentityResult.Failure(
                HugoIdentityError.InvalidDocument(IdentityDocumentProblem.MalformedJson),
            ),
            HugoIdentityCodec.decode("{"),
        )
        assertEquals(
            HugoIdentityResult.Failure(
                HugoIdentityError.InvalidDocument(IdentityDocumentProblem.RootNotObject),
            ),
            HugoIdentityCodec.decode("[]"),
        )
    }

    @Test
    fun `missing and unexpected fields fail closed`() {
        assertEquals(
            HugoIdentityResult.Failure(HugoIdentityError.MissingField("identityId")),
            HugoIdentityCodec.decode("{\"schemaVersion\":\"1.0\"}"),
        )
        assertEquals(
            HugoIdentityResult.Failure(HugoIdentityError.UnexpectedFields(sortedSetOf("bodyId"))),
            HugoIdentityCodec.decode(
                "{\"schemaVersion\":\"1.0\",\"identityId\":\"$canonicalId\",\"bodyId\":\"looi\"}",
            ),
        )
    }

    @Test
    fun `serialized identity contains no host body device installation or provider association`() {
        val encoded = HugoIdentityCodec.encode(
            HugoIdentity(HugoIdentityId.fromUuid(UUID.fromString(canonicalId))),
        )
        val keys = Json.parseToJsonElement(encoded).jsonObject.keys

        assertEquals(setOf("schemaVersion", "identityId"), keys)
        listOf("host", "body", "device", "installation", "provider", "account").forEach { term ->
            assertFalse(encoded.contains(term, ignoreCase = true))
        }
    }

    private fun successfulId(result: HugoIdentityResult<HugoIdentityId>): HugoIdentityId {
        assertTrue(result is HugoIdentityResult.Success)
        return (result as HugoIdentityResult.Success).value
    }
}
