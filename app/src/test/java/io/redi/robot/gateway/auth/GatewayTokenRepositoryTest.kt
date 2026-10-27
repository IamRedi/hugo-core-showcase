package io.redi.robot.gateway.auth

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayTokenRepositoryTest {
    @Test
    fun `store restore and bounded access preserve token but grant no HUGO authority`() {
        val storage = MemoryGatewayTokenStorage()
        val fixture = fixture()
        val repository = GatewayTokenRepository(storage, releasePolicy())

        val evidence = repository.store(fixture.request, null).success()
        fixture.token.close()
        var callbackReference: ByteArray? = null
        val restored = GatewayTokenRepository(storage, releasePolicy()).withValidToken(
            endpoint(),
            setOf(GatewayAuthScope.TEXT),
            time(1_100),
        ) { transient ->
            callbackReference = transient
            transient.copyOf()
        }.success()

        assertArrayEquals(fixture.expected, restored)
        assertTrue(callbackReference!!.all { it == 0.toByte() })
        assertEquals(GatewayTokenState.ACTIVE, evidence.state)
        assertFalse(evidence.grantsTrust)
        assertFalse(evidence.grantsAuthorization)
        assertFalse(evidence.approvesSafety)
        assertFalse(evidence.executesCapability)
        val snapshot = repository.snapshot(time(1_100)).success()
        assertTrue(snapshot.hasActiveToken)
        assertFalse(snapshot.authenticatesGatewayNow)
        assertFalse(snapshot.grantsTrust)
        assertFalse(snapshot.grantsAuthorization)
        assertFalse(snapshot.approvesSafety)
        assertFalse(snapshot.establishesConnection)
        assertFalse(snapshot.executesCapability)
        assertFalse(snapshot.metadata.toString().contains(fixture.tokenId.value))
    }

    @Test
    fun `exact expiry and revoked token fail closed without callback`() {
        val storage = MemoryGatewayTokenStorage()
        val fixture = fixture(expiresAt = 1_100)
        val repository = GatewayTokenRepository(storage, releasePolicy())
        repository.store(fixture.request, null).success()
        fixture.token.close()
        var calls = 0

        assertEquals(
            GatewayAuthResult.Expired,
            repository.withValidToken(endpoint(), setOf(GatewayAuthScope.TEXT), time(1_100)) { calls++ },
        )
        val revoked = repository.revoke(fixture.tokenId, time(1_101), GatewayTokenRevocationReason.LOGOUT).success()
        assertEquals(GatewayTokenState.REVOKED, revoked.state)
        assertEquals(
            GatewayAuthResult.Revoked,
            GatewayTokenRepository(storage, releasePolicy()).withValidToken(
                endpoint(),
                setOf(GatewayAuthScope.TEXT),
                time(1_102),
            ) { calls++ },
        )
        assertEquals(0, calls)
        assertFalse(storage.content!!.containsSequence(fixture.expected))
    }

    @Test
    fun `scope audience and endpoint policy mismatch are distinct and fail closed`() {
        val storage = MemoryGatewayTokenStorage()
        val fixture = fixture(scopes = setOf(GatewayAuthScope.TEXT))
        val repository = GatewayTokenRepository(storage, releasePolicy())
        repository.store(fixture.request, null).success()
        fixture.token.close()
        var invoked = false

        assertEquals(
            GatewayAuthResult.InsufficientScope,
            repository.withValidToken(endpoint(), setOf(GatewayAuthScope.VISION), time(1_100)) { invoked = true },
        )
        assertEquals(
            GatewayAuthResult.EndpointDenied,
            repository.withValidToken(endpoint("https://unlisted.test"), setOf(GatewayAuthScope.TEXT), time(1_100)) {
                invoked = true
            },
        )
        val multiHostPolicy = policy(
            GatewayBuildMode.RELEASE,
            setOf(endpoint("https://gateway.test"), endpoint("https://other.test")),
        )
        assertEquals(
            GatewayAuthResult.AudienceMismatch,
            GatewayTokenRepository(storage, multiHostPolicy).withValidToken(
                endpoint("https://other.test"),
                setOf(GatewayAuthScope.TEXT),
                time(1_100),
            ) { invoked = true },
        )
        assertFalse(invoked)
    }

    @Test
    fun `rotation requires exact current token and never silently replaces`() {
        val storage = MemoryGatewayTokenStorage()
        val first = fixture()
        val repository = GatewayTokenRepository(storage, releasePolicy())
        repository.store(first.request, null).success()
        first.token.close()
        val preserved = storage.content!!.copyOf()
        val second = fixture(tokenSeed = 91)

        assertEquals(GatewayAuthResult.UnexpectedCurrentToken, repository.store(second.request, null))
        assertArrayEquals(preserved, storage.content)
        assertEquals(
            GatewayAuthResult.TokenMismatch,
            repository.store(second.request, GatewayTokenId.newReference()),
        )
        val rotated = repository.store(second.request, first.tokenId).success()
        second.token.close()

        assertEquals(2, rotated.revision)
        assertEquals(second.tokenId, repository.snapshot(time(1_100)).success().metadata?.tokenId)
        assertFalse(storage.content!!.containsSequence(first.expected))
    }

    @Test
    fun `duplicate token and failed atomic rotation preserve prior persistent state`() {
        val storage = MemoryGatewayTokenStorage()
        val first = fixture()
        val repository = GatewayTokenRepository(storage, releasePolicy())
        repository.store(first.request, null).success()
        val original = storage.content!!.copyOf()

        assertEquals(GatewayAuthResult.Duplicate, repository.store(first.request, first.tokenId))
        storage.failWrites = true
        val second = fixture(tokenSeed = 81)
        assertEquals(GatewayAuthResult.StorageUnavailable, repository.store(second.request, first.tokenId))
        assertArrayEquals(original, storage.content)

        first.token.close()
        second.token.close()
    }

    @Test
    fun `release and debug endpoint rules are deterministic and fail closed`() {
        val release = releasePolicy()
        assertEquals(GatewayEndpointDecision.Allowed, release.evaluate(endpoint()))
        assertEquals(
            GatewayEndpointDecision.Denied(GatewayEndpointDenialReason.CLEARTEXT_FORBIDDEN),
            release.evaluate(endpoint("http://localhost:8080")),
        )
        assertEquals(
            GatewayEndpointDecision.Denied(GatewayEndpointDenialReason.ENDPOINT_NOT_ALLOWLISTED),
            release.evaluate(endpoint("https://other.test")),
        )

        val debug = policy(GatewayBuildMode.DEBUG, emptySet())
        assertEquals(GatewayEndpointDecision.Allowed, debug.evaluate(endpoint("http://127.0.0.1:8080")))
        assertEquals(GatewayEndpointDecision.Allowed, debug.evaluate(endpoint("http://10.0.2.2:9090")))
        assertEquals(
            GatewayEndpointDecision.Denied(GatewayEndpointDenialReason.CLEARTEXT_NOT_LOOPBACK),
            debug.evaluate(endpoint("http://gateway.test:8080")),
        )
        assertEquals(
            GatewayEndpointDecision.Denied(GatewayEndpointDenialReason.ENDPOINT_NOT_ALLOWLISTED),
            debug.evaluate(endpoint()),
        )
    }

    @Test
    fun `endpoint parsing is canonical and rejects path credentials query fragment and invalid allowlists`() {
        assertEquals("https://gateway.test", endpoint("https://gateway.test:443/").wireValue)
        assertEquals("http://localhost:8080", endpoint("http://localhost:8080").wireValue)
        listOf(
            "https://user:pass@gateway.test",
            "https://gateway.test/path",
            "https://gateway.test?x=1",
            "https://gateway.test/#fragment",
            "ftp://gateway.test",
        ).forEach {
            assertTrue(GatewayEndpointOrigin.parse(it) is GatewayAuthModelResult.Failure)
        }
        assertTrue(
            GatewayEndpointPolicy.create(
                GatewayBuildMode.RELEASE,
                setOf(endpoint("https://127.0.0.1")),
            )
                is GatewayAuthModelResult.Failure,
        )
        assertEquals(
            GatewayEndpointDecision.Denied(GatewayEndpointDenialReason.ENDPOINT_NOT_ALLOWLISTED),
            releasePolicy().evaluate(endpoint("https://gateway.test:8443")),
        )
    }

    @Test
    fun `token identity lifetime scope and secret sizes are bounded and redacted`() {
        val bytes = byteArrayOf(1, 2, 3)
        val token = token(bytes)
        bytes.fill(0)
        assertEquals("GatewayAccessToken(REDACTED)", token.toString())
        assertEquals("gateway-token:[redacted]", GatewayTokenId.newReference().toString())
        assertEquals("gateway-client-session:[redacted]", GatewayClientSessionId.newReference().toString())
        assertTrue(GatewayAccessToken.copyOf(ByteArray(0)) is GatewayAuthModelResult.Failure)
        assertTrue(
            GatewayAccessToken.copyOf(ByteArray(GatewayAccessToken.MAXIMUM_BYTES + 1)) is GatewayAuthModelResult.Failure,
        )
        val invalid = StoreGatewayTokenRequest.create(
            GatewayTokenId.newReference(),
            GatewayClientSessionId.newReference(),
            endpoint(),
            emptySet(),
            time(1_000),
            time(1_100),
            token,
        )
        assertEquals(
            GatewayAuthModelResult.Failure(GatewayAuthModelError.EMPTY_SCOPES),
            invalid,
        )
        token.close()
    }

    @Test
    fun `corrupt unsupported and unavailable protected storage never exposes a token`() {
        val outcomes = listOf(
            GatewayTokenDocumentReadResult.Corrupt to GatewayAuthResult.Corrupt,
            GatewayTokenDocumentReadResult.UnsupportedVersion("9") to GatewayAuthResult.UnsupportedVersion("9"),
            GatewayTokenDocumentReadResult.Unavailable to GatewayAuthResult.StorageUnavailable,
            GatewayTokenDocumentReadResult.Content(byteArrayOf(1, 2, 3)) to GatewayAuthResult.Corrupt,
        )
        var invoked = false
        outcomes.forEach { (read, expected) ->
            val result = GatewayTokenRepository(
                MemoryGatewayTokenStorage(readOverride = read),
                releasePolicy(),
            ).withValidToken(endpoint(), setOf(GatewayAuthScope.TEXT), time(1_100)) { invoked = true }
            assertEquals(expected, result)
        }
        assertFalse(invoked)
    }

    @Test
    fun `explicit reset removes token without creating identity token or authority`() {
        val storage = MemoryGatewayTokenStorage()
        val fixture = fixture()
        val repository = GatewayTokenRepository(storage, releasePolicy())
        repository.store(fixture.request, null).success()
        fixture.token.close()

        repository.reset(ExplicitGatewayAuthReset.afterOperatorConfirmation()).success()

        assertEquals(null, repository.snapshot(time(1_100)).success().metadata)
        assertEquals(
            GatewayAuthResult.Absent,
            repository.withValidToken(endpoint(), setOf(GatewayAuthScope.TEXT), time(1_100)) { error("must not run") },
        )
    }

    @Test
    fun `persistence is deterministic and metadata contains no HUGO phone user provider or authority state`() {
        val firstStorage = MemoryGatewayTokenStorage()
        val secondStorage = MemoryGatewayTokenStorage()
        val first = fixture(fixedIds = true)
        val second = fixture(fixedIds = true)
        GatewayTokenRepository(firstStorage, releasePolicy()).store(first.request, null).success()
        GatewayTokenRepository(secondStorage, releasePolicy()).store(second.request, null).success()
        first.token.close()
        second.token.close()

        assertArrayEquals(firstStorage.content, secondStorage.content)
        val names = GatewayTokenMetadata::class.java.declaredFields.map { it.name.lowercase() }
        listOf("hugoid", "phone", "device", "user", "provider", "permission", "authorization", "body").forEach {
            forbidden -> assertFalse("unexpected metadata field $forbidden: $names", names.any { forbidden in it })
        }
    }

    @Test
    fun `network security files deny release cleartext and constrain debug cleartext to loopback`() {
        val mainManifest = projectFile("src/main/AndroidManifest.xml").readText()
        val releaseConfig = projectFile("src/main/res/xml/network_security_config.xml").readText()
        val debugConfig = projectFile("src/debug/res/xml/network_security_config.xml").readText()

        assertTrue(mainManifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(mainManifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertTrue(releaseConfig.contains("cleartextTrafficPermitted=\"false\""))
        assertFalse(releaseConfig.contains("<domain"))
        listOf("localhost", "127.0.0.1", "10.0.2.2").forEach { assertTrue(debugConfig.contains(">$it<")) }
        assertFalse(debugConfig.contains("gateway.test"))
        assertFalse(mainManifest.contains("android.permission.INTERNET\" />"))
    }

    @Test
    fun `gateway auth source has no provider key secret constant backend or runtime coupling`() {
        val source = projectFile("src/main/java/io/redi/robot/gateway/auth")
            .walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        listOf(
            "import android.",
            "import androidx.",
            "apiKey =",
            "providerKey",
            "clientSecret",
            "AuthorizationDecision",
            "CapabilityRequest",
            "HugoIdentity",
            "BluetoothGatt",
            "io.redi.robot.body.looi",
            "io.redi.robot.platform",
        ).forEach { assertFalse("forbidden gateway auth coupling: $it", source.contains(it)) }
    }

    private data class Fixture(
        val tokenId: GatewayTokenId,
        val token: GatewayAccessToken,
        val expected: ByteArray,
        val request: StoreGatewayTokenRequest,
    )

    private fun fixture(
        expiresAt: Long = 1_600,
        scopes: Set<GatewayAuthScope> = setOf(GatewayAuthScope.TEXT, GatewayAuthScope.REALTIME),
        tokenSeed: Int = 17,
        fixedIds: Boolean = false,
    ): Fixture {
        val tokenId = if (fixedIds) tokenId("10000000-0000-4000-8000-000000000001") else GatewayTokenId.newReference()
        val clientId = if (fixedIds) clientId("20000000-0000-4000-8000-000000000001") else
            GatewayClientSessionId.newReference()
        val expected = ByteArray(48) { index -> (tokenSeed + index * 3).toByte() }
        val token = token(expected)
        val request = StoreGatewayTokenRequest.create(
            tokenId,
            clientId,
            endpoint(),
            scopes,
            time(1_000),
            time(expiresAt),
            token,
        ).successModel()
        return Fixture(tokenId, token, expected, request)
    }

    private fun endpoint(value: String = "https://gateway.test"): GatewayEndpointOrigin =
        GatewayEndpointOrigin.parse(value).successModel()

    private fun releasePolicy(): GatewayEndpointPolicy =
        policy(GatewayBuildMode.RELEASE, setOf(endpoint("https://gateway.test")))

    private fun policy(mode: GatewayBuildMode, origins: Set<GatewayEndpointOrigin>): GatewayEndpointPolicy =
        GatewayEndpointPolicy.create(mode, origins).successModel()

    private fun token(value: ByteArray): GatewayAccessToken = GatewayAccessToken.copyOf(value).successModel()
    private fun time(value: Long): GatewayAuthEpochSeconds = GatewayAuthEpochSeconds.create(value).successModel()
    private fun tokenId(value: String): GatewayTokenId = GatewayTokenId.parse(value).successModel()
    private fun clientId(value: String): GatewayClientSessionId = GatewayClientSessionId.parse(value).successModel()

    private fun projectFile(relative: String): File = sequenceOf(File(relative), File("app/$relative"), File("../app/$relative"))
        .first { it.exists() }

    private fun <T> GatewayAuthModelResult<T>.successModel(): T = (this as GatewayAuthModelResult.Success).value
    private fun <T> GatewayAuthResult<T>.success(): T = (this as GatewayAuthResult.Success).value

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean = indices.any { start ->
        start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] }
    }

    private class MemoryGatewayTokenStorage(
        private val readOverride: GatewayTokenDocumentReadResult? = null,
    ) : ProtectedGatewayTokenStorage {
        var content: ByteArray? = null
        var failWrites: Boolean = false

        override fun read(): GatewayTokenDocumentReadResult = readOverride ?: content?.copyOf()?.let {
            GatewayTokenDocumentReadResult.Content(it)
        } ?: GatewayTokenDocumentReadResult.Missing

        override fun writeAtomically(plaintext: ByteArray): GatewayTokenDocumentWriteResult = if (failWrites) {
            GatewayTokenDocumentWriteResult.FAILED_PRESERVING_PREVIOUS
        } else {
            content = plaintext.copyOf()
            GatewayTokenDocumentWriteResult.COMMITTED
        }

        override fun reset(): GatewayTokenDocumentResetResult {
            content?.fill(0)
            content = null
            return GatewayTokenDocumentResetResult.RESET
        }
    }
}
