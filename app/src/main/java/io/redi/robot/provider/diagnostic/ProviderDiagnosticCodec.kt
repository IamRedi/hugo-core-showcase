package io.redi.robot.provider.diagnostic

import io.redi.robot.adapter.api.AdapterId
import io.redi.robot.adapter.api.AdapterResult
import io.redi.robot.adapter.api.AdapterVersion
import io.redi.robot.adapter.compatibility.CompatibilityCheckStatus
import io.redi.robot.adapter.compatibility.CompatibilityDimension
import io.redi.robot.adapter.compatibility.CompatibilityFindingCode
import io.redi.robot.adapter.compatibility.CompatibilityReportStatus
import io.redi.robot.adapter.registry.AdapterKnowledgeState
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.negotiation.CapabilityNegotiationReason
import io.redi.robot.capability.negotiation.CapabilityNegotiationStatus
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.capability.provider.RedactedProviderAlias
import io.redi.robot.connection.api.ConnectionAvailability
import io.redi.robot.connection.api.ConnectionMethodId
import io.redi.robot.connection.api.ConnectionMethodReference
import io.redi.robot.connection.api.ConnectionMethodVersion
import io.redi.robot.connection.api.ConnectionReadiness
import io.redi.robot.connection.api.ConnectionResult
import io.redi.robot.discovery.identity.IdentityResolutionReason
import io.redi.robot.discovery.identity.IdentityResolutionStatus
import io.redi.robot.manifest.resolution.ManifestResolutionReason
import io.redi.robot.manifest.resolution.ManifestResolutionStatus
import io.redi.robot.provider.calibration.CalibrationDomainResult
import io.redi.robot.provider.calibration.CalibrationItemId
import io.redi.robot.provider.calibration.CalibrationNegotiationReadinessStatus
import io.redi.robot.provider.calibration.CalibrationStatus
import io.redi.robot.provider.runtime.ProviderOrchestrationPhase
import io.redi.robot.provider.runtime.ProviderOrchestrationStopReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

object ProviderDiagnosticCodec {
    private const val MAXIMUM_EXPORT_BYTES = 64 * 1024
    private val json = Json { ignoreUnknownKeys = false }
    private val rootFields = setOf(
        "schemaVersion", "generatedAt", "summary", "lifecycle", "identity", "manifest",
        "compatibility", "adapter", "connection", "negotiation", "blockedState", "calibration", "evidence",
    )

    fun encode(snapshot: ProviderDiagnosticSnapshot): ProviderDiagnosticResult<String> {
        val text = json.encodeToString(JsonElement.serializer(), encodeRoot(snapshot))
        return if (text.toByteArray(Charsets.UTF_8).size <= MAXIMUM_EXPORT_BYTES) {
            ProviderDiagnosticResult.Success(text)
        } else diagnosticFailure(ProviderDiagnosticError.OUTPUT_TOO_LARGE)
    }

    fun decode(text: String): ProviderDiagnosticResult<ProviderDiagnosticSnapshot> {
        if (text.toByteArray(Charsets.UTF_8).size > MAXIMUM_EXPORT_BYTES) {
            return diagnosticFailure(ProviderDiagnosticError.OUTPUT_TOO_LARGE)
        }
        return try {
            val root = Json.parseToJsonElement(text) as? JsonObject
                ?: fail(ProviderDiagnosticError.MALFORMED_DOCUMENT)
            exact(root, rootFields)
            val schema = value(ProviderDiagnosticSchemaVersion.parse(root.string("schemaVersion")))
            ProviderDiagnosticSnapshot.create(
                schemaVersion = schema,
                generatedAt = root.long("generatedAt"),
                summary = decodeSummary(root.obj("summary")),
                lifecycle = root.optionalObj("lifecycle")?.let(::decodeLifecycle),
                identity = root.optionalObj("identity")?.let(::decodeIdentity),
                manifest = root.optionalObj("manifest")?.let(::decodeManifest),
                compatibility = root.optionalObj("compatibility")?.let(::decodeCompatibility),
                adapter = root.optionalObj("adapter")?.let(::decodeAdapter),
                connection = root.optionalObj("connection")?.let(::decodeConnection),
                negotiation = root.optionalObj("negotiation")?.let(::decodeNegotiation),
                blockedState = root.optionalObj("blockedState")?.let(::decodeBlocked),
                calibration = root.optionalObj("calibration")?.let(::decodeCalibration),
                evidence = root.array("evidence").map { decodeEvidence(it.asObject("evidence")) },
            )
        } catch (failure: DecodeFailure) {
            diagnosticFailure(failure.error)
        } catch (_: Exception) {
            diagnosticFailure(ProviderDiagnosticError.MALFORMED_DOCUMENT)
        }
    }

    private fun encodeRoot(value: ProviderDiagnosticSnapshot) = buildJsonObject {
        put("schemaVersion", string(value.schemaVersion.wireValue))
        put("generatedAt", number(value.generatedAt))
        put("summary", encodeSummary(value.summary))
        put("lifecycle", value.lifecycle?.let(::encodeLifecycle) ?: JsonNull)
        put("identity", value.identity?.let(::encodeIdentity) ?: JsonNull)
        put("manifest", value.manifest?.let(::encodeManifest) ?: JsonNull)
        put("compatibility", value.compatibility?.let(::encodeCompatibility) ?: JsonNull)
        put("adapter", value.adapter?.let(::encodeAdapter) ?: JsonNull)
        put("connection", value.connection?.let(::encodeConnection) ?: JsonNull)
        put("negotiation", value.negotiation?.let(::encodeNegotiation) ?: JsonNull)
        put("blockedState", value.blockedState?.let(::encodeBlocked) ?: JsonNull)
        put("calibration", value.calibration?.let(::encodeCalibration) ?: JsonNull)
        put("evidence", buildJsonArray { value.evidence.forEach { add(encodeEvidence(it)) } })
    }

    private fun encodeSummary(value: ProviderSummaryDiagnostic) = buildJsonObject {
        put("diagnosticId", string(value.diagnosticId.value))
        put("definitionId", value.definitionId?.let { string(it.value) } ?: JsonNull)
        put("family", value.family?.let { string(it.name) } ?: JsonNull)
        put("alias", value.alias?.let { string(it.value) } ?: JsonNull)
        put("identityKnown", boolean(value.identityKnown))
    }

    private fun decodeSummary(root: JsonObject): ProviderSummaryDiagnostic {
        exact(root, setOf("diagnosticId", "definitionId", "family", "alias", "identityKnown"))
        return ProviderSummaryDiagnostic(
            value(ProviderDiagnosticId.parse(root.string("diagnosticId"))),
            root.optionalString("definitionId")?.let { providerValue(ProviderDefinitionId.parse(it)) },
            root.optionalString("family")?.let { enum<ProviderFamily>(it, "summary.family") },
            root.optionalString("alias")?.let { providerValue(RedactedProviderAlias.parse(it)) },
            root.boolean("identityKnown"),
        )
    }

    private fun encodeLifecycle(value: LifecycleDiagnostic) = buildJsonObject {
        put("orchestrationPhase", string(value.orchestrationPhase.name))
        put("stopReason", value.stopReason?.let { string(it.name) } ?: JsonNull)
        put("lifecycleState", string(value.lifecycleState.name))
        put("generation", value.generation?.let(::number) ?: JsonNull)
    }

    private fun decodeLifecycle(root: JsonObject): LifecycleDiagnostic {
        exact(root, setOf("orchestrationPhase", "stopReason", "lifecycleState", "generation"))
        return LifecycleDiagnostic(
            enum(root.string("orchestrationPhase"), "lifecycle.orchestrationPhase"),
            root.optionalString("stopReason")?.let { enum<ProviderOrchestrationStopReason>(it, "lifecycle.stopReason") },
            enum(root.string("lifecycleState"), "lifecycle.lifecycleState"),
            root.optionalLong("generation"),
        )
    }

    private fun encodeIdentity(value: IdentityDiagnostic) = buildJsonObject {
        put("status", string(value.status.name))
        put("reason", string(value.reason.name))
        put("candidateCount", number(value.candidateCount.toLong()))
        put("evidenceReferences", ids(value.evidenceReferences))
        put("freshness", string(value.freshness.name))
    }

    private fun decodeIdentity(root: JsonObject): IdentityDiagnostic {
        exact(root, setOf("status", "reason", "candidateCount", "evidenceReferences", "freshness"))
        return IdentityDiagnostic(
            enum(root.string("status"), "identity.status"),
            enum(root.string("reason"), "identity.reason"),
            root.int("candidateCount"),
            root.array("evidenceReferences").map { diagnosticId(it.asString("identity.evidenceReferences")) },
            enum(root.string("freshness"), "identity.freshness"),
        )
    }

    private fun encodeManifest(value: ManifestDiagnostic) = buildJsonObject {
        put("status", string(value.status.name))
        put("reason", string(value.reason.name))
        put("candidateReferences", ids(value.candidateReferences))
    }

    private fun decodeManifest(root: JsonObject): ManifestDiagnostic {
        exact(root, setOf("status", "reason", "candidateReferences"))
        return ManifestDiagnostic(
            enum(root.string("status"), "manifest.status"),
            enum(root.string("reason"), "manifest.reason"),
            root.array("candidateReferences").map { diagnosticId(it.asString("manifest.candidateReferences")) },
        )
    }

    private fun encodeCompatibility(value: CompatibilityDiagnostic) = buildJsonObject {
        put("status", string(value.status.name))
        put("checks", buildJsonArray { value.checks.forEach { add(encodeCheck(it)) } })
    }

    private fun encodeCheck(value: CompatibilityCheckDiagnostic) = buildJsonObject {
        put("dimension", string(value.dimension.name))
        put("checkId", string(value.checkId.value))
        put("status", string(value.status.name))
        put("finding", string(value.finding.name))
        put("requiredValues", safeValues(value.requiredValues))
        put("observedValues", safeValues(value.observedValues))
        put("evidenceReferences", ids(value.evidenceReferences))
    }

    private fun decodeCompatibility(root: JsonObject): CompatibilityDiagnostic {
        exact(root, setOf("status", "checks"))
        return CompatibilityDiagnostic(
            enum(root.string("status"), "compatibility.status"),
            root.array("checks").map { decodeCheck(it.asObject("compatibility.check")) },
        )
    }

    private fun decodeCheck(root: JsonObject): CompatibilityCheckDiagnostic {
        exact(root, setOf("dimension", "checkId", "status", "finding", "requiredValues", "observedValues", "evidenceReferences"))
        return CompatibilityCheckDiagnostic(
            enum(root.string("dimension"), "compatibility.dimension"),
            safeValue(root.string("checkId")),
            enum(root.string("status"), "compatibility.check.status"),
            enum(root.string("finding"), "compatibility.finding"),
            root.array("requiredValues").map { safeValue(it.asString("compatibility.requiredValues")) },
            root.array("observedValues").map { safeValue(it.asString("compatibility.observedValues")) },
            root.array("evidenceReferences").map { diagnosticId(it.asString("compatibility.evidenceReferences")) },
        )
    }

    private fun encodeAdapter(value: AdapterDiagnostic) = buildJsonObject {
        put("known", boolean(value.known))
        put("knowledgeState", value.knowledgeState?.let { string(it.name) } ?: JsonNull)
        put("adapterId", value.adapterId?.let { string(it.wireValue) } ?: JsonNull)
        put("adapterVersion", value.adapterVersion?.let { string(it.wireValue) } ?: JsonNull)
        put("descriptorReference", value.descriptorReference?.let { string(it.value) } ?: JsonNull)
    }

    private fun decodeAdapter(root: JsonObject): AdapterDiagnostic {
        exact(root, setOf("known", "knowledgeState", "adapterId", "adapterVersion", "descriptorReference"))
        return AdapterDiagnostic(
            root.boolean("known"),
            root.optionalString("knowledgeState")?.let { enum<AdapterKnowledgeState>(it, "adapter.knowledgeState") },
            root.optionalString("adapterId")?.let { adapterValue(AdapterId.parse(it)) },
            root.optionalString("adapterVersion")?.let { adapterValue(AdapterVersion.parse(it)) },
            root.optionalString("descriptorReference")?.let(::diagnosticId),
        )
    }

    private fun encodeConnection(value: ConnectionDiagnostic) = buildJsonObject {
        put("availability", value.availability?.let { string(it.name) } ?: JsonNull)
        put("readiness", string(value.readiness.name))
        put("method", value.method?.let { string("${it.id.value}@${it.version.value}") } ?: JsonNull)
        put("sessionReference", value.sessionReference?.let { string(it.value) } ?: JsonNull)
        put("freshness", string(value.freshness.name))
    }

    private fun decodeConnection(root: JsonObject): ConnectionDiagnostic {
        exact(root, setOf("availability", "readiness", "method", "sessionReference", "freshness"))
        return ConnectionDiagnostic(
            root.optionalString("availability")?.let { enum<ConnectionAvailability>(it, "connection.availability") },
            enum(root.string("readiness"), "connection.readiness"),
            root.optionalString("method")?.let(::connectionMethod),
            root.optionalString("sessionReference")?.let(::diagnosticId),
            enum(root.string("freshness"), "connection.freshness"),
        )
    }

    private fun encodeNegotiation(value: NegotiatedCapabilitiesDiagnostic) = buildJsonObject {
        put("status", string(value.status.name))
        put("reason", string(value.reason.name))
        put("capabilities", strings(value.capabilities.map { it.wireValue }))
        put("negotiationReference", string(value.negotiationReference.value))
        put("freshness", string(value.freshness.name))
    }

    private fun decodeNegotiation(root: JsonObject): NegotiatedCapabilitiesDiagnostic {
        exact(root, setOf("status", "reason", "capabilities", "negotiationReference", "freshness"))
        return NegotiatedCapabilitiesDiagnostic(
            enum(root.string("status"), "negotiation.status"),
            enum(root.string("reason"), "negotiation.reason"),
            root.array("capabilities").map { capability(it.asString("negotiation.capability")) },
            diagnosticId(root.string("negotiationReference")),
            enum(root.string("freshness"), "negotiation.freshness"),
        )
    }

    private fun encodeBlocked(value: BlockedStateDiagnostic) = buildJsonObject {
        put("kind", string(value.kind.value))
        put("resolutionAvailability", string(value.resolutionAvailability.value))
    }

    private fun decodeBlocked(root: JsonObject): BlockedStateDiagnostic {
        exact(root, setOf("kind", "resolutionAvailability"))
        return BlockedStateDiagnostic(
            safeValue(root.string("kind")),
            safeValue(root.string("resolutionAvailability")),
        )
    }

    private fun encodeCalibration(value: CalibrationReadinessDiagnostic) = buildJsonObject {
        put("readiness", string(value.readiness.name))
        put("status", value.status?.let { string(it.name) } ?: JsonNull)
        put("profileReference", value.profileReference?.let { string(it.value) } ?: JsonNull)
        put("unmetItems", strings(value.unmetItems.map { it.value }))
    }

    private fun decodeCalibration(root: JsonObject): CalibrationReadinessDiagnostic {
        exact(root, setOf("readiness", "status", "profileReference", "unmetItems"))
        return CalibrationReadinessDiagnostic(
            enum(root.string("readiness"), "calibration.readiness"),
            root.optionalString("status")?.let { enum<CalibrationStatus>(it, "calibration.status") },
            root.optionalString("profileReference")?.let(::diagnosticId),
            root.array("unmetItems").map { calibrationItem(it.asString("calibration.unmetItem")) },
        )
    }

    private fun encodeEvidence(value: ProviderDiagnosticEvidence) = buildJsonObject {
        put("reference", string(value.reference.value))
        put("source", string(value.source.name))
        put("provenance", string(value.provenance.value))
        put("observedAt", value.observedAt?.let(::number) ?: JsonNull)
        put("validUntil", value.validUntil?.let(::number) ?: JsonNull)
        put("freshness", string(value.freshness.name))
    }

    private fun decodeEvidence(root: JsonObject): ProviderDiagnosticEvidence {
        exact(root, setOf("reference", "source", "provenance", "observedAt", "validUntil", "freshness"))
        return ProviderDiagnosticEvidence(
            diagnosticId(root.string("reference")),
            enum(root.string("source"), "evidence.source"),
            safeValue(root.string("provenance")),
            root.optionalLong("observedAt"),
            root.optionalLong("validUntil"),
            enum(root.string("freshness"), "evidence.freshness"),
        )
    }

    private fun exact(root: JsonObject, expected: Set<String>) {
        val missing = expected - root.keys
        if (missing.isNotEmpty()) fail(ProviderDiagnosticError.MISSING_FIELD(missing.sorted().first()))
        val unexpected = root.keys - expected
        if (unexpected.isNotEmpty()) fail(ProviderDiagnosticError.UNEXPECTED_FIELDS(unexpected.toSortedSet()))
    }

    private fun diagnosticId(raw: String) = value(ProviderDiagnosticId.parse(raw))
    private fun safeValue(raw: String) = value(DiagnosticSafeValue.parse(raw))
    private fun capability(raw: String): CapabilityReference = when (val result = CapabilityReference.parse(raw)) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> fail(ProviderDiagnosticError.INVALID_FIELD("negotiation.capability"))
    }
    private fun calibrationItem(raw: String): CalibrationItemId = when (val result = CalibrationItemId.parse(raw)) {
        is CalibrationDomainResult.Success -> result.value
        is CalibrationDomainResult.Failure -> fail(ProviderDiagnosticError.INVALID_FIELD("calibration.unmetItem"))
    }
    private fun connectionMethod(raw: String): ConnectionMethodReference {
        if (raw.count { it == '@' } != 1) fail(ProviderDiagnosticError.INVALID_FIELD("connection.method"))
        val (id, version) = raw.split('@', limit = 2)
        return ConnectionMethodReference(connectionValue(ConnectionMethodId.parse(id)), connectionValue(ConnectionMethodVersion.parse(version)))
    }
    private fun <T> value(result: ProviderDiagnosticResult<T>): T = when (result) {
        is ProviderDiagnosticResult.Success -> result.value
        is ProviderDiagnosticResult.Failure -> fail(result.error)
    }
    private fun <T> providerValue(result: ProviderIdentityResult<T>): T = when (result) {
        is ProviderIdentityResult.Success -> result.value
        is ProviderIdentityResult.Failure -> fail(ProviderDiagnosticError.INVALID_FIELD("summary"))
    }
    private fun <T> adapterValue(result: AdapterResult<T>): T = when (result) {
        is AdapterResult.Success -> result.value
        is AdapterResult.Failure -> fail(ProviderDiagnosticError.INVALID_FIELD("adapter"))
    }
    private fun <T> connectionValue(result: ConnectionResult<T>): T = when (result) {
        is ConnectionResult.Success -> result.value
        is ConnectionResult.Failure -> fail(ProviderDiagnosticError.INVALID_FIELD("connection.method"))
    }
    private inline fun <reified T : Enum<T>> enum(raw: String, field: String): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fail(ProviderDiagnosticError.INVALID_FIELD(field))
    private fun fail(error: ProviderDiagnosticError): Nothing = throw DecodeFailure(error)
    private class DecodeFailure(val error: ProviderDiagnosticError) : RuntimeException()
}

private fun string(value: String) = JsonPrimitive(value)
private fun number(value: Long) = JsonPrimitive(value)
private fun boolean(value: Boolean) = JsonPrimitive(value)
private fun strings(values: List<String>) = buildJsonArray { values.forEach { add(string(it)) } }
private fun ids(values: List<ProviderDiagnosticId>) = strings(values.map { it.value })
private fun safeValues(values: List<DiagnosticSafeValue>) = strings(values.map { it.value })

private fun JsonObject.string(name: String): String = (get(name) as? JsonPrimitive)
    ?.takeIf { it.isString }?.content ?: throw IllegalArgumentException(name)
private fun JsonObject.optionalString(name: String): String? = when (val value = get(name)) {
    JsonNull -> null
    is JsonPrimitive -> value.takeIf { it.isString }?.content ?: throw IllegalArgumentException(name)
    else -> throw IllegalArgumentException(name)
}
private fun JsonObject.long(name: String): Long = (get(name) as? JsonPrimitive)?.content?.toLongOrNull()
    ?: throw IllegalArgumentException(name)
private fun JsonObject.int(name: String): Int = (get(name) as? JsonPrimitive)?.content?.toIntOrNull()
    ?: throw IllegalArgumentException(name)
private fun JsonObject.boolean(name: String): Boolean = (get(name) as? JsonPrimitive)?.content?.let {
    when (it) { "true" -> true; "false" -> false; else -> null }
} ?: throw IllegalArgumentException(name)
private fun JsonObject.optionalLong(name: String): Long? = when (val value = get(name)) {
    JsonNull -> null
    is JsonPrimitive -> value.content.toLongOrNull() ?: throw IllegalArgumentException(name)
    else -> throw IllegalArgumentException(name)
}
private fun JsonObject.obj(name: String): JsonObject = get(name) as? JsonObject ?: throw IllegalArgumentException(name)
private fun JsonObject.optionalObj(name: String): JsonObject? = when (val value = get(name)) {
    JsonNull -> null
    is JsonObject -> value
    else -> throw IllegalArgumentException(name)
}
private fun JsonObject.array(name: String): JsonArray = get(name) as? JsonArray ?: throw IllegalArgumentException(name)
private fun JsonElement.asString(field: String): String = (this as? JsonPrimitive)
    ?.takeIf { it.isString }?.content ?: throw IllegalArgumentException(field)
private fun JsonElement.asObject(field: String): JsonObject = this as? JsonObject ?: throw IllegalArgumentException(field)
