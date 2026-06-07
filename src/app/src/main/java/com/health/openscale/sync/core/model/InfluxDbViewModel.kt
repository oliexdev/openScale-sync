package com.health.openscale.sync.core.model

import androidx.core.content.edit
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.health.openscale.sync.R

class InfluxDbViewModel(private val sharedPreferences: SharedPreferences) : ViewModelInterface(sharedPreferences) {
    companion object {
        const val URL = "influxdb_url"
        const val IS_V2 = "influxdb_is_v2"
        const val ORG = "influxdb_org"
        const val BUCKET = "influxdb_bucket"
        const val TOKEN = "influxdb_token"
        const val DATABASE = "influxdb_database"
        const val USERNAME = "influxdb_username"
        const val PASSWORD = "influxdb_password"
        const val MEASUREMENT = "influxdb_measurement"
    }

    private val _url = MutableLiveData<String>(sharedPreferences.getString(URL, ""))
    private val _isV2 = MutableLiveData<Boolean>(sharedPreferences.getBoolean(IS_V2, true))
    private val _org = MutableLiveData<String>(sharedPreferences.getString(ORG, ""))
    private val _bucket = MutableLiveData<String>(sharedPreferences.getString(BUCKET, "openscale"))
    private val _token = MutableLiveData<String>(sharedPreferences.getString(TOKEN, ""))
    private val _database = MutableLiveData<String>(sharedPreferences.getString(DATABASE, "openscale"))
    private val _dbUsername = MutableLiveData<String>(sharedPreferences.getString(USERNAME, ""))
    private val _dbPassword = MutableLiveData<String>(sharedPreferences.getString(PASSWORD, ""))
    private val _measurement = MutableLiveData<String>(sharedPreferences.getString(MEASUREMENT, "openscale"))
    private val _connecting = MutableLiveData<Boolean>(false)

    override fun getName(): String = "InfluxDB"
    override fun getIcon(): Int = R.drawable.ic_influxdb

    val url: LiveData<String> = _url
    fun setUrl(value: String) { _url.value = value; sharedPreferences.edit { putString(URL, value) } }

    val isV2: LiveData<Boolean> = _isV2
    fun setIsV2(value: Boolean) { _isV2.value = value; sharedPreferences.edit { putBoolean(IS_V2, value) } }

    val org: LiveData<String> = _org
    fun setOrg(value: String) { _org.value = value; sharedPreferences.edit { putString(ORG, value) } }

    val bucket: LiveData<String> = _bucket
    fun setBucket(value: String) { _bucket.value = value; sharedPreferences.edit { putString(BUCKET, value) } }

    val token: LiveData<String> = _token
    fun setToken(value: String) { _token.value = value; sharedPreferences.edit { putString(TOKEN, value) } }

    val database: LiveData<String> = _database
    fun setDatabase(value: String) { _database.value = value; sharedPreferences.edit { putString(DATABASE, value) } }

    val dbUsername: LiveData<String> = _dbUsername
    fun setDbUsername(value: String) { _dbUsername.value = value; sharedPreferences.edit { putString(USERNAME, value) } }

    val dbPassword: LiveData<String> = _dbPassword
    fun setDbPassword(value: String) { _dbPassword.value = value; sharedPreferences.edit { putString(PASSWORD, value) } }

    val measurement: LiveData<String> = _measurement
    fun setMeasurement(value: String) { _measurement.value = value; sharedPreferences.edit { putString(MEASUREMENT, value) } }

    val connecting: LiveData<Boolean> = _connecting
    fun setConnecting(value: Boolean) { _connecting.value = value }
}
