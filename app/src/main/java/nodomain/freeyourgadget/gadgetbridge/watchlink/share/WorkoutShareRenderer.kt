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
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.watchlink.WatchLinkFonts
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

enum class ShareBackground { MAP, PASTEL, TRANSPARENT }

enum class ShareInk { DARK, LIGHT }

/** What the offline map has to cover, see [OfflineMapLayers.render]. */
class MapRequest(val detailArea: WorldRect, val detailScale: Double, val baseArea: WorldRect, val baseScale: Double)

/**
 * Draws a shared workout in a 1080x1920 design space: the animated route video
 * ([drawVideoFrame]) and the static card ([drawCard]). Every frame is a pure function of the time
 * it is asked for, so the preview, the encoded MP4 and a re-export are identical.
 *
 * Timeline: the start point blooms in a close-up, the head runs the route at the workout's own
 * tempo while the camera follows and widens to the whole route, the finish pops and a highlight
 * sweeps the line, then the live counter panel morphs into the summary card.
 */
class WorkoutShareRenderer(
    context: Context,
    private val model: WorkoutShareModel,
    private val map: OfflineMapLayers?,
) {
    private val displayFace = WatchLinkFonts.display(context)
    private val regularFace = WatchLinkFonts.regular(context)
    private val mediumFace = WatchLinkFonts.medium(context)
    private val semiBoldFace = WatchLinkFonts.semiBold(context)
    private val slowLabel = context.getString(R.string.workout_share_legend_slow)
    private val fastLabel = context.getString(R.string.workout_share_legend_fast)
    private val wordmark = context.getString(R.string.workout_share_wordmark)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 7f
        color = ColorUtils.setAlphaComponent(ACCENT, 70)
        pathEffect = DashPathEffect(floatArrayOf(1f, 18f), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = pastelMapFilter()
    }
    private val grainShader = BitmapShader(makeGrain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    private val grainPaint = Paint().apply {
        shader = grainShader
        alpha = 16
    }
    private val grainMatrix = Matrix()
    private val bitmapMatrix = Matrix()
    private val path = Path()
    private val panelPath = Path()
    private val xs = FloatArray(model.route.size)
    private val ys = FloatArray(model.route.size)

    // ---------------------------------------------------------------- video

    fun drawVideoFrame(canvas: Canvas, t: Float, background: ShareBackground, kinds: Set<ShareStatKind>) {
        val camera = videoCamera(t)
        drawBackground(canvas, camera, background, t)

        if (model.hasRoute) {
            project(camera)
            val head = headAt(runProgress(t))
            if (t < RUN_END) {
                drawGhost(canvas)
            }
            drawRoute(canvas, head.index)
            if (t >= RUN_END && t < FINISH_END) {
                drawShimmer(canvas, easeInOutCubic(progress(t, RUN_END, FINISH_END)))
            }
            drawStart(canvas, easeOutBack(progress(t, 0.05f, 0.65f)))
            if (t < RUN_END) {
                drawHead(canvas, camera.sx(head.x), camera.sy(head.y), head.pace, t)
            } else {
                drawFinish(canvas, t)
            }
        }

        drawHeader(canvas, ShareInk.DARK, progress(t, 0.1f, 0.7f))
        drawPanel(canvas, t, camera, background, kinds)
    }

    private fun drawPanel(canvas: Canvas, t: Float, camera: Camera, background: ShareBackground, kinds: Set<ShareStatKind>) {
        val enter = easeOutCubic(progress(t, 0.3f, 0.9f))
        if (enter <= 0f) return
        // The live counter panel becomes the summary card: one object, so the eye follows it
        val morph = easeInOutCubic(progress(t, FINISH_END, TRANSITION_END))
        val rect = lerpRect(PILL, SUMMARY_CARD, morph)
        rect.offset(0f, (1f - enter) * 280f)
        drawGlass(canvas, rect, lerp(56f, 60f, morph), camera, background, enter)

        val liveAlpha = enter * (1f - progress(morph, 0f, 0.45f))
        if (liveAlpha > 0f) {
            drawLiveCounters(canvas, rect, t, liveAlpha)
        }
        val revealStart = TRANSITION_END - 0.25f
        if (t >= revealStart) {
            drawSummaryContent(canvas, rect, t - revealStart, ShareInk.DARK, kinds)
        }
    }

    private fun drawLiveCounters(canvas: Canvas, rect: RectF, t: Float, alpha: Float) {
        val p = runProgress(t)
        val head = if (model.hasRoute) headAt(p) else null
        val meters = model.distanceMeters * (head?.distanceFraction ?: p)
        val seconds = (model.durationSeconds * (head?.timeFraction ?: p)).toLong()
        val left = rect.left + 52f
        val baseline = rect.top + 142f

        val number = model.formatDistance(meters)
        setText(displayFace, 132f, inkColor(ShareInk.DARK, alpha))
        canvas.drawText(number, left, baseline, text)
        val numberWidth = text.measureText(number)
        setText(mediumFace, 38f, mutedColor(ShareInk.DARK, alpha))
        canvas.drawText(model.distanceUnit, left + numberWidth + 14f, baseline, text)

        setText(mediumFace, 50f, inkColor(ShareInk.DARK, alpha))
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(model.formatElapsed(seconds), rect.right - 52f, baseline - 8f, text)
        text.textAlign = Paint.Align.LEFT

        if (model.heartRates.size >= 2) {
            drawHeartSpline(canvas, RectF(left, rect.top + 172f, rect.right - 52f, rect.bottom - 30f), p, alpha)
        }
    }

    private fun drawHeartSpline(canvas: Canvas, area: RectF, progress: Float, alpha: Float) {
        val values = model.heartRates
        var min = values[0]
        var max = values[0]
        for (v in values) {
            if (v < min) min = v
            if (v > max) max = v
        }
        val range = (max - min).takeIf { it > 0f } ?: 1f
        fun x(i: Int) = area.left + area.width() * i / (values.size - 1)
        fun y(v: Float) = area.bottom - area.height() * (v - min) / range

        path.reset()
        var px = x(0)
        var py = y(values[0])
        path.moveTo(px, py)
        for (i in 1 until values.size) {
            val cx = x(i)
            val cy = y(values[i])
            val mid = (px + cx) / 2f
            path.cubicTo(mid, py, mid, cy, cx, cy)
            px = cx
            py = cy
        }

        val save = canvas.save()
        canvas.clipRect(area.left - 8f, area.top - 8f, area.left + area.width() * progress, area.bottom + 8f)
        routePaint.color = ColorUtils.setAlphaComponent(HEART, (255 * alpha).toInt())
        routePaint.strokeWidth = 5f
        canvas.drawPath(path, routePaint)
        canvas.restoreToCount(save)
    }

    // ---------------------------------------------------------------- card

    fun drawCard(canvas: Canvas, background: ShareBackground, ink: ShareInk, kinds: Set<ShareStatKind>) {
        val camera = fitCamera(CARD_ROUTE_VIEW)
        drawBackground(canvas, camera, background, CARD_TIME)
        if (model.hasRoute) {
            project(camera)
            drawRoute(canvas, (model.route.size - 1).toFloat())
            drawStart(canvas, 1f)
            drawFinish(canvas, DURATION)
        }
        drawHeader(canvas, ink, 1f)
        if (background != ShareBackground.TRANSPARENT) {
            drawGlass(canvas, RectF(SUMMARY_CARD), 60f, camera, background, 1f)
        }
        drawSummaryContent(canvas, RectF(SUMMARY_CARD), REVEALED, ink, kinds)
    }

    // ---------------------------------------------------------------- shared pieces

    private fun drawHeader(canvas: Canvas, ink: ShareInk, reveal: Float) {
        val u = easeOutCubic(reveal)
        if (u <= 0f) return
        val dy = (1f - u) * 36f
        setText(mediumFace, 52f, inkColor(ink, u))
        trailedText(canvas, model.title, MARGIN, 160f + dy, dy, ink)
        setText(regularFace, 34f, mutedColor(ink, u))
        trailedText(canvas, model.dateText, MARGIN, 214f + dy, dy, ink)
    }

    /** The summary: distance as the hero, the pace legend, up to four stats and the wordmark. */
    private fun drawSummaryContent(canvas: Canvas, rect: RectF, revealT: Float, ink: ShareInk, kinds: Set<ShareStatKind>) {
        val left = rect.left + 56f

        reveal(revealT, 0f).let { (a, dy) ->
            if (a > 0f) {
                val number = model.formatDistance(model.distanceMeters)
                setText(displayFace, 220f, inkColor(ink, a))
                trailedText(canvas, number, left, rect.top + 236f + dy, dy, ink)
                val width = text.measureText(number)
                setText(mediumFace, 50f, mutedColor(ink, a))
                trailedText(canvas, model.distanceUnit, left + width + 18f, rect.top + 236f + dy, dy, ink)
            }
        }

        if (model.hasRoute) {
            reveal(revealT, 0.3f).let { (a, dy) ->
                if (a > 0f) drawLegend(canvas, rect.right - 56f - LEGEND_WIDTH, rect.top + 96f + dy, ink, a)
            }
        }

        val shown = ShareStatKind.entries.filter { it in kinds }.mapNotNull { model.stats[it] }.take(4)
        shown.forEachIndexed { index, stat ->
            val (a, dy) = reveal(revealT, 0.12f + 0.07f * index)
            if (a <= 0f) return@forEachIndexed
            val column = index % 2
            val row = index / 2
            val x = if (column == 0) left else rect.left + rect.width() / 2f + 20f
            val top = rect.top + 330f + row * 172f + dy
            setText(regularFace, 32f, mutedColor(ink, a))
            trailedText(canvas, stat.label, x, top + 34f, dy, ink)
            setText(semiBoldFace, 58f, inkColor(ink, a))
            trailedText(canvas, stat.value, x, top + 104f, dy, ink)
        }

        reveal(revealT, 0.45f).let { (a, _) ->
            if (a > 0f) {
                setText(mediumFace, 30f, mutedColor(ink, a))
                text.textAlign = Paint.Align.RIGHT
                canvas.drawText(wordmark, rect.right - 56f, rect.bottom - 44f, text)
                text.textAlign = Paint.Align.LEFT
            }
        }
        text.clearShadowLayer()
    }

    private fun drawLegend(canvas: Canvas, x: Float, y: Float, ink: ShareInk, alpha: Float) {
        val a = (255 * alpha).toInt()
        fill.shader = LinearGradient(
            x, 0f, x + LEGEND_WIDTH, 0f,
            intArrayOf(
                ColorUtils.setAlphaComponent(PACE_SLOW, a),
                ColorUtils.setAlphaComponent(PACE_MID, a),
                ColorUtils.setAlphaComponent(PACE_FAST, a)
            ),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(x, y, x + LEGEND_WIDTH, y + 12f, 6f, 6f, fill)
        fill.shader = null
        setText(regularFace, 28f, mutedColor(ink, alpha))
        canvas.drawText(slowLabel, x, y + 50f, text)
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(fastLabel, x + LEGEND_WIDTH, y + 50f, text)
        text.textAlign = Paint.Align.LEFT
    }

    /**
     * Frosted glass: a soft shadow, the blurred map (when there is one) seen through the panel, a
     * translucent white fill, a top highlight and a bright hairline border.
     */
    private fun drawGlass(canvas: Canvas, rect: RectF, radius: Float, camera: Camera, background: ShareBackground, alpha: Float) {
        fill.shader = null
        for (i in 1..3) {
            fill.color = Color.argb((12 * alpha / i).toInt(), 0x1D, 0x1F, 0x1C)
            val spread = i * 7f
            canvas.drawRoundRect(
                rect.left - spread, rect.top - spread + spread, rect.right + spread, rect.bottom + spread + spread,
                radius + spread, radius + spread, fill
            )
        }

        panelPath.reset()
        panelPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
        val layers = map
        if (background == ShareBackground.MAP && layers != null) {
            val save = canvas.save()
            canvas.clipPath(panelPath)
            mapPaint.alpha = (255 * alpha).toInt()
            drawMapImage(canvas, camera, layers.blurredBase)
            mapPaint.alpha = 255
            canvas.restoreToCount(save)
        }
        fill.color = Color.argb((150 * alpha).toInt(), 255, 255, 255)
        canvas.drawPath(panelPath, fill)
        fill.shader = LinearGradient(
            0f, rect.top, 0f, rect.top + rect.height() * 0.6f,
            Color.argb((95 * alpha).toInt(), 255, 255, 255), Color.argb(0, 255, 255, 255),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(panelPath, fill)
        fill.shader = null
        border.strokeWidth = 2.5f
        border.color = Color.argb((230 * alpha).toInt(), 255, 255, 255)
        canvas.drawPath(panelPath, border)
    }

    private fun drawBackground(canvas: Canvas, camera: Camera, background: ShareBackground, t: Float) {
        val layers = map
        when {
            background == ShareBackground.TRANSPARENT -> Unit
            background == ShareBackground.MAP && layers != null -> {
                canvas.drawColor(Color.rgb(0xF2, 0xEF, 0xE9))
                drawMapImage(canvas, camera, layers.base)
                drawMapImage(canvas, camera, layers.detail)
                canvas.drawColor(Color.argb(60, 255, 255, 255))
                drawGrain(canvas, t)
            }
            else -> drawPastel(canvas, t)
        }
    }

    /** White page with large drifting pastel blobs: the glassmorphism backdrop. */
    private fun drawPastel(canvas: Canvas, t: Float) {
        canvas.drawColor(Color.WHITE)
        for (blob in BLOBS) {
            val x = blob.x + blob.ax * sin(t * blob.speed + blob.phase)
            val y = blob.y + blob.ay * cos(t * blob.speed * 0.8f + blob.phase)
            fill.shader = RadialGradient(x, y, blob.radius, blob.color, blob.color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, blob.radius, fill)
        }
        fill.shader = null
        drawGrain(canvas, t)
    }

    /** Film grain that changes every other frame, deterministically. */
    private fun drawGrain(canvas: Canvas, t: Float) {
        val step = (t * FPS / 2f).toInt()
        val h = (step * 1103515245 + 12345) and 0x7FFFFFFF
        grainMatrix.setTranslate((h % GRAIN_SIZE).toFloat(), ((h / GRAIN_SIZE) % GRAIN_SIZE).toFloat())
        grainShader.setLocalMatrix(grainMatrix)
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), grainPaint)
    }

    private fun drawMapImage(canvas: Canvas, camera: Camera, image: MapImage) {
        val scale = (image.worldPerPixel * camera.scale).toFloat()
        bitmapMatrix.setScale(scale, scale)
        bitmapMatrix.postTranslate(camera.sx(image.originX), camera.sy(image.originY))
        canvas.drawBitmap(image.bitmap, bitmapMatrix, mapPaint)
    }

    // ---------------------------------------------------------------- route

    private fun project(camera: Camera) {
        val route = model.route
        for (i in route.indices) {
            xs[i] = camera.sx(route[i].x)
            ys[i] = camera.sy(route[i].y)
        }
    }

    /**
     * Smooth path through the projected points: quadratic curves between midpoints, so the line
     * reads as a spline. Ends at the midpoint after [to], or, with [tail], runs on to (tx, ty).
     */
    private fun buildPath(from: Int, to: Int, tail: Boolean, tx: Float, ty: Float) {
        path.reset()
        if (from == 0) {
            path.moveTo(xs[0], ys[0])
        } else {
            path.moveTo((xs[from - 1] + xs[from]) / 2f, (ys[from - 1] + ys[from]) / 2f)
        }
        for (i in from until to) {
            path.quadTo(xs[i], ys[i], (xs[i] + xs[i + 1]) / 2f, (ys[i] + ys[i + 1]) / 2f)
        }
        if (tail) {
            path.quadTo(xs[to], ys[to], tx, ty)
        }
    }

    private fun drawGhost(canvas: Canvas) {
        val last = model.route.size - 1
        buildPath(0, last, true, xs[last], ys[last])
        canvas.drawPath(path, ghostPaint)
    }

    /** The route up to [headIndex] (fractional point index), coloured by pace over a white casing. */
    private fun drawRoute(canvas: Canvas, headIndex: Float) {
        val n = model.route.size
        val last = floor(headIndex).toInt().coerceIn(0, n - 1)
        val f = headIndex - last
        val hx = if (last < n - 1) lerp(xs[last], xs[last + 1], f) else xs[last]
        val hy = if (last < n - 1) lerp(ys[last], ys[last + 1], f) else ys[last]

        if (last == 0) {
            path.reset()
            path.moveTo(xs[0], ys[0])
            path.lineTo(hx, hy)
        } else {
            buildPath(0, last, true, hx, hy)
        }
        routePaint.color = CASING
        routePaint.strokeWidth = 22f
        canvas.drawPath(path, routePaint)
        routePaint.strokeWidth = 12f

        if (last == 0) {
            routePaint.color = paceColor(model.route[0].pace)
            canvas.drawPath(path, routePaint)
            return
        }
        var start = 0
        while (start < last) {
            val end = min(start + CHUNK, last)
            buildPath(start, end, end == last, hx, hy)
            var pace = 0f
            for (i in start..end) pace += model.route[i].pace
            routePaint.color = paceColor(pace / (end - start + 1))
            canvas.drawPath(path, routePaint)
            start = end
        }
    }

    private fun drawShimmer(canvas: Canvas, u: Float) {
        val n = model.route.size
        val window = max(2, (n * 0.09f).toInt())
        val head = ((n - 1 + window) * u).toInt()
        val to = head.coerceIn(1, n - 1)
        val from = (head - window).coerceIn(0, to - 1)
        buildPath(from, to, true, xs[to], ys[to])
        routePaint.color = Color.argb((220 * sin(PI * u)).toInt().coerceAtLeast(0), 255, 255, 255)
        routePaint.strokeWidth = 7f
        canvas.drawPath(path, routePaint)
    }

    private fun drawStart(canvas: Canvas, k: Float) {
        if (k <= 0f) return
        fill.color = Color.WHITE
        canvas.drawCircle(xs[0], ys[0], 14f * k, fill)
        border.strokeWidth = 5f * k
        border.color = ACCENT
        canvas.drawCircle(xs[0], ys[0], 14f * k, border)
    }

    private fun drawHead(canvas: Canvas, x: Float, y: Float, pace: Float, t: Float) {
        val color = paceColor(pace)
        val pulse = 0.5f + 0.5f * sin(t * 6.2f)
        fill.color = ColorUtils.setAlphaComponent(color, (50 + 40 * pulse).toInt())
        canvas.drawCircle(x, y, 30f + 6f * pulse, fill)
        fill.color = Color.WHITE
        canvas.drawCircle(x, y, 16f, fill)
        fill.color = color
        canvas.drawCircle(x, y, 10f, fill)
        border.strokeWidth = 3f
        border.color = ACCENT
        canvas.drawCircle(x, y, 16f, border)
    }

    private fun drawFinish(canvas: Canvas, t: Float) {
        val last = model.route.size - 1
        val x = xs[last]
        val y = ys[last]
        val halo = progress(t, RUN_END, RUN_END + 0.9f)
        if (halo in 0f..0.999f) {
            border.strokeWidth = 4f
            border.color = ColorUtils.setAlphaComponent(ACCENT, ((1f - halo) * 140).toInt())
            canvas.drawCircle(x, y, 18f + 48f * easeOutCubic(halo), border)
        }
        val k = easeOutBack(progress(t, RUN_END, RUN_END + 0.45f))
        if (k <= 0f) return
        fill.color = ACCENT
        canvas.drawCircle(x, y, 17f * k, fill)
        fill.color = Color.WHITE
        canvas.drawCircle(x, y, 6f * k, fill)
    }

    // ---------------------------------------------------------------- text helpers

    private fun setText(face: android.graphics.Typeface, size: Float, color: Int) {
        text.typeface = face
        text.textSize = size
        text.color = color
        text.textAlign = Paint.Align.LEFT
    }

    /**
     * Text with a short directional trail while it is still moving ([dy] > 0): the motion blur that
     * keeps an entrance from reading as a slide fade. Light ink also gets a soft shadow so it stays
     * legible on any photo.
     */
    private fun trailedText(canvas: Canvas, value: String, x: Float, y: Float, dy: Float, ink: ShareInk) {
        if (ink == ShareInk.LIGHT) {
            text.setShadowLayer(10f, 0f, 2f, Color.argb(110, 0, 0, 0))
        } else {
            text.clearShadowLayer()
        }
        if (dy > 2f) {
            val color = text.color
            val alpha = Color.alpha(color)
            text.color = ColorUtils.setAlphaComponent(color, alpha / 3)
            canvas.drawText(value, x, y + dy * 0.45f, text)
            text.color = ColorUtils.setAlphaComponent(color, alpha / 7)
            canvas.drawText(value, x, y + dy * 0.9f, text)
            text.color = color
        }
        canvas.drawText(value, x, y, text)
    }

    private fun inkColor(ink: ShareInk, alpha: Float) =
        ColorUtils.setAlphaComponent(if (ink == ShareInk.DARK) INK_DARK else Color.WHITE, (255 * alpha.coerceIn(0f, 1f)).toInt())

    private fun mutedColor(ink: ShareInk, alpha: Float) =
        ColorUtils.setAlphaComponent(if (ink == ShareInk.DARK) MUTED_DARK else Color.WHITE, ((if (ink == ShareInk.DARK) 255 else 220) * alpha.coerceIn(0f, 1f)).toInt())

    /** Alpha and remaining slide-up offset of an element revealed [delay] seconds into the summary. */
    private fun reveal(revealT: Float, delay: Float): Pair<Float, Float> {
        val u = easeOutCubic(progress(revealT, delay, delay + 0.45f))
        return Pair(u, (1f - u) * 48f)
    }

    // ---------------------------------------------------------------- camera

    private class Camera(val cx: Double, val cy: Double, val scale: Double, val screenX: Float, val screenY: Float) {
        fun sx(worldX: Double) = ((worldX - cx) * scale + screenX).toFloat()
        fun sy(worldY: Double) = ((worldY - cy) * scale + screenY).toFloat()
    }

    private class WorldHead(
        val x: Double, val y: Double, val index: Float, val pace: Float,
        val distanceFraction: Float, val timeFraction: Float,
    )

    private fun fitCamera(view: RectF): Camera = Camera(
        (model.minX + model.maxX) / 2.0, (model.minY + model.maxY) / 2.0,
        fitScale(model, view), view.centerX(), view.centerY()
    )

    private fun videoCamera(t: Float): Camera {
        val view = lerpRect(RUN_VIEW, SUMMARY_VIEW, easeInOutCubic(progress(t, FINISH_END, TRANSITION_END)))
        val fit = fitCamera(view)
        if (!model.hasRoute) return fit
        val close = fit.scale * CLOSE_ZOOM
        val start = model.route[0]
        return when {
            t < RUN_START -> {
                // A slow push in on the start point
                val push = 1.0 + 0.06 * (1.0 - easeOutCubic(progress(t, 0f, RUN_START)))
                Camera(start.x, start.y, close / push, view.centerX(), view.centerY())
            }
            t < RUN_END -> {
                val p = runProgress(t)
                // Follow a smoothed head so GPS jitter does not shake the frame
                val a = worldHeadAt((p - FOLLOW_SPAN).coerceAtLeast(0f))
                val b = worldHeadAt(p)
                val c = worldHeadAt((p + FOLLOW_SPAN).coerceAtMost(1f))
                val followX = (a.x + b.x + c.x) / 3.0
                val followY = (a.y + b.y + c.y) / 3.0
                // Stay close for the first stretch, then widen to the whole route
                val z = easeInOutCubic(progress(progress(t, RUN_START, RUN_END), 0.18f, 1f)).toDouble()
                val scale = close * (fit.scale / close).pow(z)
                Camera(
                    followX + (fit.cx - followX) * z, followY + (fit.cy - followY) * z,
                    scale, view.centerX(), view.centerY()
                )
            }
            else -> {
                // Settle with a small overshoot, then hold
                val s = progress(t, RUN_END, FINISH_END).toDouble()
                val settle = 1.0 + 0.03 * sin(PI * s) * (1.0 - s)
                Camera(fit.cx, fit.cy, fit.scale * settle, view.centerX(), view.centerY())
            }
        }
    }

    private fun runProgress(t: Float) = easeInOutSine(progress(t, RUN_START, RUN_END))

    private fun worldHeadAt(p: Float): WorldHead {
        val route = model.route
        val first = route.first()
        val last = route.last()
        if (p <= first.timeFraction) return WorldHead(first.x, first.y, 0f, first.pace, 0f, 0f)
        if (p >= last.timeFraction) {
            return WorldHead(last.x, last.y, (route.size - 1).toFloat(), last.pace, 1f, 1f)
        }
        var lo = 0
        var hi = route.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (route[mid].timeFraction <= p) lo = mid else hi = mid
        }
        val a = route[lo]
        val b = route[hi]
        val span = b.timeFraction - a.timeFraction
        val f = if (span > 0f) ((p - a.timeFraction) / span).coerceIn(0f, 1f) else 0f
        return WorldHead(
            a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, lo + f,
            lerp(a.pace, b.pace, f), lerp(a.distanceFraction, b.distanceFraction, f), p
        )
    }

    /** [worldHeadAt] with the screen position already projected. */
    private fun headAt(p: Float): WorldHead = worldHeadAt(p)

    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
        const val FPS = 30

        /** Start bloom, then the 12-second run, the finish, the morph and the summary hold. */
        const val RUN_START = 0.8f
        const val RUN_END = 11.2f
        const val FINISH_END = 12.0f
        const val TRANSITION_END = 12.8f
        const val DURATION = 16.5f
        val FRAME_COUNT: Int = (DURATION * FPS).roundToInt()

        private const val CARD_TIME = 4f
        private const val REVEALED = 99f
        private const val CLOSE_ZOOM = 2.4
        private const val FOLLOW_SPAN = 0.025f
        private const val CHUNK = 6
        private const val MARGIN = 72f
        private const val LEGEND_WIDTH = 230f
        private const val GRAIN_SIZE = 160
        private const val FIT_PADDING = 64f

        /** Smallest route extent the camera frames, about 300 m, so short walks are not over-zoomed. */
        private const val MIN_SPAN_WORLD = 2000.0

        private val RUN_VIEW = RectF(72f, 290f, 1008f, 1500f)
        private val SUMMARY_VIEW = RectF(96f, 270f, 984f, 1010f)
        private val CARD_ROUTE_VIEW = RectF(96f, 290f, 984f, 1020f)
        private val PILL = RectF(72f, 1560f, 1008f, 1820f)
        private val SUMMARY_CARD = RectF(56f, 1060f, 1024f, 1840f)

        // WatchLink "Cocoa" look: deep cocoa for markers and accents, pink for heart rate
        private val ACCENT = Color.rgb(0x4F, 0x34, 0x22)
        private val HEART = Color.rgb(0xF0, 0x6A, 0x8C)
        private val PACE_SLOW = Color.rgb(0x8D, 0xB3, 0x7A)
        private val PACE_MID = Color.rgb(0xF1, 0xC6, 0x6E)
        private val PACE_FAST = Color.rgb(0xE3, 0x89, 0x7D)
        private val INK_DARK = Color.rgb(0x1D, 0x1F, 0x1C)
        private val MUTED_DARK = Color.rgb(0x6F, 0x6A, 0x5F)
        private val CASING = Color.argb(235, 255, 255, 255)

        private class Blob(
            val x: Float, val y: Float, val radius: Float, val color: Int,
            val ax: Float, val ay: Float, val speed: Float, val phase: Float,
        )

        private val BLOBS = listOf(
            Blob(180f, 360f, 720f, Color.argb(205, 0xCF, 0xE6, 0xC3), 90f, 70f, 0.21f, 0.0f),
            Blob(960f, 760f, 680f, Color.argb(190, 0xFC, 0xD9, 0xC2), 80f, 90f, 0.17f, 1.7f),
            Blob(260f, 1420f, 760f, Color.argb(185, 0xCD, 0xE4, 0xF6), 100f, 70f, 0.19f, 3.1f),
            Blob(900f, 1780f, 640f, Color.argb(175, 0xE5, 0xDA, 0xF5), 70f, 80f, 0.23f, 4.4f),
        )

        fun paceColor(pace: Float): Int = if (pace < 0.5f) {
            ColorUtils.blendARGB(PACE_SLOW, PACE_MID, pace * 2f)
        } else {
            ColorUtils.blendARGB(PACE_MID, PACE_FAST, (pace - 0.5f) * 2f)
        }

        private fun fitScale(model: WorkoutShareModel, view: RectF): Double {
            val width = max(model.maxX - model.minX, MIN_SPAN_WORLD)
            val height = max(model.maxY - model.minY, MIN_SPAN_WORLD)
            return min((view.width() - 2 * FIT_PADDING) / width, (view.height() - 2 * FIT_PADDING) / height)
        }

        /** The map areas a share of [model] can show, or null when it has no route. */
        fun mapRequest(model: WorkoutShareModel): MapRequest? {
            if (!model.hasRoute) return null
            val cx = (model.minX + model.maxX) / 2.0
            val cy = (model.minY + model.maxY) / 2.0
            val fitRun = fitScale(model, RUN_VIEW)
            val close = fitRun * CLOSE_ZOOM
            val fitSummary = fitScale(model, SUMMARY_VIEW)
            val fitCard = fitScale(model, CARD_ROUTE_VIEW)

            // Detail: the route plus what the close-up camera sees around any point of it
            val halfW = WIDTH / 2.0 / close
            val halfH = HEIGHT / 2.0 / close
            val detail = WorldRect(model.minX - halfW, model.minY - halfH, model.maxX + halfW, model.maxY + halfH)

            fun screen(scale: Double, view: RectF) = WorldRect(
                cx - view.centerX() / scale, cy - view.centerY() / scale,
                cx + (WIDTH - view.centerX()) / scale, cy + (HEIGHT - view.centerY()) / scale
            )
            val base = screen(fitRun, RUN_VIEW)
                .union(screen(fitSummary, SUMMARY_VIEW))
                .union(screen(fitCard, CARD_ROUTE_VIEW))
                .union(detail)
                .inflate(0.08)
            val widest = min(fitRun, min(fitSummary, fitCard))
            return MapRequest(detail, fitRun * 1.4, base, widest * 0.6)
        }

        /** Desaturated and lifted: the map becomes a quiet pastel ground under the route. */
        private fun pastelMapFilter(): ColorMatrixColorFilter {
            val matrix = ColorMatrix().apply { setSaturation(0.35f) }
            val lift = ColorMatrix(
                floatArrayOf(
                    0.82f, 0f, 0f, 0f, 42f,
                    0f, 0.82f, 0f, 0f, 44f,
                    0f, 0f, 0.82f, 0f, 40f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
            matrix.postConcat(lift)
            return ColorMatrixColorFilter(matrix)
        }

        private fun makeGrain(): Bitmap {
            val bitmap = createBitmap(GRAIN_SIZE, GRAIN_SIZE)
            val pixels = IntArray(GRAIN_SIZE * GRAIN_SIZE)
            var seed = 0x2545F491
            for (i in pixels.indices) {
                seed = seed * 1664525 + 1013904223
                val v = (seed ushr 24) and 0xFF
                pixels[i] = Color.rgb(v, v, v)
            }
            bitmap.setPixels(pixels, 0, GRAIN_SIZE, 0, 0, GRAIN_SIZE, GRAIN_SIZE)
            return bitmap
        }

        fun progress(t: Float, from: Float, to: Float): Float =
            if (to <= from) (if (t >= to) 1f else 0f) else ((t - from) / (to - from)).coerceIn(0f, 1f)

        fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

        fun lerpRect(a: RectF, b: RectF, f: Float) = RectF(
            lerp(a.left, b.left, f), lerp(a.top, b.top, f), lerp(a.right, b.right, f), lerp(a.bottom, b.bottom, f)
        )

        fun easeOutCubic(x: Float): Float = 1f - (1f - x).pow(3)

        fun easeInOutCubic(x: Float): Float = if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f

        fun easeInOutSine(x: Float): Float = (-(cos(PI * x) - 1.0) / 2.0).toFloat()

        fun easeOutBack(x: Float): Float {
            if (x <= 0f) return 0f
            val c1 = 1.70158f
            val c3 = c1 + 1f
            return 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2)
        }
    }
}
