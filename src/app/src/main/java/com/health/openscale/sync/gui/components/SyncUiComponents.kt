package com.health.openscale.sync.gui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.health.openscale.sync.R
import com.health.openscale.sync.core.service.ServiceInterface
import kotlinx.coroutines.launch

/** Transient success/info messages are shown via a Snackbar provided by MainActivity. */
val LocalSnackbar = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * OutlinedTextField for secrets (API tokens / passwords) with an eye toggle, so the user
 * can verify what they pasted. Material3 has no dedicated password field — the standard
 * approach is a normal text field + PasswordVisualTransformation + custom trailing toggle;
 * this wrapper just avoids duplicating that across services.
 */
@Composable
fun SecretOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    painter = painterResource(
                        if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility
                    ),
                    contentDescription = stringResource(if (visible) R.string.field_hide else R.string.field_show)
                )
            }
        },
        modifier = modifier
    )
}

/** Consistent full-width primary action button with a busy state, used on every detail screen. */
@Composable
fun SyncConnectButton(
    text: String,
    connectingText: String,
    connecting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = { if (!connecting) onClick() },
        enabled = enabled && !connecting,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (connecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
            Text(connectingText)
        } else {
            Text(text)
        }
    }
}

/**
 * Grouped action area for a service detail screen: a primary "Sync now" (full sync) button
 * and a secondary outlined "Test connection" button, both with icons. Replaces the old
 * floating full-sync FAB. Full-sync is handled here via [ServiceInterface.runFullSync];
 * the service supplies its own [onTest] (which surfaces success via the snackbar itself).
 */
@Composable
fun SyncActionButtons(
    service: ServiceInterface,
    activity: ComponentActivity,
    testConnecting: Boolean,
    onTest: () -> Unit
) {
    val showMessage = LocalSnackbar.current
    var fullSyncing by remember { mutableStateOf(false) }
    val enabled = service.viewModel().syncEnabled.value

    OutlinedButton(
        onClick = { if (!testConnecting) onTest() },
        enabled = enabled && !testConnecting && !fullSyncing,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (testConnecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp
            )
        } else {
            Icon(painter = painterResource(R.drawable.ic_link), contentDescription = null)
        }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (testConnecting) R.string.service_connecting else R.string.service_test_connection))
    }

    Button(
        onClick = {
            if (!fullSyncing) {
                fullSyncing = true
                activity.lifecycleScope.launch {
                    val count = service.runFullSync()
                    if (count != null) showMessage(activity.resources.getQuantityString(R.plurals.sync_service_full_synced_info, count, count))
                    fullSyncing = false
                }
            }
        },
        enabled = enabled && !fullSyncing && !testConnecting,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (fullSyncing) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
        } else {
            Icon(imageVector = Icons.Filled.Refresh, contentDescription = null)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (fullSyncing) stringResource(R.string.sync_service_syncing_text)
            else stringResource(R.string.sync_service_full_sync_button, service.viewModel().getName())
        )
    }
}

/** Card shown when a backend has failed ops waiting in its retry queue. */
@Composable
fun PendingQueueCard(service: ServiceInterface, activity: ComponentActivity) {
    var pending by remember { mutableIntStateOf(service.pendingRetryCount()) }
    if (pending <= 0) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                pluralStringResource(R.plurals.retry_pending_title, pending, pending),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            Row {
                TextButton(onClick = {
                    activity.lifecycleScope.launch {
                        service.retryPending()
                        pending = service.pendingRetryCount()
                    }
                }) { Text(stringResource(R.string.retry_now_button)) }
                TextButton(onClick = {
                    service.clearPending()
                    pending = 0
                }) { Text(stringResource(R.string.retry_discard_button)) }
            }
        }
    }
}

/** Inline error banner (Material 3 error container + icon) shown below a detail screen's action button. */
@Composable
fun SyncErrorBanner(message: String?) {
    if (message.isNullOrBlank()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
