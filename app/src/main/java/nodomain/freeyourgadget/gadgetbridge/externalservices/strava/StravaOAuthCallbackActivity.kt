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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity
import nodomain.freeyourgadget.gadgetbridge.activities.externalservices.OnlineFitnessTrackersPreferencesActivity
import org.slf4j.LoggerFactory

/**
 * Receives the Strava authorization redirect ([StravaApiClient.REDIRECT_URI]), exchanges the code
 * for tokens and returns to the "Online fitness trackers" settings.
 */
class StravaOAuthCallbackActivity : AbstractGBActivity() {

    private val LOG = LoggerFactory.getLogger(StravaOAuthCallbackActivity::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data
        if (uri == null) {
            LOG.error("No URI in Strava OAuth callback")
            finishWithMessage(getString(R.string.strava_connect_failed, getString(R.string.unknown_error)))
            return
        }
        handleCallback(uri)
    }

    private fun handleCallback(uri: Uri) {
        val tokens = StravaTokenManager(this)
        if (!tokens.consumeOAuthState(uri.getQueryParameter("state"))) {
            LOG.error("Strava OAuth callback with unexpected state, ignoring")
            finishWithMessage(getString(R.string.strava_connect_failed, getString(R.string.unknown_error)))
            return
        }

        val error = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")
        if (error != null || code == null) {
            LOG.warn("Strava authorization not granted: {}", error)
            finishWithMessage(getString(R.string.strava_connect_denied))
            return
        }

        // The user can untick scopes on the consent screen; without activity:write nothing uploads.
        val grantedScopes = uri.getQueryParameter("scope")?.split(",") ?: emptyList()
        if ("activity:write" !in grantedScopes) {
            finishWithMessage(getString(R.string.strava_scope_missing))
            return
        }

        lifecycleScope.launch {
            val failure = withContext(Dispatchers.IO) {
                StravaApiClient(applicationContext, tokens).exchangeCode(code)
            }
            if (failure == null) {
                LOG.info("Connected to Strava")
                finishWithMessage(getString(R.string.strava_connected_toast))
            } else {
                LOG.warn("Strava token exchange failed: {}", failure)
                finishWithMessage(getString(R.string.strava_connect_failed, failure))
            }
        }
    }

    private fun finishWithMessage(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        // Back to the settings screen, which refreshes its Strava status in onResume.
        val settingsIntent = Intent(applicationContext, OnlineFitnessTrackersPreferencesActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(settingsIntent)
        finish()
    }
}
