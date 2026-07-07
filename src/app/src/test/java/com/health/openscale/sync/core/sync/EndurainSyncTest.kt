/*
 *  Copyright (C) 2026  Dany Mestas
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
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurementValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Covers [EndurainSync.buildWeightRequest] — the openScale-values → Endurain-weight-body mapping.
 * Pure logic, no network. Verifies kg conversion for masses, %-passthrough, null omission, that no
 * forbidden keys (bmi/source) are emitted, and that numbers are serialized as JSON numbers.
 */
class EndurainSyncTest {

    // UTC so the formatted calendar date is deterministic regardless of the test host's zone.
    private val dateOnly = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val gson = Gson()

    private fun mv(key: String, unit: String, value: Float, typeId: Int = 0) =
        OpenScaleMeasurementValue(typeId, key, key, unit, false, value)

    private fun measurement(values: List<OpenScaleMeasurementValue>) =
        OpenScaleMeasurement.fromValues(1, 1, Date(0L), "alice", values)

    @Test
    fun fullMapping_massesInKg_percentagesPassThrough() {
        val m = measurement(listOf(
            mv("WEIGHT", "kg", 80f),
            mv("BODY_FAT", "%", 20f),
            mv("WATER", "%", 55f),
            mv("MUSCLE", "%", 40f),   // % → kg via weight
            mv("BONE", "kg", 3.2f),   // already kg → direct
            mv("VISCERAL_FAT", "", 9f)
        ))

        val req = EndurainSync.buildWeightRequest(m, dateOnly)

        assertEquals("1970-01-01", req.date)
        assertEquals(80f, req.weight!!, 0.0001f)
        assertEquals(20f, req.bodyFat!!, 0.0001f)
        assertEquals(55f, req.bodyWater!!, 0.0001f)
        assertEquals(32f, req.muscleMass!!, 0.0001f)   // 80 * 40 / 100
        assertEquals(3.2f, req.boneMass!!, 0.0001f)
        assertEquals(9f, req.visceralFat!!, 0.0001f)
    }

    @Test
    fun muscleAlreadyKg_passesThrough_boneAsPercentConverted() {
        val m = measurement(listOf(
            mv("WEIGHT", "kg", 100f),
            mv("MUSCLE", "kg", 45f),  // already kg → direct
            mv("BONE", "%", 3f)       // % → kg via weight
        ))

        val req = EndurainSync.buildWeightRequest(m, dateOnly)

        assertEquals(45f, req.muscleMass!!, 0.0001f)
        assertEquals(3f, req.boneMass!!, 0.0001f)   // 100 * 3 / 100
    }

    @Test
    fun absentMetrics_areNull() {
        val m = measurement(listOf(mv("WEIGHT", "kg", 70f)))
        val req = EndurainSync.buildWeightRequest(m, dateOnly)

        assertEquals(70f, req.weight!!, 0.0001f)
        assertNull(req.bodyFat)
        assertNull(req.bodyWater)
        assertNull(req.muscleMass)
        assertNull(req.boneMass)
        assertNull(req.visceralFat)
    }

    @Test
    fun json_omitsNulls_hasNoForbiddenKeys_usesNumbers() {
        val m = measurement(listOf(mv("WEIGHT", "kg", 70f)))
        val json = gson.toJson(EndurainSync.buildWeightRequest(m, dateOnly))

        // present + numeric (not a quoted string)
        assertTrue(json.contains("\"weight\":70"))
        assertFalse(json.contains("\"weight\":\""))
        // absent optionals omitted (Gson drops nulls)
        assertFalse(json.contains("body_fat"))
        assertFalse(json.contains("muscle_mass"))
        assertFalse(json.contains("visceral_fat"))
        // never sent: server auto-calcs bmi; source enum rejects non-garmin
        assertFalse(json.contains("bmi"))
        assertFalse(json.contains("source"))
    }
}
