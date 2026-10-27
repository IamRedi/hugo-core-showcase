package io.redi.robot.skills.local

import io.redi.robot.capability.api.CapabilityActorKind
import io.redi.robot.capability.api.CapabilityArgumentName
import io.redi.robot.capability.api.CapabilityArgumentValue
import io.redi.robot.capability.api.CapabilityContractReference
import io.redi.robot.capability.api.CapabilityData
import io.redi.robot.capability.api.CapabilityDescriptorResult
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.skills.api.LocalSkillClass
import io.redi.robot.skills.api.SkillDefinition
import io.redi.robot.skills.api.SkillModelResult
import io.redi.robot.skills.api.SkillReference
import io.redi.robot.skills.api.SkillRegistry

/** Reviewed H050 catalog. It contains observations and light-off only; motion is intentionally absent. */
object HugoLocalSkillCatalog {
    val definitions: List<SkillDefinition> = listOf(
        definition(
            skill = "hugo.local.connection_status@1.0",
            capability = "provider.connection.status@1.0",
            skillClass = LocalSkillClass.OBSERVATION,
            output = "hugo.contract.provider_connection_status_output@1.0",
            maximumDurationMs = 2_000L,
        ),
        definition(
            skill = "hugo.local.lower_contact_status@1.0",
            capability = "safety.lower_contact.status@1.0",
            skillClass = LocalSkillClass.OBSERVATION,
            output = "hugo.contract.lower_contact_status_output@1.0",
            maximumDurationMs = 2_000L,
        ),
        definition(
            skill = "hugo.local.light_off@1.0",
            capability = "body.light.control@1.0",
            skillClass = LocalSkillClass.NON_MOTION_CONTROL,
            input = lightOffInput(),
            maximumDurationMs = 2_000L,
        ),
    ).sortedBy { it.reference }

    val registry: SkillRegistry = model(SkillRegistry.create(definitions))

    private fun definition(
        skill: String,
        capability: String,
        skillClass: LocalSkillClass,
        input: CapabilityData? = null,
        output: String? = null,
        maximumDurationMs: Long,
    ): SkillDefinition = model(
        SkillDefinition.create(
            reference = model(SkillReference.parse(skill)),
            capability = identity(CapabilityReference.parse(capability)),
            actor = CapabilityActorKind.LOCAL_USER,
            skillClass = skillClass,
            inputContract = input?.contract,
            fixedInput = input,
            outputContract = output?.let { descriptor(CapabilityContractReference.parse(it)) },
            maximumDurationMs = maximumDurationMs,
            cancellationRequired = true,
        ),
    )

    private fun lightOffInput(): CapabilityData = envelope(
        CapabilityData.create(
            descriptor(CapabilityContractReference.parse("hugo.contract.body_light_input@1.0")),
            mapOf(
                envelope(CapabilityArgumentName.parse("enabled")) to CapabilityArgumentValue.Flag(false),
            ),
        ),
    )

    private fun <T> model(result: SkillModelResult<T>): T = when (result) {
        is SkillModelResult.Success -> result.value
        is SkillModelResult.Failure -> error("invalid built-in skill: ${result.error}")
    }

    private fun <T> identity(result: CapabilityIdentityResult<T>): T = when (result) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> error("invalid built-in capability: ${result.error}")
    }

    private fun <T> descriptor(result: CapabilityDescriptorResult<T>): T = when (result) {
        is CapabilityDescriptorResult.Success -> result.value
        is CapabilityDescriptorResult.Failure -> error("invalid built-in contract: ${result.error}")
    }

    private fun <T> envelope(result: CapabilityEnvelopeResult<T>): T = when (result) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> error("invalid built-in capability data: ${result.error}")
    }
}
