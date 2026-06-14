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

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.model.ViewModelInterface
import java.util.Date

/** Minimal ViewModel for tests — only name/icon are abstract; the rest comes from the base. */
class FakeViewModel(prefs: SharedPreferences, private val name: String) : ViewModelInterface(prefs) {
    override fun getName(): String = name
    override fun getIcon(): Int = 0
}

/**
 * A programmable [ServiceInterface] used to observe exactly what reaches "the wire" and to simulate
 * backend behaviour (success / scripted failures / batching / partial bulk). It records every raw op
 * in [wire] so tests can assert the full flow (submit / reconcile / retry) without a real backend.
 */
class FakeBackend(
    context: Context,
    prefs: SharedPreferences,
    name: String = "Fake",
    private val multiUser: Boolean = false,
    private val inbound: Boolean = false,
) : ServiceInterface(context) {

    private val vm = FakeViewModel(prefs, name)
    override fun viewModel(): ViewModelInterface = vm
    override val isMultiUser: Boolean get() = multiUser
    override val supportsInbound: Boolean get() = inbound

    /** Every raw wire op in order, e.g. "insert#7@1000", "delete@1000", "clear:1", "connect", "insertAll(3)". */
    val wire = mutableListOf<String>()

    /** Scripted results for the single-item ops: each op pops the head; empty ⇒ Success. */
    val scripted = ArrayDeque<SyncResult<Unit>>()

    /** When true, every single-item op fails (overrides [scripted]) — handy for retry/cap tests. */
    var failAll = false

    /** When true, insertAll/updateAll become one batch call instead of the default per-item loop. */
    var batch = false
    /** In batch mode: which measurements "succeeded" (default: all). Lets tests model partial bulk. */
    var batchSucceed: (List<OpenScaleMeasurement>) -> List<OpenScaleMeasurement> = { it }

    /** Each onReconciled call recorded as (changedUserIds, currentMeasurementIds), in order. */
    val reconciledCalls = mutableListOf<Pair<Set<Int>, List<Int>>>()
    /** When true, onReconciled throws — to assert a hook failure doesn't break reconcile. */
    var failOnReconciled = false

    override suspend fun onReconciled(current: List<OpenScaleMeasurement>, changedUserIds: Set<Int>) {
        if (failOnReconciled) throw RuntimeException("onReconciled boom")
        reconciledCalls += changedUserIds to current.map { it.id }
    }

    private fun next(): SyncResult<Unit> = when {
        failAll -> SyncResult.Failure(SyncResult.ErrorType.API_ERROR, "failAll")
        scripted.isEmpty() -> SyncResult.Success(Unit)
        else -> scripted.removeFirst()
    }

    override suspend fun connect() { wire += "connect" }

    override suspend fun insert(measurement: OpenScaleMeasurement): SyncResult<Unit> {
        wire += "insert#${measurement.id}@${measurement.date.time}"; return next()
    }

    override suspend fun update(measurement: OpenScaleMeasurement): SyncResult<Unit> {
        wire += "update#${measurement.id}@${measurement.date.time}"; return next()
    }

    override suspend fun delete(userId: Int, date: Date): SyncResult<Unit> {
        wire += "delete@${date.time}/u$userId"; return next()
    }

    override suspend fun clear(userId: Int): SyncResult<Unit> {
        wire += "clear:$userId"; return next()
    }

    @Composable
    override fun ComposeSettings(activity: ComponentActivity) { /* no UI in tests */ }

    override suspend fun insertAll(measurements: List<OpenScaleMeasurement>): BulkResult =
        if (batch) bulk("insertAll", measurements) else super.insertAll(measurements)

    override suspend fun updateAll(measurements: List<OpenScaleMeasurement>): BulkResult =
        if (batch) bulk("updateAll", measurements) else super.updateAll(measurements)

    private fun bulk(label: String, ms: List<OpenScaleMeasurement>): BulkResult {
        wire += "$label(${ms.size})"
        val ok = batchSucceed(ms)
        val failure = if (ok.size == ms.size) null
            else SyncResult.Failure(SyncResult.ErrorType.API_ERROR, "scripted bulk failure")
        return BulkResult(ok, failure)
    }
}
