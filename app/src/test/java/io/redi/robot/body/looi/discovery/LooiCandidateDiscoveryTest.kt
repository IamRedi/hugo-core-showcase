package io.redi.robot.body.looi.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LooiCandidateDiscoveryTest {

    @Test
    fun `identifies candidate when multiple reliable clues align`() {
        val candidates = rankLooiScanCandidates(
            listOf(
                LooiScanObservation(
                    deviceId = "device-a",
                    displayName = "LOOI MobileBot",
                    serviceUuids = setOf("0000FED0-0000-1000-8000-00805F9B34FB"),
                    manufacturerIdentifiers = setOf("unknown"),
                ),
            ),
        )

        assertEquals(1, candidates.size)
        val candidate = candidates[0]
        assertEquals("device-a", candidate.deviceId)
        assertEquals(LooiCandidateStatus.CANDIDATE, candidate.status)
        assertTrue(candidate.profileHints.contains(LooiProfileHint.PROFILE_A))
        assertTrue(candidate.reasons.isNotEmpty())
        assertTrue(candidate.confidence > 0f)
    }

    @Test
    fun `does not trust unknown or weak name-only scan as match`() {
        val candidates = rankLooiScanCandidates(
            listOf(
                LooiScanObservation(
                    deviceId = "device-b",
                    displayName = "Kitchen Lamp",
                ),
            ),
        )

        assertEquals(1, candidates.size)
        assertEquals(LooiCandidateStatus.NON_MATCH, candidates[0].status)
    }

    @Test
    fun `returns ambiguous when profile A and profile B indicators both appear`() {
        val candidates = rankLooiScanCandidates(
            listOf(
                LooiScanObservation(
                    deviceId = "device-c",
                    displayName = "LOOI Hybrid",
                    serviceUuids = setOf(
                        "0000FED0-0000-1000-8000-00805F9B34FB",
                        "0000FED7-0000-1000-8000-00805F9B34FB",
                    ),
                ),
            ),
        )

        assertEquals(1, candidates.size)
        val candidate = candidates[0]
        assertEquals(LooiCandidateStatus.AMBIGUOUS, candidate.status)
        assertEquals(
            setOf(LooiProfileHint.PROFILE_A, LooiProfileHint.PROFILE_B),
            candidate.profileHints,
        )
    }

    @Test
    fun `missing display name still classifies overlap service evidence as unresolved candidate`() {
        val candidates = rankLooiScanCandidates(
            listOf(
                LooiScanObservation(
                    deviceId = "device-d",
                    displayName = null,
                    serviceUuids = setOf("0000FED5-0000-1000-8000-00805F9B34FB"),
                ),
            ),
        )

        assertEquals(1, candidates.size)
        val candidate = candidates[0]
        assertEquals(LooiCandidateStatus.UNKNOWN, candidate.status)
        assertEquals(LooiProfileHint.PROFILE_UNRESOLVED, candidate.profileHints.first())
        assertEquals("device-d", candidate.deviceId)
    }

    @Test
    fun `merges duplicate observations by device and deduplicates candidates`() {
        val candidates = rankLooiScanCandidates(
            listOf(
                LooiScanObservation(
                    deviceId = "device-e",
                    displayName = "LOOI Mini",
                    serviceUuids = setOf("0000FED0-0000-1000-8000-00805F9B34FB"),
                ),
                LooiScanObservation(
                    deviceId = "device-e",
                    displayName = "LOOI Mini",
                    serviceUuids = setOf("0000FED0-0000-1000-8000-00805F9B34FB"),
                ),
            ),
        )

        assertEquals(1, candidates.size)
        assertEquals("device-e", candidates[0].deviceId)
        assertEquals(6, candidates[0].score)
    }

    @Test
    fun `conflicting evidence without profile split can remain known candidate`() {
        val candidates = rankLooiScanCandidates(
            listOf(
                LooiScanObservation(
                    deviceId = "device-f",
                    displayName = "LOOI Bridge",
                    manufacturerIdentifiers = setOf("looi official"),
                ),
                LooiScanObservation(
                    deviceId = "device-g",
                    displayName = "LOOI Bridge",
                    serviceUuids = setOf("0000FEF0-0000-1000-8000-00805F9B34FB"),
                ),
                LooiScanObservation(
                    deviceId = "device-h",
                    displayName = "Random Device",
                    serviceUuids = setOf("0000FED5-0000-1000-8000-00805F9B34FB"),
                ),
            ),
        )

        assertEquals(3, candidates.size)
        val candidateByManufacturerName = candidates.first { it.deviceId == "device-f" }
        val candidateByService = candidates.first { it.deviceId == "device-g" }
        val unresolvedByOverlap = candidates.first { it.deviceId == "device-h" }

        assertEquals(LooiCandidateStatus.CANDIDATE, candidateByManufacturerName.status)
        assertEquals(LooiCandidateStatus.UNKNOWN, candidateByService.status)
        assertEquals(LooiCandidateStatus.UNKNOWN, unresolvedByOverlap.status)
        assertEquals(
            setOf(LooiProfileHint.PROFILE_UNRESOLVED),
            unresolvedByOverlap.profileHints,
        )
        assertEquals(setOf(LooiProfileHint.PROFILE_UNRESOLVED), candidateByService.profileHints)
        assertFalse(candidateByManufacturerName.reasons.isEmpty())
        assertFalse(candidateByService.reasons.isEmpty())
        assertEquals("Random Device", unresolvedByOverlap.displayName)
    }

    @Test
    fun `scores and ranks multiple candidates deterministically`() {
        val candidates = rankLooiScanCandidates(
            listOf(
                LooiScanObservation(
                    deviceId = "device-i",
                    displayName = "Ambient",
                    serviceUuids = setOf("0000FED5-0000-1000-8000-00805F9B34FB"),
                ),
                LooiScanObservation(
                    deviceId = "device-j",
                    displayName = "LOOI Hub",
                    serviceUuids = setOf("0000FED0-0000-1000-8000-00805F9B34FB"),
                    manufacturerIdentifiers = setOf("looi official"),
                ),
            ),
        )

        assertEquals("device-j", candidates[0].deviceId)
    }
}
