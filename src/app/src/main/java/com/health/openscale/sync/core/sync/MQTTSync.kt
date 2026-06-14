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
import com.google.gson.JsonParser
import com.health.openscale.sync.BuildConfig
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.service.SyncResult
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import timber.log.Timber
import java.util.Date
import java.util.concurrent.TimeUnit

class MQTTSync(private val mqttClient: Mqtt5BlockingClient) : SyncInterface() {
    private val gson = GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mmZ").create()

    // Per-user topic: the stable userId is the path segment; the username travels in the JSON payload.
    private fun userTopic(userId: Int, event: String) = "openScaleSync/$userId/measurements/$event"

    /**
     * Publishes a user's complete, date-sorted measurement series as ONE retained, slim columnar
     * message `{fields, units, rows}` on the per-user "history" topic. Passing an empty list clears
     * the snapshot. The payload itself is built by the pure [buildHistoryPayload].
     */
    fun publishHistory(userId: Int, measurements: List<OpenScaleMeasurement>) : SyncResult<Unit> =
        publishMessage(buildHistoryPayload(measurements), userTopic(userId, "history"), true)

    fun insert(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
        return publishMeasurement(measurement, userTopic(measurement.userId, "insert"))
    }

    fun delete(userId: Int, date: Date) : SyncResult<Unit> {
        val message = mapOf("dateTime" to gson.toJsonTree(date))
        return publishMessage(message, userTopic(userId, "delete"))
    }

    fun clear(userId: Int) : SyncResult<Unit> {
        return publishMessage(true, userTopic(userId, "clear"))
    }

    /** Clears the retained per-user "last" measurement (after a delete/clear of that user). */
    fun clearLastMeasurement(userId: Int) : SyncResult<Unit> {
        return publishMessage(mapOf<String, Any>(), userTopic(userId, "last"), true)
    }

    fun update(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
        return publishMeasurement(measurement, userTopic(measurement.userId, "update"))
    }

    fun publishLastMeasurement(measurement: OpenScaleMeasurement?): SyncResult<Unit> {
        return if (measurement != null) {
            publishMeasurement(measurement, userTopic(measurement.userId, "last"), true)
        } else {
            // No measurement (e.g. last entry cleared) → can't target a per-user topic; use the shared one.
            publishMessage(mapOf<String, Any>(), "openScaleSync/measurements/last", true)
        }
    }

    fun publishHomeAssistantDiscovery(
        jsonPayloadString: String,
        deviceSwVersion: String? = null,
        userId: Int,
        username: String
    ): SyncResult<Unit> {
        if (!mqttClient.state.isConnected) {
            Timber.e("MQTTSync: Cannot publish HA discovery, instance client is not connected.")
            return SyncResult.Failure(
                SyncResult.ErrorType.API_ERROR,
                "Instance client not connected for HA discovery publish."
            )
        }

        val currentAppVersion: String = BuildConfig.VERSION_NAME // App version determined here
        Timber.d("MQTTSync: Publishing Home Assistant discovery payload (App Version: $currentAppVersion).")

        try {
            // Parse the incoming JSON string to a JsonObject
            val payloadJson = JsonParser.parseString(jsonPayloadString).asJsonObject

            // Ensure "origin" object exists and add/update the software version
            val originObject = payloadJson.getAsJsonObject("origin")
                ?: run {
                    // If 'origin' is missing, create a default structure. This makes the payload more robust.
                    Timber.w("MQTTSync: 'origin' object missing in HA discovery payload. Creating default.")
                    val defaultOrigin = JsonParser.parseString("""{"name": "openScale Sync", "sw_version": ""}""").asJsonObject
                    payloadJson.add("origin", defaultOrigin)
                    defaultOrigin // Return the newly created object
                }
            originObject.addProperty("sw_version", currentAppVersion)

            // The device object represents openScale (the data source), so it gets the openScale
            // version — shown on the HA device page. Omitted if the version could not be determined.
            if (deviceSwVersion != null) {
                payloadJson.getAsJsonObject("device")?.addProperty("sw_version", deviceSwVersion)
            }

            // --- Per-user device: stable userId-based identity, username as the HA display name ---
            payloadJson.getAsJsonObject("device")?.apply {
                addProperty("identifiers", "openscale_$userId")
                addProperty("name", if (username.isNotBlank()) "openScale ($username)" else "openScale ($userId)")
            }
            // Point the entities at this user's retained "last" measurement topic.
            payloadJson.addProperty("state_topic", "openScaleSync/$userId/measurements/last")
            // Make each component's unique_id user-specific so HA keeps them as separate entities.
            payloadJson.getAsJsonObject("components")?.entrySet()?.forEach { entry ->
                val obj = entry.value.asJsonObject
                val baseUid = obj.get("unique_id")?.asString ?: "openscale"
                obj.addProperty("unique_id", "${baseUid}_$userId")
            }

            val bytes = gson.toJson(payloadJson).toByteArray()

            val publishResult = mqttClient.publish(
                Mqtt5Publish.builder()
                    .topic("homeassistant/device/openscale_$userId/config")
                    .payload(bytes)
                    .retain(true)
                    .build()
            )

            if (publishResult.error.isPresent) {
                val error = publishResult.error.get()
                Timber.w(error, "MQTTSync: Failed to publish Home Assistant discovery. Reason: ${error.message}")
                return SyncResult.Failure(
                    SyncResult.ErrorType.API_ERROR,
                    "Failed to publish Home Assistant discovery: ${error.message}",
                    error
                )
            } else {
                Timber.d("MQTTSync: Home Assistant discovery payload published successfully.")
                return SyncResult.Success(Unit)
            }
        } catch (e: Exception) { // Catch errors during JSON parsing or publishing
            Timber.e(e, "MQTTSync: Error during Home Assistant discovery publish process.")
            return SyncResult.Failure(
                SyncResult.ErrorType.UNKNOWN_ERROR,
                "Error during Home Assistant discovery publish: ${e.message}",
                e
            )
        }
    }

    /**
     * Inbound (bidirectional): subscribe to [topicFilter] and drain currently available (incl.
     * retained) messages, returning (topic, payloadString) pairs. On-demand poll model — external
     * systems publish measurements as retained messages on openScaleSync/inbound/<userId>/… so they
     * persist until imported. Stops after [perMessageTimeoutMs] with no further message.
     */
    fun drainInboundMessages(topicFilter: String, perMessageTimeoutMs: Long): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val publishes = mqttClient.publishes(MqttGlobalPublishFilter.ALL)
        try {
            mqttClient.subscribeWith().topicFilter(topicFilter).send()
            while (true) {
                val opt = publishes.receive(perMessageTimeoutMs, TimeUnit.MILLISECONDS)
                if (!opt.isPresent) break
                val p = opt.get()
                out.add(p.topic.toString() to String(p.payloadAsBytes))
            }
        } catch (e: Exception) {
            Timber.w(e, "MQTT inbound drain interrupted")
        } finally {
            runCatching { mqttClient.unsubscribeWith().topicFilter(topicFilter).send() }
            runCatching { publishes.close() }
        }
        return out
    }

    private fun publishMeasurement(measurement: OpenScaleMeasurement, topic: String, retain : Boolean = false) : SyncResult<Unit> {
        val payload = gson.toJson(measurement).toByteArray()
        val publish = Mqtt5Publish.builder()
            .topic(topic)
            .payload(payload)
            .retain(retain)
            .build()
        val result = mqttClient.publish(publish)
        if (result.error.isPresent) {
            return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"Publishing failed ${measurement.date} to $topic" ,result.error.get())
        } else {
            return SyncResult.Success(Unit)
        }
    }

    private fun publishMessage(message: Map<String, Any>, topic: String, retain : Boolean = false) : SyncResult<Unit> {
        val payload = gson.toJson(message).toByteArray()
        val publish = Mqtt5Publish.builder()
            .topic(topic)
            .payload(payload)
            .retain(retain)
            .build()
        val result = mqttClient.publish(publish)
        if (result.error.isPresent) {
            return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"Publishing failed ${message.values} to $topic" ,result.error.get())
        } else {
            return SyncResult.Success(Unit)
        }
    }

    private fun publishMessage(value : Boolean, topic: String, retain : Boolean = false) : SyncResult<Unit> {
        val publish = Mqtt5Publish.builder()
            .topic(topic)
            .payload(value.toString().toByteArray())
            .retain(retain)
            .build()
        val result = mqttClient.publish(publish)
        if (result.error.isPresent) {
            return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"Publishing failed $value to $topic" ,result.error.get())
        } else {
            return SyncResult.Success(Unit)
        }
    }

    companion object {
        /**
         * Builds the slim, retained-friendly columnar history payload `{fields, units, rows}` from a
         * user's measurements. Names/units live once in the header (not per row) → ~10× smaller than
         * the per-measurement form. Canonical columns come from [OpenScaleMeasurement.CANONICAL_METRICS]
         * (the single source of truth); every other generic value (incl. `custom_<id>`) becomes an
         * extra column keyed by its stable `backendKey()`. `rows` are date-sorted; a cell is `null`
         * when that metric is absent.
         */
        fun buildHistoryPayload(measurements: List<OpenScaleMeasurement>): Map<String, Any> {
            val canonical = OpenScaleMeasurement.CANONICAL_METRICS
            val canonicalKeys = canonical.map { it.backendKey }            // weight, body_fat, water, muscle
            val canonicalKeySet = canonicalKeys.toHashSet()

            // Stable, sorted union of non-canonical generic keys present anywhere (with their unit).
            val extraUnits = sortedMapOf<String, String>()
            measurements.forEach { m ->
                m.values.forEach { v ->
                    val key = v.backendKey()
                    if (v.value != null && key !in canonicalKeySet) extraUnits.putIfAbsent(key, v.unit)
                }
            }
            val extraKeys = extraUnits.keys.toList()
            val fields = listOf("date") + canonicalKeys + extraKeys
            val units = canonical.associate { it.backendKey to it.unit } + extraUnits

            val rows = measurements.sortedBy { it.date.time }.map { m ->
                // Old openScale (no generic values) → fall back to the convenience fields as-is;
                // otherwise emit a canonical value only when that metric is actually present.
                val hasValues = m.values.isNotEmpty()
                val present = m.values.mapTo(HashSet()) { it.backendKey() }
                val byKey = m.values.associateBy { it.backendKey() }
                val row = ArrayList<Any?>(fields.size)
                row.add(m.date.time)
                canonical.forEach { metric ->
                    row.add(if (!hasValues || metric.backendKey in present) metric.accessor(m) else null)
                }
                extraKeys.forEach { row.add(byKey[it]?.value) }
                row
            }

            return mapOf("fields" to fields, "units" to units, "rows" to rows)
        }
    }
}