package com.autoflow.features.dashboard.model

import com.autoflow.features.permissions.model.PermissionState
import com.autoflow.features.permissions.model.PermissionType
import com.autoflow.features.profiles.model.AutomationProfile

data class DashboardState(
    val permissionStates: List<PermissionState> = emptyList(),
    val isAutomationRunning: Boolean = false,
    val selectedProfile: AutomationProfile? = null,
    val clickActions: List<ClickActionModel> = emptyList()
) {
    val accessibilityGranted: Boolean
        get() = permissionStates.isGranted(PermissionType.ACCESSIBILITY)

    val overlayGranted: Boolean
        get() = permissionStates.isGranted(PermissionType.OVERLAY)

    val batteryOptimizationDisabled: Boolean
        get() = permissionStates.isGranted(PermissionType.BATTERY_OPTIMIZATION)

    val allPermissionsGranted: Boolean
        get() = accessibilityGranted && overlayGranted
}

private fun List<PermissionState>.isGranted(type: PermissionType): Boolean {
    return firstOrNull { it.type == type }?.granted == true
}
