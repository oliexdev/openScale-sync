package com.health.openscale.sync.core.sync

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import kotlin.reflect.KClass

class HealthConnectSync(private var healthConnectClient: HealthConnectClient) : SyncInterface(){
    suspend fun fullSync(measurements: List<OpenScaleMeasurement>) {
        Timber.d("Writing ${measurements.size} measurements to HealthConnect")

        val records = mutableListOf<Record>()

        measurements.forEach {
            val weightRecord = buildWeightRecord(it)
            records.add(weightRecord)

            val waterRecord = buildWaterRecord(it)
            records.add(waterRecord)

            val fatRecord = buildFatRecord(it)
            records.add(fatRecord)
        }

        Timber.d("Converted the ${measurements.size} measurements to ${records.size} records")

        try {
            healthConnectClient.insertRecords(records)
        } catch (e: Exception) {
            Timber.e( e.toString())
        } finally {
            Timber.d("Writing ${records.size} measurements done")
        }
    }

    suspend fun insert(measurement: OpenScaleMeasurement) {
        val records = mutableListOf<Record>()

        val weightRecord = buildWeightRecord(measurement)
        records.add(weightRecord)

        val waterRecord = buildWaterRecord(measurement)
        records.add(waterRecord)

        val fatRecord = buildFatRecord(measurement)
        records.add(fatRecord)

        try {
            healthConnectClient.insertRecords(records)
        } catch (e: Exception) {
            Timber.e(e.toString())
        } finally {
            Timber.d("Writing measurements done")
        }
    }

    suspend fun delete(date: Date) {
        val localDate = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        val startOfDay = localDate.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val endOfDay = localDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()

        val timeRange = TimeRangeFilter.between(startOfDay, endOfDay)

        try {
            healthConnectClient.deleteRecords(
                    WeightRecord::class,
                    timeRange
                )
            healthConnectClient.deleteRecords(
                    BodyFatRecord::class,
                    timeRange
                )
            healthConnectClient.deleteRecords(
                    BodyWaterMassRecord::class,
                    timeRange
                )
            Timber.d("Successfully deleted records for date: $date")
        } catch (e: Exception) {
            Timber.e("Error deleting records for date: $date, error: ${e.message}")
        }
    }

    suspend fun clear() {
        val localDate = Date().toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        val startOfDay = localDate.minusYears(10).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val endOfDay = localDate.plusYears(10).atStartOfDay(ZoneId.systemDefault()).toInstant()

        val timeRange = TimeRangeFilter.between(startOfDay, endOfDay)

        try {
            healthConnectClient.deleteRecords(
                WeightRecord::class,
                timeRange
            )
            healthConnectClient.deleteRecords(
                BodyFatRecord::class,
                timeRange
            )
            healthConnectClient.deleteRecords(
                BodyWaterMassRecord::class,
                timeRange
            )
            Timber.d("Successfully deleted all records")
        } catch (e: Exception) {
            Timber.e("Error deleting all records, error: ${e.message}")
        }
    }

    suspend fun update(measurement: OpenScaleMeasurement) {
        val records = mutableListOf<Record>()

        try {
            val weightRecord = readWeightRecord(measurement)
            val waterRecord = readWaterRecord(measurement)
            val fatRecord = readFatRecord(measurement)

            if (weightRecord != null) {
                records.add(buildWeightRecord(measurement))
            }
            if (waterRecord != null) {
                records.add(buildWaterRecord(measurement))
            }
            if (fatRecord != null) {
                records.add(buildFatRecord(measurement))
            }

            if (records.isNotEmpty()) {
                healthConnectClient.insertRecords(records)
                Timber.d("Successfully updated records for measurement: ${measurement.id}")
            } else {
                Timber.e("No records found to update for measurement: ${measurement.id}")
            }

        } catch (e: Exception) {
            Timber.e("Error updating records for measurement: ${measurement.id}, error: ${e.message}", e)
        }
    }

    private suspend fun readWeightRecord(measurement: OpenScaleMeasurement): WeightRecord? {
        return readRecord(measurement, WeightRecord::class)
    }

    private suspend fun readWaterRecord(measurement: OpenScaleMeasurement): BodyWaterMassRecord? {
        return readRecord(measurement, BodyWaterMassRecord::class)
    }

    private suspend fun readFatRecord(measurement: OpenScaleMeasurement): BodyFatRecord? {
        return readRecord(measurement,BodyFatRecord::class)
    }

    private suspend fun <T : Record> readRecord(
        measurement: OpenScaleMeasurement,
        recordType: KClass<T>,
    ): T? {
        val timeRangeFilter = TimeRangeFilter.between(measurement.date.toInstant().minusSeconds(1), measurement.date.toInstant().plusSeconds(1))
        val dataOriginFilter = setOf(DataOrigin("com.health.openscale.sync"))
        val readRequest = ReadRecordsRequest(
            recordType = recordType,
            timeRangeFilter = timeRangeFilter,
            dataOriginFilter = dataOriginFilter
        )
        val response: ReadRecordsResponse<T> = healthConnectClient.readRecords(readRequest)
        return response.records.firstOrNull()
    }

    private fun buildMetadata(measurement: OpenScaleMeasurement, type: String): Metadata {
        return Metadata(
            clientRecordId = measurement.id.toString() + "_" + type,
            clientRecordVersion = Instant.now().toEpochMilli()
        )
    }

    private fun buildWeightRecord(measurement: OpenScaleMeasurement): WeightRecord {
        return WeightRecord(
            time = measurement.date.toInstant(),
            zoneOffset = null,
            weight = Mass.kilograms(measurement.weight.toDouble()),
            metadata = buildMetadata(measurement, "weight")
        )
    }

    private fun buildWaterRecord(measurement: OpenScaleMeasurement): BodyWaterMassRecord {
        return BodyWaterMassRecord(
            time = measurement.date.toInstant(),
            zoneOffset = null,
            mass = Mass.kilograms(measurement.weight.toDouble() * measurement.water.toDouble() / 100),
            metadata = buildMetadata(measurement, "water")
        )
    }

    private fun buildFatRecord(measurement: OpenScaleMeasurement): BodyFatRecord {
        return BodyFatRecord(
            time = measurement.date.toInstant(),
            zoneOffset = null,
            percentage = Percentage(measurement.fat.toDouble()),
            metadata = buildMetadata(measurement, "fat")
        )
    }
}