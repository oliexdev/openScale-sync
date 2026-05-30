package com.health.openscale.sync.core.service

import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for the list of sync backends.
 *
 * The order matters: it drives both the settings UI / overview order and the
 * real-time dispatch order. Adding a backend means adding it here once — there is
 * no second registration site to keep in sync.
 */
object BackendRegistry {
    fun create(context: Context, prefs: SharedPreferences): List<ServiceInterface> = listOf(
        HealthConnectService(context, prefs),
        MQTTService(context, prefs),
        WgerService(context, prefs),
        InfluxDbService(context, prefs),
        WebhookService(context, prefs)
    )
}
