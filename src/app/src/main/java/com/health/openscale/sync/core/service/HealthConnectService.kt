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
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch
import java.util.Date

class HealthConnectService(
    private val context: Context,
    private val sharedPreferences: SharedPreferences
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

    override suspend fun init() {
        healthConnectClient = detectHealthConnect()
    }

    override fun viewModel(): ViewModelInterface {
        return viewModel
    }

    override suspend fun sync(measurements: List<OpenScaleMeasurement>) : SyncResult<Unit> {
        checkAllPermissionsGranted()

        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            return healthConnectSync.fullSync(measurements)
        }

        return SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)
    }

    override suspend fun insert(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
        checkAllPermissionsGranted()
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            return healthConnectSync.insert(measurement)
        }

        return SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)
    }

    override suspend fun delete(date: Date) : SyncResult<Unit> {
        checkAllPermissionsGranted()
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            return healthConnectSync.delete(date)
        }

        return SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)
    }

    override suspend fun clear() : SyncResult<Unit> {
        checkAllPermissionsGranted()
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            return healthConnectSync.clear()
        }

        return SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)
    }

    override suspend fun update(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
        checkAllPermissionsGranted()
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            return healthConnectSync.update(measurement)
        }

        return SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)
    }

    override fun registerActivityResultLauncher(activity: ComponentActivity) {
        healthConnectRequestPermissions = activity.registerForActivityResult(healthConnectPermissionContract) { granted ->
            activity.lifecycle.coroutineScope.launch { checkAllPermissionsGranted() }

            setDebugMessage(granted.toString())
            if (granted.containsAll(requiredPermissions)) {
                setDebugMessage("health connect permissions granted")
            } else {
                setDebugMessage("health connect lack of required permissions")
            }
        }
    }

    suspend fun checkAllPermissionsGranted() : Boolean {
        if (healthConnectClient != null) {
            val granted = healthConnectClient!!.permissionController.getGrantedPermissions()
            if (granted.containsAll(requiredPermissions)) {
                viewModel.setAllPermissionsGranted(true)
                healthConnectSync = HealthConnectSync(healthConnectClient!!)
                setDebugMessage("health connect permissions already granted")
                return true
            } else {
                setDebugMessage("health connect permissions not all granted")
                viewModel.setAllPermissionsGranted(false)
            }
        }

        return false
    }

    suspend fun requestPermissions() {
        if (healthConnectClient != null) {
            val granted = healthConnectClient!!.permissionController.getGrantedPermissions()
            if (granted.containsAll(requiredPermissions)) {
                setDebugMessage("health connect permissions already granted")
            } else {
                healthConnectRequestPermissions.launch(requiredPermissions)
            }
        }
    }

    suspend fun detectHealthConnect(): HealthConnectClient? {
        setDebugMessage("Detect health connect")
        val availabilityStatus = HealthConnectClient.getSdkStatus(context)

        if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE) {
            setErrorMessage(context.getString(R.string.health_connect_not_available_text))
            viewModel.setConnectAvailable(false)
            return null
        }

        if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
            setErrorMessage(context.getString(R.string.health_connect_not_installed_or_update_required_error))
            viewModel.setConnectAvailable(false)
            return null
        }

        setDebugMessage("Health Connect available")

        viewModel.setConnectAvailable(true)

        healthConnectClient = HealthConnectClient.getOrCreate(context)

        checkAllPermissionsGranted()

        return healthConnectClient

    }

    @Composable
    override fun composeSettings(activity: ComponentActivity) {
        Column (
            modifier = Modifier.fillMaxWidth()
        ) {
            super.composeSettings(activity)

            if (!viewModel.connectAvailable.value) {
                Column (
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(id = R.string.health_connect_not_available_text))
                    Button(enabled = viewModel.syncEnabled.value,
                        onClick = {
                        openAppStore(activity)
                    }) {
                        Text(text = stringResource(id = R.string.health_connect_get_health_connect_button))
                    }
                }
            }
            if (!viewModel.allPermissionsGranted.value) {
                Column (
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(id = R.string.health_connect_permission_not_granted))
                    Button(enabled = viewModel.syncEnabled.value,
                        onClick = {
                        activity.lifecycleScope.launch {
                            requestPermissions()
                        }
                    }) {
                        Text(text = stringResource(id = R.string.health_connect_request_permissions_button))
                    }
                }
            }
        }
    }

    private fun openAppStore(activity: ComponentActivity) {
        val packageName = "com.google.android.apps.healthdata"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("market://details?id=$packageName")
            setPackage("com.google.android.apps.healthdata") // Google Play Store package
        }

        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
                val webIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
            }
            activity.startActivity(webIntent)
        }
    }
}