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
import android.net.Uri
import android.util.Base64
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutUploader
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.util.InternetUtils
import nodomain.freeyourgadget.gadgetbridge.util.StringResponse
import org.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLEncoder

/**
 * Blocking client for the parts of the Strava API that WatchLink uses: OAuth, FIT upload and
 * renaming an activity. Call from an IO context.
 *
 * See https://developers.strava.com/docs/authentication/ and https://developers.strava.com/docs/uploads/
 */
class StravaApiClient(
    private val context: Context,
    private val tokens: StravaTokenManager = StravaTokenManager(context)
) {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(StravaApiClient::class.java)

        /**
         * Must match the "Authorization Callback Domain" of the user's Strava API application
         * (`localhost`) and the intent filter of [StravaOAuthCallbackActivity].
         */
        const val REDIRECT_URI = "watchlink://localhost"
        const val SCOPE = "activity:write,read"

        private const val AUTHORIZE_URL = "https://www.strava.com/oauth/mobile/authorize"
        private const val TOKEN_URL = "https://www.strava.com/oauth/token"
        private const val REVOKE_URL = "https://www.strava.com/oauth/revoke"
        private const val DEAUTHORIZE_URL = "https://www.strava.com/oauth/deauthorize"
        private const val API_URL = "https://www.strava.com/api/v3"

        /** Refresh this long before the access token expires (Strava issues them for 6 hours). */
        private const val TOKEN_EXPIRY_MARGIN_SECONDS = 5 * 60

        /** Strava processes uploads asynchronously; poll this often, this many times. */
        private const val POLL_INTERVAL_MS = 3000L
        private const val MAX_POLLS = 10

        /** Matches both "duplicate of activity 123" and "duplicate of <a href='/activities/123'>". */
        private val DUPLICATE_REGEX = Regex("duplicate of .*?(?:activities/|activity )(\\d+)", RegexOption.IGNORE_CASE)
        private val HTML_TAG_REGEX = Regex("<[^>]*>")

        fun activityUrl(activityId: String) = "https://www.strava.com/activities/$activityId"
    }

    /** The authorization URL to open, or null when no client ID has been entered. */
    fun authorizeUri(state: String): Uri? {
        val clientId = tokens.getClientId() ?: return null
        return Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("approval_prompt", "auto")
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("state", state)
            .build()
    }

    /** Exchanges an authorization code for tokens. Returns null on success, else a localized reason. */
    fun exchangeCode(code: String): String? {
        val clientId = tokens.getClientId()
        val clientSecret = tokens.getClientSecret()
        if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank()) {
            return context.getString(R.string.strava_error_not_connected)
        }

        var networkReason: String? = null
        val response = postForm(
            TOKEN_URL,
            mapOf(
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "code" to code,
                "grant_type" to "authorization_code"
            )
        ) { networkReason = it }

        val body = response.body
        if (response.statusCode != 200 || body == null) {
            return networkReason ?: httpError(response)
        }
        return try {
            tokens.saveTokens(JSONObject(body))
            null
        } catch (e: Exception) {
            LOG.error("Unexpected Strava token response", e)
            context.getString(R.string.toast_error_unexpected_response)
        }
    }

    /** A usable access token, refreshing it first when it is about to expire. Null when not connected. */
    fun validAccessToken(): String? {
        return synchronized(StravaTokenManager.REFRESH_LOCK) { refreshIfNeeded() }
    }

    private fun refreshIfNeeded(): String? {
        val accessToken = tokens.getAccessToken()
        val nowSeconds = System.currentTimeMillis() / 1000
        if (accessToken != null && tokens.getExpiresAt() - nowSeconds > TOKEN_EXPIRY_MARGIN_SECONDS) {
            return accessToken
        }

        val refreshToken = tokens.getRefreshToken() ?: return null
        val clientId = tokens.getClientId() ?: return null
        val clientSecret = tokens.getClientSecret() ?: return null
        val response = postForm(
            TOKEN_URL,
            mapOf(
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken
            )
        )
        val body = response.body
        if (response.statusCode != 200 || body == null) {
            LOG.warn("Strava token refresh failed with status {}", response.statusCode)
            return null
        }
        return try {
            tokens.saveTokens(JSONObject(body))
            tokens.getAccessToken()
        } catch (e: Exception) {
            LOG.error("Unexpected Strava refresh response", e)
            null
        }
    }

    /**
     * Uploads [payload] (a FIT file) and waits for Strava to turn it into an activity. A workout
     * that Strava already holds counts as uploaded, with the id of the existing activity.
     */
    fun upload(summary: BaseActivitySummary, payload: File): WorkoutUploader.UploadResult {
        val token = validAccessToken()
            ?: return failure(context.getString(R.string.strava_error_not_connected))

        val fields = mutableMapOf(
            "data_type" to "fit",
            "external_id" to "watchlink-${summary.id}-${summary.startTime.time / 1000}"
        )
        // Without a user-set name Strava names the activity itself ("Morning Run"), which beats
        // a bare activity-kind label.
        summary.name?.let { fields["name"] = it }

        var uploaded = false
        var statusCode: Int? = null
        var responseText: String? = null
        var networkReason: String? = null
        InternetUtils.uploadBinaryFile(
            uri = Uri.parse("$API_URL/uploads"),
            file = payload,
            requestHeaders = mapOf("Authorization" to "Bearer $token"),
            formFields = fields
        ) { success, code, response, reason ->
            uploaded = success
            statusCode = code
            responseText = response
            networkReason = reason
        }

        if (!uploaded) {
            return failure(networkReason)
        }
        val body = responseText
        if ((statusCode != 201 && statusCode != 200) || body == null) {
            return failure(httpError(StringResponse(statusCode, body)))
        }
        return try {
            awaitActivity(token, JSONObject(body))
        } catch (e: Exception) {
            LOG.error("Unexpected Strava upload response", e)
            failure(context.getString(R.string.toast_error_unexpected_response))
        }
    }

    /** Polls an upload until Strava reports the activity it created, or an error. */
    private fun awaitActivity(token: String, initial: JSONObject): WorkoutUploader.UploadResult {
        var upload = initial
        repeat(MAX_POLLS) {
            resolveUpload(upload)?.let { return it }
            val uploadId = upload.optLong("id", -1L)
            if (uploadId < 0) {
                return failure(context.getString(R.string.toast_error_unexpected_response))
            }
            Thread.sleep(POLL_INTERVAL_MS)
            val response = InternetUtils.doStringRequestWithStatus(
                Uri.parse("$API_URL/uploads/$uploadId"),
                "GET",
                mapOf("Authorization" to "Bearer $token")
            )
            val body = response.body
            if (response.statusCode != 200 || body == null) {
                return failure(httpError(response))
            }
            upload = JSONObject(body)
        }
        // Not an error on Strava's side: the next run uploads again, and the duplicate check then
        // hands back the finished activity.
        return resolveUpload(upload)
            ?: failure(context.getString(R.string.strava_error_still_processing))
    }

    /** The final result of an upload, or null while Strava is still processing it. */
    private fun resolveUpload(upload: JSONObject): WorkoutUploader.UploadResult? {
        val activityId = upload.optLong("activity_id", 0L)
        if (activityId > 0) {
            return WorkoutUploader.UploadResult(true, activityId.toString(), null)
        }
        val error = if (upload.isNull("error")) null else upload.optString("error")
        if (!error.isNullOrBlank()) {
            DUPLICATE_REGEX.find(error)?.let { match ->
                LOG.info("Workout is already on Strava as activity {}", match.groupValues[1])
                return WorkoutUploader.UploadResult(true, match.groupValues[1], null)
            }
            return failure(error.replace(HTML_TAG_REGEX, "").trim())
        }
        return null
    }

    /** Renames an existing activity. */
    fun updateName(activityId: String, name: String): Boolean {
        val token = validAccessToken() ?: return false
        val response = InternetUtils.doStringRequestWithStatus(
            Uri.parse("$API_URL/activities/$activityId"),
            "PUT",
            mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"),
            JSONObject().put("name", name).toString()
        )
        if (response.statusCode != 200) {
            LOG.warn("Renaming Strava activity {} failed with status {}", activityId, response.statusCode)
        }
        return response.statusCode == 200
    }

    /** Revokes WatchLink's access on Strava. Best-effort: the local tokens are cleared regardless. */
    fun revoke() {
        val clientId = tokens.getClientId()
        val clientSecret = tokens.getClientSecret()
        val refreshToken = tokens.getRefreshToken()
        if (clientId != null && clientSecret != null && refreshToken != null) {
            val basic = Base64.encodeToString("$clientId:$clientSecret".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val response = postForm(
                REVOKE_URL,
                mapOf("token" to refreshToken, "token_type_hint" to "refresh_token"),
                mapOf("Authorization" to "Basic $basic")
            )
            if (response.statusCode == 200) {
                return
            }
            LOG.warn("Strava revoke failed with status {}, trying legacy deauthorize", response.statusCode)
        }
        // The legacy endpoint is supported until June 2027.
        val accessToken = tokens.getAccessToken() ?: return
        postForm(DEAUTHORIZE_URL, mapOf("access_token" to accessToken))
    }

    private fun postForm(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        onError: (reason: String) -> Unit = {}
    ): StringResponse {
        val body = fields.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        return InternetUtils.doStringRequestWithStatus(
            Uri.parse(url),
            "POST",
            headers + ("Content-Type" to "application/x-www-form-urlencoded"),
            body,
            false,
            onError
        )
    }

    private fun httpError(response: StringResponse): String {
        val statusCode = response.statusCode
            ?: return InternetUtils.networkFailureReason(null)
        return when (statusCode) {
            401 -> context.getString(R.string.strava_error_unauthorized)
            429 -> context.getString(R.string.strava_error_rate_limited)
            else -> {
                val message = try {
                    response.body?.let { JSONObject(it).optString("message") }
                } catch (e: Exception) {
                    null
                }
                context.getString(R.string.strava_error_http, statusCode, message ?: "")
            }
        }
    }

    private fun failure(reason: String?) = WorkoutUploader.UploadResult(
        false,
        null,
        reason ?: context.getString(R.string.strava_upload_failed_generic)
    )
}
