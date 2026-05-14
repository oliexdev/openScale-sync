package com.health.openscale.sync.core.service

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import com.health.openscale.sync.core.model.InfluxDbViewModel
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.sync.InfluxDbSync
import com.health.openscale.sync.core.utils.RetryQueue
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Date
import java.util.concurrent.TimeUnit

class InfluxDbService(
    private val context: Context,
    private val sharedPreferences: SharedPreferences
) : ServiceInterface(context) {

    private val viewModel = InfluxDbViewModel(sharedPreferences)
    private var influxSync: InfluxDbSync? = null
    private val retryQueue = RetryQueue(context, "influxdb")

    override fun viewModel(): ViewModelInterface = viewModel

    override suspend fun init() {
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
            token = viewModel.token.value ?: "",
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
                val pending = retryQueue.size()
                drainQueue()
                val msg = context.getString(R.string.influxdb_connected_text) +
                    if (pending > 0) " ($pending ${context.getString(R.string.retry_queue_remaining)})" else ""
                setInfoMessage(msg)
            }
            is SyncResult.Failure -> {
                influxSync = null
                setErrorMessage(result)
            }
        }
    }

    private suspend fun drainQueue() {
        val sync = influxSync ?: return
        val ops = retryQueue.peek()
        if (ops.isEmpty()) return
        var failedIndex = ops.size
        for ((index, op) in ops.withIndex()) {
            val result = when (op.type) {
                "insert" -> sync.writePoint(op.toMeasurement())
                "update" -> sync.writePoint(op.toMeasurement())
                "delete" -> sync.deleteByTimestamp(Date(op.dateMs))
                "clear"  -> sync.deleteAll()
                else     -> SyncResult.Success(Unit)
            }
            if (result is SyncResult.Failure) {
                failedIndex = index
                break
            }
        }
        retryQueue.replace(ops.subList(failedIndex, ops.size))
    }

    private suspend fun withSync(action: suspend (InfluxDbSync) -> SyncResult<Unit>): SyncResult<Unit> {
        if (influxSync == null) connectInfluxDb()
        val sync = influxSync ?: return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, "Not connected")
        return action(sync)
    }

    override suspend fun insert(measurement: OpenScaleMeasurement): SyncResult<Unit> {
        val result = withSync { it.writePoint(measurement) }
        if (result is SyncResult.Failure) retryQueue.enqueue(RetryQueue.insert(measurement))
        return result
    }

    override suspend fun update(measurement: OpenScaleMeasurement): SyncResult<Unit> {
        val result = withSync { it.writePoint(measurement) }
        if (result is SyncResult.Failure) retryQueue.enqueue(RetryQueue.update(measurement))
        return result
    }

    override suspend fun delete(date: Date): SyncResult<Unit> {
        val result = withSync { it.deleteByTimestamp(date) }
        if (result is SyncResult.Failure) retryQueue.enqueue(RetryQueue.delete(date))
        return result
    }

    override suspend fun clear(): SyncResult<Unit> {
        val result = withSync { it.deleteAll() }
        if (result is SyncResult.Failure) retryQueue.enqueue(RetryQueue.clear())
        return result
    }

    override suspend fun sync(measurements: List<OpenScaleMeasurement>): SyncResult<Unit> =
        withSync { it.writePoints(measurements) }

    @Composable
    override fun composeSettings(activity: ComponentActivity) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            super.composeSettings(activity)

            val urlState by viewModel.url.observeAsState("")
            val isV2State by viewModel.isV2.observeAsState(true)
            val orgState by viewModel.org.observeAsState("")
            val bucketState by viewModel.bucket.observeAsState("")
            val tokenState by viewModel.token.observeAsState("")
            val databaseState by viewModel.database.observeAsState("")
            val usernameState by viewModel.dbUsername.observeAsState("")
            val passwordState by viewModel.dbPassword.observeAsState("")
            val measurementState by viewModel.measurement.observeAsState("")
            val connectingState by viewModel.connecting.observeAsState(false)

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
                Text(
                    stringResource(R.string.influxdb_use_v2_title),
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    enabled = viewModel.syncEnabled.value,
                    checked = isV2State,
                    onCheckedChange = { viewModel.setIsV2(it) }
                )
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
                OutlinedTextField(
                    enabled = viewModel.syncEnabled.value,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    value = tokenState,
                    onValueChange = { viewModel.setToken(it) },
                    label = { Text(stringResource(R.string.influxdb_token_title)) },
                    visualTransformation = PasswordVisualTransformation()
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
                OutlinedTextField(
                    enabled = viewModel.syncEnabled.value,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    value = passwordState,
                    onValueChange = { viewModel.setDbPassword(it) },
                    label = { Text(stringResource(R.string.influxdb_password_title)) },
                    visualTransformation = PasswordVisualTransformation()
                )
            }

            OutlinedTextField(
                enabled = viewModel.syncEnabled.value,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                value = measurementState,
                onValueChange = { viewModel.setMeasurement(it) },
                label = { Text(stringResource(R.string.influxdb_measurement_title)) }
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    enabled = viewModel.syncEnabled.value && !connectingState,
                    onClick = { activity.lifecycleScope.launch { connectInfluxDb() } }
                ) {
                    Text(
                        if (connectingState) stringResource(R.string.influxdb_connecting_text)
                        else stringResource(R.string.influxdb_connect_button)
                    )
                }

                val errorMessage by viewModel.errorMessage.observeAsState()
                if (!errorMessage.isNullOrBlank() && viewModel.syncEnabled.value) {
                    Text(errorMessage!!, color = Color.Red)
                }
            }
        }
    }
}
