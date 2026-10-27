package io.redi.robot.skills.api

import io.redi.robot.capability.api.CapabilityActorKind
import io.redi.robot.capability.api.CapabilityArgumentValue
import io.redi.robot.capability.api.CapabilityContractReference
import io.redi.robot.capability.api.CapabilityData
import io.redi.robot.capability.api.CapabilityReference
import java.util.UUID

@JvmInline
value class SkillId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[.][a-z][a-z0-9_]*)+$")

        fun parse(raw: String): SkillModelResult<SkillId> = if (pattern.matches(raw)) {
            SkillModelResult.Success(SkillId(raw))
        } else {
            SkillModelResult.Failure(SkillModelError.INVALID_SKILL_ID)
        }
    }
}

data class SkillVersion(val major: Int, val minor: Int) : Comparable<SkillVersion> {
    val wireValue: String = "$major.$minor"

    init {
        require(major in 0..999 && minor in 0..999)
    }

    override fun compareTo(other: SkillVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor })
    override fun toString(): String = wireValue

    companion object {
        fun parse(raw: String): SkillModelResult<SkillVersion> {
            val pieces = raw.split('.')
            if (pieces.size != 2 || pieces.any { it.isEmpty() || it.length > 3 || it.any { char -> !char.isDigit() } }) {
                return SkillModelResult.Failure(SkillModelError.INVALID_SKILL_VERSION)
            }
            if (pieces.any { it.length > 1 && it.startsWith('0') }) {
                return SkillModelResult.Failure(SkillModelError.INVALID_SKILL_VERSION)
            }
            val major = pieces[0].toIntOrNull()
            val minor = pieces[1].toIntOrNull()
            return if (major != null && minor != null && major in 0..999 && minor in 0..999) {
                SkillModelResult.Success(SkillVersion(major, minor))
            } else {
                SkillModelResult.Failure(SkillModelError.INVALID_SKILL_VERSION)
            }
        }
    }
}

data class SkillReference(val id: SkillId, val version: SkillVersion) : Comparable<SkillReference> {
    val wireValue: String = "${id.value}@${version.wireValue}"

    override fun compareTo(other: SkillReference): Int = compareValuesBy(this, other, { it.id.value }, { it.version })
    override fun toString(): String = wireValue

    companion object {
        fun parse(raw: String): SkillModelResult<SkillReference> {
            if (raw.count { it == '@' } != 1) return SkillModelResult.Failure(SkillModelError.INVALID_SKILL_REFERENCE)
            val (idRaw, versionRaw) = raw.split('@', limit = 2)
            val id = (SkillId.parse(idRaw) as? SkillModelResult.Success)?.value
                ?: return SkillModelResult.Failure(SkillModelError.INVALID_SKILL_REFERENCE)
            val version = (SkillVersion.parse(versionRaw) as? SkillModelResult.Success)?.value
                ?: return SkillModelResult.Failure(SkillModelError.INVALID_SKILL_REFERENCE)
            return SkillModelResult.Success(SkillReference(id, version))
        }
    }
}

@JvmInline
value class SkillExecutionId private constructor(val value: String) {
    override fun toString(): String = "skill-execution:[redacted]"

    companion object {
        fun fromUuid(uuid: UUID): SkillExecutionId = SkillExecutionId(uuid.toString())

        fun parse(raw: String): SkillModelResult<SkillExecutionId> {
            val canonical = runCatching { UUID.fromString(raw).toString() }.getOrNull()
            return if (canonical == raw) {
                SkillModelResult.Success(SkillExecutionId(raw))
            } else {
                SkillModelResult.Failure(SkillModelError.INVALID_EXECUTION_ID)
            }
        }
    }
}

enum class LocalSkillClass {
    OBSERVATION,
    NON_MOTION_CONTROL,
}

class SkillDefinition private constructor(
    val reference: SkillReference,
    val capability: CapabilityReference,
    val actor: CapabilityActorKind,
    val skillClass: LocalSkillClass,
    val inputContract: CapabilityContractReference?,
    val fixedInput: CapabilityData?,
    val outputContract: CapabilityContractReference?,
    val maximumDurationMs: Long,
    val cancellationRequired: Boolean,
) {
    companion object {
        const val MAXIMUM_DURATION_MS = 30_000L

        @Suppress("LongParameterList")
        fun create(
            reference: SkillReference,
            capability: CapabilityReference,
            actor: CapabilityActorKind,
            skillClass: LocalSkillClass,
            inputContract: CapabilityContractReference?,
            fixedInput: CapabilityData?,
            outputContract: CapabilityContractReference?,
            maximumDurationMs: Long,
            cancellationRequired: Boolean,
        ): SkillModelResult<SkillDefinition> {
            if ((fixedInput == null) != (inputContract == null)) {
                return SkillModelResult.Failure(SkillModelError.INPUT_POLICY_CONFLICT)
            }
            if (fixedInput != null && fixedInput.contract != inputContract) {
                return SkillModelResult.Failure(SkillModelError.INPUT_CONTRACT_MISMATCH)
            }
            if (maximumDurationMs !in 100L..MAXIMUM_DURATION_MS) {
                return SkillModelResult.Failure(SkillModelError.INVALID_DURATION)
            }
            if (!cancellationRequired) {
                return SkillModelResult.Failure(SkillModelError.CANCELLATION_REQUIRED)
            }
            return SkillModelResult.Success(
                SkillDefinition(
                    reference,
                    capability,
                    actor,
                    skillClass,
                    inputContract,
                    fixedInput,
                    outputContract,
                    maximumDurationMs,
                    cancellationRequired,
                ),
            )
        }
    }

    fun acceptsInput(input: CapabilityData?): Boolean = when {
        fixedInput == null -> input == null
        input == null -> false
        else -> fixedInput.canonicalSkillData() == input.canonicalSkillData()
    }

    override fun toString(): String = "SkillDefinition(" +
        "reference=$reference, capability=$capability, class=$skillClass, input=redacted, " +
        "output=$outputContract, maximumDurationMs=$maximumDurationMs)"
}

enum class SkillModelError {
    INVALID_SKILL_ID,
    INVALID_SKILL_VERSION,
    INVALID_SKILL_REFERENCE,
    INVALID_EXECUTION_ID,
    INPUT_POLICY_CONFLICT,
    INPUT_CONTRACT_MISMATCH,
    INVALID_DURATION,
    CANCELLATION_REQUIRED,
    EMPTY_REGISTRY,
    REGISTRY_TOO_LARGE,
    DUPLICATE_SKILL,
}

sealed interface SkillModelResult<out T> {
    data class Success<T>(val value: T) : SkillModelResult<T>
    data class Failure(val error: SkillModelError) : SkillModelResult<Nothing>
}

internal fun CapabilityData.canonicalSkillData(): String = buildString {
    append(contract.wireValue)
    values.entries.sortedBy { it.key.value }.forEach { (name, value) ->
        append('|')
        append(name.value)
        append('=')
        append(
            when (value) {
                is CapabilityArgumentValue.Text -> "text:${value.value}"
                is CapabilityArgumentValue.Integer -> "integer:${value.value}"
                is CapabilityArgumentValue.Decimal -> "decimal:${java.lang.Double.toHexString(value.value)}"
                is CapabilityArgumentValue.Flag -> "flag:${value.value}"
            },
        )
    }
}
