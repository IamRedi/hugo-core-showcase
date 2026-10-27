package io.redi.robot.provider.calibration

import io.redi.robot.adapter.compatibility.CompatibilityEvidenceReference
import io.redi.robot.adapter.compatibility.CompatibilityInputResult
import io.redi.robot.capability.api.CapabilityApprovalRequirement
import io.redi.robot.capability.api.CapabilityAuditId
import io.redi.robot.capability.api.CapabilityAvailabilitySemantics
import io.redi.robot.capability.api.CapabilityCancellationSemantics
import io.redi.robot.capability.api.CapabilityDescription
import io.redi.robot.capability.api.CapabilityDescriptor
import io.redi.robot.capability.api.CapabilityDescriptorResult
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.CapabilityEvidenceClass
import io.redi.robot.capability.api.CapabilityEffect
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityLabel
import io.redi.robot.capability.api.CapabilityPermissionIntent
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.CapabilityRiskClass
import io.redi.robot.capability.api.CapabilitySafetyClass
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.negotiation.CapabilityNegotiationEvidence
import io.redi.robot.capability.negotiation.CapabilityNegotiationEvidenceSource
import io.redi.robot.capability.negotiation.CapabilityNegotiationId
import io.redi.robot.capability.negotiation.CapabilityNegotiationModelResult
import io.redi.robot.capability.negotiation.CapabilityNegotiationReason
import io.redi.robot.capability.negotiation.CapabilityNegotiationReport
import io.redi.robot.capability.negotiation.CapabilityNegotiationStatus
import io.redi.robot.capability.negotiation.NegotiatedCapabilityRecord
import io.redi.robot.capability.provider.CapabilityProviderPortResult
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.capability.provider.ProviderRuntimeAvailability
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.manifest.domain.CommonManifestResult
import io.redi.robot.manifest.domain.ManifestContractReference
import io.redi.robot.manifest.domain.ManifestVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationValidatorTest {
    @Test
    fun `valid required and optional calibration requirements remain distinct`() {
        val fixture = Fixture()
        val optional = fixture.requirement(
            item = "geometry.optional_offset",
            manifest = "calibration/optional-offset@1.0",
            schema = "calibration/optional-offset-result@1.0",
            necessity = CalibrationNecessity.OPTIONAL,
        )
        val profile = fixture.profile(requirements = listOf(optional, fixture.required))
        val assessment = fixture.validate(profile = profile, results = emptyList())

        assertEquals(CalibrationStatus.REQUIRED, assessment.status)
        assertEquals(CalibrationStatus.REQUIRED, assessment.items.single { it.necessity == CalibrationNecessity.REQUIRED }.status)
        assertEquals(CalibrationStatus.NOT_CALIBRATED, assessment.items.single { it.necessity == CalibrationNecessity.OPTIONAL }.status)
        assertFalse(assessment.allRequiredItemsVerified)
        assertEquals(listOf(fixture.capability), assessment.blockedCapabilities)
    }

    @Test
    fun `valid measured result becomes a verified instance-bound fact`() {
        val fixture = Fixture()
        val result = fixture.result()
        val assessment = fixture.validate(results = listOf(result))

        assertEquals(CalibrationStatus.VERIFIED, assessment.status)
        assertTrue(assessment.allRequiredItemsVerified)
        assertTrue(assessment.blockedCapabilities.isEmpty())
        val fact = assessment.validatedFacts.single()
        assertEquals(fixture.providerA.instanceId, fact.providerInstanceId)
        assertEquals(fixture.required.itemId, fact.itemId)
        assertEquals(CalibrationVerificationLevel.MEASURED, fact.verificationLevel)
        assertEquals(result.id, fact.resultId)
    }

    @Test
    fun `out-of-range and wrong typed measurements fail validation`() {
        val fixture = Fixture()
        val outOfRange = fixture.result(measurement = fixture.measurement(11.0))
        val assessment = fixture.validate(results = listOf(outOfRange))
        assertEquals(CalibrationStatus.FAILED, assessment.status)
        assertEquals(CalibrationFinding.VALUE_OUT_OF_RANGE, assessment.items.single().finding)

        val wrongType = fixture.result(
            measurement = fixture.measurement(CalibrationValue.Text.create("not numeric").success()),
        )
        assertEquals(
            CalibrationFinding.FIELD_TYPE_MISMATCH,
            fixture.validate(results = listOf(wrongType)).items.single().finding,
        )
    }

    @Test
    fun `calibration cannot cross provider instances even for the same definition`() {
        val fixture = Fixture()
        val providerBResult = fixture.result(provider = fixture.providerB.instanceId)
        val assessment = fixture.validate(results = listOf(providerBResult))

        assertEquals(CalibrationStatus.INVALIDATED, assessment.status)
        assertEquals(CalibrationFinding.WRONG_PROVIDER_INSTANCE, assessment.items.single().finding)
        assertTrue(assessment.validatedFacts.isEmpty())

        val providerBContext = fixture.context(identity = fixture.providerB)
        val contextMismatch = fixture.validate(results = listOf(fixture.result()), context = providerBContext)
        assertEquals(CalibrationStatus.INVALIDATED, contextMismatch.status)
        assertTrue(CalibrationFinding.WRONG_PROVIDER_INSTANCE in contextMismatch.globalFindings)
    }

    @Test
    fun `wrong profile and contract version invalidate rather than migrate truth`() {
        val fixture = Fixture()
        val wrongProfile = fixture.result(
            profileId = fixture.profileId("99999999-9999-4999-8999-999999999999"),
        )
        assertEquals(
            CalibrationFinding.WRONG_PROFILE,
            fixture.validate(results = listOf(wrongProfile)).items.single().finding,
        )

        val wrongVersion = fixture.result(contractVersion = fixture.contractVersion("1.1"))
        assertEquals(
            CalibrationFinding.WRONG_CONTRACT_VERSION,
            fixture.validate(results = listOf(wrongVersion)).items.single().finding,
        )
    }

    @Test
    fun `expired result and expired evidence are represented separately`() {
        val fixture = Fixture()
        val expiredResult = fixture.result(validUntil = 20)
        val resultAssessment = fixture.validate(results = listOf(expiredResult))
        assertEquals(CalibrationStatus.EXPIRED, resultAssessment.status)
        assertEquals(CalibrationFinding.RESULT_EXPIRED, resultAssessment.items.single().finding)

        val staleEvidence = fixture.evidence(
            kind = CalibrationEvidenceKind.MEASURED_CALIBRATION,
            expiresAt = 20,
        )
        val expiredEvidence = fixture.result(evidence = listOf(staleEvidence))
        val evidenceAssessment = fixture.validate(results = listOf(expiredEvidence))
        assertEquals(CalibrationStatus.INVALIDATED, evidenceAssessment.status)
        assertEquals(CalibrationFinding.EVIDENCE_EXPIRED, evidenceAssessment.items.single().finding)
    }

    @Test
    fun `generation firmware and hardware changes invalidate only through declared conditions`() {
        val fixture = Fixture()
        val result = fixture.result()
        val cases = listOf(
            fixture.context(generation = fixture.generation(8)) to CalibrationFinding.PROVIDER_GENERATION_CHANGED,
            fixture.context(firmware = fixture.version("2.0.0")) to CalibrationFinding.FIRMWARE_VERSION_CHANGED,
            fixture.context(hardware = fixture.hardware("revision-b")) to CalibrationFinding.HARDWARE_REVISION_CHANGED,
        )
        cases.forEach { (context, finding) ->
            val assessment = fixture.validate(results = listOf(result), context = context)
            assertEquals(CalibrationStatus.INVALIDATED, assessment.status)
            assertEquals(finding, assessment.items.single().finding)
        }

        val agnosticConditions = fixture.defaultInvalidation -
            CalibrationInvalidationCondition.PROVIDER_GENERATION_CHANGED
        val agnosticRequirement = fixture.requirement(invalidationConditions = agnosticConditions)
        val agnosticProfile = fixture.profile(requirements = listOf(agnosticRequirement))
        val generationAgnostic = fixture.result(
            invalidationConditions = agnosticConditions,
        )
        assertEquals(
            CalibrationStatus.VERIFIED,
            fixture.validate(
                profile = agnosticProfile,
                results = listOf(generationAgnostic),
                context = fixture.context(generation = fixture.generation(8)),
            ).status,
        )
    }

    @Test
    fun `explicit invalidation is fail closed and auditable`() {
        val fixture = Fixture()
        val result = fixture.result()
        val context = fixture.context(invalidated = setOf(result.id))
        val assessment = fixture.validate(results = listOf(result), context = context)
        assertEquals(CalibrationStatus.INVALIDATED, assessment.status)
        assertEquals(CalibrationFinding.RESULT_EXPLICITLY_INVALIDATED, assessment.items.single().finding)
    }

    @Test
    fun `result cannot weaken invalidation conditions declared by its requirement`() {
        val fixture = Fixture()
        val weakened = fixture.result(
            invalidationConditions = fixture.defaultInvalidation -
                CalibrationInvalidationCondition.FIRMWARE_VERSION_CHANGED,
        )
        val assessment = fixture.validate(results = listOf(weakened))
        assertEquals(CalibrationStatus.INVALIDATED, assessment.status)
        assertEquals(CalibrationFinding.INVALIDATION_CONDITION_MISMATCH, assessment.items.single().finding)
    }

    @Test
    fun `inferred candidate can never silently become verified calibration`() {
        val fixture = Fixture()
        val inferred = fixture.evidence(CalibrationEvidenceKind.INFERRED_ESTIMATE)
        val candidate = fixture.result(
            status = CalibrationRecordedStatus.CANDIDATE,
            evidence = listOf(inferred),
            verification = CalibrationVerificationLevel.CANDIDATE,
        )
        val assessment = fixture.validate(results = listOf(candidate))
        assertEquals(CalibrationStatus.CANDIDATE, assessment.status)
        assertFalse(assessment.allRequiredItemsVerified)
        assertTrue(assessment.validatedFacts.isEmpty())

        val invalid = CalibrationResult.create(
            candidate.id,
            candidate.profileId,
            candidate.contractVersion,
            candidate.providerInstanceId,
            candidate.itemId,
            candidate.requirementReference,
            CalibrationRecordedStatus.VERIFIED,
            candidate.measurement,
            candidate.procedure,
            listOf(inferred),
            CalibrationVerificationLevel.MEASURED,
            candidate.compatibility,
            candidate.invalidationConditions,
            candidate.recordedAt,
            candidate.validUntil,
            candidate.auditId,
        )
        assertEquals(
            CalibrationDomainResult.Failure(CalibrationDomainError.EVIDENCE_VERIFICATION_CONFLICT),
            invalid,
        )
    }

    @Test
    fun `hardware verified evidence remains distinguishable from measured evidence`() {
        val fixture = Fixture()
        val hardwareRequirement = fixture.requirement(
            minimumVerification = CalibrationVerificationLevel.HARDWARE_VERIFIED,
        )
        val profile = fixture.profile(requirements = listOf(hardwareRequirement))
        val measured = fixture.result()
        val insufficient = fixture.validate(profile, listOf(measured))
        assertEquals(CalibrationStatus.CANDIDATE, insufficient.status)
        assertEquals(CalibrationFinding.INSUFFICIENT_VERIFICATION, insufficient.items.single().finding)

        val hardware = fixture.evidence(CalibrationEvidenceKind.HARDWARE_VERIFIED_CALIBRATION)
        val verified = fixture.result(
            evidence = listOf(hardware),
            verification = CalibrationVerificationLevel.HARDWARE_VERIFIED,
        )
        assertEquals(CalibrationStatus.VERIFIED, fixture.validate(profile, listOf(verified)).status)
    }

    @Test
    fun `low-confidence evidence remains candidate even when its verification kind is measured`() {
        val fixture = Fixture()
        val lowConfidence = fixture.evidence(
            CalibrationEvidenceKind.MEASURED_CALIBRATION,
            confidence = 700,
        )
        val assessment = fixture.validate(results = listOf(fixture.result(evidence = listOf(lowConfidence))))
        assertEquals(CalibrationStatus.CANDIDATE, assessment.status)
        assertEquals(CalibrationFinding.INSUFFICIENT_CONFIDENCE, assessment.items.single().finding)
    }

    @Test
    fun `duplicate or conflicting results are rejected instead of selected`() {
        val fixture = Fixture()
        val first = fixture.result()
        val second = fixture.result(
            id = fixture.resultId("88888888-8888-4888-8888-888888888888"),
            measurement = fixture.measurement(2.0),
        )
        val assessment = fixture.validate(results = listOf(second, first))
        assertEquals(CalibrationStatus.FAILED, assessment.status)
        assertEquals(CalibrationFinding.DUPLICATE_OR_CONFLICTING_RESULTS, assessment.items.single().finding)
        assertTrue(assessment.validatedFacts.isEmpty())
    }

    @Test
    fun `status observations represent in-progress failed and unsupported procedures without values`() {
        val fixture = Fixture()
        val cases = listOf(
            CalibrationRecordedStatus.IN_PROGRESS to CalibrationStatus.IN_PROGRESS,
            CalibrationRecordedStatus.FAILED to CalibrationStatus.FAILED,
            CalibrationRecordedStatus.UNSUPPORTED to CalibrationStatus.UNSUPPORTED,
        )
        cases.forEach { (recorded, expected) ->
            val result = fixture.result(
                status = recorded,
                measurement = null,
                evidence = emptyList(),
                verification = CalibrationVerificationLevel.UNVERIFIED,
                validUntil = null,
                invalidationConditions = fixture.defaultInvalidation,
            )
            assertEquals(expected, fixture.validate(results = listOf(result)).status)
        }
    }

    @Test
    fun `calibration request binds exact profile provider generation item and adapter-owned procedure`() {
        val fixture = Fixture()
        val request = CalibrationRequest.create(
            fixture.requestId,
            fixture.profile,
            fixture.providerA,
            fixture.providerGeneration,
            fixture.required.itemId,
            fixture.required.procedures.single(),
            fixture.timestamp(20),
            fixture.timestamp(40),
            fixture.auditId,
        ).success()
        assertEquals(fixture.profile.id, request.profileId)
        assertEquals(fixture.providerA.instanceId, request.providerInstanceId)
        assertEquals(fixture.providerGeneration, request.providerGeneration)
        assertFalse(request.grantsExecutionAuthority)

        assertEquals(
            CalibrationDomainResult.Failure(CalibrationDomainError.PROVIDER_INSTANCE_MISMATCH),
            CalibrationRequest.create(
                fixture.requestId,
                fixture.profile,
                fixture.providerB,
                fixture.providerGeneration,
                fixture.required.itemId,
                fixture.required.procedures.single(),
                fixture.timestamp(20),
                fixture.timestamp(40),
                fixture.auditId,
            ),
        )
    }

    @Test
    fun `profile result and evidence collections are immutable and canonically ordered`() {
        val fixture = Fixture()
        val evidence = mutableListOf(
            fixture.evidence(CalibrationEvidenceKind.MEASURED_CALIBRATION, "measurement.second"),
            fixture.evidence(CalibrationEvidenceKind.USER_ENTERED, "measurement.first"),
        )
        val invalidation = fixture.defaultInvalidation.toMutableSet()
        val result = fixture.result(evidence = evidence, invalidationConditions = invalidation)
        evidence.clear()
        invalidation.clear()
        assertEquals(2, result.evidence.size)
        assertTrue(result.invalidationConditions.isNotEmpty())
        assertThrows(UnsupportedOperationException::class.java) {
            (result.evidence as MutableList).clear()
        }

        val requirements = mutableListOf(
            fixture.requirement(
                item = "geometry.z_optional",
                manifest = "calibration/z-optional@1.0",
                schema = "calibration/z-optional-result@1.0",
                necessity = CalibrationNecessity.OPTIONAL,
            ),
            fixture.required,
        )
        val profile = fixture.profile(requirements = requirements)
        requirements.clear()
        assertEquals(listOf("geometry.primary", "geometry.z_optional"), profile.requirements.map { it.itemId.value })
    }

    @Test
    fun `equivalent inputs produce equivalent deterministic assessments`() {
        val fixture = Fixture()
        val optional = fixture.requirement(
            item = "geometry.secondary",
            manifest = "calibration/secondary@1.0",
            schema = "calibration/secondary-result@1.0",
            necessity = CalibrationNecessity.OPTIONAL,
        )
        val firstProfile = fixture.profile(requirements = listOf(optional, fixture.required))
        val secondProfile = fixture.profile(requirements = listOf(fixture.required, optional))
        val first = fixture.validate(firstProfile, listOf(fixture.result()))
        val second = fixture.validate(secondProfile, listOf(fixture.result()))
        assertEquals(first.toString(), second.toString())
        assertEquals(first.items, second.items)
        assertEquals(first.globalFindings, second.globalFindings)
        assertEquals(first.blockedCapabilities, second.blockedCapabilities)
    }

    @Test
    fun `validated facts expose no procedure and calibration grants no authority safety feasibility or memory`() {
        val fixture = Fixture()
        val result = fixture.result()
        val assessment = fixture.validate(results = listOf(result))
        val fact = assessment.validatedFacts.single()
        assertFalse(assessment.grantsSafetyApproval)
        assertFalse(assessment.grantsExecutionAuthority)
        assertFalse(assessment.grantsPermission)
        assertFalse(assessment.provesPhysicalFeasibility)
        assertFalse(assessment.persistsDeviceMemory)
        assertFalse(result.grantsSafetyApproval)
        assertFalse(result.grantsExecutionAuthority)
        assertFalse(result.persistsDeviceMemory)
        assertFalse(fact.javaClass.declaredFields.any { it.name.contains("procedure", ignoreCase = true) })
    }

    @Test
    fun `H029 readiness reports required calibration without granting authority or safety`() {
        val fixture = Fixture()
        val negotiation = fixture.negotiation()
        val missing = fixture.validate(results = emptyList())
        val blocked = CalibrationNegotiationReadinessEvaluator.evaluate(missing, negotiation, fixture.timestamp(20))
        assertEquals(CalibrationNegotiationReadinessStatus.CALIBRATION_REQUIRED, blocked.status)
        assertEquals(listOf(fixture.capability), blocked.blockedCapabilities)
        assertEquals(listOf(fixture.required.itemId), blocked.unmetItems)
        assertFalse(blocked.grantsAuthorization)
        assertFalse(blocked.grantsSafetyApproval)
        assertFalse(blocked.grantsExecutionAuthority)

        val verified = fixture.validate(results = listOf(fixture.result()))
        val ready = CalibrationNegotiationReadinessEvaluator.evaluate(verified, negotiation, fixture.timestamp(20))
        assertEquals(CalibrationNegotiationReadinessStatus.READY, ready.status)
        assertTrue(ready.blockedCapabilities.isEmpty())
    }

    @Test
    fun `stale H029 evidence cannot produce calibration readiness`() {
        val fixture = Fixture()
        val assessment = fixture.validate(results = listOf(fixture.result()))
        val stale = CalibrationNegotiationReadinessEvaluator.evaluate(
            assessment,
            fixture.negotiation(validUntil = 20),
            fixture.timestamp(20),
        )
        assertEquals(CalibrationNegotiationReadinessStatus.STALE_NEGOTIATION, stale.status)
    }

    @Test
    fun `generic calibration boundary contains no vendor transport Android LOOI or runtime execution type`() {
        val exposed = listOf(
            CalibrationProfile::class.java,
            CalibrationRequirement::class.java,
            CalibrationResult::class.java,
            CalibrationRequest::class.java,
            CalibrationAssessment::class.java,
            CalibrationValidator::class.java,
        ).flatMap { type ->
            type.declaredMethods.flatMap { it.parameterTypes.toList() + it.returnType } + type
        }.map { it.name.lowercase() }
        assertFalse(exposed.any { "android" in it || "bluetooth" in it || "gatt" in it })
        assertFalse(exposed.any { "looi" in it || "vendor" in it || "transport" in it })
        assertFalse(exposed.any { "permission" in it || "authorization" in it || "execute" in it })
    }

    private class Fixture {
        val contractVersion = contractVersion("1.0")
        val profileId = profileId("11111111-1111-4111-8111-111111111111")
        val requestId = requestId("22222222-2222-4222-8222-222222222222")
        val resultId = resultId("33333333-3333-4333-8333-333333333333")
        val providerA = provider("44444444-4444-4444-8444-444444444444", "Body A")
        val providerB = provider("55555555-5555-4555-8555-555555555555", "Body B")
        val providerGeneration = generation(7)
        val firmwareVersion = version("1.0.0")
        val hardwareRevision = hardware("revision-a")
        val auditId = auditId("66666666-6666-4666-8666-666666666666")
        val capability = capability("mobility.move@1.0")
        val descriptor = descriptor(capability)
        val defaultInvalidation = setOf(
            CalibrationInvalidationCondition.PROVIDER_GENERATION_CHANGED,
            CalibrationInvalidationCondition.FIRMWARE_VERSION_CHANGED,
            CalibrationInvalidationCondition.HARDWARE_REVISION_CHANGED,
            CalibrationInvalidationCondition.PROFILE_VERSION_CHANGED,
            CalibrationInvalidationCondition.MANIFEST_REQUIREMENT_CHANGED,
            CalibrationInvalidationCondition.EVIDENCE_EXPIRED,
            CalibrationInvalidationCondition.EXPLICITLY_INVALIDATED,
        )
        val required = requirement()
        val profile = profile()

        fun requirement(
            item: String = "geometry.primary",
            manifest: String = "calibration/primary@1.0",
            schema: String = "calibration/primary-result@1.0",
            necessity: CalibrationNecessity = CalibrationNecessity.REQUIRED,
            minimumVerification: CalibrationVerificationLevel = CalibrationVerificationLevel.MEASURED,
            invalidationConditions: Set<CalibrationInvalidationCondition> = defaultInvalidation,
        ): CalibrationRequirement = CalibrationRequirement.create(
            itemId(item),
            manifestReference(manifest),
            necessity,
            manifestReference(schema),
            listOf(
                CalibrationFieldDefinition.create(
                    fieldId("value"),
                    CalibrationValueKind.DECIMAL,
                    unit("unit"),
                    CalibrationNumericBounds.create(-10.0, 10.0).success(),
                ).success(),
            ),
            listOf(
                CalibrationProcedureReference(
                    manifestReference("calibration/manual-procedure@1.0"),
                    CalibrationProcedureMode.MANUAL,
                ),
            ),
            minimumVerification,
            CalibrationConfidence.create(800).success(),
            invalidationConditions,
            if (necessity == CalibrationNecessity.REQUIRED) listOf(capability) else emptyList(),
        ).success()

        fun profile(
            id: CalibrationProfileId = profileId,
            provider: ProviderIdentity = providerA,
            version: CalibrationContractVersion = contractVersion,
            requirements: List<CalibrationRequirement> = listOf(required),
        ) = CalibrationProfile.create(
            id,
            version,
            provider,
            requirements,
            timestamp(5),
            auditId,
        ).success()

        fun result(
            id: CalibrationResultId = resultId,
            profileId: CalibrationProfileId = this.profileId,
            contractVersion: CalibrationContractVersion = this.contractVersion,
            provider: ProviderInstanceId = providerA.instanceId,
            item: CalibrationItemId = required.itemId,
            requirementReference: ManifestContractReference = required.manifestReference,
            status: CalibrationRecordedStatus = CalibrationRecordedStatus.VERIFIED,
            measurement: CalibrationMeasurement? = measurement(1.0),
            procedure: CalibrationProcedureReference? = required.procedures.single(),
            evidence: List<CalibrationEvidence> = listOf(evidence(CalibrationEvidenceKind.MEASURED_CALIBRATION)),
            verification: CalibrationVerificationLevel = CalibrationVerificationLevel.MEASURED,
            compatibility: CalibrationCompatibilityBinding = compatibility(),
            invalidationConditions: Set<CalibrationInvalidationCondition> = defaultInvalidation,
            recordedAt: Long = 10,
            validUntil: Long? = 60,
        ) = CalibrationResult.create(
            id,
            profileId,
            contractVersion,
            provider,
            item,
            requirementReference,
            status,
            measurement,
            procedure,
            evidence,
            verification,
            compatibility,
            invalidationConditions,
            timestamp(recordedAt),
            validUntil?.let(::timestamp),
            auditId,
        ).success()

        fun measurement(value: Double): CalibrationMeasurement = measurement(
            CalibrationValue.Decimal.create(value).success(),
        )

        fun measurement(value: CalibrationValue) = CalibrationMeasurement.create(
            required.resultSchema,
            mapOf(fieldId("value") to value),
        ).success()

        fun evidence(
            kind: CalibrationEvidenceKind,
            reference: String = "measurement.primary",
            expiresAt: Long? = 60,
            confidence: Int = 900,
        ) = CalibrationEvidence.create(
            evidenceReference(reference),
            kind,
            CalibrationConfidence.create(confidence).success(),
            timestamp(10),
            expiresAt?.let(::timestamp),
        ).success()

        fun compatibility(
            generation: ProviderGeneration = providerGeneration,
            firmware: ManifestVersion = firmwareVersion,
            hardware: CalibrationHardwareRevision = hardwareRevision,
        ) = CalibrationCompatibilityBinding(generation, firmware, hardware)

        fun context(
            identity: ProviderIdentity = providerA,
            generation: ProviderGeneration = providerGeneration,
            firmware: ManifestVersion = firmwareVersion,
            hardware: CalibrationHardwareRevision = hardwareRevision,
            invalidated: Set<CalibrationResultId> = emptySet(),
        ) = CalibrationValidationContext.create(
            identity,
            generation,
            firmware,
            hardware,
            invalidated,
            timestamp(20),
        ).success()

        fun validate(
            profile: CalibrationProfile = this.profile,
            results: List<CalibrationResult>,
            context: CalibrationValidationContext = context(),
        ) = CalibrationValidator.validate(
            CalibrationValidationInput.create(profile, results, context).success(),
        )

        fun negotiation(validUntil: Long = 60): CapabilityNegotiationReport {
            val negotiationId = negotiationSuccess(
                CapabilityNegotiationId.parse("77777777-7777-4777-8777-777777777777"),
            )
            val record = NegotiatedCapabilityRecord(
                capability,
                descriptor,
                providerA.instanceId,
                providerGeneration,
                negotiationId,
                emptyList(),
                descriptor.inputContract,
                descriptor.outputContract,
                ProviderRuntimeAvailability.AVAILABLE,
                descriptor.limits,
                timestamp(10),
                timestamp(validUntil),
                listOf(
                    CapabilityNegotiationEvidence(
                        CapabilityNegotiationEvidenceSource.PROVIDER_NEGOTIATION,
                        compatibilitySuccess(CompatibilityEvidenceReference.parse("runtime.negotiation.evidence")),
                    ),
                ),
            )
            return CapabilityNegotiationReport(
                negotiationId,
                providerA.instanceId,
                providerGeneration,
                CapabilityNegotiationStatus.NEGOTIATED,
                CapabilityNegotiationReason.ALL_DECLARED_CAPABILITIES_NEGOTIATED,
                manifestCandidateId = null,
                negotiatedCapabilities = listOf(record),
                checks = emptyList(),
            )
        }

        fun profileId(raw: String) = CalibrationProfileId.parse(raw).success()
        fun requestId(raw: String) = CalibrationRequestId.parse(raw).success()
        fun resultId(raw: String) = CalibrationResultId.parse(raw).success()
        fun contractVersion(raw: String) = CalibrationContractVersion.parse(raw).success()
        fun generation(raw: Long) = providerPortSuccess(ProviderGeneration.create(raw))
        fun version(raw: String) = manifestSuccess(ManifestVersion.parse(raw))
        fun hardware(raw: String) = CalibrationHardwareRevision.parse(raw).success()
        fun timestamp(raw: Long) = envelopeSuccess(MonotonicTimestampMs.create(raw))

        private fun itemId(raw: String) = CalibrationItemId.parse(raw).success()
        private fun fieldId(raw: String) = CalibrationFieldId.parse(raw).success()
        private fun unit(raw: String) = CalibrationUnitReference.parse(raw).success()
        private fun evidenceReference(raw: String) = CalibrationEvidenceReference.parse(raw).success()
        private fun manifestReference(raw: String) = manifestSuccess(ManifestContractReference.parse(raw))
        private fun capability(raw: String) = capabilitySuccess(CapabilityReference.parse(raw))
        private fun descriptor(reference: CapabilityReference): CapabilityDescriptor = descriptorSuccess(
            CapabilityDescriptor.create(
                reference,
                descriptorSuccess(CapabilityLabel.parse("Move")),
                descriptorSuccess(CapabilityDescription.parse("Semantic calibration readiness fixture.")),
                inputContract = null,
                outputContract = null,
                effect = CapabilityEffect.ACT,
                risk = CapabilityRiskClass.ELEVATED,
                safety = CapabilitySafetyClass.PHYSICAL_ACTION,
                permissionIntent = CapabilityPermissionIntent.ACT,
                approvalRequirement = CapabilityApprovalRequirement.WITHIN_GRANT,
                cancellation = CapabilityCancellationSemantics.SUPPORTED,
                availability = CapabilityAvailabilitySemantics.WHILE_REGISTERED,
                evidence = CapabilityEvidenceClass.AUTOMATED_TESTED,
            ),
        )
        private fun auditId(raw: String) = envelopeSuccess(CapabilityAuditId.parse(raw))

        private fun provider(instance: String, alias: String) = ProviderIdentity(
            providerIdentitySuccess(ProviderDefinitionId.parse("test/body")),
            providerIdentitySuccess(ProviderInstanceId.parse(instance)),
            ProviderFamily.BODY_ADAPTER,
            providerIdentitySuccess(RedactedProviderAlias.parse(alias)),
        )
    }

    private companion object {
        fun <T> CalibrationDomainResult<T>.success(): T = when (this) {
            is CalibrationDomainResult.Success -> value
            is CalibrationDomainResult.Failure -> throw AssertionError(error)
        }

        fun <T> envelopeSuccess(result: CapabilityEnvelopeResult<T>): T = when (result) {
            is CapabilityEnvelopeResult.Success -> result.value
            is CapabilityEnvelopeResult.Failure -> throw AssertionError(result.error)
        }

        fun <T> capabilitySuccess(result: CapabilityIdentityResult<T>): T = when (result) {
            is CapabilityIdentityResult.Success -> result.value
            is CapabilityIdentityResult.Failure -> throw AssertionError(result.error)
        }

        fun <T> providerIdentitySuccess(result: ProviderIdentityResult<T>): T = when (result) {
            is ProviderIdentityResult.Success -> result.value
            is ProviderIdentityResult.Failure -> throw AssertionError(result.error)
        }

        fun <T> providerPortSuccess(result: CapabilityProviderPortResult<T>): T = when (result) {
            is CapabilityProviderPortResult.Success -> result.value
            is CapabilityProviderPortResult.Failure -> throw AssertionError(result.error)
        }

        fun <T> manifestSuccess(result: CommonManifestResult<T>): T = when (result) {
            is CommonManifestResult.Success -> result.value
            is CommonManifestResult.Failure -> throw AssertionError(result.error)
        }

        fun <T> descriptorSuccess(result: CapabilityDescriptorResult<T>): T = when (result) {
            is CapabilityDescriptorResult.Success -> result.value
            is CapabilityDescriptorResult.Failure -> throw AssertionError(result.error)
        }

        fun <T> negotiationSuccess(result: CapabilityNegotiationModelResult<T>): T = when (result) {
            is CapabilityNegotiationModelResult.Success -> result.value
            is CapabilityNegotiationModelResult.Failure -> throw AssertionError(result.error)
        }

        fun <T> compatibilitySuccess(result: CompatibilityInputResult<T>): T = when (result) {
            is CompatibilityInputResult.Success -> result.value
            is CompatibilityInputResult.Failure -> throw AssertionError(result.error)
        }
    }
}
