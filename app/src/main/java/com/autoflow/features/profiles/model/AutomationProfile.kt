package com.autoflow.features.profiles.model

import com.autoflow.automation.models.ExecutionMode

data class AutomationProfile(
    val id: String,
    val name: String,
    val executionMode: ExecutionMode,
    val defaultDurationMs: Long,
    val defaultDelayMs: Long
)
