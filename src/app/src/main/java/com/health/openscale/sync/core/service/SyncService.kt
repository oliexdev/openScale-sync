package com.health.openscale.sync.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.text.format.DateFormat
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.health.openscale.sync.R
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurementValue
import com.health.openscale.sync.core.model.OpenScaleViewModel
import com.health.openscale.sync.core.provider.OpenScaleDataProvider
import com.health.openscale.sync.core.utils.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import timber.log.Timber.Forest.plant
import java.time.Instant
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.content.edit

class SyncService : Service() {
    private lateinit var syncServiceList: List<ServiceInterface>
    private lateinit var prefs: SharedPreferences
    private val ID_SERVICE = 5
    private val ID_RETRY = 6

    companion object {
        /** Test seam: when set, the service uses these backends instead of [BackendRegistry].
         *  Production code never sets it. Lets an instrumentation test drive the real foreground
         *  service with a fake backend. */
        @VisibleForTesting
        var backendFactory: ((Context, SharedPreferences) -> List<ServiceInterface>)? = null

        /** Test seam: when set, the service reads from this provider instead of the real openScale
         *  ContentProvider. Lets an instrumentation test exercise the "changed"/reconcile path and
         *  the version gate deterministically, without a real openScale install. */
        @VisibleForTesting
        var dataProviderFactory: ((Context, SharedPreferences) -> OpenScaleDataProvider)? = null
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        prefs = getSharedPreferences(OpenScaleViewModel.SETTINGS_FILE, MODE_PRIVATE)

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

        // Prepare all sync backends (single source of truth in BackendRegistry; a test may inject fakes)
        syncServiceList = (backendFactory ?: { c, p -> BackendRegistry.create(c, p) })(applicationContext, prefs)

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

            delay(500.milliseconds) // small delay to give init a chance to complete
            onHandleIntent(intent)

            Timber.d("onStartCommand done in %d ms", (System.nanoTime() - t0) / 1_000_000)
        }

        return START_STICKY
    }

    private suspend fun onHandleIntent(intent: Intent?) {
        Timber.d("onHandleIntent extras: %s", intent.safeExtras())

        // Version gate: this sync app requires a minimum openScale ContentProvider API version
        // (hard gate, no legacy fallbacks). If the installed openScale is too old, flag it for the
        // UI ("please update openScale") and drop the event instead of mis-handling old-format data.
        val dataProvider = (dataProviderFactory ?: { c, p -> OpenScaleDataProvider(c, p) })(applicationContext, prefs)
        val apiVersion = runCatching { dataProvider.getApiVersion() }.getOrNull()
        if (apiVersion != null && apiVersion < OpenScaleDataProvider.MIN_API_VERSION) {
            prefs.edit { putBoolean(OpenScaleViewModel.OPENSCALE_VERSION_UNSUPPORTED, true) }
            Timber.w("openScale apiVersion=%d < required %d -> blocking sync (user must update openScale)",
                apiVersion, OpenScaleDataProvider.MIN_API_VERSION)
            stopServiceCleanly()
            return
        }
        prefs.edit { putBoolean(OpenScaleViewModel.OPENSCALE_VERSION_UNSUPPORTED, false) }

        // Resolve userId -> username once for this op (used for multi-user labelling on the wire).
        val userMap: Map<Int, String> = runCatching {
            dataProvider.getUsers().associate { it.id to it.username }
        }.getOrElse { emptyMap() }

        val mode = intent?.extras?.getString("mode") ?: "none"
        if (mode !in setOf("insert", "update", "delete", "clear", "changed")) {
            Timber.w("Unknown mode='%s' -> ignoring", mode)
            stopServiceCleanly()
            return
        }

        coroutineScope {
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
                        // The generic "values" payload is the single source of truth; weight/fat/
                        // water/muscle are derived from it (see OpenScaleMeasurement.fromValues).
                        val id     = intent?.getIntExtra("id", 0) ?: 0
                        val userId = intent?.getIntExtra("userId", 0) ?: 0
                        val date   = Date(intent?.getLongExtra("date", 0L) ?: 0L)

                        Timber.d("SyncService %s id=%d userId=%d date=%s", mode, id, userId, date)

                        if (syncService.shouldSync(userId)) {
                            val values = OpenScaleMeasurementValue.parseList(intent?.getStringExtra("values"))
                            launch {
                                val m = OpenScaleMeasurement.fromValues(id, userId, date, userMap[userId] ?: "", values)
                                val t = System.nanoTime()
                                val res = runCatching {
                                    syncService.submit(syncService.pendingOp(mode, m))
                                }.onFailure { e -> Timber.e(e, "%s.%s() threw", name, mode) }
                                    .getOrNull()

                                val ms = (System.nanoTime() - t) / 1_000_000
                                when (res) {
                                    is SyncResult.Success -> {
                                        vm.setLastSync(Instant.now())
                                        val fmt = DateFormat.getDateFormat(applicationContext).format(date)
                                        val msg = if (mode == "insert")
                                            getString(R.string.sync_service_measurement_inserted_info, m.weight, fmt)
                                        else
                                            getString(R.string.sync_service_measurement_updated_info, m.weight, fmt)
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
                        } else {
                            Timber.w("%s: userId=%d not synced (single-user backend, different selected user) -> skipping", name, userId)
                        }
                    }

                    "delete" -> {
                        val date = Date(intent?.getLongExtra("date", 0L) ?: 0L)
                        val userId = intent?.getIntExtra("userId", -1) ?: -1
                        // id identifies the exact ledger entry to forget (always present — the
                        // apiVersion gate blocks any openScale too old to send it).
                        val id = intent?.getIntExtra("id", 0) ?: 0
                        Timber.d("SyncService delete for id=%d userId=%d date=%s", id, userId, date)

                        if (!syncService.shouldSync(userId)) {
                            Timber.w("%s: delete userId=%d not synced -> skipping", name, userId)
                            continue
                        }

                        launch {
                            val res = runCatching { syncService.submit(PendingOp("delete", id = id, userId = userId, dateMs = date.time)) }
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
                        val userId = intent?.getIntExtra("userId", -1) ?: -1
                        Timber.d("SyncService clear command received for userId=%d", userId)

                        if (!syncService.shouldSync(userId)) {
                            Timber.w("%s: clear userId=%d not synced -> skipping", name, userId)
                            continue
                        }

                        launch {
                            val res = runCatching { syncService.submit(PendingOp("clear", userId = userId)) }
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

                    // Coalesced bulk signal (e.g. CSV import / backup restore in openScale):
                    // one data-less wake-up → full reconcile against the ledger (heals adds/edits/deletes).
                    "changed" -> {
                        if (!syncService.exportEnabled()) {
                            Timber.d("%s: import-only -> skipping reconcile", name)
                            continue
                        }
                        launch {
                            val allMeasurements = dataProvider.getUsers().flatMap { dataProvider.getMeasurements(it) }
                            val measurements = if (syncService.isMultiUser) allMeasurements
                                else allMeasurements.filter { it.userId == vm.selectedUserId.value }
                            val res = runCatching { syncService.reconcile(measurements) }
                                .onFailure { e -> Timber.e(e, "%s.reconcile() threw", name) }
                                .getOrNull()
                            when (res) {
                                is SyncResult.Success -> {
                                    vm.setLastSync(Instant.now())
                                    Timber.d("%s.reconcile() success (%d measurements)", name, measurements.size)
                                }
                                is SyncResult.Failure -> {
                                    syncService.setErrorMessage(res)
                                    Timber.e("(%s.reconcile) %s", name, res.message)
                                }
                                null -> Timber.w("%s.reconcile() returned null", name)
                            }
                        }
                    }
                }
            }
        }

        // Headless feedback: surface unsynced (queued) measurements as a notification,
        // auto-cleared once every backend's retry queue is empty again.
        updateRetryNotification(syncServiceList.sumOf { it.pendingRetryCount() })

        stopServiceCleanly()
    }

    /** Posts (or clears) a notification reflecting how many ops are waiting in the retry queues. */
    private fun updateRetryNotification(pending: Int) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (pending <= 0) {
            notificationManager.cancel(ID_RETRY)
            return
        }
        val channelId = createNotificationChannel(notificationManager)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_openscale_sync)
            .setContentTitle(getString(R.string.sync_retry_notification_title))
            .setContentText(resources.getQuantityString(R.plurals.sync_retry_notification_text, pending, pending))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        notificationManager.notify(ID_RETRY, notification)
    }

    private fun stopServiceCleanly() {
        Timber.d("Stopping foreground + self")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
            @Suppress("DEPRECATION")
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
