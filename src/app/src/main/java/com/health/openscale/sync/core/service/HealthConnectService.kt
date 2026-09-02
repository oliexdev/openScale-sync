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

import androidx.core.net.toUri
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.health.openscale.sync.gui.components.SyncDirectionSelector
import com.health.openscale.sync.gui.components.UserScopeSection
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.lifecycleScope
import com.health.openscale.sync.R
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.model.HealthConnectViewModel
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.sync.HealthConnectSync
import com.health.openscale.sync.gui.components.LocalSnackbar
import com.health.openscale.sync.gui.components.SyncConnectButton
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Date

class HealthConnectService(
    private val context: Context,
    sharedPreferences: SharedPreferences
) : ServiceInterface(context) {
    private val viewModel: HealthConnectViewModel = HealthConnectViewModel(sharedPreferences)//ViewModelProvider(context)[HealthConnectViewModel::class.java]
    private lateinit var healthConnectSync : HealthConnectSync
    private var healthConnectClient: HealthConnectClient? = null
    private lateinit var healthConnectRequestPermissions : ActivityResultLauncher<Set<String>>

    /**
     * Writing openScale's measurements into Health Connect is what this backend exists for, so
     * these are unconditional — without them the service has nothing to offer.
     */
    private val writePermissions = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(BodyWaterMassRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(BoneMassRecord::class),
        HealthPermission.getWritePermission(BasalMetabolicRateRecord::class),
        HealthPermission.getWritePermission(LeanBodyMassRecord::class),
    )

    /**
     * Reading serves the inbound half of the two-way sync ([readInbound]) and nothing else, so it is
     * requested and checked only once the user has picked Import/Both — an export-only setup never
     * asks for read access. Health Connect's "Minimum Scope" policy requires exactly that coupling:
     * a permission has to follow the feature that needs it, not the backend as a whole.
     */
    private val readPermissions = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyWaterMassRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(BoneMassRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
    )

    /** What the current direction setting actually needs granted. */
    private fun neededPermissions(): Set<String> =
        if (importEnabled()) writePermissions + readPermissions else writePermissions

    private val healthConnectPermissionContract =
        PermissionController.createRequestPermissionResultContract()

    override suspend fun connect() {
        detectHealthConnect()
    }

    override fun viewModel(): ViewModelInterface {
        return viewModel
    }

    // HealthConnect can read foreign records → bidirectional.
    override val supportsInbound: Boolean get() = true

    // HealthConnect writes the whole batch in one insertRecords call. Updates take the same path:
    // the write is a clientRecordId upsert, so a batch of updates is a batch of writes — no reason
    // to fall back to the per-item default loop (a forced full sync would otherwise be one round
    // trip per measurement).
    override suspend fun insertAll(measurements: List<OpenScaleMeasurement>): BulkResult {
        val perm = checkAllPermissionsGranted()
        if (perm is SyncResult.Failure) return BulkResult(emptyList(), perm)
        // Drop what Health Connect would refuse BEFORE building the batch: its record constructors
        // throw on an out-of-range value, and insertRecords() is all-or-nothing, so a single bad
        // measurement would otherwise take the whole history down with it (issues #34/#35).
        val (skipped, valid) = measurements.partition { m ->
            healthConnectSync.rejectionReason(m)?.also { reason ->
                Timber.w("Health Connect: skipping measurement id=%d date=%s (%s)", m.id, m.date, reason)
            } != null
        }
        if (valid.isEmpty()) return BulkResult(emptyList(), null, skipped)
        return when (val r = healthConnectSync.fullSync(valid)) {
            is SyncResult.Success -> BulkResult(valid, null, skipped)
            is SyncResult.Failure -> BulkResult(emptyList(), r, skipped)
        }
    }

    override suspend fun updateAll(measurements: List<OpenScaleMeasurement>): BulkResult =
        insertAll(measurements)

    override suspend fun insert(measurement: OpenScaleMeasurement) : SyncResult<Unit> =
        checkAllPermissionsGranted().let {
            if (it is SyncResult.Failure) it else healthConnectSync.insert(measurement)
        }

    override suspend fun delete(userId: Int, date: Date) : SyncResult<Unit> =
        checkAllPermissionsGranted().let {
            if (it is SyncResult.Failure) it else healthConnectSync.delete(date)
        }

    override suspend fun clear(userId: Int) : SyncResult<Unit> =
        checkAllPermissionsGranted().let {
            if (it is SyncResult.Failure) it else healthConnectSync.clear()
        }

    override suspend fun update(measurement: OpenScaleMeasurement) : SyncResult<Unit> =
        checkAllPermissionsGranted().let {
            if (it is SyncResult.Failure) it else healthConnectSync.update(measurement)
        }

    /**
     * Inbound source (bidirectional): read what OTHER apps wrote to Health Connect. Echo is
     * prevented via dataOrigin (own writes excluded). The base [runInbound] pipeline reconciles the
     * results into openScale.
     *
     * Fat and water ride openScale's fixed columns; bone and lean mass have none, so they go through
     * the generic value payload — which is also why they stay in kg, the unit openScale keeps those
     * two types in.
     */
    override suspend fun readInbound(userId: Int, sinceMs: Long): List<InboundMeasurement> {
        val perm = checkAllPermissionsGranted()
        if (perm is SyncResult.Failure) throw IllegalStateException("Health Connect permissions not granted")
        return healthConnectSync.readInboundReadings(context.packageName, sinceMs).map { reading ->
            InboundMeasurement(
                timeMs = reading.timeMs,
                weightKg = reading.weightKg,
                fatPct = reading.fatPct,
                waterPct = reading.waterPct,
                valuesJson = genericValues(
                    "BONE" to reading.boneKg,
                    "LBM" to reading.leanKg
                )
            )
        }
    }

    /** openScale's generic value format, or null when there is nothing to put in it. */
    private fun genericValues(vararg values: Pair<String, Float?>): String? {
        val present = values.filter { it.second != null }
        if (present.isEmpty()) return null
        return present.joinToString(prefix = "[", postfix = "]") { (key, value) ->
            // The MIN_API_VERSION gate guarantees a v3 peer, which matches by identity.
            """{"identity":"builtin.${key.lowercase()}","value":$value}"""
        }
    }

    override fun registerActivityResultLauncher(activity: ComponentActivity) {
        healthConnectRequestPermissions = activity.registerForActivityResult(healthConnectPermissionContract) { granted ->
            activity.lifecycle.coroutineScope.launch {
                checkAllPermissionsGranted()

                setDebugMessage(granted.toString())
                if (granted.containsAll(neededPermissions())) {
                    setDebugMessage("health connect permissions granted")
                } else {
                    setDebugMessage("health connect lack of required permissions")
                    viewModel.setAllPermissionsGranted(false)
                }
            }
        }
    }

    suspend fun checkAllPermissionsGranted() : SyncResult<Unit> {
        val currentClient = healthConnectClient
        if (currentClient == null) {
            viewModel.setAllPermissionsGranted(false)
            viewModel.setConnectAvailable(false)
            return SyncResult.Failure(SyncResult.ErrorType.API_ERROR, "Health Connect is not available")
        }

        if (!viewModel.connectAvailable.value) {
            viewModel.setConnectAvailable(false)
            return SyncResult.Failure(SyncResult.ErrorType.API_ERROR, "Health Connect is not available")
        }

        try {
            val granted = currentClient.permissionController.getGrantedPermissions()
            val needed = neededPermissions()

            if (granted.containsAll(needed)) {
                viewModel.setAllPermissionsGranted(true)

                if (!this::healthConnectSync.isInitialized) {
                    healthConnectSync = HealthConnectSync(currentClient)
                    clearErrorMessage()
                    setDebugMessage("HealthConnectSync initialized")
                }

                setDebugMessage("All Health Connect permissions are granted")
                return SyncResult.Success(Unit)
            } else {
                viewModel.setAllPermissionsGranted(false)
                return SyncResult.Failure(SyncResult.ErrorType.API_ERROR, "Not all required Health Connect permissions are granted. Granted: $granted, Required: $needed")
            }
        } catch (e: Exception) {
            viewModel.setAllPermissionsGranted(false)
            return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, null, e)
        }
    }

    suspend fun requestPermissions() {
        val currentClient = healthConnectClient
        if (currentClient == null) {
            viewModel.setConnectAvailable(false)
            return
        }

        if (!this::healthConnectRequestPermissions.isInitialized) {
            setErrorMessage(SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, "ActivityResultLauncher not initialized"))
            return
        }

        try {
            if (checkAllPermissionsGranted() is SyncResult.Success) {
                return
            }

            healthConnectRequestPermissions.launch(neededPermissions())
        } catch (e: Exception) {
            setErrorMessage(SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, null, e))
        }
    }

    suspend fun detectHealthConnect(): HealthConnectClient? {
        try {
            val availabilityStatus = HealthConnectClient.getSdkStatus(context)

            when(availabilityStatus) {
                HealthConnectClient.SDK_AVAILABLE -> {
                    viewModel.setConnectAvailable(true)
                    healthConnectClient = HealthConnectClient.getOrCreate(context)
                    checkAllPermissionsGranted()
                    return healthConnectClient
                }
                else -> {
                    setErrorMessage(SyncResult.Failure(SyncResult.ErrorType.API_ERROR, "Health Connect is not available"))
                    viewModel.setConnectAvailable(false)
                    viewModel.setAllPermissionsGranted(false)
                    healthConnectClient = null
                    return null
                }

            }
        } catch (e:Exception) {
            setErrorMessage(SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, null, e))
            viewModel.setConnectAvailable(false)
            viewModel.setAllPermissionsGranted(false)
            healthConnectClient = null
            return null
        }
    }

    private suspend fun testConnection() {
        when (val result = checkAllPermissionsGranted()) {
            is SyncResult.Success -> setInfoMessage(context.getString(R.string.health_connect_connected_text))
            is SyncResult.Failure -> setErrorMessage(result)
        }
    }

    @Composable
    override fun ComposeSettings(activity: ComponentActivity) {
        val showMessage = LocalSnackbar.current
        val ready = viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value
        DetailScaffold(
            activity = activity,
            showActions = ready,
            testConnecting = false,
            onTest = {
                activity.lifecycleScope.launch {
                    testConnection()
                    if (viewModel.errorMessage.value.isNullOrEmpty())
                        showMessage(context.getString(R.string.service_connection_successful))
                }
            }
        ) {
            // Single-user backend: pick which openScale user this destination receives.
            val osUsers = remember { openScaleDataService.getUsers() }
            UserScopeSection(
                isMultiUser = isMultiUser,
                users = osUsers,
                selectedUserId = viewModel.selectedUserId.value,
                onUserSelected = { viewModel.setSelectedUserId(it.id) },
                enabled = viewModel.syncEnabled.value
            )
            // Per-backend direction (export / import / both). Inbound is pulled by the global Sync button.
            // Switching it changes which permissions are needed (reads only for import/both), so the
            // grant state is re-evaluated right away — that is what surfaces the request button below.
            SyncDirectionSelector(
                current = viewModel.syncDirection.value,
                onChange = {
                    viewModel.setSyncDirection(it)
                    activity.lifecycleScope.launch { checkAllPermissionsGranted() }
                },
                enabled = viewModel.syncEnabled.value,
                serviceName = viewModel.getName()
            )
            if (!viewModel.connectAvailable.value) {
                Text(stringResource(id = R.string.health_connect_not_available_text))
                SyncConnectButton(
                    text = stringResource(id = R.string.health_connect_get_health_connect_button),
                    connectingText = stringResource(id = R.string.health_connect_get_health_connect_button),
                    connecting = false,
                    enabled = viewModel.syncEnabled.value,
                    onClick = { openAppStore(activity) }
                )
            }
            if (!viewModel.allPermissionsGranted.value) {
                // Prominent disclosure: Health Connect policy wants the what/why/how stated in the
                // app, with an affirmative action, BEFORE the system prompt appears — the rationale
                // screen does not count, since it sits behind a link inside that very prompt.
                var showDisclosure by remember { mutableStateOf(false) }

                Text(stringResource(id = R.string.health_connect_permission_not_granted))
                SyncConnectButton(
                    text = stringResource(id = R.string.health_connect_request_permissions_button),
                    connectingText = stringResource(id = R.string.health_connect_request_permissions_button),
                    connecting = false,
                    enabled = viewModel.syncEnabled.value,
                    onClick = { showDisclosure = true }
                )

                if (showDisclosure) {
                    AlertDialog(
                        onDismissRequest = { showDisclosure = false },
                        title = { Text(stringResource(id = R.string.disclosure_title)) },
                        text = {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(stringResource(id = R.string.disclosure_write))
                                // Only named when it is actually about to be requested.
                                if (importEnabled()) {
                                    Text(stringResource(id = R.string.disclosure_read))
                                }
                                Text(stringResource(id = R.string.disclosure_how))
                                // Same link the rationale screen carries, so the full policy is one
                                // tap away at the moment consent is actually given.
                                val linkText = stringResource(id = R.string.rationale_privacy_link)
                                val url = stringResource(id = R.string.rationale_privacy_url)
                                Text(buildAnnotatedString {
                                    withStyle(
                                        SpanStyle(
                                            color = MaterialTheme.colorScheme.primary,
                                            textDecoration = TextDecoration.Underline
                                        )
                                    ) {
                                        append(linkText)
                                    }
                                    addLink(LinkAnnotation.Url(url), 0, linkText.length)
                                })
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showDisclosure = false
                                activity.lifecycleScope.launch { requestPermissions() }
                            }) { Text(stringResource(id = R.string.disclosure_accept)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDisclosure = false }) {
                                Text(stringResource(id = R.string.disclosure_decline))
                            }
                        }
                    )
                }
            }
        }
    }

    private fun openAppStore(activity: ComponentActivity) {
        val packageName = "com.google.android.apps.healthdata"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = "market://details?id=$packageName".toUri()
            setPackage("com.google.android.apps.healthdata") // Google Play Store package
        }

        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW).apply {
                data = "https://play.google.com/store/apps/details?id=$packageName".toUri()
            }
            activity.startActivity(webIntent)
        }
    }
}