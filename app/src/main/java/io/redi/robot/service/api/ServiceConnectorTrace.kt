package io.redi.robot.service.api

import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.capability.provider.ProviderIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Redacted fake/diagnostic trace: no instance, auth-session, account, scope name, token, or payload. */
data class ServiceConnectorTrace(
    val providerDefinitionId: ProviderDefinitionId,
    val providerFamily: ProviderFamily,
    val capability: String,
    val status: ServiceOperationAssessmentStatus,
    val checkedDimensions: List<ServiceOperationCheckDimension>,
    val findings: List<ServiceOperationFinding>,
    val matchedScopeCount: Int,
    val missingScopeCount: Int,
    val remainingRateUnits: Int?,
    val retryAtMs: Long?,
) {
    companion object {
        const val SCHEMA_VERSION: String = "1.0"

        fun from(
            identity: ProviderIdentity,
            assessment: ServiceOperationAssessment,
        ): ServiceConnectorTrace = ServiceConnectorTrace(
            providerDefinitionId = identity.definitionId,
            providerFamily = identity.family,
            capability = assessment.capability.wireValue,
            status = assessment.status,
            checkedDimensions = assessment.checkedDimensions.sortedBy { it.name },
            findings = assessment.findings.sortedBy { it.name },
            matchedScopeCount = assessment.matchedScopes.size,
            missingScopeCount = assessment.missingScopes.size,
            remainingRateUnits = assessment.remainingRateUnits,
            retryAtMs = assessment.retryAt?.value,
        )
    }
}

object ServiceConnectorTraceCodec {
    fun encode(trace: ServiceConnectorTrace): String = Json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(ServiceConnectorTrace.SCHEMA_VERSION))
            put("providerDefinitionId", JsonPrimitive(trace.providerDefinitionId.value))
            put("providerFamily", JsonPrimitive(trace.providerFamily.wireValue))
            put("capability", JsonPrimitive(trace.capability))
            put("status", JsonPrimitive(trace.status.name.lowercase()))
            put(
                "checkedDimensions",
                JsonArray(trace.checkedDimensions.map { JsonPrimitive(it.name.lowercase()) }),
            )
            put("findings", JsonArray(trace.findings.map { JsonPrimitive(it.name.lowercase()) }))
            put("matchedScopeCount", JsonPrimitive(trace.matchedScopeCount))
            put("missingScopeCount", JsonPrimitive(trace.missingScopeCount))
            put(
                "remainingRateUnits",
                trace.remainingRateUnits?.let(::JsonPrimitive) ?: JsonNull,
            )
            put("retryAtMs", trace.retryAtMs?.let(::JsonPrimitive) ?: JsonNull)
        },
    )
}
