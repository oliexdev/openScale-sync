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


import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.service.SyncResult
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import timber.log.Timber
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeSet

class WgerSync(wgerRetrofit: Retrofit) : SyncInterface() {
    private val wgerApi : WgerApi = wgerRetrofit.create(WgerApi::class.java)
    // Locale.US: API wire format — must stay ASCII regardless of the device locale
    private val wgerDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply { timeZone = TimeZone.getDefault() }

    /** The weight unit of the wger profile, read once per connection (see [profileUsesPounds]). */
    private var pounds: Boolean? = null

    /**
     * Whether the profile is set to pounds. wger reads and writes every weight in the unit of the
     * user's profile — up to 2.6 it stores the bare number and labels it with that unit, from 2.7 on
     * it converts — so 80 kg sent as is would become 80 lb. A profile without the field is kg.
     */
    private suspend fun profileUsesPounds(): Boolean = pounds ?: run {
        // Answered as the profile itself, or as a list holding it.
        val profile = wgerApi.userProfile().let { p ->
            p.getAsJsonArray("results")?.firstOrNull()?.asJsonObject ?: p
        }
        (profile.get("weight_unit")?.takeIf { it.isJsonPrimitive }?.asString == "lb").also { pounds = it }
    }

    /** openScale's kg in the profile's unit, at the 2 decimals wger accepts (it rejects more). */
    private suspend fun toWger(kg: Float): Float =
        roundForWger(if (profileUsesPounds()) kg / KG_PER_LB else kg)

    /** A weight wger reports, in kg. */
    private suspend fun fromWger(weight: Float): Float =
        if (profileUsesPounds()) weight * KG_PER_LB else weight

    /**
     * Writes [measurement] as an upsert: the entry already sitting at its time is patched, otherwise
     * a new one is posted. wger 2.4 on keeps any number of entries per date, so a blind POST for a
     * time it already has — an entry we imported from it, or one a lost ledger forgot we sent —
     * would leave a duplicate there.
     */
    suspend fun insert(measurement: OpenScaleMeasurement) : SyncResult<Unit> = upsert(measurement)

    private suspend fun upsert(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
            try {
                val entry = WgerWeightEntryRequest(wgerDateFormat.format(measurement.date), toWger(measurement.weight))
                val existing = findEntry(measurement.date)
                val response: Response<Unit> =
                    if (existing != null) wgerApi.update(existing.id, entry) else wgerApi.insert(entry)
                if (response.isSuccessful) {
                    return SyncResult.Success(Unit)
                } else {
                    return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"wger ${wgerDateFormat.format(measurement.date)} ${if (existing != null) "update" else "insert"} response error ${response.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
            }
    }

    /**
     * The entry at [date]. wger stores times to the microsecond and openScale to the millisecond,
     * so an entry is matched by the second it falls in rather than by an exact timestamp. The hits
     * are checked against that window here as well: wger up to 2.2 has no range lookup and ignores
     * the unknown filter, answering with every entry — patching the first of those would overwrite
     * an unrelated weigh-in.
     */
    private suspend fun findEntry(date: Date): WgerWeightEntry? {
        val window = date.time until date.time + MATCH_WINDOW_MS
        val hits = wgerApi.getWeightEntriesBetween(
            wgerDateFormat.format(date), wgerDateFormat.format(Date(window.last + 1))
        ).results.orEmpty().filter { e -> e.date?.let { parseWgerDate(it) } in window }
        if (hits.size > 1) {
            Timber.w("wger: %d entries at %s, using the first", hits.size, wgerDateFormat.format(date))
        }
        return hits.firstOrNull()
    }

    suspend fun delete(date: Date) : SyncResult<Unit> {
        try {
            val existing = findEntry(date)
            if (existing != null) {
                val response: Response<Unit> = wgerApi.delete(existing.id)
                if (response.isSuccessful) {
                    return SyncResult.Success(Unit)
                } else {
                    return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"wger delete response error ${response.errorBody()?.string()}}")
                }
            } else {
                return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"no weight entry found for date: ${wgerDateFormat.format(date)}")
            }
        } catch (e: Exception) {
            return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
        }
    }

    suspend fun clear() : SyncResult<Unit> {
            try {
                var wgerWeightEntryList = wgerApi.weightEntryList()

                do {
                    wgerWeightEntryList.results?.forEach { wgerWeightEntry ->
                        val response: Response<Unit> = wgerApi.delete(wgerWeightEntry.id)
                        if (!response.isSuccessful) {
                            return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"wger delete response error ${response.errorBody()?.string()}}")
                        }
                    }

                    wgerWeightEntryList = wgerApi.weightEntryList()
                } while (wgerWeightEntryList.count != 0L)

                return SyncResult.Success(Unit)
            } catch (e: Exception) {
                return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
            }
    }

    /** Same upsert as [insert]: a missing entry is recreated instead of failing for good, which is
     *  what a forced full sync needs when the entry was deleted on the wger side. */
    suspend fun update(measurement: OpenScaleMeasurement) : SyncResult<Unit> = upsert(measurement)

    /** One weight entry read from wger: its time in openScale's epoch millis, and — when it carries
     *  a date only, [timeMs] then being midnight rather than a real time — that date
     *  (see [dateOnlyDay]). */
    data class WgerReading(val timeMs: Long, val weightKg: Float, val day: LocalDate? = null)

    /**
     * Inbound (bidirectional): read weight entries from Wger, at the time wger has them — so that
     * exporting such a measurement back finds and patches the very entry it came from instead of
     * adding a second one. Wger has no data origin, so the caller decides which of them openScale
     * lacks (see [unknownToOpenScale]). Weights come back in kg, whatever unit the profile uses.
     */
    suspend fun readInboundWeights(): List<WgerReading> {
        // Every page, not just the first: a longer history was silently cut off. A failing page
        // throws, so the caller writes nothing rather than an arbitrary part of the history.
        val entries = ArrayList<WgerWeightEntry>()
        do {
            val page = wgerApi.getWeightEntries(limit = PAGE_SIZE, offset = entries.size)
            val results = page.results.orEmpty()
            entries += results
        } while (page.next != null && results.isNotEmpty())
        return entries.mapNotNull { e ->
            val date = e.date ?: return@mapNotNull null
            val timeMs = parseWgerDate(date) ?: return@mapNotNull null
            WgerReading(timeMs, fromWger(e.weight), dateOnlyDay(date))
        }
    }

    /**
     * The date of an entry without a real time, or null for a real time: what wger up to 2.3
     * returns, and what its 2.4 migration turned those entries into — the date column became a
     * date-time one, every old entry landing on midnight (in UTC, or the server's zone). Such an
     * entry looks like a real time and would never match the measurement it stands for, which
     * weighed in at 07:12, so it would come in again as a second one. Nobody weighs in at exactly
     * 00:00:00.000. The date is taken where the entry IS midnight: read in the device's zone,
     * midnight UTC is still the previous evening anywhere west of Greenwich.
     */
    private fun dateOnlyDay(date: String): LocalDate? {
        val time = runCatching { OffsetDateTime.parse(date) }.getOrNull()
            ?: return runCatching { LocalDate.parse(date) }.getOrNull()
        return listOf(
            time.toLocalDateTime(),
            time.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),
            time.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
        ).firstOrNull { it.toLocalTime() == LocalTime.MIDNIGHT }?.toLocalDate()
    }

    /** An ISO date-time (wger 2.4 on, any fraction and offset) or a plain date (up to 2.3). */
    private fun parseWgerDate(date: String): Long? =
        runCatching { OffsetDateTime.parse(date).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()

    interface WgerApi {
        @GET("weightentry")
        suspend fun weightEntryList(): WgerWeightEntryList

        @GET("weightentry/")
        suspend fun getWeightEntries(@Query("limit") limit: Int, @Query("offset") offset: Int): WgerWeightEntryList

        @GET("weightentry/")
        suspend fun getWeightEntriesBetween(
            @Query("date__gte") from: String,
            @Query("date__lt") until: String
        ): WgerWeightEntryList

        @GET("userprofile/")
        suspend fun userProfile(): JsonObject

        @POST("weightentry/")
        suspend fun insert(@Body entry: WgerWeightEntryRequest): Response<Unit>

        @PATCH("weightentry/{wger_id}/")
        suspend fun update(
            @Path("wger_id") wgerId: String,
            @Body entry: WgerWeightEntryRequest
        ): Response<Unit>

        @DELETE("weightentry/{wger_id}/")
        suspend fun delete(@Path("wger_id") wgerId: String) : Response<Unit>
    }

    data class WgerWeightEntryList(
        @SerializedName("count")
        val count: Long = -1,
        @SerializedName("next")
        val next: String? = null,
        @SerializedName("previous")
        val previous: String? = null,
        @SerializedName("results")
        val results: List<WgerWeightEntry>? = null
    )

    data class WgerWeightEntry(
        // String, not a number: wger 2.7 moved weight entries into the measurements
        // and the id exposed over the compat endpoint is a UUID. This makes sure
        // the code works with both versions
        @SerializedName("id")
        val id: String = "",
        @SerializedName("date")
        val date: String? = null,
        @SerializedName("weight")
        val weight: Float = 0f
    )

    // Sent as JSON: wger 2.7 accepts only JSON. Older servers accept JSON as well
    data class WgerWeightEntryRequest(
        @SerializedName("date")
        val date: String,
        @SerializedName("weight")
        val weight: Float
    )

    companion object {
        /** An entry belongs to a measurement when it falls in the same second. */
        private const val MATCH_WINDOW_MS = 1000L
        /** Entries per inbound request; the server may cap it lower, paging covers the rest. */
        private const val PAGE_SIZE = 999
        /** The international avoirdupois pound. */
        private const val KG_PER_LB = 0.45359237f

        internal fun roundForWger(weight: Float): Float =
            BigDecimal(weight.toString()).setScale(2, RoundingMode.HALF_UP).toFloat()

        /**
         * The [readings] openScale has no measurement for yet — openScale stays master, so a time
         * it already has is left alone rather than overwritten. Matched by the same second the
         * export side uses, so several weigh-ins of one day all come in, and our own exports, which
         * come back from wger at the time we sent, are recognised as known. An entry of a date-only
         * server sits at midnight rather than at a real time; that one is matched by its day, the
         * only thing it says.
         */
        fun unknownToOpenScale(readings: List<WgerReading>, openScaleTimes: Collection<Long>): List<WgerReading> {
            val times = TreeSet(openScaleTimes)
            val zone = ZoneId.systemDefault()
            fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            val days = openScaleTimes.mapTo(HashSet()) { day(it) }
            return readings.filterNot { r ->
                if (r.day != null) r.day in days
                // openScale has a measurement in (timeMs - window, timeMs]: r is that measurement's entry.
                else times.subSet(r.timeMs - MATCH_WINDOW_MS + 1, true, r.timeMs, true).isNotEmpty()
            }
        }
    }
}


