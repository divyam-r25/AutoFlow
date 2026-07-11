package com.autoflow.features.dashboard

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.autoflow.core.overlay.OverlayService
import com.autoflow.core.permission.PermissionManager
import com.autoflow.features.dashboard.model.DashboardState
import com.autoflow.features.permissions.model.PermissionState
import com.autoflow.features.permissions.model.PermissionType
import com.autoflow.features.profiles.data.ProfileStorage
import com.autoflow.features.profiles.model.AutomationProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val permissionManager = PermissionManager(application)
    private val profileStorage = ProfileStorage(application)

    private val profiles: List<AutomationProfile> = profileStorage.loadProfiles()

    private val _dashboardState = MutableStateFlow(DashboardState())
    val dashboardState: StateFlow<DashboardState> = _dashboardState.asStateFlow()

    init {
        val selectedProfileId = profileStorage.loadSelectedProfileId()
            ?: profiles.firstOrNull()?.id
            ?: DEFAULT_PROFILE_ID

        selectProfile(selectedProfileId)
        refreshPermissions()
    }

    fun refreshPermissions() {
        _dashboardState.value = _dashboardState.value.copy(
            permissionStates = buildPermissionStates()
        )
    }

    fun getAccessibilitySettingsIntent(): Intent =
        permissionManager.createAccessibilitySettingsIntent()

    fun getOverlaySettingsIntent(): Intent =
        permissionManager.createOverlaySettingsIntent()

    fun getBatteryOptimizationIntent(): Intent =
        permissionManager.createBatteryOptimizationIntent()

    fun toggleAutomation(): Boolean {
        val context = getApplication<Application>()
        val newRunning = !_dashboardState.value.isAutomationRunning

        if (newRunning) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        } else {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        _dashboardState.value = _dashboardState.value.copy(isAutomationRunning = newRunning)
        return newRunning
    }

    fun selectProfile(profileId: String) {
        val profile = profiles.firstOrNull { it.id == profileId } ?: profiles.firstOrNull() ?: return
        profileStorage.saveSelectedProfileId(profile.id)

        val actions = profileStorage.loadClickActions(profile.id)

        _dashboardState.value = _dashboardState.value.copy(
            selectedProfile = profile,
            clickActions = actions
        )
    }

    private fun buildPermissionStates(): List<PermissionState> {
        return listOf(
            PermissionState(
                type = PermissionType.ACCESSIBILITY,
                title = "Accessibility Service",
                description = "Required for automated clicks and gestures",
                granted = permissionManager.isAccessibilityServiceEnabled()
            ),
            PermissionState(
                type = PermissionType.OVERLAY,
                title = "Floating Overlay",
                description = "Required to show click points on screen",
                granted = permissionManager.isOverlayPermissionGranted()
            ),
            PermissionState(
                type = PermissionType.BATTERY_OPTIMIZATION,
                title = "Battery Optimization",
                description = "Prevents system from stopping automation",
                granted = permissionManager.isBatteryOptimizationDisabled()
            )
        )
    }

    private companion object {
        const val DEFAULT_PROFILE_ID = "default"
    }
}
