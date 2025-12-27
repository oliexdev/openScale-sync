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
import com.health.openscale.sync.core.service.SyncResult
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

class WgerSync(private val wgerRetrofit: Retrofit) : SyncInterface() {
    private val wgerApi : WgerApi = wgerRetrofit.create(WgerApi::class.java)
    private val wgerDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").apply { timeZone = TimeZone.getDefault() }

    suspend fun fullSync(measurements: List<OpenScaleMeasurement>) : SyncResult<Unit> {
        var failureCount = 0

        measurements.forEach { measurement ->
            try {
                val response: Response<Unit> = wgerApi.insert(wgerDateFormat.format(measurement.date), measurement.weight)
                if (!response.isSuccessful) {
                    Timber.d("wger ${wgerDateFormat.format(measurement.date)} insert response error ${response.errorBody()?.string()}")
                    failureCount++
                }
            } catch (e: Exception) {
                return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
            }
        }

        if (failureCount > 0) {
            return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"$failureCount of ${measurements.size} measurements failed to sync",null)
        } else {
            return SyncResult.Success(Unit)
        }
    }

    suspend fun insert(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
            try {
                val response: Response<Unit> = wgerApi.insert(wgerDateFormat.format(measurement.date), measurement.weight)
                if (response.isSuccessful) {
                    return SyncResult.Success(Unit)
                } else {
                    return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"wger ${wgerDateFormat.format(measurement.date)} insert response error ${response.errorBody()?.string()}")
                }
            } catch (e: Exception) {
                return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
            }
    }

    suspend fun delete(date: Date) : SyncResult<Unit> {
        try {
            val wgerWeightEntryList = wgerApi.getWeightEntry(wgerDateFormat.format(date))
            if (wgerWeightEntryList.results?.isNotEmpty() == true) {
                val wgerId = wgerWeightEntryList.results[0].id
                val response: Response<Unit> = wgerApi.delete(wgerId)
                if (response.isSuccessful) {
                    return SyncResult.Success(Unit)
                } else {
                    return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"wger delete response error ${response.errorBody()?.string()}}")
                }
            } else {
                return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"no weight entry found for date: ${wgerDateFormat.format(date)}")
            }
        } catch (e: Exception) {
            return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
        }
    }

    suspend fun clear() : SyncResult<Unit> {
            try {
                var wgerWeightEntryList = wgerApi.weightEntryList()

                do {
                    wgerWeightEntryList.results?.forEach { wgerWeightEntry ->
                        val response: Response<Unit> = wgerApi.delete(wgerWeightEntry.id)
                        if (!response.isSuccessful) {
                            return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"wger delete response error ${response.errorBody()?.string()}}")
                        }
                    }

                    wgerWeightEntryList = wgerApi.weightEntryList()
                } while (wgerWeightEntryList.count != 0L)

                return SyncResult.Success(Unit)
            } catch (e: Exception) {
                return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
            }
    }

    suspend fun update(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
            try {
                val wgerWeightEntryList = wgerApi.getWeightEntry(wgerDateFormat.format(measurement.date))
                if (wgerWeightEntryList.results?.isNotEmpty() == true) {
                    val wgerId = wgerWeightEntryList.results[0].id
                    val response: Response<Unit> = wgerApi.update(wgerId, wgerDateFormat.format(measurement.date), measurement.weight)
                    if (response.isSuccessful) {
                        return SyncResult.Success(Unit)
                    } else {
                        return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"wger delete response error ${response.errorBody()?.string()}}")
                    }
                } else {
                    return SyncResult.Failure(SyncResult.ErrorType.API_ERROR,"no weight entry found for date: ${wgerDateFormat.format(measurement.date)}")
                }
            } catch (e: Exception) {
                return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR,null ,e)
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


