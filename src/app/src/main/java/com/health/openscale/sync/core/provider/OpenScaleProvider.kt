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
package com.health.openscale.sync.core.provider

import androidx.core.net.toUri
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager.PERMISSION_GRANTED
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.health.openscale.sync.R
import com.health.openscale.sync.core.model.OpenScaleViewModel
import com.health.openscale.sync.core.model.ViewModelInterface
import timber.log.Timber

class OpenScaleProvider (
    private val context: Context,
    openScaleDataService : OpenScaleDataProvider,
    sharedPreferences: SharedPreferences
) {
    private val viewModel: OpenScaleViewModel = OpenScaleViewModel(sharedPreferences)//ViewModelProvider(context)[OpenScaleViewModel::class.java]
    private val requiredPermissions = sharedPreferences.getString(OpenScaleViewModel.PACKAGE_NAME, "com.health.openscale") + ".READ_WRITE_DATA"
    private lateinit var requestPermission : ActivityResultLauncher<String>

     fun init() {
        checkPermissionGranted()
    }

    fun viewModel(): ViewModelInterface {
        return viewModel
    }

    fun checkPermissionGranted() {
        if (ContextCompat.checkSelfPermission(context, requiredPermissions) == PERMISSION_GRANTED) {
            viewModel.setAllPermissionsGranted(true)
        } else {
            viewModel.setAllPermissionsGranted(false)
        }
    }

    fun registerActivityResultLauncher(activity: ComponentActivity) {
        requestPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                checkPermissionGranted()

                if (isGranted) {
                    Timber.d("openScale permission is granted")
                } else {
                    Timber.d("openScale permission is not granted")
                }
            }
    }

    fun requestPermissions() {
        requestPermission.launch(requiredPermissions)
    }

    @Composable
    fun ComposeSettings(activity: ComponentActivity) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // openScale availability / permission prompts only. The per-user selection now lives in
            // each single-user backend's settings (HealthConnect/Wger); multi-user backends sync all.
            if (!viewModel.connectAvailable.value) {
                Text(text = stringResource(id = R.string.open_scale_not_available_error))
                Button(onClick = { openAppStore(activity) }) {
                    Text(stringResource(id = R.string.open_scale_get_open_scale_button))
                }
            } else if (!viewModel.allPermissionsGranted.value) {
                Text(text = stringResource(id = R.string.open_scale_permission_not_granted))
                Button(onClick = { requestPermissions() }) {
                    Text(stringResource(id = R.string.open_scale_request_permissions_button))
                }
            }
        }
    }

    private fun openAppStore(activity: ComponentActivity) {
        val packageName = "com.health.openscale.pro"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = "market://details?id=$packageName".toUri()
            setPackage("com.health.openscale.pro") // Google Play Store package
        }

        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW).apply {
                data = "https://github.com/oliexdev/openScale".toUri()
            }
            activity.startActivity(webIntent)
        }
    }

}