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

import androidx.core.content.edit
import android.content.SharedPreferences
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.time.Instant


/** Per-backend sync direction. Only inbound-capable backends (see ServiceInterface.supportsInbound)
 *  offer IMPORT/BOTH; everything else stays EXPORT (the default, = previous behaviour). */
// Declaration order drives the segmented-control order in SyncDirectionSelector (Both first).
enum class SyncDirection { BOTH, EXPORT, IMPORT }

abstract class ViewModelInterface(private val sharedPreferences: SharedPreferences) : ViewModel() {
    companion object {
        // Per-service flags, suffixed with getName()
        const val SYNC_ENABLED_PREFIX = "syncEnabled"
        const val LAST_SYNC_PREFIX = "lastSync"
        // Per-service selected openScale user (single-user backends like HealthConnect/Wger).
        // -1 = not chosen yet. Multi-user backends ignore this and sync all users.
        const val SELECTED_USER_PREFIX = "selectedUser"
        // Per-service sync direction (EXPORT default). Only meaningful for inbound-capable backends.
        const val SYNC_DIRECTION_PREFIX = "syncDirection"
    }

    private val _connectAvailable = mutableStateOf(false)
    private val _allPermissionsGranted = mutableStateOf(false)
    private val _syncEnabled = mutableStateOf(sharedPreferences.getBoolean(SYNC_ENABLED_PREFIX+getName(), false))
    private val _lastSync = MutableLiveData<Instant>(Instant.ofEpochMilli(sharedPreferences.getLong(LAST_SYNC_PREFIX+getName(), 0L)))
    private val _errorMessage = MutableLiveData<String?>(null)
    private val _infoMessage = MutableLiveData<String?>(null)
    private val _debugMessage = MutableLiveData<String?>(null)

    abstract fun getName() : String
    abstract fun getIcon() : Int

    val connectAvailable: State<Boolean> get() = _connectAvailable

    fun setConnectAvailable(value: Boolean) {
        _connectAvailable.value = value
    }

    val allPermissionsGranted: State<Boolean> get() = _allPermissionsGranted

    fun setAllPermissionsGranted(value: Boolean) {
        _allPermissionsGranted.value = value
    }

    val syncEnabled: State<Boolean> get() = _syncEnabled

    fun setSyncEnabled(value: Boolean) {
        _syncEnabled.value = value
        sharedPreferences.edit { putBoolean(SYNC_ENABLED_PREFIX+getName(), value) }
    }

    val lastSync: LiveData<Instant> get() = _lastSync

    fun setLastSync(value: Instant) {
        _lastSync.value = value
        sharedPreferences.edit { putLong(LAST_SYNC_PREFIX+getName(), value.toEpochMilli()) }
    }

    // Per-service value if chosen; otherwise fall back to the (legacy) global selection so existing
    // single-user setups keep working until the user picks a per-service user.
    private val _selectedUserId = mutableStateOf(
        sharedPreferences.getInt(SELECTED_USER_PREFIX + getName(), -1).let { perService ->
            if (perService != -1) perService
            else sharedPreferences.getInt(OpenScaleViewModel.SELECTED_USER_ID, -1)
        }
    )

    /** Per-service selected openScale user id, or -1 if not chosen. Only meaningful for
     *  single-user backends; multi-user backends ignore it (see ServiceInterface.isMultiUser). */
    val selectedUserId: State<Int> get() = _selectedUserId

    fun setSelectedUserId(value: Int) {
        _selectedUserId.value = value
        sharedPreferences.edit { putInt(SELECTED_USER_PREFIX+getName(), value) }
    }

    private val _syncDirection = mutableStateOf(
        runCatching {
            SyncDirection.valueOf(sharedPreferences.getString(SYNC_DIRECTION_PREFIX + getName(), null) ?: SyncDirection.BOTH.name)
        }.getOrDefault(SyncDirection.BOTH)
    )

    /** Per-service sync direction (EXPORT default). Inbound-capable backends may set IMPORT/BOTH. */
    val syncDirection: State<SyncDirection> get() = _syncDirection

    fun setSyncDirection(value: SyncDirection) {
        _syncDirection.value = value
        sharedPreferences.edit { putString(SYNC_DIRECTION_PREFIX + getName(), value.name) }
    }

    val errorMessage: LiveData<String?> = _errorMessage
    fun setErrorMessage(value: String) {
        this._errorMessage.value = value
    }

    fun setInfoMessage(value: String) {
        this._infoMessage.value = value
    }

    fun setDebugMessage(value: String) {
        this._debugMessage.value = value
    }
}