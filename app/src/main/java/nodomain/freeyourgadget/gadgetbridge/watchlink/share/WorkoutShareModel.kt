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
package nodomain.freeyourgadget.gadgetbridge.watchlink.share

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutValueFormatter
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import java.text.DateFormat
import java.text.DecimalFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** One route point in world pixels (Web Mercator at [WorkoutShareModel.WORLD_ZOOM], 256 px tiles). */
class RoutePoint(
    val x: Double,
    val y: Double,
    /** 0..1 position in the workout's time, used to move the head at the real tempo. */
    val timeFraction: Float,
    /** 0..1 share of the drawn distance up to this point. */
    val distanceFraction: Float,
    /** 0 = slowest, 1 = fastest of this workout (10th..90th percentile). */
    val pace: Float,
)

enum class ShareStatKind { DURATION, PACE, HEART_RATE, CALORIES }

class ShareStat(val kind: ShareStatKind, val label: String, val value: String)

/**
 * Everything the share renderer needs about one workout, already projected and formatted, so that
 * drawing a frame is a pure function of time.
 */
class WorkoutShareModel(
    val title: String,
    val dateText: String,
    val route: List<RoutePoint>,
    /** Heart rate over the workout, evenly spaced in time; empty when not recorded. */
    val heartRates: FloatArray,
    val distanceMeters: Double,
    val durationSeconds: Long,
    /** Display units per metre (km or mi) and its label. */
    private val distanceUnitFactor: Double,
    val distanceUnit: String,
    val stats: Map<ShareStatKind, ShareStat>,
) {
    val hasRoute: Boolean get() = route.size >= 2

    val minX: Double = route.minOfOrNull { it.x } ?: 0.0
    val maxX: Double = route.maxOfOrNull { it.x } ?: 0.0
    val minY: Double = route.minOfOrNull { it.y } ?: 0.0
    val maxY: Double = route.maxOfOrNull { it.y } ?: 0.0

    private val distanceFormat = DecimalFormat("0.00")

    /** The distance number in the display unit, e.g. "5.62". */
    fun formatDistance(meters: Double): String = distanceFormat.format(meters * distanceUnitFactor)

    /** Elapsed time as m:ss or h:mm:ss, for the running counter. */
    fun formatElapsed(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        else String.format(Locale.getDefault(), "%d:%02d", m, s)
    }

    companion object {
        const val WORLD_ZOOM = 20
        const val TILE_SIZE = 256
        val MAP_SIZE: Double = TILE_SIZE.toDouble() * (1L shl WORLD_ZOOM)

        /** Distance cut from both ends of the route when hiding where it starts and ends. */
        const val PRIVACY_TRIM_METERS = 200.0
        private const val MAX_POINTS = 800
        private const val HEART_RATE_SAMPLES = 60

        fun lonToX(lon: Double): Double = (lon + 180.0) / 360.0 * MAP_SIZE

        fun latToY(lat: Double): Double {
            val s = sin(Math.toRadians(lat)).coerceIn(-0.9999, 0.9999)
            return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * MAP_SIZE
        }

        fun build(
            context: Context,
            summary: BaseActivitySummary,
            track: ActivityTrack?,
            hideEnds: Boolean,
        ): WorkoutShareModel {
            val kind = ActivityKind.fromCode(summary.activityKind)
            val formatter = WorkoutValueFormatter(kind)
            val data = summary.summaryData?.let {
                try {
                    ActivitySummaryData.fromJson(it)
                } catch (e: Exception) {
                    null
                }
            }

            val points = track?.allPoints.orEmpty().filter { it.location != null && it.time != null }
            val route = buildRoute(points, hideEnds)
            val heartRates = sampleHeartRates(points)

            // Prefer the device's own summary, fall back to what the track says
            var trackDistance = 0.0
            for (i in 1 until points.size) {
                trackDistance += GPSCoordinate.distanceHaversine(points[i - 1].location, points[i].location)
            }
            val distanceMeters = data?.getNumber(ActivitySummaryEntries.DISTANCE_METERS, null)?.toDouble()
                ?.takeIf { it > 0 } ?: trackDistance
            val durationSeconds = data?.getNumber(ActivitySummaryEntries.ACTIVE_SECONDS, null)?.toLong()
                ?.takeIf { it > 0 }
                ?: ((summary.endTime.time - summary.startTime.time) / 1000).coerceAtLeast(0)

            val oneKm = formatter.convert(1.0, ActivitySummaryEntries.UNIT_KILOMETERS, true)
            val distanceUnitFactor = oneKm.value / 1000.0
            val distanceUnit = formatter.getUnitString(oneKm.unit)

            val stats = LinkedHashMap<ShareStatKind, ShareStat>()
            if (durationSeconds > 0) {
                stats[ShareStatKind.DURATION] = ShareStat(
                    ShareStatKind.DURATION,
                    context.getString(R.string.workout_share_stat_duration),
                    DateTimeUtils.formatSportsDuration(durationSeconds * 1000L, TimeUnit.MILLISECONDS)
                )
            }
            val paceSecondsPerKm = data?.getNumber(ActivitySummaryEntries.PACE_AVG_SECONDS_KM, null)?.toDouble()
                ?.takeIf { it > 0 }
                ?: if (distanceMeters > 0 && durationSeconds > 0) durationSeconds / (distanceMeters / 1000.0) else 0.0
            if (paceSecondsPerKm > 0 && distanceMeters > 0) {
                stats[ShareStatKind.PACE] = ShareStat(
                    ShareStatKind.PACE,
                    context.getString(R.string.workout_share_stat_pace),
                    formatter.formatValue(paceSecondsPerKm, ActivitySummaryEntries.UNIT_SECONDS_PER_KM, true)
                )
            }
            val utils = HeartRateUtils.getInstance()
            val recordedHeartRates = points.map { it.heartRate }.filter { utils.isValidHeartRateValue(it) }
            val averageHeartRate = data?.getNumber(ActivitySummaryEntries.HR_AVG, null)?.toDouble()
                ?.takeIf { it > 0 }
                ?: if (recordedHeartRates.isNotEmpty()) recordedHeartRates.average() else 0.0
            if (averageHeartRate > 0) {
                stats[ShareStatKind.HEART_RATE] = ShareStat(
                    ShareStatKind.HEART_RATE,
                    context.getString(R.string.workout_share_stat_heart_rate),
                    context.getString(R.string.workout_share_value_bpm, averageHeartRate.roundToInt())
                )
            }
            val calories = data?.getNumber(ActivitySummaryEntries.CALORIES_BURNT, null)?.toDouble() ?: 0.0
            if (calories > 0) {
                stats[ShareStatKind.CALORIES] = ShareStat(
                    ShareStatKind.CALORIES,
                    context.getString(R.string.workout_share_stat_calories),
                    formatter.formatValue(calories, ActivitySummaryEntries.UNIT_KCAL, true)
                )
            }

            val title = summary.name?.takeIf { it.isNotBlank() } ?: kind.getLabel(context)
            val dateText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(summary.startTime)

            return WorkoutShareModel(
                title, dateText, route, heartRates, distanceMeters, durationSeconds,
                distanceUnitFactor, distanceUnit, stats,
            )
        }

        private fun buildRoute(
            points: List<nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint>,
            hideEnds: Boolean,
        ): List<RoutePoint> {
            if (points.size < 2) return emptyList()

            // Cumulative distance, to trim the ends and to spread the counter
            val cumulative = DoubleArray(points.size)
            for (i in 1 until points.size) {
                cumulative[i] = cumulative[i - 1] + GPSCoordinate.distanceHaversine(points[i - 1].location, points[i].location)
            }
            val total = cumulative.last()
            var first = 0
            var last = points.size - 1
            // Short routes keep their ends; trimming would leave too little to show
            if (hideEnds && total >= 3 * PRIVACY_TRIM_METERS) {
                while (first < last && cumulative[first] < PRIVACY_TRIM_METERS) first++
                while (last > first && cumulative[last] > total - PRIVACY_TRIM_METERS) last--
            }
            if (last - first < 1) return emptyList()

            // Speed per point: recorded when available, otherwise from the neighbours
            val speeds = FloatArray(points.size)
            for (i in points.indices) {
                val recorded = points[i].speed
                speeds[i] = if (recorded > 0f) recorded else {
                    val a = max(0, i - 2)
                    val b = min(points.size - 1, i + 2)
                    val seconds = (points[b].time.time - points[a].time.time) / 1000.0
                    if (seconds > 0) ((cumulative[b] - cumulative[a]) / seconds).toFloat() else 0f
                }
            }
            val smoothed = FloatArray(points.size) { i ->
                var sum = 0f
                var n = 0
                for (j in max(0, i - 3)..min(points.size - 1, i + 3)) {
                    if (speeds[j] > 0f) {
                        sum += speeds[j]
                        n++
                    }
                }
                if (n > 0) sum / n else 0f
            }
            val moving = smoothed.filter { it > 0f }.sorted()
            val slow = if (moving.isEmpty()) 0f else moving[(moving.size * 0.1).toInt().coerceIn(0, moving.size - 1)]
            val fast = if (moving.isEmpty()) 1f else moving[(moving.size * 0.9).toInt().coerceIn(0, moving.size - 1)]
            val range = (fast - slow).takeIf { it > 0.01f } ?: 1f

            val startTime = points[first].time.time
            val timeSpan = (points[last].time.time - startTime).coerceAtLeast(1L).toDouble()
            val distanceStart = cumulative[first]
            val distanceSpan = (cumulative[last] - distanceStart).coerceAtLeast(1.0)

            // Even index sampling keeps the shape and bounds the per-frame work
            val count = last - first + 1
            val step = max(1.0, count / MAX_POINTS.toDouble())
            val route = ArrayList<RoutePoint>(min(count, MAX_POINTS) + 1)
            var position = first.toDouble()
            while (position <= last) {
                val i = position.toInt()
                route += point(points[i], cumulative[i], smoothed[i], startTime, timeSpan, distanceStart, distanceSpan, slow, range)
                position += step
            }
            if (route.size < 2 || route.last().timeFraction < 1f) {
                route += point(points[last], cumulative[last], smoothed[last], startTime, timeSpan, distanceStart, distanceSpan, slow, range)
            }
            return route
        }

        private fun point(
            p: nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint,
            cumulativeMeters: Double,
            speed: Float,
            startTime: Long,
            timeSpan: Double,
            distanceStart: Double,
            distanceSpan: Double,
            slow: Float,
            range: Float,
        ) = RoutePoint(
            x = lonToX(p.location?.longitude ?: 0.0),
            y = latToY(p.location?.latitude ?: 0.0),
            timeFraction = ((p.time.time - startTime) / timeSpan).toFloat().coerceIn(0f, 1f),
            distanceFraction = ((cumulativeMeters - distanceStart) / distanceSpan).toFloat().coerceIn(0f, 1f),
            pace = if (speed > 0f) ((speed - slow) / range).coerceIn(0f, 1f) else 0.5f,
        )

        private fun sampleHeartRates(points: List<nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint>): FloatArray {
            val utils = HeartRateUtils.getInstance()
            val valid = points.filter { utils.isValidHeartRateValue(it.heartRate) }
            if (valid.size < 4) return FloatArray(0)
            val start = valid.first().time.time
            val span = (valid.last().time.time - start).coerceAtLeast(1L)
            val sums = FloatArray(HEART_RATE_SAMPLES)
            val counts = IntArray(HEART_RATE_SAMPLES)
            for (p in valid) {
                val bucket = ((p.time.time - start) * (HEART_RATE_SAMPLES - 1) / span).toInt().coerceIn(0, HEART_RATE_SAMPLES - 1)
                sums[bucket] += p.heartRate.toFloat()
                counts[bucket]++
            }
            // Empty buckets borrow the previous value so the line stays continuous
            val out = FloatArray(HEART_RATE_SAMPLES)
            var previous = valid.first().heartRate.toFloat()
            for (i in 0 until HEART_RATE_SAMPLES) {
                if (counts[i] > 0) previous = sums[i] / counts[i]
                out[i] = previous
            }
            return out
        }
    }
}
