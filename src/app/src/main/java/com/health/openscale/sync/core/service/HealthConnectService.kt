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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
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
import java.util.Date

class HealthConnectService(
    private val context: Context,
    sharedPreferences: SharedPreferences
) : ServiceInterface(context) {
    private val viewModel: HealthConnectViewModel = HealthConnectViewModel(sharedPreferences)//ViewModelProvider(context)[HealthConnectViewModel::class.java]
    private lateinit var healthConnectSync : HealthConnectSync
    private var healthConnectClient: HealthConnectClient? = null
    private lateinit var healthConnectRequestPermissions : ActivityResultLauncher<Set<String>>

    private val requiredPermissions = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(BodyWaterMassRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
    )

    private val healthConnectPermissionContract =
        PermissionController.createRequestPermissionResultContract()

    override suspend fun doInit() {
        detectHealthConnect()
    }

    override fun viewModel(): ViewModelInterface {
        return viewModel
    }

    override suspend fun sync(measurements: List<OpenScaleMeasurement>) : SyncResult<Unit> {
        val permissionResult = checkAllPermissionsGranted()
        if (permissionResult is SyncResult.Failure) {
            return permissionResult
        }

        return healthConnectSync.fullSync(measurements)
    }

    override suspend fun doInsert(measurement: OpenScaleMeasurement) : SyncResult<Unit> =
        checkAllPermissionsGranted().let {
            if (it is SyncResult.Failure) it else healthConnectSync.insert(measurement)
        }

    override suspend fun doDelete(date: Date) : SyncResult<Unit> =
        checkAllPermissionsGranted().let {
            if (it is SyncResult.Failure) it else healthConnectSync.delete(date)
        }

    override suspend fun doClear() : SyncResult<Unit> =
        checkAllPermissionsGranted().let {
            if (it is SyncResult.Failure) it else healthConnectSync.clear()
        }

    override suspend fun doUpdate(measurement: OpenScaleMeasurement) : SyncResult<Unit> =
        checkAllPermissionsGranted().let {
            if (it is SyncResult.Failure) it else healthConnectSync.update(measurement)
        }

    override fun registerActivityResultLauncher(activity: ComponentActivity) {
        healthConnectRequestPermissions = activity.registerForActivityResult(healthConnectPermissionContract) { granted ->
            activity.lifecycle.coroutineScope.launch {
                checkAllPermissionsGranted()

                setDebugMessage(granted.toString())
                if (granted.containsAll(requiredPermissions)) {
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

            if (granted.containsAll(requiredPermissions)) {
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
                return SyncResult.Failure(SyncResult.ErrorType.API_ERROR, "Not all required Health Connect permissions are granted. Granted: $granted, Required: $requiredPermissions")
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

            healthConnectRequestPermissions.launch(requiredPermissions)
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
                Text(stringResource(id = R.string.health_connect_permission_not_granted))
                SyncConnectButton(
                    text = stringResource(id = R.string.health_connect_request_permissions_button),
                    connectingText = stringResource(id = R.string.health_connect_request_permissions_button),
                    connecting = false,
                    enabled = viewModel.syncEnabled.value,
                    onClick = { activity.lifecycleScope.launch { requestPermissions() } }
                )
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