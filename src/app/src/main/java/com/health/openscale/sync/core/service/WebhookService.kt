package com.health.openscale.sync.core.service

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.health.openscale.sync.R
import com.health.openscale.sync.gui.components.LocalSnackbar
import com.health.openscale.sync.gui.components.SecretOutlinedTextField
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.model.WebhookViewModel
import com.health.openscale.sync.core.sync.WebhookSync
import okhttp3.OkHttpClient
import java.util.Date
import java.util.concurrent.TimeUnit

class WebhookService(
    private val context: Context,
    private val sharedPreferences: SharedPreferences
) : ServiceInterface(context) {

    private val viewModel = WebhookViewModel(sharedPreferences)
    private var webhookSync: WebhookSync? = null
    private var lastUrl: String? = null
    private var lastAuthHeader: String? = null

    override val retryQueueKey = "webhook"

    override fun viewModel(): ViewModelInterface = viewModel

    override suspend fun doInit() {
        initWebhook()
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

    private suspend fun withSync(action: suspend (WebhookSync) -> SyncResult<Unit>): SyncResult<Unit> {
        initWebhook()
        val sync = webhookSync ?: return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, "Not configured")
        val result = action(sync)
        if (result is SyncResult.Success) clearErrorMessage()
        return result
    }

    override suspend fun doInsert(measurement: OpenScaleMeasurement): SyncResult<Unit> =
        withSync { it.insert(measurement) }

    override suspend fun doUpdate(measurement: OpenScaleMeasurement): SyncResult<Unit> =
        withSync { it.update(measurement) }

    override suspend fun doDelete(date: Date): SyncResult<Unit> =
        withSync { it.delete(date) }

    override suspend fun doClear(): SyncResult<Unit> =
        withSync { it.clear() }

    override suspend fun sync(measurements: List<OpenScaleMeasurement>): SyncResult<Unit> =
        withSync { it.fullSync(measurements) }

    private suspend fun testConnection() {
        if (!viewModel.syncEnabled.value) return
        val url = viewModel.url.value ?: ""
        if (url.isBlank()) {
            setErrorMessage(context.getString(R.string.webhook_url_empty_error))
            return
        }
        viewModel.setConnecting(true)
        initWebhook()
        val result = webhookSync?.test()
            ?: SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, "Not configured")
        viewModel.setConnecting(false)
        when (result) {
            is SyncResult.Success -> {
                clearErrorMessage()
                setInfoMessage(context.getString(R.string.webhook_connected_text))
            }
            is SyncResult.Failure -> setErrorMessage(result)
        }
    }

    @Composable
    override fun composeSettings(activity: ComponentActivity) {
        val showMessage = LocalSnackbar.current
        val connectingState by viewModel.connecting.observeAsState(false)
        DetailScaffold(
            activity = activity,
            testConnecting = connectingState,
            onTest = {
                activity.lifecycleScope.launch {
                    testConnection()
                    if (viewModel.errorMessage.value.isNullOrEmpty())
                        showMessage(context.getString(R.string.service_connection_successful))
                }
            }
        ) {
            val urlState by viewModel.url.observeAsState("")
            val authHeaderState by viewModel.authHeader.observeAsState("")

            OutlinedTextField(
                enabled = viewModel.syncEnabled.value,
                modifier = Modifier.fillMaxWidth(),
                value = urlState,
                onValueChange = { viewModel.setUrl(it) },
                label = { Text(stringResource(R.string.webhook_url_title)) },
                placeholder = { Text(stringResource(R.string.webhook_url_hint)) },
                singleLine = true
            )

            SecretOutlinedTextField(
                value = authHeaderState,
                onValueChange = { viewModel.setAuthHeader(it) },
                label = stringResource(R.string.webhook_auth_header_title),
                enabled = viewModel.syncEnabled.value,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
