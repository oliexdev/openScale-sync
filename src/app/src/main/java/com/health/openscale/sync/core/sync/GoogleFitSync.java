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
import com.health.openscale.sync.BuildConfig;
import com.health.openscale.sync.R;
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

            showToast(context.getResources().getString(R.string.txt_error_cannot_sign_in_to_googleFit));

            return false;
        }

        return true;
    }

    public void insertMeasurement(final Date date, float weight) {
        if (!checkPermission()) {
            return;
        }

        DataSource dataSource = new DataSource.Builder()
                        .setAppPackageName(BuildConfig.APPLICATION_ID)
                        .setDataType(DataType.TYPE_WEIGHT)
                        .setType(DataSource.TYPE_RAW)
                        .build();

        DataSet dataSet = DataSet.create(dataSource);
        DataPoint dataPoint = dataSet.createDataPoint().setTimestamp(date.getTime(), TimeUnit.MILLISECONDS);
        dataPoint.getValue(Field.FIELD_WEIGHT).setFloat(weight);
        dataSet.add(dataPoint);

        Fitness.getHistoryClient(context, GoogleSignIn.getLastSignedInAccount(context))
                .insertData(dataSet)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        Timber.d(context.getResources().getString(R.string.txt_successful_insert_googleFit_data) + " " + date);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Timber.e(context.getResources().getString(R.string.txt_error_insert_googleFit_data) + " " + e.getMessage());
                    }
                });
    }

    public void deleteMeasurement(final Date date) {
        if (!checkPermission()) {
            return;
        }

        DataDeleteRequest request = new DataDeleteRequest.Builder()
                .addDataType(DataType.TYPE_WEIGHT)
                .setTimeInterval(date.getTime() - 100, date.getTime()+100, TimeUnit.MILLISECONDS)
                .build();

        Fitness.getHistoryClient(context, GoogleSignIn.getLastSignedInAccount(context))
                .deleteData(request)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        Timber.d(context.getResources().getString(R.string.txt_successful_delete_googleFit_data) + " " + date);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Timber.e(context.getResources().getString(R.string.txt_error_deletion_googleFit_data) + " " + e.getMessage());
                    }
                });
    }

    public void clearMeasurements() {
        if (!checkPermission()) {
            return;
        }

        Calendar cal = Calendar.getInstance();
        cal.setTime(new Date());
        long endTime = cal.getTimeInMillis();
        cal.add(Calendar.YEAR, -5);
        long startTime = cal.getTimeInMillis();

        DataDeleteRequest request = new DataDeleteRequest.Builder()
                .addDataType(DataType.TYPE_WEIGHT)
                .setTimeInterval(startTime, endTime, TimeUnit.MILLISECONDS)
                .build();

        Fitness.getHistoryClient(context, GoogleSignIn.getLastSignedInAccount(context))
                .deleteData(request)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        Timber.d(context.getResources().getString(R.string.txt_successful_cleared_googleFit_data));
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Timber.e(context.getResources().getString(R.string.txt_error_clearing_googleFit_data) + " " + e.getMessage());
                    }
                });
    }

    public void updateMeasurement(final Date date, float weight) {
        if (!checkPermission()) {
            return;
        }

        DataSource dataSource = new DataSource.Builder()
                .setAppPackageName(BuildConfig.APPLICATION_ID)
                .setDataType(DataType.TYPE_WEIGHT)
                .setType(DataSource.TYPE_RAW)
                .build();

        DataSet dataSet = DataSet.create(dataSource);
        DataPoint dataPoint = dataSet.createDataPoint().setTimestamp(date.getTime(), TimeUnit.MILLISECONDS);
        dataPoint.getValue(Field.FIELD_WEIGHT).setFloat(weight);
        dataSet.add(dataPoint);

        DataUpdateRequest request = new DataUpdateRequest.Builder()
                .setDataSet(dataSet)
                .setTimeInterval(date.getTime()-100, date.getTime()+100, TimeUnit.MILLISECONDS)
                .build();

        Fitness.getHistoryClient(context, GoogleSignIn.getLastSignedInAccount(context))
                .updateData(request)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        Timber.d(context.getResources().getString(R.string.txt_successful_updated_googleFit_data) + " " + date);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Timber.e(context.getResources().getString(R.string.txt_error_updating_googleFit_data) + " " + e.getMessage());
                    }
                });
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
                        Timber.d(context.getResources().getString(R.string.txt_successful_request_googleFit_measurements));

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
