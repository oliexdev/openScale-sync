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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import com.health.openscale.sync.R
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.model.GarminViewModel
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.sync.garmin.GarminAuth
import com.health.openscale.sync.core.sync.garmin.GarminConnectSync
import com.health.openscale.sync.gui.components.LocalSnackbar
import com.health.openscale.sync.gui.components.SecretOutlinedTextField
import com.health.openscale.sync.gui.components.UserScopeSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Garmin Connect backend: uploads every measurement of the selected openScale user
 * as a FIT weight-scale file. Login (incl. MFA) happens once via the test button or
 * on the first sync; afterwards stored OAuth tokens are used and refreshed silently.
 */
class GarminService(
    private val context: Context,
    sharedPreferences: SharedPreferences
) : ServiceInterface(context) {

    /** A pending MFA prompt: the auth layer is waiting for the user to enter the code. */
    data class MfaRequest(val method: String, val answer: CompletableDeferred<String>)

    private val viewModel = GarminViewModel(sharedPreferences)
    private val auth = GarminAuth(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val sync = GarminConnectSync(auth, client, loginProvider = { interactiveLogin() })

    private val _mfaRequest = MutableLiveData<MfaRequest?>(null)
    val mfaRequest: LiveData<MfaRequest?> = _mfaRequest

    // One Garmin account = one person: the user picks which openScale user this service syncs.
    override val isMultiUser: Boolean get() = false

    override fun viewModel(): ViewModelInterface = viewModel

    override suspend fun connect() {
        // Nothing to hold open: tokens are checked lazily per call and refreshed silently.
        val hasToken = withContext(Dispatchers.IO) { auth.ensureAccessToken() != null }
        if (viewModel.syncEnabled.value && !hasToken &&
            (viewModel.email.value.isNullOrBlank() || viewModel.password.value.isNullOrBlank())) {
            setErrorMessage(context.getString(R.string.garmin_credentials_missing))
        }
    }

    /**
     * Runs the full Garmin login (called lazily when no usable token exists) and returns
     * a valid bearer token. May surface the MFA dialog through [mfaRequest].
     */
    private suspend fun interactiveLogin(): String {
        val email = viewModel.email.value?.trim().orEmpty()
        val password = viewModel.password.value.orEmpty()
        if (email.isBlank() || password.isBlank())
            throw GarminAuth.LoginException(context.getString(R.string.garmin_credentials_missing))

        auth.login(email, password) { method ->
            val deferred = CompletableDeferred<String>()
            _mfaRequest.postValue(MfaRequest(method, deferred))
            try {
                withTimeout(180_000) { deferred.await() }
            } catch (e: Exception) {
                throw GarminAuth.MfaCancelledException()
            } finally {
                _mfaRequest.postValue(null)
            }
        }
        return auth.ensureAccessToken()
            ?: throw GarminAuth.LoginException("login finished but no token")
    }

    private suspend fun guard(action: suspend () -> Unit): SyncResult<Unit> {
        if (!viewModel.syncEnabled.value)
            return SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, "Service disabled")
        return try {
            // Garmin's OkHttp calls are blocking: never run them on the main thread.
            withContext(Dispatchers.IO) { action() }
            clearErrorMessage()
            SyncResult.Success(Unit)
        } catch (e: GarminAuth.MfaCancelledException) {
            SyncResult.Failure(SyncResult.ErrorType.API_ERROR,
                context.getString(R.string.garmin_mfa_cancelled))
        } catch (e: GarminAuth.LoginException) {
            SyncResult.Failure(SyncResult.ErrorType.API_ERROR, e.message)
        } catch (e: Exception) {
            Timber.e(e, "Garmin sync action failed")
            SyncResult.Failure(SyncResult.ErrorType.API_ERROR,
                e.message ?: e.javaClass.simpleName, e)
        }
    }

    override suspend fun insert(measurement: OpenScaleMeasurement): SyncResult<Unit> =
        guard { sync.upload(measurement) }

    override suspend fun update(measurement: OpenScaleMeasurement): SyncResult<Unit> =
        // Garmin keys weigh-ins by timestamp: replace the old record.
        guard {
            runCatching { sync.delete(measurement.date) }
            sync.upload(measurement)
        }

    override suspend fun delete(userId: Int, date: Date): SyncResult<Unit> =
        guard { sync.delete(date) }

    override suspend fun clear(userId: Int): SyncResult<Unit> {
        Timber.w("Garmin: clear not supported (would wipe ALL weigh-ins in Garmin) - ignoring")
        return SyncResult.Success(Unit)
    }

    private suspend fun testConnection() {
        viewModel.setConnecting(true)
        val result = guard {
            if (auth.ensureAccessToken() == null) interactiveLogin()
        }
        viewModel.setConnecting(false)
        when (result) {
            is SyncResult.Success -> {
                clearErrorMessage()
                setInfoMessage(context.getString(R.string.garmin_connected_text))
            }
            is SyncResult.Failure -> setErrorMessage(result)
        }
    }

    @Composable
    override fun ComposeSettings(activity: ComponentActivity) {
        val showMessage = LocalSnackbar.current
        val connectingState by viewModel.connecting.observeAsState(false)

        // MFA dialog: shown whenever the auth layer asks for a code (login or background re-login).
        val pendingMfa by mfaRequest.observeAsState()
        pendingMfa?.let { request ->
            var code by remember(request) { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = {
                    request.answer.completeExceptionally(GarminAuth.MfaCancelledException())
                },
                title = { Text(stringResource(R.string.garmin_mfa_title)) },
                text = {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text(stringResource(R.string.garmin_mfa_hint, request.method)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        request.answer.complete(code.trim())
                    }) { Text(stringResource(android.R.string.ok)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        request.answer.completeExceptionally(GarminAuth.MfaCancelledException())
                    }) { Text(stringResource(android.R.string.cancel)) }
                }
            )
        }

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
            // Single-user backend: pick which openScale user this Garmin account receives.
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

            val emailState by viewModel.email.observeAsState("")
            val passwordState by viewModel.password.observeAsState("")

            OutlinedTextField(
                enabled = viewModel.syncEnabled.value,
                modifier = Modifier.fillMaxWidth(),
                value = emailState,
                onValueChange = { viewModel.setEmail(it) },
                label = { Text(stringResource(R.string.garmin_email_title)) },
                placeholder = { Text(stringResource(R.string.garmin_email_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                singleLine = true
            )

            SecretOutlinedTextField(
                value = passwordState,
                onValueChange = { viewModel.setPassword(it) },
                label = stringResource(R.string.garmin_password_title),
                enabled = viewModel.syncEnabled.value,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = stringResource(R.string.garmin_login_note),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
