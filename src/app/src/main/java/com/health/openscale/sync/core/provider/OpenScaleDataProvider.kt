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
package com.health.openscale.sync.core.provider

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurementValue
import com.health.openscale.sync.core.datatypes.OpenScaleUser
import com.health.openscale.sync.core.model.OpenScaleViewModel
import timber.log.Timber
import java.util.Date

/**
 * Reads openScale's ContentProvider. Normally it follows the openScale variant the user selected;
 * [packageOverride] points it at a specific one instead, which is what lets the variant picker look
 * into a candidate before switching to it.
 */
open class OpenScaleDataProvider(
    private val context: Context,
    private val sharedPreferences: SharedPreferences,
    private val packageOverride: String? = null
) {
    // Resolved per access, not once in the constructor: switching variants has to take effect on the
    // instances already handed to the backends, not only after the next app start.
    private val openScalePackage: String
        get() = packageOverride
            ?: sharedPreferences.getString(OpenScaleViewModel.PACKAGE_NAME, "com.health.openscale")!!
    private val authority: String get() = "$openScalePackage.provider"

    companion object {
        /** Minimum openScale ContentProvider API version this sync app requires
         *  (v2 = sync Intents carry userId on delete/clear). */
        const val MIN_API_VERSION = 2
    }

    open fun getUsers(): List<OpenScaleUser> {

        val userUri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(authority)
            .path("users")
            .build()

        val records = context.contentResolver.query(
            userUri,
            null,
            null,
            null,
            null
        )

        val users = arrayListOf<OpenScaleUser>()

        records.use { record ->
            while (record?.moveToNext() == true) {
                var id: Int? = null
                var username: String? = null

                for (i in 0 until record.columnCount) {
                    if (record.getColumnName(i).equals("_ID")) {
                        id = record.getInt(i)
                    }

                    if (record.getColumnName(i).equals("username")) {
                        username = record.getString(i)
                    }
                }

                if (id != null && username != null) {
                    users.add(OpenScaleUser(id, username))
                } else {
                    Timber.e("ID or username missing")
                }
            }
        }

        Timber.d(users.toString())

        return users
    }

    /** True only when the installed openScale is KNOWN to be older than the required API version
     *  (false when the version can't be determined, so transient failures don't block/warn). */
    fun isVersionTooOld(): Boolean = getApiVersion()?.let { it < MIN_API_VERSION } ?: false

    /** The installed openScale's user-facing version name (e.g. "3.1.1"), read straight from the
     *  PackageManager — no provider round-trip needed, works even for too-old versions. Null if
     *  openScale isn't installed or the name can't be read. Lets the version-gate banner name the
     *  actual installed version instead of a hardcoded minimum. */
    fun getInstalledVersionName(): String? = runCatching {
        context.packageManager.getPackageInfo(openScalePackage, 0).versionName
    }.getOrNull()

    /**
     * How many measurements this openScale holds for [userId], counted on the cursor without
     * building a single measurement object. The variant picker shows this for every candidate, and
     * materialising whole histories just to size them up would be wasteful.
     *
     * Null when the provider cannot be read at all — most likely because this variant's
     * READ_WRITE_DATA permission was never granted.
     */
    open fun countMeasurements(userId: Int): Int? = runCatching {
        val uri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(authority)
            .path("measurements/$userId")
            .build()
        context.contentResolver.query(uri, null, null, null, null).use { it?.count }
    }.getOrNull()

    open fun getApiVersion(): Int? {
        val metaUri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(authority)
            .path("meta")
            .build()

        val records = context.contentResolver.query(
            metaUri,
            null,
            null,
            null,
            null
        )

        records.use { record ->
            while (record?.moveToNext() == true) {
                for (i in 0 until record.columnCount) {
                    if (record.getColumnName(i).equals("apiVersion")) {
                        return record.getInt(i)
                    }
                }
            }
        }

        return null
    }

    open fun getMeasurements(openScaleUser: OpenScaleUser): List<OpenScaleMeasurement> {
        Timber.d("Get measurements for user ${openScaleUser.id}")
        val measurementsUri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(authority)
            .path("measurements/" + openScaleUser.id)
            .build()

        val records = context.contentResolver.query(
            measurementsUri,
            null,
            null,
            null,
            null
        )

        val measurements = arrayListOf<OpenScaleMeasurement>()

        records.use { record ->
            while (record?.moveToNext() == true) {
                var id: Int? = null
                var dateTime: Date? = null
                var valuesJson: String? = null

                for (i in 0 until record.columnCount) {
                    when (record.getColumnName(i)) {
                        "_ID"      -> id = record.getInt(i)
                        "datetime" -> dateTime = Date(record.getLong(i))
                        "values_json" -> valuesJson = record.getString(i)
                    }
                }

                if (id != null && dateTime != null && valuesJson != null) {
                    // weight/fat/water/muscle are derived from the generic value set (single source).
                    measurements.add(
                        OpenScaleMeasurement.fromValues(
                            id, openScaleUser.id, dateTime, openScaleUser.username, OpenScaleMeasurementValue.parseList(valuesJson)
                        )
                    )
                } else {
                    Timber.e("Measurement row missing _ID/datetime/values_json")
                }
            }
        }

        Timber.d("Loaded ${measurements.size} measurements for user ${openScaleUser.id}")

        return measurements
    }

    private fun measurementUri(userId: Int): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority(authority)
        .path("measurements/$userId")
        .build()

    /**
     * Weight is in kg; fat/water/muscle in percent (openScale converts to the user's unit).
     * [valuesJson] is the same self-describing format as the outbound values and carries everything
     * the fixed columns cannot (bone mass, lean mass, custom types); openScale reads both in one go
     * and lets the fixed columns win on overlap.
     */
    private fun measurementValues(
        dateMs: Long, weightKg: Float,
        fat: Float?, water: Float?, muscle: Float?, valuesJson: String?
    ) = ContentValues().apply {
        put("datetime", dateMs)
        put("weight", weightKg)
        fat?.let { put("fat", it) }
        water?.let { put("water", it) }
        muscle?.let { put("muscle", it) }
        valuesJson?.let { put("values_json", it) }
    }

    /**
     * Inbound (bidirectional sync): add a measurement to openScale for [userId] via the
     * ContentProvider. openScale's insert uses OnConflictStrategy.IGNORE on (userId, timestamp), so
     * this can never overwrite an existing measurement — and it reports back nothing either way (the
     * provider returns a null Uri on success and on conflict alike), which is why the caller decides
     * insert vs. [updateMeasurement] by comparing timestamps itself.
     */
    open fun insertMeasurement(
        userId: Int, dateMs: Long, weightKg: Float,
        fat: Float? = null, water: Float? = null, muscle: Float? = null, valuesJson: String? = null
    ): Boolean =
        runCatching {
            context.contentResolver.insert(
                measurementUri(userId),
                measurementValues(dateMs, weightKg, fat, water, muscle, valuesJson)
            )
            true
        }.getOrElse { Timber.e(it, "inbound insert failed"); false }

    /**
     * Inbound: enrich the measurement openScale already holds at [dateMs] for [userId]. Only the
     * values passed here are touched; anything openScale has beyond them stays as it is.
     *
     * Returns whether openScale actually changed something — it reports 0 rows both when no
     * measurement matches the timestamp and when every value already had that exact value, so this
     * is a real "was anything imported" signal, not just "the call went through".
     */
    open fun updateMeasurement(
        userId: Int, dateMs: Long, weightKg: Float,
        fat: Float? = null, water: Float? = null, muscle: Float? = null, valuesJson: String? = null
    ): Boolean =
        runCatching {
            context.contentResolver.update(
                measurementUri(userId),
                measurementValues(dateMs, weightKg, fat, water, muscle, valuesJson),
                null, null
            ) > 0
        }.getOrElse { Timber.e(it, "inbound update failed"); false }

}