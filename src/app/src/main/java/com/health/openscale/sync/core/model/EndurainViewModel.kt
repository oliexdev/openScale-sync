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

/**
 * Persisted, non-secret settings for the Endurain backend: the server origin and the last-used
 * username (kept only to prefill the form and label the login status). The password is never
 * stored; OAuth tokens live encrypted in [com.health.openscale.sync.core.sync.EndurainTokenManager].
 */
class EndurainViewModel(private val sharedPreferences: SharedPreferences) : ViewModelInterface(sharedPreferences) {
    companion object {
        const val SERVER = "endurain_server"
        const val USERNAME = "endurain_username"
    }

    private val _endurainServer = MutableLiveData<String>(sharedPreferences.getString(SERVER, ""))
    private val _endurainUsername = MutableLiveData<String>(sharedPreferences.getString(USERNAME, ""))

    // Bumped whenever tokens change (login / logout) so Compose recomposes the login status section.
    private val _loginStateVersion = MutableLiveData(0)

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

    val loginStateVersion: LiveData<Int> = _loginStateVersion
    fun notifyLoginStateChanged() {
        _loginStateVersion.value = (_loginStateVersion.value ?: 0) + 1
    }
}
