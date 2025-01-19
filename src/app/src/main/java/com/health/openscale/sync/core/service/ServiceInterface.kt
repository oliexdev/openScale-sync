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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.core.provider.OpenScaleDataProvider
import com.health.openscale.sync.core.provider.OpenScaleProvider
import timber.log.Timber
import java.util.Date


abstract class ServiceInterface (
    private val context: Context
) {
    lateinit var navController : NavHostController
    lateinit var openScaleService: OpenScaleProvider
    lateinit var openScaleDataService: OpenScaleDataProvider

    abstract suspend fun init()
    abstract fun viewModel() : ViewModelInterface
    abstract suspend fun sync(measurements: List<OpenScaleMeasurement>)
    abstract suspend fun insert(measurement: OpenScaleMeasurement)
    abstract suspend fun delete(date: Date)
    abstract suspend fun clear()
    abstract suspend fun update(measurement: OpenScaleMeasurement)

    fun setErrorMessage(message : String) {
        viewModel().setErrorMessage(message)
        Timber.e("ERROR: $message")
    }

    fun setInfoMessage(message : String) {
        viewModel().setInfoMessage(message)
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        Timber.i("INFO: $message")
    }

    fun setDebugMessage(message : String) {
        viewModel().setDebugMessage(message)
        Timber.d("DEBUG: $message")
    }

    open fun registerActivityResultLauncher(activity: ComponentActivity) {

    }

    @Composable
    open fun composeSettings(activity: ComponentActivity) {
        composeBasicSettings(activity) // need to be called as a private function because of the open keyword
    }

    @Composable
    private fun composeBasicSettings(activity: ComponentActivity) {
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
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    )

                    Text("Enable sync service")
                }
            }
        }
}