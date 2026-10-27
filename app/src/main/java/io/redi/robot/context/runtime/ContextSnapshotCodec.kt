package io.redi.robot.context.runtime

import io.redi.robot.context.api.ContextObservationClaim
import io.redi.robot.context.api.ContextValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** Canonical ephemeral snapshot projection. It is not a persistence or memory codec. */
object ContextSnapshotCodec {
    private val json = Json { encodeDefaults = true }

    fun encode(snapshot: ContextSnapshot): String = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(ContextSnapshot.SCHEMA_VERSION))
            put("asOfMonotonicMs", JsonPrimitive(snapshot.asOf.value))
            put("selection", encodeSelection(snapshot.keySelection))
            put(
                "allowedPrivacyClassifications",
                buildJsonArray {
                    snapshot.allowedPrivacyClassifications.sortedBy { it.wireValue }.forEach {
                        add(JsonPrimitive(it.wireValue))
                    }
                },
            )
            put(
                "budget",
                buildJsonObject {
                    put("maximumItems", JsonPrimitive(snapshot.appliedBudget.maximumItems))
                    put(
                        "maximumEvidencePerItem",
                        JsonPrimitive(snapshot.appliedBudget.maximumEvidencePerItem),
                    )
                    put("maximumTotalEvidence", JsonPrimitive(snapshot.appliedBudget.maximumTotalEvidence))
                    put("maximumUtf8Bytes", JsonPrimitive(snapshot.appliedBudget.maximumUtf8Bytes))
                },
            )
            put("inputObservationCount", JsonPrimitive(snapshot.inputObservationCount))
            put("retainedEvidenceCount", JsonPrimitive(snapshot.retainedEvidenceCount))
            put(
                "items",
                buildJsonArray {
                    snapshot.items.forEach { item -> add(encodeItem(item)) }
                },
            )
            put(
                "omissions",
                buildJsonArray {
                    snapshot.omissions.forEach { omission ->
                        add(
                            buildJsonObject {
                                put("reason", JsonPrimitive(omission.reason.wireValue))
                                put("count", JsonPrimitive(omission.count))
                            },
                        )
                    }
                },
            )
        },
    )

    private fun encodeSelection(selection: ContextKeySelection) = buildJsonObject {
        when (selection) {
            ContextKeySelection.All -> put("kind", JsonPrimitive("all"))
            is ContextKeySelection.Exact -> {
                put("kind", JsonPrimitive("exact"))
                put("keys", buildJsonArray { selection.keys.forEach { add(JsonPrimitive(it.value)) } })
            }
        }
    }

    private fun encodeItem(item: ContextSnapshotItem) = buildJsonObject {
        put("key", JsonPrimitive(item.key.value))
        put("resolution", encodeResolution(item.resolution))
        put("effectivePrivacyClassification", JsonPrimitive(item.effectivePrivacyClassification.wireValue))
        put(
            "evidence",
            buildJsonArray {
                item.evidence.forEach { evidence ->
                    add(
                        buildJsonObject {
                            put("observationId", JsonPrimitive(evidence.observationId.value))
                            put("sourceType", JsonPrimitive(evidence.sourceType.value))
                            put("sourceReference", JsonPrimitive(evidence.sourceReference.value))
                            put("claim", encodeClaim(evidence.claim))
                            put("confidencePermille", JsonPrimitive(evidence.confidence.permille))
                            put("privacyClassification", JsonPrimitive(evidence.privacyClassification.wireValue))
                            put("observedAtMonotonicMs", JsonPrimitive(evidence.observedAt.value))
                            put("expiresAtMonotonicMs", JsonPrimitive(evidence.expiresAt.value))
                        },
                    )
                }
            },
        )
    }

    private fun encodeResolution(resolution: ContextSnapshotResolution) = buildJsonObject {
        when (resolution) {
            is ContextSnapshotResolution.Known -> {
                put("status", JsonPrimitive("known"))
                put("value", encodeValue(resolution.value))
            }
            is ContextSnapshotResolution.Unknown -> {
                put("status", JsonPrimitive("unknown"))
                put(
                    "reasons",
                    buildJsonArray { resolution.reasons.forEach { add(JsonPrimitive(it.wireValue)) } },
                )
            }
            is ContextSnapshotResolution.Conflict -> {
                put("status", JsonPrimitive("conflict"))
                put(
                    "candidates",
                    buildJsonArray { resolution.candidates.forEach { add(encodeValue(it)) } },
                )
            }
        }
    }

    private fun encodeClaim(claim: ContextObservationClaim) = buildJsonObject {
        when (claim) {
            is ContextObservationClaim.Known -> {
                put("status", JsonPrimitive("known"))
                put("value", encodeValue(claim.value))
            }
            is ContextObservationClaim.Unknown -> {
                put("status", JsonPrimitive("unknown"))
                put("reason", JsonPrimitive(claim.reason.wireValue))
            }
            is ContextObservationClaim.Conflict -> {
                put("status", JsonPrimitive("conflict"))
                put("candidates", buildJsonArray { claim.candidates.forEach { add(encodeValue(it)) } })
            }
        }
    }

    private fun encodeValue(value: ContextValue) = buildJsonObject {
        put("type", JsonPrimitive(value.wireType))
        put(
            "value",
            when (value) {
                is ContextValue.BooleanValue -> JsonPrimitive(value.value)
                is ContextValue.IntegerValue -> JsonPrimitive(value.value)
                is ContextValue.DecimalValue -> JsonPrimitive(value.canonicalValue)
                is ContextValue.TextValue -> JsonPrimitive(value.value.value)
                is ContextValue.SymbolValue -> JsonPrimitive(value.value.value)
            },
        )
    }
}
