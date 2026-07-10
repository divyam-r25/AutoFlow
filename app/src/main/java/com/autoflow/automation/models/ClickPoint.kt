package com.autoflow.automation.models

/**
 * Represents a single automation point on the screen.
 *
 * @param id Unique identifier for this point
 * @param x The X coordinate on screen (pixels)
 * @param y The Y coordinate on screen (pixels)
 * @param actionType The type of action to perform at this point
 * @param duration Duration in milliseconds (click duration, long press hold time, swipe duration, or wait time)
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
    val endX: Float = 0f,
    val endY: Float = 0f,
    val order: Int = 0,
    val label: String = ""
)
