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