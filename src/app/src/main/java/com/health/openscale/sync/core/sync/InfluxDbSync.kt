package com.health.openscale.sync.core.sync

import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.service.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date

class InfluxDbSync(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val isV2: Boolean,
    private val org: String,
    private val bucket: String,
    private val token: String,
    private val database: String,
    private val dbUsername: String,
    private val dbPassword: String,
    private val measurementName: String
) : SyncInterface() {

    // Escape a value for use as an InfluxDB line-protocol tag value (comma, equals, space).
    private fun escapeTag(value: String): String =
        value.replace("\\", "\\\\").replace(",", "\\,").replace("=", "\\=").replace(" ", "\\ ")

    private fun toLineProtocol(m: OpenScaleMeasurement): String {
        // userId is the stable per-user tag; username is added as a readable tag when present.
        val userTags = ",user_id=${m.userId}" +
            (if (m.username.isNotBlank()) ",user=${escapeTag(m.username)}" else "")
        // Phase 2: emit the full generic value set as fields (all types incl. custom, keyed by
        // backendKey()); fall back to the legacy fixed fields for an older openScale (no values).
        val numeric = m.values.filter { it.value != null }
        val fields = if (numeric.isNotEmpty()) {
            numeric.joinToString(",") { "${it.backendKey()}=${it.value}" }
        } else {
            // Fallback (values should always be present); keep the core fixed fields.
            OpenScaleMeasurement.CANONICAL_METRICS.joinToString(",") { "${it.backendKey}=${it.accessor(m)}" }
        }
        return "$measurementName,source=openscale$userTags $fields ${m.date.time * 1_000_000L}"
    }

    private fun Request.Builder.addAuth(): Request.Builder = when {
        isV2 -> addHeader("Authorization", "Token $token")
        dbUsername.isNotBlank() -> addHeader("Authorization", Credentials.basic(dbUsername, dbPassword))
        else -> this
    }

    suspend fun writePoints(measurements: List<OpenScaleMeasurement>): SyncResult<Unit> = withContext(Dispatchers.IO) {
        if (measurements.isEmpty()) return@withContext SyncResult.Success(Unit)
        val body = measurements.joinToString("\n") { toLineProtocol(it) }
        val url = if (isV2)
            "$baseUrl/api/v2/write?org=${enc(org)}&bucket=${enc(bucket)}&precision=ns"
        else
            "$baseUrl/write?db=${enc(database)}&precision=ns"
        runRequest(Request.Builder().url(url).addAuth()
            .post(body.toRequestBody("text/plain; charset=utf-8".toMediaType())).build())
    }

    suspend fun writePoint(measurement: OpenScaleMeasurement): SyncResult<Unit> =
        writePoints(listOf(measurement))

    suspend fun deleteByTimestamp(userId: Int, date: Date): SyncResult<Unit> = withContext(Dispatchers.IO) {
        if (isV2) {
            val instant = Instant.ofEpochMilli(date.time)
            val start = DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))
            val stop = DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1))
            val json = """{"start":"$start","stop":"$stop","predicate":"_measurement=\"$measurementName\" AND user_id=\"$userId\""}"""
            val url = "$baseUrl/api/v2/delete?org=${enc(org)}&bucket=${enc(bucket)}"
            runRequest(Request.Builder().url(url).addAuth()
                .post(json.toRequestBody("application/json".toMediaType())).build())
        } else {
            val tsNs = date.time * 1_000_000L
            val q = """DELETE FROM "$measurementName" WHERE time = $tsNs AND user_id = '$userId'"""
            val url = "$baseUrl/query?db=${enc(database)}"
            runRequest(Request.Builder().url(url).addAuth()
                .post("q=${enc(q)}".toRequestBody("application/x-www-form-urlencoded".toMediaType())).build())
        }
    }

    suspend fun deleteAll(userId: Int): SyncResult<Unit> = withContext(Dispatchers.IO) {
        if (isV2) {
            val json = """{"start":"1970-01-01T00:00:00Z","stop":"2100-01-01T00:00:00Z","predicate":"_measurement=\"$measurementName\" AND user_id=\"$userId\""}"""
            val url = "$baseUrl/api/v2/delete?org=${enc(org)}&bucket=${enc(bucket)}"
            runRequest(Request.Builder().url(url).addAuth()
                .post(json.toRequestBody("application/json".toMediaType())).build())
        } else {
            val q = """DELETE FROM "$measurementName" WHERE user_id = '$userId'"""
            val url = "$baseUrl/query?db=${enc(database)}"
            runRequest(Request.Builder().url(url).addAuth()
                .post("q=${enc(q)}".toRequestBody("application/x-www-form-urlencoded".toMediaType())).build())
        }
    }

    suspend fun testConnection(): SyncResult<Unit> = withContext(Dispatchers.IO) {
        runRequest(Request.Builder().url("$baseUrl/ping").addAuth().get().build())
    }

    private fun runRequest(request: Request): SyncResult<Unit> = try {
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful || response.code == 204) SyncResult.Success(Unit)
            else SyncResult.Failure(SyncResult.ErrorType.API_ERROR,
                "HTTP ${response.code}: ${response.body.string().take(200)}")
        }
    } catch (e: Exception) {
        SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, cause = e)
    }

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
