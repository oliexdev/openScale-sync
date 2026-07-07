/*
 *  Copyright (C) 2026  Dany Mestas
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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.lifecycleScope
import com.health.openscale.sync.R
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.model.EndurainViewModel
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.sync.EndurainSync
import com.health.openscale.sync.core.sync.EndurainTokenManager
import com.health.openscale.sync.gui.components.LocalSnackbar
import com.health.openscale.sync.gui.components.SecretOutlinedTextField
import com.health.openscale.sync.gui.components.UserScopeSection
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Export-only sync backend for Endurain (self-hosted FastAPI fitness server). Auth is OAuth2
 * username/password login → short-lived JWT access token + rotating refresh token; the tokens are
 * stored encrypted (no password kept) and refreshed proactively in [connect] and reactively on 401
 * inside [EndurainSync]. Body weight + composition is written to the `health_weight` table via
 * POST (upsert-by-date). MFA is supported; SSO/PKCE is out of scope.
 */
class EndurainService(
    private val context: Context,
    sharedPreferences: SharedPreferences
) : ServiceInterface(context) {
    private val viewModel: EndurainViewModel = EndurainViewModel(sharedPreferences)
    private val tokenManager = EndurainTokenManager(context)
    private var endurainSync: EndurainSync? = null

    override fun viewModel(): ViewModelInterface = viewModel

    // Export only: Endurain has a readable weight API but inbound import is out of scope here.
    override val supportsInbound: Boolean get() = false

    // Stable retry-queue key (independent of the display name).
    override val retryQueueKey: String get() = "endurain"

    private fun buildSync(): EndurainSync =
        EndurainSync(viewModel.endurainServer.value.orEmpty(), tokenManager).also { endurainSync = it }

    override suspend fun connect() {
        if (!viewModel.syncEnabled.value) return

        val server = viewModel.endurainServer.value.orEmpty().trim()
        if (server.isEmpty()) {
            viewModel.setConnectAvailable(false)
            viewModel.setAllPermissionsGranted(false)
            return
        }

        val sync = buildSync()

        if (!tokenManager.isLoggedIn()) {
            viewModel.setConnectAvailable(false)
            viewModel.setAllPermissionsGranted(false)
            setErrorMessage(context.getString(R.string.endurain_not_logged_in))
            return
        }

        // Proactive refresh: mint a fresh access token if the current one has expired (15-min TTL).
        if (!sync.ensureFreshAccessToken()) {
            viewModel.setConnectAvailable(false)
            viewModel.setAllPermissionsGranted(false)
            setErrorMessage(context.getString(R.string.endurain_session_expired))
            return
        }

        when (val res = sync.testConnection()) {
            is SyncResult.Success -> {
                viewModel.setAllPermissionsGranted(true)
                viewModel.setConnectAvailable(true)
                clearErrorMessage()
                setInfoMessage(context.getString(R.string.endurain_successful_connected_text))
            }
            is SyncResult.Failure -> {
                viewModel.setConnectAvailable(false)
                viewModel.setAllPermissionsGranted(false)
                setErrorMessage(res)
            }
        }
    }

    private suspend fun withSync(block: suspend (EndurainSync) -> SyncResult<Unit>): SyncResult<Unit> {
        val sync = endurainSync
        return if (sync != null && viewModel.connectAvailable.value && viewModel.allPermissionsGranted.value)
            block(sync)
        else SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED)
    }

    // POST is upsert-by-date on Endurain, so insert and update are the same wire call.
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
            val osUsers = remember { openScaleDataService.getUsers() }
            UserScopeSection(
                isMultiUser = isMultiUser,
                users = osUsers,
                selectedUserId = viewModel.selectedUserId.value,
                onUserSelected = { viewModel.setSelectedUserId(it.id) },
                enabled = viewModel.syncEnabled.value
            )

            val enabled = viewModel.syncEnabled.value

            val serverState by viewModel.endurainServer.observeAsState("")
            OutlinedTextField(
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                value = serverState,
                onValueChange = { viewModel.setEndurainServer(it) },
                label = { Text(stringResource(id = R.string.endurain_server_name_title)) },
                placeholder = { Text(stringResource(id = R.string.endurain_server_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            // Login status (recomposes when tokens change via loginStateVersion).
            val loginVersion by viewModel.loginStateVersion.observeAsState(0)
            val usernameState by viewModel.endurainUsername.observeAsState("")
            val loggedIn = remember(loginVersion) { tokenManager.isLoggedIn() }
            val refreshExpiresAt = remember(loginVersion) { tokenManager.getRefreshTokenExpiresAt() }

            if (loggedIn) {
                Text(stringResource(id = R.string.endurain_logged_in_status, usernameState))
                if (refreshExpiresAt > 0L) {
                    val expText = DateFormat.getDateTimeInstance().format(Date(refreshExpiresAt * 1000))
                    Text(stringResource(id = R.string.endurain_refresh_expires, expText))
                }
                OutlinedButton(
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        tokenManager.clear()
                        viewModel.notifyLoginStateChanged()
                        viewModel.setConnectAvailable(false)
                        viewModel.setAllPermissionsGranted(false)
                        clearErrorMessage()
                    }
                ) { Text(stringResource(id = R.string.endurain_logout_button)) }
            } else {
                Text(stringResource(id = R.string.endurain_not_logged_in))

                var username by remember { mutableStateOf(usernameState) }
                var password by remember { mutableStateOf("") }
                var mfaRequired by remember { mutableStateOf(false) }
                var mfaCode by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }

                OutlinedTextField(
                    enabled = enabled && !busy,
                    modifier = Modifier.fillMaxWidth(),
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(id = R.string.endurain_username_title)) },
                    singleLine = true
                )
                SecretOutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(id = R.string.endurain_password_title),
                    enabled = enabled && !busy,
                    modifier = Modifier.fillMaxWidth()
                )

                if (mfaRequired) {
                    OutlinedTextField(
                        enabled = enabled && !busy,
                        modifier = Modifier.fillMaxWidth(),
                        value = mfaCode,
                        onValueChange = { mfaCode = it },
                        label = { Text(stringResource(id = R.string.endurain_mfa_code_title)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Button(
                        enabled = enabled && !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true
                            activity.lifecycleScope.launch {
                                val sync = buildSync()
                                when (val r = sync.verifyMfa(username, mfaCode)) {
                                    is EndurainSync.LoginResult.Success -> {
                                        viewModel.setEndurainUsername(username)
                                        viewModel.notifyLoginStateChanged()
                                        mfaRequired = false
                                        connect()
                                        showMessage(context.getString(R.string.endurain_login_success))
                                    }
                                    is EndurainSync.LoginResult.MfaRequired -> { /* stay on MFA step */ }
                                    is EndurainSync.LoginResult.Failure ->
                                        setErrorMessage(context.getString(R.string.endurain_login_failed) + " (" + r.message + ")")
                                }
                                busy = false
                            }
                        }
                    ) { Text(stringResource(id = R.string.endurain_verify_button)) }
                } else {
                    Button(
                        enabled = enabled && !busy,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            busy = true
                            activity.lifecycleScope.launch {
                                val sync = buildSync()
                                when (val r = sync.login(username, password)) {
                                    is EndurainSync.LoginResult.Success -> {
                                        viewModel.setEndurainUsername(username)
                                        viewModel.notifyLoginStateChanged()
                                        connect()
                                        showMessage(context.getString(R.string.endurain_login_success))
                                    }
                                    is EndurainSync.LoginResult.MfaRequired -> {
                                        viewModel.setEndurainUsername(username)
                                        mfaRequired = true
                                    }
                                    is EndurainSync.LoginResult.Failure ->
                                        setErrorMessage(context.getString(R.string.endurain_login_failed) + " (" + r.message + ")")
                                }
                                busy = false
                            }
                        }
                    ) { Text(stringResource(id = R.string.endurain_login_button)) }
                }
            }
        }
    }
}
