package io.redi.robot.body.looi.discovery

import io.redi.robot.body.looi.ble.scan.BleScanResult

data class LooiScanObservation(
    val deviceId: String,
    val displayName: String?,
    val serviceUuids: Set<String> = emptySet(),
    val manufacturerIdentifiers: Set<String> = emptySet(),
    val rssi: Int? = null,
)

fun BleScanResult.toLooiScanObservation(): LooiScanObservation = LooiScanObservation(
    deviceId = id,
    displayName = displayName,
    rssi = rssi,
)

enum class LooiProfileHint {
    PROFILE_A,
    PROFILE_B,
    PROFILE_UNRESOLVED,
}

enum class LooiCandidateStatus {
    CANDIDATE,
    AMBIGUOUS,
    UNKNOWN,
    NON_MATCH,
}

enum class LooiEvidenceSource {
    DISPLAY_NAME,
    SERVICE_UUID,
    MANUFACTURER,
}

data class LooiEvidence(
    val source: LooiEvidenceSource,
    val value: String,
    val weight: Int,
    val detail: String,
)

data class LooiScanCandidate(
    val deviceId: String,
    val displayName: String?,
    val status: LooiCandidateStatus,
    val confidence: Float,
    val score: Int,
    val profileHints: Set<LooiProfileHint>,
    val reasons: List<LooiEvidence>,
)

private data class AggregatedLooiObservation(
    val deviceId: String,
    val displayNames: Set<String>,
    val serviceUuids: Set<String>,
    val manufacturerIdentifiers: Set<String>,
)

private const val NAME_LOOI_MIN_SCORE = 2
private const val NAME_LOOI_STRONG_SCORE = 4
private const val SERVICE_PROFILE_SCORE = 4
private const val SERVICE_OVERLAP_PROFILE_SCORE = 2
private const val MANUFACTURER_SCORE = 3
private const val CANDIDATE_MIN_SCORE = 5

private val PROFILE_A_UNIQUE_PREFIXES = setOf("fed0", "fed1", "fed2", "fed9", "feda")
private val PROFILE_B_UNIQUE_PREFIXES = setOf("fed4", "fed6", "fed7")
private val PROFILE_SHARED_PREFIXES = setOf("fed5", "fed8", "fef0")

private fun normalizeUuid(uuid: String): String = uuid.lowercase().replace("-", "")

private fun matchesAnyServicePrefix(uuids: Set<String>, prefixes: Set<String>): Boolean = uuids
    .asSequence()
    .map(::normalizeUuid)
    .any { normalized -> prefixes.any { prefix -> normalized.contains(prefix) } }

private fun profileHintsForObservation(observation: AggregatedLooiObservation): Set<LooiProfileHint> {
    val hasProfileA = matchesAnyServicePrefix(observation.serviceUuids, PROFILE_A_UNIQUE_PREFIXES)
    val hasProfileB = matchesAnyServicePrefix(observation.serviceUuids, PROFILE_B_UNIQUE_PREFIXES)
    return when {
        hasProfileA && hasProfileB -> setOf(LooiProfileHint.PROFILE_A, LooiProfileHint.PROFILE_B)
        hasProfileA -> setOf(LooiProfileHint.PROFILE_A)
        hasProfileB -> setOf(LooiProfileHint.PROFILE_B)
        matchesAnyServicePrefix(observation.serviceUuids, PROFILE_SHARED_PREFIXES) -> setOf(LooiProfileHint.PROFILE_UNRESOLVED)
        else -> emptySet()
    }
}

private fun evaluateNameEvidence(names: Set<String>): List<LooiEvidence> = names
    .filter { it.isNotBlank() }
    .flatMap { name ->
        if (name.contains("looi", ignoreCase = true)) {
            val weight = if (name.equals("looi", ignoreCase = true)) NAME_LOOI_STRONG_SCORE else NAME_LOOI_MIN_SCORE
            listOf(
                LooiEvidence(
                    source = LooiEvidenceSource.DISPLAY_NAME,
                    value = name,
                    weight = weight,
                    detail = "display-name contains LOOI pattern",
                ),
            )
        } else {
            emptyList()
        }
    }

private fun evaluateServiceEvidence(observation: AggregatedLooiObservation): List<LooiEvidence> = observation.serviceUuids
    .map(::normalizeUuid)
    .flatMap { uuid ->
        when {
            PROFILE_A_UNIQUE_PREFIXES.any { uuid.contains(it) } ->
                listOf(
                    LooiEvidence(
                        source = LooiEvidenceSource.SERVICE_UUID,
                        value = uuid,
                        weight = SERVICE_PROFILE_SCORE,
                        detail = "service UUID matches profile-A indicator",
                    ),
                )
            PROFILE_B_UNIQUE_PREFIXES.any { uuid.contains(it) } ->
                listOf(
                    LooiEvidence(
                        source = LooiEvidenceSource.SERVICE_UUID,
                        value = uuid,
                        weight = SERVICE_PROFILE_SCORE,
                        detail = "service UUID matches profile-B indicator",
                    ),
                )
            PROFILE_SHARED_PREFIXES.any { uuid.contains(it) } ->
                listOf(
                    LooiEvidence(
                        source = LooiEvidenceSource.SERVICE_UUID,
                        value = uuid,
                        weight = SERVICE_OVERLAP_PROFILE_SCORE,
                        detail = "service UUID has possible LOOI overlap signature",
                    ),
                )
            else -> emptyList()
        }
    }

private fun evaluateManufacturerEvidence(manufacturers: Set<String>): List<LooiEvidence> = manufacturers
    .filter { it.isNotBlank() }
    .flatMap { manufacturer ->
        if (manufacturer.contains("looi", ignoreCase = true)) {
            listOf(
                LooiEvidence(
                    source = LooiEvidenceSource.MANUFACTURER,
                    value = manufacturer,
                    weight = MANUFACTURER_SCORE,
                    detail = "manufacturer identifier contains LOOI-like value",
                ),
            )
        } else {
            emptyList()
        }
    }

private fun confidenceFrom(score: Int): Float = (score / 10f).coerceIn(0f, 1f)

private fun classifyCandidate(score: Int, profileHints: Set<LooiProfileHint>): LooiCandidateStatus {
    return when {
        score == 0 -> LooiCandidateStatus.NON_MATCH
        profileHints.size >= 2 -> LooiCandidateStatus.AMBIGUOUS
        score >= CANDIDATE_MIN_SCORE -> LooiCandidateStatus.CANDIDATE
        else -> LooiCandidateStatus.UNKNOWN
    }
}

private fun aggregateObservations(observations: List<LooiScanObservation>): List<AggregatedLooiObservation> = observations
    .groupBy { it.deviceId }
    .map { (deviceId, entries) ->
        AggregatedLooiObservation(
            deviceId = deviceId,
            displayNames = entries.mapNotNull { it.displayName }.toSet(),
            serviceUuids = entries.flatMap { it.serviceUuids }.toSet(),
            manufacturerIdentifiers = entries.flatMap { it.manufacturerIdentifiers }.toSet(),
        )
    }

private fun displayNameForCandidate(observation: AggregatedLooiObservation): String? =
    observation.displayNames.firstOrNull { it.isNotBlank() }

private fun statusRank(status: LooiCandidateStatus): Int = when (status) {
    LooiCandidateStatus.CANDIDATE -> 0
    LooiCandidateStatus.AMBIGUOUS -> 1
    LooiCandidateStatus.UNKNOWN -> 2
    LooiCandidateStatus.NON_MATCH -> 3
}

fun rankLooiScanCandidates(observations: List<LooiScanObservation>): List<LooiScanCandidate> {
    return aggregateObservations(observations).map { observation ->
        val nameEvidence = evaluateNameEvidence(observation.displayNames)
        val serviceEvidence = evaluateServiceEvidence(observation)
        val manufacturerEvidence = evaluateManufacturerEvidence(observation.manufacturerIdentifiers)
        val reasons = (nameEvidence + serviceEvidence + manufacturerEvidence)
            .sortedWith(compareByDescending<LooiEvidence> { it.weight }.thenBy { it.value })
        val score = reasons.sumOf { it.weight }
        val profileHints = profileHintsForObservation(observation)
        val status = classifyCandidate(score, profileHints)
        val confidence = if (status == LooiCandidateStatus.NON_MATCH) {
            0f
        } else {
            confidenceFrom(score) * (if (status == LooiCandidateStatus.AMBIGUOUS) 0.9f else 1f)
        }

        LooiScanCandidate(
            deviceId = observation.deviceId,
            displayName = displayNameForCandidate(observation),
            status = status,
            confidence = confidence,
            score = score,
            profileHints = profileHints,
            reasons = reasons,
        )
    }.sortedWith(
        compareBy<LooiScanCandidate> { statusRank(it.status) }
            .thenByDescending { it.confidence }
            .thenByDescending { it.score }
            .thenBy { it.deviceId },
    )
}
