package io.redi.robot.acceptance

import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarHttpMethod
import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarReadOnlyProfile
import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarReadOnlySandbox
import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarReadOnlyTransport
import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarReadRequest
import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarSandboxResult
import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarTransportResult
import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarWireRequest
import io.redi.robot.adapter.service.googlecalendar.GoogleCalendarWireResponse
import io.redi.robot.capability.api.CapabilityEffect
import io.redi.robot.capability.api.CapabilityEnvelopeResult
import io.redi.robot.capability.api.CapabilityPermissionIntent
import io.redi.robot.capability.api.MonotonicTimestampMs
import io.redi.robot.context.api.ContextObservation
import io.redi.robot.context.api.ContextObservationClaim
import io.redi.robot.context.api.ContextObservationResult
import io.redi.robot.context.api.ContextPrivacyClassification
import io.redi.robot.context.api.ContextSourceReference
import io.redi.robot.context.api.ContextUnknownReason
import io.redi.robot.context.runtime.ContextSnapshot
import io.redi.robot.context.runtime.ContextSnapshotBudget
import io.redi.robot.context.runtime.ContextSnapshotCodec
import io.redi.robot.context.runtime.ContextSnapshotEngine
import io.redi.robot.context.runtime.ContextSnapshotOmissionReason
import io.redi.robot.context.runtime.ContextSnapshotRequest
import io.redi.robot.context.runtime.ContextSnapshotResult
import io.redi.robot.context.sources.CoarsePresenceAcquisition
import io.redi.robot.context.sources.CoarsePresenceContextSource
import io.redi.robot.context.sources.CoarsePresenceRegion
import io.redi.robot.context.sources.CoarsePresenceRequest
import io.redi.robot.context.sources.LocalTimeContextSource
import io.redi.robot.context.sources.LocalTimeSample
import io.redi.robot.context.sources.TimePresenceObservationBatch
import io.redi.robot.context.sources.TimePresenceSourceResult
import io.redi.robot.service.auth.CredentialBoundaryResult
import io.redi.robot.service.auth.CredentialDocumentReadResult
import io.redi.robot.service.auth.CredentialDocumentResetResult
import io.redi.robot.service.auth.CredentialDocumentWriteResult
import io.redi.robot.service.auth.CredentialEpochMillis
import io.redi.robot.service.auth.CredentialModelResult
import io.redi.robot.service.auth.ProtectedServiceCredentialStorage
import io.redi.robot.service.auth.ServiceCredentialBoundary
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** H045 acceptance evidence only. It does not compose any production runtime or external account. */
class M4PersonalContextAcceptanceTest {
    @Test
    fun `time and denied presence flow through H037 with privacy budgets and exact expiry`() {
        val timeBatch = sourceSuccess(
            LocalTimeContextSource.observe(
                sourceSuccess(
                    LocalTimeSample.create(
                        Instant.parse("2026-09-01T10:08:00Z").toEpochMilli(),
                        "UTC",
                        monotonic(1_000),
                    ),
                ),
                source("milestone4_clock_acceptance"),
            ),
        )
        val presenceBatch = deniedPresence()
        val observations = timeBatch.observations + presenceBatch.observations

        val fresh = snapshot(
            observations,
            now = 1_000,
            privacy = setOf(ContextPrivacyClassification.PRIVATE, ContextPrivacyClassification.RESTRICTED),
            maximumItems = 5,
        )
        assertEquals(5, fresh.items.size)
        assertEquals(
            ContextUnknownReason.PERMISSION_DENIED,
            (presenceBatch.observations.single().claim as ContextObservationClaim.Unknown).reason,
        )

        val privateOnly = snapshot(
            observations,
            now = 1_000,
            privacy = setOf(ContextPrivacyClassification.PRIVATE),
            maximumItems = 5,
        )
        assertEquals(4, privateOnly.items.size)
        assertEquals(1, privateOnly.omissionCount(ContextSnapshotOmissionReason.PRIVACY_FILTERED))

        val budgeted = snapshot(
            observations,
            now = 1_000,
            privacy = setOf(ContextPrivacyClassification.PRIVATE, ContextPrivacyClassification.RESTRICTED),
            maximumItems = 2,
        )
        assertEquals(2, budgeted.items.size)
        assertEquals(3, budgeted.omissionCount(ContextSnapshotOmissionReason.ITEM_BUDGET))

        val presenceExpired = snapshot(
            observations,
            now = 31_000,
            privacy = setOf(ContextPrivacyClassification.PRIVATE, ContextPrivacyClassification.RESTRICTED),
            maximumItems = 5,
        )
        assertEquals(4, presenceExpired.items.size)
        assertFalse(presenceExpired.items.any { it.key.value == "presence.authorized_region" })
        assertTrue(
            snapshot(
                observations,
                now = 61_000,
                privacy = setOf(ContextPrivacyClassification.PRIVATE, ContextPrivacyClassification.RESTRICTED),
                maximumItems = 5,
            ).items.isEmpty(),
        )
        assertNonAuthority(timeBatch)
        assertNonAuthority(presenceBatch)
        assertFalse(ContextSnapshotCodec.encode(fresh).contains("authorization", ignoreCase = true))
    }

    @Test
    fun `selected Calendar service remains one bounded read then remote before local revoke`() {
        val events = mutableListOf<String>()
        val storage = AcceptanceCredentialStorage(events)
        val transport = AcceptanceCalendarTransport(events)
        val sandbox = GoogleCalendarReadOnlySandbox(ServiceCredentialBoundary(storage), transport)
        val token = "test-only-test-only".toByteArray(StandardCharsets.UTF_8)

        val connection = calendarSuccess(
            sandbox.connect(
                token,
                setOf(GoogleCalendarReadOnlyProfile.OAUTH_SCOPE),
                epoch(1_000),
                epoch(10_000),
            ),
        )
        token.fill(0)
        val read = calendarSuccess(
            sandbox.read(
                connection,
                calendarSuccess(GoogleCalendarReadRequest.create(100, 200, 2)),
                epoch(2_000),
            ),
        )

        assertEquals(CapabilityEffect.OBSERVE, GoogleCalendarReadOnlyProfile.descriptor.effect)
        assertEquals(CapabilityPermissionIntent.OBSERVE, GoogleCalendarReadOnlyProfile.descriptor.permissionIntent)
        assertEquals("https://www.googleapis.com/auth/calendar.events.readonly", GoogleCalendarReadOnlyProfile.OAUTH_SCOPE)
        assertEquals(GoogleCalendarHttpMethod.GET, transport.lastRequest?.method)
        assertEquals(2, read.eventCount)
        assertEquals(0, read.retainedEventPayloads)
        assertFalse(read.toString().contains("Private acceptance event"))
        assertFalse(connection.grantsTrust)
        assertFalse(connection.grantsAuthorization)
        assertFalse(connection.establishesConnection)
        assertFalse(connection.registersProvider)
        assertFalse(connection.executesCapability)

        events.clear()
        val revoked = calendarSuccess(sandbox.revoke(connection, epoch(3_000)))
        assertEquals(listOf("remote_revoke", "local_write"), events)
        assertTrue(revoked.remoteRevoked)
        assertTrue(revoked.localCredentialRevoked)
        assertEquals(
            CredentialBoundaryResult.Revoked,
            ServiceCredentialBoundary(storage).withCredential(connection.credentialId, epoch(3_001)) {
                error("revoked credential must never be exposed")
            },
        )
        assertFalse((storage.content ?: ByteArray(0)).containsSequence("test-only-test-only".toByteArray()))
        assertFalse(revoked.grantsAuthorization)
        assertFalse(revoked.executesCapability)
    }

    @Test
    fun `M4 production sources contain no hidden persistence background tracking or action UI`() {
        val contextRoots = listOf(
            sourceRoot("context/api"),
            sourceRoot("context/runtime"),
            sourceRoot("context/provider"),
            sourceRoot("context/sources"),
            sourceRoot("device/phone/sensors"),
            sourceRoot("feature/controlcenter"),
        )
        val sourceText = contextRoots.flatMap { root ->
            root.walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.toList()
        }.joinToString("\n")
        listOf(
            "SharedPreferences",
            "DataStore",
            "RoomDatabase",
            "SQLiteDatabase",
            "FileOutputStream",
            "WorkManager",
            "AlarmManager",
            "BOOT_COMPLETED",
        ).forEach { prohibited ->
            assertFalse("Hidden M4 persistence/background token: $prohibited", sourceText.contains(prohibited))
        }

        val screen = sourceFile("feature/controlcenter/ControlCenterScreen.kt").readText()
        assertTrue(screen.contains("onRetry"))
        listOf("onConnect", "onAuthorize", "onExecute", "onEditSource", "onApprove").forEach {
            assertFalse("Control Center action callback found: $it", screen.contains(it))
        }

        val manifest = repositoryFile("app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.ACCESS_COARSE_LOCATION"))
        assertFalse(manifest.contains("android.permission.ACCESS_BACKGROUND_LOCATION"))
        assertFalse(manifest.contains("android.intent.action.BOOT_COMPLETED"))
        assertFalse(manifest.contains("androidx.work"))
    }

    @Test
    fun `only the ADR-selected service adapter exists and generic M4 boundaries stay vendor neutral`() {
        val adapterRoot = sourceRoot("adapter/service")
        val adapterDirectories = adapterRoot.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.sorted()
        assertEquals(listOf("googlecalendar"), adapterDirectories)

        val genericRoots = listOf(
            sourceRoot("context/api"),
            sourceRoot("context/runtime"),
            sourceRoot("context/provider"),
            sourceRoot("context/sources"),
            sourceRoot("service/api"),
            sourceRoot("service/auth"),
            sourceRoot("feature/controlcenter"),
        )
        val generic = genericRoots.flatMap { root ->
            root.walkTopDown().filter { it.extension == "kt" }.map { it.readText() }.toList()
        }.joinToString("\n")
        listOf(
            "com.google.android",
            "google.calendar",
            "calendar.events.list",
            "BluetoothGatt",
            "FED9",
            "body.looi",
        ).forEach { prohibited ->
            assertFalse("Generic M4 vendor/transport leakage: $prohibited", generic.contains(prohibited, true))
        }
    }

    @Test
    fun `H036 through H044 evidence is complete dated and privacy redacted`() {
        val documents = listOf(
            "H036_CONTEXT_OBSERVATION_CONTRACT.md",
            "H037_CONTEXT_SNAPSHOT_ENGINE.md",
            "H038_ACTIVE_PROVIDER_CONTEXT.md",
            "H039_PHONE_SENSOR_DEVICE_ADAPTER.md",
            "H040_SERVICE_CONNECTOR_CONTRACT.md",
            "H041_CONNECTOR_CREDENTIAL_BOUNDARY.md",
            "H042_CONTROL_CENTER_SHELL.md",
            "H043_READ_ONLY_CONNECTOR_FEASIBILITY.md",
            "H044_TIME_PRESENCE_CONTEXT_SOURCES.md",
        )
        documents.forEach { name ->
            val text = documentationFile(name).readText()
            assertTrue("$name is not complete", text.replace("`", "").contains("Status: DONE"))
            assertTrue("$name lacks a completion date", Regex("20[0-9]{2}-[0-9]{2}-[0-9]{2}").containsMatchIn(text))
        }

        val calendarEvidence = documentationFile("H043_READ_ONLY_CONNECTOR_FEASIBILITY.md").readText()
        assertFalse(Regex("[A-Z0-9._%+-]+@(gmail|googlemail|outlook|hotmail|yahoo)\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
            .containsMatchIn(calendarEvidence))
        assertTrue(calendarEvidence.contains("payload=discarded"))
        val presenceEvidence = documentationFile("H044_TIME_PRESENCE_CONTEXT_SOURCES.md").readText()
        assertTrue(presenceEvidence.contains("location=redacted"))
        assertFalse(Regex("-?[0-9]{1,3}\\.[0-9]{5,}\\s*,\\s*-?[0-9]{1,3}\\.[0-9]{5,}")
            .containsMatchIn(presenceEvidence))
    }

    private fun deniedPresence(): TimePresenceObservationBatch {
        val region = sourceSuccess(CoarsePresenceRegion.create(0.0, 0.0, 10_000.0))
        val request = sourceSuccess(
            CoarsePresenceRequest.create(source("milestone4_presence_acceptance"), region, 10_000, 30_000),
        )
        return sourceSuccess(
            CoarsePresenceContextSource.observe(
                request,
                CoarsePresenceAcquisition.PermissionDenied,
                monotonic(1_000),
            ),
        )
    }

    private fun snapshot(
        observations: List<ContextObservation>,
        now: Long,
        privacy: Set<ContextPrivacyClassification>,
        maximumItems: Int,
    ): ContextSnapshot = snapshotSuccess(
        ContextSnapshotEngine.build(
            observations,
            snapshotSuccess(
                ContextSnapshotRequest.create(
                    now = monotonic(now),
                    requestedKeys = null,
                    allowedPrivacyClassifications = privacy,
                    budget = snapshotSuccess(
                        ContextSnapshotBudget.create(maximumItems, 1, maximumItems, 8_192),
                    ),
                ),
            ),
        ),
    )

    private fun ContextSnapshot.omissionCount(reason: ContextSnapshotOmissionReason): Int =
        omissions.firstOrNull { it.reason == reason }?.count ?: 0

    private fun assertNonAuthority(batch: TimePresenceObservationBatch) {
        assertFalse(batch.persistsHistory)
        assertFalse(batch.grantsTrust)
        assertFalse(batch.grantsPermission)
        assertFalse(batch.grantsAuthorization)
        assertFalse(batch.grantsSafetyApproval)
        assertFalse(batch.grantsExecutionAuthority)
    }

    private fun source(value: String): ContextSourceReference = when (val result = ContextSourceReference.parse(value)) {
        is ContextObservationResult.Success -> result.value
        is ContextObservationResult.Failure -> error("invalid acceptance source")
    }

    private fun monotonic(value: Long): MonotonicTimestampMs = when (val result = MonotonicTimestampMs.create(value)) {
        is CapabilityEnvelopeResult.Success -> result.value
        is CapabilityEnvelopeResult.Failure -> error("invalid acceptance monotonic time")
    }

    private fun epoch(value: Long): CredentialEpochMillis =
        (CredentialEpochMillis.create(value) as CredentialModelResult.Success).value

    private fun <T> sourceSuccess(result: TimePresenceSourceResult<T>): T =
        (result as TimePresenceSourceResult.Success).value

    private fun <T> snapshotSuccess(result: ContextSnapshotResult<T>): T =
        (result as ContextSnapshotResult.Success).value

    private fun <T> calendarSuccess(result: GoogleCalendarSandboxResult<T>): T =
        (result as GoogleCalendarSandboxResult.Success).value

    private fun sourceRoot(relative: String): File = listOf(
        File("src/main/java/io/redi/robot/$relative"),
        File("app/src/main/java/io/redi/robot/$relative"),
        File("../app/src/main/java/io/redi/robot/$relative"),
    ).first { it.isDirectory }

    private fun sourceFile(relative: String): File = File(sourceRoot(relative.substringBeforeLast('/')), relative.substringAfterLast('/'))

    private fun repositoryFile(relative: String): File = listOf(
        File(relative),
        File("../$relative"),
        File("../../$relative"),
    ).first { it.isFile }

    private fun documentationFile(name: String): File = repositoryFile("docs/$name")

    private class AcceptanceCredentialStorage(
        private val events: MutableList<String>,
    ) : ProtectedServiceCredentialStorage {
        var content: ByteArray? = null

        override fun read(): CredentialDocumentReadResult = content?.let {
            CredentialDocumentReadResult.Content(it.copyOf())
        } ?: CredentialDocumentReadResult.Missing

        override fun writeAtomically(plaintext: ByteArray): CredentialDocumentWriteResult {
            events += "local_write"
            content?.fill(0)
            content = plaintext.copyOf()
            return CredentialDocumentWriteResult.COMMITTED
        }

        override fun reset(): CredentialDocumentResetResult {
            content?.fill(0)
            content = null
            return CredentialDocumentResetResult.RESET
        }
    }

    private class AcceptanceCalendarTransport(
        private val events: MutableList<String>,
    ) : GoogleCalendarReadOnlyTransport {
        var lastRequest: GoogleCalendarWireRequest? = null

        override fun execute(
            request: GoogleCalendarWireRequest,
            accessToken: ByteArray,
        ): GoogleCalendarTransportResult<GoogleCalendarWireResponse> {
            lastRequest = request
            val body = """{"kind":"calendar#events","items":[{"summary":"Private acceptance event"},{}]}"""
                .toByteArray(StandardCharsets.UTF_8)
            val response = (
                GoogleCalendarWireResponse.copyOf(200, body) as GoogleCalendarSandboxResult.Success
                ).value
            return GoogleCalendarTransportResult.Success(response)
        }

        override fun revoke(accessToken: ByteArray): GoogleCalendarTransportResult<Int> {
            events += "remote_revoke"
            return GoogleCalendarTransportResult.Success(200)
        }
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean = indices.any { start ->
        start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] }
    }
}
