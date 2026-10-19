/*  Copyright (C) 2026 WatchLink contributors

    This file is part of WatchLink, a modified version of Gadgetbridge.

    WatchLink is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    WatchLink is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.watchlink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import nodomain.freeyourgadget.gadgetbridge.R
import kotlin.math.abs
import kotlin.math.min

/**
 * A compact spline chart: a curve that is flat at each value (so it never overshoots the data),
 * a gradient fading towards the bottom, and a dot marking the latest value. Purely decorative
 * trend; the exact numbers live in the full charts.
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val inset = DOT_RING_RADIUS_DP * density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = LINE_WIDTH_DP * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MaterialColors.getColor(this@SparklineView, R.attr.stat_tile_bg, Color.WHITE)
    }
    private val linePath = Path()
    private val fillPath = Path()

    private var values = FloatArray(0)

    @ColorInt
    private var lineColor = Color.GRAY

    enum class Style {
        /** A curve that is flat at each value. */
        SMOOTH,

        /** Flat runs joined by vertical steps with rounded corners, for a calm, blocky trend. */
        ROUNDED_STEP,
    }

    var style: Style = Style.ROUNDED_STEP
        set(value) {
            field = value
            invalidate()
        }

    fun setData(values: FloatArray, @ColorInt color: Int) {
        this.values = if (values.size > MAX_STEPS) resample(values, MAX_STEPS) else values
        lineColor = color
        linePaint.color = color
        dotPaint.color = color
        fillPaint.shader = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fillPaint.shader = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val count = values.size
        if (count == 0) {
            return
        }

        val left = paddingLeft + inset
        val right = width - paddingRight - inset
        val top = paddingTop + inset
        val bottom = height - paddingBottom - inset
        if (right <= left || bottom <= top) {
            return
        }

        var low = values[0]
        var high = values[0]
        for (value in values) {
            if (value < low) low = value
            if (value > high) high = value
        }
        val range = high - low

        fun xAt(index: Int) = if (count == 1) right else left + (right - left) * index / (count - 1)
        // A flat series sits in the middle rather than on the floor.
        fun yAt(value: Float) = if (range <= 0f) (top + bottom) / 2f else bottom - (bottom - top) * (value - low) / range

        linePath.reset()
        var previousX = xAt(0)
        var previousY = yAt(values[0])
        linePath.moveTo(previousX, previousY)
        val cornerRadius = CORNER_RADIUS_DP * density
        for (i in 1 until count) {
            val x = xAt(i)
            val y = yAt(values[i])
            val midX = (previousX + x) / 2f
            if (style == Style.SMOOTH) {
                linePath.cubicTo(midX, previousY, midX, y, x, y)
            } else {
                val dy = y - previousY
                if (abs(dy) < 0.5f) {
                    linePath.lineTo(x, y)
                } else {
                    // Run flat to the middle, turn down/up with a rounded corner, run flat again
                    val r = min(cornerRadius, min(abs(dy) / 2f, (x - previousX) / 2f))
                    val sign = if (dy > 0f) 1f else -1f
                    linePath.lineTo(midX - r, previousY)
                    linePath.quadTo(midX, previousY, midX, previousY + sign * r)
                    linePath.lineTo(midX, y - sign * r)
                    linePath.quadTo(midX, y, midX + r, y)
                    linePath.lineTo(x, y)
                }
            }
            previousX = x
            previousY = y
        }

        if (count > 1) {
            if (fillPaint.shader == null) {
                fillPaint.shader = LinearGradient(
                    0f, top, 0f, height.toFloat(),
                    ColorUtils.setAlphaComponent(lineColor, FILL_TOP_ALPHA),
                    ColorUtils.setAlphaComponent(lineColor, 0),
                    Shader.TileMode.CLAMP,
                )
            }
            fillPath.reset()
            fillPath.addPath(linePath)
            fillPath.lineTo(previousX, height.toFloat())
            fillPath.lineTo(xAt(0), height.toFloat())
            fillPath.close()
            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(linePath, linePaint)
        }

        canvas.drawCircle(previousX, previousY, DOT_RING_RADIUS_DP * density, dotRingPaint)
        canvas.drawCircle(previousX, previousY, DOT_RADIUS_DP * density, dotPaint)
    }

    private companion object {
        /** More steps than this read as noise in a row this small, so longer series are averaged down. */
        const val MAX_STEPS = 10
        const val CORNER_RADIUS_DP = 6f

        /** Averages [values] into [bins] equal slices, keeping their order. */
        fun resample(values: FloatArray, bins: Int): FloatArray = FloatArray(bins) { bin ->
            val from = bin * values.size / bins
            val to = maxOf(from + 1, (bin + 1) * values.size / bins)
            var sum = 0f
            for (i in from until to) sum += values[i]
            sum / (to - from)
        }

        const val LINE_WIDTH_DP = 2.5f
        const val DOT_RADIUS_DP = 3.5f
        const val DOT_RING_RADIUS_DP = 5.5f
        const val FILL_TOP_ALPHA = 0x55
    }
}
