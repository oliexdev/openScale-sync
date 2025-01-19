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