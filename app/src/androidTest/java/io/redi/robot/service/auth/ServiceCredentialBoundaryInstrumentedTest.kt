package io.redi.robot.service.auth

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.redi.robot.capability.provider.ProviderDefinitionId
import io.redi.robot.capability.provider.ProviderIdentityResult
import io.redi.robot.platform.service.auth.AndroidProtectedServiceCredentialStorage
import io.redi.robot.service.api.ServiceAuthenticationSchemeReference
import io.redi.robot.service.api.ServiceAuthorizationScopeReference
import io.redi.robot.service.api.ServiceConnectorResult
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceCredentialBoundaryInstrumentedTest {
    @Test
    fun keystoreStoreRestoreLogoutAndResetFailClosed() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val suffix = UUID.randomUUID().toString()
        val directory = File(context.noBackupFilesDir, "h041-test-$suffix")
        val alias = "hugo.service.credentials.test.$suffix"
        val storage = AndroidProtectedServiceCredentialStorage(directory, alias)
        val boundary = ServiceCredentialBoundary(storage)
        val credentialId = ServiceCredentialId.newReference()
        val secretBytes = UUID.randomUUID().toString().toByteArray(StandardCharsets.UTF_8)
        val secret = secret(secretBytes)
        try {
            val request = request(credentialId, secret, expiry = time(20_000))
            val stored = boundary.store(request, generation(0)).success()
            secret.close()

            val encryptedFile = File(directory, "service-credentials.enc")
            assertTrue(encryptedFile.isFile)
            assertFalse(encryptedFile.readBytes().containsSequence(secretBytes))

            val restored = ServiceCredentialBoundary(
                AndroidProtectedServiceCredentialStorage(directory, alias),
            ).withCredential(credentialId, time(10_000)) { it.copyOf() }.success()
            assertArrayEquals(secretBytes, restored)

            val logout = ServiceCredentialBoundary(
                AndroidProtectedServiceCredentialStorage(directory, alias),
            ).logout(
                credentialId,
                stored.generation,
                stored.recordRevision,
                time(11_000),
            ).success()
            assertEquals(ServiceCredentialKnowledgeState.REVOKED, logout.state)
            assertEquals(
                CredentialBoundaryResult.Revoked,
                boundary.withCredential(credentialId, time(11_001)) { error("must not run") },
            )
            assertFalse(encryptedFile.readBytes().containsSequence(secretBytes))
            println("H041_ANDROID=keystore_restore_logout_fail_closed;credential=redacted")

            boundary.reset(ExplicitCredentialStoreReset.afterOperatorConfirmation()).success()
            assertEquals(
                CredentialBoundaryResult.Absent,
                boundary.withCredential(credentialId, time(12_000)) { error("must not run") },
            )
        } finally {
            AndroidProtectedServiceCredentialStorage(directory, alias).reset()
            directory.deleteRecursively()
            secretBytes.fill(0)
            secret.close()
        }
    }

    @Test
    fun ciphertextTamperingAndUnsupportedEnvelopeFailClosed() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val suffix = UUID.randomUUID().toString()
        val directory = File(context.noBackupFilesDir, "h041-tamper-$suffix")
        val alias = "hugo.service.credentials.tamper.$suffix"
        val storage = AndroidProtectedServiceCredentialStorage(directory, alias)
        val boundary = ServiceCredentialBoundary(storage)
        val id = ServiceCredentialId.newReference()
        val secretBytes = UUID.randomUUID().toString().toByteArray(StandardCharsets.UTF_8)
        val secret = secret(secretBytes)
        try {
            boundary.store(request(id, secret, null), generation(0)).success()
            secret.close()
            val file = File(directory, "service-credentials.enc")
            val envelope = file.readText(StandardCharsets.UTF_8)
            val marker = "\"ciphertext\":\""
            val index = envelope.indexOf(marker) + marker.length
            require(index >= marker.length)
            val replacement = if (envelope[index] == 'A') 'B' else 'A'
            file.writeText(envelope.replaceRange(index, index + 1, replacement.toString()), StandardCharsets.UTF_8)

            assertEquals(
                CredentialBoundaryResult.Corrupt,
                boundary.withCredential(id, time(2_000)) { error("must not run") },
            )

            boundary.reset(ExplicitCredentialStoreReset.afterOperatorConfirmation()).success()
            directory.mkdirs()
            file.writeText(
                """{"envelopeVersion":"9","algorithm":"AES/GCM/NoPadding","keyVersion":"1","nonce":"AAAAAAAAAAAAAAAA","ciphertext":"AAAAAAAAAAAAAAAAAAAAAA=="}""",
                StandardCharsets.UTF_8,
            )
            assertEquals(
                CredentialBoundaryResult.UnsupportedVersion("9"),
                boundary.withCredential(id, time(2_000)) { error("must not run") },
            )
            println("H041_ANDROID=tamper_and_version_fail_closed;credential=redacted")
        } finally {
            AndroidProtectedServiceCredentialStorage(directory, alias).reset()
            directory.deleteRecursively()
            secretBytes.fill(0)
            secret.close()
        }
    }

    private fun request(
        id: ServiceCredentialId,
        secret: ServiceCredentialSecret,
        expiry: CredentialEpochMillis?,
    ): StoreServiceCredentialRequest = StoreServiceCredentialRequest.create(
        id,
        providerDefinition(),
        authenticationScheme(),
        setOf(scope()),
        time(1_000),
        expiry,
        secret,
    ).success()

    private fun providerDefinition(): ProviderDefinitionId = when (
        val parsed = ProviderDefinitionId.parse("example.calendar/service")
    ) {
        is ProviderIdentityResult.Success -> parsed.value
        is ProviderIdentityResult.Failure -> error("provider fixture")
    }

    private fun authenticationScheme(): ServiceAuthenticationSchemeReference = when (
        val parsed = ServiceAuthenticationSchemeReference.parse("auth.standard/delegated@1.0")
    ) {
        is ServiceConnectorResult.Success -> parsed.value
        is ServiceConnectorResult.Failure -> error("scheme fixture")
    }

    private fun scope(): ServiceAuthorizationScopeReference = when (
        val parsed = ServiceAuthorizationScopeReference.parse("scope.calendar/read@1.0")
    ) {
        is ServiceConnectorResult.Success -> parsed.value
        is ServiceConnectorResult.Failure -> error("scope fixture")
    }

    private fun secret(bytes: ByteArray): ServiceCredentialSecret =
        (ServiceCredentialSecret.copyOf(bytes) as CredentialModelResult.Success).value

    private fun time(value: Long): CredentialEpochMillis =
        (CredentialEpochMillis.create(value) as CredentialModelResult.Success).value

    private fun generation(value: Long): CredentialStoreGeneration =
        (CredentialStoreGeneration.create(value) as CredentialModelResult.Success).value

    private fun <T> CredentialModelResult<T>.success(): T = (this as CredentialModelResult.Success).value
    private fun <T> CredentialBoundaryResult<T>.success(): T = (this as CredentialBoundaryResult.Success).value

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean = indices.any { start ->
        start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] }
    }
}
