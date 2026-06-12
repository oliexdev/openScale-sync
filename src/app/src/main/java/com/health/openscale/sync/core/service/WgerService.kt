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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import com.health.openscale.sync.gui.components.UserScopeSection
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.lifecycleScope
import com.health.openscale.sync.R
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.datatypes.OpenScaleUser
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.model.WgerViewModel
import com.health.openscale.sync.core.sync.WgerSync
import com.health.openscale.sync.gui.components.LocalSnackbar
import com.health.openscale.sync.gui.components.SecretOutlinedTextField
import com.health.openscale.sync.gui.components.SyncDirectionSelector
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class WgerService(
    private val context: Context,
    sharedPreferences: SharedPreferences
) : ServiceInterface(context) {
    private val viewModel: WgerViewModel = WgerViewModel(sharedPreferences)//ViewModelProvider(context)[MQTTViewModel::class.java]
    private lateinit var wgerSync : WgerSync
    private lateinit var wgerRetrofit: Retrofit

    override suspend fun connect() {
        connectWger()
    }

    override fun viewModel(): ViewModelInterface {
        return viewModel
    }

    // Wger weight entries are readable via REST → bidirectional.
    override val supportsInbound: Boolean get() = true

    // No bulk override: Wger's REST API has no batch endpoint, so the default per-item loop
    // (insert/update) is already optimal.

    override suspend fun insert(measurement: OpenScaleMeasurement) : SyncResult<Unit> =
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value)
            wgerSync.insert(measurement)
        else SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)

    override suspend fun delete(userId: Int, date: Date) : SyncResult<Unit> =
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value)
            wgerSync.delete(date)
        else SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)

    override suspend fun clear(userId: Int) : SyncResult<Unit> =
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value)
            wgerSync.clear()
        else SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)

    override suspend fun update(measurement: OpenScaleMeasurement) : SyncResult<Unit> =
        if (viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value)
            wgerSync.update(measurement)
        else SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)

    /**
     * Inbound source (bidirectional): read weight entries from Wger. Wger has no data origin and
     * only a date, so we apply openScale-as-master day-level gap-fill: only return entries for days
     * openScale has no measurement yet (avoids echo/duplicates). The base [runInbound] writes them.
     */
    override suspend fun readInbound(userId: Int, sinceMs: Long): List<InboundMeasurement> {
        if (!::wgerSync.isInitialized || !viewModel.connectAvailable.value || !viewModel.allPermissionsGranted.value) {
            throw IllegalStateException("Wger not connected")
        }
        val entries = wgerSync.readInboundWeights()
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
        val existingDays = openScaleDataService.getMeasurements(OpenScaleUser(userId, ""))
            .map { dayKey.format(it.date) }.toSet()
        return entries.mapNotNull { (dateMs, kg) ->
            if (dayKey.format(Date(dateMs)) in existingDays) null else InboundMeasurement(dateMs, kg)
        }
    }

    private suspend fun connectWger() {
        if (viewModel.syncEnabled.value) {
            try {
                // Strip control characters (e.g. a trailing newline from a pasted token) —
                // OkHttp rejects header values containing them and would crash the call thread.
                val authToken = viewModel.wgerApiToken.value.orEmpty().filterNot { it.isISOControl() }.trim()
                val baseUrl = (viewModel.wgerServer.value ?: "").trim()

                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    val newRequest = chain.request().newBuilder()
                        .addHeader("Authorization", "Token $authToken")
                        .build()
                    chain.proceed(newRequest)
                }.build()

                wgerRetrofit = Retrofit.Builder()
                    .client(client)
                    .baseUrl(baseUrl)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                wgerSync = WgerSync(wgerRetrofit)
                val wgerApi: WgerSync.WgerApi = wgerRetrofit.create(WgerSync.WgerApi::class.java)
                val wgerWeightEntryList = wgerApi.weightEntryList()

                if (wgerWeightEntryList.count >= 0) {
                    viewModel.setAllPermissionsGranted(true)
                    viewModel.setConnectAvailable(true)
                    clearErrorMessage()
                    setInfoMessage(context.getString(R.string.wger_successful_connected_text))
                } else {
                    setErrorMessage(context.getString(R.string.wger_not_successful_connected_error))
                }
            } catch (ex: Exception) {
                setErrorMessage(ex.message ?: context.getString(R.string.sync_service_unknown_error))
            }
        }
    }

    @Composable
    override fun ComposeSettings(activity: ComponentActivity) {
        val showMessage = LocalSnackbar.current
        DetailScaffold(
            activity = activity,
            testConnecting = false,
            onTest = {
                activity.lifecycleScope.launch {
                    connectWger()
                    if (viewModel.errorMessage.value.isNullOrEmpty())
                        showMessage(context.getString(R.string.service_connection_successful))
                }
            }
        ) {
            // Single-user backend: pick which openScale user this destination receives.
            val osUsers = remember { openScaleDataService.getUsers() }
            UserScopeSection(
                isMultiUser = isMultiUser,
                users = osUsers,
                selectedUserId = viewModel.selectedUserId.value,
                onUserSelected = { viewModel.setSelectedUserId(it.id) },
                enabled = viewModel.syncEnabled.value
            )
            // Per-backend direction (export / import / both). Inbound is pulled by the global Sync button.
            SyncDirectionSelector(
                current = viewModel.syncDirection.value,
                onChange = { viewModel.setSyncDirection(it) },
                enabled = viewModel.syncEnabled.value,
                serviceName = viewModel.getName()
            )

            val serverNameState by viewModel.wgerServer.observeAsState("")
            OutlinedTextField(
                enabled = viewModel.syncEnabled.value,
                modifier = Modifier.fillMaxWidth(),
                value = serverNameState,
                onValueChange = { viewModel.setWgerServer(it) },
                label = { Text(stringResource(id = R.string.wger_server_name_title)) },
                placeholder = { Text(stringResource(id = R.string.wger_server_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            val wgerApiTokenState by viewModel.wgerApiToken.observeAsState("")
            SecretOutlinedTextField(
                value = wgerApiTokenState,
                onValueChange = { viewModel.setWgerApiToken(it) },
                label = stringResource(id = R.string.wger_api_token_title),
                enabled = viewModel.syncEnabled.value,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

}