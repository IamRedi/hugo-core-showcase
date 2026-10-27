package io.redi.robot.showcase

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ShowcaseAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test fun isolatedShowcaseAndActualReducerWalkthrough() {
        assertEquals("io.redi.hugo.showcase", context.packageName)
        assertEquals("1", device.executeShellCommand("getprop ro.kernel.qemu").trim())
        Configurator.getInstance().setWaitForIdleTimeout(1_000L)
        File(context.getExternalFilesDir(null), "showcase-captures").listFiles().orEmpty()
            .filter { it.extension in setOf("png", "xml") }.forEach { assertTrue(it.delete()) }
        @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES)
        val forbidden = setOf("android.permission.INTERNET", "android.permission.CAMERA", "android.permission.RECORD_AUDIO", "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN", "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")
        assertTrue("No network or physical-input permission in the evaluation APK", info.requestedPermissions.orEmpty().none { it in forbidden })
        assertTrue(info.activities.orEmpty().none { it.name == "io.redi.robot.MainActivity" })
        assertTrue(info.services.orEmpty().none { it.name.contains("ConnectedDeviceService") })
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitText("One identity.\nMany possible bodies.")
        shot("runtime")
        click("Safety"); click("Propose light")
        waitText("AWAITING_APPROVAL"); waitText("Virtual light: 0%  ·  Executed: 0")
        shot("proposal")
        click("Approve once"); waitText("VIRTUAL_ACTION_APPLIED"); waitText("Virtual light: 65%  ·  Executed: 1")
        shot("approved")
        click("Overspeed"); click("Approve once"); waitText("SPEED_LIMIT_EXCEEDED")
        shot("blocked")
        click("Stale command"); click("Approve once"); waitText("STALE_COMMAND")
        click("Propose light"); click("Virtual reconnect"); click("Approve once"); waitText("GENERATION_CHANGED")
        shot("generation")
        click("LOCAL STOP"); waitText("EMERGENCY_STOP_LATCHED"); shot("stop")
        click("Memory"); click("Create model candidate"); waitText("CANDIDATE")
        shot("memory-candidate")
        click("Try model self-validation"); waitText("SELF_VALIDATION_FORBIDDEN")
        shot("memory-blocked")
        click("Build user correction draft"); waitText("VALIDATED")
        shot("memory-correction")
        click("Architecture"); waitText("Boundaries by design."); shot("architecture")
        assertTrue(device.executeShellCommand("dumpsys activity services ${context.packageName}").let { !it.contains("ConnectedDeviceService") })
    }
    private fun click(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        do {
            refreshAccessibility()
            var target = device.findObject(By.text(text))
            while (target != null && !target.isClickable) target = target.parent
            if (target != null && target.isEnabled) { target.click(); return }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        shot("failure")
        fail("Visible, clickable and enabled: $text")
    }
    private fun waitText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        do {
            refreshAccessibility()
            if (device.hasObject(By.text(text))) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        shot("failure")
        fail("Visible: $text")
    }
    private fun refreshAccessibility() {
        // API 34+ cache can lag behind Compose's rendered state on the review
        // emulator. Re-read the actual node tree; keep exact result assertions.
        if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
    }
    private fun shot(name: String) {
        instrumentation.waitForIdleSync()
        val directory = File(context.getExternalFilesDir(null), "showcase-captures").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
        if (name == "failure") device.dumpWindowHierarchy(File(directory, "failure.xml"))
    }
}
