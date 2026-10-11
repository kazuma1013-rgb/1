package com.kazum.scan3d

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 撮影済みの方向を3重の輪で表示する。内側=低、中央=中、外側=高。 */
class CoverageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var filled: Array<BooleanArray> = Array(Coverage.RINGS) { BooleanArray(Coverage.SECTORS) }
    private var currentRing = 1
    private var currentHeading = 0f

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 0, 0, 0) }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(90, 255, 255, 255)
    }
    private val done = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(80, 220, 120)
    }
    private val activeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(70, 255, 220, 0)
    }
    private val pointer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 210, 0); strokeWidth = 6f; strokeCap = Paint.Cap.ROUND
    }

    fun update(filled: Array<BooleanArray>, ring: Int, relativeHeading: Float) {
        this.filled = filled
        currentRing = ring
        currentHeading = relativeHeading
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy)
        canvas.drawCircle(cx, cy, radius, bg)

        val band = radius / (Coverage.RINGS + 1.2f)
        val sweep = 360f / Coverage.SECTORS
        for (ring in 0 until Coverage.RINGS) {
            val r = band * (ring + 1.1f)
            val stroke = band * 0.8f
            val oval = RectF(cx - r, cy - r, cx + r, cy + r)
            if (ring == currentRing) {
                activeRing.strokeWidth = band
                canvas.drawOval(oval, activeRing)
            }
            empty.strokeWidth = stroke
            done.strokeWidth = stroke
            for (s in 0 until Coverage.SECTORS) {
                // 上（12時）を起点に時計回り
                val start = -90f + s * sweep + 1f
                canvas.drawArc(oval, start, sweep - 2f, false, if (filled[ring][s]) done else empty)
            }
        }

        val a = Math.toRadians((currentHeading - 90f).toDouble())
        val tip = radius * 0.98f
        canvas.drawLine(cx, cy, cx + (tip * cos(a)).toFloat(), cy + (tip * sin(a)).toFloat(), pointer)
    }
}
