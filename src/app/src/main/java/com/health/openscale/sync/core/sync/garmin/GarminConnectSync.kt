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
package com.health.openscale.sync.core.sync.garmin

import com.google.gson.JsonParser
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurementValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Talks to Garmin Connect: uploads a measurement as a FIT weight-scale file
 * (the same thing a real scale sends) and deletes weigh-ins by date.
 *
 * Auth is delegated to [GarminAuth]; [loginProvider] is invoked when no usable
 * token exists and must return a valid bearer (i.e. run the full login flow).
 */
class GarminConnectSync(
    private val auth: GarminAuth,
    private val client: OkHttpClient,
    private val loginProvider: suspend () -> String
) {

    class UploadException(message: String) : Exception(message)

    /** True when a stored token exists (no interactive login needed). */
    fun hasStoredToken(): Boolean = auth.ensureAccessToken() != null

    private suspend fun bearer(): String =
        auth.ensureAccessToken() ?: loginProvider()

    /**
     * Upload one measurement. Metrics openScale does not have are left out
     * (invalid FIT value), never sent as 0.
     */
    suspend fun upload(m: OpenScaleMeasurement) {
        val weight = m.weight.toDouble()
        if (weight <= 0.0) throw UploadException("measurement has no usable weight")

        // Convenience fields are percentages (openScale-sync contract); the generic
        // value list carries the raw units and is authoritative for the extras.
        fun raw(key: String): OpenScaleMeasurementValue? =
            m.values.firstOrNull { it.identity.substringAfterLast('.').equals(key, true) }

        fun pctToKg(pct: Double?): Double? = pct?.let { weight * it / 100.0 }
        fun toKg(v: OpenScaleMeasurementValue?): Double? {
            val value = v?.value?.toDouble() ?: return null
            return when (v.unit) {
                "kg" -> value
                "%" -> weight * value / 100.0
                else -> null
            }
        }

        val muscleKg = toKg(raw("muscle")) ?: pctToKg(m.muscle?.toDouble())
        val boneKg = toKg(raw("bone")) ?: pctToKg(m.bone?.toDouble())
        val visceralRating = raw("visceral_fat")?.value?.toDouble()
        val bmr = raw("bmr")?.value?.toDouble() ?: raw("calories")?.value?.toDouble()

        val epochSec = m.date.time / 1000
        val fit = FitWeightEncoder()
        fit.writeFileInfo(epochSec)
        fit.writeFileCreator()
        fit.writeDeviceInfo(epochSec)
        fit.writeWeightScale(
            timestampSec = epochSec,
            weight = weight,
            percentFat = m.body_fat?.toDouble(),
            percentHydration = m.water?.toDouble(),
            muscleMass = muscleKg,
            boneMass = boneKg,
            visceralFatRating = visceralRating,
            basalMet = bmr
        )
        val fitBytes = fit.finish()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", "body_composition.fit",
                fitBytes.toRequestBody("application/octet-stream".toMediaType())
            )
            .build()

        // One retry on 401: the cached bearer looked valid but the server disagrees.
        var lastCode = 0
        for (attempt in 1..2) {
            val req = Request.Builder()
                .url(UPLOAD_URL)
                .header("Authorization", "Bearer ${bearer()}")
                .header("User-Agent", GCM_USER_AGENT)
                .post(requestBody)
                .build()
            client.newCall(req).execute().use { resp ->
                lastCode = resp.code
                if (resp.isSuccessful) {
                    Timber.i("Garmin: uploaded measurement %s (%.1f kg)", m.date, m.weight)
                    return
                }
            }
            if (lastCode == 401 && attempt == 1) {
                Timber.d("Garmin: 401 on upload -> refreshing token and retrying once")
                auth.invalidateAccessToken()
            } else {
                break
            }
        }
        throw UploadException("Garmin upload failed: HTTP $lastCode")
    }

    /**
     * Delete the weigh-in(s) at [date]: fetch the day and remove entries whose UTC
     * timestamp is within [MATCH_WINDOW_MS] of the measurement (or the only entry of
     * the day when it is the single one). Best-effort for the reconcile/move flow.
     */
    suspend fun delete(date: Date) {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
        val dayReq = Request.Builder()
            .url("$WEIGHT_URL/dayview/$day?includeAll=true")
            .header("Authorization", "Bearer ${bearer()}")
            .header("User-Agent", GCM_USER_AGENT)
            .build()

        val entries = client.newCall(dayReq).execute().use { resp ->
            if (!resp.isSuccessful) return   // nothing we can do; nothing to delete either
            val json = JsonParser.parseString(resp.body.string()).asJsonObject
            json.getAsJsonArray("dateWeightList")?.toList() ?: emptyList()
        }
        if (entries.isEmpty()) return

        val gmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.S", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val matches = entries.mapNotNull { e ->
            val o = e.asJsonObject
            val pk = o.get("samplePk")?.asLong ?: return@mapNotNull null
            val ts = o.get("timestampGMT")?.asString?.let {
                runCatching { gmt.parse(it)?.time }.getOrNull()
            }
            if (ts != null && kotlin.math.abs(ts - date.time) <= MATCH_WINDOW_MS) pk
            else null
        }.ifEmpty {
            if (entries.size == 1)
                listOfNotNull(entries[0].asJsonObject.get("samplePk")?.asLong)
            else emptyList()
        }

        for (pk in matches) {
            val delReq = Request.Builder()
                .url("$WEIGHT_URL/weight/$day/byversion/$pk")
                .header("Authorization", "Bearer ${bearer()}")
                .header("User-Agent", GCM_USER_AGENT)
                .delete()
                .build()
            client.newCall(delReq).execute().use { resp ->
                Timber.d("Garmin: delete weigh-in %d -> HTTP %d", pk, resp.code)
            }
        }
    }

    companion object {
        private const val DOMAIN = "garmin.com"
        private const val GCM_USER_AGENT = "com.garmin.android.apps.connectmobile"
        private const val UPLOAD_URL = "https://connectapi.$DOMAIN/upload-service/upload"
        private const val WEIGHT_URL = "https://connectapi.$DOMAIN/weight-service/weight"
        private const val MATCH_WINDOW_MS = 15 * 60 * 1000L
    }
}
