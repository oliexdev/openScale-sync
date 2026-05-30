package com.health.openscale.sync.core.model

import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.health.openscale.sync.R

class WebhookViewModel(private val sharedPreferences: SharedPreferences) : ViewModelInterface(sharedPreferences) {

    private val _url = MutableLiveData<String>(sharedPreferences.getString("webhook_url", ""))
    private val _authHeader = MutableLiveData<String>(sharedPreferences.getString("webhook_auth_header", ""))
    private val _connecting = MutableLiveData<Boolean>(false)

    override fun getName(): String = "Webhook"
    override fun getIcon(): Int = R.drawable.ic_webhook

    val url: LiveData<String> = _url
    fun setUrl(value: String) { _url.value = value; sharedPreferences.edit().putString("webhook_url", value).apply() }

    val authHeader: LiveData<String> = _authHeader
    fun setAuthHeader(value: String) { _authHeader.value = value; sharedPreferences.edit().putString("webhook_auth_header", value).apply() }

    val connecting: LiveData<Boolean> = _connecting
    fun setConnecting(value: Boolean) { _connecting.value = value }
}
