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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.health.openscale.sync.R
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.model.MQTTViewModel
import com.health.openscale.sync.core.model.OpenScaleViewModel
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.provider.OpenScaleDataProvider
import com.health.openscale.sync.core.sync.MQTTSync
import com.health.openscale.sync.gui.components.LocalSnackbar
import com.health.openscale.sync.gui.components.SecretOutlinedTextField
import com.health.openscale.sync.gui.components.SyncDirectionSelector
import com.health.openscale.sync.gui.components.UserScopeSection
import org.json.JSONObject
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

    override suspend fun connect() {
        connectMQTT()
    }

    override fun viewModel(): ViewModelInterface {
        return viewModel
    }

    // MQTT encodes the stable userId in the topic path (username travels in the JSON payload) → multi-user.
    override val isMultiUser: Boolean get() = true

    // Inbound: drain retained messages on openScaleSync/inbound/<userId>/… → bidirectional.
    override val supportsInbound: Boolean get() = true

    /**
     * Inbound source: subscribe to the inbound topic tree and drain currently available (retained)
     * measurement messages. Each message is a JSON `{date, weight[, body_fat, water, values, userId]}`;
     * the target user comes from the topic segment `openScaleSync/inbound/<userId>/…` or the payload.
     */
    override suspend fun readInbound(userId: Int, sinceMs: Long): List<InboundMeasurement> {
        if (!viewModel.syncEnabled.value) return emptyList()
        if (!::mqttClient.isInitialized || !mqttClient.state.isConnected) connectMQTT()
        if (!::mqttSync.isInitialized || !mqttClient.state.isConnected) {
            throw IllegalStateException("MQTT broker not connected")
        }
        return mqttSync.drainInboundMessages("openScaleSync/inbound/#", 1500)
            .mapNotNull { (topic, payload) -> parseInboundMessage(topic, payload) }
    }

    private fun parseInboundMessage(topic: String, payload: String): InboundMeasurement? {
        if (payload.isBlank()) return null
        return runCatching {
            val topicUser = topic.split("/").getOrNull(2)?.toIntOrNull()
            val o = JSONObject(payload)
            val date = o.optLong("date", 0L)
            if (date == 0L || !o.has("weight")) return@runCatching null
            InboundMeasurement(
                timeMs = date,
                weightKg = o.getDouble("weight").toFloat(),
                fatPct = if (o.has("body_fat")) o.getDouble("body_fat").toFloat() else null,
                waterPct = if (o.has("water")) o.getDouble("water").toFloat() else null,
                valuesJson = if (o.has("values")) o.getJSONArray("values").toString() else null,
                userId = if (o.has("userId")) o.optInt("userId") else topicUser
            )
        }.getOrNull()
    }

    // Determines the installed openScale version ("<versionName> (API <apiVersion>)") for the
    // Home Assistant device page. Returns null if openScale is not installed or not accessible.
    private fun openScaleVersionInfo(): String? {
        return try {
            val packageName = sharedPreferences.getString(OpenScaleViewModel.PACKAGE_NAME, "com.health.openscale")!!
            val versionName = context.packageManager.getPackageInfo(packageName, 0).versionName
                ?: return null
            val apiVersion = OpenScaleDataProvider(context, sharedPreferences).getApiVersion()
            if (apiVersion != null) "$versionName (API $apiVersion)" else versionName
        } catch (e: Exception) {
            Timber.w(e, "MQTTService: Could not determine openScale version for HA discovery.")
            null
        }
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

    // Bulk insert/update: refresh each user's retained "last" topic with that user's newest in the
    // batch. The full retained history snapshot is published separately in onReconciled (which has
    // the complete set, not just this delta). Both insert and update batches take this route.
    override suspend fun insertAll(measurements: List<OpenScaleMeasurement>): BulkResult =
        bulkPublish(measurements)

    override suspend fun updateAll(measurements: List<OpenScaleMeasurement>): BulkResult =
        bulkPublish(measurements)

    private suspend fun bulkPublish(measurements: List<OpenScaleMeasurement>): BulkResult {
        val r = ensureConnectedAndExecute("reconcile") { _ ->
            measurements.groupBy { it.userId }.forEach { (_, perUser) ->
                perUser.maxByOrNull { it.date }?.let { publishLastMeasurement(it) }
            }
            SyncResult.Success(Unit)
        }
        return when (r) {
            is SyncResult.Success -> BulkResult(measurements)
            is SyncResult.Failure -> BulkResult(emptyList(), r)
        }
    }

    // Post-reconcile: (re)publish the retained per-user history snapshot for the users whose data
    // changed (or all of them on a forced/manual full sync). Best-effort — failures are swallowed by
    // reconcile's runCatching; the next sync refreshes the snapshot.
    override suspend fun onReconciled(current: List<OpenScaleMeasurement>, changedUserIds: Set<Int>) {
        if (changedUserIds.isEmpty()) return
        val byUser = current.groupBy { it.userId }
        ensureConnectedAndExecute("history") { syncHandler ->
            changedUserIds.forEach { userId ->
                syncHandler.publishHistory(userId, byUser[userId] ?: emptyList())
            }
            SyncResult.Success(Unit)
        }
    }

    override suspend fun insert(measurement: OpenScaleMeasurement): SyncResult<Unit> =
        ensureConnectedAndExecute("insert") { syncHandler ->
            val r = syncHandler.insert(measurement)
            if (r is SyncResult.Success) {
                publishLastMeasurement(measurement)
            }
            r
        }

    override suspend fun delete(userId: Int, date: Date): SyncResult<Unit> =
        ensureConnectedAndExecute("delete") { syncHandler ->
            val r = syncHandler.delete(userId, date)
            if (r is SyncResult.Success) {
                val lastDate = viewModel.getLastPublishedDate(userId)
                if (lastDate != 0L && date.time >= lastDate) {
                    syncHandler.clearLastMeasurement(userId)
                    viewModel.clearLastPublishedDate(userId)
                }
            }
            r
        }

    override suspend fun clear(userId: Int): SyncResult<Unit> =
        ensureConnectedAndExecute("clear") { syncHandler ->
            val r = syncHandler.clear(userId)
            if (r is SyncResult.Success) {
                syncHandler.clearLastMeasurement(userId)
                syncHandler.publishHistory(userId, emptyList())   // clear the retained history snapshot
                viewModel.clearLastPublishedDate(userId)
            }
            r
        }

    override suspend fun update(measurement: OpenScaleMeasurement): SyncResult<Unit> =
        ensureConnectedAndExecute("update") { syncHandler ->
            val r = syncHandler.update(measurement)
            if (r is SyncResult.Success) {
                publishLastMeasurement(measurement)
            }
            r
        }

    private fun publishLastMeasurement(measurement: OpenScaleMeasurement) {
        val lastDate = viewModel.getLastPublishedDate(measurement.userId)
        if (lastDate == 0L || measurement.date.time >= lastDate) {
            mqttSync.publishLastMeasurement(measurement)
            viewModel.setLastPublishedDate(measurement.userId, measurement.date.time)
        }
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
                var clientBuilder = MqttClient.builder()
                    .useMqttVersion5()
                    .serverHost(viewModel.mqttServer.value.toString())
                    .serverPort(viewModel.mqttPort.value.toString().toIntOrNull() ?: 1883)
                    .identifier("openScaleSync")

                if (viewModel.mqttUseSsl.value == true) {
                    clientBuilder = clientBuilder.sslWithDefaultConfig()
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

                        // One Home Assistant device per openScale user (stable userId identity,
                        // username as the display name) so multiple users don't collide into one entity.
                        val sw = openScaleVersionInfo()
                        val users = OpenScaleDataProvider(context, sharedPreferences).getUsers()
                        for (user in users) {
                            val discoveryResult = mqttSync.publishHomeAssistantDiscovery(
                                jsonPayloadString = jsonPayloadString,
                                deviceSwVersion = sw,
                                userId = user.id,
                                username = user.username
                            )
                            if (discoveryResult is SyncResult.Failure) {
                                Timber.w("MQTTService: HA discovery publish failed for user ${user.id}: ${discoveryResult.message}")
                            }
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
    override fun ComposeSettings(activity: ComponentActivity) {
        val showMessage = LocalSnackbar.current
        val mqttConnectingState by viewModel.mqttConnecting.observeAsState(false)
        DetailScaffold(
            activity = activity,
            testConnecting = mqttConnectingState,
            onTest = {
                activity.lifecycleScope.launch {
                    connectMQTT()
                    if (viewModel.errorMessage.value.isNullOrEmpty())
                        showMessage(context.getString(R.string.service_connection_successful))
                }
            }
        ) {
            // Multi-user backend: states that every openScale user is synced (no per-user picker).
            UserScopeSection(
                isMultiUser = isMultiUser,
                users = emptyList(),
                selectedUserId = 0,
                onUserSelected = {},
                enabled = viewModel.syncEnabled.value
            )
            // Per-backend direction (export / import / both). Inbound is pulled by the global Sync button.
            SyncDirectionSelector(
                current = viewModel.syncDirection.value,
                onChange = { viewModel.setSyncDirection(it) },
                enabled = viewModel.syncEnabled.value,
                serviceName = viewModel.getName()
            )

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
                    placeholder = { Text(stringResource(id = R.string.mqtt_server_name_hint)) },
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
                    placeholder = { Text(stringResource(id = R.string.mqtt_server_port_number_hint)) },
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

                SecretOutlinedTextField(
                    value = passwordState,
                    onValueChange = { viewModel.setMQTTPassword(it) },
                    label = stringResource(id = R.string.mqtt_password_title),
                    enabled = viewModel.syncEnabled.value,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
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
                    color = if (viewModel.syncEnabled.value) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
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
                    color = if (viewModel.syncEnabled.value) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Switch(
                    checked = useDiscoveryState,
                    onCheckedChange = { newCheckedState ->
                        viewModel.setMqttUseDiscovery(newCheckedState)
                    },
                    enabled = viewModel.syncEnabled.value
                )
            }

        }
    }

}