package com.autoflow.core.permission

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils

/**
 * Manages checking and launching permission-related settings for AutoFlow.
 *
 * AutoFlow requires three permissions to function:
 * 1. Accessibility Service — to dispatch gestures (clicks, swipes, long press)
 * 2. Overlay Permission — to show floating controls and click point markers
 * 3. Battery Optimization — to prevent the system from killing the service (optional but recommended)
 */
class PermissionManager(
    private val context: Context
) {

    companion object {
        private const val ACCESSIBILITY_SERVICE_CLASS =
            "com.autoflow.core.service.AutoFlowAccessibilityService"
    }

    /**
     * Checks whether AutoFlow's AccessibilityService is currently enabled
     * by querying the system's enabled accessibility services list.
     */
    fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(context, ACCESSIBILITY_SERVICE_CLASS)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentString = splitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentString)
            if (enabledComponent != null && enabledComponent == expectedComponent) {
                return true
            }
        }
        return false
    }

    /**
     * Checks whether the app has permission to draw overlays.
     */
    fun isOverlayPermissionGranted(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Checks whether battery optimization is disabled for this app.
     * When disabled, the system won't kill our services aggressively.
     */
    fun isBatteryOptimizationDisabled(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Creates an intent to open the Accessibility Settings screen.
     * The user must manually find and enable AutoFlow from the list.
     */
    fun createAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Creates an intent to open the Overlay Permission screen for this app.
     */
    fun createOverlaySettingsIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Creates an intent to request battery optimization exemption.
     * This shows a system dialog (not a settings screen).
     */
    fun createBatteryOptimizationIntent(): Intent {
        return Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
