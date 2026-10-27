package io.redi.robot.provider.calibration

import io.redi.robot.capability.api.CapabilityAuditId
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.manifest.domain.ManifestVersion
import java.util.Collections

class CalibrationValidationContext private constructor(
    val providerIdentity: ProviderIdentity,
    val providerGeneration: ProviderGeneration,
    val firmwareVersion: ManifestVersion?,
    val hardwareRevision: CalibrationHardwareRevision?,
    explicitlyInvalidatedResults: Set<CalibrationResultId>,
    val now: MonotonicTimestampMs,
) {
    val explicitlyInvalidatedResults: Set<CalibrationResultId> = Collections.unmodifiableSet(
        explicitlyInvalidatedResults.sortedBy { it.value }.toSet(),
    )

    companion object {
        fun create(
            providerIdentity: ProviderIdentity,
            providerGeneration: ProviderGeneration,
            firmwareVersion: ManifestVersion?,
            hardwareRevision: CalibrationHardwareRevision?,
            explicitlyInvalidatedResults: Set<CalibrationResultId>,
            now: MonotonicTimestampMs,
        ): CalibrationDomainResult<CalibrationValidationContext> = if (
            explicitlyInvalidatedResults.size > 128
        ) failure(CalibrationDomainError.TOO_MANY_INVALIDATIONS)
        else CalibrationDomainResult.Success(
            CalibrationValidationContext(
                providerIdentity,
                providerGeneration,
                firmwareVersion,
                hardwareRevision,
                explicitlyInvalidatedResults,
                now,
            ),
        )
    }
}

class CalibrationValidationInput private constructor(
    val profile: CalibrationProfile,
    results: List<CalibrationResult>,
    val context: CalibrationValidationContext,
) {
    val results: List<CalibrationResult> = Collections.unmodifiableList(
        results.sortedWith(compareBy({ it.itemId.value }, { it.recordedAt.value }, { it.id.value })),
    )

    companion object {
        fun create(
            profile: CalibrationProfile,
            results: List<CalibrationResult>,
            context: CalibrationValidationContext,
        ): CalibrationDomainResult<CalibrationValidationInput> = if (results.size > 128) {
            failure(CalibrationDomainError.TOO_MANY_RESULTS)
        } else CalibrationDomainResult.Success(CalibrationValidationInput(profile, results, context))
    }
}

enum class CalibrationStatus {
    REQUIRED,
    NOT_CALIBRATED,
    IN_PROGRESS,
    CANDIDATE,
    VERIFIED,
    EXPIRED,
    INVALIDATED,
    FAILED,
    UNSUPPORTED,
}

enum class CalibrationFinding {
    MATCHED,
    OPTIONAL_NOT_CALIBRATED,
    REQUIRED_RESULT_MISSING,
    RESULT_IN_PROGRESS,
    RESULT_CANDIDATE_ONLY,
    RESULT_REPORTED_FAILED,
    RESULT_UNSUPPORTED,
    WRONG_PROVIDER_INSTANCE,
    WRONG_PROVIDER_DEFINITION,
    WRONG_PROFILE,
    WRONG_CONTRACT_VERSION,
    WRONG_REQUIREMENT_REFERENCE,
    INVALIDATION_CONDITION_MISMATCH,
    UNKNOWN_CALIBRATION_ITEM,
    DUPLICATE_OR_CONFLICTING_RESULTS,
    RESULT_FROM_FUTURE,
    RESULT_EXPIRED,
    PROVIDER_GENERATION_CHANGED,
    FIRMWARE_VERSION_CHANGED,
    HARDWARE_REVISION_CHANGED,
    RESULT_EXPLICITLY_INVALIDATED,
    EVIDENCE_EXPIRED,
    SCHEMA_MISMATCH,
    FIELD_SET_MISMATCH,
    FIELD_TYPE_MISMATCH,
    VALUE_OUT_OF_RANGE,
    UNSUPPORTED_PROCEDURE,
    INSUFFICIENT_VERIFICATION,
    INSUFFICIENT_CONFIDENCE,
}

data class CalibrationAssessmentEvidence(
    val resultId: CalibrationResultId,
    val auditId: CapabilityAuditId,
    val references: List<CalibrationEvidenceReference>,
)

data class CalibrationItemAssessment(
    val itemId: CalibrationItemId,
    val necessity: CalibrationNecessity,
    val status: CalibrationStatus,
    val finding: CalibrationFinding,
    val resultId: CalibrationResultId?,
    val verificationLevel: CalibrationVerificationLevel?,
    val affectedCapabilities: List<CapabilityReference>,
    val evidence: CalibrationAssessmentEvidence?,
)

/** A verified semantic fact projection for later World Model consumers; it contains no procedure. */
data class ValidatedCalibrationFact(
    val profileId: CalibrationProfileId,
    val resultId: CalibrationResultId,
    val providerInstanceId: ProviderInstanceId,
    val providerGeneration: ProviderGeneration,
    val itemId: CalibrationItemId,
    val measurement: CalibrationMeasurement,
    val verificationLevel: CalibrationVerificationLevel,
    val evidence: List<CalibrationEvidence>,
    val validUntil: MonotonicTimestampMs,
    val auditId: CapabilityAuditId,
)

class CalibrationAssessment internal constructor(
    val profileId: CalibrationProfileId,
    val providerInstanceId: ProviderInstanceId,
    val providerGeneration: ProviderGeneration,
    val status: CalibrationStatus,
    items: List<CalibrationItemAssessment>,
    validatedFacts: List<ValidatedCalibrationFact>,
    globalFindings: List<CalibrationFinding>,
    blockedCapabilities: List<CapabilityReference>,
    val evaluatedAt: MonotonicTimestampMs,
) {
    val items: List<CalibrationItemAssessment> = Collections.unmodifiableList(
        items.sortedBy { it.itemId.value },
    )
    val validatedFacts: List<ValidatedCalibrationFact> = Collections.unmodifiableList(
        validatedFacts.sortedBy { it.itemId.value },
    )
    val globalFindings: List<CalibrationFinding> = Collections.unmodifiableList(
        globalFindings.distinct().sortedBy { it.ordinal },
    )
    val blockedCapabilities: List<CapabilityReference> = Collections.unmodifiableList(
        blockedCapabilities.distinct().sortedBy { it.wireValue },
    )
    val allRequiredItemsVerified: Boolean = items
        .filter { it.necessity == CalibrationNecessity.REQUIRED }
        .all { it.status == CalibrationStatus.VERIFIED }
    val grantsSafetyApproval: Boolean get() = false
    val grantsExecutionAuthority: Boolean get() = false
    val grantsPermission: Boolean get() = false
    val provesPhysicalFeasibility: Boolean get() = false
    val persistsDeviceMemory: Boolean get() = false

    override fun toString(): String = "CalibrationAssessment(" +
        "profileId=${profileId.value}, status=$status, generation=${providerGeneration.value}, " +
        "items=${items.map { it.itemId.value to it.status }}, " +
        "facts=${validatedFacts.map { it.itemId.value }}, blocked=${blockedCapabilities.map { it.wireValue }})"
}

object CalibrationValidator {
    fun validate(input: CalibrationValidationInput): CalibrationAssessment {
        val profile = input.profile
        val context = input.context
        val globalFindings = mutableListOf<CalibrationFinding>()

        if (context.providerIdentity.instanceId != profile.providerIdentity.instanceId) {
            globalFindings += CalibrationFinding.WRONG_PROVIDER_INSTANCE
        }
        if (context.providerIdentity.definitionId != profile.providerIdentity.definitionId ||
            context.providerIdentity.family != profile.providerIdentity.family
        ) {
            globalFindings += CalibrationFinding.WRONG_PROVIDER_DEFINITION
        }
        if (profile.createdAt.value > context.now.value) {
            globalFindings += CalibrationFinding.RESULT_FROM_FUTURE
        }

        val knownItems = profile.requirements.map { it.itemId }.toSet()
        if (input.results.any { it.itemId !in knownItems }) {
            globalFindings += CalibrationFinding.UNKNOWN_CALIBRATION_ITEM
        }

        val items = profile.requirements.map { requirement ->
            assessItem(
                profile,
                requirement,
                input.results.filter { it.itemId == requirement.itemId },
                context,
                globalFindings,
            )
        }

        val structuralFailure = globalFindings.any {
            it in setOf(
                CalibrationFinding.UNKNOWN_CALIBRATION_ITEM,
                CalibrationFinding.DUPLICATE_OR_CONFLICTING_RESULTS,
                CalibrationFinding.RESULT_FROM_FUTURE,
            )
        }
        val providerInvalid = globalFindings.any {
            it == CalibrationFinding.WRONG_PROVIDER_INSTANCE ||
                it == CalibrationFinding.WRONG_PROVIDER_DEFINITION
        }
        val status = aggregateStatus(items, structuralFailure, providerInvalid)
        val facts = items.mapNotNull { assessment ->
            if (assessment.status != CalibrationStatus.VERIFIED || assessment.resultId == null) return@mapNotNull null
            val result = input.results.singleOrNull { it.id == assessment.resultId } ?: return@mapNotNull null
            val measurement = result.measurement ?: return@mapNotNull null
            val validUntil = result.validUntil ?: return@mapNotNull null
            ValidatedCalibrationFact(
                profile.id,
                result.id,
                result.providerInstanceId,
                result.compatibility.providerGeneration,
                result.itemId,
                measurement,
                result.verificationLevel,
                result.evidence,
                validUntil,
                result.auditId,
            )
        }
        val blocked = items.filter {
            it.necessity == CalibrationNecessity.REQUIRED && it.status != CalibrationStatus.VERIFIED
        }.flatMap { it.affectedCapabilities }
        return CalibrationAssessment(
            profile.id,
            profile.providerIdentity.instanceId,
            context.providerGeneration,
            status,
            items,
            facts,
            globalFindings,
            blocked,
            context.now,
        )
    }

    private fun assessItem(
        profile: CalibrationProfile,
        requirement: CalibrationRequirement,
        results: List<CalibrationResult>,
        context: CalibrationValidationContext,
        globalFindings: MutableList<CalibrationFinding>,
    ): CalibrationItemAssessment {
        if (results.isEmpty()) {
            return item(
                requirement,
                if (requirement.necessity == CalibrationNecessity.REQUIRED) {
                    CalibrationStatus.REQUIRED
                } else CalibrationStatus.NOT_CALIBRATED,
                if (requirement.necessity == CalibrationNecessity.REQUIRED) {
                    CalibrationFinding.REQUIRED_RESULT_MISSING
                } else CalibrationFinding.OPTIONAL_NOT_CALIBRATED,
            )
        }
        if (results.size != 1) {
            globalFindings += CalibrationFinding.DUPLICATE_OR_CONFLICTING_RESULTS
            return item(
                requirement,
                CalibrationStatus.FAILED,
                CalibrationFinding.DUPLICATE_OR_CONFLICTING_RESULTS,
            )
        }
        val result = results.single()
        val evidence = CalibrationAssessmentEvidence(
            result.id,
            result.auditId,
            result.evidence.map { it.reference }.sortedBy { it.value },
        )

        fun assessed(status: CalibrationStatus, finding: CalibrationFinding) = item(
            requirement,
            status,
            finding,
            result.id,
            result.verificationLevel,
            evidence,
        )

        if (result.providerInstanceId != profile.providerIdentity.instanceId ||
            result.providerInstanceId != context.providerIdentity.instanceId
        ) return assessed(CalibrationStatus.INVALIDATED, CalibrationFinding.WRONG_PROVIDER_INSTANCE)
        if (result.profileId != profile.id) {
            return assessed(CalibrationStatus.INVALIDATED, CalibrationFinding.WRONG_PROFILE)
        }
        if (result.contractVersion != profile.contractVersion) {
            return assessed(CalibrationStatus.INVALIDATED, CalibrationFinding.WRONG_CONTRACT_VERSION)
        }
        if (result.requirementReference != requirement.manifestReference) {
            return assessed(CalibrationStatus.INVALIDATED, CalibrationFinding.WRONG_REQUIREMENT_REFERENCE)
        }
        if (result.invalidationConditions != requirement.invalidationConditions) {
            return assessed(CalibrationStatus.INVALIDATED, CalibrationFinding.INVALIDATION_CONDITION_MISMATCH)
        }
        if (result.recordedAt.value > context.now.value) {
            return assessed(CalibrationStatus.FAILED, CalibrationFinding.RESULT_FROM_FUTURE)
        }
        if (result.procedure != null && result.procedure !in requirement.procedures) {
            return assessed(CalibrationStatus.FAILED, CalibrationFinding.UNSUPPORTED_PROCEDURE)
        }
        when (result.status) {
            CalibrationRecordedStatus.IN_PROGRESS ->
                return assessed(CalibrationStatus.IN_PROGRESS, CalibrationFinding.RESULT_IN_PROGRESS)
            CalibrationRecordedStatus.FAILED ->
                return assessed(CalibrationStatus.FAILED, CalibrationFinding.RESULT_REPORTED_FAILED)
            CalibrationRecordedStatus.UNSUPPORTED ->
                return assessed(CalibrationStatus.UNSUPPORTED, CalibrationFinding.RESULT_UNSUPPORTED)
            CalibrationRecordedStatus.CANDIDATE,
            CalibrationRecordedStatus.VERIFIED,
            -> Unit
        }

        if (result.validUntil == null || context.now.value >= result.validUntil.value) {
            return assessed(CalibrationStatus.EXPIRED, CalibrationFinding.RESULT_EXPIRED)
        }
        invalidationFinding(profile, requirement, result, context)?.let {
            return assessed(CalibrationStatus.INVALIDATED, it)
        }
        measurementFinding(requirement, result.measurement)?.let {
            return assessed(CalibrationStatus.FAILED, it)
        }
        if (result.status == CalibrationRecordedStatus.CANDIDATE) {
            return assessed(CalibrationStatus.CANDIDATE, CalibrationFinding.RESULT_CANDIDATE_ONLY)
        }
        if (result.verificationLevel < requirement.minimumVerification) {
            return assessed(CalibrationStatus.CANDIDATE, CalibrationFinding.INSUFFICIENT_VERIFICATION)
        }
        if ((result.evidence.maxOfOrNull { it.confidence.permille } ?: -1) <
            requirement.minimumConfidence.permille
        ) return assessed(CalibrationStatus.CANDIDATE, CalibrationFinding.INSUFFICIENT_CONFIDENCE)
        return assessed(CalibrationStatus.VERIFIED, CalibrationFinding.MATCHED)
    }

    private fun invalidationFinding(
        profile: CalibrationProfile,
        requirement: CalibrationRequirement,
        result: CalibrationResult,
        context: CalibrationValidationContext,
    ): CalibrationFinding? {
        val conditions = result.invalidationConditions
        if (CalibrationInvalidationCondition.PROVIDER_GENERATION_CHANGED in conditions &&
            result.compatibility.providerGeneration != context.providerGeneration
        ) return CalibrationFinding.PROVIDER_GENERATION_CHANGED
        if (CalibrationInvalidationCondition.FIRMWARE_VERSION_CHANGED in conditions &&
            result.compatibility.firmwareVersion != context.firmwareVersion
        ) return CalibrationFinding.FIRMWARE_VERSION_CHANGED
        if (CalibrationInvalidationCondition.HARDWARE_REVISION_CHANGED in conditions &&
            result.compatibility.hardwareRevision != context.hardwareRevision
        ) return CalibrationFinding.HARDWARE_REVISION_CHANGED
        if (CalibrationInvalidationCondition.PROFILE_VERSION_CHANGED in conditions &&
            result.contractVersion != profile.contractVersion
        ) return CalibrationFinding.WRONG_CONTRACT_VERSION
        if (CalibrationInvalidationCondition.MANIFEST_REQUIREMENT_CHANGED in conditions &&
            result.requirementReference != requirement.manifestReference
        ) return CalibrationFinding.WRONG_REQUIREMENT_REFERENCE
        if (CalibrationInvalidationCondition.EVIDENCE_EXPIRED in conditions &&
            result.evidence.any { !it.isFreshAt(context.now) }
        ) return CalibrationFinding.EVIDENCE_EXPIRED
        if (result.id in context.explicitlyInvalidatedResults) {
            return CalibrationFinding.RESULT_EXPLICITLY_INVALIDATED
        }
        return null
    }

    private fun measurementFinding(
        requirement: CalibrationRequirement,
        measurement: CalibrationMeasurement?,
    ): CalibrationFinding? {
        measurement ?: return CalibrationFinding.FIELD_SET_MISMATCH
        if (measurement.schema != requirement.resultSchema) return CalibrationFinding.SCHEMA_MISMATCH
        val definitions = requirement.fields.associateBy { it.id }
        if (measurement.values.keys != definitions.keys) return CalibrationFinding.FIELD_SET_MISMATCH
        measurement.values.forEach { (id, value) ->
            val definition = definitions.getValue(id)
            if (value.kind != definition.kind) return CalibrationFinding.FIELD_TYPE_MISMATCH
            val numericValue = when (value) {
                is CalibrationValue.Decimal -> value.value
                is CalibrationValue.Integer -> value.value.toDouble()
                is CalibrationValue.BooleanValue,
                is CalibrationValue.Text,
                -> null
            }
            if (numericValue != null && definition.bounds?.contains(numericValue) != true) {
                return CalibrationFinding.VALUE_OUT_OF_RANGE
            }
        }
        return null
    }

    private fun item(
        requirement: CalibrationRequirement,
        status: CalibrationStatus,
        finding: CalibrationFinding,
        resultId: CalibrationResultId? = null,
        verificationLevel: CalibrationVerificationLevel? = null,
        evidence: CalibrationAssessmentEvidence? = null,
    ) = CalibrationItemAssessment(
        requirement.itemId,
        requirement.necessity,
        status,
        finding,
        resultId,
        verificationLevel,
        requirement.affectedCapabilities,
        evidence,
    )

    private fun aggregateStatus(
        items: List<CalibrationItemAssessment>,
        structuralFailure: Boolean,
        providerInvalid: Boolean,
    ): CalibrationStatus {
        if (structuralFailure || items.any { it.status == CalibrationStatus.FAILED }) {
            return CalibrationStatus.FAILED
        }
        if (providerInvalid) return CalibrationStatus.INVALIDATED
        val required = items.filter { it.necessity == CalibrationNecessity.REQUIRED }
        return when {
            required.any { it.status == CalibrationStatus.UNSUPPORTED } -> CalibrationStatus.UNSUPPORTED
            required.any { it.status == CalibrationStatus.INVALIDATED } -> CalibrationStatus.INVALIDATED
            required.any { it.status == CalibrationStatus.EXPIRED } -> CalibrationStatus.EXPIRED
            required.any { it.status == CalibrationStatus.IN_PROGRESS } -> CalibrationStatus.IN_PROGRESS
            required.any { it.status == CalibrationStatus.REQUIRED } -> CalibrationStatus.REQUIRED
            required.any { it.status == CalibrationStatus.CANDIDATE } -> CalibrationStatus.CANDIDATE
            required.all { it.status == CalibrationStatus.VERIFIED } -> CalibrationStatus.VERIFIED
            else -> CalibrationStatus.NOT_CALIBRATED
        }
    }
}

private fun <T> failure(error: CalibrationDomainError): CalibrationDomainResult<T> =
    CalibrationDomainResult.Failure(error)
