package io.redi.robot.firstlife.voice

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePrivacyArchitectureTest {
    @Test
    fun `voice domain is Android-free and contains no persistence or transport coupling`() {
        val root = locate("app/src/main/java/io/redi/robot/firstlife/voice")
        val source = root.walkTopDown().filter(File::isFile).joinToString("\n") { it.readText() }
        listOf("import android.", "Bluetooth", "GATT", "FED9", "SharedPreferences", "RoomDatabase", "FileOutputStream").forEach {
            assertFalse("forbidden voice-domain coupling: $it", source.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `Android adapter has no recording file logging or cloud SDK path`() {
        val source = locate("app/src/main/java/io/redi/robot/platform/voice/android/AndroidOnDeviceSpeechRecognizer.kt").readText()
        listOf("MediaRecorder", "AudioRecord", "FileOutputStream", "Log.", "println(", "http://", "https://", "apiKey").forEach {
            assertFalse("forbidden Android voice behavior: $it", source.contains(it, ignoreCase = true))
        }
        assertTrue(source.contains("createOnDeviceSpeechRecognizer"))
        assertTrue(source.contains("EXTRA_PREFER_OFFLINE"))
    }

    private fun locate(relative: String): File = sequenceOf(File(relative), File("../$relative"), File("../../$relative"))
        .firstOrNull(File::exists) ?: error("Unable to locate $relative")
}
