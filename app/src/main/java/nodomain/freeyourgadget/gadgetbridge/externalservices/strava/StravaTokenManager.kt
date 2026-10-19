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
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * Stores the user's own Strava API application credentials and the OAuth tokens of the connected
 * athlete. The client secret is the user's, entered on the device, and never part of the source.
 */
// FIXME MasterKey/EncryptedSharedPreferences is deprecated - same as WandererTokenManager
@Suppress("DEPRECATION")
class StravaTokenManager(context: Context) {
    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(StravaTokenManager::class.java)
        private const val PREFS_NAME = "strava_tokens"

        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_SECRET = "client_secret"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_ATHLETE_NAME = "athlete_name"
        private const val KEY_OAUTH_STATE = "oauth_state"

        /** Serialises token refreshes: Strava invalidates the old refresh token on every refresh. */
        val REFRESH_LOCK = Any()
    }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val tokenPreferences = createPreferences(context)

    private fun createPreferences(context: Context): SharedPreferences {
        return try {
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            LOG.warn("Unable to decrypt Strava token preferences, resetting them instead", e)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit(commit = true) { clear() }
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    fun saveClientCredentials(clientId: String, clientSecret: String) {
        tokenPreferences.edit {
            putString(KEY_CLIENT_ID, clientId.trim())
            putString(KEY_CLIENT_SECRET, clientSecret.trim())
        }
    }

    fun getClientId(): String? = tokenPreferences.getString(KEY_CLIENT_ID, null)

    fun getClientSecret(): String? = tokenPreferences.getString(KEY_CLIENT_SECRET, null)

    fun hasClientCredentials(): Boolean =
        !getClientId().isNullOrBlank() && !getClientSecret().isNullOrBlank()

    /**
     * Stores the tokens from a token endpoint response. The athlete is only included on the
     * initial code exchange, so a refresh keeps the stored name.
     */
    fun saveTokens(response: JSONObject) {
        tokenPreferences.edit {
            putString(KEY_ACCESS_TOKEN, response.getString("access_token"))
            putString(KEY_REFRESH_TOKEN, response.getString("refresh_token"))
            putLong(KEY_EXPIRES_AT, response.getLong("expires_at"))
            response.optJSONObject("athlete")?.let { athlete ->
                val name = listOf(athlete.optString("firstname"), athlete.optString("lastname"))
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                putString(KEY_ATHLETE_NAME, name)
            }
        }
    }

    fun getAccessToken(): String? = tokenPreferences.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = tokenPreferences.getString(KEY_REFRESH_TOKEN, null)

    /** Expiry of the access token, in epoch seconds. */
    fun getExpiresAt(): Long = tokenPreferences.getLong(KEY_EXPIRES_AT, 0L)

    fun getAthleteName(): String? = tokenPreferences.getString(KEY_ATHLETE_NAME, null)

    fun isLoggedIn(): Boolean = hasClientCredentials() && getRefreshToken() != null

    /** Clears the athlete's tokens but keeps the API application credentials for a reconnect. */
    fun clearTokens() {
        tokenPreferences.edit {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_EXPIRES_AT)
            remove(KEY_ATHLETE_NAME)
            remove(KEY_OAUTH_STATE)
        }
    }

    /** Generates and remembers the anti-CSRF `state` sent with an authorization request. */
    fun newOAuthState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val state = bytes.joinToString("") { "%02x".format(it) }
        tokenPreferences.edit { putString(KEY_OAUTH_STATE, state) }
        return state
    }

    /** Whether [state] matches the pending authorization request; the stored value is consumed. */
    fun consumeOAuthState(state: String?): Boolean {
        val expected = tokenPreferences.getString(KEY_OAUTH_STATE, null)
        tokenPreferences.edit { remove(KEY_OAUTH_STATE) }
        return expected != null && expected == state
    }
}
