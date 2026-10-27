package io.redi.robot.architecture.evidence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BaselineEvidenceManifestTest {
    private val manifestText = locateManifest().readText()

    @Test
    fun `accepted T001 through T032 baseline parses with complete immutable references`() {
        val manifest = BaselineEvidenceManifestParser.parse(manifestText)

        assertEquals("T001-T032", manifest.checkpointId)
        assertEquals("0bcf536cdaae3b5c9c22247bb093ac6fc68cff26", manifest.checkpointCommit)
        assertEquals("dfeb86b1627cdc7ee2a05057e9987138d154daaf", manifest.checkpointTree)
        assertEquals("ee35539cd1985f64f9bbf62dd9b386316a3e4a1e", manifest.architectureCommit)
        assertEquals(139, manifest.repositoryFileCount)
        assertEquals(30, manifest.evidence.size)
        assertEquals(30, manifest.evidence.map { it.path }.toSet().size)
        assertTrue(manifest.evidence.any { it.path == "docs/SPARK_TASKS.md" })
        assertTrue(manifest.evidence.any { it.path == "docs/T032_M2_ACCEPTANCE_RUN_2026-08-24_25.md" })
        assertTrue(manifest.evidence.any { it.path.endsWith("t032-hardware-manual-drive-2026-08-24.json") })
        assertFalse(manifest.runtimeBehaviorModified)
        assertFalse(manifest.historyResigned)
        assertFalse(manifest.hardwareReverified)
    }

    @Test
    fun `unsupported schema is rejected`() {
        val invalid = manifestText.replaceFirst("\"schemaVersion\": \"1.0\"", "\"schemaVersion\": \"2.0\"")
        assertThrows(BaselineEvidenceParseException::class.java) {
            BaselineEvidenceManifestParser.parse(invalid)
        }
    }

    @Test
    fun `malformed immutable object id is rejected`() {
        val invalid = manifestText.replaceFirst(
            "\"commit\": \"0bcf536cdaae3b5c9c22247bb093ac6fc68cff26\"",
            "\"commit\": \"mutable-name\"",
        )
        assertThrows(BaselineEvidenceParseException::class.java) {
            BaselineEvidenceManifestParser.parse(invalid)
        }
    }

    @Test
    fun `duplicate evidence path is rejected`() {
        val entry = """
            {
                  "path": "app/src/test/resources/fixtures/t005-malformed.json",
                  "blob": "7dda86704ed09eb9f1ea385e5b30ae5fb45e97fb"
                }
        """.trimIndent()
        val invalid = manifestText.replaceFirst(entry, "$entry,\n    $entry")
        assertThrows(BaselineEvidenceParseException::class.java) {
            BaselineEvidenceManifestParser.parse(invalid)
        }
    }

    @Test
    fun `path traversal is rejected`() {
        val invalid = manifestText.replaceFirst(
            "app/src/test/resources/fixtures/t005-malformed.json",
            "../t005-malformed.json",
        )
        assertThrows(BaselineEvidenceParseException::class.java) {
            BaselineEvidenceManifestParser.parse(invalid)
        }
    }

    private fun locateManifest(): File {
        val candidates = listOf(
            File("architecture/evidence/t001-t032-baseline.json"),
            File("../architecture/evidence/t001-t032-baseline.json"),
            File("../../architecture/evidence/t001-t032-baseline.json"),
        )
        return candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate architecture/evidence/t001-t032-baseline.json")
    }
}

private data class BaselineEvidenceManifest(
    val checkpointId: String,
    val checkpointCommit: String,
    val checkpointTree: String,
    val architectureCommit: String,
    val repositoryFileCount: Int,
    val evidence: List<LockedEvidence>,
    val runtimeBehaviorModified: Boolean,
    val historyResigned: Boolean,
    val hardwareReverified: Boolean,
)

private data class LockedEvidence(val path: String, val blob: String)

private class BaselineEvidenceParseException(message: String) : IllegalArgumentException(message)

private object BaselineEvidenceManifestParser {
    private val objectIdPattern = Regex("^[0-9a-f]{40}$")
    private val allowedEvidenceRoots = listOf("docs/", "app/src/test/resources/fixtures/")

    fun parse(text: String): BaselineEvidenceManifest {
        val root = runCatching { Json.parseToJsonElement(text) }
            .getOrElse { fail("Invalid JSON") }
            .asObject("root")
        root.requireExactKeys(
            "root",
            "schemaVersion",
            "checkpointId",
            "lockedByTask",
            "taskRange",
            "repositoryCheckpoint",
            "architectureBaseline",
            "coverage",
            "evidence",
            "claims",
        )

        requireValue(root.string("schemaVersion") == "1.0", "Unsupported schemaVersion")
        requireValue(root.string("checkpointId") == "T001-T032", "Unexpected checkpointId")
        requireValue(root.string("lockedByTask") == "H002", "Unexpected locking task")

        val taskRange = root.obj("taskRange").also {
            it.requireExactKeys("taskRange", "first", "last", "count", "ledgerPath")
        }
        requireValue(taskRange.string("first") == "T001", "Task range must begin at T001")
        requireValue(taskRange.string("last") == "T032", "Task range must end at T032")
        requireValue(taskRange.int("count") == 32, "Task count must be 32")
        requireSafeRelativePath(taskRange.string("ledgerPath"))

        val checkpoint = root.obj("repositoryCheckpoint").also {
            it.requireExactKeys("repositoryCheckpoint", "commit", "tree", "parent", "committedAt", "subject")
        }
        val checkpointCommit = checkpoint.objectId("commit")
        val checkpointTree = checkpoint.objectId("tree")
        checkpoint.objectId("parent")
        requireValue(checkpoint.string("committedAt").isNotBlank(), "Missing checkpoint timestamp")
        requireValue(checkpoint.string("subject").isNotBlank(), "Missing checkpoint subject")

        val architecture = root.obj("architectureBaseline").also {
            it.requireExactKeys("architectureBaseline", "task", "commit", "tree", "acceptanceRecordPath")
        }
        requireValue(architecture.string("task") == "H001", "Architecture baseline must be H001")
        val architectureCommit = architecture.objectId("commit")
        architecture.objectId("tree")
        requireSafeRelativePath(architecture.string("acceptanceRecordPath"))

        val coverage = root.obj("coverage").also {
            it.requireExactKeys("coverage", "repositoryFileCount", "evidenceRoots")
        }
        val repositoryFileCount = coverage.int("repositoryFileCount")
        requireValue(repositoryFileCount > 0, "Repository file count must be positive")
        val roots = coverage.array("evidenceRoots").mapIndexed { index, element ->
            val item = element.asObject("evidenceRoots[$index]")
            item.requireExactKeys("evidenceRoots[$index]", "path", "fileCount")
            EvidenceRoot(item.string("path"), item.int("fileCount"))
        }
        requireValue(roots.map { it.path }.toSet().size == roots.size, "Duplicate evidence root")
        requireValue(roots.all { it.fileCount > 0 }, "Evidence root counts must be positive")

        val evidence = root.array("evidence").mapIndexed { index, element ->
            val item = element.asObject("evidence[$index]")
            item.requireExactKeys("evidence[$index]", "path", "blob")
            val path = item.string("path")
            requireSafeRelativePath(path)
            requireValue(allowedEvidenceRoots.any(path::startsWith), "Evidence path outside locked roots: $path")
            LockedEvidence(path, item.objectId("blob"))
        }
        requireValue(evidence.isNotEmpty(), "Evidence index must not be empty")
        requireValue(evidence.map { it.path }.toSet().size == evidence.size, "Duplicate evidence path")
        requireValue(roots.sumOf { it.fileCount } == evidence.size, "Evidence root counts do not match index")
        roots.forEach { rootEntry ->
            val prefix = rootEntry.path.trimEnd('/') + "/"
            requireValue(
                evidence.count { it.path.startsWith(prefix) } == rootEntry.fileCount,
                "Evidence count mismatch for ${rootEntry.path}",
            )
        }

        val claims = root.obj("claims").also {
            it.requireExactKeys("claims", "runtimeBehaviorModified", "historyResigned", "hardwareReverified")
        }
        val runtimeBehaviorModified = claims.boolean("runtimeBehaviorModified")
        val historyResigned = claims.boolean("historyResigned")
        val hardwareReverified = claims.boolean("hardwareReverified")
        requireValue(!runtimeBehaviorModified, "H002 cannot modify runtime behavior")
        requireValue(!historyResigned, "H002 cannot re-sign history")
        requireValue(!hardwareReverified, "H002 cannot claim new hardware verification")

        return BaselineEvidenceManifest(
            checkpointId = "T001-T032",
            checkpointCommit = checkpointCommit,
            checkpointTree = checkpointTree,
            architectureCommit = architectureCommit,
            repositoryFileCount = repositoryFileCount,
            evidence = evidence,
            runtimeBehaviorModified = runtimeBehaviorModified,
            historyResigned = historyResigned,
            hardwareReverified = hardwareReverified,
        )
    }

    private fun requireSafeRelativePath(path: String) {
        val segments = path.split('/')
        requireValue(
            path.isNotBlank() &&
                !path.startsWith('/') &&
                '\\' !in path &&
                segments.none { it.isBlank() || it == "." || it == ".." },
            "Unsafe relative path: $path",
        )
    }

    private fun JsonElement.asObject(path: String): JsonObject =
        this as? JsonObject ?: fail("Expected object at $path")

    private fun JsonObject.requireExactKeys(path: String, vararg expected: String) {
        val expectedKeys = expected.toSet()
        requireValue(keys == expectedKeys, "Unexpected or missing fields at $path")
    }

    private fun JsonObject.obj(key: String): JsonObject =
        get(key)?.asObject(key) ?: fail("Missing object: $key")

    private fun JsonObject.array(key: String): JsonArray =
        get(key) as? JsonArray ?: fail("Missing array: $key")

    private fun JsonObject.string(key: String): String {
        val primitive = get(key) as? JsonPrimitive ?: fail("Missing string: $key")
        requireValue(primitive.isString && primitive.content.isNotBlank(), "Invalid string: $key")
        return primitive.content
    }

    private fun JsonObject.int(key: String): Int =
        (get(key) as? JsonPrimitive)?.intOrNull ?: fail("Invalid integer: $key")

    private fun JsonObject.boolean(key: String): Boolean =
        (get(key) as? JsonPrimitive)?.booleanOrNull ?: fail("Invalid boolean: $key")

    private fun JsonObject.objectId(key: String): String = string(key).also {
        requireValue(objectIdPattern.matches(it), "Invalid immutable object ID: $key")
    }

    private fun requireValue(condition: Boolean, message: String) {
        if (!condition) fail(message)
    }

    private fun fail(message: String): Nothing = throw BaselineEvidenceParseException(message)

    private data class EvidenceRoot(val path: String, val fileCount: Int)
}
