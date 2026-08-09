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
package com.health.openscale.sync.core.provider

import androidx.core.net.toUri
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager.PERMISSION_GRANTED
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.health.openscale.sync.R
import com.health.openscale.sync.core.model.OpenScaleViewModel
import com.health.openscale.sync.core.model.ViewModelInterface
import com.health.openscale.sync.gui.components.LocalSnackbar
import timber.log.Timber

/**
 * One installed openScale variant, as the picker shows it.
 *
 * [userCount] and [measurementCount] are null when this variant's ContentProvider could not be read
 * — practically always a missing READ_WRITE_DATA grant for that package. That is not an error: the
 * variant stays selectable, and the permission is requested right after the switch.
 */
data class OpenScaleVariant(
    val packageName: String,
    val labelRes: Int,
    val versionName: String?,
    val userCount: Int?,
    val measurementCount: Int?
) {
    val readable: Boolean get() = userCount != null
}

class OpenScaleProvider (
    private val context: Context,
    openScaleDataService : OpenScaleDataProvider,
    private val sharedPreferences: SharedPreferences
) {
    private val viewModel: OpenScaleViewModel = OpenScaleViewModel(sharedPreferences)//ViewModelProvider(context)[OpenScaleViewModel::class.java]

    /**
     * Recomputed on every access: the permission is named after the selected openScale variant, so
     * a cached value would keep asking for the previous variant's grant after a switch.
     */
    private val requiredPermissions: String
        get() = sharedPreferences.getString(OpenScaleViewModel.PACKAGE_NAME, "com.health.openscale") + ".READ_WRITE_DATA"

    private lateinit var requestPermission : ActivityResultLauncher<String>

    /**
     * Called with the newly chosen package when the user switches openScale variants. MainActivity
     * stores the choice and resets the backends' id-keyed state; this class only owns the UI.
     */
    var onVariantChanged: ((String) -> Unit)? = null

    /**
     * The installed openScale variants, as Compose state. Scanning them costs a PackageManager
     * lookup plus a provider query per candidate, so it happens on [refreshVariants] — not on every
     * recomposition — and the UI just reads the result.
     */
    private val _variants = mutableStateOf<List<OpenScaleVariant>>(emptyList())

    /**
     * Rescan which openScale variants are installed. Called on every start AND every resume:
     * openScale can be installed, updated or removed while this app sits in the background, and a
     * stale list would either hide a fresh install or offer one that is gone.
     */
    fun refreshVariants() {
        _variants.value = installedVariants(context, sharedPreferences)
        viewModel.setConnectAvailable(_variants.value.isNotEmpty())
    }

    companion object {
        /**
         * Every known openScale flavor, in the order the automatic pick falls back to when the data
         * cannot break the tie. A debug build ranks last on purpose — it used to outrank both the
         * F-Droid and the Pro install, so testing openScale once was enough to silently redirect
         * the sync.
         *
         * They are separate apps with separate databases, which is why the choice matters at all:
         * measurement and user ids only mean something within one install.
         */
        private val KNOWN_VARIANTS = listOf(
            "com.health.openscale.pro" to R.string.open_scale_variant_pro,
            "com.health.openscale" to R.string.open_scale_variant_default,
            "com.health.openscale.oss" to R.string.open_scale_variant_oss,
            "com.health.openscale.beta" to R.string.open_scale_variant_beta,
            "com.health.openscale.light" to R.string.open_scale_variant_light,
            "com.health.openscale.debug" to R.string.open_scale_variant_debug
        )

        /** The openScale variants present on this device, each described as far as it can be read. */
        fun installedVariants(context: Context, prefs: SharedPreferences): List<OpenScaleVariant> =
            KNOWN_VARIANTS.filter { (pkg, _) -> isInstalled(context, pkg) }
                .map { (pkg, labelRes) -> describeVariant(context, prefs, pkg, labelRes) }

        private fun isInstalled(context: Context, packageName: String): Boolean =
            try {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }

        private fun describeVariant(
            context: Context, prefs: SharedPreferences, packageName: String, labelRes: Int
        ): OpenScaleVariant {
            val provider = OpenScaleDataProvider(context, prefs, packageOverride = packageName)
            // A candidate we may hold no permission for — every read here is best-effort.
            val users = runCatching { provider.getUsers() }.getOrNull()
            return OpenScaleVariant(
                packageName = packageName,
                labelRes = labelRes,
                versionName = provider.getInstalledVersionName(),
                userCount = users?.size,
                measurementCount = users?.sumOf { provider.countMeasurements(it.id) ?: 0 }
            )
        }

        /**
         * The variant to sync with, given what is installed and what was chosen before.
         *
         * A stored choice wins for as long as that variant is still installed — otherwise the
         * picker would be pointless, since resolution runs on every app start. Without a usable
         * choice the one holding the most measurements wins, because that is what "the openScale
         * with my data" means; [KNOWN_VARIANTS] order only breaks ties (fresh installs, or
         * candidates we cannot read).
         */
        fun resolveVariant(installed: List<OpenScaleVariant>, stored: String?): String? {
            if (installed.isEmpty()) return null
            installed.firstOrNull { it.packageName == stored }?.let { return it.packageName }
            return installed.maxByOrNull { it.measurementCount ?: 0 }?.packageName
        }

        /**
         * Make sure a usable variant is stored, and return it.
         *
         * For the headless service, which can run before the app has ever been opened after an
         * openScale install — without this the stored package stays "null" and every provider read
         * goes to "null.provider". Deliberately cheap in the normal case: a package-manager lookup
         * for the stored variant, and a full scan only when that one is gone.
         */
        fun ensureVariantResolved(context: Context, prefs: SharedPreferences): String? {
            val stored = prefs.getString(OpenScaleViewModel.PACKAGE_NAME, null)?.takeIf { it != "null" }
            if (stored != null && isInstalled(context, stored)) return stored

            val resolved = resolveVariant(installedVariants(context, prefs), stored)
            prefs.edit { putString(OpenScaleViewModel.PACKAGE_NAME, resolved ?: "null") }
            Timber.i("openScale variant resolved to %s (was %s)", resolved, stored)
            return resolved
        }
    }

     fun init() {
        checkPermissionGranted()
    }

    fun viewModel(): ViewModelInterface {
        return viewModel
    }

    fun checkPermissionGranted() {
        if (ContextCompat.checkSelfPermission(context, requiredPermissions) == PERMISSION_GRANTED) {
            viewModel.setAllPermissionsGranted(true)
        } else {
            viewModel.setAllPermissionsGranted(false)
        }
    }

    /**
     * Whether this variant's permission has ever been asked for. Needed because
     * [shouldShowRequestPermissionRationale] answers "false" both BEFORE the first request and AFTER
     * a permanent denial — on its own it cannot tell those two apart. Kept per permission, since
     * each openScale variant has its own.
     */
    private val askedKey: String get() = "permissionAsked_$requiredPermissions"

    /**
     * True once Android will no longer show the system dialog: the user denied the permission for
     * good (on Android 11+ the second denial is enough). Requesting again then returns "denied"
     * immediately and nothing appears on screen, so the only remaining route is app settings.
     */
    fun isPermissionPermanentlyDenied(activity: ComponentActivity): Boolean =
        ContextCompat.checkSelfPermission(context, requiredPermissions) != PERMISSION_GRANTED &&
            sharedPreferences.getBoolean(askedKey, false) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, requiredPermissions)

    fun registerActivityResultLauncher(activity: ComponentActivity) {
        requestPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                sharedPreferences.edit { putBoolean(askedKey, true) }
                checkPermissionGranted()

                if (isGranted) {
                    Timber.d("openScale permission is granted")
                } else {
                    Timber.d("openScale permission is not granted")
                }
            }
    }

    fun requestPermissions() {
        requestPermission.launch(requiredPermissions)
    }

    @Composable
    fun ComposeSettings(activity: ComponentActivity) {
        // Full width and start-aligned, like every other settings section (see UserScopeSection):
        // centered prose next to full-width cards reads as two unrelated screens.
        Column(modifier = Modifier.fillMaxWidth()) {
            // Which openScale first, then whether we may read it — the permission is named after the
            // variant, so asking for it before the variant is settled would ask for the wrong one.
            VariantSection()

            // openScale availability / permission prompts only. The per-user selection now lives in
            // each single-user backend's settings (HealthConnect/Wger); multi-user backends sync all.
            if (!viewModel.connectAvailable.value) {
                ActionCard(
                    message = stringResource(id = R.string.open_scale_not_available_error),
                    actionLabel = stringResource(id = R.string.open_scale_get_open_scale_button),
                    onAction = { openAppStore(activity) }
                )
            } else if (!viewModel.allPermissionsGranted.value) {
                // Asking again is pointless once Android stopped showing the dialog — the button
                // would fire and visibly do nothing. Send the user where the switch actually is.
                if (isPermissionPermanentlyDenied(activity)) {
                    ActionCard(
                        message = stringResource(id = R.string.open_scale_permission_denied_permanently),
                        actionLabel = stringResource(id = R.string.open_scale_open_app_settings_button),
                        onAction = { openAppSettings(activity) }
                    )
                } else {
                    ActionCard(
                        message = stringResource(id = R.string.open_scale_permission_not_granted),
                        actionLabel = stringResource(id = R.string.open_scale_request_permissions_button),
                        onAction = { requestPermissions() }
                    )
                }
            }
        }
    }

    /**
     * A blocked state with the one action that unblocks it. Both cases here are missing
     * prerequisites, not failures, so they use the neutral surface rather than the error container
     * that [com.health.openscale.sync.gui.components.SyncErrorBanner] reserves for things that
     * actually went wrong — and they keep the message and its remedy in one card instead of leaving
     * a loose sentence above a loose button.
     */
    @Composable
    private fun ActionCard(message: String, actionLabel: String, onAction: () -> Unit) {
        Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            // fillMaxWidth is what makes the centred button below actually centred: without it the
            // Column is only as wide as the message row, and align() centres against that instead.
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(text = message, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                // A filled button, not a text one: this is the single action that unblocks the whole
                // screen, and nothing else on the card competes with it. Centred rather than
                // trailing, since it is the card's purpose and not one option among several.
                Button(
                    onClick = onAction,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) { Text(actionLabel) }
            }
        }
    }

    /**
     * Shows which openScale is being synced, and lets the user change it — but only when there is
     * something to change. With a single variant installed (the normal case) this is one read-only
     * line: information, not a decision to make.
     */
    @Composable
    private fun VariantSection() {
        val variants = _variants.value
        if (variants.isEmpty()) return

        val selectedPackage = viewModel.openScalePackage.value
        val selected = variants.firstOrNull { it.packageName == selectedPackage } ?: variants.first()
        var choosing by remember { mutableStateOf(false) }
        var pendingSwitch by remember { mutableStateOf<OpenScaleVariant?>(null) }

        val changeable = variants.size > 1

        Text(
            text = stringResource(id = R.string.open_scale_variant_section_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        // One card, one purpose: with a single openScale installed it states a fact, with several it
        // IS the control. No trailing button inside a tappable card — one target, not two nested.
        val cardModifier = Modifier.fillMaxWidth()
        OutlinedCard(
            modifier = if (changeable)
                cardModifier.clickable(
                    onClickLabel = stringResource(id = R.string.open_scale_variant_change_button)
                ) { choosing = true }
            else cardModifier
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_openscale_sync_monochrome),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    // Identity on top, data below — the old single line crammed name, version and
                    // counts into one string and gave none of them any weight.
                    Text(
                        text = stringResource(id = selected.labelRes),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = variantDetails(selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (changeable) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (choosing) {
            AlertDialog(
                onDismissRequest = { choosing = false },
                title = { Text(stringResource(id = R.string.open_scale_variant_choose_title)) },
                text = {
                    // selectableGroup() + Role.RadioButton make this an actual single-choice list to
                    // a screen reader ("2 of 3"), not three unrelated tappable rows.
                    Column(modifier = Modifier.selectableGroup()) {
                        variants.forEach { variant ->
                            val isSelected = variant.packageName == selected.packageName
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = isSelected,
                                        role = Role.RadioButton,
                                        onClick = {
                                            choosing = false
                                            // Switching is destructive enough to confirm; picking the
                                            // current one is a no-op, not a reset.
                                            if (!isSelected) pendingSwitch = variant
                                        }
                                    )
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = isSelected, onClick = null)
                                Column(modifier = Modifier.padding(start = 12.dp)) {
                                    Text(
                                        text = stringResource(id = variant.labelRes),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = variantDetails(variant),
                                        style = MaterialTheme.typography.bodySmall,
                                        // A candidate we cannot read yet is still selectable, but it
                                        // should not look as substantiated as one we counted.
                                        color = if (variant.readable)
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        else
                                            MaterialTheme.colorScheme.outline
                                    )
                                    // The package is the disambiguator, so it belongs here where
                                    // candidates are compared — not on the settings screen, where it
                                    // is just noise next to a name the user already recognises.
                                    Text(
                                        text = variant.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { choosing = false }) {
                        Text(stringResource(id = R.string.cancel))
                    }
                }
            )
        }

        pendingSwitch?.let { target ->
            val showMessage = LocalSnackbar.current
            val switchedMessage = stringResource(
                id = R.string.open_scale_variant_switched, stringResource(id = target.labelRes)
            )
            AlertDialog(
                onDismissRequest = { pendingSwitch = null },
                // The icon carries the weight the wording alone cannot: this discards state.
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_warning),
                        contentDescription = null
                    )
                },
                title = {
                    Text(stringResource(
                        id = R.string.open_scale_variant_switch_title,
                        stringResource(id = target.labelRes)
                    ))
                },
                text = { Text(stringResource(id = R.string.open_scale_variant_switch_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingSwitch = null
                        selectVariant(target)
                        showMessage(switchedMessage)
                    }) {
                        Text(stringResource(id = R.string.open_scale_variant_switch_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingSwitch = null }) {
                        Text(stringResource(id = R.string.cancel))
                    }
                }
            )
        }
    }

    /** "3.1.2 · 2 users, 1284 measurements" — the supporting line under the variant's name. The
     *  counts are what actually identify the install holding someone's data; a version alone does
     *  not, and an unreadable candidate says so instead of showing zeros. */
    @Composable
    private fun variantDetails(variant: OpenScaleVariant): String {
        val detail = if (variant.readable)
            stringResource(
                id = R.string.open_scale_variant_summary,
                variant.userCount ?: 0, variant.measurementCount ?: 0
            )
        else
            stringResource(id = R.string.open_scale_variant_no_access)

        return listOfNotNull(variant.versionName, detail).joinToString(" · ")
    }

    private fun selectVariant(variant: OpenScaleVariant) {
        onVariantChanged?.invoke(variant.packageName)
        viewModel.setOpenScalePackage(variant.packageName)
        viewModel.setConnectAvailable(true)
        // The permission is per package, so the new one has to be granted before anything can be read.
        checkPermissionGranted()
        if (!viewModel.allPermissionsGranted.value) {
            requestPermissions()
        }
    }

    /**
     * This app's system settings page, where a permanently denied permission can still be granted.
     * Returning from it lands in onResume, which re-checks the grant, so the card disappears on its
     * own once the user flipped the switch.
     */
    private fun openAppSettings(activity: ComponentActivity) {
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                }
            )
        }.onFailure { Timber.e(it, "cannot open app settings") }
    }

    private fun openAppStore(activity: ComponentActivity) {
        val packageName = "com.health.openscale.pro"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = "market://details?id=$packageName".toUri()
            setPackage("com.health.openscale.pro") // Google Play Store package
        }

        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW).apply {
                data = "https://github.com/oliexdev/openScale".toUri()
            }
            activity.startActivity(webIntent)
        }
    }

}