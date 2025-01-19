package com.health.openscale.sync.core.model

import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.health.openscale.sync.R

class MQTTViewModel(private val sharedPreferences: SharedPreferences) : ViewModelInterface(sharedPreferences) {
    private val _mqttServer = MutableLiveData<String>(sharedPreferences.getString("mqtt_server", ""))
    private val _mqttPort = MutableLiveData<Int>(sharedPreferences.getInt("mqtt_port", 8883))
    private val _mqttUsername = MutableLiveData<String>(sharedPreferences.getString("mqtt_username", ""))
    private val _mqttPassword = MutableLiveData<String>(sharedPreferences.getString("mqtt_password", ""))

    override fun getName(): String {
        return "MQTT"
    }

    override fun getIcon(): Int {
        return R.drawable.ic_mqtt
    }

    val mqttServer: LiveData<String> = _mqttServer
    fun setMQTTServer(value: String) {
        this._mqttServer.value = value
        sharedPreferences.edit().putString("mqtt_server", value).apply()
    }

    val mqttPort: LiveData<Int> = _mqttPort
    fun setMQTTPort(value: Int) {
        this._mqttPort.value = value
        sharedPreferences.edit().putInt("mqtt_port", value).apply()
    }

    val mqttUsername: LiveData<String> = _mqttUsername
    fun setMQTTUsername(value: String) {
        this._mqttUsername.value = value
        sharedPreferences.edit().putString("mqtt_username", value).apply()
    }

    val mqttPassword: LiveData<String> = _mqttPassword
    fun setMQTTPassword(value: String) {
        this._mqttPassword.value = value
        sharedPreferences.edit().putString("mqtt_password", value).apply()
    }
}