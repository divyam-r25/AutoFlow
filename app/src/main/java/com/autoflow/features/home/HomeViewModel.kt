package com.autoflow.features.home

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.autoflow.core.overlay.OverlayService
import com.autoflow.core.permission.PermissionManager
import com.autoflow.data.model.DashboardState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel for the Home screen.
 *
 * Uses [AndroidViewModel] because it needs the Application context
 * to check system permissions via [PermissionManager].
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val permissionManager = PermissionManager(application)

    private val _dashboardState = MutableStateFlow(DashboardState())
    val dashboardState: StateFlow<DashboardState> = _dashboardState.asStateFlow()

    init {
        refreshPermissions()
    }

    /**
     * Re-checks all permission statuses from the system.
     * Should be called every time the screen resumes (user may have
     * toggled permissions in Settings and returned to the app).
     */
    fun refreshPermissions() {
        _dashboardState.value = _dashboardState.value.copy(
            accessibilityGranted = permissionManager.isAccessibilityServiceEnabled(),
            overlayGranted = permissionManager.isOverlayPermissionGranted(),
            batteryOptimizationDisabled = permissionManager.isBatteryOptimizationDisabled()
        )
    }

    /** Intent to open system Accessibility Settings */
    fun getAccessibilitySettingsIntent(): Intent =
        permissionManager.createAccessibilitySettingsIntent()

    /** Intent to open Overlay Permission for this app */
    fun getOverlaySettingsIntent(): Intent =
        permissionManager.createOverlaySettingsIntent()

    /** Intent to request battery optimization exemption */
    fun getBatteryOptimizationIntent(): Intent =
        permissionManager.createBatteryOptimizationIntent()

    fun updateAutomationStatus(running: Boolean) {
        _dashboardState.value = _dashboardState.value.copy(
            isAutomationRunning = running
        )
    }

    /**
     * Starts or stops the overlay service based on current state.
     * Returns the new running state.
     */
    fun toggleAutomation(): Boolean {
        val context = getApplication<Application>()
        val newRunning = !_dashboardState.value.isAutomationRunning

        if (newRunning) {
            val intent = Intent(context, OverlayService::class.java)
            context.startForegroundService(intent)
        } else {
            val intent = Intent(context, OverlayService::class.java)
            context.stopService(intent)
        }

        updateAutomationStatus(newRunning)
        return newRunning
    }
}
