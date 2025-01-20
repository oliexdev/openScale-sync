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
import com.health.openscale.sync.core.datatypes.OpenScaleUser
import timber.log.Timber
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Date

class OpenScaleDataProvider(
    private val context: Context,
    private val sharedPreferences: SharedPreferences
) {
    private val authority = sharedPreferences.getString("packageName", "com.health.openscale") + ".provider"

    fun getUsers(): List<OpenScaleUser> {

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

    fun checkVersion(): Boolean {
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
                    var apiVersion : Int? = null
                    var versionCode : Int? = null

                    for (i in 0 until record.columnCount) {
                        if (record.getColumnName(i).equals("apiVersion")) {
                            apiVersion = record.getInt(i)
                        }

                        if (record.getColumnName(i).equals("versionCode")) {
                            versionCode = record.getInt(i)
                        }
                    }

                Timber.d("openScale version $versionCode with content provider API version $apiVersion")

                    if (versionCode != null) {
                        if (versionCode >= 43) {
                            return true
                        }
                    }
                }
            }

        return false
    }

    fun getMeasurements(openScaleUser: OpenScaleUser): List<OpenScaleMeasurement> {
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
                var weight: Float? = null
                var fat: Float? = null
                var water: Float? = null
                var muscle: Float? = null

                for (i in 0 until record.columnCount) {
                    if (record.getColumnName(i).equals("_ID")) {
                        id = record.getInt(i)
                    }

                    if (record.getColumnName(i).equals("datetime")) {
                        val timestamp = record.getLong(i)
                        dateTime = Date(timestamp)
                    }

                    if (record.getColumnName(i).equals("weight")) {
                        weight = roundFloat(record.getFloat(i))
                    }

                    if (record.getColumnName(i).equals("fat")) {
                        fat = roundFloat(record.getFloat(i))
                    }

                    if (record.getColumnName(i).equals("water")) {
                        water = roundFloat(record.getFloat(i))
                    }

                    if (record.getColumnName(i).equals("muscle")) {
                        muscle = roundFloat(record.getFloat(i))
                    }
                }

                if (id != null && dateTime != null && weight != null && fat != null && water != null && muscle != null) {
                    var measurement = OpenScaleMeasurement(
                        id,
                        dateTime,
                        weight,
                        fat,
                        water,
                        muscle
                    )

                    measurements.add(measurement)
                    Timber.d("Measurement by provide $measurement")
                } else {
                    Timber.e("Not all required parameters are set")
                }
            }
        }

        Timber.d("Loaded ${measurements.size} measurements for user ${openScaleUser.id}")

        return measurements
    }

    private fun roundFloat(number: Float): Float {
        val bigDecimal = BigDecimal(number.toDouble())
        val rounded = bigDecimal.setScale(2, RoundingMode.HALF_UP)
        return rounded.toFloat()
    }

    fun insertMeasurement(date: Date, weight: Float, userId: Int) {
        val measurementsUri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(authority)
            .path("measurements")
            .build()

        val values = ContentValues()

        values.put("datetime", date.time)
        values.put("weight", weight)
        values.put("userId", userId)

        context.contentResolver.insert(measurementsUri, values)
    }

    fun getSavedSelectedUserId(): Int? {
        val userId = sharedPreferences.getInt("selectedOpenScaleUserId", -1)

        if (userId == -1) {
            return null
        }

        return userId
    }

    fun saveSelectedUserId(userId: Int?) {
        if (userId != null) {
            sharedPreferences.edit().putInt("selectedOpenScaleUserId", userId).apply()
        } else {
            sharedPreferences.edit().putInt("selectedOpenScaleUserId", -1).apply()
        }
    }
}