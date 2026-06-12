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
package com.health.openscale.sync.gui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.health.openscale.sync.R
import com.health.openscale.sync.core.datatypes.OpenScaleUser

/**
 * Per-service openScale user picker for single-user backends (HealthConnect, Wger).
 * Bound to the service's ViewModelInterface.selectedUserId; multi-user backends don't use this.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSelectionDropdown(
    users: List<OpenScaleUser>,
    selectedUserId: Int,
    onUserSelected: (OpenScaleUser) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = users.firstOrNull { it.id == selectedUserId } ?: users.firstOrNull()

    ExposedDropdownMenuBox(
        modifier = modifier.fillMaxWidth(),
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = !expanded }
    ) {
        TextField(
            label = { Text(stringResource(id = R.string.open_scale_user_text)) },
            value = selected?.username ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth()
        )

        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            users.forEach { user ->
                DropdownMenuItem(
                    text = { Text(user.username) },
                    onClick = {
                        onUserSelected(user)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Consistent "Synced users" section shown on every backend's settings screen, so the picker-vs-no-
 * picker difference becomes a self-explanatory contrast instead of a silent gap:
 * - single-user backends (HealthConnect, Wger) show the [UserSelectionDropdown] + a one-line caption,
 * - multi-user backends (MQTT, Webhook, InfluxDB) show a read-only info row stating that every
 *   openScale user is synced (and kept separate) — clearly a fact, not a disabled control.
 */
@Composable
fun UserScopeSection(
    isMultiUser: Boolean,
    users: List<OpenScaleUser>,
    selectedUserId: Int,
    onUserSelected: (OpenScaleUser) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = stringResource(id = R.string.sync_users_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        if (isMultiUser) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Groups,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(id = R.string.sync_users_all),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(id = R.string.sync_users_all_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            LaunchedEffect(users) {
                if (selectedUserId == -1 && users.isNotEmpty()) onUserSelected(users.first())
            }
            if (users.isNotEmpty()) {
                UserSelectionDropdown(
                    users = users,
                    selectedUserId = selectedUserId,
                    onUserSelected = onUserSelected,
                    enabled = enabled
                )
                Text(
                    text = stringResource(id = R.string.sync_users_single_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
