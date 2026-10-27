package io.redi.robot.feature.hihugo.ui

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HugoSvgFaceAssetTest {
    private val projectRoot = sequenceOf(File("."), File("app")).first { File(it, "src/main").exists() }

    @Test
    fun `selected identity SVG is exact local inert source`() {
        val asset = File(projectRoot, "src/main/assets/${HugoFaceSvgAsset.PATH}")
        val source = asset.readText()
        val digest = MessageDigest.getInstance("SHA-256").digest(asset.readBytes()).joinToString("") { "%02x".format(it) }

        assertEquals(369_761L, asset.length())
        assertEquals(HugoFaceSvgAsset.SHA256, digest)
        assertTrue(source.contains("viewBox=\"0 0 2048 2048\""))
        assertTrue(source.contains("<c2pa:manifest>"))
        assertFalse(source.contains("<script", ignoreCase = true))
        assertFalse(Regex("\\s(?:xlink:)?href\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(source))
        assertFalse(source.contains("<image", ignoreCase = true))

        val raster = File(projectRoot, "src/main/res/drawable-nodpi/hugo_face.png")
        val rasterDigest = MessageDigest.getInstance("SHA-256").digest(raster.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(252_610L, raster.length())
        assertEquals(HugoFaceSvgAsset.RASTER_SHA256, rasterDigest)

        val bootRaster = File(projectRoot, "src/main/res/drawable-nodpi/hugo_face_boot.png")
        val bootRasterDigest = MessageDigest.getInstance("SHA-256").digest(bootRaster.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals(121_259L, bootRaster.length())
        assertEquals(HugoFaceSvgAsset.BOOT_RASTER_SHA256, bootRasterDigest)

        val derivedLayers = listOf(
            Triple("hugo_face_base.png", 122_930L, HugoFaceSvgAsset.BASE_RASTER_SHA256),
            Triple("hugo_left_eye.png", 51_712L, HugoFaceSvgAsset.LEFT_EYE_RASTER_SHA256),
            Triple("hugo_right_eye.png", 53_157L, HugoFaceSvgAsset.RIGHT_EYE_RASTER_SHA256),
            Triple("hugo_left_eye_socket.png", 32_171L, HugoFaceSvgAsset.LEFT_SOCKET_RASTER_SHA256),
            Triple("hugo_left_eye_globe.png", 35_236L, HugoFaceSvgAsset.LEFT_GLOBE_RASTER_SHA256),
            Triple("hugo_right_eye_socket.png", 30_873L, HugoFaceSvgAsset.RIGHT_SOCKET_RASTER_SHA256),
            Triple("hugo_right_eye_globe.png", 39_126L, HugoFaceSvgAsset.RIGHT_GLOBE_RASTER_SHA256),
            Triple("hugo_left_eye_ready.png", 49_119L, HugoFaceSvgAsset.LEFT_READY_RASTER_SHA256),
            Triple("hugo_right_eye_ready.png", 50_708L, HugoFaceSvgAsset.RIGHT_READY_RASTER_SHA256),
            Triple("hugo_mouth.png", 6_331L, HugoFaceSvgAsset.MOUTH_RASTER_SHA256),
        )
        derivedLayers.forEach { (name, expectedLength, expectedDigest) ->
            val layer = File(projectRoot, "src/main/res/drawable-nodpi/$name")
            val layerDigest = MessageDigest.getInstance("SHA-256").digest(layer.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(expectedLength, layer.length())
            assertEquals(expectedDigest, layerDigest)
        }
    }

    @Test
    fun `renderer is Compose local-only and has no WebView surface`() {
        val renderer = File(
            projectRoot,
            "src/main/java/io/redi/robot/feature/hihugo/ui/HiHugoSvgFace.kt",
        ).readText()

        assertTrue(renderer.contains("R.drawable.hugo_face_base"))
        assertTrue(renderer.contains("R.drawable.hugo_left_eye"))
        assertTrue(renderer.contains("R.drawable.hugo_right_eye"))
        assertTrue(renderer.contains("R.drawable.hugo_left_eye_socket"))
        assertTrue(renderer.contains("R.drawable.hugo_left_eye_globe"))
        assertTrue(renderer.contains("R.drawable.hugo_left_eye_ready"))
        assertTrue(renderer.contains("R.drawable.hugo_mouth"))
        assertFalse(renderer.contains("WebView"))
        assertFalse(renderer.contains("javaScriptEnabled"))
        assertFalse(renderer.contains("http://"))
        assertFalse(renderer.contains("https://"))
        listOf("BOOT", "BLINK", "ATTEND", "THINK", "SPEAK", "OBSERVE", "READY", "NONE").forEach { motion ->
            assertTrue(renderer.contains("HiHugoMotion.$motion"))
        }
        assertTrue(renderer.contains("drawErrorOverlay"))
        assertTrue(renderer.contains("drawBlockedOverlay"))
        assertTrue(renderer.contains("OriginalHugoEye"))
        assertTrue(renderer.contains("transformOrigin = pivot"))
        assertTrue(renderer.contains("motionKind == HiHugoMotion.BOOT -> .10f"))
        assertTrue(renderer.contains("motionKind == HiHugoMotion.BLINK -> 1f - motion * .93f"))
        assertTrue(renderer.contains("gazeMovesInsideSocket"))
        assertTrue(renderer.contains("state == HiHugoFaceState.BODY_READY -> OriginalHugoLayer(readyDrawableId)"))
        assertFalse(renderer.contains("drawSpeakingOverlay"))
        assertFalse(renderer.contains("drawReadyOverlay"))
        assertFalse(renderer.contains("drawExpressiveEyes"))
        assertFalse(renderer.contains("eyeAlmondPath"))
        assertFalse(renderer.contains("Brush.radialGradient"))
        assertTrue(renderer.contains("listOf(.135f to true, .865f to false)"))
        assertFalse(renderer.contains("radius = unit * (.455f"))
    }
}
