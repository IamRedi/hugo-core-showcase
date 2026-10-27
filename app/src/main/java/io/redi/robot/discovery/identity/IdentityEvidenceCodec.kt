package io.redi.robot.discovery.identity

import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.discovery.api.DiscoveryConfidence
import io.redi.robot.discovery.api.DiscoveryHostReference
import io.redi.robot.discovery.api.DiscoveryObservationId
import io.redi.robot.discovery.api.DiscoveryObservationResult
import io.redi.robot.discovery.api.DiscoverySourceType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Strict `1.0` protected-storage codec. Diagnostics must use [IdentityEvidence.redacted]. */
object IdentityEvidenceCodec {
    private const val schemaVersion = "1.0"
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(evidence: IdentityEvidence): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(schemaVersion))
            put("evidenceId", JsonPrimitive(evidence.evidenceId.value))
            put(
                "observationIds",
                buildJsonArray {
                    evidence.observationIds.sortedBy { it.value }.forEach { add(JsonPrimitive(it.value)) }
                },
            )
            put(
                "provenance",
                buildJsonObject {
                    put("observingHostReference", JsonPrimitive(evidence.provenance.observingHost.value))
                    put(
                        "sourceTypes",
                        buildJsonArray {
                            evidence.provenance.sourceTypes.sortedBy { it.value }.forEach {
                                add(JsonPrimitive(it.value))
                            }
                        },
                    )
                    put("producer", JsonPrimitive(evidence.provenance.producer.value))
                },
            )
            put("evidenceType", JsonPrimitive(evidence.evidenceType.wireValue))
            put("strength", JsonPrimitive(evidence.strength.wireValue))
            put("confidence", JsonPrimitive(evidence.confidence.value))
            put("verificationLevel", JsonPrimitive(evidence.verificationLevel.wireValue))
            put(
                "protectedValueReference",
                evidence.protectedValueReference?.let { JsonPrimitive(it.value) } ?: JsonNull,
            )
            put("disposition", JsonPrimitive(evidence.disposition.wireValue))
            put(
                "target",
                buildJsonObject {
                    put(
                        "definitionId",
                        evidence.target.definitionId?.let { JsonPrimitive(it.value) } ?: JsonNull,
                    )
                    put(
                        "instanceId",
                        evidence.target.instanceId?.let { JsonPrimitive(it.value) } ?: JsonNull,
                    )
                    put(
                        "family",
                        evidence.target.family?.let { JsonPrimitive(it.wireValue) } ?: JsonNull,
                    )
                },
            )
            put(
                "contradictions",
                buildJsonArray {
                    evidence.contradictions.sortedBy { it.value }.forEach { add(JsonPrimitive(it.value)) }
                },
            )
            put("observedAtMonotonicMs", JsonPrimitive(evidence.observedAt.value))
            put("expiresAtMonotonicMs", JsonPrimitive(evidence.expiresAt.value))
        },
    )

    fun decode(text: String): IdentityEvidenceResult<IdentityEvidence> = decode {
        val root = parseRoot(text)
        root.requireExactKeys(
            "evidence",
            "schemaVersion",
            "evidenceId",
            "observationIds",
            "provenance",
            "evidenceType",
            "strength",
            "confidence",
            "verificationLevel",
            "protectedValueReference",
            "disposition",
            "target",
            "contradictions",
            "observedAtMonotonicMs",
            "expiresAtMonotonicMs",
        )
        val foundVersion = root.string("schemaVersion")
        if (foundVersion != schemaVersion) {
            throw EvidenceParseException(IdentityEvidenceError.UnsupportedSchemaVersion(foundVersion))
        }
        val evidenceType = IdentityEvidenceType.fromWireValue(root.string("evidenceType"))
            ?: fail("evidenceType")
        val strength = IdentityEvidenceStrength.entries.firstOrNull {
            it.wireValue == root.string("strength")
        } ?: fail("strength")
        if (strength != evidenceType.strength) fail("strength")

        val provenanceRoot = root.obj("provenance").also {
            it.requireExactKeys("provenance", "observingHostReference", "sourceTypes", "producer")
        }
        val targetRoot = root.obj("target").also {
            it.requireExactKeys("target", "definitionId", "instanceId", "family")
        }
        val observationIds = root.array("observationIds").mapIndexed { index, element ->
            unwrapObservation(DiscoveryObservationId.parse(element.string("observationIds[$index]")))
        }.also { if (it.distinct().size != it.size) fail("observationIds") }
        val sourceTypes = provenanceRoot.array("sourceTypes").mapIndexed { index, element ->
            unwrapObservation(
                DiscoverySourceType.parse(element.string("provenance.sourceTypes[$index]")),
            )
        }.also { if (it.distinct().size != it.size) fail("provenance.sourceTypes") }
        val contradictions = root.array("contradictions").mapIndexed { index, element ->
            unwrap(IdentityEvidenceId.parse(element.string("contradictions[$index]")))
        }.also { if (it.distinct().size != it.size) fail("contradictions") }
        unwrap(
            IdentityEvidence.create(
                evidenceId = unwrap(IdentityEvidenceId.parse(root.string("evidenceId"))),
                observationIds = observationIds.toSet(),
                provenance = unwrap(
                    IdentityEvidenceProvenance.create(
                        observingHost = unwrapObservation(
                            DiscoveryHostReference.parse(provenanceRoot.string("observingHostReference")),
                        ),
                        sourceTypes = sourceTypes.toSet(),
                        producer = unwrap(IdentityEvidenceProducer.parse(provenanceRoot.string("producer"))),
                    ),
                ),
                evidenceType = evidenceType,
                confidence = unwrapObservation(DiscoveryConfidence.create(root.double("confidence"))),
                verificationLevel = IdentityVerificationLevel.fromWireValue(root.string("verificationLevel"))
                    ?: fail("verificationLevel"),
                protectedValueReference = root.nullableString("protectedValueReference")?.let {
                    unwrap(ProtectedIdentityEvidenceReference.parse(it))
                },
                disposition = IdentityEvidenceDisposition.fromWireValue(root.string("disposition"))
                    ?: fail("disposition"),
                target = unwrap(
                    IdentityEvidenceTarget.create(
                        definitionId = targetRoot.nullableString("definitionId")?.let {
                            unwrapProvider(ProviderDefinitionId.parse(it))
                        },
                        instanceId = targetRoot.nullableString("instanceId")?.let {
                            unwrapProvider(ProviderInstanceId.parse(it))
                        },
                        family = targetRoot.nullableString("family")?.let { raw ->
                            ProviderFamily.fromWireValue(raw) ?: fail("target.family")
                        },
                    ),
                ),
                contradictions = contradictions.toSet(),
                observedAt = monotonic(root.long("observedAtMonotonicMs"), "observedAtMonotonicMs"),
                expiresAt = monotonic(root.long("expiresAtMonotonicMs"), "expiresAtMonotonicMs"),
            ),
        )
    }

    private fun parseRoot(text: String): JsonObject = runCatching {
        Json.parseToJsonElement(text) as? JsonObject
    }.getOrNull() ?: fail("evidence")

    private fun JsonObject.requireExactKeys(path: String, vararg expected: String) {
        val expectedSet = expected.toSet()
        val missing = expectedSet - keys
        if (missing.isNotEmpty()) {
            throw EvidenceParseException(IdentityEvidenceError.MissingField("$path.${missing.sorted().first()}"))
        }
        val unexpected = keys - expectedSet
        if (unexpected.isNotEmpty()) {
            throw EvidenceParseException(IdentityEvidenceError.UnexpectedFields(unexpected.toSortedSet()))
        }
    }

    private fun JsonObject.obj(field: String): JsonObject = get(field) as? JsonObject ?: fail(field)

    private fun JsonObject.array(field: String): JsonArray = get(field) as? JsonArray ?: fail(field)

    private fun JsonObject.string(field: String): String {
        val value = get(field) as? JsonPrimitive ?: fail(field)
        return value.content.takeIf { value.isString } ?: fail(field)
    }

    private fun JsonObject.nullableString(field: String): String? = when (val value = get(field)) {
        JsonNull -> null
        is JsonPrimitive -> value.content.takeIf { value.isString } ?: fail(field)
        else -> fail(field)
    }

    private fun JsonObject.long(field: String): Long {
        val value = get(field) as? JsonPrimitive ?: fail(field)
        return value.longOrNull?.takeIf { !value.isString } ?: fail(field)
    }

    private fun JsonObject.double(field: String): Double {
        val value = get(field) as? JsonPrimitive ?: fail(field)
        return value.doubleOrNull?.takeIf { !value.isString } ?: fail(field)
    }

    private fun JsonElement.string(path: String): String {
        val value = this as? JsonPrimitive ?: fail(path)
        return value.content.takeIf { value.isString } ?: fail(path)
    }

    private fun monotonic(value: Long, field: String): MonotonicTimestampMs = when (
        val result = MonotonicTimestampMs.create(value)
    ) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> fail(field)
    }

    private fun <T> unwrap(result: IdentityEvidenceResult<T>): T = when (result) {
        is IdentityEvidenceResult.Success -> result.value
        is IdentityEvidenceResult.Failure -> throw EvidenceParseException(result.error)
    }

    private fun <T> unwrapObservation(result: DiscoveryObservationResult<T>): T = when (result) {
        is DiscoveryObservationResult.Success -> result.value
        is DiscoveryObservationResult.Failure -> fail("observationValue")
    }

    private fun <T> unwrapProvider(result: ProviderIdentityResult<T>): T = when (result) {
        is ProviderIdentityResult.Success -> result.value
        is ProviderIdentityResult.Failure -> fail("providerIdentityValue")
    }

    private fun <T> decode(block: () -> T): IdentityEvidenceResult<T> = try {
        IdentityEvidenceResult.Success(block())
    } catch (error: EvidenceParseException) {
        IdentityEvidenceResult.Failure(error.error)
    }

    private fun fail(field: String): Nothing = throw EvidenceParseException(
        IdentityEvidenceError.InvalidDocument(field),
    )

    private class EvidenceParseException(val error: IdentityEvidenceError) : RuntimeException()
}
