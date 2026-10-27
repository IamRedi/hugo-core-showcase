package io.redi.robot.memory.domain

import io.redi.robot.capability.provider.ProviderInstanceId
import io.redi.robot.context.api.ContextConfidence
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextSourceReference
import io.redi.robot.context.api.ContextSourceType
import io.redi.robot.context.api.ContextValue
import io.redi.robot.hugo.identity.HugoIdentityId
import java.util.Collections
import java.util.UUID

/**
 * H058 common memory envelope. A pure, provider-neutral, storage-independent representation of one
 * memory candidate/record: identity, layer/type, Hugo ownership, scope binding, subjects, content,
 * provenance, confidence, trust state, disposition, sensitivity, allowed use, retention,
 * supersession/conflict links, lineage, and minimal tombstone audit.
 *
 * The envelope is not a fact, not authorization, not a safety decision, and not storage. Trust and
 * disposition transitions belong to later memory tasks; this type represents their states and
 * enforces only fail-closed construction invariants.
 */
class MemoryEnvelope private constructor(
    val recordId: MemoryRecordId,
    val schemaVersion: MemorySchemaVersion,
    val ownerIdentity: HugoIdentityId,
    val layer: MemoryLayer,
    val type: MemoryTypeToken,
    val scope: MemoryScope,
    val subjects: List<MemorySubjectToken>,
    val content: ContextValue?,
    val provenance: MemoryProvenance,
    val confidence: ContextConfidence?,
    val validationState: MemoryValidationState,
    val disposition: MemoryDisposition,
    val privacy: ContextPrivacyClassification,
    val allowedUse: MemoryAllowedUse,
    val retention: MemoryRetention?,
    val supersedes: MemoryRecordId?,
    val conflictsWith: List<MemoryRecordId>,
    val lineage: MemoryLineage?,
    val tombstone: MemoryTombstone?,
    val createdAtEpochSecond: Long,
    val updatedAtEpochSecond: Long?,
) {
    val grantsAuthority: Boolean get() = false
    val approvesSafety: Boolean get() = false
    val isFact: Boolean get() = false
    val authorizesAnyUse: Boolean get() = false

    override fun toString(): String = "MemoryEnvelope(record=$recordId, owner=anchored, layer=$layer, " +
        "type=$type, scope=${scope.kind}, subjects=${subjects.size}, content=[redacted], " +
        "origin=${provenance.origin}, modelGenerated=${provenance.modelGenerated}, " +
        "state=$validationState/$disposition, privacy=$privacy, use=$allowedUse, " +
        "retention=${retention?.retentionClass?.name ?: "none"}, supersedes=${supersedes != null}, " +
        "conflicts=${conflictsWith.size}, lineage=${lineage?.derivedFrom?.size ?: 0}, " +
        "tombstoned=${tombstone != null})"

    companion object {
        /**
         * Creates a live (non-tombstoned) record. Creation policy applies here: model-generated
         * provenance must start as a candidate, sensitive records cannot gain model-facing allowed
         * uses, and layer/scope binding enforces ADR-016.
         */
        @Suppress("LongParameterList")
        fun create(
            recordId: MemoryRecordId,
            ownerIdentity: HugoIdentityId,
            layer: MemoryLayer,
            type: MemoryTypeToken,
            scope: MemoryScope,
            subjects: List<MemorySubjectToken>,
            content: ContextValue,
            provenance: MemoryProvenance,
            confidence: ContextConfidence,
            validationState: MemoryValidationState,
            disposition: MemoryDisposition,
            privacy: ContextPrivacyClassification,
            allowedUse: MemoryAllowedUse,
            retention: MemoryRetention,
            supersedes: MemoryRecordId?,
            conflictsWith: List<MemoryRecordId>,
            lineage: MemoryLineage?,
            createdAtEpochSecond: Long,
            updatedAtEpochSecond: Long?,
        ): MemoryDomainResult<MemoryEnvelope> {
            if (disposition == MemoryDisposition.TOMBSTONED) {
                return MemoryDomainResult.Failure(MemoryDomainError.TombstoneRequiresFactory)
            }
            if (provenance.modelGenerated && validationState != MemoryValidationState.CANDIDATE) {
                return MemoryDomainResult.Failure(MemoryDomainError.ModelOutputMustStartCandidate)
            }
            if (privacy in SENSITIVE_OR_RESTRICTED && allowedUse != MemoryAllowedUse.LOCAL_ONLY &&
                allowedUse != MemoryAllowedUse.NEVER_MODEL
            ) {
                return MemoryDomainResult.Failure(MemoryDomainError.SensitiveAllowedUseRestricted)
            }
            if (!layer.permits(scope)) {
                return MemoryDomainResult.Failure(MemoryDomainError.LayerScopeMismatch)
            }
            val retentionWindow = checkRetentionWindow(retention, createdAtEpochSecond)
            if (retentionWindow != null) return MemoryDomainResult.Failure(retentionWindow)
            val subjectError = checkSubjects(subjects)
            if (subjectError != null) return MemoryDomainResult.Failure(subjectError)
            val linkError = checkLinks(recordId, supersedes, conflictsWith)
            if (linkError != null) return MemoryDomainResult.Failure(linkError)
            if (lineage != null && lineage.derivedFrom.contains(recordId)) {
                return MemoryDomainResult.Failure(MemoryDomainError.SelfReferenceForbidden)
            }
            if (updatedAtEpochSecond != null && updatedAtEpochSecond < createdAtEpochSecond) {
                return MemoryDomainResult.Failure(MemoryDomainError.InvalidUpdatedAt)
            }
            return MemoryDomainResult.Success(
                MemoryEnvelope(
                    recordId,
                    MemorySchemaVersion.V1,
                    ownerIdentity,
                    layer,
                    type,
                    scope,
                    subjects,
                    content,
                    provenance,
                    confidence,
                    validationState,
                    disposition,
                    privacy,
                    allowedUse,
                    retention,
                    supersedes,
                    conflictsWith,
                    lineage,
                    null,
                    createdAtEpochSecond,
                    updatedAtEpochSecond,
                ),
            )
        }

        /**
         * The only producer of a tombstoned envelope. Personal/content payload (content,
         * confidence, retention) is dropped; bounded non-personal identity/audit/linkage
         * (provenance tokens, subjects, links, lineage, classification) survives as the minimal
         * audit the memory architecture requires.
         */
        fun tombstoneOf(
            record: MemoryEnvelope,
            actor: MemoryCreator,
            tombstonedAtEpochSecond: Long,
        ): MemoryDomainResult<MemoryEnvelope> {
            if (record.disposition == MemoryDisposition.TOMBSTONED) {
                return MemoryDomainResult.Failure(MemoryDomainError.AlreadyTombstoned)
            }
            return MemoryDomainResult.Success(
                MemoryEnvelope(
                    record.recordId,
                    record.schemaVersion,
                    record.ownerIdentity,
                    record.layer,
                    record.type,
                    record.scope,
                    record.subjects,
                    null,
                    record.provenance,
                    null,
                    record.validationState,
                    MemoryDisposition.TOMBSTONED,
                    record.privacy,
                    record.allowedUse,
                    null,
                    record.supersedes,
                    record.conflictsWith,
                    record.lineage,
                    MemoryTombstone.create(actor, tombstonedAtEpochSecond),
                    record.createdAtEpochSecond,
                    record.updatedAtEpochSecond,
                ),
            )
        }

        /**
         * Structural restore for persisted documents (codec path). It re-checks shape, bounds,
         * null-ness, link, and ordering invariants but deliberately does not re-apply creation
         * policy: a stored model-inferred record that later validation marked VALIDATED must
         * round-trip with its original provenance intact.
         */
        @Suppress("LongParameterList")
        internal fun restore(
            recordId: MemoryRecordId,
            ownerIdentity: HugoIdentityId,
            layer: MemoryLayer,
            type: MemoryTypeToken,
            scope: MemoryScope,
            subjects: List<MemorySubjectToken>,
            content: ContextValue?,
            provenance: MemoryProvenance,
            confidence: ContextConfidence?,
            validationState: MemoryValidationState,
            disposition: MemoryDisposition,
            privacy: ContextPrivacyClassification,
            allowedUse: MemoryAllowedUse,
            retention: MemoryRetention?,
            supersedes: MemoryRecordId?,
            conflictsWith: List<MemoryRecordId>,
            lineage: MemoryLineage?,
            tombstone: MemoryTombstone?,
            createdAtEpochSecond: Long,
            updatedAtEpochSecond: Long?,
        ): MemoryDomainResult<MemoryEnvelope> {
            if (disposition == MemoryDisposition.TOMBSTONED) {
                if (tombstone == null || content != null || confidence != null || retention != null) {
                    return MemoryDomainResult.Failure(MemoryDomainError.TombstonePayloadInconsistent)
                }
            } else {
                if (tombstone != null) return MemoryDomainResult.Failure(MemoryDomainError.TombstoneForbidden)
                if (content == null) return MemoryDomainResult.Failure(MemoryDomainError.ContentRequired)
                if (confidence == null) return MemoryDomainResult.Failure(MemoryDomainError.ConfidenceRequired)
                if (retention == null) return MemoryDomainResult.Failure(MemoryDomainError.RetentionRequired)
            }
            val subjectError = checkSubjects(subjects)
            if (subjectError != null) return MemoryDomainResult.Failure(subjectError)
            val linkError = checkLinks(recordId, supersedes, conflictsWith)
            if (linkError != null) return MemoryDomainResult.Failure(linkError)
            if (lineage != null && lineage.derivedFrom.contains(recordId)) {
                return MemoryDomainResult.Failure(MemoryDomainError.SelfReferenceForbidden)
            }
            if (updatedAtEpochSecond != null && updatedAtEpochSecond < createdAtEpochSecond) {
                return MemoryDomainResult.Failure(MemoryDomainError.InvalidUpdatedAt)
            }
            return MemoryDomainResult.Success(
                MemoryEnvelope(
                    recordId,
                    MemorySchemaVersion.V1,
                    ownerIdentity,
                    layer,
                    type,
                    scope,
                    subjects,
                    content,
                    provenance,
                    confidence,
                    validationState,
                    disposition,
                    privacy,
                    allowedUse,
                    retention,
                    supersedes,
                    conflictsWith,
                    lineage,
                    tombstone,
                    createdAtEpochSecond,
                    updatedAtEpochSecond,
                ),
            )
        }

        private fun checkRetentionWindow(retention: MemoryRetention, createdAtEpochSecond: Long): MemoryDomainError? =
            if (retention.expiresAtEpochSecond != null && retention.expiresAtEpochSecond <= createdAtEpochSecond) {
                MemoryDomainError.InvalidRetentionWindow
            } else {
                null
            }

        private fun checkSubjects(subjects: List<MemorySubjectToken>): MemoryDomainError? = when {
            subjects.isEmpty() || subjects.size > MAXIMUM_SUBJECTS -> MemoryDomainError.InvalidSubjects
            subjects.distinct().size != subjects.size -> MemoryDomainError.InvalidSubjects
            else -> null
        }

        private fun checkLinks(
            recordId: MemoryRecordId,
            supersedes: MemoryRecordId?,
            conflictsWith: List<MemoryRecordId>,
        ): MemoryDomainError? = when {
            supersedes == recordId -> MemoryDomainError.SelfReferenceForbidden
            conflictsWith.contains(recordId) -> MemoryDomainError.SelfReferenceForbidden
            conflictsWith.size > MAXIMUM_CONFLICTS -> MemoryDomainError.InvalidConflictLinks
            conflictsWith.distinct().size != conflictsWith.size -> MemoryDomainError.InvalidConflictLinks
            supersedes != null && conflictsWith.contains(supersedes) -> MemoryDomainError.InvalidConflictLinks
            else -> null
        }

        internal val SENSITIVE_OR_RESTRICTED = setOf(
            ContextPrivacyClassification.SENSITIVE,
            ContextPrivacyClassification.RESTRICTED,
        )

        internal const val MAXIMUM_SUBJECTS = 4
        internal const val MAXIMUM_CONFLICTS = 8
    }
}

/** Trust epistemics only. Transition policy belongs to later memory tasks. */
enum class MemoryValidationState {
    CANDIDATE,
    VALIDATED,
    DISPUTED,
    ;
}

/** Record lifecycle/deletion disposition. EXPIRED is derived from retention, never stored. */
enum class MemoryDisposition {
    ACTIVE,
    SUPERSEDED,
    TOMBSTONED,
    ;
}

/** Authoritative memory layers (Memory Architecture V2 layer table). */
enum class MemoryLayer {
    WORKING,
    EPISODIC,
    SEMANTIC,
    PERSONAL,
    DEVICE,
    BODY,
    STRATEGY,
    ;

    /** ADR-016 binding: instance-scoped evidence can never be created as universal Hugo memory. */
    fun permits(scope: MemoryScope): Boolean = when (this) {
        WORKING, EPISODIC, SEMANTIC, PERSONAL -> scope is MemoryScope.GlobalPersonal
        DEVICE -> scope is MemoryScope.DeviceInstance
        BODY -> scope is MemoryScope.BodyInstance
        STRATEGY -> scope is MemoryScope.GlobalPersonal || scope is MemoryScope.ProviderInstance
    }
}

/**
 * Where a record's subject matter is owned. Instance identifiers reuse the H004 opaque
 * provider-instance identity; versions are generic bounded tokens. No vendor or SDK types.
 */
sealed interface MemoryScope {
    val kind: String

    data object GlobalPersonal : MemoryScope {
        override val kind: String = "global_personal"
    }

    data class BodyInstance(
        val instanceId: ProviderInstanceId,
        val version: MemoryInstanceVersionToken?,
    ) : MemoryScope {
        override val kind: String = "body_instance"

        override fun toString(): String = "MemoryScope.BodyInstance(instance=[redacted], version=$version)"
    }

    data class DeviceInstance(
        val instanceId: ProviderInstanceId,
        val version: MemoryInstanceVersionToken?,
    ) : MemoryScope {
        override val kind: String = "device_instance"

        override fun toString(): String = "MemoryScope.DeviceInstance(instance=[redacted], version=$version)"
    }

    data class ProviderInstance(
        val instanceId: ProviderInstanceId,
        val version: MemoryInstanceVersionToken?,
    ) : MemoryScope {
        override val kind: String = "provider_instance"

        override fun toString(): String = "MemoryScope.ProviderInstance(instance=[redacted], version=$version)"
    }
}

/**
 * Memory allowed-use policy — the authoritative "allowed uses" axis. The classes form a monotonic
 * ladder: PROVIDER_ALLOWED implies gateway and local use; NEVER_MODEL excludes gateway and provider
 * model contexts entirely.
 */
enum class MemoryAllowedUse(val wireValue: String) {
    LOCAL_ONLY("local_only"),
    GATEWAY_ALLOWED("gateway_allowed"),
    PROVIDER_ALLOWED("provider_allowed"),
    NEVER_MODEL("never_model"),
    ;

    companion object {
        fun fromWireValue(raw: String): MemoryAllowedUse? = entries.firstOrNull { it.wireValue == raw }
    }
}

enum class MemoryRetentionClass {
    SESSION_SCOPED,
    POLICY_CONTROLLED,
    UNTIL_CORRECTED,
    FIXED_INTERVAL,
    ;
}

/** Retention intent. Expiry instants are caller-injected epoch seconds; no clock lives here. */
class MemoryRetention private constructor(
    val retentionClass: MemoryRetentionClass,
    val expiresAtEpochSecond: Long?,
) {
    override fun toString(): String = "MemoryRetention(${retentionClass.name}, expiresAt=$expiresAtEpochSecond)"

    /** Value semantics: retention is compared by class and expiry, never by identity. */
    override fun equals(other: Any?): Boolean = other is MemoryRetention &&
        retentionClass == other.retentionClass && expiresAtEpochSecond == other.expiresAtEpochSecond

    override fun hashCode(): Int = 31 * retentionClass.hashCode() + (expiresAtEpochSecond?.hashCode() ?: 0)

    companion object {
        fun create(
            retentionClass: MemoryRetentionClass,
            expiresAtEpochSecond: Long?,
        ): MemoryDomainResult<MemoryRetention> = when {
            retentionClass == MemoryRetentionClass.FIXED_INTERVAL && expiresAtEpochSecond == null ->
                MemoryDomainResult.Failure(MemoryDomainError.InvalidRetentionWindow)
            retentionClass != MemoryRetentionClass.FIXED_INTERVAL && expiresAtEpochSecond != null ->
                MemoryDomainResult.Failure(MemoryDomainError.InvalidRetentionWindow)
            else -> MemoryDomainResult.Success(MemoryRetention(retentionClass, expiresAtEpochSecond))
        }
    }
}

/**
 * Bounded provenance for one record. `modelGenerated` is a caller-declared fact about the creator;
 * it makes "model output is candidate only" enforceable at creation without inferring from names.
 * All references are bounded project-owned tokens; there is no free-text channel.
 */
class MemoryProvenance private constructor(
    val origin: MemoryProvenanceOrigin,
    val modelGenerated: Boolean,
    val sourceType: ContextSourceType,
    val sourceReference: ContextSourceReference,
    val creator: MemoryCreator,
    val observedAtEpochSecond: Long?,
) {
    override fun toString(): String = "MemoryProvenance(origin=$origin, modelGenerated=$modelGenerated, " +
        "sourceType=$sourceType, sourceReference=$sourceReference, creator=$creator, observedAt=$observedAtEpochSecond)"

    companion object {
        /** Creation path: definitional binding between origin and the model-generated flag. */
        fun create(
            origin: MemoryProvenanceOrigin,
            modelGenerated: Boolean,
            sourceType: ContextSourceType,
            sourceReference: ContextSourceReference,
            creator: MemoryCreator,
            observedAtEpochSecond: Long?,
        ): MemoryDomainResult<MemoryProvenance> {
            if (origin == MemoryProvenanceOrigin.MODEL_INFERRED && !modelGenerated) {
                return MemoryDomainResult.Failure(MemoryDomainError.ModelProvenanceMismatch)
            }
            return MemoryDomainResult.Success(
                MemoryProvenance(origin, modelGenerated, sourceType, sourceReference, creator, observedAtEpochSecond),
            )
        }

        /** Codec path: structural restore without the creation-time definitional check. */
        internal fun restore(
            origin: MemoryProvenanceOrigin,
            modelGenerated: Boolean,
            sourceType: ContextSourceType,
            sourceReference: ContextSourceReference,
            creator: MemoryCreator,
            observedAtEpochSecond: Long?,
        ): MemoryProvenance =
            MemoryProvenance(origin, modelGenerated, sourceType, sourceReference, creator, observedAtEpochSecond)
    }
}

/** The five authoritative provenance origins. */
enum class MemoryProvenanceOrigin(val wireValue: String) {
    USER_EXPLICIT("user_explicit"),
    OBSERVED("observed"),
    TOOL_RESULT("tool_result"),
    CONVERSATION_SUMMARY("conversation_summary"),
    MODEL_INFERRED("model_inferred"),
    ;

    companion object {
        fun fromWireValue(raw: String): MemoryProvenanceOrigin? = entries.firstOrNull { it.wireValue == raw }
    }
}

/** Bounded lineage of derived records. Source references can never be erased from the envelope. */
class MemoryLineage private constructor(derivedFrom: List<MemoryRecordId>) {
    val derivedFrom: List<MemoryRecordId> = Collections.unmodifiableList(derivedFrom.toList())

    override fun toString(): String = "MemoryLineage(derivedFrom=${derivedFrom.size} records)"

    companion object {
        const val MAXIMUM_DERIVED_FROM = 8

        fun create(derivedFrom: List<MemoryRecordId>): MemoryDomainResult<MemoryLineage> = when {
            derivedFrom.isEmpty() || derivedFrom.size > MAXIMUM_DERIVED_FROM ->
                MemoryDomainResult.Failure(MemoryDomainError.InvalidLineage)
            derivedFrom.distinct().size != derivedFrom.size ->
                MemoryDomainResult.Failure(MemoryDomainError.InvalidLineage)
            else -> MemoryDomainResult.Success(MemoryLineage(derivedFrom))
        }
    }
}

/** Minimal, non-personal deletion audit. No reason text, no content, no payload. */
class MemoryTombstone private constructor(
    val tombstonedAtEpochSecond: Long,
    val actor: MemoryCreator,
) {
    override fun toString(): String = "MemoryTombstone(at=$tombstonedAtEpochSecond, actor=[redacted])"

    companion object {
        fun create(actor: MemoryCreator, tombstonedAtEpochSecond: Long): MemoryTombstone =
            MemoryTombstone(tombstonedAtEpochSecond, actor)
    }
}

@JvmInline
value class MemoryRecordId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val canonicalPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val caseInsensitivePattern = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )

        fun fromUuid(uuid: UUID): MemoryRecordId = MemoryRecordId(uuid.toString())

        fun parse(raw: String): MemoryDomainResult<MemoryRecordId> = when {
            raw.isBlank() -> MemoryDomainResult.Failure(MemoryDomainError.BlankId)
            !caseInsensitivePattern.matches(raw) ->
                MemoryDomainResult.Failure(MemoryDomainError.MalformedId)
            !canonicalPattern.matches(raw) ->
                MemoryDomainResult.Failure(MemoryDomainError.NonCanonicalId)
            else -> MemoryDomainResult.Success(MemoryRecordId(raw))
        }
    }
}

enum class MemorySchemaVersion(val wireValue: String) {
    V1("1.0"),
    ;
}

/** Bounded semantic token for record types (layer-specific vocabularies arrive in later tasks). */
@JvmInline
value class MemoryTypeToken private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): MemoryDomainResult<MemoryTypeToken> =
            semanticToken(raw, MINIMUM_TOKEN_LENGTH, MAXIMUM_TYPE_TOKEN_LENGTH)
                ?.let { MemoryDomainResult.Success(MemoryTypeToken(it)) }
                ?: MemoryDomainResult.Failure(MemoryDomainError.InvalidToken)
    }
}

/** Bounded opaque subject key. Subject keys are non-personal semantic links. */
@JvmInline
value class MemorySubjectToken private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): MemoryDomainResult<MemorySubjectToken> =
            semanticToken(raw, MINIMUM_TOKEN_LENGTH, MAXIMUM_SUBJECT_TOKEN_LENGTH)
                ?.let { MemoryDomainResult.Success(MemorySubjectToken(it)) }
                ?: MemoryDomainResult.Failure(MemoryDomainError.InvalidToken)
    }
}

/** Bounded creator token. Identifies the creating actor kind, never user content. */
@JvmInline
value class MemoryCreator private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): MemoryDomainResult<MemoryCreator> =
            semanticToken(raw, MINIMUM_TOKEN_LENGTH, MAXIMUM_CREATOR_TOKEN_LENGTH)
                ?.let { MemoryDomainResult.Success(MemoryCreator(it)) }
                ?: MemoryDomainResult.Failure(MemoryDomainError.InvalidToken)
    }
}

/** Bounded generic instance/version token for scope binding. */
@JvmInline
value class MemoryInstanceVersionToken private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(raw: String): MemoryDomainResult<MemoryInstanceVersionToken> =
            semanticToken(raw, 1, MAXIMUM_INSTANCE_VERSION_TOKEN_LENGTH)
                ?.let { MemoryDomainResult.Success(MemoryInstanceVersionToken(it)) }
                ?: MemoryDomainResult.Failure(MemoryDomainError.InvalidToken)
    }
}

enum class MemoryDomainError {
    BlankId,
    MalformedId,
    NonCanonicalId,
    InvalidToken,
    ModelProvenanceMismatch,
    ModelOutputMustStartCandidate,
    TombstoneRequiresFactory,
    AlreadyTombstoned,
    TombstoneForbidden,
    TombstonePayloadInconsistent,
    ContentRequired,
    ConfidenceRequired,
    RetentionRequired,
    SensitiveAllowedUseRestricted,
    LayerScopeMismatch,
    InvalidRetentionWindow,
    InvalidSubjects,
    SelfReferenceForbidden,
    InvalidConflictLinks,
    InvalidLineage,
    InvalidUpdatedAt,
    UnsupportedSchemaVersion,
    MalformedDocument,
    InvalidDocument,
    MissingField,
    UnexpectedField,
    InvalidField,
    InvalidPrivacyValue,
    InvalidAllowedUseValue,
    InvalidOriginValue,
    InvalidContentValue,
    ;
}

sealed interface MemoryDomainResult<out T> {
    data class Success<T>(val value: T) : MemoryDomainResult<T>
    data class Failure(val error: MemoryDomainError) : MemoryDomainResult<Nothing>
}

private const val MINIMUM_TOKEN_LENGTH = 3
private const val MAXIMUM_TYPE_TOKEN_LENGTH = 80
private const val MAXIMUM_SUBJECT_TOKEN_LENGTH = 96
private const val MAXIMUM_CREATOR_TOKEN_LENGTH = 64
private const val MAXIMUM_INSTANCE_VERSION_TOKEN_LENGTH = 64

private val semanticTokenPattern = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$")

private fun semanticToken(raw: String, minimumLength: Int, maximumLength: Int): String? = raw.takeIf {
    it.length in minimumLength..maximumLength && semanticTokenPattern.matches(it)
}
