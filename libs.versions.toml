package com.autoflow.data.model

/**
 * Represents the current state of the home dashboard.
 * Used by HomeViewModel to drive the UI.
 */
data class DashboardState(

    val accessibilityGranted: Boolean = false,

    val overlayGranted: Boolean = false,

    val batteryOptimizationDisabled: Boolean = false,

    val isAutomationRunning: Boolean = false

) {
    /**
     * All required permissions must be granted before automation can start.
     * Battery optimization is recommended but not required.
     */
    val allPermissionsGranted: Boolean
        get() = accessibilityGranted && overlayGranted
}