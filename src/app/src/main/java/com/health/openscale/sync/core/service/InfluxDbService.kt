package com.health.openscale.sync.core.service

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.RadioButton
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
import com.health.openscale.sync.gui.components.LocalSnackbar
import com.health.openscale.sync.gui.components.SecretOutlinedTextField
import com.health.openscale.sync.gui.components.UserScopeSection
import com.health.openscale.sync.core.model.InfluxDbViewModel
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.sync.InfluxDbSync
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Date
import java.util.concurrent.TimeUnit

class InfluxDbService(
    private val context: Context,
    sharedPreferences: SharedPreferences
) : ServiceInterface(context) {

    private val viewModel = InfluxDbViewModel(sharedPreferences)
    private var influxSync: InfluxDbSync? = null

    override val retryQueueKey = "influxdb"

    // InfluxDB carries userId/username as tags → can serve all users.
    override val isMultiUser: Boolean get() = true

    override fun viewModel(): ViewModelInterface = viewModel

    override suspend fun connect() {
        connectInfluxDb()
    }

    private fun buildSync(): InfluxDbSync {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        return InfluxDbSync(
            client = client,
            baseUrl = viewModel.url.value?.trimEnd('/') ?: "",
            isV2 = viewModel.isV2.value ?: true,
            org = viewModel.org.value ?: "",
            bucket = viewModel.bucket.value ?: "",
            token = (viewModel.token.value ?: "").filterNot { it.isISOControl() }.trim(),
            database = viewModel.database.value ?: "",
            dbUsername = viewModel.dbUsername.value ?: "",
            dbPassword = viewModel.dbPassword.value ?: "",
            measurementName = viewModel.measurement.value ?: "openscale"
        )
    }

    private suspend fun connectInfluxDb() {
        if (!viewModel.syncEnabled.value) return
        val url = viewModel.url.value ?: ""
        if (url.isBlank()) {
            setErrorMessage(context.getString(R.string.influxdb_url_empty_error))
            return
        }
        viewModel.setConnecting(true)
        val sync = buildSync()
        val result = sync.testConnection()
        viewModel.setConnecting(false)
        when (result) {
            is SyncResult.Success -> {
                influxSync = sync
                clearErrorMessage()
                setInfoMessage(context.getString(R.string.influxdb_connected_text))
            }
            is SyncResult.Failure -> {
                influxSync = null
                setErrorMessage(result)
            }
        }
    }

    private suspend fun withSync(action: suspend (InfluxDbSync) -> SyncResult<Unit>): SyncResult<Unit> {
        if (influxSync == null) connectInfluxDb()
        val sync = influxSync ?: return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, "Not connected")
        return action(sync)
    }

    override suspend fun insert(measurement: OpenScaleMeasurement): SyncResult<Unit> =
        withSync { it.writePoint(measurement) }

    override suspend fun update(measurement: OpenScaleMeasurement): SyncResult<Unit> =
        withSync { it.writePoint(measurement) }

    override suspend fun delete(userId: Int, date: Date): SyncResult<Unit> =
        withSync { it.deleteByTimestamp(userId, date) }

    override suspend fun clear(userId: Int): SyncResult<Unit> =
        withSync { it.deleteAll(userId) }

    // InfluxDB writes the whole batch as one line-protocol request (insert == update: upsert by time).
    override suspend fun insertAll(measurements: List<OpenScaleMeasurement>): BulkResult =
        bulkWrite(measurements)

    override suspend fun updateAll(measurements: List<OpenScaleMeasurement>): BulkResult =
        bulkWrite(measurements)

    private suspend fun bulkWrite(measurements: List<OpenScaleMeasurement>): BulkResult =
        when (val r = withSync { it.writePoints(measurements) }) {
            is SyncResult.Success -> BulkResult(measurements)
            is SyncResult.Failure -> BulkResult(emptyList(), r)
        }

    @Composable
    override fun ComposeSettings(activity: ComponentActivity) {
        val showMessage = LocalSnackbar.current
        val connectingState by viewModel.connecting.observeAsState(false)
        DetailScaffold(
            activity = activity,
            testConnecting = connectingState,
            onTest = {
                activity.lifecycleScope.launch {
                    connectInfluxDb()
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
            val urlState by viewModel.url.observeAsState("")
            val isV2State by viewModel.isV2.observeAsState(true)
            val orgState by viewModel.org.observeAsState("")
            val bucketState by viewModel.bucket.observeAsState("")
            val tokenState by viewModel.token.observeAsState("")
            val databaseState by viewModel.database.observeAsState("")
            val usernameState by viewModel.dbUsername.observeAsState("")
            val passwordState by viewModel.dbPassword.observeAsState("")
            val measurementState by viewModel.measurement.observeAsState("")

            OutlinedTextField(
                enabled = viewModel.syncEnabled.value,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                value = urlState,
                onValueChange = { viewModel.setUrl(it) },
                label = { Text(stringResource(R.string.influxdb_url_title)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    enabled = viewModel.syncEnabled.value,
                    selected = !isV2State,
                    onClick = { viewModel.setIsV2(false) }
                )
                Text("v1")
                Spacer(modifier = Modifier.width(16.dp))
                RadioButton(
                    enabled = viewModel.syncEnabled.value,
                    selected = isV2State,
                    onClick = { viewModel.setIsV2(true) }
                )
                Text("v2")
            }

            if (isV2State) {
                OutlinedTextField(
                    enabled = viewModel.syncEnabled.value,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    value = orgState,
                    onValueChange = { viewModel.setOrg(it) },
                    label = { Text(stringResource(R.string.influxdb_org_title)) }
                )
                OutlinedTextField(
                    enabled = viewModel.syncEnabled.value,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    value = bucketState,
                    onValueChange = { viewModel.setBucket(it) },
                    label = { Text(stringResource(R.string.influxdb_bucket_title)) }
                )
                SecretOutlinedTextField(
                    value = tokenState,
                    onValueChange = { viewModel.setToken(it) },
                    label = stringResource(R.string.influxdb_token_title),
                    enabled = viewModel.syncEnabled.value,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            } else {
                OutlinedTextField(
                    enabled = viewModel.syncEnabled.value,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    value = databaseState,
                    onValueChange = { viewModel.setDatabase(it) },
                    label = { Text(stringResource(R.string.influxdb_database_title)) }
                )
                OutlinedTextField(
                    enabled = viewModel.syncEnabled.value,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    value = usernameState,
                    onValueChange = { viewModel.setDbUsername(it) },
                    label = { Text(stringResource(R.string.influxdb_username_title)) }
                )
                SecretOutlinedTextField(
                    value = passwordState,
                    onValueChange = { viewModel.setDbPassword(it) },
                    label = stringResource(R.string.influxdb_password_title),
                    enabled = viewModel.syncEnabled.value,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            }

            OutlinedTextField(
                enabled = viewModel.syncEnabled.value,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                value = measurementState,
                onValueChange = { viewModel.setMeasurement(it) },
                label = { Text(stringResource(R.string.influxdb_measurement_title)) }
            )

        }
    }
}
