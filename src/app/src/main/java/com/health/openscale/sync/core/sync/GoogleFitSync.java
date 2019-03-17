/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.core.sync;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.widget.Toast;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.fitness.Fitness;
import com.google.android.gms.fitness.FitnessOptions;
import com.google.android.gms.fitness.data.DataPoint;
import com.google.android.gms.fitness.data.DataSet;
import com.google.android.gms.fitness.data.DataSource;
import com.google.android.gms.fitness.data.DataType;
import com.google.android.gms.fitness.data.Field;
import com.google.android.gms.fitness.request.DataDeleteRequest;
import com.google.android.gms.fitness.request.DataReadRequest;
import com.google.android.gms.fitness.request.DataUpdateRequest;
import com.google.android.gms.fitness.result.DataReadResponse;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.health.openscale.sync.gui.MainActivity;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import timber.log.Timber;

import static android.os.Looper.getMainLooper;

public class GoogleFitSync {
    private Context context;
    private ArrayList<GoogleFitMeasurement> googleFitMeasurements;

    public class GoogleFitMeasurement {
        public GoogleFitMeasurement(Date date, float weight) {
            this.date = date;
            this.weight = weight;
        }

        @Override
        public String toString() {
            return "date " + date + " weight " + weight;
        }

        public Date date;
        public float weight;
    }

    public GoogleFitSync(Context context) {
        this.context = context;
        this.googleFitMeasurements = new ArrayList<>();
    }

    public static FitnessOptions getFitnessOptions() {
        return FitnessOptions.builder()
               .addDataType(DataType.TYPE_WEIGHT, FitnessOptions.ACCESS_READ)
               .addDataType(DataType.TYPE_WEIGHT, FitnessOptions.ACCESS_WRITE)
               .build();
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

    private boolean checkPermission() {
        if (!(GoogleSignIn.hasPermissions(GoogleSignIn.getLastSignedInAccount(context), getFitnessOptions())
        && GoogleSignIn.getLastSignedInAccount(context) != null)) {
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(mainIntent);

            showToast("Can't sync to GoogleFit. Please login into a Google Account");

            return false;
        }

        return true;
    }

    public void insertMeasurement(final Date date, float weight) {
        if (!checkPermission()) {
            return;
        }

        DataSource dataSource = new DataSource.Builder()
                        .setAppPackageName(context)
                        .setDataType(DataType.TYPE_WEIGHT)
                        .setType(DataSource.TYPE_RAW)
                        .build();

        DataSet dataSet = DataSet.create(dataSource);
        DataPoint dataPoint = dataSet.createDataPoint().setTimeInterval(date.getTime(), date.getTime(), TimeUnit.MILLISECONDS);
        dataPoint.getValue(Field.FIELD_WEIGHT).setFloat(weight);
        dataSet.add(dataPoint);

        Fitness.getHistoryClient(context, GoogleSignIn.getLastSignedInAccount(context)).insertData(dataSet);
    }

    public void deleteMeasurement(final Date date) {
        if (!checkPermission()) {
            return;
        }

        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        long endTime = cal.getTimeInMillis() + 100;
        cal.add(Calendar.MILLISECOND, -100);
        long startTime = cal.getTimeInMillis();

        DataDeleteRequest request = new DataDeleteRequest.Builder()
                .deleteAllSessions()
                .deleteAllData()
                .setTimeInterval(startTime, endTime, TimeUnit.MILLISECONDS)
                .build();

        Fitness.getHistoryClient(context, GoogleSignIn.getLastSignedInAccount(context)).
                deleteData(request).
                addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        Timber.d("Successful deleted GoogleFit data " + date);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Timber.e("Error on deletion of GoogleFit data " + e.getMessage());
                    }
                });
    }

    public void updateMeasurement(final Date date, float weight) {
        if (!checkPermission()) {
            return;
        }

        DataSource dataSource = new DataSource.Builder()
                .setAppPackageName(context)
                .setDataType(DataType.TYPE_WEIGHT)
                .setType(DataSource.TYPE_RAW)
                .build();

        DataSet dataSet = DataSet.create(dataSource);
        DataPoint dataPoint = dataSet.createDataPoint().setTimeInterval(date.getTime(), date.getTime(), TimeUnit.MILLISECONDS);
        dataPoint.getValue(Field.FIELD_WEIGHT).setFloat(weight);
        dataSet.add(dataPoint);

        DataUpdateRequest request = new DataUpdateRequest.Builder()
                .setDataSet(dataSet)
                .setTimeInterval(date.getTime(), date.getTime(), TimeUnit.MILLISECONDS)
                .build();

        Fitness.getHistoryClient(context, GoogleSignIn.getLastSignedInAccount(context)).updateData(request);
    }

    public Task<DataReadResponse> queryMeasurements() {
        if (!checkPermission()) {
            return null;
        }

        Calendar cal = Calendar.getInstance();
        cal.setTime(new Date());
        long endTime = cal.getTimeInMillis();
        cal.add(Calendar.YEAR, -5);
        long startTime = cal.getTimeInMillis();

        DataReadRequest readRequest = new DataReadRequest.Builder()
                .read(DataType.TYPE_WEIGHT)
                .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                //.bucketByTime(1, TimeUnit.MINUTES)
                .build();

        return Fitness.getHistoryClient(context, GoogleSignIn.getLastSignedInAccount(context))
                .readData(readRequest)
                .addOnSuccessListener(new OnSuccessListener<DataReadResponse>() {
                    @Override
                    public void onSuccess(DataReadResponse dataReadResponse) {
                        Timber.d("successful request GoogleFit measurements");

                        for (DataSet set : dataReadResponse.getDataSets()) {
                            for (DataPoint dp : set.getDataPoints()) {
                                googleFitMeasurements.add(new GoogleFitMeasurement(new Date(dp.getStartTime(TimeUnit.MILLISECONDS)), dp.getValue(Field.FIELD_WEIGHT).asFloat()));
                            }
                        }
                    }
                });
    }

    public List<GoogleFitMeasurement> getQueryMeasurementsResults() {
        return googleFitMeasurements;
    }
}
