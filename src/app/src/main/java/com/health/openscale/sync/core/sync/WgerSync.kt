/**
 * Copyright (C) 2021 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.core.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.health.openscale.sync.core.datatypes.ScaleMeasurement
import com.health.openscale.sync.gui.view.StatusViewAdapter
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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

class WgerSync(private val context: Context) : ScaleMeasurementSync(context) {
    private var wgerApi: WgerApi
    private var wgerRetrofit: Retrofit

    init {
        try {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val newRequest = chain.request().newBuilder()
                    .addHeader(
                        "Authorization",
                        "Token " + prefs.getString(
                            "wgerApiKey",
                            "7faf59e0fac4aceb12d90c2f2603349d4de8471b"
                        )
                    )
                    .build()
                chain.proceed(newRequest)
            }.build()

            wgerRetrofit = Retrofit.Builder()
                .client(client)
                .baseUrl(prefs.getString("wgerServer", "https://wger.de/api/v2/"))
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            wgerApi = wgerRetrofit.create(WgerApi::class.java)
        } catch (ex: Exception) {
            showToast(ex.message + " using default url https://wger.de/api/v2/")

            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val newRequest = chain.request().newBuilder()
                    .addHeader(
                        "Authorization",
                        "Token " + prefs.getString(
                            "wgerApiKey",
                            "7faf59e0fac4aceb12d90c2f2603349d4de8471b"
                        )
                    )
                    .build()
                chain.proceed(newRequest)
            }.build()

            wgerRetrofit = Retrofit.Builder()
                .client(client)
                .baseUrl("https://wger.de/api/v2/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            wgerApi = wgerRetrofit.create(WgerApi::class.java)
        }
    }

    override fun getName(): String {
        return "WgerSync"
    }

    override fun isEnable(): Boolean {
        return prefs.getBoolean("enableWger", false)
    }

    private fun showToast(msg: String) {
        val mHandler = Handler(Looper.getMainLooper())
        mHandler.post { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }

    val wgerWeightList: Call<WgerWeightEntryList>
        get() = wgerApi.weightEntryList

    override suspend fun insert(measurement: ScaleMeasurement) {
        val wgerDateFormat = DateFormat.format("yyyy-MM-dd", measurement.date).toString()

        val responseBodyCall = wgerApi!!.insert(wgerDateFormat, measurement.weight)
        responseBodyCall.enqueue(object : Callback<ResponseBody?> {
            override fun onResponse(call: Call<ResponseBody?>, response: Response<ResponseBody?>) {
                if (response.isSuccessful) {
                    Timber.d("wger successful inserted " + response.message())
                } else {
                    Timber.d("wger insert response error " + response.message())
                }
            }

            override fun onFailure(call: Call<ResponseBody?>, t: Throwable) {
                Timber.e("wger insert failure " + t.message)
            }
        })
    }

    override fun delete(date: Date) {
        val wgerDateFormat = DateFormat.format("yyyy-MM-dd", date).toString()

        val callWgerWeightEntry = wgerApi!!.getWeightEntry(wgerDateFormat)

        callWgerWeightEntry.enqueue(object : Callback<WgerWeightEntryList> {
            override fun onResponse(
                call: Call<WgerWeightEntryList>,
                response: Response<WgerWeightEntryList>
            ) {
                if (response.isSuccessful) {
                    val wgerWeightEntryList = response.body()!!.results

                    if (!wgerWeightEntryList!!.isEmpty()) {
                        val wgerId = wgerWeightEntryList[0].id
                        val responseBodyCall = wgerApi.delete(wgerId)

                        responseBodyCall.enqueue(object : Callback<ResponseBody?> {
                            override fun onResponse(
                                call: Call<ResponseBody?>,
                                response: Response<ResponseBody?>
                            ) {
                                if (response.isSuccessful) {
                                    Timber.d("wger successful deleted " + response.message())
                                    return
                                } else {
                                    Timber.d("wger delete response error " + response.message())
                                }
                            }

                            override fun onFailure(call: Call<ResponseBody?>, t: Throwable) {
                                Timber.e("wger update delete " + t.message)
                            }
                        })
                    }
                } else {
                    Timber.d("get weight entry list error " + response.message())
                }
            }

            override fun onFailure(call: Call<WgerWeightEntryList>, t: Throwable) {
                Timber.e("get weight entry list failure " + t.message)
            }
        })
    }

    override fun clear() {
        val callWgerWeightEntryList: Call<WgerWeightEntryList> = wgerApi.weightEntryList

        callWgerWeightEntryList.enqueue(object : Callback<WgerWeightEntryList> {
            override fun onResponse(
                call: Call<WgerWeightEntryList>,
                response: Response<WgerWeightEntryList>
            ) {
                if (response.isSuccessful) {
                    Timber.d("successfully wger weight entry list updated")
                    val wgerWeightEntryList = response.body()!!.results

                    for (wgerWeightEntry in wgerWeightEntryList!!) {
                        val responseBodyCall = wgerApi!!.delete(wgerWeightEntry.id)

                        responseBodyCall.enqueue(object : Callback<ResponseBody?> {
                            override fun onResponse(
                                call: Call<ResponseBody?>,
                                response: Response<ResponseBody?>
                            ) {
                                if (response.isSuccessful) {
                                    Timber.d("wger successful deleted " + response.message())
                                } else {
                                    Timber.d("wger delete response error " + response.message())
                                }
                            }

                            override fun onFailure(call: Call<ResponseBody?>, t: Throwable) {
                                Timber.e("wger update delete " + t.message)
                            }
                        })
                    }
                } else {
                    Timber.d("get weight entry list error " + response.message())
                }
            }

            override fun onFailure(call: Call<WgerWeightEntryList>, t: Throwable) {
                Timber.e("get weight entry list failure " + t.message)
            }
        })
    }

    override fun update(measurement: ScaleMeasurement) {
        val wgerDateFormat = DateFormat.format("yyyy-MM-dd", measurement.date).toString()

        val callWgerWeightEntryList = wgerApi!!.getWeightEntry(wgerDateFormat)

        callWgerWeightEntryList.enqueue(object : Callback<WgerWeightEntryList> {
            override fun onResponse(
                call: Call<WgerWeightEntryList>,
                response: Response<WgerWeightEntryList>
            ) {
                if (response.isSuccessful) {
                    val wgerWeightEntryList = response.body()!!.results

                    if (!wgerWeightEntryList!!.isEmpty()) {
                        val wgerId = wgerWeightEntryList[0].id
                        val responseBodyCall =
                            wgerApi.update(wgerId, wgerDateFormat, measurement.weight)

                        responseBodyCall.enqueue(object : Callback<ResponseBody?> {
                            override fun onResponse(
                                call: Call<ResponseBody?>,
                                response: Response<ResponseBody?>
                            ) {
                                if (response.isSuccessful) {
                                    Timber.d("wger successful updated " + response.message())
                                } else {
                                    Timber.d("wger update response error " + response.message())
                                }
                            }

                            override fun onFailure(call: Call<ResponseBody?>, t: Throwable) {
                                Timber.e("wger update failure " + t.message)
                            }
                        })
                    }
                } else {
                    Timber.d("get weight entry list error " + response.message())
                }
            }

            override fun onFailure(call: Call<WgerWeightEntryList>, t: Throwable) {
                Timber.e("get weight entry list failure " + t.message)
            }
        })
    }

    override fun hasPermission(): Boolean {
        TODO("Not yet implemented")
    }

    override fun askPermission(context: ComponentActivity) {

    }

    override fun checkStatus(statusView: StatusViewAdapter) {
        Timber.d("Check Wger sync status")

        if (!isEnable()) {
            return
        }

        val callWgerWeightEntryList: Call<WgerWeightEntryList> = wgerApi.weightEntryList

        callWgerWeightEntryList.enqueue(object : Callback<WgerWeightEntryList?> {
            override fun onResponse(
                call: Call<WgerWeightEntryList?>,
                response: Response<WgerWeightEntryList?>
            ) {
                if (response.isSuccessful) {
                    Timber.d("wger successful connected " + response.message())
                } else {
                    Timber.d("wger connected response error " + response.message())
                }
            }

            override fun onFailure(call: Call<WgerWeightEntryList?>, t: Throwable) {
                Timber.e("get connection failure " + t.message)
            }
        })
    }

    private interface WgerApi {
        @get:GET("weightentry")
        val weightEntryList: Call<WgerWeightEntryList>

        @GET("weightentry/")
        fun getWeightEntry(@Query("date") wgerDate: String?): Call<WgerWeightEntryList>

        @POST("weightentry/")
        @FormUrlEncoded
        fun insert(@Field("date") date: String?, @Field("weight") weight: Float): Call<ResponseBody>

        @PATCH("weightentry/{wger_id}/")
        @FormUrlEncoded
        fun update(
            @Path(value = "wger_id", encoded = true) wgerId: Long,
            @Field("date") date: String?,
            @Field("weight") weight: Float
        ): Call<ResponseBody>

        @DELETE("weightentry/{wger_id}/")
        fun delete(@Path(value = "wger_id", encoded = true) wgerId: Long): Call<ResponseBody>
    }

    @Keep
    inner class WgerWeightEntryList {
        @JvmField
        @SerializedName("results")
        var results: List<WgerWeightEntry>? = null
    }

    @Keep
    inner class WgerWeightEntry {
        @SerializedName("id")
        var id: Long = 0

        @JvmField
        @SerializedName("date")
        var date: String? = null

        @JvmField
        @SerializedName("weight")
        var weight: Float = 0f
    }
}


