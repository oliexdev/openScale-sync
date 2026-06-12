package com.health.openscale.sync.core.service

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.health.openscale.sync.R
import com.health.openscale.sync.gui.components.LocalSnackbar
import com.health.openscale.sync.gui.components.SecretOutlinedTextField
import com.health.openscale.sync.gui.components.UserScopeSection
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.model.WebhookViewModel
import com.health.openscale.sync.core.sync.WebhookSync
import okhttp3.OkHttpClient
import java.util.Date
import java.util.concurrent.TimeUnit

class WebhookService(
    private val context: Context,
    sharedPreferences: SharedPreferences
) : ServiceInterface(context) {

    private val viewModel = WebhookViewModel(sharedPreferences)
    private var webhookSync: WebhookSync? = null
    private var lastUrl: String? = null
    private var lastAuthHeader: String? = null

    override val retryQueueKey = "webhook"

    // Webhook carries userId + username in the JSON payload → can serve all users.
    override val isMultiUser: Boolean get() = true

    override fun viewModel(): ViewModelInterface = viewModel

    override suspend fun connect() {
        initWebhook()
    }

    private fun initWebhook() {
        if (!viewModel.syncEnabled.value) return
        val url = (viewModel.url.value ?: "").trim()
        // Strip control chars (e.g. a pasted trailing newline) — OkHttp rejects them in header values.
        val authHeader = (viewModel.authHeader.value ?: "").filterNot { it.isISOControl() }.trim()
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

    override suspend fun insert(measurement: OpenScaleMeasurement): SyncResult<Unit> =
        withSync { it.insert(measurement) }

    override suspend fun update(measurement: OpenScaleMeasurement): SyncResult<Unit> =
        withSync { it.update(measurement) }

    override suspend fun delete(userId: Int, date: Date): SyncResult<Unit> =
        withSync { it.delete(userId, date) }

    override suspend fun clear(userId: Int): SyncResult<Unit> =
        withSync { it.clear(userId) }

    // Webhook batches a whole insert/update set into one POST.
    override suspend fun insertAll(measurements: List<OpenScaleMeasurement>): BulkResult =
        bulkPost("insert", measurements)

    override suspend fun updateAll(measurements: List<OpenScaleMeasurement>): BulkResult =
        bulkPost("update", measurements)

    private suspend fun bulkPost(event: String, measurements: List<OpenScaleMeasurement>): BulkResult =
        when (val r = withSync { it.postBatch(event, measurements) }) {
            is SyncResult.Success -> BulkResult(measurements)
            is SyncResult.Failure -> BulkResult(emptyList(), r)
        }

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
    override fun ComposeSettings(activity: ComponentActivity) {
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
            // Multi-user backend: states that every openScale user is synced (no per-user picker).
            UserScopeSection(
                isMultiUser = isMultiUser,
                users = emptyList(),
                selectedUserId = 0,
                onUserSelected = {},
                enabled = viewModel.syncEnabled.value
            )
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
