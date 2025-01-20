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

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.health.openscale.sync.core.datatypes.OpenScaleUser
import com.health.openscale.sync.core.model.OpenScaleViewModel
import com.health.openscale.sync.core.model.ViewModelInterface
import timber.log.Timber

class OpenScaleProvider (
    private val context: Context,
    private val openScaleDataService : OpenScaleDataProvider,
    private val sharedPreferences: SharedPreferences
) {
    private val viewModel: OpenScaleViewModel = OpenScaleViewModel(sharedPreferences)//ViewModelProvider(context)[OpenScaleViewModel::class.java]
    private val requiredPermissions = sharedPreferences.getString("packageName", "com.health.openscale") + ".READ_WRITE_DATA"
    private lateinit var requestPermission : ActivityResultLauncher<String>

     fun init() {
        checkPermissionGranted()

        if (viewModel.allPermissionsGranted.value) {
            viewModel.setOpenScaleUsers(openScaleDataService.getUsers())
            viewModel.selectOpenScaleUser(getSelectedUser())
        }
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
                    viewModel.setOpenScaleUsers(openScaleDataService.getUsers())
                    viewModel.selectOpenScaleUser(getSelectedUser())
                } else {
                    Timber.d("openScale permission is not granted")
                }
            }
    }

    fun requestPermissions() {
        requestPermission.launch(requiredPermissions)
    }

    fun getSelectedUser(): OpenScaleUser {
        val selectedUserId = openScaleDataService.getSavedSelectedUserId()
        try {
            if (selectedUserId != null) {
                return viewModel.openScaleUsers.value.first { user -> user.id == selectedUserId }
            } else {
                return viewModel.openScaleUsers.value.first()
            }
        } catch (e: NoSuchElementException) {
            viewModel.setErrorMessage("Cannot find any openScale user")
        }

        return OpenScaleUser(Int.MAX_VALUE, "<none found>")
    }

    @Composable
    fun composeSettings(activity: ComponentActivity) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
                UserSelect()
            } else {
                if (!viewModel.connectAvailable.value) {
                    Text(text = "openScale is not available on this device.")
                    Button(onClick = {
                        openAppStore(activity)
                    }){
                        Text("Get openScale")
                    }
                }
                else if (!viewModel.allPermissionsGranted.value)  {
                    Text(text = "Permission to openScale not granted")
                    Button(onClick = {
                        requestPermissions()
                    }){
                        Text("Request openScale permission")
                    }
                }
            }
        }
    }

    private fun openAppStore(activity: ComponentActivity) {
        val packageName = "com.health.openscale.pro"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("market://details?id=$packageName")
            setPackage("com.health.openscale.pro") // Google Play Store package
        }

        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://github.com/oliexdev/openScale")
            }
            activity.startActivity(webIntent)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun UserSelect() {
        var expanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(
            modifier = Modifier.fillMaxWidth(),
            expanded = expanded,
            onExpandedChange = {
                expanded = !expanded
            }
        ) {
            val selectedOpenScale by viewModel.openScaleSelectedUser.observeAsState()

            TextField(
                label = { Text("openScale user") },
                value = selectedOpenScale?.username ?: "",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                    .fillMaxWidth()
            )

            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                viewModel.openScaleUsers.value.forEach { user ->
                    DropdownMenuItem(
                        text = {
                            Text(user.username)
                        },
                        onClick = {
                            viewModel.selectOpenScaleUser(user)
                            openScaleDataService.saveSelectedUserId(user.id)
                            expanded = false
                        }
                    )
                }

            }
        }
    }
}