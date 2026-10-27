package io.redi.robot.adapter.compatibility

import io.redi.robot.adapter.api.AdapterManifestKind
import io.redi.robot.adapter.api.AdapterManifestSupport
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.manifest.domain.BodyManifest
import io.redi.robot.manifest.domain.CommonProviderManifest
import io.redi.robot.manifest.domain.ManifestCompatibilityConstraint
import io.redi.robot.manifest.domain.ManifestCompatibilitySubject
import io.redi.robot.manifest.domain.ManifestExtensionReference
import io.redi.robot.manifest.domain.ManifestVersion
import io.redi.robot.manifest.domain.ManifestVersionRange
import io.redi.robot.manifest.domain.ServiceManifest
import io.redi.robot.manifest.domain.SpecializedManifestKind

/** Pure H024 matrix evaluation. Every conclusion is evidence only, never runtime authority. */
object CompatibilityMatcher {
    fun match(request: CompatibilityRequest): CompatibilityReport {
        val resolved = request.resolvedManifest
        val common = resolved.common.manifest
        val specialized = resolved.specialized.manifest
        val manifestEvidence = CompatibilityEvidence(
            CompatibilityEvidenceSource.RESOLVED_MANIFEST,
            CompatibilityEvidenceReference.fromManifest(resolved),
        )
        val checks = mutableListOf<CompatibilityCheck>()

        checks += check(
            dimension = CompatibilityDimension.COMMON_MANIFEST_SCHEMA,
            checkId = "common_manifest_schema",
            required = listOf("major:${CommonProviderManifest.supportedSchemaMajor}"),
            observed = listOf(common.schemaVersion.wireValue),
            evidence = listOf(manifestEvidence),
            matched = common.schemaVersion.major == CommonProviderManifest.supportedSchemaMajor,
            failure = CompatibilityFindingCode.UNSUPPORTED_SCHEMA,
        )
        checks += check(
            dimension = CompatibilityDimension.SPECIALIZED_MANIFEST_SCHEMA,
            checkId = "specialized_manifest_schema",
            required = listOf(
                "kind:${specialized.kind.name}",
                "major:${CommonProviderManifest.supportedSchemaMajor}",
            ),
            observed = listOf("${specialized.kind.name}@${specialized.schemaVersion.wireValue}"),
            evidence = listOf(manifestEvidence),
            matched = specialized.schemaVersion.major == CommonProviderManifest.supportedSchemaMajor &&
                specialized.kind.providerFamily == common.provider.family,
            failure = CompatibilityFindingCode.UNSUPPORTED_SCHEMA,
        )
        checks += check(
            dimension = CompatibilityDimension.PROVIDER_DEFINITION,
            checkId = "provider_definition",
            required = listOf(resolved.identityBinding.definitionId.value),
            observed = listOf(common.provider.definitionId.value),
            evidence = listOf(manifestEvidence),
            matched = resolved.identityBinding.definitionId == common.provider.definitionId,
            failure = CompatibilityFindingCode.IDENTITY_MISMATCH,
        )
        val requiredFamily = resolved.identityBinding.family ?: common.provider.family
        checks += check(
            dimension = CompatibilityDimension.PROVIDER_FAMILY,
            checkId = "provider_family",
            required = listOf(requiredFamily.wireValue),
            observed = listOf(common.provider.family.wireValue, specialized.kind.providerFamily.wireValue),
            evidence = listOf(manifestEvidence),
            matched = requiredFamily == common.provider.family &&
                specialized.kind.providerFamily == common.provider.family,
            failure = CompatibilityFindingCode.PROVIDER_FAMILY_MISMATCH,
        )

        val adapter = request.adapter
        if (adapter == null) {
            checks += missingAdapterChecks(
                common.capabilities,
                common.adapter.adapterId.value,
                manifestEvidence,
                request.hugoRuntime,
            )
        } else {
            val adapterEvidence = CompatibilityEvidence(
                CompatibilityEvidenceSource.ADAPTER_DESCRIPTOR,
                adapter.evidenceReference,
            )
            checks += adapterChecks(request, manifestEvidence, adapterEvidence)
        }
        checks += connectionChecks(request, manifestEvidence)
        checks += extensionChecks(request, manifestEvidence)
        checks += declaredConstraintChecks(request, manifestEvidence)

        return CompatibilityReport(
            status = overallStatus(checks, adapter == null),
            providerDefinitionId = common.provider.definitionId,
            providerFamily = common.provider.family,
            requiredAdapterId = common.adapter.adapterId,
            adapterCandidateId = adapter?.descriptor?.identity?.id,
            checks = checks,
        )
    }

    private fun adapterChecks(
        request: CompatibilityRequest,
        manifestEvidence: CompatibilityEvidence,
        adapterEvidence: CompatibilityEvidence,
    ): List<CompatibilityCheck> {
        val resolved = request.resolvedManifest
        val common = resolved.common.manifest
        val specialized = resolved.specialized.manifest
        val adapter = checkNotNull(request.adapter).descriptor
        val evidence = listOf(manifestEvidence, adapterEvidence)
        val checks = mutableListOf<CompatibilityCheck>()
        checks += check(
            dimension = CompatibilityDimension.ADAPTER_IDENTITY,
            checkId = "adapter_identity",
            required = listOf(common.adapter.adapterId.value),
            observed = listOf(adapter.identity.id.wireValue),
            evidence = evidence,
            matched = common.adapter.adapterId.value == adapter.identity.id.wireValue,
            failure = CompatibilityFindingCode.ADAPTER_IDENTITY_MISMATCH,
        )
        val adapterManifestVersion = parseManifestVersion(adapter.identity.version.wireValue)
        checks += check(
            dimension = CompatibilityDimension.ADAPTER_VERSION,
            checkId = "adapter_version",
            required = listOf(rangeText(common.adapter.versions)),
            observed = listOf(adapter.identity.version.wireValue),
            evidence = evidence,
            matched = adapterManifestVersion != null && common.adapter.versions.supports(adapterManifestVersion),
            failure = CompatibilityFindingCode.UNSUPPORTED_VERSION,
        )
        checks += adapterManifestSupportChecks(
            adapter.manifestSupport,
            common.provider.family,
            common.schemaVersion.major,
            specialized.kind,
            specialized.schemaVersion.major,
            evidence,
        )
        val runtimeEvidence = CompatibilityEvidence(
            CompatibilityEvidenceSource.HUGO_RUNTIME,
            request.hugoRuntime.evidenceReference,
        )
        val runtime = adapter.runtimeCompatibility
        checks += if (runtime == null) {
            CompatibilityCheck(
                dimension = CompatibilityDimension.HUGO_RUNTIME,
                checkId = "hugo_runtime",
                requirement = CompatibilityRequirement.REQUIRED,
                status = CompatibilityCheckStatus.UNKNOWN,
                finding = CompatibilityFindingCode.UNSUPPORTED_VERSION,
                requiredValues = listOf("adapter_declared_runtime_range"),
                observedValues = listOf(request.hugoRuntime.version.wireValue, "range:not_declared"),
                evidence = sortedEvidence(evidence + runtimeEvidence),
            )
        } else {
            check(
                dimension = CompatibilityDimension.HUGO_RUNTIME,
                checkId = "hugo_runtime",
                required = listOf(runtimeRangeText(runtime)),
                observed = listOf(request.hugoRuntime.version.wireValue),
                evidence = evidence + runtimeEvidence,
                matched = runtime.supports(request.hugoRuntime.version),
                failure = CompatibilityFindingCode.UNSUPPORTED_VERSION,
            )
        }
        checks += capabilityChecks(common.capabilities, adapter.supportedCapabilities, evidence)
        return checks
    }

    private fun adapterManifestSupportChecks(
        support: List<AdapterManifestSupport>,
        family: io.redi.robot.capability.provider.ProviderFamily,
        commonSchemaMajor: Int,
        specializedKind: SpecializedManifestKind,
        specializedSchemaMajor: Int,
        evidence: List<CompatibilityEvidence>,
    ): List<CompatibilityCheck> {
        val familySupport = support.filter { it.providerFamily == family }
        val commonSupport = familySupport.filter { it.commonManifestSchemaMajor == commonSchemaMajor }
        val expectedKind = when (specializedKind) {
            SpecializedManifestKind.BODY -> AdapterManifestKind.BODY
            SpecializedManifestKind.DEVICE -> AdapterManifestKind.DEVICE
            SpecializedManifestKind.SERVICE -> AdapterManifestKind.SERVICE
        }
        val specializedSupport = commonSupport.filter {
            it.specialization?.kind == expectedKind && it.specialization.schemaMajor == specializedSchemaMajor
        }
        return listOf(
            check(
                CompatibilityDimension.PROVIDER_FAMILY,
                "adapter_provider_family",
                listOf(family.wireValue),
                familySupport.map { it.providerFamily.wireValue },
                evidence,
                familySupport.isNotEmpty(),
                CompatibilityFindingCode.PROVIDER_FAMILY_MISMATCH,
            ),
            check(
                CompatibilityDimension.COMMON_MANIFEST_SCHEMA,
                "adapter_common_manifest_schema",
                listOf(commonSchemaMajor.toString()),
                familySupport.map { it.commonManifestSchemaMajor.toString() },
                evidence,
                commonSupport.isNotEmpty(),
                CompatibilityFindingCode.UNSUPPORTED_SCHEMA,
            ),
            check(
                CompatibilityDimension.SPECIALIZED_MANIFEST_SCHEMA,
                "adapter_specialized_manifest_schema",
                listOf("${expectedKind.name}@$specializedSchemaMajor"),
                commonSupport.mapNotNull {
                    it.specialization?.let { value -> "${value.kind.name}@${value.schemaMajor}" }
                },
                evidence,
                specializedSupport.isNotEmpty(),
                CompatibilityFindingCode.UNSUPPORTED_SCHEMA,
            ),
        )
    }

    private fun capabilityChecks(
        required: List<CapabilityReference>,
        offered: List<CapabilityReference>,
        evidence: List<CompatibilityEvidence>,
    ): List<CompatibilityCheck> = if (required.isEmpty()) {
        listOf(noRequirement(CompatibilityDimension.CAPABILITY, "capabilities", evidence))
    } else {
        required.sortedBy { it.wireValue }.map { capability ->
            val sameIdentity = offered.filter { it.id == capability.id }.sortedBy { it.wireValue }
            check(
                dimension = CompatibilityDimension.CAPABILITY,
                checkId = "capability:${capability.id.wireValue}",
                required = listOf(capability.wireValue),
                observed = sameIdentity.map { it.wireValue },
                evidence = evidence,
                matched = capability in offered,
                failure = CompatibilityFindingCode.UNSUPPORTED_CAPABILITY,
            )
        }
    }

    private fun missingAdapterChecks(
        capabilities: List<CapabilityReference>,
        adapterId: String,
        manifestEvidence: CompatibilityEvidence,
        runtime: HugoRuntimeEvidence,
    ): List<CompatibilityCheck> = listOf(
        CompatibilityCheck(
            dimension = CompatibilityDimension.ADAPTER_IDENTITY,
            checkId = "adapter_identity",
            requirement = CompatibilityRequirement.REQUIRED,
            status = CompatibilityCheckStatus.MISSING,
            finding = CompatibilityFindingCode.MISSING_ADAPTER,
            requiredValues = listOf(adapterId),
            observedValues = emptyList(),
            evidence = listOf(manifestEvidence),
        ),
        CompatibilityCheck(
            dimension = CompatibilityDimension.ADAPTER_VERSION,
            checkId = "adapter_version",
            requirement = CompatibilityRequirement.REQUIRED,
            status = CompatibilityCheckStatus.MISSING,
            finding = CompatibilityFindingCode.MISSING_ADAPTER,
            requiredValues = listOf("manifest_adapter_range"),
            observedValues = emptyList(),
            evidence = listOf(manifestEvidence),
        ),
        CompatibilityCheck(
            dimension = CompatibilityDimension.HUGO_RUNTIME,
            checkId = "hugo_runtime",
            requirement = CompatibilityRequirement.REQUIRED,
            status = CompatibilityCheckStatus.UNKNOWN,
            finding = CompatibilityFindingCode.MISSING_ADAPTER,
            requiredValues = listOf("adapter_runtime_range"),
            observedValues = listOf(runtime.version.wireValue),
            evidence = sortedEvidence(
                listOf(
                    manifestEvidence,
                    CompatibilityEvidence(CompatibilityEvidenceSource.HUGO_RUNTIME, runtime.evidenceReference),
                ),
            ),
        ),
    ) + capabilities.sortedBy { it.wireValue }.map {
        CompatibilityCheck(
            dimension = CompatibilityDimension.CAPABILITY,
            checkId = "capability:${it.id.wireValue}",
            requirement = CompatibilityRequirement.REQUIRED,
            status = CompatibilityCheckStatus.MISSING,
            finding = CompatibilityFindingCode.MISSING_ADAPTER,
            requiredValues = listOf(it.wireValue),
            observedValues = emptyList(),
            evidence = listOf(manifestEvidence),
        )
    }

    private fun connectionChecks(
        request: CompatibilityRequest,
        manifestEvidence: CompatibilityEvidence,
    ): List<CompatibilityCheck> {
        val declared = request.resolvedManifest.common.manifest.connectionMethods
        if (declared.isEmpty()) {
            return listOf(noRequirement(CompatibilityDimension.CONNECTION_METHOD, "connection_methods", listOf(manifestEvidence)))
        }
        return declared.sortedBy { it.reference.wireValue }.map { method ->
            val matching = request.connectionMethods.filter {
                it.method.id.value == method.reference.id.value
            }
            val exact = matching.filter { it.method.version.value == method.reference.version.wireValue }
            val requirement = if (method.required) CompatibilityRequirement.REQUIRED else CompatibilityRequirement.OPTIONAL
            CompatibilityCheck(
                dimension = CompatibilityDimension.CONNECTION_METHOD,
                checkId = "connection:${method.reference.id.value}",
                requirement = requirement,
                status = when {
                    exact.isNotEmpty() -> CompatibilityCheckStatus.MATCHED
                    method.required -> CompatibilityCheckStatus.FAILED
                    else -> CompatibilityCheckStatus.PARTIAL
                },
                finding = if (exact.isNotEmpty()) CompatibilityFindingCode.MATCHED
                else CompatibilityFindingCode.UNSUPPORTED_CONNECTION_METHOD,
                requiredValues = listOf(method.reference.wireValue),
                observedValues = matching.map { "${it.method.id.value}@${it.method.version.value}" }.sorted(),
                evidence = sortedEvidence(
                    listOf(manifestEvidence) + matching.map {
                        CompatibilityEvidence(CompatibilityEvidenceSource.CONNECTION_CONTRACT, it.evidenceReference)
                    },
                ),
            )
        }
    }

    private fun extensionChecks(
        request: CompatibilityRequest,
        manifestEvidence: CompatibilityEvidence,
    ): List<CompatibilityCheck> {
        val references = extensionReferences(request)
        if (references.isEmpty()) {
            return listOf(noRequirement(CompatibilityDimension.EXTENSION_SCHEMA, "extension_schemas", listOf(manifestEvidence)))
        }
        return references.groupBy { it.id }.toSortedMap(compareBy { it.value }).flatMap { (id, declarations) ->
            val distinctClaims = declarations.map { it.schemaVersion to it.digest }.distinct()
            if (distinctClaims.size > 1) {
                listOf(
                    CompatibilityCheck(
                        dimension = CompatibilityDimension.EXTENSION_SCHEMA,
                        checkId = "extension:${id.value}",
                        requirement = CompatibilityRequirement.REQUIRED,
                        status = CompatibilityCheckStatus.CONFLICTING,
                        finding = CompatibilityFindingCode.CONFLICTING_CONSTRAINT,
                        requiredValues = declarations.map { "${it.id.value}@${it.schemaVersion.wireValue}:${it.digest.value}" }.sorted(),
                        observedValues = emptyList(),
                        evidence = listOf(manifestEvidence),
                    ),
                )
            } else {
                val declaration = declarations.first()
                val support = request.extensionSchemas.filter { it.id == id }
                val exact = support.filter { it.schemaVersion == declaration.schemaVersion }
                listOf(
                    CompatibilityCheck(
                        dimension = CompatibilityDimension.EXTENSION_SCHEMA,
                        checkId = "extension:${id.value}",
                        requirement = CompatibilityRequirement.REQUIRED,
                        status = if (exact.isNotEmpty()) CompatibilityCheckStatus.MATCHED else CompatibilityCheckStatus.MISSING,
                        finding = if (exact.isNotEmpty()) CompatibilityFindingCode.MATCHED
                        else CompatibilityFindingCode.EXTENSION_SCHEMA_MISMATCH,
                        requiredValues = listOf("${id.value}@${declaration.schemaVersion.wireValue}"),
                        observedValues = support.map { "${it.id.value}@${it.schemaVersion.wireValue}" }.sorted(),
                        evidence = sortedEvidence(
                            listOf(manifestEvidence) + support.map {
                                CompatibilityEvidence(CompatibilityEvidenceSource.EXTENSION_CONTRACT, it.evidenceReference)
                            },
                        ),
                    ),
                )
            }
        }
    }

    private fun extensionReferences(request: CompatibilityRequest): List<ManifestExtensionReference> {
        val specialized = request.resolvedManifest.specialized.manifest
        return request.resolvedManifest.common.manifest.extensions + specialized.extensions +
            if (specialized is BodyManifest) listOfNotNull(specialized.geometryProfile?.extension) else emptyList()
    }

    private fun declaredConstraintChecks(
        request: CompatibilityRequest,
        manifestEvidence: CompatibilityEvidence,
    ): List<CompatibilityCheck> {
        val constraints = request.resolvedManifest.common.manifest.compatibility.toMutableList()
        val specialized = request.resolvedManifest.specialized.manifest
        if (specialized is ServiceManifest) {
            constraints += ManifestCompatibilityConstraint(
                ManifestCompatibilitySubject.PROVIDER_API,
                specialized.apiCompatibility,
            )
        }
        if (constraints.isEmpty()) {
            return listOf(noRequirement(CompatibilityDimension.DECLARED_CONSTRAINT, "declared_constraints", listOf(manifestEvidence)))
        }
        return constraints.groupBy { it.subject }.toSortedMap(compareBy { it.name }).map { (subject, ranges) ->
            val versions = request.providerVersions.filter { it.subject == subject }
            val distinctVersions = versions.map { it.version }.distinct()
            val evidence = sortedEvidence(
                listOf(manifestEvidence) + versions.map {
                    CompatibilityEvidence(CompatibilityEvidenceSource.PROVIDER_VERSION, it.evidenceReference)
                },
            )
            when {
                !rangesOverlap(ranges.map { it.versions }) -> CompatibilityCheck(
                    CompatibilityDimension.DECLARED_CONSTRAINT,
                    "constraint:${subject.name.lowercase()}",
                    CompatibilityRequirement.REQUIRED,
                    CompatibilityCheckStatus.CONFLICTING,
                    CompatibilityFindingCode.CONFLICTING_CONSTRAINT,
                    ranges.map { rangeText(it.versions) }.sorted(),
                    distinctVersions.map { it.wireValue }.sorted(),
                    evidence,
                )
                distinctVersions.size > 1 -> CompatibilityCheck(
                    CompatibilityDimension.DECLARED_CONSTRAINT,
                    "constraint:${subject.name.lowercase()}",
                    CompatibilityRequirement.REQUIRED,
                    CompatibilityCheckStatus.CONFLICTING,
                    CompatibilityFindingCode.CONFLICTING_CONSTRAINT,
                    ranges.map { rangeText(it.versions) }.sorted(),
                    distinctVersions.map { it.wireValue }.sorted(),
                    evidence,
                )
                distinctVersions.isEmpty() -> CompatibilityCheck(
                    CompatibilityDimension.DECLARED_CONSTRAINT,
                    "constraint:${subject.name.lowercase()}",
                    CompatibilityRequirement.REQUIRED,
                    CompatibilityCheckStatus.UNKNOWN,
                    CompatibilityFindingCode.MISSING_PROVIDER_VERSION,
                    ranges.map { rangeText(it.versions) }.sorted(),
                    emptyList(),
                    evidence,
                )
                else -> {
                    val version = distinctVersions.single()
                    check(
                        CompatibilityDimension.DECLARED_CONSTRAINT,
                        "constraint:${subject.name.lowercase()}",
                        ranges.map { rangeText(it.versions) }.sorted(),
                        listOf(version.wireValue),
                        evidence,
                        ranges.all { it.versions.supports(version) },
                        CompatibilityFindingCode.UNSUPPORTED_VERSION,
                    )
                }
            }
        }
    }

    private fun overallStatus(
        checks: List<CompatibilityCheck>,
        missingAdapter: Boolean,
    ): CompatibilityReportStatus = when {
        checks.any { it.status == CompatibilityCheckStatus.FAILED || it.status == CompatibilityCheckStatus.CONFLICTING } ->
            CompatibilityReportStatus.INCOMPATIBLE
        missingAdapter -> CompatibilityReportStatus.MISSING_ADAPTER
        checks.any { it.requirement == CompatibilityRequirement.REQUIRED && it.status == CompatibilityCheckStatus.MISSING } ->
            CompatibilityReportStatus.INCOMPATIBLE
        checks.any { it.status == CompatibilityCheckStatus.UNKNOWN } -> CompatibilityReportStatus.UNKNOWN
        checks.any { it.status == CompatibilityCheckStatus.PARTIAL } -> CompatibilityReportStatus.PARTIALLY_COMPATIBLE
        else -> CompatibilityReportStatus.COMPATIBLE
    }

    private fun check(
        dimension: CompatibilityDimension,
        checkId: String,
        required: List<String>,
        observed: List<String>,
        evidence: List<CompatibilityEvidence>,
        matched: Boolean,
        failure: CompatibilityFindingCode,
    ): CompatibilityCheck = CompatibilityCheck(
        dimension = dimension,
        checkId = checkId,
        requirement = CompatibilityRequirement.REQUIRED,
        status = if (matched) CompatibilityCheckStatus.MATCHED else CompatibilityCheckStatus.FAILED,
        finding = if (matched) CompatibilityFindingCode.MATCHED else failure,
        requiredValues = required.sorted(),
        observedValues = observed.sorted(),
        evidence = sortedEvidence(evidence),
    )

    private fun noRequirement(
        dimension: CompatibilityDimension,
        checkId: String,
        evidence: List<CompatibilityEvidence>,
    ): CompatibilityCheck = CompatibilityCheck(
        dimension = dimension,
        checkId = checkId,
        requirement = CompatibilityRequirement.INFORMATIONAL,
        status = CompatibilityCheckStatus.MATCHED,
        finding = CompatibilityFindingCode.NO_REQUIREMENT,
        requiredValues = emptyList(),
        observedValues = emptyList(),
        evidence = sortedEvidence(evidence),
    )

    private fun sortedEvidence(values: List<CompatibilityEvidence>): List<CompatibilityEvidence> =
        values.distinct().sortedWith(compareBy({ it.source.ordinal }, { it.reference.value }))

    private fun parseManifestVersion(raw: String): ManifestVersion? = when (
        val result = ManifestVersion.parse(raw)
    ) {
        is io.redi.robot.manifest.domain.CommonManifestResult.Success -> result.value
        is io.redi.robot.manifest.domain.CommonManifestResult.Failure -> null
    }

    private fun ManifestVersionRange.supports(version: ManifestVersion): Boolean =
        (minimumInclusive == null || version >= minimumInclusive) &&
            (maximumExclusive == null || version < maximumExclusive)

    private fun rangesOverlap(ranges: List<ManifestVersionRange>): Boolean {
        val minimum = ranges.mapNotNull { it.minimumInclusive }.maxOrNull()
        val maximum = ranges.mapNotNull { it.maximumExclusive }.minOrNull()
        return minimum == null || maximum == null || minimum < maximum
    }

    private fun rangeText(range: ManifestVersionRange): String =
        "[${range.minimumInclusive?.wireValue ?: "-infinity"},${range.maximumExclusive?.wireValue ?: "+infinity"})"

    private fun runtimeRangeText(range: io.redi.robot.adapter.api.HugoRuntimeCompatibility): String =
        "[${range.minimumInclusive?.wireValue ?: "-infinity"},${range.maximumExclusive?.wireValue ?: "+infinity"})"
}
