plugins {
    id("com.android.application") version "8.8.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
}

// A publication inventory of resolved coordinates, not a bundled dependency cache.
tasks.register("writeEvaluationDependencyInventory") {
    doLast {
        val hashes = mutableMapOf<java.io.File, String>()
        fun digest(file: java.io.File): String = hashes.getOrPut(file) {
            java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
        }
        val records = subprojects.sortedBy { it.name }.flatMap { module ->
            listOf("debugRuntimeClasspath", "debugUnitTestRuntimeClasspath", "debugAndroidTestRuntimeClasspath").flatMap { scope ->
                module.configurations.findByName(scope)?.resolvedConfiguration?.resolvedArtifacts.orEmpty()
                    .sortedBy { it.moduleVersion.id.toString() }.map { artifact ->
                        val id = artifact.moduleVersion.id
                        mapOf("module" to module.name, "scope" to scope, "group" to id.group,
                            "artifact" to id.name, "version" to id.version,
                            "file" to artifact.file.name, "sha256" to digest(artifact.file))
                    }
            }
        }
        val output = layout.buildDirectory.file("evaluation-dependencies.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(records)) + "\n")
        println("Resolved dependency artifact records: ${records.size}")
    }
}
