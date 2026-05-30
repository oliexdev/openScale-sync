package com.health.openscale.sync.core.model

import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.health.openscale.sync.R

class WebhookViewModel(private val sharedPreferences: SharedPreferences) : ViewModelInterface(sharedPreferences) {
    companion object {
        const val URL = "webhook_url"
        const val AUTH_HEADER = "webhook_auth_header"
    }

    private val _url = MutableLiveData<String>(sharedPreferences.getString(URL, ""))
    private val _authHeader = MutableLiveData<String>(sharedPreferences.getString(AUTH_HEADER, ""))
    private val _connecting = MutableLiveData<Boolean>(false)

    override fun getName(): String = "Webhook"
    override fun getIcon(): Int = R.drawable.ic_webhook

    val url: LiveData<String> = _url
    fun setUrl(value: String) { _url.value = value; sharedPreferences.edit().putString(URL, value).apply() }

    val authHeader: LiveData<String> = _authHeader
    fun setAuthHeader(value: String) { _authHeader.value = value; sharedPreferences.edit().putString(AUTH_HEADER, value).apply() }

    val connecting: LiveData<Boolean> = _connecting
    fun setConnecting(value: Boolean) { _connecting.value = value }
}
