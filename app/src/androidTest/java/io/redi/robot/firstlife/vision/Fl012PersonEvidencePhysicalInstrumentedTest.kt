package io.redi.robot.firstlife.vision

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.pm.PackageManager
import io.redi.robot.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** User-staged Samsung acceptance. It records text-only evidence and never captures media. */
@RunWith(AndroidJUnit4::class)
class Fl012PersonEvidencePhysicalInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()
    private val sceneTimeoutMs: Long by lazy {
        InstrumentationRegistry.getArguments().getString(ARG_SCENE_TIMEOUT_MS)?.toLongOrNull()
            ?.coerceIn(SCENE_TIMEOUT_MS, MAX_STAGED_SCENE_TIMEOUT_MS)
            ?: SCENE_TIMEOUT_MS
    }

    @Test
    fun stagedSceneProducesRequestedBoundedEvidence() {
        val expected = InstrumentationRegistry.getArguments().getString(ARG_EXPECTED_SCENE)
        assumeTrue("Run explicitly with -e $ARG_EXPECTED_SCENE <scene>", expected != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission(android.Manifest.permission.INTERNET, context.packageName),
        )
        composeRule.onNodeWithTag("hihugo-open-camera").performScrollTo().performClick()
        var completed = false
        try {
            when (expected) {
                "EMPTY" -> waitForEvidence("NO_PERSON")
                "LOSS" -> {
                    waitForEvidence("STABLE_PERSON")
                    waitForEvidence("NO_PERSON")
                }
                "LEFT", "CENTER", "RIGHT" -> waitForEvidence("$expected · STABLE_PERSON")
                "MULTIPLE" -> waitForEvidence("MULTIPLE_PEOPLE")
                "PERFORMANCE" -> verifyPerformanceBudget()
                else -> error("Unsupported redacted FL012 scene")
            }
            completed = true
        } finally {
            runCatching { composeRule.onNodeWithTag("hihugo-close-camera").performScrollTo().performClick() }
        }
        if (completed) {
            composeRule.onNodeWithTag("hihugo-person-evidence").assertDoesNotExist()
            composeRule.onNodeWithTag("hihugo-person-detector-performance").assertDoesNotExist()
        }
    }

    private fun waitForEvidence(expected: String) {
        try {
            composeRule.waitUntil(timeoutMillis = sceneTimeoutMs) {
                nodeText("hihugo-person-evidence").contains(expected)
            }
        } catch (error: Throwable) {
            throw AssertionError(
                "Expected redacted evidence '$expected'; last='${nodeText("hihugo-person-evidence")}', " +
                    "performance='${nodeText("hihugo-person-detector-performance")}'",
                error,
            )
        }
        val text = nodeText("hihugo-person-evidence")
        assertTrue(text.contains(expected))
        assertTrue(!text.contains("image", ignoreCase = true))
        assertTrue(!text.contains("face", ignoreCase = true))
        println("FL012_REDACTED evidence='$text'")
    }

    private fun verifyPerformanceBudget() {
        composeRule.waitUntil(timeoutMillis = PERFORMANCE_TIMEOUT_MS) {
            val text = nodeText("hihugo-person-detector-performance")
            Regex("n=(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { it >= MIN_SAMPLES } == true
        }
        val text = nodeText("hihugo-person-detector-performance")
        val median = Regex("median=(\\d+) ms").find(text)!!.groupValues[1].toLong()
        val p95 = Regex("p95=(\\d+) ms").find(text)!!.groupValues[1].toLong()
        assertTrue("median=$median exceeds 100 ms", median <= 100)
        assertTrue("p95=$p95 exceeds 150 ms", p95 <= 150)
        println("FL012_REDACTED performance='$text'")
    }

    private fun nodeText(tag: String): String = runCatching {
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString(" ") { it.text }
    }.getOrDefault("")

    companion object {
        const val ARG_EXPECTED_SCENE = "fl012ExpectedScene"
        const val ARG_SCENE_TIMEOUT_MS = "fl012SceneTimeoutMs"
        const val MIN_SAMPLES = 30
        const val SCENE_TIMEOUT_MS = 30_000L
        const val MAX_STAGED_SCENE_TIMEOUT_MS = 90_000L
        const val PERFORMANCE_TIMEOUT_MS = 45_000L
    }
}
