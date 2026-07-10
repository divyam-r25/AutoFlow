package com.autoflow.automation.actions

import android.accessibilityservice.GestureDescription
import android.graphics.Path

/**
 * Creates a long press gesture at the specified screen coordinates.
 *
 * A long press is identical to a click but with a longer duration
 * (typically 500ms+). This triggers long-press context menus and
 * similar UI behaviors in target apps.
 */
object LongPressAction {

    /** Default long press duration in milliseconds */
    private const val DEFAULT_DURATION = 500L

    /**
     * Builds a GestureDescription for a long press.
     *
     * @param x X coordinate in screen pixels
     * @param y Y coordinate in screen pixels
     * @param duration How long to hold the press in ms (default 500ms)
     * @return A GestureDescription ready to be dispatched
     */
    fun create(x: Float, y: Float, duration: Long = DEFAULT_DURATION): GestureDescription {
        val path = Path().apply {
            moveTo(x, y)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
    }
}
