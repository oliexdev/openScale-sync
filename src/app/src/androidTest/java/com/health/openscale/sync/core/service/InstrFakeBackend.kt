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
import com.health.openscale.sync.core.datatypes.OpenScaleUser
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.provider.OpenScaleDataProvider
import java.util.Collections
import java.util.Date

private class InstrFakeViewModel(prefs: SharedPreferences, private val name: String) : ViewModelInterface(prefs) {
    override fun getName(): String = name
    override fun getIcon(): Int = 0
}

/**
 * Backend for the SyncService instrumentation tests: multi-user (accepts any user), records every
 * wire op thread-safely so the test can poll [wire] after the real foreground service dispatches.
 */
class InstrFakeBackend(context: Context, prefs: SharedPreferences) : ServiceInterface(context) {
    private val vm = InstrFakeViewModel(prefs, NAME)
    override fun viewModel(): ViewModelInterface = vm
    override val isMultiUser: Boolean get() = true

    val wire: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun record(op: String): SyncResult<Unit> { wire += op; return SyncResult.Success(Unit) }

    override suspend fun connect() {}
    override suspend fun insert(measurement: OpenScaleMeasurement) = record("insert#${measurement.id}@${measurement.date.time}")
    override suspend fun update(measurement: OpenScaleMeasurement) = record("update#${measurement.id}@${measurement.date.time}")
    override suspend fun delete(userId: Int, date: Date) = record("delete@${date.time}/u$userId")
    override suspend fun clear(userId: Int) = record("clear:$userId")

    @Composable
    override fun ComposeSettings(activity: ComponentActivity) { /* no UI in tests */ }

    companion object {
        const val NAME = "InstrFake"
    }
}

/** A fully scriptable [OpenScaleDataProvider] for the instrumentation tests (no real openScale needed). */
class InstrFakeProvider(
    ctx: Context,
    prefs: SharedPreferences,
    var version: Int? = MIN_API_VERSION,
    var userList: List<OpenScaleUser> = listOf(OpenScaleUser(1, "Alice")),
    var measurementsByUser: Map<Int, List<OpenScaleMeasurement>> = emptyMap(),
) : OpenScaleDataProvider(ctx, prefs) {
    override fun getApiVersion(): Int? = version
    override fun getUsers(): List<OpenScaleUser> = userList
    override fun getMeasurements(openScaleUser: OpenScaleUser): List<OpenScaleMeasurement> =
        measurementsByUser[openScaleUser.id] ?: emptyList()
}
