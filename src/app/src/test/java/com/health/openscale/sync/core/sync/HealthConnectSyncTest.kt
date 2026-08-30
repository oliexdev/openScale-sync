/*
 *  Copyright (C) 2026  olie.xdev <olie.xdev@googlemail.com>
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

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurementValue
import com.health.openscale.sync.core.service.SyncResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Date
import kotlin.reflect.KClass

/**
 * Covers the write path of [HealthConnectSync]: which records a measurement maps to, and that
 * updating never reads anything back.
 *
 * The read-back is the regression under test. It filtered by our own package name, hardcoded
 * without the applicationIdSuffix — so on the OSS and debug flavors Health Connect saw a request
 * for another app's data: it threw with write-only permissions (the op then stuck in the retry
 * queue forever) and matched nothing with read permissions granted (every value but BMR silently
 * dropped from the update). [FakeHealthConnectClient.readRecords] therefore throws the way Health
 * Connect does under write-only permissions: any test here that reads fails.
 */
class HealthConnectSyncTest {

    private class FakeHealthConnectClient : HealthConnectClient {
        val written = mutableListOf<List<Record>>()
        /** Every (recordType, timeRangeFilter) a delete was issued for. */
        val deleted = mutableListOf<Pair<KClass<out Record>, TimeRangeFilter>>()

        /** What a read returns, per record type. Only the inbound tests fill this. */
        val stored = mutableListOf<Record>()

        /**
         * Health Connect's behaviour under write-only permissions. Left on for every write test, so
         * a read-back reintroduced into the write path fails them.
         */
        var readsRejected = true

        override suspend fun insertRecords(records: List<Record>): InsertRecordsResponse {
            written += records
            return InsertRecordsResponse(records.map { it.metadata.clientRecordId.orEmpty() })
        }

        override suspend fun deleteRecords(recordType: KClass<out Record>, timeRangeFilter: TimeRangeFilter) {
            deleted += recordType to timeRangeFilter
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Record> readRecords(request: ReadRecordsRequest<T>): ReadRecordsResponse<T> {
            if (readsRejected) throw SecurityException(
                "Caller does not have permission to read data for the following " +
                    "(recordType: ${request.recordType}) from other applications."
            )
            return ReadRecordsResponse(stored.filter { request.recordType.isInstance(it) } as List<T>, null)
        }

        override val permissionController: PermissionController get() = error("not used by these tests")
        override suspend fun updateRecords(records: List<Record>) = error("not used by these tests")
        override suspend fun deleteRecords(
            recordType: KClass<out Record>, recordIdsList: List<String>, clientRecordIdsList: List<String>
        ) = error("not used by these tests")
        override suspend fun <T : Record> readRecord(recordType: KClass<T>, recordId: String): ReadRecordResponse<T> =
            error("not used by these tests")
        override suspend fun aggregate(request: AggregateRequest): AggregationResult = error("not used by these tests")
        override suspend fun aggregateGroupByDuration(
            request: AggregateGroupByDurationRequest
        ): List<AggregationResultGroupedByDuration> = error("not used by these tests")
        override suspend fun aggregateGroupByPeriod(
            request: AggregateGroupByPeriodRequest
        ): List<AggregationResultGroupedByPeriod> = error("not used by these tests")
        override suspend fun getChangesToken(request: ChangesTokenRequest): String = error("not used by these tests")
        override suspend fun getChanges(changesToken: String): ChangesResponse = error("not used by these tests")
    }

    private val client = FakeHealthConnectClient()
    private val sync = HealthConnectSync(client)

    private fun mv(key: String, unit: String, value: Float) =
        OpenScaleMeasurementValue(0, key, key, unit, false, value)

    /** Weight + fat + water are always present; the optional values are opt-in per test. */
    private fun measurement(id: Int = 7, extra: List<OpenScaleMeasurementValue> = emptyList()) =
        OpenScaleMeasurement.fromValues(
            id, 1, Date(1_000_000L), "alice",
            listOf(
                mv("WEIGHT", "kg", 80f),
                mv("BODY_FAT", "%", 20f),
                mv("WATER", "%", 55f)
            ) + extra
        )

    /** The clientRecordIds of the last write — that is what Health Connect upserts on. */
    private fun lastWrittenIds() = client.written.last().map { it.metadata.clientRecordId }

    @Test
    fun update_writesWithoutReadingBack() = runTest {
        val result = sync.update(measurement())

        assertTrue("update must not depend on a read-back", result is SyncResult.Success)
        assertEquals(listOf("7_weight", "7_water", "7_fat"), lastWrittenIds())
    }

    @Test
    fun update_writesTheSameRecordsAsInsert() = runTest {
        sync.insert(measurement())
        val afterInsert = lastWrittenIds()

        sync.update(measurement())

        assertEquals(afterInsert, lastWrittenIds())
    }

    @Test
    fun update_carriesOptionalValues_whenOpenScaleHasThem() = runTest {
        // LBM and bone arrive as absolute masses and are stored as a percentage of weight; both are
        // non-zero here, so their records must be written — the old read-back path dropped them
        // whenever no record existed yet.
        val m = measurement(
            extra = listOf(
                mv("LBM", "kg", 60f),
                mv("BONE", "kg", 3f),
                mv("BMR", "kcal", 1600f)
            )
        )

        sync.update(m)

        assertEquals(listOf("7_weight", "7_water", "7_fat", "7_lbm", "7_bone", "7_bmr"), lastWrittenIds())
    }

    @Test
    fun write_omitsOptionalRecords_whenValuesAreAbsent() = runTest {
        sync.insert(measurement())

        assertEquals(listOf("7_weight", "7_water", "7_fat"), lastWrittenIds())
    }

    @Test
    fun delete_onlyCoversTheMeasurementItself_notTheWholeDay() = runTest {
        val time = Date(1_000_000L)

        val result = sync.delete(time)

        assertTrue(result is SyncResult.Success)
        // Every type we write is cleared, but only in a window around this one measurement — the day
        // range this used to use took a second weigh-in on the same day with it.
        assertEquals(6, client.deleted.size)
        client.deleted.forEach { (_, range) ->
            assertEquals(time.toInstant().minusSeconds(1), range.startTime)
            assertEquals(time.toInstant().plusSeconds(1), range.endTime)
        }
    }

    @Test
    fun fullSync_writesEveryMeasurementInOneBatch() = runTest {
        val result = sync.fullSync(listOf(measurement(id = 1), measurement(id = 2)))

        assertTrue(result is SyncResult.Success)
        assertEquals(1, client.written.size)
        assertEquals(
            listOf("1_weight", "1_water", "1_fat", "2_weight", "2_water", "2_fat"),
            lastWrittenIds()
        )
    }

    // --- Inbound grouping ------------------------------------------------------------------
    // Health Connect has no notion of "one weigh-in", so the reading is assembled from separate
    // records. Exercised on the pure grouping step: dataOrigin cannot be set on a Metadata from
    // outside androidx, so building foreign records is not possible here.

    @Test
    fun inbound_groupsRecordsWrittenSlightlyApart_andCarriesBoneAndLeanMass() = runTest {
        // A foreign app writing its records a moment apart — matching the exact millisecond, as this
        // used to, threw away everything but the weight.
        val readings = sync.groupInboundReadings(
            weights = listOf(10_000L to 80f),
            fatsPct = listOf(10_400L to 21f),
            watersKg = listOf(10_500L to 44f),
            bonesKg = listOf(10_900L to 3.2f),
            leansKg = listOf(11_000L to 60f)
        )

        val reading = readings.single()
        assertEquals(10_000L, reading.timeMs)
        assertEquals(80f, reading.weightKg, 0.001f)
        assertEquals(21f, reading.fatPct!!, 0.001f)
        assertEquals(55f, reading.waterPct!!, 0.001f)      // 44 kg of 80 kg
        assertEquals(3.2f, reading.boneKg!!, 0.001f)
        assertEquals(60f, reading.leanKg!!, 0.001f)
    }

    @Test
    fun inbound_doesNotStealValuesFromANeighbouringWeighIn() = runTest {
        val readings = sync.groupInboundReadings(
            weights = listOf(10_000L to 80f, 60_000L to 81f),
            fatsPct = listOf(
                59_500L to 22f,      // clearly the second weigh-in
                40_000L to 99f       // too far from either → dropped
            )
        ).associateBy { it.timeMs }

        assertNull(readings.getValue(10_000L).fatPct)
        assertEquals(22f, readings.getValue(60_000L).fatPct!!, 0.001f)
    }

    @Test
    fun inbound_keepsTheClosestCandidate_whenSeveralCompete() = runTest {
        val readings = sync.groupInboundReadings(
            weights = listOf(10_000L to 80f),
            fatsPct = listOf(11_500L to 30f, 10_100L to 21f)
        )

        assertEquals(21f, readings.single().fatPct!!, 0.001f)
    }

    @Test
    fun inbound_withoutAWeightRecord_yieldsNothing() = runTest {
        // Weight is the anchor and openScale's mandatory value — a lone body-fat record is unusable.
        assertEquals(emptyList<HealthConnectSync.InboundReading>(),
            sync.groupInboundReadings(weights = emptyList(), fatsPct = listOf(10_000L to 21f)))
    }

    // --- Value validation (issues #34, #35) ---------------------------------------------
    // The androidx record constructors validate in their init block and THROW, outside the
    // try/catch in writeRecords() — which is exactly how a 409.5 % body fat and a measurement
    // timed in the future crashed the app instead of failing the sync.

    /** A measurement built from raw values, so a test can put anything in any field. */
    private fun measurementOf(
        date: Date = Date(1_000_000L),
        weight: Float = 80f,
        fat: Float = 20f,
        water: Float = 55f,
        extra: List<OpenScaleMeasurementValue> = emptyList()
    ) = OpenScaleMeasurement.fromValues(
        7, 1, date, "alice",
        listOf(mv("WEIGHT", "kg", weight), mv("BODY_FAT", "%", fat), mv("WATER", "%", water)) + extra
    )

    @Test
    fun rejectionReason_acceptsAnOrdinaryMeasurement() {
        assertNull(sync.rejectionReason(measurement()))
    }

    @Test
    fun rejectionReason_flagsABodyFatAbove100Percent() {
        // Issue #34: 409.5 % = 4095/10, a 12-bit "no value" sentinel from the scale.
        assertNotNull(sync.rejectionReason(measurementOf(fat = 409.5f)))
    }

    @Test
    fun rejectionReason_flagsAMeasurementTimedInTheFuture() {
        // Issue #35: Health Connect refuses a record timed after the wall clock.
        val inFourHours = Date(System.currentTimeMillis() + 4 * 60 * 60 * 1000L)
        assertNotNull(sync.rejectionReason(measurementOf(date = inFourHours)))
        // ... and accepts it again once its time has come, without anyone touching the data.
        assertNull(sync.rejectionReason(measurementOf(date = inFourHours),
            now = inFourHours.toInstant().plusSeconds(1)))
    }

    @Test
    fun rejectionReason_flagsAnImpossibleWeight() {
        assertNotNull(sync.rejectionReason(measurementOf(weight = 1_001f)))
        assertNotNull(sync.rejectionReason(measurementOf(weight = -1f)))
        // requireNonNegative is `value >= 0`, which NaN fails too.
        assertNotNull(sync.rejectionReason(measurementOf(weight = Float.NaN)))
    }

    @Test
    fun rejectionReason_flagsAnOutOfRangeBmr() {
        assertNotNull(sync.rejectionReason(
            measurementOf(extra = listOf(mv("BMR", "kcal", 20_000f)))))
    }

    @Test
    fun rejectionReason_ignoresOptionalValuesThatAreNotWritten() {
        // Lean/bone records are only built when > 0, so a zero must not count as out of range.
        assertNull(sync.rejectionReason(
            measurementOf(extra = listOf(mv("LBM", "%", 0f), mv("BONE", "%", 0f)))))
    }

    @Test
    fun insert_reportsAnInvalidMeasurementInsteadOfThrowing() = runTest {
        val result = sync.insert(measurementOf(fat = 409.5f))

        assertTrue(result is SyncResult.Failure)
        assertEquals(SyncResult.ErrorType.INVALID_DATA,
            (result as SyncResult.Failure).errorType)
        assertTrue("nothing may be written for a rejected measurement", client.written.isEmpty())
    }

    @Test
    fun fullSync_stillWritesTheValidMeasurements_whenOneIsInvalid() = runTest {
        // The filtering happens in HealthConnectService.insertAll; what matters here is that a
        // batch of valid measurements is unaffected by a neighbour having been dropped.
        val result = sync.fullSync(listOf(measurement(id = 1), measurement(id = 2)))

        assertTrue(result is SyncResult.Success)
        assertFalse(client.written.isEmpty())
    }
}
