/*
 *  Copyright (C) 2026  Mirko Seipel
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
import com.google.gson.annotations.SerializedName
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.service.SyncResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Wire layer for SparkyFitness (self-hosted fitness/nutrition tracker), export only.
 *
 * Auth: a SparkyFitness API key (Settings → API keys) sent as `x-api-key` on every request. The key
 * must not be read-only, otherwise every write is rejected with 403.
 *
 * Data model on the server side:
 *  - Body measurements live in one **check-in row per day** (`/measurements/check-in`). POST is an
 *    upsert by `entry_date` that only touches the columns present in the body, so values the user
 *    entered by hand on the same day (steps, height, …) are left alone.
 *  - Everything without a check-in column goes into **custom categories** (frequency `Daily`) with
 *    `source = "openScale"`. A type SparkyFitness already knows (e.g. lean body mass) is written
 *    under SparkyFitness' own name, so it lands next to the values other integrations deliver
 *    instead of in a parallel category; only unknown types get a category named after openScale. The source keeps our entries apart from manual
 *    ones and from other integrations, so deletes only ever remove what this app wrote.
 *
 * Like Endurain, SparkyFitness keys body data by calendar day, so several openScale measurements on
 * the same day collapse to the last one written.
 *
 * @param serverOrigin bare origin the user entered (e.g. https://sparky.example.com); the
 *  `/api/measurements/` prefix is appended here.
 * @param includeDerived also export values openScale computes itself (BMI, WHR, WHtR, …) as custom
 *  categories; read on every upsert so a settings change applies without reconnecting. Estimated
 *  BMR and TDEE are never exported, see [buildPayload].
 * @param transport test hook: an interceptor that answers requests without a network. It runs
 *  after the API key is set, so tests also see the auth header. Production callers leave it null.
 */
class SparkyFitnessSync(
    serverOrigin: String,
    apiKey: String,
    private val includeDerived: () -> Boolean,
    transport: Interceptor? = null
) : SyncInterface() {

    // Locale.US: API wire format, must stay ASCII regardless of device locale. SparkyFitness'
    // `entry_date` is a calendar date (YYYY-MM-DD), formatted in the device's local time zone.
    private val dateOnly = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }

    private val api: SparkyApi

    // name → id, filled lazily once per sync instance; categories are never renamed by us.
    private var categoryIds: MutableMap<String, String>? = null

    init {
        // Strip control characters (e.g. a trailing newline from a pasted key) — OkHttp rejects
        // header values containing them and would crash the call thread.
        val key = apiKey.filterNot { it.isISOControl() }.trim()
        val baseUrl = serverOrigin.trim().trimEnd('/') + "/api/measurements/"
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("x-api-key", key).build())
            }
            .apply { transport?.let { addInterceptor(it) } }
            .build()
        api = Retrofit.Builder()
            .client(http)
            .baseUrl(baseUrl)
            // serializeNulls: a delete is an upsert that sets our columns to null, so nulls must
            // reach the wire. Upsert bodies are maps that simply leave absent values out.
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().serializeNulls().create()))
            .build()
            .create(SparkyApi::class.java)
    }

    // --- Connectivity / permission check ------------------------------------------------

    /** GET the category list to verify the server, the key and the check-in read permission. */
    suspend fun testConnection(): SyncResult<Unit> = try {
        val resp = api.getCategories()
        when {
            resp.isSuccessful -> {
                categoryIds = resp.body().orEmpty().associate { it.name to it.id }.toMutableMap()
                SyncResult.Success(Unit)
            }
            resp.code() == 401 || resp.code() == 403 ->
                SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED, "HTTP ${resp.code()}")
            else -> SyncResult.Failure(SyncResult.ErrorType.API_ERROR, httpError("connect", resp))
        }
    } catch (e: Exception) {
        SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, null, e)
    }

    // --- Export -------------------------------------------------------------------------

    /**
     * Insert and update are the same operation: both endpoints are upserts by day.
     *
     * The sync service dispatches every openScale event in its own coroutine, so several
     * measurements of one day can arrive at the same time. Writes are therefore serialized
     * ([writeLock]), and a 409 on the check-in is retried once: SparkyFitness before v1.9.0 can
     * reject the second of two concurrent first writes of a day on its unique (user, date) index,
     * although by then the row exists and the same upsert succeeds.
     */
    suspend fun upsert(measurement: OpenScaleMeasurement): SyncResult<Unit> = try {
        writeLock.withLock { upsertLocked(measurement) }
    } catch (e: Exception) {
        SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, null, e)
    }

    private suspend fun upsertLocked(measurement: OpenScaleMeasurement): SyncResult<Unit> {
        val day = dateOnly.format(measurement.date)
        val payload = buildPayload(measurement, includeDerived())
        val body = payload.checkIn + ("entry_date" to day)

        val checkIn = api.upsertCheckIn(body).let { first ->
            if (first.code() == 409) {
                first.errorBody()?.close()
                api.upsertCheckIn(body)
            } else first
        }
        return if (!checkIn.isSuccessful) {
            failure("check-in $day upsert", checkIn)
        } else {
            var result: SyncResult<Unit> = SyncResult.Success(Unit)
            for (v in payload.custom) {
                val categoryId = ensureCategory(v)
                if (categoryId == null) {
                    result = SyncResult.Failure(SyncResult.ErrorType.API_ERROR, "sparkyfitness: no category for ${v.name}")
                    break
                }
                val resp = api.upsertCustomEntry(
                    CustomEntryRequest(categoryId, v.value, day, SOURCE)
                )
                if (!resp.isSuccessful) {
                    result = failure("custom entry ${v.name} $day upsert", resp)
                    break
                }
            }
            result
        }
    }

    /**
     * Remove what this app wrote for [date]: our check-in columns are set to null (the row itself
     * stays, it may hold values entered by hand), and our custom entries of that day are deleted.
     */
    suspend fun delete(date: Date): SyncResult<Unit> = try {
        writeLock.withLock { deleteLocked(date) }
    } catch (e: Exception) {
        SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, null, e)
    }

    private suspend fun deleteLocked(date: Date): SyncResult<Unit> {
        val day = dateOnly.format(date)
        val existing = api.getCheckInRange(day, day)
        return when {
            !existing.isSuccessful -> failure("check-in $day lookup", existing)
            else -> {
                // Only touch an existing row: an all-null upsert would otherwise create an empty one.
                val hasRow = existing.body().orEmpty().isNotEmpty()
                val cleared = if (hasRow) api.upsertCheckIn(clearCheckInBody(day)) else null
                if (cleared != null && !cleared.isSuccessful) failure("check-in $day delete", cleared)
                else deleteCustomEntries(day)
            }
        }
    }

    /**
     * Delete every custom entry this app wrote. Check-in columns are deliberately left in place:
     * SparkyFitness does not record who wrote a check-in value, so a blanket reset would also wipe
     * weights the user entered by hand or another integration delivered.
     */
    suspend fun clear(): SyncResult<Unit> = try {
        writeLock.withLock {
            val resp = api.getAllCustomEntries()
            if (!resp.isSuccessful) failure("custom entries list", resp)
            else deleteEntries(resp.body().orEmpty())
        }
    } catch (e: Exception) {
        SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, null, e)
    }

    private suspend fun deleteCustomEntries(day: String): SyncResult<Unit> {
        val resp = api.getCustomEntriesByDate(day)
        return if (!resp.isSuccessful) failure("custom entries $day lookup", resp)
        else deleteEntries(resp.body().orEmpty())
    }

    private suspend fun deleteEntries(entries: List<CustomEntry>): SyncResult<Unit> {
        for (e in entries.filter { it.source == SOURCE }) {
            val del = api.deleteCustomEntry(e.id)
            // 404: already gone (e.g. deleted in the app meanwhile) — keeps deletes idempotent.
            if (!del.isSuccessful && del.code() != 404) return failure("custom entry delete", del)
        }
        return SyncResult.Success(Unit)
    }

    /** Category id for [v], creating the category on first use. Null if the server refuses. */
    private suspend fun ensureCategory(v: CustomValue): String? {
        val known = categoryIds ?: api.getCategories().takeIf { it.isSuccessful }?.body().orEmpty()
            .associate { it.name to it.id }.toMutableMap().also { categoryIds = it }
        known[v.name]?.let { return it }

        val created = api.createCategory(
            CategoryRequest(
                name = v.name,
                display_name = v.displayName,
                frequency = "Daily",
                measurement_type = v.unit.ifBlank { "N/A" },
                data_type = "numeric"
            )
        )
        val id = created.body()?.id
            // 409: someone created it in between (or our cache was stale) → re-read the list.
            ?: api.getCategories().takeIf { it.isSuccessful }?.body()?.firstOrNull { it.name == v.name }?.id
        if (id != null) known[v.name] = id
        return id
    }

    private fun <T> failure(what: String, resp: Response<T>): SyncResult.Failure =
        SyncResult.Failure(
            // A 400 is a validation error for this measurement: retrying the same values never
            // helps, so it must not block the retry queue.
            if (resp.code() == 400) SyncResult.ErrorType.INVALID_DATA else SyncResult.ErrorType.API_ERROR,
            httpError(what, resp)
        )

    private fun <T> httpError(what: String, resp: Response<T>): String =
        "sparkyfitness $what error HTTP ${resp.code()}: ${resp.errorBody()?.string()?.take(200)}"

    // --- Retrofit contract + DTOs -------------------------------------------------------

    interface SparkyApi {
        @POST("check-in")
        suspend fun upsertCheckIn(@Body body: Map<String, @JvmSuppressWildcards Any?>): Response<ResponseBody>

        @GET("check-in-measurements-range/{start}/{end}")
        suspend fun getCheckInRange(@Path("start") start: String, @Path("end") end: String): Response<List<CheckInRow>>

        @GET("custom-categories")
        suspend fun getCategories(): Response<List<Category>>

        @POST("custom-categories")
        suspend fun createCategory(@Body body: CategoryRequest): Response<CreatedId>

        @POST("custom-entries")
        suspend fun upsertCustomEntry(@Body body: CustomEntryRequest): Response<ResponseBody>

        @GET("custom-entries/{date}")
        suspend fun getCustomEntriesByDate(@Path("date") date: String): Response<List<CustomEntry>>

        @GET("custom-entries")
        suspend fun getAllCustomEntries(@Query("limit") limit: Int? = null): Response<List<CustomEntry>>

        @DELETE("custom-entries/{id}")
        suspend fun deleteCustomEntry(@Path("id") id: String): Response<ResponseBody>
    }

    data class CheckInRow(@SerializedName("entry_date") val entry_date: String? = null)

    data class Category(
        @SerializedName("id") val id: String,
        @SerializedName("name") val name: String
    )

    data class CategoryRequest(
        @SerializedName("name") val name: String,
        @SerializedName("display_name") val display_name: String?,
        @SerializedName("frequency") val frequency: String,
        @SerializedName("measurement_type") val measurement_type: String,
        @SerializedName("data_type") val data_type: String
    )

    data class CreatedId(@SerializedName("id") val id: String? = null)

    data class CustomEntryRequest(
        @SerializedName("category_id") val category_id: String,
        @SerializedName("value") val value: Float,
        @SerializedName("entry_date") val entry_date: String,
        @SerializedName("source") val source: String
    )

    data class CustomEntry(
        @SerializedName("id") val id: String,
        @SerializedName("source") val source: String? = null
    )

    /** One openScale value bound for a custom category. */
    data class CustomValue(val name: String, val displayName: String?, val unit: String, val value: Float)

    /** What one measurement turns into on the wire: check-in columns plus custom values. */
    data class Payload(val checkIn: Map<String, Float>, val custom: List<CustomValue>)

    companion object {
        /** `source` of every custom entry we write; deletes are scoped to it. */
        const val SOURCE = "openScale"

        // Process-wide, not per instance: connect() builds a new instance, and an op still running
        // on the old one must not overlap with the first op on the new one.
        private val writeLock = Mutex()

        // SparkyFitness accepts a measured BMR only inside this range (numeric(6,1) column,
        // MIN/MAX_MEASURED_BMR_KCAL server-side); anything else fails the whole check-in with 400.
        private const val MIN_BMR_KCAL = 715f
        private const val MAX_BMR_KCAL = 7250f

        /** Check-in columns this app owns; a delete sets exactly these to null. */
        val CHECK_IN_COLUMNS = listOf(
            "weight", "body_fat_percentage", "body_water_percentage", "muscle_mass_kg",
            "bone_mass_kg", "bmr", "waist", "hips", "neck"
        )

        /**
         * openScale keys that SparkyFitness knows as a health type of its own, mapped to SparkyFitness'
         * name (DEFAULT_UNITS_BY_HEALTH_TYPE in SparkyFitnessServer/services/healthDataHandlers.ts).
         * Those categories are created by SparkyFitness without a display name, so we do the same.
         * Units must match: lean body mass is kg on both sides.
         */
        val SPARKY_TYPES = mapOf("lbm" to "lean_body_mass")

        // Readable names for openScale's built-in types, whose `name` is only the enum key
        // (e.g. VISCERAL_FAT). Users can rename a category in SparkyFitness; we never overwrite it.
        private val DISPLAY_NAMES = mapOf(
            "bmi" to "BMI",
            "whr" to "Waist-to-hip ratio",
            "whtr" to "Waist-to-height ratio",
            "impedance" to "Impedance (scale raw value)",
            "visceral_fat" to "Visceral fat"
        )

        /** Category display name: our label for built-ins, the user's own name for custom types. */
        fun displayName(key: String, name: String): String? {
            if (key in SPARKY_TYPES) return null
            DISPLAY_NAMES[key]?.let { return it }
            val isEnumKey = name.isBlank() || name.equals(key, ignoreCase = true) ||
                name.equals(key.replace('_', ' '), ignoreCase = true)
            return if (isEnumKey) key.replace('_', ' ').replaceFirstChar { it.uppercase() } else name
        }

        /**
         * openScale keys never exported as custom values even with [includeDerived]: BMR and TDEE
         * are formula estimates in openScale, and SparkyFitness would mix them into its calorie
         * and adaptive-TDEE calculations as if they were measured.
         */
        private val NEVER_CUSTOM = setOf("bmr", "tdee")

        /** Body of a delete: all our check-in columns set to null. */
        fun clearCheckInBody(day: String): Map<String, Any?> =
            CHECK_IN_COLUMNS.associateWith<String, Any?> { null } + ("entry_date" to day)

        /**
         * Maps an openScale measurement to SparkyFitness. Reads the self-describing generic
         * [OpenScaleMeasurement.values] (canonical units: kg, cm, %, kcal) rather than the
         * convenience fields. Pure, so it is unit-testable without a network client.
         *
         *  - weight/masses in kg, fat/water in % (converted via the weight where openScale holds
         *    the other unit). Note SparkyFitness' own `water` means drinking water in ml — body
         *    water must go to `body_water_percentage`.
         *  - `bmr` only when the scale measured it (not derived) and it lies inside the server's
         *    accepted range.
         *  - waist/hips/neck in cm.
         *  - every other numeric value becomes a custom category named after its backend key;
         *    derived values only with [includeDerived], BMR/TDEE never.
         */
        fun buildPayload(m: OpenScaleMeasurement, includeDerived: Boolean): Payload {
            val byKey = m.values.associateBy { it.backendKey() }
            val weight = byKey["weight"]?.value?.takeIf { it > 0f } ?: m.weight.takeIf { it > 0f }

            fun measured(key: String) = byKey[key]?.takeIf { !it.isDerived }?.value

            fun asPercent(key: String): Float? {
                val v = byKey[key]?.takeIf { !it.isDerived } ?: return null
                val value = v.value ?: return null
                return when {
                    v.unit == "%" -> value
                    weight != null -> value / weight * 100f
                    else -> null
                }
            }

            fun asKg(key: String): Float? {
                val v = byKey[key]?.takeIf { !it.isDerived } ?: return null
                val value = v.value ?: return null
                return when {
                    v.unit == "kg" -> value
                    weight != null -> weight * value / 100f
                    else -> null
                }
            }

            val checkIn = linkedMapOf<String, Float?>(
                "weight" to weight,
                "body_fat_percentage" to asPercent("body_fat")?.takeIf { it in 0f..100f },
                "body_water_percentage" to asPercent("water")?.takeIf { it in 0f..100f },
                "muscle_mass_kg" to asKg("muscle"),
                "bone_mass_kg" to asKg("bone"),
                "bmr" to measured("bmr")?.takeIf { it in MIN_BMR_KCAL..MAX_BMR_KCAL },
                "waist" to measured("waist"),
                "hips" to measured("hips"),
                "neck" to measured("neck")
            ).filterValues { it != null && it.isFinite() && it > 0f }.mapValues { it.value!! }

            // Keys whose value went (or would go) into a check-in column.
            val consumed = setOf("weight", "body_fat", "water", "muscle", "bone", "bmr", "waist", "hips", "neck")

            val custom = m.values.mapNotNull { v ->
                val key = v.backendKey()
                val value = v.value
                when {
                    key in consumed && !v.isDerived -> null
                    key in NEVER_CUSTOM -> null
                    v.isDerived && !includeDerived -> null
                    value == null || !value.isFinite() -> null   // text values (comment) and gaps
                    else -> CustomValue(SPARKY_TYPES[key] ?: key, displayName(key, v.name), v.unit, value)
                }
            }
            return Payload(checkIn, custom)
        }
    }
}
