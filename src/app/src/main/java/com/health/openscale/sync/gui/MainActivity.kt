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
package com.health.openscale.sync.gui

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Blue
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.activity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.health.openscale.sync.BuildConfig
import com.health.openscale.sync.R
import com.health.openscale.sync.core.provider.OpenScaleDataProvider
import com.health.openscale.sync.core.provider.OpenScaleProvider
import com.health.openscale.sync.core.service.HealthConnectService
import com.health.openscale.sync.core.service.MQTTService
import com.health.openscale.sync.core.service.ServiceInterface
import com.health.openscale.sync.core.service.SyncResult
import com.health.openscale.sync.core.service.WgerService
import com.health.openscale.sync.gui.theme.OpenScaleSyncTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber.DebugTree
import timber.log.Timber.Forest.plant
import java.time.Instant
import java.util.Date


class MainActivity : AppCompatActivity() {
    private lateinit var navController : NavHostController
    private lateinit var openScaleService: OpenScaleProvider
    private lateinit var openScaleDataService: OpenScaleDataProvider
    private lateinit var syncServiceList : List<ServiceInterface>
    private val currentTitle = MutableLiveData<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        plant(DebugTree())

        currentTitle.value = getString(R.string.title_overview)
        val sharedPreferences: SharedPreferences = getSharedPreferences("openScaleSyncSettings", Context.MODE_PRIVATE)

        sharedPreferences.edit().putString("packageName", detectPackage()).apply()

        openScaleDataService = OpenScaleDataProvider(this, sharedPreferences)
        openScaleService = OpenScaleProvider(this, openScaleDataService, sharedPreferences)

        openScaleService.registerActivityResultLauncher(this)

        lifecycleScope.launch {
            openScaleService.init()
        }

        if (sharedPreferences.getString("packageName", "null") == "null") {
            openScaleService.viewModel().setConnectAvailable(false)
        } else {
            openScaleService.viewModel().setConnectAvailable(true)
        }

        syncServiceList = listOf(
            HealthConnectService(applicationContext, sharedPreferences),
            MQTTService(applicationContext, sharedPreferences),
            WgerService(applicationContext, sharedPreferences)
        )

        for (syncService in syncServiceList) {
            syncService.registerActivityResultLauncher(this)
        }

            lifecycleScope.launch {
            for (syncService in syncServiceList) {
                syncService.openScaleService = openScaleService
                syncService.openScaleDataService = openScaleDataService
                if (syncService.viewModel().syncEnabled.value) {
                    syncService.init()
                }
            }
        }

        setContent {
            composeMainView(this)
        }
    }


    private fun detectPackage(): String {
        if (doesExist("com.health.openscale")) {
            return "com.health.openscale"
        }

        if (doesExist("com.health.openscale.oss")) {
            return "com.health.openscale.oss"
        }

        if (doesExist("com.health.openscale.light")) {
            return "com.health.openscale.light"
        }

        if (doesExist("com.health.openscale.pro")) {
            return "com.health.openscale.pro"
        }

        return "null"
    }

    private fun doesExist(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun composeMainView(activity: ComponentActivity) {
        val snackbarHostState = remember { SnackbarHostState() }
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val versionCheckPerformed = remember { mutableStateOf(false) }

        LaunchedEffect(Unit,openScaleService.viewModel().allPermissionsGranted.value, openScaleService.viewModel().connectAvailable.value) {
            if (!versionCheckPerformed.value && openScaleService.viewModel().allPermissionsGranted.value && openScaleService.viewModel().connectAvailable.value) {
                val supportsRealtimeSync = openScaleDataService.checkVersion()
                if (!supportsRealtimeSync) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = activity.getString(R.string.realtime_sync_update_required_snackbar_text),
                                duration = SnackbarDuration.Long
                            )
                        }
                }
                versionCheckPerformed.value = true
            }
        }

        OpenScaleSyncTheme {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = { navigationDrawerSheet(drawerState, scope) }
            ) {
                Scaffold(
                    topBar = {
                        val title by currentTitle.observeAsState()
                        TopAppBar(
                            title = { Text(text = title.toString()) },
                            navigationIcon = {
                                if (title == getString(R.string.title_overview)) {
                                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                        Icon(
                                            imageVector = Icons.Filled.Menu,
                                            contentDescription = "Menu"
                                        )
                                    }
                                } else {
                                    IconButton(onClick = { scope.launch { navController.navigateUp() } }) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back"
                                        )
                                    }
                                }
                                             },
                            colors = TopAppBarDefaults.mediumTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            )
                        )
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) { snackbarData ->
                            Snackbar(
                                modifier = Modifier.padding(8.dp), // Padding around the snackbar.
                                shape = RoundedCornerShape(8.dp), // Rounded corners for the snackbar.
                                containerColor = MaterialTheme.colorScheme.primary, // Custom background color.
                                contentColor = White,    // Custom text and icon color.
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.Info,
                                        contentDescription = "openScale sync",
                                        tint = LocalContentColor.current // Uses the contentColor from Snackbar.
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(snackbarData.visuals.message)
                                }
                            }
                        }
                    }
                ){ paddingValues -> // Get the padding values from the Scaffold
                    Surface(
                        modifier = Modifier
                            .padding(paddingValues)
                            .fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        navController = rememberNavController()
                        NavHost(navController = navController, startDestination = "overview") {
                            composable("overview") {
                                HomeScreen(activity)
                                currentTitle.value = stringResource(id = R.string.title_overview)
                            }
                            for (syncService in syncServiceList) {
                                composable(
                                    syncService.viewModel().getName()
                                ) {
                                    fullSyncFloatingButton(syncService)
                                    syncService.composeSettings(activity)
                                    currentTitle.value = syncService.viewModel().getName()
                                }
                                syncService.navController = navController
                            }
                            composable("about") {
                                AboutScreen()
                                currentTitle.value = stringResource(id = R.string.title_about)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun HomeScreen(activity: ComponentActivity) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            openScaleService.composeSettings(activity)

            Row {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = "arrowUp",
                    modifier = Modifier.size(32.dp)
                )

                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "arrowDown",
                    modifier = Modifier.size(32.dp)
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 256.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(syncServiceList) { item ->
                    SyncServiceGridItem(item)
                }
            }
        }
    }

    @Composable
    fun AboutScreen() {
        Column {
            Column (
                modifier = Modifier.padding(16.dp)
            ) {
                Text("openScale sync", style = MaterialTheme.typography.titleMedium)
                Text(BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")", style = MaterialTheme.typography.bodyMedium)
            }
            Column (
                modifier = Modifier.padding(16.dp)
            ) {
                Text(stringResource(R.string.about_title_maintainer), style = MaterialTheme.typography.titleMedium)
                Text("olie.xdev <olie.xdev@googlemail.com>", style = MaterialTheme.typography.bodyMedium)
            }
            Column (
                modifier = Modifier.padding(16.dp)
            ) {
                Text(stringResource(R.string.about_title_website), style = MaterialTheme.typography.titleMedium)
                val annotatedString = buildAnnotatedString {
                    val linkText = "https://github.com/oliexdev/openScale-sync"
                    withStyle(
                        SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 16.sp,
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(linkText)
                    }
                    addLink(LinkAnnotation.Url(linkText), 0, linkText.length)
                }

                Text(annotatedString, style = MaterialTheme.typography.bodyMedium)
            }
            Column (
                modifier = Modifier.padding(16.dp)
            ) {
                Text(stringResource(id = R.string.about_title_license), style = MaterialTheme.typography.titleMedium)
                Text("GPLv3", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @Composable
    fun navigationDrawerSheet(drawerState : DrawerState, scope : CoroutineScope) {
        ModalDrawerSheet(
            drawerContainerColor = MaterialTheme.colorScheme.background
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(8.dp)
                    .fillMaxWidth()
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_openscale_sync_foreground),
                    contentDescription = "openScale sync",
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "openScale sync")
            }
            NavigationDrawerItem(
                label = { Text(stringResource(id = R.string.title_overview)) },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = stringResource(id = R.string.title_overview)
                    )
                },
                selected = false,
                onClick = {
                    scope.launch { drawerState.close() }
                    navController.navigate("overview")
                }
            )

            for (syncService in syncServiceList) {
                NavigationDrawerItem(
                    label = { Text(syncService.viewModel().getName()) },
                    icon = {
                        Icon(
                            painter = painterResource(id = syncService.viewModel().getIcon()),
                            contentDescription = syncService.viewModel().getName(),
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate(syncService.viewModel().getName())
                    }
                )
            }

            HorizontalDivider()

            NavigationDrawerItem(
                label = { Text(stringResource(id = R.string.title_about)) },
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_about),
                        contentDescription = stringResource(R.string.title_about)
                    )
                },
                selected = false,
                onClick = {
                    scope.launch { drawerState.close() }
                    navController.navigate("about")
                }
            )
        }
    }
    @Composable
    fun fullSyncFloatingButton(syncService: ServiceInterface) {

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            ExtendedFloatingActionButton(
                onClick = {
                    if (syncService.viewModel().syncEnabled.value && !syncService.viewModel().syncRunning.value) {
                        lifecycleScope.launch {
                            syncService.viewModel().setSyncRunning(true)
                            openScaleDataService.checkVersion()
                            val measurements = openScaleDataService.getMeasurements(openScaleService.getSelectedUser())
                            val syncResult = syncService.sync(measurements)

                            if (syncResult is SyncResult.Success) {
                                syncService.viewModel().setLastSync(Instant.now())
                                syncService.setInfoMessage(getString(R.string.sync_service_full_synced_info, measurements.size))
                            } else {
                                syncService.setErrorMessage(syncResult as SyncResult.Failure)
                            }

                            syncService.viewModel().setSyncRunning(false)
                        }
                    }
                },
                containerColor = if (syncService.viewModel().syncEnabled.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.inverseOnSurface,
                contentColor = if (syncService.viewModel().syncEnabled.value) MaterialTheme.colorScheme.onSecondaryContainer else Color.Gray,
                text = {
                    if (syncService.viewModel().syncRunning.value) {
                        Text(
                            text = stringResource(id = R.string.sync_service_syncing_text),
                            color = if (syncService.viewModel().syncEnabled.value) MaterialTheme.colorScheme.onSecondaryContainer else Color.Gray
                        )
                    } else {
                        Text(
                            text = stringResource(id = R.string.sync_service_full_sync_button),
                            color = if (syncService.viewModel().syncEnabled.value) MaterialTheme.colorScheme.onSecondaryContainer else Color.Gray
                        )
                    }
                },
                icon = {
                    if (syncService.viewModel().syncRunning.value) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.sync_service_full_sync_button),
                            tint = if (syncService.viewModel().syncEnabled.value) MaterialTheme.colorScheme.onSecondaryContainer else Color.Gray
                        )
                    }
                }
            )
        }
    }

    @Composable
    fun SyncServiceGridItem(syncService: ServiceInterface) {
        Card(
            modifier = Modifier.padding(8.dp),
            onClick = {
                navController.navigate(syncService.viewModel().getName())
            },
            enabled = openScaleService.viewModel().allPermissionsGranted.value && openScaleService.viewModel().connectAvailable.value
        ) {
            Box(modifier = Modifier.padding(16.dp)) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = syncService.viewModel().getIcon()),
                            contentDescription = syncService.viewModel().getName(),
                            tint = if (syncService.viewModel().syncEnabled.value) MaterialTheme.colorScheme.primary else Color.Gray,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            " ${syncService.viewModel().getName()}",
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (syncService.viewModel().syncEnabled.value) MaterialTheme.colorScheme.primary else Color.Gray,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Column (
                        modifier = Modifier.padding(16.dp)
                    ) {
                        val errorMessage by syncService.viewModel().errorMessage.observeAsState()
                        if (errorMessage != null && errorMessage != "") {
                            Text(
                                "$errorMessage",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        val lastSync by syncService.viewModel().lastSync.observeAsState()
                        if (lastSync?.toEpochMilli() == 0L) {
                            Text(
                                stringResource(id = R.string.sync_service_last_sync_never_text),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (syncService.viewModel().syncEnabled.value) MaterialTheme.colorScheme.onSecondaryContainer else Color.Gray
                            )
                        } else {
                            val dateFormat = DateFormat.getDateFormat(applicationContext)
                            val timeFormat = DateFormat.getTimeFormat(applicationContext)
                            val timeDateFormat = dateFormat.format(Date.from(lastSync)) + " " + timeFormat.format(Date.from(lastSync))
                            Text(
                                stringResource(id = R.string.sync_service_last_sync_formatted_text, timeDateFormat),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (syncService.viewModel().syncEnabled.value) MaterialTheme.colorScheme.onSecondaryContainer else Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}

