package io.redi.robot.interaction.localintent

/** Pure H046 exact-corpus matcher. It performs no lookup, authorization, dispatch, or execution. */
class LocalIntentRouter(
    private val corpus: LocalIntentCorpus = HugoLocalIntentCorpus.corpus,
) {
    fun route(localeTag: String, alternatives: List<String>): LocalIntentRouteReport {
        val locale = (LocalIntentLocale.parse(localeTag) as? LocalIntentModelResult.Success)?.value
        if (locale == null || locale !in corpus.allowedLocales) {
            return report(LocalIntentRouteStatus.UNSUPPORTED_LOCALE, null, locale, 0, emptyList())
        }
        if (alternatives.isEmpty() || alternatives.size > LocalIntentPolicy.MAX_ALTERNATIVES) {
            return report(LocalIntentRouteStatus.INVALID_INPUT, null, locale, 0, emptyList())
        }
        val normalized = alternatives.map { LocalIntentText.normalizeInput(it) }
        if (normalized.any { it == null }) {
            return report(LocalIntentRouteStatus.INVALID_INPUT, null, locale, 0, emptyList())
        }
        val distinctAlternatives = normalized.filterNotNull().distinct().sorted()
        val matches = corpus.rules.asSequence()
            .filter { it.locale == locale }
            .filter { rule -> rule.phrases.any(distinctAlternatives::contains) }
            .sortedBy { it.id.value }
            .toList()
        if (matches.isEmpty()) {
            return report(LocalIntentRouteStatus.NO_MATCH, null, locale, distinctAlternatives.size, emptyList())
        }

        val stopMatches = matches.filter { it.proposal.priority == LocalIntentPriority.STOP }
        val considered = if (stopMatches.isNotEmpty()) stopMatches else matches
        val proposals = considered.distinctBy { it.proposal.canonicalKey }
        return if (proposals.size == 1) {
            report(
                LocalIntentRouteStatus.PROPOSED,
                proposals.single().proposal,
                locale,
                distinctAlternatives.size,
                matches.map { it.id },
            )
        } else {
            report(
                LocalIntentRouteStatus.AMBIGUOUS,
                null,
                locale,
                distinctAlternatives.size,
                matches.map { it.id },
            )
        }
    }

    private fun report(
        status: LocalIntentRouteStatus,
        proposal: LocalCapabilityProposal?,
        locale: LocalIntentLocale?,
        alternativeCount: Int,
        rules: List<LocalIntentRuleId>,
    ): LocalIntentRouteReport = LocalIntentRouteReport(
        status = status,
        proposal = proposal,
        evidence = LocalIntentRoutingEvidence(
            corpusId = corpus.id,
            corpusVersion = corpus.version,
            locale = locale,
            normalizedAlternativeCount = alternativeCount,
            matchedRuleIds = rules.distinct().sortedBy { it.value },
        ),
    )
}
