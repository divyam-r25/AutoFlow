package com.autoflow.core.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.autoflow.automation.engine.ClickEngine
import com.autoflow.automation.models.ActionType
import com.autoflow.automation.models.AutomationConfig
import com.autoflow.automation.models.ClickPoint
import com.autoflow.automation.models.EngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.abs

/**
 * Foreground service that manages the floating overlay UI.
 *
 * Components:
 * - **Floating Bubble**: A draggable circle that toggles the control panel
 * - **Control Panel**: Buttons for Play, Pause, Stop, Add Point, Close
 * - **Click Point Markers**: Numbered draggable circles at each saved position
 *
 * The overlay communicates with [AutoFlowAccessibilityService] through the
 * [ClickEngine] to execute automation sequences.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "autoflow_overlay"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.autoflow.ACTION_STOP"

        // Colors matching AutoFlowColors
        private const val COLOR_PRIMARY = 0xFF3B82F6.toInt()
        private const val COLOR_PRIMARY_DARK = 0xFF1D4ED8.toInt()
        private const val COLOR_SURFACE = 0xFF1E293B.toInt()
        private const val COLOR_CARD = 0xFF334155.toInt()
        private const val COLOR_SUCCESS = 0xFF22C55E.toInt()
        private const val COLOR_WARNING = 0xFFF59E0B.toInt()
        private const val COLOR_ERROR = 0xFFEF4444.toInt()
        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_TEXT_SECONDARY = 0xFFCBD5E1.toInt()
    }

    private lateinit var windowManager: WindowManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Bubble
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    // Control Panel
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var isPanelVisible = false

    // Click point markers
    private data class MarkerData(
        val view: View,
        val params: WindowManager.LayoutParams,
        var id: Int
    )
    private val markers = mutableListOf<MarkerData>()
    private var nextPointId = 1

    // Engine
    private val clickEngine = ClickEngine()
    private var isRunning = false

    // Play/Pause button reference for updating icon
    private var playPauseButton: ImageView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        createBubble()
        observeEngineState()
        Log.i(TAG, "OverlayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                clickEngine.stop()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        clickEngine.stop()
        removeAllOverlays()
        serviceScope.cancel()
        Log.i(TAG, "OverlayService destroyed")
    }

    // ── Notification ────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(com.autoflow.R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(com.autoflow.R.string.notification_channel_description)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoFlow")
            .setContentText("Overlay is active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(
                Notification.Action.Builder(
                    null, "Stop", stopPendingIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    // ── Bubble ──────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubble() {
        val sizePx = dpToPx(56)

        val bubbleIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

        bubbleView = FrameLayout(this).apply {
            background = createCircleDrawable(COLOR_PRIMARY)
            elevation = dpToPx(8).toFloat()
            val iconSize = dpToPx(28)
            val padding = (sizePx - iconSize) / 2
            setPadding(padding, padding, padding, padding)
            addView(bubbleIcon, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        bubbleParams = createOverlayParams(sizePx, sizePx).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(8)
            y = dpToPx(200)
        }

        bubbleView?.setOnTouchListener(createDragTouchListener(bubbleParams!!) {
            togglePanel()
        })

        windowManager.addView(bubbleView, bubbleParams)
    }

    // ── Control Panel ───────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun createPanel() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRectDrawable(COLOR_SURFACE, dpToPx(16))
            elevation = dpToPx(12).toFloat()
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
        }

        // Title
        val title = TextView(this).apply {
            text = "AutoFlow"
            setTextColor(COLOR_TEXT)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        panel.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpToPx(8) })

        // Button row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // Play / Pause button
        playPauseButton = createPanelButton(
            if (isRunning) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            COLOR_SUCCESS
        ) {
            onPlayPauseClicked()
        }
        buttonRow.addView(playPauseButton, createButtonParams())

        // Stop button
        buttonRow.addView(
            createPanelButton(android.R.drawable.ic_delete, COLOR_ERROR) {
                onStopClicked()
            }, createButtonParams()
        )

        // Add Point button
        buttonRow.addView(
            createPanelButton(android.R.drawable.ic_input_add, COLOR_PRIMARY) {
                addClickPoint()
            }, createButtonParams()
        )

        // Close button
        buttonRow.addView(
            createPanelButton(android.R.drawable.ic_menu_close_clear_cancel, COLOR_CARD) {
                stopSelf()
            }, createButtonParams()
        )

        panel.addView(buttonRow)

        // Point count label
        val pointCount = TextView(this).apply {
            text = "${markers.size} click point${if (markers.size != 1) "s" else ""}"
            setTextColor(COLOR_TEXT_SECONDARY)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        panel.addView(pointCount, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpToPx(6) })

        panelView = panel
        panelParams = createOverlayParams(
            dpToPx(220),
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleParams?.x ?: 0) + dpToPx(64)
            y = bubbleParams?.y ?: dpToPx(200)
        }

        windowManager.addView(panelView, panelParams)
        isPanelVisible = true
    }

    private fun togglePanel() {
        if (isPanelVisible) {
            hidePanel()
        } else {
            createPanel()
        }
    }

    private fun hidePanel() {
        panelView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Panel already removed")
            }
        }
        panelView = null
        playPauseButton = null
        isPanelVisible = false
    }

    // ── Click Point Markers ─────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun addClickPoint() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)

        val id = nextPointId++
        val markerSize = dpToPx(36)

        val marker = FrameLayout(this).apply {
            background = createCircleDrawable(COLOR_PRIMARY)
            elevation = dpToPx(4).toFloat()
        }

        val label = TextView(this).apply {
            text = "$id"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        marker.addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val markerParams = createOverlayParams(markerSize, markerSize).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels / 2 - markerSize / 2
            y = metrics.heightPixels / 2 - markerSize / 2
        }

        // Long press to delete
        var longPressDetected = false
        val longPressRunnable = Runnable {
            longPressDetected = true
            removeClickPoint(id)
        }

        marker.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        longPressDetected = false
                        initialX = markerParams.x
                        initialY = markerParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        v.handler?.postDelayed(longPressRunnable, 800)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > 5 || abs(dy) > 5) {
                            v.handler?.removeCallbacks(longPressRunnable)
                        }
                        markerParams.x = initialX + dx.toInt()
                        markerParams.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(v, markerParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.handler?.removeCallbacks(longPressRunnable)
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(marker, markerParams)
        markers.add(MarkerData(marker, markerParams, id))

        // Refresh panel to update point count
        if (isPanelVisible) {
            hidePanel()
            createPanel()
        }

        Log.i(TAG, "Added click point #$id at center of screen")
    }

    private fun removeClickPoint(id: Int) {
        val index = markers.indexOfFirst { it.id == id }
        if (index >= 0) {
            val markerData = markers.removeAt(index)
            try {
                windowManager.removeView(markerData.view)
            } catch (e: Exception) {
                Log.w(TAG, "Marker already removed")
            }

            // Refresh panel to update point count
            if (isPanelVisible) {
                hidePanel()
                createPanel()
            }

            Log.i(TAG, "Removed click point #$id")
        }
    }

    // ── Engine Controls ─────────────────────────────────────────────

    private fun onPlayPauseClicked() {
        when (clickEngine.state.value) {
            EngineState.IDLE, EngineState.COMPLETED -> {
                startAutomation()
            }
            EngineState.RUNNING -> {
                clickEngine.pause()
            }
            EngineState.PAUSED -> {
                clickEngine.resume()
            }
        }
    }

    private fun onStopClicked() {
        clickEngine.stop()
        showMarkers()
        isRunning = false
        updatePlayPauseIcon()
    }

    private fun startAutomation() {
        if (markers.isEmpty()) {
            Log.w(TAG, "No click points to execute")
            return
        }

        // Collect current marker positions as click points
        val points = markers.mapIndexed { index, markerData ->
            val halfSize = dpToPx(18) // half of marker size
            ClickPoint(
                id = markerData.id,
                x = (markerData.params.x + halfSize).toFloat(),
                y = (markerData.params.y + halfSize).toFloat(),
                actionType = ActionType.CLICK,
                duration = 100L,
                order = index
            )
        }

        val config = AutomationConfig(
            executionMode = com.autoflow.automation.models.ExecutionMode.SEQUENTIAL,
            isInfinite = true,
            clickIntervalMs = 500L
        )

        // Hide markers and panel during automation so they don't intercept gestures
        hidePanel()
        hideMarkers()

        isRunning = true
        clickEngine.start(points, config, serviceScope)
    }

    private fun hideMarkers() {
        markers.forEach { markerData ->
            markerData.params.flags = markerData.params.flags or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try {
                markerData.view.alpha = 0.4f
                windowManager.updateViewLayout(markerData.view, markerData.params)
            } catch (e: Exception) {
                Log.w(TAG, "Could not update marker")
            }
        }
    }

    private fun showMarkers() {
        markers.forEach { markerData ->
            markerData.params.flags = markerData.params.flags and
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try {
                markerData.view.alpha = 1.0f
                windowManager.updateViewLayout(markerData.view, markerData.params)
            } catch (e: Exception) {
                Log.w(TAG, "Could not update marker")
            }
        }
    }

    private fun observeEngineState() {
        clickEngine.state.onEach { state ->
            when (state) {
                EngineState.COMPLETED -> {
                    isRunning = false
                    showMarkers()
                    updatePlayPauseIcon()
                }
                else -> {
                    updatePlayPauseIcon()
                }
            }
        }.launchIn(serviceScope)
    }

    private fun updatePlayPauseIcon() {
        playPauseButton?.setImageResource(
            when (clickEngine.state.value) {
                EngineState.RUNNING -> android.R.drawable.ic_media_pause
                else -> android.R.drawable.ic_media_play
            }
        )
    }

    // ── Cleanup ─────────────────────────────────────────────────────

    private fun removeAllOverlays() {
        bubbleView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { }
        }
        bubbleView = null

        hidePanel()

        markers.forEach { markerData ->
            try { windowManager.removeView(markerData.view) } catch (e: Exception) { }
        }
        markers.clear()
    }

    // ── Utility ─────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun createOverlayParams(width: Int, height: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
    }

    private fun createCircleDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun createRoundRectDrawable(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createDragTouchListener(
        params: WindowManager.LayoutParams,
        onClick: () -> Unit
    ): View.OnTouchListener {
        return object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(v, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        // If the touch barely moved, treat it as a click
                        if (abs(event.rawX - initialTouchX) < 10 &&
                            abs(event.rawY - initialTouchY) < 10
                        ) {
                            onClick()
                        }
                        return true
                    }
                }
                return false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createPanelButton(
        iconRes: Int,
        bgColor: Int,
        onClick: () -> Unit
    ): ImageView {
        val size = dpToPx(42)
        return ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            background = createCircleDrawable(bgColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = dpToPx(10)
            setPadding(pad, pad, pad, pad)
            elevation = dpToPx(2).toFloat()
            setOnClickListener { onClick() }
        }
    }

    private fun createButtonParams(): LinearLayout.LayoutParams {
        val size = dpToPx(42)
        return LinearLayout.LayoutParams(size, size).apply {
            marginStart = dpToPx(6)
            marginEnd = dpToPx(6)
        }
    }
}
