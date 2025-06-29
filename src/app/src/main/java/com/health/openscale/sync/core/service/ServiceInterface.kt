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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.health.openscale.sync.R
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.provider.OpenScaleDataProvider
import com.health.openscale.sync.core.provider.OpenScaleProvider
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Date

sealed class SyncResult<out T> {
    data class Success<T>(val data: T) : SyncResult<T>()
    data class Failure(val errorType: ErrorType, val message: String? = null, val cause: Throwable? = null) : SyncResult<Nothing>()

    enum class ErrorType {
        PERMISSION_DENIED,
        API_ERROR,
        UNKNOWN_ERROR
    }
}

abstract class ServiceInterface (
    private val context: Context
) {
    lateinit var navController : NavHostController
    lateinit var openScaleService: OpenScaleProvider
    lateinit var openScaleDataService: OpenScaleDataProvider

    abstract suspend fun init()
    abstract fun viewModel() : ViewModelInterface
    abstract suspend fun sync(measurements: List<OpenScaleMeasurement>) : SyncResult<Unit>
    abstract suspend fun insert(measurement: OpenScaleMeasurement) : SyncResult<Unit>
    abstract suspend fun delete(date: Date) : SyncResult<Unit>
    abstract suspend fun clear() : SyncResult<Unit>
    abstract suspend fun update(measurement: OpenScaleMeasurement) : SyncResult<Unit>

    fun setErrorMessage(message : String) {
        val fullMessage = viewModel().getName() + ": " + message
        viewModel().setErrorMessage(fullMessage)
        Toast.makeText(context, fullMessage, Toast.LENGTH_SHORT).show()
        Timber.e("[ERROR] $fullMessage")
    }

    fun setErrorMessage(failure: SyncResult.Failure) {
        var fullMessage : String

        when (failure.errorType) {
            SyncResult.ErrorType.PERMISSION_DENIED -> {
                fullMessage = context.getString(R.string.sync_service_permission_error)
            }
            SyncResult.ErrorType.API_ERROR -> {
                fullMessage = context.getString(R.string.sync_service_api_error)
            }
            SyncResult.ErrorType.UNKNOWN_ERROR -> {
                fullMessage = context.getString(R.string.sync_service_unknown_error)
            }
        }

        if (failure.message != null) {
            fullMessage += " (" + failure.message + ")"
        } else if (failure.cause != null) {
            fullMessage += " (" + failure.cause.message + ")"
        }

        setErrorMessage(fullMessage)
    }

    fun setInfoMessage(message : String) {
        val fullMessage = viewModel().getName() + ": " + message
        viewModel().setInfoMessage(fullMessage)
        Toast.makeText(context, fullMessage, Toast.LENGTH_SHORT).show()
        Timber.i("[INFO] $fullMessage")
    }

    fun setDebugMessage(message : String) {
        val fullMessage = viewModel().getName() + ": " + message
        viewModel().setDebugMessage(fullMessage)
        Timber.d("[DEBUG] $fullMessage")
    }

    fun clearErrorMessage() {
        viewModel().setErrorMessage("")
    }

    open fun registerActivityResultLauncher(activity: ComponentActivity) {

    }

    @Composable
    open fun composeSettings(activity: ComponentActivity) {
        composeBasicSettings(activity) // need to be called as a private function because of the open keyword
    }

    @Composable
    private fun composeBasicSettings(activity: ComponentActivity) {
        val coroutineScope = rememberCoroutineScope()

        Column (
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = viewModel().syncEnabled.value,
                        onCheckedChange = {
                            viewModel().setSyncEnabled(it)

                            if (it) {
                                coroutineScope.launch {
                                    init()
                                }
                            }
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    )

                    Text(stringResource(id = R.string.sync_service_enable_sync_service_button))
                }
            }
        }
}