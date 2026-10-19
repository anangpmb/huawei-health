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

    fun setData(values: FloatArray, @ColorInt color: Int) {
        this.values = values
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

        var min = values[0]
        var max = values[0]
        for (value in values) {
            if (value < min) min = value
            if (value > max) max = value
        }
        val range = max - min

        fun xAt(index: Int) = if (count == 1) right else left + (right - left) * index / (count - 1)
        // A flat series sits in the middle rather than on the floor.
        fun yAt(value: Float) = if (range <= 0f) (top + bottom) / 2f else bottom - (bottom - top) * (value - min) / range

        linePath.reset()
        var previousX = xAt(0)
        var previousY = yAt(values[0])
        linePath.moveTo(previousX, previousY)
        for (i in 1 until count) {
            val x = xAt(i)
            val y = yAt(values[i])
            val midX = (previousX + x) / 2f
            linePath.cubicTo(midX, previousY, midX, y, x, y)
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
        const val LINE_WIDTH_DP = 2.5f
        const val DOT_RADIUS_DP = 3.5f
        const val DOT_RING_RADIUS_DP = 5.5f
        const val FILL_TOP_ALPHA = 0x55
    }
}
