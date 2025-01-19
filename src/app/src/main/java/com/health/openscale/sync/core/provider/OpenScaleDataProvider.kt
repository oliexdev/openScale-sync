package com.health.openscale.sync.core.provider

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.datatypes.OpenScaleUser
import timber.log.Timber
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
                        weight = record.getFloat(i)
                    }

                    if (record.getColumnName(i).equals("fat")) {
                        fat = record.getFloat(i)
                    }

                    if (record.getColumnName(i).equals("water")) {
                        water = record.getFloat(i)
                    }

                    if (record.getColumnName(i).equals("muscle")) {
                        muscle = record.getFloat(i)
                    }
                }

                if (id != null && dateTime != null && weight != null && fat != null && water != null && muscle != null) {
                    measurements.add(
                        OpenScaleMeasurement(
                            id,
                            dateTime,
                            weight,
                            fat,
                            water,
                            muscle
                        )
                    )
                } else {
                    Timber.e("Not all required parameters are set")
                }
            }
        }

        Timber.d("Loaded ${measurements.size} measurements for user ${openScaleUser.id}")

        return measurements
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