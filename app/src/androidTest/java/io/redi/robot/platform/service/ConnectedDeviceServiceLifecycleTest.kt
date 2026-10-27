package io.redi.robot.platform.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.redi.robot.body.looi.connection.ConnectionPhase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the service bind/stop boundary without scanning or connecting to a BLE device. */
@RunWith(AndroidJUnit4::class)
class ConnectedDeviceServiceLifecycleTest {
    @Test
    fun boundServiceCanEndAnIdleUserSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        var binder: ConnectedDeviceService.ConnectionBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service as? ConnectedDeviceService.ConnectionBinder
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }

        assertTrue(context.bindService(Intent(context, ConnectedDeviceService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            val service = binder
            assertNotNull(service)
            assertEquals(ConnectionPhase.IDLE, service?.currentState()?.phase)
            assertEquals(ConnectionPhase.IDLE, service?.endUserSession()?.phase)
        } finally {
            context.unbindService(connection)
        }
    }
}
