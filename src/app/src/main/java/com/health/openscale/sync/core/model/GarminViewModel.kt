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
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.health.openscale.sync.R

class GarminViewModel(private val sharedPreferences: SharedPreferences) : ViewModelInterface(sharedPreferences) {
    companion object {
        const val EMAIL = "garmin_email"
        const val PASSWORD = "garmin_password"
    }

    private val _email = MutableLiveData<String>(sharedPreferences.getString(EMAIL, ""))
    private val _password = MutableLiveData<String>(sharedPreferences.getString(PASSWORD, ""))
    private val _connecting = MutableLiveData<Boolean>(false)

    override fun getName(): String = "Garmin Connect"
    override fun getIcon(): Int = R.drawable.ic_garmin

    val email: LiveData<String> = _email
    fun setEmail(value: String) { _email.value = value; sharedPreferences.edit { putString(EMAIL, value) } }

    val password: LiveData<String> = _password
    fun setPassword(value: String) { _password.value = value; sharedPreferences.edit { putString(PASSWORD, value) } }

    val connecting: LiveData<Boolean> = _connecting
    fun setConnecting(value: Boolean) { _connecting.value = value }
}
