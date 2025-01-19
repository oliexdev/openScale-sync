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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.lifecycleScope
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
    private lateinit var healthConnectClient: HealthConnectClient
    private lateinit var healthConnectRequestPermissions : ActivityResultLauncher<Set<String>>

    private val requiredPermissions = setOf(
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(BodyWaterMassRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
    )

    private val healthConnectPermissionContract =
        PermissionController.createRequestPermissionResultContract()

    override suspend fun init() {
        healthConnectClient = detectHealthConnect()!!
    }

    override fun viewModel(): ViewModelInterface {
        return viewModel
    }

    override suspend fun sync(measurements: List<OpenScaleMeasurement>) {
        checkAllPermissionsGranted()
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            setInfoMessage("Health Connect full sync")
            healthConnectSync.fullSync(measurements)
        }
    }

    override suspend fun insert(measurement: OpenScaleMeasurement) {
        checkAllPermissionsGranted()
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            setInfoMessage("Health Connect insert")
            healthConnectSync.insert(measurement)
        }
    }

    override suspend fun delete(date: Date) {
        checkAllPermissionsGranted()
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            setInfoMessage("Health Connect delete")
            healthConnectSync.delete(date)
        }
    }

    override suspend fun clear() {
        checkAllPermissionsGranted()
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            setInfoMessage("Health Connect clear")
            healthConnectSync.clear()
        }
    }

    override suspend fun update(measurement: OpenScaleMeasurement) {
        checkAllPermissionsGranted()
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            setInfoMessage("Health Connect update")
            healthConnectSync.update(measurement)
        }
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

    suspend fun checkAllPermissionsGranted() {
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        if (granted.containsAll(requiredPermissions)) {
            viewModel.setAllPermissionsGranted(true)
            healthConnectSync = HealthConnectSync(healthConnectClient)
            setDebugMessage("health connect permissions already granted")
        } else {
            setDebugMessage("health connect permissions not all granted")
            viewModel.setAllPermissionsGranted(false)
        }
    }

    suspend fun requestPermissions() {
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        if (granted.containsAll(requiredPermissions)) {
            setDebugMessage("health connect permissions already granted")
        } else {
            healthConnectRequestPermissions.launch(requiredPermissions)
        }
    }

    suspend fun detectHealthConnect(): HealthConnectClient? {
        setDebugMessage("Detect health connect")
        val availabilityStatus = HealthConnectClient.getSdkStatus(context)

        if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE) {
            setErrorMessage("HealthConnect not available")
            viewModel.setConnectAvailable(false)
            return null
        }

        if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
            setErrorMessage("HealthConnect not installed or has to be updated. Consider redirecting to appstore")
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
                    Text("Health Connect is not available on this device.")
                    Button(enabled = viewModel.syncEnabled.value,
                        onClick = {
                        openAppStore(activity)
                    }) {
                        Text(text = "Get HealthConnect")
                    }
                }
            }
            if (!viewModel.allPermissionsGranted.value) {
                Column (
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Permissions to HealthConnect not granted")
                    Button(enabled = viewModel.syncEnabled.value,
                        onClick = {
                        activity.lifecycleScope.launch {
                            requestPermissions()
                        }
                    }) {
                        Text(text = "Request HealthConnect permissions")
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