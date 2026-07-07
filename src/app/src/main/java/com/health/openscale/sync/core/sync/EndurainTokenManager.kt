/*
 *  Copyright (C) 2026  Dany Mestas
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 */
package com.health.openscale.sync.core.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber

/**
 * Encrypted at-rest storage for the Endurain OAuth tokens. Endurain issues a short-lived access
 * token (~15 min) plus a longer-lived rotating refresh token (~7 days). We never store the user's
 * password — only the tokens — matching Gadgetbridge's model. Tokens are held in an
 * [EncryptedSharedPreferences] (AES256) file separate from the plain app prefs.
 *
 * Expiries are stored as ABSOLUTE epoch-seconds, computed from the `expires_in` /
 * `refresh_token_expires_in` (relative seconds) the server returns at login/refresh.
 */
class EndurainTokenManager(context: Context) {
    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Persist a freshly minted token pair. [expiresIn] / [refreshExpiresIn] are the relative
     * lifetimes in seconds from the Endurain token response; they are converted to absolute
     * epoch-second deadlines here.
     */
    fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long, refreshExpiresIn: Long) {
        val now = System.currentTimeMillis() / 1000
        prefs.edit {
            putString(KEY_ACCESS, accessToken)
            putString(KEY_REFRESH, refreshToken)
            putLong(KEY_ACCESS_EXP, now + expiresIn)
            putLong(KEY_REFRESH_EXP, now + refreshExpiresIn)
        }
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS, null)
    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH, null)

    fun getRefreshTokenExpiresAt(): Long = prefs.getLong(KEY_REFRESH_EXP, 0L)

    // A small clock-skew margin so we refresh slightly early rather than racing the server's cutoff.
    fun isAccessTokenExpired(): Boolean =
        (System.currentTimeMillis() / 1000) >= (prefs.getLong(KEY_ACCESS_EXP, 0L) - EXPIRY_SKEW_SECONDS)

    fun isRefreshTokenExpired(): Boolean =
        (System.currentTimeMillis() / 1000) >= prefs.getLong(KEY_REFRESH_EXP, 0L)

    /** Logged in = we still hold a refresh token that has not yet expired. */
    fun isLoggedIn(): Boolean = getRefreshToken() != null && !isRefreshTokenExpired()

    fun clear() {
        prefs.edit { clear() }
        Timber.d("Endurain tokens cleared")
    }

    companion object {
        private const val PREFS_FILE = "endurain_tokens"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_ACCESS_EXP = "access_token_expires_at"
        private const val KEY_REFRESH_EXP = "refresh_token_expires_at"
        private const val EXPIRY_SKEW_SECONDS = 30L
    }
}
