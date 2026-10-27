package io.redi.robot.gateway.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GatewayContractTest {
    @Test
    fun `text realtime vision and embedding request shapes round trip canonically`() {
        val documents = listOf(
            request("text", "\"prompt\":\"Hello\",") ,
            request(
                "realtime",
                "\"realtime\":{\"inputModalities\":[\"text\",\"audio\"],\"outputModalities\":[\"audio\"]},",
            ),
            request("vision", "\"prompt\":\"What is visible?\",\"attachmentReference\":\"frame:Abcdefgh123\","),
            request("embedding", "\"embeddingInputs\":[\"one\",\"two\"],", skills = "[]"),
        )

        val decoded = documents.map { successful(GatewayContractCodec.decodeRequest(it)) }

        assertEquals(GatewayOperation.entries, decoded.map { it.operation })
        decoded.forEach { value ->
            val canonical = GatewayContractCodec.encodeRequest(value)
            assertEquals(canonical, GatewayContractCodec.encodeRequest(successful(GatewayContractCodec.decodeRequest(canonical))))
        }
        assertEquals(setOf(GatewayRealtimeModality.AUDIO, GatewayRealtimeModality.TEXT), decoded[1].realtime?.inputModalities)
        assertEquals("gateway-attachment:[redacted]", decoded[2].attachmentReference.toString())
    }

    @Test
    fun `cancellation is exact correlated and canonical`() {
        val raw = """{"schemaVersion":"1.0","messageType":"cancel","requestId":"10000000-0000-4000-8000-000000000001","cancellationId":"40000000-0000-4000-8000-000000000001","reason":"barge_in"}"""
        val value = successful(GatewayContractCodec.decodeCancellation(raw))

        assertEquals(GatewayCancellationReason.BARGE_IN, value.reason)
        assertEquals(raw, GatewayContractCodec.encodeCancellation(value))
        assertEquals("gateway-request:[redacted]", value.requestId.toString())
    }

    @Test
    fun `portable examples validate partial output proposal refusal timeout usage and embedding`() {
        val examples = contractFile("v1/examples").listFiles().orEmpty().sortedBy { it.name }
        assertEquals(
            listOf(
                "cancelled.json",
                "embedding-complete.json",
                "failure.json",
                "refusal.json",
                "text-partial-usage-complete.json",
                "timeout.json",
                "tool-proposal.json",
            ),
            examples.map { it.name },
        )

        val payloadTypes = mutableSetOf<String>()
        examples.forEach { example ->
            val elements = Json.parseToJsonElement(example.readText()) as JsonArray
            val events = elements.map { element -> successful(GatewayContractCodec.decodeEvent(element.toString())) }
            assertTrue(GatewayStreamValidator.validate(events) is GatewayContractResult.Success)
            events.forEach { payloadTypes += it.payload::class.simpleName.orEmpty() }
            events.forEach { event ->
                val canonical = GatewayContractCodec.encodeEvent(event)
                assertEquals(canonical, GatewayContractCodec.encodeEvent(successful(GatewayContractCodec.decodeEvent(canonical))))
            }
        }

        assertTrue(
            payloadTypes.containsAll(
                setOf("PartialText", "ToolProposal", "Refusal", "TimedOut", "Usage", "Embedding", "Cancelled", "Failed"),
            ),
        )
    }

    @Test
    fun `stream order is consecutive correlated unique and exactly one terminal`() {
        val valid = eventsFromExample("text-partial-usage-complete.json")
        assertTrue(GatewayStreamValidator.validate(valid) is GatewayContractResult.Success)

        assertFailure(GatewayStreamValidator.validate(valid.drop(1)), GatewayContractError.INVALID_EVENT_ORDER)
        assertFailure(GatewayStreamValidator.validate(valid.dropLast(1)), GatewayContractError.INVALID_EVENT_ORDER)
        assertFailure(
            GatewayStreamValidator.validate(listOf(valid[0], valid[2], valid[3])),
            GatewayContractError.INVALID_EVENT_ORDER,
        )
        assertFailure(
            GatewayStreamValidator.validate(listOf(valid[0], valid[1], valid[2], valid[2], valid[3])),
            GatewayContractError.CORRELATION_MISMATCH,
        )
        assertFailure(
            GatewayStreamValidator.validate(listOf(valid[0], valid[0].copy(sequence = 1), valid[3].copy(sequence = 2))),
            GatewayContractError.CORRELATION_MISMATCH,
        )
    }

    @Test
    fun `tool proposal is H050 reference only and carries no authority`() {
        val event = eventsFromExample("tool-proposal.json")[1]
        val proposal = (event.payload as GatewayEventPayload.ToolProposal).proposal
        val encoded = GatewayContractCodec.encodeEvent(event).lowercase()

        assertEquals("hugo.local.connection_status@1.0", proposal.skill.wireValue)
        assertTrue(proposal.arguments.isEmpty())
        assertEquals("gateway-event:[redacted]", proposal.proposalId.toString())
        listOf("authorized", "trusted", "safetyapproved", "executionauthority", "capabilityrequest").forEach {
            assertFalse(encoded.contains(it))
        }

        val matchingRequest = successful(
            GatewayContractCodec.decodeRequest(
                request(
                    operation = "text",
                    operationFields = "\"prompt\":\"Hello\",",
                    skills = "[\"hugo.local.connection_status@1.0\"]",
                    requestId = "10000000-0000-4000-8000-000000000002",
                ),
            ),
        )
        assertTrue(
            GatewayStreamValidator.validate(matchingRequest, eventsFromExample("tool-proposal.json"))
                is GatewayContractResult.Success,
        )
        val undeclaredRequest = successful(
            GatewayContractCodec.decodeRequest(
                request(
                    operation = "text",
                    operationFields = "\"prompt\":\"Hello\",",
                    requestId = "10000000-0000-4000-8000-000000000002",
                ),
            ),
        )
        assertFailure(
            GatewayStreamValidator.validate(undeclaredRequest, eventsFromExample("tool-proposal.json")),
            GatewayContractError.UNDECLARED_SKILL_PROPOSAL,
        )
    }

    @Test
    fun `usage distinguishes measured estimated and unknown without inventing cost`() {
        val measured = eventsFromExample("text-partial-usage-complete.json")[2].payload as GatewayEventPayload.Usage
        val unknown = eventsFromExample("embedding-complete.json")[2].payload as GatewayEventPayload.Usage

        assertEquals(GatewayUsageEvidence.MEASURED, measured.usage.evidence)
        assertEquals(42L, measured.usage.estimatedCost?.micros)
        assertEquals(GatewayUsageEvidence.UNKNOWN, unknown.usage.evidence)
        assertEquals(null, unknown.usage.inputUnits)
        assertEquals(null, unknown.usage.estimatedCost)
        assertFailure(
            GatewayUsage.create(1, null, GatewayUsageEvidence.UNKNOWN, null),
            GatewayContractError.INVALID_USAGE,
        )
    }

    @Test
    fun `schema compatibility is explicit and major changes fail closed`() {
        val required = GatewaySchemaVersion.CURRENT
        assertEquals(GatewaySchemaCompatibility.EXACT, required.compatibilityWith(required))
        assertEquals(
            GatewaySchemaCompatibility.COMPATIBLE_ADDITIVE_MINOR,
            GatewaySchemaVersion(1, 1).compatibilityWith(required),
        )
        assertEquals(
            GatewaySchemaCompatibility.UNSUPPORTED_OLDER_MINOR,
            GatewaySchemaVersion(1, 0).compatibilityWith(GatewaySchemaVersion(1, 1)),
        )
        assertEquals(
            GatewaySchemaCompatibility.INCOMPATIBLE_MAJOR,
            GatewaySchemaVersion(2, 0).compatibilityWith(required),
        )
        assertFailure(
            GatewayContractCodec.decodeRequest(request("text", "\"prompt\":\"Hello\",", version = "2.0")),
            GatewayContractError.UNSUPPORTED_SCHEMA_VERSION,
        )
    }

    @Test
    fun `strict decoder rejects missing unexpected malformed and contradictory fields`() {
        val valid = request("text", "\"prompt\":\"Hello\",")
        assertFailure(
            GatewayContractCodec.decodeRequest(valid.dropLast(1) + ",\"apiKey\":\"forbidden\"}"),
            GatewayContractError.UNEXPECTED_FIELD,
        )
        assertFailure(
            GatewayContractCodec.decodeRequest(valid.replace("\"prompt\":\"Hello\",", "")),
            GatewayContractError.MISSING_FIELD,
        )
        assertFailure(
            GatewayContractCodec.decodeRequest(valid.replace("\"operation\":\"text\"", "\"operation\":\"vision\"")),
            GatewayContractError.MISSING_FIELD,
        )
        assertTrue(GatewayContractCodec.decodeRequest("not-json") is GatewayContractResult.Failure)
    }

    @Test
    fun `content vectors tools budgets and event counts are hard bounded`() {
        assertFailure(GatewayText.create("x".repeat(GatewayText.MAX_CODE_POINTS + 1)), GatewayContractError.LIMIT_EXCEEDED)
        assertFailure(GatewayBudget.create(0, 1_000), GatewayContractError.INVALID_BUDGET)
        assertFailure(GatewayBudget.create(100, 120_001), GatewayContractError.INVALID_BUDGET)
        val oversizedVector = eventJson(
            sequence = 1,
            operation = "embedding",
            event = "{\"type\":\"embedding\",\"inputIndex\":0,\"vector\":[${List(4097) { "0.0" }.joinToString()}]}",
        )
        assertFailure(GatewayContractCodec.decodeEvent(oversizedVector), GatewayContractError.INVALID_EMBEDDING)
        val tooManyEvents = List(GatewayStreamValidator.MAX_EVENTS + 1) { eventsFromExample("timeout.json")[0] }
        assertFailure(GatewayStreamValidator.validate(tooManyEvents), GatewayContractError.INVALID_EVENT_ORDER)
    }

    @Test
    fun `equivalent unordered skill declarations and arguments encode deterministically`() {
        val first = successful(
            GatewayContractCodec.decodeRequest(
                request(
                    "text",
                    "\"prompt\":\"Hello\",",
                    skills = "[\"hugo.local.lower_contact_status@1.0\",\"hugo.local.connection_status@1.0\"]",
                ),
            ),
        )
        val second = successful(
            GatewayContractCodec.decodeRequest(
                request(
                    "text",
                    "\"prompt\":\"Hello\",",
                    skills = "[\"hugo.local.connection_status@1.0\",\"hugo.local.lower_contact_status@1.0\"]",
                ),
            ),
        )

        assertEquals(GatewayContractCodec.encodeRequest(first), GatewayContractCodec.encodeRequest(second))
        assertNotEquals(first.requestId.toString(), first.requestId.value)
    }

    @Test
    fun `artifact declares all operations terminals and provider neutral boundaries`() {
        val schema = contractFile("v1/gateway-message.schema.json").readText()
        val openApi = contractFile("v1/openapi.yaml").readText()
        GatewayOperation.entries.forEach { assertTrue(schema.contains("\"${it.wireValue}\"")) }
        listOf("completed", "refusal", "failed", "cancelled", "timed_out").forEach {
            assertTrue(schema.contains("\"$it\""))
        }
        assertTrue(openApi.contains("openapi: 3.1.0"))
        val machineArtifacts = (schema + openApi).lowercase()
        listOf("apikey", "providerkey", "bluetoothgatt", "fed9", "macaddress", "rawpacket", "looi").forEach {
            assertFalse("forbidden machine-contract term: $it", machineArtifacts.contains(it))
        }
    }

    @Test
    fun `contract model has no Android transport provider secret or runtime coupling`() {
        val sourceRoot = File("src/main/java/io/redi/robot/gateway/contract").takeIf { it.isDirectory }
            ?: File("app/src/main/java/io/redi/robot/gateway/contract")
        val source = sourceRoot.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        listOf(
            "import android.",
            "import androidx.",
            "BluetoothGatt",
            "apiKey",
            "providerSecret",
            "io.redi.robot.body.looi",
            "io.redi.robot.platform",
            "CapabilityRequest",
            "AuthorizationDecision",
        ).forEach { assertFalse("forbidden coupling: $it", source.contains(it)) }
    }

    private fun request(
        operation: String,
        operationFields: String,
        skills: String = "[]",
        version: String = "1.0",
        requestId: String = "10000000-0000-4000-8000-000000000001",
    ): String = """{"schemaVersion":"$version","messageType":"request","requestId":"$requestId","operation":"$operation","payload":{$operationFields"declaredSkills":$skills,"budget":{"maximumOutputUnits":512,"timeoutMs":30000}}}"""

    private fun eventJson(sequence: Int, operation: String, event: String): String =
        """{"schemaVersion":"1.0","messageType":"event","requestId":"10000000-0000-4000-8000-000000000001","eventId":"20000000-0000-4000-8000-000000000099","sequence":$sequence,"operation":"$operation","event":$event}"""

    private fun eventsFromExample(name: String): List<GatewayEvent> {
        val array = Json.parseToJsonElement(contractFile("v1/examples/$name").readText()) as JsonArray
        return array.map { successful(GatewayContractCodec.decodeEvent(it.toString())) }
    }

    private fun contractFile(relative: String): File {
        val roots = listOf(File("../gateway-contract"), File("gateway-contract"), File("../../gateway-contract"))
        return roots.map { File(it, relative) }.firstOrNull { it.exists() }
            ?: error("Gateway contract artifact not found: $relative")
    }

    private fun <T> successful(result: GatewayContractResult<T>): T {
        assertTrue(result is GatewayContractResult.Success)
        return (result as GatewayContractResult.Success).value
    }

    private fun assertFailure(result: GatewayContractResult<*>, error: GatewayContractError) {
        assertEquals(GatewayContractResult.Failure(error), result)
    }
}
