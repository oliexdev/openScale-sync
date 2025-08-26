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
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import com.health.openscale.sync.R
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.utils.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import timber.log.Timber.Forest.plant
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.Date

class SyncService : Service() {
    private lateinit var syncServiceList: Array<ServiceInterface>
    private lateinit var prefs: SharedPreferences
    private val ID_SERVICE = 5

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        prefs = getSharedPreferences("openScaleSyncSettings", Context.MODE_PRIVATE)

        // Ensure at least a debug tree is available
        if (Timber.forest().isEmpty()) {
            plant(Timber.DebugTree())
        }

        // Initialize file logging if enabled (does not clear logs)
        LogManager.init(applicationContext, prefs)

        Timber.d("SyncService created (thread=%s)", Thread.currentThread().name)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val t0 = System.nanoTime()
        Timber.d(
            "onStartCommand(startId=%d, flags=0x%X, thread=%s)",
            startId, flags, Thread.currentThread().name
        )

        showNotification() // Required foreground service notification

        // Prepare all sync backends
        syncServiceList = arrayOf(
            HealthConnectService(applicationContext, prefs),
            MQTTService(applicationContext, prefs),
            WgerService(applicationContext, prefs)
        )

        CoroutineScope(Dispatchers.Main).launch {
            // Initialize only enabled services
            for (syncService in syncServiceList) {
                val name = syncService.viewModel().getName()
                if (syncService.viewModel().syncEnabled.value) {
                    val t = System.nanoTime()
                    runCatching { syncService.init() }
                        .onSuccess {
                            Timber.d("%s.init() ok in %d ms", name, (System.nanoTime() - t) / 1_000_000)
                        }
                        .onFailure { e -> Timber.e(e, "%s.init() failed", name) }
                } else {
                    Timber.d("%s [disabled]", name)
                }
            }

            delay(500) // small delay to give init a chance to complete
            onHandleIntent(intent)

            Timber.d("onStartCommand done in %d ms", (System.nanoTime() - t0) / 1_000_000)
        }

        return START_STICKY
    }

    protected fun onHandleIntent(intent: Intent?) {
        Timber.d("onHandleIntent extras: %s", intent.safeExtras())

        val openScaleUserId = prefs.getInt("selectedOpenScaleUserId", -1)
        Timber.d("selectedOpenScaleUserId=%d", openScaleUserId)

        val mode = intent?.extras?.getString("mode") ?: "none"
        if (mode !in setOf("insert", "update", "delete", "clear")) {
            Timber.w("Unknown mode='%s' -> ignoring", mode)
            stopServiceCleanly()
            return
        }

        for (syncService in syncServiceList) {
            val vm = syncService.viewModel()
            val name = vm.getName()

            if (!vm.syncEnabled.value) {
                Timber.d("%s [disabled]", name)
                continue
            }

            Timber.d("%s [enabled]", name)

            when (mode) {
                "insert", "update" -> {
                    // Read all extras safely, no NPEs
                    val userId = intent?.getIntExtra("userId", 0) ?: 0
                    val weight = roundFloat(intent?.getFloatExtra("weight", 0.0f) ?: 0f)
                    val fat    = roundFloat(intent?.getFloatExtra("fat", 0.0f) ?: 0f)
                    val water  = roundFloat(intent?.getFloatExtra("water", 0.0f) ?: 0f)
                    val muscle = roundFloat(intent?.getFloatExtra("muscle", 0.0f) ?: 0f)
                    val date   = Date(intent?.getLongExtra("date", 0L) ?: 0L)

                    Timber.d(
                        "SyncService %s userId=%d date=%s w=%.2f f=%.2f wa=%.2f m=%.2f",
                        mode, userId, date, weight, fat, water, muscle
                    )

                    CoroutineScope(Dispatchers.Main).launch {
                        val m = OpenScaleMeasurement(0, date, weight, fat, water, muscle)
                        val t = System.nanoTime()
                        val res = runCatching {
                            if (mode == "insert") syncService.insert(m) else syncService.update(m)
                        }.onFailure { e -> Timber.e(e, "%s.%s() threw", name, mode) }
                            .getOrNull()

                        val ms = (System.nanoTime() - t) / 1_000_000
                        when (res) {
                            is SyncResult.Success -> {
                                vm.setLastSync(Instant.now())
                                val fmt = DateFormat.getDateFormat(applicationContext).format(date)
                                val msg = if (mode == "insert")
                                    getString(R.string.sync_service_measurement_inserted_info, weight, fmt)
                                else
                                    getString(R.string.sync_service_measurement_updated_info, weight, fmt)
                                syncService.setInfoMessage(msg)
                                Timber.d("%s.%s() success in %d ms", name, mode, ms)
                            }
                            is SyncResult.Failure -> {
                                syncService.setErrorMessage(res)
                                Timber.e("(%s.%s) %s in %d ms", name, mode, res.message, ms)
                            }
                            null -> {
                                Timber.w("%s.%s() returned null in %d ms", name, mode, ms)
                            }
                        }
                    }
                }

                "delete" -> {
                    val date = Date(intent?.getLongExtra("date", 0L) ?: 0L)
                    Timber.d("SyncService delete for date=%s", date)

                    CoroutineScope(Dispatchers.Main).launch {
                        val res = runCatching { syncService.delete(date) }
                            .onFailure { e -> Timber.e(e, "%s.delete() threw", name) }
                            .getOrNull()
                        when (res) {
                            is SyncResult.Success -> {
                                vm.setLastSync(Instant.now())
                                val fmt = DateFormat.getDateFormat(applicationContext).format(date)
                                syncService.setInfoMessage(getString(R.string.sync_service_measurement_deleted_info, fmt))
                                Timber.d("%s.delete() success", name)
                            }
                            is SyncResult.Failure -> {
                                syncService.setErrorMessage(res)
                                Timber.e("(%s.delete) %s", name, res.message)
                            }
                            null -> {
                                Timber.w("%s.delete() returned null", name)
                            }
                        }
                    }
                }

                "clear" -> {
                    Timber.d("SyncService clear command received")

                    CoroutineScope(Dispatchers.Main).launch {
                        val res = runCatching { syncService.clear() }
                            .onFailure { e -> Timber.e(e, "%s.clear() threw", name) }
                            .getOrNull()
                        when (res) {
                            is SyncResult.Success -> {
                                vm.setLastSync(Instant.now())
                                syncService.setInfoMessage(getString(R.string.sync_service_measurement_cleared_info))
                                Timber.d("%s.clear() success", name)
                            }
                            is SyncResult.Failure -> {
                                syncService.setErrorMessage(res)
                                Timber.e("(%s.clear) %s", name, res.message)
                            }
                            null -> {
                                Timber.w("%s.clear() returned null", name)
                            }
                        }
                    }
                }
            }
        }

        stopServiceCleanly()
    }

    private fun stopServiceCleanly() {
        Timber.d("Stopping foreground + self")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun roundFloat(number: Float): Float {
        val n = if (number.isFinite()) number else 0f
        val big = BigDecimal(n.toDouble()).setScale(2, RoundingMode.HALF_UP)
        return big.toFloat()
    }

    /** Creates required foreground notification for service */
    private fun showNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channelId = createNotificationChannel(notificationManager)
        val notification = NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_launcher_openscale_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Registers a notification channel if not yet existing */
    private fun createNotificationChannel(notificationManager: NotificationManager): String {
        val channelId = "openScale sync"
        val channel = NotificationChannel(
            channelId,
            "openScale sync service",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            importance = NotificationManager.IMPORTANCE_DEFAULT
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        notificationManager.createNotificationChannel(channel)
        return channelId
    }

    // ------- Intent debugging (extras only, safe) -------

    private val REDACT_KEYS = setOf(
        "token","auth","password","secret","apikey","api_key","authorization","bearer"
    )

    /**
     * Safe summary of intent extras.
     * - Redacts sensitive keys
     * - Truncates long strings
     * - Limits number of items
     */
    private fun Intent?.safeExtras(): String {
        if (this == null) return "extras=null"
        val b = extras ?: return "extras=null"
        if (b.isEmpty) return "extras={}"
        val keys = runCatching { b.keySet().sorted() }.getOrElse { emptyList() }
        val parts = mutableListOf<String>()

        fun trunc(v: Any?): String {
            val s = v?.toString() ?: "null"
            return if (s.length > 256) s.take(256) + "…(${s.length})" else s
        }

        for (k in keys) {
            val raw = runCatching { b.get(k) }.getOrNull()
            val entry = when {
                REDACT_KEYS.any { k.contains(it, ignoreCase = true) } -> "$k=«redacted»"
                raw is ByteArray      -> "$k=byte[${raw.size}]"
                raw is IntArray       -> "$k=int[${raw.size}]"
                raw is LongArray      -> "$k=long[${raw.size}]"
                raw is FloatArray     -> "$k=float[${raw.size}]"
                raw is DoubleArray    -> "$k=double[${raw.size}]"
                raw is BooleanArray   -> "$k=bool[${raw.size}]"
                raw is Array<*>       -> "$k=array[${raw.size}]"
                else                  -> "$k=${trunc(raw)}"
            }
            parts += entry
            if (parts.size >= 20) { parts += "…+${keys.size - 20} more"; break }
        }

        return "extras={${parts.joinToString(", ")}}"
    }
}
