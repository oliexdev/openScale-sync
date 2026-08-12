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
package com.health.openscale.sync.gui.permission

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.health.openscale.sync.R
import com.health.openscale.sync.gui.theme.OpenScaleSyncTheme

/**
 * The Health Connect permissions rationale, reached from the Health Connect permission dialog
 * (ACTION_SHOW_PERMISSIONS_RATIONALE on Android 13 and below, the ViewPermissionUsageActivity alias
 * on 14 and later — see AndroidManifest).
 *
 * Health Connect policy requires this screen to say how the data is used, per direction, which is
 * why it spells out what is written and what is read rather than only linking the privacy policy.
 * The read section names the setting that turns reading on, because with the default Export
 * direction the app requests no read permissions at all (HealthConnectService.neededPermissions).
 */
class PermissionsRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenScaleSyncTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Column (
                        modifier = Modifier.fillMaxSize()
                    ){
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
                            Text(text = "openScale sync", color = MaterialTheme.colorScheme.onPrimary)
                        }

                        Column (
                            modifier = Modifier
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState())
                        ){
                            Text(
                                text = stringResource(id = R.string.rationale_title),
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(id = R.string.rationale_intro),
                                style = MaterialTheme.typography.bodyMedium
                            )

                            Section(R.string.rationale_write_title, R.string.rationale_write_text)
                            Section(R.string.rationale_read_title, R.string.rationale_read_text)
                            Section(R.string.rationale_privacy_title, R.string.rationale_privacy_text)

                            Spacer(modifier = Modifier.height(16.dp))

                            val linkText = stringResource(id = R.string.rationale_privacy_link)
                            val url = stringResource(id = R.string.rationale_privacy_url)
                            Text(buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        color = MaterialTheme.colorScheme.primary,
                                        textDecoration = TextDecoration.Underline
                                    )
                                ) {
                                    append(linkText)
                                }
                                addLink(LinkAnnotation.Url(url), 0, linkText.length)
                            })
                        }
                    }
                }
            }
        }
    }

    /** One titled paragraph of the rationale (written / read / privacy). */
    @Composable
    private fun Section(titleRes: Int, textRes: Int) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(id = titleRes),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(id = textRes),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}