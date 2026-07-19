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
package com.health.openscale.sync.core.model

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.health.openscale.sync.R
import com.health.openscale.sync.core.sync.TokenStore
import timber.log.Timber

/**
 * Settings + at-rest auth state for the Endurain backend.
 *
 * Persisted, non-secret settings: the server origin and the last-used username (kept only to prefill
 * the form and label the login status). The password is never stored.
 *
 * OAuth tokens are also kept here (implementing [TokenStore]), matching how every other backend keeps
 * its secrets in its ViewModel (Wger token, InfluxDB token/password, MQTT password): plain `putString`
 * in app-private storage, which on minSdk 31 is already sandboxed and file-based-encrypted at rest by
 * the platform. Endurain issues a short-lived access token (~15 min) plus a longer-lived rotating
 * refresh token (~7 days); expiries are stored as ABSOLUTE epoch-seconds, computed from the relative
 * `expires_in` / `refresh_token_expires_in` the server returns at login/refresh. The wire layer
 * ([com.health.openscale.sync.core.sync.EndurainSync]) reads/writes tokens through the [TokenStore]
 * interface so it never depends on this Android ViewModel type directly.
 */
class EndurainViewModel(private val sharedPreferences: SharedPreferences) :
    ViewModelInterface(sharedPreferences), TokenStore {
    companion object {
        const val SERVER = "endurain_server"
        const val USERNAME = "endurain_username"

        private const val KEY_ACCESS = "endurain_access_token"
        private const val KEY_REFRESH = "endurain_refresh_token"
        private const val KEY_ACCESS_EXP = "endurain_access_token_expires_at"
        private const val KEY_REFRESH_EXP = "endurain_refresh_token_expires_at"
        private const val EXPIRY_SKEW_SECONDS = 30L
    }

    private val _endurainServer = MutableLiveData<String>(sharedPreferences.getString(SERVER, ""))
    private val _endurainUsername = MutableLiveData<String>(sharedPreferences.getString(USERNAME, ""))

    // Observable login state so Compose recomposes the login-status section directly. Updated (from
    // any thread) in saveTokens/clearTokens.
    private val _loggedIn = MutableLiveData(isLoggedIn())
    val loggedIn: LiveData<Boolean> = _loggedIn
    private val _refreshTokenExpiresAt = MutableLiveData(getRefreshTokenExpiresAt())
    val refreshTokenExpiresAt: LiveData<Long> = _refreshTokenExpiresAt

    override fun getName(): String {
        return "Endurain"
    }

    override fun getIcon(): Int {
        return R.drawable.ic_endurain
    }

    val endurainServer: LiveData<String> = _endurainServer
    fun setEndurainServer(value: String) {
        this._endurainServer.value = value
        sharedPreferences.edit { putString(SERVER, value) }
    }

    val endurainUsername: LiveData<String> = _endurainUsername
    fun setEndurainUsername(value: String) {
        this._endurainUsername.value = value
        sharedPreferences.edit { putString(USERNAME, value) }
    }

    // --- Token storage (TokenStore) -----------------------------------------------------

    /**
     * Persist a freshly minted token pair. [expiresIn] / [refreshExpiresIn] are the relative
     * lifetimes in seconds from the Endurain token response; they are converted to absolute
     * epoch-second deadlines here. Called from the wire layer, possibly on a background thread —
     * the LiveData is updated via postValue.
     */
    override fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long, refreshExpiresIn: Long) {
        val now = System.currentTimeMillis() / 1000
        sharedPreferences.edit {
            putString(KEY_ACCESS, accessToken)
            putString(KEY_REFRESH, refreshToken)
            putLong(KEY_ACCESS_EXP, now + expiresIn)
            putLong(KEY_REFRESH_EXP, now + refreshExpiresIn)
        }
        _loggedIn.postValue(isLoggedIn())
        _refreshTokenExpiresAt.postValue(getRefreshTokenExpiresAt())
    }

    override fun getAccessToken(): String? = sharedPreferences.getString(KEY_ACCESS, null)
    override fun getRefreshToken(): String? = sharedPreferences.getString(KEY_REFRESH, null)

    fun getRefreshTokenExpiresAt(): Long = sharedPreferences.getLong(KEY_REFRESH_EXP, 0L)

    // A small clock-skew margin so we refresh slightly early rather than racing the server's cutoff.
    override fun isAccessTokenExpired(): Boolean =
        (System.currentTimeMillis() / 1000) >= (sharedPreferences.getLong(KEY_ACCESS_EXP, 0L) - EXPIRY_SKEW_SECONDS)

    override fun isRefreshTokenExpired(): Boolean =
        (System.currentTimeMillis() / 1000) >= sharedPreferences.getLong(KEY_REFRESH_EXP, 0L)

    /** Logged in = we still hold a refresh token that has not yet expired. */
    fun isLoggedIn(): Boolean = getRefreshToken() != null && !isRefreshTokenExpired()

    fun clearTokens() {
        sharedPreferences.edit {
            remove(KEY_ACCESS)
            remove(KEY_REFRESH)
            remove(KEY_ACCESS_EXP)
            remove(KEY_REFRESH_EXP)
        }
        _loggedIn.postValue(false)
        _refreshTokenExpiresAt.postValue(0L)
        Timber.d("Endurain tokens cleared")
    }
}
