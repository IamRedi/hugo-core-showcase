package io.redi.robot.firstlife.identity

import io.redi.robot.hugo.identity.HugoIdentity
import io.redi.robot.hugo.identity.HugoIdentityCodec
import io.redi.robot.hugo.identity.HugoIdentityId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HugoIdentityBootstrapperTest {
    @Test
    fun `first launch creates exactly one identity and second launch restores it`() {
        val storage = MemoryIdentityStorage()
        val entropyCalls = AtomicInteger()
        val bootstrapper = HugoIdentityBootstrapper(storage, countingEntropy(entropyCalls))

        val first = bootstrapper.bootstrap(run(1))
        val second = bootstrapper.bootstrap(run(2))

        assertEquals(HugoIdentityBootstrapStatus.CREATED, first.status)
        assertEquals(HugoIdentityBootstrapStatus.RESTORED, second.status)
        assertEquals(first.identity, second.identity)
        assertEquals(first.safeDisplayIdentity, second.safeDisplayIdentity)
        assertNotEquals(first.runReference, second.runReference)
        assertEquals(1, entropyCalls.get())
        assertEquals(1, storage.createAttempts)
    }

    @Test
    fun `repository and process-like recreation restores the exact H003 identity`() {
        val storage = MemoryIdentityStorage()
        val created = HugoIdentityBootstrapper(storage, fixedEntropy(10)).bootstrap(run(1))

        val restored = HugoIdentityBootstrapper(storage, fixedEntropy(99)).bootstrap(run(2))

        assertEquals(HugoIdentityBootstrapStatus.RESTORED, restored.status)
        assertEquals(created.identity, restored.identity)
        assertEquals(created.safeDisplayIdentity, restored.safeDisplayIdentity)
    }

    @Test
    fun `concurrent first bootstrap returns one stored identity without silent replacement`() {
        val storage = MemoryIdentityStorage()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val first = pool.submit<HugoIdentityBootstrapSnapshot> {
            start.await()
            HugoIdentityBootstrapper(storage, fixedEntropy(20)).bootstrap(run(1))
        }
        val second = pool.submit<HugoIdentityBootstrapSnapshot> {
            start.await()
            HugoIdentityBootstrapper(storage, fixedEntropy(21)).bootstrap(run(2))
        }

        start.countDown()
        val snapshots = listOf(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals(1, snapshots.count { it.status == HugoIdentityBootstrapStatus.CREATED })
        assertEquals(1, snapshots.count { it.status == HugoIdentityBootstrapStatus.RESTORED })
        assertEquals(1, snapshots.map { it.identity }.distinct().size)
        assertEquals(snapshots.first().identity, HugoIdentityCodec.decode(storage.content!!).decodeSuccess())
    }

    @Test
    fun `corrupt stored identity fails closed without requesting entropy`() {
        val storage = MemoryIdentityStorage(content = "{broken")
        val entropyCalls = AtomicInteger()

        val snapshot = HugoIdentityBootstrapper(storage, countingEntropy(entropyCalls)).bootstrap(run(1))

        assertEquals(HugoIdentityBootstrapStatus.CORRUPT_IDENTITY, snapshot.status)
        assertEquals(HugoIdentityRecoveryState.EXPLICIT_RESET_REQUIRED, snapshot.recoveryState)
        assertNull(snapshot.identity)
        assertNull(snapshot.safeDisplayIdentity)
        assertEquals(0, entropyCalls.get())
        assertEquals("{broken", storage.content)
    }

    @Test
    fun `unsupported H003 or storage schema fails closed`() {
        val unsupportedH003 = MemoryIdentityStorage(
            content = """{"schemaVersion":"2.0","identityId":"00000000-0000-0000-0000-000000000001"}""",
        )
        val unsupportedEnvelope = MemoryIdentityStorage(readOverride = HugoIdentityStorageReadResult.UnsupportedVersion("2"))

        listOf(unsupportedH003, unsupportedEnvelope).forEach { storage ->
            val snapshot = HugoIdentityBootstrapper(storage, fixedEntropy(2)).bootstrap(run(1))
            assertEquals(HugoIdentityBootstrapStatus.UNSUPPORTED_SCHEMA, snapshot.status)
            assertEquals(HugoIdentityRecoveryState.EXPLICIT_RESET_REQUIRED, snapshot.recoveryState)
            assertNull(snapshot.identity)
            assertEquals(0, storage.createAttempts)
        }
    }

    @Test
    fun `storage read failure does not generate or replace an identity`() {
        val entropyCalls = AtomicInteger()
        val storage = MemoryIdentityStorage(readOverride = HugoIdentityStorageReadResult.Unavailable)

        val first = HugoIdentityBootstrapper(storage, countingEntropy(entropyCalls)).bootstrap(run(1))
        val second = HugoIdentityBootstrapper(storage, countingEntropy(entropyCalls)).bootstrap(run(2))

        assertEquals(HugoIdentityBootstrapStatus.STORAGE_UNAVAILABLE, first.status)
        assertEquals(HugoIdentityRecoveryState.RETRY_STORAGE, first.recoveryState)
        assertEquals(HugoIdentityBootstrapStatus.STORAGE_UNAVAILABLE, second.status)
        assertEquals(0, entropyCalls.get())
        assertEquals(0, storage.createAttempts)
    }

    @Test
    fun `failed atomic create returns no accepted identity`() {
        val storage = MemoryIdentityStorage(createUnavailable = true)

        val snapshot = HugoIdentityBootstrapper(storage, fixedEntropy(3)).bootstrap(run(1))

        assertEquals(HugoIdentityBootstrapStatus.STORAGE_UNAVAILABLE, snapshot.status)
        assertNull(snapshot.identity)
        assertNull(storage.content)
    }

    @Test
    fun `explicit reset removes identity and only a later bootstrap creates a new one`() {
        val storage = MemoryIdentityStorage()
        val first = HugoIdentityBootstrapper(storage, fixedEntropy(4)).bootstrap(run(1))
        val bootstrapper = HugoIdentityBootstrapper(storage, fixedEntropy(5))

        val reset = bootstrapper.reset(
            ExplicitHugoIdentityReset.afterUserConfirmation(HugoIdentityResetReason.OPERATOR_RECOVERY),
            run(2),
        )

        assertTrue(reset is HugoIdentityResetOutcome.Completed)
        assertNull(storage.content)
        val recreated = bootstrapper.bootstrap(run(3))
        assertEquals(HugoIdentityBootstrapStatus.CREATED, recreated.status)
        assertNotEquals(first.identity, recreated.identity)
    }

    @Test
    fun `failed explicit reset preserves blocked stored state`() {
        val storage = MemoryIdentityStorage(content = "corrupt", resetFails = true)
        val bootstrapper = HugoIdentityBootstrapper(storage, fixedEntropy(6))

        val outcome = bootstrapper.reset(
            ExplicitHugoIdentityReset.afterUserConfirmation(HugoIdentityResetReason.CORRUPT_STORAGE),
            run(1),
        )

        assertTrue(outcome is HugoIdentityResetOutcome.Failed)
        assertEquals("corrupt", storage.content)
        assertEquals(HugoIdentityBootstrapStatus.CORRUPT_IDENTITY, bootstrapper.bootstrap(run(2)).status)
    }

    @Test
    fun `body provider connection and voice changes cannot alter persisted identity`() {
        val storage = MemoryIdentityStorage()
        val bootstrapper = HugoIdentityBootstrapper(storage, fixedEntropy(7))
        val original = bootstrapper.bootstrap(run(1))

        val unrelatedContext = mutableMapOf(
            "body" to "looi-a",
            "provider" to "provider-b",
            "connection" to "disconnected",
            "voiceSession" to "cancelled",
        )
        unrelatedContext["body"] = "none"
        unrelatedContext["provider"] = "provider-c"

        val restored = HugoIdentityBootstrapper(storage, fixedEntropy(8)).bootstrap(run(2))
        assertEquals(original.identity, restored.identity)
        assertFalse(storage.content!!.contains("looi", ignoreCase = true))
        assertFalse(storage.content!!.contains("provider", ignoreCase = true))
        assertFalse(storage.content!!.contains("voice", ignoreCase = true))
        assertFalse(storage.content!!.contains("connection", ignoreCase = true))
    }

    @Test
    fun `persisted document contains only the strict H003 fields and display value is redacted`() {
        val storage = MemoryIdentityStorage()
        val snapshot = HugoIdentityBootstrapper(storage, fixedEntropy(9)).bootstrap(run(1))
        val encoded = storage.content!!

        assertEquals(setOf("identityId", "schemaVersion"), fieldNames(encoded))
        assertFalse(snapshot.safeDisplayIdentity!!.value.contains(snapshot.identity!!.id.value))
        assertTrue(snapshot.safeDisplayIdentity.value.matches(Regex("^HUGO-[0-9A-F]{8}$")))
        listOf("user", "name", "transcript", "provider", "permission", "activeBody", "session", "ai").forEach {
            assertFalse("Unexpected identity persistence term $it", encoded.contains(it, ignoreCase = true))
        }
    }

    private fun fixedEntropy(suffix: Int): HugoIdentityEntropySource = HugoIdentityEntropySource {
        HugoIdentity(HugoIdentityId.fromUuid(UUID.fromString("00000000-0000-0000-0000-${suffix.toString().padStart(12, '0')}")))
    }

    private fun countingEntropy(calls: AtomicInteger): HugoIdentityEntropySource = HugoIdentityEntropySource {
        val value = calls.incrementAndGet()
        HugoIdentity(HugoIdentityId.fromUuid(UUID.fromString("00000000-0000-0000-0000-${value.toString().padStart(12, '0')}")))
    }

    private fun run(suffix: Int): HugoBootstrapRunReference = HugoBootstrapRunReference.fromUuid(
        UUID.fromString("10000000-0000-0000-0000-${suffix.toString().padStart(12, '0')}"),
    )

    private fun fieldNames(encoded: String): Set<String> = Regex("\\\"([^\\\"]+)\\\"\\s*:")
        .findAll(encoded)
        .map { it.groupValues[1] }
        .toSet()

    private class MemoryIdentityStorage(
        content: String? = null,
        var readOverride: HugoIdentityStorageReadResult? = null,
        var createUnavailable: Boolean = false,
        var resetFails: Boolean = false,
    ) : HugoIdentityBootstrapStorage {
        @Volatile
        var content: String? = content
        var createAttempts: Int = 0

        override fun read(): HugoIdentityStorageReadResult = synchronized(this) {
            readOverride ?: content?.let(HugoIdentityStorageReadResult::Content)
            ?: HugoIdentityStorageReadResult.Missing
        }

        override fun createIfAbsent(encodedIdentity: String): HugoIdentityStorageCreateResult = synchronized(this) {
            createAttempts++
            if (createUnavailable) return HugoIdentityStorageCreateResult.Unavailable
            val existing = content
            if (existing != null) {
                HugoIdentityStorageCreateResult.Existing(HugoIdentityStorageReadResult.Content(existing))
            } else {
                content = encodedIdentity
                HugoIdentityStorageCreateResult.Created
            }
        }

        override fun reset(): HugoIdentityStorageResetResult = synchronized(this) {
            if (resetFails) return HugoIdentityStorageResetResult.FAILED
            content = null
            readOverride = null
            HugoIdentityStorageResetResult.RESET
        }
    }
}

private fun io.redi.robot.hugo.identity.HugoIdentityResult<HugoIdentity>.decodeSuccess(): HugoIdentity =
    (this as io.redi.robot.hugo.identity.HugoIdentityResult.Success).value
