/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.core.sync

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import com.health.openscale.sync.core.datatypes.ScaleMeasurement
import com.health.openscale.sync.gui.view.StatusViewAdapter
import java.util.Date

abstract class ScaleMeasurementSync(context: Context) {
    @JvmField
    protected var prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    abstract fun getName(): String
    abstract fun isEnable(): Boolean
    abstract suspend fun insert(measurement: ScaleMeasurement)
    abstract fun delete(date: Date)
    abstract fun clear()
    abstract fun update(measurement: ScaleMeasurement)
    abstract fun hasPermission() : Boolean
    abstract fun askPermission(context: ComponentActivity)
    abstract fun checkStatus(statusView: StatusViewAdapter)
}
