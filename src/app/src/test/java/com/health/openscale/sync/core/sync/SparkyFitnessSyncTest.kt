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

import com.google.gson.JsonParser
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurementValue
import com.health.openscale.sync.core.service.SyncResult
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Date

/**
 * Covers [SparkyFitnessSync]: the pure openScale → SparkyFitness mapping ([SparkyFitnessSync.buildPayload])
 * and the request sequence of upsert/delete/clear against a fake server (no network).
 */
class SparkyFitnessSyncTest {

    private fun mv(key: String, unit: String, value: Float, derived: Boolean = false) =
        OpenScaleMeasurementValue("builtin.${key.lowercase()}", key, unit, derived, value)

    private fun measurement(values: List<OpenScaleMeasurementValue>, date: Date = DAY) =
        OpenScaleMeasurement.fromValues(1, 1, date, "alice", values)

    // --- Mapping ------------------------------------------------------------------------

    @Test
    fun fullMapping_checkInColumnsInSparkyUnits() {
        val m = measurement(listOf(
            mv("WEIGHT", "kg", 80f),
            mv("BODY_FAT", "%", 20f),
            mv("WATER", "%", 55f),
            mv("MUSCLE", "%", 40f),    // % → kg via weight
            mv("BONE", "kg", 3.2f),    // already kg
            mv("BMR", "kcal", 1800f),  // measured by the scale
            mv("WAIST", "cm", 90f),
            mv("HIPS", "cm", 100f),
            mv("NECK", "cm", 40f)
        ))

        val p = SparkyFitnessSync.buildPayload(m, includeDerived = false)

        assertEquals(80f, p.checkIn["weight"]!!, EPS)
        assertEquals(20f, p.checkIn["body_fat_percentage"]!!, EPS)
        assertEquals(55f, p.checkIn["body_water_percentage"]!!, EPS)
        assertEquals(32f, p.checkIn["muscle_mass_kg"]!!, EPS)   // 80 * 40 / 100
        assertEquals(3.2f, p.checkIn["bone_mass_kg"]!!, EPS)
        assertEquals(1800f, p.checkIn["bmr"]!!, EPS)
        assertEquals(90f, p.checkIn["waist"]!!, EPS)
        assertEquals(100f, p.checkIn["hips"]!!, EPS)
        assertEquals(40f, p.checkIn["neck"]!!, EPS)
        assertTrue(p.custom.isEmpty())
    }

    @Test
    fun bodyWater_goesToPercentColumn_neverToSparkyDrinkingWater() {
        val m = measurement(listOf(mv("WEIGHT", "kg", 80f), mv("WATER", "kg", 44f)))

        val p = SparkyFitnessSync.buildPayload(m, includeDerived = false)

        assertEquals(55f, p.checkIn["body_water_percentage"]!!, EPS)   // 44 / 80 * 100
        assertFalse(p.checkIn.containsKey("water"))
    }

    @Test
    fun massesAlreadyKg_passThrough_percentConverted() {
        val m = measurement(listOf(
            mv("WEIGHT", "kg", 100f),
            mv("MUSCLE", "kg", 45f),
            mv("BONE", "%", 3f),
            mv("BODY_FAT", "kg", 25f)
        ))

        val p = SparkyFitnessSync.buildPayload(m, includeDerived = false)

        assertEquals(45f, p.checkIn["muscle_mass_kg"]!!, EPS)
        assertEquals(3f, p.checkIn["bone_mass_kg"]!!, EPS)          // 100 * 3 / 100
        assertEquals(25f, p.checkIn["body_fat_percentage"]!!, EPS)  // 25 / 100 * 100
    }

    @Test
    fun absentValues_areOmitted() {
        val p = SparkyFitnessSync.buildPayload(measurement(listOf(mv("WEIGHT", "kg", 70f))), false)

        assertEquals(setOf("weight"), p.checkIn.keys)
        assertTrue(p.custom.isEmpty())
    }

    @Test
    fun estimatedBmr_isNeverSent() {
        val m = measurement(listOf(
            mv("WEIGHT", "kg", 80f),
            mv("BMR", "kcal", 1750f, derived = true),
            mv("TDEE", "kcal", 2400f, derived = true)
        ))

        for (include in listOf(false, true)) {
            val p = SparkyFitnessSync.buildPayload(m, include)
            assertFalse("bmr column with includeDerived=$include", p.checkIn.containsKey("bmr"))
            assertTrue("custom with includeDerived=$include", p.custom.none { it.name == "bmr" || it.name == "tdee" })
        }
    }

    @Test
    fun measuredBmr_outsideServerRange_isDropped() {
        val low = measurement(listOf(mv("WEIGHT", "kg", 80f), mv("BMR", "kcal", 500f)))
        val high = measurement(listOf(mv("WEIGHT", "kg", 80f), mv("BMR", "kcal", 9000f)))

        assertNull(SparkyFitnessSync.buildPayload(low, false).checkIn["bmr"])
        assertNull(SparkyFitnessSync.buildPayload(high, false).checkIn["bmr"])
    }

    @Test
    fun otherMeasuredValues_becomeCustomCategories() {
        val m = measurement(listOf(
            mv("WEIGHT", "kg", 80f),
            mv("VISCERAL_FAT", "", 9f),
            mv("LBM", "kg", 64f),
            OpenScaleMeasurementValue("ble.segmental.fat.left_arm", "Fat left arm", "%", false, 18f)
        ))

        val custom = SparkyFitnessSync.buildPayload(m, false).custom.associateBy { it.name }

        assertEquals(setOf("visceral_fat", "lbm", "segmental_fat_left_arm"), custom.keys)
        assertEquals(9f, custom.getValue("visceral_fat").value, EPS)
        assertEquals("", custom.getValue("visceral_fat").unit)
        assertEquals("kg", custom.getValue("lbm").unit)
        assertEquals("Fat left arm", custom.getValue("segmental_fat_left_arm").displayName)
    }

    @Test
    fun derivedValues_onlyWithSetting() {
        val m = measurement(listOf(
            mv("WEIGHT", "kg", 80f),
            mv("BMI", "", 24.7f, derived = true),
            mv("WHR", "", 0.9f, derived = true),
            mv("WHTR", "", 0.5f, derived = true)
        ))

        assertTrue(SparkyFitnessSync.buildPayload(m, includeDerived = false).custom.isEmpty())
        assertEquals(
            setOf("bmi", "whr", "whtr"),
            SparkyFitnessSync.buildPayload(m, includeDerived = true).custom.map { it.name }.toSet()
        )
    }

    @Test
    fun textAndInvalidValues_areSkipped() {
        val m = measurement(listOf(
            mv("WEIGHT", "kg", 80f),
            OpenScaleMeasurementValue("builtin.comment", "COMMENT", "", false, null, "after workout"),
            mv("BODY_FAT", "%", Float.NaN),
            mv("WAIST", "cm", 0f)
        ))

        val p = SparkyFitnessSync.buildPayload(m, includeDerived = true)

        assertEquals(setOf("weight"), p.checkIn.keys)
        assertTrue(p.custom.isEmpty())
    }

    @Test
    fun deleteBody_nullsExactlyOurColumns() {
        val body = SparkyFitnessSync.clearCheckInBody("2026-10-15")

        assertEquals("2026-10-15", body["entry_date"])
        assertEquals(SparkyFitnessSync.CHECK_IN_COLUMNS.toSet() + "entry_date", body.keys)
        assertTrue(SparkyFitnessSync.CHECK_IN_COLUMNS.all { body.getValue(it) == null })
        // columns the user may have entered by hand are not ours to clear
        assertFalse(body.containsKey("steps"))
        assertFalse(body.containsKey("height"))
    }

    // --- Wire behaviour against a fake server ---------------------------------------

    /** Records every request and answers by "METHOD path" with canned JSON. */
    private class FakeServer(private val routes: Map<String, Pair<Int, String>>) : Interceptor {
        val calls = mutableListOf<Triple<String, String, String>>()   // method, path, body
        val apiKeys = mutableListOf<String?>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val path = req.url.encodedPath.removePrefix("/api/measurements/")
            val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() } ?: ""
            calls += Triple(req.method, path, body)
            apiKeys += req.header("x-api-key")
            val (code, json) = routes["${req.method} $path"] ?: (404 to """{"error":"not found"}""")
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fake")
                .body(json.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun sync(server: FakeServer, includeDerived: Boolean = false) =
        SparkyFitnessSync("https://sparky.test/", " key-123\n", { includeDerived }, server)

    @Test
    fun upsert_writesCheckIn_thenCreatesMissingCategory_andEntry() = runTest {
        val server = FakeServer(mapOf(
            "POST check-in" to (200 to "{}"),
            "GET custom-categories" to (200 to """[{"id":"c-lbm","name":"lbm"}]"""),
            "POST custom-categories" to (201 to """{"id":"c-vf"}"""),
            "POST custom-entries" to (201 to "{}")
        ))
        val m = measurement(listOf(mv("WEIGHT", "kg", 80f), mv("VISCERAL_FAT", "", 9f), mv("LBM", "kg", 64f)))

        val res = sync(server).upsert(m)

        assertTrue(res.toString(), res is SyncResult.Success)
        assertEquals("key-123", server.apiKeys.distinct().single())   // trimmed, on every call

        val checkIn = JsonParser.parseString(server.calls.first { it.second == "check-in" }.third).asJsonObject
        assertEquals(DAY_STR, checkIn["entry_date"].asString)
        assertEquals(80.0, checkIn["weight"].asDouble, 1e-4)

        val created = server.calls.filter { it.first == "POST" && it.second == "custom-categories" }
        assertEquals(1, created.size)   // lbm already existed, only visceral_fat is new
        val category = JsonParser.parseString(created.single().third).asJsonObject
        assertEquals("visceral_fat", category["name"].asString)
        assertEquals("Daily", category["frequency"].asString)
        assertEquals("N/A", category["measurement_type"].asString)

        val entries = server.calls.filter { it.second == "custom-entries" }
            .map { JsonParser.parseString(it.third).asJsonObject }
        assertEquals(setOf("c-vf", "c-lbm"), entries.map { it["category_id"].asString }.toSet())
        assertTrue(entries.all { it["source"].asString == SparkyFitnessSync.SOURCE && it["entry_date"].asString == DAY_STR })
    }

    @Test
    fun upsert_validationError_isInvalidData() = runTest {
        val server = FakeServer(mapOf("POST check-in" to (400 to """{"error":"bmr out of range"}""")))

        val res = sync(server).upsert(measurement(listOf(mv("WEIGHT", "kg", 80f))))

        assertTrue(res is SyncResult.Failure)
        assertEquals(SyncResult.ErrorType.INVALID_DATA, (res as SyncResult.Failure).errorType)
    }

    @Test
    fun delete_nullsCheckIn_andRemovesOnlyOurCustomEntries() = runTest {
        val server = FakeServer(mapOf(
            "GET check-in-measurements-range/$DAY_STR/$DAY_STR" to (200 to """[{"entry_date":"$DAY_STR"}]"""),
            "POST check-in" to (200 to "{}"),
            "GET custom-entries/$DAY_STR" to (200 to """[
                {"id":"e1","source":"openScale"},
                {"id":"e2","source":"manual"},
                {"id":"e3","source":"Health Connect"}
            ]"""),
            "DELETE custom-entries/e1" to (200 to "{}")
        ))

        val res = sync(server).delete(DAY)

        assertTrue(res.toString(), res is SyncResult.Success)
        val cleared = JsonParser.parseString(server.calls.single { it.second == "check-in" }.third).asJsonObject
        assertTrue(cleared["weight"].isJsonNull)          // nulls reach the wire
        assertFalse(cleared.has("steps"))
        assertEquals(listOf("custom-entries/e1"), server.calls.filter { it.first == "DELETE" }.map { it.second })
    }

    @Test
    fun delete_withoutCheckInRow_doesNotCreateAnEmptyOne() = runTest {
        val server = FakeServer(mapOf(
            "GET check-in-measurements-range/$DAY_STR/$DAY_STR" to (200 to "[]"),
            "GET custom-entries/$DAY_STR" to (200 to "[]")
        ))

        val res = sync(server).delete(DAY)

        assertTrue(res is SyncResult.Success)
        assertTrue(server.calls.none { it.first == "POST" })
    }

    @Test
    fun clear_removesOurCustomEntries_butNoCheckInValues() = runTest {
        val server = FakeServer(mapOf(
            "GET custom-entries" to (200 to """[{"id":"e1","source":"openScale"},{"id":"e2","source":"manual"}]"""),
            "DELETE custom-entries/e1" to (404 to """{"error":"gone"}""")   // already gone counts as done
        ))

        val res = sync(server).clear()

        assertTrue(res.toString(), res is SyncResult.Success)
        assertTrue(server.calls.none { it.second.startsWith("check-in") })
        assertEquals(listOf("custom-entries/e1"), server.calls.filter { it.first == "DELETE" }.map { it.second })
    }

    @Test
    fun testConnection_rejectedKey_isPermissionDenied() = runTest {
        val server = FakeServer(mapOf("GET custom-categories" to (401 to """{"error":"unauthorized"}""")))

        val res = sync(server).testConnection()

        assertEquals(SyncResult.ErrorType.PERMISSION_DENIED, (res as SyncResult.Failure).errorType)
    }

    companion object {
        private const val EPS = 0.0001f

        // Local noon: the formatted calendar day is the same in every test-host time zone.
        private val DAY: Date = Calendar.getInstance().apply {
            clear(); set(2026, Calendar.OCTOBER, 15, 12, 0)
        }.time
        private const val DAY_STR = "2026-10-15"
    }
}
