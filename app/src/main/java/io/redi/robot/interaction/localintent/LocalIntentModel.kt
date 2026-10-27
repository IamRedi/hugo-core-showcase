package io.redi.robot.interaction.localintent

import io.redi.robot.capability.api.CapabilityArgumentValue
import io.redi.robot.capability.api.CapabilityData
import io.redi.robot.capability.api.CapabilityReference
import java.text.Normalizer
import java.util.Collections
import java.util.Locale

@JvmInline
value class LocalIntentCorpusId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val pattern = Regex("^[a-z][a-z0-9_]*(?:[.][a-z][a-z0-9_]*)*$")

        fun parse(raw: String): LocalIntentModelResult<LocalIntentCorpusId> = if (pattern.matches(raw)) {
            LocalIntentModelResult.Success(LocalIntentCorpusId(raw))
        } else {
            LocalIntentModelResult.Failure(LocalIntentModelError.INVALID_CORPUS_ID)
        }
    }
}

@JvmInline
value class LocalIntentRuleId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val pattern = Regex("^[a-z][a-z0-9_]*(?:[.][a-z][a-z0-9_]*)*$")

        fun parse(raw: String): LocalIntentModelResult<LocalIntentRuleId> = if (pattern.matches(raw)) {
            LocalIntentModelResult.Success(LocalIntentRuleId(raw))
        } else {
            LocalIntentModelResult.Failure(LocalIntentModelError.INVALID_RULE_ID)
        }
    }
}

/** Exact canonical locale tag. H046 does not infer a language from a partial or nearby tag. */
@JvmInline
value class LocalIntentLocale private constructor(val tag: String) {
    override fun toString(): String = tag

    companion object {
        private val exactLanguageRegion = Regex("^[a-z]{2,3}-[A-Z]{2}$")

        fun parse(raw: String): LocalIntentModelResult<LocalIntentLocale> = if (
            exactLanguageRegion.matches(raw)
        ) {
            LocalIntentModelResult.Success(LocalIntentLocale(raw))
        } else {
            LocalIntentModelResult.Failure(LocalIntentModelError.INVALID_LOCALE)
        }
    }
}

enum class LocalIntentPriority {
    NORMAL,
    STOP,
}

/**
 * A semantic proposal only. It has no provider target, request correlation, grant, approval,
 * safety decision, connection state, availability claim, or execution method.
 */
data class LocalCapabilityProposal(
    val capability: CapabilityReference,
    val input: CapabilityData?,
    val priority: LocalIntentPriority,
) {
    init {
        require(
            priority != LocalIntentPriority.STOP ||
                capability.wireValue == LocalIntentPolicy.STOP_CAPABILITY && input == null,
        ) { "STOP priority is reserved for the input-free semantic stop capability" }
    }

    internal val canonicalKey: String
        get() = buildString {
            append(capability.wireValue)
            append('|')
            append(priority.name)
            input?.let { data ->
                append('|')
                append(data.contract.wireValue)
                data.values.entries.sortedBy { it.key.value }.forEach { (name, value) ->
                    append('|')
                    append(name.value)
                    append('=')
                    append(value.canonicalValue())
                }
            }
        }

    val createsRequest: Boolean get() = false
    val selectsProvider: Boolean get() = false
    val grantsTrust: Boolean get() = false
    val grantsAuthorization: Boolean get() = false
    val grantsSafetyApproval: Boolean get() = false
    val executesCapability: Boolean get() = false

    override fun toString(): String =
        "LocalCapabilityProposal(capability=$capability, priority=$priority, input=redacted, authority=false, execution=false)"
}

class LocalIntentRule private constructor(
    val id: LocalIntentRuleId,
    val locale: LocalIntentLocale,
    phrases: Set<String>,
    val proposal: LocalCapabilityProposal,
) {
    val phrases: Set<String> = Collections.unmodifiableSet(phrases.toSortedSet())

    companion object {
        const val MAX_PHRASES_PER_RULE = 16

        fun create(
            id: LocalIntentRuleId,
            locale: LocalIntentLocale,
            phrases: Set<String>,
            proposal: LocalCapabilityProposal,
        ): LocalIntentModelResult<LocalIntentRule> {
            if (phrases.isEmpty() || phrases.size > MAX_PHRASES_PER_RULE) {
                return LocalIntentModelResult.Failure(LocalIntentModelError.INVALID_PHRASE_SET)
            }
            val normalized = phrases.map { LocalIntentText.normalizeCorpusPhrase(it) }
            if (normalized.any { it == null } || normalized.filterNotNull().size != phrases.size) {
                return LocalIntentModelResult.Failure(LocalIntentModelError.INVALID_PHRASE)
            }
            val values = normalized.filterNotNull().toSet()
            if (values.size != phrases.size) {
                return LocalIntentModelResult.Failure(LocalIntentModelError.DUPLICATE_PHRASE)
            }
            return LocalIntentModelResult.Success(LocalIntentRule(id, locale, values, proposal))
        }
    }
}

class LocalIntentCorpus private constructor(
    val id: LocalIntentCorpusId,
    val version: Int,
    allowedLocales: Set<LocalIntentLocale>,
    rules: List<LocalIntentRule>,
) {
    val allowedLocales: Set<LocalIntentLocale> =
        Collections.unmodifiableSet(allowedLocales.toSortedSet(compareBy { it.tag }))
    val rules: List<LocalIntentRule> =
        Collections.unmodifiableList(rules.sortedWith(compareBy({ it.locale.tag }, { it.id.value })))

    companion object {
        const val MAX_RULES = 128

        fun create(
            id: LocalIntentCorpusId,
            version: Int,
            allowedLocales: Set<LocalIntentLocale>,
            rules: List<LocalIntentRule>,
        ): LocalIntentModelResult<LocalIntentCorpus> {
            if (version <= 0) return LocalIntentModelResult.Failure(LocalIntentModelError.INVALID_CORPUS_VERSION)
            if (allowedLocales.isEmpty()) return LocalIntentModelResult.Failure(LocalIntentModelError.EMPTY_LOCALE_ALLOWLIST)
            if (rules.isEmpty() || rules.size > MAX_RULES) {
                return LocalIntentModelResult.Failure(LocalIntentModelError.INVALID_RULE_COUNT)
            }
            if (rules.map { it.id }.distinct().size != rules.size) {
                return LocalIntentModelResult.Failure(LocalIntentModelError.DUPLICATE_RULE_ID)
            }
            if (rules.any { it.locale !in allowedLocales }) {
                return LocalIntentModelResult.Failure(LocalIntentModelError.RULE_LOCALE_NOT_ALLOWED)
            }
            return LocalIntentModelResult.Success(LocalIntentCorpus(id, version, allowedLocales, rules))
        }
    }
}

enum class LocalIntentRouteStatus {
    PROPOSED,
    NO_MATCH,
    AMBIGUOUS,
    UNSUPPORTED_LOCALE,
    INVALID_INPUT,
}

class LocalIntentRoutingEvidence internal constructor(
    val corpusId: LocalIntentCorpusId,
    val corpusVersion: Int,
    val locale: LocalIntentLocale?,
    val normalizedAlternativeCount: Int,
    matchedRuleIds: List<LocalIntentRuleId>,
) {
    val matchedRuleIds: List<LocalIntentRuleId> =
        Collections.unmodifiableList(matchedRuleIds.distinct().sortedBy { it.value })

    init {
        require(normalizedAlternativeCount in 0..LocalIntentPolicy.MAX_ALTERNATIVES)
    }

    override fun toString(): String =
        "LocalIntentRoutingEvidence(corpus=$corpusId@$corpusVersion, locale=$locale, " +
            "alternatives=$normalizedAlternativeCount, rules=$matchedRuleIds, transcript=redacted)"
}

data class LocalIntentRouteReport(
    val status: LocalIntentRouteStatus,
    val proposal: LocalCapabilityProposal?,
    val evidence: LocalIntentRoutingEvidence,
) {
    init {
        require((status == LocalIntentRouteStatus.PROPOSED) == (proposal != null))
    }

    val impliesExecution: Boolean get() = false
    val impliesAuthorization: Boolean get() = false
    val impliesTrust: Boolean get() = false
}

enum class LocalIntentModelError {
    INVALID_CORPUS_ID,
    INVALID_RULE_ID,
    INVALID_LOCALE,
    INVALID_CORPUS_VERSION,
    EMPTY_LOCALE_ALLOWLIST,
    INVALID_RULE_COUNT,
    DUPLICATE_RULE_ID,
    RULE_LOCALE_NOT_ALLOWED,
    INVALID_PHRASE_SET,
    INVALID_PHRASE,
    DUPLICATE_PHRASE,
}

sealed interface LocalIntentModelResult<out T> {
    data class Success<T>(val value: T) : LocalIntentModelResult<T>
    data class Failure(val error: LocalIntentModelError) : LocalIntentModelResult<Nothing>
}

object LocalIntentPolicy {
    const val MAX_ALTERNATIVES = 3
    const val MAX_UTTERANCE_CODE_POINTS = 160
    const val STOP_CAPABILITY = "mobility.stop@1.0"
}

internal object LocalIntentText {
    fun normalizeInput(raw: String): String? = normalize(raw, requireCanonical = false)

    fun normalizeCorpusPhrase(raw: String): String? = normalize(raw, requireCanonical = true)

    private fun normalize(raw: String, requireCanonical: Boolean): String? {
        val codePoints = raw.codePoints().toArray()
        if (codePoints.isEmpty() || codePoints.size > LocalIntentPolicy.MAX_UTTERANCE_CODE_POINTS) return null
        if (codePoints.any { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }) return null
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
            .trim()
            .replace(Regex(" +"), " ")
            .lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return null
        return if (!requireCanonical || normalized == raw) normalized else null
    }
}

private fun CapabilityArgumentValue.canonicalValue(): String = when (this) {
    is CapabilityArgumentValue.Text -> "text:$value"
    is CapabilityArgumentValue.Integer -> "integer:$value"
    is CapabilityArgumentValue.Decimal -> "decimal:${java.lang.Double.toHexString(value)}"
    is CapabilityArgumentValue.Flag -> "flag:$value"
}
