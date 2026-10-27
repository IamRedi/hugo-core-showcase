package io.redi.robot.platform.permissions

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class BlePermissionPolicyTest {

    @Test
    fun `sdk 30 requires coarse and fine location runtime permission`() {
        val permissions = permissionRequirementForRuntime(Build.VERSION_CODES.R)
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            permissions,
        )
    }

    @Test
    fun `sdk 31 requires scan and connect runtime permissions`() {
        val permissions = permissionRequirementForRuntime(Build.VERSION_CODES.S)
        assertEquals(
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
            permissions,
        )
    }

    @Test
    fun `permission state marks denied as requestable before first request`() {
        val required = setOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

        val state = evaluatePermissionState(
            requiredPermissions = required,
            isGranted = { false },
            shouldShowRationale = { false },
            requestedAtLeastOnce = false,
        )

        assertEquals(required, state.deniedPermissions)
        assertTrue(state.permanentlyDeniedPermissions.isEmpty())
        assertFalse(state.allPermissionsGranted)
    }

    @Test
    fun `permission state marks denied as permanently denied only after request`() {
        val required = setOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

        val state = evaluatePermissionState(
            requiredPermissions = required,
            isGranted = { false },
            shouldShowRationale = { false },
            requestedAtLeastOnce = true,
        )

        assertEquals(required, state.deniedPermissions)
        assertEquals(required, state.permanentlyDeniedPermissions)
        assertTrue(state.hasPermanentlyDenied)
        assertFalse(state.canRequestPermissionAgain)
    }
}
