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
        const val TOPIC_PREFIX = "mqtt_topic_prefix"
        /** Generated once per installation, see MQTTService.clientIdentifier. */
        const val CLIENT_ID = "mqtt_client_id"

        /**
         * Effective prefix the last connection used, so the next connection can clear the retained
         * topics of a prefix that has since changed. Absent until the first connection.
         */
        const val PUBLISHED_TOPIC_PREFIX = "mqtt_published_topic_prefix"

        /**
         * Input filter for the prefix field: ASCII letters, digits, `_`, `-` and spaces. Nothing
         * else is rewritten while typing; [topicLevel] makes the final level.
         */
        fun sanitizeTopicPrefix(raw: String): String =
            raw.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == ' ' }

        /**
         * The prefix as one MQTT topic level: trimmed, spaces as underscores, no separators or
         * wildcards. `inbound` and `measurements` are level names inside the unprefixed tree; as a
         * prefix they would make this installation publish into another one's topics.
         */
        fun topicLevel(prefix: String): String {
            val level = sanitizeTopicPrefix(prefix).trim().replace(' ', '_')
            return if (level in setOf("inbound", "measurements")) "${level}_" else level
        }
    }

    private val _mqttServer = MutableLiveData<String>(sharedPreferences.getString(SERVER, ""))
    private val _mqttPort = MutableLiveData<Int>(sharedPreferences.getInt(PORT, 8883))
    private val _mqttUsername = MutableLiveData<String>(sharedPreferences.getString(USERNAME, ""))
    private val _mqttPassword = MutableLiveData<String>(sharedPreferences.getString(PASSWORD, ""))
    private val _mqttConnecting = MutableLiveData<Boolean>(false)

    private val _mqttUseSsl = MutableLiveData<Boolean>(sharedPreferences.getBoolean(USE_SSL, true))
    private val _mqttUseDiscovery = MutableLiveData<Boolean>(sharedPreferences.getBoolean(USE_DISCOVERY, true))
    private val _mqttTopicPrefix = MutableLiveData<String>(sharedPreferences.getString(TOPIC_PREFIX, "") ?: "")

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

    /**
     * Optional extra topic level that keeps several installations apart on one broker
     * (`openScaleSync/<prefix>/<userId>/…`). Empty keeps the original topics.
     */
    val mqttTopicPrefix: LiveData<String> = _mqttTopicPrefix

    fun setMqttTopicPrefix(value: String) {
        val sanitized = sanitizeTopicPrefix(value)
        _mqttTopicPrefix.value = sanitized
        sharedPreferences.edit { putString(TOPIC_PREFIX, sanitized) }
    }

    /** Epoch millis of the newest measurement published to [userId]'s retained "last" topic, or 0. */
    fun getLastPublishedDate(userId: Int): Long = lastPublishedDates[userId] ?: 0L

    fun setLastPublishedDate(userId: Int, date: Long) {
        lastPublishedDates[userId] = date
        persistLastPublishedDates()
    }

    /** After a prefix change: the new "last" topics are empty, so the next sync must republish. */
    fun clearAllLastPublishedDates() {
        lastPublishedDates.clear()
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