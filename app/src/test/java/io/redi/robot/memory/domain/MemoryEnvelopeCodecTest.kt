package io.redi.robot.memory.domain

import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextSourceType
import io.redi.robot.context.api.ContextSourceReference
import io.redi.robot.context.api.ContextText
import io.redi.robot.context.api.ContextValue
import io.redi.robot.hugo.identity.HugoIdentityId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEnvelopeCodecTest {
    @Test
    fun `canonical round trip for user explicit validated personal envelope`() {
        val envelope = userExplicitValidated()
        val decoded = MemoryEnvelopeCodec.decode(MemoryEnvelopeCodec.encode(envelope)).success()
        assertEquals(MemoryEnvelopeCodec.encode(envelope), MemoryEnvelopeCodec.encode(decoded))
    }

    @Test
    fun `canonical round trip for model inferred candidate with lineage and conflicts`() {
        val envelope = modelInferredCandidate()
        val decoded = MemoryEnvelopeCodec.decode(MemoryEnvelopeCodec.encode(envelope)).success()
        assertEquals(MemoryEnvelopeCodec.encode(envelope), MemoryEnvelopeCodec.encode(decoded))
    }

    @Test
    fun `canonical round trip for body instance scoped record`() {
        val envelope = bodyScopedRecord()
        val decoded = MemoryEnvelopeCodec.decode(MemoryEnvelopeCodec.encode(envelope)).success()
        assertEquals(MemoryEnvelopeCodec.encode(envelope), MemoryEnvelopeCodec.encode(decoded))
    }

    @Test
    fun `canonical round trip for tombstoned minimal audit envelope`() {
        val tombstoned = MemoryEnvelope.tombstoneOf(modelInferredCandidate(), creator("user_control"), 8_888L).success()
        val decoded = MemoryEnvelopeCodec.decode(MemoryEnvelopeCodec.encode(tombstoned)).success()
        assertEquals(MemoryEnvelopeCodec.encode(tombstoned), MemoryEnvelopeCodec.encode(decoded))
        assertEquals(MemoryDisposition.TOMBSTONED, decoded.disposition)
        assertEquals(null, decoded.content)
    }

    @Test
    fun `stored post validated model inferred record decodes with provenance intact`() {
        val document = """
            {"schemaVersion":"1.0","recordId":"${rid(1)}","ownerIdentity":"${identity()}",
             "layer":"SEMANTIC","type":"fact",
             "scope":{"kind":"global_personal","instanceId":null,"version":null},
             "subjects":["language"],
             "content":{"wireType":"text","value":"validated model answer"},
             "provenance":{"origin":"model_inferred","modelGenerated":true,
                           "sourceType":"memory.test","sourceReference":"memory_test",
                           "creator":"gateway","observedAtEpochSecond":900},
             "confidence":800,"validationState":"VALIDATED","disposition":"ACTIVE",
             "privacy":"private","allowedUse":"gateway_allowed",
             "retention":{"retentionClass":"UNTIL_CORRECTED","expiresAtEpochSecond":null},
             "supersedes":null,"conflictsWith":[],"lineage":null,"tombstone":null,
             "createdAtEpochSecond":1000,"updatedAtEpochSecond":1100}
        """.trimIndent()
        val decoded = MemoryEnvelopeCodec.decode(document).success()
        assertEquals(MemoryValidationState.VALIDATED, decoded.validationState)
        assertTrue(decoded.provenance.modelGenerated)
        assertEquals(MemoryProvenanceOrigin.MODEL_INFERRED, decoded.provenance.origin)
    }

    @Test
    fun `the same validated model inferred fields are rejected at creation`() {
        val result = MemoryEnvelope.create(
            recordId = recordId(1),
            ownerIdentity = identityId(),
            layer = MemoryLayer.SEMANTIC,
            type = token("fact"),
            scope = MemoryScope.GlobalPersonal,
            subjects = listOf(subject("language")),
            content = textValue("validated model answer"),
            provenance = provenance(MemoryProvenanceOrigin.MODEL_INFERRED, true),
            confidence = confidence(800),
            validationState = MemoryValidationState.VALIDATED,
            disposition = MemoryDisposition.ACTIVE,
            privacy = ContextPrivacyClassification.PRIVATE,
            allowedUse = MemoryAllowedUse.GATEWAY_ALLOWED,
            retention = retention(MemoryRetentionClass.UNTIL_CORRECTED, null),
            supersedes = null,
            conflictsWith = emptyList(),
            lineage = null,
            createdAtEpochSecond = 1_000L,
            updatedAtEpochSecond = 1_100L,
        )
        assertEquals(MemoryDomainError.ModelOutputMustStartCandidate, (result as MemoryDomainResult.Failure).error)
    }

    @Test
    fun `unknown missing and malformed documents fail closed`() {
        val valid = MemoryEnvelopeCodec.encode(userExplicitValidated())

        val unknownField = valid.replace(
            "\"subjects\"",
            "\"surpriseField\":1,\"subjects\"",
        )
        assertTrue(MemoryEnvelopeCodec.decode(unknownField) is MemoryDomainResult.Failure)

        val parsedDocument = Json.parseToJsonElement(valid).let { it as JsonObject }
        val missingField = JsonObject(parsedDocument.toMap() - "type").toString()
        assertTrue(MemoryEnvelopeCodec.decode(missingField) is MemoryDomainResult.Failure)

        val wrongVersion = valid.replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"2.0\"")
        assertEquals(
            MemoryDomainError.UnsupportedSchemaVersion,
            (MemoryEnvelopeCodec.decode(wrongVersion) as MemoryDomainResult.Failure).error,
        )

        val malformedId = valid.replace("\"recordId\":\"${rid(1)}\"", "\"recordId\":\"not-a-uuid\"")
        assertTrue(MemoryEnvelopeCodec.decode(malformedId) is MemoryDomainResult.Failure)

        assertTrue(MemoryEnvelopeCodec.decode(valid.dropLast(20)) is MemoryDomainResult.Failure)
        assertTrue(MemoryEnvelopeCodec.decode("not json at all") is MemoryDomainResult.Failure)
        assertTrue(MemoryEnvelopeCodec.decode("[1,2,3]") is MemoryDomainResult.Failure)
    }

    @Test
    fun `schema corpus documents decode exactly as annotated`() {
        corpusDocuments("valid").forEach { (name, text) ->
            val result = MemoryEnvelopeCodec.decode(text)
            assertTrue("corpus valid document $name failed: $result", result is MemoryDomainResult.Success)
        }
        corpusDocuments("invalid").forEach { (name, text) ->
            val result = MemoryEnvelopeCodec.decode(text)
            assertTrue("corpus invalid document $name unexpectedly decoded", result is MemoryDomainResult.Failure)
        }
    }

    @Test
    fun `codec source stays free of storage provider and platform coupling`() {
        val sources = sourceFiles()
        assertTrue(sources.isNotEmpty())
        val text = sources.joinToString(separator = "\n") { it.readText(Charsets.UTF_8) }
        listOf(
            "android.",
            "java.io.File",
            "Room",
            "SQLite",
            "System.currentTimeMillis",
            "SystemClock",
            "io.redi.robot.platform.",
            "io.redi.robot.feature.",
        ).forEach { forbidden ->
            assertFalse("memory codec must not reference $forbidden", text.contains(forbidden))
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun corpusDocuments(kind: String): List<Pair<String, String>> {
        val relative = "app/src/test/resources/memory/domain/corpus/$kind"
        val roots = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }.take(8)
        val root = roots.firstOrNull { File(it, relative).isDirectory }
            ?: error("Unable to locate memory corpus root: $relative")
        return root.listFiles { file -> file.extension == "json" }!!
            .sortedBy { it.name }
            .map { it.name to it.readText(Charsets.UTF_8) }
    }

    private fun userExplicitValidated(): MemoryEnvelope = MemoryEnvelope.create(
        recordId = recordId(1),
        ownerIdentity = identityId(),
        layer = MemoryLayer.PERSONAL,
        type = token("preference"),
        scope = MemoryScope.GlobalPersonal,
        subjects = listOf(subject("language")),
        content = textValue("prefers short answers"),
        provenance = provenance(MemoryProvenanceOrigin.USER_EXPLICIT, modelGenerated = false),
        confidence = confidence(900),
        validationState = MemoryValidationState.VALIDATED,
        disposition = MemoryDisposition.ACTIVE,
        privacy = ContextPrivacyClassification.PRIVATE,
        allowedUse = MemoryAllowedUse.GATEWAY_ALLOWED,
        retention = retention(MemoryRetentionClass.UNTIL_CORRECTED, null),
        supersedes = null,
        conflictsWith = emptyList(),
        lineage = null,
        createdAtEpochSecond = 1_000L,
        updatedAtEpochSecond = null,
    ).success()

    private fun modelInferredCandidate(): MemoryEnvelope = MemoryEnvelope.create(
        recordId = recordId(2),
        ownerIdentity = identityId(),
        layer = MemoryLayer.SEMANTIC,
        type = token("fact"),
        scope = MemoryScope.GlobalPersonal,
        subjects = listOf(subject("language")),
        content = textValue("maybe a fact"),
        provenance = provenance(MemoryProvenanceOrigin.MODEL_INFERRED, modelGenerated = true),
        confidence = confidence(400),
        validationState = MemoryValidationState.CANDIDATE,
        disposition = MemoryDisposition.ACTIVE,
        privacy = ContextPrivacyClassification.PRIVATE,
        allowedUse = MemoryAllowedUse.GATEWAY_ALLOWED,
        retention = retention(MemoryRetentionClass.POLICY_CONTROLLED, null),
        supersedes = null,
        conflictsWith = listOf(recordId(7)),
        lineage = lineageOf(listOf(recordId(9))),
        createdAtEpochSecond = 2_000L,
        updatedAtEpochSecond = null,
    ).success()

    private fun bodyScopedRecord(): MemoryEnvelope = MemoryEnvelope.create(
        recordId = recordId(3),
        ownerIdentity = identityId(),
        layer = MemoryLayer.BODY,
        type = token("calibration_quirk"),
        scope = MemoryScope.BodyInstance(providerInstance(), versionToken("profile_a")),
        subjects = listOf(subject("lower_contact")),
        content = textValue("quirk under cold start"),
        provenance = provenance(MemoryProvenanceOrigin.OBSERVED, modelGenerated = false),
        confidence = confidence(600),
        validationState = MemoryValidationState.CANDIDATE,
        disposition = MemoryDisposition.ACTIVE,
        privacy = ContextPrivacyClassification.PRIVATE,
        allowedUse = MemoryAllowedUse.LOCAL_ONLY,
        retention = retention(MemoryRetentionClass.POLICY_CONTROLLED, null),
        supersedes = null,
        conflictsWith = emptyList(),
        lineage = null,
        createdAtEpochSecond = 3_000L,
        updatedAtEpochSecond = null,
    ).success()

    private fun <T> MemoryDomainResult<T>.success(): T = when (this) {
        is MemoryDomainResult.Success -> value
        is MemoryDomainResult.Failure -> error("expected success, got $error")
    }

    private fun recordId(index: Int): MemoryRecordId = MemoryRecordId.fromUuid(
        UUID.fromString("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"),
    )

    private fun rid(index: Int): String = recordId(index).value

    private fun identity(): String = "ffffffff-0000-4000-8000-000000000001"

    private fun identityId(): HugoIdentityId = HugoIdentityId.fromUuid(UUID.fromString(identity()))

    private fun providerInstance(): ProviderInstanceId = ProviderInstanceId.fromUuid(
        UUID.fromString("eeeeeeee-0000-4000-8000-000000000001"),
    )

    private fun token(raw: String): MemoryTypeToken =
        (MemoryTypeToken.parse(raw) as MemoryDomainResult.Success).value
    private fun subject(raw: String): MemorySubjectToken =
        (MemorySubjectToken.parse(raw) as MemoryDomainResult.Success).value
    private fun creator(raw: String): MemoryCreator =
        (MemoryCreator.parse(raw) as MemoryDomainResult.Success).value
    private fun versionToken(raw: String): MemoryInstanceVersionToken =
        (MemoryInstanceVersionToken.parse(raw) as MemoryDomainResult.Success).value

    private fun provenance(origin: MemoryProvenanceOrigin, modelGenerated: Boolean): MemoryProvenance =
        (MemoryProvenance.create(
            origin,
            modelGenerated,
            (ContextSourceType.parse("memory.test")
                as io.redi.robot.context.api.ContextObservationResult.Success).value,
            (ContextSourceReference.parse("memory_test")
                as io.redi.robot.context.api.ContextObservationResult.Success).value,
            creator("hugo_runtime"),
            null,
        ) as MemoryDomainResult.Success).value

    private fun confidence(permille: Int): io.redi.robot.context.api.ContextConfidence =
        (io.redi.robot.context.api.ContextConfidence.create(permille)
            as io.redi.robot.context.api.ContextObservationResult.Success).value

    private fun textValue(raw: String): ContextValue =
        ContextValue.TextValue(
            (ContextText.create(raw) as io.redi.robot.context.api.ContextObservationResult.Success).value,
        )

    private fun retention(
        retentionClass: MemoryRetentionClass,
        expiry: Long?,
    ): MemoryRetention = (MemoryRetention.create(retentionClass, expiry) as MemoryDomainResult.Success).value

    private fun lineageOf(ids: List<MemoryRecordId>): MemoryLineage =
        (MemoryLineage.create(ids) as MemoryDomainResult.Success).value

    private fun sourceFiles(): List<File> {
        val relative = "app/src/main/java/io/redi/robot/memory/domain"
        val roots = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }.take(8)
        val root = roots.firstOrNull { File(it, relative).isDirectory }
            ?: error("Unable to locate memory domain source root")
        return File(root, relative).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
