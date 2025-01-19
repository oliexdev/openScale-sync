package com.health.openscale.sync.core.model

import android.content.SharedPreferences
import com.health.openscale.sync.R

class HealthConnectViewModel(private val sharedPreferences: SharedPreferences) : ViewModelInterface(sharedPreferences) {
    override fun getName(): String {
        return "Health Connect"
    }

    override fun getIcon(): Int {
        return R.drawable.ic_health_connect
    }
}