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
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.health.openscale.sync.R

class MQTTViewModel(private val sharedPreferences: SharedPreferences) : ViewModelInterface(sharedPreferences) {
    private val _mqttServer = MutableLiveData<String>(sharedPreferences.getString("mqtt_server", ""))
    private val _mqttPort = MutableLiveData<Int>(sharedPreferences.getInt("mqtt_port", 8883))
    private val _mqttUsername = MutableLiveData<String>(sharedPreferences.getString("mqtt_username", ""))
    private val _mqttPassword = MutableLiveData<String>(sharedPreferences.getString("mqtt_password", ""))
    private val _mqttConnecting = MutableLiveData<Boolean>(false)

    private val _mqttUseSsl = MutableLiveData<Boolean>(sharedPreferences.getBoolean("mqtt_use_ssl", true))
    private val _mqttUseDiscovery = MutableLiveData<Boolean>(sharedPreferences.getBoolean("mqtt_use_discovery", true))

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

    val mqttConnecting: LiveData<Boolean> = _mqttConnecting
    fun setMQTTConnecting(value: Boolean) {
        this._mqttConnecting.value = value
    }

    val mqttUseSsl: LiveData<Boolean> = _mqttUseSsl

    fun setMqttUseSsl(useSsl: Boolean) {
        _mqttUseSsl.value = useSsl
        sharedPreferences.edit().putBoolean("mqtt_use_ssl", useSsl).apply()
    }

    val mqttUseDiscovery: LiveData<Boolean> = _mqttUseDiscovery

    fun setMqttUseDiscovery(useDiscovery: Boolean) {
        _mqttUseDiscovery.value = useDiscovery
        sharedPreferences.edit().putBoolean("mqtt_use_discovery", useDiscovery).apply()
    }
}