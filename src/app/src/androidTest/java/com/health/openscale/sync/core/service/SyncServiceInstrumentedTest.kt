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

import android.app.Instrumentation
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurementValue
import com.health.openscale.sync.core.datatypes.OpenScaleUser
import com.health.openscale.sync.core.model.OpenScaleViewModel
import com.health.openscale.sync.core.provider.OpenScaleDataProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.Date
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives the REAL foreground [SyncService] on a device/emulator across every intent mode and several
 * situations (version gate, disabled backend, unknown mode), with the backend + the openScale data
 * provider injected via the [SyncService] test seams. Each case launches the service exactly like
 * openScale does (`am start-foreground-service` + a sync Intent) and asserts what reached the backend.
 */
@RunWith(AndroidJUnit4::class)
class SyncServiceInstrumentedTest {

    private val instr: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = instr.targetContext

    private val captured = AtomicReference<InstrFakeBackend?>(null)
    private lateinit var provider: InstrFakeProvider

    @Before
    fun setUp() {
        // Fresh settings / ledger / queue so each case starts clean.
        prefs(OpenScaleViewModel.SETTINGS_FILE).edit().clear().commit()
        prefs("export_ledger_${InstrFakeBackend.NAME}").edit().clear().commit()
        prefs("retry_queue_${InstrFakeBackend.NAME}").edit().clear().commit()
        captured.set(null)

        provider = InstrFakeProvider(ctx, prefs(OpenScaleViewModel.SETTINGS_FILE))
        // Default: one enabled fake backend + the scriptable provider.
        injectBackend(enabled = true)
        SyncService.dataProviderFactory = { _, _ -> provider }
    }

    @After
    fun tearDown() {
        SyncService.backendFactory = null
        SyncService.dataProviderFactory = null
    }

    // --- the five intent modes ------------------------------------------------------------

    @Test
    fun insert_isDispatched() {
        start("mode" to "insert", "id" to 7, "userId" to 1, "date" to 1000L)
        awaitWire { it.contains("insert#7@1000") }
    }

    @Test
    fun update_isDispatched() {
        start("mode" to "update", "id" to 7, "userId" to 1, "date" to 1000L)
        awaitWire { it.contains("update#7@1000") }
    }

    @Test
    fun delete_isDispatched() {
        start("mode" to "delete", "id" to 7, "userId" to 1, "date" to 1000L)
        awaitWire { it.contains("delete@1000/u1") }
    }

    @Test
    fun clear_isDispatched() {
        start("mode" to "clear", "userId" to 1)
        awaitWire { it.contains("clear:1") }
    }

    @Test
    fun changed_reconcilesAllProviderMeasurements() {
        provider.userList = listOf(OpenScaleUser(1, "Alice"))
        provider.measurementsByUser = mapOf(1 to listOf(m(1, 1000), m(2, 2000)))
        start("mode" to "changed")
        awaitWire { it.containsAll(listOf("insert#1@1000", "insert#2@2000")) }
    }

    // --- situations that must NOT dispatch -------------------------------------------------

    @Test
    fun versionTooOld_blocksAndFlagsPref() {
        provider.version = OpenScaleDataProvider.MIN_API_VERSION - 1
        start("mode" to "insert", "id" to 7, "userId" to 1, "date" to 1000L)
        assertNoDispatch()
        assertTrue(prefs(OpenScaleViewModel.SETTINGS_FILE)
            .getBoolean(OpenScaleViewModel.OPENSCALE_VERSION_UNSUPPORTED, false))
    }

    @Test
    fun disabledBackend_isSkipped() {
        injectBackend(enabled = false)   // re-inject a disabled backend
        start("mode" to "insert", "id" to 7, "userId" to 1, "date" to 1000L)
        assertNoDispatch()
    }

    @Test
    fun unknownMode_isIgnored() {
        start("mode" to "frobnicate")
        assertNoDispatch()
    }

    // --- malformed / extreme intents (must not crash the service) -------------------------

    @Test
    fun insert_withGarbageValues_doesNotCrash() {
        start("mode" to "insert", "id" to 7, "userId" to 1, "date" to 1000L, "values" to "not-json")
        awaitWire { it.contains("insert#7@1000") }   // values ignored, weight derived to 0
    }

    @Test
    fun insert_withMissingExtras_usesDefaults() {
        start("mode" to "insert")                     // no id/userId/date → 0/0/0
        awaitWire { it.contains("insert#0@0") }
    }

    @Test
    fun changed_multiUser_reconcilesEveryUser() {
        // Same timestamp across DIFFERENT users is legal (userId disambiguates) — both must sync.
        provider.userList = listOf(OpenScaleUser(1, "Alice"), OpenScaleUser(2, "Bob"))
        provider.measurementsByUser = mapOf(1 to listOf(m(1, 1000, user = 1)), 2 to listOf(m(2, 1000, user = 2)))
        start("mode" to "changed")
        awaitWire { it.containsAll(listOf("insert#1@1000", "insert#2@1000")) }
    }

    // --- helpers --------------------------------------------------------------------------

    private fun prefs(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun injectBackend(enabled: Boolean) {
        SyncService.backendFactory = { c, p ->
            listOf(InstrFakeBackend(c, p).also {
                it.viewModel().setSyncEnabled(enabled)
                captured.set(it)
            })
        }
    }

    private fun m(id: Int, timeMs: Long, weight: Float = 80f, user: Int = 1) =
        OpenScaleMeasurement.fromValues(
            id, user, Date(timeMs), "",
            listOf(OpenScaleMeasurementValue(0, "WEIGHT", "Weight", "kg", false, weight))
        )

    private fun start(vararg extras: Pair<String, Any>) {
        val sb = StringBuilder(
            "am start-foreground-service -n ${ctx.packageName}/com.health.openscale.sync.core.service.SyncService"
        )
        for ((k, v) in extras) when (v) {
            is Int -> sb.append(" --ei $k $v")
            is Long -> sb.append(" --el $k $v")
            else -> sb.append(" --es $k $v")
        }
        val out = runShell(sb.toString())
        assertTrue("am failed to start the service: $out", out.contains("Starting service"))
    }

    private fun awaitWire(timeoutMs: Long = 15_000, predicate: (List<String>) -> Boolean): InstrFakeBackend {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val b = captured.get()
            if (b != null && predicate(snapshot(b))) return b
            Thread.sleep(50)
        }
        val b = captured.get()
        throw AssertionError("foreground service never produced the expected ops; wire=${b?.let { snapshot(it) }}")
    }

    /** Wait a settle window and assert the backend received NO op (blocked / disabled / unknown). */
    private fun assertNoDispatch(settleMs: Long = 4_000) {
        Thread.sleep(settleMs)
        captured.get()?.let { assertTrue("expected no dispatch but got ${snapshot(it)}", snapshot(it).isEmpty()) }
    }

    private fun snapshot(b: InstrFakeBackend): List<String> = synchronized(b.wire) { b.wire.toList() }

    private fun runShell(cmd: String): String {
        val pfd = instr.uiAutomation.executeShellCommand(cmd)
        return FileInputStream(pfd.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
