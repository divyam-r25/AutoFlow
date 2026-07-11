package com.autoflow.automation.actions

import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * Creates a swipe gesture between two screen coordinates.
 *
 * A swipe is a path-based gesture that moves from a start point
 * to an end point over a specified duration. Useful for scrolling,
 * dismissing notifications, and navigating between screens.
 */
object SwipeAction {

    /** Default swipe duration in milliseconds */
    private const val DEFAULT_DURATION = 300L

    /**
     * Builds a GestureDescription for a swipe gesture.
     *
     * @param startX Start X coordinate in screen pixels
     * @param startY Start Y coordinate in screen pixels
     * @param endX End X coordinate in screen pixels
     * @param endY End Y coordinate in screen pixels
     * @param duration How long the swipe takes in ms (default 300ms)
     * @return A GestureDescription ready to be dispatched
     */
    fun create(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long = DEFAULT_DURATION
    ): GestureDescription {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
    }
}
