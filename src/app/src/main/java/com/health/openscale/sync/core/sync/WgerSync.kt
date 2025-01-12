/**
 * Copyright (C) 2021 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.core.sync;

import android.content.Context;
import android.os.Handler;
import android.text.format.DateFormat;
import android.widget.Toast;

import androidx.annotation.Keep;

import com.google.gson.annotations.SerializedName;
import com.health.openscale.sync.R;
import com.health.openscale.sync.core.datatypes.ScaleMeasurement;
import com.health.openscale.sync.gui.view.StatusView;

import java.io.IOException;
import java.util.Date;
import java.util.List;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.DELETE;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;
import timber.log.Timber;

import static android.os.Looper.getMainLooper;

public class WgerSync extends ScaleMeasurementSync {
    private Context context;
    private WgerApi wgerApi;
    private Retrofit wgerRetrofit;

    public WgerSync(Context context) {
        super(context);
        this.context = context;

        try {
            OkHttpClient client = new OkHttpClient.Builder().addInterceptor(new Interceptor() {
                @Override
                public okhttp3.Response intercept(Chain chain) throws IOException {
                    Request newRequest = chain.request().newBuilder()
                            .addHeader("Authorization", "Token " + prefs.getString("wgerApiKey", "7faf59e0fac4aceb12d90c2f2603349d4de8471b"))
                            .build();
                    return chain.proceed(newRequest);
                }
            }).build();

            wgerRetrofit = new Retrofit.Builder()
                    .client(client)
                    .baseUrl(prefs.getString("wgerServer", "https://wger.de/api/v2/"))
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();

            wgerApi = wgerRetrofit.create(WgerApi.class);
        } catch (Exception ex) {
            showToast(ex.getMessage() + " using default url https://wger.de/api/v2/");

            OkHttpClient client = new OkHttpClient.Builder().addInterceptor(new Interceptor() {
                @Override
                public okhttp3.Response intercept(Chain chain) throws IOException {
                    Request newRequest = chain.request().newBuilder()
                            .addHeader("Authorization", "Token " + prefs.getString("wgerApiKey", "7faf59e0fac4aceb12d90c2f2603349d4de8471b"))
                            .build();
                    return chain.proceed(newRequest);
                }
            }).build();

            wgerRetrofit = new Retrofit.Builder()
                    .client(client)
                    .baseUrl("https://wger.de/api/v2/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();

            wgerApi = wgerRetrofit.create(WgerApi.class);
        }
    }

    @Override
    public String getName() {
        return "WgerSync";
    }

    @Override
    public boolean isEnable() {
        return prefs.getBoolean("enableWger", false);
    }

    private void showToast(final String msg) {
        Handler mHandler = new Handler(getMainLooper());
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    public Call<WgerWeightEntryList> getWgerWeightList() {
        return wgerApi.getWeightEntryList();
    }

    @Override
    public void insert(final ScaleMeasurement measurement) {
        String wgerDateFormat = DateFormat.format("yyyy-MM-dd", measurement.getDate()).toString();

        Call<ResponseBody> responseBodyCall = wgerApi.insert(wgerDateFormat, measurement.getWeight());
        responseBodyCall.enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                if (response.isSuccessful()) {
                    Timber.d("wger successful inserted " + response.message());
                } else {
                    Timber.d("wger insert response error " + response.message());
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                Timber.e("wger insert failure " + t.getMessage());
            }
        });
    }

    @Override
    public void delete(final Date date) {
        String wgerDateFormat = DateFormat.format("yyyy-MM-dd", date).toString();

        Call<WgerWeightEntryList> callWgerWeightEntry = wgerApi.getWeightEntry(wgerDateFormat);

        callWgerWeightEntry.enqueue(new Callback<WgerWeightEntryList>() {
            @Override
            public void onResponse(Call<WgerWeightEntryList> call, Response<WgerWeightEntryList> response) {
                if (response.isSuccessful()) {
                    List<WgerWeightEntry> wgerWeightEntryList = response.body().results;

                    if (!wgerWeightEntryList.isEmpty()) {
                        long wgerId = wgerWeightEntryList.get(0).id;
                        Call<ResponseBody> responseBodyCall = wgerApi.delete(wgerId);

                        responseBodyCall.enqueue(new Callback<ResponseBody>() {
                            @Override
                            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                                if (response.isSuccessful()) {
                                    Timber.d("wger successful deleted " + response.message());
                                    return;
                                } else {
                                    Timber.d("wger delete response error " + response.message());
                                }
                            }

                            @Override
                            public void onFailure(Call<ResponseBody> call, Throwable t) {
                                Timber.e("wger update delete " + t.getMessage());
                            }
                        });
                    }
                } else {
                    Timber.d("get weight entry list error " + response.message());
                }
            }

            @Override
            public void onFailure(Call<WgerWeightEntryList> call, Throwable t) {
                Timber.e("get weight entry list failure " + t.getMessage());
            }
        });

    }

    @Override
    public void clear() {
        Call<WgerWeightEntryList> callWgerWeightEntryList = wgerApi.getWeightEntryList();

        callWgerWeightEntryList.enqueue(new Callback<WgerWeightEntryList>() {
            @Override
            public void onResponse(Call<WgerWeightEntryList> call, Response<WgerWeightEntryList> response) {
                if (response.isSuccessful()) {
                    Timber.d("successfully wger weight entry list updated");
                    List<WgerWeightEntry> wgerWeightEntryList = response.body().results;

                    for (WgerWeightEntry wgerWeightEntry : wgerWeightEntryList) {
                        Call<ResponseBody> responseBodyCall = wgerApi.delete(wgerWeightEntry.id);

                        responseBodyCall.enqueue(new Callback<ResponseBody>() {
                            @Override
                            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                                if (response.isSuccessful()) {
                                    Timber.d("wger successful deleted " + response.message());
                                } else {
                                    Timber.d("wger delete response error " + response.message());
                                }
                            }

                            @Override
                            public void onFailure(Call<ResponseBody> call, Throwable t) {
                                Timber.e("wger update delete " + t.getMessage());
                            }
                        });
                    }
                } else {
                    Timber.d("get weight entry list error " + response.message());
                }
            }

            @Override
            public void onFailure(Call<WgerWeightEntryList> call, Throwable t) {
                Timber.e("get weight entry list failure " + t.getMessage());
            }
        });
    }

    @Override
    public void update(final ScaleMeasurement measurement) {
        String wgerDateFormat = DateFormat.format("yyyy-MM-dd", measurement.getDate()).toString();

        Call<WgerWeightEntryList> callWgerWeightEntryList = wgerApi.getWeightEntry(wgerDateFormat);

        callWgerWeightEntryList.enqueue(new Callback<WgerWeightEntryList>() {
            @Override
            public void onResponse(Call<WgerWeightEntryList> call, Response<WgerWeightEntryList> response) {
                if (response.isSuccessful()) {
                    List<WgerWeightEntry> wgerWeightEntryList = response.body().results;

                    if (!wgerWeightEntryList.isEmpty()) {
                        long wgerId = wgerWeightEntryList.get(0).id;
                        Call<ResponseBody> responseBodyCall = wgerApi.update(wgerId, wgerDateFormat, measurement.getWeight());

                        responseBodyCall.enqueue(new Callback<ResponseBody>() {
                            @Override
                            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                                if (response.isSuccessful()) {
                                    Timber.d("wger successful updated " + response.message());
                                } else {
                                    Timber.d("wger update response error " + response.message());
                                }
                            }

                            @Override
                            public void onFailure(Call<ResponseBody> call, Throwable t) {
                                Timber.e("wger update failure " + t.getMessage());
                            }
                        });
                    }
                } else {
                    Timber.d("get weight entry list error " + response.message());
                }
            }

            @Override
            public void onFailure(Call<WgerWeightEntryList> call, Throwable t) {
                Timber.e("get weight entry list failure " + t.getMessage());
            }
        });
    }

    @Override
    public void checkStatus(final StatusView statusView) {
        Timber.d("Check Wger sync status");

        if (!isEnable()) {
            statusView.setCheck(false, "Wger sync is disabled");
            return;
        }

        Call<WgerWeightEntryList> callWgerWeightEntryList = wgerApi.getWeightEntryList();

        callWgerWeightEntryList.enqueue(new Callback<WgerWeightEntryList>() {
            @Override
            public void onResponse(Call<WgerWeightEntryList> call, Response<WgerWeightEntryList> response) {
                if (response.isSuccessful()) {
                    statusView.setCheck(true, context.getResources().getString(R.string.txt_wger_connection_successful));
                    Timber.d("wger successful connected " + response.message());
                } else {
                    statusView.setCheck(false, context.getResources().getString(R.string.txt_wger_wrong_api_key));
                    Timber.d("wger connected response error " + response.message());
                }
            }

            @Override
            public void onFailure(Call<WgerWeightEntryList> call, Throwable t) {
                statusView.setCheck(false, context.getResources().getString(R.string.txt_wger_wrong_api_key));
                Timber.e("get connection failure " + t.getMessage());
            }
        });
    }

    private interface WgerApi {
        @GET("weightentry")
        Call<WgerWeightEntryList> getWeightEntryList();

        @GET("weightentry/")
        Call<WgerWeightEntryList> getWeightEntry(@Query("date") String wgerDate);

        @POST("weightentry/")
        @FormUrlEncoded
        Call<ResponseBody> insert(@retrofit2.http.Field("date") String date, @Field("weight") float weight);

        @PATCH("weightentry/{wger_id}/")
        @FormUrlEncoded
        Call<ResponseBody> update(@Path(value = "wger_id", encoded = true) long wgerId, @retrofit2.http.Field("date") String date, @Field("weight") float weight);

        @DELETE("weightentry/{wger_id}/")
        Call<ResponseBody> delete(@Path(value = "wger_id", encoded = true) long wgerId);
    }

    @Keep
    public class WgerWeightEntryList {
        @SerializedName("results")
        public List<WgerWeightEntry> results;
    }

    @Keep
    public class WgerWeightEntry {
        @SerializedName("id")
        public long id;
        @SerializedName("date")
        public String date;
        @SerializedName("weight")
        public float weight;
    }
}


