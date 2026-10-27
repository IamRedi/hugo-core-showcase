package io.redi.robot.hugo.identity

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Persistent logical identity for one Hugo runtime relationship.
 *
 * This value is deliberately not derived from an Android installation, host, body, device,
 * service account, or AI-provider account. Association with those entities belongs to runtime
 * context outside this contract.
 */
data class HugoIdentity(
    val id: HugoIdentityId,
    val contractVersion: HugoIdentityContractVersion = HugoIdentityContractVersion.V1,
)

/** Opaque identity value with no host, body, provider, or installation semantics. */
@JvmInline
value class HugoIdentityId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val canonicalPattern = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
        private val caseInsensitivePattern = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )

        /**
         * Creates the opaque value from caller-provided entropy without selecting persistence or
         * tying identity creation to an installation lifecycle.
         */
        fun fromUuid(uuid: UUID): HugoIdentityId = HugoIdentityId(uuid.toString())

        fun parse(raw: String): HugoIdentityResult<HugoIdentityId> {
            if (raw.isBlank()) {
                return HugoIdentityResult.Failure(HugoIdentityError.BlankIdentityId)
            }
            if (!caseInsensitivePattern.matches(raw)) {
                return HugoIdentityResult.Failure(
                    HugoIdentityError.InvalidIdentityId(IdentityIdProblem.Malformed),
                )
            }
            if (!canonicalPattern.matches(raw)) {
                return HugoIdentityResult.Failure(
                    HugoIdentityError.InvalidIdentityId(IdentityIdProblem.NonCanonical),
                )
            }
            return HugoIdentityResult.Success(HugoIdentityId(raw))
        }
    }
}

enum class HugoIdentityContractVersion(val wireValue: String) {
    V1("1.0"),
}

sealed interface HugoIdentityResult<out T> {
    data class Success<T>(val value: T) : HugoIdentityResult<T>

    data class Failure(val error: HugoIdentityError) : HugoIdentityResult<Nothing>
}

sealed interface HugoIdentityError {
    data object BlankIdentityId : HugoIdentityError

    data class InvalidIdentityId(val problem: IdentityIdProblem) : HugoIdentityError

    data class UnsupportedContractVersion(val found: String) : HugoIdentityError

    data class MissingField(val field: String) : HugoIdentityError

    data class UnexpectedFields(val fields: Set<String>) : HugoIdentityError

    data class InvalidField(val field: String) : HugoIdentityError

    data class InvalidDocument(val problem: IdentityDocumentProblem) : HugoIdentityError
}

enum class IdentityIdProblem {
    Malformed,
    NonCanonical,
}

enum class IdentityDocumentProblem {
    Blank,
    MalformedJson,
    RootNotObject,
}

/** Strict, deterministic wire codec for the identity value only. */
object HugoIdentityCodec {
    private const val schemaVersionField = "schemaVersion"
    private const val identityIdField = "identityId"
    private val expectedFields = setOf(schemaVersionField, identityIdField)
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(identity: HugoIdentity): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put(schemaVersionField, JsonPrimitive(identity.contractVersion.wireValue))
            put(identityIdField, JsonPrimitive(identity.id.value))
        },
    )

    fun decode(text: String): HugoIdentityResult<HugoIdentity> {
        if (text.isBlank()) {
            return HugoIdentityResult.Failure(
                HugoIdentityError.InvalidDocument(IdentityDocumentProblem.Blank),
            )
        }

        val element = runCatching { Json.parseToJsonElement(text) }
            .getOrElse {
                return HugoIdentityResult.Failure(
                    HugoIdentityError.InvalidDocument(IdentityDocumentProblem.MalformedJson),
                )
            }
        val root = element as? JsonObject
            ?: return HugoIdentityResult.Failure(
                HugoIdentityError.InvalidDocument(IdentityDocumentProblem.RootNotObject),
            )

        val missing = expectedFields - root.keys
        if (missing.isNotEmpty()) {
            return HugoIdentityResult.Failure(HugoIdentityError.MissingField(missing.sorted().first()))
        }
        val unexpected = root.keys - expectedFields
        if (unexpected.isNotEmpty()) {
            return HugoIdentityResult.Failure(
                HugoIdentityError.UnexpectedFields(unexpected.toSortedSet()),
            )
        }

        val version = root.stringField(schemaVersionField)
            ?: return HugoIdentityResult.Failure(
                HugoIdentityError.InvalidField(schemaVersionField),
            )
        if (version != HugoIdentityContractVersion.V1.wireValue) {
            return HugoIdentityResult.Failure(
                HugoIdentityError.UnsupportedContractVersion(version),
            )
        }

        val rawId = root.stringField(identityIdField)
            ?: return HugoIdentityResult.Failure(HugoIdentityError.InvalidField(identityIdField))
        return when (val parsedId = HugoIdentityId.parse(rawId)) {
            is HugoIdentityResult.Success -> HugoIdentityResult.Success(
                HugoIdentity(
                    id = parsedId.value,
                    contractVersion = HugoIdentityContractVersion.V1,
                ),
            )
            is HugoIdentityResult.Failure -> parsedId
        }
    }

    private fun JsonObject.stringField(field: String): String? {
        val value = get(field) as? JsonPrimitive ?: return null
        return value.content.takeIf { value.isString }
    }
}
