package com.autoflow.automation.actions

import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * Creates a click gesture at the specified screen coordinates.
 *
 * A click is a brief touch event at a single point. The default
 * duration of 100ms simulates a natural finger tap.
 */
object ClickAction {

    /**
     * Builds a GestureDescription for a single click.
     *
     * @param x X coordinate in screen pixels
     * @param y Y coordinate in screen pixels
     * @param duration How long the touch lasts in ms (default 100ms)
     * @return A GestureDescription ready to be dispatched
     */
    fun create(x: Float, y: Float, duration: Long = 100L): GestureDescription {
        val path = Path().apply {
            moveTo(x, y)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
    }
}
