package io.redi.robot.capability.api

@JvmInline
value class CapabilityLabel private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): CapabilityDescriptorResult<CapabilityLabel> =
            validateText(raw, maxLength = 80, field = "label") { CapabilityLabel(it) }
    }
}

@JvmInline
value class CapabilityDescription private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): CapabilityDescriptorResult<CapabilityDescription> =
            validateText(raw, maxLength = 500, field = "description") { CapabilityDescription(it) }
    }
}

@JvmInline
value class CapabilityContractId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val segment = "[a-z][a-z0-9]*(?:_[a-z0-9]+)*"
        private val canonicalPattern = Regex("^$segment(?:[.]$segment)+$")

        fun parse(raw: String): CapabilityDescriptorResult<CapabilityContractId> = when {
            raw.isBlank() -> CapabilityDescriptorResult.Failure(
                CapabilityDescriptorError.InvalidField("contractId", DescriptorFieldProblem.Blank),
            )
            !canonicalPattern.matches(raw) -> CapabilityDescriptorResult.Failure(
                CapabilityDescriptorError.InvalidField("contractId", DescriptorFieldProblem.Malformed),
            )
            else -> CapabilityDescriptorResult.Success(CapabilityContractId(raw))
        }
    }
}

data class CapabilityContractReference(
    val id: CapabilityContractId,
    val version: CapabilityVersion,
) {
    val wireValue: String = "${id.value}@${version.wireValue}"

    override fun toString(): String = wireValue

    companion object {
        fun parse(raw: String): CapabilityDescriptorResult<CapabilityContractReference> {
            if (raw.count { it == '@' } != 1) {
                return CapabilityDescriptorResult.Failure(
                    CapabilityDescriptorError.InvalidField(
                        "contractReference",
                        DescriptorFieldProblem.Malformed,
                    ),
                )
            }
            val (idText, versionText) = raw.split('@', limit = 2)
            val id = when (val result = CapabilityContractId.parse(idText)) {
                is CapabilityDescriptorResult.Success -> result.value
                is CapabilityDescriptorResult.Failure -> return result
            }
            val version = when (val result = CapabilityVersion.parse(versionText)) {
                is CapabilityIdentityResult.Success -> result.value
                is CapabilityIdentityResult.Failure -> return CapabilityDescriptorResult.Failure(
                    CapabilityDescriptorError.InvalidField(
                        "contractVersion",
                        DescriptorFieldProblem.Malformed,
                    ),
                )
            }
            return CapabilityDescriptorResult.Success(CapabilityContractReference(id, version))
        }
    }
}

enum class CapabilityEffect {
    OBSERVE,
    ACT,
    MODIFY,
}

enum class CapabilityPermissionIntent {
    OBSERVE,
    ACT,
    MODIFY,
}

enum class CapabilityApprovalRequirement {
    WITHIN_GRANT,
    SESSION_CONFIRMATION,
    ONE_TIME_CONFIRMATION,
    EVERY_REQUEST,
    PROHIBITED,
}

enum class CapabilityRiskClass {
    INFORMATIONAL,
    LOW,
    ELEVATED,
    HIGH,
    SAFETY_CRITICAL,
}

enum class CapabilitySafetyClass {
    NON_PHYSICAL,
    PHYSICAL_OBSERVATION,
    PHYSICAL_ACTION,
    SAFETY_CRITICAL_ACTION,
}

enum class CapabilityCancellationSemantics {
    NOT_APPLICABLE,
    SUPPORTED,
    REQUIRED,
}

/** Declares availability behavior, never the provider's current availability. */
enum class CapabilityAvailabilitySemantics {
    WHILE_REGISTERED,
    SESSION_SCOPED,
    DYNAMIC_STATE,
}

enum class CapabilityEvidenceClass {
    DECLARED,
    DOCUMENTED,
    AUTOMATED_TESTED,
    DEVICE_VERIFIED,
    HARDWARE_VERIFIED,
}

@JvmInline
value class CapabilityLimitKey private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val canonicalPattern = Regex("^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$")

        fun parse(raw: String): CapabilityDescriptorResult<CapabilityLimitKey> = when {
            raw.isBlank() -> CapabilityDescriptorResult.Failure(
                CapabilityDescriptorError.InvalidField("limitKey", DescriptorFieldProblem.Blank),
            )
            !canonicalPattern.matches(raw) -> CapabilityDescriptorResult.Failure(
                CapabilityDescriptorError.InvalidField("limitKey", DescriptorFieldProblem.Malformed),
            )
            else -> CapabilityDescriptorResult.Success(CapabilityLimitKey(raw))
        }
    }
}

@JvmInline
value class CapabilityUnit private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val canonicalPattern = Regex("^[A-Za-z%][A-Za-z0-9_/%.-]{0,31}$")

        fun parse(raw: String): CapabilityDescriptorResult<CapabilityUnit> = when {
            raw.isBlank() -> CapabilityDescriptorResult.Failure(
                CapabilityDescriptorError.InvalidField("unit", DescriptorFieldProblem.Blank),
            )
            !canonicalPattern.matches(raw) -> CapabilityDescriptorResult.Failure(
                CapabilityDescriptorError.InvalidField("unit", DescriptorFieldProblem.Malformed),
            )
            else -> CapabilityDescriptorResult.Success(CapabilityUnit(raw))
        }
    }
}

data class CapabilityNumericLimit private constructor(
    val key: CapabilityLimitKey,
    val minimum: Double?,
    val maximum: Double?,
    val unit: CapabilityUnit?,
) {
    companion object {
        fun create(
            key: CapabilityLimitKey,
            minimum: Double?,
            maximum: Double?,
            unit: CapabilityUnit? = null,
        ): CapabilityDescriptorResult<CapabilityNumericLimit> {
            if (minimum == null && maximum == null) {
                return CapabilityDescriptorResult.Failure(
                    CapabilityDescriptorError.InvalidLimit(key, CapabilityLimitProblem.MissingBounds),
                )
            }
            if (minimum?.isFinite() == false || maximum?.isFinite() == false) {
                return CapabilityDescriptorResult.Failure(
                    CapabilityDescriptorError.InvalidLimit(key, CapabilityLimitProblem.NonFinite),
                )
            }
            if (minimum != null && maximum != null && minimum > maximum) {
                return CapabilityDescriptorResult.Failure(
                    CapabilityDescriptorError.InvalidLimit(key, CapabilityLimitProblem.ReversedBounds),
                )
            }
            return CapabilityDescriptorResult.Success(
                CapabilityNumericLimit(key, minimum, maximum, unit),
            )
        }
    }
}

@JvmInline
value class CapabilityMetadataKey private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val segment = "[a-z][a-z0-9]*(?:_[a-z0-9]+)*"
        private val canonicalPattern = Regex("^$segment(?:[.]$segment)*$")
        private val rawProtocolSegments = setOf(
            "bluetooth",
            "characteristic",
            "endpoint",
            "gatt",
            "mac",
            "packet",
            "transport",
            "uuid",
        )

        fun parse(raw: String): CapabilityDescriptorResult<CapabilityMetadataKey> = when {
            raw.isBlank() -> CapabilityDescriptorResult.Failure(
                CapabilityDescriptorError.InvalidField("metadataKey", DescriptorFieldProblem.Blank),
            )
            !canonicalPattern.matches(raw) -> CapabilityDescriptorResult.Failure(
                CapabilityDescriptorError.InvalidField("metadataKey", DescriptorFieldProblem.Malformed),
            )
            raw.split('.', '_').any(rawProtocolSegments::contains) -> CapabilityDescriptorResult.Failure(
                CapabilityDescriptorError.RawProtocolMetadata(raw),
            )
            else -> CapabilityDescriptorResult.Success(CapabilityMetadataKey(raw))
        }
    }
}

@JvmInline
value class CapabilityMetadataValue private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): CapabilityDescriptorResult<CapabilityMetadataValue> =
            validateText(raw, maxLength = 256, field = "metadataValue") {
                CapabilityMetadataValue(it)
            }
    }
}

class CapabilityDescriptor private constructor(
    val reference: CapabilityReference,
    val label: CapabilityLabel,
    val description: CapabilityDescription,
    val inputContract: CapabilityContractReference?,
    val outputContract: CapabilityContractReference?,
    val effect: CapabilityEffect,
    val risk: CapabilityRiskClass,
    val safety: CapabilitySafetyClass,
    val permissionIntent: CapabilityPermissionIntent,
    val approvalRequirement: CapabilityApprovalRequirement,
    val cancellation: CapabilityCancellationSemantics,
    val availability: CapabilityAvailabilitySemantics,
    val evidence: CapabilityEvidenceClass,
    val limits: List<CapabilityNumericLimit>,
    val metadata: Map<CapabilityMetadataKey, CapabilityMetadataValue>,
) {
    val isReadOnly: Boolean = effect == CapabilityEffect.OBSERVE

    companion object {
        private const val maxLimits = 32
        private const val maxMetadataEntries = 16

        @Suppress("LongParameterList")
        fun create(
            reference: CapabilityReference,
            label: CapabilityLabel,
            description: CapabilityDescription,
            inputContract: CapabilityContractReference?,
            outputContract: CapabilityContractReference?,
            effect: CapabilityEffect,
            risk: CapabilityRiskClass,
            safety: CapabilitySafetyClass,
            permissionIntent: CapabilityPermissionIntent,
            approvalRequirement: CapabilityApprovalRequirement,
            cancellation: CapabilityCancellationSemantics,
            availability: CapabilityAvailabilitySemantics,
            evidence: CapabilityEvidenceClass,
            limits: List<CapabilityNumericLimit> = emptyList(),
            metadata: Map<CapabilityMetadataKey, CapabilityMetadataValue> = emptyMap(),
        ): CapabilityDescriptorResult<CapabilityDescriptor> {
            val expectedPermission = when (effect) {
                CapabilityEffect.OBSERVE -> CapabilityPermissionIntent.OBSERVE
                CapabilityEffect.ACT -> CapabilityPermissionIntent.ACT
                CapabilityEffect.MODIFY -> CapabilityPermissionIntent.MODIFY
            }
            if (permissionIntent != expectedPermission) {
                return CapabilityDescriptorResult.Failure(
                    CapabilityDescriptorError.PermissionEffectConflict(effect, permissionIntent),
                )
            }
            if (
                effect == CapabilityEffect.OBSERVE &&
                safety in setOf(
                    CapabilitySafetyClass.PHYSICAL_ACTION,
                    CapabilitySafetyClass.SAFETY_CRITICAL_ACTION,
                )
            ) {
                return CapabilityDescriptorResult.Failure(
                    CapabilityDescriptorError.SafetyEffectConflict(effect, safety),
                )
            }
            if (
                (safety == CapabilitySafetyClass.SAFETY_CRITICAL_ACTION) !=
                (risk == CapabilityRiskClass.SAFETY_CRITICAL)
            ) {
                return CapabilityDescriptorResult.Failure(
                    CapabilityDescriptorError.SafetyRiskConflict(safety, risk),
                )
            }
            if (limits.size > maxLimits) {
                return CapabilityDescriptorResult.Failure(
                    CapabilityDescriptorError.TooManyLimits(maxLimits),
                )
            }
            if (limits.map { it.key }.toSet().size != limits.size) {
                return CapabilityDescriptorResult.Failure(
                    CapabilityDescriptorError.DuplicateLimitKeys,
                )
            }
            if (metadata.size > maxMetadataEntries) {
                return CapabilityDescriptorResult.Failure(
                    CapabilityDescriptorError.TooManyMetadataEntries(maxMetadataEntries),
                )
            }

            return CapabilityDescriptorResult.Success(
                CapabilityDescriptor(
                    reference = reference,
                    label = label,
                    description = description,
                    inputContract = inputContract,
                    outputContract = outputContract,
                    effect = effect,
                    risk = risk,
                    safety = safety,
                    permissionIntent = permissionIntent,
                    approvalRequirement = approvalRequirement,
                    cancellation = cancellation,
                    availability = availability,
                    evidence = evidence,
                    limits = limits.toList(),
                    metadata = metadata.toMap(),
                ),
            )
        }
    }
}

sealed interface CapabilityDescriptorResult<out T> {
    data class Success<T>(val value: T) : CapabilityDescriptorResult<T>

    data class Failure(val error: CapabilityDescriptorError) : CapabilityDescriptorResult<Nothing>
}

sealed interface CapabilityDescriptorError {
    data class InvalidField(
        val field: String,
        val problem: DescriptorFieldProblem,
    ) : CapabilityDescriptorError

    data class InvalidLimit(
        val key: CapabilityLimitKey,
        val problem: CapabilityLimitProblem,
    ) : CapabilityDescriptorError

    data class PermissionEffectConflict(
        val effect: CapabilityEffect,
        val permission: CapabilityPermissionIntent,
    ) : CapabilityDescriptorError

    data class SafetyEffectConflict(
        val effect: CapabilityEffect,
        val safety: CapabilitySafetyClass,
    ) : CapabilityDescriptorError

    data class SafetyRiskConflict(
        val safety: CapabilitySafetyClass,
        val risk: CapabilityRiskClass,
    ) : CapabilityDescriptorError

    data object DuplicateLimitKeys : CapabilityDescriptorError

    data class TooManyLimits(val maximum: Int) : CapabilityDescriptorError

    data class TooManyMetadataEntries(val maximum: Int) : CapabilityDescriptorError

    data class RawProtocolMetadata(val key: String) : CapabilityDescriptorError
}

enum class DescriptorFieldProblem {
    Blank,
    TooLong,
    ControlCharacter,
    Malformed,
}

enum class CapabilityLimitProblem {
    MissingBounds,
    NonFinite,
    ReversedBounds,
}

private fun <T> validateText(
    raw: String,
    maxLength: Int,
    field: String,
    construct: (String) -> T,
): CapabilityDescriptorResult<T> = when {
    raw.isBlank() -> CapabilityDescriptorResult.Failure(
        CapabilityDescriptorError.InvalidField(field, DescriptorFieldProblem.Blank),
    )
    raw.length > maxLength -> CapabilityDescriptorResult.Failure(
        CapabilityDescriptorError.InvalidField(field, DescriptorFieldProblem.TooLong),
    )
    raw.any(Char::isISOControl) -> CapabilityDescriptorResult.Failure(
        CapabilityDescriptorError.InvalidField(field, DescriptorFieldProblem.ControlCharacter),
    )
    else -> CapabilityDescriptorResult.Success(construct(raw))
}
