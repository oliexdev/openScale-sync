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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.health.openscale.sync.R
import com.health.openscale.sync.core.datatypes.OpenScaleUser

class OpenScaleViewModel(private val sharedPreferences: SharedPreferences) : ViewModelInterface(sharedPreferences) {
    private val _openScaleUsers: MutableState<List<OpenScaleUser>> = mutableStateOf(listOf())
    private val _openScaleSelectedUser = MutableLiveData<OpenScaleUser?>(null)

    init {
    }

    override fun getName(): String {
        return "OpenScale"
    }

    override fun getIcon(): Int {
        return R.drawable.ic_launcher_openscale_sync
    }

    val openScaleUsers: State<List<OpenScaleUser>> get() = _openScaleUsers

    fun setOpenScaleUsers(value: List<OpenScaleUser>) {
        _openScaleUsers.value = value
    }


    val openScaleSelectedUser: LiveData<OpenScaleUser?> = _openScaleSelectedUser

    fun selectOpenScaleUser(value: OpenScaleUser?) {
        _openScaleSelectedUser.value = value
    }
}