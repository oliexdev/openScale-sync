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


import android.text.format.DateFormat
import com.google.gson.annotations.SerializedName
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import timber.log.Timber
import java.util.Date

class WgerSync(private val wgerRetrofit: Retrofit) : SyncInterface() {
    private val wgerApi : WgerApi = wgerRetrofit.create(WgerApi::class.java)

    suspend fun fullSync(measurements: List<OpenScaleMeasurement>) {
        measurements.forEach { measurement ->
            val wgerDateFormat = DateFormat.format("yyyy-MM-dd", measurement.date).toString()

            try {
                val response: Response<Unit> = wgerApi.insert(wgerDateFormat, measurement.weight)
                if (!response.isSuccessful) {
                    Timber.d("wger $wgerDateFormat insert response error ${response.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                Timber.e("wger $wgerDateFormat insert failure ${e.message}")
            }
        }
        Timber.d("wger full sync completed")
    }

    suspend fun insert(measurement: OpenScaleMeasurement) {
            try {
                val wgerDateFormat = DateFormat.format("yyyy-MM-dd", measurement.date).toString()

                val response: Response<Unit> = wgerApi.insert(wgerDateFormat, measurement.weight)
                if (response.isSuccessful) {
                    Timber.d("wger $wgerDateFormat successful inserted")
                } else {
                    Timber.d("wger $wgerDateFormat insert response error ${response.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                Timber.e("wger insert failure ${e.message}")
            }
    }

    suspend fun delete(date: Date) {
        val wgerDateFormat = DateFormat.format("yyyy-MM-dd", date).toString()

        try {
            val wgerWeightEntryList = wgerApi.getWeightEntry(wgerDateFormat)
            if (wgerWeightEntryList.results?.isNotEmpty() == true) {
                val wgerId = wgerWeightEntryList.results[0].id
                val response: Response<Unit> = wgerApi.delete(wgerId)
                if (response.isSuccessful) {
                    Timber.d("wger $wgerId successful deleted")
                } else {
                    Timber.d("wger delete response error ${response.errorBody()?.string()}}")
                }
            } else {
                Timber.d("no weight entry found for date: $wgerDateFormat")
            }
        } catch (e: Exception) {
            Timber.e("wger delete failure ${e.message}")
        }
    }

    suspend fun clear() {
        Timber.d("wger clear started")
            try {
                var wgerWeightEntryList = wgerApi.weightEntryList()

                do {
                    wgerWeightEntryList.results?.forEach { wgerWeightEntry ->
                        val response: Response<Unit> = wgerApi.delete(wgerWeightEntry.id)
                        if (!response.isSuccessful) {
                            Timber.d("wger delete response error ${response.errorBody()?.string()}}")
                        }
                    }

                    wgerWeightEntryList = wgerApi.weightEntryList()
                } while (wgerWeightEntryList.count != 0L)

                Timber.d("wger successful cleared")
            } catch (e: Exception) {
                Timber.e("wger clear failure ${e.message}")
            }
    }

    suspend fun update(measurement: OpenScaleMeasurement) {
            try {
                val wgerDateFormat = DateFormat.format("yyyy-MM-dd", measurement.date).toString()

                val wgerWeightEntryList = wgerApi.getWeightEntry(wgerDateFormat)
                if (wgerWeightEntryList.results?.isNotEmpty() == true) {
                    val wgerId = wgerWeightEntryList.results[0].id
                    val response: Response<Unit> = wgerApi.update(wgerId, wgerDateFormat, measurement.weight)
                    if (response.isSuccessful) {
                        Timber.d("wger $wgerId successful updated")
                    } else {
                        Timber.d("wger delete response error ${response.errorBody()?.string()}}")
                    }
                } else {
                    Timber.d("no weight entry found for date: $wgerDateFormat")
                }
            } catch (e: Exception) {
                Timber.e("wger update failure ${e.message}")
            }
    }

    interface WgerApi {
        @GET("weightentry")
        suspend fun weightEntryList(): WgerWeightEntryList

        @GET("weightentry/")
        suspend fun getWeightEntry(@Query("date") wgerDate: String): WgerWeightEntryList

        @POST("weightentry/")
        @FormUrlEncoded
        suspend fun insert(@Field("date") date: String?, @Field("weight") weight: Float): Response<Unit>

        @PATCH("weightentry/{wger_id}/")
        @FormUrlEncoded
        suspend fun update(
            @Path("wger_id") wgerId: Long,
            @Field("date") date: String,
            @Field("weight") weight: Float
        ): Response<Unit>

        @DELETE("weightentry/{wger_id}/")
        suspend fun delete(@Path("wger_id") wgerId: Long) : Response<Unit>
    }

    data class WgerWeightEntryList(
        @SerializedName("count")
        val count: Long = -1,
        @SerializedName("next")
        val next: String? = null,
        @SerializedName("previous")
        val previous: String? = null,
        @SerializedName("results")
        val results: List<WgerWeightEntry>? = null
    )

    data class WgerWeightEntry(
        @SerializedName("id")
        val id: Long = 0,
        @SerializedName("date")
        val date: String? = null,
        @SerializedName("weight")
        val weight: Float = 0f
    )
}


