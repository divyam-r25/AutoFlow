package com.autoflow.core.overlay

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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.PopupMenu
import android.text.InputType
import com.autoflow.automation.engine.ClickEngine
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
 * Foreground service managing the floating overlay UI.
 *
 * Features:
 * - Draggable floating bubble to toggle the control panel
 * - Control Panel with Play/Pause, Stop, Add Point, Global Settings, Close
 * - Numbered draggable markers — tap to configure individually
 * - Per-pointer config dialog: delay (value + unit picker) + tap duration
 * - Global Settings dialog with Apply-to-All
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "autoflow_overlay"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.autoflow.ACTION_STOP"

        private const val COLOR_BG         = 0xE8182432.toInt()  // dark navy, slightly transparent
        private const val COLOR_CARD        = 0xFF1E293B.toInt()
        private const val COLOR_CARD_INNER  = 0xFF334155.toInt()
        private const val COLOR_PRIMARY     = 0xFF3B82F6.toInt()
        private const val COLOR_SURFACE     = 0xFF1E293B.toInt()
        private const val COLOR_SUCCESS     = 0xFF22C55E.toInt()
        private const val COLOR_WARNING     = 0xFFF59E0B.toInt()
        private const val COLOR_ERROR       = 0xFFEF4444.toInt()
        private const val COLOR_TEXT        = 0xFFFFFFFF.toInt()
        private const val COLOR_TEXT_SEC    = 0xFFCBD5E1.toInt()
        private const val COLOR_TEXT_HINT   = 0xFF94A3B8.toInt()
        private const val COLOR_DIVIDER     = 0xFF334155.toInt()
        private const val COLOR_SELECTED    = 0xFF3B82F6.toInt()

        // Unit multipliers to convert to milliseconds
        private const val UNIT_MS = 0
        private const val UNIT_S  = 1
        private const val UNIT_MIN = 2
        private val UNIT_LABELS = arrayOf("Millisecond(s)", "Second(s)", "Minute(s)")
        private val UNIT_TO_MS  = longArrayOf(1L, 1000L, 60_000L)
    }

    // ── Window Manager ────────────────────────────────────────────────
    private lateinit var windowManager: WindowManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Bubble ────────────────────────────────────────────────────────
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    // ── Control Panel ─────────────────────────────────────────────────
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var isPanelVisible = false
    private var playPauseButton: ImageView? = null

    // ── Config Dialog ─────────────────────────────────────────────────
    private var configDialogView: View? = null

    // ── Markers ───────────────────────────────────────────────────────
    /**
     * Represents one click-point marker in the overlay.
     *
     * @param tapDuration   how long the tap is held (ms)
     * @param delayValue    numeric value the user entered (interpreted with delayUnit)
     * @param delayUnit     one of UNIT_MS / UNIT_S / UNIT_MIN
     */
    private data class MarkerData(
        val view: View,
        val params: WindowManager.LayoutParams,
        val id: Int,
        var tapDuration: Long = 100L,
        var delayValue: Long = 300L,
        var delayUnit: Int = UNIT_MS,
        var actionType: ActionType = ActionType.CLICK,
        var swipeDirection: Int = 1, // Default DOWN
        var swipeLengthDp: Int = 200
    ) {
        /** Effective delay in milliseconds */
        val delayMs: Long get() = delayValue * UNIT_TO_MS[delayUnit]
    }

    private val markers = mutableListOf<MarkerData>()
    private var nextPointId = 1

    // ── Engine ────────────────────────────────────────────────────────
    private val clickEngine = ClickEngine()
    private var isRunning = false

    // ── Global Defaults ───────────────────────────────────────────────
    private var globalTapDuration: Long = 100L
    private var globalDelayValue: Long = 300L
    private var globalDelayUnit: Int = UNIT_MS
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { clickEngine.stop(); stopSelf() }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        clickEngine.stop()
        removeAllOverlays()
        serviceScope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, getString(com.autoflow.R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoFlow").setContentText("Overlay active")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(null, "Stop", pi).build())
            .setOngoing(true).build()
    }

    // ─────────────────────────────────────────────────────────────────
    // Floating Bubble
    // ─────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubble() {
        val sz = dpToPx(56)
        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        bubbleView = FrameLayout(this).apply {
            background = createCircle(COLOR_PRIMARY)
            elevation = dpToPx(8).toFloat()
            val p = (sz - dpToPx(28)) / 2
            setPadding(p, p, p, p)
            addView(icon, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        bubbleParams = overlayParams(sz, sz).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(8); y = dpToPx(200)
        }
        bubbleView?.setOnTouchListener(dragListener(bubbleParams!!) { togglePanel() })
        windowManager.addView(bubbleView, bubbleParams)
    }

    // ─────────────────────────────────────────────────────────────────
    // Control Panel
    // ─────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun createPanel() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COLOR_CARD, dpToPx(16))
            elevation = dpToPx(12).toFloat()
            setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14))
        }

        // Title
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(makeText("⚡ AutoFlow", COLOR_TEXT, 14f, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(header, wrapW().apply { bottomMargin = dpToPx(10) })

        // Buttons
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        playPauseButton = panelBtn(
            if (isRunning) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            COLOR_SUCCESS) { onPlayPauseClicked() }
        row.addView(playPauseButton, btnP())
        row.addView(panelBtn(android.R.drawable.ic_delete, COLOR_ERROR) { onStopClicked() }, btnP())
        row.addView(panelBtn(android.R.drawable.ic_input_add, COLOR_PRIMARY) { addClickPoint() }, btnP())
        row.addView(panelBtn(android.R.drawable.ic_menu_close_clear_cancel, 0xFF475569.toInt()) { stopSelf() }, btnP())
        panel.addView(row)

        val n = markers.size
        val lbl = makeText(
            if (n == 0) "Tap + to add click points" else "$n point${if (n!=1) "s" else ""} · tap to configure",
            COLOR_TEXT_HINT, 11f)
        lbl.gravity = Gravity.CENTER
        panel.addView(lbl, wrapW().apply { topMargin = dpToPx(8) })

        panelView = panel
        panelParams = overlayParams(dpToPx(280), WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleParams?.x ?: 0) + dpToPx(64); y = bubbleParams?.y ?: dpToPx(200)
        }
        windowManager.addView(panelView, panelParams)
        isPanelVisible = true
    }

    private fun togglePanel() { if (isPanelVisible) hidePanel() else createPanel() }

    private fun hidePanel() {
        panelView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        panelView = null; playPauseButton = null; isPanelVisible = false
    }

    // ─────────────────────────────────────────────────────────────────
    // Markers
    // ─────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun addClickPoint() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getMetrics(metrics)

        val id = nextPointId++
        val sz = dpToPx(44)

        val circle = FrameLayout(this).apply {
            background = createCircle(COLOR_PRIMARY); elevation = dpToPx(5).toFloat()
        }
        circle.addView(makeText("$id", COLOR_TEXT, 13f, bold = true).also { it.gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val mp = overlayParams(sz, sz).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels / 2 - sz / 2; y = metrics.heightPixels / 2 - sz / 2
        }
        val md = MarkerData(circle, mp, id, globalTapDuration, globalDelayValue, globalDelayUnit)
        circle.setOnTouchListener(markerTouchListener(md))

        windowManager.addView(circle, mp)
        markers.add(md)
        if (isPanelVisible) { hidePanel(); createPanel() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun markerTouchListener(md: MarkerData) = object : View.OnTouchListener {
        private var ix = 0; private var iy = 0
        private var itx = 0f; private var ity = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    ix = md.params.x; iy = md.params.y
                    itx = e.rawX; ity = e.rawY; moved = false; return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - itx; val dy = e.rawY - ity
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    md.params.x = ix + dx.toInt(); md.params.y = iy + dy.toInt()
                    windowManager.updateViewLayout(v, md.params); return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) showPointConfig(md); return true
                }
            }
            return false
        }
    }

    private fun removeMarker(id: Int) {
        markers.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { i ->
            val md = markers.removeAt(i)
            try { windowManager.removeView(md.view) } catch (_: Exception) {}
            if (isPanelVisible) { hidePanel(); createPanel() }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Per-Pointer Config Dialog  (matches the screenshot UX)
    // ─────────────────────────────────────────────────────────────────
    //
    //  ┌─────────────────────────────────────────┐
    //  │  ① ─────────────────────────────── [✕] │
    //  │  The delay time before performing the   │
    //  │  next action                            │
    //  │  ┌──────────┐  ┌──────────────────────┐│
    //  │  │   300    │  │  Millisecond(s)   ▾  ││  ← tap to expand unit list
    //  │  └──────────┘  └──────────────────────┘│
    //  │  [− −]  [−]          [+]  [+ +]        │   ← value steppers
    //  │ ─────────────────────────────────────── │
    //  │  ⏱ Tap Duration                        │
    //  │  [ − ]  100 ms  [ + ]                  │
    //  │ ─────────────────────────────────────── │
    //  │     [Delete]                   [  OK  ] │
    //  └─────────────────────────────────────────┘

    private fun showPointConfig(md: MarkerData) {
        dismissDialog()

        var workValue = md.delayValue
        var workUnit  = md.delayUnit
        var workActionType = md.actionType
        var workSwipeDirection = md.swipeDirection
        var workSwipeLength = md.swipeLengthDp

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(0xFF2D2D2D.toInt(), dpToPx(12))
            elevation = dpToPx(16).toFloat()
            setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(16))
        }

        // Header Row (Badge containing the ID)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = FrameLayout(this).apply {
            background = createCircle(Color.WHITE)
            val s = dpToPx(28); minimumWidth = s; minimumHeight = s
        }
        badge.addView(makeText("${md.id}", 0xFFF59E0B.toInt(), 13f, bold = true).also { it.gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(dpToPx(28), dpToPx(28)))
        headerRow.addView(badge)
        root.addView(headerRow, fillW().apply { bottomMargin = dpToPx(12) })

        // Description
        val desc = makeText("The delay time before performing the next action", 0xFFB0B0B0.toInt(), 14f).apply {
            lineHeight = dpToPx(20)
        }
        root.addView(desc, fillW().apply { bottomMargin = dpToPx(16) })

        // Action Type Row
        val actionTypeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionTypeRow.addView(makeText("Action Type", 0xFFB0B0B0.toInt(), 14f))
        actionTypeRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        
        val actionTypeBtn = makeText(if (workActionType == ActionType.CLICK) "Click  ▾" else "Swipe  ▾", Color.WHITE, 14f).apply {
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
        }
        val actionTypePopup = PopupMenu(this, actionTypeBtn)
        actionTypePopup.menu.add(0, 0, 0, "Click")
        actionTypePopup.menu.add(0, 1, 0, "Swipe")
        
        val swipeConfigContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (workActionType == ActionType.SWIPE) View.VISIBLE else View.GONE
        }

        actionTypePopup.setOnMenuItemClickListener { item ->
            workActionType = if (item.itemId == 0) ActionType.CLICK else ActionType.SWIPE
            actionTypeBtn.text = if (workActionType == ActionType.CLICK) "Click  ▾" else "Swipe  ▾"
            swipeConfigContainer.visibility = if (workActionType == ActionType.SWIPE) View.VISIBLE else View.GONE
            true
        }
        actionTypeBtn.setOnClickListener { actionTypePopup.show() }
        actionTypeRow.addView(actionTypeBtn)
        root.addView(actionTypeRow, fillW().apply { bottomMargin = dpToPx(12) })

        // Input field + Unit selector row (Delay Label)
        val delayLabelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        delayLabelRow.addView(makeText("Delay Interval", 0xFFB0B0B0.toInt(), 12f))
        root.addView(delayLabelRow, fillW().apply { bottomMargin = dpToPx(6) })

        val valueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }

        // EditText for manual value entry
        val valueInputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val valueInput = EditText(this).apply {
            setText("$workValue")
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER_HORIZONTAL
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(0, 0, 0, dpToPx(6))
        }
        val underline = View(this).apply {
            setBackgroundColor(0xFF888888.toInt())
        }
        valueInputContainer.addView(valueInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        valueInputContainer.addView(underline, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)))

        // Unit selector dropdown button
        val unitBtn = makeText("${UNIT_LABELS[workUnit]}  ▾", Color.WHITE, 14f).apply {
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            gravity = Gravity.CENTER
        }

        // Setup PopupMenu for Unit selection
        val popup = PopupMenu(this, unitBtn)
        UNIT_LABELS.forEachIndexed { idx, label ->
            popup.menu.add(0, idx, 0, label)
        }
        popup.setOnMenuItemClickListener { item ->
            workUnit = item.itemId
            unitBtn.text = "${UNIT_LABELS[workUnit]}  ▾"
            true
        }
        unitBtn.setOnClickListener { popup.show() }

        valueRow.addView(valueInputContainer,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpToPx(16) })
        valueRow.addView(unitBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(valueRow, fillW().apply { bottomMargin = dpToPx(16) })

        // Swipe Config Fields Container
        val SWIPE_DIRECTION_LABELS = arrayOf("Up", "Down", "Left", "Right")

        // 1. Swipe Direction Row
        val swipeDirRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        swipeDirRow.addView(makeText("Swipe Direction", 0xFFB0B0B0.toInt(), 14f))
        swipeDirRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        
        val swipeDirBtn = makeText("${SWIPE_DIRECTION_LABELS[workSwipeDirection]}  ▾", Color.WHITE, 14f).apply {
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
        }
        val swipeDirPopup = PopupMenu(this, swipeDirBtn)
        SWIPE_DIRECTION_LABELS.forEachIndexed { idx, label ->
            swipeDirPopup.menu.add(0, idx, 0, label)
        }
        swipeDirPopup.setOnMenuItemClickListener { item ->
            workSwipeDirection = item.itemId
            swipeDirBtn.text = "${SWIPE_DIRECTION_LABELS[workSwipeDirection]}  ▾"
            true
        }
        swipeDirBtn.setOnClickListener { swipeDirPopup.show() }
        swipeDirRow.addView(swipeDirBtn)
        swipeConfigContainer.addView(swipeDirRow, fillW().apply { bottomMargin = dpToPx(12) })

        // 2. Swipe Length Row
        val swipeLengthRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        swipeLengthRow.addView(makeText("Swipe Length (dp)", 0xFFB0B0B0.toInt(), 14f))
        swipeLengthRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val swipeLenInputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val swipeLenInput = EditText(this).apply {
            setText("$workSwipeLength")
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(0, 0, 0, dpToPx(4))
        }
        val swipeLenUnderline = View(this).apply {
            setBackgroundColor(0xFF888888.toInt())
        }
        swipeLenInputContainer.addView(swipeLenInput, LinearLayout.LayoutParams(dpToPx(70), LinearLayout.LayoutParams.WRAP_CONTENT))
        swipeLenInputContainer.addView(swipeLenUnderline, LinearLayout.LayoutParams(dpToPx(70), dpToPx(1)))
        
        swipeLengthRow.addView(swipeLenInputContainer)
        swipeConfigContainer.addView(swipeLengthRow, fillW().apply { bottomMargin = dpToPx(16) })

        root.addView(swipeConfigContainer, fillW())

        // Bottom buttons row (DELETE | CANCEL | OK)
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }

        // DELETE button (bottom-left)
        val deleteBtn = makeText("DELETE", 0xFFFF4081.toInt(), 14f, bold = true).apply {
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            setOnClickListener {
                removeMarker(md.id)
                dismissDialog()
            }
        }
        bottomRow.addView(deleteBtn)

        // Spacer to push CANCEL and OK to the right
        bottomRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        // CANCEL button (bottom-right)
        val cancelBtn = makeText("CANCEL", 0xFFFF4081.toInt(), 14f, bold = true).apply {
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            setOnClickListener { dismissDialog() }
        }
        bottomRow.addView(cancelBtn)

        // OK button (bottom-right)
        val okBtn = makeText("OK", 0xFFFF4081.toInt(), 14f, bold = true).apply {
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            setOnClickListener {
                val enteredVal = valueInput.text.toString().toLongOrNull() ?: workValue
                workValue = enteredVal.coerceAtLeast(1L)
                md.delayValue = workValue
                md.delayUnit  = workUnit
                
                md.actionType = workActionType
                if (workActionType == ActionType.SWIPE) {
                    md.swipeDirection = workSwipeDirection
                    md.swipeLengthDp = swipeLenInput.text.toString().toIntOrNull() ?: workSwipeLength
                }
                
                // Update marker view text to show Swipe direction arrow
                val textView = (md.view as? FrameLayout)?.getChildAt(0) as? TextView
                if (textView != null) {
                    if (md.actionType == ActionType.SWIPE) {
                        val arrow = when (md.swipeDirection) {
                            0 -> "↑" // UP
                            1 -> "↓" // DOWN
                            2 -> "←" // LEFT
                            3 -> "→" // RIGHT
                            else -> ""
                        }
                        textView.text = "${md.id}$arrow"
                    } else {
                        textView.text = "${md.id}"
                    }
                }
                
                dismissDialog()
            }
        }
        bottomRow.addView(okBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dpToPx(8) })

        root.addView(bottomRow, fillW())

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getMetrics(metrics)

        configDialogView = root
        val dp = overlayParams(dpToPx(290), WindowManager.LayoutParams.WRAP_CONTENT, focusable = true).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (md.params.x - dpToPx(130)).coerceIn(dpToPx(8), metrics.widthPixels - dpToPx(300))
            y = (md.params.y + dpToPx(50)).coerceAtMost(metrics.heightPixels - dpToPx(350))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        windowManager.addView(configDialogView, dp)
    }



    private fun dismissDialog() {
        configDialogView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        configDialogView = null
    }

    // ─────────────────────────────────────────────────────────────────
    // Engine Controls
    // ─────────────────────────────────────────────────────────────────

    private fun onPlayPauseClicked() {
        when (clickEngine.state.value) {
            EngineState.IDLE, EngineState.COMPLETED -> startAutomation()
            EngineState.RUNNING  -> clickEngine.pause()
            EngineState.PAUSED   -> clickEngine.resume()
        }
    }

    private fun onStopClicked() {
        clickEngine.stop(); showMarkers(); isRunning = false; updatePlayPauseIcon()
    }

    private fun startAutomation() {
        if (markers.isEmpty()) return
        val half = dpToPx(22).toFloat()
        val points = markers.mapIndexed { i, md ->
            val startX = md.params.x + half
            val startY = md.params.y + half
            val (endX, endY) = if (md.actionType == ActionType.SWIPE) {
                val lengthPx = dpToPx(md.swipeLengthDp).toFloat()
                when (md.swipeDirection) {
                    0 -> Pair(startX, startY - lengthPx) // UP
                    1 -> Pair(startX, startY + lengthPx) // DOWN
                    2 -> Pair(startX - lengthPx, startY) // LEFT
                    3 -> Pair(startX + lengthPx, startY) // RIGHT
                    else -> Pair(startX, startY)
                }
            } else {
                Pair(0f, 0f)
            }
            ClickPoint(
                id = md.id, x = startX, y = startY,
                actionType = md.actionType,
                duration = if (md.actionType == ActionType.SWIPE) 300L else md.tapDuration,
                delayMs = md.delayMs, endX = endX, endY = endY, order = i
            )
        }
        val config = AutomationConfig(
            executionMode = globalMode, isInfinite = globalIsInfinite,
            clickIntervalMs = globalDelayValue * UNIT_TO_MS[globalDelayUnit]
        )
        hidePanel(); hideMarkers(); isRunning = true
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
                EngineState.COMPLETED -> { isRunning = false; showMarkers(); updatePlayPauseIcon() }
                else -> updatePlayPauseIcon()
            }
        }.launchIn(serviceScope)
    }

    private fun updatePlayPauseIcon() {
        playPauseButton?.setImageResource(
            if (clickEngine.state.value == EngineState.RUNNING)
                android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────────────

    private fun removeAllOverlays() {
        bubbleView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        hidePanel(); dismissDialog()
        markers.forEach { md -> try { windowManager.removeView(md.view) } catch (_: Exception) {} }
        markers.clear()
    }

    // ─────────────────────────────────────────────────────────────────
    // View / Layout Helpers
    // ─────────────────────────────────────────────────────────────────

    private fun makeText(text: String, color: Int, size: Float, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text; setTextColor(color); textSize = size
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun panelBtn(iconRes: Int, bg: Int, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(iconRes); setColorFilter(Color.WHITE)
            background = createCircle(bg); scaleType = ImageView.ScaleType.CENTER_INSIDE
            val p = dpToPx(10); setPadding(p, p, p, p)
            elevation = dpToPx(2).toFloat(); setOnClickListener { onClick() }
        }

    private fun makeDividerLine(vertical: Boolean = false): View = View(this).apply {
        setBackgroundColor(COLOR_DIVIDER)
        layoutParams = if (vertical)
            LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT)
        else
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun fillW() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun wrapW() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun btnP(dp: Int = 42) = LinearLayout.LayoutParams(dpToPx(dp), dpToPx(dp)).apply {
        marginStart = dpToPx(4); marginEnd = dpToPx(4)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun dragListener(params: WindowManager.LayoutParams, onClick: () -> Unit) =
        object : View.OnTouchListener {
            private var ix = 0; private var iy = 0
            private var itx = 0f; private var ity = 0f

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { ix=params.x; iy=params.y; itx=e.rawX; ity=e.rawY; return true }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = ix + (e.rawX-itx).toInt(); params.y = iy + (e.rawY-ity).toInt()
                        windowManager.updateViewLayout(v, params); return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (abs(e.rawX-itx)<10 && abs(e.rawY-ity)<10) onClick(); return true
                    }
                }
                return false
            }
        }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    private fun overlayParams(w: Int, h: Int, focusable: Boolean = false) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        if (focusable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT)

    private fun createCircle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun roundRect(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = radius.toFloat(); setColor(color)
    }
}
