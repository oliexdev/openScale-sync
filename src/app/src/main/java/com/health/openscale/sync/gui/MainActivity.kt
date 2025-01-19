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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
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
    private val currentTitle = MutableLiveData("Overview")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            plant(DebugTree())
        }

        val sharedPreferences: SharedPreferences = getSharedPreferences("openScaleSyncSettings", Context.MODE_PRIVATE)

        sharedPreferences.edit().putString("packageName", detectPackage()).apply()

        // Initialize ViewModel
        openScaleDataService = OpenScaleDataProvider(this, sharedPreferences)
        openScaleService = OpenScaleProvider(this, openScaleDataService, sharedPreferences)

        openScaleService.registerActivityResultLauncher(this)

        lifecycleScope.launch {
            openScaleService.init()
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
                syncService.init()
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
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()

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
                                if (title == "Overview") {
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
                                currentTitle.value = "Overview"
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
                                currentTitle.value = "About"
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
                    contentDescription = "to",
                    modifier = Modifier.size(32.dp)
                )

                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "to",
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
                Text("Maintainer", style = MaterialTheme.typography.titleMedium)
                Text("olie.xdev <olie.xdev@googlemail.com>", style = MaterialTheme.typography.bodyMedium)
            }
            Column (
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Website", style = MaterialTheme.typography.titleMedium)
                Text("https://github.com/oliexdev/openScale/wiki/openScale-sync", style = MaterialTheme.typography.bodyMedium)
            }
            Column (
                modifier = Modifier.padding(16.dp)
            ) {
                Text("License", style = MaterialTheme.typography.titleMedium)
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
                    contentDescription = "App Icon",
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "openScale sync")
            }
            NavigationDrawerItem(
                label = { Text("Overview") },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = "Overview"
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
                label = { Text("About") },
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_about),
                        contentDescription = "About"
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
                    if (syncService.viewModel().syncEnabled.value) {
                        lifecycleScope.launch {
                            openScaleDataService.checkVersion()
                            syncService.sync(openScaleDataService.getMeasurements(openScaleService.getSelectedUser()))
                            syncService.viewModel().setLastSync(Instant.now())
                        }
                    }
                },
                containerColor = if (syncService.viewModel().syncEnabled.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.inverseOnSurface,
                contentColor = if (syncService.viewModel().syncEnabled.value) MaterialTheme.colorScheme.onSecondaryContainer else Color.Gray,
                text = {
                    Text(
                        text = "Fully sync",
                        color = if (syncService.viewModel().syncEnabled.value) MaterialTheme.colorScheme.onSecondaryContainer else Color.Gray
                    )
                },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Full sync",
                        tint = if (syncService.viewModel().syncEnabled.value) MaterialTheme.colorScheme.onSecondaryContainer else Color.Gray
                    )
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
                                "Last sync never",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (syncService.viewModel().syncEnabled.value) MaterialTheme.colorScheme.onPrimary else Color.Gray
                            )
                        } else {
                            val dateFormat = DateFormat.getDateFormat(applicationContext)
                            val timeFormat = DateFormat.getTimeFormat(applicationContext)
                            val timeDateFormat = dateFormat.format(Date.from(lastSync)) + " " + timeFormat.format(Date.from(lastSync))
                            Text(
                                "Last sync $timeDateFormat",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (syncService.viewModel().syncEnabled.value) MaterialTheme.colorScheme.onPrimary else Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}

