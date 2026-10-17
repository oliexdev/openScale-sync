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
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Date

/**
 * Covers the wire format of the wger weight entry endpoint: the entry list parses
 * with the UUID ids of wger 2.7 on and the integer ids before, and entries are
 * written as JSON, the only request body wger 2.7 accepts.
 */
class WgerSyncTest {

    private val gson = Gson()

    private fun responseWithId(id: String) =
        """{"count":1,"next":null,"previous":null,"results":""" +
            """[{"id":$id,"date":"2026-08-28T07:30:00+02:00","weight":77.5}]}"""

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

    private val measurement = OpenScaleMeasurement(1, 1, Date(0L), 77.5f, 0f, 0f, 0f, 0f, 0f)

    /** A [WgerSync] whose requests are recorded and answered without any network. */
    private fun recordingSync(requests: MutableList<Request>): WgerSync {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = if (request.method == "GET") responseWithId("\"$uuid\"") else "{}"
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
        assertEquals("POST", requests.single().method)
        assertEquals("/api/v2/weightentry/", requests.single().url.encodedPath)
        assertJsonEntry(requests.single())
    }

    @Test
    fun update_patchesJson_toTheUuidOfTheEntry() = runTest {
        val requests = mutableListOf<Request>()

        val result = recordingSync(requests).update(measurement)

        assertTrue(result is SyncResult.Success)
        val patch = requests.last()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/v2/weightentry/$uuid/", patch.url.encodedPath)
        assertJsonEntry(patch)
    }
}
