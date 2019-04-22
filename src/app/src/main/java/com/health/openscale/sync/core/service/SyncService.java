/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.core.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;

import com.health.openscale.sync.R;
import com.health.openscale.sync.core.sync.GoogleFitSync;

import java.util.Date;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import timber.log.Timber;

import static androidx.core.app.NotificationCompat.PRIORITY_LOW;

public class SyncService extends Service {
    private static final int ID_SERVICE = 5;

    private GoogleFitSync syncProvider;
    private SharedPreferences prefs;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        syncProvider = new GoogleFitSync(getApplicationContext());
        prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        onHandleIntent(intent);

        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if (Timber.forest().isEmpty()) {
            Timber.plant(new Timber.DebugTree());
        }

        showNotification();
    }

   // @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        if (!prefs.getBoolean("enableGoogleFit", true)) {
            Timber.d(getResources().getString(R.string.txt_sign_request_received_but_disabled));
            stopForeground(true);
            return;
        }

        Timber.d(getResources().getString(R.string.txt_sign_request_received));

        String mode = intent.getExtras().getString("mode");
        int openScaleUserId = prefs.getInt("openScaleUserId", 0);

        if (mode.equals("insert")) {
            int userId = intent.getIntExtra("userId", 0);
            float weight = intent.getFloatExtra("weight", 0.0f);
            Date date = new Date(intent.getLongExtra("date", 0L));

            Timber.d( getResources().getString(R.string.txt_sync_insert)+ " user Id: " + userId + " weight: " + weight + " date: " + date);

            if (userId == openScaleUserId) {
                syncProvider.insertMeasurement(date, weight);
            } else {
                Timber.d(getResources().getString(R.string.txt_openScale_userid_missmatch));
            }
        } else if (mode.equals("update")) {
            int userId = intent.getIntExtra("userId", 0);
            float weight = intent.getFloatExtra("weight", 0.0f);
            Date date = new Date(intent.getLongExtra("date", 0L));

            Timber.d(getResources().getString(R.string.txt_sync_update) + " userId: " + userId + " weight: " + weight + " date: " + date);

            if (userId == openScaleUserId) {
                syncProvider.updateMeasurement(date, weight);
            }
            else {
                Timber.d(getResources().getString(R.string.txt_openScale_userid_missmatch));
            }
        } else if (mode.equals("delete")) {
            Date date = new Date(intent.getLongExtra("date", 0L));

            Timber.d(getResources().getString(R.string.txt_sync_delete) + " date: " + date);

            syncProvider.deleteMeasurement(date);
        } else if (mode.equals("clear")) {
            Timber.d(getResources().getString(R.string.txt_sync_clear));

            syncProvider.clearMeasurements();
        }

        stopForeground(true);
    }

    private void showNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String channelId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? createNotificationChannel(notificationManager) : "";
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, channelId);
        Notification notification = notificationBuilder
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_launcher_openscale_sync)
                .setPriority(PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();

        startForeground(ID_SERVICE, notification);
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private String createNotificationChannel(NotificationManager notificationManager){
        String channelId = getResources().getString(R.string.app_name);

        NotificationChannel channel = new NotificationChannel(channelId, getResources().getString(R.string.app_name) + " service", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setImportance(NotificationManager.IMPORTANCE_DEFAULT);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        notificationManager.createNotificationChannel(channel);

        return channelId;
    }
}

/*
    Intent insertIntent = new Intent();
    insertIntent.setComponent(new ComponentName("com.health.openscale.sync", "com.health.openscale.sync.core.service.SyncService"));
            insertIntent.putExtra("mode", "insert");
            insertIntent.putExtra("userId", scaleMeasurement.getUserId());
            insertIntent.putExtra("weight", scaleMeasurement.getWeight());
            insertIntent.putExtra("date", scaleMeasurement.getDateTime().getTime());
            ContextCompat.startForegroundService(context, insertIntent);
*/