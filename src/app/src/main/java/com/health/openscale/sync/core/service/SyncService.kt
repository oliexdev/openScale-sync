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
package com.health.openscale.sync.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.health.openscale.sync.R
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import timber.log.Timber.Forest.plant
import java.util.Date

class SyncService : Service() {
    private lateinit var syncServiceList : Array<ServiceInterface>
    private lateinit var prefs: SharedPreferences
    private val ID_SERVICE = 5

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        showNotification() // Start foreground service immediately

        prefs = getSharedPreferences("openScaleSyncSettings", Context.MODE_PRIVATE)

        syncServiceList = arrayOf(
            HealthConnectService(applicationContext, prefs),
            MQTTService(applicationContext, prefs),
            WgerService(applicationContext, prefs)
        )

        CoroutineScope(Dispatchers.Main).launch {
            for (syncService in syncServiceList) {
                if (syncService.viewModel().syncEnabled.value) {
                    syncService.init()
                }
            }
        }
        onHandleIntent(intent)

        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()

        if (Timber.forest().isEmpty()) {
            plant(Timber.DebugTree())
        }
    }

    protected fun onHandleIntent(intent: Intent) {
        Timber.d("openScale sync service handle intent started")

        var mode: String? = "none"
        var openScaleUserId = 0

        try {
            mode = intent.extras!!.getString("mode")
            openScaleUserId = prefs.getInt("selectedOpenScaleUserId", -1)
        } catch (ex: NullPointerException) {
            Timber.e(ex.message)
        }

        for (syncService in syncServiceList) {
            if (!syncService.viewModel().syncEnabled.value) {
                Timber.d(syncService.viewModel().getName() + " [disabled]")
                continue
            }

            Timber.d(syncService.viewModel().getName()  + " [enabled]")

            if (mode == "insert") {
                val userId = intent.getIntExtra("userId", 0)
                val weight = intent.getFloatExtra("weight", 0.0f)
                val date = Date(intent.getLongExtra("date", 0L))

                Timber.d("SyncService insert command received for user Id: $userId weight: $weight date: $date")

                if (userId == openScaleUserId) {
                    CoroutineScope(Dispatchers.Main).launch {
                        syncService.insert(OpenScaleMeasurement(0, date, weight, 0f, 0f, 0f))
                    }
                } else {
                    Timber.d("openScale sync userId and openScale userId mismatched")
                }
            } else if (mode == "update") {
                val userId = intent.getIntExtra("userId", 0)
                val weight = intent.getFloatExtra("weight", 0.0f)
                val date = Date(intent.getLongExtra("date", 0L))

                Timber.d("SyncService update command received for userId: $userId weight: $weight date: $date")

                if (userId == openScaleUserId) {
                    CoroutineScope(Dispatchers.Main).launch {
                        syncService.update(OpenScaleMeasurement(0, date, weight, 0f, 0f, 0f))
                    }
                } else {
                    Timber.d("openScale sync userId and openScale userId mismatched")
                }
            } else if (mode == "delete") {
                val date = Date(intent.getLongExtra("date", 0L))

                Timber.d("SyncService delete command received for date: $date")

                CoroutineScope(Dispatchers.Main).launch {
                    syncService.delete(date)
                }
            } else if (mode == "clear") {
                Timber.d("SyncService clear command received")

                CoroutineScope(Dispatchers.Main).launch {
                    syncService.clear()
                }
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = createNotificationChannel(notificationManager)
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
        val notification = notificationBuilder
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_launcher_openscale_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun createNotificationChannel(notificationManager: NotificationManager): String {
        val channelId = resources.getString(R.string.app_name)

        val channel = NotificationChannel(
            channelId,
            resources.getString(R.string.app_name) + " service",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.importance = NotificationManager.IMPORTANCE_DEFAULT
        channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        notificationManager.createNotificationChannel(channel)

        return channelId
    }

} /*
    Intent insertIntent = new Intent();
    insertIntent.setComponent(new ComponentName("com.health.openscale.sync", "com.health.openscale.sync.core.service.SyncService"));
            insertIntent.putExtra("mode", "insert");
            insertIntent.putExtra("userId", scaleMeasurement.getUserId());
            insertIntent.putExtra("weight", scaleMeasurement.getWeight());
            insertIntent.putExtra("date", scaleMeasurement.getDateTime().getTime());
            ContextCompat.startForegroundService(context, insertIntent);
*/

