package com.autoflow.features.permissions.model

enum class PermissionType {
    ACCESSIBILITY,
    OVERLAY,
    BATTERY_OPTIMIZATION
}

data class PermissionState(
    val type: PermissionType,
    val title: String,
    val description: String,
    val granted: Boolean
)
