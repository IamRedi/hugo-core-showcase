package io.redi.robot.capability.api

/** Semantic namespace independent of provider, vendor, host, adapter, transport, and UI. */
@JvmInline
value class CapabilityNamespace private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val segment = "[a-z][a-z0-9]*(?:_[a-z0-9]+)*"
        private val canonicalPattern = Regex("^$segment(?:[.]$segment)*$")

        fun parse(raw: String): CapabilityIdentityResult<CapabilityNamespace> = when {
            raw.isBlank() -> CapabilityIdentityResult.Failure(
                CapabilityIdentityError.InvalidNamespace(CapabilityNameProblem.Blank),
            )
            !canonicalPattern.matches(raw) -> CapabilityIdentityResult.Failure(
                CapabilityIdentityError.InvalidNamespace(CapabilityNameProblem.Malformed),
            )
            else -> CapabilityIdentityResult.Success(CapabilityNamespace(raw))
        }
    }
}

/** Final semantic operation/observation name within a capability namespace. */
@JvmInline
value class CapabilityName private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val canonicalPattern = Regex("^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$")

        fun parse(raw: String): CapabilityIdentityResult<CapabilityName> = when {
            raw.isBlank() -> CapabilityIdentityResult.Failure(
                CapabilityIdentityError.InvalidName(CapabilityNameProblem.Blank),
            )
            !canonicalPattern.matches(raw) -> CapabilityIdentityResult.Failure(
                CapabilityIdentityError.InvalidName(CapabilityNameProblem.Malformed),
            )
            else -> CapabilityIdentityResult.Success(CapabilityName(raw))
        }
    }
}

data class CapabilityId(
    val namespace: CapabilityNamespace,
    val name: CapabilityName,
) {
    val wireValue: String = "${namespace.value}.${name.value}"

    override fun toString(): String = wireValue

    companion object {
        fun parse(raw: String): CapabilityIdentityResult<CapabilityId> {
            if (raw.isBlank()) {
                return CapabilityIdentityResult.Failure(CapabilityIdentityError.BlankCapabilityId)
            }
            val separator = raw.lastIndexOf('.')
            if (separator <= 0 || separator == raw.lastIndex) {
                return CapabilityIdentityResult.Failure(
                    CapabilityIdentityError.MalformedCapabilityId,
                )
            }
            val namespace = when (val result = CapabilityNamespace.parse(raw.substring(0, separator))) {
                is CapabilityIdentityResult.Success -> result.value
                is CapabilityIdentityResult.Failure -> return result
            }
            val name = when (val result = CapabilityName.parse(raw.substring(separator + 1))) {
                is CapabilityIdentityResult.Success -> result.value
                is CapabilityIdentityResult.Failure -> return result
            }
            return CapabilityIdentityResult.Success(CapabilityId(namespace, name))
        }
    }
}

data class CapabilityVersion private constructor(
    val major: Int,
    val minor: Int,
) {
    val wireValue: String = "$major.$minor"

    override fun toString(): String = wireValue

    companion object {
        private val canonicalPattern = Regex("^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$")

        fun create(major: Int, minor: Int): CapabilityIdentityResult<CapabilityVersion> = when {
            major <= 0 -> CapabilityIdentityResult.Failure(
                CapabilityIdentityError.InvalidVersion(CapabilityVersionProblem.MajorMustBePositive),
            )
            minor < 0 -> CapabilityIdentityResult.Failure(
                CapabilityIdentityError.InvalidVersion(CapabilityVersionProblem.MinorMustNotBeNegative),
            )
            else -> CapabilityIdentityResult.Success(CapabilityVersion(major, minor))
        }

        fun parse(raw: String): CapabilityIdentityResult<CapabilityVersion> {
            if (raw.isBlank()) {
                return CapabilityIdentityResult.Failure(
                    CapabilityIdentityError.InvalidVersion(CapabilityVersionProblem.Blank),
                )
            }
            if (!canonicalPattern.matches(raw)) {
                return CapabilityIdentityResult.Failure(
                    CapabilityIdentityError.InvalidVersion(CapabilityVersionProblem.Malformed),
                )
            }
            val (majorText, minorText) = raw.split('.', limit = 2)
            val major = majorText.toIntOrNull()
            val minor = minorText.toIntOrNull()
            if (major == null || minor == null) {
                return CapabilityIdentityResult.Failure(
                    CapabilityIdentityError.InvalidVersion(CapabilityVersionProblem.OutsideIntegerRange),
                )
            }
            return create(major, minor)
        }
    }
}

data class CapabilityReference(
    val id: CapabilityId,
    val version: CapabilityVersion,
) {
    val wireValue: String = "${id.wireValue}@${version.wireValue}"

    override fun toString(): String = wireValue

    /**
     * Evaluates this offered contract against [required]. Compatibility is directional: a newer
     * minor can satisfy an older requirement within the same major, but not the reverse.
     */
    fun compatibilityWith(required: CapabilityReference): CapabilityCompatibility = when {
        id != required.id -> CapabilityCompatibility.Incompatible(
            CapabilityIncompatibilityReason.DifferentCapability,
        )
        version.major != required.version.major -> CapabilityCompatibility.Incompatible(
            CapabilityIncompatibilityReason.MajorVersionMismatch,
        )
        version.minor < required.version.minor -> CapabilityCompatibility.Incompatible(
            CapabilityIncompatibilityReason.OfferedMinorTooOld,
        )
        version.minor == required.version.minor -> CapabilityCompatibility.Exact
        else -> CapabilityCompatibility.BackwardCompatibleMinor(
            offered = version,
            required = required.version,
        )
    }

    companion object {
        fun parse(raw: String): CapabilityIdentityResult<CapabilityReference> {
            if (raw.count { it == '@' } != 1) {
                return CapabilityIdentityResult.Failure(
                    CapabilityIdentityError.MalformedCapabilityReference,
                )
            }
            val (idText, versionText) = raw.split('@', limit = 2)
            val id = when (val result = CapabilityId.parse(idText)) {
                is CapabilityIdentityResult.Success -> result.value
                is CapabilityIdentityResult.Failure -> return result
            }
            val version = when (val result = CapabilityVersion.parse(versionText)) {
                is CapabilityIdentityResult.Success -> result.value
                is CapabilityIdentityResult.Failure -> return result
            }
            return CapabilityIdentityResult.Success(CapabilityReference(id, version))
        }
    }
}

sealed interface CapabilityCompatibility {
    data object Exact : CapabilityCompatibility

    data class BackwardCompatibleMinor(
        val offered: CapabilityVersion,
        val required: CapabilityVersion,
    ) : CapabilityCompatibility

    data class Incompatible(
        val reason: CapabilityIncompatibilityReason,
    ) : CapabilityCompatibility
}

enum class CapabilityIncompatibilityReason {
    DifferentCapability,
    MajorVersionMismatch,
    OfferedMinorTooOld,
}

sealed interface CapabilityIdentityResult<out T> {
    data class Success<T>(val value: T) : CapabilityIdentityResult<T>

    data class Failure(val error: CapabilityIdentityError) : CapabilityIdentityResult<Nothing>
}

sealed interface CapabilityIdentityError {
    data object BlankCapabilityId : CapabilityIdentityError

    data object MalformedCapabilityId : CapabilityIdentityError

    data class InvalidNamespace(val problem: CapabilityNameProblem) : CapabilityIdentityError

    data class InvalidName(val problem: CapabilityNameProblem) : CapabilityIdentityError

    data class InvalidVersion(val problem: CapabilityVersionProblem) : CapabilityIdentityError

    data object MalformedCapabilityReference : CapabilityIdentityError
}

enum class CapabilityNameProblem {
    Blank,
    Malformed,
}

enum class CapabilityVersionProblem {
    Blank,
    Malformed,
    MajorMustBePositive,
    MinorMustNotBeNegative,
    OutsideIntegerRange,
}
