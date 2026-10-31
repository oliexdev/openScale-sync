package com.health.openscale.sync.core.service

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.units.Mass
import com.health.openscale.sync.core.model.SyncDirection
import com.health.openscale.sync.core.sync.HealthConnectSyncTest.FakeHealthConnectClient
import kotlinx.coroutines.test.runTest
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

        val second = service.runInbound(1)

        assertTrue(first is SyncResult.Success && second is SyncResult.Success)
        assertEquals(1, (first as SyncResult.Success).data.imported)
        assertEquals(0, (second as SyncResult.Success).data.imported)
        assertEquals(time.toEpochMilli(), openScale.measurements.values.single().date.time)
        assertEquals(1, openScale.inboundWrites.count { it.startsWith("insert@") })
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
