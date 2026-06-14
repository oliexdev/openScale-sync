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

import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurementValue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

/**
 * Covers [MQTTSync.buildHistoryPayload] — the slim columnar history payload that backs the retained
 * `…/measurements/history` topic and the HA statistics-import blueprint. Pure logic, no MQTT client.
 */
class MQTTSyncTest {

    private fun mv(key: String, unit: String, value: Float, typeId: Int = 0) =
        OpenScaleMeasurementValue(typeId, key, key, unit, false, value)

    private fun measurement(id: Int, dateMs: Long, values: List<OpenScaleMeasurementValue>) =
        OpenScaleMeasurement.fromValues(id, 1, Date(dateMs), "alice", values)

    @Suppress("UNCHECKED_CAST")
    @Test
    fun canonicalUnitsNormalized_customAndExtraColumnsIncluded_absentCellsNull() {
        val m = measurement(1, 2000L, listOf(
            mv("WEIGHT", "kg", 82.0f),
            mv("BODY_FAT", "%", 18.0f),
            mv("WAIST", "cm", 80.0f),
            mv("CUSTOM", "cm", 88.0f, typeId = 5)
        ))

        val p = MQTTSync.buildHistoryPayload(listOf(m))

        // date + canonical four (unified backend keys) + sorted extras (custom_5 < waist).
        assertEquals(listOf("date", "weight", "body_fat", "water", "muscle", "custom_5", "waist"), p["fields"])

        val units = p["units"] as Map<String, String>
        assertEquals("kg", units["weight"])
        assertEquals("%", units["body_fat"])
        assertEquals("cm", units["waist"])
        assertEquals("cm", units["custom_5"])

        val rows = p["rows"] as List<List<Any?>>
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals(2000L, row[0])
        assertEquals(82.0f, row[1])      // weight
        assertEquals(18.0f, row[2])      // fat (already %, taken as-is)
        assertEquals(null, row[3])       // water absent → null, not 0
        assertEquals(null, row[4])       // muscle absent → null
        assertEquals(88.0f, row[5])      // custom_5
        assertEquals(80.0f, row[6])      // waist
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun rowsAreDateSorted_andExtraColumnUnionedAcrossMeasurements() {
        val m1 = measurement(1, 3000L, listOf(mv("WEIGHT", "kg", 80.0f), mv("WAIST", "cm", 90.0f)))
        val m2 = measurement(2, 1000L, listOf(mv("WEIGHT", "kg", 81.0f)))   // no waist

        val p = MQTTSync.buildHistoryPayload(listOf(m1, m2))

        assertEquals(listOf("date", "weight", "body_fat", "water", "muscle", "waist"), p["fields"])

        val rows = p["rows"] as List<List<Any?>>
        // ascending by date: m2 (1000) first, then m1 (3000)
        assertEquals(1000L, rows[0][0])
        assertEquals(81.0f, rows[0][1])
        assertEquals(null, rows[0][5])   // m2 has no waist → null in the unioned column
        assertEquals(3000L, rows[1][0])
        assertEquals(90.0f, rows[1][5])  // m1 waist
    }

    @Test
    fun emptyList_yieldsHeaderOnly_noRows() {
        val p = MQTTSync.buildHistoryPayload(emptyList())
        assertEquals(listOf("date", "weight", "body_fat", "water", "muscle"), p["fields"])
        assertEquals(emptyList<Any>(), p["rows"])
    }
}
