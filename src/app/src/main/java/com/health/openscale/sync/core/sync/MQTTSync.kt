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
package com.health.openscale.sync.core.sync

import com.google.gson.GsonBuilder

import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import timber.log.Timber

import java.util.Date

class MQTTSync(private val mqttClient: Mqtt5BlockingClient) : SyncInterface() {
    private val gson = GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mmZ").create()

    fun fullSync(measurements: List<OpenScaleMeasurement>) {
        measurements.forEach { measurement ->
            publishMeasurement(measurement, "openScaleSync/measurements/insert")
        }
    }

    fun insert(measurement: OpenScaleMeasurement) {
        publishMeasurement(measurement, "openScaleSync/measurements/insert")
    }

    fun delete(date: Date) {

        val message = mapOf("dateTime" to gson.toJsonTree(date))
        publishMessage(message, "openScaleSync/measurements/delete")
    }

    fun clear() {
        publishMessage(true, "openScaleSync/measurements/clear")
    }

    fun update(measurement: OpenScaleMeasurement) {
        publishMeasurement(measurement, "openScaleSync/measurements/update")
    }

    private fun publishMeasurement(measurement: OpenScaleMeasurement, topic: String) {
        val payload = gson.toJson(measurement).toByteArray()
        val publish = Mqtt5Publish.builder()
            .topic(topic)
            .payload(payload)
            .build()
        val result = mqttClient.publish(publish)
        if (result.error.isPresent) {
            Timber.e("Publishing failed ${measurement.date} to $topic")
            Timber.e("Error: ${result.error.get()}")
        } else {
            Timber.d("Publishing success ${measurement.date} to $topic")
        }
    }

    private fun publishMessage(message: Map<String, Any>, topic: String) {
        val payload = gson.toJson(message).toByteArray()
        val publish = Mqtt5Publish.builder()
            .topic(topic)
            .payload(payload)
            .build()
        val result = mqttClient.publish(publish)
        if (result.error.isPresent) {
            Timber.e("Publishing failed ${message.values} to $topic")
            Timber.e("Error: ${result.error.get()}")
        } else {
            Timber.d("Publishing success ${message.values} to $topic")
        }
    }

    private fun publishMessage(value : Boolean, topic: String) {
        val publish = Mqtt5Publish.builder()
            .topic(topic)
            .payload(value.toString().toByteArray())
            .build()
        val result = mqttClient.publish(publish)
        if (result.error.isPresent) {
            Timber.e("Publishing failed $value to $topic")
            Timber.e("Error: ${result.error.get()}")
        } else {
            Timber.d("Publishing success　$value to $topic")
        }
    }
}