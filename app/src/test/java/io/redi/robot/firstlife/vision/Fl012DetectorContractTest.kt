package io.redi.robot.firstlife.vision

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Fl012DetectorContractTest {
    private val projectRoot = sequenceOf(File("."), File("app")).first { File(it, "src/main").exists() }

    @Test
    fun `golden fixture is bounded representative and contains no media`() {
        val fixture = File(projectRoot, "src/test/resources/fixtures/fl012-person-evidence.json")
        val json = Json.parseToJsonElement(fixture.readText()).jsonObject
        assertEquals(0, json.getValue("no_person").jsonArray.size)
        assertEquals(1, json.getValue("left").jsonArray.size)
        assertEquals(1, json.getValue("center").jsonArray.size)
        assertEquals(1, json.getValue("right").jsonArray.size)
        assertEquals(2, json.getValue("multiple").jsonArray.size)
        assertTrue(fixture.parentFile!!.listFiles()!!.none { it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") })

        fun adapted(name: String) = PersonDetectorResultAdapter.adapt(
            json.getValue(name).jsonArray.map { element ->
                val item = element.jsonObject
                DetectorCandidate(
                    "person",
                    item.getValue("confidence").jsonPrimitive.content.toFloat(),
                    DetectorBoundingBoxPixels(
                        item.getValue("left").jsonPrimitive.content.toFloat() * 640,
                        item.getValue("top").jsonPrimitive.content.toFloat() * 480,
                        item.getValue("right").jsonPrimitive.content.toFloat() * 640,
                        item.getValue("bottom").jsonPrimitive.content.toFloat() * 480,
                    ),
                )
            },
            640,
            480,
        )
        assertEquals(0, adapted("no_person").size)
        assertEquals(1, adapted("left").size)
        assertEquals(0.20f, adapted("left").single().boundingBox.centerX, 0.0001f)
        assertEquals(2, adapted("multiple").size)
    }

    @Test
    fun `approved model digest size source and pinned dependency are exact`() {
        val model = File(projectRoot, "src/main/assets/models/efficientdet_lite0_int8_v1.tflite")
        val provenance = File(projectRoot, "src/main/assets/models/README.md").readText()
        val build = File(projectRoot, "build.gradle.kts").readText()
        val manifest = File(projectRoot, "src/main/AndroidManifest.xml").readText()
        assertEquals(4_602_795L, model.length())
        val approvedCompressedBytes = 495_413L + 22_510_915L + model.length()
        assertTrue(approvedCompressedBytes <= 27L * 1024L * 1024L)
        assertEquals(
            "0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb",
            MessageDigest.getInstance("SHA-256").digest(model.readBytes()).joinToString("") { "%02x".format(it) },
        )
        assertTrue(provenance.contains("storage.googleapis.com/mediapipe-models"))
        assertTrue(provenance.contains("Apache License 2.0"))
        val retainedLicense = File(projectRoot.canonicalFile.parentFile, "third_party/licenses/Apache-2.0.txt")
        assertTrue(retainedLicense.readText().contains("Apache License"))
        assertTrue(build.contains("com.google.mediapipe:tasks-vision:1.0.0"))
        assertFalse(build.contains("tasks-vision:latest"))
        assertTrue(manifest.contains("android.permission.INTERNET\" tools:node=\"remove\""))
        assertTrue(manifest.contains("android.permission.ACCESS_NETWORK_STATE\" tools:node=\"remove\""))
    }

    @Test
    fun `platform is local live stream person-only and domain cannot expose pixels or identity`() {
        val main = File(projectRoot, "src/main/java")
        val detector = File(main, "io/redi/robot/platform/vision/android/AndroidMediaPipePersonDetector.kt").readText()
        val domain = File(main, "io/redi/robot/firstlife/vision").walkTopDown()
            .filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertTrue(detector.contains("RunningMode.LIVE_STREAM"))
        assertTrue(detector.contains("setMaxResults(PersonDetectionObservation.MAX_RESULTS)"))
        assertTrue(detector.contains("setCategoryAllowlist(listOf(PERSON_CATEGORY))"))
        assertTrue(detector.contains("SCORE_THRESHOLD = 0.60f"))
        listOf("http", "cloud", "FaceDetector", "embedding", "recognition", "looi").forEach {
            assertFalse(detector.contains(it, ignoreCase = true))
        }
        listOf("import android.", "ImageProxy", "Bitmap", "MPImage", "FaceDetector", "faceEmbedding", "userName", "providerId").forEach {
            assertFalse(domain.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `detector boxes crossing frame edges are clipped but contradictory boxes remain invalid`() {
        val clipped = PersonDetectorResultAdapter.adapt(
            listOf(
                DetectorCandidate(
                    "person",
                    0.82f,
                    DetectorBoundingBoxPixels(-12f, -4f, 200f, 490f),
                ),
            ),
            640,
            480,
        ).single()
        assertTrue(clipped.boundingBox.isValid())
        assertEquals(0f, clipped.boundingBox.left, 0f)
        assertEquals(0f, clipped.boundingBox.top, 0f)
        assertEquals(1f, clipped.boundingBox.bottom, 0f)

        val contradictory = PersonDetectorResultAdapter.adapt(
            listOf(DetectorCandidate("person", 0.9f, DetectorBoundingBoxPixels(500f, 20f, 400f, 200f))),
            640,
            480,
        ).single()
        assertFalse(contradictory.boundingBox.isValid())
    }

}
