package io.redi.robot.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArchitectureDependencyGuardTest {
    private val domainPackages = listOf("core", "safety")

    private val v2Boundaries = listOf(
        SourceBoundary("Showcase simulation domain", "showcase/domain"),
        SourceBoundary("HUGO identity", "hugo/identity"),
        SourceBoundary("First Life identity bootstrap", "firstlife/identity"),
        SourceBoundary("First Life interaction reducer", "firstlife/interaction"),
        SourceBoundary("First Life voice session", "firstlife/voice"),
        SourceBoundary("First Life vision session", "firstlife/vision"),
        SourceBoundary("First Life orientation controller", "firstlife/orientation"),
        SourceBoundary("First Life experience composition", "firstlife/experience"),
        SourceBoundary("First Life runtime coordinator", "firstlife/runtime"),
        SourceBoundary("HiHugo presentation reducer", "feature/hihugo/presentation"),
        SourceBoundary("capability API", "capability/api"),
        SourceBoundary("capability negotiation", "capability/negotiation"),
        SourceBoundary("adapter compatibility", "adapter/compatibility"),
        SourceBoundary("adapter registry", "adapter/registry"),
        SourceBoundary("adapter storage", "adapter/storage"),
        SourceBoundary("provider runtime", "provider/runtime"),
        SourceBoundary("provider calibration", "provider/calibration"),
        SourceBoundary("provider instance storage", "provider/storage"),
        SourceBoundary("provider diagnostics", "provider/diagnostic"),
        SourceBoundary("BLE connection wrapper", "platform/connection/ble"),
        SourceBoundary("trust domain", "trust/domain"),
        SourceBoundary("authorization runtime", "trust/runtime"),
        SourceBoundary("manifest domain", "manifest/domain"),
        SourceBoundary("manifest resolution", "manifest/resolution"),
        SourceBoundary("connection API", "connection/api"),
        SourceBoundary("context observation API", "context/api"),
        SourceBoundary("context snapshot runtime", "context/runtime"),
        SourceBoundary("active-provider context", "context/provider"),
        SourceBoundary("time and presence context sources", "context/sources"),
        SourceBoundary("local intent router", "interaction/localintent"),
        SourceBoundary("audio session reducer", "voice/session"),
        SourceBoundary("explicit audio capture", "voice/capture"),
        SourceBoundary("speech adapter API", "voice/api"),
        SourceBoundary("skill registry API", "skills/api"),
        SourceBoundary("local capability-backed skills", "skills/local"),
        SourceBoundary("AI gateway contract", "gateway/contract"),
        SourceBoundary("gateway authentication", "gateway/auth"),
        SourceBoundary("model policy runtime", "gateway/policy"),
        SourceBoundary("AI gateway client", "ai/gateway"),
        SourceBoundary("bounded conversation context", "conversation/context"),
        SourceBoundary("AI tool proposal validator", "ai/tools"),
        SourceBoundary("conversation turn runtime", "conversation/runtime"),
        SourceBoundary("memory domain", "memory/domain"),
        SourceBoundary("memory record storage", "memory/storage"),
        SourceBoundary("working memory", "memory/working"),
        SourceBoundary("episodic memory", "memory/episodic"),
        SourceBoundary("semantic memory", "memory/semantic"),
        SourceBoundary("personal memory", "memory/personal"),
        SourceBoundary("phone sensor domain", "device/phone/sensors"),
        SourceBoundary("service connector API", "service/api"),
        SourceBoundary("service credential boundary", "service/auth"),
        SourceBoundary("discovery API", "discovery/api"),
        SourceBoundary("discovery identity", "discovery/identity"),
    )

    private val forbiddenImportPrefixes = listOf(
        "android.",
        "androidx.",
        "okhttp3.",
        "retrofit2.",
        "io.ktor",
        "com.google.firebase",
        "com.squareup.moshi",
    )

    private val v2ForbiddenImportPrefixes = forbiddenImportPrefixes + listOf(
        "io.redi.robot.body.looi.",
        "io.redi.robot.platform.",
        "io.redi.robot.feature.",
        "io.redi.robot.ui.",
        "io.redi.robot.app.",
        "io.redi.robot.ai.",
        "io.redi.robot.voice.",
        "io.redi.robot.service.auth.",
        "io.redi.robot.credentials.",
        "io.redi.robot.security.secret.",
        "org.eclipse.paho.",
        "org.ros.",
        "com.",
    )

    private val v2ForbiddenReferences = listOf(
        "android.bluetooth.",
        "BluetoothGatt",
        "BluetoothDevice",
        "BluetoothGattCallback",
        "BluetoothAdapter",
        "BluetoothLeScanner",
        "FED0",
        "FED8",
        "FED9",
        "FE00",
        "FF02",
    )

    private val allowedBoundaryImports = mapOf(
        "voice/capture" to listOf("io.redi.robot.voice.session."),
        "voice/api" to listOf("io.redi.robot.voice.session.", "io.redi.robot.voice.capture."),
        "ai/tools" to listOf("io.redi.robot.ai.gateway."),
        "conversation/runtime" to listOf(
            "io.redi.robot.voice.session.",
            "io.redi.robot.voice.api.",
            "io.redi.robot.ai.gateway.",
        ),
        "gateway/policy" to listOf("io.redi.robot.ai.gateway."),
    )

    private val worldModelForbiddenRuntimeReferences = listOf(
        "android.bluetooth.",
        "BluetoothGatt",
        "BluetoothDevice",
        "BluetoothGattCallback",
        "FED0",
        "FED8",
        "FED9",
        "FE00",
        "FF02",
        "GattOperation",
    )

    @Test
    fun `domain packages contain no forbidden dependencies`() {
        val violations = detectForbiddenImportsInDomainPackages()
        assertTrue(
            "Expected no forbidden domain imports, found: ${
                violations.joinToString(separator = "\n") { it.imported }
            }",
            violations.isEmpty(),
        )
    }

    @Test
    fun `negative fixture shows forbidden import detection`() {
        val source = """
            package io.redi.robot.core

            import android.bluetooth.BluetoothDevice

            class ForbiddenDependencyFixture
        """.trimIndent()

        val violations = detectForbiddenImports(source, "sample.kt")
        assertEquals(1, violations.size)
        assertEquals("android.bluetooth.BluetoothDevice", violations.first().imported)
        assertEquals("sample.kt", violations.first().file)
    }

    @Test
    fun `H019 V2 pure packages contain no concrete adapter or platform dependency`() {
        val violations = v2Boundaries.flatMap(::detectBoundaryViolations)

        assertTrue(
            "Expected V2 pure packages to remain provider-neutral, found:\n${
                violations.joinToString(separator = "\n") { it.describe() }
            }",
            violations.isEmpty(),
        )
    }

    @Test
    fun `H019 negative fixtures detect LOOI Android and platform leakage`() {
        assertFixtureIsRejected("architecture/h019/generic-imports-looi.kt", "capability API")
        assertFixtureIsRejected("architecture/h019/capability-uses-bluetooth.kt", "capability API")
        assertFixtureIsRejected("architecture/h019/manifest-imports-ble-runtime.kt", "manifest domain")
    }

    @Test
    fun `H019 positive fixture remains provider neutral`() {
        val boundary = v2Boundaries.single { it.name == "capability API" }
        val violations = detectBoundaryViolations(
            boundary = boundary,
            source = fixtureText("architecture/h019/allowed-provider-neutral-contract.kt"),
            filePath = "allowed-provider-neutral-contract.kt",
        )

        assertTrue("Expected provider-neutral fixture to be accepted: $violations", violations.isEmpty())
    }

    @Test
    fun `H019 permits LOOI adapter dependency inward without a reverse edge`() {
        val looiFacade = sourceFile("body/looi/capability/LooiCompatibilityFacade.kt")
        assertTrue(
            "Expected the adapter-side facade to depend on generic capability contracts",
            looiFacade.readText(Charsets.UTF_8).contains("import io.redi.robot.capability.api."),
        )

        val genericToLooi = v2Boundaries
            .flatMap(::detectBoundaryViolations)
            .filter { it is ImportViolation && it.imported.startsWith("io.redi.robot.body.looi.") }
        assertTrue(
            "Generic HUGO packages must not create a reverse dependency on the LOOI adapter: $genericToLooi",
            genericToLooi.isEmpty(),
        )
    }

    @Test
    fun `H019 World Model documentation contains no vendor runtime protocol coupling`() {
        val worldModel = documentationFile("WORLD_MODEL_AND_AFFORDANCES.md")
        val violations = detectForbiddenReferences(
            source = worldModel.readText(Charsets.UTF_8),
            filePath = worldModel.path,
            forbiddenReferences = worldModelForbiddenRuntimeReferences,
        )

        assertTrue(
            "World Model documentation must not contain runtime protocol details: $violations",
            violations.isEmpty(),
        )
    }

    private fun detectForbiddenImportsInDomainPackages(): List<ImportViolation> {
        val sourceRoot = candidateSourceRoots().firstOrNull() ?: error("No Kotlin source root found")
        return domainPackages.flatMap { pkg ->
            val packageRoot = File(sourceRoot, "io/redi/robot/$pkg")
            if (!packageRoot.exists()) {
                emptyList()
            } else {
                packageRoot
                    .walkTopDown()
                    .filter { it.extension == "kt" }
                    .flatMap { file -> detectForbiddenImports(file.readText(), file.path).asSequence() }
                    .toList()
            }
        }
    }

    private fun detectForbiddenImports(source: String, filePath: String): List<ImportViolation> {
        return detectForbiddenImports(source, filePath, forbiddenImportPrefixes)
    }

    private fun detectForbiddenImports(
        source: String,
        filePath: String,
        forbiddenPrefixes: List<String>,
    ): List<ImportViolation> {
        return source
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith("import ") }
            .map { it.removePrefix("import ").substringBefore(" as ").trim() }
            .filter { it.isNotEmpty() }
            .filter { imported -> forbiddenPrefixes.any { imported.startsWith(it) } }
            .map { ImportViolation(file = filePath, imported = it) }
            .toList()
    }

    private fun detectBoundaryViolations(boundary: SourceBoundary): List<BoundaryViolation> {
        return sourceFiles(boundary.relativePath).flatMap { file ->
            detectBoundaryViolations(boundary, file.readText(Charsets.UTF_8), file.path)
        }
    }

    private fun detectBoundaryViolations(
        boundary: SourceBoundary,
        source: String,
        filePath: String,
    ): List<BoundaryViolation> {
        val allowed = allowedBoundaryImports[boundary.relativePath].orEmpty()
        val imports = detectForbiddenImports(source, filePath, v2ForbiddenImportPrefixes)
            .filterNot { violation -> allowed.any(violation.imported::startsWith) }
        return imports + detectForbiddenReferences(source, filePath, v2ForbiddenReferences)
    }

    private fun detectForbiddenReferences(
        source: String,
        filePath: String,
        forbiddenReferences: List<String>,
    ): List<ReferenceViolation> {
        return forbiddenReferences
            .filter { source.contains(it) }
            .map { ReferenceViolation(filePath, it) }
    }

    private fun assertFixtureIsRejected(path: String, boundaryName: String) {
        val boundary = v2Boundaries.single { it.name == boundaryName }
        val violations = detectBoundaryViolations(boundary, fixtureText(path), path)
        assertTrue("Expected $path to be rejected", violations.isNotEmpty())
    }

    private fun sourceFiles(relativePath: String): List<File> {
        val sourceRoot = candidateSourceRoots().firstOrNull() ?: error("No Kotlin source root found")
        val packageRoot = File(sourceRoot, "io/redi/robot/$relativePath")
        check(packageRoot.isDirectory) { "Expected source directory: ${packageRoot.path}" }
        return packageRoot
            .walkTopDown()
            .filter { it.extension == "kt" }
            .sortedBy { it.path }
            .toList()
    }

    private fun sourceFile(relativePath: String): File {
        val sourceRoot = candidateSourceRoots().firstOrNull() ?: error("No Kotlin source root found")
        return File(sourceRoot, "io/redi/robot/$relativePath").also {
            check(it.isFile) { "Expected source file: ${it.path}" }
        }
    }

    private fun fixtureText(relativePath: String): String {
        val fixture = candidateTestResourceRoots()
            .asSequence()
            .map { File(it, relativePath) }
            .firstOrNull { it.isFile }
            ?: error("No fixture found: $relativePath")
        return fixture.readText(Charsets.UTF_8)
    }

    private fun documentationFile(name: String): File {
        val candidates = listOf(File("../docs", name), File("docs", name), File("../../docs", name))
        return candidates.firstOrNull { it.isFile }
            ?: error("No documentation file found: $name")
    }

    private fun candidateSourceRoots(): List<File> {
        val candidates = listOf(
            File("src/main/java"), // when running in app module
            File("app/src/main/java"), // when running from repo root
            File("../app/src/main/java"), // when working directory shifts
        )
        return candidates.filter { it.exists() && it.isDirectory }
    }

    private fun candidateTestResourceRoots(): List<File> {
        val candidates = listOf(
            File("src/test/resources"), // when running in app module
            File("app/src/test/resources"), // when running from repo root
            File("../app/src/test/resources"), // when working directory shifts
        )
        return candidates.filter { it.exists() && it.isDirectory }
    }

    private data class SourceBoundary(val name: String, val relativePath: String)

    private sealed interface BoundaryViolation {
        fun describe(): String
    }

    private data class ImportViolation(val file: String, val imported: String) : BoundaryViolation {
        override fun describe(): String = "$file imports $imported"
    }

    private data class ReferenceViolation(val file: String, val reference: String) : BoundaryViolation {
        override fun describe(): String = "$file references $reference"
    }
}
