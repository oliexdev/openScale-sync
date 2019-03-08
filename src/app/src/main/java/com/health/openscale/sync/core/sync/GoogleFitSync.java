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
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.health.openscale.sync.gui.MainActivity;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import timber.log.Timber;

import static android.os.Looper.getMainLooper;
import static java.text.DateFormat.getTimeInstance;

public class GoogleFitSync {
    private Context context;

    public GoogleFitSync(Context context) {
        this.context = context;
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
        if (!GoogleSignIn.hasPermissions(GoogleSignIn.getLastSignedInAccount(context), getFitnessOptions())) {
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(mainIntent);

            showToast("Can't sync to GoogleFit. Please login into a Google Account");

            return false;
        }

        return true;
    }

    public void insertMeasurement(Date date, float weight) {
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

    public void deleteMeasurement(Date date) {
        if (!checkPermission()) {
            return;
        }

        DataDeleteRequest request = new DataDeleteRequest.Builder()
                        .setTimeInterval(date.getTime(), date.getTime(), TimeUnit.MILLISECONDS)
                        .addDataType(DataType.TYPE_WEIGHT)
                        .build();

        Fitness.getHistoryClient(context, GoogleSignIn.getLastSignedInAccount(context)).deleteData(request);
    }

    public void updateMeasurement(Date date, float weight) {
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

    public void queryMeasurements() {
        if (!checkPermission()) {
            return;
        }

        Calendar cal = Calendar.getInstance();
        cal.setTime(new Date());
        long endTime = cal.getTimeInMillis();
        cal.add(Calendar.YEAR, -1);
        long startTime = cal.getTimeInMillis();

        DataReadRequest readRequest = new DataReadRequest.Builder()
                .read(DataType.TYPE_WEIGHT)
                .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                //.bucketByTime(1, TimeUnit.MINUTES)
                .build();


        Fitness.getHistoryClient(context, GoogleSignIn.getLastSignedInAccount(context))
                .readData(readRequest)
                .addOnSuccessListener(new OnSuccessListener<DataReadResponse>() {
                    @Override
                    public void onSuccess(DataReadResponse dataReadResponse) {
                        Timber.d("onSuccess()");
                        for (DataSet set : dataReadResponse.getDataSets()) {
                            dumpDataSet(set);
                        }
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Timber.e( "onFailure() " + e);
                    }
                })
                .addOnCompleteListener(new OnCompleteListener<DataReadResponse>() {
                    @Override
                    public void onComplete(@NonNull Task<DataReadResponse> task) {
                        Timber.d( "onComplete()");
                    }
                });
    }

    private void dumpDataSet(DataSet dataSet) {
        Timber.d( "Data returned for Data type: " + dataSet.getDataType().getName());
        Timber.d("Data Points size " + dataSet.getDataPoints().size());
        DateFormat dateFormat = getTimeInstance();

        for (DataPoint dp : dataSet.getDataPoints()) {
            Timber.d( "Data point:");
            Timber.d( "\tType: " + dp.getDataType().getName());
            Timber.d( "\tStart: " + dateFormat.format(dp.getStartTime(TimeUnit.MILLISECONDS)));
            Timber.d( "\tEnd: " + dateFormat.format(dp.getEndTime(TimeUnit.MILLISECONDS)));
            for (Field field : dp.getDataType().getFields()) {
                Timber.d( "\tField: " + field.getName() + " Value: " + dp.getValue(field));
            }
        }
    }
}
