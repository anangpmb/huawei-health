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
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.DailyTotals
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import org.slf4j.LoggerFactory
import java.text.NumberFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TreeMap

/**
 * Heart rate of the selected day, averaged per half hour; the value is the latest measurement.
 */
object HeartRateTrendWidget : SparklineWidget() {
    private val LOG = LoggerFactory.getLogger(HeartRateTrendWidget::class.java)
    private const val BUCKET_SECONDS = 30 * 60

    override val id = "watchlink_hr_trend"
    override val label = R.string.menuitem_hr
    override val name = R.string.widget_hr_trend
    override val icon = R.drawable.ic_heartrate
    override val chartTab = "heartrate"
    override val lineColor = R.color.watchlink_line_heart
    override val chipColor = R.color.watchlink_chip_blue

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsHeartRateMeasurement(device)

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): SparklineWidget.Data {
        // bucket index -> [sum of bpm, sample count], in time order
        val buckets = TreeMap<Int, IntArray>()
        var latest = 0
        var latestTimestamp = 0
        try {
            val utils = HeartRateUtils.getInstance()
            for (device in scope.devices) {
                for (sample in scope.allSamples(device)) {
                    val heartRate = sample.heartRate
                    if (!utils.isValidHeartRateValue(heartRate)) continue
                    val bucket = (sample.timestamp - scope.query.timeFrom) / BUCKET_SECONDS
                    val sum = buckets.getOrPut(bucket) { IntArray(2) }
                    sum[0] += heartRate
                    sum[1]++
                    if (sample.timestamp > latestTimestamp) {
                        latestTimestamp = sample.timestamp
                        latest = heartRate
                    }
                }
            }
        } catch (e: Exception) {
            LOG.error("Could not load heart rate trend", e)
        }
        val trend = buckets.values.map { it[0].toFloat() / it[1] }.toFloatArray()
        return SparklineWidget.Data(if (latest > 0) latest.toDouble() else null, trend)
    }

    override fun formatValue(context: Context, value: Double): String =
        context.getString(R.string.widget_trend_value_bpm, value.toInt())
}

/**
 * Steps of the selected day, accumulated hour by hour; the value is the day's total.
 */
object StepsTrendWidget : SparklineWidget() {
    private val LOG = LoggerFactory.getLogger(StepsTrendWidget::class.java)
    private const val HOURS = 24

    override val id = "watchlink_steps_trend"
    override val label = R.string.steps
    override val name = R.string.widget_steps_trend
    override val icon = R.drawable.ic_steps
    override val chartTab = "stepsweek"
    override val lineColor = R.color.watchlink_line_steps
    override val chipColor = R.color.watchlink_chip_sage

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsStepCounter(device)

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): SparklineWidget.Data {
        val perHour = IntArray(HOURS)
        var lastHour = -1
        var total = 0
        try {
            for (device in scope.devices.filter { it.deviceCoordinator.supportsStepCounter(it) }) {
                for (sample in scope.allSamples(device)) {
                    val steps = sample.steps
                    if (steps <= 0) continue
                    // coerceIn keeps the 25th hour of a DST change on the last slot
                    val hour = ((sample.timestamp - scope.query.timeFrom) / 3600).coerceIn(0, HOURS - 1)
                    perHour[hour] += steps
                    if (hour > lastHour) lastHour = hour
                }
            }
            // The same total the steps gauge shows
            total = scope.stepsTotal()
        } catch (e: Exception) {
            LOG.error("Could not load steps trend", e)
        }

        if (lastHour < 0) {
            return SparklineWidget.Data(if (total > 0) total.toDouble() else null, FloatArray(0))
        }
        val trend = FloatArray(lastHour + 1)
        var running = 0f
        for (hour in 0..lastHour) {
            running += perHour[hour]
            trend[hour] = running
        }
        return SparklineWidget.Data(total.toDouble(), trend)
    }

    override fun formatValue(context: Context, value: Double): String =
        context.getString(R.string.widget_trend_value_steps, NumberFormat.getIntegerInstance().format(value.toLong()))
}

/**
 * Sleep duration of the last seven nights up to the selected day; the value is the latest night.
 */
object SleepTrendWidget : SparklineWidget() {
    private val LOG = LoggerFactory.getLogger(SleepTrendWidget::class.java)
    private const val NIGHTS = 7

    override val id = "watchlink_sleep_trend"
    override val label = R.string.menuitem_sleep
    override val name = R.string.widget_sleep_trend
    override val icon = R.drawable.ic_activity_sleep
    override val chartTab = "sleep"
    override val lineColor = R.color.watchlink_line_sleep
    override val chipColor = R.color.watchlink_chip_yellow

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsSleepMeasurement(device)

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): SparklineWidget.Data {
        val devices = scope.devices.filter { it.deviceCoordinator.supportsSleepMeasurement(it) }
        val minutesPerNight = try {
            scope.db { db ->
                FloatArray(NIGHTS) { index ->
                    val day = GregorianCalendar.getInstance()
                    day.timeInMillis = scope.query.timeTo * 1000L
                    day.add(Calendar.DAY_OF_YEAR, index - (NIGHTS - 1))
                    // Averaged over devices like the sleep gauge, ignoring devices without data
                    val minutes = devices
                        .map { DailyTotals.getDailyTotalsForDevice(it, day, db).sleep }
                        .filter { it > 0 }
                    if (minutes.isEmpty()) 0f else minutes.average().toFloat()
                }
            }
        } catch (e: Exception) {
            LOG.error("Could not load sleep trend", e)
            FloatArray(0)
        }

        val selectedNight = minutesPerNight.lastOrNull() ?: 0f
        // Nights without data would read as zero hours of sleep, so they are left out
        val trend = minutesPerNight.filter { it > 0f }.toFloatArray()
        return SparklineWidget.Data(if (selectedNight > 0f) selectedNight / 60.0 else null, trend)
    }

    override fun formatValue(context: Context, value: Double): String =
        context.getString(R.string.widget_trend_value_hours, String.format(Locale.getDefault(), "%.1f", value))
}
