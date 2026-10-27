package io.redi.robot.firstlife.identity

import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.redi.robot.HugoFirstLifeApplication
import io.redi.robot.MainActivity
import io.redi.robot.platform.storage.AndroidHugoIdentityStorage
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HugoIdentityBootstrapInstrumentedTest {
    @Test
    fun applicationBootstrapAndRepositoryRecreationReturnSameIdentity() {
        val application = ApplicationProvider.getApplicationContext<HugoFirstLifeApplication>()
        val applicationSnapshot = application.hugoIdentityBootstrapSnapshot

        val restored = application.createIdentityBootstrapper().bootstrap(HugoBootstrapRunReference.newReference())

        assertTrue(
            applicationSnapshot.status == HugoIdentityBootstrapStatus.CREATED ||
                applicationSnapshot.status == HugoIdentityBootstrapStatus.RESTORED,
        )
        assertEquals(HugoIdentityBootstrapStatus.RESTORED, restored.status)
        assertEquals(applicationSnapshot.identity, restored.identity)
        assertEquals(applicationSnapshot.safeDisplayIdentity, restored.safeDisplayIdentity)
        assertNotEquals(applicationSnapshot.runReference, restored.runReference)
        println("FL002_SAFE_IDENTITY=${applicationSnapshot.safeDisplayIdentity}")
    }

    @Test
    fun activityRecreationPreservesExactIdentity() {
        val application = ApplicationProvider.getApplicationContext<HugoFirstLifeApplication>()
        val expectedIdentity = application.hugoIdentityBootstrapSnapshot.identity
        var beforeRecreation = expectedIdentity
        var afterRecreation = expectedIdentity

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                beforeRecreation = (activity.application as HugoFirstLifeApplication)
                    .hugoIdentityBootstrapSnapshot.identity
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                afterRecreation = (activity.application as HugoFirstLifeApplication)
                    .hugoIdentityBootstrapSnapshot.identity
            }
        }

        assertEquals(expectedIdentity, beforeRecreation)
        assertEquals(expectedIdentity, afterRecreation)
        println("FL002_ACTIVITY_RECREATION_IDENTITY=${application.hugoIdentityBootstrapSnapshot.safeDisplayIdentity}")
    }

    @Test
    fun authenticatedNoBackupStorageRestoresAndTamperingFailsClosed() {
        val application = ApplicationProvider.getApplicationContext<HugoFirstLifeApplication>()
        val suffix = UUID.randomUUID().toString()
        val directory = File(application.noBackupFilesDir, "fl002-test-$suffix")
        val alias = "hugo.first-life.identity.test.$suffix"
        val storage = AndroidHugoIdentityStorage(directory, alias)
        val bootstrapper = HugoIdentityBootstrapper(storage)
        try {
            val created = bootstrapper.bootstrap(HugoBootstrapRunReference.newReference())
            val restored = HugoIdentityBootstrapper(
                AndroidHugoIdentityStorage(directory, alias),
            ).bootstrap(HugoBootstrapRunReference.newReference())

            assertEquals(HugoIdentityBootstrapStatus.CREATED, created.status)
            assertEquals(HugoIdentityBootstrapStatus.RESTORED, restored.status)
            assertEquals(created.identity, restored.identity)

            val primary = File(directory, "hugo-identity.enc")
            val envelope = primary.readText(Charsets.UTF_8)
            val ciphertextMarker = "\"ciphertext\":\""
            val valueStart = envelope.indexOf(ciphertextMarker) + ciphertextMarker.length
            require(valueStart >= ciphertextMarker.length)
            val replacement = if (envelope[valueStart] == 'A') 'B' else 'A'
            primary.writeText(envelope.replaceRange(valueStart, valueStart + 1, replacement.toString()), Charsets.UTF_8)

            val corrupt = HugoIdentityBootstrapper(
                AndroidHugoIdentityStorage(directory, alias),
            ).bootstrap(HugoBootstrapRunReference.newReference())
            assertEquals(HugoIdentityBootstrapStatus.CORRUPT_IDENTITY, corrupt.status)
            assertEquals(HugoIdentityRecoveryState.EXPLICIT_RESET_REQUIRED, corrupt.recoveryState)
            assertNull(corrupt.identity)
        } finally {
            bootstrapper.reset(
                ExplicitHugoIdentityReset.afterUserConfirmation(HugoIdentityResetReason.OPERATOR_RECOVERY),
                HugoBootstrapRunReference.newReference(),
            )
            directory.deleteRecursively()
        }
    }

    @Test
    fun unsupportedStorageEnvelopeFailsClosedWithoutCreatingIdentity() {
        val application = ApplicationProvider.getApplicationContext<HugoFirstLifeApplication>()
        val suffix = UUID.randomUUID().toString()
        val directory = File(application.noBackupFilesDir, "fl002-version-test-$suffix").apply { mkdirs() }
        val alias = "hugo.first-life.identity.version-test.$suffix"
        try {
            File(directory, "hugo-identity.enc").writeText(
                """{"envelopeVersion":"2","algorithm":"AES/GCM/NoPadding","keyVersion":"1","nonce":"AAAAAAAAAAAAAAAA","ciphertext":"AAAAAAAAAAAAAAAAAAAAAA=="}""",
                Charsets.UTF_8,
            )

            val snapshot = HugoIdentityBootstrapper(
                AndroidHugoIdentityStorage(directory, alias),
            ).bootstrap(HugoBootstrapRunReference.newReference())

            assertEquals(HugoIdentityBootstrapStatus.UNSUPPORTED_SCHEMA, snapshot.status)
            assertEquals(HugoIdentityRecoveryState.EXPLICIT_RESET_REQUIRED, snapshot.recoveryState)
            assertNull(snapshot.identity)
        } finally {
            AndroidHugoIdentityStorage(directory, alias).reset()
            directory.deleteRecursively()
        }
    }
}
