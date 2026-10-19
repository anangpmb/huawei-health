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
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack
import nodomain.freeyourgadget.gadgetbridge.util.AndroidUtils
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.kotlin.getDevice
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream

/**
 * Share a workout as an animated route video (MP4) or as a card (PNG, optionally transparent),
 * with a live preview of exactly what will be exported.
 */
class WorkoutShareActivity : AbstractGBActivity() {

    private lateinit var device: GBDevice
    private var summaryId = -1L
    private var summary: BaseActivitySummary? = null
    private var track: ActivityTrack? = null
    private var model: WorkoutShareModel? = null
    private var mapLayers: OfflineMapLayers? = null
    private var mapJob: Job? = null
    private var modelJob: Job? = null
    private var exportJob: Job? = null
    private var pendingSave: File? = null

    private lateinit var preview: WorkoutSharePreviewView
    private lateinit var preparing: View
    private lateinit var preparingText: TextView
    private lateinit var modeGroup: MaterialButtonToggleGroup
    private lateinit var videoButton: MaterialButton
    private lateinit var modeHint: TextView
    private lateinit var backgroundGroup: ChipGroup
    private lateinit var mapChip: Chip
    private lateinit var transparentChip: Chip
    private lateinit var mapHint: TextView
    private lateinit var inkGroup: MaterialButtonToggleGroup
    private lateinit var statChips: Map<ShareStatKind, Chip>
    private lateinit var hideEnds: MaterialSwitch
    private lateinit var actions: View
    private lateinit var progressGroup: View
    private lateinit var progressText: TextView
    private lateinit var progressBar: LinearProgressIndicator

    private val saveVideo = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        uri?.let { copyPendingTo(it) }
    }
    private val saveImage = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        uri?.let { copyPendingTo(it) }
    }

    private val isVideo: Boolean get() = modeGroup.checkedButtonId == R.id.share_mode_video

    private val selectedBackground: ShareBackground
        get() = when (backgroundGroup.checkedChipId) {
            R.id.share_background_map -> ShareBackground.MAP
            R.id.share_background_transparent -> ShareBackground.TRANSPARENT
            else -> ShareBackground.PASTEL
        }

    private val selectedInk: ShareInk
        get() = if (inkGroup.checkedButtonId == R.id.share_ink_dark) ShareInk.DARK else ShareInk.LIGHT

    private val selectedStats: Set<ShareStatKind>
        get() = statChips.filter { it.value.isChecked && it.value.isEnabled }.keys

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workout_share)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val intentDevice = intent.getDevice()
        summaryId = intent.getLongExtra(EXTRA_SUMMARY_ID, -1L)
        if (intentDevice == null || summaryId < 0) {
            finish()
            return
        }
        device = intentDevice

        bindViews()
        preview.maxPreviewHeight = (resources.displayMetrics.heightPixels * 0.58f).toInt()
        mapChip.isEnabled = OfflineMapLayers.isConfigured()
        if (!mapChip.isEnabled) {
            mapHint.setText(R.string.workout_share_map_not_configured)
            mapHint.visibility = View.VISIBLE
        } else {
            mapChip.isChecked = true
        }
        updateOptionVisibility()
        loadWorkout()
    }

    private fun bindViews() {
        preview = findViewById(R.id.share_preview)
        preparing = findViewById(R.id.share_preparing)
        preparingText = findViewById(R.id.share_preparing_text)
        modeGroup = findViewById(R.id.share_mode)
        videoButton = findViewById(R.id.share_mode_video)
        modeHint = findViewById(R.id.share_mode_hint)
        backgroundGroup = findViewById(R.id.share_background)
        mapChip = findViewById(R.id.share_background_map)
        transparentChip = findViewById(R.id.share_background_transparent)
        mapHint = findViewById(R.id.share_map_hint)
        inkGroup = findViewById(R.id.share_ink)
        statChips = mapOf(
            ShareStatKind.DURATION to findViewById(R.id.share_stat_duration),
            ShareStatKind.PACE to findViewById(R.id.share_stat_pace),
            ShareStatKind.HEART_RATE to findViewById(R.id.share_stat_heart_rate),
            ShareStatKind.CALORIES to findViewById(R.id.share_stat_calories),
        )
        hideEnds = findViewById(R.id.share_hide_ends)
        actions = findViewById(R.id.share_actions)
        progressGroup = findViewById(R.id.share_progress_group)
        progressText = findViewById(R.id.share_progress_text)
        progressBar = findViewById(R.id.share_progress)

        modeGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) updateOptionVisibility()
        }
        backgroundGroup.setOnCheckedStateChangeListener { _, _ ->
            if (selectedBackground == ShareBackground.MAP) ensureMap()
            updateOptionVisibility()
        }
        inkGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) preview.ink = selectedInk
        }
        for (chip in statChips.values) {
            chip.setOnCheckedChangeListener { _, _ -> preview.stats = selectedStats }
        }
        hideEnds.setOnCheckedChangeListener { _, _ -> rebuildModel() }
        findViewById<MaterialButton>(R.id.share_share).setOnClickListener { export(share = true) }
        findViewById<MaterialButton>(R.id.share_save).setOnClickListener { export(share = false) }
        findViewById<MaterialButton>(R.id.share_cancel).setOnClickListener { exportJob?.cancel() }
    }

    /** Shows only the options that apply to the current mode and background. */
    private fun updateOptionVisibility() {
        val video = isVideo
        // A transparent video is not a thing that shares well; it is a card option
        transparentChip.visibility = if (video) View.GONE else View.VISIBLE
        if (video && selectedBackground == ShareBackground.TRANSPARENT) {
            backgroundGroup.check(R.id.share_background_pastel)
        }
        inkGroup.visibility =
            if (!video && selectedBackground == ShareBackground.TRANSPARENT) View.VISIBLE else View.GONE
        preview.mode = if (video) WorkoutSharePreviewView.Mode.VIDEO else WorkoutSharePreviewView.Mode.CARD
        preview.background = selectedBackground
        preview.ink = selectedInk
        preview.stats = selectedStats
    }

    // ---------------------------------------------------------------- data

    private fun loadWorkout() {
        showPreparing(getString(R.string.workout_share_loading))
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    val found = GBApplication.acquireDbReadOnly().use { db ->
                        db.daoSession.baseActivitySummaryDao.load(summaryId)
                    }
                    if (found == null) {
                        null
                    } else {
                        val provider = device.deviceCoordinator.getActivityTrackProvider(device, applicationContext)
                        Pair(found, provider?.getActivityTrack(found))
                    }
                } catch (e: Exception) {
                    LOG.error("Could not load workout {}", summaryId, e)
                    null
                }
            }
            if (loaded == null) {
                GB.toast(this@WorkoutShareActivity, getString(R.string.workout_share_loading_failed), Toast.LENGTH_LONG, GB.ERROR)
                finish()
                return@launch
            }
            summary = loaded.first
            track = loaded.second
            rebuildModel()
        }
    }

    /** Rebuilds what is drawn, e.g. after hiding the route ends, which also moves the map area. */
    private fun rebuildModel() {
        val currentSummary = summary ?: return
        val hide = hideEnds.isChecked
        modelJob?.cancel()
        modelJob = lifecycleScope.launch {
            val built = withContext(Dispatchers.Default) {
                WorkoutShareModel.build(applicationContext, currentSummary, track, hide)
            }
            model = built
            mapJob?.cancel()
            // Detach the old map from the preview before freeing it
            preview.renderer = WorkoutShareRenderer(this@WorkoutShareActivity, built, null)
            mapLayers?.recycle()
            mapLayers = null
            hidePreparing()

            for ((kind, chip) in statChips) {
                chip.isEnabled = built.stats.containsKey(kind)
            }
            videoButton.isEnabled = built.hasRoute
            modeHint.visibility = if (built.hasRoute) View.GONE else View.VISIBLE
            if (!built.hasRoute) {
                modeGroup.check(R.id.share_mode_card)
            }
            updateOptionVisibility()
            if (selectedBackground == ShareBackground.MAP) ensureMap()
        }
    }

    private fun ensureMap() {
        val current = model ?: return
        if (mapLayers != null || mapJob?.isActive == true) return
        val request = WorkoutShareRenderer.mapRequest(current) ?: return
        showPreparing(getString(R.string.workout_share_preparing_map))
        mapJob = lifecycleScope.launch {
            val layers = withContext(Dispatchers.IO) {
                OfflineMapLayers.render(
                    applicationContext,
                    request.detailArea, request.detailScale, request.baseArea, request.baseScale
                )
            }
            hidePreparing()
            if (model !== current) {
                layers?.recycle()
                return@launch
            }
            if (layers == null) {
                mapHint.setText(R.string.workout_share_map_unavailable)
                mapHint.visibility = View.VISIBLE
                backgroundGroup.check(R.id.share_background_pastel)
                return@launch
            }
            mapHint.visibility = View.GONE
            mapLayers = layers
            preview.renderer = WorkoutShareRenderer(this@WorkoutShareActivity, current, layers)
        }
    }

    // ---------------------------------------------------------------- export

    private fun export(share: Boolean) {
        val current = model ?: return
        if (exportJob?.isActive == true) return
        if (selectedBackground == ShareBackground.MAP && mapLayers == null && mapJob?.isActive == true) {
            // Exporting now would silently fall back to the pastel background
            GB.toast(this, getString(R.string.workout_share_preparing_map), Toast.LENGTH_SHORT, GB.INFO)
            return
        }
        val background = selectedBackground
        val ink = selectedInk
        val stats = selectedStats
        val video = isVideo && current.hasRoute
        // Its own renderer: the export runs off the main thread while the preview keeps playing
        val renderer = WorkoutShareRenderer(this, current, if (background == ShareBackground.MAP) mapLayers else null)
        val directory = File(cacheDir, SHARE_DIRECTORY).apply { mkdirs() }
        val file = File(directory, "watchlink-workout-$summaryId." + if (video) "mp4" else "png")

        exportJob = lifecycleScope.launch {
            showProgress(true, if (video) getString(R.string.workout_share_rendering_video, 0) else getString(R.string.workout_share_rendering_card))
            val ok = try {
                if (video) {
                    ShareVideoEncoder().encode(
                        file,
                        WorkoutShareRenderer.FRAME_COUNT,
                        render = { canvas, frame ->
                            renderer.drawVideoFrame(canvas, frame / WorkoutShareRenderer.FPS.toFloat(), background, stats)
                        },
                        onProgress = { p ->
                            val percent = (p * 100).toInt()
                            progressBar.post {
                                progressBar.setProgressCompat(percent, true)
                                progressText.text = getString(R.string.workout_share_rendering_video, percent)
                            }
                        }
                    )
                } else {
                    withContext(Dispatchers.Default) { writeCard(renderer, file, background, ink, stats) }
                }
                true
            } catch (e: CancellationException) {
                file.delete()
                throw e
            } catch (e: Exception) {
                LOG.error("Workout share export failed", e)
                file.delete()
                GB.toast(
                    this@WorkoutShareActivity,
                    getString(R.string.workout_share_failed, e.localizedMessage ?: e.javaClass.simpleName),
                    Toast.LENGTH_LONG, GB.ERROR
                )
                false
            } finally {
                showProgress(false, null)
            }
            if (!ok) return@launch

            if (share) {
                try {
                    AndroidUtils.shareFile(this@WorkoutShareActivity, file, if (video) "video/mp4" else "image/png")
                } catch (e: Exception) {
                    LOG.error("Sharing {} failed", file, e)
                }
            } else {
                pendingSave = file
                if (video) saveVideo.launch(file.name) else saveImage.launch(file.name)
            }
        }
    }

    private fun writeCard(renderer: WorkoutShareRenderer, file: File, background: ShareBackground, ink: ShareInk, stats: Set<ShareStatKind>) {
        val bitmap = createBitmap(WorkoutShareRenderer.WIDTH, WorkoutShareRenderer.HEIGHT)
        try {
            renderer.drawCard(Canvas(bitmap), background, ink, stats)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun copyPendingTo(uri: Uri) {
        val file = pendingSave ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } != null
                } catch (e: Exception) {
                    LOG.error("Saving {} failed", file, e)
                    false
                }
            }
            GB.toast(
                this@WorkoutShareActivity,
                getString(if (ok) R.string.workout_share_saved else R.string.workout_share_save_failed),
                Toast.LENGTH_SHORT, if (ok) GB.INFO else GB.ERROR
            )
        }
    }

    // ---------------------------------------------------------------- state

    private fun showPreparing(message: String) {
        preparingText.text = message
        preparing.visibility = View.VISIBLE
    }

    private fun hidePreparing() {
        preparing.visibility = View.GONE
    }

    private fun showProgress(visible: Boolean, message: String?) {
        progressGroup.visibility = if (visible) View.VISIBLE else View.GONE
        actions.visibility = if (visible) View.GONE else View.VISIBLE
        if (visible) {
            progressBar.setProgressCompat(0, false)
            progressText.text = message
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        exportJob?.cancel()
        mapJob?.cancel()
        modelJob?.cancel()
        if (::preview.isInitialized) {
            preview.renderer = null
        }
        mapLayers?.recycle()
        mapLayers = null
        super.onDestroy()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(WorkoutShareActivity::class.java)
        private const val EXTRA_SUMMARY_ID = "watchlink_share_summary_id"
        private const val SHARE_DIRECTORY = "workout_share"

        fun intent(context: Context, device: GBDevice, summaryId: Long): Intent =
            Intent(context, WorkoutShareActivity::class.java)
                .putExtra(GBDevice.EXTRA_DEVICE, device)
                .putExtra(EXTRA_SUMMARY_ID, summaryId)
    }
}
