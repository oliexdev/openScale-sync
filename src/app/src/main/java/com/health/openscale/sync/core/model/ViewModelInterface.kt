package com.health.openscale.sync.core.model

import android.content.SharedPreferences
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.time.Instant


abstract class ViewModelInterface(private val sharedPreferences: SharedPreferences) : ViewModel() {
    private val _connectAvailable = mutableStateOf(false)
    private val _allPermissionsGranted = mutableStateOf(false)
    private val _syncEnabled = mutableStateOf(sharedPreferences.getBoolean("syncEnabled"+getName(), false))
    private val _syncRunning = mutableStateOf(false)
    private val _lastSync = MutableLiveData<Instant>(Instant.ofEpochMilli(sharedPreferences.getLong("lastSync"+getName(), 0L)))
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
        sharedPreferences.edit().putBoolean("syncEnabled"+getName(), value).apply()
    }

    val lastSync: LiveData<Instant> get() = _lastSync

    fun setLastSync(value: Instant) {
        _lastSync.value = value
        sharedPreferences.edit().putLong("lastSync"+getName(), value.toEpochMilli()).apply()
    }

    val syncRunning: State<Boolean> get() = _syncRunning

    fun setSyncRunning(value: Boolean) {
        this._syncRunning.value = value
    }

    val errorMessage: LiveData<String?> = _errorMessage
    fun setErrorMessage(value: String) {
        this._errorMessage.value = value
    }

    val infoMessage: LiveData<String?> = _infoMessage
    fun setInfoMessage(value: String) {
        this._infoMessage.value = value
    }

    val debugMessage: LiveData<String?> = _debugMessage
    fun setDebugMessage(value: String) {
        this._debugMessage.value = value
    }
}