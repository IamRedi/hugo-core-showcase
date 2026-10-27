package io.redi.robot.adapter.api

import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.provider.ProviderFamily
import io.redi.robot.manifest.domain.CommonProviderManifestDocument
import io.redi.robot.manifest.domain.ManifestVersion
import io.redi.robot.manifest.domain.SpecializedManifestKind
import io.redi.robot.manifest.domain.SpecializedManifestValidation
import io.redi.robot.manifest.domain.SpecializedProviderManifest
import io.redi.robot.manifest.domain.SpecializedManifestValidator

data class AdapterSchemaVersion private constructor(
    val major: Int,
    val minor: Int,
) {
    val wireValue: String = "$major.$minor"

    companion object {
        private val pattern = Regex("^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$")

        fun parse(raw: String): AdapterResult<AdapterSchemaVersion> = parseTwoPartVersion(
            raw = raw,
            field = "schemaVersion",
            requirePositiveMajor = true,
            build = ::AdapterSchemaVersion,
        )
    }
}

data class AdapterId private constructor(val wireValue: String) {
    companion object {
        private val pattern = Regex(
            "^[a-z][a-z0-9]*(?:[.][a-z][a-z0-9]*){1,7}/[a-z][a-z0-9]*(?:[-.][a-z0-9]+)*$",
        )

        fun parse(raw: String): AdapterResult<AdapterId> = when {
            raw.length !in 3..160 || !pattern.matches(raw) -> adapterFailure(
                AdapterError.MalformedAdapterId,
            )
            else -> AdapterResult.Success(AdapterId(raw))
        }
    }
}

data class AdapterVersion private constructor(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<AdapterVersion> {
    val wireValue: String = "$major.$minor.$patch"

    override fun compareTo(other: AdapterVersion): Int = compareValuesBy(
        this,
        other,
        AdapterVersion::major,
        AdapterVersion::minor,
        AdapterVersion::patch,
    )

    companion object {
        private val pattern = Regex(
            "^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$",
        )

        fun parse(raw: String): AdapterResult<AdapterVersion> = parseThreePartVersion(
            raw = raw,
            field = "adapterVersion",
            build = ::AdapterVersion,
        )
    }
}

data class HugoRuntimeVersion private constructor(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<HugoRuntimeVersion> {
    val wireValue: String = "$major.$minor.$patch"

    override fun compareTo(other: HugoRuntimeVersion): Int = compareValuesBy(
        this,
        other,
        HugoRuntimeVersion::major,
        HugoRuntimeVersion::minor,
        HugoRuntimeVersion::patch,
    )

    companion object {
        private val pattern = Regex(
            "^(0|[1-9][0-9]*)[.](0|[1-9][0-9]*)[.](0|[1-9][0-9]*)$",
        )

        fun parse(raw: String): AdapterResult<HugoRuntimeVersion> = parseThreePartVersion(
            raw = raw,
            field = "runtimeVersion",
            build = ::HugoRuntimeVersion,
        )
    }
}

data class HugoRuntimeCompatibility private constructor(
    val minimumInclusive: HugoRuntimeVersion?,
    val maximumExclusive: HugoRuntimeVersion?,
) {
    fun supports(version: HugoRuntimeVersion): Boolean =
        (minimumInclusive == null || version >= minimumInclusive) &&
            (maximumExclusive == null || version < maximumExclusive)

    companion object {
        fun create(
            minimumInclusive: HugoRuntimeVersion?,
            maximumExclusive: HugoRuntimeVersion?,
        ): AdapterResult<HugoRuntimeCompatibility> = when {
            minimumInclusive == null && maximumExclusive == null -> adapterFailure(
                AdapterError.MissingRuntimeCompatibilityBounds,
            )
            minimumInclusive != null && maximumExclusive != null &&
                minimumInclusive >= maximumExclusive -> adapterFailure(AdapterError.InvalidRuntimeCompatibilityRange)
            else -> AdapterResult.Success(
                HugoRuntimeCompatibility(minimumInclusive, maximumExclusive),
            )
        }
    }
}

data class AdapterPackageDigest private constructor(val wireValue: String) {
    companion object {
        private val pattern = Regex("^sha256:[0-9a-f]{64}$")

        fun parse(raw: String): AdapterResult<AdapterPackageDigest> = when {
            !pattern.matches(raw) -> adapterFailure(AdapterError.MalformedPackageDigest)
            else -> AdapterResult.Success(AdapterPackageDigest(raw))
        }
    }
}

data class AdapterPublisherReference private constructor(val wireValue: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._/-][a-z0-9]+)*$")

        fun parse(raw: String): AdapterResult<AdapterPublisherReference> = parseReference(
            raw,
            AdapterError.MalformedPublisherReference,
            ::AdapterPublisherReference,
        )
    }
}

data class AdapterSourceReference private constructor(val wireValue: String) {
    companion object {
        private val pattern = Regex("^[a-z][a-z0-9]*(?:[._/-][a-z0-9]+)*$")

        fun parse(raw: String): AdapterResult<AdapterSourceReference> = parseReference(
            raw,
            AdapterError.MalformedSourceReference,
            ::AdapterSourceReference,
        )
    }
}

enum class AdapterProvenanceSource {
    BUILT_IN,
    OFFICIAL_VENDOR,
    HUGO_MAINTAINED,
    LOCAL_DEVELOPMENT,
    DEVELOPER_MODE_CANDIDATE,
    INSTALLABLE_PACKAGE,
}

data class AdapterProvenance(
    val source: AdapterProvenanceSource,
    val publisher: AdapterPublisherReference,
    val sourceReference: AdapterSourceReference,
)

data class AdapterIdentity(
    val id: AdapterId,
    val version: AdapterVersion,
)

enum class AdapterManifestKind(val providerFamily: ProviderFamily) {
    BODY(ProviderFamily.BODY_ADAPTER),
    DEVICE(ProviderFamily.DEVICE_ADAPTER),
    SERVICE(ProviderFamily.SERVICE_CONNECTOR),
}

data class AdapterSpecializedManifestSupport(
    val kind: AdapterManifestKind,
    val schemaMajor: Int,
)

data class AdapterManifestSupport(
    val providerFamily: ProviderFamily,
    val commonManifestSchemaMajor: Int,
    val specialization: AdapterSpecializedManifestSupport?,
)

class AdapterDescriptor private constructor(
    val schemaVersion: AdapterSchemaVersion,
    val identity: AdapterIdentity,
    val packageDigest: AdapterPackageDigest,
    val provenance: AdapterProvenance,
    val manifestSupport: List<AdapterManifestSupport>,
    val supportedCapabilities: List<CapabilityReference>,
    val runtimeCompatibility: HugoRuntimeCompatibility?,
) {
    companion object {
        const val supportedSchemaMajor: Int = 1
        private const val maximumManifestSupportDeclarations = 16
        private const val maximumCapabilityDeclarations = 128

        fun create(
            schemaVersion: AdapterSchemaVersion,
            identity: AdapterIdentity,
            packageDigest: AdapterPackageDigest,
            provenance: AdapterProvenance,
            manifestSupport: List<AdapterManifestSupport>,
            supportedCapabilities: List<CapabilityReference>,
            runtimeCompatibility: HugoRuntimeCompatibility?,
        ): AdapterResult<AdapterDescriptor> {
            if (schemaVersion.major != supportedSchemaMajor) {
                return adapterFailure(
                    AdapterError.UnsupportedDescriptorSchemaVersion(schemaVersion.wireValue),
                )
            }
            if (manifestSupport.isEmpty() || manifestSupport.size > maximumManifestSupportDeclarations) {
                return adapterFailure(AdapterError.InvalidManifestSupportDeclarations)
            }
            if (supportedCapabilities.size > maximumCapabilityDeclarations) {
                return adapterFailure(AdapterError.InvalidCapabilityDeclarations)
            }
            manifestSupport.forEach { support ->
                if (support.commonManifestSchemaMajor <= 0 || support.specialization?.schemaMajor?.let { it <= 0 } == true) {
                    return adapterFailure(AdapterError.InvalidManifestSupportDeclarations)
                }
                if (support.specialization?.kind?.providerFamily != null &&
                    support.specialization.kind.providerFamily != support.providerFamily
                ) {
                    return adapterFailure(AdapterError.ConflictingManifestSupportDeclaration)
                }
            }
            val supportKeys = manifestSupport.map { support ->
                "${support.providerFamily}:${support.specialization?.kind ?: "COMMON"}"
            }
            if (supportKeys.distinct().size != supportKeys.size) {
                return adapterFailure(AdapterError.DuplicateManifestSupportDeclaration)
            }
            if (supportedCapabilities.map { it.wireValue }.distinct().size != supportedCapabilities.size) {
                return adapterFailure(AdapterError.DuplicateCapabilityDeclaration)
            }
            return AdapterResult.Success(
                AdapterDescriptor(
                    schemaVersion = schemaVersion,
                    identity = identity,
                    packageDigest = packageDigest,
                    provenance = provenance,
                    manifestSupport = manifestSupport.sortedBy { support ->
                        "${support.providerFamily}:${support.specialization?.kind ?: "COMMON"}"
                    },
                    supportedCapabilities = supportedCapabilities.sortedBy(CapabilityReference::wireValue),
                    runtimeCompatibility = runtimeCompatibility,
                ),
            )
        }
    }
}

data class AdapterIntegrityExpectation(
    val identity: AdapterIdentity,
    val packageDigest: AdapterPackageDigest,
)

enum class AdapterIntegrityStatus {
    MATCH,
    ADAPTER_ID_MISMATCH,
    ADAPTER_VERSION_MISMATCH,
    PACKAGE_DIGEST_MISMATCH,
}

data class AdapterIntegrityVerification(
    val expected: AdapterIntegrityExpectation,
    val observed: AdapterIntegrityExpectation,
    val status: AdapterIntegrityStatus,
)

object AdapterIntegrityVerifier {
    fun verify(
        expected: AdapterIntegrityExpectation,
        observed: AdapterIntegrityExpectation,
    ): AdapterIntegrityVerification = AdapterIntegrityVerification(
        expected = expected,
        observed = observed,
        status = when {
            expected.identity.id != observed.identity.id -> AdapterIntegrityStatus.ADAPTER_ID_MISMATCH
            expected.identity.version != observed.identity.version -> AdapterIntegrityStatus.ADAPTER_VERSION_MISMATCH
            expected.packageDigest != observed.packageDigest -> AdapterIntegrityStatus.PACKAGE_DIGEST_MISMATCH
            else -> AdapterIntegrityStatus.MATCH
        },
    )
}

sealed interface AdapterManifestCompatibilityResult {
    data object Compatible : AdapterManifestCompatibilityResult

    data class Incompatible(val reason: AdapterManifestCompatibilityFailure) : AdapterManifestCompatibilityResult
}

enum class AdapterManifestCompatibilityFailure {
    MANIFEST_ADAPTER_REFERENCE_MISMATCH,
    ADAPTER_VERSION_OUTSIDE_MANIFEST_RANGE,
    UNSUPPORTED_PROVIDER_FAMILY,
    UNSUPPORTED_COMMON_MANIFEST_SCHEMA,
    INVALID_SPECIALIZED_MANIFEST_BINDING,
    UNSUPPORTED_SPECIALIZED_MANIFEST,
    UNSUPPORTED_SPECIALIZED_MANIFEST_SCHEMA,
    UNSUPPORTED_CAPABILITY,
}

object AdapterManifestCompatibilityMatcher {
    fun match(
        adapter: AdapterDescriptor,
        common: CommonProviderManifestDocument,
        specialized: SpecializedProviderManifest? = null,
    ): AdapterManifestCompatibilityResult {
        val manifest = common.manifest
        if (manifest.adapter.adapterId.value != adapter.identity.id.wireValue) {
            return incompatible(AdapterManifestCompatibilityFailure.MANIFEST_ADAPTER_REFERENCE_MISMATCH)
        }
        val version = ManifestVersion.parse(adapter.identity.version.wireValue).manifestValueOrNull()
            ?: return incompatible(AdapterManifestCompatibilityFailure.ADAPTER_VERSION_OUTSIDE_MANIFEST_RANGE)
        if (!manifest.adapter.versions.supports(version)) {
            return incompatible(AdapterManifestCompatibilityFailure.ADAPTER_VERSION_OUTSIDE_MANIFEST_RANGE)
        }
        val matchingFamily = adapter.manifestSupport.filter {
            it.providerFamily == manifest.provider.family
        }
        if (matchingFamily.isEmpty()) {
            return incompatible(AdapterManifestCompatibilityFailure.UNSUPPORTED_PROVIDER_FAMILY)
        }
        val commonSupport = matchingFamily.filter {
            it.commonManifestSchemaMajor == manifest.schemaVersion.major
        }
        if (commonSupport.isEmpty()) {
            return incompatible(AdapterManifestCompatibilityFailure.UNSUPPORTED_COMMON_MANIFEST_SCHEMA)
        }
        if (specialized != null) {
            if (SpecializedManifestValidator.validate(specialized, common) !is SpecializedManifestValidation.Valid) {
                return incompatible(AdapterManifestCompatibilityFailure.INVALID_SPECIALIZED_MANIFEST_BINDING)
            }
            val expectedKind = specialized.kind.toAdapterManifestKind()
            val specializedSupport = commonSupport.firstOrNull {
                it.specialization?.kind == expectedKind
            } ?: return incompatible(AdapterManifestCompatibilityFailure.UNSUPPORTED_SPECIALIZED_MANIFEST)
            if (specializedSupport.specialization?.schemaMajor != specialized.schemaVersion.major) {
                return incompatible(AdapterManifestCompatibilityFailure.UNSUPPORTED_SPECIALIZED_MANIFEST_SCHEMA)
            }
        }
        val supported = adapter.supportedCapabilities.map { it.wireValue }.toSet()
        if (manifest.capabilities.any { it.wireValue !in supported }) {
            return incompatible(AdapterManifestCompatibilityFailure.UNSUPPORTED_CAPABILITY)
        }
        return AdapterManifestCompatibilityResult.Compatible
    }

    private fun incompatible(reason: AdapterManifestCompatibilityFailure) =
        AdapterManifestCompatibilityResult.Incompatible(reason)
}

sealed interface AdapterResult<out T> {
    data class Success<T>(val value: T) : AdapterResult<T>
    data class Failure(val error: AdapterError) : AdapterResult<Nothing>
}

sealed interface AdapterError {
    data object MalformedAdapterId : AdapterError
    data object MalformedPackageDigest : AdapterError
    data object MalformedPublisherReference : AdapterError
    data object MalformedSourceReference : AdapterError
    data class MalformedVersion(val field: String) : AdapterError
    data class UnsupportedDescriptorSchemaVersion(val found: String) : AdapterError
    data object MissingRuntimeCompatibilityBounds : AdapterError
    data object InvalidRuntimeCompatibilityRange : AdapterError
    data object InvalidManifestSupportDeclarations : AdapterError
    data object ConflictingManifestSupportDeclaration : AdapterError
    data object DuplicateManifestSupportDeclaration : AdapterError
    data object InvalidCapabilityDeclarations : AdapterError
    data object DuplicateCapabilityDeclaration : AdapterError
    data object MalformedDocument : AdapterError
    data class MissingFields(val path: String, val fields: Set<String>) : AdapterError
    data class UnexpectedFields(val path: String, val fields: Set<String>) : AdapterError
    data class InvalidField(val path: String) : AdapterError
}

private fun <T> adapterFailure(error: AdapterError): AdapterResult<T> = AdapterResult.Failure(error)

private fun <T> parseTwoPartVersion(
    raw: String,
    field: String,
    requirePositiveMajor: Boolean,
    build: (Int, Int) -> T,
): AdapterResult<T> {
    val parts = raw.split('.')
    if (parts.size != 2 || parts.any { it.isEmpty() || it.length > 1 && it.startsWith('0') }) {
        return adapterFailure(AdapterError.MalformedVersion(field))
    }
    val numbers = parts.map { it.toIntOrNull() ?: return adapterFailure(AdapterError.MalformedVersion(field)) }
    if (numbers.any { it < 0 } || requirePositiveMajor && numbers.first() <= 0) {
        return adapterFailure(AdapterError.MalformedVersion(field))
    }
    return AdapterResult.Success(build(numbers[0], numbers[1]))
}

private fun <T> parseThreePartVersion(
    raw: String,
    field: String,
    build: (Int, Int, Int) -> T,
): AdapterResult<T> {
    val parts = raw.split('.')
    if (parts.size != 3 || parts.any { it.isEmpty() || it.length > 1 && it.startsWith('0') }) {
        return adapterFailure(AdapterError.MalformedVersion(field))
    }
    val numbers = parts.map { it.toIntOrNull() ?: return adapterFailure(AdapterError.MalformedVersion(field)) }
    if (numbers.any { it < 0 }) return adapterFailure(AdapterError.MalformedVersion(field))
    return AdapterResult.Success(build(numbers[0], numbers[1], numbers[2]))
}

private fun <T> parseReference(
    raw: String,
    error: AdapterError,
    build: (String) -> T,
): AdapterResult<T> {
    val pattern = Regex("^[a-z][a-z0-9]*(?:[._/-][a-z0-9]+)*$")
    return if (raw.length !in 3..160 || !pattern.matches(raw)) adapterFailure(error)
    else AdapterResult.Success(build(raw))
}

private fun ManifestVersionRangeSupports(versions: io.redi.robot.manifest.domain.ManifestVersionRange, version: ManifestVersion): Boolean =
    (versions.minimumInclusive == null || version >= versions.minimumInclusive) &&
        (versions.maximumExclusive == null || version < versions.maximumExclusive)

private fun io.redi.robot.manifest.domain.ManifestVersionRange.supports(version: ManifestVersion): Boolean =
    ManifestVersionRangeSupports(this, version)

private fun io.redi.robot.manifest.domain.CommonManifestResult<ManifestVersion>.manifestValueOrNull(): ManifestVersion? =
    when (this) {
        is io.redi.robot.manifest.domain.CommonManifestResult.Success -> value
        is io.redi.robot.manifest.domain.CommonManifestResult.Failure -> null
    }

private fun SpecializedManifestKind.toAdapterManifestKind(): AdapterManifestKind = when (this) {
    SpecializedManifestKind.BODY -> AdapterManifestKind.BODY
    SpecializedManifestKind.DEVICE -> AdapterManifestKind.DEVICE
    SpecializedManifestKind.SERVICE -> AdapterManifestKind.SERVICE
}
