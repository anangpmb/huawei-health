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

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * Live 9:16 preview of the share: plays the video timeline in a loop, or shows the card over a
 * checkerboard so a transparent background reads as transparent. With animations turned off in the
 * system settings it shows the final frame instead of playing.
 */
class WorkoutSharePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { VIDEO, CARD }

    var renderer: WorkoutShareRenderer? = null
        set(value) {
            field = value
            invalidate()
        }
    var mode: Mode = Mode.VIDEO
        set(value) {
            field = value
            updatePlayback()
            invalidate()
        }
    var background: ShareBackground = ShareBackground.PASTEL
        set(value) {
            field = value
            invalidate()
        }
    var ink: ShareInk = ShareInk.DARK
        set(value) {
            field = value
            invalidate()
        }
    var stats: Set<ShareStatKind> = ShareStatKind.entries.toSet()
        set(value) {
            field = value
            invalidate()
        }

    /** Tallest the preview may be, so the options below stay reachable. */
    var maxPreviewHeight: Int = Int.MAX_VALUE

    private var time = WorkoutShareRenderer.DURATION - 0.01f
    private val checker = Paint()
    private val clip = Path()
    private val radius = 24 * resources.displayMetrics.density

    private val animator = ValueAnimator.ofFloat(0f, WorkoutShareRenderer.DURATION).apply {
        duration = (WorkoutShareRenderer.DURATION * 1000).toLong()
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            time = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        var height = availableWidth * WorkoutShareRenderer.HEIGHT / WorkoutShareRenderer.WIDTH
        height = min(height, maxPreviewHeight)
        val width = height * WorkoutShareRenderer.WIDTH / WorkoutShareRenderer.HEIGHT
        setMeasuredDimension(width, height)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updatePlayback()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Also called from the View constructor, before this class's fields exist
        if (isAttachedToWindow) {
            updatePlayback()
        }
    }

    private fun updatePlayback() {
        val play = mode == Mode.VIDEO && isAttachedToWindow && isShown && animationsEnabled()
        if (play && !animator.isStarted) {
            animator.start()
        } else if (!play) {
            animator.cancel()
            time = WorkoutShareRenderer.DURATION - 0.01f
        }
    }

    private fun animationsEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ValueAnimator.areAnimatorsEnabled()
        } else {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = renderer ?: return
        val save = canvas.save()
        clip.reset()
        clip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)

        if (mode == Mode.CARD && background == ShareBackground.TRANSPARENT) {
            drawChecker(canvas)
        }
        val scale = width / WorkoutShareRenderer.WIDTH.toFloat()
        canvas.scale(scale, scale)
        if (mode == Mode.VIDEO) {
            r.drawVideoFrame(canvas, time, background, stats)
        } else {
            r.drawCard(canvas, background, ink, stats)
        }
        canvas.restoreToCount(save)
    }

    private fun drawChecker(canvas: Canvas) {
        val cell = 12 * resources.displayMetrics.density
        // Dark checks under light ink and the other way round, like a photo would be
        val (a, b) = if (ink == ShareInk.LIGHT) {
            Pair(Color.rgb(0x5A, 0x5F, 0x58), Color.rgb(0x4A, 0x4F, 0x48))
        } else {
            Pair(Color.rgb(0xEE, 0xEE, 0xEE), Color.rgb(0xDD, 0xDD, 0xDD))
        }
        var y = 0f
        var row = 0
        while (y < height) {
            var x = 0f
            var column = 0
            while (x < width) {
                checker.color = if ((row + column) % 2 == 0) a else b
                canvas.drawRect(x, y, x + cell, y + cell, checker)
                x += cell
                column++
            }
            y += cell
            row++
        }
    }
}
