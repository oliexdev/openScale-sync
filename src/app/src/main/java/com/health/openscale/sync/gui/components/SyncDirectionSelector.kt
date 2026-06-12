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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.health.openscale.sync.R
import com.health.openscale.sync.core.model.SyncDirection

/**
 * Per-backend sync direction picker (export / import / both) for inbound-capable backends
 * (HealthConnect, Wger, MQTT). Bound to ViewModelInterface.syncDirection.
 *
 * A Material 3 single-choice segmented control with a consistent directional icon vocabulary
 * (⇅ both, → export, ← import). The title spells out the active data flow with the concrete
 * backend name in parentheses, e.g. "Sync direction (openScale → MQTT)".
 */
@Composable
fun SyncDirectionSelector(
    current: SyncDirection,
    onChange: (SyncDirection) -> Unit,
    enabled: Boolean,
    serviceName: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = stringResource(id = R.string.sync_direction_title) +
                " (" + stringResource(id = current.flowDescription(), serviceName) + ")",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SyncDirection.entries.forEachIndexed { index, dir ->
                SegmentedButton(
                    selected = current == dir,
                    onClick = { onChange(dir) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, SyncDirection.entries.size),
                    icon = {
                        Icon(
                            imageVector = dir.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                ) {
                    Text(stringResource(id = dir.shortLabel()))
                }
            }
        }
    }
}

private fun SyncDirection.icon(): ImageVector = when (this) {
    SyncDirection.EXPORT -> Icons.Filled.Upload
    SyncDirection.IMPORT -> Icons.Filled.Download
    SyncDirection.BOTH -> Icons.Filled.SyncAlt
}

private fun SyncDirection.shortLabel(): Int = when (this) {
    SyncDirection.EXPORT -> R.string.sync_direction_export
    SyncDirection.IMPORT -> R.string.sync_direction_import
    SyncDirection.BOTH -> R.string.sync_direction_both
}

private fun SyncDirection.flowDescription(): Int = when (this) {
    SyncDirection.EXPORT -> R.string.sync_direction_export_desc
    SyncDirection.IMPORT -> R.string.sync_direction_import_desc
    SyncDirection.BOTH -> R.string.sync_direction_both_desc
}
