package com.health.openscale.sync.core.model

import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.health.openscale.sync.R

class InfluxDbViewModel(private val sharedPreferences: SharedPreferences) : ViewModelInterface(sharedPreferences) {

    private val _url = MutableLiveData<String>(sharedPreferences.getString("influxdb_url", "http://192.168.1.100:8086"))
    private val _isV2 = MutableLiveData<Boolean>(sharedPreferences.getBoolean("influxdb_is_v2", true))
    private val _org = MutableLiveData<String>(sharedPreferences.getString("influxdb_org", ""))
    private val _bucket = MutableLiveData<String>(sharedPreferences.getString("influxdb_bucket", "openscale"))
    private val _token = MutableLiveData<String>(sharedPreferences.getString("influxdb_token", ""))
    private val _database = MutableLiveData<String>(sharedPreferences.getString("influxdb_database", "openscale"))
    private val _dbUsername = MutableLiveData<String>(sharedPreferences.getString("influxdb_username", ""))
    private val _dbPassword = MutableLiveData<String>(sharedPreferences.getString("influxdb_password", ""))
    private val _measurement = MutableLiveData<String>(sharedPreferences.getString("influxdb_measurement", "openscale"))
    private val _connecting = MutableLiveData<Boolean>(false)

    override fun getName(): String = "InfluxDB"
    override fun getIcon(): Int = R.drawable.ic_influxdb

    val url: LiveData<String> = _url
    fun setUrl(value: String) { _url.value = value; sharedPreferences.edit().putString("influxdb_url", value).apply() }

    val isV2: LiveData<Boolean> = _isV2
    fun setIsV2(value: Boolean) { _isV2.value = value; sharedPreferences.edit().putBoolean("influxdb_is_v2", value).apply() }

    val org: LiveData<String> = _org
    fun setOrg(value: String) { _org.value = value; sharedPreferences.edit().putString("influxdb_org", value).apply() }

    val bucket: LiveData<String> = _bucket
    fun setBucket(value: String) { _bucket.value = value; sharedPreferences.edit().putString("influxdb_bucket", value).apply() }

    val token: LiveData<String> = _token
    fun setToken(value: String) { _token.value = value; sharedPreferences.edit().putString("influxdb_token", value).apply() }

    val database: LiveData<String> = _database
    fun setDatabase(value: String) { _database.value = value; sharedPreferences.edit().putString("influxdb_database", value).apply() }

    val dbUsername: LiveData<String> = _dbUsername
    fun setDbUsername(value: String) { _dbUsername.value = value; sharedPreferences.edit().putString("influxdb_username", value).apply() }

    val dbPassword: LiveData<String> = _dbPassword
    fun setDbPassword(value: String) { _dbPassword.value = value; sharedPreferences.edit().putString("influxdb_password", value).apply() }

    val measurement: LiveData<String> = _measurement
    fun setMeasurement(value: String) { _measurement.value = value; sharedPreferences.edit().putString("influxdb_measurement", value).apply() }

    val connecting: LiveData<Boolean> = _connecting
    fun setConnecting(value: Boolean) { _connecting.value = value }
}
