package com.autoflow.core.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.autoflow.automation.actions.ClickAction
import com.autoflow.automation.actions.LongPressAction
import com.autoflow.automation.actions.SwipeAction

/**
 * AutoFlow's AccessibilityService — the core automation engine.
 *
 * This service is required by Android to perform programmatic gestures
 * (clicks, swipes, long presses) on the screen. It uses the
 * [GestureDescription] API available on Android 7+ (API 24).
 *
 * The service exposes a static [instance] so that the overlay and
 * click engine can communicate with it to dispatch gestures.
 *
 * IMPORTANT: The user must manually enable this service in
 * Settings → Accessibility → AutoFlow.
 */
class AutoFlowAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoFlowA11y"

        /**
         * Static reference to the running service instance.
         * Null when the service is not connected.
         */
        @Volatile
        var instance: AutoFlowAccessibilityService? = null
            private set

        /**
         * Whether the accessibility service is currently connected and ready.
         */
        val isConnected: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AutoFlow Accessibility Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't process accessibility events.
        // AutoFlow only uses gesture dispatch, not event monitoring.
    }

    override fun onInterrupt() {
        Log.w(TAG, "AutoFlow Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "AutoFlow Accessibility Service destroyed")
    }

    // ── Gesture Dispatch API ───────────────────────────────────────

    /**
     * Performs a single click (tap) at the given screen coordinates.
     *
     * @param x X coordinate in pixels
     * @param y Y coordinate in pixels
     * @param duration Touch duration in ms (default 100ms for a natural tap)
     * @return true if the gesture was dispatched successfully
     */
    fun performClick(x: Float, y: Float, duration: Long = 100L): Boolean {
        val gesture = ClickAction.create(x, y, duration)
        return dispatchGestureCompat(gesture)
    }

    /**
     * Performs a long press at the given screen coordinates.
     *
     * @param x X coordinate in pixels
     * @param y Y coordinate in pixels
     * @param duration Hold duration in ms (default 500ms)
     * @return true if the gesture was dispatched successfully
     */
    fun performLongPress(x: Float, y: Float, duration: Long = 500L): Boolean {
        val gesture = LongPressAction.create(x, y, duration)
        return dispatchGestureCompat(gesture)
    }

    /**
     * Performs a swipe from one point to another.
     *
     * @param startX Start X in pixels
     * @param startY Start Y in pixels
     * @param endX End X in pixels
     * @param endY End Y in pixels
     * @param duration Swipe duration in ms (default 300ms)
     * @return true if the gesture was dispatched successfully
     */
    fun performSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        duration: Long = 300L
    ): Boolean {
        val gesture = SwipeAction.create(startX, startY, endX, endY, duration)
        return dispatchGestureCompat(gesture)
    }

    /**
     * Dispatches a gesture with error handling.
     */
    private fun dispatchGestureCompat(gesture: GestureDescription): Boolean {
        return try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Gesture completed")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Gesture cancelled")
                }
            }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch gesture", e)
            false
        }
    }
}
