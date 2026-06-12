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
package com.health.openscale.sync.core.model

import android.content.SharedPreferences
import com.health.openscale.sync.R

class OpenScaleViewModel(sharedPreferences: SharedPreferences) : ViewModelInterface(sharedPreferences) {
    companion object {
        /** The app's main settings store. */
        const val SETTINGS_FILE = "openScaleSyncSettings"
        const val PACKAGE_NAME = "packageName"
        const val SELECTED_USER_ID = "selectedOpenScaleUserId"
        // Set by SyncService when the installed openScale is older than MIN_API_VERSION;
        // observed by the UI to show an "update openScale" warning banner.
        const val OPENSCALE_VERSION_UNSUPPORTED = "openScaleVersionUnsupported"
    }

    override fun getName(): String {
        return "OpenScale"
    }

    override fun getIcon(): Int {
        return R.drawable.ic_launcher_openscale_sync
    }
}