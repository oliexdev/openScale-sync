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

import androidx.core.content.edit
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.health.openscale.sync.R
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurementValue
import com.health.openscale.sync.core.model.SyncDirection
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.provider.OpenScaleDataProvider
import com.health.openscale.sync.core.provider.OpenScaleProvider
import com.health.openscale.sync.gui.components.PendingQueueCard
import com.health.openscale.sync.gui.components.SyncActionButtons
import com.health.openscale.sync.gui.components.SyncErrorBanner
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

// One lock per backend name, shared across all ServiceInterface instances in this process
// (SyncService real-time, PeriodicSyncWorker, MainActivity manual sync) so every export-ledger
// read-modify-write is atomic regardless of which component performs it.
private val ledgerLocks = ConcurrentHashMap<String, Any>()

sealed class SyncResult<out T> {
    data class Success<T>(val data: T) : SyncResult<T>()
    data class Failure(val errorType: ErrorType, val message: String? = null, val cause: Throwable? = null) : SyncResult<Nothing>()

    enum class ErrorType {
        PERMISSION_DENIED,
        API_ERROR,
        UNKNOWN_ERROR
    }
}

/**
 * One measurement to import INTO openScale from an external source (Phase 4 inbound).
 * [weightKg] is mandatory; [fatPct]/[waterPct]/[musclePct] are convenience fields written via the
 * provider's fixed columns. [valuesJson] (optional) carries an arbitrary generic value set
 * (all types incl. custom) for future-flexible sources, written via the generic provider insert.
 */
data class InboundMeasurement(
    val timeMs: Long,
    val weightKg: Float,
    val fatPct: Float? = null,
    val waterPct: Float? = null,
    val musclePct: Float? = null,
    val valuesJson: String? = null,
    // Optional per-item target user (multi-user sources like MQTT carry it per message); when null
    // the pipeline writes to the userId passed to runInbound (single-user sources).
    val userId: Int? = null
)

/**
 * Result of a bulk apply ([ServiceInterface.insertAll]/[ServiceInterface.updateAll]): which measurements were
 * actually applied (so reconcile can update the ledger per item) plus the failure, if any. The
 * default per-item loop fills [succeeded] precisely; a batch override reports all-or-nothing.
 */
data class BulkResult(
    val succeeded: List<OpenScaleMeasurement>,
    val failure: SyncResult.Failure? = null
)

/**
 * A single queued/dispatched operation (insert/update/delete/clear). Carries everything needed to
 * replay it after a failure; the generic value set is the single source of truth (weight/fat/water/
 * muscle are derived via [toMeasurement]). Persisted as Gson JSON in the per-service retry queue.
 */
data class PendingOp(
    val type: String,
    val id: Int = 0,
    val userId: Int = 0,
    val dateMs: Long = 0L,
    val username: String = "",
    val values: List<OpenScaleMeasurementValue> = emptyList()
) {
    fun toMeasurement() = OpenScaleMeasurement.fromValues(id, userId, Date(dateMs), username, values)
}

abstract class ServiceInterface (
    private val context: Context
) {
    lateinit var navController : NavHostController
    lateinit var openScaleService: OpenScaleProvider
    lateinit var openScaleDataService: OpenScaleDataProvider

    abstract fun viewModel() : ViewModelInterface

    /**
     * Whether this backend can disambiguate multiple openScale users at the destination and should
     * therefore sync ALL users. Multi-user backends carry the user identity on the wire
     * (MQTT topic / payload / tag). Single-user backends (HealthConnect, Wger) keep this false and
     * only sync the per-service selected user (see [shouldSync]).
     */
    open val isMultiUser: Boolean get() = false

    /** Whether this backend can pull data INTO openScale (bidirectional). HealthConnect/Wger = true. */
    open val supportsInbound: Boolean get() = false

    /** Outbound active unless the user set this backend to import-only. */
    fun exportEnabled(): Boolean = viewModel().syncDirection.value != SyncDirection.IMPORT

    /** Inbound active only for inbound-capable backends set to import or both. */
    fun importEnabled(): Boolean = supportsInbound && viewModel().syncDirection.value != SyncDirection.EXPORT

    /**
     * Per-service gate for the real-time (outbound) path: respects the direction setting, then
     * multi-user backends accept every user while single-user backends only accept their user.
     */
    fun shouldSync(userId: Int): Boolean =
        exportEnabled() && (isMultiUser || userId == viewModel().selectedUserId.value)

    /**
     * Full manual sync of all measurements of the selected user to this backend.
     * Returns the number of synced measurements on success, or null on failure
     * (the error is surfaced via the error banner). Only call from the UI context
     * where openScaleService/openScaleDataService are wired.
     */
    suspend fun runFullSync() : Int? {
        // Multi-user backends sync all users; single-user backends only their selected user.
        val allUsers = openScaleDataService.getUsers()
        val users = if (isMultiUser) allUsers else allUsers.filter { it.id == viewModel().selectedUserId.value }
        val measurements = users.flatMap { openScaleDataService.getMeasurements(it) }
        return when (val result = reconcile(measurements)) {
            is SyncResult.Success -> {
                viewModel().setLastSync(Instant.now())
                measurements.size
            }
            is SyncResult.Failure -> {
                setErrorMessage(result)
                null
            }
        }
    }

    // --- Export ledger (Phase 3 reliability backstop) -----------------------------------
    // Per-backend record of what we've exported (measurementId → fingerprint + time + user),
    // persisted as Gson/SharedPreferences (no separate class, no Room/KSP). It is kept LIVE by
    // dispatch() on every real-time op, so it always knows each measurement's last-synced timestamp
    // — which lets the real-time path detect a moved (re-timed) measurement, and lets reconcile()
    // diff openScale's current state to heal missed inserts/updates/deletes (incl. moves) on
    // push-only backends (MQTT/Webhook/InfluxDB) where a plain re-push can't.
    private data class LedgerEntry(val hash: Long, val dateMs: Long, val userId: Int)
    private val ledgerPrefs by lazy {
        context.getSharedPreferences("export_ledger_${viewModel().getName()}", Context.MODE_PRIVATE)
    }
    private val ledgerMapType = object : TypeToken<MutableMap<Int, LedgerEntry>>() {}.type
    private val ledgerLock: Any get() = ledgerLocks.getOrPut(viewModel().getName()) { Any() }

    private fun ledgerLoad(): MutableMap<Int, LedgerEntry> {
        val json = ledgerPrefs.getString("ledger", null) ?: return mutableMapOf()
        return runCatching { retryGson.fromJson<MutableMap<Int, LedgerEntry>>(json, ledgerMapType) ?: mutableMapOf() }
            .getOrElse { mutableMapOf() }
    }
    private fun ledgerStore(map: Map<Int, LedgerEntry>) =
        ledgerPrefs.edit { putString("ledger", retryGson.toJson(map)) }

    // All mutations are atomic read-modify-write under ledgerLock so the live real-time path and a
    // concurrent reconcile/worker never clobber each other.
    /** Immutable snapshot for read-only classification in reconcile(). */
    private fun ledgerSnapshot(): Map<Int, LedgerEntry> = synchronized(ledgerLock) { HashMap(ledgerLoad()) }
    /** Single-entry lookup (move detection on update). */
    private fun ledgerEntry(id: Int): LedgerEntry? = synchronized(ledgerLock) { ledgerLoad()[id] }
    /** Record a successful insert/update: id → fingerprint + time + user. */
    private fun ledgerRecord(id: Int, m: OpenScaleMeasurement) = synchronized(ledgerLock) {
        val l = ledgerLoad(); l[id] = LedgerEntry(contentHash(m), m.date.time, m.userId); ledgerStore(l)
    }
    /** Forget an exported measurement by its stable id. */
    private fun ledgerForget(id: Int) = synchronized(ledgerLock) {
        val l = ledgerLoad(); if (l.remove(id) != null) ledgerStore(l)
    }
    /** Drop every entry of one user (clear). */
    private fun ledgerClearUser(userId: Int) = synchronized(ledgerLock) {
        val l = ledgerLoad(); if (l.entries.removeAll { it.value.userId == userId }) ledgerStore(l)
    }

    /** Value fingerprint (weight/fat/water/muscle + generic values) to detect content edits. The
     *  timestamp is deliberately NOT part of it — a time change is detected separately (ledger
     *  dateMs vs current) and handled as a move, since backends key records by (user, time). */
    private fun contentHash(m: OpenScaleMeasurement): Long =
        listOf(m.weight, m.fat, m.water, m.muscle, m.values).hashCode().toLong()

    /**
     * Diffs [current] (the authoritative openScale measurements for this backend's users) against
     * the export ledger and pushes the delta: new id → insert, changed fingerprint → update,
     * changed timestamp → move (delete old + insert new), id in ledger but gone from openScale →
     * delete (heals a missed delete-push, which a plain re-push cannot). The ledger is the only way
     * to detect deletions and moves on push-only backends.
     */
    suspend fun reconcile(current: List<OpenScaleMeasurement>): SyncResult<Unit> {
        val ledger = ledgerSnapshot()                       // read-only classification base
        val currentIds = current.mapTo(HashSet()) { it.id }

        // Classify the delta against the ledger:
        //   unknown id                     → insert
        //   known id, timestamp changed    → move (backends key by time, so the old record would
        //                                     orphan: delete it at the old time, insert at the new)
        //   known id, same time, new hash  → update
        //   known id, same time, same hash → no-op
        val inserts = ArrayList<OpenScaleMeasurement>()
        val updates = ArrayList<OpenScaleMeasurement>()
        val moves = ArrayList<Pair<OpenScaleMeasurement, LedgerEntry>>()
        for (m in current) {
            val prev = ledger[m.id]
            when {
                prev == null -> inserts += m
                prev.dateMs != m.date.time -> moves += (m to prev)
                prev.hash != contentHash(m) -> updates += m
                // else unchanged → no-op
            }
        }

        var failure: SyncResult.Failure? = null

        // Moved measurements: remove the stale record at the OLD timestamp (best-effort, via the raw
        // delete so a since-gone record doesn't pollute the retry queue), then insert at the new one.
        // submit() records the new ledger entry on success.
        for ((m, prev) in moves) {
            runCatching { delete(prev.userId, Date(prev.dateMs)) }
            (submit(pendingOp("insert", m)) as? SyncResult.Failure)?.let { failure = it }
        }

        // Apply inserts/updates through the bulk operator (one call for batch-capable backends,
        // a per-item loop otherwise). The ledger is updated only for what actually applied; the
        // rest is queued so the retry path re-pushes it per item.
        for ((batch, isInsert) in listOf(inserts to true, updates to false)) {
            if (batch.isEmpty()) continue
            val res = if (isInsert) insertAll(batch) else updateAll(batch)
            val appliedIds = res.succeeded.mapTo(HashSet()) { it.id }
            for (m in batch) {
                if (m.id in appliedIds) ledgerRecord(m.id, m)
                else retryEnqueue(pendingOp(if (isInsert) "insert" else "update", m))
            }
            res.failure?.let { failure = it }
        }

        // Deletes: ids we exported before but openScale no longer has. Per item — the protocols
        // don't batch deletes, and reconcile deletes are rare. submit() forgets them on success.
        for ((id, e) in ledger) {
            if (id !in currentIds) {
                (submit(PendingOp("delete", id = id, userId = e.userId, dateMs = e.dateMs)) as? SyncResult.Failure)?.let { failure = it }
            }
        }

        return failure ?: SyncResult.Success(Unit)
    }

    // --- Inbound (Phase 4: external source → openScale) ---------------------------------
    /**
     * Inbound-capable backends ([supportsInbound]) override this to pull measurements from their
     * source for [userId] since [sinceMs]. Source-specific echo/dedup (HealthConnect dataOrigin
     * filter, Wger day-level gap-fill) happens inside the implementation.
     */
    open suspend fun readInbound(userId: Int, sinceMs: Long): List<InboundMeasurement> = emptyList()

    /**
     * Generic inbound pipeline: read from the source and write into openScale. openScale stays
     * master — the provider insert uses IGNORE on (userId,timestamp), so existing measurements are
     * never overwritten (gap-fill). Returns the number of newly imported measurements.
     */
    suspend fun runInbound(userId: Int): SyncResult<Int> {
        if (!importEnabled()) {
            return SyncResult.Failure(SyncResult.ErrorType.API_ERROR, "Import is not enabled for this backend")
        }
        return try {
            val since = Instant.now().minus(Duration.ofDays(730)).toEpochMilli()
            val items = readInbound(userId, since)
            var imported = 0
            for (m in items) {
                val targetUser = m.userId ?: userId
                val ok = if (m.valuesJson != null)
                    openScaleDataService.insertMeasurementGeneric(targetUser, m.timeMs, m.weightKg, m.valuesJson)
                else
                    openScaleDataService.insertMeasurement(targetUser, m.timeMs, m.weightKg, m.fatPct, m.waterPct, m.musclePct)
                if (ok) imported++
            }
            SyncResult.Success(imported)
        } catch (e: Exception) {
            SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, null, e)
        }
    }

    // Raw single-item primitives implemented by concrete backends: just the guard + wire call,
    // no retry awareness. submit() (real-time) and the bulk operators reuse them; the central
    // retry queue lives only in submit()/reconcile().
    protected abstract suspend fun connect()
    protected abstract suspend fun insert(measurement: OpenScaleMeasurement) : SyncResult<Unit>
    protected abstract suspend fun update(measurement: OpenScaleMeasurement) : SyncResult<Unit>
    protected abstract suspend fun delete(userId: Int, date: Date) : SyncResult<Unit>
    protected abstract suspend fun clear(userId: Int) : SyncResult<Unit>

    // Bulk apply for mass sync (reconcile / initial export). The DEFAULT just loops the single-item
    // op and reports per-item success — so a backend that implements only the single primitives gets
    // correct (if unbatched) mass sync for free. Backends whose protocol can batch (Webhook one
    // POST, InfluxDB one line-protocol write, HealthConnect insertRecords, MQTT full publish)
    // override these with a single call (all-or-nothing).
    protected open suspend fun insertAll(measurements: List<OpenScaleMeasurement>): BulkResult =
        loopBulk(measurements) { insert(it) }

    protected open suspend fun updateAll(measurements: List<OpenScaleMeasurement>): BulkResult =
        loopBulk(measurements) { update(it) }

    private suspend fun loopBulk(
        measurements: List<OpenScaleMeasurement>,
        op: suspend (OpenScaleMeasurement) -> SyncResult<Unit>
    ): BulkResult {
        val succeeded = ArrayList<OpenScaleMeasurement>(measurements.size)
        var failure: SyncResult.Failure? = null
        for (m in measurements) when (val r = op(m)) {
            is SyncResult.Success -> succeeded += m
            is SyncResult.Failure -> failure = r
        }
        return BulkResult(succeeded, failure)
    }

    // Lifecycle: connect to the backend, then replay any backlog queued by a previous run.
    suspend fun init() {
        connect()
        drainQueue()
    }

    /**
     * The one central place a real-time op flows through: run it via [dispatch], and on failure
     * remember it in the retry queue so the next init() replays it. reconcile() routes its deletes
     * through here too. submit() is the only thing that touches the queue.
     */
    suspend fun submit(op: PendingOp): SyncResult<Unit> {
        val result = dispatch(op)
        if (result is SyncResult.Failure) retryEnqueue(op)
        return result
    }

    /**
     * Run one op AND keep the export ledger live, so reconcile sees an up-to-date picture and the
     * real-time path can detect moves. An update whose id already sits at a DIFFERENT timestamp in
     * the ledger is a MOVE — backends key records by (user, time), so we drop the stale record at the
     * old time and recreate at the new one. No retry-enqueue here; submit()/drainQueue() own that.
     */
    private suspend fun dispatch(op: PendingOp): SyncResult<Unit> = when (op.type) {
        "insert" -> op.toMeasurement().let { m ->
            insert(m).also { if (it is SyncResult.Success) ledgerRecord(op.id, m) }
        }
        "update" -> op.toMeasurement().let { m ->
            val prev = ledgerEntry(op.id)
            if (prev != null && prev.dateMs != op.dateMs) {
                runCatching { delete(prev.userId, Date(prev.dateMs)) }   // stale record at the old time
                insert(m).also { if (it is SyncResult.Success) ledgerRecord(op.id, m) }
            } else {
                update(m).also { if (it is SyncResult.Success) ledgerRecord(op.id, m) }
            }
        }
        "delete" -> delete(op.userId, Date(op.dateMs)).also {
            if (it is SyncResult.Success) ledgerForget(op.id)
        }
        "clear"  -> clear(op.userId).also { if (it is SyncResult.Success) ledgerClearUser(op.userId) }
        else     -> SyncResult.Success(Unit)
    }

    private suspend fun drainQueue() {
        val ops = retryPeek()
        if (ops.isEmpty()) return
        var failedIndex = ops.size
        for ((index, op) in ops.withIndex()) {
            if (dispatch(op) is SyncResult.Failure) {
                failedIndex = index
                break
            }
        }
        retryReplace(ops.subList(failedIndex, ops.size))
    }

    // --- Persisted retry queue (inlined; no separate class) ----------------------------
    // Per-service queue of failed ops, replayed transparently on the next init()/connect.
    // Override the key only to keep an existing on-disk queue file.
    protected open val retryQueueKey: String get() = viewModel().getName()
    private val retryPrefs by lazy {
        context.getSharedPreferences("retry_queue_$retryQueueKey", Context.MODE_PRIVATE)
    }
    private val retryGson = Gson()
    private val retryListType = object : TypeToken<List<PendingOp>>() {}.type

    fun pendingOp(type: String, m: OpenScaleMeasurement) =
        PendingOp(type, m.id, m.userId, m.date.time, m.username, m.values)

    @Synchronized
    private fun retryPeek(): List<PendingOp> {
        val json = retryPrefs.getString("queue", null) ?: return emptyList()
        return runCatching { retryGson.fromJson<List<PendingOp>>(json, retryListType) ?: emptyList() }
            .getOrElse { emptyList() }
    }

    @Synchronized
    private fun retryEnqueue(op: PendingOp) {
        val current = if (op.type == "clear") mutableListOf() else retryPeek().toMutableList()
        current.add(op)
        if (current.size > RETRY_MAX_SIZE) current.subList(0, current.size - RETRY_MAX_SIZE).clear()
        retryPrefs.edit { putString("queue", retryGson.toJson(current)) }
    }

    @Synchronized
    private fun retryReplace(ops: List<PendingOp>) {
        retryPrefs.edit { putString("queue", retryGson.toJson(ops)) }
    }

    // --- Minimal read/action API for the UI (queue stays otherwise private) -------------
    /** Number of failed ops currently waiting to be retried. */
    fun pendingRetryCount(): Int = retryPeek().size

    /** Replay the backlog now (e.g. user tapped "retry"). Keeps anything that still fails. */
    suspend fun retryPending() = drainQueue()

    /** Drop the whole backlog (e.g. user tapped "discard"). */
    fun clearPending() = retryReplace(emptyList())

    fun setErrorMessage(message : String) {
        viewModel().setErrorMessage(message)
        Timber.e("[ERROR] %s: %s", viewModel().getName(), message)
    }

    fun setErrorMessage(failure: SyncResult.Failure) {
        var fullMessage : String

        when (failure.errorType) {
            SyncResult.ErrorType.PERMISSION_DENIED -> {
                fullMessage = context.getString(R.string.sync_service_permission_error)
            }
            SyncResult.ErrorType.API_ERROR -> {
                fullMessage = context.getString(R.string.sync_service_api_error)
            }
            SyncResult.ErrorType.UNKNOWN_ERROR -> {
                fullMessage = context.getString(R.string.sync_service_unknown_error)
            }
        }

        if (failure.message != null) {
            fullMessage += " (" + failure.message + ")"
        } else if (failure.cause != null) {
            fullMessage += " (" + failure.cause.message + ")"
        }

        setErrorMessage(fullMessage)
    }

    fun setInfoMessage(message : String) {
        viewModel().setInfoMessage(message)
        Timber.i("[INFO] %s: %s", viewModel().getName(), message)
    }

    fun setDebugMessage(message : String) {
        val fullMessage = viewModel().getName() + ": " + message
        viewModel().setDebugMessage(fullMessage)
        Timber.d("[DEBUG] $fullMessage")
    }

    fun clearErrorMessage() {
        viewModel().setErrorMessage("")
    }

    open fun registerActivityResultLauncher(activity: ComponentActivity) {

    }

    @Composable
    abstract fun ComposeSettings(activity: ComponentActivity)

    /**
     * Shared detail-screen layout: a scrollable form area on top (the given [form] fields +
     * pending-queue card) and a fixed action area pinned to the bottom of the screen (error
     * banner + full-sync / test-connection buttons). The enable toggle lives in the top app bar.
     */
    @Composable
    protected fun DetailScaffold(
        activity: ComponentActivity,
        showActions: Boolean = true,
        testConnecting: Boolean = false,
        onTest: () -> Unit = {},
        form: @Composable ColumnScope.() -> Unit
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                form()
                PendingQueueCard(this@ServiceInterface, activity)
            }
            if (showActions) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val errorMessage by viewModel().errorMessage.observeAsState()
                    if (viewModel().syncEnabled.value) SyncErrorBanner(errorMessage)
                    SyncActionButtons(this@ServiceInterface, activity, testConnecting, onTest)
                }
            }
        }
    }

    companion object {
        private const val RETRY_MAX_SIZE = 500
    }
}