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

import androidx.core.content.edit
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.health.openscale.sync.core.service.BackendRegistry
import com.health.openscale.sync.core.service.ServiceInterface
import com.health.openscale.sync.core.service.SyncResult
import com.health.openscale.sync.core.model.OpenScaleViewModel
import com.health.openscale.sync.core.utils.LogManager
import com.health.openscale.sync.gui.components.LocalSnackbar
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
    private var snackbarHostStateRef: SnackbarHostState? = null

    private lateinit var saveLogLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        plant(DebugTree())

        currentTitle.value = getString(R.string.title_overview)
        val sharedPreferences: SharedPreferences = getSharedPreferences(OpenScaleViewModel.SETTINGS_FILE, MODE_PRIVATE)

        sharedPreferences.edit { putString(OpenScaleViewModel.PACKAGE_NAME, detectPackage()) }

        LogManager.init(this, sharedPreferences)

        openScaleDataService = OpenScaleDataProvider(this, sharedPreferences)
        openScaleService = OpenScaleProvider(this, openScaleDataService, sharedPreferences)

        openScaleService.registerActivityResultLauncher(this)

        lifecycleScope.launch {
            openScaleService.init()
        }

        if (sharedPreferences.getString(OpenScaleViewModel.PACKAGE_NAME, "null") == "null") {
            openScaleService.viewModel().setConnectAvailable(false)
        } else {
            openScaleService.viewModel().setConnectAvailable(true)
        }

        syncServiceList = BackendRegistry.create(applicationContext, sharedPreferences)

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

        saveLogLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri = result.data?.data
                if (uri == null) {
                    lifecycleScope.launch {
                        snackbarHostStateRef?.showSnackbar(
                            message = getString(R.string.logging_export_failed),
                            duration = SnackbarDuration.Short
                        )
                    }
                    return@registerForActivityResult
                }

                val ok = runCatching {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        LogManager.logFile(this).inputStream().use { input ->
                            input.copyTo(output)
                        }
                    } ?: error("No OutputStream")
                }.isSuccess

                lifecycleScope.launch {
                    snackbarHostStateRef?.showSnackbar(
                        message = if (ok) getString(R.string.log_saved_success)
                        else getString(R.string.logging_export_failed),
                        duration = SnackbarDuration.Short
                    )
                }
            } else {
                lifecycleScope.launch {
                    snackbarHostStateRef?.showSnackbar(
                        message = getString(R.string.log_saved_canceled),
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }


        setContent {
            ComposeMainView(this)
        }
    }


    private fun detectPackage(): String {
        if (doesExist("com.health.openscale")) {
            return "com.health.openscale"
        }

        if (doesExist("com.health.openscale.debug")) {
            return "com.health.openscale.debug"
        }

        if (doesExist("com.health.openscale.oss")) {
            return "com.health.openscale.oss"
        }

        if (doesExist("com.health.openscale.beta")) {
            return "com.health.openscale.beta"
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
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ComposeMainView(activity: ComponentActivity) {
        val snackbarHostState = remember { SnackbarHostState() }
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val versionCheckPerformed = remember { mutableStateOf(false) }

        snackbarHostStateRef = snackbarHostState

        DisposableEffect(snackbarHostState) {
            snackbarHostStateRef = snackbarHostState
            onDispose { snackbarHostStateRef = null }
        }

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
            CompositionLocalProvider(
                LocalSnackbar provides { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
            ) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = { NavigationDrawerSheet(drawerState, scope) }
            ) {
                Scaffold(
                    topBar = {
                        val title by currentTitle.observeAsState()
                        val service = title?.let { t -> syncServiceList.firstOrNull { it.viewModel().getName() == t } }
                        TopAppBar(
                            title = {
                                if (service != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            painter = painterResource(service.viewModel().getIcon()),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(text = service.viewModel().getName())
                                    }
                                } else {
                                    Text(text = title.toString())
                                }
                            },
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
                            actions = {
                                if (service != null) {
                                    Switch(
                                        checked = service.viewModel().syncEnabled.value,
                                        onCheckedChange = {
                                            service.viewModel().setSyncEnabled(it)
                                            if (it) scope.launch { service.init() }
                                        },
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
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
                                    syncService.ComposeSettings(activity)
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
    }

    @Composable
    fun HomeScreen(activity: ComponentActivity) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Source: openScale connection + user selector
                openScaleService.ComposeSettings(activity)

                Spacer(Modifier.height(8.dp))
                OverallStatusBanner()
                Spacer(Modifier.height(16.dp))

                Text(
                    stringResource(R.string.dashboard_services_header),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                syncServiceList.forEach { SyncServiceStatusRow(it) }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                GlobalSyncButton()
            }
        }
    }

    /** Single-glance answer to "is my data flowing?" */
    @Composable
    fun OverallStatusBanner() {
        val enabled = syncServiceList.filter { it.viewModel().syncEnabled.value }
        val pending = enabled.sumOf { it.pendingRetryCount() }
        val (text, color) = when {
            enabled.isEmpty() ->
                stringResource(R.string.dashboard_status_no_service) to MaterialTheme.colorScheme.onSurfaceVariant
            pending > 0 ->
                pluralStringResource(R.plurals.dashboard_status_pending, pending, pending) to MaterialTheme.colorScheme.error
            else ->
                stringResource(R.string.dashboard_status_all_synced) to MaterialTheme.colorScheme.primary
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = color)
    }

    /** One status row per backend. Tap → opens that service's detail screen. */
    @Composable
    fun SyncServiceStatusRow(syncService: ServiceInterface) {
        val vm = syncService.viewModel()
        val enabled = vm.syncEnabled.value
        val lastSync by vm.lastSync.observeAsState()
        val errorMessage by vm.errorMessage.observeAsState()
        val pending = syncService.pendingRetryCount()
        val canOpen = openScaleService.viewModel().allPermissionsGranted.value &&
                openScaleService.viewModel().connectAvailable.value

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            onClick = { if (canOpen) navController.navigate(vm.getName()) },
            enabled = canOpen
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = vm.getIcon()),
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        vm.getName(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val (statusText, statusColor) = when {
                        !enabled ->
                            stringResource(R.string.service_status_off) to MaterialTheme.colorScheme.onSurfaceVariant
                        pending > 0 ->
                            pluralStringResource(R.plurals.service_status_pending, pending, pending) to MaterialTheme.colorScheme.error
                        !errorMessage.isNullOrEmpty() ->
                            stringResource(R.string.service_status_error) to MaterialTheme.colorScheme.error
                        lastSync != null && lastSync?.toEpochMilli() != 0L -> {
                            val dateFormat = DateFormat.getDateFormat(applicationContext)
                            val timeFormat = DateFormat.getTimeFormat(applicationContext)
                            val ts = dateFormat.format(Date.from(lastSync)) + " " + timeFormat.format(Date.from(lastSync))
                            (stringResource(R.string.service_status_synced) + " · " + ts) to MaterialTheme.colorScheme.primary
                        }
                        else ->
                            stringResource(R.string.sync_service_last_sync_never_text) to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(statusText, style = MaterialTheme.typography.bodyMedium, color = statusColor)
                }
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    /** One global full-sync action instead of a FAB per card. */
    @Composable
    fun GlobalSyncButton() {
        var running by remember { mutableStateOf(false) }
        val showMessage = LocalSnackbar.current
        val canSync = syncServiceList.any { it.viewModel().syncEnabled.value } &&
                openScaleService.viewModel().connectAvailable.value

        Button(
            onClick = {
                if (!running) {
                    running = true
                    lifecycleScope.launch {
                        openScaleDataService.checkVersion()
                        val measurements = openScaleDataService.getMeasurements(openScaleService.getSelectedUser())
                        for (service in syncServiceList.filter { it.viewModel().syncEnabled.value }) {
                            val result = service.sync(measurements)
                            if (result is SyncResult.Success) {
                                service.viewModel().setLastSync(Instant.now())
                            } else {
                                service.setErrorMessage(result as SyncResult.Failure)
                            }
                        }
                        running = false
                        showMessage(resources.getQuantityString(R.plurals.sync_service_full_synced_info, measurements.size, measurements.size))
                    }
                }
            },
            enabled = canSync && !running,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sync_service_syncing_text))
            } else {
                Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.dashboard_sync_all_button))
            }
        }
    }

    @Composable
    fun AboutScreen() {
        Column {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_openscale_sync_foreground),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 16.dp)
                    .size(96.dp)
            )
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

            LoggingCard()
        }
    }

    @Composable
    fun LoggingCard() {
        val ctx = this@MainActivity
        val prefs = getSharedPreferences(OpenScaleViewModel.SETTINGS_FILE, MODE_PRIVATE)
        val scope = rememberCoroutineScope()

        val loggingEnabled = remember { mutableStateOf(LogManager.isEnabled(prefs)) }
        val hasFile = remember { mutableStateOf(LogManager.hasLogFile(ctx)) }

        LaunchedEffect(loggingEnabled.value) {
            hasFile.value = LogManager.hasLogFile(ctx)
        }

        Card(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(id = R.string.logging_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.padding(top = 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(id = R.string.logging_enabled_label))
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = loggingEnabled.value,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    LogManager.clearLog(ctx)
                                    scope.launch {
                                        snackbarHostStateRef?.showSnackbar(
                                            message = ctx.getString(R.string.log_new_file_created),
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                }
                                LogManager.setEnabled(ctx, prefs, checked)
                                loggingEnabled.value = checked
                                hasFile.value = LogManager.hasLogFile(ctx)
                            }
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    FilledTonalButton(
                        enabled = hasFile.value,
                        onClick = {
                            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TITLE, "openscale_sync_log.txt")
                            }
                            ctx.saveLogLauncher.launch(intent)
                        }
                    ) {
                        Text(stringResource(id = R.string.logging_export_button))
                    }
                }
            }
        }
    }


    @Composable
    fun NavigationDrawerSheet(drawerState : DrawerState, scope : CoroutineScope) {
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
                Text(text = "openScale sync", color = MaterialTheme.colorScheme.onPrimary)
            }

            val current by currentTitle.observeAsState()
            val overviewTitle = stringResource(id = R.string.title_overview)
            val aboutTitle = stringResource(id = R.string.title_about)

            NavigationDrawerItem(
                label = { Text(overviewTitle) },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = null
                    )
                },
                selected = current == overviewTitle,
                onClick = {
                    scope.launch { drawerState.close() }
                    navController.navigate("overview")
                }
            )

            for (syncService in syncServiceList) {
                val name = syncService.viewModel().getName()
                NavigationDrawerItem(
                    label = { Text(name) },
                    icon = {
                        Icon(
                            painter = painterResource(id = syncService.viewModel().getIcon()),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    selected = current == name,
                    onClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate(name)
                    }
                )
            }

            HorizontalDivider()

            NavigationDrawerItem(
                label = { Text(stringResource(id = R.string.title_about)) },
                icon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_about),
                        contentDescription = null
                    )
                },
                selected = current == aboutTitle,
                onClick = {
                    scope.launch { drawerState.close() }
                    navController.navigate("about")
                }
            )
        }
    }

}

