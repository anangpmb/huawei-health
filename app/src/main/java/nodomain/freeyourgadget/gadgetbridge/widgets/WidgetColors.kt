package nodomain.freeyourgadget.gadgetbridge.widgets

import android.graphics.Color
import androidx.annotation.ColorInt

/**
 * Shared palette for the dashboard widgets.
 *
 * WatchLink: softened to the pastel "Soft" palette (see mainline colors_watchlink.xml).
 */
object WidgetColors {
    @ColorInt
    val unknown: Int = Color.argb(25, 128, 128, 128)

    @ColorInt
    val notWorn: Int = Color.rgb(0x6F, 0x6A, 0x5F)

    @ColorInt
    val worn: Int = Color.rgb(0xCF, 0xC8, 0xBA)

    @ColorInt
    val activity: Int = Color.rgb(0x8D, 0xB3, 0x7A)

    @ColorInt
    val exercise: Int = Color.rgb(0xEE, 0x9A, 0x5B)

    @ColorInt
    val deepSleep: Int = Color.rgb(0x4E, 0x62, 0xA6)

    @ColorInt
    val lightSleep: Int = Color.rgb(0x9B, 0xB7, 0xE6)

    @ColorInt
    val remSleep: Int = Color.rgb(0xD5, 0x8B, 0xC0)

    @ColorInt
    val awakeSleep: Int = Color.rgb(0xF3, 0xB7, 0x76)

    @ColorInt
    val distance: Int = Color.rgb(0x6F, 0xA0, 0xD6)

    @ColorInt
    val activeTime: Int = Color.rgb(0xA5, 0x8B, 0xD6)
}
