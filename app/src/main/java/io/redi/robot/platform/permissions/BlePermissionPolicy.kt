package io.redi.robot.platform.permissions

import android.Manifest
import android.os.Build

data class PermissionDecisionState(
    val requiredPermissions: Set<String>,
    val grantedPermissions: Set<String>,
    val deniedPermissions: Set<String>,
    val permanentlyDeniedPermissions: Set<String>,
)

fun permissionRequirementForRuntime(apiLevel: Int = Build.VERSION.SDK_INT): List<String> =
    if (apiLevel >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }

fun evaluatePermissionState(
    requiredPermissions: Set<String>,
    isGranted: (String) -> Boolean,
    shouldShowRationale: (String) -> Boolean,
    requestedAtLeastOnce: Boolean,
): PermissionDecisionState {
    val grantedPermissions = requiredPermissions.filter { isGranted(it) }.toSet()
    val deniedPermissions = requiredPermissions - grantedPermissions
    val permanentlyDeniedPermissions =
        if (requestedAtLeastOnce) {
            deniedPermissions.filterNot { shouldShowRationale(it) }.toSet()
        } else {
            emptySet()
        }
    return PermissionDecisionState(
        requiredPermissions = requiredPermissions,
        grantedPermissions = grantedPermissions,
        deniedPermissions = deniedPermissions,
        permanentlyDeniedPermissions = permanentlyDeniedPermissions,
    )
}

val PermissionDecisionState.allPermissionsGranted: Boolean
    get() = deniedPermissions.isEmpty()

val PermissionDecisionState.hasPermanentlyDenied: Boolean
    get() = permanentlyDeniedPermissions.isNotEmpty()

val PermissionDecisionState.canRequestPermissionAgain: Boolean
    get() = deniedPermissions.any { it !in permanentlyDeniedPermissions }
