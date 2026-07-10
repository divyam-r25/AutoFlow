package com.autoflow.permission

import android.content.Context
import android.provider.Settings

class PermissionManager(
    private val context: Context
) {

    fun isOverlayPermissionGranted(): Boolean {

        return Settings.canDrawOverlays(context)

    }

    /*
     * Accessibility permission cannot be reliably checked yet.
     * We'll implement this properly in Milestone 3 when we build
     * the Accessibility Service.
     */
    fun isAccessibilityPermissionGranted(): Boolean {

        return false

    }

}