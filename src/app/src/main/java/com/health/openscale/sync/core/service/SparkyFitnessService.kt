/*
 *  Copyright (C) 2026  Mirko Seipel
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.health.openscale.sync.R
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.model.SparkyFitnessViewModel
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.sync.SparkyFitnessSync
import com.health.openscale.sync.gui.components.LocalSnackbar
import com.health.openscale.sync.gui.components.SecretOutlinedTextField
import com.health.openscale.sync.gui.components.UserScopeSection
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Export-only sync backend for SparkyFitness (self-hosted fitness and nutrition tracker). Auth is
 * a user API key. Body weight and composition go into the daily check-in, every other openScale
 * value into a custom category (see [SparkyFitnessSync]).
 */
class SparkyFitnessService(
    private val context: Context,
    sharedPreferences: SharedPreferences
) : ServiceInterface(context) {
    private val viewModel: SparkyFitnessViewModel = SparkyFitnessViewModel(sharedPreferences)
    private var sparkySync: SparkyFitnessSync? = null

    override fun viewModel(): ViewModelInterface = viewModel

    // Export only: SparkyFitness has a readable check-in API, but inbound import is out of scope here.
    override val supportsInbound: Boolean get() = false

    // Stable retry-queue key (independent of the display name).
    override val retryQueueKey: String get() = "sparkyfitness"

    private fun buildSync(): SparkyFitnessSync =
        SparkyFitnessSync(
            viewModel.server.value.orEmpty(),
            viewModel.apiKey.value.orEmpty(),
            { viewModel.includeDerived.value ?: false }
        ).also { sparkySync = it }

    override suspend fun connect() {
        if (!viewModel.syncEnabled.value) return

        if (viewModel.server.value.isNullOrBlank() || viewModel.apiKey.value.isNullOrBlank()) {
            viewModel.setConnectAvailable(false)
            viewModel.setAllPermissionsGranted(false)
            setErrorMessage(context.getString(R.string.sparkyfitness_not_configured))
            return
        }

        when (val res = buildSync().testConnection()) {
            is SyncResult.Success -> {
                viewModel.setAllPermissionsGranted(true)
                viewModel.setConnectAvailable(true)
                clearErrorMessage()
                setInfoMessage(context.getString(R.string.sparkyfitness_successful_connected_text))
            }
            is SyncResult.Failure -> {
                viewModel.setConnectAvailable(false)
                viewModel.setAllPermissionsGranted(false)
                setErrorMessage(res)
            }
        }
    }

    private suspend fun withSync(block: suspend (SparkyFitnessSync) -> SyncResult<Unit>): SyncResult<Unit> {
        val sync = sparkySync
        return if (sync != null && viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value)
            block(sync)
        else SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)
    }

    // Both SparkyFitness endpoints are upserts by day, so insert and update are the same wire call.
    override suspend fun insert(measurement: OpenScaleMeasurement): SyncResult<Unit> =
        withSync { it.upsert(measurement) }

    override suspend fun update(measurement: OpenScaleMeasurement): SyncResult<Unit> =
        withSync { it.upsert(measurement) }

    override suspend fun delete(userId: Int, date: Date): SyncResult<Unit> =
        withSync { it.delete(date) }

    override suspend fun clear(userId: Int): SyncResult<Unit> =
        withSync { it.clear() }

    @Composable
    override fun ComposeSettings(activity: ComponentActivity) {
        val showMessage = LocalSnackbar.current

        DetailScaffold(
            activity = activity,
            testConnecting = false,
            onTest = {
                activity.lifecycleScope.launch {
                    connect()
                    if (viewModel.errorMessage.value.isNullOrEmpty())
                        showMessage(context.getString(R.string.service_connection_successful))
                }
            }
        ) {
            // Single-user backend: pick which openScale user this destination receives.
            val osUsers = remember {
                runCatching { openScaleDataService.getUsers() }.getOrDefault(emptyList())
            }
            UserScopeSection(
                isMultiUser = isMultiUser,
                users = osUsers,
                selectedUserId = viewModel.selectedUserId.value,
                onUserSelected = { viewModel.setSelectedUserId(it.id) },
                enabled = viewModel.syncEnabled.value
            )

            val enabled = viewModel.syncEnabled.value

            val serverState by viewModel.server.observeAsState("")
            OutlinedTextField(
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                value = serverState,
                onValueChange = { viewModel.setServer(it) },
                label = { Text(stringResource(id = R.string.sparkyfitness_server_name_title)) },
                placeholder = { Text(stringResource(id = R.string.sparkyfitness_server_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            val apiKeyState by viewModel.apiKey.observeAsState("")
            SecretOutlinedTextField(
                value = apiKeyState,
                onValueChange = { viewModel.setApiKey(it) },
                label = stringResource(id = R.string.sparkyfitness_api_key_title),
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            )

            val includeDerivedState by viewModel.includeDerived.observeAsState(false)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { viewModel.setIncludeDerived(!includeDerivedState) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(id = R.string.sparkyfitness_include_derived_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = includeDerivedState,
                    onCheckedChange = { viewModel.setIncludeDerived(it) },
                    enabled = enabled
                )
            }
            Text(
                text = stringResource(id = R.string.sparkyfitness_include_derived_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp, end = 8.dp)
            )
        }
    }
}
