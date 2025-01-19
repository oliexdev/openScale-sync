/*
 *  Copyright (C) 2025  olie.xdev <olie.xdev@googlemail.com>
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
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.health.openscale.sync.R

class WgerViewModel(private val sharedPreferences: SharedPreferences) : ViewModelInterface(sharedPreferences) {
    private val _wgerServer = MutableLiveData<String>(sharedPreferences.getString("wger_server", "https://wger.de/api/v2/"))
    private val _wgerApiToken = MutableLiveData<String>(sharedPreferences.getString("wger_api_token", ""))

    override fun getName(): String {
        return "Wger"
    }

    override fun getIcon(): Int {
        return R.drawable.ic_wger
    }

    val wgerServer: LiveData<String> = _wgerServer
    fun setWgerServer(value: String) {
        this._wgerServer.value = value
        sharedPreferences.edit().putString("wger_server", value).apply()
    }

    val wgerApiToken: LiveData<String> = _wgerApiToken
    fun setWgerApiToken(value: String) {
        this._wgerApiToken.value = value
        sharedPreferences.edit().putString("wger_api_token", value).apply()
    }
}