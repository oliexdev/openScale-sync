package com.health.openscale.sync.core.service

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.health.openscale.sync.R
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.model.WebhookViewModel
import com.health.openscale.sync.core.sync.WebhookSync
import com.health.openscale.sync.core.utils.RetryQueue
import okhttp3.OkHttpClient
import java.util.Date
import java.util.concurrent.TimeUnit

class WebhookService(
    private val context: Context,
    private val sharedPreferences: SharedPreferences
) : ServiceInterface(context) {

    private val viewModel = WebhookViewModel(sharedPreferences)
    private var webhookSync: WebhookSync? = null
    private val retryQueue = RetryQueue(context, "webhook")
    private var lastUrl: String? = null
    private var lastAuthHeader: String? = null

    override fun viewModel(): ViewModelInterface = viewModel

    override suspend fun init() {
        initWebhook()
        drainQueue()
    }

    private fun initWebhook() {
        if (!viewModel.syncEnabled.value) return
        val url = viewModel.url.value ?: ""
        val authHeader = viewModel.authHeader.value ?: ""
        if (url.isBlank()) return
        if (webhookSync != null && url == lastUrl && authHeader == lastAuthHeader) return
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        webhookSync = WebhookSync(client = client, url = url, authHeader = authHeader)
        lastUrl = url
        lastAuthHeader = authHeader
    }

    private suspend fun drainQueue() {
        val sync = webhookSync ?: return
        val ops = retryQueue.peek()
        if (ops.isEmpty()) return
        var failedIndex = ops.size
        for ((index, op) in ops.withIndex()) {
            val result = when (op.type) {
                "insert" -> sync.insert(op.toMeasurement())
                "update" -> sync.update(op.toMeasurement())
                "delete" -> sync.delete(Date(op.dateMs))
                "clear"  -> sync.clear()
                else     -> SyncResult.Success(Unit)
            }
            if (result is SyncResult.Failure) {
                failedIndex = index
                break
            }
        }
        retryQueue.replace(ops.subList(failedIndex, ops.size))
    }

    private suspend fun withSync(action: suspend (WebhookSync) -> SyncResult<Unit>): SyncResult<Unit> {
        initWebhook()
        val sync = webhookSync ?: return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, "Not configured")
        val result = action(sync)
        if (result is SyncResult.Success) clearErrorMessage()
        return result
    }

    override suspend fun insert(measurement: OpenScaleMeasurement): SyncResult<Unit> {
        val result = withSync { it.insert(measurement) }
        if (result is SyncResult.Failure) retryQueue.enqueue(RetryQueue.insert(measurement))
        return result
    }

    override suspend fun update(measurement: OpenScaleMeasurement): SyncResult<Unit> {
        val result = withSync { it.update(measurement) }
        if (result is SyncResult.Failure) retryQueue.enqueue(RetryQueue.update(measurement))
        return result
    }

    override suspend fun delete(date: Date): SyncResult<Unit> {
        val result = withSync { it.delete(date) }
        if (result is SyncResult.Failure) retryQueue.enqueue(RetryQueue.delete(date))
        return result
    }

    override suspend fun clear(): SyncResult<Unit> {
        val result = withSync { it.clear() }
        if (result is SyncResult.Failure) retryQueue.enqueue(RetryQueue.clear())
        return result
    }

    override suspend fun sync(measurements: List<OpenScaleMeasurement>): SyncResult<Unit> =
        withSync { it.fullSync(measurements) }

    @Composable
    override fun composeSettings(activity: ComponentActivity) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            super.composeSettings(activity)

            val urlState by viewModel.url.observeAsState("")
            val authHeaderState by viewModel.authHeader.observeAsState("")

            OutlinedTextField(
                enabled = viewModel.syncEnabled.value,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                value = urlState,
                onValueChange = { viewModel.setUrl(it) },
                label = { Text(stringResource(R.string.webhook_url_title)) }
            )

            OutlinedTextField(
                enabled = viewModel.syncEnabled.value,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                value = authHeaderState,
                onValueChange = { viewModel.setAuthHeader(it) },
                label = { Text(stringResource(R.string.webhook_auth_header_title)) },
                visualTransformation = PasswordVisualTransformation()
            )

            val errorMessage by viewModel.errorMessage.observeAsState()
            if (!errorMessage.isNullOrBlank() && viewModel.syncEnabled.value) {
                Text(errorMessage!!, color = Color.Red)
            }
        }
    }
}
