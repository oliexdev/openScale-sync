/*
 *  Copyright (C) 2026  Mirko Seipel
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
 * Settings for the SparkyFitness backend: server origin, API key and whether values openScale
 * derives itself are exported too. The API key is kept like every other backend keeps its secret
 * (Wger token, InfluxDB token): plain `putString` in app-private storage.
 */
class SparkyFitnessViewModel(private val sharedPreferences: SharedPreferences) :
    ViewModelInterface(sharedPreferences) {
    companion object {
        const val SERVER = "sparkyfitness_server"
        const val API_KEY = "sparkyfitness_api_key"
        const val INCLUDE_DERIVED = "sparkyfitness_include_derived"
    }

    private val _server = MutableLiveData<String>(sharedPreferences.getString(SERVER, ""))
    private val _apiKey = MutableLiveData<String>(sharedPreferences.getString(API_KEY, ""))
    private val _includeDerived = MutableLiveData(sharedPreferences.getBoolean(INCLUDE_DERIVED, false))

    override fun getName(): String {
        return "SparkyFitness"
    }

    override fun getIcon(): Int {
        return R.drawable.ic_sparkyfitness
    }

    val server: LiveData<String> = _server
    fun setServer(value: String) {
        this._server.value = value
        sharedPreferences.edit { putString(SERVER, value) }
    }

    val apiKey: LiveData<String> = _apiKey
    fun setApiKey(value: String) {
        this._apiKey.value = value
        sharedPreferences.edit { putString(API_KEY, value) }
    }

    val includeDerived: LiveData<Boolean> = _includeDerived
    fun setIncludeDerived(value: Boolean) {
        this._includeDerived.value = value
        sharedPreferences.edit { putBoolean(INCLUDE_DERIVED, value) }
    }
}
