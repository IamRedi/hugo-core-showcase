package io.redi.robot.interaction.localintent

import io.redi.robot.capability.api.CapabilityArgumentName
import io.redi.robot.capability.api.CapabilityArgumentValue
import io.redi.robot.capability.api.CapabilityData
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.CapabilityIdentityResult
import io.redi.robot.capability.api.CapabilityReference
import io.redi.robot.capability.api.CapabilityContractReference
import io.redi.robot.capability.api.CapabilityDescriptorResult

/** Versioned, provider-neutral H046 command corpus. No phrase selects a provider or invokes a port. */
object HugoLocalIntentCorpus {
    val corpus: LocalIntentCorpus = model(
        LocalIntentCorpus.create(
            id = model(LocalIntentCorpusId.parse("hugo.local_intent")),
            version = 1,
            allowedLocales = setOf(locale("sq-AL"), locale("en-US")),
            rules = listOf(
                rule("stop.sq_al", "sq-AL", setOf("ndalo", "ndalo tani", "stop"), stop()),
                rule("connection_status.sq_al", "sq-AL", setOf("statusi i lidhjes", "a je i lidhur"), proposal("provider.connection.status@1.0")),
                rule("lower_contact_status.sq_al", "sq-AL", setOf("statusi i sensorëve të poshtëm"), proposal("safety.lower_contact.status@1.0")),
                rule("light_off.sq_al", "sq-AL", setOf("fike dritën"), lightOff()),
                rule("stop.en_us", "en-US", setOf("stop", "stop now"), stop()),
                rule("connection_status.en_us", "en-US", setOf("connection status", "are you connected"), proposal("provider.connection.status@1.0")),
                rule("lower_contact_status.en_us", "en-US", setOf("lower contact status"), proposal("safety.lower_contact.status@1.0")),
                rule("light_off.en_us", "en-US", setOf("light off", "turn the light off"), lightOff()),
            ),
        ),
    )

    private fun stop() = proposal(LocalIntentPolicy.STOP_CAPABILITY, priority = LocalIntentPriority.STOP)

    private fun lightOff(): LocalCapabilityProposal {
        val input = envelope(
            CapabilityData.create(
                descriptor(CapabilityContractReference.parse("hugo.contract.body_light_input@1.0")),
                mapOf(envelope(CapabilityArgumentName.parse("enabled")) to CapabilityArgumentValue.Flag(false)),
            ),
        )
        return proposal("body.light.control@1.0", input)
    }

    private fun proposal(
        capability: String,
        input: CapabilityData? = null,
        priority: LocalIntentPriority = LocalIntentPriority.NORMAL,
    ) = LocalCapabilityProposal(identity(CapabilityReference.parse(capability)), input, priority)

    private fun rule(
        id: String,
        locale: String,
        phrases: Set<String>,
        proposal: LocalCapabilityProposal,
    ): LocalIntentRule = model(
        LocalIntentRule.create(model(LocalIntentRuleId.parse(id)), locale(locale), phrases, proposal),
    )

    private fun locale(tag: String): LocalIntentLocale = model(LocalIntentLocale.parse(tag))

    private fun <T> model(result: LocalIntentModelResult<T>): T = when (result) {
        is LocalIntentModelResult.Success -> result.value
        is LocalIntentModelResult.Failure -> error("Invalid built-in local intent corpus: ${result.error}")
    }

    private fun <T> identity(result: CapabilityIdentityResult<T>): T = when (result) {
        is CapabilityIdentityResult.Success -> result.value
        is CapabilityIdentityResult.Failure -> error("Invalid built-in capability: ${result.error}")
    }

    private fun <T> descriptor(result: CapabilityDescriptorResult<T>): T = when (result) {
        is CapabilityDescriptorResult.Success -> result.value
        is CapabilityDescriptorResult.Failure -> error("Invalid built-in input contract: ${result.error}")
    }

    private fun <T> envelope(result: CapabilityEnvelopeResult<T>): T = when (result) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> error("Invalid built-in input: ${result.error}")
    }
}
