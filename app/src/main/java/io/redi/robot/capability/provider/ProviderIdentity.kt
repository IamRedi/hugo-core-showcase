package io.redi.robot.capability.provider

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Stable public identity for an adapter-supported provider definition, not a physical instance. */
@JvmInline
value class ProviderDefinitionId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val canonicalPattern = Regex(
            "^[a-z][a-z0-9]*(?:[.][a-z][a-z0-9]*)*/[a-z][a-z0-9]*(?:[-_][a-z0-9]+)*$",
        )

        fun parse(raw: String): ProviderIdentityResult<ProviderDefinitionId> = when {
            raw.isBlank() -> ProviderIdentityResult.Failure(
                ProviderIdentityError.InvalidDefinitionId(ProviderIdentifierProblem.Blank),
            )
            !canonicalPattern.matches(raw) -> ProviderIdentityResult.Failure(
                ProviderIdentityError.InvalidDefinitionId(ProviderIdentifierProblem.Malformed),
            )
            else -> ProviderIdentityResult.Success(ProviderDefinitionId(raw))
        }
    }
}

/**
 * Private HUGO registration identity for one configured provider binding.
 *
 * It is not a hardware/device/body ID, service account ID, transport address, endpoint, or current
 * connection identity. Protected mappings to external identifiers belong outside this contract.
 */
@JvmInline
value class ProviderInstanceId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val canonicalPattern = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
        private val caseInsensitivePattern = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )

        fun fromUuid(uuid: UUID): ProviderInstanceId = ProviderInstanceId(uuid.toString())

        fun parse(raw: String): ProviderIdentityResult<ProviderInstanceId> = when {
            raw.isBlank() -> ProviderIdentityResult.Failure(
                ProviderIdentityError.InvalidInstanceId(ProviderIdentifierProblem.Blank),
            )
            !caseInsensitivePattern.matches(raw) -> ProviderIdentityResult.Failure(
                ProviderIdentityError.InvalidInstanceId(ProviderIdentifierProblem.Malformed),
            )
            !canonicalPattern.matches(raw) -> ProviderIdentityResult.Failure(
                ProviderIdentityError.InvalidInstanceId(ProviderIdentifierProblem.NonCanonical),
            )
            else -> ProviderIdentityResult.Success(ProviderInstanceId(raw))
        }
    }
}

enum class ProviderFamily(val wireValue: String) {
    BODY_ADAPTER("body_adapter"),
    DEVICE_ADAPTER("device_adapter"),
    SERVICE_CONNECTOR("service_connector"),
    ;

    companion object {
        fun fromWireValue(value: String): ProviderFamily? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

/** User-facing alias that must not contain stable transport identifiers. */
@JvmInline
value class RedactedProviderAlias private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private const val maxLength = 64
        private val macAddressPattern = Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")

        fun parse(raw: String): ProviderIdentityResult<RedactedProviderAlias> = when {
            raw.isBlank() -> ProviderIdentityResult.Failure(
                ProviderIdentityError.InvalidAlias(ProviderAliasProblem.Blank),
            )
            raw.length > maxLength -> ProviderIdentityResult.Failure(
                ProviderIdentityError.InvalidAlias(ProviderAliasProblem.TooLong),
            )
            raw.any(Char::isISOControl) -> ProviderIdentityResult.Failure(
                ProviderIdentityError.InvalidAlias(ProviderAliasProblem.ControlCharacter),
            )
            macAddressPattern.containsMatchIn(raw) -> ProviderIdentityResult.Failure(
                ProviderIdentityError.InvalidAlias(ProviderAliasProblem.StableAddress),
            )
            else -> ProviderIdentityResult.Success(RedactedProviderAlias(raw))
        }
    }
}

data class ProviderIdentity(
    val definitionId: ProviderDefinitionId,
    val instanceId: ProviderInstanceId,
    val family: ProviderFamily,
    val displayAlias: RedactedProviderAlias,
)

/** Safe identity projection for logs, diagnostics, and user-facing selection. */
data class RedactedProviderIdentity(
    val definitionId: ProviderDefinitionId,
    val family: ProviderFamily,
    val displayAlias: RedactedProviderAlias,
)

fun ProviderIdentity.redacted(): RedactedProviderIdentity = RedactedProviderIdentity(
    definitionId = definitionId,
    family = family,
    displayAlias = displayAlias,
)

sealed interface ProviderIdentityResult<out T> {
    data class Success<T>(val value: T) : ProviderIdentityResult<T>

    data class Failure(val error: ProviderIdentityError) : ProviderIdentityResult<Nothing>
}

sealed interface ProviderIdentityError {
    data class InvalidDefinitionId(val problem: ProviderIdentifierProblem) : ProviderIdentityError

    data class InvalidInstanceId(val problem: ProviderIdentifierProblem) : ProviderIdentityError

    data class InvalidAlias(val problem: ProviderAliasProblem) : ProviderIdentityError

    data class UnsupportedSchemaVersion(val found: String) : ProviderIdentityError

    data class MissingField(val field: String) : ProviderIdentityError

    data class UnexpectedFields(val fields: Set<String>) : ProviderIdentityError

    data class InvalidField(val field: String) : ProviderIdentityError

    data object MalformedDocument : ProviderIdentityError
}

enum class ProviderIdentifierProblem {
    Blank,
    Malformed,
    NonCanonical,
}

enum class ProviderAliasProblem {
    Blank,
    TooLong,
    ControlCharacter,
    StableAddress,
}

/** Strict protected-storage codec. Diagnostics must use [redacted] instead. */
object ProviderIdentityCodec {
    private const val schemaVersion = "1.0"
    private val expectedFields = setOf(
        "schemaVersion",
        "definitionId",
        "instanceId",
        "family",
        "displayAlias",
    )
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(identity: ProviderIdentity): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(schemaVersion))
            put("definitionId", JsonPrimitive(identity.definitionId.value))
            put("instanceId", JsonPrimitive(identity.instanceId.value))
            put("family", JsonPrimitive(identity.family.wireValue))
            put("displayAlias", JsonPrimitive(identity.displayAlias.value))
        },
    )

    fun decode(text: String): ProviderIdentityResult<ProviderIdentity> {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return ProviderIdentityResult.Failure(ProviderIdentityError.MalformedDocument)
        val missing = expectedFields - root.keys
        if (missing.isNotEmpty()) {
            return ProviderIdentityResult.Failure(
                ProviderIdentityError.MissingField(missing.sorted().first()),
            )
        }
        val unexpected = root.keys - expectedFields
        if (unexpected.isNotEmpty()) {
            return ProviderIdentityResult.Failure(
                ProviderIdentityError.UnexpectedFields(unexpected.toSortedSet()),
            )
        }

        val version = root.stringField("schemaVersion")
            ?: return ProviderIdentityResult.Failure(
                ProviderIdentityError.InvalidField("schemaVersion"),
            )
        if (version != schemaVersion) {
            return ProviderIdentityResult.Failure(
                ProviderIdentityError.UnsupportedSchemaVersion(version),
            )
        }

        val definition = when (
            val result = root.stringField("definitionId")?.let(ProviderDefinitionId::parse)
        ) {
            is ProviderIdentityResult.Success -> result.value
            is ProviderIdentityResult.Failure -> return result
            null -> return ProviderIdentityResult.Failure(
                ProviderIdentityError.InvalidField("definitionId"),
            )
        }
        val instance = when (
            val result = root.stringField("instanceId")?.let(ProviderInstanceId::parse)
        ) {
            is ProviderIdentityResult.Success -> result.value
            is ProviderIdentityResult.Failure -> return result
            null -> return ProviderIdentityResult.Failure(
                ProviderIdentityError.InvalidField("instanceId"),
            )
        }
        val familyValue = root.stringField("family")
            ?: return ProviderIdentityResult.Failure(ProviderIdentityError.InvalidField("family"))
        val family = ProviderFamily.fromWireValue(familyValue)
            ?: return ProviderIdentityResult.Failure(ProviderIdentityError.InvalidField("family"))
        val alias = when (
            val result = root.stringField("displayAlias")?.let(RedactedProviderAlias::parse)
        ) {
            is ProviderIdentityResult.Success -> result.value
            is ProviderIdentityResult.Failure -> return result
            null -> return ProviderIdentityResult.Failure(
                ProviderIdentityError.InvalidField("displayAlias"),
            )
        }

        return ProviderIdentityResult.Success(
            ProviderIdentity(
                definitionId = definition,
                instanceId = instance,
                family = family,
                displayAlias = alias,
            ),
        )
    }

    private fun JsonObject.stringField(field: String): String? {
        val value = get(field) as? JsonPrimitive ?: return null
        return value.content.takeIf { value.isString }
    }
}
