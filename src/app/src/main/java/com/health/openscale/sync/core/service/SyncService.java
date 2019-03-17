/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.core.service;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;

import com.health.openscale.sync.R;
import com.health.openscale.sync.core.sync.GoogleFitSync;

import java.util.Date;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import timber.log.Timber;

import static androidx.core.app.NotificationCompat.PRIORITY_MIN;

public class SyncService extends IntentService {
    private static final int ID_SERVICE = 5;

    private GoogleFitSync syncProvider;
    private SharedPreferences prefs;

    public SyncService() {
        super("openScale Sync Service");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        syncProvider = new GoogleFitSync(getApplicationContext());
        prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        if (!prefs.getBoolean("enableGoogleFit", true)) {
            Timber.d("Sync request received but GoogleFit sync is disabeld");
            return;
        }

        Timber.d("Sync request received");

        showNotification();

        String mode = intent.getExtras().getString("mode");

        if (mode.equals("insert")) {
            Timber.d("Insert measurement");

            int userId = intent.getIntExtra("userId", 0);
            float weight = intent.getFloatExtra("weight", 0.0f);
            Date date = new Date(intent.getLongExtra("date", 0L));

            Timber.d("user Id " + userId);
            Timber.d("weight " + weight);
            Timber.d("date " + date);

            syncProvider.insertMeasurement(date,weight);
        } else if (mode.equals("update")) {
            Timber.d("Update measurement");

            int userId = intent.getIntExtra("userId", 0);
            float weight = intent.getFloatExtra("weight", 0.0f);
            Date date = new Date(intent.getLongExtra("date", 0L));

            Timber.d("user Id " + userId);
            Timber.d("weight " + weight);

            syncProvider.updateMeasurement(date,weight);
        } else if (mode.equals("delete")) {
            Timber.d("Delete measurement");

            Date date = new Date(intent.getLongExtra("date", 0L));

            Timber.d("date " + date);

            syncProvider.deleteMeasurement(date);
        }
    }

    private void showNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String channelId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? createNotificationChannel(notificationManager) : "openScale Sync";
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, channelId);
        Notification notification = notificationBuilder
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_launcher_openscale_sync)
                .setPriority(PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();

        startForeground(ID_SERVICE, notification);
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private String createNotificationChannel(NotificationManager notificationManager){
        String channelId = "openScale Sync";

        NotificationChannel channel = new NotificationChannel(channelId, "openScale Sync Service", NotificationManager.IMPORTANCE_DEFAULT);
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