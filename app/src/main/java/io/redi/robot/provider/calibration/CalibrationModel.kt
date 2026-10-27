package io.redi.robot.provider.calibration

import io.redi.robot.capability.api.CapabilityAuditId
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderGeneration
import io.redi.robot.capability.provider.ProviderIdentity
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.manifest.domain.ManifestContractReference
import io.redi.robot.manifest.domain.ManifestVersion
import java.util.Collections
import java.util.UUID

@JvmInline
value class CalibrationProfileId private constructor(val value: String) {
    companion object {
        fun fromUuid(uuid: UUID): CalibrationProfileId = CalibrationProfileId(uuid.toString())
        fun parse(raw: String): CalibrationDomainResult<CalibrationProfileId> = parseUuid(raw, ::CalibrationProfileId)
    }
}

@JvmInline
value class CalibrationRequestId private constructor(val value: String) {
    companion object {
        fun fromUuid(uuid: UUID): CalibrationRequestId = CalibrationRequestId(uuid.toString())
        fun parse(raw: String): CalibrationDomainResult<CalibrationRequestId> = parseUuid(raw, ::CalibrationRequestId)
    }
}

@JvmInline
value class CalibrationResultId private constructor(val value: String) {
    companion object {
        fun fromUuid(uuid: UUID): CalibrationResultId = CalibrationResultId(uuid.toString())
        fun parse(raw: String): CalibrationDomainResult<CalibrationResultId> = parseUuid(raw, ::CalibrationResultId)
    }
}

data class CalibrationContractVersion private constructor(
    val major: Int,
    val minor: Int,
) {
    val wireValue: String = "$major.$minor"
    val isSupported: Boolean get() = major == supportedMajor

    companion object {
        const val supportedMajor: Int = 1
        private val pattern = Regex("^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$")

        fun parse(raw: String): CalibrationDomainResult<CalibrationContractVersion> {
            if (!pattern.matches(raw)) return failure(CalibrationDomainError.INVALID_CONTRACT_VERSION)
            val (major, minor) = raw.split('.').map(String::toInt)
            if (major <= 0) return failure(CalibrationDomainError.INVALID_CONTRACT_VERSION)
            return CalibrationDomainResult.Success(CalibrationContractVersion(major, minor))
        }
    }
}

@JvmInline
value class CalibrationItemId private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[.][a-z][a-z0-9_]*)+$")
        fun parse(raw: String): CalibrationDomainResult<CalibrationItemId> = if (pattern.matches(raw)) {
            CalibrationDomainResult.Success(CalibrationItemId(raw))
        } else failure(CalibrationDomainError.INVALID_ITEM_ID)
    }
}

@JvmInline
value class CalibrationFieldId private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$")
        fun parse(raw: String): CalibrationDomainResult<CalibrationFieldId> = if (pattern.matches(raw)) {
            CalibrationDomainResult.Success(CalibrationFieldId(raw))
        } else failure(CalibrationDomainError.INVALID_FIELD_ID)
    }
}

@JvmInline
value class CalibrationUnitReference private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[A-Za-z%][A-Za-z0-9_/%.-]{0,31}$")
        fun parse(raw: String): CalibrationDomainResult<CalibrationUnitReference> = if (pattern.matches(raw)) {
            CalibrationDomainResult.Success(CalibrationUnitReference(raw))
        } else failure(CalibrationDomainError.INVALID_UNIT)
    }
}

@JvmInline
value class CalibrationHardwareRevision private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,63}$")
        fun parse(raw: String): CalibrationDomainResult<CalibrationHardwareRevision> = if (
            pattern.matches(raw) && !raw.contains("//") && !raw.contains("..")
        ) CalibrationDomainResult.Success(CalibrationHardwareRevision(raw))
        else failure(CalibrationDomainError.INVALID_HARDWARE_REVISION)
    }
}

@JvmInline
value class CalibrationEvidenceReference private constructor(val value: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._/-][a-z0-9]+){1,7}$")
        fun parse(raw: String): CalibrationDomainResult<CalibrationEvidenceReference> = if (
            raw.length in 3..120 && pattern.matches(raw)
        ) CalibrationDomainResult.Success(CalibrationEvidenceReference(raw))
        else failure(CalibrationDomainError.INVALID_EVIDENCE_REFERENCE)
    }
}

enum class CalibrationNecessity {
    REQUIRED,
    OPTIONAL,
}

enum class CalibrationValueKind {
    DECIMAL,
    INTEGER,
    BOOLEAN,
    TEXT,
}

sealed interface CalibrationValue {
    val kind: CalibrationValueKind

    data class Decimal private constructor(val value: Double) : CalibrationValue {
        override val kind: CalibrationValueKind = CalibrationValueKind.DECIMAL

        companion object {
            fun create(value: Double): CalibrationDomainResult<Decimal> = if (value.isFinite()) {
                CalibrationDomainResult.Success(Decimal(value))
            } else failure(CalibrationDomainError.NON_FINITE_VALUE)
        }
    }

    data class Integer(val value: Long) : CalibrationValue {
        override val kind: CalibrationValueKind = CalibrationValueKind.INTEGER
    }

    data class BooleanValue(val value: Boolean) : CalibrationValue {
        override val kind: CalibrationValueKind = CalibrationValueKind.BOOLEAN
    }

    data class Text private constructor(val value: String) : CalibrationValue {
        override val kind: CalibrationValueKind = CalibrationValueKind.TEXT

        companion object {
            fun create(value: String): CalibrationDomainResult<Text> = if (
                value.isNotBlank() && value.length <= 160 && value.none(Char::isISOControl)
            ) CalibrationDomainResult.Success(Text(value))
            else failure(CalibrationDomainError.INVALID_TEXT_VALUE)
        }
    }
}

data class CalibrationNumericBounds private constructor(
    val minimumInclusive: Double?,
    val maximumInclusive: Double?,
) {
    fun contains(value: Double): Boolean = value.isFinite() &&
        (minimumInclusive == null || value >= minimumInclusive) &&
        (maximumInclusive == null || value <= maximumInclusive)

    companion object {
        fun create(
            minimumInclusive: Double?,
            maximumInclusive: Double?,
        ): CalibrationDomainResult<CalibrationNumericBounds> = when {
            minimumInclusive == null && maximumInclusive == null ->
                failure(CalibrationDomainError.MISSING_BOUNDS)
            minimumInclusive?.isFinite() == false || maximumInclusive?.isFinite() == false ->
                failure(CalibrationDomainError.NON_FINITE_BOUNDS)
            minimumInclusive != null && maximumInclusive != null && minimumInclusive > maximumInclusive ->
                failure(CalibrationDomainError.REVERSED_BOUNDS)
            else -> CalibrationDomainResult.Success(
                CalibrationNumericBounds(minimumInclusive, maximumInclusive),
            )
        }
    }
}

data class CalibrationFieldDefinition private constructor(
    val id: CalibrationFieldId,
    val kind: CalibrationValueKind,
    val unit: CalibrationUnitReference?,
    val bounds: CalibrationNumericBounds?,
) {
    companion object {
        fun create(
            id: CalibrationFieldId,
            kind: CalibrationValueKind,
            unit: CalibrationUnitReference?,
            bounds: CalibrationNumericBounds?,
        ): CalibrationDomainResult<CalibrationFieldDefinition> {
            val numeric = kind == CalibrationValueKind.DECIMAL || kind == CalibrationValueKind.INTEGER
            return if ((!numeric && (unit != null || bounds != null)) || (numeric && bounds == null)) {
                failure(CalibrationDomainError.FIELD_DEFINITION_CONFLICT)
            } else {
                CalibrationDomainResult.Success(CalibrationFieldDefinition(id, kind, unit, bounds))
            }
        }
    }
}

enum class CalibrationProcedureMode {
    MANUAL,
    SEMI_AUTOMATIC,
    AUTOMATIC,
}

data class CalibrationProcedureReference(
    val contract: ManifestContractReference,
    val mode: CalibrationProcedureMode,
)

enum class CalibrationVerificationLevel {
    UNVERIFIED,
    CANDIDATE,
    MEASURED,
    HARDWARE_VERIFIED,
}

enum class CalibrationEvidenceKind(val maximumVerification: CalibrationVerificationLevel) {
    VENDOR_DEFAULT_SPECIFICATION(CalibrationVerificationLevel.CANDIDATE),
    USER_ENTERED(CalibrationVerificationLevel.CANDIDATE),
    MEASURED_CALIBRATION(CalibrationVerificationLevel.MEASURED),
    HARDWARE_VERIFIED_CALIBRATION(CalibrationVerificationLevel.HARDWARE_VERIFIED),
    INFERRED_ESTIMATE(CalibrationVerificationLevel.CANDIDATE),
}

@JvmInline
value class CalibrationConfidence private constructor(val permille: Int) {
    companion object {
        fun create(permille: Int): CalibrationDomainResult<CalibrationConfidence> = if (permille in 0..1000) {
            CalibrationDomainResult.Success(CalibrationConfidence(permille))
        } else failure(CalibrationDomainError.INVALID_CONFIDENCE)
    }
}

class CalibrationEvidence private constructor(
    val reference: CalibrationEvidenceReference,
    val kind: CalibrationEvidenceKind,
    val confidence: CalibrationConfidence,
    val observedAt: MonotonicTimestampMs,
    val expiresAt: MonotonicTimestampMs?,
) {
    fun isFreshAt(now: MonotonicTimestampMs): Boolean =
        now.value >= observedAt.value && (expiresAt == null || now.value < expiresAt.value)

    companion object {
        fun create(
            reference: CalibrationEvidenceReference,
            kind: CalibrationEvidenceKind,
            confidence: CalibrationConfidence,
            observedAt: MonotonicTimestampMs,
            expiresAt: MonotonicTimestampMs?,
        ): CalibrationDomainResult<CalibrationEvidence> = if (
            expiresAt != null && expiresAt.value <= observedAt.value
        ) failure(CalibrationDomainError.INVALID_EVIDENCE_FRESHNESS)
        else CalibrationDomainResult.Success(
            CalibrationEvidence(reference, kind, confidence, observedAt, expiresAt),
        )
    }
}

enum class CalibrationInvalidationCondition {
    PROVIDER_GENERATION_CHANGED,
    FIRMWARE_VERSION_CHANGED,
    HARDWARE_REVISION_CHANGED,
    PROFILE_VERSION_CHANGED,
    MANIFEST_REQUIREMENT_CHANGED,
    EVIDENCE_EXPIRED,
    EXPLICITLY_INVALIDATED,
}

class CalibrationRequirement private constructor(
    val itemId: CalibrationItemId,
    val manifestReference: ManifestContractReference,
    val necessity: CalibrationNecessity,
    val resultSchema: ManifestContractReference,
    fields: List<CalibrationFieldDefinition>,
    procedures: List<CalibrationProcedureReference>,
    val minimumVerification: CalibrationVerificationLevel,
    val minimumConfidence: CalibrationConfidence,
    invalidationConditions: Set<CalibrationInvalidationCondition>,
    affectedCapabilities: List<CapabilityReference>,
) {
    val fields: List<CalibrationFieldDefinition> = Collections.unmodifiableList(
        fields.sortedBy { it.id.value },
    )
    val procedures: List<CalibrationProcedureReference> = Collections.unmodifiableList(
        procedures.sortedWith(compareBy({ it.contract.wireValue }, { it.mode.ordinal })),
    )
    val invalidationConditions: Set<CalibrationInvalidationCondition> = Collections.unmodifiableSet(
        invalidationConditions.toSortedSet(compareBy { it.ordinal }),
    )
    val affectedCapabilities: List<CapabilityReference> = Collections.unmodifiableList(
        affectedCapabilities.sortedBy { it.wireValue },
    )

    companion object {
        @Suppress("LongParameterList")
        fun create(
            itemId: CalibrationItemId,
            manifestReference: ManifestContractReference,
            necessity: CalibrationNecessity,
            resultSchema: ManifestContractReference,
            fields: List<CalibrationFieldDefinition>,
            procedures: List<CalibrationProcedureReference>,
            minimumVerification: CalibrationVerificationLevel,
            minimumConfidence: CalibrationConfidence,
            invalidationConditions: Set<CalibrationInvalidationCondition>,
            affectedCapabilities: List<CapabilityReference>,
        ): CalibrationDomainResult<CalibrationRequirement> = when {
            fields.isEmpty() || fields.size > 32 -> failure(CalibrationDomainError.INVALID_FIELD_COUNT)
            fields.map { it.id }.distinct().size != fields.size -> failure(CalibrationDomainError.DUPLICATE_FIELD)
            procedures.isEmpty() || procedures.size > 16 -> failure(CalibrationDomainError.INVALID_PROCEDURE_COUNT)
            procedures.distinct().size != procedures.size -> failure(CalibrationDomainError.DUPLICATE_PROCEDURE)
            minimumVerification < CalibrationVerificationLevel.MEASURED ->
                failure(CalibrationDomainError.INVALID_MINIMUM_VERIFICATION)
            invalidationConditions.isEmpty() -> failure(CalibrationDomainError.MISSING_INVALIDATION_CONDITION)
            affectedCapabilities.size > 64 || affectedCapabilities.distinct().size != affectedCapabilities.size ->
                failure(CalibrationDomainError.INVALID_CAPABILITY_BINDINGS)
            else -> CalibrationDomainResult.Success(
                CalibrationRequirement(
                    itemId,
                    manifestReference,
                    necessity,
                    resultSchema,
                    fields,
                    procedures,
                    minimumVerification,
                    minimumConfidence,
                    invalidationConditions,
                    affectedCapabilities,
                ),
            )
        }
    }
}

class CalibrationProfile private constructor(
    val id: CalibrationProfileId,
    val contractVersion: CalibrationContractVersion,
    val providerIdentity: ProviderIdentity,
    requirements: List<CalibrationRequirement>,
    val createdAt: MonotonicTimestampMs,
    val auditId: CapabilityAuditId,
) {
    val requirements: List<CalibrationRequirement> = Collections.unmodifiableList(
        requirements.sortedBy { it.itemId.value },
    )

    fun requirement(itemId: CalibrationItemId): CalibrationRequirement? =
        requirements.firstOrNull { it.itemId == itemId }

    companion object {
        fun create(
            id: CalibrationProfileId,
            contractVersion: CalibrationContractVersion,
            providerIdentity: ProviderIdentity,
            requirements: List<CalibrationRequirement>,
            createdAt: MonotonicTimestampMs,
            auditId: CapabilityAuditId,
        ): CalibrationDomainResult<CalibrationProfile> = when {
            !contractVersion.isSupported -> failure(CalibrationDomainError.UNSUPPORTED_CONTRACT_VERSION)
            requirements.isEmpty() || requirements.size > 64 -> failure(CalibrationDomainError.INVALID_REQUIREMENT_COUNT)
            requirements.map { it.itemId }.distinct().size != requirements.size ->
                failure(CalibrationDomainError.DUPLICATE_REQUIREMENT)
            requirements.map { it.manifestReference }.distinct().size != requirements.size ->
                failure(CalibrationDomainError.DUPLICATE_MANIFEST_REQUIREMENT)
            else -> CalibrationDomainResult.Success(
                CalibrationProfile(id, contractVersion, providerIdentity, requirements, createdAt, auditId),
            )
        }
    }
}

class CalibrationMeasurement private constructor(
    val schema: ManifestContractReference,
    values: Map<CalibrationFieldId, CalibrationValue>,
) {
    val values: Map<CalibrationFieldId, CalibrationValue> = Collections.unmodifiableMap(
        values.entries.sortedBy { it.key.value }.associate { it.key to it.value },
    )

    companion object {
        fun create(
            schema: ManifestContractReference,
            values: Map<CalibrationFieldId, CalibrationValue>,
        ): CalibrationDomainResult<CalibrationMeasurement> = if (values.isEmpty() || values.size > 32) {
            failure(CalibrationDomainError.INVALID_MEASUREMENT_FIELD_COUNT)
        } else CalibrationDomainResult.Success(CalibrationMeasurement(schema, values))
    }
}

data class CalibrationCompatibilityBinding(
    val providerGeneration: ProviderGeneration,
    val firmwareVersion: ManifestVersion?,
    val hardwareRevision: CalibrationHardwareRevision?,
)

enum class CalibrationRecordedStatus {
    IN_PROGRESS,
    CANDIDATE,
    VERIFIED,
    FAILED,
    UNSUPPORTED,
}

class CalibrationResult private constructor(
    val id: CalibrationResultId,
    val profileId: CalibrationProfileId,
    val contractVersion: CalibrationContractVersion,
    val providerInstanceId: ProviderInstanceId,
    val itemId: CalibrationItemId,
    val requirementReference: ManifestContractReference,
    val status: CalibrationRecordedStatus,
    val measurement: CalibrationMeasurement?,
    val procedure: CalibrationProcedureReference?,
    evidence: List<CalibrationEvidence>,
    val verificationLevel: CalibrationVerificationLevel,
    val compatibility: CalibrationCompatibilityBinding,
    invalidationConditions: Set<CalibrationInvalidationCondition>,
    val recordedAt: MonotonicTimestampMs,
    val validUntil: MonotonicTimestampMs?,
    val auditId: CapabilityAuditId,
) {
    val evidence: List<CalibrationEvidence> = Collections.unmodifiableList(
        evidence.sortedWith(compareBy({ it.kind.ordinal }, { it.reference.value })),
    )
    val invalidationConditions: Set<CalibrationInvalidationCondition> = Collections.unmodifiableSet(
        invalidationConditions.toSortedSet(compareBy { it.ordinal }),
    )
    val grantsSafetyApproval: Boolean get() = false
    val grantsExecutionAuthority: Boolean get() = false
    val persistsDeviceMemory: Boolean get() = false

    companion object {
        @Suppress("LongParameterList")
        fun create(
            id: CalibrationResultId,
            profileId: CalibrationProfileId,
            contractVersion: CalibrationContractVersion,
            providerInstanceId: ProviderInstanceId,
            itemId: CalibrationItemId,
            requirementReference: ManifestContractReference,
            status: CalibrationRecordedStatus,
            measurement: CalibrationMeasurement?,
            procedure: CalibrationProcedureReference?,
            evidence: List<CalibrationEvidence>,
            verificationLevel: CalibrationVerificationLevel,
            compatibility: CalibrationCompatibilityBinding,
            invalidationConditions: Set<CalibrationInvalidationCondition>,
            recordedAt: MonotonicTimestampMs,
            validUntil: MonotonicTimestampMs?,
            auditId: CapabilityAuditId,
        ): CalibrationDomainResult<CalibrationResult> {
            if (!contractVersion.isSupported) return failure(CalibrationDomainError.UNSUPPORTED_CONTRACT_VERSION)
            if (evidence.size > 32) return failure(CalibrationDomainError.TOO_MUCH_EVIDENCE)
            if (evidence.distinctBy { it.reference }.size != evidence.size) {
                return failure(CalibrationDomainError.DUPLICATE_EVIDENCE)
            }
            val measuredStatus = status == CalibrationRecordedStatus.CANDIDATE ||
                status == CalibrationRecordedStatus.VERIFIED
            if (measuredStatus != (measurement != null && evidence.isNotEmpty())) {
                return failure(CalibrationDomainError.RESULT_PAYLOAD_CONFLICT)
            }
            if (!measuredStatus && (verificationLevel != CalibrationVerificationLevel.UNVERIFIED || validUntil != null)) {
                return failure(CalibrationDomainError.RESULT_PAYLOAD_CONFLICT)
            }
            if (measuredStatus && (validUntil == null || validUntil.value <= recordedAt.value)) {
                return failure(CalibrationDomainError.INVALID_RESULT_FRESHNESS)
            }
            if (measuredStatus && invalidationConditions.isEmpty()) {
                return failure(CalibrationDomainError.MISSING_INVALIDATION_CONDITION)
            }
            if (status == CalibrationRecordedStatus.VERIFIED &&
                verificationLevel < CalibrationVerificationLevel.MEASURED
            ) return failure(CalibrationDomainError.VERIFICATION_STATUS_CONFLICT)
            if (verificationLevel > (evidence.maxOfOrNull { it.kind.maximumVerification }
                    ?: CalibrationVerificationLevel.UNVERIFIED)
            ) return failure(CalibrationDomainError.EVIDENCE_VERIFICATION_CONFLICT)
            if (CalibrationInvalidationCondition.FIRMWARE_VERSION_CHANGED in invalidationConditions &&
                compatibility.firmwareVersion == null
            ) return failure(CalibrationDomainError.MISSING_COMPATIBILITY_BINDING)
            if (CalibrationInvalidationCondition.HARDWARE_REVISION_CHANGED in invalidationConditions &&
                compatibility.hardwareRevision == null
            ) return failure(CalibrationDomainError.MISSING_COMPATIBILITY_BINDING)
            return CalibrationDomainResult.Success(
                CalibrationResult(
                    id,
                    profileId,
                    contractVersion,
                    providerInstanceId,
                    itemId,
                    requirementReference,
                    status,
                    measurement,
                    procedure,
                    evidence,
                    verificationLevel,
                    compatibility,
                    invalidationConditions,
                    recordedAt,
                    validUntil,
                    auditId,
                ),
            )
        }
    }
}

class CalibrationRequest private constructor(
    val id: CalibrationRequestId,
    val profileId: CalibrationProfileId,
    val contractVersion: CalibrationContractVersion,
    val providerInstanceId: ProviderInstanceId,
    val providerGeneration: ProviderGeneration,
    val itemId: CalibrationItemId,
    val procedure: CalibrationProcedureReference,
    val requestedAt: MonotonicTimestampMs,
    val deadline: MonotonicTimestampMs,
    val auditId: CapabilityAuditId,
) {
    val grantsExecutionAuthority: Boolean get() = false

    companion object {
        @Suppress("LongParameterList")
        fun create(
            id: CalibrationRequestId,
            profile: CalibrationProfile,
            providerIdentity: ProviderIdentity,
            providerGeneration: ProviderGeneration,
            itemId: CalibrationItemId,
            procedure: CalibrationProcedureReference,
            requestedAt: MonotonicTimestampMs,
            deadline: MonotonicTimestampMs,
            auditId: CapabilityAuditId,
        ): CalibrationDomainResult<CalibrationRequest> {
            val requirement = profile.requirement(itemId)
                ?: return failure(CalibrationDomainError.UNKNOWN_CALIBRATION_ITEM)
            return when {
                providerIdentity.instanceId != profile.providerIdentity.instanceId ->
                    failure(CalibrationDomainError.PROVIDER_INSTANCE_MISMATCH)
                providerIdentity.definitionId != profile.providerIdentity.definitionId ||
                    providerIdentity.family != profile.providerIdentity.family ->
                    failure(CalibrationDomainError.PROVIDER_DEFINITION_MISMATCH)
                procedure !in requirement.procedures -> failure(CalibrationDomainError.UNSUPPORTED_PROCEDURE)
                deadline.value <= requestedAt.value -> failure(CalibrationDomainError.INVALID_REQUEST_DEADLINE)
                else -> CalibrationDomainResult.Success(
                    CalibrationRequest(
                        id,
                        profile.id,
                        profile.contractVersion,
                        providerIdentity.instanceId,
                        providerGeneration,
                        itemId,
                        procedure,
                        requestedAt,
                        deadline,
                        auditId,
                    ),
                )
            }
        }
    }
}

sealed interface CalibrationDomainResult<out T> {
    data class Success<T>(val value: T) : CalibrationDomainResult<T>
    data class Failure(val error: CalibrationDomainError) : CalibrationDomainResult<Nothing>
}

enum class CalibrationDomainError {
    INVALID_IDENTIFIER,
    INVALID_CONTRACT_VERSION,
    UNSUPPORTED_CONTRACT_VERSION,
    INVALID_ITEM_ID,
    INVALID_FIELD_ID,
    INVALID_UNIT,
    INVALID_HARDWARE_REVISION,
    INVALID_EVIDENCE_REFERENCE,
    NON_FINITE_VALUE,
    INVALID_TEXT_VALUE,
    MISSING_BOUNDS,
    NON_FINITE_BOUNDS,
    REVERSED_BOUNDS,
    FIELD_DEFINITION_CONFLICT,
    INVALID_CONFIDENCE,
    INVALID_EVIDENCE_FRESHNESS,
    INVALID_FIELD_COUNT,
    DUPLICATE_FIELD,
    INVALID_PROCEDURE_COUNT,
    DUPLICATE_PROCEDURE,
    INVALID_MINIMUM_VERIFICATION,
    MISSING_INVALIDATION_CONDITION,
    INVALID_CAPABILITY_BINDINGS,
    INVALID_REQUIREMENT_COUNT,
    DUPLICATE_REQUIREMENT,
    DUPLICATE_MANIFEST_REQUIREMENT,
    INVALID_MEASUREMENT_FIELD_COUNT,
    TOO_MUCH_EVIDENCE,
    TOO_MANY_RESULTS,
    TOO_MANY_INVALIDATIONS,
    DUPLICATE_EVIDENCE,
    RESULT_PAYLOAD_CONFLICT,
    INVALID_RESULT_FRESHNESS,
    VERIFICATION_STATUS_CONFLICT,
    EVIDENCE_VERIFICATION_CONFLICT,
    MISSING_COMPATIBILITY_BINDING,
    UNKNOWN_CALIBRATION_ITEM,
    PROVIDER_INSTANCE_MISMATCH,
    PROVIDER_DEFINITION_MISMATCH,
    UNSUPPORTED_PROCEDURE,
    INVALID_REQUEST_DEADLINE,
}

private fun <T> failure(error: CalibrationDomainError): CalibrationDomainResult<T> =
    CalibrationDomainResult.Failure(error)

private fun <T> parseUuid(raw: String, construct: (String) -> T): CalibrationDomainResult<T> {
    val pattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    return if (pattern.matches(raw)) CalibrationDomainResult.Success(construct(raw))
    else failure(CalibrationDomainError.INVALID_IDENTIFIER)
}
