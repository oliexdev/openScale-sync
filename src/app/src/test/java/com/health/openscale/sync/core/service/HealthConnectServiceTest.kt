package com.health.openscale.sync.core.service

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import com.health.openscale.sync.core.model.OpenScaleViewModel
import com.health.openscale.sync.core.model.SyncDirection
import com.health.openscale.sync.core.sync.HealthConnectSyncTest.FakeHealthConnectClient
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/** History access is an optional addition to importing, never a prerequisite for normal sync. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HealthConnectServiceTest {
    private val client = FakeHealthConnectClient()
    private lateinit var service: HealthConnectService
    private lateinit var openScale: FakeDataProvider
    private val historyPermission = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    private val readPermissions = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(BodyWaterMassRecord::class),
        HealthPermission.getReadPermission(BoneMassRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class)
    )
    private val writePermissions = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(BodyWaterMassRecord::class),
        HealthPermission.getWritePermission(BoneMassRecord::class),
        HealthPermission.getWritePermission(LeanBodyMassRecord::class),
        HealthPermission.getWritePermission(BasalMetabolicRateRecord::class)
    )

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("health-connect-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        service = HealthConnectService(context, prefs, client)
        openScale = FakeDataProvider(context, prefs)
        service.openScaleDataService = openScale
        service.viewModel().setConnectAvailable(true)
    }

    private fun enableImport() {
        service.viewModel().setSyncDirection(SyncDirection.IMPORT)
        client.grantedPermissions = writePermissions + readPermissions + historyPermission
        client.readsRejected = false
    }

    private fun storeWeight(time: Instant, kg: Double = 80.0) {
        client.stored += WeightRecord(time, null, Mass.kilograms(kg), client.metadataFrom("other.app"))
    }

    private fun changes(token: String, next: String, vararg records: Record, hasMore: Boolean = false) {
        client.changes[token] = ChangesResponse(records.map(::UpsertionChange), next, hasMore, false)
    }

    @Test
    fun fullImportWritesMeasurementsOlderThanTwoYearsIntoOpenScale() = runTest {
        enableImport()
        val old = Instant.parse("2010-01-01T12:00:00Z")
        val recent = Instant.parse("2026-10-01T12:00:00Z")
        storeWeight(old, 80.0)
        storeWeight(recent, 81.0)

        // Exercises the real shared pipeline -> HC adapter -> paged importer -> provider calls.
        val result = service.runInbound(1)

        assertTrue(result is SyncResult.Success)
        assertEquals(2, (result as SyncResult.Success).data.imported)
        assertEquals(mapOf(old.toEpochMilli() to 80f, recent.toEpochMilli() to 81f),
            openScale.measurements.values.associate { it.date.time to it.weight })
        assertTrue(openScale.measurements.values.all { it.userId == 1 })
    }

    @Test
    fun repeatingFullImportDoesNotInsertDuplicateMeasurements() = runTest {
        enableImport()
        val time = Instant.parse("2010-01-01T12:00:00Z")
        storeWeight(time)
        val first = service.runInbound(1)
        val initialReads = client.reads.size
        val initialWrites = openScale.inboundWrites.size

        val second = service.runInbound(1)

        assertTrue(first is SyncResult.Success && second is SyncResult.Success)
        assertEquals(1, (first as SyncResult.Success).data.imported)
        assertEquals(0, (second as SyncResult.Success).data.imported)
        assertEquals(time.toEpochMilli(), openScale.measurements.values.single().date.time)
        assertEquals(1, openScale.inboundWrites.count { it.startsWith("insert@") })
        assertEquals(initialReads, client.reads.size)
        assertEquals(initialWrites, openScale.inboundWrites.size)
        assertEquals(listOf("changes:1"), client.changeRequests)
        assertEquals("token", client.events.first())
    }

    @Test
    fun backdatedChangesReadOnlyTheirWindow_andDoNotRewriteRecentHistory() = runTest {
        enableImport()
        val old = Instant.parse("2010-01-01T12:00:00Z")
        val recent = Instant.parse("2026-10-01T12:00:00Z")
        storeWeight(old)
        storeWeight(recent)
        assertTrue(service.runInbound(1) is SyncResult.Success)
        client.stored.removeAt(0)
        val corrected = WeightRecord(old, null, Mass.kilograms(79.0), client.metadataFrom("other.app"))
        client.stored += corrected
        changes("changes:1", "updated", corrected)
        client.reads.clear()
        openScale.inboundWrites.clear()

        val result = service.runInbound(1)

        assertTrue(result is SyncResult.Success)
        assertEquals(listOf("update@${old.toEpochMilli()}/u1"), openScale.inboundWrites)
        assertEquals(79f, openScale.measurements.values.first { it.date.toInstant() == old }.weight)
        assertTrue(client.reads.all {
            it.timeRangeFilter.startTime == old.minusSeconds(6) &&
                it.timeRangeFilter.endTime == old.plusMillis(6001)
        })
    }

    @Test
    fun compositionOnlyChangeFindsUnchangedWeightAndOtherMetrics() = runTest {
        enableImport()
        val time = Instant.parse("2020-01-01T12:00:00Z")
        storeWeight(time)
        client.stored += BodyWaterMassRecord(time.minusMillis(500), null, Mass.kilograms(40.0),
            client.metadataFrom("other.app"))
        client.stored += BoneMassRecord(time, null, Mass.kilograms(3.0), client.metadataFrom("other.app"))
        client.stored += LeanBodyMassRecord(time, null, Mass.kilograms(60.0), client.metadataFrom("other.app"))
        assertTrue(service.runInbound(1) is SyncResult.Success)
        val fat = BodyFatRecord(time.plusSeconds(2), null, Percentage(25.0), client.metadataFrom("other.app"))
        client.stored += fat
        changes("changes:1", "fat-added", fat)

        assertTrue(service.runInbound(1) is SyncResult.Success)

        val stored = openScale.measurements.values.single()
        assertEquals(25f, stored.body_fat)
        assertEquals(50f, stored.water)
        assertEquals(3f, stored.values.first { it.isBuiltin("BONE") }.value)
        assertEquals(60f, stored.values.first { it.isBuiltin("LBM") }.value)
    }

    @Test
    fun changesAcrossPagesAndSeparatedDatesAreAllImported() = runTest {
        enableImport()
        assertTrue(service.runInbound(1) is SyncResult.Success)
        storeWeight(Instant.parse("2010-01-01T12:00:00Z"))
        storeWeight(Instant.parse("2026-10-01T12:00:00Z"))
        changes("changes:1", "page-2", client.stored[0], hasMore = true)
        changes("page-2", "complete", client.stored[1])
        client.reads.clear()

        assertTrue(service.runInbound(1) is SyncResult.Success)

        assertEquals(listOf("changes:1", "page-2"), client.changeRequests)
        assertEquals(2, openScale.measurements.size)
        assertTrue(client.reads.all {
            java.time.Duration.between(it.timeRangeFilter.startTime, it.timeRangeFilter.endTime).seconds < 13
        })
    }

    @Test
    fun failedChangesPageDoesNotAdvanceCursorOrApplyPartialBatch() = runTest {
        enableImport()
        service.runInbound(1)
        storeWeight(Instant.parse("2020-01-01T12:00:00Z"))
        changes("changes:1", "page-2", client.stored.single(), hasMore = true)
        client.failingChangeToken = "page-2"

        assertTrue(service.runInbound(1) is SyncResult.Failure)
        assertTrue(openScale.inboundWrites.isEmpty())
        client.failingChangeToken = null
        changes("page-2", "done")

        assertTrue(service.runInbound(1) is SyncResult.Success)
        assertEquals(listOf("changes:1", "page-2", "changes:1", "page-2"), client.changeRequests)
        assertEquals(1, openScale.measurements.size)
    }

    @Test
    fun failedWindowReadDoesNotAcknowledgeChanges() = runTest {
        enableImport()
        service.runInbound(1)
        storeWeight(Instant.parse("2020-01-01T12:00:00Z"))
        changes("changes:1", "done", client.stored.single())
        client.readsRejected = true

        assertTrue(service.runInbound(1) is SyncResult.Failure)
        assertTrue(openScale.inboundWrites.isEmpty())
        client.readsRejected = false
        assertTrue(service.runInbound(1) is SyncResult.Success)
        assertEquals(listOf("changes:1", "changes:1"), client.changeRequests)
    }

    @Test
    fun cancellationPreservesCursorAndIsPropagated() = runTest {
        enableImport()
        service.runInbound(1)
        client.beforeChanges = { throw CancellationException("cancelled") }
        try {
            service.runInbound(1)
            org.junit.Assert.fail("Expected cancellation")
        } catch (_: CancellationException) { }
        client.beforeChanges = null

        assertTrue(service.runInbound(1) is SyncResult.Success)
        assertEquals(listOf("changes:1", "changes:1"), client.changeRequests)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun overlappingServiceInstancesConsumeCursorInOrder() = runTest {
        enableImport()
        service.runInbound(1)
        changes("changes:1", "done")
        val gate = CompletableDeferred<Unit>()
        client.beforeChanges = { if (it == "changes:1") gate.await() }
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("health-connect-test", Context.MODE_PRIVATE)
        val other = HealthConnectService(context, prefs, client).also {
            it.openScaleDataService = openScale
            it.viewModel().setConnectAvailable(true)
        }
        val first = async { service.runInbound(1) }
        runCurrent()
        val second = async { other.runInbound(1) }
        runCurrent()
        assertEquals(listOf("changes:1"), client.changeRequests)
        gate.complete(Unit)

        assertTrue(first.await() is SyncResult.Success)
        assertTrue(second.await() is SyncResult.Success)
        assertEquals(listOf("changes:1", "done"), client.changeRequests)
    }

    @Test
    fun silentlyRefusedInsertDoesNotAcknowledgeChanges() = runTest {
        enableImport()
        service.runInbound(1)
        val time = Instant.parse("2020-01-01T12:00:00Z")
        storeWeight(time)
        changes("changes:1", "done", client.stored.single())
        openScale.insertRejects += time.toEpochMilli()

        assertTrue(service.runInbound(1) is SyncResult.Failure)
        openScale.insertRejects.clear()
        assertTrue(service.runInbound(1) is SyncResult.Success)

        assertEquals(listOf("changes:1", "changes:1"), client.changeRequests)
        assertEquals(1, openScale.measurements.size)
    }

    @Test
    fun failedUpdateIsRetriedButAlreadyMatchingNoOpIsAcknowledged() = runTest {
        enableImport()
        val time = Instant.parse("2020-01-01T12:00:00Z")
        storeWeight(time)
        service.runInbound(1)
        client.stored.clear()
        storeWeight(time, 81.0)
        changes("changes:1", "updated", client.stored.single())
        openScale.writeFails = true

        assertTrue(service.runInbound(1) is SyncResult.Failure)
        openScale.writeFails = false
        assertTrue(service.runInbound(1) is SyncResult.Success)
        // A false row count is normal when the values already match, e.g. a replay after a crash.
        changes("updated", "done", client.stored.single())
        openScale.updateChangesNothing += time.toEpochMilli()
        assertTrue(service.runInbound(1) is SyncResult.Success)

        assertEquals(81f, openScale.measurements.values.single().weight)
        assertEquals(listOf("changes:1", "changes:1", "updated"), client.changeRequests)
    }

    @Test
    fun duplicateForeignWeightsAtOneTimestampAreAppliedOnceWithoutBlockingCursor() = runTest {
        enableImport()
        val time = Instant.parse("2020-01-01T12:00:00Z")
        storeWeight(time)
        client.stored += WeightRecord(time, null, Mass.kilograms(80.03), client.metadataFrom("aggregator.app"))

        assertTrue(service.runInbound(1) is SyncResult.Success)
        assertEquals(1, openScale.inboundWrites.size)
        assertEquals(1, openScale.measurements.size)
        val firstWeight = openScale.measurements.values.single().weight
        changes("changes:1", "done", *client.stored.toTypedArray())
        assertTrue(service.runInbound(1) is SyncResult.Success)
        assertEquals(2, openScale.inboundWrites.size)
        assertEquals(firstWeight, openScale.measurements.values.single().weight)
        assertTrue(service.runInbound(1) is SyncResult.Success)
        assertEquals("done", client.changeRequests.last())
    }

    @Test
    fun providerValidationNoOpDoesNotBlockLaterImports() = runTest {
        enableImport()
        val time = Instant.parse("2020-01-01T12:00:00Z")
        storeWeight(time)
        service.runInbound(1)
        val fat = BodyFatRecord(time, null, Percentage(0.0), client.metadataFrom("other.app"))
        client.stored += fat
        changes("changes:1", "done", fat)
        openScale.updateChangesNothing += time.toEpochMilli()

        assertTrue(service.runInbound(1) is SyncResult.Success)
        assertTrue(service.runInbound(1) is SyncResult.Success)

        assertEquals(null, openScale.measurements.values.single().body_fat)
        assertEquals(listOf("changes:1", "done"), client.changeRequests)
    }

    @Test
    fun failedInsertRetriesTheSameChangesToken() = runTest {
        enableImport()
        service.runInbound(1)
        storeWeight(Instant.parse("2020-01-01T12:00:00Z"))
        changes("changes:1", "done", client.stored.single())
        openScale.writeFails = true

        assertTrue(service.runInbound(1) is SyncResult.Failure)
        openScale.writeFails = false
        assertTrue(service.runInbound(1) is SyncResult.Success)

        assertEquals(listOf("changes:1", "changes:1"), client.changeRequests)
        assertEquals(1, openScale.measurements.size)
    }

    @Test
    fun expiredTokenRestartsFullImportWithTokenAcquiredBeforeRead() = runTest {
        enableImport()
        service.runInbound(1)
        client.changes["changes:1"] = ChangesResponse(emptyList(), "expired", false, true)
        storeWeight(Instant.parse("2010-01-01T12:00:00Z"))
        client.events.clear()

        assertTrue(service.runInbound(1) is SyncResult.Success)
        assertEquals("token", client.events.first())
        assertEquals(2, client.tokenRequests.size)
        assertEquals(1, openScale.measurements.size)
        assertTrue(service.runInbound(1) is SyncResult.Success)
        assertEquals("changes:2", client.changeRequests.last())
    }

    @Test
    fun manualFullSyncStillRescansAllAccessibleHistory() = runTest {
        enableImport()
        service.viewModel().setSelectedUserId(1)
        service.runInbound(1)
        storeWeight(Instant.parse("2010-01-01T12:00:00Z"))

        assertEquals(1, service.runFullSync()!!.imported.imported)

        assertEquals(2, client.tokenRequests.size)
        assertTrue(client.changeRequests.isEmpty())
    }

    @Test
    fun resettingBackendStateOrRestoringRequiredPermissionsRestartsHistoryImport() = runTest {
        enableImport()
        service.runInbound(1)
        service.resetSyncState()
        assertTrue(service.runInbound(1) is SyncResult.Success)
        assertEquals(2, client.tokenRequests.size)
        client.grantedPermissions -= HealthPermission.getReadPermission(WeightRecord::class)
        assertTrue(service.runInbound(1) is SyncResult.Failure)
        client.grantedPermissions += HealthPermission.getReadPermission(WeightRecord::class)
        assertTrue(service.runInbound(1) is SyncResult.Success)
        assertEquals(3, client.tokenRequests.size)
    }

    @Test
    fun cursorSurvivesServiceRecreation_butChangingTargetOrHistoryAccessStartsFullImport() = runTest {
        enableImport()
        storeWeight(Instant.parse("2010-01-01T12:00:00Z"))
        service.runInbound(1)
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("health-connect-test", Context.MODE_PRIVATE)
        service = HealthConnectService(context, prefs, client).also {
            it.openScaleDataService = openScale
            it.viewModel().setConnectAvailable(true)
        }

        assertTrue(service.runInbound(1) is SyncResult.Success)
        assertEquals(1, client.tokenRequests.size)
        assertTrue(service.runInbound(2) is SyncResult.Success)
        assertEquals(2, client.tokenRequests.size)
        assertEquals(setOf(1, 2), openScale.measurements.values.map { it.userId }.toSet())
        client.grantedPermissions -= historyPermission
        assertTrue(service.runInbound(2) is SyncResult.Success)
        client.grantedPermissions += historyPermission
        assertTrue(service.runInbound(2) is SyncResult.Success)
        assertEquals(4, client.tokenRequests.size)
        prefs.edit().putString(OpenScaleViewModel.PACKAGE_NAME, "com.health.openscale.debug").commit()
        assertTrue(service.runInbound(2) is SyncResult.Success)
        assertEquals(5, client.tokenRequests.size)
    }

    @Test
    fun ownChangesAndDeletionsAdvanceTokenWithoutReadingOrDeletingHistory() = runTest {
        enableImport()
        val time = Instant.parse("2020-01-01T12:00:00Z")
        storeWeight(time)
        service.runInbound(1)
        val own = WeightRecord(time, null, Mass.kilograms(80.0),
            client.metadataFrom(RuntimeEnvironment.getApplication().packageName))
        client.changes["changes:1"] = ChangesResponse(
            listOf(UpsertionChange(own), DeletionChange("foreign-id")), "done", false, false)
        client.reads.clear()
        openScale.inboundWrites.clear()

        assertTrue(service.runInbound(1) is SyncResult.Success)
        assertTrue(service.runInbound(1) is SyncResult.Success)

        assertTrue(client.reads.isEmpty())
        assertTrue(openScale.inboundWrites.isEmpty())
        assertEquals(1, openScale.measurements.size)
        assertEquals(listOf("changes:1", "done"), client.changeRequests)
    }

    @Test
    fun laterPageFailureDoesNotWritePartialHistoryIntoOpenScale() = runTest {
        enableImport()
        client.pageSize = 1
        client.failingPageToken = "cursor:1"
        storeWeight(Instant.parse("2010-01-01T12:00:00Z"))
        storeWeight(Instant.parse("2026-10-01T12:00:00Z"))

        val result = service.runInbound(1)

        assertTrue(result is SyncResult.Failure)
        assertEquals("scripted page failure", (result as SyncResult.Failure).cause?.message)
        assertEquals(listOf(null, "cursor:1"), client.reads.map { it.pageToken })
        assertTrue(openScale.inboundWrites.isEmpty())
        assertTrue(openScale.measurements.isEmpty())
    }

    @Test
    fun initialImportSetupRequestsHistoryAlongsideRegularPermissions() = runTest {
        service.viewModel().setSyncDirection(SyncDirection.BOTH)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
        service.registerActivityResultLauncher(activity)

        service.requestPermissions()

        val requested = shadowOf(activity).lastRequestedPermission
        assertEquals(writePermissions + readPermissions + historyPermission, requested.requestedPermissions.toSet())
    }

    @Test
    fun failingHistoryFeatureQueryDoesNotDisableNormalSync() = runTest {
        service.viewModel().setSyncDirection(SyncDirection.BOTH)
        client.featureQueryThrows = true
        client.grantedPermissions = writePermissions + readPermissions

        assertTrue(service.checkAllPermissionsGranted() is SyncResult.Success)
        assertTrue(service.viewModel().allPermissionsGranted.value)
        assertFalse(historyPermission in service.permissionsToRequest())
    }

    @Test
    fun unchangedImportsAreNotWrittenBackToHealthConnect() {
        // Another app's record cannot be changed, only copied next to it (#51).
        assertFalse(service.exportsUnchangedImports)
    }

    @Test
    fun exportOnlyDoesNotRequestReadOrHistoryAccess() = runTest {
        service.viewModel().setSyncDirection(SyncDirection.EXPORT)
        client.grantedPermissions = writePermissions

        assertTrue(service.checkAllPermissionsGranted() is SyncResult.Success)
        assertEquals(writePermissions, service.permissionsToRequest())
    }

    @Test
    fun decliningHistoryStillAllowsNormalSync() = runTest {
        service.viewModel().setSyncDirection(SyncDirection.BOTH)
        client.grantedPermissions = writePermissions + readPermissions

        assertTrue(service.checkAllPermissionsGranted() is SyncResult.Success)
        assertTrue(service.viewModel().allPermissionsGranted.value)
        assertEquals(writePermissions + readPermissions + historyPermission, service.permissionsToRequest())
    }

    @Test
    fun unsupportedHistoryIsNotRequested_andNormalImportStillWorks() = runTest {
        service.viewModel().setSyncDirection(SyncDirection.IMPORT)
        client.historyAvailable = false
        client.grantedPermissions = writePermissions + readPermissions

        assertTrue(service.checkAllPermissionsGranted() is SyncResult.Success)
        assertFalse(historyPermission in service.permissionsToRequest())
    }

    @Test
    fun revokingHistoryDoesNotDisableNormalSync() = runTest {
        service.viewModel().setSyncDirection(SyncDirection.BOTH)
        client.grantedPermissions = writePermissions + readPermissions + historyPermission
        assertTrue(service.checkAllPermissionsGranted() is SyncResult.Success)

        client.grantedPermissions = writePermissions + readPermissions

        assertTrue(service.checkAllPermissionsGranted() is SyncResult.Success)
        assertTrue(service.viewModel().allPermissionsGranted.value)
    }

    @Test
    fun historyDoesNotReplaceTheRequiredDataTypePermissions() = runTest {
        service.viewModel().setSyncDirection(SyncDirection.BOTH)
        client.grantedPermissions = writePermissions + historyPermission

        assertTrue(service.checkAllPermissionsGranted() is SyncResult.Failure)
        assertFalse(service.viewModel().allPermissionsGranted.value)
    }
}
