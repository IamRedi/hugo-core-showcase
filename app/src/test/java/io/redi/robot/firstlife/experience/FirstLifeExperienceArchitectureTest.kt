package io.redi.robot.firstlife.experience

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLifeExperienceArchitectureTest {
    @Test
    fun `composition domain has no Android vendor transport protocol or execution coupling`() {
        val root = sourceRoot()
        val source = File(root, "io/redi/robot/firstlife/experience")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .joinToString("\n") { it.readText(Charsets.UTF_8) }
        listOf(
            "import android.",
            "import androidx.",
            "io.redi.robot.body.looi",
            "io.redi.robot.platform",
            "Bluetooth",
            "Gatt",
            "FED8",
            "FED9",
            "encoder",
        ).forEach { forbidden ->
            assertFalse("experience composition leaked $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun `presentation snapshot carries booleans and typed state but no private payload types`() {
        val fields = FirstLifeExperienceSnapshot::class.java.declaredFields
        assertTrue(fields.isNotEmpty())
        fields.forEach { field ->
            val type = field.type.name
            assertFalse(type.contains("HugoIdentity"))
            assertFalse(type.contains("ProviderInstance"))
            assertFalse(type.contains("CameraFrame"))
            assertFalse(type.contains("BoundingBox"))
            assertFalse(type.contains("Transcript"))
        }
        assertFalse(FirstLifeExperienceSnapshot::class.java.declaredFields.any { it.type == String::class.java })
    }

    private fun sourceRoot(): File = listOf(
        File("src/main/java"),
        File("app/src/main/java"),
        File("../app/src/main/java"),
    ).first { it.isDirectory }
}
