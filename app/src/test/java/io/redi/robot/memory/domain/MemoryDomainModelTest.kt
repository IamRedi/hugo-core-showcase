package io.redi.robot.memory.domain

import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextSourceReference
import io.redi.robot.context.api.ContextSourceType
import io.redi.robot.context.api.ContextText
import io.redi.robot.context.api.ContextValue
import io.redi.robot.hugo.identity.HugoIdentityId
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDomainModelTest {
    @Test
    fun `valid user explicit validated personal envelope is accepted`() {
        val envelope = MemoryEnvelope.create(
            recordId = recordId(1),
            ownerIdentity = identity(),
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

        assertEquals(MemoryValidationState.VALIDATED, envelope.validationState)
        assertFalse(envelope.grantsAuthority)
        assertFalse(envelope.isFact)
        assertFalse(envelope.authorizesAnyUse)
    }

    @Test
    fun `model inferred candidate is accepted and model provenance is definitionally generated`() {
        val provenance = provenance(MemoryProvenanceOrigin.MODEL_INFERRED, modelGenerated = true)
        val envelope = validEnvelope(
            layer = MemoryLayer.SEMANTIC,
            provenance = provenance,
            validationState = MemoryValidationState.CANDIDATE,
            lineage = lineageOf(listOf(recordId(9))),
        ).success()
        assertTrue(provenance.modelGenerated)
        assertEquals(MemoryValidationState.CANDIDATE, envelope.validationState)
    }

    @Test
    fun `model generated provenance must start as candidate at creation`() {
        listOf(MemoryValidationState.VALIDATED, MemoryValidationState.DISPUTED).forEach { state ->
            val result = validEnvelope(
                provenance = provenance(MemoryProvenanceOrigin.MODEL_INFERRED, modelGenerated = true),
                validationState = state,
            )
            assertTrue(result is MemoryDomainResult.Failure)
            assertEquals(MemoryDomainError.ModelOutputMustStartCandidate, (result as MemoryDomainResult.Failure).error)
        }
    }

    @Test
    fun `model inferred origin definitionally requires the model generated flag`() {
        val result = MemoryProvenance.create(
            MemoryProvenanceOrigin.MODEL_INFERRED,
            modelGenerated = false,
            sourceType(),
            sourceReference(),
            creator("gateway"),
            null,
        )
        assertTrue(result is MemoryDomainResult.Failure)
        assertEquals(MemoryDomainError.ModelProvenanceMismatch, (result as MemoryDomainResult.Failure).error)
    }

    @Test
    fun `conversation summary origin is candidate only when model generated`() {
        val summary = validEnvelope(
            layer = MemoryLayer.EPISODIC,
            provenance = provenance(MemoryProvenanceOrigin.CONVERSATION_SUMMARY, modelGenerated = false),
            validationState = MemoryValidationState.VALIDATED,
        )
        assertTrue(summary is MemoryDomainResult.Success)
    }

    @Test
    fun `sensitive and restricted records can never gain gateway or provider allowed use`() {
        listOf(ContextPrivacyClassification.SENSITIVE, ContextPrivacyClassification.RESTRICTED).forEach { privacy ->
            listOf(MemoryAllowedUse.GATEWAY_ALLOWED, MemoryAllowedUse.PROVIDER_ALLOWED).forEach { use ->
                val result = validEnvelope(privacy = privacy, allowedUse = use)
                assertEquals(
                    MemoryDomainError.SensitiveAllowedUseRestricted,
                    (result as MemoryDomainResult.Failure).error,
                )
            }
            listOf(MemoryAllowedUse.LOCAL_ONLY, MemoryAllowedUse.NEVER_MODEL).forEach { use ->
                assertTrue(validEnvelope(privacy = privacy, allowedUse = use) is MemoryDomainResult.Success)
            }
        }
    }

    @Test
    fun `layer scope matrix enforces adr 016 instance binding`() {
        val bodyScope = MemoryScope.BodyInstance(providerInstance(), versionToken("v1"))
        val deviceScope = MemoryScope.DeviceInstance(providerInstance(), null)
        val providerScope = MemoryScope.ProviderInstance(providerInstance(), versionToken("v2"))

        assertTrue(validEnvelope(layer = MemoryLayer.BODY, scope = bodyScope) is MemoryDomainResult.Success)
        assertTrue(validEnvelope(layer = MemoryLayer.DEVICE, scope = deviceScope) is MemoryDomainResult.Success)
        assertTrue(
            validEnvelope(layer = MemoryLayer.STRATEGY, scope = providerScope) is MemoryDomainResult.Success,
        )
        assertTrue(validEnvelope(layer = MemoryLayer.WORKING) is MemoryDomainResult.Success)
        assertTrue(validEnvelope(layer = MemoryLayer.EPISODIC) is MemoryDomainResult.Success)

        listOf(
            MemoryLayer.PERSONAL to bodyScope,
            MemoryLayer.SEMANTIC to deviceScope,
            MemoryLayer.WORKING to providerScope,
            MemoryLayer.BODY to MemoryScope.GlobalPersonal,
            MemoryLayer.DEVICE to MemoryScope.GlobalPersonal,
            MemoryLayer.PERSONAL to providerScope,
        ).forEach { (layer, scope) ->
            val result = validEnvelope(layer = layer, scope = scope)
            assertEquals(
                "expected LayerScopeMismatch for $layer/$scope",
                MemoryDomainError.LayerScopeMismatch,
                (result as MemoryDomainResult.Failure).error,
            )
        }
    }

    @Test
    fun `retention fixed interval requires expiry and other classes forbid it`() {
        assertTrue(
            validEnvelope(retention = retention(MemoryRetentionClass.FIXED_INTERVAL, 5_000L))
                is MemoryDomainResult.Success,
        )
        assertTrue(
            MemoryRetention.create(MemoryRetentionClass.FIXED_INTERVAL, null) is MemoryDomainResult.Failure,
        )
        assertTrue(
            MemoryRetention.create(MemoryRetentionClass.SESSION_SCOPED, 5_000L) is MemoryDomainResult.Failure,
        )
        assertTrue(
            MemoryRetention.create(MemoryRetentionClass.FIXED_INTERVAL, 5_000L) is MemoryDomainResult.Success,
        )
        val expired = validEnvelope(
            retention = retention(MemoryRetentionClass.FIXED_INTERVAL, 999L),
        )
        assertEquals(MemoryDomainError.InvalidRetentionWindow, (expired as MemoryDomainResult.Failure).error)
    }

    @Test
    fun `subjects are bounded distinct and non empty`() {
        assertTrue(validEnvelope(subjects = listOf(subject("one"))) is MemoryDomainResult.Success)
        assertEquals(
            MemoryDomainError.InvalidSubjects,
            (validEnvelope(subjects = emptyList()) as MemoryDomainResult.Failure).error,
        )
        assertEquals(
            MemoryDomainError.InvalidSubjects,
            (validEnvelope(subjects = (1..5).map { subject("subject$it") }) as MemoryDomainResult.Failure).error,
        )
        assertEquals(
            MemoryDomainError.InvalidSubjects,
            (validEnvelope(subjects = listOf(subject("same"), subject("same"))) as MemoryDomainResult.Failure).error,
        )
    }

    @Test
    fun `links reject self references and bounded conflicts`() {
        assertEquals(
            MemoryDomainError.SelfReferenceForbidden,
            (validEnvelope(supersedes = recordId(1)) as MemoryDomainResult.Failure).error,
        )
        assertEquals(
            MemoryDomainError.SelfReferenceForbidden,
            (validEnvelope(conflictsWith = listOf(recordId(1))) as MemoryDomainResult.Failure).error,
        )
        assertEquals(
            MemoryDomainError.InvalidConflictLinks,
            (validEnvelope(conflictsWith = (2..10).map { recordId(it) }) as MemoryDomainResult.Failure).error,
        )
        assertEquals(
            MemoryDomainError.InvalidConflictLinks,
            (validEnvelope(conflictsWith = listOf(recordId(2), recordId(2))) as MemoryDomainResult.Failure).error,
        )
        assertEquals(
            MemoryDomainError.InvalidConflictLinks,
            (validEnvelope(supersedes = recordId(2), conflictsWith = listOf(recordId(2)))
                as MemoryDomainResult.Failure).error,
        )
        assertTrue(validEnvelope(supersedes = recordId(2), conflictsWith = listOf(recordId(3)))
            is MemoryDomainResult.Success)
    }

    @Test
    fun `lineage is bounded distinct and self free`() {
        assertTrue(validEnvelope(lineage = lineageOf(listOf(recordId(5)))) is MemoryDomainResult.Success)
        assertEquals(
            MemoryDomainError.SelfReferenceForbidden,
            (validEnvelope(lineage = lineageOf(listOf(recordId(1)))) as MemoryDomainResult.Failure).error,
        )
        assertTrue(
            MemoryLineage.create((2..10).map { recordId(it) }) is MemoryDomainResult.Failure,
        )
        assertTrue(
            MemoryLineage.create(listOf(recordId(5), recordId(5))) is MemoryDomainResult.Failure,
        )
    }

    @Test
    fun `updated at can never precede creation`() {
        assertEquals(
            MemoryDomainError.InvalidUpdatedAt,
            (validEnvelope(updatedAtEpochSecond = 999L) as MemoryDomainResult.Failure).error,
        )
        assertTrue(validEnvelope(updatedAtEpochSecond = 1_000L) is MemoryDomainResult.Success)
    }

    @Test
    fun `tombstoned state cannot be constructed directly`() {
        val result = validEnvelope(disposition = MemoryDisposition.TOMBSTONED)
        assertEquals(MemoryDomainError.TombstoneRequiresFactory, (result as MemoryDomainResult.Failure).error)
    }

    @Test
    fun `tombstone retains minimal audit and linkage while dropping personal payload`() {
        val record = validEnvelope(
            layer = MemoryLayer.SEMANTIC,
            provenance = provenance(MemoryProvenanceOrigin.MODEL_INFERRED, modelGenerated = true),
            lineage = lineageOf(listOf(recordId(9))),
            conflictsWith = listOf(recordId(7)),
        ).success()
        val tombstoned = MemoryEnvelope.tombstoneOf(record, creator("user_control"), 9_999L).success()

        assertEquals(MemoryDisposition.TOMBSTONED, tombstoned.disposition)
        assertEquals(null, tombstoned.content)
        assertEquals(null, tombstoned.confidence)
        assertEquals(null, tombstoned.retention)
        assertNotNull(tombstoned.tombstone)
        assertEquals(record.provenance, tombstoned.provenance)
        assertEquals(record.subjects, tombstoned.subjects)
        assertEquals(record.lineage, tombstoned.lineage)
        assertEquals(record.conflictsWith, tombstoned.conflictsWith)
        assertEquals(record.privacy, tombstoned.privacy)

        assertEquals(
            MemoryDomainError.AlreadyTombstoned,
            (MemoryEnvelope.tombstoneOf(tombstoned, creator("user_control"), 10_000L)
                as MemoryDomainResult.Failure).error,
        )
    }

    @Test
    fun `diagnostics never carry content or owner identity`() {
        val envelope = validEnvelope(
            provenance = provenance(MemoryProvenanceOrigin.USER_EXPLICIT, modelGenerated = false),
        ).success()
        val rendered = envelope.toString() + envelope.scope.toString()
        assertFalse(rendered.contains("prefers"))
        assertFalse(rendered.contains(identity().value))
        assertTrue(envelope.toString().contains("content=[redacted]"))
    }

    @Test
    fun `malformed identifiers and tokens are rejected typed`() {
        assertTrue(MemoryRecordId.parse("not-a-uuid") is MemoryDomainResult.Failure)
        assertTrue(
            MemoryRecordId.parse("0000000A-0000-0000-0000-00000000000B".uppercase()) is MemoryDomainResult.Failure,
        )
        assertTrue(MemoryRecordId.parse("") is MemoryDomainResult.Failure)
        assertTrue(MemoryTypeToken.parse("Bad-Token") is MemoryDomainResult.Failure)
        assertTrue(MemoryTypeToken.parse("ab") is MemoryDomainResult.Failure)
        assertTrue(MemorySubjectToken.parse("x") is MemoryDomainResult.Failure)
        assertTrue(MemoryCreator.parse("Has Space") is MemoryDomainResult.Failure)
    }

    private fun assertNotNull(value: Any?) {
        org.junit.Assert.assertNotNull(value)
    }

    // ---------------------------------------------------------------- helpers

    private fun validEnvelope(
        layer: MemoryLayer = MemoryLayer.SEMANTIC,
        scope: MemoryScope = MemoryScope.GlobalPersonal,
        subjects: List<MemorySubjectToken> = listOf(subject("language")),
        provenance: MemoryProvenance = provenance(MemoryProvenanceOrigin.USER_EXPLICIT, modelGenerated = false),
        validationState: MemoryValidationState = MemoryValidationState.CANDIDATE,
        disposition: MemoryDisposition = MemoryDisposition.ACTIVE,
        privacy: ContextPrivacyClassification = ContextPrivacyClassification.PRIVATE,
        allowedUse: MemoryAllowedUse = MemoryAllowedUse.GATEWAY_ALLOWED,
        retention: MemoryRetention = retention(MemoryRetentionClass.UNTIL_CORRECTED, null),
        supersedes: MemoryRecordId? = null,
        conflictsWith: List<MemoryRecordId> = emptyList(),
        lineage: MemoryLineage? = null,
        updatedAtEpochSecond: Long? = null,
    ): MemoryDomainResult<MemoryEnvelope> = MemoryEnvelope.create(
        recordId = recordId(1),
        ownerIdentity = identity(),
        layer = layer,
        type = token("fact"),
        scope = scope,
        subjects = subjects,
        content = textValue("prefers short answers"),
        provenance = provenance,
        confidence = confidence(700),
        validationState = validationState,
        disposition = disposition,
        privacy = privacy,
        allowedUse = allowedUse,
        retention = retention,
        supersedes = supersedes,
        conflictsWith = conflictsWith,
        lineage = lineage,
        createdAtEpochSecond = 1_000L,
        updatedAtEpochSecond = updatedAtEpochSecond,
    )

    @Test
    fun `memory domain source contains no storage clock platform or retrieval coupling`() {
        val sources = sourceFiles()
        assertTrue(sources.isNotEmpty())
        val text = sources.joinToString(separator = System.lineSeparator()) { it.readText(Charsets.UTF_8) }
        listOf(
            "android.",
            "java.io.File",
            "SQLite",
            "Room",
            "System.currentTimeMillis",
            "SystemClock",
            "java.time.Clock",
            "kotlinx.coroutines",
            "io.redi.robot.platform.",
            "io.redi.robot.feature.",
            "io.redi.robot.body.looi.",
            "MemoryContextPacket",
            "GatewayClient",
        ).forEach { forbidden ->
            assertFalse("memory/domain must not reference $forbidden", text.contains(forbidden))
        }
    }

    private fun sourceFiles(): List<File> {
        val relative = "app/src/main/java/io/redi/robot/memory/domain"
        val roots = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }.take(8)
        val root = roots.firstOrNull { File(it, relative).isDirectory }
            ?: error("Unable to locate memory domain source root")
        return File(root, relative).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun <T> MemoryDomainResult<T>.success(): T = when (this) {
        is MemoryDomainResult.Success -> value
        is MemoryDomainResult.Failure -> error("expected success, got $error")
    }

    private fun recordId(index: Int): MemoryRecordId = MemoryRecordId.fromUuid(
        UUID.fromString("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"),
    )

    private fun identity(): HugoIdentityId = HugoIdentityId.fromUuid(
        UUID.fromString("ffffffff-0000-4000-8000-000000000001"),
    )

    private fun providerInstance(): ProviderInstanceId = ProviderInstanceId.fromUuid(
        UUID.fromString("eeeeeeee-0000-4000-8000-000000000001"),
    )

    private fun token(raw: String): MemoryTypeToken = (MemoryTypeToken.parse(raw) as MemoryDomainResult.Success).value
    private fun versionToken(raw: String): MemoryInstanceVersionToken =
        (MemoryInstanceVersionToken.parse(raw) as MemoryDomainResult.Success).value
    private fun subject(raw: String): MemorySubjectToken =
        (MemorySubjectToken.parse(raw) as MemoryDomainResult.Success).value
    private fun creator(raw: String): MemoryCreator =
        (MemoryCreator.parse(raw) as MemoryDomainResult.Success).value

    private fun sourceType(): ContextSourceType =
        (ContextSourceType.parse("memory.test") as io.redi.robot.context.api.ContextObservationResult.Success).value

    private fun sourceReference(): ContextSourceReference =
        (ContextSourceReference.parse("memory_test")
            as io.redi.robot.context.api.ContextObservationResult.Success).value

    private fun provenance(origin: MemoryProvenanceOrigin, modelGenerated: Boolean): MemoryProvenance =
        (MemoryProvenance.create(origin, modelGenerated, sourceType(), sourceReference(), creator("hugo_runtime"), null)
            as MemoryDomainResult.Success).value

    private fun confidence(permille: Int): io.redi.robot.context.api.ContextConfidence =
        (io.redi.robot.context.api.ContextConfidence.create(permille)
            as io.redi.robot.context.api.ContextObservationResult.Success).value

    private fun textValue(raw: String): ContextValue =
        ContextValue.TextValue((ContextText.create(raw) as io.redi.robot.context.api.ContextObservationResult.Success).value)

    private fun retention(
        retentionClass: MemoryRetentionClass,
        expiry: Long?,
    ): MemoryRetention = (MemoryRetention.create(retentionClass, expiry) as MemoryDomainResult.Success).value

    private fun lineageOf(ids: List<MemoryRecordId>): MemoryLineage =
        (MemoryLineage.create(ids) as MemoryDomainResult.Success).value
}
