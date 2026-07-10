package com.autoflow.core.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autoflow.automation.engine.ClickEngine
import com.autoflow.automation.engine.ClickEvent
import com.autoflow.automation.models.ActionType
import com.autoflow.automation.models.AutomationConfig
import com.autoflow.automation.models.ClickPoint
import com.autoflow.automation.models.EngineState
import com.autoflow.automation.models.ExecutionMode
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
 * Features:
 * - **Floating Bubble**: Draggable trigger to show/hide control panel
 * - **Control Panel**: Play/Pause, Stop, Add Point, Settings, Close buttons
 * - **Click Point Markers**: Numbered, draggable markers; tap to configure individually
 * - **Point Config Dialog**: Per-pointer tap duration and delay controls
 * - **Global Settings Dialog**: Default duration/delay + Apply-to-All pointers
 * - **Tap Ripple Animation**: Expanding blue circle shown at each touch location
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "autoflow_overlay"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.autoflow.ACTION_STOP"

        // Design tokens matching AutoFlowColors
        private const val COLOR_PRIMARY    = 0xFF3B82F6.toInt()
        private const val COLOR_SURFACE    = 0xFF1E293B.toInt()
        private const val COLOR_CARD       = 0xFF334155.toInt()
        private const val COLOR_CARD_LIGHT = 0xFF475569.toInt()
        private const val COLOR_SUCCESS    = 0xFF22C55E.toInt()
        private const val COLOR_WARNING    = 0xFFF59E0B.toInt()
        private const val COLOR_ERROR      = 0xFFEF4444.toInt()
        private const val COLOR_TEXT       = 0xFFFFFFFF.toInt()
        private const val COLOR_TEXT_SEC   = 0xFFCBD5E1.toInt()
        private const val COLOR_DIVIDER    = 0xFF475569.toInt()
    }

    // ── Window Manager ───────────────────────────────────────────────
    private lateinit var windowManager: WindowManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Bubble ───────────────────────────────────────────────────────
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    // ── Control Panel ────────────────────────────────────────────────
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var isPanelVisible = false
    private var playPauseButton: ImageView? = null

    // ── Config / Settings Dialogs ────────────────────────────────────
    private var configDialogView: View? = null
    private var configDialogParams: WindowManager.LayoutParams? = null

    // ── Click Point Markers ──────────────────────────────────────────
    /**
     * Mutable state per marker — duration/delay are var so the config dialog can update them.
     */
    private data class MarkerData(
        val view: View,
        val params: WindowManager.LayoutParams,
        val id: Int,
        var duration: Long = 100L,    // tap hold time in ms
        var delayMs: Long = 0L        // post-click wait (0 = use global default)
    )
    private val markers = mutableListOf<MarkerData>()
    private var nextPointId = 1

    // ── Engine ───────────────────────────────────────────────────────
    private val clickEngine = ClickEngine()
    private var isRunning = false

    // ── Global Defaults ──────────────────────────────────────────────
    private var globalDuration: Long = 100L
    private var globalDelayMs: Long = 500L
    private var globalMode: ExecutionMode = ExecutionMode.SEQUENTIAL
    private var globalIsInfinite: Boolean = true

    // ─────────────────────────────────────────────────────────────────
    // Service Lifecycle
    // ─────────────────────────────────────────────────────────────────

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
        if (intent?.action == ACTION_STOP) {
            clickEngine.stop()
            stopSelf()
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

    // ─────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(com.autoflow.R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(com.autoflow.R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val stopPI = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoFlow")
            .setContentText("Overlay active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(null, "Stop", stopPI).build())
            .setOngoing(true)
            .build()
    }

    // ─────────────────────────────────────────────────────────────────
    // Floating Bubble
    // ─────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubble() {
        val sizePx = dpToPx(56)

        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

        bubbleView = FrameLayout(this).apply {
            background = createCircleDrawable(COLOR_PRIMARY)
            elevation = dpToPx(8).toFloat()
            val pad = (sizePx - dpToPx(28)) / 2
            setPadding(pad, pad, pad, pad)
            addView(icon, FrameLayout.LayoutParams(
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

    // ─────────────────────────────────────────────────────────────────
    // Control Panel
    // ─────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun createPanel() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRectDrawable(COLOR_SURFACE, dpToPx(16))
            elevation = dpToPx(12).toFloat()
            setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14))
        }

        // Title row
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "⚡ AutoFlow"
            setTextColor(COLOR_TEXT)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(titleRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpToPx(10) })

        // Button row
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // Play/Pause
        playPauseButton = createPanelButton(
            if (isRunning) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            COLOR_SUCCESS
        ) { onPlayPauseClicked() }
        buttonRow.addView(playPauseButton, createBtnParams())

        // Stop
        buttonRow.addView(
            createPanelButton(android.R.drawable.ic_delete, COLOR_ERROR) { onStopClicked() },
            createBtnParams()
        )

        // Add Point
        buttonRow.addView(
            createPanelButton(android.R.drawable.ic_input_add, COLOR_PRIMARY) { addClickPoint() },
            createBtnParams()
        )

        // Global Settings (gear icon)
        buttonRow.addView(
            createPanelButton(android.R.drawable.ic_menu_preferences, COLOR_WARNING) {
                showGlobalSettingsDialog()
            },
            createBtnParams()
        )

        // Close overlay
        buttonRow.addView(
            createPanelButton(android.R.drawable.ic_menu_close_clear_cancel, COLOR_CARD) { stopSelf() },
            createBtnParams()
        )

        panel.addView(buttonRow)

        // Point count label
        val countLabel = TextView(this).apply {
            text = buildPointCountLabel()
            setTextColor(COLOR_TEXT_SEC)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        panel.addView(countLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dpToPx(8) })

        panelView = panel
        panelParams = createOverlayParams(dpToPx(260), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleParams?.x ?: 0) + dpToPx(64)
            y = bubbleParams?.y ?: dpToPx(200)
        }

        windowManager.addView(panelView, panelParams)
        isPanelVisible = true
    }

    private fun togglePanel() {
        if (isPanelVisible) hidePanel() else createPanel()
    }

    private fun hidePanel() {
        panelView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        panelView = null
        playPauseButton = null
        isPanelVisible = false
    }

    private fun buildPointCountLabel(): String {
        val n = markers.size
        return if (n == 0) "No click points yet. Tap + to add."
        else "$n click point${if (n != 1) "s" else ""} configured"
    }

    // ─────────────────────────────────────────────────────────────────
    // Click Point Markers
    // ─────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun addClickPoint() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)

        val id = nextPointId++
        val size = dpToPx(44)

        // Outer ring effect
        val ring = FrameLayout(this).apply {
            background = createCircleDrawable(COLOR_PRIMARY)
            elevation = dpToPx(5).toFloat()
        }

        // Label
        val label = TextView(this).apply {
            text = "$id"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        ring.addView(label, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val markerParams = createOverlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels / 2 - size / 2
            y = metrics.heightPixels / 2 - size / 2
        }

        val markerData = MarkerData(
            view = ring,
            params = markerParams,
            id = id,
            duration = globalDuration,
            delayMs = 0L  // 0 means fall back to global default
        )

        ring.setOnTouchListener(createMarkerTouchListener(markerData))

        windowManager.addView(ring, markerParams)
        markers.add(markerData)

        if (isPanelVisible) { hidePanel(); createPanel() }

        Log.i(TAG, "Added click point #$id")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createMarkerTouchListener(markerData: MarkerData): View.OnTouchListener {
        return object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = markerData.params.x
                        initialY = markerData.params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > 8 || abs(dy) > 8) moved = true
                        markerData.params.x = initialX + dx.toInt()
                        markerData.params.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(v, markerData.params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            // Tap on marker → open individual config
                            showPointConfigDialog(markerData)
                        }
                        return true
                    }
                }
                return false
            }
        }
    }

    private fun removeClickPoint(id: Int) {
        val index = markers.indexOfFirst { it.id == id }
        if (index >= 0) {
            val md = markers.removeAt(index)
            try { windowManager.removeView(md.view) } catch (_: Exception) {}
            if (isPanelVisible) { hidePanel(); createPanel() }
            Log.i(TAG, "Removed click point #$id")
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Individual Point Config Dialog
    // ─────────────────────────────────────────────────────────────────

    private fun showPointConfigDialog(markerData: MarkerData) {
        dismissConfigDialog()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRectDrawable(COLOR_SURFACE, dpToPx(18))
            elevation = dpToPx(16).toFloat()
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerTitle = TextView(this).apply {
            text = "Point #${markerData.id}"
            setTextColor(COLOR_TEXT)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        val deleteBtn = createTextButton("✕ Delete", COLOR_ERROR) {
            removeClickPoint(markerData.id)
            dismissConfigDialog()
        }
        header.addView(headerTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(deleteBtn)
        card.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpToPx(12) })

        // Divider
        card.addView(makeDivider())

        // Tap Duration row
        card.addView(makeSectionLabel("⏱ Tap Duration"))
        val durationLabel = makeValueLabel("${markerData.duration} ms")
        card.addView(makeStepRow(
            label = durationLabel,
            step = 10L,
            min = 10L,
            max = 2000L,
            getValue = { markerData.duration },
            setValue = { v ->
                markerData.duration = v
                durationLabel.text = "$v ms"
                // Update marker color to reflect custom setting
                val ratio = v.toFloat() / 2000f
                val blended = blendColor(COLOR_PRIMARY, COLOR_WARNING, ratio)
                (markerData.view as? FrameLayout)?.background = createCircleDrawable(blended)
            }
        ))

        card.addView(makeSpacer(8))

        // Post-click Delay row
        card.addView(makeSectionLabel("⏸ Delay After Tap"))
        val effectiveDelay = if (markerData.delayMs == 0L) globalDelayMs else markerData.delayMs
        val delayLabel = makeValueLabel(if (markerData.delayMs == 0L) "Default (${globalDelayMs}ms)" else "${markerData.delayMs} ms")
        card.addView(makeStepRow(
            label = delayLabel,
            step = 100L,
            min = 0L,
            max = 10000L,
            getValue = { if (markerData.delayMs == 0L) effectiveDelay else markerData.delayMs },
            setValue = { v ->
                markerData.delayMs = if (v == 0L) 0L else v
                delayLabel.text = if (v == 0L) "Default (${globalDelayMs}ms)" else "$v ms"
            }
        ))

        card.addView(makeSpacer(12))

        // Reset + Done row
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        bottomRow.addView(createTextButton("Reset", COLOR_TEXT_SEC) {
            markerData.duration = globalDuration
            markerData.delayMs = 0L
            (markerData.view as? FrameLayout)?.background = createCircleDrawable(COLOR_PRIMARY)
            dismissConfigDialog()
        })
        bottomRow.addView(makeSpacer(8))
        bottomRow.addView(createTextButton("✓ Done", COLOR_SUCCESS) {
            dismissConfigDialog()
        })
        card.addView(bottomRow)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)

        configDialogView = card
        configDialogParams = createOverlayParams(
            dpToPx(260),
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = markerData.params.x + dpToPx(50)
            y = markerData.params.y.coerceAtMost(metrics.heightPixels - dpToPx(260))
        }
        windowManager.addView(configDialogView, configDialogParams)
    }

    // ─────────────────────────────────────────────────────────────────
    // Global Settings Dialog
    // ─────────────────────────────────────────────────────────────────

    private fun showGlobalSettingsDialog() {
        dismissConfigDialog()
        if (isPanelVisible) hidePanel()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundRectDrawable(COLOR_SURFACE, dpToPx(18))
            elevation = dpToPx(16).toFloat()
            setPadding(dpToPx(18), dpToPx(18), dpToPx(18), dpToPx(18))
        }

        // Title
        val title = TextView(this).apply {
            text = "⚙ Global Settings"
            setTextColor(COLOR_TEXT)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        card.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpToPx(12) })

        card.addView(makeDivider())

        // Default Duration
        card.addView(makeSectionLabel("⏱ Default Tap Duration"))
        val durLabel = makeValueLabel("$globalDuration ms")
        card.addView(makeStepRow(
            label = durLabel, step = 10L, min = 10L, max = 2000L,
            getValue = { globalDuration },
            setValue = { v -> globalDuration = v; durLabel.text = "$v ms" }
        ))

        card.addView(makeSpacer(10))

        // Default Delay
        card.addView(makeSectionLabel("⏸ Default Delay After Tap"))
        val delLabel = makeValueLabel("$globalDelayMs ms")
        card.addView(makeStepRow(
            label = delLabel, step = 100L, min = 100L, max = 10000L,
            getValue = { globalDelayMs },
            setValue = { v -> globalDelayMs = v; delLabel.text = "$v ms" }
        ))

        card.addView(makeSpacer(10))
        card.addView(makeDivider())
        card.addView(makeSpacer(10))

        // Apply to All button
        val applyAllBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = createRoundRectDrawable(COLOR_PRIMARY, dpToPx(10))
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            setOnClickListener {
                applyGlobalToAllMarkers()
                dismissConfigDialog()
            }
        }
        val applyIcon = TextView(this).apply {
            text = "↻  Apply to All Pointers"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        applyAllBtn.addView(applyIcon)
        card.addView(applyAllBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dpToPx(8) })

        // Done
        val doneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        doneRow.addView(createTextButton("✓ Done", COLOR_SUCCESS) { dismissConfigDialog() })
        card.addView(doneRow)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)

        configDialogView = card
        configDialogParams = createOverlayParams(
            dpToPx(280),
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels / 2 - dpToPx(140)
            y = dpToPx(120)
        }
        windowManager.addView(configDialogView, configDialogParams)
    }

    private fun applyGlobalToAllMarkers() {
        markers.forEach { md ->
            md.duration = globalDuration
            md.delayMs = 0L
            (md.view as? FrameLayout)?.background = createCircleDrawable(COLOR_PRIMARY)
        }
        Log.i(TAG, "Applied global settings to all ${markers.size} markers")
    }

    private fun dismissConfigDialog() {
        configDialogView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        configDialogView = null
    }

    // ─────────────────────────────────────────────────────────────────
    // Engine Controls
    // ─────────────────────────────────────────────────────────────────

    private fun onPlayPauseClicked() {
        when (clickEngine.state.value) {
            EngineState.IDLE, EngineState.COMPLETED -> startAutomation()
            EngineState.RUNNING -> clickEngine.pause()
            EngineState.PAUSED -> clickEngine.resume()
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

        val halfSize = dpToPx(22).toFloat()
        val points = markers.mapIndexed { index, md ->
            ClickPoint(
                id = md.id,
                x = md.params.x + halfSize,
                y = md.params.y + halfSize,
                actionType = ActionType.CLICK,
                duration = md.duration,
                delayMs = md.delayMs,
                order = index
            )
        }

        val config = AutomationConfig(
            executionMode = globalMode,
            isInfinite = globalIsInfinite,
            clickIntervalMs = globalDelayMs
        )

        hidePanel()
        hideMarkers()
        isRunning = true
        clickEngine.start(points, config, serviceScope)
    }

    private fun hideMarkers() {
        markers.forEach { md ->
            md.params.flags = md.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try { md.view.alpha = 0.35f; windowManager.updateViewLayout(md.view, md.params) } catch (_: Exception) {}
        }
    }

    private fun showMarkers() {
        markers.forEach { md ->
            md.params.flags = md.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try { md.view.alpha = 1f; windowManager.updateViewLayout(md.view, md.params) } catch (_: Exception) {}
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
                else -> updatePlayPauseIcon()
            }
        }.launchIn(serviceScope)

        // Observe tap events → show ripple animation
        clickEngine.eventFlow.onEach { event ->
            showTapRipple(event.x, event.y)
        }.launchIn(serviceScope)
    }

    private fun updatePlayPauseIcon() {
        playPauseButton?.setImageResource(
            if (clickEngine.state.value == EngineState.RUNNING)
                android.R.drawable.ic_media_pause
            else
                android.R.drawable.ic_media_play
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // Tap Ripple Animation
    // ─────────────────────────────────────────────────────────────────

    /**
     * Displays an expanding, fading blue circle at the tap coordinates
     * to give visual feedback that a tap was performed.
     */
    private fun showTapRipple(x: Float, y: Float) {
        val rippleSize = dpToPx(60)

        val ripple = View(this).apply {
            background = createCircleDrawable(COLOR_PRIMARY)
            alpha = 0.85f
        }

        val rippleParams = createOverlayParams(rippleSize, rippleSize).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = (x - rippleSize / 2).toInt()
            this.y = (y - rippleSize / 2).toInt()
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }

        try {
            windowManager.addView(ripple, rippleParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add ripple view", e)
            return
        }

        // Scale + fade animator
        val animator = ValueAnimator.ofFloat(0.1f, 2.2f).apply {
            duration = 420
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                val progress = anim.animatedFraction
                ripple.scaleX = scale
                ripple.scaleY = scale
                ripple.alpha = 0.85f * (1f - progress)
                try {
                    windowManager.updateViewLayout(ripple, rippleParams)
                } catch (_: Exception) {}
            }
        }
        animator.start()

        // Remove after animation completes
        ripple.postDelayed({
            try { windowManager.removeView(ripple) } catch (_: Exception) {}
        }, 450)
    }

    // ─────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────

    private fun removeAllOverlays() {
        bubbleView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        bubbleView = null
        hidePanel()
        dismissConfigDialog()
        markers.forEach { md -> try { windowManager.removeView(md.view) } catch (_: Exception) {} }
        markers.clear()
    }

    // ─────────────────────────────────────────────────────────────────
    // Step-Control Row Builder (±10ms style controls)
    // ─────────────────────────────────────────────────────────────────

    private fun makeStepRow(
        label: TextView,
        step: Long,
        min: Long,
        max: Long,
        getValue: () -> Long,
        setValue: (Long) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createRoundRectDrawable(COLOR_CARD, dpToPx(10))
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
        }

        val btnMinus = createStepButton("−") {
            val newVal = (getValue() - step).coerceAtLeast(min)
            setValue(newVal)
        }
        val btnPlus = createStepButton("+") {
            val newVal = (getValue() + step).coerceAtMost(max)
            setValue(newVal)
        }

        // Fast-decrease button (–10x)
        val btnFastMinus = createStepButton("−−") {
            val newVal = (getValue() - step * 5).coerceAtLeast(min)
            setValue(newVal)
        }
        val btnFastPlus = createStepButton("++") {
            val newVal = (getValue() + step * 5).coerceAtMost(max)
            setValue(newVal)
        }

        row.addView(btnFastMinus, createBtnParams(36))
        row.addView(btnMinus, createBtnParams(36))
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(btnPlus, createBtnParams(36))
        row.addView(btnFastPlus, createBtnParams(36))

        return row
    }

    // ─────────────────────────────────────────────────────────────────
    // Widget Factory Helpers
    // ─────────────────────────────────────────────────────────────────

    private fun makeSectionLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(COLOR_TEXT_SEC)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dpToPx(4)
            layoutParams = lp
        }

    private fun makeValueLabel(initialText: String): TextView =
        TextView(this).apply {
            text = initialText
            setTextColor(COLOR_TEXT)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

    private fun createStepButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundRectDrawable(COLOR_CARD_LIGHT, dpToPx(6))
            setOnClickListener { onClick() }
        }

    private fun createTextButton(label: String, textColor: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(textColor)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
            setOnClickListener { onClick() }
        }

    private fun makeDivider(): View = View(this).apply {
        setBackgroundColor(COLOR_DIVIDER)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        lp.topMargin = dpToPx(4)
        lp.bottomMargin = dpToPx(4)
        layoutParams = lp
    }

    private fun makeSpacer(dp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(dp))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createPanelButton(iconRes: Int, bgColor: Int, onClick: () -> Unit): ImageView {
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

    private fun createBtnParams(sizeDp: Int = 42): LinearLayout.LayoutParams {
        val px = dpToPx(sizeDp)
        return LinearLayout.LayoutParams(px, px).apply {
            marginStart = dpToPx(4)
            marginEnd = dpToPx(4)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Drag Touch Listener for Bubble
    // ─────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun createDragTouchListener(
        params: WindowManager.LayoutParams,
        onClick: () -> Unit
    ): View.OnTouchListener {
        return object : View.OnTouchListener {
            private var initialX = 0; private var initialY = 0
            private var initialTouchX = 0f; private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(v, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (abs(event.rawX - initialTouchX) < 10 &&
                            abs(event.rawY - initialTouchY) < 10
                        ) onClick()
                        return true
                    }
                }
                return false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Drawing Utilities
    // ─────────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun createOverlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    )

    private fun createCircleDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun createRoundRectDrawable(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = radius.toFloat(); setColor(color)
    }

    /** Linearly interpolate between two ARGB colors. t=0 → colorA, t=1 → colorB. */
    private fun blendColor(colorA: Int, colorB: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val a = (Color.alpha(colorA) + f * (Color.alpha(colorB) - Color.alpha(colorA))).toInt()
        val r = (Color.red(colorA) + f * (Color.red(colorB) - Color.red(colorA))).toInt()
        val g = (Color.green(colorA) + f * (Color.green(colorB) - Color.green(colorA))).toInt()
        val b = (Color.blue(colorA) + f * (Color.blue(colorB) - Color.blue(colorA))).toInt()
        return Color.argb(a, r, g, b)
    }
}
