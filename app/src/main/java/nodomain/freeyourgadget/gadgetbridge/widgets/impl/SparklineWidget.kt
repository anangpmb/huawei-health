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
package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.ImageViewCompat
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.watchlink.SparklineView
import nodomain.freeyourgadget.gadgetbridge.widgets.GBWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetActions
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import com.google.android.material.color.MaterialColors
import nodomain.freeyourgadget.gadgetbridge.watchlink.WatchLinkFonts

/**
 * WatchLink: a full-width metric row - pastel icon chip, title, current value and a spline of the
 * recent trend. Tapping it opens the matching chart, like the gauge widgets.
 */
abstract class SparklineWidget : GBWidget<SparklineWidget.Data> {
    override val defaultColumns: Int = 2
    override val allowedColumns: List<Int> = listOf(2)

    /** Chart tab opened when the row is tapped, or null if nothing should be opened. */
    protected open val chartTab: String? = null

    /** Colour of the spline and the icon. */
    @get:ColorRes
    protected abstract val lineColor: Int

    /** Pastel colour of the round chip behind the icon. */
    @get:ColorRes
    protected abstract val chipColor: Int

    /** The current value as shown under the title, e.g. "95 bpm". */
    protected abstract fun formatValue(context: Context, value: Double): String

    override fun createView(inflater: LayoutInflater, parent: ViewGroup): View =
        inflater.inflate(R.layout.dashboard_widget_sparkline, parent, false)

    override fun bind(view: View, config: WidgetConfig, data: Data) {
        val context = view.context
        val line = ContextCompat.getColor(context, lineColor)

        view.findViewById<TextView>(R.id.sparkline_label).text = config.title(context, this)
        val value = data.value
        val valueText = if (value != null) formatValue(context, value) else context.getString(R.string.stats_empty_value)
        view.findViewById<TextView>(R.id.sparkline_value).text = styledValue(context, valueText)

        ViewCompat.setBackgroundTintList(
            view.findViewById(R.id.sparkline_icon_chip),
            ColorStateList.valueOf(ContextCompat.getColor(context, chipColor))
        )
        val iconView = view.findViewById<ImageView>(R.id.sparkline_icon)
        iconView.setImageResource(icon)
        ImageViewCompat.setImageTintList(iconView, ColorStateList.valueOf(line))

        view.findViewById<SparklineView>(R.id.sparkline_chart).setData(data.trend, line)
        view.contentDescription = context.getString(label) + ", " + valueText
    }

    override fun onClick(view: View, config: WidgetConfig, timestamp: Int) {
        val tab = chartTab ?: return
        WidgetActions.openChart(view.context, config, this, tab, label, "", timestamp)
    }

    /**
     * "95 bpm" as a big bold number with a small, muted, regular-weight unit after it, the way the
     * metric rows read at a glance.
     */
    private fun styledValue(context: Context, text: String): CharSequence {
        val space = text.indexOf(' ')
        if (space <= 0) return text
        val styled = SpannableString(text)
        val muted = MaterialColors.getColor(
            context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY
        )
        styled.setSpan(RelativeSizeSpan(UNIT_SIZE), space, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        styled.setSpan(ForegroundColorSpan(muted), space, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        styled.setSpan(FaceSpan(WatchLinkFonts.regular(context)), space, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return styled
    }

    /** Sets a typeface on a span of text; TypefaceSpan(Typeface) needs API 28. */
    private class FaceSpan(private val face: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(paint: TextPaint) {
            paint.typeface = face
        }

        override fun updateMeasureState(paint: TextPaint) {
            paint.typeface = face
        }
    }

    private companion object {
        const val UNIT_SIZE = 0.58f
    }

    /** [value] is null when there is nothing to show; [trend] is drawn left to right, oldest first. */
    class Data(val value: Double?, val trend: FloatArray)
}
