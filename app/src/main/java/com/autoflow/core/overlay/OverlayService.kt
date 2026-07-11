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
        var delayUnit: Int = UNIT_MS
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
        row.addView(panelBtn(android.R.drawable.ic_menu_preferences, COLOR_WARNING) { showGlobalSettings() }, btnP())
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

        // Working copies so we don't mutate until OK
        var workValue = md.delayValue
        var workUnit  = md.delayUnit
        var workDuration = md.tapDuration
        var unitDropdownOpen = false

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COLOR_CARD, dpToPx(18))
            elevation = dpToPx(20).toFloat()
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(14))
        }

        // ── Header row (badge + close) ──────────────────────────────
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        // Number badge (orange/amber for the active point)
        val badge = FrameLayout(this).apply {
            background = createCircle(COLOR_WARNING)
            val s = dpToPx(28); minimumWidth = s; minimumHeight = s
        }
        badge.addView(makeText("${md.id}", Color.WHITE, 11f, bold = true).also { it.gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(dpToPx(28), dpToPx(28)))
        headerRow.addView(badge, LinearLayout.LayoutParams(dpToPx(28), dpToPx(28)).apply { marginEnd = dpToPx(8) })
        headerRow.addView(makeDividerLine(vertical = false),
            LinearLayout.LayoutParams(0, 2, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        val closeBtn = makeText("  ✕", COLOR_TEXT_SEC, 14f).apply {
            setOnClickListener { dismissDialog() }
        }
        headerRow.addView(closeBtn)
        root.addView(headerRow, fillW().apply { bottomMargin = dpToPx(12) })

        // ── Delay description ─────────────────────────────────────────
        val desc = makeText(
            "The delay time before performing the next action",
            COLOR_TEXT_SEC, 12f
        ).apply { lineHeight = dpToPx(18) }
        root.addView(desc, fillW().apply { bottomMargin = dpToPx(12) })

        // ── Value field + Unit selector row ───────────────────────────
        val valueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }

        // Value display input (manually editable)
        val valueInput = EditText(this).apply {
            setText("$workValue")
            setTextColor(COLOR_TEXT)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundRect(COLOR_CARD_INNER, dpToPx(8))
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            minWidth = dpToPx(80)
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        // Unit selector button (shows current unit + expand arrow)
        val unitBtn: TextView = makeText("${UNIT_LABELS[workUnit]}  ▾", COLOR_TEXT, 12f).apply {
            background = roundRect(COLOR_CARD_INNER, dpToPx(8))
            setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
        }

        // Inline unit dropdown list (hidden initially)
        val unitDropdown = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(0xFF253347.toInt(), dpToPx(8))
            visibility = View.GONE
        }

        // Build unit option rows
        fun buildUnitOptions(onSelect: (Int) -> Unit) {
            unitDropdown.removeAllViews()
            UNIT_LABELS.forEachIndexed { idx, label ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
                    background = if (idx == workUnit) roundRect(COLOR_PRIMARY, dpToPx(6)) else null
                    setOnClickListener { onSelect(idx) }
                }
                row.addView(makeText(label,
                    if (idx == workUnit) Color.WHITE else COLOR_TEXT_SEC, 12f),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (idx == workUnit) {
                    row.addView(makeText("✓", COLOR_TEXT, 12f, bold = true).apply {
                        gravity = Gravity.END
                    })
                }
                unitDropdown.addView(row)
            }
        }

        unitBtn.setOnClickListener {
            unitDropdownOpen = !unitDropdownOpen
            if (unitDropdownOpen) {
                buildUnitOptions { selectedIdx ->
                    workUnit = selectedIdx
                    unitBtn.text = "${UNIT_LABELS[workUnit]}  ▾"
                    unitDropdownOpen = false
                    unitDropdown.visibility = View.GONE
                    buildUnitOptions {}  // refresh checkmark
                }
                unitDropdown.visibility = View.VISIBLE
            } else {
                unitDropdown.visibility = View.GONE
            }
        }

        valueRow.addView(valueInput,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpToPx(8) })
        valueRow.addView(unitBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(valueRow, fillW().apply { bottomMargin = dpToPx(8) })
        root.addView(unitDropdown, fillW().apply { bottomMargin = dpToPx(8) })

        // ── Stepper row for delay value ──────────────────────────────
        val stepRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        val stepSizes = listOf(50L to "−−", 1L to "−", 1L to "+", 50L to "++")
        stepSizes.forEachIndexed { i, (step, label) ->
            val isInc = i >= 2
            val btn = makeText(" $label ", COLOR_TEXT, 13f, bold = true).apply {
                background = roundRect(COLOR_CARD_INNER, dpToPx(8))
                setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
                setOnClickListener {
                    val currentVal = valueInput.text.toString().toLongOrNull() ?: workValue
                    workValue = if (isInc) (currentVal + step).coerceAtMost(99999L)
                                else        (currentVal - step).coerceAtLeast(1L)
                    valueInput.setText("$workValue")
                    valueInput.setSelection(valueInput.text.length)
                }
            }
            stepRow.addView(btn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dpToPx(4); marginEnd = dpToPx(4) })
        }
        root.addView(stepRow, fillW().apply { bottomMargin = dpToPx(14) })

        // ── Divider ────────────────────────────────────────────────────
        root.addView(makeDividerLine(), fillW().apply { bottomMargin = dpToPx(12) })

        // ── Tap Duration section ───────────────────────────────────────
        root.addView(makeText("⏱  Tap Duration", COLOR_TEXT_SEC, 11f, bold = true),
            fillW().apply { bottomMargin = dpToPx(8) })

        val durDisplay = makeText("${workDuration} ms", COLOR_TEXT, 14f, bold = true).apply {
            gravity = Gravity.CENTER
            background = roundRect(COLOR_CARD_INNER, dpToPx(8))
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
        }
        fun refreshDur() { durDisplay.text = "${workDuration} ms" }

        val durRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val durMinus = makeText("  −  ", COLOR_TEXT, 14f, bold = true).apply {
            background = roundRect(COLOR_CARD_INNER, dpToPx(8))
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            setOnClickListener { workDuration = (workDuration - 10).coerceAtLeast(10L); refreshDur() }
        }
        val durPlus = makeText("  +  ", COLOR_TEXT, 14f, bold = true).apply {
            background = roundRect(COLOR_CARD_INNER, dpToPx(8))
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            setOnClickListener { workDuration = (workDuration + 10).coerceAtMost(2000L); refreshDur() }
        }
        durRow.addView(durMinus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpToPx(6) })
        durRow.addView(durDisplay, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dpToPx(6) })
        durRow.addView(durPlus)
        root.addView(durRow, fillW().apply { bottomMargin = dpToPx(14) })

        // ── Divider ────────────────────────────────────────────────────
        root.addView(makeDividerLine(), fillW().apply { bottomMargin = dpToPx(12) })

        // ── Bottom buttons (Delete | OK) ───────────────────────────────
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val deleteBtn = makeText("✕  Delete", COLOR_ERROR, 12f, bold = true).apply {
            background = roundRect(0xFF3B1F22.toInt(), dpToPx(8))
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            setOnClickListener { removeMarker(md.id); dismissDialog() }
        }
        val okBtn = makeText("   OK   ", Color.WHITE, 13f, bold = true).apply {
            background = roundRect(COLOR_PRIMARY, dpToPx(8))
            setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10))
            setOnClickListener {
                val enteredVal = valueInput.text.toString().toLongOrNull() ?: workValue
                workValue = enteredVal.coerceIn(1L, 99999L)
                // Commit to the marker
                md.delayValue = workValue
                md.delayUnit  = workUnit
                md.tapDuration = workDuration
                dismissDialog()
            }
        }
        bottomRow.addView(deleteBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpToPx(8) })
        // Spacer
        bottomRow.addView(View(this), LinearLayout.LayoutParams(0,1,1f))
        bottomRow.addView(okBtn)
        root.addView(bottomRow, fillW())

        // ── Position and show ─────────────────────────────────────────
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

    // ─────────────────────────────────────────────────────────────────
    // Global Settings Dialog
    // ─────────────────────────────────────────────────────────────────

    private fun showGlobalSettings() {
        dismissDialog()
        if (isPanelVisible) hidePanel()

        var workValue = globalDelayValue
        var workUnit  = globalDelayUnit
        var workDur   = globalTapDuration
        var dropOpen  = false

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COLOR_CARD, dpToPx(18))
            elevation = dpToPx(20).toFloat()
            setPadding(dpToPx(18), dpToPx(18), dpToPx(18), dpToPx(16))
        }

        root.addView(makeText("⚙  Global Settings", COLOR_TEXT, 16f, bold = true).apply {
            gravity = Gravity.CENTER
        }, fillW().apply { bottomMargin = dpToPx(14) })
        root.addView(makeDividerLine(), fillW().apply { bottomMargin = dpToPx(14) })

        // Delay section
        root.addView(makeText("Default delay between taps", COLOR_TEXT_SEC, 11f, bold = true),
            fillW().apply { bottomMargin = dpToPx(10) })

        val valueInput = EditText(this).apply {
            setText("$workValue")
            setTextColor(COLOR_TEXT)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; background = roundRect(COLOR_CARD_INNER, dpToPx(8))
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            minWidth = dpToPx(80)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val unitBtn = makeText("${UNIT_LABELS[workUnit]}  ▾", COLOR_TEXT, 12f).apply {
            background = roundRect(COLOR_CARD_INNER, dpToPx(8))
            setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
        }
        val unitDropdown = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(0xFF253347.toInt(), dpToPx(8))
            visibility = View.GONE
        }

        fun buildOptions() {
            unitDropdown.removeAllViews()
            UNIT_LABELS.forEachIndexed { idx, label ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
                    background = if (idx == workUnit) roundRect(COLOR_PRIMARY, dpToPx(6)) else null
                    setOnClickListener {
                        workUnit = idx; unitBtn.text = "${UNIT_LABELS[idx]}  ▾"
                        dropOpen = false; unitDropdown.visibility = View.GONE; buildOptions()
                    }
                }
                row.addView(makeText(label, if (idx == workUnit) Color.WHITE else COLOR_TEXT_SEC, 12f),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (idx == workUnit) row.addView(makeText("✓", COLOR_TEXT, 12f, bold = true))
                unitDropdown.addView(row)
            }
        }
        buildOptions()
        unitBtn.setOnClickListener {
            dropOpen = !dropOpen
            unitDropdown.visibility = if (dropOpen) View.VISIBLE else View.GONE
            if (dropOpen) buildOptions()
        }

        val vr = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        vr.addView(valueInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dpToPx(8) })
        vr.addView(unitBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(vr, fillW().apply { bottomMargin = dpToPx(8) })
        root.addView(unitDropdown, fillW().apply { bottomMargin = dpToPx(8) })

        val sr = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        listOf(50L to "−−", 1L to "−", 1L to "+", 50L to "++").forEachIndexed { i, (step, lbl) ->
            val inc = i >= 2
            sr.addView(makeText(" $lbl ", COLOR_TEXT, 13f, bold = true).apply {
                background = roundRect(COLOR_CARD_INNER, dpToPx(8))
                setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
                setOnClickListener {
                    val currentVal = valueInput.text.toString().toLongOrNull() ?: workValue
                    workValue = if (inc) (currentVal + step).coerceAtMost(99999L)
                                else     (currentVal - step).coerceAtLeast(1L)
                    valueInput.setText("$workValue")
                    valueInput.setSelection(valueInput.text.length)
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dpToPx(4); marginEnd = dpToPx(4) })
        }
        root.addView(sr, fillW().apply { bottomMargin = dpToPx(14) })
        root.addView(makeDividerLine(), fillW().apply { bottomMargin = dpToPx(12) })

        // Tap duration section
        root.addView(makeText("⏱  Default tap duration", COLOR_TEXT_SEC, 11f, bold = true),
            fillW().apply { bottomMargin = dpToPx(8) })
        val durDisplay = makeText("${workDur} ms", COLOR_TEXT, 14f, bold = true).apply {
            gravity = Gravity.CENTER; background = roundRect(COLOR_CARD_INNER, dpToPx(8))
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
        }
        fun refreshDur() { durDisplay.text = "${workDur} ms" }
        val dr = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        makeText("  −  ", COLOR_TEXT, 14f, bold = true).apply {
            background = roundRect(COLOR_CARD_INNER, dpToPx(8))
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            setOnClickListener { workDur = (workDur - 10).coerceAtLeast(10L); refreshDur() }
        }.also { dr.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpToPx(6) }) }
        dr.addView(durDisplay, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dpToPx(6) })
        makeText("  +  ", COLOR_TEXT, 14f, bold = true).apply {
            background = roundRect(COLOR_CARD_INNER, dpToPx(8))
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            setOnClickListener { workDur = (workDur + 10).coerceAtMost(2000L); refreshDur() }
        }.also { dr.addView(it) }
        root.addView(dr, fillW().apply { bottomMargin = dpToPx(16) })
        root.addView(makeDividerLine(), fillW().apply { bottomMargin = dpToPx(12) })

        // Apply to all
        val applyBtn = makeText("↻  Apply to All Pointers", Color.WHITE, 13f, bold = true).apply {
            gravity = Gravity.CENTER
            background = roundRect(COLOR_PRIMARY, dpToPx(10))
            setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12))
            setOnClickListener {
                val enteredVal = valueInput.text.toString().toLongOrNull() ?: workValue
                workValue = enteredVal.coerceIn(1L, 99999L)
                globalDelayValue = workValue; globalDelayUnit = workUnit; globalTapDuration = workDur
                markers.forEach { m ->
                    m.tapDuration = workDur; m.delayValue = workValue; m.delayUnit = workUnit
                }
                dismissDialog()
            }
        }
        root.addView(applyBtn, fillW().apply { bottomMargin = dpToPx(8) })

        val doneBtn = makeText("   ✓  Done   ", Color.WHITE, 13f, bold = true).apply {
            gravity = Gravity.CENTER; background = roundRect(COLOR_SUCCESS, dpToPx(10))
            setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12))
            setOnClickListener {
                val enteredVal = valueInput.text.toString().toLongOrNull() ?: workValue
                workValue = enteredVal.coerceIn(1L, 99999L)
                globalDelayValue = workValue; globalDelayUnit = workUnit; globalTapDuration = workDur
                dismissDialog()
            }
        }
        root.addView(doneBtn, fillW())

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getMetrics(metrics)

        configDialogView = root
        windowManager.addView(root, overlayParams(dpToPx(290), WindowManager.LayoutParams.WRAP_CONTENT, focusable = true).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels / 2 - dpToPx(145); y = dpToPx(100)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        })
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
            ClickPoint(
                id = md.id, x = md.params.x + half, y = md.params.y + half,
                actionType = ActionType.CLICK, duration = md.tapDuration,
                delayMs = md.delayMs, order = i
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
        if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT)

    private fun createCircle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun roundRect(color: Int, radius: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = radius.toFloat(); setColor(color)
    }
}
