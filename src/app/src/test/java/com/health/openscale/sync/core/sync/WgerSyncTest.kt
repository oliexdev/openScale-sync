/*
 *  Copyright (C) 2026  Roland Geider
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

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.service.SyncResult
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Date

/**
 * Covers the wire format of the wger weight entry endpoint: the entry list parses
 * with the UUID ids of wger 2.7 on and the integer ids before, and entries are
 * written as JSON, the only request body wger 2.7 accepts.
 */
class WgerSyncTest {

    private val gson = Gson()

    private fun responseWithId(id: String, date: String = ENTRY_DATE) =
        """{"count":1,"next":null,"previous":null,"results":""" +
            """[{"id":$id,"date":"$date","weight":77.5}]}"""

    private fun parseSingleEntry(id: String): WgerSync.WgerWeightEntry {
        val list = gson.fromJson(responseWithId(id), WgerSync.WgerWeightEntryList::class.java)
        val results = list.results
        assertEquals(1, results?.size)
        return results!![0]
    }

    @Test
    fun uuidId_ofCurrentServers_parses() {
        val entry = parseSingleEntry("\"0198f3a1-8b2c-7def-9012-3456789abcde\"")

        assertEquals("0198f3a1-8b2c-7def-9012-3456789abcde", entry.id)
    }

    @Test
    fun integerId_ofOlderServers_parsesAsItsLiteral() {
        val entry = parseSingleEntry("4711")

        assertEquals("4711", entry.id)
    }

    private val uuid = "0198f3a1-8b2c-7def-9012-3456789abcde"

    /** A measurement at a time wger has no entry for. */
    private val measurement = OpenScaleMeasurement(1, 1, Date(0L), 77.5f, 0f, 0f, 0f, 0f, 0f)

    /** A measurement at the time of the entry the server answers with. */
    private val measurementAtEntry = measurement.copy(date = Date(ENTRY_MS))

    /**
     * A [WgerSync] whose requests are recorded and answered without any network. Every GET answers
     * with one entry at [entryDate] — whatever the filter asked for, the way a server that ignores
     * an unknown filter does.
     */
    private fun recordingSync(
        requests: MutableList<Request>, entryDate: String = ENTRY_DATE, unit: String = "kg"
    ): WgerSync {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = when {
                request.url.encodedPath.endsWith("userprofile/") -> """{"weight_unit":"$unit"}"""
                request.method == "GET" -> responseWithId("\"$uuid\"", entryDate)
                else -> "{}"
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (request.method == "POST") 201 else 200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val retrofit = Retrofit.Builder()
            .client(client)
            .baseUrl("https://wger.example/api/v2/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return WgerSync(retrofit)
    }

    /** The weight entry requests, without the one-off profile read for the unit. */
    private fun entryRequests(requests: List<Request>) =
        requests.filterNot { it.url.encodedPath.endsWith("userprofile/") }

    private fun assertJsonEntry(request: Request) {
        val body = request.body!!
        assertEquals("application", body.contentType()?.type)
        assertEquals("json", body.contentType()?.subtype)

        val json = JsonParser.parseString(Buffer().also { body.writeTo(it) }.readUtf8()).asJsonObject
        assertEquals(setOf("date", "weight"), json.keySet())
        assertEquals(77.5f, json["weight"].asFloat, 0.0001f)
    }

    @Test
    fun insert_postsJson() = runTest {
        val requests = mutableListOf<Request>()

        val result = recordingSync(requests).insert(measurement)

        assertTrue(result is SyncResult.Success)
        val post = requests.last()               // after the lookup that found no entry at that time
        assertEquals("POST", post.method)
        assertEquals("/api/v2/weightentry/", post.url.encodedPath)
        assertJsonEntry(post)
    }

    @Test
    fun update_patchesJson_toTheUuidOfTheEntry() = runTest {
        val requests = mutableListOf<Request>()

        val result = recordingSync(requests).update(measurementAtEntry)

        assertTrue(result is SyncResult.Success)
        val patch = requests.last()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/v2/weightentry/$uuid/", patch.url.encodedPath)
        assertJsonEntry(patch)
    }

    @Test
    fun insert_ofATimeWgerAlreadyHas_patchesThatEntry_insteadOfPostingADuplicate() = runTest {
        // An entry imported from wger and exported back: wger 2.4 on would keep a second one.
        val requests = mutableListOf<Request>()

        val result = recordingSync(requests).insert(measurementAtEntry)

        assertTrue(result is SyncResult.Success)
        assertEquals(listOf("GET", "PATCH"), entryRequests(requests).map { it.method })
        assertEquals("/api/v2/weightentry/$uuid/", requests.last().url.encodedPath)
    }

    @Test
    fun entryLookup_asksForTheSecondTheMeasurementFallsIn() = runTest {
        val requests = mutableListOf<Request>()

        recordingSync(requests).insert(measurementAtEntry)

        val lookup = entryRequests(requests).first().url
        val from = OffsetDateTime.parse(lookup.queryParameter("date__gte")).toInstant().toEpochMilli()
        val until = OffsetDateTime.parse(lookup.queryParameter("date__lt")).toInstant().toEpochMilli()
        assertEquals(ENTRY_MS, from)
        assertEquals(ENTRY_MS + 1000, until)
    }

    @Test
    fun entryWithMicroseconds_isStillFound() = runTest {
        // wger keeps microseconds, openScale milliseconds: the same weigh-in, 0.456 ms apart.
        val requests = mutableListOf<Request>()

        recordingSync(requests, entryDate = "2026-08-28T07:30:00.123456+02:00")
            .insert(measurementAtEntry.copy(date = Date(ENTRY_MS + 123)))

        assertEquals("PATCH", requests.last().method)
    }

    @Test
    fun entryOutsideTheWindow_isNeverPatched_evenWhenTheServerReturnsIt() = runTest {
        // wger up to 2.2 ignores the range filter and answers with every entry.
        val requests = mutableListOf<Request>()

        recordingSync(requests).insert(measurement)

        assertEquals(listOf("GET", "POST"), entryRequests(requests).map { it.method })
    }

    @Test
    fun inbound_keepsWgersTime_insteadOfMidnight() = runTest {
        val readings = recordingSync(mutableListOf()).readInboundWeights()

        assertEquals(listOf(WgerSync.WgerReading(ENTRY_MS, 77.5f)), readings)
    }

    @Test
    fun inbound_ofADateOnlyEntry_isAtLocalMidnight() = runTest {
        val readings = recordingSync(mutableListOf(), entryDate = "2026-08-28").readInboundWeights()

        val midnight = LocalDate.of(2026, 8, 28).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(listOf(WgerSync.WgerReading(midnight, 77.5f, LocalDate.of(2026, 8, 28))), readings)
    }

    @Test
    fun liveResponseFormat_ofWger28_parses() {
        // The shape wger.de (2.8.0-dev) answers with: microseconds in the date, the weight as a string.
        val json = """{"count":1,"next":null,"previous":null,"results":[{"id":"$uuid",""" +
            """"date":"2001-01-01T07:30:00.123000+01:00","weight":"77.50","user":1}]}"""

        val entry = gson.fromJson(json, WgerSync.WgerWeightEntryList::class.java).results!!.single()

        assertEquals(77.5f, entry.weight, 0.0001f)
        assertEquals("2001-01-01T07:30:00.123000+01:00", entry.date)
    }

    @Test
    fun anEntryMigratedFromADateOnlyServer_countsAsDateOnly() = runTest {
        // wger 2.4 turned every old date into midnight UTC; the server returns it in its own zone.
        val readings = recordingSync(mutableListOf(), entryDate = "2025-03-05T01:00:00+01:00").readInboundWeights()

        assertEquals(LocalDate.of(2025, 3, 5), readings.single().day)
    }

    @Test
    fun aMigratedEntry_keepsItsDate_westOfGreenwich() = runTest {
        // Midnight UTC is the previous evening in New York; the entry still stands for March 5th.
        val readings = recordingSync(mutableListOf(), entryDate = "2025-03-04T19:00:00-05:00").readInboundWeights()

        assertEquals(LocalDate.of(2025, 3, 5), readings.single().day)
    }

    @Test
    fun aRealTime_isNotDateOnly() = runTest {
        assertNull(recordingSync(mutableListOf()).readInboundWeights().single().day)
    }

    /**
     * A [WgerSync] over a server that pages its entries [pageSize] at a time, the way wger's
     * limit/offset pagination does when it caps the limit; [failAtOffset] answers that page with 500.
     */
    private fun pagingSync(
        dates: List<String>, pageSize: Int, requests: MutableList<Request>, failAtOffset: Int? = null
    ): WgerSync {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val offset = request.url.queryParameter("offset")?.toInt() ?: 0
            val page = dates.drop(offset).take(pageSize)
            val next = if (offset + page.size < dates.size) "\"https://wger.example/next\"" else "null"
            val body = if (request.url.encodedPath.endsWith("userprofile/")) """{"weight_unit":"kg"}"""
                else """{"count":${dates.size},"next":$next,"previous":null,"results":[""" +
                    page.joinToString(",") { """{"id":"$uuid","date":"$it","weight":77.5}""" } + "]}"
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (offset == failAtOffset && !request.url.encodedPath.endsWith("userprofile/")) 500 else 200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val retrofit = Retrofit.Builder()
            .client(client)
            .baseUrl("https://wger.example/api/v2/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return WgerSync(retrofit)
    }

    private val threeDates = listOf(
        "2026-08-26T07:30:00+02:00", "2026-08-27T07:30:00+02:00", "2026-08-28T07:30:00+02:00"
    )

    @Test
    fun inbound_readsEveryPage() = runTest {
        val requests = mutableListOf<Request>()

        val readings = pagingSync(threeDates, pageSize = 2, requests).readInboundWeights()

        assertEquals(3, readings.size)
        assertEquals(listOf("0", "2"), entryRequests(requests).map { it.url.queryParameter("offset") })
    }

    @Test
    fun inbound_failsAsAWhole_whenALaterPageFails() = runTest {
        // A partial history must not reach openScale; runInbound turns the throw into a Failure.
        val failed = runCatching {
            pagingSync(threeDates, pageSize = 2, mutableListOf(), failAtOffset = 2).readInboundWeights()
        }

        assertTrue(failed.isFailure)
    }

    // --- Units ------------------------------------------------------------------------------

    private fun sentWeight(request: Request): Float =
        JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject["weight"].asFloat

    @Test
    fun export_toAPoundsProfile_sendsPounds() = runTest {
        // wger takes every weight in the profile's unit: 80 kg sent as is would read as 80 lb.
        val requests = mutableListOf<Request>()

        recordingSync(requests, unit = "lb").insert(measurement.copy(weight = 80f))

        assertEquals(176.37f, sentWeight(requests.last()), 0.0001f)
    }

    @Test
    fun import_fromAPoundsProfile_comesBackInKg() = runTest {
        val readings = recordingSync(mutableListOf(), unit = "lb").readInboundWeights()

        // The entry says 77.5 — in pounds.
        assertEquals(35.153f, readings.single().weightKg, 0.001f)
    }

    @Test
    fun export_roundsToTheTwoDecimalsWgerAccepts() = runTest {
        // A weight imported from Health Connect can carry more; wger rejects a third decimal.
        val requests = mutableListOf<Request>()

        recordingSync(requests).insert(measurement.copy(weight = 77.1107f))

        assertEquals(77.11f, sentWeight(requests.last()), 0.0001f)
    }

    @Test
    fun theProfileUnit_isReadOnce() = runTest {
        val requests = mutableListOf<Request>()
        val sync = recordingSync(requests, unit = "lb")

        sync.insert(measurement)
        sync.insert(measurement)
        sync.readInboundWeights()

        assertEquals(1, requests.count { it.url.encodedPath.endsWith("userprofile/") })
    }

    // --- Which entries openScale lacks ------------------------------------------------------

    private fun reading(timeMs: Long, day: LocalDate? = null) = WgerSync.WgerReading(timeMs, 77.5f, day)

    @Test
    fun severalWeighInsOfADay_allComeIn_whenOpenScaleHasAnotherOneThatDay() {
        // The old day-level gap-fill dropped both, because openScale had a measurement that day.
        val morning = reading(ENTRY_MS)
        val evening = reading(ENTRY_MS + 12 * 3_600_000L)

        val unknown = WgerSync.unknownToOpenScale(listOf(morning, evening), listOf(ENTRY_MS + 3_600_000L))

        assertEquals(listOf(morning, evening), unknown)
    }

    @Test
    fun aTimeOpenScaleAlreadyHas_isLeftAlone_evenWithAnotherWeight() {
        // Our own export coming back from wger, or an entry edited there: openScale stays master.
        val unknown = WgerSync.unknownToOpenScale(listOf(reading(ENTRY_MS)), listOf(ENTRY_MS))

        assertEquals(emptyList<WgerSync.WgerReading>(), unknown)
    }

    @Test
    fun anEntryInTheSecondOfAnOpenScaleMeasurement_isThatMeasurement() {
        // wger keeps microseconds: an entry imported earlier comes back a fraction later.
        val unknown = WgerSync.unknownToOpenScale(
            listOf(reading(ENTRY_MS + 999), reading(ENTRY_MS + 1000)), listOf(ENTRY_MS)
        )

        assertEquals(listOf(reading(ENTRY_MS + 1000)), unknown)
    }

    @Test
    fun aDateOnlyEntry_isMatchedByItsDay() {
        // wger up to 2.3: midnight is not a real time, the day is all the entry says.
        val day = LocalDate.of(2026, 8, 28)
        val midnight = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val known = WgerSync.unknownToOpenScale(listOf(reading(midnight, day)), listOf(midnight + 7 * 3_600_000L))
        val unknown = WgerSync.unknownToOpenScale(listOf(reading(midnight, day)), listOf(midnight - 3_600_000L))

        assertEquals(emptyList<WgerSync.WgerReading>(), known)
        assertEquals(1, unknown.size)
    }

    private companion object {
        const val ENTRY_DATE = "2026-08-28T07:30:00+02:00"
        val ENTRY_MS = OffsetDateTime.parse(ENTRY_DATE).toInstant().toEpochMilli()
    }
}
