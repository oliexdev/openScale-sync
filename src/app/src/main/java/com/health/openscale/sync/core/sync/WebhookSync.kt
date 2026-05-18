package com.health.openscale.sync.core.sync

import com.google.gson.Gson
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.service.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class WebhookSync(
    private val client: OkHttpClient,
    private val url: String,
    private val authHeader: String
) : SyncInterface() {

    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun measurementToMap(m: OpenScaleMeasurement): MutableMap<String, Any> =
        mutableMapOf<String, Any>(
            "id" to m.id,
            "userId" to m.userId,
            "date" to dateFormat.format(m.date),
            "weight" to m.weight,
            "fat" to m.fat,
            "water" to m.water,
            "muscle" to m.muscle
        ).also { map -> m.extraFields.forEach { (k, v) -> map[k] = v } }

    suspend fun fullSync(measurements: List<OpenScaleMeasurement>): SyncResult<Unit> =
        post(gson.toJson(mapOf(
            "event" to "fullSync",
            "measurements" to measurements.map { measurementToMap(it) }
        )))

    suspend fun insert(measurement: OpenScaleMeasurement): SyncResult<Unit> =
        post(gson.toJson(measurementToMap(measurement).also { it["event"] = "insert" }))

    suspend fun update(measurement: OpenScaleMeasurement): SyncResult<Unit> =
        post(gson.toJson(measurementToMap(measurement).also { it["event"] = "update" }))

    suspend fun delete(date: Date): SyncResult<Unit> =
        post(gson.toJson(mapOf("event" to "delete", "date" to dateFormat.format(date))))

    suspend fun clear(): SyncResult<Unit> =
        post(gson.toJson(mapOf("event" to "clear")))

    private suspend fun post(json: String): SyncResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url(url)
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            if (authHeader.isNotBlank()) builder.addHeader("Authorization", authHeader)
            client.newCall(builder.build()).execute().use { response ->
                if (response.isSuccessful) SyncResult.Success(Unit)
                else SyncResult.Failure(SyncResult.ErrorType.API_ERROR,
                    "HTTP ${response.code}: ${response.body?.string()?.take(200)}")
            }
        } catch (e: Exception) {
            SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, cause = e)
        }
    }
}
