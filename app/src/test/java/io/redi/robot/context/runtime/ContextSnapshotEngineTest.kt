package io.redi.robot.context.runtime

import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.context.api.ContextConfidence
import io.redi.robot.context.api.ContextKey
import io.redi.robot.context.api.ContextObservation
import io.redi.robot.context.api.ContextObservationClaim
import io.redi.robot.context.api.ContextObservationId
import io.redi.robot.context.api.ContextObservationResult
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextSourceReference
import io.redi.robot.context.api.ContextSourceType
import io.redi.robot.context.api.ContextText
import io.redi.robot.context.api.ContextUnknownReason
import io.redi.robot.context.api.ContextValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ContextSnapshotEngineTest {
    @Test
    fun `virtual time excludes future and expired observations at exact boundaries`() {
        val result = snapshot(
            listOf(
                observation(1, key = "state.fresh", observedAt = 99, expiresAt = 101),
                observation(2, key = "state.future", observedAt = 101, expiresAt = 120),
                observation(3, key = "state.stale", observedAt = 90, expiresAt = 100),
            ),
            request(now = 100),
        )

        assertEquals(listOf("state.fresh"), result.items.map { it.key.value })
        assertEquals(1, result.omissionCount(ContextSnapshotOmissionReason.NOT_YET_VALID))
        assertEquals(1, result.omissionCount(ContextSnapshotOmissionReason.STALE))
    }

    @Test
    fun `normalization keeps latest evidence per source and retains cross-source conflict`() {
        val inputs = listOf(
            observation(1, source = "source_one", value = false, observedAt = 80),
            observation(2, source = "source_one", value = true, observedAt = 95),
            observation(3, source = "source_two", value = false, observedAt = 90),
        )

        val result = snapshot(inputs, request())
        val item = result.items.single()
        val conflict = item.resolution as ContextSnapshotResolution.Conflict

        assertEquals(listOf("boolean:false", "boolean:true"), conflict.candidates.map(::valueKey))
        assertEquals(listOf("source_one", "source_two"), item.evidence.map { it.sourceReference.value })
        assertEquals(1, result.omissionCount(ContextSnapshotOmissionReason.SUPERSEDED))
    }

    @Test
    fun `unknown reasons remain explicit and are never converted to a fact`() {
        val result = snapshot(
            listOf(
                observation(
                    1,
                    source = "source_two",
                    claim = ContextObservationClaim.Unknown(ContextUnknownReason.PERMISSION_DENIED),
                ),
                observation(
                    2,
                    source = "source_one",
                    claim = ContextObservationClaim.Unknown(ContextUnknownReason.SOURCE_UNAVAILABLE),
                ),
            ),
            request(),
        )
        val unknown = result.items.single().resolution as ContextSnapshotResolution.Unknown

        assertEquals(
            listOf(ContextUnknownReason.PERMISSION_DENIED, ContextUnknownReason.SOURCE_UNAVAILABLE),
            unknown.reasons,
        )
        assertEquals(2, result.items.single().evidence.size)
    }

    @Test
    fun `an H036 conflict remains conflict while agreeing known evidence normalizes to known`() {
        val inputConflict = successfulObservation(
            ContextObservationClaim.Conflict.create(
                listOf(ContextValue.BooleanValue(true), ContextValue.BooleanValue(false)),
            ),
        )
        val conflictResult = snapshot(listOf(observation(1, claim = inputConflict)), request())
        val conflict = conflictResult.items.single().resolution as ContextSnapshotResolution.Conflict
        assertEquals(listOf("boolean:false", "boolean:true"), conflict.candidates.map(::valueKey))

        val agreement = snapshot(
            listOf(
                observation(2, source = "source_one", value = true),
                observation(3, source = "source_two", value = true),
            ),
            request(),
        )
        assertEquals(
            ContextSnapshotResolution.Known(ContextValue.BooleanValue(true)),
            agreement.items.single().resolution,
        )
        assertEquals(2, agreement.retainedEvidenceCount)
    }

    @Test
    fun `privacy and exact key scope filter without granting authorization`() {
        val result = snapshot(
            listOf(
                observation(1, key = "state.allowed", privacy = ContextPrivacyClassification.PRIVATE),
                observation(2, key = "state.secret", privacy = ContextPrivacyClassification.RESTRICTED),
                observation(3, key = "state.other", privacy = ContextPrivacyClassification.PRIVATE),
            ),
            request(
                keys = listOf(key("state.allowed"), key("state.secret")),
                privacy = setOf(ContextPrivacyClassification.PRIVATE),
            ),
        )

        assertEquals(listOf("state.allowed"), result.items.map { it.key.value })
        assertEquals(1, result.omissionCount(ContextSnapshotOmissionReason.KEY_FILTERED))
        assertEquals(1, result.omissionCount(ContextSnapshotOmissionReason.PRIVACY_FILTERED))
        assertEquals(setOf(ContextPrivacyClassification.PRIVATE), result.allowedPrivacyClassifications)
    }

    @Test
    fun `duplicate observations dedupe but contradictory reuse of an id fails closed`() {
        val original = observation(1, value = true)
        val duplicateResult = snapshot(listOf(original, original), request())
        assertEquals(1, duplicateResult.retainedEvidenceCount)
        assertEquals(1, duplicateResult.omissionCount(ContextSnapshotOmissionReason.DUPLICATE_OBSERVATION))

        val failure = ContextSnapshotEngine.build(
            listOf(original, observation(1, value = false)),
            request(),
        )
        assertEquals(
            ContextSnapshotResult.Failure(ContextSnapshotError.ConflictingObservationId(original.observationId)),
            failure,
        )
    }

    @Test
    fun `item evidence and total evidence limits omit whole items without hiding conflicts`() {
        val tooManyForItem = snapshot(
            listOf(
                observation(1, source = "source_one"),
                observation(2, source = "source_two"),
            ),
            request(budget = budget(maxItems = 8, evidencePerItem = 1, totalEvidence = 8)),
        )
        assertTrue(tooManyForItem.items.isEmpty())
        assertEquals(1, tooManyForItem.omissionCount(ContextSnapshotOmissionReason.EVIDENCE_PER_ITEM_BUDGET))

        val totalLimited = snapshot(
            listOf(
                observation(3, key = "a.key", source = "source_one"),
                observation(4, key = "a.key", source = "source_two"),
                observation(5, key = "b.key", source = "source_one"),
                observation(6, key = "b.key", source = "source_two"),
            ),
            request(budget = budget(maxItems = 8, evidencePerItem = 2, totalEvidence = 2)),
        )
        assertEquals(listOf("a.key"), totalLimited.items.map { it.key.value })
        assertEquals(1, totalLimited.omissionCount(ContextSnapshotOmissionReason.TOTAL_EVIDENCE_BUDGET))
    }

    @Test
    fun `item and canonical utf8 size budgets hold exactly`() {
        val inputs = (1..4).map { index ->
            observation(
                index,
                key = "state.item_$index",
                claim = ContextObservationClaim.Known(
                    ContextValue.TextValue(text("x".repeat(140))),
                ),
            )
        }
        val itemLimited = snapshot(
            inputs,
            request(budget = budget(maxItems = 2, evidencePerItem = 2, totalEvidence = 8)),
        )
        assertEquals(2, itemLimited.items.size)
        assertEquals(2, itemLimited.omissionCount(ContextSnapshotOmissionReason.ITEM_BUDGET))

        val sizeLimited = snapshot(
            inputs,
            request(budget = budget(maxItems = 4, evidencePerItem = 2, totalEvidence = 8, bytes = 900)),
        )
        assertTrue(sizeLimited.canonicalUtf8Size() <= 900)
        assertTrue(sizeLimited.omissionCount(ContextSnapshotOmissionReason.SIZE_BUDGET) > 0)
    }

    @Test
    fun `snapshot fails closed when required metadata alone exceeds size budget`() {
        val manyRequestedKeys = (1..40).map { key("requested.context_key_$it") }
        val failure = ContextSnapshotEngine.build(
            emptyList(),
            request(
                keys = manyRequestedKeys,
                budget = budget(maxItems = 1, evidencePerItem = 1, totalEvidence = 1, bytes = 512),
            ),
        )

        assertTrue(failure is ContextSnapshotResult.Failure)
        assertTrue((failure as ContextSnapshotResult.Failure).error is ContextSnapshotError.SnapshotBudgetTooSmall)
    }

    @Test
    fun `equivalent inputs produce identical snapshots independent of ordering`() {
        val inputs = listOf(
            observation(1, key = "z.key", source = "source_two", value = false),
            observation(2, key = "a.key", source = "source_one", value = true),
            observation(3, key = "z.key", source = "source_one", value = true),
            observation(
                4,
                key = "u.key",
                source = "source_three",
                claim = ContextObservationClaim.Unknown(ContextUnknownReason.NOT_OBSERVED),
            ),
        )
        val expected = ContextSnapshotCodec.encode(snapshot(inputs, request()))

        repeat(30) { seed ->
            val shuffled = inputs.shuffled(Random(seed))
            assertEquals(expected, ContextSnapshotCodec.encode(snapshot(shuffled, request())))
        }
    }

    @Test
    fun `exact key order is explicit deterministic selection priority under budget`() {
        val inputs = listOf(
            observation(1, key = "a.key"),
            observation(2, key = "z.key"),
        )
        val result = snapshot(
            inputs,
            request(
                keys = listOf(key("z.key"), key("a.key")),
                budget = budget(maxItems = 1, evidencePerItem = 2, totalEvidence = 2),
            ),
        )

        assertEquals(listOf("z.key"), result.items.map { it.key.value })
        assertEquals(1, result.omissionCount(ContextSnapshotOmissionReason.ITEM_BUDGET))
    }

    @Test
    fun `request and budget validation are bounded and defensively copied`() {
        assertTrue(ContextSnapshotBudget.create(0, 1, 1, 512) is ContextSnapshotResult.Failure)
        assertTrue(ContextSnapshotBudget.create(1, 2, 1, 512) is ContextSnapshotResult.Failure)
        assertTrue(ContextSnapshotBudget.create(1, 1, 1, 511) is ContextSnapshotResult.Failure)
        val keys = mutableListOf(key("a.key"))
        val privacy = mutableSetOf(ContextPrivacyClassification.PUBLIC)
        val created = successful(ContextSnapshotRequest.create(time(100), keys, privacy, budget()))
        keys.clear()
        privacy.clear()

        assertEquals(listOf("a.key"), (created.keySelection as ContextKeySelection.Exact).keys.map { it.value })
        assertEquals(setOf(ContextPrivacyClassification.PUBLIC), created.allowedPrivacyClassifications)
        assertEquals(
            ContextSnapshotResult.Failure(ContextSnapshotError.DuplicateRequestedKey),
            ContextSnapshotRequest.create(
                time(100),
                listOf(key("a.key"), key("a.key")),
                setOf(ContextPrivacyClassification.PUBLIC),
                budget(),
            ),
        )
    }

    @Test
    fun `snapshot is ephemeral evidence with no authority persistence or platform fields`() {
        val snapshot = snapshot(listOf(observation(1)), request())
        val encoded = ContextSnapshotCodec.encode(snapshot).lowercase()
        val prohibitedFields = setOf(
            "authorized",
            "trusted",
            "safetyapproved",
            "executionauthority",
            "activebody",
            "memoryid",
            "persisted",
            "android",
            "bluetooth",
            "looi",
        )

        assertFalse(prohibitedFields.any(encoded::contains))
        assertTrue(encoded.contains("sourceReference".lowercase()))
        assertEquals(snapshot.canonicalUtf8Size(), encoded.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `canonical snapshot matches portable H037 fixture`() {
        val expected = checkNotNull(javaClass.getResource("/fixtures/h037-context-snapshot-v1.json"))
            .readText()
            .trim()
        val actual = ContextSnapshotCodec.encode(snapshot(listOf(observation(1)), request()))

        assertEquals(expected, actual)
    }

    private fun observation(
        id: Int,
        key: String = "state.value",
        source: String = "source_one",
        value: Boolean = true,
        claim: ContextObservationClaim = ContextObservationClaim.Known(ContextValue.BooleanValue(value)),
        privacy: ContextPrivacyClassification = ContextPrivacyClassification.PUBLIC,
        observedAt: Long = 90,
        expiresAt: Long = 110,
    ): ContextObservation = successfulObservation(
        ContextObservation.create(
            observationId = successfulObservation(
                ContextObservationId.parse("${id.toString(16).padStart(8, '0')}-0000-4000-8000-000000000000"),
            ),
            sourceType = successfulObservation(ContextSourceType.parse("test.source")),
            sourceReference = successfulObservation(ContextSourceReference.parse(source)),
            key = successfulObservation(ContextKey.parse(key)),
            claim = claim,
            confidence = successfulObservation(ContextConfidence.create(900)),
            privacyClassification = privacy,
            observedAt = time(observedAt),
            expiresAt = time(expiresAt),
        ),
    )

    private fun request(
        now: Long = 100,
        keys: List<ContextKey>? = null,
        privacy: Set<ContextPrivacyClassification> = ContextPrivacyClassification.entries.toSet(),
        budget: ContextSnapshotBudget = budget(),
    ): ContextSnapshotRequest = successful(
        ContextSnapshotRequest.create(time(now), keys, privacy, budget),
    )

    private fun budget(
        maxItems: Int = 32,
        evidencePerItem: Int = 8,
        totalEvidence: Int = 64,
        bytes: Int = 65_536,
    ): ContextSnapshotBudget = successful(
        ContextSnapshotBudget.create(maxItems, evidencePerItem, totalEvidence, bytes),
    )

    private fun snapshot(
        observations: Collection<ContextObservation>,
        request: ContextSnapshotRequest,
    ): ContextSnapshot = successful(ContextSnapshotEngine.build(observations, request))

    private fun ContextSnapshot.omissionCount(reason: ContextSnapshotOmissionReason): Int =
        omissions.firstOrNull { it.reason == reason }?.count ?: 0

    private fun key(value: String): ContextKey = successfulObservation(ContextKey.parse(value))

    private fun text(value: String): ContextText = successfulObservation(ContextText.create(value))

    private fun time(value: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(value)) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> error("invalid test time")
    }

    private fun valueKey(value: ContextValue): String = "${value.wireType}:${value.canonicalValue}"

    private fun <T> successful(result: ContextSnapshotResult<T>): T {
        assertTrue(result is ContextSnapshotResult.Success)
        return (result as ContextSnapshotResult.Success).value
    }

    private fun <T> successfulObservation(result: ContextObservationResult<T>): T {
        assertTrue(result is ContextObservationResult.Success)
        return (result as ContextObservationResult.Success).value
    }
}
