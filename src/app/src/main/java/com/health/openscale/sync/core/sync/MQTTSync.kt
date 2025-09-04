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
import com.health.openscale.sync.core.service.SyncResult
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import java.util.Date

class MQTTSync(private val mqttClient: Mqtt5BlockingClient) : SyncInterface() {
    private val gson = GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mmZ").create()

    fun fullSync(measurements: List<OpenScaleMeasurement>) : SyncResult<Unit> {
        var failureCount = 0

        measurements.sortedBy { measurements -> measurements.id }.forEach { measurement ->
            val syncResult = publishMeasurement(measurement, "openScaleSync/measurements/insert")

            if (syncResult is SyncResult.Failure) {
                failureCount++
            }
        }

        if (failureCount > 0) {
            return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"$failureCount of ${measurements.size} measurements failed to sync",null)
        } else {
            return SyncResult.Success(Unit)
        }
    }

    fun insert(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
        return publishMeasurement(measurement, "openScaleSync/measurements/insert")
    }

    fun delete(date: Date) : SyncResult<Unit> {
        val message = mapOf("dateTime" to gson.toJsonTree(date))
        return publishMessage(message, "openScaleSync/measurements/delete")
    }

    fun clear() : SyncResult<Unit> {
        return publishMessage(true, "openScaleSync/measurements/clear")
    }

    fun update(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
        return publishMeasurement(measurement, "openScaleSync/measurements/update")
    }

    private fun publishMeasurement(measurement: OpenScaleMeasurement, topic: String) : SyncResult<Unit> {
        val payload = gson.toJson(measurement).toByteArray()
        val publish = Mqtt5Publish.builder()
            .topic(topic)
            .payload(payload)
            .build()
        val result = mqttClient.publish(publish)
        if (result.error.isPresent) {
            return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"Publishing failed ${measurement.date} to $topic" ,result.error.get())
        } else {
            return SyncResult.Success(Unit)
        }
    }

    private fun publishMessage(message: Map<String, Any>, topic: String) : SyncResult<Unit> {
        val payload = gson.toJson(message).toByteArray()
        val publish = Mqtt5Publish.builder()
            .topic(topic)
            .payload(payload)
            .build()
        val result = mqttClient.publish(publish)
        if (result.error.isPresent) {
            return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"Publishing failed ${message.values} to $topic" ,result.error.get())
        } else {
            return SyncResult.Success(Unit)
        }
    }

    private fun publishMessage(value : Boolean, topic: String) : SyncResult<Unit> {
        val publish = Mqtt5Publish.builder()
            .topic(topic)
            .payload(value.toString().toByteArray())
            .build()
        val result = mqttClient.publish(publish)
        if (result.error.isPresent) {
            return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"Publishing failed $value to $topic" ,result.error.get())
        } else {
            return SyncResult.Success(Unit)
        }
    }
}