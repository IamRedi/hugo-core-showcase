package io.redi.robot

import android.app.Application
import io.redi.robot.firstlife.identity.HugoBootstrapRunReference
import io.redi.robot.firstlife.identity.HugoIdentityBootstrapSnapshot
import io.redi.robot.firstlife.identity.HugoIdentityBootstrapper
import io.redi.robot.platform.storage.AndroidHugoIdentityStorage
import java.io.File

/** Process composition root for FL002 only; it creates no body, provider, or session state. */
class HugoFirstLifeApplication : Application() {
    lateinit var hugoIdentityBootstrapSnapshot: HugoIdentityBootstrapSnapshot
        private set

    override fun onCreate() {
        super.onCreate()
        hugoIdentityBootstrapSnapshot = createIdentityBootstrapper().bootstrap(
            HugoBootstrapRunReference.newReference(),
        )
    }

    internal fun createIdentityBootstrapper(): HugoIdentityBootstrapper = HugoIdentityBootstrapper(
        AndroidHugoIdentityStorage(File(noBackupFilesDir, IDENTITY_DIRECTORY)),
    )

    private companion object {
        const val IDENTITY_DIRECTORY = "hugo-identity"
    }
}
