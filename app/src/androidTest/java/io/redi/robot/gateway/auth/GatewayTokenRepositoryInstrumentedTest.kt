package io.redi.robot.gateway.auth

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.redi.robot.platform.gateway.auth.AndroidProtectedGatewayTokenStorage
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
class GatewayTokenRepositoryInstrumentedTest {
    @Test
    fun keystoreRestoreExpiryRevokeAndResetFailClosed() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val suffix = UUID.randomUUID().toString()
        val root = File(context.noBackupFilesDir, "h052-test-$suffix")
        val alias = "hugo.gateway.auth.test.$suffix"
        val storage = AndroidProtectedGatewayTokenStorage(root, alias)
        val repository = GatewayTokenRepository(storage, policy())
        val id = GatewayTokenId.newReference()
        val client = GatewayClientSessionId.newReference()
        val secretBytes = UUID.randomUUID().toString().toByteArray(StandardCharsets.UTF_8)
        val token = token(secretBytes)
        try {
            repository.store(request(id, client, token, expiresAt = 1_200), null).success()
            token.close()
            val encryptedFile = File(
                File(root, AndroidProtectedGatewayTokenStorage.STORAGE_DIRECTORY),
                AndroidProtectedGatewayTokenStorage.ENCRYPTED_FILE,
            )
            assertTrue(encryptedFile.isFile)
            assertFalse(encryptedFile.readBytes().containsSequence(secretBytes))

            val restored = GatewayTokenRepository(
                AndroidProtectedGatewayTokenStorage(root, alias),
                policy(),
            ).withValidToken(endpoint(), setOf(GatewayAuthScope.TEXT), time(1_100)) { it.copyOf() }.success()
            assertArrayEquals(secretBytes, restored)
            assertEquals(
                GatewayAuthResult.Expired,
                repository.withValidToken(endpoint(), setOf(GatewayAuthScope.TEXT), time(1_200)) {
                    error("expired callback")
                },
            )

            repository.revoke(id, time(1_201), GatewayTokenRevocationReason.LOGOUT).success()
            assertEquals(
                GatewayAuthResult.Revoked,
                GatewayTokenRepository(AndroidProtectedGatewayTokenStorage(root, alias), policy()).withValidToken(
                    endpoint(),
                    setOf(GatewayAuthScope.TEXT),
                    time(1_202),
                ) { error("revoked callback") },
            )
            assertFalse(encryptedFile.readBytes().containsSequence(secretBytes))
            println("H052_ANDROID=keystore_restore_expiry_revoke_fail_closed;token=redacted;endpoint=redacted")

            repository.reset(ExplicitGatewayAuthReset.afterOperatorConfirmation()).success()
            assertEquals(null, repository.snapshot(time(1_203)).success().metadata)
        } finally {
            AndroidProtectedGatewayTokenStorage(root, alias).reset()
            root.deleteRecursively()
            secretBytes.fill(0)
            token.close()
        }
    }

    @Test
    fun authenticatedCiphertextTamperAndUnsupportedEnvelopeFailClosed() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val suffix = UUID.randomUUID().toString()
        val root = File(context.noBackupFilesDir, "h052-tamper-$suffix")
        val alias = "hugo.gateway.auth.tamper.$suffix"
        val storage = AndroidProtectedGatewayTokenStorage(root, alias)
        val repository = GatewayTokenRepository(storage, policy())
        val id = GatewayTokenId.newReference()
        val token = token(UUID.randomUUID().toString().toByteArray(StandardCharsets.UTF_8))
        val encryptedFile = File(
            File(root, AndroidProtectedGatewayTokenStorage.STORAGE_DIRECTORY),
            AndroidProtectedGatewayTokenStorage.ENCRYPTED_FILE,
        )
        try {
            repository.store(request(id, GatewayClientSessionId.newReference(), token, 1_200), null).success()
            token.close()
            val envelope = encryptedFile.readText(StandardCharsets.UTF_8)
            val marker = "\"ciphertext\":\""
            val index = envelope.indexOf(marker) + marker.length
            require(index >= marker.length)
            val replacement = if (envelope[index] == 'A') 'B' else 'A'
            encryptedFile.writeText(
                envelope.replaceRange(index, index + 1, replacement.toString()),
                StandardCharsets.UTF_8,
            )
            assertEquals(GatewayAuthResult.Corrupt, repository.snapshot(time(1_100)))

            repository.reset(ExplicitGatewayAuthReset.afterOperatorConfirmation()).success()
            encryptedFile.parentFile?.mkdirs()
            encryptedFile.writeText(
                """{"envelopeVersion":"9","algorithm":"AES/GCM/NoPadding","keyVersion":"1","nonce":"AAAAAAAAAAAAAAAA","ciphertext":"AAAAAAAAAAAAAAAAAAAAAA=="}""",
                StandardCharsets.UTF_8,
            )
            assertEquals(GatewayAuthResult.UnsupportedVersion("9"), repository.snapshot(time(1_100)))
            println("H052_ANDROID=tamper_and_version_fail_closed;token=redacted;endpoint=redacted")
        } finally {
            AndroidProtectedGatewayTokenStorage(root, alias).reset()
            root.deleteRecursively()
            token.close()
        }
    }

    private fun request(
        id: GatewayTokenId,
        client: GatewayClientSessionId,
        token: GatewayAccessToken,
        expiresAt: Long,
    ): StoreGatewayTokenRequest = StoreGatewayTokenRequest.create(
        id,
        client,
        endpoint(),
        setOf(GatewayAuthScope.TEXT),
        time(1_000),
        time(expiresAt),
        token,
    ).successModel()

    private fun endpoint(): GatewayEndpointOrigin = GatewayEndpointOrigin.parse("https://gateway.test").successModel()
    private fun policy(): GatewayEndpointPolicy =
        GatewayEndpointPolicy.create(GatewayBuildMode.RELEASE, setOf(endpoint())).successModel()
    private fun token(bytes: ByteArray): GatewayAccessToken = GatewayAccessToken.copyOf(bytes).successModel()
    private fun time(value: Long): GatewayAuthEpochSeconds = GatewayAuthEpochSeconds.create(value).successModel()
    private fun <T> GatewayAuthModelResult<T>.successModel(): T = (this as GatewayAuthModelResult.Success).value
    private fun <T> GatewayAuthResult<T>.success(): T = (this as GatewayAuthResult.Success).value

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean = indices.any { start ->
        start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] }
    }
}
