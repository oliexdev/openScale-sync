/**
 * Copyright (C) 2019 by olie.xdev@googlemail.com All Rights Reserved
 */
package com.health.openscale.sync.core.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.units.Mass
import com.health.openscale.sync.core.datatypes.ScaleMeasurement
import com.health.openscale.sync.gui.view.StatusViewAdapter
import timber.log.Timber
import java.time.ZoneId
import java.util.Date

class HealthConnectSync(val context: Context) : ScaleMeasurementSync(context) {

    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class)
    )

    val requestPermissionActivityContract = PermissionController.createRequestPermissionResultContract()

    override fun getName(): String {
        return "HealthConnectSync"
    }

    override fun isEnable(): Boolean {
        return prefs.getBoolean("enableHealthConnect", true)
    }

    override suspend fun insert(measurement: ScaleMeasurement) {
        val time = measurement.date.toInstant().atZone(ZoneId.systemDefault())
        val weightRecord = WeightRecord(
            weight = Mass.kilograms(measurement.weight.toDouble()),
            time = time.toInstant(),
            zoneOffset = time.offset
        )
        val records = listOf(weightRecord)
        try {
            //healthConnectClient.insertRecords(records)
            Toast.makeText(context, "Successfully insert records", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, e.message.toString(), Toast.LENGTH_SHORT).show()
        }
    }

    override fun delete(date: Date) {

    }

    override fun clear() {

    }

    override fun update(measurement: ScaleMeasurement) {

    }

    override fun hasPermission(): Boolean {
        val healthConnectClient = detectHealthConnect()

        if (healthConnectClient != null) {
            Timber.d("Health Connect found")
        }

        return false
    }

    override fun askPermission(context: ComponentActivity) {
        context.registerForActivityResult(requestPermissionActivityContract) { granted ->
            if (granted.containsAll(PERMISSIONS)) {
                Timber.d("Health Connect permissions granted")
            } else {
                Timber.d("Health Connect permissions not granted")
            }
        }
    }

    override fun checkStatus(statusView: StatusViewAdapter) {
        Timber.d("Check Health Connect sync status")

    }




    suspend fun checkPermissionsAndRun(healthConnectClient: HealthConnectClient) {
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        if (granted.containsAll(PERMISSIONS)) {
            // Permissions already granted; proceed with inserting or reading data
        } else {
           // requestPermissions.launch(PERMISSIONS)
        }
    }

    fun detectHealthConnect(): HealthConnectClient? {
        val availabilityStatus = HealthConnectClient.getSdkStatus(context)
        if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE) {
            return null // early return as there is no viable integration
        }
        if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
            // Optionally redirect to package installer to find a provider, for example:
            val uriString = "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setPackage("com.android.vending")
                    data = Uri.parse(uriString)
                    putExtra("overlay", true)
                    putExtra("callerId", context.packageName)
                }
            )
            return null
        }

        return HealthConnectClient.getOrCreate(context)

    }
}
