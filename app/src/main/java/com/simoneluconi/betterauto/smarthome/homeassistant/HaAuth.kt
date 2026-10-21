package com.simoneluconi.betterauto.smarthome.homeassistant

import com.simoneluconi.betterauto.smarthome.SmartHomeAuth
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** Access and refresh token; a long-lived token has no refresh token and never expires here. */
data class HaTokens(val access: String, val refresh: String?, val expiresAtMs: Long) {
    /** Worth refreshing before use: a minute of margin. */
    fun expiresSoon(nowMs: Long) = refresh != null && nowMs > expiresAtMs - REFRESH_MARGIN_MS

    companion object {
        const val REFRESH_MARGIN_MS = 60_000L
        fun longLived(token: String) = HaTokens(token.trim(), null, Long.MAX_VALUE)
    }
}

/** Where to reach Home Assistant and with what: stored encrypted, never logged. */
data class HaCredentials(val url: String, val tokens: HaTokens, val homeName: String? = null) {
    fun toJson(): String = JSONObject()
        .put("url", url).put("access", tokens.access).putOpt("refresh", tokens.refresh).put("expires", tokens.expiresAtMs)
        .putOpt("home", homeName)
        .toString()

    companion object {
        fun fromJson(text: String?): HaCredentials? = text?.let {
            runCatching {
                val j = JSONObject(it)
                HaCredentials(
                    j.getString("url"),
                    HaTokens(j.getString("access"), j.optString("refresh").takeIf { r -> r.isNotEmpty() && !j.isNull("refresh") }, j.getLong("expires")),
                    j.optString("home").takeIf { h -> h.isNotEmpty() && !j.isNull("home") },
                )
            }.getOrNull()
        }
    }
}

/** Home Assistant answered the token request with a refusal (code used, refresh token revoked...). */
class HaAuthRefused(message: String) : IOException(message)

/**
 * Home Assistant's own login (https://developers.home-assistant.io/docs/auth_api): the browser opens
 * /auth/authorize, Home Assistant sends the driver back to [REDIRECT_URI] with a code, exchanged at
 * /auth/token for an access token (30 minutes) and a refresh token. The client id is the project's page
 * for this service ([SmartHomeAuth]), which declares [REDIRECT_URI] with a `<link rel="redirect_uri">`:
 * Home Assistant reads it to allow our app scheme.
 */
object HaAuth {
    val CLIENT_ID = SmartHomeAuth.clientId(HaMapping.PROVIDER_ID)
    val REDIRECT_URI = SmartHomeAuth.redirectUri(HaMapping.PROVIDER_ID)

    /**
     * What the driver typed, as a base URL ("https://host[:port]"), or null if it is not one. Without a
     * scheme: http for the usual local port 8123, https otherwise (a domain, Nabu Casa).
     */
    fun normalizeUrl(input: String): String? {
        val text = input.trim().trimEnd('/')
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val withScheme = when {
            text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) -> text
            Regex(":8123(/|$)").containsMatchIn(text) -> "http://$text"
            else -> "https://$text"
        }
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.host.isEmpty()) return null
        val port = if (url.port == defaultPort(url.scheme)) "" else ":${url.port}"
        return "${url.scheme}://${url.host.let { if (':' in it) "[$it]" else it }}$port"
    }

    private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

    /** A clipboard text that is a web address (http or https, one line): a candidate to check with [isHomeAssistant]. */
    fun clipboardUrl(text: String?): String? {
        val t = text?.trim() ?: return null
        if (t.length > 300 || t.contains('\n') || !(t.startsWith("http://", true) || t.startsWith("https://", true))) return null
        return normalizeUrl(t)
    }

    /**
     * Whether [baseUrl] answers like Home Assistant, without logging in: its web app manifest names it
     * (blocking, a few seconds at most).
     */
    fun isHomeAssistant(client: OkHttpClient, baseUrl: String): Boolean = runCatching {
        client.newCall(Request.Builder().url("$baseUrl/manifest.json").build()).execute().use { r ->
            r.isSuccessful && isManifest(r.body?.string().orEmpty().take(MANIFEST_MAX_CHARS))
        }
    }.getOrDefault(false)

    /** Home Assistant's manifest.json: {"name": "Home Assistant", ...}. */
    fun isManifest(text: String): Boolean = runCatching {
        JSONObject(text).optString("name").contains("Home Assistant", ignoreCase = true)
    }.getOrDefault(false)

    private const val MANIFEST_MAX_CHARS = 20_000

    /**
     * Only reachable at home: .local names, private and link-local addresses, bare host names. VPN
     * ranges (Tailscale's 100.64/10) are not: they work from the car too.
     */
    fun isLocal(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa") || host == "localhost" || '.' !in host && ':' !in host) return true
        val v4 = host.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 && host.count { c -> c == '.' } == 3 }
        if (v4 != null) {
            val (a, b) = v4[0] to v4[1]
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)
        }
        return host.startsWith("fe80:") || host.startsWith("fd") || host.startsWith("fc") || host == "::1"
    }

    fun authorizeUrl(baseUrl: String, state: String): String =
        "$baseUrl/auth/authorize".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("state", state)
            .build().toString()

    fun webSocketUrl(baseUrl: String): String =
        baseUrl.replaceFirst(Regex("^http", RegexOption.IGNORE_CASE), "ws") + "/api/websocket"

    /** The code from the browser for tokens (blocking: call off the main thread). */
    fun exchange(client: OkHttpClient, baseUrl: String, code: String, nowMs: Long = System.currentTimeMillis()): HaTokens =
        post(client, baseUrl, FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("client_id", CLIENT_ID).build(), nowMs, null)

    /** A new access token; the refresh token stays the same. */
    fun refresh(client: OkHttpClient, baseUrl: String, refreshToken: String, nowMs: Long = System.currentTimeMillis()): HaTokens =
        post(client, baseUrl, FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken).add("client_id", CLIENT_ID).build(), nowMs, refreshToken)

    /** Ends the login on the server too (best effort, on unlinking). */
    fun revoke(client: OkHttpClient, baseUrl: String, refreshToken: String) {
        runCatching {
            client.newCall(Request.Builder().url("$baseUrl/auth/revoke").post(FormBody.Builder().add("token", refreshToken).build()).build()).execute().close()
        }
    }

    private fun post(client: OkHttpClient, baseUrl: String, body: FormBody, nowMs: Long, previousRefresh: String?): HaTokens {
        val request = Request.Builder().url("$baseUrl/auth/token").post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            // 400 with "invalid_grant" (or 403): the server said no, retrying will not help
            if (response.code == 400 || response.code == 401 || response.code == 403) {
                throw HaAuthRefused(runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty().ifEmpty { "HTTP ${response.code}" })
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return parseTokens(text, nowMs, previousRefresh)
        }
    }

    /** A /auth/token answer: {"access_token", "expires_in", "refresh_token" (first time only), "token_type"}. */
    fun parseTokens(json: String, nowMs: Long, previousRefresh: String?): HaTokens {
        val j = runCatching { JSONObject(json) }.getOrElse { throw IOException("not JSON") }
        val access = j.optString("access_token").takeIf { it.isNotEmpty() } ?: throw IOException("no access token")
        val refresh = j.optString("refresh_token").takeIf { it.isNotEmpty() && !j.isNull("refresh_token") } ?: previousRefresh
        val expiresIn = j.optLong("expires_in", DEFAULT_EXPIRES_S)
        return HaTokens(access, refresh, nowMs + expiresIn * 1000)
    }

    private const val DEFAULT_EXPIRES_S = 1800L
}
