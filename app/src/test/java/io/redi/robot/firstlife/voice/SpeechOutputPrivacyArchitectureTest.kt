package io.redi.robot.firstlife.voice

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechOutputPrivacyArchitectureTest {
    @Test
    fun `speech domain is Android free and platform adapter has no cloud or LOOI coupling`() {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val domain = File(root, "io/redi/robot/firstlife/voice")
        val domainText = domain.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val adapter = File(root, "io/redi/robot/platform/voice/android/AndroidLocalTextToSpeech.kt").readText()
        assertFalse(domainText.contains("import android."))
        assertFalse(adapter.contains("looi", ignoreCase = true))
        assertFalse(adapter.contains("http", ignoreCase = true))
        assertFalse(adapter.contains("Bluetooth"))
        assertTrue(adapter.contains("isNetworkConnectionRequired"))
        assertTrue(adapter.contains("sq-AL") || adapter.contains("LOCAL_ALBANIAN_LOCALE"))
    }

    @Test
    fun `fixed localized resources contain only accepted Albanian templates`() {
        val root = sequenceOf(File("src/main/res"), File("app/src/main/res")).first { it.exists() }
        val defaultText = File(root, "values/strings.xml").readText()
        val albanianText = File(root, "values-sq/strings.xml").readText()
        listOf(defaultText, albanianText).forEach { text ->
            assertTrue(text.contains("Përshëndetje. Unë jam HUGO. Kush je ti?"))
            assertTrue(text.contains("Gëzohem që të njoh, %1\$s."))
        }
    }
}
