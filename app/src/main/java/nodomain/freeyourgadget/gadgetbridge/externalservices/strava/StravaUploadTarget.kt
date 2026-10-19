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
package nodomain.freeyourgadget.gadgetbridge.externalservices.strava

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.RemoteLookup
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.TrackUpdateResult
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutPayloadFormat
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploadStore
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploadTarget
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploader
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.entities.WorkoutUpload
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import nodomain.freeyourgadget.gadgetbridge.watchlink.DemoMode
import java.io.File

/**
 * Strava as an online fitness tracker. Workouts are uploaded as FIT, which carries the sport,
 * heart rate and GPS track, so Strava needs nothing else from the summary.
 */
object StravaUploadTarget : WorkoutUploadTarget {
    /** Key of the auto-upload switch in the "Online fitness trackers" settings. */
    const val PREF_AUTO_UPLOAD_ENABLED = "strava_auto_upload_enabled"

    override val service = WorkoutUploadStore.SERVICE_STRAVA
    override val nameRes = R.string.pref_category_strava
    override val uploadMenuItemRes = R.id.activity_action_upload_to_strava
    override val format = WorkoutPayloadFormat.FIT

    // A FIT can be synthesized from the summary alone; Strava records it as an indoor activity.
    override val requiresTrack = false

    override val failureNotificationId = 4790

    override fun isLoggedIn(context: Context) = StravaTokenManager(context).isLoggedIn()

    override fun isAutoUploadEnabled(prefs: GBPrefs) =
        prefs.getBoolean(PREF_AUTO_UPLOAD_ENABLED, false)

    override fun failureMessage(context: Context, reason: String): String =
        context.getString(R.string.strava_upload_failed, reason)

    override val uploadedRes = R.string.strava_successfully_uploaded

    override fun uploadFailedMessage(context: Context, reason: String?): String =
        if (reason != null) {
            context.getString(R.string.strava_upload_failed, reason)
        } else {
            context.getString(R.string.strava_upload_failed_generic)
        }

    override fun activityUrl(context: Context, remoteActivityId: String): String =
        StravaApiClient.activityUrl(remoteActivityId)

    override fun upload(context: Context, summary: BaseActivitySummary, payload: File): WorkoutUploader.UploadResult {
        if (DemoMode.isDemoWorkout(summary)) {
            return WorkoutUploader.UploadResult(false, null, context.getString(R.string.watchlink_demo_no_upload))
        }
        return StravaApiClient(context).upload(summary, payload)
    }

    /**
     * Only a name the user set locally is pushed; otherwise Strava's own naming is kept. The kind
     * is left alone because it came from the FIT and the user may have corrected it on Strava.
     */
    override fun updateMetadata(context: Context, remoteActivityId: String, summary: BaseActivitySummary): Boolean {
        val name = summary.name ?: return true
        return StravaApiClient(context).updateName(remoteActivityId, name)
    }

    // The API has no way to replace the file behind an existing activity.
    override fun updateTrack(
        context: Context,
        remoteActivityId: String,
        payload: File,
        summary: BaseActivitySummary,
        row: WorkoutUpload
    ) = TrackUpdateResult(success = false, unsupported = true)

    // Third-party apps cannot upload photos to Strava.
    override fun syncPhoto(
        context: Context,
        remoteActivityId: String,
        photoFile: File?,
        previousMediaId: Int?
    ): WorkoutUploader.PhotoSyncResult? = null

    // A private activity is invisible with the requested scopes, so a 404 would not prove the
    // activity is gone; a re-upload is caught by Strava's duplicate check anyway.
    override fun lookupRemote(context: Context, remoteActivityId: String): RemoteLookup? = null
}
