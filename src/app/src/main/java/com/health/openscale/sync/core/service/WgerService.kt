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
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.model.WgerViewModel
import com.health.openscale.sync.core.sync.WgerSync
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Date

class WgerService(
    private val context: Context,
    private val sharedPreferences: SharedPreferences
) : ServiceInterface(context) {
    private val viewModel: WgerViewModel = WgerViewModel(sharedPreferences)//ViewModelProvider(context)[MQTTViewModel::class.java]
    private lateinit var wgerSync : WgerSync
    private lateinit var wgerRetrofit: Retrofit

    override suspend fun init() {
        connectWger()
    }

    override fun viewModel(): ViewModelInterface {
        return viewModel
    }

    override suspend fun sync(measurements: List<OpenScaleMeasurement>) {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            wgerSync.fullSync(measurements)
        }
    }
    override suspend fun insert(measurement: OpenScaleMeasurement) {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            wgerSync.insert(measurement)
        }
    }

    override suspend fun delete(date: Date) {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            wgerSync.delete(date)
        }
    }

    override suspend fun clear() {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            wgerSync.clear()
        }
    }

    override suspend fun update(measurement: OpenScaleMeasurement) {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            wgerSync.update(measurement)
        }
    }

    private fun connectWger() {
        try {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val newRequest = chain.request().newBuilder()
                    .addHeader(
                        "Authorization",
                        "Token " + viewModel.wgerApiToken.value
                    )
                    .build()
                chain.proceed(newRequest)
            }.build()

            wgerRetrofit = Retrofit.Builder()
                .client(client)
                .baseUrl(viewModel.wgerServer.value!!)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            wgerSync = WgerSync(wgerRetrofit)
            viewModel.setAllPermissionsGranted(true)
            viewModel.setConnectAvailable(true)
            viewModel.setErrorMessage("")
        } catch (ex: Exception) {
            setErrorMessage("$ex.message")
        }
    }

    @Composable
    override fun composeSettings(activity: ComponentActivity) {
        Column (
            modifier = Modifier.fillMaxWidth()
        ) {
            super.composeSettings(activity)

            Column (
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val serverNameState by viewModel.wgerServer.observeAsState("")

                OutlinedTextField(
                    enabled = viewModel.syncEnabled.value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    value = serverNameState,
                    onValueChange = {
                        viewModel.setWgerServer(it)
                    },
                    label = { Text("Server Name") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text
                    )
                )
            }
            Column (
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val wgerApiTokenState by viewModel.wgerApiToken.observeAsState("")

                OutlinedTextField(
                    enabled = viewModel.syncEnabled.value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    value = wgerApiTokenState,
                    onValueChange = {
                        viewModel.setWgerApiToken(it)
                    },
                    label = { Text("Wger Api Token") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text
                    )
                )
            }
            Column (
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val errorMessage by viewModel.errorMessage.observeAsState()

                if (errorMessage != null && viewModel.syncEnabled.value) {
                    Text("$errorMessage", color = Color.Red)
                }
                Button(onClick = {
                        activity.lifecycleScope.launch {
                            connectWger()
                        }
                    },
                    enabled = viewModel.syncEnabled.value)
                {
                    Text(text = "Connect to Wger")
                }
            }
        }
    }

}