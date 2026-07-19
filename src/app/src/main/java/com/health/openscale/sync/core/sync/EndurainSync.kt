/*
 *  Copyright (C) 2026  Dany Mestas
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
package com.health.openscale.sync.core.sync

import com.google.gson.annotations.SerializedName
import com.health.openscale.sync.core.datatypes.OpenScaleMeasurement
import com.health.openscale.sync.core.service.SyncResult
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Narrow token accessor the wire layer depends on, so it never references the Android ViewModel that
 * actually stores the tokens ([com.health.openscale.sync.core.model.EndurainViewModel]).
 */
interface TokenStore {
    fun getAccessToken(): String?
    fun getRefreshToken(): String?
    fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long, refreshExpiresIn: Long)
    fun isAccessTokenExpired(): Boolean
    fun isRefreshTokenExpired(): Boolean
}

/**
 * Wire layer for Endurain's health-weight REST API (export only).
 *
 * Auth model (clean-room from Endurain's own server spec, NOT copied from the AGPL Gadgetbridge
 * client): OAuth2 username/password login → short-lived JWT access token + rotating refresh token.
 * Every request carries `X-Client-Type: mobile`. Non-auth requests carry
 * `Authorization: Bearer <access>`, injected by the interceptor from the [tokenStore] on each call
 * (so a token refreshed mid-batch is picked up automatically). Token refresh sends the refresh token
 * in its own Authorization header. On a 401 we refresh once and retry (reactive path).
 *
 * @param serverOrigin bare origin the user entered (e.g. https://endurain.example.com); the
 *  `/api/v1/` prefix is appended here.
 */
class EndurainSync(
    serverOrigin: String,
    private val tokenStore: TokenStore
) : SyncInterface() {

    // Locale.US: API wire format, must stay ASCII regardless of device locale. Endurain's `date`
    // column is a calendar date (YYYY-MM-DD), formatted in the device's local time zone.
    private val dateOnly = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }

    private val api: EndurainApi

    init {
        val baseUrl = serverOrigin.trim().trimEnd('/') + "/api/v1/"
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder().header("X-Client-Type", "mobile")
                // Auth endpoints authenticate themselves (login = none, refresh = refresh token in
                // its own header) — never overwrite them with the access token.
                val isAuthEndpoint = original.url.encodedPath.contains("/auth/")
                if (!isAuthEndpoint && original.header("Authorization") == null) {
                    tokenStore.getAccessToken()?.let { builder.header("Authorization", "Bearer $it") }
                }
                chain.proceed(builder.build())
            }
            .build()
        api = Retrofit.Builder()
            .client(client)
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(EndurainApi::class.java)
    }

    // --- Auth ---------------------------------------------------------------------------

    /** Outcome of a login / MFA-verify attempt. */
    sealed class LoginResult {
        /** Tokens obtained and persisted. */
        object Success : LoginResult()
        /** Server requires a second factor; call [verifyMfa] with the code for this username. */
        data class MfaRequired(val username: String) : LoginResult()
        data class Failure(val message: String) : LoginResult()
    }

    suspend fun login(username: String, password: String): LoginResult = try {
        handleTokenResponse(api.login(username, password), username)
    } catch (e: Exception) {
        LoginResult.Failure(e.message ?: "unknown error")
    }

    suspend fun verifyMfa(username: String, code: String): LoginResult = try {
        handleTokenResponse(api.verifyMfa(MfaRequest(username, code)), username)
    } catch (e: Exception) {
        LoginResult.Failure(e.message ?: "unknown error")
    }

    private fun handleTokenResponse(resp: Response<TokenResponse>, username: String): LoginResult {
        val body = resp.body()
        return when {
            !resp.isSuccessful -> LoginResult.Failure("HTTP ${resp.code()}: ${resp.errorBody()?.string()?.take(200)}")
            body == null -> LoginResult.Failure("empty response")
            body.mfa_required == true -> LoginResult.MfaRequired(username)
            body.access_token != null && body.refresh_token != null -> {
                tokenStore.saveTokens(
                    body.access_token, body.refresh_token,
                    body.expires_in ?: 0L, body.refresh_token_expires_in ?: 0L
                )
                LoginResult.Success
            }
            else -> LoginResult.Failure(body.detail ?: "no tokens returned")
        }
    }

    /** Exchange the refresh token for a fresh access/refresh pair. Returns true on success. */
    private suspend fun refreshAccessToken(): Boolean {
        val refresh = tokenStore.getRefreshToken() ?: return false
        if (tokenStore.isRefreshTokenExpired()) return false
        return try {
            val resp = api.refresh("Bearer $refresh", emptyMap())
            val body = resp.body()
            if (resp.isSuccessful && body?.access_token != null && body.refresh_token != null) {
                tokenStore.saveTokens(
                    body.access_token, body.refresh_token,
                    body.expires_in ?: 0L, body.refresh_token_expires_in ?: 0L
                )
                true
            } else false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Proactive refresh used by the service before a sync run: if the access token is expired (or
     * missing) and a valid refresh token exists, mint a new pair. Returns true if a usable access
     * token is available afterwards.
     */
    suspend fun ensureFreshAccessToken(): Boolean {
        if (!tokenStore.isAccessTokenExpired() && tokenStore.getAccessToken() != null) return true
        return refreshAccessToken()
    }

    /** Run an authenticated call; on 401, refresh once and retry. */
    private suspend fun <T> withAuthRetry(call: suspend () -> Response<T>): Response<T> {
        val resp = call()
        return if (resp.code() == 401 && refreshAccessToken()) call() else resp
    }

    // --- Connectivity / permission check ------------------------------------------------

    /** GET a single page to verify the server, token and health:read scope. */
    suspend fun testConnection(): SyncResult<Unit> = try {
        val resp = withAuthRetry { api.getWeight(1, 1) }
        when {
            resp.isSuccessful -> SyncResult.Success(Unit)
            resp.code() == 401 || resp.code() == 403 ->
                SyncResult.Failure(SyncResult.ErrorType.PERMISSION_DENIED, "HTTP ${resp.code()}")
            else -> SyncResult.Failure(SyncResult.ErrorType.API_ERROR, "HTTP ${resp.code()}: ${resp.errorBody()?.string()?.take(200)}")
        }
    } catch (e: Exception) {
        SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, null, e)
    }

    // --- Export -------------------------------------------------------------------------

    /** Insert/update are the same operation: POST is upsert-by-date on Endurain. */
    suspend fun upsert(measurement: OpenScaleMeasurement): SyncResult<Unit> = try {
        val resp = withAuthRetry { api.postWeight(buildWeightRequest(measurement, dateOnly)) }
        if (resp.isSuccessful) SyncResult.Success(Unit)
        else SyncResult.Failure(
            SyncResult.ErrorType.API_ERROR,
            "endurain ${dateOnly.format(measurement.date)} upsert error HTTP ${resp.code()}: ${resp.errorBody()?.string()?.take(200)}"
        )
    } catch (e: Exception) {
        SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, null, e)
    }

    suspend fun delete(date: Date): SyncResult<Unit> = try {
        val id = findIdByDate(dateOnly.format(date))
        if (id == null) {
            // Already absent → deletion is a no-op success (keeps reconcile idempotent).
            SyncResult.Success(Unit)
        } else {
            val resp = withAuthRetry { api.deleteWeight(id) }
            if (resp.isSuccessful) SyncResult.Success(Unit)
            else SyncResult.Failure(SyncResult.ErrorType.API_ERROR, "endurain delete error HTTP ${resp.code()}")
        }
    } catch (e: Exception) {
        SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, null, e)
    }

    /** Delete every weight record of the authenticated user (records are token-scoped server-side). */
    suspend fun clear(): SyncResult<Unit> {
        return try {
            // Always fetch the first page: each pass deletes it, so the next records shift up.
            // Bounded by CLEAR_MAX_PAGES as a backstop against a server that never drains.
            var page = 0
            while (page < CLEAR_MAX_PAGES) {
                page++
                val resp = withAuthRetry { api.getWeight(1, PAGE_SIZE) }
                if (!resp.isSuccessful) {
                    return SyncResult.Failure(SyncResult.ErrorType.API_ERROR, "endurain clear list error HTTP ${resp.code()}")
                }
                val records = resp.body()?.records.orEmpty()
                if (records.isEmpty()) return SyncResult.Success(Unit)
                for (r in records) {
                    val del = withAuthRetry { api.deleteWeight(r.id) }
                    if (!del.isSuccessful) {
                        return SyncResult.Failure(SyncResult.ErrorType.API_ERROR, "endurain clear delete error HTTP ${del.code()}")
                    }
                }
            }
            SyncResult.Failure(SyncResult.ErrorType.API_ERROR, "endurain clear exceeded page limit")
        } catch (e: Exception) {
            SyncResult.Failure(SyncResult.ErrorType.UNKNOWN_ERROR, null, e)
        }
    }

    /** Resolve a record id by its calendar date by paging the list (Endurain has no date query). */
    private suspend fun findIdByDate(dateStr: String): Int? {
        var page = 1
        while (page <= CLEAR_MAX_PAGES) {
            val resp = withAuthRetry { api.getWeight(page, PAGE_SIZE) }
            if (!resp.isSuccessful) return null
            val records = resp.body()?.records.orEmpty()
            records.firstOrNull { it.date == dateStr }?.let { return it.id }
            if (records.size < PAGE_SIZE) return null
            page++
        }
        return null
    }

    // --- Retrofit contract + DTOs -------------------------------------------------------

    interface EndurainApi {
        @FormUrlEncoded
        @POST("auth/login")
        suspend fun login(
            @Field("username") username: String,
            @Field("password") password: String
        ): Response<TokenResponse>

        @POST("auth/mfa/verify")
        suspend fun verifyMfa(@Body body: MfaRequest): Response<TokenResponse>

        @POST("auth/refresh")
        suspend fun refresh(
            @Header("Authorization") authorization: String,
            @Body body: Map<String, String>
        ): Response<TokenResponse>

        @POST("health/weight")
        suspend fun postWeight(@Body body: WeightRequest): Response<WeightRead>

        @GET("health/weight")
        suspend fun getWeight(
            @Query("page_number") pageNumber: Int?,
            @Query("num_records") numRecords: Int?
        ): Response<WeightListResponse>

        @DELETE("health/weight/{id}")
        suspend fun deleteWeight(@Path("id") id: Int): Response<Unit>
    }

    data class TokenResponse(
        @SerializedName("access_token") val access_token: String? = null,
        @SerializedName("refresh_token") val refresh_token: String? = null,
        @SerializedName("expires_in") val expires_in: Long? = null,
        @SerializedName("refresh_token_expires_in") val refresh_token_expires_in: Long? = null,
        @SerializedName("token_type") val token_type: String? = null,
        @SerializedName("mfa_required") val mfa_required: Boolean? = null,
        @SerializedName("detail") val detail: String? = null
    )

    data class MfaRequest(
        @SerializedName("username") val username: String,
        @SerializedName("mfa_code") val mfa_code: String
    )

    // Nullable fields are omitted by Gson (nulls are not serialized by default), so only present
    // metrics are sent — matching Endurain's forbid-extra / optional-field schema.
    data class WeightRequest(
        @SerializedName("date") val date: String,
        @SerializedName("weight") val weight: Float? = null,
        @SerializedName("body_fat") val bodyFat: Float? = null,
        @SerializedName("body_water") val bodyWater: Float? = null,
        @SerializedName("muscle_mass") val muscleMass: Float? = null,
        @SerializedName("bone_mass") val boneMass: Float? = null,
        @SerializedName("visceral_fat") val visceralFat: Float? = null
    )

    data class WeightRead(
        @SerializedName("id") val id: Int = 0,
        @SerializedName("date") val date: String? = null
    )

    data class WeightListResponse(
        @SerializedName("total") val total: Int? = null,
        @SerializedName("records") val records: List<WeightRead>? = null
    )

    companion object {
        private const val PAGE_SIZE = 100
        private const val CLEAR_MAX_PAGES = 1000

        /**
         * Maps an openScale measurement to Endurain's weight body. Reads the self-describing generic
         * [OpenScaleMeasurement.values] (unit-aware) rather than the sync-app convenience fields,
         * which are all normalised to %. Endurain wants weight/masses in kg and fat/water in %. Only
         * present values are set; null fields are omitted by Gson (Endurain's schema forbids unknown
         * keys but accepts missing optional ones). `bmi` is left for the server to auto-calculate;
         * `source`, `physique_rating`, `metabolic_age` are omitted (openScale has no source / the
         * server enum rejects a non-garmin source). Pure/static so it is unit-testable without a
         * network client.
         */
        fun buildWeightRequest(m: OpenScaleMeasurement, dateOnly: SimpleDateFormat): WeightRequest {
            val byKey = m.values.associateBy { it.key }

            // Fallback for a very old openScale that emits no generic values: use the convenience
            // fields (fat/water are %; muscle/bone are % → convert to kg via weight).
            if (byKey.isEmpty()) {
                val w = m.weight.takeIf { it > 0f }
                return WeightRequest(
                    date = dateOnly.format(m.date),
                    weight = w,
                    bodyFat = m.body_fat.takeIf { it > 0f },
                    bodyWater = m.water.takeIf { it > 0f },
                    muscleMass = if (w != null) m.muscle.takeIf { it > 0f }?.let { w * it / 100f } else null,
                    boneMass = if (w != null) m.bone.takeIf { it > 0f }?.let { w * it / 100f } else null,
                    visceralFat = null
                )
            }

            val weight = byKey["WEIGHT"]?.value?.takeIf { it > 0f }

            // A composition value as a percentage of body weight.
            fun asPercent(key: String): Float? {
                val v = byKey[key] ?: return null
                val value = v.value ?: return null
                return when {
                    v.unit == "%" -> value
                    weight != null -> value / weight * 100f
                    else -> null
                }
            }

            // A composition value as an absolute mass in kg.
            fun asKg(key: String): Float? {
                val v = byKey[key] ?: return null
                val value = v.value ?: return null
                return when {
                    v.unit == "kg" -> value
                    weight != null -> weight * value / 100f
                    else -> null
                }
            }

            return WeightRequest(
                date = dateOnly.format(m.date),
                weight = weight,
                bodyFat = asPercent("BODY_FAT"),
                bodyWater = asPercent("WATER"),
                muscleMass = asKg("MUSCLE"),
                boneMass = asKg("BONE"),
                visceralFat = byKey["VISCERAL_FAT"]?.value  // unitless rating, passed through
            )
        }
    }
}
