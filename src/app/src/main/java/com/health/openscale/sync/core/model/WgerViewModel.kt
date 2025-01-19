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