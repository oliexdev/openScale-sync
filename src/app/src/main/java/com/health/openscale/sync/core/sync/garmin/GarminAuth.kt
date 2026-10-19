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
package com.health.openscale.sync.core.sync.garmin

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal port of the garth (https://github.com/matin/garth) Garmin SSO login to Kotlin:
 *
 *   1. GET  sso/mobile/sso/en/sign-in            (session cookies)
 *   2. POST sso/mobile/api/login                 (JSON; SUCCESSFUL -> ticket, MFA_REQUIRED -> code)
 *   3. POST sso/mobile/api/mfa/verifyCode        (only when MFA is on)
 *   4. GET  connectapi/oauth-service/oauth/preauthorized   (OAuth1-signed, ticket -> OAuth1 token)
 *   5. POST connectapi/oauth-service/oauth/exchange/user/2.0 (OAuth1-signed -> OAuth2 bearer)
 *
 * The OAuth2 bearer expires after ~1 h and is silently re-exchanged from the OAuth1 token
 * (valid ~1 year). Only when the OAuth1 token is gone/expired is a full password login done
 * again, which may ask the user for an MFA code via [promptMfa].
 *
 * Tokens are persisted in SharedPreferences, so a weigh-in sync normally needs no password
 * and no MFA prompt at all.
 */
class GarminAuth(context: Context) {

    data class OAuth1Token(val token: String, val secret: String)
    data class OAuth2Token(val accessToken: String, val refreshToken: String, val expiresAt: Long)
    data class OAuthConsumer(val key: String, val secret: String)

    class LoginException(message: String) : Exception(message)
    class MfaCancelledException : Exception("MFA cancelled by user")

    private val prefs = context.getSharedPreferences("garmin_auth", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val random = SecureRandom()

    private val cookieJar = InMemoryCookieJar()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var consumer: OAuthConsumer? = null

    // --- persistence ---------------------------------------------------------

    private var oauth1: OAuth1Token?
        get() = prefs.getString(KEY_OAUTH1, null)?.let {
            runCatching { gson.fromJson(it, OAuth1Token::class.java) }.getOrNull()
        }
        set(value) = prefs.edit {
            if (value == null) remove(KEY_OAUTH1) else putString(KEY_OAUTH1, gson.toJson(value))
        }

    private var oauth2: OAuth2Token?
        get() = prefs.getString(KEY_OAUTH2, null)?.let {
            runCatching { gson.fromJson(it, OAuth2Token::class.java) }.getOrNull()
        }
        set(value) = prefs.edit {
            if (value == null) remove(KEY_OAUTH2) else putString(KEY_OAUTH2, gson.toJson(value))
        }

    /** Forget all tokens (e.g. credentials changed or login keeps failing). */
    fun logout() {
        oauth1 = null
        oauth2 = null
        cookieJar.clear()
    }

    /** Drop the cached OAuth2 token so the next [ensureAccessToken] re-exchanges it. */
    @Synchronized
    fun invalidateAccessToken() {
        oauth2 = null
    }

    /**
     * A bearer token that is valid right now, or null when a full password login is required
     * (no tokens yet, or the long-lived OAuth1 token expired / was revoked).
     */
    @Synchronized
    fun ensureAccessToken(): String? {
        oauth2?.let {
            if (System.currentTimeMillis() < it.expiresAt - 60_000) return it.accessToken
        }
        // OAuth2 expired -> silently re-exchange using the long-lived OAuth1 token.
        val o1 = oauth1 ?: return null
        return try {
            val fresh = exchange(o1, login = false)
            oauth2 = fresh
            Timber.d("Garmin: OAuth2 token refreshed silently")
            fresh.accessToken
        } catch (e: Exception) {
            Timber.w(e, "Garmin: OAuth1 exchange failed -> full login required")
            oauth1 = null   // don't hammer a dead token on every sync
            null
        }
    }

    // --- full login ------------------------------------------------------------

    /**
     * Full email/password login (with MFA via [promptMfa] when the account requires it).
     * On success both tokens are stored; [ensureAccessToken] answers without a login afterwards.
     */
    suspend fun login(email: String, password: String, promptMfa: suspend (method: String) -> String) {
        logout()
        val serviceUrl = "https://mobile.integration.$DOMAIN/gcm/android"
        val loginParams = mapOf(
            "clientId" to CLIENT_ID,
            "locale" to "en-US",
            "service" to serviceUrl
        )

        // 1. Session cookies
        ssoRequest(
            "GET", "https://sso.$DOMAIN/mobile/sso/en/sign-in",
            query = mapOf("clientId" to CLIENT_ID), body = null
        )

        // 2. Credentials
        val loginBody = gson.toJson(
            mapOf("username" to email, "password" to password,
                  "rememberMe" to false, "captchaToken" to "")
        ).toRequestBody(JSON)
        val loginResp = ssoRequest(
            "POST", "https://sso.$DOMAIN/mobile/api/login",
            query = loginParams, body = loginBody
        )
        val loginJson = JsonParser.parseString(loginResp).asJsonObject
        val status = loginJson.getAsJsonObject("responseStatus")?.get("type")?.asString
            ?: throw LoginException("Unexpected SSO response")

        val ticket = when (status) {
            "SUCCESSFUL" -> loginJson.get("serviceTicketId").asString
            "MFA_REQUIRED" -> {
                val method = loginJson.getAsJsonObject("customerMfaInfo")
                    ?.get("mfaLastMethodUsed")?.asString ?: "email"
                val code = promptMfa(method)
                val mfaBody = gson.toJson(
                    mapOf("mfaMethod" to method, "mfaVerificationCode" to code,
                          "rememberMyBrowser" to false, "reconsentList" to emptyList<String>(),
                          "mfaSetup" to false)
                ).toRequestBody(JSON)
                val mfaResp = ssoRequest(
                    "POST", "https://sso.$DOMAIN/mobile/api/mfa/verifyCode",
                    query = loginParams, body = mfaBody
                )
                val mfaJson = JsonParser.parseString(mfaResp).asJsonObject
                if (mfaJson.getAsJsonObject("responseStatus")?.get("type")?.asString != "SUCCESSFUL")
                    throw LoginException("MFA code rejected")
                mfaJson.get("serviceTicketId").asString
            }
            else -> {
                val msg = loginJson.getAsJsonObject("responseStatus")?.get("message")?.asString ?: status
                throw LoginException("Garmin login failed: $msg")
            }
        }

        // 3. ticket -> OAuth1 -> OAuth2
        val o1 = getOAuth1Token(ticket)
        oauth1 = o1
        oauth2 = exchange(o1, login = true)
        Timber.i("Garmin: login successful, tokens stored")
    }

    // --- wire calls --------------------------------------------------------------

    private fun ssoRequest(method: String, url: String, query: Map<String, String>,
                           body: okhttp3.RequestBody?): String {
        val urlBuilder = url.toHttpUrl().newBuilder()
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val headersBuilder = okhttp3.Headers.Builder()
        SSO_HEADERS.forEach { (k, v) -> headersBuilder.add(k, v) }
        val req = Request.Builder().url(urlBuilder.build())
            .headers(headersBuilder.build())
            .method(method, body)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body.string().orEmpty()
            if (!resp.isSuccessful) throw LoginException("SSO HTTP ${resp.code}")
            return text
        }
    }

    private fun fetchConsumer(): OAuthConsumer {
        consumer?.let { return it }
        val req = Request.Builder().url(OAUTH_CONSUMER_URL).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw LoginException("Cannot fetch OAuth consumer (${resp.code})")
            val json = JsonParser.parseString(resp.body.string()).asJsonObject
            val c = OAuthConsumer(json.get("consumer_key").asString, json.get("consumer_secret").asString)
            consumer = c
            return c
        }
    }

    private fun getOAuth1Token(ticket: String): OAuth1Token {
        val c = fetchConsumer()
        val base = "https://connectapi.$DOMAIN/oauth-service/oauth/preauthorized"
        val query = mapOf(
            "ticket" to ticket,
            "login-url" to "https://mobile.integration.$DOMAIN/gcm/android",
            "accepts-mfa-tokens" to "true"
        )
        val auth = oauth1Header("GET", base, query, c, null)
        val urlBuilder = base.toHttpUrl().newBuilder()
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val req = Request.Builder().url(urlBuilder.build())
            .header("Authorization", auth)
            .header("User-Agent", GCM_USER_AGENT)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body.string().orEmpty()
            if (!resp.isSuccessful) throw LoginException("OAuth1 request failed (${resp.code})")
            val params = text.split("&").mapNotNull {
                val i = it.indexOf('=')
                if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
            }.toMap()
            return OAuth1Token(
                params["oauth_token"] ?: throw LoginException("No oauth_token in response"),
                params["oauth_token_secret"] ?: throw LoginException("No oauth_token_secret in response")
            )
        }
    }

    private fun exchange(o1: OAuth1Token, login: Boolean): OAuth2Token {
        val c = fetchConsumer()
        val base = "https://connectapi.$DOMAIN/oauth-service/oauth/exchange/user/2.0"
        val form = mutableMapOf<String, String>()
        if (login) form["audience"] = "GARMIN_CONNECT_MOBILE_ANDROID_DI"
        val auth = oauth1Header("POST", base, form, c, o1)
        val bodyBuilder = FormBody.Builder()
        form.forEach { (k, v) -> bodyBuilder.add(k, v) }
        val req = Request.Builder().url(base)
            .header("Authorization", auth)
            .header("User-Agent", GCM_USER_AGENT)
            .post(bodyBuilder.build())
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body.string().orEmpty()
            if (!resp.isSuccessful) throw LoginException("OAuth2 exchange failed (${resp.code})")
            val json: JsonObject = JsonParser.parseString(text).asJsonObject
            val now = System.currentTimeMillis()
            return OAuth2Token(
                accessToken = json.get("access_token").asString,
                refreshToken = json.get("refresh_token")?.asString ?: "",
                expiresAt = now + json.get("expires_in").asLong * 1000L
            )
        }
    }

    // --- OAuth1 HMAC-SHA1 signing -------------------------------------------------

    private fun oauth1Header(
        method: String, baseUrl: String, params: Map<String, String>,
        consumer: OAuthConsumer, token: OAuth1Token?
    ): String {
        val nonce = ByteArray(16).also { random.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val timestamp = (System.currentTimeMillis() / 1000).toString()

        val oauthParams = mutableMapOf(
            "oauth_consumer_key" to consumer.key,
            "oauth_nonce" to nonce,
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to timestamp,
            "oauth_version" to "1.0"
        )
        if (token != null) oauthParams["oauth_token"] = token.token

        val allParams = (params + oauthParams).toList()
            .sortedBy { it.first }
            .joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }
        val baseString = "${method.uppercase()}&${enc(baseUrl)}&${enc(allParams)}"

        val signingKey = "${enc(consumer.secret)}&${enc(token?.secret ?: "")}"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(signingKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val signature = java.util.Base64.getEncoder()
            .encodeToString(mac.doFinal(baseString.toByteArray(Charsets.UTF_8)))

        oauthParams["oauth_signature"] = signature
        return oauthParams.toList().joinToString(", ", prefix = "OAuth ") {
            "${enc(it.first)}=\"${enc(it.second)}\""
        }
    }

    /** RFC 3986 percent-encoding (Java's URLEncoder is for HTML forms and gets this wrong). */
    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    // --- trivial in-memory cookie jar (the SSO session needs cookies for MFA) ------

    private class InMemoryCookieJar : CookieJar {
        private val store = HashMap<String, MutableList<Cookie>>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val list = store.getOrPut(url.host) { mutableListOf() }
            cookies.forEach { c ->
                list.removeAll { it.name == c.name }
                list.add(c)
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return store[url.host]?.filter { it.expiresAt > now } ?: emptyList()
        }

        @Synchronized
        fun clear() = store.clear()
    }

    companion object {
        private const val DOMAIN = "garmin.com"
        private const val CLIENT_ID = "GCM_ANDROID_DARK"
        private const val KEY_OAUTH1 = "oauth1"
        private const val KEY_OAUTH2 = "oauth2"
        private const val GCM_USER_AGENT = "com.garmin.android.apps.connectmobile"
        /** Same public Garmin Connect Mobile consumer credentials the garth library uses. */
        private const val OAUTH_CONSUMER_URL = "https://thegarth.s3.amazonaws.com/oauth_consumer.json"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        // Browser-like headers so the SSO endpoints don't answer with a bot challenge.
        private val SSO_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) " +
                    "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )
    }
}
