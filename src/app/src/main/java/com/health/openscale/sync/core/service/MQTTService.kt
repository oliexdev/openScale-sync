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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.model.MQTTViewModel
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.sync.MQTTSync
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.text.Charsets.UTF_8

// Example broker https://www.hivemq.com/
class MQTTService(
    private val context: Context,
    private val sharedPreferences: SharedPreferences
) : ServiceInterface(context) {
    private val viewModel: MQTTViewModel = MQTTViewModel(sharedPreferences)//ViewModelProvider(context)[MQTTViewModel::class.java]
    private lateinit var mqttClient: Mqtt5BlockingClient
    private lateinit var mqttSync: MQTTSync

    override suspend fun init() {
        connectMQTT()
    }

    override fun viewModel(): ViewModelInterface {
        return viewModel
    }

    override suspend fun sync(measurements: List<OpenScaleMeasurement>) {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            mqttSync.fullSync(measurements)
        }
    }
    override suspend fun insert(measurement: OpenScaleMeasurement) {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            mqttSync.insert(measurement)
        }
    }

    override suspend fun delete(date: Date) {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            mqttSync.delete(date)
        }
    }

    override suspend fun clear() {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            mqttSync.clear()
        }
    }

    override suspend fun update(measurement: OpenScaleMeasurement) {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            mqttSync.update(measurement)
        }
    }

    private fun connectMQTT() {
        if (viewModel.syncEnabled.value) {
            try {
                mqttClient = MqttClient.builder()
                    .useMqttVersion5()
                    .serverHost(viewModel.mqttServer.value.toString())
                    .serverPort(8883)
                    .identifier("openScaleSync")
                    .sslWithDefaultConfig()
                    .buildBlocking()

                mqttClient.connectWith()
                    .simpleAuth()
                    .username(viewModel.mqttUsername.value.toString())
                    .password(UTF_8.encode(viewModel.mqttPassword.value.toString()))
                    .applySimpleAuth()
                    .send()

                setInfoMessage("Successful connected to MQTT broker")
                mqttSync = MQTTSync(mqttClient)
                viewModel.setConnectAvailable(true)
                viewModel.setAllPermissionsGranted(true)
                viewModel.setErrorMessage("")
            } catch (result: Exception) {
                setErrorMessage("${result.message}")
            }
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
                val serverNameState by viewModel.mqttServer.observeAsState("")

                OutlinedTextField(
                    enabled = viewModel.syncEnabled.value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    value = serverNameState,
                    onValueChange = {
                        viewModel.setMQTTServer(it)
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
                val serverPortState by viewModel.mqttPort.observeAsState(0)

                OutlinedTextField(
                    enabled = viewModel.syncEnabled.value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    value = if (serverPortState == 0) "" else serverPortState.toString(),
                    onValueChange = { newValue ->
                        val filteredValue = newValue.filter { char -> char.isDigit() }

                        val intValue = filteredValue.toIntOrNull()
                        if (intValue != null && intValue in 0..65535) {
                            viewModel.setMQTTPort(intValue)
                        } else {
                            viewModel.setMQTTPort(0)
                        }
                    },
                    label = { Text("Port Number") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    )
                )
            }
            Column (
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val usernameState by viewModel.mqttUsername.observeAsState("")

                OutlinedTextField(
                    enabled = viewModel.syncEnabled.value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    value = usernameState,
                    onValueChange = {
                        viewModel.setMQTTUsername(it)
                    },
                    label = { Text("Username") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text
                    )
                )
            }
            Column (
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val passwordState by viewModel.mqttPassword.observeAsState("")

                OutlinedTextField(
                    enabled = viewModel.syncEnabled.value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    value = passwordState,
                    onValueChange = {
                        viewModel.setMQTTPassword(it)
                    },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                    )
                )
            }
            Column (
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(onClick = {
                    activity.lifecycleScope.launch {
                        connectMQTT()
                    }
                },
                    enabled = viewModel.syncEnabled.value)
                {
                    Text(text = "Connect to MQTT broker")
                }

                val errorMessage by viewModel.errorMessage.observeAsState()

                if (errorMessage != null && errorMessage != "" && viewModel.syncEnabled.value) {
                    Text("$errorMessage", color = Color.Red)
                }
            }
        }
    }

}