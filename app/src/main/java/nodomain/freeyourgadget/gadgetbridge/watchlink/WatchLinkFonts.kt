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
import android.content.res.Resources
import android.graphics.Typeface
import androidx.annotation.FontRes
import androidx.core.content.res.ResourcesCompat
import nodomain.freeyourgadget.gadgetbridge.R

/**
 * Poppins typefaces for text drawn in code (charts, stat tiles), matching the XML type scale.
 * ResourcesCompat caches loaded fonts, so these are cheap to call repeatedly.
 */
object WatchLinkFonts {
    @JvmStatic
    fun regular(context: Context): Typeface = load(context, R.font.poppins_regular, Typeface.DEFAULT)

    @JvmStatic
    fun medium(context: Context): Typeface =
        load(context, R.font.poppins_medium, Typeface.create("sans-serif-medium", Typeface.NORMAL))

    @JvmStatic
    fun semiBold(context: Context): Typeface = load(context, R.font.poppins_semibold, Typeface.DEFAULT_BOLD)

    /** Instrument Serif, the display face for big numbers in shared workout media. */
    @JvmStatic
    fun display(context: Context): Typeface = load(context, R.font.instrument_serif_regular, Typeface.SERIF)

    @JvmStatic
    fun displayItalic(context: Context): Typeface =
        load(context, R.font.instrument_serif_italic, Typeface.create(Typeface.SERIF, Typeface.ITALIC))

    private fun load(context: Context, @FontRes font: Int, fallback: Typeface): Typeface =
        try {
            ResourcesCompat.getFont(context, font) ?: fallback
        } catch (e: Resources.NotFoundException) {
            fallback
        }
}
