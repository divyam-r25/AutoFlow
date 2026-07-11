package com.autoflow.core.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.view.View

/**
 * Full-screen transparent overlay that draws animated lines between
 * swipe start and end markers. Not touchable — sits below all markers.
 */
class SwipeLineOverlay(context: Context) : View(context) {

    data class SwipePair(val start: PointF, val end: PointF, val id: Int)

    private val pairs = mutableListOf<SwipePair>()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3B82F6.toInt()  // Blue
        strokeWidth = 14f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x553B82F6.toInt()  // Translucent blue glow
        strokeWidth = 28f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3B82F6.toInt()
        style = Paint.Style.FILL
    }

    fun update(newPairs: List<SwipePair>) {
        pairs.clear()
        pairs.addAll(newPairs)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (pair in pairs) {
            val sx = pair.start.x
            val sy = pair.start.y
            val ex = pair.end.x
            val ey = pair.end.y

            // Glow behind line
            canvas.drawLine(sx, sy, ex, ey, glowPaint)
            // Main line
            canvas.drawLine(sx, sy, ex, ey, linePaint)
            // End dot
            canvas.drawCircle(ex, ey, 18f, dotPaint)
        }
    }
}
