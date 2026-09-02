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
import org.junit.Assert.assertNull
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
    private lateinit var openScale: FakeDataProvider

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        prefs = ctx.getSharedPreferences("test_settings", Context.MODE_PRIVATE)
        // Start every test with an empty ledger + outstanding set for the "Fake" backend.
        ctx.getSharedPreferences("export_ledger_Fake", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("retry_queue_Fake", Context.MODE_PRIVATE).edit().clear().commit()
        backend = FakeBackend(ctx, prefs)
        // The drain re-derives outstanding ids from openScale, so the provider is part of the setup.
        openScale = FakeDataProvider(ctx, prefs)
        backend.openScaleDataService = openScale
    }

    // Build like the real pipeline: the generic value set is the source of truth (weight is derived),
    // so it round-trips through PendingOp.toMeasurement() and the content hash stays stable.
    private fun m(id: Int, timeMs: Long, weight: Float = 80f, user: Int = 1) =
        OpenScaleMeasurement.fromValues(
            id, user, Date(timeMs), "",
            listOf(OpenScaleMeasurementValue("builtin.weight", "Weight", "kg", false, weight))
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
        openScale.put(m(7, 1000))
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
    fun drain_replaysCurrentValues_notTheSnapshotOfTheFailedOp() = runTest {
        backend.scripted.addLast(SyncResult.Failure(SyncResult.ErrorType.API_ERROR))
        backend.submit(backend.pendingOp("insert", m(7, 1000)))

        // The user corrects the measurement in openScale before the replay gets its chance.
        openScale.put(m(7, 2000, weight = 77f))

        backend.wire.clear()
        backend.retryPending()
        assertEquals(listOf("insert#7@2000"), backend.wire)
        assertEquals(0, backend.pendingRetryCount())

        // And the ledger holds the corrected state, so a reconcile over it is a no-op.
        val r = backend.reconcile(listOf(m(7, 2000, weight = 77f)))
        assertEquals(1, (r as SyncResult.Success).data.unchanged)
    }

    @Test
    fun drain_pushesADelete_whenTheMeasurementIsGoneFromOpenScale() = runTest {
        openScale.put(m(7, 1000))
        backend.submit(backend.pendingOp("insert", m(7, 1000)))       // exported → in the ledger
        backend.scripted.addLast(SyncResult.Failure(SyncResult.ErrorType.API_ERROR))
        backend.submit(backend.pendingOp("update", m(7, 1000)))       // fails → outstanding

        openScale.measurements.remove(7)                              // deleted in openScale meanwhile

        backend.wire.clear()
        backend.retryPending()
        // The stale op would have resurrected it; re-deriving turns it into the delete that never
        // made it out.
        assertEquals(listOf("delete@1000/u1"), backend.wire)
        assertEquals(0, backend.pendingRetryCount())
    }

    @Test
    fun drain_dropsWhatNeitherOpenScaleNorTheLedgerKnows() = runTest {
        backend.scripted.addLast(SyncResult.Failure(SyncResult.ErrorType.API_ERROR))
        backend.submit(backend.pendingOp("insert", m(7, 1000)))       // never exported, then removed

        backend.wire.clear()
        backend.retryPending()
        assertEquals(emptyList<String>(), backend.wire)
        assertEquals(0, backend.pendingRetryCount())
    }

    @Test
    fun drain_isolatesAPermanentlyFailingMeasurement() = runTest {
        openScale.put(m(1, 1000), m(2, 2000))
        backend.failAll = true
        backend.submit(backend.pendingOp("insert", m(1, 1000)))
        backend.submit(backend.pendingOp("insert", m(2, 2000)))
        assertEquals(2, backend.pendingRetryCount())

        // id 1 keeps failing, id 2 would work — the old drain stopped at the head and never tried it.
        backend.failAll = false
        backend.scripted.addLast(SyncResult.Failure(SyncResult.ErrorType.API_ERROR))
        backend.wire.clear()
        backend.retryPending()

        assertEquals(listOf("insert#1@1000", "insert#2@2000"), backend.wire)
        assertEquals(1, backend.pendingRetryCount())
    }

    @Test
    fun drain_givesUpAfterConsecutiveFailures_andKeepsEverythingOutstanding() = runTest {
        val all = (1..5).map { m(it, it * 1000L) }
        all.forEach { openScale.put(it) }
        backend.failAll = true
        backend.reconcile(all)
        assertEquals(5, backend.pendingRetryCount())

        backend.wire.clear()
        backend.retryPending()

        // Three failures in a row read as "backend down": stop, waste no more battery, lose nothing.
        assertEquals(3, backend.wire.size)
        assertEquals(5, backend.pendingRetryCount())
    }

    @Test
    fun outstandingSet_countsMeasurements_notRetries() = runTest {
        openScale.put(m(7, 1000))
        backend.failAll = true
        repeat(5) { backend.submit(backend.pendingOp("update", m(7, 1000))) }

        // The old per-op queue grew by one entry per attempt and reported "5 measurements pending".
        assertEquals(1, backend.pendingRetryCount())
    }

    @Test
    fun drain_keepsTheSetWhenOpenScaleCannotBeRead() = runTest {
        openScale.put(m(7, 1000))
        backend.scripted.addLast(SyncResult.Failure(SyncResult.ErrorType.API_ERROR))
        backend.submit(backend.pendingOp("insert", m(7, 1000)))

        openScale.readFails = true
        backend.wire.clear()
        backend.retryPending()

        assertEquals(emptyList<String>(), backend.wire)
        assertEquals(1, backend.pendingRetryCount())
    }

    @Test
    fun legacyQueue_isUpgradedToTheOutstandingSet() = runTest {
        // A backlog written by an older version: two ops for the same measurement plus one for
        // another. The old drain could never work these off once one of them failed permanently.
        ctx.getSharedPreferences("retry_queue_Fake", Context.MODE_PRIVATE).edit().putString(
            "queue",
            """[{"type":"insert","id":7,"userId":1,"dateMs":1000,"username":"","values":[]},
                {"type":"update","id":7,"userId":1,"dateMs":1000,"username":"","values":[]},
                {"type":"insert","id":8,"userId":1,"dateMs":2000,"username":"","values":[]}]"""
        ).commit()

        assertEquals(2, backend.pendingRetryCount())

        openScale.put(m(7, 1000), m(8, 2000))
        backend.wire.clear()
        backend.retryPending()
        assertEquals(listOf("insert#7@1000", "insert#8@2000"), backend.wire)
        assertEquals(0, backend.pendingRetryCount())
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

    /** The issue-32 case: the receiver lost data, so the manual (forced) sync must re-push even
     *  though the ledger considers everything up to date — as updates, not duplicate inserts. */
    @Test
    fun reconcile_force_rePushesLedgerKnownMeasurements_asUpdates() = runTest {
        val state = listOf(m(1, 1000), m(2, 2000))
        backend.reconcile(state)
        backend.wire.clear()

        val r = backend.reconcile(state, force = true)
        assertTrue(r is SyncResult.Success)
        assertEquals(listOf("update#1@1000", "update#2@2000"), backend.wire)
    }

    // --- Reconcile statistics -------------------------------------------------------------

    @Test
    fun reconcileStats_countAcknowledgedOpsOnly() = runTest {
        backend.reconcile(listOf(m(1, 1000), m(2, 2000), m(3, 3000)))   // seed ledger

        // 1 unchanged · 2 value-changed (update) · 3 time-changed (move) · 4 new (insert)
        val r = backend.reconcile(listOf(m(1, 1000), m(2, 2000, weight = 99f), m(3, 3500), m(4, 4000)))
        assertTrue(r is SyncResult.Success)
        val stats = (r as SyncResult.Success).data
        assertEquals(1, stats.inserted)
        assertEquals(1, stats.updated)
        assertEquals(1, stats.moved)
        assertEquals(1, stats.unchanged)
        assertEquals(3, stats.sent)                 // unchanged is NOT part of sent
    }

    @Test
    fun reconcileStats_noOpRunSendsNothing() = runTest {
        val state = listOf(m(1, 1000), m(2, 2000))
        backend.reconcile(state)
        val r = backend.reconcile(state)
        assertEquals(0, (r as SyncResult.Success).data.sent)
        assertEquals(2, r.data.unchanged)
    }

    @Test
    fun reconcileStats_countsDeletes() = runTest {
        backend.reconcile(listOf(m(1, 1000), m(2, 2000)))
        val r = backend.reconcile(listOf(m(1, 1000)))       // id 2 removed in openScale
        assertEquals(1, (r as SyncResult.Success).data.deleted)
        assertEquals(1, r.data.sent)
    }

    /** A failed op must not be counted as sent — the ledger doesn't record it either. */
    @Test
    fun reconcileStats_partialBulkFailure_countsOnlyApplied() = runTest {
        backend.batch = true
        backend.batchSucceed = { ms -> ms.filter { it.id != 2 } }   // id 2 fails in the batch

        val r = backend.reconcile(listOf(m(1, 1000), m(2, 2000), m(3, 3000)))
        assertTrue(r is SyncResult.Failure)                 // failure still wins the return value
        assertEquals(1, backend.pendingRetryCount())        // id 2 queued, not counted anywhere
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

    // --- Inbound (external source → openScale) --------------------------------------------

    /** An inbound-capable backend set to import, with openScale already holding [existing]. */
    private fun inboundBackend(vararg existing: OpenScaleMeasurement): FakeBackend {
        val b = FakeBackend(ctx, prefs, name = "Fake", inbound = true)
        b.openScaleDataService = openScale
        b.viewModel().setSyncDirection(SyncDirection.BOTH)
        existing.forEach { openScale.put(it) }
        return b
    }

    @Test
    fun inbound_addsUnknownTimestamps_andEnrichesTheOnesOpenScaleAlreadyHas() = runTest {
        val b = inboundBackend(m(1, 1000))
        b.inboundReadings += InboundMeasurement(timeMs = 1000, weightKg = 80f, fatPct = 21f)  // known
        b.inboundReadings += InboundMeasurement(timeMs = 5000, weightKg = 81f)                // new

        val r = b.runInbound(1)

        // The old pipeline blind-inserted both; openScale ignored the duplicate, so the fat value
        // that reading carried was dropped without a trace.
        assertEquals(listOf("update@1000/u1", "insert@5000/u1"), openScale.inboundWrites)
        assertEquals(InboundStats(imported = 1, updated = 1), (r as SyncResult.Success).data)
    }

    @Test
    fun inbound_doesNotCountAnUpdateThatChangedNothing() = runTest {
        val b = inboundBackend(m(1, 1000))
        openScale.updateChangesNothing += 1000L
        b.inboundReadings += InboundMeasurement(timeMs = 1000, weightKg = 80f)

        val r = b.runInbound(1)

        // openScale reports 0 rows when every value already matched — that is not an import.
        assertEquals(InboundStats(), (r as SyncResult.Success).data)
    }

    @Test
    fun inbound_countsOnlyInsertsOpenScaleReallyStored() = runTest {
        val b = inboundBackend()
        openScale.insertRejects += 5000L                       // provider drops it, reports nothing
        b.inboundReadings += InboundMeasurement(timeMs = 5000, weightKg = 81f)
        b.inboundReadings += InboundMeasurement(timeMs = 6000, weightKg = 82f)

        val r = b.runInbound(1)

        // "The call did not throw" used to count as imported; the result is verified against
        // openScale now, so only the row that actually landed counts.
        assertEquals(InboundStats(imported = 1), (r as SyncResult.Success).data)
    }

    @Test
    fun inbound_isSkipped_whenTheBackendIsExportOnly() = runTest {
        val b = inboundBackend()
        b.viewModel().setSyncDirection(SyncDirection.EXPORT)
        b.inboundReadings += InboundMeasurement(timeMs = 5000, weightKg = 81f)

        assertTrue(b.runInbound(1) is SyncResult.Failure)
        assertEquals(emptyList<String>(), openScale.inboundWrites)
    }

    // --- onReconciled snapshot hook -------------------------------------------------------

    @Test
    fun onReconciled_firesWithFullSet_andChangedUsers_whenSomethingChanged() = runTest {
        val r = backend.reconcile(listOf(m(1, 1000, user = 1), m(2, 2000, user = 2)))
        assertTrue(r is SyncResult.Success)
        assertEquals(1, backend.reconciledCalls.size)
        val (changedUsers, currentIds) = backend.reconciledCalls.single()
        assertEquals(setOf(1, 2), changedUsers)          // both users had an insert
        assertEquals(listOf(1, 2), currentIds)           // hook sees the FULL current set
    }

    @Test
    fun onReconciled_notCalled_onNoOpReconcile() = runTest {
        val state = listOf(m(1, 1000, user = 1), m(2, 2000, user = 2))
        backend.reconcile(state)
        backend.reconciledCalls.clear()
        backend.reconcile(state)                         // nothing changed
        assertEquals(emptyList<Pair<Set<Int>, List<Int>>>(), backend.reconciledCalls)
    }

    @Test
    fun onReconciled_force_firesForAllUsers_evenWhenUnchanged() = runTest {
        val state = listOf(m(1, 1000, user = 1), m(2, 2000, user = 2))
        backend.reconcile(state)
        backend.reconciledCalls.clear()
        backend.reconcile(state, force = true)           // no change, but forced
        assertEquals(setOf(1, 2), backend.reconciledCalls.single().first)
    }

    @Test
    fun onReconciled_onlyChangedUser_getsSnapshot() = runTest {
        backend.reconcile(listOf(m(1, 1000, user = 1), m(2, 2000, user = 2)))
        backend.reconciledCalls.clear()
        // add a measurement for user 2 only → only user 2 changed
        backend.reconcile(listOf(m(1, 1000, user = 1), m(2, 2000, user = 2), m(3, 3000, user = 2)))
        assertEquals(setOf(2), backend.reconciledCalls.single().first)
    }

    @Test
    fun onReconciled_failure_doesNotBreakReconcile() = runTest {
        backend.failOnReconciled = true
        val r = backend.reconcile(listOf(m(1, 1000, user = 1)))
        assertTrue(r is SyncResult.Success)              // hook error swallowed
        assertEquals(0, backend.pendingRetryCount())     // insert still applied, not queued
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
    fun clearOp_failing_leavesTheExportedMeasurementsOutstanding() = runTest {
        openScale.put(m(1, 1000), m(2, 2000))
        backend.submit(backend.pendingOp("insert", m(1, 1000)))
        backend.submit(backend.pendingOp("insert", m(2, 2000)))
        assertEquals(0, backend.pendingRetryCount())

        // openScale wiped the user; our clear does not reach the backend.
        openScale.measurements.clear()
        backend.scripted.addLast(SyncResult.Failure(SyncResult.ErrorType.API_ERROR))
        backend.submit(PendingOp("clear", userId = 1))

        // What the receiver still wrongly holds is exactly what the ledger recorded, so both ids are
        // outstanding — and re-deriving them turns each into the delete the clear owed.
        assertEquals(2, backend.pendingRetryCount())
        backend.wire.clear()
        backend.retryPending()
        assertEquals(listOf("delete@1000/u1", "delete@2000/u1"), backend.wire)
        assertEquals(0, backend.pendingRetryCount())
    }

    @Test
    fun retryQueue_isCappedAt500() = runTest {
        backend.failAll = true
        repeat(505) { backend.submit(backend.pendingOp("insert", m(it, it.toLong()))) }
        assertEquals(500, backend.pendingRetryCount())
    }

    // --- Unsendable measurements (issues #34, #35) -----------------------------------------
    // A backend can hold data it will never accept — Health Connect refuses a body fat above
    // 100 % or a record timed in the future. Such a measurement must not fail the run, must not
    // enter the retry queue (a replay cannot fix it) and must not enter the ledger (so correcting
    // it in openScale, or simply the clock catching up, heals it on the next run).

    @Test
    fun reconcile_countsAnUnsendableMeasurementAsSkipped_notAsFailure() = runTest {
        backend.batch = true
        backend.invalidIds += 2

        val r = backend.reconcile(listOf(m(1, 1000), m(2, 2000), m(3, 3000)))

        assertTrue(r is SyncResult.Success)
        val stats = (r as SyncResult.Success).data
        assertEquals(2, stats.inserted)
        assertEquals(1, stats.skipped)
        assertEquals(2, stats.sent)              // skipped is NOT part of sent
    }

    @Test
    fun reconcile_doesNotQueueOrLedgerAnUnsendableMeasurement() = runTest {
        backend.batch = true
        backend.invalidIds += 2
        backend.reconcile(listOf(m(1, 1000), m(2, 2000)))

        // Never retried: a replay would fail exactly the same way, every init() forever.
        assertEquals(0, backend.pendingRetryCount())

        // Never ledgered: once openScale holds a value the backend accepts, it goes out by itself.
        backend.invalidIds.clear()
        backend.wire.clear()
        val r = backend.reconcile(listOf(m(1, 1000), m(2, 2000)))
        assertEquals(1, (r as SyncResult.Success).data.inserted)
        assertTrue(backend.wire.any { it.contains("insertAll") })
    }

    @Test
    fun submit_doesNotQueueAnUnsendableMeasurement() = runTest {
        backend.invalidIds += 7
        val r = backend.submit(backend.pendingOp("insert", m(7, 1000)))

        assertTrue(r is SyncResult.Failure)
        assertEquals(SyncResult.ErrorType.INVALID_DATA, (r as SyncResult.Failure).errorType)
        assertEquals(0, backend.pendingRetryCount())
    }

    @Test
    fun loopBulk_reportsUnsendableMeasurementsAsSkipped() = runTest {
        // Same behaviour without a batching backend: the default per-item bulk loop.
        backend.invalidIds += 2
        val r = backend.reconcile(listOf(m(1, 1000), m(2, 2000)))

        val stats = (r as SyncResult.Success).data
        assertEquals(1, stats.inserted)
        assertEquals(1, stats.skipped)
        assertEquals(0, backend.pendingRetryCount())
    }

    // --- Nothing a backend does may escape as a throw --------------------------------------

    @Test
    fun submit_turnsAThrowingBackendIntoAFailure() = runTest {
        backend.throwsOnWrite = true

        val r = backend.submit(backend.pendingOp("insert", m(7, 1000)))

        assertTrue(r is SyncResult.Failure)
        assertEquals(SyncResult.ErrorType.UNKNOWN_ERROR, (r as SyncResult.Failure).errorType)
        // A throw is a transport-level unknown, not bad data → it stays queued for a retry.
        assertEquals(1, backend.pendingRetryCount())
    }

    @Test
    fun reconcile_turnsAThrowingBulkBackendIntoAFailure() = runTest {
        backend.batch = true
        backend.throwsOnWrite = true

        val r = backend.reconcile(listOf(m(1, 1000), m(2, 2000)))

        assertTrue("a throwing backend must not escape reconcile()", r is SyncResult.Failure)
        assertEquals(SyncResult.ErrorType.UNKNOWN_ERROR, (r as SyncResult.Failure).errorType)
        assertEquals(2, backend.pendingRetryCount())
    }

    @Test
    fun runFullSync_turnsAThrowingBackendIntoAnErrorMessage() = runTest {
        openScale.readFails = false
        openScale.put(m(1, 1000))
        backend.viewModel().setSelectedUserId(1)
        backend.batch = true
        backend.throwsOnWrite = true

        assertNull("a crash must become a reported failure", backend.runFullSync())
    }
}
