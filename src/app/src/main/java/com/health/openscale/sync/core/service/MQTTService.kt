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

    // Centralized function to ensure client is connected before performing an operation
    private suspend fun <T> ensureConnectedAndExecute(
        operationName: String,
        action: suspend (mqttSyncInstance: MQTTSync) -> SyncResult<T>
    ): SyncResult<T> {
        if (!viewModel.syncEnabled.value) {
            return SyncResult.Failure(
                SyncResult.ErrorType.API_ERROR,
                "MQTT Sync service is not enabled."
            )
        }

        // Check if client is initialized and connected
        if (!::mqttClient.isInitialized || !mqttClient.state.isConnected) {
            Timber.w("MQTTService: Client for '$operationName' not initialized or not connected. Attempting to reconnect...")
            connectMQTT() // Attempt to establish or re-establish the connection

            // After attempting to connect, check the status again
            if (!::mqttClient.isInitialized || !mqttClient.state.isConnected) {
                Timber.e("MQTTService: Failed to connect to MQTT broker for '$operationName'.")
                viewModel.setConnectAvailable(false) // Update ViewModel
                return SyncResult.Failure(
                    SyncResult.ErrorType.API_ERROR,
                    "Failed to connect to MQTT broker for $operationName."
                )
            }
            Timber.i("MQTTService: Reconnect successful for '$operationName'.")
        }

        // At this point, mqttClient should be initialized and connected,
        // and mqttSync should also be initialized.
        if (!::mqttSync.isInitialized) {
            // This case should ideally not happen if connectMQTT() correctly initializes mqttSync
            Timber.e("MQTTService: mqttSync not initialized even after successful connection check for '$operationName'. This is unexpected.")
            return SyncResult.Failure(
                SyncResult.ErrorType.UNKNOWN_ERROR,
                "Internal error: MQTT sync handler not initialized for $operationName."
            )
        }

        return action(mqttSync)
    }

    override suspend fun sync(measurements: List<OpenScaleMeasurement>): SyncResult<Unit> {
        return ensureConnectedAndExecute("fullSync") {syncHandler ->
            syncHandler.fullSync(measurements)
        }
    }

    override suspend fun insert(measurement: OpenScaleMeasurement): SyncResult<Unit> {
        return ensureConnectedAndExecute("insert") { syncHandler ->
            syncHandler.insert(measurement)
        }
    }

    override suspend fun delete(date: Date): SyncResult<Unit> {
        return ensureConnectedAndExecute("delete") { syncHandler ->
            syncHandler.delete(date)
        }
    }

    override suspend fun clear(): SyncResult<Unit> {
        return ensureConnectedAndExecute("clear") { syncHandler ->
            syncHandler.clear()
        }
    }

    override suspend fun update(measurement: OpenScaleMeasurement): SyncResult<Unit> {
        return ensureConnectedAndExecute("update") { syncHandler ->
            syncHandler.update(measurement)
        }
    }

    fun current(measurement: OpenScaleMeasurement) : SyncResult<Unit> {
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value) {
            return mqttSync.current(measurement)
        }

        return SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)
    }

    private suspend fun connectMQTT() {
        if (!viewModel.syncEnabled.value) {
            viewModel.setConnectAvailable(false)
            // Consider explicitly disconnecting if the client is already initialized and connected
            // if (::mqttClient.isInitialized && mqttClient.state.isConnected) {
            //     withContext(Dispatchers.IO) { try { mqttClient.disconnect() } catch (e: Exception) { Timber.e(e, "Error disconnecting previous client.") } }
            // }
            return
        }

        viewModel.setMQTTConnecting(true)
        viewModel.setConnectAvailable(false) // Assume not connected until success
        clearErrorMessage()

        try {
            withContext(Dispatchers.IO) {
                // Step 1: Build the client and assign to the class member
                // If this fails, mqttClient might remain uninitialized or hold an old instance.
                val clientBuilder = MqttClient.builder()
                    .useMqttVersion5()
                    .serverHost(viewModel.mqttServer.value.toString())
                    .serverPort(viewModel.mqttPort.value.toString().toIntOrNull() ?: 1883)
                    .identifier("openScaleSync")

                if (viewModel.mqttUseSsl.value == true) {
                    clientBuilder.sslWithDefaultConfig()
                }

                // Assign directly to the class member.
                // If buildBlocking() throws, the catch block outside withContext will handle it.
                // If a previous mqttClient existed, it's overwritten here.
                mqttClient = clientBuilder.buildBlocking()
                Timber.d("MQTTService: Client built. State: ${mqttClient.state}")


                // Step 2: Connect the class member client
                // If this fails, mqttClient is built but not connected.
                mqttClient.connectWith()
                    .simpleAuth()
                    .username(viewModel.mqttUsername.value.toString())
                    .password(UTF_8.encode(viewModel.mqttPassword.value.toString()))
                    .applySimpleAuth()
                    .send() // This will throw an exception on failure.

                Timber.d("MQTTService: Client connected. State: ${mqttClient.state}")

                // If we reach here, mqttClient is successfully built and connected.
                // Now, initialize or re-initialize mqttSync with the connected client.
                mqttSync = MQTTSync(mqttClient) // Initialize/Re-initialize the main mqttSync instance

                // Step 3: Home Assistant Discovery (if enabled)
                // This now uses the initialized this.mqttSync and its this.mqttClient
                if (viewModel.mqttUseDiscovery.value == true) {
                    Timber.d("MQTTService: Home Assistant discovery is enabled. Preparing payload...")
                    try {
                        val inputStream = context.resources.openRawResource(R.raw.homeassistant_payload)
                        val jsonPayloadString = inputStream.bufferedReader().use { it.readText() }

                        // Call the method on the now initialized mqttSync instance
                        val discoveryResult = mqttSync.publishHomeAssistantDiscovery(
                            jsonPayloadString = jsonPayloadString
                        )

                        if (discoveryResult is SyncResult.Failure) {
                            Timber.w("MQTTService: Home Assistant Discovery publish failed: ${discoveryResult.message}")
                            // Decide if this non-critical error should be shown to the user or just logged.
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "MQTTService: Error during Home Assistant discovery preparation or publish.")
                    }
                }
                // All steps successful within withContext(Dispatchers.IO)
            }

            // If withContext completed without throwing, connection and setup were successful.
            setInfoMessage(context.getString(R.string.mqtt_broker_successful_connected_text))
            viewModel.setConnectAvailable(true)
            clearErrorMessage()

        } catch (e: Exception) {
            Timber.e(e, "MQTT Connection or Setup Error in connectMQTT")
            val errorMsg = when (e) {
                is java.net.UnknownHostException -> "Broker not found: ${viewModel.mqttServer.value}"
                is java.net.ConnectException -> "Connection refused by broker: ${viewModel.mqttServer.value}:${viewModel.mqttPort.value}"
                // MqttClientStateException could be thrown by connectWith().send() if client is in a wrong state
                is com.hivemq.client.mqtt.exceptions.MqttClientStateException -> "MQTT client state error: ${e.message}"
                else -> "MQTT Connection failed: ${e.localizedMessage ?: "Unknown error"}"
            }
            setErrorMessage(errorMsg)
            viewModel.setConnectAvailable(false) // Explicitly set to false on any error
        } finally {
            viewModel.setMQTTConnecting(false)
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

            val useDiscoveryState by viewModel.mqttUseDiscovery.observeAsState(true)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = viewModel.syncEnabled.value,
                        onClick = {
                            if (viewModel.syncEnabled.value) {
                                viewModel.setMqttUseDiscovery(!useDiscoveryState)
                            }
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(id = R.string.mqtt_use_discovery_tite),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (viewModel.syncEnabled.value) MaterialTheme.colorScheme.onSurface else Color.Gray
                )
                Switch(
                    checked = useDiscoveryState,
                    onCheckedChange = { newCheckedState ->
                        viewModel.setMqttUseDiscovery(newCheckedState)
                    },
                    enabled = viewModel.syncEnabled.value
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