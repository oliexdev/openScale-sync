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

import androidx.annotation.VisibleForTesting
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.service.SyncResult
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import kotlin.math.abs
import kotlin.reflect.KClass

class HealthConnectSync(private var healthConnectClient: HealthConnectClient) : SyncInterface(){
    /**
     * Why Health Connect would reject [measurement], or null when it is writable.
     *
     * This has to run BEFORE [buildRecords]: the androidx record constructors validate in their init
     * block and THROW, and the platform rejects a record timed in the future the same way — so an
     * out-of-range value never reached [writeRecords]' try/catch but tore down the whole caller
     * instead (issues #34 and #35). Filtering here also keeps one bad measurement from failing the
     * entire batch, since insertRecords() is all-or-nothing.
     *
     * These limits are Health Connect's, not openScale's: no other backend validates values
     * client-side, so none of them get a check like this.
     *
     * The returned text is for the log, not the UI — the user only ever sees the skipped count.
     */
    @VisibleForTesting
    internal fun rejectionReason(measurement: OpenScaleMeasurement, now: Instant = Instant.now()): String? {
        // Platform-side check in InstantRecord: a record must not be timed in the future. Scales with
        // their own (mis-set) clock produce these, so it is worth skipping rather than failing —
        // once the wall clock catches up, the next reconcile sends it without any user action.
        val instant = measurement.date.toInstant()
        if (instant.isAfter(now)) return "date $instant is in the future"

        // Mass records: >= 0 and <= 1000 kg. A percentage <= 100 with a weight <= 1000 kg can never
        // exceed that for the derived water/lean/bone masses, so the percent limits below cover them.
        outOfRange("weight", measurement.weight, MAX_WEIGHT_KG)?.let { return it }
        outOfRange("body fat", measurement.body_fat, MAX_PERCENT)?.let { return it }
        outOfRange("water", measurement.water, MAX_PERCENT)?.let { return it }
        // Only built when > 0 (see buildRecords), so only those need checking.
        if (measurement.lbm > 0f) outOfRange("lean body mass", measurement.lbm, MAX_PERCENT)?.let { return it }
        if (measurement.bone > 0f) outOfRange("bone", measurement.bone, MAX_PERCENT)?.let { return it }

        val bmr = measurement.values.firstOrNull { it.isBuiltin("BMR") }?.value ?: 0f
        if (bmr > 0f) outOfRange("BMR", bmr, MAX_BMR_KCAL_PER_DAY)?.let { return it }

        return null
    }

    /** Health Connect's requireNonNegative is `value >= 0`, which NaN fails too — mirror that here. */
    private fun outOfRange(name: String, value: Float, max: Float): String? =
        if (!value.isFinite() || value < 0f || value > max) "$name is $value" else null

    /**
     * The record set one measurement maps to. Weight/water/fat are always written; lean body mass,
     * bone mass and BMR only when openScale actually has that value. Every record carries a stable
     * [buildMetadata] clientRecordId, which is what makes a re-insert an upsert (see [insert]).
     *
     * Only call this for a measurement [rejectionReason] cleared — the constructors throw otherwise.
     */
    private fun buildRecords(measurement: OpenScaleMeasurement): List<Record> {
        val records = mutableListOf<Record>()

        records.add(buildWeightRecord(measurement))
        records.add(buildWaterRecord(measurement))
        records.add(buildFatRecord(measurement))

        if (measurement.lbm > 0f) {
            records.add(buildLeanBodyMassRecord(measurement))
        }

        if (measurement.bone > 0f) {
            records.add(buildBoneMassRecord(measurement))
        }

        val bmrValue = measurement.values.firstOrNull { it.isBuiltin("BMR") }?.value ?: 0f
        if (bmrValue > 0f) {
            records.add(buildBMRRecord(measurement))
        }

        return records
    }

    private suspend fun writeRecords(records: List<Record>) : SyncResult<Unit> {
        return try {
            healthConnectClient.insertRecords(records)
            SyncResult.Success(Unit)
        } catch (e: Exception) {
            SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
        }
    }

    suspend fun fullSync(measurements: List<OpenScaleMeasurement>) : SyncResult<Unit> =
        writeRecords(measurements.flatMap { buildRecords(it) })

    /**
     * Write one measurement. This is an UPSERT, not a blind add: every record carries a
     * clientRecordId derived from the openScale measurement id, and Health Connect replaces an
     * existing record of the same clientRecordId when the clientRecordVersion is higher (which
     * [buildMetadata] guarantees by stamping the current time). That is why [fullSync] can re-push
     * the whole history without duplicating it — and why [update] is the same operation.
     */
    suspend fun insert(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
        rejectionReason(measurement)?.let { reason ->
            Timber.w("Health Connect: skipping measurement id=%d (%s)", measurement.id, reason)
            return SyncResult.Failure(SyncResult.ErrorType.INVALID_DATA, reason)
        }
        return writeRecords(buildRecords(measurement))
    }

    /**
     * Delete the one measurement taken at [date] — meaning the records we wrote at exactly that
     * instant, which is where [buildRecords] puts them. The window around it only absorbs rounding;
     * openScale cannot hold two measurements of one user a second apart.
     *
     * It deliberately does not span the calendar day, as it used to: that wiped every measurement we
     * had written that day, so deleting the morning weigh-in took the evening one with it, and the
     * move branch (delete at the old time, insert at the new) did the same to a same-day neighbour.
     *
     * Deleting by clientRecordId would be exact, but Health Connect fails the call for a
     * non-existing identifier, and the optional records (lean mass, bone mass, BMR) are only written
     * when openScale has that value — a missing one would turn into a failed delete.
     */
    suspend fun delete(date: Date) : SyncResult<Unit> {
        val instant = date.toInstant()
        val timeRange = TimeRangeFilter.between(instant.minusSeconds(1), instant.plusSeconds(1))

        return deleteRange(timeRange)
    }

    suspend fun clear() : SyncResult<Unit> {
        val localDate = Date().toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        val startOfDay = localDate.minusYears(10).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val endOfDay = localDate.plusYears(10).atStartOfDay(ZoneId.systemDefault()).toInstant()

        return deleteRange(TimeRangeFilter.between(startOfDay, endOfDay))
    }

    /**
     * Drop every record type we write in [timeRange]. Health Connect scopes deletes to the calling
     * app, so this never reaches another app's data.
     */
    private suspend fun deleteRange(timeRange: TimeRangeFilter) : SyncResult<Unit> {
        return try {
            listOf(
                WeightRecord::class,
                BodyFatRecord::class,
                BodyWaterMassRecord::class,
                LeanBodyMassRecord::class,
                BasalMetabolicRateRecord::class,
                BoneMassRecord::class
            ).forEach { healthConnectClient.deleteRecords(it, timeRange) }

            SyncResult.Success(Unit)
        } catch (e: Exception) {
            SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
        }
    }

    /**
     * Updating is writing: the clientRecordId upsert in [insert] already replaces the existing
     * records of this measurement in place.
     *
     * This deliberately does NOT read the current records back first. Doing so needed a dataOrigin
     * filter on our own package name, which is only correct for the Play build — the OSS and debug
     * flavors carry an applicationIdSuffix, so the filter named a foreign app there: with write-only
     * permissions Health Connect rejected the read outright (SecurityException, the op then stuck in
     * the retry queue forever), and with read permissions granted it silently matched nothing, so
     * every value except BMR was dropped from the update. Writing unconditionally is both correct on
     * every flavor and independent of the read permissions, which only the inbound path needs.
     *
     * It also fixes a second asymmetry: a value added in openScale after the first sync (say a fat
     * percentage) could never reach Health Connect, because the read found no record to update.
     */
    suspend fun update(measurement: OpenScaleMeasurement) : SyncResult<Unit> = insert(measurement)

    /**
     * One inbound reading from Health Connect (foreign app): a weight plus whatever other metrics
     * the same weigh-in produced. [boneKg]/[leanKg] stay masses because that is openScale's own unit
     * for them, while fat and water are percentages of the weight.
     */
    data class InboundReading(
        val timeMs: Long,
        val weightKg: Float,
        val fatPct: Float? = null,
        val waterPct: Float? = null,
        val boneKg: Float? = null,
        val leanKg: Float? = null
    )

    /**
     * Inbound (bidirectional): read the records written by OTHER apps since [sinceMillis], excluding
     * our own [ownPackage] writes (echo prevention via dataOrigin).
     *
     * The weight record is the anchor — Health Connect has no notion of "one weigh-in", so a reading
     * is a weight plus the other records closest to it within [INBOUND_GROUPING_TOLERANCE]. Matching
     * on the exact millisecond, as this did, silently dropped everything from apps that write their
     * records a moment apart or round to whole seconds. Each record is consumed once, by its nearest
     * weight, so two weigh-ins close together cannot both claim the same body-fat value.
     *
     * Basal metabolic rate is deliberately not imported: openScale derives it itself, so a foreign
     * value would be overwritten by its own calculation anyway.
     */
    suspend fun readInboundReadings(ownPackage: String, sinceMillis: Long): List<InboundReading> {
        val range = TimeRangeFilter.after(Instant.ofEpochMilli(sinceMillis))
        fun foreign(record: Record) = record.metadata.dataOrigin.packageName != ownPackage

        // The record types carry their instant on their own class (the shared interface is internal
        // to androidx), so each caller maps its record to (time, value) itself.
        suspend fun <T : Record> readForeign(type: KClass<T>, sample: (T) -> Pair<Long, Float>): List<Pair<Long, Float>> =
            healthConnectClient.readRecords(ReadRecordsRequest(type, range))
                .records.filter(::foreign).map(sample)

        return groupInboundReadings(
            weights = readForeign(WeightRecord::class) {
                it.time.toEpochMilli() to it.weight.inKilograms.toFloat()
            },
            fatsPct = readForeign(BodyFatRecord::class) {
                it.time.toEpochMilli() to it.percentage.value.toFloat()
            },
            watersKg = readForeign(BodyWaterMassRecord::class) {
                it.time.toEpochMilli() to it.mass.inKilograms.toFloat()
            },
            bonesKg = readForeign(BoneMassRecord::class) {
                it.time.toEpochMilli() to it.mass.inKilograms.toFloat()
            },
            leansKg = readForeign(LeanBodyMassRecord::class) {
                it.time.toEpochMilli() to it.mass.inKilograms.toFloat()
            }
        )
    }

    /**
     * Turn the per-type (timestamp, value) lists into one reading per weigh-in. Pure, so the
     * grouping rules are testable without a Health Connect client.
     */
    @VisibleForTesting
    internal fun groupInboundReadings(
        weights: List<Pair<Long, Float>>,
        fatsPct: List<Pair<Long, Float>> = emptyList(),
        watersKg: List<Pair<Long, Float>> = emptyList(),
        bonesKg: List<Pair<Long, Float>> = emptyList(),
        leansKg: List<Pair<Long, Float>> = emptyList()
    ): List<InboundReading> {
        if (weights.isEmpty()) return emptyList()
        val weightTimes = weights.map { it.first }

        /** Attach each candidate to its nearest weigh-in, closest candidate winning a contest. */
        fun near(candidates: List<Pair<Long, Float>>): Map<Long, Float> {
            val best = HashMap<Long, Pair<Long, Float>>()   // anchor -> (distance, value)
            for ((time, value) in candidates) {
                val anchor = weightTimes.minByOrNull { abs(it - time) } ?: continue
                val distance = abs(anchor - time)
                if (distance > INBOUND_GROUPING_TOLERANCE_MS) continue
                val held = best[anchor]
                if (held == null || distance < held.first) best[anchor] = distance to value
            }
            return best.mapValues { it.value.second }
        }

        val fatAt = near(fatsPct)
        val waterAt = near(watersKg)
        val boneAt = near(bonesKg)
        val leanAt = near(leansKg)

        return weights.map { (t, kg) ->
            InboundReading(
                timeMs = t,
                weightKg = kg,
                fatPct = fatAt[t],
                waterPct = waterAt[t]?.let { if (kg > 0f) it / kg * 100f else null },
                boneKg = boneAt[t],
                leanKg = leanAt[t]
            )
        }
    }

    private fun buildMetadata(measurement: OpenScaleMeasurement, type: String): Metadata {
        return Metadata.manualEntry(
            clientRecordId = measurement.id.toString() + "_" + type,
            clientRecordVersion = Instant.now().toEpochMilli()
        )
    }

    private fun buildWeightRecord(measurement: OpenScaleMeasurement): WeightRecord {
        val measurementInstant = measurement.date.toInstant()
        val zoneOffset = ZoneId.systemDefault().rules.getOffset(measurementInstant)

        return WeightRecord(
            time = measurementInstant,
            zoneOffset = zoneOffset,
            weight = Mass.kilograms(measurement.weight.toDouble()),
            metadata = buildMetadata(measurement, "weight")
        )
    }

    private fun buildWaterRecord(measurement: OpenScaleMeasurement): BodyWaterMassRecord {
        val measurementInstant = measurement.date.toInstant()
        val zoneOffset = ZoneId.systemDefault().rules.getOffset(measurementInstant)

        return BodyWaterMassRecord(
            time = measurementInstant,
            zoneOffset = zoneOffset,
            mass = Mass.kilograms(measurement.weight.toDouble() * measurement.water.toDouble() / 100),
            metadata = buildMetadata(measurement, "water")
        )
    }

    private fun buildFatRecord(measurement: OpenScaleMeasurement): BodyFatRecord {
        val measurementInstant = measurement.date.toInstant()
        val zoneOffset = ZoneId.systemDefault().rules.getOffset(measurementInstant)

        return BodyFatRecord(
            time = measurementInstant,
            zoneOffset = zoneOffset,
            percentage = Percentage(measurement.body_fat.toDouble()),
            metadata = buildMetadata(measurement, "fat")   // HC clientRecordId suffix — internal dedup key, kept stable
        )
    }

    private fun buildLeanBodyMassRecord(measurement: OpenScaleMeasurement): LeanBodyMassRecord {
        val measurementInstant = measurement.date.toInstant()
        val zoneOffset = ZoneId.systemDefault().rules.getOffset(measurementInstant)

        return LeanBodyMassRecord(
            time = measurementInstant,
            zoneOffset = zoneOffset,
            mass = Mass.kilograms((measurement.weight * measurement.lbm / 100f).toDouble()),
            metadata = buildMetadata(measurement, "lbm")
        )
    }

    private fun buildBoneMassRecord(measurement: OpenScaleMeasurement): BoneMassRecord {
        val measurementInstant = measurement.date.toInstant()
        val zoneOffset = ZoneId.systemDefault().rules.getOffset(measurementInstant)

        return BoneMassRecord(
            time = measurementInstant,
            zoneOffset = zoneOffset,
            mass = Mass.kilograms((measurement.weight * measurement.bone / 100f).toDouble()),
            metadata = buildMetadata(measurement, "bone")
        )
    }

    private fun buildBMRRecord(measurement: OpenScaleMeasurement): BasalMetabolicRateRecord {
        val measurementInstant = measurement.date.toInstant()
        val zoneOffset = ZoneId.systemDefault().rules.getOffset(measurementInstant)
        val bmrKcalPerDay = (measurement.values.firstOrNull { it.isBuiltin("BMR") }?.value ?: 0f).toDouble()
        val bmrWatts = bmrKcalPerDay * (4184.0 / 86400.0) // kcal/day → Watts

        return BasalMetabolicRateRecord(
            time = measurementInstant,
            zoneOffset = zoneOffset,
            basalMetabolicRate = Power.watts(bmrWatts),
            metadata = buildMetadata(measurement, "bmr")
        )
    }

    companion object {
        /**
         * How far from the weight record a body-composition record may sit and still count as the
         * same weigh-in. Wide enough for apps that write their records sequentially or round to
         * whole seconds, far too narrow to reach a neighbouring weigh-in.
         */
        private const val INBOUND_GROUPING_TOLERANCE_MS = 2_000L

        // The limits the androidx record constructors enforce (connect-client 1.1.0): WeightRecord
        // and the mass records take 0..1000 kg, BodyFatRecord 0..100 %, BasalMetabolicRateRecord
        // 0..10000 kcal/day. Exceeding any of them throws out of the constructor, hence
        // [rejectionReason].
        private const val MAX_WEIGHT_KG = 1_000f
        private const val MAX_PERCENT = 100f
        private const val MAX_BMR_KCAL_PER_DAY = 10_000f
    }
}