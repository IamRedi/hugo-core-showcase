package io.redi.robot.skills.api

import io.redi.robot.capability.api.CapabilityReference
import java.util.Collections

class SkillRegistry private constructor(definitions: List<SkillDefinition>) {
    private val byReference: Map<SkillReference, SkillDefinition> = definitions.associateBy { it.reference }
    val snapshot: SkillRegistrySnapshot = SkillRegistrySnapshot(definitions)

    fun findExact(reference: SkillReference): SkillDefinition? = byReference[reference]

    fun findByCapability(capability: CapabilityReference): List<SkillDefinition> =
        snapshot.definitions.filter { it.capability == capability }

    companion object {
        const val MAX_SKILLS = 64

        fun create(definitions: List<SkillDefinition>): SkillModelResult<SkillRegistry> = when {
            definitions.isEmpty() -> SkillModelResult.Failure(SkillModelError.EMPTY_REGISTRY)
            definitions.size > MAX_SKILLS -> SkillModelResult.Failure(SkillModelError.REGISTRY_TOO_LARGE)
            definitions.map { it.reference }.distinct().size != definitions.size ->
                SkillModelResult.Failure(SkillModelError.DUPLICATE_SKILL)
            else -> SkillModelResult.Success(SkillRegistry(definitions))
        }
    }
}

class SkillRegistrySnapshot internal constructor(definitions: List<SkillDefinition>) {
    val schemaVersion: String = "1.0"
    val definitions: List<SkillDefinition> = Collections.unmodifiableList(definitions.sortedBy { it.reference })

    val installed: Boolean get() = false
    val enabled: Boolean get() = false
    val authorized: Boolean get() = false
    val executing: Boolean get() = false
}
