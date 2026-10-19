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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.GB

/**
 * Wires the Strava category of the "Online fitness trackers" settings: the user's own API
 * application credentials, connecting through Strava's OAuth, and disconnecting.
 */
class StravaPreferencesController(private val fragment: PreferenceFragmentCompat) {
    companion object {
        private const val PREF_STATUS = "pref_key_strava_status"
        private const val PREF_CONNECT = "pref_key_strava_connect"
        private const val PREF_DISCONNECT = "pref_key_strava_disconnect"
    }

    fun attach() {
        fragment.findPreference<Preference>(PREF_CONNECT)?.setOnPreferenceClickListener {
            showCredentialsDialog()
            true
        }
        fragment.findPreference<Preference>(PREF_DISCONNECT)?.setOnPreferenceClickListener {
            disconnect()
            true
        }
        refresh()
    }

    fun refresh() {
        if (!fragment.isAdded) {
            return
        }
        val context = fragment.requireContext()
        val tokens = StravaTokenManager(context)
        val connected = tokens.isLoggedIn()

        val athleteName = tokens.getAthleteName()
        fragment.findPreference<Preference>(PREF_STATUS)?.summary = when {
            !connected -> context.getString(R.string.strava_status_not_connected)
            athleteName.isNullOrBlank() -> context.getString(R.string.strava_status_connected_anonymous)
            else -> context.getString(R.string.strava_status_connected, athleteName)
        }
        fragment.findPreference<Preference>(PREF_CONNECT)?.isVisible = !connected
        fragment.findPreference<Preference>(PREF_DISCONNECT)?.isVisible = connected
        // Auto-upload only makes sense once connected, as for the other trackers.
        fragment.findPreference<Preference>(StravaUploadTarget.PREF_AUTO_UPLOAD_ENABLED)?.isEnabled = connected
    }

    private fun showCredentialsDialog() {
        val context = fragment.requireContext()
        val tokens = StravaTokenManager(context)
        val padding = (20 * context.resources.displayMetrics.density).toInt()

        val clientIdInput = EditText(context).apply {
            setHint(R.string.strava_client_id_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(tokens.getClientId().orEmpty())
        }
        val clientSecretInput = EditText(context).apply {
            setHint(R.string.strava_client_secret_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(tokens.getClientSecret().orEmpty())
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(clientIdInput)
            addView(clientSecretInput)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.strava_credentials_title)
            .setMessage(R.string.strava_credentials_message)
            .setView(layout)
            .setPositiveButton(R.string.pref_strava_connect_title) { _, _ ->
                val clientId = clientIdInput.text.toString().trim()
                val clientSecret = clientSecretInput.text.toString().trim()
                if (clientId.isEmpty() || clientSecret.isEmpty()) {
                    GB.toast(context.getString(R.string.strava_credentials_missing), Toast.LENGTH_SHORT, GB.WARN)
                    return@setPositiveButton
                }
                tokens.saveClientCredentials(clientId, clientSecret)
                startAuthorization(tokens)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Opens Strava's consent screen: the Strava app when installed, the browser otherwise. */
    private fun startAuthorization(tokens: StravaTokenManager) {
        val context = fragment.requireContext()
        val uri = StravaApiClient(context, tokens).authorizeUri(tokens.newOAuthState()) ?: return
        try {
            fragment.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            GB.toast(context.getString(R.string.strava_no_browser), Toast.LENGTH_LONG, GB.WARN)
        }
    }

    private fun disconnect() {
        val appContext = fragment.requireContext().applicationContext
        fragment.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val tokens = StravaTokenManager(appContext)
                StravaApiClient(appContext, tokens).revoke()
                tokens.clearTokens()
            }
            GB.toast(appContext.getString(R.string.strava_disconnected_toast), Toast.LENGTH_SHORT, GB.INFO)
            refresh()
        }
    }
}
