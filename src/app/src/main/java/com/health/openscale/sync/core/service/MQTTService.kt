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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.openscale.sync.R
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.model.MQTTViewModel
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.sync.MQTTSync
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
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

    override suspend fun sync(measurements: List<OpenScaleMeasurement>) : SyncResult<Unit> {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            return mqttSync.fullSync(measurements)
        }

        return SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)
    }
    override suspend fun insert(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            return mqttSync.insert(measurement)
        }

        return SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)
    }

    override suspend fun delete(date: Date) : SyncResult<Unit> {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            return mqttSync.delete(date)
        }

        return SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)
    }

    override suspend fun clear() : SyncResult<Unit> {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            return mqttSync.clear()
        }

        return SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)
    }

    override suspend fun update(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            return mqttSync.update(measurement)
        }

        return SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)
    }

    private suspend fun connectMQTT() {
        if (viewModel.syncEnabled.value) {
            viewModel.setMQTTConnecting(true)
            try {
                var connectedSuccessfully = false

                withContext(Dispatchers.IO) {
                    val clientBuilder = MqttClient.builder()
                        .useMqttVersion5()
                        .serverHost(viewModel.mqttServer.value.toString())
                        .serverPort(viewModel.mqttPort.value.toString().toInt())
                        .identifier("openScaleSync")

                        if (viewModel.mqttUseSsl.value == true) {
                            Timber.d("MQTT: SSL/TLS is enabled. Applying SSL configuration.")
                            clientBuilder.sslWithDefaultConfig()
                        }

                        mqttClient = clientBuilder.buildBlocking()

                        mqttClient.connectWith()
                        .simpleAuth()
                        .username(viewModel.mqttUsername.value.toString())
                        .password(UTF_8.encode(viewModel.mqttPassword.value.toString()))
                        .applySimpleAuth()
                        .send()

                    mqttSync = MQTTSync(mqttClient)
                    connectedSuccessfully = true
                }

                if (connectedSuccessfully) {
                    setInfoMessage(context.getString(R.string.mqtt_broker_successful_connected_text))

                    viewModel.setConnectAvailable(true)
                    viewModel.setAllPermissionsGranted(true)
                    clearErrorMessage()

                    viewModel.setMQTTConnecting(false)
                }
            } catch (result: Exception) {
                setErrorMessage("${result.message}")
                viewModel.setMQTTConnecting(false)
            }
        }
    }

    @Composable
    override fun composeSettings(activity: ComponentActivity) {
        Column (
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
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
                    label = { Text(stringResource(id = R.string.mqtt_server_name_title)) },
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
                    label = { Text(stringResource(id = R.string.mqtt_server_port_number_title)) },
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
                    label = { Text(stringResource(id = R.string.mqtt_username_title)) },
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
                    label = { Text(stringResource(id = R.string.mqtt_password_title)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                    )
                )
            }

            val useSslState by viewModel.mqttUseSsl.observeAsState(true)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = viewModel.syncEnabled.value,
                        onClick = {
                            if (viewModel.syncEnabled.value) {
                                viewModel.setMqttUseSsl(!useSslState)
                            }
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(id = R.string.mqtt_use_ssl_tls_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (viewModel.syncEnabled.value) MaterialTheme.colorScheme.onSurface else Color.Gray
                )
                Switch(
                    checked = useSslState,
                    onCheckedChange = { newCheckedState ->
                        viewModel.setMqttUseSsl(newCheckedState)
                    },
                    enabled = viewModel.syncEnabled.value
                )
            }

            if (!useSslState && viewModel.syncEnabled.value) {
                Text(
                    text = stringResource(id = R.string.mqtt_ssl_disabled_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp, bottom = 8.dp, end = 8.dp)
                )
            }

            Column (
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val mqttConnectingState by viewModel.mqttConnecting.observeAsState(false)

                Button(onClick = {
                    if (!mqttConnectingState) {
                        activity.lifecycleScope.launch {
                            connectMQTT()
                        }
                    }
                },
                    enabled = viewModel.syncEnabled.value)
                {
                    if (mqttConnectingState) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text = stringResource(id = R.string.mqtt_connecting_text))
                    } else {
                        Text(text = stringResource(id = R.string.mqtt_connect_to_mqtt_broker_button))
                    }
                }

                val errorMessage by viewModel.errorMessage.observeAsState()

                if (errorMessage != null && errorMessage != "" && viewModel.syncEnabled.value) {
                    Text("$errorMessage", color = Color.Red)
                }
            }
        }
    }

}