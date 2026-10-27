package io.redi.robot.firstlife.vision

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPrivacyArchitectureTest {
    @Test
    fun `vision domain is Android free and adapter exposes preview plus analysis only`() {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val domain = File(root, "io/redi/robot/firstlife/vision").walkTopDown()
            .filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val adapter = File(root, "io/redi/robot/platform/vision/android/AndroidCameraXSession.kt").readText()
        assertFalse(domain.contains("import android."))
        assertFalse(domain.contains("ImageProxy"))
        assertFalse(adapter.contains("ImageCapture"))
        assertFalse(adapter.contains("VideoCapture"))
        assertFalse(adapter.contains("MediaRecorder"))
        assertFalse(adapter.contains("looi", ignoreCase = true))
        assertFalse(adapter.contains("http", ignoreCase = true))
        assertTrue(adapter.contains("STRATEGY_KEEP_ONLY_LATEST"))
        assertTrue(adapter.contains("DEFAULT_FRONT_CAMERA"))
        assertFalse(adapter.contains("DEFAULT_BACK_CAMERA"))
        assertTrue(adapter.contains("image.close()"))
    }
}
