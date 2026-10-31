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
package com.health.openscale.sync.core.work

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.health.openscale.sync.core.model.OpenScaleViewModel
import com.health.openscale.sync.core.provider.OpenScaleDataProvider
import com.health.openscale.sync.core.service.BackendRegistry
import com.health.openscale.sync.core.service.ServiceInterface
import com.health.openscale.sync.core.service.SyncResult
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Optional periodic background sync (Phase 4 / reliability). When the user enables it, this runs
 * every few hours WITHOUT the app being opened:
 *  - **inbound** first, for all backends: pull from each import-enabled backend into openScale
 *    (openScale stays master), so what came from a backend is known as the source's before
 *    anything goes out, and every backend gets every import of the run,
 *  - **outbound**: reconcile each export-enabled backend against the export ledger (heals missed
 *    pushes incl. deletes).
 *
 * This is the safety net for the OEM-killer case and the automatic counterpart to the manual
 * "Sync now" button. Off by default; the instant push remains the primary outbound path.
 */
class PeriodicSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(OpenScaleViewModel.SETTINGS_FILE, Context.MODE_PRIVATE)
        val dataProvider = OpenScaleDataProvider(applicationContext, prefs)

        // Version gate: don't act against an incompatible openScale (the UI warns separately).
        val apiVersion = runCatching { dataProvider.getApiVersion() }.getOrNull()
        if (apiVersion != null && apiVersion < OpenScaleDataProvider.MIN_API_VERSION) {
            prefs.edit { putBoolean(OpenScaleViewModel.OPENSCALE_VERSION_UNSUPPORTED, true) }
            return Result.success()
        }

        val services = BackendRegistry.create(applicationContext, prefs)
        // Never emptyList(): reconcile() deletes what is missing from the list it is given, so a
        // failed read would wipe the destination's history instead of skipping one cycle.
        val allUsers = runCatching { dataProvider.getUsers() }.getOrElse { return Result.retry() }
        var allMeasurements = runCatching { allUsers.flatMap { dataProvider.getMeasurements(it) } }
            .getOrElse { return Result.retry() }

        var anyFailure = false
        try {
            val enabled = services.filter { it.viewModel().syncEnabled.value }
            for (service in enabled) {
                service.openScaleDataService = dataProvider
                runCatching { service.init() }.onFailure { Timber.e(it, "%s.init() failed in periodic sync", service.viewModel().getName()) }
            }

            // Phase 1, inbound for every backend before any export: each backend then gets what any
            // other one imported in this same run, whatever their order, and whatever came from a
            // backend is known as the source's before anything is exported to it.
            val importFailed = HashSet<ServiceInterface>()
            var imported = 0
            for (service in enabled.filter { it.importEnabled() }) {
                val vm = service.viewModel()
                when (val r = runCatching { service.runInbound(vm.selectedUserId.value) }.getOrNull()) {
                    is SyncResult.Failure, null -> { anyFailure = true; importFailed += service }
                    is SyncResult.Success -> if (r.data.total > 0) {
                        imported += r.data.total
                        Timber.d(
                            "%s: imported %d new and enriched %d existing measurement(s)",
                            vm.getName(), r.data.imported, r.data.updated
                        )
                    }
                }
            }
            // A failed re-read keeps the older list: it only lacks the new measurements, none of
            // which a ledger knows yet, so reconcile cannot take them as deleted.
            if (imported > 0) {
                allMeasurements = runCatching { allUsers.flatMap { dataProvider.getMeasurements(it) } }
                    .getOrDefault(allMeasurements)
            }

            // Phase 2, outbound. A backend whose import failed waits for the next run.
            for (service in enabled.filter { it.exportEnabled() && it !in importFailed }) {
                val vm = service.viewModel()
                val measurements = if (service.isMultiUser) allMeasurements
                    else allMeasurements.filter { it.userId == vm.selectedUserId.value }
                val r = runCatching { service.reconcile(measurements) }.getOrNull()
                if (r is SyncResult.Failure) anyFailure = true
            }
        } finally {
            services.forEach { runCatching { it.close() } }
        }

        return if (anyFailure) Result.retry() else Result.success()
    }

    companion object {
        const val WORK_NAME = "openScaleSyncPeriodic"
        const val PREF_KEY = "periodicSyncEnabled"

        /** Enables/disables the periodic background sync (unique periodic work). */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
