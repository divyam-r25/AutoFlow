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
import android.graphics.PointF
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
 * - Control Panel with Play/Pause, Stop, Add Point, Close
 * - Numbered draggable markers — tap to configure individually
 * - Per-pointer config dialog: action type, delay, duration
 * - Swipe mode: visual end-point marker + live line drawn on screen
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "autoflow_overlay"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.autoflow.ACTION_STOP"

        private const val COLOR_BG         = 0xE8182432.toInt()
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
        private const val COLOR_END_MARKER  = 0xFF60A5FA.toInt() // Lighter blue for end-point

        // Unit multipliers to convert to milliseconds
        private const val UNIT_MS  = 0
        private const val UNIT_S   = 1
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

    // ── Swipe Line Overlay ────────────────────────────────────────────
    private var swipeLineOverlay: SwipeLineOverlay? = null
    private var swipeLineParams: WindowManager.LayoutParams? = null

    // ── Markers ───────────────────────────────────────────────────────
    /**
     * Represents one automation point marker in the overlay.
     *
     * @param tapDuration   how long the tap/swipe gesture is held (ms)
     * @param delayValue    numeric value the user entered (interpreted with delayUnit)
     * @param delayUnit     one of UNIT_MS / UNIT_S / UNIT_MIN
     * @param actionType    CLICK or SWIPE
     * @param swipeEndView  the draggable end-point circle (only for SWIPE)
     * @param swipeEndParams layout params of the end-point marker
     */
    private data class MarkerData(
        val view: View,
        val params: WindowManager.LayoutParams,
        val id: Int,
        var tapDuration: Long = 100L,
        var delayValue: Long = 300L,
        var delayUnit: Int = UNIT_MS,
        var actionType: ActionType = ActionType.CLICK,
        var swipeEndView: View? = null,
        var swipeEndParams: WindowManager.LayoutParams? = null
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
        createSwipeLineOverlay()
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
    // Swipe Line Canvas Overlay
    // ─────────────────────────────────────────────────────────────────

    private fun createSwipeLineOverlay() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getMetrics(metrics)
        swipeLineOverlay = SwipeLineOverlay(this)
        swipeLineParams = WindowManager.LayoutParams(
            metrics.widthPixels, metrics.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        windowManager.addView(swipeLineOverlay, swipeLineParams)
    }

    /** Collect all swipe pairs and redraw the canvas overlay */
    private fun refreshSwipeLines() {
        val half = dpToPx(22).toFloat()
        val endHalf = dpToPx(18).toFloat()
        val pairs = markers
            .filter { it.actionType == ActionType.SWIPE && it.swipeEndParams != null }
            .map { md ->
                SwipeLineOverlay.SwipePair(
                    start = PointF(md.params.x + half, md.params.y + half),
                    end   = PointF(md.swipeEndParams!!.x + endHalf, md.swipeEndParams!!.y + endHalf),
                    id    = md.id
                )
            }
        swipeLineOverlay?.update(pairs)
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
                    windowManager.updateViewLayout(v, md.params)
                    refreshSwipeLines()  // live update the line
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) showPointConfig(md); return true
                }
            }
            return false
        }
    }

    /** Creates a draggable end-point marker for swipe gestures */
    @SuppressLint("ClickableViewAccessibility")
    private fun createSwipeEndMarker(md: MarkerData) {
        if (md.swipeEndView != null) return  // already exists

        val sz = dpToPx(36)
        val endCircle = FrameLayout(this).apply {
            background = createCircle(COLOR_END_MARKER)
            elevation = dpToPx(4).toFloat()
            alpha = 0.92f
        }
        // Hollow inner to distinguish from start
        val inner = View(this).apply {
            background = createCircle(0xFF1E293B.toInt())
        }
        val innerSz = dpToPx(14)
        endCircle.addView(inner, FrameLayout.LayoutParams(innerSz, innerSz, Gravity.CENTER))

        // Position 200px below the start marker initially
        val startX = md.params.x + dpToPx(22) - sz / 2
        val startY = md.params.y + dpToPx(200)

        val ep = overlayParams(sz, sz).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX; y = startY
        }
        md.swipeEndView = endCircle
        md.swipeEndParams = ep

        endCircle.setOnTouchListener(object : View.OnTouchListener {
            private var ix = 0; private var iy = 0
            private var itx = 0f; private var ity = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { ix=ep.x; iy=ep.y; itx=e.rawX; ity=e.rawY; return true }
                    MotionEvent.ACTION_MOVE -> {
                        ep.x = ix + (e.rawX-itx).toInt(); ep.y = iy + (e.rawY-ity).toInt()
                        windowManager.updateViewLayout(v, ep)
                        refreshSwipeLines()
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(endCircle, ep)
        refreshSwipeLines()
    }

    private fun removeSwipeEndMarker(md: MarkerData) {
        md.swipeEndView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        md.swipeEndView = null
        md.swipeEndParams = null
        refreshSwipeLines()
    }

    private fun removeMarker(id: Int) {
        markers.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { i ->
            val md = markers.removeAt(i)
            removeSwipeEndMarker(md)
            try { windowManager.removeView(md.view) } catch (_: Exception) {}
            if (isPanelVisible) { hidePanel(); createPanel() }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Per-Pointer Config Dialog
    // ─────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun showPointConfig(md: MarkerData) {
        dismissDialog()

        var workValue      = md.delayValue
        var workUnit       = md.delayUnit
        var workActionType = md.actionType
        var workDuration   = md.tapDuration

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(0xFF1E293B.toInt(), dpToPx(16))
            elevation = dpToPx(20).toFloat()
            setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(14))
        }

        // ── Header: badge + title ─────────────────────────────────────
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = FrameLayout(this).apply {
            background = createCircle(COLOR_PRIMARY)
            val s = dpToPx(32); minimumWidth = s; minimumHeight = s
        }
        badge.addView(makeText("${md.id}", COLOR_TEXT, 14f, bold = true).also { it.gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(dpToPx(32), dpToPx(32)))
        headerRow.addView(badge)
        headerRow.addView(makeText("  Point ${md.id}", COLOR_TEXT, 15f, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(headerRow, fillW().apply { bottomMargin = dpToPx(16) })

        // ── Divider ───────────────────────────────────────────────────
        root.addView(makeDividerLine(), fillW().apply { bottomMargin = dpToPx(16) })

        // ── Action Type row ───────────────────────────────────────────
        val actionTypeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionTypeRow.addView(makeText("Action Type", COLOR_TEXT_HINT, 13f))
        actionTypeRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val actionTypeBtn = makeText(
            if (workActionType == ActionType.CLICK) "Click  ▾" else "Swipe  ▾",
            COLOR_TEXT, 14f).apply {
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            background = roundRect(COLOR_CARD_INNER, dpToPx(8))
        }
        val swipeHintContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (workActionType == ActionType.SWIPE) View.VISIBLE else View.GONE
        }

        val actionTypePopup = PopupMenu(this, actionTypeBtn)
        actionTypePopup.menu.add(0, 0, 0, "Click")
        actionTypePopup.menu.add(0, 1, 0, "Swipe")
        actionTypePopup.setOnMenuItemClickListener { item ->
            workActionType = if (item.itemId == 0) ActionType.CLICK else ActionType.SWIPE
            actionTypeBtn.text = if (workActionType == ActionType.CLICK) "Click  ▾" else "Swipe  ▾"
            swipeHintContainer.visibility = if (workActionType == ActionType.SWIPE) View.VISIBLE else View.GONE
            true
        }
        actionTypeBtn.setOnClickListener { actionTypePopup.show() }
        actionTypeRow.addView(actionTypeBtn)
        root.addView(actionTypeRow, fillW().apply { bottomMargin = dpToPx(8) })

        // Swipe Hint (shown when Swipe selected)
        val swipeHint = makeText("◉  Drag the blue end-point on screen to set swipe direction & length", 0xFF60A5FA.toInt(), 12f).apply {
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            background = roundRect(0x1A3B82F6, dpToPx(8))
        }
        swipeHintContainer.addView(swipeHint, fillW())
        root.addView(swipeHintContainer, fillW().apply { bottomMargin = dpToPx(12) })

        // ── Divider ───────────────────────────────────────────────────
        root.addView(makeDividerLine(), fillW().apply { bottomMargin = dpToPx(12) })

        // ── Delay row ─────────────────────────────────────────────────
        root.addView(makeText("Delay After Action", COLOR_TEXT_HINT, 12f), fillW().apply { bottomMargin = dpToPx(6) })

        val delayRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val delayInputContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val delayInput = EditText(this).apply {
            setText("$workValue")
            setTextColor(COLOR_TEXT)
            textSize = 20f
            gravity = Gravity.CENTER_HORIZONTAL
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(0, 0, 0, dpToPx(4))
        }
        val delayUnderline = View(this).apply { setBackgroundColor(COLOR_PRIMARY) }
        delayInputContainer.addView(delayInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        delayInputContainer.addView(delayUnderline, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(2)))

        val unitBtn = makeText("${UNIT_LABELS[workUnit]}  ▾", COLOR_TEXT_SEC, 13f).apply {
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            background = roundRect(COLOR_CARD_INNER, dpToPx(8))
        }
        val popup = PopupMenu(this, unitBtn)
        UNIT_LABELS.forEachIndexed { idx, label -> popup.menu.add(0, idx, 0, label) }
        popup.setOnMenuItemClickListener { item ->
            workUnit = item.itemId
            unitBtn.text = "${UNIT_LABELS[workUnit]}  ▾"
            true
        }
        unitBtn.setOnClickListener { popup.show() }

        delayRow.addView(delayInputContainer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpToPx(12) })
        delayRow.addView(unitBtn)
        root.addView(delayRow, fillW().apply { bottomMargin = dpToPx(16) })

        // ── Duration row ──────────────────────────────────────────────
        root.addView(makeDividerLine(), fillW().apply { bottomMargin = dpToPx(12) })

        // Duration label changes based on action type
        val durationLabel = makeText(
            if (workActionType == ActionType.SWIPE) "Swipe Speed (ms)" else "Tap Duration (ms)",
            COLOR_TEXT_HINT, 12f)
        root.addView(durationLabel, fillW().apply { bottomMargin = dpToPx(6) })

        actionTypePopup.setOnMenuItemClickListener { item ->
            workActionType = if (item.itemId == 0) ActionType.CLICK else ActionType.SWIPE
            actionTypeBtn.text = if (workActionType == ActionType.CLICK) "Click  ▾" else "Swipe  ▾"
            swipeHintContainer.visibility = if (workActionType == ActionType.SWIPE) View.VISIBLE else View.GONE
            durationLabel.text = if (workActionType == ActionType.SWIPE) "Swipe Speed (ms)" else "Tap Duration (ms)"
            true
        }

        val durationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val durationInputContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val durationInput = EditText(this).apply {
            setText("$workDuration")
            setTextColor(COLOR_TEXT)
            textSize = 20f
            gravity = Gravity.CENTER_HORIZONTAL
            background = null
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(0, 0, 0, dpToPx(4))
        }
        val durationUnderline = View(this).apply { setBackgroundColor(COLOR_PRIMARY) }
        durationInputContainer.addView(durationInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        durationInputContainer.addView(durationUnderline, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(2)))

        val msLabel = makeText("ms", COLOR_TEXT_SEC, 13f).apply {
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            background = roundRect(COLOR_CARD_INNER, dpToPx(8))
        }

        durationRow.addView(durationInputContainer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpToPx(12) })
        durationRow.addView(msLabel)
        root.addView(durationRow, fillW().apply { bottomMargin = dpToPx(16) })

        // ── Bottom buttons ────────────────────────────────────────────
        root.addView(makeDividerLine(), fillW().apply { bottomMargin = dpToPx(12) })

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }

        val deleteBtn = makeText("DELETE", 0xFFFF4081.toInt(), 13f, bold = true).apply {
            setPadding(dpToPx(8), dpToPx(10), dpToPx(8), dpToPx(10))
            setOnClickListener { removeMarker(md.id); dismissDialog() }
        }
        bottomRow.addView(deleteBtn)
        bottomRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        val cancelBtn = makeText("CANCEL", 0xFFFF4081.toInt(), 13f, bold = true).apply {
            setPadding(dpToPx(8), dpToPx(10), dpToPx(8), dpToPx(10))
            setOnClickListener { dismissDialog() }
        }
        bottomRow.addView(cancelBtn)

        val okBtn = makeText("OK", 0xFFFF4081.toInt(), 13f, bold = true).apply {
            setPadding(dpToPx(8), dpToPx(10), dpToPx(8), dpToPx(10))
            setOnClickListener {
                // Save delay
                md.delayValue = (delayInput.text.toString().toLongOrNull() ?: workValue).coerceAtLeast(1L)
                md.delayUnit  = workUnit

                // Save duration
                md.tapDuration = (durationInput.text.toString().toLongOrNull() ?: workDuration).coerceAtLeast(1L)

                // Save action type — manage end-point marker
                val previousType = md.actionType
                md.actionType = workActionType

                if (workActionType == ActionType.SWIPE) {
                    // Show end-point marker if switching to swipe (or already swipe)
                    createSwipeEndMarker(md)
                    // Update badge text with arrow indicator
                    (md.view as? FrameLayout)?.getChildAt(0)?.let { tv ->
                        (tv as? TextView)?.text = "${md.id}↕"
                    }
                } else {
                    // Remove end-point if switching back to click
                    if (previousType == ActionType.SWIPE) removeSwipeEndMarker(md)
                    (md.view as? FrameLayout)?.getChildAt(0)?.let { tv ->
                        (tv as? TextView)?.text = "${md.id}"
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
        val dp = overlayParams(dpToPx(300), WindowManager.LayoutParams.WRAP_CONTENT, focusable = true).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (md.params.x - dpToPx(140)).coerceIn(dpToPx(8), metrics.widthPixels - dpToPx(308))
            y = (md.params.y + dpToPx(50)).coerceAtMost(metrics.heightPixels - dpToPx(400))
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
        val endHalf = dpToPx(18).toFloat()

        val points = markers.mapIndexed { i, md ->
            val startX = md.params.x + half
            val startY = md.params.y + half

            val (endX, endY) = if (md.actionType == ActionType.SWIPE && md.swipeEndParams != null) {
                Pair(md.swipeEndParams!!.x + endHalf, md.swipeEndParams!!.y + endHalf)
            } else {
                Pair(0f, 0f)
            }

            ClickPoint(
                id = md.id,
                x = startX, y = startY,
                actionType = md.actionType,
                duration = md.tapDuration,
                delayMs = md.delayMs,
                endX = endX, endY = endY,
                order = i
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
            // Also hide end-point markers
            md.swipeEndView?.let { ev ->
                md.swipeEndParams?.flags = md.swipeEndParams!!.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                try { ev.alpha = 0.2f; windowManager.updateViewLayout(ev, md.swipeEndParams) } catch (_: Exception) {}
            }
        }
    }

    private fun showMarkers() {
        markers.forEach { md ->
            md.params.flags = md.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try { md.view.alpha = 1f; windowManager.updateViewLayout(md.view, md.params) } catch (_: Exception) {}
            // Also restore end-point markers
            md.swipeEndView?.let { ev ->
                md.swipeEndParams?.flags = md.swipeEndParams!!.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                try { ev.alpha = 0.92f; windowManager.updateViewLayout(ev, md.swipeEndParams) } catch (_: Exception) {}
            }
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
        swipeLineOverlay?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        swipeLineOverlay = null
        hidePanel(); dismissDialog()
        markers.forEach { md ->
            md.swipeEndView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
            try { windowManager.removeView(md.view) } catch (_: Exception) {}
        }
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
