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
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.health.openscale.sync.R

class MQTTViewModel(private val sharedPreferences: SharedPreferences) : ViewModelInterface(sharedPreferences) {
    companion object {
        const val SERVER = "mqtt_server"
        const val PORT = "mqtt_port"
        const val USERNAME = "mqtt_username"
        const val PASSWORD = "mqtt_password"
        const val USE_SSL = "mqtt_use_ssl"
        const val USE_DISCOVERY = "mqtt_use_discovery"
        const val LAST_PUBLISHED_DATES = "last_published_dates"
    }

    private val _mqttServer = MutableLiveData<String>(sharedPreferences.getString(SERVER, ""))
    private val _mqttPort = MutableLiveData<Int>(sharedPreferences.getInt(PORT, 8883))
    private val _mqttUsername = MutableLiveData<String>(sharedPreferences.getString(USERNAME, ""))
    private val _mqttPassword = MutableLiveData<String>(sharedPreferences.getString(PASSWORD, ""))
    private val _mqttConnecting = MutableLiveData<Boolean>(false)

    private val _mqttUseSsl = MutableLiveData<Boolean>(sharedPreferences.getBoolean(USE_SSL, true))
    private val _mqttUseDiscovery = MutableLiveData<Boolean>(sharedPreferences.getBoolean(USE_DISCOVERY, true))

    private val gson = Gson()
    private val lastPublishedDatesType = object : TypeToken<MutableMap<Int, Long>>() {}.type
    private val lastPublishedDates: MutableMap<Int, Long> =
        runCatching {
            sharedPreferences.getString(LAST_PUBLISHED_DATES, null)
                ?.let { gson.fromJson<MutableMap<Int, Long>>(it, lastPublishedDatesType) }
        }.getOrNull() ?: mutableMapOf()

    override fun getName(): String {
        return "MQTT"
    }

    override fun getIcon(): Int {
        return R.drawable.ic_mqtt
    }

    val mqttServer: LiveData<String> = _mqttServer
    fun setMQTTServer(value: String) {
        this._mqttServer.value = value
        sharedPreferences.edit { putString(SERVER, value) }
    }

    val mqttPort: LiveData<Int> = _mqttPort
    fun setMQTTPort(value: Int) {
        this._mqttPort.value = value
        sharedPreferences.edit { putInt(PORT, value) }
    }

    val mqttUsername: LiveData<String> = _mqttUsername
    fun setMQTTUsername(value: String) {
        this._mqttUsername.value = value
        sharedPreferences.edit { putString(USERNAME, value) }
    }

    val mqttPassword: LiveData<String> = _mqttPassword
    fun setMQTTPassword(value: String) {
        this._mqttPassword.value = value
        sharedPreferences.edit { putString(PASSWORD, value) }
    }

    val mqttConnecting: LiveData<Boolean> = _mqttConnecting
    fun setMQTTConnecting(value: Boolean) {
        this._mqttConnecting.value = value
    }

    val mqttUseSsl: LiveData<Boolean> = _mqttUseSsl

    fun setMqttUseSsl(useSsl: Boolean) {
        _mqttUseSsl.value = useSsl
        sharedPreferences.edit { putBoolean(USE_SSL, useSsl) }
    }

    val mqttUseDiscovery: LiveData<Boolean> = _mqttUseDiscovery

    fun setMqttUseDiscovery(useDiscovery: Boolean) {
        _mqttUseDiscovery.value = useDiscovery
        sharedPreferences.edit { putBoolean(USE_DISCOVERY, useDiscovery) }
    }

    /** Epoch millis of the newest measurement published to [userId]'s retained "last" topic, or 0. */
    fun getLastPublishedDate(userId: Int): Long = lastPublishedDates[userId] ?: 0L

    fun setLastPublishedDate(userId: Int, date: Long) {
        lastPublishedDates[userId] = date
        persistLastPublishedDates()
    }

    fun clearLastPublishedDate(userId: Int) {
        lastPublishedDates.remove(userId)
        persistLastPublishedDates()
    }

    private fun persistLastPublishedDates() {
        sharedPreferences.edit { putString(LAST_PUBLISHED_DATES, gson.toJson(lastPublishedDates)) }
    }
}