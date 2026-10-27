package io.redi.robot.interaction.localintent

import io.redi.robot.capability.api.CapabilityArgumentValue
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalIntentRouterTest {
    private val router = LocalIntentRouter()

    @Test
    fun `built in Albanian and English corpus maps exact phrases to semantic proposals`() {
        val cases = listOf(
            Triple("sq-AL", "ndalo", "mobility.stop@1.0"),
            Triple("sq-AL", "statusi i lidhjes", "provider.connection.status@1.0"),
            Triple("sq-AL", "statusi i sensorëve të poshtëm", "safety.lower_contact.status@1.0"),
            Triple("sq-AL", "fike dritën", "body.light.control@1.0"),
            Triple("en-US", "stop now", "mobility.stop@1.0"),
            Triple("en-US", "connection status", "provider.connection.status@1.0"),
            Triple("en-US", "lower contact status", "safety.lower_contact.status@1.0"),
            Triple("en-US", "turn the light off", "body.light.control@1.0"),
        )

        cases.forEach { (locale, phrase, capability) ->
            val result = router.route(locale, listOf(phrase))
            assertEquals("$locale / $phrase", LocalIntentRouteStatus.PROPOSED, result.status)
            assertEquals(capability, result.proposal?.capability?.wireValue)
        }
    }

    @Test
    fun `normalization is bounded to case spaces and NFC without stripping meaning`() {
        assertEquals(
            "mobility.stop@1.0",
            router.route("sq-AL", listOf("  NDALO   TANI  ")).proposal?.capability?.wireValue,
        )
        assertEquals(
            "body.light.control@1.0",
            router.route("sq-AL", listOf("fike drite\u0308n")).proposal?.capability?.wireValue,
        )
        assertEquals(
            LocalIntentRouteStatus.NO_MATCH,
            router.route("en-US", listOf("stop.")).status,
        )
    }

    @Test
    fun `light off carries existing typed semantic contract and no raw implementation value`() {
        val proposal = router.route("en-US", listOf("light off")).proposal!!
        assertEquals("hugo.contract.body_light_input@1.0", proposal.input?.contract?.wireValue)
        val value = proposal.input?.values?.values?.single() as CapabilityArgumentValue.Flag
        assertFalse(value.value)
        assertEquals("enabled", proposal.input?.values?.keys?.single()?.value)
    }

    @Test
    fun `locale allowlist is exact and never inferred`() {
        listOf("sq", "sq_AL", "sq-al", "SQ-AL", "en", "en-us", "fr-FR").forEach { locale ->
            val result = router.route(locale, listOf("stop"))
            assertEquals(locale, LocalIntentRouteStatus.UNSUPPORTED_LOCALE, result.status)
            assertNull(result.proposal)
        }
    }

    @Test
    fun `adversarial near matches negations and injected text never propose`() {
        val noMatches = listOf(
            "mos ndalo",
            "ndaloj",
            "ndalo tani dhe ec",
            "please stop",
            "unstoppable",
            "stoplight",
            "stop.",
            "turn the light off now",
            "do not turn the light off",
        )
        noMatches.forEach { phrase ->
            val locale = if (phrase.startsWith("n") || phrase.startsWith("m")) "sq-AL" else "en-US"
            val result = router.route(locale, listOf(phrase))
            assertTrue("$phrase -> ${result.status}", result.status != LocalIntentRouteStatus.PROPOSED)
            assertNull(result.proposal)
        }
        assertEquals(
            LocalIntentRouteStatus.INVALID_INPUT,
            router.route("en-US", listOf("st\u200Bop")).status,
        )
    }

    @Test
    fun `prefix and suffix fuzz around every action phrase remains safe`() {
        val phrases = listOf(
            "ndalo" to "sq-AL",
            "fike dritën" to "sq-AL",
            "stop" to "en-US",
            "turn the light off" to "en-US",
        )
        val additions = listOf("x", "mos ", "please ", " tani", " now", ".", "!", "?", " 0")
        val explicitlyAllowed = setOf("ndalo tani", "stop now")
        phrases.forEach { (phrase, locale) ->
            additions.forEach { addition ->
                val variants = listOf(addition + phrase, phrase + addition)
                variants.filter { it.trim() != phrase && it !in explicitlyAllowed }.forEach { candidate ->
                    assertNull("unexpected proposal for '$candidate'", router.route(locale, listOf(candidate)).proposal)
                }
            }
        }
    }

    @Test
    fun `distinct non stop matches are ambiguous and produce no proposal`() {
        val custom = corpus(
            rule("one", "status", "provider.connection.status@1.0"),
            rule("two", "status", "safety.lower_contact.status@1.0"),
        )
        val report = LocalIntentRouter(custom).route("en-US", listOf("status"))
        assertEquals(LocalIntentRouteStatus.AMBIGUOUS, report.status)
        assertNull(report.proposal)
        assertEquals(listOf("one", "two"), report.evidence.matchedRuleIds.map { it.value })
    }

    @Test
    fun `exact semantic stop wins over other alternatives but does not execute`() {
        val report = router.route("en-US", listOf("connection status", "stop"))
        assertEquals(LocalIntentRouteStatus.PROPOSED, report.status)
        assertEquals(LocalIntentPriority.STOP, report.proposal?.priority)
        assertEquals("mobility.stop@1.0", report.proposal?.capability?.wireValue)
        assertFalse(report.impliesExecution)
        assertFalse(report.proposal!!.executesCapability)
    }

    @Test
    fun `candidate and corpus ordering do not change equivalent report`() {
        val first = router.route("en-US", listOf("ARE YOU CONNECTED", "connection status"))
        val second = router.route("en-US", listOf("connection status", "are you connected"))
        assertEquivalent(first, second)

        val one = rule("one", "status", "provider.connection.status@1.0")
        val two = rule("two", "status", "safety.lower_contact.status@1.0")
        val forward = LocalIntentRouter(corpus(one, two)).route("en-US", listOf("status"))
        val reverse = LocalIntentRouter(corpus(two, one)).route("en-US", listOf("status"))
        assertEquivalent(forward, reverse)
    }

    @Test
    fun `invalid bounded input fails closed and diagnostics redact utterance`() {
        assertEquals(LocalIntentRouteStatus.INVALID_INPUT, router.route("en-US", emptyList()).status)
        assertEquals(
            LocalIntentRouteStatus.INVALID_INPUT,
            router.route("en-US", List(LocalIntentPolicy.MAX_ALTERNATIVES + 1) { "stop" }).status,
        )
        assertEquals(
            LocalIntentRouteStatus.INVALID_INPUT,
            router.route("en-US", listOf("s".repeat(LocalIntentPolicy.MAX_UTTERANCE_CODE_POINTS + 1))).status,
        )
        assertEquals(LocalIntentRouteStatus.INVALID_INPUT, router.route("en-US", listOf("stop\nnow")).status)
        val evidence = router.route("en-US", listOf("private spoken words")).evidence.toString()
        assertTrue(evidence.contains("transcript=redacted"))
        assertFalse(evidence.contains("private spoken words"))
    }

    @Test
    fun `proposal cannot imply provider trust permission safety or execution authority`() {
        val proposal = router.route("sq-AL", listOf("fike dritën")).proposal!!
        assertFalse(proposal.createsRequest)
        assertFalse(proposal.selectsProvider)
        assertFalse(proposal.grantsTrust)
        assertFalse(proposal.grantsAuthorization)
        assertFalse(proposal.grantsSafetyApproval)
        assertFalse(proposal.executesCapability)

        val report = router.route("sq-AL", listOf("ndalo"))
        assertFalse(report.impliesTrust)
        assertFalse(report.impliesAuthorization)
        assertFalse(report.impliesExecution)
    }

    @Test
    fun `domain contains no Android vendor transport LOOI registry or dispatcher coupling`() {
        val root = File("src/main/java/io/redi/robot/interaction/localintent")
        val source = root.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        listOf(
            "import android.",
            "Bluetooth",
            "Gatt",
            "FED",
            "Looi",
            "ProviderInstanceId",
            "ProviderRegistry",
            "AuthorizationGate",
            "CapabilityRequest",
            ".execute(",
            ".dispatch(",
        ).forEach { forbidden -> assertFalse("forbidden coupling: $forbidden", source.contains(forbidden)) }
    }

    @Test
    fun `corpus factories reject duplicate identity noncanonical phrase and unallowed locale`() {
        val id = model(LocalIntentCorpusId.parse("test.corpus"))
        val en = model(LocalIntentLocale.parse("en-US"))
        val sq = model(LocalIntentLocale.parse("sq-AL"))
        val one = rule("one", "status", "provider.connection.status@1.0")
        val duplicate = model(LocalIntentRuleId.parse("one"))
        val other = model(
            LocalIntentRule.create(duplicate, en, setOf("other"), proposal("provider.connection.status@1.0")),
        )
        assertEquals(
            LocalIntentModelError.DUPLICATE_RULE_ID,
            (LocalIntentCorpus.create(id, 1, setOf(en), listOf(one, other)) as LocalIntentModelResult.Failure).error,
        )
        assertEquals(
            LocalIntentModelError.RULE_LOCALE_NOT_ALLOWED,
            (LocalIntentCorpus.create(id, 1, setOf(sq), listOf(one)) as LocalIntentModelResult.Failure).error,
        )
        assertEquals(
            LocalIntentModelError.INVALID_PHRASE,
            (LocalIntentRule.create(model(LocalIntentRuleId.parse("bad")), en, setOf("Not Canonical"), proposal("provider.connection.status@1.0")) as LocalIntentModelResult.Failure).error,
        )
    }

    private fun assertEquivalent(first: LocalIntentRouteReport, second: LocalIntentRouteReport) {
        assertEquals(first.status, second.status)
        assertEquals(first.proposal?.canonicalKey, second.proposal?.canonicalKey)
        assertEquals(first.evidence.corpusId, second.evidence.corpusId)
        assertEquals(first.evidence.corpusVersion, second.evidence.corpusVersion)
        assertEquals(first.evidence.locale, second.evidence.locale)
        assertEquals(first.evidence.normalizedAlternativeCount, second.evidence.normalizedAlternativeCount)
        assertEquals(first.evidence.matchedRuleIds, second.evidence.matchedRuleIds)
    }

    private fun corpus(vararg rules: LocalIntentRule): LocalIntentCorpus = model(
        LocalIntentCorpus.create(
            model(LocalIntentCorpusId.parse("test.corpus")),
            1,
            setOf(model(LocalIntentLocale.parse("en-US"))),
            rules.toList(),
        ),
    )

    private fun rule(id: String, phrase: String, capability: String): LocalIntentRule = model(
        LocalIntentRule.create(
            model(LocalIntentRuleId.parse(id)),
            model(LocalIntentLocale.parse("en-US")),
            setOf(phrase),
            proposal(capability),
        ),
    )

    private fun proposal(raw: String) = LocalCapabilityProposal(capability(raw), null, LocalIntentPriority.NORMAL)

    private fun capability(raw: String): CapabilityReference = when (val result = CapabilityReference.parse(raw)) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> error(result)
    }

    private fun <T> model(result: LocalIntentModelResult<T>): T = when (result) {
        is LocalIntentModelResult.Success -> result.value
        is LocalIntentModelResult.Failure -> error(result)
    }
}
