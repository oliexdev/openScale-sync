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

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurementValue
import com.health.openscale.sync.core.model.SyncDirection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date

/**
 * Exercises the reliability core of [ServiceInterface] (real-time dispatch incl. moves, the export
 * ledger, the retry queue, reconcile diffing, and the bulk operators) through a programmable
 * [FakeBackend]. Robolectric supplies a real Context/SharedPreferences for the ledger + queue;
 * SDK 34 sidesteps Robolectric's lag behind compileSdk 36.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServiceInterfaceTest {

    private lateinit var ctx: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var backend: FakeBackend

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        prefs = ctx.getSharedPreferences("test_settings", Context.MODE_PRIVATE)
        // Start every test with an empty ledger + retry queue for the "Fake" backend.
        ctx.getSharedPreferences("export_ledger_Fake", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("retry_queue_Fake", Context.MODE_PRIVATE).edit().clear().commit()
        backend = FakeBackend(ctx, prefs)
    }

    // Build like the real pipeline: the generic value set is the source of truth (weight is derived),
    // so it round-trips through PendingOp.toMeasurement() and the content hash stays stable.
    private fun m(id: Int, timeMs: Long, weight: Float = 80f, user: Int = 1) =
        OpenScaleMeasurement.fromValues(
            id, user, Date(timeMs), "",
            listOf(OpenScaleMeasurementValue(0, "WEIGHT", "Weight", "kg", false, weight))
        )

    // --- Real-time dispatch ---------------------------------------------------------------

    @Test
    fun insert_callsBackend_andRecordsLedger() = runTest {
        val r = backend.submit(backend.pendingOp("insert", m(7, 1000)))
        assertTrue(r is SyncResult.Success)
        assertEquals(listOf("insert#7@1000"), backend.wire)

        // The ledger now knows id 7 → a reconcile of the same state is a no-op.
        backend.wire.clear()
        assertTrue(backend.reconcile(listOf(m(7, 1000))) is SyncResult.Success)
        assertEquals(emptyList<String>(), backend.wire)
    }

    @Test
    fun update_sameTimestamp_isPlainUpdate() = runTest {
        backend.submit(backend.pendingOp("insert", m(7, 1000, weight = 80f)))
        backend.wire.clear()
        backend.submit(backend.pendingOp("update", m(7, 1000, weight = 81f)))
        assertEquals(listOf("update#7@1000"), backend.wire)
    }

    @Test
    fun update_changedTimestamp_isMove_deleteOldThenInsertNew() = runTest {
        backend.submit(backend.pendingOp("insert", m(7, 1000)))
        backend.wire.clear()
        backend.submit(backend.pendingOp("update", m(7, 2000)))   // same id, new time
        assertEquals(listOf("delete@1000/u1", "insert#7@2000"), backend.wire)

        // After the move the ledger sits at the new time → editing the value there is a plain update.
        backend.wire.clear()
        backend.submit(backend.pendingOp("update", m(7, 2000, weight = 90f)))
        assertEquals(listOf("update#7@2000"), backend.wire)
    }

    @Test
    fun delete_forgetsLedgerEntry() = runTest {
        backend.submit(backend.pendingOp("insert", m(7, 1000)))
        backend.submit(PendingOp("delete", id = 7, userId = 1, dateMs = 1000))
        backend.wire.clear()
        // id 7 is gone from the ledger → reconciling against an empty openScale issues no delete.
        assertTrue(backend.reconcile(emptyList()) is SyncResult.Success)
        assertEquals(emptyList<String>(), backend.wire)
    }

    // --- Retry queue ----------------------------------------------------------------------

    @Test
    fun failedOp_isQueued_andReplayedOnInit() = runTest {
        backend.scripted.addLast(SyncResult.Failure(SyncResult.ErrorType.API_ERROR, "boom"))
        val r = backend.submit(backend.pendingOp("insert", m(7, 1000)))
        assertTrue(r is SyncResult.Failure)
        assertEquals(1, backend.pendingRetryCount())

        backend.wire.clear()
        backend.init()   // connect + drain → replays the insert (now succeeds)
        assertEquals(listOf("connect", "insert#7@1000"), backend.wire)
        assertEquals(0, backend.pendingRetryCount())
    }

    @Test
    fun drain_stopsAtFirstFailure_andKeepsTail() = runTest {
        // queue two ops, both fail on submit
        backend.scripted.addLast(SyncResult.Failure(SyncResult.ErrorType.API_ERROR))
        backend.submit(backend.pendingOp("insert", m(1, 1000)))
        backend.scripted.addLast(SyncResult.Failure(SyncResult.ErrorType.API_ERROR))
        backend.submit(backend.pendingOp("insert", m(2, 2000)))
        assertEquals(2, backend.pendingRetryCount())

        // drain: first op fails again → both stay queued
        backend.scripted.addLast(SyncResult.Failure(SyncResult.ErrorType.API_ERROR))
        backend.retryPending()
        assertEquals(2, backend.pendingRetryCount())
    }

    // --- Reconcile ------------------------------------------------------------------------

    @Test
    fun reconcile_classifiesInsertUpdateMove_andSkipsUnchanged() = runTest {
        backend.reconcile(listOf(m(1, 1000), m(2, 2000), m(3, 3000)))   // seed ledger
        backend.wire.clear()

        // 1 unchanged · 2 value-changed (update) · 3 time-changed (move) · 4 new (insert)
        val r = backend.reconcile(listOf(m(1, 1000), m(2, 2000, weight = 99f), m(3, 3500), m(4, 4000)))
        assertTrue(r is SyncResult.Success)

        assertTrue(backend.wire.contains("update#2@2000"))
        assertTrue(backend.wire.contains("delete@3000/u1"))   // move: old time removed
        assertTrue(backend.wire.contains("insert#3@3500"))    // move: new time inserted
        assertTrue(backend.wire.contains("insert#4@4000"))
        assertFalse(backend.wire.any { it.contains("#1") })   // 1 untouched
    }

    @Test
    fun reconcile_deletesIdsGoneFromOpenScale() = runTest {
        backend.reconcile(listOf(m(1, 1000), m(2, 2000)))
        backend.wire.clear()
        backend.reconcile(listOf(m(1, 1000)))                 // id 2 removed in openScale
        assertEquals(listOf("delete@2000/u1"), backend.wire)
    }

    @Test
    fun reconcile_isIdempotent() = runTest {
        val state = listOf(m(1, 1000), m(2, 2000))
        backend.reconcile(state)
        backend.wire.clear()
        backend.reconcile(state)
        assertEquals(emptyList<String>(), backend.wire)
    }

    // --- Bulk operators -------------------------------------------------------------------

    @Test
    fun reconcile_usesBulkInsert_forBatchingBackend() = runTest {
        backend.batch = true
        backend.reconcile(listOf(m(1, 1000), m(2, 2000), m(3, 3000)))
        // one batched call, not three single inserts
        assertEquals(listOf("insertAll(3)"), backend.wire)
    }

    @Test
    fun bulkPartialFailure_recordsApplied_andQueuesTheRest() = runTest {
        backend.batch = true
        backend.batchSucceed = { ms -> ms.filter { it.id != 2 } }   // id 2 fails in the batch

        val r = backend.reconcile(listOf(m(1, 1000), m(2, 2000), m(3, 3000)))
        assertTrue(r is SyncResult.Failure)
        assertEquals(1, backend.pendingRetryCount())               // only id 2 queued

        // 1 and 3 are in the ledger now → a re-reconcile only retries the still-missing id 2.
        backend.batch = false
        backend.wire.clear()
        backend.reconcile(listOf(m(1, 1000), m(2, 2000), m(3, 3000)))
        assertEquals(listOf("insert#2@2000"), backend.wire)
    }

    // --- Direction / multi-user gating ----------------------------------------------------

    @Test
    fun directionGating_exportVsImport() {
        backend.viewModel().setSyncDirection(SyncDirection.EXPORT)
        assertTrue(backend.exportEnabled())
        assertFalse(backend.importEnabled())                       // supportsInbound = false here

        backend.viewModel().setSyncDirection(SyncDirection.IMPORT)
        assertFalse(backend.exportEnabled())
    }

    @Test
    fun shouldSync_respectsSelectedUser_forSingleUserBackend() {
        backend.viewModel().setSyncDirection(SyncDirection.BOTH)
        backend.viewModel().setSelectedUserId(1)
        assertTrue(backend.shouldSync(1))
        assertFalse(backend.shouldSync(2))
    }

    @Test
    fun shouldSync_acceptsEveryUser_forMultiUserBackend() {
        val multi = FakeBackend(ctx, prefs, name = "Multi", multiUser = true)
        multi.viewModel().setSyncDirection(SyncDirection.BOTH)
        assertTrue(multi.shouldSync(1))
        assertTrue(multi.shouldSync(42))
    }

    // --- Multi-user (identity is (userId, timestamp)) -------------------------------------

    @Test
    fun multiUser_reconcile_pushesAllUsers_andDeletesPerUser() = runTest {
        val multi = FakeBackend(ctx, prefs, name = "Multi", multiUser = true)
        ctx.getSharedPreferences("export_ledger_Multi", Context.MODE_PRIVATE).edit().clear().commit()

        // Same timestamp for two DIFFERENT users is legal — the userId disambiguates the record.
        multi.reconcile(listOf(m(1, 1000, user = 1), m(2, 1000, user = 2)))
        assertTrue(multi.wire.contains("insert#1@1000"))
        assertTrue(multi.wire.contains("insert#2@1000"))

        // Remove user 1's measurement → delete targets user 1 only; user 2 untouched.
        multi.wire.clear()
        multi.reconcile(listOf(m(2, 1000, user = 2)))
        assertEquals(listOf("delete@1000/u1"), multi.wire)
    }

    // --- Same timestamp, same user (one openScale measurement edited) ---------------------

    @Test
    fun sameTimestamp_distinctIds_trackedIndependently() = runTest {
        // (Can't happen within one user in openScale, but the ledger keys by id regardless.)
        backend.reconcile(listOf(m(1, 1000), m(2, 1000)))
        assertTrue(backend.wire.contains("insert#1@1000"))
        assertTrue(backend.wire.contains("insert#2@1000"))

        // editing only id 2 updates only id 2
        backend.wire.clear()
        backend.reconcile(listOf(m(1, 1000), m(2, 1000, weight = 99f)))
        assertEquals(listOf("update#2@1000"), backend.wire)
    }

    // --- Reliability edge cases -----------------------------------------------------------

    @Test
    fun reconcile_emptyState_doesNothing() = runTest {
        assertTrue(backend.reconcile(emptyList()) is SyncResult.Success)
        assertEquals(emptyList<String>(), backend.wire)
    }

    @Test
    fun bulk_emptyState_makesNoCall() = runTest {
        backend.batch = true
        assertTrue(backend.reconcile(emptyList()) is SyncResult.Success)
        assertEquals(emptyList<String>(), backend.wire)
    }

    @Test
    fun move_whenStaleDeleteFails_stillInsertsAtNewTime() = runTest {
        backend.submit(backend.pendingOp("insert", m(7, 1000)))     // ledger @1000
        backend.wire.clear()
        backend.scripted.addLast(SyncResult.Failure(SyncResult.ErrorType.API_ERROR))  // the cleanup delete fails
        backend.submit(backend.pendingOp("update", m(7, 2000)))     // move
        assertEquals(listOf("delete@1000/u1", "insert#7@2000"), backend.wire)

        // The ledger still advanced to the new time → editing there is a plain update.
        backend.wire.clear()
        backend.submit(backend.pendingOp("update", m(7, 2000, weight = 90f)))
        assertEquals(listOf("update#7@2000"), backend.wire)
    }

    @Test
    fun clearOp_failing_resetsTheQueue() = runTest {
        backend.failAll = true
        backend.submit(backend.pendingOp("insert", m(1, 1000)))
        backend.submit(backend.pendingOp("insert", m(2, 2000)))
        assertEquals(2, backend.pendingRetryCount())
        backend.submit(PendingOp("clear", userId = 1))              // clear supersedes the backlog
        assertEquals(1, backend.pendingRetryCount())
    }

    @Test
    fun retryQueue_isCappedAt500() = runTest {
        backend.failAll = true
        repeat(505) { backend.submit(backend.pendingOp("insert", m(it, it.toLong()))) }
        assertEquals(500, backend.pendingRetryCount())
    }
}
