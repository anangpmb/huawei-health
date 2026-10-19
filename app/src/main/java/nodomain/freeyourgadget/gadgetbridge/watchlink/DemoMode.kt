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
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.DeviceDeleteActivity
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceManager
import nodomain.freeyourgadget.gadgetbridge.devices.test.TestDeviceConst
import nodomain.freeyourgadget.gadgetbridge.devices.test.TestFeature
import nodomain.freeyourgadget.gadgetbridge.devices.test.activity.TestActivitySummaryParser
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession
import nodomain.freeyourgadget.gadgetbridge.entities.Device
import nodomain.freeyourgadget.gadgetbridge.entities.User
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory
import java.util.Calendar
import java.util.Date

/**
 * Demo mode: shows WatchLink with sample data and no watch. It adds Gadgetbridge's simulated test
 * device, limited to what a Huawei watch offers, plus a couple of weeks of workouts with GPS
 * routes. The simulated device "connects" without Bluetooth and generates its samples on demand,
 * so the dashboard, charts, workouts and the share video all run through their real code paths.
 *
 * Turning it off deletes the demo device together with all of its data. Demo workouts are never
 * uploaded to online trackers.
 */
object DemoMode {
    private val LOG = LoggerFactory.getLogger(DemoMode::class.java)

    /** Locally administered address, so it can never be a real watch. */
    const val DEMO_ADDRESS = "02:57:4C:00:DE:40"

    /** What a Huawei watch supports in WatchLink; everything else stays off. */
    private val FEATURES: Set<String> = setOf(
        TestFeature.ACTIVE_CALORIES,
        TestFeature.ACTIVITY_DATA_FETCHING,
        TestFeature.ACTIVITY_DISTANCE,
        TestFeature.ACTIVITY_TRACKING,
        TestFeature.AWAKE_SLEEP,
        TestFeature.CHARTS,
        TestFeature.CONTINUOUS_TEMPERATURE,
        TestFeature.HEART_RATE_MEASUREMENT,
        TestFeature.HEART_RATE_RESTING,
        TestFeature.HEART_RATE_STATS,
        TestFeature.HRV_MEASUREMENT,
        TestFeature.MANUAL_HEART_RATE_MEASUREMENT,
        TestFeature.MUSIC_INFO,
        TestFeature.RECORDED_ACTIVITIES,
        TestFeature.REM_SLEEP,
        TestFeature.SLEEP_MEASUREMENT,
        TestFeature.SLEEP_SCORE,
        TestFeature.SPEEDZONES,
        TestFeature.SPO2,
        TestFeature.STEP_COUNTER,
        TestFeature.STRESS_MEASUREMENT,
        TestFeature.TEMPERATURE_MEASUREMENT,
        TestFeature.VO2_MAX,
    ).map { it.name }.toSet()

    private class DemoWorkout(val kind: ActivityKind, val daysAgo: Int, val hour: Int, val minutes: Int)

    /** Spread over the last two weeks, in the window the upload worker would look at. */
    private val WORKOUTS = listOf(
        DemoWorkout(ActivityKind.RUNNING, 1, 6, 42),
        DemoWorkout(ActivityKind.WALKING, 2, 18, 35),
        DemoWorkout(ActivityKind.CYCLING, 4, 7, 75),
        DemoWorkout(ActivityKind.RUNNING, 6, 6, 28),
        DemoWorkout(ActivityKind.HIKING, 8, 8, 110),
        DemoWorkout(ActivityKind.SWIMMING, 10, 19, 40),
        DemoWorkout(ActivityKind.RUNNING, 12, 6, 51),
    )

    @JvmStatic
    fun demoDevice(): GBDevice? = GBApplication.app()?.deviceManager?.getDeviceByAddress(DEMO_ADDRESS)

    @JvmStatic
    fun isEnabled(): Boolean = demoDevice() != null

    @JvmStatic
    fun isDemo(device: GBDevice?): Boolean = device?.address.equals(DEMO_ADDRESS, ignoreCase = true)

    @JvmStatic
    fun isDemoAddress(address: String?): Boolean = address.equals(DEMO_ADDRESS, ignoreCase = true)

    /** Whether [summary] belongs to the demo device. False when that cannot be told. */
    @JvmStatic
    fun isDemoWorkout(summary: BaseActivitySummary): Boolean = try {
        isDemoAddress(summary.device?.identifier)
    } catch (e: Exception) {
        // A summary detached from its DAO session cannot resolve its device
        false
    }

    /** The drawer entry: explains demo mode, then turns it on or off. */
    @JvmStatic
    fun showDialog(activity: AppCompatActivity) {
        val enabled = isEnabled()
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.watchlink_demo_title)
            .setMessage(if (enabled) R.string.watchlink_demo_message_disable else R.string.watchlink_demo_message_enable)
            .setPositiveButton(if (enabled) R.string.watchlink_demo_disable else R.string.watchlink_demo_enable) { _, _ ->
                if (enabled) disable(activity) else enable(activity)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun enable(activity: AppCompatActivity) {
        val context = activity.applicationContext
        activity.lifecycleScope.launch {
            val created = withContext(Dispatchers.IO) { createDevice(context) }
            if (!created) {
                GB.toast(context, context.getString(R.string.watchlink_demo_failed), Toast.LENGTH_LONG, GB.ERROR)
                return@launch
            }
            LocalBroadcastManager.getInstance(context).sendBroadcast(Intent(DeviceManager.ACTION_REFRESH_DEVICELIST))

            // The device list reloads asynchronously; connect once the demo device is in it
            var device = demoDevice()
            var attempts = 0
            while (device == null && attempts < 30) {
                delay(100)
                device = demoDevice()
                attempts++
            }
            device?.let { GBApplication.deviceService(it).connect() }
            GB.toast(context, context.getString(R.string.watchlink_demo_enabled), Toast.LENGTH_LONG, GB.INFO)
        }
    }

    private fun disable(activity: AppCompatActivity) {
        val device = demoDevice() ?: return
        // The regular delete flow disconnects it and removes its samples and workouts
        activity.startActivity(
            Intent(activity, DeviceDeleteActivity::class.java)
                .putExtra(DeviceDeleteActivity.EXTRA_DEVICE, device)
                .putExtra(DeviceDeleteActivity.EXTRA_DELETE_FILES, true)
        )
    }

    private fun createDevice(context: Context): Boolean {
        return try {
            GBApplication.getDeviceSpecificSharedPrefs(DEMO_ADDRESS)
                ?.edit()
                ?.putStringSet(TestDeviceConst.PREF_TEST_FEATURES, FEATURES)
                ?.apply()

            GBApplication.acquireDB().use { db ->
                val session = db.daoSession
                val gbDevice = GBDevice(
                    DEMO_ADDRESS, context.getString(R.string.watchlink_demo_device_name), "", null, DeviceType.TEST
                )
                gbDevice.firmwareVersion = "1.0.0"
                gbDevice.firmwareVersion2 = "N/A"
                gbDevice.setState(GBDevice.State.INITIALIZED)
                val device = DBHelper.getDevice(gbDevice, session)
                val user = DBHelper.getUser(session)
                insertWorkouts(session, device, user)
            }
            true
        } catch (e: Exception) {
            LOG.error("Could not create the demo device", e)
            false
        }
    }

    private fun insertWorkouts(session: DaoSession, device: Device, user: User) {
        val parser = TestActivitySummaryParser()
        val now = Calendar.getInstance()
        val summaries = WORKOUTS.map { workout ->
            val start = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, -workout.daysAgo)
                set(Calendar.HOUR_OF_DAY, workout.hour)
                set(Calendar.MINUTE, 12)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val summary = BaseActivitySummary()
            summary.startTime = start.time
            summary.endTime = Date(start.timeInMillis + workout.minutes * 60_000L)
            summary.device = device
            summary.user = user
            summary.activityKind = workout.kind.code
            // Fills the summary data the test track provider and the stats rely on
            parser.parseBinaryData(summary, false)
            summary
        }
        session.baseActivitySummaryDao.insertOrReplaceInTx(summaries)
    }
}
