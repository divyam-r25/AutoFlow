package com.autoflow.automation.models

/**
 * Represents a single automation point on the screen.
 *
 * @param id Unique identifier for this point
 * @param x The X coordinate on screen (pixels)
 * @param y The Y coordinate on screen (pixels)
 * @param actionType The type of action to perform at this point
 * @param duration How long the tap/gesture lasts in ms (e.g. 50ms for a fast tap, 500ms for long press)
 * @param delayMs Post-click wait time in ms; 0 means fall back to the global [AutomationConfig.clickIntervalMs]
 * @param endX End X coordinate for swipe gestures
 * @param endY End Y coordinate for swipe gestures
 * @param order Execution order (lower = earlier)
 * @param label Optional user-defined label for this point
 */
data class ClickPoint(
    val id: Int,
    val x: Float,
    val y: Float,
    val actionType: ActionType = ActionType.CLICK,
    val duration: Long = 100L,
    val delayMs: Long = 0L,
    val endX: Float = 0f,
    val endY: Float = 0f,
    val order: Int = 0,
    val label: String = ""
)
