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
import com.health.openscale.sync.core.model.MQTTViewModel
import com.health.openscale.sync.core.service.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Covers [MQTTSync.buildHistoryPayload] — the slim columnar history payload that backs the retained
 * `…/measurements/history` topic and the HA statistics-import blueprint. Pure logic, no MQTT client.
 */
class MQTTSyncTest {

    private fun mv(key: String, unit: String, value: Float) =
        OpenScaleMeasurementValue("builtin.${key.lowercase()}", key, unit, false, value)

    private fun measurement(id: Int, dateMs: Long, values: List<OpenScaleMeasurementValue>) =
        OpenScaleMeasurement.fromValues(id, 1, Date(dateMs), "alice", values)

    private val gson = com.google.gson.GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mmZ").create()

    @Test
    fun topics_withoutPrefix_areUnchanged() {
        assertEquals("openScaleSync", MQTTSync.topicBase(""))
        assertEquals("1", MQTTSync.deviceKey("", 1))
    }

    @Test
    fun topics_withPrefix_keepTwoPhonesWithUserOneApart() {
        assertEquals("openScaleSync/alice", MQTTSync.topicBase("alice"))
        assertEquals("alice_1", MQTTSync.deviceKey("alice", 1))
        assertNotEquals(MQTTSync.deviceKey("alice", 1), MQTTSync.deviceKey("bob", 1))
    }

    @Test
    fun inboundUserId_readsTheLevelAfterInbound_withOrWithoutPrefix() {
        assertEquals(3, MQTTSync.inboundUserId("openScaleSync/inbound/3/scale"))
        assertEquals(3, MQTTSync.inboundUserId("openScaleSync/alice/inbound/3/scale"))
        assertEquals(null, MQTTSync.inboundUserId("openScaleSync/inbound/x"))
        assertEquals(null, MQTTSync.inboundUserId("openScaleSync/alice/3/measurements/last"))
    }

    @Test
    fun retainedTopics_areTheDiscoveryConfigAndTheLastAndHistorySnapshots() {
        assertEquals(
            listOf(
                "homeassistant/device/openscale_alice_1/config",
                "openScaleSync/alice/1/measurements/last",
                "openScaleSync/alice/1/measurements/history",
            ),
            MQTTSync.retainedTopics("alice", 1),
        )
    }

    @Test
    fun prefixChange_clearsTheOldPrefixForEveryUser() {
        val cleared = mutableListOf<Pair<String, Int>>()
        val record = MQTTSync.clearPreviousPrefix("alice", "bob", listOf(1, 2)) { p, u ->
            cleared += p to u
            SyncResult.Success(Unit)
        }
        assertTrue(record)
        assertEquals(listOf("alice" to 1, "alice" to 2), cleared)
    }

    @Test
    fun prefixChange_whoseClearFails_keepsTheOldPrefixRecorded() {
        val record = MQTTSync.clearPreviousPrefix("alice", "bob", listOf(1)) { _, _ ->
            SyncResult.Failure(SyncResult.ErrorType.API_ERROR, "broker gone")
        }
        assertFalse(record)
    }

    @Test
    fun prefixChange_neverClearsTheSharedDefaultOrAnUnknownPrefix() {
        val clear: (String, Int) -> SyncResult<Unit> = { _, _ -> error("must not clear") }
        // Moving off the empty default: other phones on the default still use those topics.
        assertTrue(MQTTSync.clearPreviousPrefix("", "bob", listOf(1), clear))
        // First connection: nothing is known to be ours.
        assertTrue(MQTTSync.clearPreviousPrefix(null, "bob", listOf(1), clear))
        assertTrue(MQTTSync.clearPreviousPrefix("bob", "bob", listOf(1), clear))
    }

    @Test
    fun topicPrefix_inputFilter_dropsOnlyCharactersThatCanNeverBeUsed() {
        assertEquals("inbound", MQTTViewModel.sanitizeTopicPrefix("inbound"))
        assertEquals("alice b ", MQTTViewModel.sanitizeTopicPrefix("alice b "))
        assertEquals("ab", MQTTViewModel.sanitizeTopicPrefix("a/#+b"))
        assertEquals("ab", MQTTViewModel.sanitizeTopicPrefix("aäb"))
    }

    @Test
    fun topicLevel_isOneSafeLevel() {
        assertEquals("alice_b", MQTTViewModel.topicLevel(" alice b "))
        assertEquals("inbound_", MQTTViewModel.topicLevel("inbound"))
        assertEquals("measurements_", MQTTViewModel.topicLevel("measurements"))
        assertEquals("inbounds", MQTTViewModel.topicLevel("inbounds"))
        assertEquals("", MQTTViewModel.topicLevel("   "))
    }

    @Test
    fun measurementPayload_leavesOutMetricsTheMeasurementDoesNotHave() {
        val weightOnly = MQTTSync.buildMeasurementPayload(gson, measurement(1, 2000L, listOf(mv("WEIGHT", "kg", 82.0f))))

        assertEquals(82.0, weightOnly.get("weight").asDouble, 1e-6)
        listOf("body_fat", "water", "muscle", "lbm", "bone").forEach {
            assertEquals("$it must be absent, not 0", false, weightOnly.has(it))
        }
    }

    @Test
    fun measurementPayload_keepsMetricsThatArePresent() {
        val full = MQTTSync.buildMeasurementPayload(gson, measurement(1, 2000L, listOf(
            mv("WEIGHT", "kg", 80.0f),
            mv("BODY_FAT", "%", 20.0f),
            mv("WATER", "%", 55.0f),
        )))

        assertEquals(20.0, full.get("body_fat").asDouble, 1e-6)
        assertEquals(55.0, full.get("water").asDouble, 1e-6)
        assertEquals(false, full.has("muscle"))
        assertEquals(true, full.has("values"))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun canonicalUnitsNormalized_customAndExtraColumnsIncluded_absentCellsNull() {
        val m = measurement(1, 2000L, listOf(
            mv("WEIGHT", "kg", 82.0f),
            mv("BODY_FAT", "%", 18.0f),
            mv("WAIST", "cm", 80.0f),
            OpenScaleMeasurementValue("user.brustumfang", "Brustumfang", "cm", false, 88.0f)
        ))

        val p = MQTTSync.buildHistoryPayload(listOf(m))

        // date + canonical four (unified backend keys) + sorted extras (brustumfang < waist).
        assertEquals(listOf("date", "weight", "body_fat", "water", "muscle", "brustumfang", "waist"), p["fields"])

        val units = p["units"] as Map<String, String>
        assertEquals("kg", units["weight"])
        assertEquals("%", units["body_fat"])
        assertEquals("cm", units["waist"])
        assertEquals("cm", units["brustumfang"])

        val rows = p["rows"] as List<List<Any?>>
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals(2000L, row[0])
        assertEquals(82.0f, row[1])      // weight
        assertEquals(18.0f, row[2])      // fat (already %, taken as-is)
        assertEquals(null, row[3])       // water absent → null, not 0
        assertEquals(null, row[4])       // muscle absent → null
        assertEquals(88.0f, row[5])      // brustumfang
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
