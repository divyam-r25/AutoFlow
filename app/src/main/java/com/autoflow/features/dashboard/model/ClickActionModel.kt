package com.autoflow.features.dashboard.model

import com.autoflow.automation.models.ActionType

data class ClickActionModel(
    val id: Int,
    val label: String,
    val actionType: ActionType,
    val durationMs: Long,
    val delayMs: Long
)
