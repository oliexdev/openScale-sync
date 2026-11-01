package com.health.openscale.sync.core.sync

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.WeightRecord
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurementValue
import com.health.openscale.sync.core.service.SyncResult
import com.health.openscale.sync.core.sync.HealthConnectSyncTest.FakeHealthConnectClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Date

class HealthConnectHeartRateTest {
    private val client = FakeHealthConnectClient()
    private val sync = HealthConnectSync(client)

    private fun measurement(id: Int = 7, bpm: Float? = 72f) = OpenScaleMeasurement.fromValues(
        id, 1, Date(1_000_000L), "alice", buildList {
            add(OpenScaleMeasurementValue("builtin.weight", "Weight", "kg", false, 80f))
            bpm?.let { add(OpenScaleMeasurementValue("builtin.heart_rate", "Heart rate", "/min", false, it)) }
        }
    )

    @Test
    fun exportsOneSampleAtTheWeighingTimeWithStableIdentity() = runTest {
        val m = measurement()

        assertTrue(sync.insert(m) is SyncResult.Success)

        val record = client.written.single().filterIsInstance<HeartRateRecord>().single()
        val instant = m.date.toInstant()
        val offset = ZoneId.systemDefault().rules.getOffset(instant)
        assertEquals(instant, record.startTime)
        assertEquals(instant, record.endTime)
        assertEquals(offset, record.startZoneOffset)
        assertEquals(offset, record.endZoneOffset)
        assertEquals(listOf(HeartRateRecord.Sample(instant, 72)), record.samples)
        assertEquals("7_heart_rate", record.metadata.clientRecordId)
        assertTrue(client.reads.isEmpty())
    }

    @Test
    fun missingOrInvalidPulseDoesNotSuppressTheWeight() = runTest {
        for (bpm in listOf(null, 0f, -1f, 301f, 72.5f, Float.NaN,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertTrue("bpm=$bpm", sync.insert(measurement(bpm = bpm)) is SyncResult.Success)
            assertEquals("bpm=$bpm", listOf(WeightRecord::class), client.written.last().map { it::class })
        }
    }

    @Test
    fun acceptsBothHealthConnectBpmBoundaries() = runTest {
        for (bpm in listOf(1f, 300f)) {
            assertTrue(sync.insert(measurement(bpm = bpm)) is SyncResult.Success)
            val sample = client.written.last().filterIsInstance<HeartRateRecord>().single().samples.single()
            assertEquals(bpm.toLong(), sample.beatsPerMinute)
        }
    }

    @Test
    fun updatesPulseThroughTheSameClientRecordIdWithoutReadPermission() = runTest {
        sync.insert(measurement(bpm = 72f))
        val original = client.written.last().filterIsInstance<HeartRateRecord>().single()

        assertTrue(sync.update(measurement(bpm = 73f)) is SyncResult.Success)

        val updated = client.written.last().filterIsInstance<HeartRateRecord>().single()
        assertEquals(original.metadata.clientRecordId, updated.metadata.clientRecordId)
        assertEquals(73L, updated.samples.single().beatsPerMinute)
        assertTrue(client.reads.isEmpty())
    }

    @Test
    fun fullSyncIncludesOnlyAvailablePulseSamplesInTheExistingBatch() = runTest {
        assertTrue(sync.fullSync(listOf(
            measurement(id = 1), measurement(id = 2, bpm = null), measurement(id = 3, bpm = 75f)
        )) is SyncResult.Success)

        val records = client.written.single()
        assertEquals(3, records.filterIsInstance<WeightRecord>().size)
        assertEquals(listOf("1_heart_rate", "3_heart_rate"),
            records.filterIsInstance<HeartRateRecord>().map { it.metadata.clientRecordId })
        assertTrue(client.reads.isEmpty())
    }

    @Test
    fun deletingAWeighingAlsoDeletesItsPulse() = runTest {
        val date = measurement().date

        assertTrue(sync.delete(date) is SyncResult.Success)

        val range = client.deleted.single { it.first == HeartRateRecord::class }.second
        assertEquals(date.toInstant().minusSeconds(1), range.startTime)
        assertEquals(date.toInstant().plusSeconds(1), range.endTime)
        assertTrue(client.reads.isEmpty())
    }

    @Test
    fun clearingExportsAlsoClearsPulse() = runTest {
        assertTrue(sync.clear() is SyncResult.Success)

        assertEquals(1, client.deleted.count { it.first == HeartRateRecord::class })
        assertTrue(client.reads.isEmpty())
    }
}
