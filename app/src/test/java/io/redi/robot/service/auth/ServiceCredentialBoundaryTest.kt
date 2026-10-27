package io.redi.robot.service.auth

import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.service.api.ServiceAuthenticationSchemeReference
import io.redi.robot.service.api.ServiceAuthorizationScopeReference
import io.redi.robot.service.api.ServiceConnectorResult
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceCredentialBoundaryTest {
    @Test
    fun `store restore and bounded access preserve exact secret without granting authority`() {
        val storage = MemoryCredentialStorage()
        val boundary = ServiceCredentialBoundary(storage)
        val fixture = fixture(expiry = time(2_000))

        val stored = boundary.store(fixture.request, generation(0)).success()
        fixture.secret.close()
        val recreated = ServiceCredentialBoundary(storage)
        var callbackReference: ByteArray? = null
        val accessed = recreated.withCredential(fixture.id, time(1_500)) { transient ->
            callbackReference = transient
            transient.copyOf()
        }.success()

        assertArrayEquals(fixture.expectedSecret, accessed)
        assertTrue(callbackReference!!.all { it == 0.toByte() })
        assertEquals(1, stored.generation.value)
        assertFalse(stored.grantsTrust)
        assertFalse(stored.grantsAuthorization)
        assertFalse(stored.establishesConnection)
        assertFalse(stored.executesCapability)
        val snapshot = recreated.snapshot(time(1_500)).success()
        assertEquals(ServiceCredentialKnowledgeState.ACTIVE, snapshot.records.single().stateAt(time(1_500)))
        assertFalse(snapshot.grantsTrust)
        assertFalse(snapshot.grantsAuthorization)
        assertFalse(snapshot.establishesConnection)
        assertFalse(snapshot.executesCapability)
        assertFalse(snapshot.records.single().toString().contains(fixture.id.value))
    }

    @Test
    fun `expired credential fails closed and never enters callback`() {
        val storage = MemoryCredentialStorage()
        val fixture = fixture(expiry = time(1_100))
        val boundary = ServiceCredentialBoundary(storage)
        boundary.store(fixture.request, generation(0)).success()
        fixture.secret.close()
        var invoked = false

        val result = boundary.withCredential(fixture.id, time(1_100)) {
            invoked = true
        }

        assertEquals(CredentialBoundaryResult.Expired, result)
        assertFalse(invoked)
        assertEquals(
            ServiceCredentialKnowledgeState.EXPIRED,
            boundary.snapshot(time(1_100)).success().records.single().stateAt(time(1_100)),
        )
    }

    @Test
    fun `logout removes secret material and revoked record fails closed after recreation`() {
        val storage = MemoryCredentialStorage()
        val fixture = fixture(expiry = null)
        val boundary = ServiceCredentialBoundary(storage)
        boundary.store(fixture.request, generation(0)).success()
        fixture.secret.close()
        assertTrue(storage.content!!.containsSequence(fixture.expectedSecret))

        val evidence = boundary.logout(fixture.id, generation(1), revision(1), time(2_000)).success()

        assertEquals(ServiceCredentialKnowledgeState.REVOKED, evidence.state)
        assertFalse(storage.content!!.containsSequence(fixture.expectedSecret))
        var invoked = false
        val access = ServiceCredentialBoundary(storage).withCredential(fixture.id, time(2_001)) {
            invoked = true
        }
        assertEquals(CredentialBoundaryResult.Revoked, access)
        assertFalse(invoked)
        val metadata = boundary.snapshot(time(2_001)).success().records.single()
        assertEquals(ServiceCredentialRevocationReason.LOGOUT, metadata.revocationReason)
        assertEquals(ServiceCredentialKnowledgeState.REVOKED, metadata.stateAt(time(2_001)))
    }

    @Test
    fun `explicit revoke uses optimistic generation and record revision`() {
        val storage = MemoryCredentialStorage()
        val fixture = fixture(expiry = null)
        val boundary = ServiceCredentialBoundary(storage)
        boundary.store(fixture.request, generation(0)).success()

        assertEquals(
            CredentialBoundaryResult.StaleGeneration,
            boundary.revoke(
                fixture.id,
                generation(0),
                revision(1),
                time(2_000),
                ServiceCredentialRevocationReason.USER_REVOKED,
            ),
        )
        assertEquals(
            CredentialBoundaryResult.StaleRecordRevision,
            boundary.revoke(
                fixture.id,
                generation(1),
                revision(2),
                time(2_000),
                ServiceCredentialRevocationReason.USER_REVOKED,
            ),
        )
        boundary.revoke(
            fixture.id,
            generation(1),
            revision(1),
            time(2_000),
            ServiceCredentialRevocationReason.USER_REVOKED,
        ).success()
        assertEquals(
            CredentialBoundaryResult.Revoked,
            boundary.revoke(
                fixture.id,
                generation(2),
                revision(2),
                time(2_001),
                ServiceCredentialRevocationReason.USER_REVOKED,
            ),
        )
        fixture.secret.close()
    }

    @Test
    fun `duplicate and failed atomic replacement preserve previous credential`() {
        val storage = MemoryCredentialStorage()
        val fixture = fixture(expiry = null)
        val boundary = ServiceCredentialBoundary(storage)
        boundary.store(fixture.request, generation(0)).success()
        val original = storage.content!!.copyOf()

        val duplicateSecret = secret(byteArrayOf(91, 92, 93))
        val duplicate = request(fixture.id, duplicateSecret, null)
        assertEquals(CredentialBoundaryResult.Duplicate, boundary.store(duplicate, generation(1)))
        duplicateSecret.close()

        storage.failWrites = true
        val otherSecret = secret(byteArrayOf(81, 82, 83))
        val other = request(ServiceCredentialId.newReference(), otherSecret, null)
        assertEquals(CredentialBoundaryResult.StorageUnavailable, boundary.store(other, generation(1)))
        otherSecret.close()
        assertArrayEquals(original, storage.content)
        fixture.secret.close()
    }

    @Test
    fun `corrupt unsupported and unavailable stores fail closed`() {
        val id = ServiceCredentialId.newReference()
        var invoked = false
        listOf(
            MemoryCredentialStorage(readOverride = CredentialDocumentReadResult.Corrupt) to
                CredentialBoundaryResult.Corrupt,
            MemoryCredentialStorage(readOverride = CredentialDocumentReadResult.UnsupportedVersion("9")) to
                CredentialBoundaryResult.UnsupportedVersion("9"),
            MemoryCredentialStorage(readOverride = CredentialDocumentReadResult.Unavailable) to
                CredentialBoundaryResult.StorageUnavailable,
        ).forEach { (storage, expected) ->
            val result = ServiceCredentialBoundary(storage).withCredential(id, time(1_000)) { invoked = true }
            assertEquals(expected, result)
        }
        assertFalse(invoked)
    }

    @Test
    fun `snapshots are immutable canonically ordered and contain no secret account or session state`() {
        val storage = MemoryCredentialStorage()
        val boundary = ServiceCredentialBoundary(storage)
        val high = credentialId("ffffffff-ffff-4fff-8fff-ffffffffffff")
        val low = credentialId("00000000-0000-4000-8000-000000000000")
        val firstSecret = secret(byteArrayOf(11, 12, 13))
        val secondSecret = secret(byteArrayOf(21, 22, 23))
        boundary.store(request(high, firstSecret, null), generation(0)).success()
        boundary.store(request(low, secondSecret, null), generation(1)).success()
        firstSecret.close()
        secondSecret.close()

        val snapshot = boundary.snapshot(time(1_500)).success()
        assertEquals(listOf(low, high), snapshot.records.map { it.id })
        assertTrue(snapshot.records.javaClass.name.contains("Unmodifiable"))
        val names = ServiceCredentialMetadata::class.java.declaredFields.map { it.name.lowercase() }
        listOf("account", "session", "permission", "body", "connection", "trust").forEach { forbidden ->
            assertFalse("Unexpected persisted field $forbidden in $names", names.any { forbidden in it })
        }
    }

    @Test
    fun `reset is explicit and does not create another credential`() {
        val storage = MemoryCredentialStorage()
        val fixture = fixture(null)
        val boundary = ServiceCredentialBoundary(storage)
        boundary.store(fixture.request, generation(0)).success()
        boundary.reset(ExplicitCredentialStoreReset.afterOperatorConfirmation()).success()

        assertEquals(CredentialBoundaryResult.Absent, boundary.withCredential(fixture.id, time(2_000)) { Unit })
        assertEquals(0, boundary.snapshot(time(2_000)).success().records.size)
        fixture.secret.close()
    }

    @Test
    fun `main source and resources contain no production-shaped credential assignment`() {
        val root = sequenceOf(File("src/main"), File("app/src/main"), File("../app/src/main"))
            .first { it.isDirectory }
        val assignment = Regex(
            "(?i)(api[_-]?key|client[_-]?secret|access[_-]?token)\\s*[=:]\\s*[\\\"'][A-Za-z0-9_./+-]{16,}",
        )
        val knownSecretPrefix = Regex("(?:sk-|ghp_|xox[baprs]-)[A-Za-z0-9_-]{16,}")
        val violations = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("kt", "java", "xml", "json", "properties") }
            .forEach { file ->
                val text = file.readText(Charsets.UTF_8)
                if (assignment.containsMatchIn(text)) violations += "${file.path}:credential-assignment"
                if (knownSecretPrefix.containsMatchIn(text)) violations += "${file.path}:known-secret-prefix"
            }
        assertTrue("Production-shaped credential material found: $violations", violations.isEmpty())
    }

    private data class Fixture(
        val id: ServiceCredentialId,
        val secret: ServiceCredentialSecret,
        val expectedSecret: ByteArray,
        val request: StoreServiceCredentialRequest,
    )

    private fun fixture(expiry: CredentialEpochMillis?): Fixture {
        val id = ServiceCredentialId.newReference()
        val expected = ByteArray(32) { index -> (index * 3 + 17).toByte() }
        val secret = secret(expected)
        return Fixture(id, secret, expected, request(id, secret, expiry))
    }

    private fun request(
        id: ServiceCredentialId,
        secret: ServiceCredentialSecret,
        expiry: CredentialEpochMillis?,
    ): StoreServiceCredentialRequest = StoreServiceCredentialRequest.create(
        id,
        providerDefinition(),
        authenticationScheme(),
        setOf(scope("scope.calendar/read@1.0"), scope("scope.calendar/profile@1.0")),
        time(1_000),
        expiry,
        secret,
    ).success()

    private fun providerDefinition(): ProviderDefinitionId = when (
        val result = ProviderDefinitionId.parse("example.calendar/service")
    ) {
        is ProviderIdentityResult.Success -> result.value
        is ProviderIdentityResult.Failure -> error("provider fixture: ${result.error}")
    }

    private fun authenticationScheme(): ServiceAuthenticationSchemeReference = when (
        val result = ServiceAuthenticationSchemeReference.parse("auth.standard/delegated@1.0")
    ) {
        is ServiceConnectorResult.Success -> result.value
        is ServiceConnectorResult.Failure -> error("scheme fixture: ${result.error}")
    }

    private fun scope(value: String): ServiceAuthorizationScopeReference = when (
        val result = ServiceAuthorizationScopeReference.parse(value)
    ) {
        is ServiceConnectorResult.Success -> result.value
        is ServiceConnectorResult.Failure -> error("scope fixture: ${result.error}")
    }

    private fun secret(bytes: ByteArray): ServiceCredentialSecret =
        (ServiceCredentialSecret.copyOf(bytes) as CredentialModelResult.Success).value

    private fun time(value: Long): CredentialEpochMillis =
        (CredentialEpochMillis.create(value) as CredentialModelResult.Success).value

    private fun generation(value: Long): CredentialStoreGeneration =
        (CredentialStoreGeneration.create(value) as CredentialModelResult.Success).value

    private fun revision(value: Long): CredentialRecordRevision =
        (CredentialRecordRevision.create(value) as CredentialModelResult.Success).value

    private fun credentialId(value: String): ServiceCredentialId =
        (ServiceCredentialId.parse(value) as CredentialModelResult.Success).value

    private fun <T> CredentialModelResult<T>.success(): T = (this as CredentialModelResult.Success).value
    private fun <T> CredentialBoundaryResult<T>.success(): T = (this as CredentialBoundaryResult.Success).value

    private class MemoryCredentialStorage(
        var readOverride: CredentialDocumentReadResult? = null,
    ) : ProtectedServiceCredentialStorage {
        var content: ByteArray? = null
        var failWrites: Boolean = false

        override fun read(): CredentialDocumentReadResult = readOverride ?: content?.let {
            CredentialDocumentReadResult.Content(it.copyOf())
        } ?: CredentialDocumentReadResult.Missing

        override fun writeAtomically(plaintext: ByteArray): CredentialDocumentWriteResult {
            if (failWrites) return CredentialDocumentWriteResult.FAILED_PRESERVING_PREVIOUS
            content = plaintext.copyOf()
            return CredentialDocumentWriteResult.COMMITTED
        }

        override fun reset(): CredentialDocumentResetResult {
            content?.fill(0)
            content = null
            return CredentialDocumentResetResult.RESET
        }
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean = indices.any { start ->
        start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] }
    }

}
