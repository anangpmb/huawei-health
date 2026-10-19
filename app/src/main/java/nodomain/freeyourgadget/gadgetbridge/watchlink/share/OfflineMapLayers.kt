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
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.util.maps.MapDataLoader
import nodomain.freeyourgadget.gadgetbridge.util.maps.MapsManager
import org.mapsforge.core.graphics.TileBitmap
import org.mapsforge.core.model.Tile
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.layer.renderer.DatabaseRenderer
import org.mapsforge.map.layer.renderer.RendererJob
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture
import org.slf4j.LoggerFactory
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/** An area in world pixels (see [WorkoutShareModel.WORLD_ZOOM]). */
class WorldRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top

    fun union(other: WorldRect) = WorldRect(
        minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom)
    )

    fun inflate(fraction: Double): WorldRect {
        val dx = width * fraction
        val dy = height * fraction
        return WorldRect(left - dx, top - dy, right + dx, bottom + dy)
    }
}

/** A map image aligned to the world: world = origin + image pixel * [worldPerPixel]. */
class MapImage(val bitmap: Bitmap, val originX: Double, val originY: Double, val worldPerPixel: Double)

/**
 * The offline (mapsforge) map under a shared workout: a detailed layer around the route, a coarse
 * base layer for everything else on screen, and a blurred copy of the base for frosted panels.
 * Rendered once per share, then drawn with a matrix on every frame.
 */
class OfflineMapLayers(val base: MapImage, val detail: MapImage, val blurredBase: MapImage) {

    fun recycle() {
        base.bitmap.recycle()
        detail.bitmap.recycle()
        blurredBase.bitmap.recycle()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(OfflineMapLayers::class.java)
        private const val TILE = WorkoutShareModel.TILE_SIZE
        private const val MIN_ZOOM = 4
        private const val MAX_ZOOM = 19
        private const val MAX_TILES = 220
        private const val MAX_SIDE = 2600
        private const val BLUR_FACTOR = 10

        /** Whether the user picked a folder of offline maps; rendering may still find no usable file. */
        fun isConfigured(): Boolean =
            GBApplication.getPrefs().getString(MapsManager.PREF_MAPS_FOLDER, "").isNotEmpty()

        /**
         * Renders both layers. [detailScale] and [baseScale] are the screen pixels per world pixel
         * each layer should roughly match. Null when no map file is available or nothing covers
         * the area. Blocking and slow (one mapsforge job per tile): call off the main thread.
         */
        fun render(
            context: Context,
            detailArea: WorldRect,
            detailScale: Double,
            baseArea: WorldRect,
            baseScale: Double,
        ): OfflineMapLayers? {
            val app = context.applicationContext
            val loader = MapDataLoader(app)
            val loaded = loader.loadMultiMapDataStore()
            if (!loaded.anyLoaded) {
                loaded.dataStore.close()
                return null
            }

            AndroidGraphicFactory.createInstance(app as GBApplication)
            val displayModel = DisplayModel().apply { setFixedTileSize(TILE) }
            val theme = RenderThemeFuture(AndroidGraphicFactory.INSTANCE, loader.resolveTheme(), displayModel)
            theme.run()
            val renderer = DatabaseRenderer(loaded.dataStore, AndroidGraphicFactory.INSTANCE, null, null, false, false, null)

            try {
                val base = renderLayer(renderer, loaded.dataStore, theme, displayModel, baseArea, baseScale)
                    ?: return null
                val detail = renderLayer(renderer, loaded.dataStore, theme, displayModel, detailArea, detailScale)
                if (detail == null) {
                    base.bitmap.recycle()
                    return null
                }
                // Downscaling then drawing back up with filtering reads as a soft blur
                val small = base.bitmap.scale(
                    max(1, base.bitmap.width / BLUR_FACTOR),
                    max(1, base.bitmap.height / BLUR_FACTOR),
                    true
                )
                val blurred = MapImage(
                    small, base.originX, base.originY,
                    base.worldPerPixel * base.bitmap.width / small.width
                )
                return OfflineMapLayers(base, detail, blurred)
            } catch (e: Exception) {
                LOG.error("Failed to render the offline map", e)
                return null
            } finally {
                theme.decrementRefCount()
                loaded.dataStore.close()
            }
        }

        private fun renderLayer(
            renderer: DatabaseRenderer,
            store: MultiMapDataStore,
            theme: RenderThemeFuture,
            displayModel: DisplayModel,
            area: WorldRect,
            scale: Double,
        ): MapImage? {
            // One image pixel should be about 1/scale world pixels: 2^(WORLD_ZOOM - zoom) = 1/scale
            var zoom = (WorkoutShareModel.WORLD_ZOOM + ceil(ln(scale) / ln(2.0))).toInt().coerceIn(MIN_ZOOM, MAX_ZOOM)
            var worldPerPixel = 1.0
            var width = 0
            var height = 0
            while (true) {
                worldPerPixel = 2.0.pow(WorkoutShareModel.WORLD_ZOOM - zoom)
                width = ceil(area.width / worldPerPixel).toInt()
                height = ceil(area.height / worldPerPixel).toInt()
                val tiles = (width / TILE + 2) * (height / TILE + 2)
                if ((max(width, height) <= MAX_SIDE && tiles <= MAX_TILES) || zoom <= MIN_ZOOM) break
                zoom--
            }
            if (width <= 0 || height <= 0) return null

            val bitmap = createBitmap(width, height, Bitmap.Config.RGB_565)
            bitmap.eraseColor(Color.rgb(0xF2, 0xEF, 0xE9))
            val canvas = Canvas(bitmap)
            val originX = area.left / worldPerPixel
            val originY = area.top / worldPerPixel
            val tilesPerSide = 1 shl zoom
            val txMin = floor(originX / TILE).toInt().coerceAtLeast(0)
            val txMax = floor((originX + width) / TILE).toInt().coerceAtMost(tilesPerSide - 1)
            val tyMin = floor(originY / TILE).toInt().coerceAtLeast(0)
            val tyMax = floor((originY + height) / TILE).toInt().coerceAtMost(tilesPerSide - 1)

            var drewAny = false
            for (tx in txMin..txMax) {
                for (ty in tyMin..tyMax) {
                    val tile = Tile(tx, ty, zoom.toByte(), TILE)
                    val job = RendererJob(tile, store, theme, displayModel, 1f, false, false)
                    val tileBitmap: TileBitmap = renderer.executeJob(job) ?: continue
                    val androidBitmap = AndroidGraphicFactory.getBitmap(tileBitmap)
                    canvas.drawBitmap(androidBitmap, (tx * TILE - originX).toFloat(), (ty * TILE - originY).toFloat(), null)
                    drewAny = true
                }
            }
            if (!drewAny) {
                bitmap.recycle()
                return null
            }
            return MapImage(bitmap, area.left, area.top, worldPerPixel)
        }
    }
}
