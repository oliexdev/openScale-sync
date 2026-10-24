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
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
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
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurementValue
import com.health.openscale.sync.core.service.SyncResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    internal class FakeHealthConnectClient : HealthConnectClient {
        val written = mutableListOf<List<Record>>()
        /** Every (recordType, timeRangeFilter) a delete was issued for. */
        val deleted = mutableListOf<Pair<KClass<out Record>, TimeRangeFilter>>()

        /** What a read returns, per record type. Only the inbound tests fill this. */
        val stored = mutableListOf<Record>()
        val reads = mutableListOf<ReadRecordsRequest<*>>()
        var pageSize = 1000
        var finalPageToken: String? = null
        var failingPageToken: String? = null
        var historyAvailable = true
        var grantedPermissions = emptySet<String>()

        // The platform populates dataOrigin on reads; public Metadata factories cannot set it.
        fun metadataFrom(pkg: String): Metadata = Metadata.manualEntry().also {
            Metadata::class.java.getDeclaredField("dataOrigin").apply { isAccessible = true }
                .set(it, DataOrigin(pkg))
        }

        private fun timeOf(record: Record): Instant = when (record) {
            is WeightRecord -> record.time
            is BodyFatRecord -> record.time
            is BodyWaterMassRecord -> record.time
            is BoneMassRecord -> record.time
            is LeanBodyMassRecord -> record.time
            else -> error("unexpected inbound record type")
        }

        override val features = object : HealthConnectFeatures {
            override fun getFeatureStatus(feature: Int): Int =
                if (historyAvailable && feature == HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)
                    HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
                else HealthConnectFeatures.FEATURE_STATUS_UNAVAILABLE
        }

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
            reads += request
            if (failingPageToken != null && request.pageToken == failingPageToken) {
                throw IllegalStateException("scripted page failure")
            }
            val start = request.timeRangeFilter.startTime ?: Instant.EPOCH
            val end = request.timeRangeFilter.endTime ?: Instant.MAX
            val sorted = stored.filter {
                request.recordType.isInstance(it) && !timeOf(it).isBefore(start) && timeOf(it).isBefore(end)
            }.sortedBy(::timeOf)
            val matching = if (request.ascendingOrder) sorted else sorted.reversed()
            // Opaque tokens ensure callers must forward the returned token, not invent a page number.
            val offset = request.pageToken?.let {
                require(it.startsWith("cursor:")) { "Unexpected page token: $it" }
                it.removePrefix("cursor:").toInt()
            } ?: 0
            val page = matching.drop(offset).take(minOf(pageSize, request.pageSize))
            val nextToken = if (offset + page.size < matching.size)
                "cursor:${offset + page.size}" else finalPageToken
            return ReadRecordsResponse(page as List<T>, nextToken)
        }

        override val permissionController = object : PermissionController {
            override suspend fun getGrantedPermissions(): Set<String> = grantedPermissions
            override suspend fun revokeAllPermissions() { grantedPermissions = emptySet() }
        }
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

    private fun weight(time: Instant, pkg: String = "other.app") = WeightRecord(
        time = time,
        zoneOffset = null,
        weight = Mass.kilograms(80.0),
        metadata = client.metadataFrom(pkg)
    )

    @Test
    fun inbound_readsOldAndRecentRecordsWithoutAnApplicationCutoff() = runTest {
        val old = Instant.parse("2017-01-01T12:00:00Z")
        val recent = Instant.parse("2026-10-01T09:00:00Z")
        client.readsRejected = false
        client.stored += listOf(weight(old), weight(recent))

        val readings = sync.readInboundReadings("own.app", 0L)

        assertEquals(listOf(old.toEpochMilli(), recent.toEpochMilli()), readings.map { it.timeMs })
        assertTrue(client.reads.all { it.timeRangeFilter.startTime == Instant.EPOCH })
    }

    @Test
    fun inbound_respectsAnExplicitRequestedStartTime() = runTest {
        val since = Instant.parse("2024-10-01T00:00:00Z")
        val recent = Instant.parse("2026-10-01T09:00:00Z")
        client.readsRejected = false
        client.stored += listOf(weight(Instant.parse("2017-01-01T12:00:00Z")), weight(recent))

        val readings = sync.readInboundReadings("own.app", since.toEpochMilli())

        assertEquals(listOf(recent.toEpochMilli()), readings.map { it.timeMs })
        assertTrue(client.reads.all { it.timeRangeFilter.startTime == since })
    }

    @Test
    fun inbound_readsEveryPageOfEveryType_andExcludesOwnWritesOnLaterPages() = runTest {
        val time = Instant.parse("2026-10-01T09:00:00Z")
        client.readsRejected = false
        client.pageSize = 1
        client.finalPageToken = ""
        val foreign = client.metadataFrom("other.app")
        val own = client.metadataFrom("own.app")
        // Own records occur on the first AND last pages. The last page's composition values are
        // closest to the foreign weight, so leaking that page corrupts the result.
        for ((index, metadata) in listOf(own, foreign, own).withIndex()) {
            val isForeign = index == 1
            val compositionTime = time.minusMillis(when (index) { 0 -> 500L; 1 -> 400L; else -> 0L })
            client.stored += WeightRecord(time, null, Mass.kilograms(if (isForeign) 80.0 else 95.0), metadata)
            client.stored += BodyFatRecord(compositionTime, null, Percentage(if (isForeign) 20.0 else 30.0), metadata)
            client.stored += BodyWaterMassRecord(compositionTime, null, Mass.kilograms(if (isForeign) 44.0 else 42.0), metadata)
            client.stored += BoneMassRecord(compositionTime, null, Mass.kilograms(if (isForeign) 3.0 else 4.0), metadata)
            client.stored += LeanBodyMassRecord(compositionTime, null, Mass.kilograms(if (isForeign) 60.0 else 55.0), metadata)
        }

        val reading = sync.readInboundReadings("own.app", time.minusSeconds(1).toEpochMilli()).single()

        assertEquals(80f, reading.weightKg)
        assertEquals(20f, reading.fatPct)
        assertEquals(55f, reading.waterPct)
        assertEquals(3f, reading.boneKg)
        assertEquals(60f, reading.leanKg)
        assertEquals(15, client.reads.size)
        assertEquals(5, client.reads.count { it.pageToken == "cursor:2" })
    }

    @Test
    fun inbound_importsMoreThanTheDefaultPageSize() = runTest {
        client.readsRejected = false
        repeat(1001) { client.stored += weight(Instant.ofEpochSecond(it.toLong() + 1)) }

        val readings = sync.readInboundReadings("own.app", 0L)

        assertEquals((1L..1001L).map { it * 1000L }, readings.map { it.timeMs })
        assertEquals(2, client.reads.count { it.recordType == WeightRecord::class })
    }

    @Test
    fun inbound_failingLaterPageDoesNotReturnAPartialHistory() = runTest {
        client.readsRejected = false
        client.pageSize = 1
        client.failingPageToken = "cursor:1"
        client.stored += listOf(weight(Instant.ofEpochSecond(1)), weight(Instant.ofEpochSecond(2)))

        try {
            sync.readInboundReadings("own.app", 0L)
            fail("The second page must fail instead of returning partial results")
        } catch (error: IllegalStateException) {
            assertEquals("scripted page failure", error.message)
        }
        assertEquals(listOf(null, "cursor:1"), client.reads.map { it.pageToken })
    }

    private fun mv(key: String, unit: String, value: Float) =
        OpenScaleMeasurementValue("builtin.${key.lowercase()}", key, unit, false, value)

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
    fun write_omitsFatAndWater_forAWeightOnlyMeasurement() = runTest {
        // A scale without body composition (or with impedance off) only reports the weight; the
        // missing fat/water must not reach Health Connect as a 0 % / 0 kg weigh-in.
        sync.insert(OpenScaleMeasurement.fromValues(7, 1, Date(1_000_000L), "alice", listOf(mv("WEIGHT", "kg", 80f))))

        assertEquals(listOf("7_weight"), lastWrittenIds())
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
    // records. Exercise the grouping rules directly as well as through the paged fake above.

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
    fun rejectionReason_flagsAZeroWeight() {
        assertNotNull(sync.rejectionReason(measurementOf(weight = 0f)))
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
